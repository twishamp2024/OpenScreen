import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { Status } from "../lib/backend";

const invoke = vi.fn();
const listeners = new Map<string, (e: { payload: unknown }) => void>();
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...a: unknown[]) => invoke(...a) }));
vi.mock("@tauri-apps/api/event", () => ({
  listen: async (name: string, cb: (e: { payload: unknown }) => void) => {
    listeners.set(name, cb);
    return () => listeners.delete(name);
  },
}));

const { HomeApp } = await import("./HomeApp");
const { validName } = await import("./SettingsDialog");

const base: Status = {
  phase: "waiting",
  pairingUri:
    "openscreen://pair?v=1&h=192.168.29.42&p=47820&t=lXPoAB1-XtnXbHZwLlJ13iMUH7T1NeqCWmA9FvHqSe8&f=Pj4_A0Jni6caoMTewX711vX8StLStu7E1ZA-89W1rz8&n=Windows%20PC",
  expiresAtMs: Date.now() + 299_000,
  port: 47820,
  interfaces: [{ name: "WiFi", ip: "192.168.29.42", prefixLen: 24, kind: "wifi" }],
  sender: null,
  error: null,
  lastRejection: null,
  lastEndReason: null,
  receiverName: "Windows PC",
};

function mockBackend(status: Status) {
  invoke.mockImplementation(async (cmd: string) => {
    switch (cmd) {
      case "get_status":
        return status;
      case "app_info":
        return { appVersion: "0.1.0", protocolVersion: 1, defaultPort: 47820 };
      case "get_settings":
        return {
          receiverName: "Windows PC",
          theme: "system",
          viewerAlwaysOnTop: false,
          showStats: true,
        };
      default:
        return undefined;
    }
  });
}

afterEach(() => {
  cleanup();
  invoke.mockReset();
  listeners.clear();
});

describe("HomeApp", () => {
  it("shows the QR code, countdown, instructions and local address while waiting", async () => {
    mockBackend(base);
    render(<HomeApp />);
    expect(await screen.findByRole("img", { name: /Pairing QR code/ })).toBeTruthy();
    expect(screen.getByText(/New code in 4:5\d/)).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Pair your phone" })).toBeTruthy();
    expect(screen.getByText(/192\.168\.29\.42 \(WiFi\) · port 47820/)).toBeTruthy();
    expect(screen.getByText("Waiting for your phone")).toBeTruthy();
  });

  it("shows the connected phone instead of the QR code", async () => {
    mockBackend({
      ...base,
      phase: "connected",
      pairingUri: null,
      expiresAtMs: null,
      sender: {
        device: {
          manufacturer: "Google",
          model: "Pixel 8",
          androidVersion: "15",
          sdkInt: 35,
          appVersion: "0.1.0",
        },
        display: { width: 1080, height: 2400, densityDpi: 420, rotation: 0 },
        capabilities: { codecs: ["H264"], maxFps: 60 },
        address: "192.168.29.50",
        video: { width: 864, height: 1920, fps: 30, codec: "H264", rotation: 0 },
        streamState: "streaming",
      },
    });
    render(<HomeApp />);
    expect(await screen.findByRole("heading", { name: "Google Pixel 8" })).toBeTruthy();
    expect(screen.getByText(/864×1920 · 30 FPS · H264/)).toBeTruthy();
    expect(screen.queryByRole("img", { name: /Pairing QR code/ })).toBeNull();
    expect(screen.getByRole("button", { name: "End session" })).toBeTruthy();
  });

  it("explains network errors with a way out", async () => {
    mockBackend({
      ...base,
      phase: "error",
      pairingUri: null,
      interfaces: [],
      error: "No Wi-Fi or Ethernet connection…",
    });
    render(<HomeApp />);
    expect(await screen.findByText(/No Wi-Fi or Ethernet connection/)).toBeTruthy();
    expect(screen.getAllByRole("button", { name: /Refresh network/ }).length).toBeGreaterThan(0);
  });

  it("warns about refused connection attempts", async () => {
    mockBackend({
      ...base,
      lastRejection: { code: "TOKEN_EXPIRED", address: "192.168.29.77", atMs: Date.now() },
    });
    render(<HomeApp />);
    expect(await screen.findByText(/192\.168\.29\.77 used an expired QR code/)).toBeTruthy();
  });

  it("updates live from status events", async () => {
    mockBackend(base);
    render(<HomeApp />);
    await screen.findByRole("img", { name: /Pairing QR code/ });
    listeners.get("openscreen://status")?.({ payload: { ...base, phase: "suspended" } });
    expect(await screen.findByText("Reconnecting…")).toBeTruthy();
  });
});

describe("validName", () => {
  it("matches the protocol's display-name rules", () => {
    expect(validName("Study PC")).toBe(true);
    expect(validName("   ")).toBe(false);
    expect(validName("x".repeat(33))).toBe(false);
    expect(validName("a\nb")).toBe(false);
    expect(validName("工作室")).toBe(true);
  });
});
