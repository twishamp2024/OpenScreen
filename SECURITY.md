# Security Policy

OpenScreen shares the contents of a phone screen, which can include very
private information. We take reports seriously.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's private vulnerability reporting: on the repository page, open
**Security → Report a vulnerability**. Include:

- affected app(s) and versions,
- what an attacker needs (same Wi-Fi? physical access? a malicious app?),
- steps to reproduce or a proof of concept,
- the impact you observed.

We aim to acknowledge reports within 7 days and to agree on a disclosure
date with you, normally no later than 90 days after the report.

## Supported versions

Only the latest release receives security fixes until the project reaches
1.0.

## Scope

In scope, for example:

- anyone other than the paired phone connecting to the PC's signaling server,
- anyone other than the paired PC receiving or decrypting the stream,
- bypassing pairing-token expiry or single use, or resuming someone else's
  session,
- the signaling server being reachable from outside the local network,
- any path by which the PC can cause actions on the phone (the apps are meant
  to be strictly view-only),
- leaking personal data beyond what PROTOCOL.md says is sent.

Out of scope: attacks requiring a rooted/compromised phone or an
administrator account on the PC; denial of service by someone on the same
network flooding it; social engineering a user into scanning a QR code shown
by an attacker's PC.

The threat model is described in
[docs/ARCHITECTURE.md §7](docs/ARCHITECTURE.md#7-security-model).
