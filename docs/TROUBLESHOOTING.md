# Troubleshooting

> The apps' error messages use the same wording as this page, so you can
> search for the message you see.

## Quick checklist

1. Phone and PC are on the **same network** (same router; not a guest
   network). The phone's Wi-Fi address and the PC's address shown under the
   QR code usually share the first three numbers, e.g. `192.168.1.x`.
2. OpenScreen on Windows is open and showing a QR code.
3. Windows Firewall allowed OpenScreen on **Private** networks, and the
   network is set to **Private** (Settings → Network & Internet → your
   network → Network profile type).
4. VPNs are off on both devices.

## Error messages

| Message (phone) | Meaning | What to do |
|---|---|---|
| **This isn't an OpenScreen code** | QR is not a pairing URI (`NOT_A_PAIRING_URI`) | Scan the code shown in the OpenScreen window on your PC. |
| **Update OpenScreen on your phone** / **on your PC** | Protocol versions differ (`UNSUPPORTED_VERSION`, `PROTOCOL_UNSUPPORTED`) | Install the same release on both devices. |
| **The QR code expired** | Token older than 5 min (`TOKEN_EXPIRED`) | Scan the new code; the PC refreshes it automatically. |
| **This QR code is not valid any more** | Token already used or unknown (`TOKEN_INVALID`) | Scan the code currently on screen. A code works only once. |
| **Your PC is on a different network** | No QR address is in the phone's Wi-Fi subnet (warning) | Connect both to the same Wi-Fi. Mesh systems and dual-band routers are fine if they are one network. |
| **Can't reach your PC — connection timed out** | TCP connect to the signaling port timed out | The network blocks device-to-device traffic (guest/hotel/campus Wi-Fi, "AP/client isolation"), or Windows Firewall is blocking. See below. |
| **Can't reach your PC — connection refused** | Something answered but nothing is listening | OpenScreen on the PC is closed or restarted; scan its new code. |
| **Security check failed** | TLS certificate does not match the QR fingerprint | You scanned an old code, or something on the network is intercepting traffic. Scan the current code; if it keeps happening, use a different network. |
| **Connected, but video can't get through** | Signaling works, WebRTC (UDP) fails | Windows Firewall blocks UDP for `msedgewebview2.exe`, or the network blocks UDP between devices. Allow OpenScreen on Private networks; try another network. |
| **Your PC is already showing another phone** | `SESSION_BUSY` | Stop the other phone's session, or close the viewer window. |
| **Too many attempts** | `RATE_LIMITED` | Wait one minute. |
| **Connection lost — reconnecting…** | Wi-Fi blip; resuming within 60 s | Wait. If it does not come back within a minute, scan the QR code again. |
| **Screen sharing was stopped** | Android ended MediaProjection (user tapped *Stop sharing* in the status bar, or the system revoked it) | Tap **Start Streaming** again and allow sharing. |
| **Screen capture permission denied** | The consent dialog was dismissed | Tap **Start Streaming** and choose **Entire screen** → **Start**. |

## Client isolation (guest Wi-Fi, hotels, campuses, some mesh "IoT" networks)

Symptom: *Can't reach your PC — connection timed out* even though both
devices are on the same Wi-Fi name.

Many networks deliberately block devices from talking to each other. There
is no way around this from an app. Options:

- use your home/main network instead of the guest network;
- turn off "AP isolation" / "client isolation" in the router settings;
- connect the PC to the **phone's hotspot** (support for this configuration
  is verified in Phase 9).

## Windows Firewall

OpenScreen needs:

- inbound **TCP 47820** (or 47821–47829) for the OpenScreen app;
- **UDP** for `msedgewebview2.exe` (WebRTC media) — normally allowed
  automatically because the PC also sends packets to the phone.

The installer adds the TCP rule for Private networks. If you use a
development build or clicked *Cancel* on a firewall prompt: Windows Security →
Firewall & network protection → *Allow an app through firewall* → enable
**OpenScreen** for **Private**.

OpenScreen intentionally does not ask to be allowed on **Public** networks.

## Different subnets

Some routers put 2.4 GHz and 5 GHz, or wired and wireless, on separate
subnets (for example `192.168.0.x` and `192.168.1.x`). Routing between them
may or may not work; the app warns but still tries. If it fails, put both
devices on the same band/network.

## Video looks blurry or stutters

- Choose **720p** on the phone; Wi-Fi congestion lowers quality automatically.
- Move closer to the router or use 5 GHz.
- Keep the PC on Ethernet if possible.
- The viewer shows FPS, bitrate, resolution and network round-trip time;
  include them in bug reports.

## Collecting information for a bug report

Include the app versions, devices, network type, the exact message, and —
if video connected — a screenshot of the viewer's statistics overlay (press
**I** in the viewer). On the phone, expand **Network diagnostics**.
