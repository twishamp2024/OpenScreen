# OpenScreen Architecture

OpenScreen mirrors an Android phone's screen to a Windows PC over the local
Wi-Fi network. It is **view-only**: video flows from the phone to the PC and
nothing flows back that can act on the phone.

This document describes the system as designed in Phase 1. Sections marked
**(planned)** describe behaviour that later phases implement; the protocol
itself is specified normatively in [PROTOCOL.md](PROTOCOL.md).

---

## 1. System overview

```
┌──────────────────────── Android (Sender) ────────────────────────┐      ┌─────────────────────── Windows (Receiver) ───────────────────────┐
│                                                                  │      │                                                                  │
│  Compose UI ──► StreamingService (foreground, mediaProjection)   │      │  Rust core (Tauri 2)                    WebView2 (React/TS)      │
│                     │                                            │      │  ┌────────────────────────┐            ┌──────────────────────┐  │
│                     ▼                                            │      │  │ Interface discovery    │            │ Home window          │  │
│  MediaProjection ─► VirtualDisplay ─► SurfaceTexture (GPU)       │      │  │ Pairing tokens + QR    │──events──► │  QR, status, settings│  │
│                     │                                            │      │  │ TLS cert (ephemeral)   │            └──────────────────────┘  │
│                     ▼                                            │      │  │ WSS signaling server   │            ┌──────────────────────┐  │
│  WebRTC VideoSource ─► HW H.264 encoder (MediaCodec)             │      │  │ Session state machine  │◄─invoke──► │ Viewer window        │  │
│                     │                                            │      │  │ Message validation     │            │  RTCPeerConnection   │  │
│                     ▼                                            │      │  └──────────┬─────────────┘            │  <video> renderer    │  │
│  RTCPeerConnection (sendonly) ── SRTP/DTLS over UDP ─────────────┼──────┼─────────────┼──────────────────────────►  getStats() overlay  │  │
│                     ▲                                            │      │             │                          └──────────────────────┘  │
│  SignalingClient ───┴── WSS (TLS, pinned cert) ──────────────────┼──────┼─────────────┘                                                  │
└──────────────────────────────────────────────────────────────────┘      └──────────────────────────────────────────────────────────────────┘
```

Two channels connect the devices:

| Channel | Transport | Direction | Carries |
|---|---|---|---|
| Signaling | WebSocket over TLS (`wss://`), TCP | both | Pairing handshake, SDP offer/answer, ICE candidates, stream state |
| Media | WebRTC SRTP over DTLS, UDP | phone → PC | One H.264 video track (RTP) + RTCP feedback |

RTCP feedback (receiver reports, NACK, PLI, transport-wide congestion
control) flows PC → phone. It is part of the WebRTC standard, it only
controls the encoder's bitrate and keyframes, and it cannot express input.

There is no cloud component, no account, and no STUN/TURN server.

---

## 2. Components

### 2.1 Android sender (`android/`)

| Module | Kind | Responsibility |
|---|---|---|
| `:core` | Android library | Protocol models and validation, pairing-URI parsing, signaling client, WebRTC session, screen capturer, statistics. The `protocol` package is plain Kotlin with JVM unit tests and no Android dependencies. |
| `:app` | Android application | Compose UI, ViewModels, `StreamingService` foreground service, QR scanner, settings persistence, permission flows. |

Key runtime objects (planned):

- **`StreamingService`** — foreground service with
  `foregroundServiceType="mediaProjection"`. Owns the `MediaProjection`,
  the capturer and the peer connection so streaming survives the activity
  being backgrounded. Shows the persistent notification with a *Stop* action.
- **`ScreenCapturer`** — implements `org.webrtc.VideoCapturer`. Creates a
  `VirtualDisplay` that renders into a `SurfaceTextureHelper` surface, so
  frames stay on the GPU as OES textures and go straight into the hardware
  encoder without a CPU copy. Listens for display rotation and resizes the
  virtual display.
- **`SignalingClient`** — OkHttp WebSocket bound to the Wi-Fi `Network`,
  with a custom `X509TrustManager` that accepts exactly the certificate whose
  SHA-256 fingerprint was in the QR code.
