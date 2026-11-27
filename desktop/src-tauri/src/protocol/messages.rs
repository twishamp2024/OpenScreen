//! Signaling messages (docs/PROTOCOL.md §4) with the same strict validation
//! as the shared JSON schema. Unknown fields are ignored; unknown types fail.

use serde::{Deserialize, Serialize};

use super::constants::{MAX_CANDIDATE_CHARS, MAX_MESSAGE_BYTES, MAX_NAME_LENGTH, MAX_SDP_CHARS};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceInfo {
    pub manufacturer: String,
    pub model: String,
    pub android_version: String,
    pub sdk_int: u32,
    pub app_version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DisplayInfo {
    pub width: u32,
    pub height: u32,
    pub density_dpi: u32,
    pub rotation: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    pub codecs: Vec<String>,
    pub max_fps: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiverInfo {
    pub name: String,
    pub os: String,
    pub app_version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoInfo {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub codec: String,
    pub rotation: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IceCandidate {
    pub candidate: String,
    // `deserialize_with` makes these required-but-nullable (PROTOCOL.md §4.3).
    #[serde(deserialize_with = "Option::deserialize")]
    pub sdp_mid: Option<String>,
    #[serde(deserialize_with = "Option::deserialize")]
    pub sdp_m_line_index: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct VersionRange {
    pub min: u32,
    pub max: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
pub enum Message {
    // sender → receiver
    #[serde(rename_all = "camelCase")]
    Hello {
        protocol: u32,
        token: String,
        device: DeviceInfo,
        display: DisplayInfo,
        capabilities: Capabilities,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        features: Vec<String>,
    },
    #[serde(rename_all = "camelCase")]
    Resume {
        protocol: u32,
        session_id: String,
        resume_token: String,
    },
    #[serde(rename_all = "camelCase")]
    Offer {
        sdp: String,
        #[serde(default)]
        ice_restart: bool,
    },
    StreamState {
        state: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        video: Option<VideoInfo>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        reason: Option<String>,
    },
    // receiver → sender
    #[serde(rename_all = "camelCase")]
    Welcome {
        protocol: u32,
        session_id: String,
        resume_token: String,
        resume_window_ms: u64,
        receiver: ReceiverInfo,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        features: Vec<String>,
    },
    #[serde(rename_all = "camelCase")]
    Resumed {
        session_id: String,
        resume_token: String,
        resume_window_ms: u64,
    },
    Answer {
        sdp: String,
    },
    // both
    Ice {
        #[serde(deserialize_with = "Option::deserialize")]
        candidate: Option<IceCandidate>,
    },
    Bye {
        reason: String,
    },
    Error {
        code: String,
        message: String,
        fatal: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        supported: Option<VersionRange>,
    },
}

/// Why a frame was rejected; maps onto protocol error codes.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DecodeError {
    #[error("message too large")]
    TooLarge,
    #[error("malformed: {0}")]
    Malformed(String),
    #[error("invalid {0}")]
    Invalid(&'static str),
}

impl DecodeError {
    pub fn code(&self) -> ErrorCode {
        match self {
            DecodeError::TooLarge => ErrorCode::MessageTooLarge,
            DecodeError::Malformed(m) if m.contains("unknown variant") => {
                ErrorCode::UnexpectedMessage
            }
            _ => ErrorCode::MalformedMessage,
        }
    }
}

/// Protocol error codes (constants.json `errorCodes`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    ProtocolUnsupported,
    TokenInvalid,
    TokenExpired,
    SessionBusy,
    ResumeRejected,
    MalformedMessage,
    UnexpectedMessage,
    MessageTooLarge,
    RateLimited,
    HelloTimeout,
    CodecUnsupported,
    InternalError,
}

impl ErrorCode {
    pub const ALL: [ErrorCode; 12] = [
        Self::ProtocolUnsupported,
        Self::TokenInvalid,
        Self::TokenExpired,
        Self::SessionBusy,
        Self::ResumeRejected,
        Self::MalformedMessage,
        Self::UnexpectedMessage,
        Self::MessageTooLarge,
        Self::RateLimited,
        Self::HelloTimeout,
        Self::CodecUnsupported,
        Self::InternalError,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::ProtocolUnsupported => "PROTOCOL_UNSUPPORTED",
            Self::TokenInvalid => "TOKEN_INVALID",
            Self::TokenExpired => "TOKEN_EXPIRED",
            Self::SessionBusy => "SESSION_BUSY",
            Self::ResumeRejected => "RESUME_REJECTED",
            Self::MalformedMessage => "MALFORMED_MESSAGE",
            Self::UnexpectedMessage => "UNEXPECTED_MESSAGE",
            Self::MessageTooLarge => "MESSAGE_TOO_LARGE",
            Self::RateLimited => "RATE_LIMITED",
            Self::HelloTimeout => "HELLO_TIMEOUT",
            Self::CodecUnsupported => "CODEC_UNSUPPORTED",
            Self::InternalError => "INTERNAL_ERROR",
        }
    }

    /// English log text; apps show their own localized text per code.
    pub fn default_message(self) -> &'static str {
        match self {
            Self::ProtocolUnsupported => "Protocol version not supported by this receiver.",
            Self::TokenInvalid => "Pairing code is not valid.",
            Self::TokenExpired => "Pairing code expired.",
            Self::SessionBusy => "Another phone is already connected.",
            Self::ResumeRejected => "Session cannot be resumed.",
            Self::MalformedMessage => "Malformed message.",
            Self::UnexpectedMessage => "Unexpected message.",
            Self::MessageTooLarge => "Message too large.",
            Self::RateLimited => "Too many attempts.",
            Self::HelloTimeout => "No hello received in time.",
            Self::CodecUnsupported => "No supported video codec offered.",
            Self::InternalError => "Internal error.",
        }
    }

    pub fn to_message(self, fatal: bool) -> Message {
        Message::Error {
            code: self.as_str().to_owned(),
            message: self.default_message().to_owned(),
            fatal,
            supported: None,
        }
    }
}

impl Message {
    pub fn type_name(&self) -> &'static str {
        match self {
            Message::Hello { .. } => "hello",
            Message::Resume { .. } => "resume",
            Message::Offer { .. } => "offer",
            Message::StreamState { .. } => "stream-state",
            Message::Welcome { .. } => "welcome",
            Message::Resumed { .. } => "resumed",
            Message::Answer { .. } => "answer",
            Message::Ice { .. } => "ice",
            Message::Bye { .. } => "bye",
            Message::Error { .. } => "error",
        }
    }

    pub fn decode(text: &str) -> Result<Message, DecodeError> {
        if text.len() > MAX_MESSAGE_BYTES {
            return Err(DecodeError::TooLarge);
        }
        let m: Message =
            serde_json::from_str(text).map_err(|e| DecodeError::Malformed(e.to_string()))?;
        m.validate()?;
        Ok(m)
    }

    /// Serializes a message after validating it; invalid messages are never sent.
    pub fn encode(&self) -> Result<String, DecodeError> {
        self.validate()?;
        serde_json::to_string(self).map_err(|e| DecodeError::Malformed(e.to_string()))
    }

    pub fn validate(&self) -> Result<(), DecodeError> {
        use DecodeError::Invalid;
        let ok = |cond: bool, field: &'static str| if cond { Ok(()) } else { Err(Invalid(field)) };
        match self {
            Message::Hello {
                protocol,
                token,
                device,
                display,
                capabilities,
                features,
            } => {
                ok(*protocol >= 1, "protocol")?;
                ok(is_token(token), "token")?;
                ok(len_in(&device.manufacturer, 1, 64), "device.manufacturer")?;
                ok(len_in(&device.model, 1, 64), "device.model")?;
                ok(
                    len_in(&device.android_version, 1, 16),
                    "device.androidVersion",
                )?;
                ok((29..=100).contains(&device.sdk_int), "device.sdkInt")?;
                ok(len_in(&device.app_version, 1, 32), "device.appVersion")?;
                ok(
                    is_pixels(display.width) && is_pixels(display.height),
                    "display size",
                )?;
                ok(
                    (1..=2000).contains(&display.density_dpi),
                    "display.densityDpi",
                )?;
                ok(is_rotation(display.rotation), "display.rotation")?;
                let c = &capabilities.codecs;
                ok(
                    (1..=8).contains(&c.len()) && c.iter().all(|x| is_codec(x)) && unique(c),
                    "capabilities.codecs",
                )?;
                ok(
                    (1..=240).contains(&capabilities.max_fps),
                    "capabilities.maxFps",
                )?;
                validate_features(features)
            }
            Message::Resume {
                protocol,
                session_id,
                resume_token,
            } => {
                ok(*protocol >= 1, "protocol")?;
                ok(is_session_id(session_id), "sessionId")?;
                ok(is_token(resume_token), "resumeToken")
            }
            Message::Offer { sdp, .. } | Message::Answer { sdp } => ok(is_sdp(sdp), "sdp"),
            Message::StreamState {
                state,
                video,
                reason,
            } => {
                ok(
                    ["ready", "streaming", "paused", "stopped"].contains(&state.as_str()),
                    "state",
                )?;
                ok(
                    state != "streaming" || video.is_some(),
                    "video required when streaming",
                )?;
                if let Some(v) = video {
                    ok(is_pixels(v.width) && is_pixels(v.height), "video size")?;
                    ok((1..=240).contains(&v.fps), "video.fps")?;
                    ok(is_codec(&v.codec), "video.codec")?;
                    ok(is_rotation(v.rotation), "video.rotation")?;
                }
                ok(
                    reason.as_deref().is_none_or(|r| {
                        [
                            "user",
                            "projection-revoked",
                            "encoder-error",
                            "app-backgrounded",
                        ]
                        .contains(&r)
                    }),
                    "reason",
                )
            }
            Message::Welcome {
                protocol,
                session_id,
                resume_token,
                resume_window_ms,
                receiver,
                features,
            } => {
                ok(*protocol >= 1, "protocol")?;
                ok(is_session_id(session_id), "sessionId")?;
                ok(is_token(resume_token), "resumeToken")?;
                ok(*resume_window_ms <= 300_000, "resumeWindowMs")?;
                ok(is_display_name(&receiver.name), "receiver.name")?;
                ok(receiver.os == "windows", "receiver.os")?;
                ok(len_in(&receiver.app_version, 1, 32), "receiver.appVersion")?;
                validate_features(features)
            }
            Message::Resumed {
                session_id,
                resume_token,
                resume_window_ms,
            } => {
                ok(is_session_id(session_id), "sessionId")?;
                ok(is_token(resume_token), "resumeToken")?;
                ok(*resume_window_ms <= 300_000, "resumeWindowMs")
            }
            Message::Ice { candidate } => match candidate {
                None => Ok(()),
                Some(c) => {
                    ok(
                        c.candidate.chars().count() <= MAX_CANDIDATE_CHARS,
                        "candidate",
                    )?;
                    ok(
                        c.sdp_mid.as_ref().is_none_or(|m| m.chars().count() <= 32),
                        "sdpMid",
                    )?;
                    ok(c.sdp_m_line_index.is_none_or(|i| i <= 15), "sdpMLineIndex")?;
                    ok(
                        c.sdp_mid.is_some() || c.sdp_m_line_index.is_some(),
                        "sdpMid or sdpMLineIndex",
                    )
                }
            },
            Message::Bye { reason } => ok(
                [
                    "user",
                    "receiver-closed",
                    "sender-closed",
                    "timeout",
                    "error",
                ]
                .contains(&reason.as_str()),
                "reason",
            ),
            Message::Error {
                code,
                message,
                supported,
                ..
            } => {
                ok(ErrorCode::ALL.iter().any(|c| c.as_str() == code), "code")?;
                ok(message.chars().count() <= 256, "message")?;
                ok(
                    supported.as_ref().is_none_or(|s| s.min >= 1 && s.max >= 1),
                    "supported",
                )
            }
        }
    }
}

fn len_in(s: &str, min: usize, max: usize) -> bool {
    (min..=max).contains(&s.chars().count())
}

fn is_b64url(s: &str, len: usize) -> bool {
    s.len() == len
        && s.bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
}

fn is_token(s: &str) -> bool {
    is_b64url(s, 43)
}

fn is_session_id(s: &str) -> bool {
    is_b64url(s, 22)
}

fn is_pixels(v: u32) -> bool {
    (1..=16_384).contains(&v)
}

fn is_rotation(v: u32) -> bool {
    matches!(v, 0 | 90 | 180 | 270)
}

fn is_codec(s: &str) -> bool {
    matches!(s, "H264" | "VP8" | "VP9" | "AV1")
}

fn is_sdp(s: &str) -> bool {
    len_in(s, 3, MAX_SDP_CHARS) && s.starts_with("v=0")
}

pub fn is_display_name(s: &str) -> bool {
    len_in(s, 1, MAX_NAME_LENGTH) && !s.chars().any(|c| c <= '\u{1F}' || c == '\u{7F}')
}

fn unique(v: &[String]) -> bool {
    let mut seen = std::collections::HashSet::new();
    v.iter().all(|x| seen.insert(x))
}

fn validate_features(f: &[String]) -> Result<(), DecodeError> {
    let valid = f.len() <= 16
        && unique(f)
        && f.iter().all(|x| {
            len_in(x, 1, 32)
                && x.bytes()
                    .next()
                    .is_some_and(|c| c.is_ascii_lowercase() || c.is_ascii_digit())
                && x.bytes()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'.' || c == b'-')
        });
    if valid {
        Ok(())
    } else {
        Err(DecodeError::Invalid("features"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::shared_fixtures;

    fn fixture_files(kind: &str) -> Vec<std::path::PathBuf> {
        let mut files: Vec<_> =
            std::fs::read_dir(shared_fixtures::dir().join("fixtures").join(kind))
                .unwrap()
                .map(|e| e.unwrap().path())
                .filter(|p| p.extension().is_some_and(|x| x == "json"))
                .collect();
        files.sort();
        assert!(!files.is_empty());
        files
    }

    #[test]
    fn accepts_every_valid_fixture() {
        for path in fixture_files("valid") {
            let text = std::fs::read_to_string(&path).unwrap();
            Message::decode(&text).unwrap_or_else(|e| panic!("{} rejected: {e}", path.display()));
        }
    }

    #[test]
    fn rejects_every_invalid_fixture() {
        for path in fixture_files("invalid") {
            let text = std::fs::read_to_string(&path).unwrap();
            if let Ok(m) = Message::decode(&text) {
                panic!("{} accepted as {m:?}", path.display());
            }
        }
    }

    #[test]
    fn valid_fixtures_round_trip() {
        for path in fixture_files("valid") {
            let m = Message::decode(&std::fs::read_to_string(&path).unwrap()).unwrap();
            assert_eq!(
                Message::decode(&m.encode().unwrap()).unwrap(),
                m,
                "{}",
                path.display()
            );
        }
    }

    #[test]
    fn error_codes_match_constants() {
        let c = shared_fixtures::json("constants.json");
        let shared: Vec<&str> = c["errorCodes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_str().unwrap())
            .collect();
        let ours: Vec<&str> = ErrorCode::ALL.iter().map(|c| c.as_str()).collect();
        assert_eq!(shared, ours);
        for code in ErrorCode::ALL {
            assert_eq!(serde_json::to_value(code).unwrap(), code.as_str());
        }
    }

    #[test]
    fn unknown_type_maps_to_unexpected_message() {
        let e = Message::decode(r#"{"type":"touch","x":1}"#).unwrap_err();
        assert_eq!(e.code(), ErrorCode::UnexpectedMessage);
        let e = Message::decode("not json").unwrap_err();
        assert_eq!(e.code(), ErrorCode::MalformedMessage);
        let big = format!(
            r#"{{"type":"bye","reason":"user","pad":"{}"}}"#,
            "x".repeat(70_000)
        );
        assert_eq!(
            Message::decode(&big).unwrap_err().code(),
            ErrorCode::MessageTooLarge
        );
    }

    #[test]
    fn encode_omits_absent_optionals_and_keeps_required_nulls() {
        let s = Message::StreamState {
            state: "ready".into(),
            video: None,
            reason: None,
        };
        assert_eq!(
            s.encode().unwrap(),
            r#"{"type":"stream-state","state":"ready"}"#
        );
        let ice = Message::Ice { candidate: None };
        assert_eq!(ice.encode().unwrap(), r#"{"type":"ice","candidate":null}"#);
        let c = Message::Ice {
            candidate: Some(IceCandidate {
                candidate: "".into(),
                sdp_mid: Some("0".into()),
                sdp_m_line_index: None,
            }),
        };
        assert_eq!(
            c.encode().unwrap(),
            r#"{"type":"ice","candidate":{"candidate":"","sdpMid":"0","sdpMLineIndex":null}}"#
        );
    }
}
