# OpenScreen Protocol — Version 1

Status: **Draft for implementation** (Phase 1).
Machine-readable definitions: [`shared/protocol/`](../shared/protocol/).
If this document and the schema disagree, that is a bug; open an issue.

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

Terms: the **sender** is the Android app; the **receiver** is the Windows app.

---

## 1. Overview

1. The receiver shows a QR code containing a **pairing URI** (Section 2).
2. The sender opens a **TLS WebSocket** to the receiver and pins the
   certificate fingerprint from the URI (Section 3).
3. The sender authenticates with `hello` (pairing token) or `resume`
   (resume token) (Section 5).
4. The two sides exchange WebRTC SDP and ICE candidates over the WebSocket
   (Section 6).
5. Video flows over WebRTC (Section 8). The WebSocket stays open for the
   whole session and carries state changes and teardown.

The protocol is **view-only by design**: there is no message that carries
input, commands, files, or clipboard data, and receivers MUST NOT create
WebRTC data channels.

---

## 2. Pairing URI (QR code contents)

```
openscreen://pair?v=1&h=192.168.1.20&p=47820&t=<token>&f=<fingerprint>&n=<name>
```

| Param | Required | Format | Meaning |
|---|---|---|---|
| `v` | yes | decimal integer | Protocol major version. This document is `1`. |
| `h` | yes | 1–4 IPv4 addresses, comma-separated | Receiver addresses, in preference order. Each MUST be in `10.0.0.0/8`, `172.16.0.0/12` or `192.168.0.0/16`. |
| `p` | yes | decimal integer 1–65535 | Signaling port (TCP). |
| `t` | yes | base64url, no padding, exactly 43 chars | 32-byte pairing token. |
| `f` | yes | base64url, no padding, exactly 43 chars | SHA-256 of the receiver certificate (DER). |
| `n` | no | percent-encoded UTF-8, 1–32 characters after decoding, no control characters | Human-readable receiver name shown on the phone. |

Rules:

- Scheme MUST be `openscreen` and host MUST be `pair` (case-insensitive).
- Parameters MUST NOT repeat. Unknown parameters MUST be ignored.
- Values are percent-decoded as UTF-8. `+` is a literal plus sign, not a
  space. IPv4 octets are decimal without leading zeros (`01` is invalid).
- `v` and `p` are plain decimal digits (no sign, no whitespace, no leading
  zeros).
- Control characters are U+0000–U+001F and U+007F.
- Lengths are counted in Unicode code points.
- If `v` is higher than the sender supports, the sender MUST NOT connect and
  MUST tell the user to update the Android app.
- The sender SHOULD warn (not block) when none of the `h` addresses is in
  the same subnet as its Wi-Fi address.
- The sender tries `h` addresses in order, with a 3 s TCP connect timeout
  each.

Parsers report the **first** failing check, in this order, so all
implementations agree on the error for a given URI:

| Code | Check |
|---|---|
| `NOT_A_PAIRING_URI` | scheme/host wrong, or not a URI |
| `DUPLICATE_PARAM` | a known parameter appears twice |
| `MISSING_PARAM` | `v`, `h`, `p`, `t` or `f` absent |
| `BAD_VERSION` | `v` not a positive integer |
| `UNSUPPORTED_VERSION` | `v` greater than supported |
| `BAD_HOST` | empty list, > 4 entries, malformed or non-private address |
| `BAD_PORT` | not an integer 1–65535 |
| `BAD_TOKEN` | not 43 base64url characters |
| `BAD_FINGERPRINT` | not 43 base64url characters |
| `BAD_NAME` | present but empty, > 32 characters, or contains control characters |

Canonical test vectors: [`fixtures/pairing-uris.json`](../shared/protocol/fixtures/pairing-uris.json).

---

## 3. Transport