- **`MirrorSession`** — the session state machine (Section 4), reconnect
  logic, and ICE-restart handling.

### 2.2 Windows receiver (`desktop/`)

**Rust (`desktop/src-tauri/`)** — everything that touches the network or the
OS:

| Module (planned) | Responsibility |
|---|---|
| `net::interfaces` | Enumerate adapters, keep private-range IPv4 addresses on up, non-virtual adapters, rank them (Wi-Fi/Ethernet with a gateway first). |
| `pairing` | 256-bit tokens from the OS CSPRNG, expiry, single use, constant-time comparison, attempt rate limiting. |
| `tls` | Generate an ephemeral self-signed certificate (`rcgen`) per app launch; expose its SHA-256 fingerprint for the QR code. |
| `signaling` | `tokio` + `tokio-rustls` + `tokio-tungstenite` WebSocket server bound only to the selected interface addresses. |
| `session` | Session state machine, resume tokens, timeouts, relaying validated messages to/from the viewer webview. |
| `protocol` | `serde` models of every message in PROTOCOL.md; strict validation (unknown `type`, oversize fields, wrong state → reject). |
| `config` | Persisted settings (port, theme, screenshot folder, always-on-top default). |
| `window` | Creating the viewer window, fullscreen, always-on-top, fit-to-video sizing. |

**TypeScript/React (`desktop/src/`)** — everything that is UI or WebRTC:

| Area | Responsibility |
|---|---|
| Home window | Logo, status, QR code (rendered from the pairing URI the Rust side provides), local IPs, instructions, connected device, settings. |
| Viewer window | Owns the `RTCPeerConnection` (recvonly), renders the remote track in a `<video>` with `object-fit: contain`, statistics overlay, toolbar (fullscreen, always-on-top, screenshot, fit window). |

The WebView never opens sockets itself. Signaling messages travel
`Android ⇄ Rust (WSS) ⇄ viewer webview (Tauri events / commands)`. Rust
validates every message in both directions, so a bug or compromise in the UI
layer cannot emit a message the protocol does not allow.

### 2.3 Shared protocol (`shared/protocol/`)

The single source of truth for the wire format:

- `schema/signaling.v1.schema.json` — JSON Schema (draft 2020-12) of every
  signaling message.
- `constants.json` — protocol version, limits, timeouts, error codes.
- `fixtures/valid/*.json`, `fixtures/invalid/*.json` — example messages.
- `fixtures/pairing-uris.json` — valid and invalid pairing URIs with the
  expected parse result.

See Section 6 for how both implementations are kept in sync with it.

---

## 3. Pairing and connection flow

```
Windows (Rust)                     Windows (viewer)            Android
──────────────                     ────────────────            ───────
pick interfaces, bind :47820
generate cert + token
show QR  ───────────────────────────────────────────────────►  scan QR, parse URI
                                                               check same subnet (warn if not)
                                                               WSS connect, pin cert fingerprint
◄──────────────────────────── hello {token, device} ─────────
validate token (consume)
open viewer window ───────────────► create RTCPeerConnection
── welcome {sessionId, resumeToken} ─────────────────────────►
                                                               create PC + sendonly H.264 transceiver
◄──────────────────────────── offer {sdp} ───────────────────
relay ────────────────────────────► setRemote, createAnswer
◄───────────────────────────────── answer
── answer {sdp} ─────────────────────────────────────────────►
◄═══════════════════════ ice {candidate} (both ways) ═══════►
                                   ICE connected, DTLS done ◄► ICE connected
                                                               UI: "Connected — tap Start Streaming"
                                                               user taps Start → MediaProjection consent
                                                               start FGS, create VirtualDisplay
                                                               sender.setTrack(screenTrack)  (no renegotiation)
◄──────────────────────────── stream-state {streaming, 1080×2400} ─
                                   first frame → show video
```

**Why connect before asking for capture permission:** the transport is
established with an empty sending transceiver first. Network problems
(client isolation, firewall, wrong subnet) surface *before* the user is
asked to share their screen, so the error message can be about the network
rather than leaving the user wondering why sharing "did nothing". It also
makes *Start Streaming* near-instant, because attaching a track to an
existing transceiver needs no new offer/answer.

