# Building OpenScreen for Windows

## Prerequisites

| Tool | Version |
|---|---|
| Windows | 10 (1809+) or 11, x64 |
| Rust | stable (MSRV 1.85), target `x86_64-pc-windows-msvc` |
| Visual Studio Build Tools | "Desktop development with C++" workload (MSVC + Windows SDK) |
| Node.js | 22 LTS or newer (CI uses 24) |
| WebView2 Runtime | preinstalled on Windows 11; the installer bootstraps it on Windows 10 |

Toolchain pinned in `desktop/package.json` and `desktop/src-tauri/Cargo.toml`:
Tauri 2.12, React 19, Vite 8, TypeScript 5.9.

## Develop

From `desktop/`:

```
npm ci
npm run tauri dev
```

This starts Vite on `127.0.0.1:1420` and the Tauri app with hot reload.

## Test and lint

```
npm run typecheck
npm run lint            # ESLint + Prettier check
npm test                # Vitest (UI)
cd src-tauri
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test              # Rust, incl. shared-protocol conformance tests
```

## Build the installer

```
npm run tauri build
```

Output: `src-tauri/target/release/bundle/nsis/OpenScreen_<version>_x64-setup.exe`.

The installer is per-machine (one UAC prompt) and downloads the WebView2
runtime if it is missing.

### Firewall

The receiver listens on TCP **47820** (fallback 47821–47829). The installer
([`windows/installer-hooks.nsh`](../desktop/src-tauri/windows/installer-hooks.nsh))
adds one inbound rule — this executable, those ports, **Private profile
only** — and removes it on uninstall. WebRTC media (UDP, in
`msedgewebview2.exe`) needs no inbound rule: both sides send ICE checks and
the firewall admits replies to outbound packets.

Development builds (`npm run tauri dev`) are not covered by the rule;
Windows asks the first time they listen.

### Code signing

Unsigned builds trigger SmartScreen warnings. Release builds are signed in
CI when a certificate is configured as a secret (Phase 11); signing material
is never committed.

## App icons

Icons are generated from `src-tauri/icons/icon-source.svg`:

```
npx tauri icon src-tauri/icons/icon-source.svg -o src-tauri/icons
```

Delete the generated `android/` and `ios/` folders afterwards; they are not
used.

## Version numbers

`version` must be identical in `desktop/package.json`,
`desktop/src-tauri/Cargo.toml` and `desktop/src-tauri/tauri.conf.json`;
`scripts/check-repo.mjs` enforces this.
