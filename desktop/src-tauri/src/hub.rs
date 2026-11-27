//! Receiver session logic (docs/PROTOCOL.md §5–6, ARCHITECTURE.md §4.1).
//!
//! Pure and synchronous: every method takes `now`, nothing touches the
//! network. The async server in `signaling.rs` feeds it and carries out the
//! returned [`Action`]s, which keeps every rule unit-testable.

use std::collections::{HashMap, VecDeque};
use std::net::IpAddr;
use std::time::{Duration, Instant};

use serde::Serialize;

use crate::pairing::{self, TokenManager, TokenRejection};
use crate::protocol::constants::{
    MAX_AUTH_FAILURES_PER_IP_PER_MINUTE, MAX_RESUME_REJECTIONS, RESUME_WINDOW_MS,
    SUPPORTED_PROTOCOL_MAX, SUPPORTED_PROTOCOL_MIN,
};
use crate::protocol::messages::{
    Capabilities, DeviceInfo, DisplayInfo, ErrorCode, Message, ReceiverInfo, VersionRange,
};

pub type ConnId = u64;

/// What the UI is told. Serialized to the webviews as `{"kind": ..., ...}`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum ServerEvent {
    #[serde(rename_all = "camelCase")]
    Pairing {
        token: String,
        expires_in_ms: u64,
    },
    #[serde(rename_all = "camelCase")]
    SenderConnected {
        device: DeviceInfo,
        display: DisplayInfo,
        capabilities: Capabilities,
        address: String,
    },
    Signal {
        message: Message,
    },
    Suspended,
    Resumed,
    Ended {
        reason: String,
    },
    #[serde(rename_all = "camelCase")]
    AuthRejected {
        code: String,
        address: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    Send {
        conn: ConnId,
        message: Message,
    },
    /// Send then close the connection (fatal error or bye).
    SendAndClose {
        conn: ConnId,
        message: Message,
    },
    Close {
        conn: ConnId,
    },
    Emit(ServerEvent),
}

#[derive(Debug, Clone)]
pub struct HubConfig {
    pub receiver_name: String,
    pub app_version: String,
    pub resume_window: Duration,
}

