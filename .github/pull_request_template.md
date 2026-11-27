## What does this change?

<!-- One or two sentences. Link the issue: Fixes #… -->

## Type

- [ ] Bug fix
- [ ] Feature
- [ ] Protocol change (updates docs/PROTOCOL.md, shared/protocol, Kotlin **and** Rust)
- [ ] Docs / CI / tooling

## How was it tested?

- [ ] Unit tests added or updated
- [ ] `npm test --prefix scripts` (protocol + repo checks)
- [ ] Android: `./gradlew lint test`
- [ ] Desktop: `npm run lint && npm test && cargo test`
- [ ] Manual test on real devices (list phone model / Android version / Windows version and the TESTING.md cases you ran):

## Checklist

- [ ] Still strictly view-only: nothing lets the PC act on the phone
- [ ] No new network access beyond the local network; no telemetry
- [ ] No secrets, keystores, or build outputs committed
- [ ] No performance claims added without a recorded measurement
- [ ] User-facing errors say what to do next
