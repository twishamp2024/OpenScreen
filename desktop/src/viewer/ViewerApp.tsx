import { useCallback, useEffect, useRef, useState } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { LogicalSize } from "@tauri-apps/api/dpi";
import { backend, type Settings, type Status } from "../lib/backend";
import { ReceiverPeer, type PeerState } from "../lib/receiverPeer";
import {
  computeStats,
  formatBitrate,
  formatMs,
  type StatsSample,
  type ViewerStats,
} from "../lib/stats";
import { applyTheme } from "../lib/theme";
import { Icon } from "../ui/Icon";

type Toast = { text: string; action?: { label: string; run: () => void } } | null;

function timestampStem(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `OpenScreen ${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}-${p(d.getMinutes())}-${p(d.getSeconds())}`;
}

/**
 * The dedicated viewing window. Owns the RTCPeerConnection; renders the phone
 * screen with `object-fit: contain` so it is never stretched. View-only: no
 * pointer or keyboard input is ever forwarded to the phone.
 */
export function ViewerApp() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const peerRef = useRef<ReceiverPeer | null>(null);
  const [peerState, setPeerState] = useState<PeerState>("new");
  const [status, setStatus] = useState<Status | null>(null);
  const [hasVideo, setHasVideo] = useState(false);
  const [videoSize, setVideoSize] = useState<{ w: number; h: number } | null>(null);
  const [stats, setStats] = useState<ViewerStats | null>(null);
  const [showStats, setShowStats] = useState(true);
  const [onTop, setOnTop] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [chrome, setChrome] = useState(true);
  const [toast, setToast] = useState<Toast>(null);
  const idleTimer = useRef<number | undefined>(undefined);

  // ── peer connection + signaling ──
  useEffect(() => {
    const peer = new ReceiverPeer({
      sendSignal: (m) => backend.sendSignal(m),
      onTrack: (stream) => {
        const v = videoRef.current;
        if (v) {
          v.srcObject = stream;
          void v.play().catch(() => undefined);
        }
      },
      onState: setPeerState,
      onError: (e) => console.error("webrtc", e),
    });
    peerRef.current = peer;
    let unlistenSignal: (() => void) | undefined;
    let unlistenStatus: (() => void) | undefined;
    let cancelled = false;
    (async () => {
      unlistenSignal = await backend.onSignal((m) => void peer.handle(m));
      unlistenStatus = await backend.onStatus(setStatus);
      if (cancelled) return;
      setStatus(await backend.status());
      const s: Settings = await backend.settings();
      applyTheme(s.theme);
      setShowStats(s.showStats);
      setOnTop(s.viewerAlwaysOnTop);
      for (const m of await backend.viewerReady()) await peer.handle(m);
    })().catch((e) => console.error(e));
    return () => {
      cancelled = true;
      unlistenSignal?.();
      unlistenStatus?.();
      peer.close();
    };
  }, []);

  // ── stats (1 Hz) ──
  useEffect(() => {
    let previous: StatsSample | null = null;
    const id = window.setInterval(async () => {
      const peer = peerRef.current;
      if (!peer) return;
      try {
        const report = await peer.getStats();
        const r = computeStats(report.values() as Iterable<Record<string, unknown>>, previous);
        previous = r.sample;
        setStats(r.stats);
      } catch {
        /* closed */
      }
    }, 1000);
    return () => window.clearInterval(id);
  }, []);

  // ── window controls ──
  const win = useCallback(() => getCurrentWindow(), []);

  const fitWindow = useCallback(async () => {
    const v = videoRef.current;
    if (!v || !v.videoWidth || !v.videoHeight) return;
    const w = win();
    if ((await w.isFullscreen()) || (await w.isMaximized())) return;
    const height = window.innerHeight;
    const width = Math.max(240, Math.round((height * v.videoWidth) / v.videoHeight));
    await w.setSize(new LogicalSize(width, height));
  }, [win]);

  const toggleFullscreen = useCallback(async () => {
    const w = win();
    const next = !(await w.isFullscreen());
    await w.setFullscreen(next);
    setFullscreen(next);
  }, [win]);

  const toggleOnTop = useCallback(async () => {
    const next = !onTop;
    await win().setAlwaysOnTop(next);
    setOnTop(next);
  }, [onTop, win]);

  useEffect(() => {
    void win().setAlwaysOnTop(onTop);
  }, [onTop, win]);

  const screenshot = useCallback(async () => {
    const v = videoRef.current;
    if (!v || !v.videoWidth) return;
    const canvas = document.createElement("canvas");
    canvas.width = v.videoWidth; // native stream resolution, not the window size
    canvas.height = v.videoHeight;
    canvas.getContext("2d")?.drawImage(v, 0, 0);
    const data = canvas.toDataURL("image/png").split(",")[1] ?? "";
    try {
      const path = await backend.saveScreenshot(data, timestampStem());
      setToast({
        text: `Screenshot saved to ${path}`,
        action: { label: "Open folder", run: () => void backend.openScreenshotsFolder() },
      });
    } catch (e) {
      setToast({ text: `Could not save screenshot: ${String(e)}` });
    }
  }, []);

  useEffect(() => {
    if (!toast) return;
    const id = window.setTimeout(() => setToast(null), 5000);
    return () => window.clearTimeout(id);
  }, [toast]);

  // Keyboard shortcuts act on this window only — nothing is sent to the phone.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "F11" || (e.key === "Enter" && e.altKey)) {
        e.preventDefault();
        void toggleFullscreen();
      } else if (e.key === "Escape" && fullscreen) {
        void toggleFullscreen();
      } else if (e.ctrlKey && e.key.toLowerCase() === "s") {
        e.preventDefault();
        void screenshot();
      } else if (e.key.toLowerCase() === "i" && !e.ctrlKey && !e.altKey) {
        setShowStats((s) => !s);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [fullscreen, screenshot, toggleFullscreen]);

  // Auto-hide the toolbar while the pointer is idle.
  const wake = useCallback(() => {
    setChrome(true);
    window.clearTimeout(idleTimer.current);
    idleTimer.current = window.setTimeout(() => setChrome(false), 2500);
  }, []);
  useEffect(() => {
    idleTimer.current = window.setTimeout(() => setChrome(false), 2500);
    return () => window.clearTimeout(idleTimer.current);
  }, []);

  // Re-fit the window when the phone rotates (aspect ratio flips).
  const onResize = useCallback(() => {
    const v = videoRef.current;
    if (!v?.videoWidth) return;
    const next = { w: v.videoWidth, h: v.videoHeight };
    setVideoSize((prev) => {
      const flipped = prev && prev.w > prev.h !== next.w > next.h;
      if (!prev || flipped) void fitWindow();
      return next;
    });
  }, [fitWindow]);

  const streamState = status?.sender?.streamState;
  const overlay = (() => {
    if (status?.phase === "suspended")
      return {
        title: "Reconnecting…",
        body: "The connection to your phone was interrupted. It will resume automatically if it comes back within a minute.",
      };
    if (peerState === "failed")
      return {
        title: "Video connection failed",
        body: "Signaling works but video can't get through. Allow OpenScreen on Private networks in Windows Firewall, or try another Wi-Fi network.",
      };
    if (streamState === "stopped")
      return {
        title: "Streaming stopped on the phone",
        body: "Tap Start streaming on your phone to continue.",
      };
    if (streamState === "paused") return { title: "Paused", body: "The phone paused streaming." };
    if (!hasVideo && (streamState === "ready" || peerState === "connected"))
      return {
        title: "Connected",
        body: "Tap Start streaming on your phone and choose “Entire screen”.",
      };
    if (!hasVideo)
      return { title: "Connecting…", body: "Setting up the video link with your phone." };
    return null;
  })();

  const device = status?.sender?.device;
  return (
    <main
      className="viewer"
      data-chrome={chrome || !hasVideo ? "on" : "off"}
      onMouseMove={wake}
      onContextMenu={(e) => e.preventDefault()}
    >
      <video
        ref={videoRef}
        className="viewer__video"
        autoPlay
        playsInline
        muted
        disablePictureInPicture
        onLoadedData={() => setHasVideo(true)}
        onResize={onResize}
        aria-label={device ? `Screen of ${device.model}` : "Phone screen"}
      />

      {overlay && (
        <div className="viewer__overlay" role="status">
          {(overlay.title.endsWith("…") || overlay.title === "Connected") && (
            <span className="spinner" aria-hidden="true" />
          )}
          <h2>{overlay.title}</h2>
          <p>{overlay.body}</p>
        </div>
      )}

      {showStats && hasVideo && stats && (
        <aside className="viewer__stats" aria-label="Streaming statistics">
          <span className="quality" data-quality={stats.quality}>
            {stats.quality === "unknown"
              ? "Measuring…"
              : `${stats.quality[0]!.toUpperCase()}${stats.quality.slice(1)} connection`}
          </span>
          <dl>
            <dt>Resolution</dt>
            <dd>{stats.width ? `${stats.width}×${stats.height}` : "—"}</dd>
            <dt>FPS</dt>
            <dd>{stats.fps !== null ? Math.round(stats.fps) : "—"}</dd>
            <dt>Bitrate</dt>
            <dd>{formatBitrate(stats.bitrateBps)}</dd>
            <dt>Codec</dt>
            <dd>{stats.codec ?? "—"}</dd>
            <dt>Network RTT</dt>
            <dd>{formatMs(stats.rttMs)}</dd>
            <dt title="Half the network round trip + jitter buffer + decode time. Capture and encode time on the phone are not included.">
              Est. receive latency
            </dt>
            <dd>{formatMs(stats.estimatedLatencyMs)}</dd>
            <dt>Packet loss</dt>
            <dd>{stats.packetLossPct !== null ? `${stats.packetLossPct.toFixed(1)} %` : "—"}</dd>
            <dt>Decoder</dt>
            <dd>{stats.decoder ?? "—"}</dd>
          </dl>
        </aside>
      )}

      <nav className="viewer__toolbar" aria-label="Viewer controls">
        <span className="viewer__title">
          {device ? `${device.manufacturer} ${device.model}` : "OpenScreen"}
        </span>
        <button
          type="button"
          onClick={() => void fitWindow()}
          disabled={!hasVideo}
          title="Fit window to phone"
        >
          <Icon name="fit" /> <span>Fit</span>
        </button>
        <button
          type="button"
          onClick={() => void screenshot()}
          disabled={!hasVideo}
          title="Screenshot (Ctrl+S)"
        >
          <Icon name="camera" /> <span>Screenshot</span>
        </button>
        <button
          type="button"
          aria-pressed={onTop}
          onClick={() => void toggleOnTop()}
          title="Keep on top"
        >
          <Icon name="pin" /> <span>On top</span>
        </button>
        <button
          type="button"
          aria-pressed={showStats}
          onClick={() => setShowStats((s) => !s)}
          title="Statistics (I)"
        >
          <Icon name="stats" /> <span>Stats</span>
        </button>
        <button
          type="button"
          aria-pressed={fullscreen}
          onClick={() => void toggleFullscreen()}
          title="Fullscreen (F11)"
        >
          <Icon name={fullscreen ? "exitFullscreen" : "fullscreen"} />{" "}
          <span>{fullscreen ? "Exit" : "Fullscreen"}</span>
        </button>
        <button
          type="button"
          className="danger"
          onClick={() => void backend.endSession()}
          title="End session"
        >
          <Icon name="close" /> <span>End</span>
        </button>
      </nav>

      {videoSize && <span className="sr-only">{`${videoSize.w} by ${videoSize.h}`}</span>}

      {toast && (
        <div className="toast" role="status">
          <span>{toast.text}</span>
          {toast.action && (
            <button type="button" onClick={toast.action.run}>
              {toast.action.label}
            </button>
          )}
        </div>
      )}
    </main>
  );
}