impl HubConfig {
    pub fn new(receiver_name: impl Into<String>) -> Self {
        Self {
            receiver_name: receiver_name.into(),
            app_version: env!("CARGO_PKG_VERSION").to_owned(),
            resume_window: Duration::from_millis(RESUME_WINDOW_MS),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    Negotiate,
    Active,
}

struct Session {
    id: String,
    resume_hash: [u8; 32],
    conn: Option<ConnId>,
    suspended_at: Option<Instant>,
    rejections: u32,
    phase: Phase,
    awaiting_answer: bool,
}

pub struct Hub {
    config: HubConfig,
    tokens: TokenManager,
    session: Option<Session>,
    failures: HashMap<IpAddr, VecDeque<Instant>>,
    announced_token: Option<String>,
}

impl Hub {
    pub fn new(config: HubConfig, tokens: TokenManager) -> Self {
        Self {
            config,
            tokens,
            session: None,
            failures: HashMap::new(),
            announced_token: None,
        }
    }

    pub fn has_session(&self) -> bool {
        self.session.is_some()
    }

    pub fn session_conn(&self) -> Option<ConnId> {
        self.session.as_ref().and_then(|s| s.conn)
    }

    pub fn current_token(&self, now: Instant) -> Option<(String, Instant)> {
        self.tokens.current(now).map(|(t, e)| (t.to_owned(), e))
    }

    // ── authentication ─────────────────────────────────────────────

    pub fn is_rate_limited(&mut self, ip: IpAddr, now: Instant) -> bool {
        let window = Duration::from_secs(60);
        let q = self.failures.entry(ip).or_default();
        while q.front().is_some_and(|t| now.duration_since(*t) > window) {
            q.pop_front();
        }
        q.len() >= MAX_AUTH_FAILURES_PER_IP_PER_MINUTE as usize
    }

    fn record_failure(&mut self, ip: IpAddr, now: Instant) {
        self.failures.entry(ip).or_default().push_back(now);
    }

    fn reject(&mut self, conn: ConnId, ip: IpAddr, code: ErrorCode, now: Instant) -> Vec<Action> {
        if !matches!(
            code,
            ErrorCode::SessionBusy | ErrorCode::ProtocolUnsupported
        ) {
            self.record_failure(ip, now);
        }
        vec![
            Action::SendAndClose {
                conn,
                message: code.to_message(true),
            },
            Action::Emit(ServerEvent::AuthRejected {
                code: code.as_str().to_owned(),
                address: ip.to_string(),
            }),
        ]
    }

    /// First message of a connection: `hello` or `resume`. Anything else is unexpected.
    pub fn on_first_message(
        &mut self,
        conn: ConnId,
        ip: IpAddr,
        msg: Message,
        now: Instant,
    ) -> Vec<Action> {
        if self.is_rate_limited(ip, now) {
            return vec![Action::SendAndClose {
                conn,
                message: ErrorCode::RateLimited.to_message(true),
            }];
        }
        match msg {
            Message::Hello {
                protocol,
                token,
                device,
                display,
                capabilities,
                ..
            } => {
                if !(SUPPORTED_PROTOCOL_MIN..=SUPPORTED_PROTOCOL_MAX).contains(&protocol) {
                    let mut actions = self.reject(conn, ip, ErrorCode::ProtocolUnsupported, now);
                    if let Action::SendAndClose {
                        message: Message::Error { supported, .. },
                        ..
                    } = &mut actions[0]
                    {
                        *supported = Some(VersionRange {
                            min: SUPPORTED_PROTOCOL_MIN,
                            max: SUPPORTED_PROTOCOL_MAX,
                        });
                    }
                    return actions;
                }
                if self.session.is_some() {
                    return self.reject(conn, ip, ErrorCode::SessionBusy, now);
                }
                match self.tokens.redeem(&token, now) {
                    Err(TokenRejection::Invalid) => {
                        return self.reject(conn, ip, ErrorCode::TokenInvalid, now)
                    }
                    Err(TokenRejection::Expired) => {
                        return self.reject(conn, ip, ErrorCode::TokenExpired, now)
                    }
                    Ok(()) => {}
                }
                let id = pairing::new_session_id();
                let resume = pairing::new_token();
                self.session = Some(Session {
                    id: id.clone(),
                    resume_hash: pairing::hash(&resume),
                    conn: Some(conn),
                    suspended_at: None,
                    rejections: 0,
                    phase: Phase::Negotiate,
                    awaiting_answer: false,
                });
                self.tokens.revoke(now);
                self.announced_token = None;
                vec![
                    Action::Send {
                        conn,
                        message: Message::Welcome {
                            protocol: SUPPORTED_PROTOCOL_MAX,
                            session_id: id,
                            resume_token: resume,
                            resume_window_ms: self.config.resume_window.as_millis() as u64,
                            receiver: ReceiverInfo {
                                name: self.config.receiver_name.clone(),
                                os: "windows".to_owned(),
                                app_version: self.config.app_version.clone(),
                            },
                            features: vec![],
                        },
                    },
                    Action::Emit(ServerEvent::SenderConnected {
                        device,
                        display,
                        capabilities,
                        address: ip.to_string(),
                    }),
                ]
            }
            Message::Resume {
                protocol,
                session_id,
                resume_token,
            } => {
                if !(SUPPORTED_PROTOCOL_MIN..=SUPPORTED_PROTOCOL_MAX).contains(&protocol) {
                    return self.reject(conn, ip, ErrorCode::ProtocolUnsupported, now);
                }
                let window = self.config.resume_window;
                let accepted = self.session.as_ref().is_some_and(|s| {
                    s.id == session_id
                        && pairing::matches(&resume_token, &s.resume_hash)
                        && s.suspended_at
                            .is_none_or(|t| now.duration_since(t) < window)
                });
                if !accepted {
                    let mut actions = self.reject(conn, ip, ErrorCode::ResumeRejected, now);
                    let ended = self.session.as_mut().is_some_and(|s| {
                        s.rejections += 1;
                        s.rejections >= MAX_RESUME_REJECTIONS
                    });
                    if ended {
                        actions.extend(self.end("resume-rejected", None));
                    }
                    return actions;
                }
                let s = self.session.as_mut().expect("checked above");
                let mut actions = Vec::new();
                if let Some(old) = s.conn.replace(conn) {
                    // Half-open old socket: the new connection takes over.
                    actions.push(Action::Close { conn: old });
                }
                let fresh = pairing::new_token();
                s.resume_hash = pairing::hash(&fresh);
                s.suspended_at = None;
                s.rejections = 0;
                s.awaiting_answer = false;
                actions.push(Action::Send {
                    conn,
                    message: Message::Resumed {
                        session_id: s.id.clone(),
                        resume_token: fresh,
                        resume_window_ms: window.as_millis() as u64,
                    },
                });
                actions.push(Action::Emit(ServerEvent::Resumed));
                actions
            }
            other => vec![Action::SendAndClose {
                conn,
                message: Message::Error {
                    code: ErrorCode::UnexpectedMessage.as_str().to_owned(),
                    message: format!("expected hello or resume, got {}", other.type_name()),
                    fatal: true,
                    supported: None,
                },
            }],
        }
    }

    // ── authenticated traffic ──────────────────────────────────────

    /// A message from the sender after authentication.
    pub fn on_sender_message(&mut self, conn: ConnId, msg: Message) -> Vec<Action> {
        let Some(s) = self.session.as_mut().filter(|s| s.conn == Some(conn)) else {
            return vec![Action::Close { conn }];
        };
        let unexpected = |what: &str| {
            vec![Action::SendAndClose {
                conn,
                message: Message::Error {
                    code: ErrorCode::UnexpectedMessage.as_str().to_owned(),
                    message: format!("{what} not allowed now"),
                    fatal: true,
                    supported: None,
                },
            }]
        };
        match &msg {
            Message::Offer { sdp, .. } => {
                if s.awaiting_answer {
                    return unexpected("second offer before answer");
                }
                if let Err(code) = check_offer_sdp(sdp) {
                    return vec![Action::SendAndClose {
                        conn,
                        message: code.to_message(true),
                    }];
                }
                s.awaiting_answer = true;
                vec![Action::Emit(ServerEvent::Signal { message: msg })]
            }
            Message::Ice { .. } => vec![Action::Emit(ServerEvent::Signal { message: msg })],
            Message::StreamState { state, .. } => {
                if state == "streaming" || state == "ready" {
                    s.phase = Phase::Active;
                }
                vec![Action::Emit(ServerEvent::Signal { message: msg })]
            }
            Message::Bye { .. } => self.end("sender-closed", None),
            other => unexpected(other.type_name()),
        }
    }

    /// A message the viewer wants to send to the phone (answer / ice / bye).
    pub fn from_viewer(&mut self, msg: Message) -> Result<Vec<Action>, String> {
        msg.validate().map_err(|e| e.to_string())?;
        let Some(s) = self.session.as_mut() else {
            return Err("no session".into());
        };
        match &msg {
            Message::Answer { .. } => {
                if !s.awaiting_answer {
                    return Err("no offer to answer".into());
                }
                s.awaiting_answer = false;
            }
            Message::Ice { .. } => {}
            Message::Bye { .. } => return Ok(self.end("receiver-closed", Some(msg))),
            other => return Err(format!("viewer may not send {}", other.type_name())),
        }
        Ok(match s.conn {
            Some(conn) => vec![Action::Send { conn, message: msg }],
            None => vec![], // suspended: the phone ICE-restarts after resuming anyway
        })
    }

    /// The sender's connection closed without `bye`.
    pub fn on_disconnect(&mut self, conn: ConnId, now: Instant) -> Vec<Action> {
        match self.session.as_mut() {
            Some(s) if s.conn == Some(conn) => {
                s.conn = None;
                s.suspended_at = Some(now);
                s.awaiting_answer = false;
                vec![Action::Emit(ServerEvent::Suspended)]
            }
            _ => vec![],
        }
    }

    /// Ends the session (viewer closed, user action). Sends `bye` if connected.
    pub fn end_session(&mut self, reason: &str) -> Vec<Action> {
        self.end(
            reason,
            Some(Message::Bye {
                reason: "receiver-closed".into(),
            }),
        )
    }

    fn end(&mut self, reason: &str, bye: Option<Message>) -> Vec<Action> {
        let Some(s) = self.session.take() else {
            return vec![];
        };
        let mut actions = Vec::new();
        if let Some(conn) = s.conn {
            match bye {
                Some(message) => actions.push(Action::SendAndClose { conn, message }),
                None => actions.push(Action::Close { conn }),
            }
        }
        actions.push(Action::Emit(ServerEvent::Ended {
            reason: reason.to_owned(),
        }));
        actions
    }

    /// Periodic housekeeping: resume-window expiry and QR token rotation.
    pub fn tick(&mut self, now: Instant) -> Vec<Action> {
        let mut actions = Vec::new();
        let window = self.config.resume_window;
        if self.session.as_ref().is_some_and(|s| {
            s.suspended_at
                .is_some_and(|t| now.duration_since(t) >= window)
        }) {
            actions.extend(self.end("timeout", None));
        }
        if self.session.is_none() {
            let token = match self.tokens.current(now) {
                Some((t, _)) => t.to_owned(),
                None => self.tokens.issue(now),
            };
            if self.announced_token.as_deref() != Some(token.as_str()) {
                let (_, expires) = self.tokens.current(now).expect("just issued");
                self.announced_token = Some(token.clone());
                actions.push(Action::Emit(ServerEvent::Pairing {
                    token,
                    expires_in_ms: expires.saturating_duration_since(now).as_millis() as u64,
                }));
            }
        }
        actions
    }

    /// Re-announce the current pairing state (e.g. a webview reloaded).
    pub fn reannounce(&mut self) {
        self.announced_token = None;
    }
}

/// PROTOCOL.md §8: exactly one m-section, `video`, with at least one codec we can decode.
pub fn check_offer_sdp(sdp: &str) -> Result<(), ErrorCode> {
    let lines: Vec<&str> = sdp.lines().map(str::trim_end).collect();
    let m: Vec<&&str> = lines.iter().filter(|l| l.starts_with("m=")).collect();
    if m.len() != 1 || !m[0].starts_with("m=video ") {
        return Err(ErrorCode::MalformedMessage);
    }
    let decodable = lines.iter().any(|l| {
        l.starts_with("a=rtpmap:") && {
            let codec = l
                .split_whitespace()
                .nth(1)
                .unwrap_or("")
                .split('/')
                .next()
                .unwrap_or("");
            ["H264", "VP8", "VP9", "AV1"]
                .iter()
                .any(|c| c.eq_ignore_ascii_case(codec))
        }
    });
    if decodable {
        Ok(())
    } else {
        Err(ErrorCode::CodecUnsupported)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SDP: &str = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\na=rtpmap:102 H264/90000\r\n";

    fn hub() -> Hub {
        Hub::new(
            HubConfig::new("Study PC"),
            TokenManager::new(Duration::from_secs(300), Duration::from_secs(600)),
        )
    }

    fn ip() -> IpAddr {
        "192.168.1.30".parse().unwrap()
    }

    fn hello(token: &str, protocol: u32) -> Message {
        Message::decode(&format!(
            r#"{{"type":"hello","protocol":{protocol},"token":"{token}",
                "device":{{"manufacturer":"Google","model":"Pixel 8","androidVersion":"15","sdkInt":35,"appVersion":"0.1.0"}},
                "display":{{"width":1080,"height":2400,"densityDpi":420,"rotation":0}},
                "capabilities":{{"codecs":["H264"],"maxFps":60}}}}"#
        ))
        .unwrap()
    }

    fn token_of(actions: &[Action]) -> String {
        actions
            .iter()
            .find_map(|a| match a {
                Action::Emit(ServerEvent::Pairing { token, .. }) => Some(token.clone()),
                _ => None,
            })
            .expect("pairing event")
    }

    fn error_code(a: &Action) -> Option<String> {
        match a {
            Action::SendAndClose {
                message: Message::Error { code, .. },
                ..
            } => Some(code.clone()),
            _ => None,
        }
    }

    /// Pairs and returns (hub, now, session_id, resume_token).
    fn paired() -> (Hub, Instant, String, String) {
        let now = Instant::now();
        let mut h = hub();
        let token = token_of(&h.tick(now));
        let actions = h.on_first_message(1, ip(), hello(&token, 1), now);
        let Action::Send {
            message:
                Message::Welcome {
                    session_id,
                    resume_token,
                    ..
                },
            ..
        } = &actions[0]
        else {
            panic!("expected welcome, got {actions:?}")
        };
        (h, now, session_id.clone(), resume_token.clone())
    }

    #[test]
    fn tick_announces_a_token_once_and_rotates_on_expiry() {
        let now = Instant::now();
        let mut h = hub();
        let t1 = token_of(&h.tick(now));
        assert!(h.tick(now + Duration::from_secs(10)).is_empty());
        let t2 = token_of(&h.tick(now + Duration::from_secs(301)));
        assert_ne!(t1, t2);
    }

    #[test]
    fn hello_with_valid_token_creates_a_session_and_hides_the_qr() {
        let (mut h, now, sid, resume) = paired();
        assert_eq!(sid.len(), 22);
        assert_eq!(resume.len(), 43);
        assert!(h.has_session());
        assert!(h.current_token(now).is_none());
        assert!(h.tick(now).is_empty(), "no QR while a session exists");
    }

    #[test]
    fn hello_rejections() {
        let now = Instant::now();
        let mut h = hub();
        let token = token_of(&h.tick(now));
        let a = h.on_first_message(1, ip(), hello(&pairing::new_token(), 1), now);
        assert_eq!(error_code(&a[0]).as_deref(), Some("TOKEN_INVALID"));
        let a = h.on_first_message(2, ip(), hello(&token, 2), now);
        assert_eq!(error_code(&a[0]).as_deref(), Some("PROTOCOL_UNSUPPORTED"));
        let Action::SendAndClose {
            message: Message::Error { supported, .. },
            ..
        } = &a[0]
        else {
            panic!()
        };
        assert_eq!(supported, &Some(VersionRange { min: 1, max: 1 }));
        // The real token still works after a wrong guess.
        let a = h.on_first_message(3, ip(), hello(&token, 1), now);
        assert!(matches!(
            a[0],
            Action::Send {
                message: Message::Welcome { .. },
                ..
            }
        ));
        // A second phone is turned away.
        let a = h.on_first_message(4, "192.168.1.31".parse().unwrap(), hello(&token, 1), now);
        assert_eq!(error_code(&a[0]).as_deref(), Some("SESSION_BUSY"));
    }

    #[test]
    fn expired_token_is_reported_as_expired() {
        let now = Instant::now();
        let mut h = hub();
        let old = token_of(&h.tick(now));
        let later = now + Duration::from_secs(301);
        h.tick(later);
        let a = h.on_first_message(1, ip(), hello(&old, 1), later);
        assert_eq!(error_code(&a[0]).as_deref(), Some("TOKEN_EXPIRED"));
    }

    #[test]
    fn rate_limits_after_five_failures_per_minute() {
        let now = Instant::now();
        let mut h = hub();
        h.tick(now);
        for i in 0..5 {
            h.on_first_message(i, ip(), hello(&pairing::new_token(), 1), now);
        }
        let a = h.on_first_message(9, ip(), hello(&pairing::new_token(), 1), now);
        assert_eq!(error_code(&a[0]).as_deref(), Some("RATE_LIMITED"));
        let other: IpAddr = "192.168.1.99".parse().unwrap();
        let a = h.on_first_message(10, other, hello(&pairing::new_token(), 1), now);
        assert_eq!(error_code(&a[0]).as_deref(), Some("TOKEN_INVALID"));
        let a = h.on_first_message(
            11,
            ip(),
            hello(&pairing::new_token(), 1),
            now + Duration::from_secs(61),
        );
        assert_eq!(error_code(&a[0]).as_deref(), Some("TOKEN_INVALID"));
    }

    #[test]
    fn first_message_must_be_hello_or_resume() {
        let now = Instant::now();
        let mut h = hub();
        let a = h.on_first_message(
            1,
            ip(),
            Message::Offer {
                sdp: SDP.into(),
                ice_restart: false,
            },
            now,
        );
        assert_eq!(error_code(&a[0]).as_deref(), Some("UNEXPECTED_MESSAGE"));
    }

    #[test]
    fn offer_answer_and_ice_flow_through_and_are_ordered() {
        let (mut h, _, _, _) = paired();
        let a = h.on_sender_message(
            1,
            Message::Offer {
                sdp: SDP.into(),
                ice_restart: false,
            },
        );
        assert!(matches!(
            &a[0],
            Action::Emit(ServerEvent::Signal {
                message: Message::Offer { .. }
            })
        ));
        let a = h.on_sender_message(
            1,
            Message::Offer {
                sdp: SDP.into(),
                ice_restart: false,
            },
        );
        assert_eq!(
            error_code(&a[0]).as_deref(),
            Some("UNEXPECTED_MESSAGE"),
            "no second offer before answer"
        );

        let (mut h, _, _, _) = paired();
        h.on_sender_message(
            1,
            Message::Offer {
                sdp: SDP.into(),
                ice_restart: false,
            },
        );
        let a = h.from_viewer(Message::Answer { sdp: SDP.into() }).unwrap();
        assert!(matches!(
            &a[0],
            Action::Send {
                conn: 1,
                message: Message::Answer { .. }
            }
        ));
        assert!(
            h.from_viewer(Message::Answer { sdp: SDP.into() }).is_err(),
            "only one answer per offer"
        );
        assert!(h.from_viewer(Message::Ice { candidate: None }).is_ok());
        assert!(h
            .from_viewer(Message::StreamState {
                state: "ready".into(),
                video: None,
                reason: None
            })
            .is_err());
    }

    #[test]
    fn view_only_sender_cannot_send_receiver_messages() {
        let (mut h, _, _, _) = paired();
        let a = h.on_sender_message(1, Message::Answer { sdp: SDP.into() });
        assert_eq!(error_code(&a[0]).as_deref(), Some("UNEXPECTED_MESSAGE"));
    }

    #[test]
    fn offers_must_be_single_video_with_a_known_codec() {
        assert_eq!(check_offer_sdp(SDP), Ok(()));
        assert_eq!(
            check_offer_sdp("v=0\r\nm=video 9 RTP 96\r\na=rtpmap:96 MJPEG/90000\r\n"),
            Err(ErrorCode::CodecUnsupported)
        );
        assert_eq!(
            check_offer_sdp("v=0\r\nm=video 9 RTP 102\r\na=rtpmap:102 H264/90000\r\nm=application 9 DTLS/SCTP 5000\r\n"),
            Err(ErrorCode::MalformedMessage),
            "data channels are rejected: view-only"
        );
    }

    #[test]
    fn disconnect_suspends_and_resume_rotates_the_token() {
        let (mut h, now, sid, resume) = paired();
        assert_eq!(
            h.on_disconnect(1, now),
            vec![Action::Emit(ServerEvent::Suspended)]
        );
        let later = now + Duration::from_secs(5);
        let a = h.on_first_message(
            2,
            ip(),
            Message::Resume {
                protocol: 1,
                session_id: sid.clone(),
                resume_token: resume.clone(),
            },
            later,
        );
        let Action::Send {
            conn: 2,
            message:
                Message::Resumed {
                    resume_token: fresh,
                    ..
                },
        } = &a[0]
        else {
            panic!("{a:?}")
        };
        assert_ne!(fresh, &resume);
        assert_eq!(h.session_conn(), Some(2));
        // The old token is now useless.
        h.on_disconnect(2, later);
        let a = h.on_first_message(
            3,
            ip(),
            Message::Resume {
                protocol: 1,
                session_id: sid,
                resume_token: resume,
            },
            later,
        );
        assert_eq!(error_code(&a[0]).as_deref(), Some("RESUME_REJECTED"));
    }

    #[test]
    fn resume_window_expiry_ends_the_session_and_shows_a_new_qr() {
        let (mut h, now, sid, resume) = paired();
        h.on_disconnect(1, now);
        let late = now + Duration::from_secs(61);
        let a = h.tick(late);
        assert!(a.contains(&Action::Emit(ServerEvent::Ended {
            reason: "timeout".into()
        })));
        assert!(a
            .iter()
            .any(|x| matches!(x, Action::Emit(ServerEvent::Pairing { .. }))));
        let a = h.on_first_message(
            2,
            ip(),
            Message::Resume {
                protocol: 1,
                session_id: sid,
                resume_token: resume,
            },
            late,
        );
        assert_eq!(error_code(&a[0]).as_deref(), Some("RESUME_REJECTED"));
    }

    #[test]
    fn three_bad_resumes_end_a_suspended_session() {
        let (mut h, now, sid, _) = paired();
        h.on_disconnect(1, now);
        for i in 0..3 {
            let other: IpAddr = format!("192.168.1.{}", 100 + i).parse().unwrap();
            h.on_first_message(
                10 + i,
                other,
                Message::Resume {
                    protocol: 1,
                    session_id: sid.clone(),
                    resume_token: pairing::new_token(),
                },
                now,
            );
        }
        assert!(!h.has_session());
    }

    #[test]
    fn bye_from_sender_ends_without_reply_and_viewer_close_sends_bye() {
        let (mut h, _, _, _) = paired();
        let a = h.on_sender_message(
            1,
            Message::Bye {
                reason: "user".into(),
            },
        );
        assert_eq!(
            a,
            vec![
                Action::Close { conn: 1 },
                Action::Emit(ServerEvent::Ended {
                    reason: "sender-closed".into()
                })
            ]
        );

        let (mut h, _, _, _) = paired();
        let a = h.end_session("receiver-closed");
        assert_eq!(
            a[0],
            Action::SendAndClose {
                conn: 1,
                message: Message::Bye {
                    reason: "receiver-closed".into()
                }
            }
        );
    }

    #[test]
    fn messages_from_a_stale_connection_are_dropped() {
        let (mut h, _, _, _) = paired();
        assert_eq!(
            h.on_sender_message(42, Message::Ice { candidate: None }),
            vec![Action::Close { conn: 42 }]
        );
    }
}
