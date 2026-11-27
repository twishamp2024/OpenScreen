import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

/** Mirrors `AppInfo` in src-tauri/src/lib.rs. */
export interface AppInfo {
  appVersion: string;
  protocolVersion: number;
  defaultPort: number;
}

export interface DeviceInfo {
  manufacturer: string;
  model: string;
  androidVersion: string;
  sdkInt: number;
  appVersion: string;
}

export interface DisplayInfo {
  width: number;
  height: number;
  densityDpi: number;
  rotation: number;
}

export interface VideoInfo {
  width: number;
  height: number;
  fps: number;
  codec: string;
  rotation: number;
}

export interface SenderInfo {
  device: DeviceInfo;
  display: DisplayInfo;
  capabilities: { codecs: string[]; maxFps: number };
  address: string;
  video: VideoInfo | null;
  streamState: string;
}

export interface LocalInterface {
  name: string;
  ip: string;
  prefixLen: number;
  kind: "wifi" | "ethernet" | "other";
}

export type Phase = "starting" | "waiting" | "connected" | "suspended" | "error";

/** Mirrors `Status` in src-tauri/src/lib.rs. */
export interface Status {
  phase: Phase;
  pairingUri: string | null;
  expiresAtMs: number | null;
  port: number | null;
  interfaces: LocalInterface[];
  sender: SenderInfo | null;
  error: string | null;
  lastRejection: { code: string; address: string; atMs: number } | null;
  lastEndReason: string | null;
  receiverName: string;
}

export interface Settings {
  receiverName: string;
  theme: "system" | "light" | "dark";
  viewerAlwaysOnTop: boolean;
  showStats: boolean;
}

export interface IceCandidatePayload {
  candidate: string;
  sdpMid: string | null;
  sdpMLineIndex: number | null;
}

/** Signaling messages the viewer handles or sends (docs/PROTOCOL.md §4). */
export type SignalMessage =
  | { type: "offer"; sdp: string; iceRestart?: boolean }
  | { type: "answer"; sdp: string }
  | { type: "ice"; candidate: IceCandidatePayload | null }
  | { type: "stream-state"; state: string; video?: VideoInfo; reason?: string }
  | { type: "bye"; reason: string };

export const STATUS_EVENT = "openscreen://status";
export const SIGNAL_EVENT = "openscreen://signal";
export const SETTINGS_EVENT = "openscreen://settings";

/**
 * Typed wrappers around Tauri commands. All network and OS access goes
 * through the Rust side; the UI never opens sockets itself.
 */
export const backend = {
  appInfo: () => invoke<AppInfo>("app_info"),
  status: () => invoke<Status>("get_status"),
  viewerReady: () => invoke<SignalMessage[]>("viewer_ready"),
  sendSignal: (message: SignalMessage) => invoke<void>("signal_send", { message }),
  endSession: () => invoke<void>("end_session"),
  restartServer: () => invoke<void>("restart_server"),
  settings: () => invoke<Settings>("get_settings"),
  saveSettings: (settings: Settings) => invoke<Settings>("set_settings", { settings }),
  saveScreenshot: (pngBase64: string, stem: string) =>
    invoke<string>("save_screenshot", { pngBase64, stem }),
  openScreenshotsFolder: () => invoke<void>("open_screenshots_folder"),
  onStatus: (cb: (s: Status) => void): Promise<UnlistenFn> =>
    listen<Status>(STATUS_EVENT, (e) => cb(e.payload)),
  onSignal: (cb: (m: SignalMessage) => void): Promise<UnlistenFn> =>
    listen<SignalMessage>(SIGNAL_EVENT, (e) => cb(e.payload)),
  onSettings: (cb: (s: Settings) => void): Promise<UnlistenFn> =>
    listen<Settings>(SETTINGS_EVENT, (e) => cb(e.payload)),
};
