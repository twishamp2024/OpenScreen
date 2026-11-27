import { useEffect, useState } from "react";
import { backend, type AppInfo, type Settings, type Status } from "../lib/backend";
import { applyTheme } from "../lib/theme";
import { Logo } from "../Logo";
import { Icon } from "../ui/Icon";
import { QrCode } from "./QrCode";
import { SettingsDialog } from "./SettingsDialog";

const PHASE_LABEL: Record<Status["phase"], string> = {
  starting: "Starting…",
  waiting: "Waiting for your phone",
  connected: "Phone connected",
  suspended: "Reconnecting…",
  error: "Needs attention",
};

const PHASE_STATE: Record<Status["phase"], string> = {
  starting: "idle",
  waiting: "waiting",
  connected: "connected",
  suspended: "waiting",
  error: "error",
};

const REJECTION_TEXT: Record<string, string> = {
  TOKEN_INVALID: "used an old or wrong QR code",
  TOKEN_EXPIRED: "used an expired QR code",
  SESSION_BUSY: "tried to connect while another phone is connected",
  RATE_LIMITED: "was blocked after too many attempts",
  PROTOCOL_UNSUPPORTED: "runs an incompatible OpenScreen version",
  RESUME_REJECTED: "could not resume a previous session",
};

const END_TEXT: Record<string, string> = {
  "sender-closed": "The phone ended the session.",
  "receiver-closed": "Session ended.",
  timeout: "The phone did not come back within a minute, so the session ended.",
  "resume-rejected": "The session ended after repeated failed reconnection attempts.",
  error: "The session ended because of an error.",
};

/** Wall-clock time that re-renders once a second (state updated from a timer, not during render). */
function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);
  return now;
}