---

## 4. Session state machines

### 4.1 Receiver (Rust)

```
          ┌──────────── token expires → rotate token, new QR ───────────┐
          ▼                                                             │
     ┌─────────┐  TCP+TLS accepted  ┌──────────────┐  valid hello  ┌────┴─────┐
     │ PAIRING │ ─────────────────► │ AWAIT_HELLO  │ ────────────► │ NEGOTIATE│
     └─────────┘                    └──────┬───────┘               └────┬─────┘
          ▲      bad token / timeout 10 s  │                            │ ICE connected
          │◄───────────────────────────────┘                            ▼
          │                                                        ┌─────────┐
          │◄──── bye / viewer closed / resume window expired ───── │ ACTIVE  │
          │                                                        └────┬────┘
          │                                                             │ WSS dropped
          │                                                             ▼
          │                         valid resume (≤ 60 s)         ┌──────────┐
          └──────────────────────── ◄────────────────────────     │ SUSPENDED│
                                     back to NEGOTIATE / ACTIVE   └──────────┘
```

Only one sender session exists at a time. While a session is
`NEGOTIATE`/`ACTIVE`/`SUSPENDED`, new `hello` messages are rejected with
`SESSION_BUSY` and no QR is shown.

### 4.2 Sender (Android)

`IDLE → CONNECTING → PAIRED → CONNECTED (ready) → STREAMING → STOPPING → IDLE`,
plus `RECONNECTING` reachable from `CONNECTED` and `STREAMING`. Capture keeps
running during `RECONNECTING` (frames are dropped by WebRTC while there is no
transport) so the user does not have to re-grant MediaProjection consent
after a short Wi-Fi blip.

---

## 5. Media pipeline

```
MediaProjection → VirtualDisplay → SurfaceTexture (OES, GPU)
  → org.webrtc VideoSource (adapts resolution/fps to the selected preset)
  → HardwareVideoEncoder (MediaCodec, H.264 Constrained Baseline)
  → RTP packetizer → SRTP → UDP
  → WebView2 libwebrtc jitter buffer → H.264 decoder (D3D11/DXVA when available)
  → <video> element (object-fit: contain)
```

- **One video track, no audio** in the first release.
- **No transcoding:** the phone encodes once; the PC decodes once.
- **Codec:** H.264 is forced first via `RtpTransceiver.setCodecPreferences`.
  VP8 is kept second in the list only as a fallback for phones whose hardware
  H.264 encoder is broken; the UI reports when the fallback is used.
- **Presets** (targets, not measured results):

  | Preset | Long edge | FPS | Max bitrate |
  |---|---|---|---|
  | 720p30 | 1280 | 30 | 4 Mbps |
  | 1080p30 (default) | 1920 | 30 | 8 Mbps |
  | 1080p60 | 1920 | 60 | 12 Mbps |

  The capture size keeps the device's own aspect ratio; only the long edge is
  capped. 60 FPS is offered only when the encoder reports it can sustain it.
- **Adaptive bitrate:** libwebrtc's congestion controller (GCC with
  transport-wide CC) adjusts the bitrate between a floor and the preset
  maximum. `degradationPreference = MAINTAIN_RESOLUTION` makes congestion
  reduce frame rate before resolution, which keeps text readable.
- **Rotation:** on a display-rotation change the capturer resizes the
  virtual display and buffer. The new resolution arrives in-band (the
  decoder sees new SPS/PPS); the receiver also gets a `stream-state` message
  so the viewer can offer to re-fit the window.
- **Latency:** the receiver shows what WebRTC can actually measure: network
  RTT (`candidate-pair.currentRoundTripTime`), jitter-buffer delay and decode
  time. Glass-to-glass latency needs synchronized clocks and is only measured
  in manual tests with a camera ([TESTING.md](TESTING.md)).

---

## 6. Keeping Android and Windows in sync

Code generation from the schema was considered and rejected for the first
release: generated Kotlin and Rust types are awkward to validate strictly
(state rules and cross-field checks are not expressible in the generated
code anyway). Instead:

1. `shared/protocol/` holds the schema, constants and fixtures.
2. `scripts/validate-protocol.mjs` checks that every `valid/` fixture passes
   the schema and every `invalid/` fixture fails it (CI job `protocol`).
3. The Kotlin and Rust protocol modules each have tests that load **the same
   fixture files** and assert identical accept/reject decisions and parsed
   values.
4. CI fails if any implementation disagrees with a fixture. A protocol change
   therefore means: schema + fixtures first, then both implementations, in
   one pull request.

---

## 7. Security model

**Assets:** the screen contents; the user's control over who sees them.

**Attacker model:** another device on the same LAN (curious guest, malicious
IoT device), able to sniff and to send traffic, including ARP spoofing for
an active man-in-the-middle. Not in scope: a compromised phone or PC.

| Threat | Mitigation |
|---|---|
| Stranger connects and receives the stream | The phone initiates; the PC never pulls video. The PC only accepts a sender presenting a valid token. |
| Stranger impersonates a phone to the PC | 256-bit single-use token, shown only on the PC's screen, expires after 5 min, compared in constant time; failed attempts are rate-limited per IP. |
| Stranger impersonates the PC to the phone | The QR carries the SHA-256 fingerprint of the PC's ephemeral TLS certificate; the phone accepts no other certificate. |
| MITM rewrites the SDP to insert itself | SDP travels inside the pinned TLS channel, so the DTLS fingerprints in it are authentic, so SRTP is end-to-end between the two devices. |
| Replay of an old session | Tokens are single-use; resume tokens are per-session, rotated on every resume, and die 60 s after a disconnect or when the session ends. |
| Signaling server reachable from the Internet | Bound only to private-range addresses (RFC 1918) of selected adapters, never `0.0.0.0`; connections from non-private source addresses are dropped. |
| PC controls the phone | The protocol has no input, key, or command messages; the viewer creates no data channel; the Android app holds no Accessibility, ADB, or device-admin capability. Unknown message types end the session. |
| Malformed or huge messages | 64 KiB message cap, strict schema validation, per-state allowed-message tables, hello timeout. |
| Personal data leakage | Only device model, Android version and app version are sent; no user-set device names, accounts, SSIDs or identifiers. Nothing is stored on the PC after the session except user-taken screenshots. |

---

## 8. Network behaviour

- **ICE:** host candidates only, `iceServers: []`. Android uses
  `candidateNetworkPolicy = LOW_COST` (no cellular) and gathers continually
  so it can ICE-restart after a network change.
- **WebView2 mDNS candidates:** Chromium hides local IPs behind `*.local`
  mDNS names. The viewer window is started with
  `--disable-features=WebRtcHideLocalIpsWithMdns`; even without it, the
  connection succeeds through peer-reflexive candidates because the phone's
  candidates are real IPs.
- **Android multi-network:** when Wi-Fi has no Internet access, Android may
  make cellular the default network and route LAN traffic wrongly. The
  sender requests a `TRANSPORT_WIFI` network and binds both the WebSocket
  (`Network.socketFactory`) and WebRTC to it.
- **Firewall:** the signaling port must be reachable inbound on the PC (TCP),
  and UDP for `msedgewebview2.exe`. The installer adds a firewall rule for the
  *Private* profile only (planned, Phase 10); on *Public* networks the app
  explains why it cannot be reached instead of opening the firewall.
- **Client isolation / guest Wi-Fi:** detected as a TCP connect timeout to a
  same-subnet address and reported as such (see
  [TROUBLESHOOTING.md](TROUBLESHOOTING.md)).

---

## 9. Reliability

| Event | Behaviour |
|---|---|
| ICE `disconnected` | Wait up to 5 s for libwebrtc to recover on its own. |
| ICE `failed` or network change | Sender sends a new offer with `iceRestart`. |
| WebSocket dropped | Sender reconnects with exponential backoff (0.5 s → 8 s), presents `resume`; receiver keeps the session `SUSPENDED` for 60 s and the viewer shows "Reconnecting…" over the last frame. |
| Resume window expired | Receiver ends the session and shows a fresh QR; sender returns to idle with a message. |
| MediaProjection stopped by the system or user | Sender stops capture, sends `stream-state: stopped`, keeps the connection so the user can start again. |
| Viewer window closed | Receiver sends `bye: receiver-closed`; sender stops capture and the foreground service. |
| App killed on either side | The other side detects the closed socket / ICE failure and follows the WebSocket-dropped path, ending after the resume window. |

