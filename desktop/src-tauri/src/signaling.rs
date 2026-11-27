//! Local signaling server: `wss://<lan-ip>:<port>/v1/signal` (docs/PROTOCOL.md §3).
//!
//! Thin async shell around [`Hub`]: accepts TLS 1.3 + WebSocket connections
//! from private addresses only, enforces transport limits, and carries out
//! the hub's [`Action`]s.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, watch};
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tokio_tungstenite::tungstenite::http::{HeaderValue, StatusCode};
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::{CloseFrame, WebSocketConfig};
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::hub::{Action, ConnId, Hub, ServerEvent};
use crate::net::is_allowed_peer;
use crate::protocol::constants::{
    DEFAULT_PORT, FALLBACK_PORTS, HELLO_TIMEOUT_MS, MAX_MESSAGES_PER_SECOND, MAX_MESSAGE_BYTES,
    PEER_DEAD_AFTER_MS, PING_INTERVAL_MS, WEBSOCKET_PATH, WEBSOCKET_SUBPROTOCOL,
};
use crate::protocol::messages::{ErrorCode, Message};
use crate::tls::TlsIdentity;

pub type EventSink = Arc<dyn Fn(ServerEvent) + Send + Sync>;

#[derive(Debug, Clone)]
pub struct ServerConfig {
    pub bind: Vec<Ipv4Addr>,
    pub ports: Vec<u16>,
    /// Tests only: accept connections from 127.0.0.1.
    pub allow_loopback: bool,
    pub hello_timeout: Duration,
    pub tick_interval: Duration,
}

impl ServerConfig {
    pub fn new(bind: Vec<Ipv4Addr>) -> Self {
        let mut ports = vec![DEFAULT_PORT];
        ports.extend(FALLBACK_PORTS);
        ports.push(0); // last resort: OS-assigned
        Self {
            bind,
            ports,
            allow_loopback: false,
            hello_timeout: Duration::from_millis(HELLO_TIMEOUT_MS),
            tick_interval: Duration::from_secs(1),
        }
    }
}

enum Out {
    Text(String),
    Close,
}

struct Inner {
    hub: Mutex<Hub>,
    conns: Mutex<HashMap<ConnId, mpsc::UnboundedSender<Out>>>,
    next_conn: AtomicU64,
    sink: EventSink,
    acceptor: TlsAcceptor,
    allow_loopback: bool,
    hello_timeout: Duration,
}

pub struct SignalingServer {
    inner: Arc<Inner>,
    pub port: u16,
    pub hosts: Vec<Ipv4Addr>,
    pub fingerprint: String,
    shutdown: watch::Sender<bool>,
}

#[derive(Debug, thiserror::Error)]
pub enum StartError {
    #[error("no local network address to listen on")]
    NoAddress,
    #[error("could not open a signaling port: {0}")]
    Bind(std::io::Error),
}

