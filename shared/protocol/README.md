# Shared protocol definitions

The wire format both apps must speak. Human-readable spec:
[docs/PROTOCOL.md](../../docs/PROTOCOL.md).

```
schema/signaling.v1.schema.json   JSON Schema (2020-12) for every signaling message
constants.json                    version, ports, limits, timeouts, error codes
fixtures/valid/*.json             messages every implementation must accept
fixtures/invalid/*.json           messages every implementation must reject
fixtures/pairing-uris.json        QR pairing-URI vectors with expected parse results / error codes
```

## Rules for changing the protocol

1. Change `docs/PROTOCOL.md`, the schema, `constants.json` and the fixtures
   **first**, in the same pull request as the code.
2. Add at least one valid and one invalid fixture for every new field or
   constraint.
3. Run `node scripts/validate-protocol.mjs` (after `npm ci --prefix scripts`).
4. Update the Kotlin (`android/core`) and Rust (`desktop/src-tauri`)
   implementations; their test suites read these same files, so CI fails
   until both agree.
5. Breaking changes need a new major version — see PROTOCOL.md §9.
