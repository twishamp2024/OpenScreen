# Testing

## Automated tests

| Suite | Command | Covers |
|---|---|---|
| Protocol & repo | `npm ci --prefix scripts && npm test --prefix scripts` | Schema vs. fixtures, constants consistency, pairing-URI vectors (reference parser), required files, version agreement, view-only source guard, no secrets tracked |
| Android unit (JVM) | `cd android && ./gradlew test` | Message + pairing-URI conformance with the shared fixtures; constants; the full sender session state machine (pairing, negotiation, ICE restart, drop → resume with token rotation, resume-window expiry, protocol errors); real TLS + WebSocket handshakes against a local server (pinning, subprotocol, refused); capture sizing; subnet logic; SDP codec parsing; manifest view-only policy; UI formatting and error-text coverage |
| Android lint | `./gradlew lint` | Android Lint, warnings-as-errors for Kotlin |
| Rust | `cd desktop/src-tauri && cargo test` | Message + pairing-URI conformance; token generation, single use, expiry; the receiver session hub (busy, rate limits, resume, bye, ordering, offer checks); interface selection; TLS identity; settings and screenshot paths; **integration tests over real TLS 1.3 + WebSocket**: full pairing → offer/answer → drop → resume → bye, wrong token, token reuse, wrong path, missing subprotocol, wrong certificate pin, hello timeout, unknown/binary/oversized frames |
| Frontend | `cd desktop && npm test` | WebRTC receiver (answering, candidate queueing, ICE-restart ordering, data channels refused), stats and quality grading, home window states, settings validation, **QR code decodes back to the exact pairing link** |

### End-to-end check without a phone

The probe is a headless stand-in for the phone. Debug builds print their
pairing link:

```
cd desktop
npm run tauri dev                      # prints: [openscreen] pairing link: openscreen://pair?...
cd src-tauri
cargo run --example probe -- "<link>"
```

Expected output: TLS pinned → `welcome` → offer sent → **answer from the
viewer's WebRTC stack (`a=recvonly`)** → bye. This exercises the real
signaling server, the viewer window, WebView2's RTCPeerConnection and the
relay back. Media cannot flow (the probe has no ICE/DTLS stack).

### UI preview

`npm run dev` in `desktop/`, then open
`http://127.0.0.1:1420/preview.html?state=waiting` (states: `waiting`,
`rejected`, `connected`, `suspended`, `error`; add `&theme=light`; add
`#viewer` for the viewer window). Uses Tauri's IPC mocks; dev only.

### On-device (instrumented) tests

`cd android && ./gradlew :core:connectedDebugAndroidTest` with a phone
attached runs the real libwebrtc: native library load order, the encoder
list, and that a real offer is send-only video, **leads with H.264**, and has
host candidates only. These caught two bugs the JVM tests could not (see
ARCHITECTURE.md §11).

## Verified configurations

Only pass/fail is recorded here; performance numbers are published only from
the measurement procedure below.

| Date | Phone (Android) | PC | Network | Result |
|---|---|---|---|---|
| 2026-11-27 | Redmi Note 7S (Android 10 / MIUI) | Windows 11, WebView2 158 | Home Wi-Fi, same /24 | M1, M2, M3, M8 pass: QR pairing, hardware H.264 (`OMX.qcom.video.encoder.avc`), live video, rotation re-fit. Open: occasional "encoder queue full" frame drops around rotation/heavy motion. |

## Manual test procedure (real hardware)

Run before each release with at least one phone and one PC, ideally a second
phone from a different manufacturer. Record results in the release's
tracking issue using the template at the end.

### Setup

- Phone: Android version, model, OpenScreen version.
- PC: Windows version, CPU/GPU, OpenScreen version, Wi-Fi or Ethernet.
- Router model and band.

### Cases

| ID | Case | Steps | Expected |
|---|---|---|---|
| M1 | Fresh install | Install both apps; launch | PC shows QR, local IP, port; phone shows "Not paired" |
| M2 | Pair | Scan QR | Phone shows "Connected" with the PC name; PC shows phone model; viewer window opens; no capture prompt yet |
| M3 | Start streaming | Tap Start → choose *Entire screen* | Persistent notification; viewer shows the phone screen within a few seconds |
| M4 | Deny capture | Tap Start → Cancel | Clear message; still connected; Start works afterwards |
| M5 | Aspect ratio | Resize the viewer very wide and very tall | Image never stretches; letterboxing only |
| M6 | Maximize / fullscreen / always-on-top | Use each control, F11, Esc | Behaves as labelled |
| M7 | Screenshot | Click Screenshot | PNG at the stream's native resolution in Pictures\OpenScreen |
| M8 | Rotation | Rotate phone portrait ↔ landscape (in an app that rotates) | Stream follows within ~1 s; window re-fits; no distortion |
| M9 | Quality presets | 720p30, 1080p30, 1080p60 (if offered) | Stats overlay shows the matching resolution/FPS |
| M10 | Stop from phone | Tap Stop streaming | Notification gone; viewer says streaming stopped; can start again |
| M11 | Stop from status bar | Use Android's *Stop sharing* chip | Same as M10 |
| M12 | Close viewer | Close the viewer window | Phone shows "Session ended"; capture stops; PC shows a new QR |
| M13 | Wi-Fi blip | Phone Wi-Fi off ~10 s, then on | "Reconnecting…" on both; video resumes without rescanning |
| M14 | Long outage | Wi-Fi off 90 s | Session ends cleanly on both sides with a message |
| M15 | Background app | Press Home / open other apps | Streaming continues |
| M16 | Kill PC app | End OpenScreen in Task Manager | Phone reports lost connection within ~15 s, then gives up after 60 s |
| M17 | Expired QR | Photo of a > 5 min old code | "QR code expired" |
| M18 | Reused QR | Pair, end, scan a photo of the first QR | "QR code no longer valid" |
| M19 | Wrong network | Phone on mobile data / other Wi-Fi | Different-network warning, then "Can't reach your PC" |
| M20 | Guest network | Both on a client-isolated network | "Can't reach your PC" explaining isolation |
| M21 | Firewall | Block OpenScreen in Windows Firewall | Timeout message mentioning the firewall |
| M22 | Long run | Stream 60 min at 1080p30 | No crash; FPS stable; record CPU/RAM on both at 5 and 60 min |
| M23 | View-only | Click/type/scroll in the viewer | Nothing happens on the phone |
| M24 | Second phone | While connected, scan with another phone | "PC is busy" |

### Measurements (do not publish numbers that are not recorded here)

| Metric | How |
|---|---|
| Glass-to-glass latency | Film a millisecond stopwatch on the phone and the viewer together with a 120/240 FPS camera; median of 10 frames |
| Received FPS / bitrate | Viewer stats overlay, 1-minute average |
| CPU / memory | Android Studio profiler or `adb shell top`; Task Manager (OpenScreen + `msedgewebview2.exe`) |
| Battery | Android battery usage after a 60-minute session |

### Result template

```
Release: 0.x.y   Date:   Tester:
Phone:            Android:     PC:            Windows:     Network:
| ID  | Pass/Fail | Notes |
| M1  |           |       |
...
Latency (median, 1080p30): ___ ms   CPU phone/PC: ___ / ___ %
```
