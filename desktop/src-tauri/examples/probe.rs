//! `openscreen-probe`: a headless stand-in for the phone, for testing a running receiver.
//!
//! ```text
//! cargo run --example probe -- "openscreen://pair?v=1&h=…&p=…&t=…&f=…"
//! ```
//!
//! Pins the certificate from the link, sends `hello`, then a syntactically
//! complete send-only H.264 offer. A working receiver opens its viewer window,
//! whose WebView2 RTCPeerConnection answers; the probe prints every message
//! and exits after the answer (the media path itself needs a real phone).

use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use openscreen_receiver_lib::protocol::messages::Message;
use openscreen_receiver_lib::protocol::pairing_uri::PairingInfo;
use openscreen_receiver_lib::tls::{fingerprint_of, provider};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::Message as Ws;

#[derive(Debug)]
struct Pinned(String);

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        cert: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if fingerprint_of(cert) == self.0 {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "certificate does not match the QR fingerprint".into(),
            ))
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
            &provider().signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// A complete, browser-acceptable send-only offer (fake ICE credentials and DTLS fingerprint).
const OFFER: &str = "v=0\r\n\
o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n\
s=-\r\n\
t=0 0\r\n\
a=group:BUNDLE 0\r\n\
a=msid-semantic: WMS openscreen\r\n\
m=video 9 UDP/TLS/RTP/SAVPF 102\r\n\
c=IN IP4 0.0.0.0\r\n\
a=rtcp:9 IN IP4 0.0.0.0\r\n\
a=ice-ufrag:prb1\r\n\
a=ice-pwd:probeprobeprobeprobepr\r\n\
a=ice-options:trickle\r\n\
a=fingerprint:sha-256 4A:AD:B9:B1:3F:82:18:3B:54:02:12:DF:3E:5D:49:6B:19:E5:7C:AB:3A:B8:4A:5C:19:5B:1A:2D:3E:4F:5A:6B\r\n\
a=setup:actpass\r\n\
a=mid:0\r\n\
a=sendonly\r\n\
a=msid:openscreen screen\r\n\
a=rtcp-mux\r\n\
a=rtcp-rsize\r\n\
a=rtpmap:102 H264/90000\r\n\
a=rtcp-fb:102 nack\r\n\
a=rtcp-fb:102 nack pli\r\n\
a=rtcp-fb:102 transport-cc\r\n\
a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n\
a=ssrc:1001 cname:openscreenprobe\r\n";

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let uri = std::env::args()
        .nth(1)
        .ok_or("usage: probe <openscreen://pair?... link>")?;
    let info = PairingInfo::parse(&uri).map_err(|e| format!("bad pairing link: {e}"))?;
    let host = info.hosts[0];
    println!(
        "→ connecting to {host}:{} (pinning {}…)",
        info.port,
        &info.fingerprint[..8]
    );

    let config = rustls::ClientConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pinned(info.fingerprint.clone())))
        .with_no_client_auth();
    let tcp = tokio::net::TcpStream::connect((host, info.port)).await?;
    let tls = tokio_rustls::TlsConnector::from(Arc::new(config))
        .connect(ServerName::try_from("openscreen.local")?, tcp)
        .await?;
    let mut request = format!("wss://{host}:{}/v1/signal", info.port).into_client_request()?;
    request.headers_mut().insert(
        "Sec-WebSocket-Protocol",
        HeaderValue::from_static("openscreen.v1"),
    );
    let (mut ws, _) = tokio_tungstenite::client_async(request, tls).await?;
    println!("✓ TLS 1.3 + WebSocket up, certificate pinned");

    let hello = Message::decode(&format!(
        r#"{{"type":"hello","protocol":1,"token":"{}",
            "device":{{"manufacturer":"OpenScreen","model":"Probe","androidVersion":"15","sdkInt":35,"appVersion":"0.1.0"}},
            "display":{{"width":1080,"height":2400,"densityDpi":420,"rotation":0}},
            "capabilities":{{"codecs":["H264","VP8"],"maxFps":30}}}}"#,
        info.token
    ))?;
    ws.send(Ws::text(hello.encode()?)).await?;

    let mut sent_offer = false;
    loop {
        let frame = tokio::time::timeout(Duration::from_secs(20), ws.next()).await;
        let text = match frame {
            Err(_) => return Err("timed out waiting for the receiver".into()),
            Ok(None) => return Err("receiver closed the connection".into()),
            Ok(Some(Err(e))) => return Err(e.into()),
            Ok(Some(Ok(Ws::Text(t)))) => t,
            Ok(Some(Ok(_))) => continue,
        };
        let message = Message::decode(text.as_str())?;
        match &message {
            Message::Welcome {
                receiver,
                session_id,
                ..
            } => {
                println!(
                    "✓ welcome from \"{}\" (session {}…)",
                    receiver.name,
                    &session_id[..6]
                );
                if !sent_offer {
                    sent_offer = true;
                    ws.send(Ws::text(
                        Message::Offer {
                            sdp: OFFER.into(),
                            ice_restart: false,
                        }
                        .encode()?,
                    ))
                    .await?;
                    println!("→ offer sent; waiting for the viewer window to answer");
                }
            }
            Message::Answer { sdp } => {
                let direction = sdp
                    .lines()
                    .find(|l| matches!(*l, "a=recvonly" | "a=sendrecv" | "a=inactive"))
                    .unwrap_or("?");
                println!(
                    "✓ answer from the viewer's WebRTC stack ({} bytes, {direction})",
                    sdp.len()
                );
                ws.send(Ws::text(
                    Message::Bye {
                        reason: "user".into(),
                    }
                    .encode()?,
                ))
                .await?;
                println!("✓ bye sent — end-to-end signaling works");
                return Ok(());
            }
            Message::Ice { candidate } => println!(
                "  ice from viewer: {}",
                candidate
                    .as_ref()
                    .map(|c| c.candidate.as_str())
                    .unwrap_or("(end of candidates)")
            ),
            Message::Error { code, message, .. } => {
                return Err(format!("receiver error {code}: {message}").into())
            }
            other => println!("  {}", other.type_name()),
        }
    }
}