impl SignalingServer {
    pub async fn start(
        config: ServerConfig,
        hub: Hub,
        tls: TlsIdentity,
        sink: EventSink,
    ) -> Result<Self, StartError> {
        if config.bind.is_empty() {
            return Err(StartError::NoAddress);
        }
        let (listeners, port) = bind_all(&config.bind, &config.ports).await?;
        let hosts: Vec<Ipv4Addr> = listeners
            .iter()
            .filter_map(|l| match l.local_addr().ok()?.ip() {
                IpAddr::V4(v4) => Some(v4),
                IpAddr::V6(_) => None,
            })
            .collect();
        let inner = Arc::new(Inner {
            hub: Mutex::new(hub),
            conns: Mutex::new(HashMap::new()),
            next_conn: AtomicU64::new(1),
            sink,
            acceptor: TlsAcceptor::from(tls.config.clone()),
            allow_loopback: config.allow_loopback,
            hello_timeout: config.hello_timeout,
        });
        let (shutdown, rx) = watch::channel(false);

        for listener in listeners {
            let inner = inner.clone();
            let mut rx = rx.clone();
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        _ = rx.changed() => break,
                        accepted = listener.accept() => match accepted {
                            Ok((tcp, peer)) => { tokio::spawn(handle_connection(inner.clone(), tcp, peer)); }
                            Err(e) => { log::warn!("accept failed: {e}"); tokio::time::sleep(Duration::from_millis(100)).await; }
                        },
                    }
                }
            });
        }

        let ticker = inner.clone();
        let mut rx_tick = rx.clone();
        let interval = config.tick_interval;
        tokio::spawn(async move {
            let mut every = tokio::time::interval(interval);
            loop {
                tokio::select! {
                    _ = rx_tick.changed() => break,
                    _ = every.tick() => {
                        let actions = ticker.hub.lock().unwrap().tick(Instant::now());
                        apply(&ticker, actions);
                    }
                }
            }
        });

        Ok(Self {
            inner,
            port,
            hosts,
            fingerprint: tls.fingerprint,
            shutdown,
        })
    }

    /// Answer / ICE / bye from the viewer webview.
    pub fn send_from_viewer(&self, message: Message) -> Result<(), String> {
        let actions = self.inner.hub.lock().unwrap().from_viewer(message)?;
        apply(&self.inner, actions);
        Ok(())
    }

    pub fn end_session(&self, reason: &str) {
        let actions = self.inner.hub.lock().unwrap().end_session(reason);
        apply(&self.inner, actions);
    }

    /// Current pairing token and its expiry, if no session exists.
    pub fn current_token(&self) -> Option<(String, Instant)> {
        self.inner.hub.lock().unwrap().current_token(Instant::now())
    }

    pub fn has_session(&self) -> bool {
        self.inner.hub.lock().unwrap().has_session()
    }

    pub fn reannounce(&self) {
        self.inner.hub.lock().unwrap().reannounce();
    }

    pub fn shutdown(&self) {
        self.end_session("receiver-closed");
        let _ = self.shutdown.send(true);
    }
}

async fn bind_all(ips: &[Ipv4Addr], ports: &[u16]) -> Result<(Vec<TcpListener>, u16), StartError> {
    let mut last_err = None;
    for &port in ports {
        let mut listeners = Vec::new();
        let mut chosen = port;
        for &ip in ips {
            match TcpListener::bind(SocketAddr::from((ip, chosen))).await {
                Ok(l) => {
                    if chosen == 0 {
                        chosen = l.local_addr().map(|a| a.port()).unwrap_or(0);
                    }
                    listeners.push(l);
                }
                Err(e) if port == 0 && !listeners.is_empty() => log::warn!("skipping {ip}: {e}"),
                Err(e) => {
                    last_err = Some(e);
                    listeners.clear();
                    break;
                }
            }
        }
        if !listeners.is_empty() {
            return Ok((listeners, chosen));
        }
    }
    Err(StartError::Bind(
        last_err.unwrap_or_else(|| std::io::Error::other("no ports")),
    ))
}

fn apply(inner: &Inner, actions: Vec<Action>) {
    for action in actions {
        match action {
            Action::Send { conn, message } => send(inner, conn, &message, false),
            Action::SendAndClose { conn, message } => send(inner, conn, &message, true),
            Action::Close { conn } => {
                if let Some(tx) = inner.conns.lock().unwrap().get(&conn) {
                    let _ = tx.send(Out::Close);
                }
            }
            Action::Emit(event) => (inner.sink)(event),
        }
    }
}

fn send(inner: &Inner, conn: ConnId, message: &Message, close: bool) {
    let text = match message.encode() {
        Ok(t) => t,
        Err(e) => {
            log::error!("refusing to send invalid {}: {e}", message.type_name());
            return;
        }
    };
    if let Some(tx) = inner.conns.lock().unwrap().get(&conn) {
        let _ = tx.send(Out::Text(text));
        if close {
            let _ = tx.send(Out::Close);
        }
    }
}

fn reject_response(status: StatusCode, body: &str) -> ErrorResponse {
    let mut r = ErrorResponse::new(Some(body.to_owned()));
    *r.status_mut() = status;
    r
}

