/**
 * Dev-only UI preview: http://127.0.0.1:1420/preview.html?state=waiting
 * (states: waiting | connected | suspended | error | rejected; add #viewer for the viewer).
 * Uses Tauri's official IPC mocks so the real components render in a plain browser.
 */
import { mockIPC, mockWindows } from "@tauri-apps/api/mocks";
import type { Status } from "../lib/backend";

const params = new URLSearchParams(window.location.search);
const state = params.get("state") ?? "waiting";

const sender = {
  device: {
    manufacturer: "Google",
    model: "Pixel 8",
    androidVersion: "15",
    sdkInt: 35,
    appVersion: "0.1.0",
  },
  display: { width: 1080, height: 2400, densityDpi: 420, rotation: 0 },
  capabilities: { codecs: ["H264", "VP8"], maxFps: 60 },
  address: "192.168.29.50",
  video: { width: 864, height: 1920, fps: 30, codec: "H264", rotation: 0 },
  streamState: "streaming",
};

const base: Status = {
  phase: "waiting",
  pairingUri:
    "openscreen://pair?v=1&h=192.168.29.42&p=47820&t=lXPoAB1-XtnXbHZwLlJ13iMUH7T1NeqCWmA9FvHqSe8&f=Pj4_A0Jni6caoMTewX711vX8StLStu7E1ZA-89W1rz8&n=Windows%20PC",
  expiresAtMs: Date.now() + 272_000,
  port: 47820,
  interfaces: [{ name: "WiFi", ip: "192.168.29.42", prefixLen: 24, kind: "wifi" }],
  sender: null,
  error: null,
  lastRejection: null,
  lastEndReason: null,
  receiverName: "Windows PC",
};

const statuses: Record<string, Status> = {
  waiting: base,
  rejected: {
    ...base,
    lastRejection: { code: "TOKEN_EXPIRED", address: "192.168.29.77", atMs: Date.now() },
  },
  connected: { ...base, phase: "connected", pairingUri: null, expiresAtMs: null, sender },
  suspended: { ...base, phase: "suspended", pairingUri: null, expiresAtMs: null, sender },
  error: {
    ...base,
    phase: "error",
    pairingUri: null,
    expiresAtMs: null,
    interfaces: [],
    port: null,
    error:
      "No Wi-Fi or Ethernet connection with a private (home or office) network address was found. Connect this PC to the same network as your phone, then click Refresh network.",
  },
};

mockWindows(window.location.hash === "#viewer" ? "viewer" : "main");
mockIPC(
  (cmd) => {
    switch (cmd) {
      case "get_status":
        return statuses[state] ?? base;
      case "app_info":
        return { appVersion: "0.1.0", protocolVersion: 1, defaultPort: 47820 };
      case "get_settings":
        return {
          receiverName: "Windows PC",
          theme: params.get("theme") ?? "system",
          viewerAlwaysOnTop: false,
          showStats: true,
        };
      case "viewer_ready":
        return [];
      default:
        return null;
    }
  },
  { shouldMockEvents: true },
);

await import("../main");