/** Home window: pairing, connection status, device info and settings. */
export function HomeApp() {
  const [status, setStatus] = useState<Status | null>(null);
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [showSettings, setShowSettings] = useState(false);
  const [ipcError, setIpcError] = useState(false);
  const now = useNow();
  const expires = status?.expiresAtMs ?? null;
  const remaining = expires === null ? null : Math.max(0, Math.round((expires - now) / 1000));

  useEffect(() => {
    let unlisten: Array<() => void> = [];
    let cancelled = false;
    (async () => {
      const subs = await Promise.all([
        backend.onStatus(setStatus),
        backend.onSettings((s) => {
          setSettings(s);
          applyTheme(s.theme);
        }),
      ]);
      if (cancelled) return subs.forEach((u) => u());
      unlisten = subs;
      const [s, i, st] = await Promise.all([
        backend.status(),
        backend.appInfo(),
        backend.settings(),
      ]);
      setStatus(s);
      setInfo(i);
      setSettings(st);
      applyTheme(st.theme);
    })().catch(() => setIpcError(true));
    return () => {
      cancelled = true;
      unlisten.forEach((u) => u());
    };
  }, []);

  const rejection = (() => {
    const r = status?.lastRejection;
    if (!r || now - r.atMs > 60_000) return null;
    return `A device at ${r.address} ${REJECTION_TEXT[r.code] ?? `was refused (${r.code})`}.`;
  })();

  if (ipcError) {
    return (
      <main className="home">
        <p role="alert" className="banner banner--error">
          Could not reach the OpenScreen core. Restart the app.
        </p>
      </main>
    );
  }

  const phase = status?.phase ?? "starting";
  const sender = status?.sender ?? null;

  return (
    <main className="home">
      <header className="home__header">
        <Logo size={40} />
        <div className="home__brand">
          <h1>OpenScreen</h1>
          <p className="muted">Mirror your Android phone to this PC over Wi-Fi. View-only.</p>
        </div>
        <p className="status" data-state={PHASE_STATE[phase]} aria-live="polite">
          <span className="status__dot" aria-hidden="true" />
          {PHASE_LABEL[phase]}
        </p>
        <button
          type="button"
          className="icon-button"
          onClick={() => setShowSettings(true)}
          aria-label="Settings"
          disabled={!settings}
        >
          <Icon name="settings" size={20} />
        </button>
      </header>

      {rejection && (
        <p className="banner banner--warn" role="status">
          {rejection}
        </p>
      )}
      {status?.lastEndReason && phase === "waiting" && (
        <p className="banner" role="status">
          {END_TEXT[status.lastEndReason] ?? "Session ended."}
        </p>
      )}

      {phase === "error" ? (
        <section className="card error-card" aria-labelledby="err-title">
          <h2 id="err-title">OpenScreen can't accept connections</h2>
          <p>{status?.error}</p>
          <button type="button" className="primary" onClick={() => void backend.restartServer()}>
            <Icon name="refresh" /> Refresh network
          </button>
        </section>
      ) : sender ? (
        <section className="card device" aria-labelledby="device-title">
          <div className="device__icon">
            <Icon name="phone" size={28} />
          </div>
          <div className="device__body">
            <h2 id="device-title">
              {sender.device.manufacturer} {sender.device.model}
            </h2>
            <p className="muted">
              Android {sender.device.androidVersion} · {sender.address} · app{" "}
              {sender.device.appVersion}
            </p>
            <dl className="facts">
              <dt>Screen</dt>
              <dd>
                {sender.display.width}×{sender.display.height}
              </dd>
              <dt>Stream</dt>
              <dd>
                {sender.video
                  ? `${sender.video.width}×${sender.video.height} · ${sender.video.fps} FPS · ${sender.video.codec}`
                  : sender.streamState === "stopped"
                    ? "Stopped on phone"
                    : "Waiting for Start streaming on the phone"}
              </dd>
            </dl>
            {phase === "suspended" && (
              <p className="banner banner--warn">
                Connection interrupted — waiting for the phone to come back…
              </p>
            )}
          </div>
          <button type="button" className="danger" onClick={() => void backend.endSession()}>
            End session
          </button>
        </section>
      ) : (
        <section className="card pairing" aria-labelledby="pairing-title">
          <div className="qr">
            {status?.pairingUri ? (
              <QrCode value={status.pairingUri} />
            ) : (
              <div className="qr__img qr__img--empty">
                <span className="spinner" />
              </div>
            )}
            {remaining !== null && (
              <p className="muted qr__timer">
                New code in {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}
              </p>
            )}
          </div>
          <div className="pairing__steps">
            <h2 id="pairing-title">Pair your phone</h2>
            <ol>
              <li>Connect your phone to the same Wi-Fi network as this PC.</li>
              <li>
                Open <strong>OpenScreen</strong> on your phone and tap{" "}
                <strong>Scan QR code on PC</strong>.
              </li>
              <li>
                Point the camera at this code. Each code works once and expires after 5 minutes.
              </li>
              <li>
                Tap <strong>Start streaming</strong> on the phone and choose{" "}
                <strong>Entire screen</strong>.
              </li>
            </ol>
            <details className="help">
              <summary>
                <Icon name="help" /> Phone can't connect?
              </summary>
              <ul>
                <li>
                  Guest, hotel and campus Wi-Fi often block devices from reaching each other. Use
                  your main network.
                </li>
                <li>
                  When Windows asks, allow OpenScreen on <strong>Private networks</strong>, and make
                  sure this network's profile is set to Private.
                </li>
                <li>Turn off VPNs on both devices.</li>
                <li>
                  The phone's address should start like this PC's:{" "}
                  {status?.interfaces[0]?.ip.split(".").slice(0, 3).join(".") ?? "…"}.x
                </li>
              </ul>
            </details>
          </div>
        </section>
      )}

      <footer className="home__footer">
        <div className="footer__net">
          <Icon name="wifi" />
          {status?.interfaces.length ? (
            <span>
              {status.interfaces.map((i) => `${i.ip} (${i.name})`).join(" · ")} · port {status.port}
            </span>
          ) : (
            <span className="muted">
              {phase === "starting" ? "Looking for networks…" : "No network"}
            </span>
          )}
          <button
            type="button"
            className="link"
            onClick={() => void backend.restartServer()}
            disabled={phase === "connected"}
          >
            Refresh network
          </button>
        </div>
        <p className="muted">
          <Icon name="shield" size={14} /> End-to-end encrypted on your local network · no cloud, no
          account
          {info && ` · v${info.appVersion} · protocol v${info.protocolVersion}`}
        </p>
      </footer>

      {showSettings && settings && (
        <SettingsDialog
          settings={settings}
          onClose={() => setShowSettings(false)}
          onSave={async (next) => {
            const saved = await backend.saveSettings(next);
            setSettings(saved);
            applyTheme(saved.theme);
            setShowSettings(false);
          }}
        />
      )}
    </main>
  );
}