#[allow(clippy::result_large_err)] // signature fixed by tungstenite's Callback
fn check_upgrade(req: &Request, mut resp: Response) -> Result<Response, ErrorResponse> {
    if req.uri().path() != WEBSOCKET_PATH {
        return Err(reject_response(StatusCode::NOT_FOUND, "not found"));
    }
    let offered = req
        .headers()
        .get_all("Sec-WebSocket-Protocol")
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .any(|p| p.trim() == WEBSOCKET_SUBPROTOCOL);
    if !offered {
        return Err(reject_response(
            StatusCode::BAD_REQUEST,
            "subprotocol openscreen.v1 required",
        ));
    }
    resp.headers_mut().insert(
        "Sec-WebSocket-Protocol",
        HeaderValue::from_static(WEBSOCKET_SUBPROTOCOL),
    );
    Ok(resp)
}

async fn handle_connection(inner: Arc<Inner>, tcp: TcpStream, peer: SocketAddr) {
    // Never talk to the public Internet, even if a port were forwarded.
    if !is_allowed_peer(peer.ip(), inner.allow_loopback) {
        log::warn!("dropping connection from non-private {peer}");
        return;
    }
    let _ = tcp.set_nodelay(true);
    let deadline = tokio::time::Instant::now() + inner.hello_timeout;

    let Ok(Ok(tls)) = tokio::time::timeout_at(deadline, inner.acceptor.accept(tcp)).await else {
        return;
    };
    let ws_config = WebSocketConfig::default()
        .max_message_size(Some(MAX_MESSAGE_BYTES))
        .max_frame_size(Some(MAX_MESSAGE_BYTES));
    let handshake =
        tokio_tungstenite::accept_hdr_async_with_config(tls, check_upgrade, Some(ws_config));
    let Ok(Ok(ws)) = tokio::time::timeout_at(deadline, handshake).await else {
        return;
    };

    let conn = inner.next_conn.fetch_add(1, Ordering::Relaxed);
    let (tx, mut rx) = mpsc::unbounded_channel::<Out>();
    inner.conns.lock().unwrap().insert(conn, tx.clone());
    let (mut ws_tx, mut ws_rx) = ws.split();

    // Writer: outbound messages, periodic pings, clean close.
    let writer = tokio::spawn(async move {
        let mut ping = tokio::time::interval(Duration::from_millis(PING_INTERVAL_MS));
        ping.tick().await;
        loop {
            tokio::select! {
                out = rx.recv() => match out {
                    Some(Out::Text(t)) => { if ws_tx.send(WsMessage::text(t)).await.is_err() { break; } }
                    Some(Out::Close) | None => {
                        let _ = ws_tx.send(WsMessage::Close(Some(CloseFrame { code: CloseCode::Normal, reason: "".into() }))).await;
                        break;
                    }
                },
                _ = ping.tick() => { if ws_tx.send(WsMessage::Ping(Vec::new().into())).await.is_err() { break; } }
            }
        }
        let _ = ws_tx.close().await;
    });

    let fatal = |code: ErrorCode| {
        let _ = tx.send(Out::Text(code.to_message(true).encode().expect("valid")));
        let _ = tx.send(Out::Close);
    };

    let mut authenticated = false;
    let mut window_start = Instant::now();
    let mut window_count = 0u32;
    let dead_after = Duration::from_millis(PEER_DEAD_AFTER_MS);
    loop {
        let wait = if authenticated {
            tokio::time::Instant::now() + dead_after
        } else {
            deadline
        };
        let frame = match tokio::time::timeout_at(wait, ws_rx.next()).await {
            Err(_) => {
                if !authenticated {
                    fatal(ErrorCode::HelloTimeout);
                }
                break;
            }
            Ok(None) | Ok(Some(Err(_))) => break,
            Ok(Some(Ok(f))) => f,
        };
        let text = match frame {
            WsMessage::Text(t) => t,
            WsMessage::Binary(_) => {
                fatal(ErrorCode::MalformedMessage);
                break;
            }
            WsMessage::Close(_) => break,
            _ => continue, // ping/pong: liveness only
        };

        let now = Instant::now();
        if now.duration_since(window_start) >= Duration::from_secs(1) {
            window_start = now;
            window_count = 0;
        }
        window_count += 1;
        if window_count > MAX_MESSAGES_PER_SECOND {
            fatal(ErrorCode::RateLimited);
            break;
        }

        let message = match Message::decode(text.as_str()) {
            Ok(m) => m,
            Err(e) => {
                fatal(e.code());
                break;
            }
        };
        let actions = {
            let mut hub = inner.hub.lock().unwrap();
            if authenticated {
                hub.on_sender_message(conn, message)
            } else {
                let actions = hub.on_first_message(conn, peer.ip(), message, now);
                authenticated = actions.iter().any(|a| {
                    matches!(
                        a,
                        Action::Send {
                            message: Message::Welcome { .. } | Message::Resumed { .. },
                            ..
                        }
                    )
                });
                actions
            }
        };
        let closing = actions.iter().any(|a| matches!(a, Action::SendAndClose { conn: c, .. } | Action::Close { conn: c } if *c == conn));
        apply(&inner, actions);
        if closing {
            break;
        }
    }

    // Give the writer a moment to flush a final error/bye, then clean up.
    drop(tx);
    let _ = tokio::time::timeout(Duration::from_secs(2), writer).await;
    inner.conns.lock().unwrap().remove(&conn);
    if authenticated {
        let actions = inner
            .hub
            .lock()
            .unwrap()
            .on_disconnect(conn, Instant::now());
        apply(&inner, actions);
    }
}