---

## 10. Technology decisions

Each decision lists what was chosen, the alternatives, and why.

### TD-1 — WebRTC for media
**Chosen:** WebRTC (libwebrtc on Android, Chromium's WebRTC inside WebView2).
**Alternatives:** raw RTP/UDP, a custom TCP stream (scrcpy-style), WebSocket
binary frames, RTSP.
**Why:** WebRTC gives us, for free and battle-tested: congestion control and
adaptive bitrate, packet-loss recovery (NACK/PLI), jitter buffering,
mandatory DTLS-SRTP encryption, and a hardware decoder in WebView2 that
needs no native decoding code on Windows. A TCP stream would add head-of-line
blocking latency on lossy Wi-Fi.

### TD-2 — Android WebRTC library: `io.getstream:stream-webrtc-android`
**Alternatives:** Google's `org.webrtc:google-webrtc` (no longer published
since 2020), building libwebrtc ourselves, LiveKit's `webrtc-sdk` build.
**Why:** maintained, Apache-2.0, built from upstream libwebrtc, keeps the
standard `org.webrtc` API (so upstream docs apply), ships hardware encoder
factories and is on Maven Central. Building libwebrtc ourselves is a
multi-hour, multi-GB build we do not want in CI. Pinned in Phase 3.

### TD-3 — H.264 Constrained Baseline as the primary codec
**Why:** universally hardware-encoded on Android 10+ devices and
hardware-decoded on Windows; WebView2 (Edge) includes H.264. Baseline avoids
B-frames, which add latency. VP8 is the software/hardware fallback.

### TD-4 — Phone is the offerer
**Why:** the phone knows its codec capabilities and owns the only track; the
PC answers `recvonly`. ICE restarts are therefore always initiated by the
side that notices network changes first (the phone, via `NetworkCallback`).

### TD-5 — Signaling over WSS with a QR-pinned self-signed certificate
**Alternatives:** plain `ws://` + token; HMAC-signing every message with the
token; a PAKE (e.g. SPAKE2).
**Why:** plain `ws://` protects against connection by strangers but not
against an active LAN MITM rewriting the SDP's DTLS fingerprint. Pinning a
certificate fingerprint delivered through the QR (an authenticated optical
channel) gives authenticated, encrypted signaling with standard libraries
(`rustls`, OkHttp) and no custom cryptography. A PAKE would allow typing a
short code but adds custom crypto; manual-code pairing is a possible later
addition.

### TD-6 — Tauri 2 + Rust + React/TypeScript + WebView2
Given by the project brief. Consequences we design around: the WebView
cannot run a server, so Rust hosts signaling; WebRTC must live in a webview,
so the peer connection lives in the viewer window; WebView2 arguments are set
per window via `additionalBrowserArgs` (which replaces Tauri's defaults, so
those defaults are repeated there).

### TD-7 — A dedicated viewer window that owns the peer connection
**Alternatives:** turn the home window into the viewer.
**Why:** the brief asks for a dedicated viewing window; giving it the
`RTCPeerConnection` keeps the media path in one JS context, and closing the
window has one clear meaning (end the session). The home window stays
available for status and settings.

### TD-8 — No true aspect-ratio-locked resizing in v1
**Why:** Tauri/tao has no aspect-ratio constraint on Windows. The viewer
letterboxes with `object-fit: contain` (never distorts), and offers *Fit
window to video*. A `WM_SIZING` hook via `windows-rs` is a possible later
improvement.

### TD-9 — QR scanning with CameraX + ZXing
**Alternatives:** Google ML Kit barcode scanning.
**Why:** ZXing is Apache-2.0 open source and works offline; ML Kit is
closed-source, which conflicts with a fully open-source app (and F-Droid).
A manual "paste pairing link" fallback exists for devices without a usable
camera.

