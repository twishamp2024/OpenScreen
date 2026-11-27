import { useEffect, useRef, useState } from "react";
import type { Settings } from "../lib/backend";

/** C0 controls and DEL are not allowed in names (PROTOCOL.md §2). */
const hasControl = (s: string) =>
  [...s].some((c) => c.charCodeAt(0) <= 0x1f || c.charCodeAt(0) === 0x7f);

export function validName(name: string): boolean {
  const trimmed = name.trim();
  return [...trimmed].length >= 1 && [...trimmed].length <= 32 && !hasControl(trimmed);
}

export function SettingsDialog(props: {
  settings: Settings;
  onSave: (s: Settings) => Promise<void>;
  onClose: () => void;
}) {
  const [draft, setDraft] = useState(props.settings);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open && typeof d.showModal === "function") d.showModal();
  }, []);

  const nameOk = validName(draft.receiverName);
  const set = <K extends keyof Settings>(k: K, v: Settings[K]) =>
    setDraft((d) => ({ ...d, [k]: v }));

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby="settings-title"
      onCancel={props.onClose}
      open
    >
      <form
        method="dialog"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!nameOk) return;
          setSaving(true);
          try {
            await props.onSave({ ...draft, receiverName: draft.receiverName.trim() });
          } catch (err) {
            setError(String(err));
            setSaving(false);
          }
        }}
      >
        <h2 id="settings-title">Settings</h2>

        <label className="field">
          <span>PC name shown on the phone</span>
          <input
            value={draft.receiverName}
            maxLength={32}
            onChange={(e) => set("receiverName", e.target.value)}
            aria-invalid={!nameOk}
          />
          <small className="muted">1–32 characters. Changing it creates a new QR code.</small>
        </label>

        <label className="field">
          <span>Theme</span>
          <select
            value={draft.theme}
            onChange={(e) => set("theme", e.target.value as Settings["theme"])}
          >
            <option value="system">Same as Windows</option>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
        </label>

        <label className="check">
          <input
            type="checkbox"
            checked={draft.viewerAlwaysOnTop}
            onChange={(e) => set("viewerAlwaysOnTop", e.target.checked)}
          />
          <span>Keep the viewer window on top of other windows</span>
        </label>

        <label className="check">
          <input
            type="checkbox"
            checked={draft.showStats}
            onChange={(e) => set("showStats", e.target.checked)}
          />
          <span>Show streaming statistics in the viewer</span>
        </label>

        {error && (
          <p role="alert" className="banner banner--error">
            {error}
          </p>
        )}

        <div className="dialog__actions">
          <button type="button" onClick={props.onClose}>
            Cancel
          </button>
          <button type="submit" className="primary" disabled={!nameOk || saving}>
            Save
          </button>
        </div>
      </form>
    </dialog>
  );
}