#[cfg(test)]
mod tests {
    //! Real TLS + WebSocket round trips against the server on 127.0.0.1.

    use super::*;
    use crate::hub::HubConfig;
    use crate::pairing::TokenManager;
    use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
    use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
    use rustls::{DigitallySignedStruct, SignatureScheme};
    use tokio::sync::mpsc::UnboundedReceiver;
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;

    /// Pins the receiver certificate exactly like the Android app does.
    #[derive(Debug)]
    struct Pinned(String);

    impl ServerCertVerifier for Pinned {
        fn verify_server_cert(
            &self,
            end_entity: &CertificateDer<'_>,
            _: &[CertificateDer<'_>],
            _: &ServerName<'_>,
            _: &[u8],
            _: UnixTime,
        ) -> Result<ServerCertVerified, rustls::Error> {
            if crate::tls::fingerprint_of(end_entity) == self.0 {
                Ok(ServerCertVerified::assertion())
            } else {
                Err(rustls::Error::General("pin mismatch".into()))
            }
        }
        fn verify_tls12_signature(
            &self,
            _: &[u8],
            _: &CertificateDer<'_>,
            _: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            Err(rustls::Error::General("TLS 1.2 not allowed".into()))
        }
        fn verify_tls13_signature(
            &self,
            m: &[u8],
            c: &CertificateDer<'_>,
            d: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, rustls::Error> {
            rustls::crypto::verify_tls13_signature(
                m,
                c,
                d,
                &crate::tls::provider().signature_verification_algorithms,
            )
        }
        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            crate::tls::provider()
                .signature_verification_algorithms
                .supported_schemes()
        }
    }

    type Client = tokio_tungstenite::WebSocketStream<tokio_rustls::client::TlsStream<TcpStream>>;

    struct TestServer {
        server: SignalingServer,
        events: UnboundedReceiver<ServerEvent>,
    }

    impl TestServer {
        async fn start() -> Self {
            let (tx, events) = mpsc::unbounded_channel();
            let sink: EventSink = Arc::new(move |e| {
                let _ = tx.send(e);
            });
            let mut config = ServerConfig::new(vec![Ipv4Addr::LOCALHOST]);
            config.ports = vec![0];
            config.allow_loopback = true;
            config.hello_timeout = Duration::from_millis(500);
            config.tick_interval = Duration::from_millis(20);
            let hub = Hub::new(HubConfig::new("Test PC"), TokenManager::default());
            let tls = crate::tls::generate(&[Ipv4Addr::LOCALHOST]).unwrap();
            let server = SignalingServer::start(config, hub, tls, sink)
                .await
                .unwrap();
            Self { server, events }
        }

        async fn next_event(&mut self) -> ServerEvent {
            tokio::time::timeout(Duration::from_secs(5), self.events.recv())
                .await
                .expect("event")
                .expect("open")
        }

