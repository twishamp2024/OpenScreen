# Changelog

All notable changes to OpenScreen. Versions follow [Semantic Versioning](https://semver.org/);
both apps share one version number.

## [0.1.1] — 2026-11-27

### Fixed
- **Android: dropped frames while streaming, worst during rotation.** The phone
  sent every display refresh (60 Hz) to the hardware encoder even at 30 FPS, so
  the encoder dropped frames ("encoder queue full"). A frame pacer now forwards
  at most the selected frame rate, and after a rotation the capturer skips the
  frames that still show the old layout while the encoder restarts, then sends
  one fresh frame. On a Redmi Note 7S, encoder drops fell from 128 to 14 over a
  comparable session, none caused by rotation.

### Added
- On-device test that the encoder's frame-rate and bitrate caps are applied.

### Changed
- **Release signing is optional.** Without signing secrets, GitHub releases
  publish the debug-signed APK (`OpenScreen-<version>-debug.apk`) instead of
  failing. Local builds never required signing.

## [0.1.0] — 2026-11-27

First public version: view-only Android → Windows mirroring over local Wi-Fi
with QR pairing, TLS-pinned signaling, hardware H.264 via WebRTC, a dedicated
viewer window (fullscreen, always-on-top, screenshots, statistics) and
automatic reconnection. Not published as a GitHub Release.

[0.1.1]: https://github.com/twishamp2024/OpenScreen/releases/tag/v0.1.1
[0.1.0]: https://github.com/twishamp2024/OpenScreen/commit/14645b3