| Property | Value |
|---|---|
| URL | `wss://<h>:<p>/v1/signal` |
| WebSocket subprotocol | `openscreen.v1` (`Sec-WebSocket-Protocol`) |
| TLS | 1.3 only |
| Certificate | Self-signed, ECDSA P-256, generated at receiver start-up, never persisted. SAN lists the advertised IPs. |
| Authentication of receiver | Sender MUST accept the certificate if and only if `base64url(SHA-256(DER)) == f`. Hostname and CA checks are not used. |
| Frames | Text frames only, one JSON object per frame, UTF-8. Binary frames are a protocol error. |
| Max message size | 65 536 bytes |
| Keep-alive | WebSocket ping every 5 s from both sides; the peer is considered gone after 15 s without any frame. |

Receivers MUST:

- bind only to the addresses advertised in `h`, never to `0.0.0.0`;
- close connections whose source address is not in a private range;
- answer any HTTP path other than `/v1/signal` with `404` and close;
- reject a missing or different subprotocol with `400`.

---

## 4. Messages

Every message is a JSON object with a string field `type`. Implementations
MUST ignore unknown **fields** and MUST reject unknown **types** with
`UNEXPECTED_MESSAGE`. String lengths below are in Unicode code points.

### 4.1 Sender → receiver

#### `hello`
First message of a new pairing.
```json
{
  "type": "hello",
  "protocol": 1,
  "token": "q3V1Yx8n5uJm0yH4bQ2sW9cT6dK1fL7rA0pE3iO5gZs",
  "device": {
    "manufacturer": "Google",
    "model": "Pixel 8",
    "androidVersion": "15",
    "sdkInt": 35,
    "appVersion": "0.1.0"
  },
  "display": { "width": 1080, "height": 2400, "densityDpi": 420, "rotation": 0 },
  "capabilities": { "codecs": ["H264", "VP8"], "maxFps": 60 },
  "features": []
}
```
| Field | Constraint |
|---|---|
| `protocol` | integer ≥ 1 |
| `token` | 43-char base64url |
| `device.manufacturer`, `device.model` | 1–64 chars |
| `device.androidVersion` | 1–16 chars |
| `device.sdkInt` | integer 29–100 |
| `device.appVersion` | 1–32 chars |
| `display.width`, `display.height` | integer 1–16384 (physical pixels, current orientation) |
| `display.densityDpi` | integer 1–2000 |
| `display.rotation` | one of `0`, `90`, `180`, `270` |
| `capabilities.codecs` | 1–8 of `H264`, `VP8`, `VP9`, `AV1` |
| `capabilities.maxFps` | integer 1–240 |
| `features` | optional, 0–16 strings of 1–32 chars (Section 9) |

Devices MUST NOT send user-assigned device names, account names, phone
numbers, Wi-Fi names, or hardware identifiers.

#### `resume`
First message of a reconnect within the resume window.
```json
{ "type": "resume", "protocol": 1, "sessionId": "Zk3p0Qm8vR2xT5nW7yB1cA", "resumeToken": "<43 chars>" }
```
`sessionId`: 22-char base64url (16 bytes). `resumeToken`: 43-char base64url.

#### `offer`
```json
{ "type": "offer", "sdp": "v=0\r\n...", "iceRestart": false }
```
`sdp`: 1–32 768 chars, MUST start with `v=0`. `iceRestart`: boolean, optional,
default `false`.

#### `stream-state`
```json
{
  "type": "stream-state",
  "state": "streaming",
  "video": { "width": 1080, "height": 2400, "fps": 30, "codec": "H264", "rotation": 0 }
}
```
| Field | Constraint |
|---|---|
| `state` | `ready` (transport up, not capturing), `streaming`, `paused`, `stopped` |
| `video` | required when `state` is `streaming`; same ranges as `display`; `fps` 1–240; `codec` as in capabilities |
| `reason` | optional when `state` is `stopped`: `user`, `projection-revoked`, `encoder-error`, `app-backgrounded` |

### 4.2 Receiver → sender

#### `welcome`
```json
{
  "type": "welcome",
  "protocol": 1,
  "sessionId": "Zk3p0Qm8vR2xT5nW7yB1cA",
  "resumeToken": "<43 chars>",
  "resumeWindowMs": 60000,
  "receiver": { "name": "Study PC", "os": "windows", "appVersion": "0.1.0" },
  "features": []
}
```
`receiver.name` follows the `n` rules from Section 2. `resumeWindowMs`:
integer 0–300 000.