        async fn token(&mut self) -> String {
            loop {
                if let ServerEvent::Pairing { token, .. } = self.next_event().await {
                    return token;
                }
            }
        }

        async fn connect_with(
            &self,
            path: &str,
            protocol: Option<&str>,
            pin: &str,
        ) -> Result<Client, String> {
            let config = rustls::ClientConfig::builder_with_provider(crate::tls::provider())
                .with_protocol_versions(&[&rustls::version::TLS13])
                .unwrap()
                .dangerous()
                .with_custom_certificate_verifier(Arc::new(Pinned(pin.to_owned())))
                .with_no_client_auth();
            let tcp = TcpStream::connect(("127.0.0.1", self.server.port))
                .await
                .map_err(|e| e.to_string())?;
            let tls = tokio_rustls::TlsConnector::from(Arc::new(config))
                .connect(ServerName::try_from("openscreen.local").unwrap(), tcp)
                .await
                .map_err(|e| format!("tls: {e}"))?;
            let mut request = format!("wss://127.0.0.1:{}{path}", self.server.port)
                .into_client_request()
                .unwrap();
            if let Some(p) = protocol {
                request
                    .headers_mut()
                    .insert("Sec-WebSocket-Protocol", HeaderValue::from_str(p).unwrap());
            }
            let (ws, resp) = tokio_tungstenite::client_async(request, tls)
                .await
                .map_err(|e| format!("ws: {e}"))?;
            assert_eq!(
                resp.headers().get("Sec-WebSocket-Protocol").unwrap(),
                WEBSOCKET_SUBPROTOCOL
            );
            Ok(ws)
        }

        async fn connect(&self) -> Client {
            self.connect_with(
                WEBSOCKET_PATH,
                Some(WEBSOCKET_SUBPROTOCOL),
                &self.server.fingerprint,
            )
            .await
            .unwrap()
        }
    }

    async fn send(ws: &mut Client, m: &Message) {
        ws.send(WsMessage::text(m.encode().unwrap())).await.unwrap();
    }

    async fn recv(ws: &mut Client) -> Option<Message> {
        loop {
            match tokio::time::timeout(Duration::from_secs(5), ws.next())
                .await
                .expect("frame in time")
            {
                Some(Ok(WsMessage::Text(t))) => {
                    return Some(Message::decode(t.as_str()).expect("valid message"))
                }
                Some(Ok(WsMessage::Close(_))) | None | Some(Err(_)) => return None,
                Some(Ok(_)) => continue,
            }
        }
    }