### TD-10 — Fixture-based protocol conformance instead of code generation
See Section 6.

### TD-11 — Android baseline
`minSdk 29` (Android 10), Kotlin with Jetpack Compose and Material 3, Kotlin
serialization for JSON, OkHttp for WebSockets. On Android 14+ the capture
request uses `MediaProjectionConfig.createConfigForDefaultDisplay()` so the
user is asked to share the entire screen rather than a single app.

### TD-12 — Ports
Default signaling port **47820/TCP**, falling back to 47821–47829, then an
OS-assigned port. The QR always contains the actual port. A fixed default
makes firewall rules and troubleshooting predictable.

---

## 11. Implementation notes (Phases 2–10)

Decisions and pitfalls found while implementing; each is covered by a test
or a comment at the named location.

| Topic | What we do | Where |
|---|---|---|
| Static screens | A static screen produces no frames, so a lost packet or keyframe request could not be answered. The capturer calls `SurfaceTextureHelper.forceFrame()` when no frame arrived for 500 ms. | `android/core/.../capture/ScreenStream.kt` |
| Encoder-friendly sizes | Capture dimensions are aligned to 16 px (many SoC H.264 encoders misbehave otherwise); aspect error < 1 %, and the viewer letterboxes, so nothing is stretched. | `CaptureSize.kt`, `CaptureSizeTest` |
| Android 14 projection rules | Callback registered before `createVirtualDisplay`; one virtual display per projection, resized on rotation; consent asks for the entire screen. | `StreamingService.kt`, `ScreenStream.kt`, `MainActivity.kt` |
| Precise connection errors | OkHttp wraps OS errors, so the whole exception chain is inspected to tell "refused" (app closed) from "timed out" (isolation/firewall). A test caught the original bug. | `OkHttpSignalingTransport.classify` |
| Session logic is pure | Both state machines (phone `MirrorSession`, PC `Hub`) are synchronous/clock-injected and tested without sockets; the network layers are thin. | `MirrorSessionTest`, `hub.rs` tests |
| Viewer start-up race | The offer can arrive before the viewer window's JavaScript is listening. Rust queues signals until the viewer calls `viewer_ready`. | `lib.rs` `Viewer` |
| WebView2 arguments | Every webview in one WebView2 environment must use identical browser arguments, so `BROWSER_ARGS` is shared by both windows and checked against `tauri.conf.json` by a test. | `lib.rs` |
| Prototype freezing | Tauri's `freezePrototype` broke the `qrcode` npm package (it assigns `exports.toString`). We keep the hardening, use `uqr`, and code-split the two windows so one window's failure cannot break the other. A test decodes the rendered QR back to the exact link. | `QrCode.tsx`, `QrCode.test.tsx`, `main.tsx` |
| Receive latency | `RTCRtpReceiver.jitterBufferTarget = 0` asks Chromium for its smallest jitter buffer. | `receiverPeer.ts` |
| Native load order (found on device) | `PeerConnectionFactory.initialize()` must run before *any* other `org.webrtc` call; creating the encoder factory first crashed with `UnsatisfiedLinkError` right after QR scanning. | `WebRtcEngine.kt`, `WebRtcOnDeviceTest` |
| Codec preferences (found on device) | libwebrtc rejects sender codec preferences the connection cannot also *receive*; with a software-only decoder list the H.264-first preference was silently dropped and VP8 was negotiated. The factory now gets the hardware decoder list, and the viewer's answer also prefers H.264. | `WebRtcEngine.kt`, `receiverPeer.ts` |
| Receiver name | Defaults to "Windows PC" rather than the computer name, which often contains a person's name. | `settings.rs` |
| Firewall | The per-machine installer adds one inbound rule: this executable, TCP 47820–47829, Private profile only; removed on uninstall. | `windows/installer-hooks.nsh` |

## 12. Out of scope for the first release

macOS, Linux, iOS, USB/ADB connection, audio, multiple simultaneous senders,
remote control of any kind, Internet/relay connectivity, recording.