#### `resumed`
```json
{ "type": "resumed", "sessionId": "Zk3p0Qm8vR2xT5nW7yB1cA", "resumeToken": "<new 43 chars>", "resumeWindowMs": 60000 }
```
The old resume token is invalid from this point.

#### `answer`
```json
{ "type": "answer", "sdp": "v=0\r\n..." }
```

### 4.3 Both directions

#### `ice`
```json
{ "type": "ice", "candidate": { "candidate": "candidate:1 1 udp 2122260223 192.168.1.20 54321 typ host", "sdpMid": "0", "sdpMLineIndex": 0 } }
```
`candidate.candidate`: 0–1024 chars (empty string = end of candidates for
that m-line). `candidate` itself MAY be `null` meaning end of all candidates.
`sdpMid`: string ≤ 32 chars or `null`. `sdpMLineIndex`: integer 0–15 or `null`.
At least one of `sdpMid` / `sdpMLineIndex` MUST be non-null.

#### `bye`
```json
{ "type": "bye", "reason": "user" }
```
`reason`: `user`, `receiver-closed`, `sender-closed`, `timeout`, `error`.
After sending `bye` the sender of it closes the WebSocket with code 1000.
The session and its resume token end immediately.

#### `error`
```json
{ "type": "error", "code": "TOKEN_EXPIRED", "message": "The QR code expired. Scan the new code on your PC.", "fatal": true }
```
| Field | Constraint |
|---|---|
| `code` | one of Section 7 |
| `message` | 0–256 chars, English, for logs; UIs MUST map `code` to their own localized text |
| `fatal` | boolean; when `true` the sender of the error closes the socket after sending |
| `supported` | optional `{ "min": int, "max": int }`, sent with `PROTOCOL_UNSUPPORTED` |

---

## 5. Authentication and session rules

### 5.1 Pairing tokens
- 32 bytes from the OS CSPRNG, encoded base64url without padding.
- Valid for **300 s**. The receiver rotates the token (and the QR) when it
  expires while no session exists.
- **Single use:** consumed by the first `hello` that presents it, whether or
  not that `hello` is otherwise valid.
- Compared in constant time.
- The receiver remembers hashes of tokens that expired in the last 10 min so
  it can answer `TOKEN_EXPIRED` instead of `TOKEN_INVALID`.

### 5.2 Sessions
- At most **one** session exists. While one exists, `hello` gets
  `SESSION_BUSY` (fatal) and no QR is displayed.
- On a valid `hello` the receiver creates `sessionId` (16 random bytes) and
  `resumeToken` (32 random bytes) and sends `welcome`.
- If the WebSocket closes without `bye`, the session becomes **suspended**
  for `resumeWindowMs`. A `resume` with matching `sessionId` and
  `resumeToken` reattaches it and the receiver replies `resumed` with a new
  resume token. A wrong pair gets `RESUME_REJECTED` (fatal); after 3
  rejections the suspended session is ended.
- When the window passes, the session ends.

### 5.3 Rate limiting
- At most 5 failed authentications per source IP per 60 s; further
  connection attempts from that IP get `RATE_LIMITED` (fatal) without token
  checking.
- At most 50 messages per second per connection; excess gets `RATE_LIMITED`.
- A connection that sends nothing valid within **10 s** gets
  `HELLO_TIMEOUT` (fatal).

---

## 6. Message sequencing

Allowed messages per receiver-side state. Anything else gets
`UNEXPECTED_MESSAGE` (fatal).

| Receiver state | Accepted from sender |
|---|---|
| `AWAIT_HELLO` | `hello`, `resume` |
| `NEGOTIATE` | `offer`, `ice`, `stream-state`, `bye` |
| `ACTIVE` | `offer` (ICE restart / renegotiation), `ice`, `stream-state`, `bye` |

The sender accepts `welcome`/`resumed`/`error` as the first message, then
`answer`, `ice`, `bye`, `error`.

