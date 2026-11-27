# OpenScreen

Open-source, low-latency wireless Android screen mirroring for Windows over
local Wi-Fi.

> **Status: pre-release (0.1.0).** Both apps are feature-complete for the
> first release and pass their automated tests. Live mirroring (QR pairing,
> hardware H.264, rotation) has been verified on one phone and one PC so far;
> the full manual test procedure and performance measurements are still to
> do — see [docs/TESTING.md](docs/TESTING.md#verified-configurations).

OpenScreen has two apps:

- **OpenScreen for Android** (sender) captures your phone's screen and
  streams it with WebRTC.
- **OpenScreen for Windows** (receiver) shows a QR code, receives the stream
  and displays it in its own window.

It is **view-only**. The PC cannot tap, type, press buttons or control the
phone in any way, and the Android app needs no Accessibility service, no ADB
and no root.

No account, no cloud server and no Internet connection are needed: the two
devices talk directly over your Wi-Fi network.

## How it works

1. Open OpenScreen on Windows. It shows a QR code.
2. Open OpenScreen on Android and scan the code.
3. Tap **Start Streaming** and allow screen capture.
4. Your phone's screen appears in a resizable window on the PC.

The QR code contains the PC's local address, a one-time pairing token and
the fingerprint of the PC's encryption certificate, so the phone connects
only to *that* PC and only that PC can see the stream. Video is encrypted
end-to-end with WebRTC's DTLS-SRTP. Details:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Requirements

| | Minimum |
|---|---|
| Android | Android 10 (API 29) |
| Windows | Windows 10 or 11, 64-bit, with the Microsoft Edge WebView2 runtime (preinstalled on Windows 11) |
| Network | Phone and PC on the same local network, with device-to-device traffic allowed (many guest networks block it) |

## Install

Installers will be published on the
[Releases](../../releases) page: an `.apk` for Android and an `.exe` (NSIS)
installer for Windows, each with SHA-256 checksums. No release exists yet.

## Build from source

- Android: [docs/ANDROID_BUILD.md](docs/ANDROID_BUILD.md)
- Windows: [docs/WINDOWS_BUILD.md](docs/WINDOWS_BUILD.md)
- Protocol checks: `npm ci --prefix scripts && npm test --prefix scripts`

## Repository layout

```
android/          Kotlin + Jetpack Compose sender app (:app) and library (:core)
desktop/          Tauri 2 receiver: React/TypeScript UI (src/) and Rust core (src-tauri/)
shared/protocol/  Signaling schema, constants and test fixtures shared by both apps
docs/             Architecture, protocol, build, testing and troubleshooting guides
scripts/          Repository tooling (protocol validation, consistency checks)
.github/          CI workflows, issue and pull-request templates
```

## Roadmap

| Phase | Scope | State |
|---|---|---|
| 1 | Architecture, protocol spec, repository skeleton | ✅ |
| 2 | Android MediaProjection capture | ✅ verified on device |
| 3 | Android WebRTC video transmission | ✅ verified on device (hardware H.264) |
| 4 | Windows signaling server | ✅ verified (TLS/WebSocket integration tests) |
| 5 | Windows WebRTC receiver and renderer | ✅ live video verified |
| 6 | QR pairing and secure connection | ✅ QR decode round-trip, pinning, token rules tested |
| 7 | Desktop and Android UI integration | ✅ |
| 8 | Performance optimization | ✅ configured · ⏳ measurements on hardware |
| 9 | Integration and reliability testing | ✅ automated + first device pass · ⏳ full manual procedure |
| 10 | Windows installer and Android APK packaging | ✅ |
| 11 | CI release automation and public release | ✅ workflows · ⏳ public repository and first release |

Screenshots and a demo GIF will be added after the first on-device test.

## Known limitations (by design, first release)

- Windows only on the PC side; Android only on the phone side.
- Same local network only — no Internet relay, no USB.
- No audio.
- One phone at a time.
- Networks with client isolation (common on guest, hotel and campus Wi-Fi)
  block the connection. See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).
- Performance numbers will be published only after they are measured on real
  hardware ([docs/TESTING.md](docs/TESTING.md)).

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md)
- Report vulnerabilities privately: [SECURITY.md](SECURITY.md)

## License

[MIT](LICENSE)