    fn hello(token: &str) -> Message {
        Message::decode(&format!(
            r#"{{"type":"hello","protocol":1,"token":"{token}",
                "device":{{"manufacturer":"Google","model":"Pixel 8","androidVersion":"15","sdkInt":35,"appVersion":"0.1.0"}},
                "display":{{"width":1080,"height":2400,"densityDpi":420,"rotation":0}},
                "capabilities":{{"codecs":["H264"],"maxFps":60}}}}"#
        ))
        .unwrap()
    }

    const SDP: &str = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\na=rtpmap:102 H264/90000\r\n";

    fn error_code(m: &Option<Message>) -> &str {
        match m {
            Some(Message::Error { code, .. }) => code,
            other => panic!("expected error, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn full_signaling_flow_with_resume() {
        let mut t = TestServer::start().await;
        let token = t.token().await;
        let mut ws = t.connect().await;

        send(&mut ws, &hello(&token)).await;
        let Some(Message::Welcome {
            session_id,
            resume_token,
            receiver,
            ..
        }) = recv(&mut ws).await
        else {
            panic!("no welcome")
        };
        assert_eq!(receiver.name, "Test PC");
        assert!(matches!(
            t.next_event().await,
            ServerEvent::SenderConnected { .. }
        ));

        send(
            &mut ws,
            &Message::Offer {
                sdp: SDP.into(),
                ice_restart: false,
            },
        )
        .await;
        assert!(matches!(
            t.next_event().await,
            ServerEvent::Signal {
                message: Message::Offer { .. }
            }
        ));
        t.server
            .send_from_viewer(Message::Answer { sdp: SDP.into() })
            .unwrap();
        assert!(matches!(recv(&mut ws).await, Some(Message::Answer { .. })));

        // Drop the socket without bye: the session is suspended, not ended.
        drop(ws);
        assert_eq!(t.next_event().await, ServerEvent::Suspended);
        let mut ws = t.connect().await;
        send(
            &mut ws,
            &Message::Resume {
                protocol: 1,
                session_id: session_id.clone(),
                resume_token,
            },
        )
        .await;
        assert!(matches!(recv(&mut ws).await, Some(Message::Resumed { .. })));
        assert_eq!(t.next_event().await, ServerEvent::Resumed);

        // Viewer closes: the phone gets bye, and a new QR appears.
        t.server.end_session("receiver-closed");
        assert_eq!(
            recv(&mut ws).await,
            Some(Message::Bye {
                reason: "receiver-closed".into()
            })
        );
        assert_eq!(
            t.next_event().await,
            ServerEvent::Ended {
                reason: "receiver-closed".into()
            }
        );
        assert!(!t.token().await.is_empty());
    }

    #[tokio::test]
    async fn bad_token_is_rejected_and_reused_token_fails() {
        let mut t = TestServer::start().await;
        let token = t.token().await;
        let mut ws = t.connect().await;
        send(&mut ws, &hello(&crate::pairing::new_token())).await;
        assert_eq!(error_code(&recv(&mut ws).await), "TOKEN_INVALID");

        let mut ws = t.connect().await;
        send(&mut ws, &hello(&token)).await;
        assert!(matches!(recv(&mut ws).await, Some(Message::Welcome { .. })));
        send(
            &mut ws,
            &Message::Bye {
                reason: "user".into(),
            },
        )
        .await;
        assert!(recv(&mut ws).await.is_none(), "server closes after bye");

        let mut ws = t.connect().await;
        send(&mut ws, &hello(&token)).await;
        assert_eq!(
            error_code(&recv(&mut ws).await),
            "TOKEN_INVALID",
            "tokens are single use"
        );
    }

    #[tokio::test]
    async fn rejects_wrong_path_missing_subprotocol_and_wrong_pin() {
        let t = TestServer::start().await;
        let fp = t.server.fingerprint.clone();
        let e = t
            .connect_with("/other", Some(WEBSOCKET_SUBPROTOCOL), &fp)
            .await
            .err()
            .unwrap();
        assert!(e.contains("404"), "{e}");
        let e = t
            .connect_with(WEBSOCKET_PATH, None, &fp)
            .await
            .err()
            .unwrap();
        assert!(e.contains("400"), "{e}");
        let e = t
            .connect_with(
                WEBSOCKET_PATH,
                Some(WEBSOCKET_SUBPROTOCOL),
                &crate::pairing::new_token(),
            )
            .await
            .err()
            .unwrap();
        assert!(e.starts_with("tls"), "{e}");
    }

    #[tokio::test]
    async fn hello_timeout_and_garbage_are_answered_with_errors() {
        let t = TestServer::start().await;
        let mut ws = t.connect().await;
        assert_eq!(error_code(&recv(&mut ws).await), "HELLO_TIMEOUT");

        let mut ws = t.connect().await;
        ws.send(WsMessage::text(r#"{"type":"touch","x":5,"y":5}"#))
            .await
            .unwrap();
        assert_eq!(error_code(&recv(&mut ws).await), "UNEXPECTED_MESSAGE");

        let mut ws = t.connect().await;
        ws.send(WsMessage::Binary(vec![1, 2, 3].into()))
            .await
            .unwrap();
        assert_eq!(error_code(&recv(&mut ws).await), "MALFORMED_MESSAGE");
    }

    #[tokio::test]
    async fn oversized_frames_are_refused() {
        let t = TestServer::start().await;
        let mut ws = t.connect().await;
        let big = format!(
            r#"{{"type":"bye","reason":"user","pad":"{}"}}"#,
            "x".repeat(70_000)
        );
        let _ = ws.send(WsMessage::text(big)).await;
        assert!(recv(&mut ws).await.is_none(), "connection is dropped");
    }
}