Normal sequence:
```
S: hello          R: welcome
S: offer          R: answer
S/R: ice ...      (trickle, any order, until candidate = null)
S: stream-state {ready}
   ... user taps Start Streaming, grants consent ...
S: stream-state {streaming, video}
   ... rotation ...
S: stream-state {streaming, video (new size)}
S: stream-state {stopped, reason: user}   (sender may start again later)
S: bye {user}
```
Every `offer` MUST be answered by exactly one `answer` before the sender
sends another `offer` (no glare: the receiver never offers).

---

## 7. Error codes

| Code | Fatal | When | Sender UI guidance |
|---|---|---|---|
| `PROTOCOL_UNSUPPORTED` | yes | `protocol` outside receiver's range | "Update OpenScreen on your phone / PC" (direction from `supported`) |
| `TOKEN_INVALID` | yes | unknown or already-used token | "This QR code is not valid any more. Scan the code shown on your PC." |
| `TOKEN_EXPIRED` | yes | token expired ≤ 10 min ago | "The QR code expired. Scan the new code." |
| `SESSION_BUSY` | yes | another phone is connected | "Your PC is already showing another phone." |
| `RESUME_REJECTED` | yes | bad or expired resume | "Connection lost. Scan the QR code again." |
| `MALFORMED_MESSAGE` | yes | invalid JSON or schema violation | "Version mismatch or bug; please report." |
| `UNEXPECTED_MESSAGE` | yes | valid message in the wrong state, or unknown type | same as above |
| `MESSAGE_TOO_LARGE` | yes | > 65 536 bytes | same as above |
| `RATE_LIMITED` | yes | Section 5.3 | "Too many attempts. Wait a minute and try again." |
| `HELLO_TIMEOUT` | yes | nothing valid within 10 s | "The PC didn't get a response in time. Try again." |
| `CODEC_UNSUPPORTED` | yes | no common video codec in the offer | "Your PC can't decode this phone's video format." |
| `INTERNAL_ERROR` | either | unexpected failure | "Something went wrong on the PC." |

---

## 8. WebRTC profile

- `iceServers` MUST be empty in v1. Only host (and peer-reflexive)
  candidates are used.
- `bundlePolicy: max-bundle`, `rtcpMuxPolicy: require`.
- The offer contains exactly one m-section: `video`, direction `sendonly`.
  The answer is `recvonly`. No `application` (data channel) or `audio`
  m-sections; a receiver MUST answer `CODEC_UNSUPPORTED` if the offer
  contains no supported video codec and MUST reject extra m-sections.
- Codec preference: H.264 (`profile-level-id=42e01f`,
  `packetization-mode=1`) first, VP8 second.
- DTLS-SRTP is mandatory (WebRTC default); the DTLS fingerprints are
  authenticated by the pinned TLS channel.

---

## 9. Versioning

- `protocol` (and `v` in the URI) is the **major** version. A receiver
  supports a range `[min, max]`; v1 receivers support `[1, 1]`.
- Within a major version, changes are additive only:
  - new **optional fields** may be added to existing messages (old
    implementations ignore them);
  - new **message types** may only be sent if both sides listed a feature
    string enabling them in `hello.features` / `welcome.features`. The
    enabled set is the intersection.
- Removing or changing the meaning of a field, or making one required,
  requires a new major version and a new WebSocket path (`/v2/signal`) and
  subprotocol (`openscreen.v2`).
- No features are defined in v1.

---

## 10. Constants

Authoritative values live in
[`shared/protocol/constants.json`](../shared/protocol/constants.json):

| Name | Value |
|---|---|
| Protocol version | 1 |
| Default port / fallback range | 47820 / 47821–47829 |
| Pairing token TTL | 300 s |
| Expired-token memory | 600 s |
| Hello timeout | 10 s |
| Resume window | 60 s |
| Max message size | 65 536 B |
| Max SDP length | 32 768 chars |
| Ping interval / dead after | 5 s / 15 s |
| Auth failures per IP per minute | 5 |
| Messages per second | 50 |
