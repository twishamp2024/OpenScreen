# Contributing to OpenScreen

Thanks for helping. This file covers how to propose changes; build
instructions are in [docs/ANDROID_BUILD.md](docs/ANDROID_BUILD.md) and
[docs/WINDOWS_BUILD.md](docs/WINDOWS_BUILD.md).

## Ground rules

- **View-only is a hard rule.** Pull requests that add any way for the PC to
  send input, commands, files or clipboard data to the phone, or that add
  Accessibility, ADB or device-admin usage, will not be merged.
- **No cloud dependencies.** Features must work on a LAN with no Internet.
- **No telemetry**, analytics or crash reporting that leaves the device.
- **Do not claim performance numbers** in docs or UI unless they come from a
  measurement recorded per [docs/TESTING.md](docs/TESTING.md).

## Workflow

1. Open an issue first for anything bigger than a small fix, so the design
   can be discussed.
2. Fork, create a branch (`feat/…`, `fix/…`, `docs/…`).
3. Keep each pull request focused on one change.
4. Make sure CI passes locally:
   ```
   npm ci --prefix scripts && npm test --prefix scripts
   cd android && ./gradlew lint test
   cd desktop && npm ci && npm run lint && npm test && cargo test --manifest-path src-tauri/Cargo.toml
   ```
5. Fill in the pull-request template, including how you tested on real
   devices when the change touches capture, streaming or networking.

## Protocol changes

The protocol is shared by two independently released apps. Any change to
signaling messages must update, in the same pull request:
`docs/PROTOCOL.md`, `shared/protocol/` (schema, constants, fixtures), the
Kotlin implementation and the Rust implementation. See
[shared/protocol/README.md](shared/protocol/README.md).

## Code style

- Kotlin: official Kotlin style, enforced by Android Lint and the Kotlin
  compiler's warnings-as-errors in CI.
- Rust: `cargo fmt` and `cargo clippy -- -D warnings`.
- TypeScript: ESLint + Prettier (`npm run lint`, `npm run format`).
- Commit messages: imperative mood, ≤ 72-character summary line
  (`Add ICE restart on network change`).

## License

By contributing you agree that your contributions are licensed under the
[MIT License](LICENSE).
