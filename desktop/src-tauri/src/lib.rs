//! OpenScreen receiver core (docs/ARCHITECTURE.md §2.2).
//!
//! Rust owns everything that touches the network or the OS: interface
//! discovery, the TLS identity, pairing tokens, the signaling server and the
//! session rules. The webviews only render UI and run the WebRTC receiver;
//! every signaling message they send is validated here first.

pub mod hub;
pub mod net;
pub mod pairing;
pub mod protocol;
pub mod settings;
pub mod signaling;
pub mod tls;

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder, WindowEvent};

use crate::hub::{Hub, HubConfig, ServerEvent};
use crate::net::LocalInterface;
use crate::pairing::TokenManager;
use crate::protocol::messages::{Capabilities, DeviceInfo, DisplayInfo, Message, VideoInfo};
use crate::protocol::pairing_uri::PairingInfo;
use crate::settings::Settings;
use crate::signaling::{EventSink, ServerConfig, SignalingServer};

/// Must be identical for every webview in the app: WebView2 refuses to create
/// a second webview in the same environment with different arguments. The
/// first three are Tauri's defaults (replaced when this is set); the last
/// stops Chromium hiding LAN IPs behind mDNS names in ICE candidates.
pub const BROWSER_ARGS: &str =
    "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection,WebRtcHideLocalIpsWithMdns";

const STATUS_EVENT: &str = "openscreen://status";
const SIGNAL_EVENT: &str = "openscreen://signal";
const VIEWER: &str = "viewer";

/// Returned by the `app_info` command; mirrors `AppInfo` in `src/lib/backend.ts`.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub app_version: String,
    pub protocol_version: u32,
    pub default_port: u16,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SenderInfo {
    pub device: DeviceInfo,
    pub display: DisplayInfo,
    pub capabilities: Capabilities,
    pub address: String,
    pub video: Option<VideoInfo>,
    pub stream_state: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Rejection {
    pub code: String,
    pub address: String,
    pub at_ms: u64,
}

/// Snapshot pushed to every window on change (`openscreen://status`); mirrors `Status` in `src/lib/backend.ts`.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    /// "starting" | "waiting" | "connected" | "suspended" | "error"
    pub phase: String,
    pub pairing_uri: Option<String>,
    pub expires_at_ms: Option<u64>,
    pub port: Option<u16>,
    pub interfaces: Vec<LocalInterface>,
    pub sender: Option<SenderInfo>,
    pub error: Option<String>,
    pub last_rejection: Option<Rejection>,
    pub last_end_reason: Option<String>,
    pub receiver_name: String,
}

struct Viewer {
    ready: bool,
    queue: Vec<Message>,
}

struct AppState {
    server: Mutex<Option<Arc<SignalingServer>>>,
    status: Mutex<Status>,
    viewer: Mutex<Viewer>,
    settings: Mutex<Settings>,
    settings_path: PathBuf,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn publish(app: &AppHandle) {
    let snapshot = app.state::<AppState>().status.lock().unwrap().clone();
    let _ = app.emit(STATUS_EVENT, snapshot);
}

fn update(app: &AppHandle, f: impl FnOnce(&mut Status)) {
    f(&mut app.state::<AppState>().status.lock().unwrap());
    publish(app);
}

// ── server lifecycle ───────────────────────────────────────────────

async fn start_server(app: AppHandle) {
    let state = app.state::<AppState>();
    if let Some(old) = state.server.lock().unwrap().take() {
        old.shutdown();
    }
    let name = state.settings.lock().unwrap().receiver_name.clone();
    update(&app, |s| {
        *s = Status {
            phase: "starting".into(),
            pairing_uri: None,
            expires_at_ms: None,
            port: None,
            interfaces: vec![],
            sender: None,
            error: None,
            last_rejection: None,
            last_end_reason: None,
            receiver_name: name.clone(),
        }
    });

    let interfaces = match net::discover() {
        Ok(list) if !list.is_empty() => list,
        Ok(_) => return fail(&app, "No Wi-Fi or Ethernet connection with a private (home or office) network address was found. Connect this PC to the same network as your phone, then click Refresh network."),
        Err(e) => return fail(&app, &format!("Could not read network adapters: {e}")),
    };
    let ips: Vec<_> = interfaces.iter().map(|i| i.ip).collect();
    let tls = match tls::generate(&ips) {
        Ok(t) => t,
        Err(e) => {
            return fail(
                &app,
                &format!("Could not create the encryption certificate: {e}"),
            )
        }
    };

    let sink_app = app.clone();
    let sink: EventSink = Arc::new(move |event| on_server_event(&sink_app, event));
    let hub = Hub::new(HubConfig::new(name), TokenManager::default());
    match SignalingServer::start(ServerConfig::new(ips), hub, tls, sink).await {
        Ok(server) => {
            let port = server.port;
            let bound: Vec<_> = interfaces
                .into_iter()
                .filter(|i| server.hosts.contains(&i.ip))
                .collect();
            *state.server.lock().unwrap() = Some(Arc::new(server));
            update(&app, |s| {
                s.port = Some(port);
                s.interfaces = bound;
            });
            refresh_pairing(&app);
        }
        Err(e) => fail(
            &app,
            &format!("{e}. Another program may be using ports 47820–47829."),
        ),
    }
}

fn fail(app: &AppHandle, message: &str) {
    update(app, |s| {
        s.phase = "error".into();
        s.error = Some(message.to_owned());
    });
}

fn server(app: &AppHandle) -> Option<Arc<SignalingServer>> {
    app.state::<AppState>().server.lock().unwrap().clone()
}

/// Rebuilds the QR URI from the server's current token.
fn refresh_pairing(app: &AppHandle) {
    let Some(server) = server(app) else { return };
    let name = app
        .state::<AppState>()
        .settings
        .lock()
        .unwrap()
        .receiver_name
        .clone();
    let token = server.current_token();
    update(app, |s| match token {
        Some((token, expires)) => {
            let uri = PairingInfo {
                version: protocol::constants::PROTOCOL_VERSION,
                hosts: server.hosts.clone(),
                port: server.port,
                token,
                fingerprint: server.fingerprint.clone(),
                name: Some(name),
            }
            .to_uri();
            // Debug builds print the link so `cargo run --example probe` can pair without a camera.
            #[cfg(debug_assertions)]
            println!("[openscreen] pairing link: {uri}");
            s.pairing_uri = Some(uri);
            s.expires_at_ms = Some(
                now_ms()
                    + expires
                        .saturating_duration_since(std::time::Instant::now())
                        .as_millis() as u64,
            );
            if s.sender.is_none() {
                s.phase = "waiting".into();
            }
        }
        None => {
            s.pairing_uri = None;
            s.expires_at_ms = None;
        }
    });
}

fn on_server_event(app: &AppHandle, event: ServerEvent) {
    match event {
        ServerEvent::Pairing { .. } => refresh_pairing(app),
        ServerEvent::SenderConnected {
            device,
            display,
            capabilities,
            address,
        } => {
            {
                let state = app.state::<AppState>();
                let mut v = state.viewer.lock().unwrap();
                v.ready = false;
                v.queue.clear();
            }
            update(app, |s| {
                s.phase = "connected".into();
                s.pairing_uri = None;
                s.expires_at_ms = None;
                s.last_end_reason = None;
                s.sender = Some(SenderInfo {
                    device,
                    display: display.clone(),
                    capabilities,
                    address,
                    video: None,
                    stream_state: "negotiating".into(),
                });
            });
            let app = app.clone();
            tauri::async_runtime::spawn(async move { open_viewer(&app, &display) });
        }
        ServerEvent::Signal { message } => {
            if let Message::StreamState { state, video, .. } = &message {
                update(app, |s| {
                    if let Some(sender) = s.sender.as_mut() {
                        sender.stream_state = state.clone();
                        sender.video = video.clone();
                    }
                });
            }
            let state = app.state::<AppState>();
            let mut v = state.viewer.lock().unwrap();
            if v.ready {
                let _ = app.emit_to(VIEWER, SIGNAL_EVENT, &message);
            } else {
                v.queue.push(message); // the viewer may still be loading
            }
        }
        ServerEvent::Suspended => update(app, |s| s.phase = "suspended".into()),
        ServerEvent::Resumed => update(app, |s| s.phase = "connected".into()),
        ServerEvent::Ended { reason } => {
            update(app, |s| {
                s.phase = "waiting".into();
                s.sender = None;
                s.last_end_reason = Some(reason);
            });
            if let Some(w) = app.get_webview_window(VIEWER) {
                let _ = w.destroy();
            }
            refresh_pairing(app);
        }
        ServerEvent::AuthRejected { code, address } => update(app, |s| {
            s.last_rejection = Some(Rejection {
                code,
                address,
                at_ms: now_ms(),
            })
        }),
    }
}

/// Opens the dedicated viewer window sized to the phone's aspect ratio.
fn open_viewer(app: &AppHandle, display: &DisplayInfo) {
    if let Some(w) = app.get_webview_window(VIEWER) {
        let _ = w.set_focus();
        return;
    }
    let (w, h) = viewer_size(app, display.width as f64, display.height as f64);
    let on_top = app
        .state::<AppState>()
        .settings
        .lock()
        .unwrap()
        .viewer_always_on_top;
    let title = app
        .state::<AppState>()
        .status
        .lock()
        .unwrap()
        .sender
        .as_ref()
        .map(|s| format!("{} — OpenScreen", s.device.model))
        .unwrap_or_else(|| "OpenScreen".into());
    let result =
        WebviewWindowBuilder::new(app, VIEWER, WebviewUrl::App("index.html#viewer".into()))
            .title(title)
            .inner_size(w, h)
            .min_inner_size(240.0, 240.0)
            .resizable(true)
            .maximizable(true)
            .always_on_top(on_top)
            .center()
            .additional_browser_args(BROWSER_ARGS)
            .build();
    if let Err(e) = result {
        log::error!("could not open viewer: {e}");
        if let Some(server) = server(app) {
            server.end_session("error");
        }
    }
}

/// Fits the phone's aspect ratio into ~85 % of the primary monitor's height.
fn viewer_size(app: &AppHandle, width: f64, height: f64) -> (f64, f64) {
    let (max_w, max_h) = app
        .primary_monitor()
        .ok()
        .flatten()
        .map(|m| {
            let size = m.size().to_logical::<f64>(m.scale_factor());
            (size.width * 0.85, size.height * 0.85)
        })
        .unwrap_or((1280.0, 800.0));
    let toolbar = 48.0;
    let scale = ((max_h - toolbar) / height).min(max_w / width).min(1.0);
    (
        (width * scale).round().max(240.0),
        (height * scale + toolbar).round().max(240.0),
    )
}

// ── commands ───────────────────────────────────────────────────────

#[tauri::command]
fn app_info() -> AppInfo {
    AppInfo {
        app_version: env!("CARGO_PKG_VERSION").to_owned(),
        protocol_version: protocol::constants::PROTOCOL_VERSION,
        default_port: protocol::constants::DEFAULT_PORT,
    }
}

#[tauri::command]
fn get_status(state: tauri::State<'_, AppState>) -> Status {
    state.status.lock().unwrap().clone()
}

/// The viewer calls this once its listeners are attached; returns signals that arrived earlier.
#[tauri::command]
fn viewer_ready(state: tauri::State<'_, AppState>) -> Vec<Message> {
    let mut v = state.viewer.lock().unwrap();
    v.ready = true;
    std::mem::take(&mut v.queue)
}

#[tauri::command]
fn signal_send(app: AppHandle, message: Message) -> Result<(), String> {
    server(&app).ok_or("not running")?.send_from_viewer(message)
}

#[tauri::command]
fn end_session(app: AppHandle) {
    if let Some(s) = server(&app) {
        s.end_session("receiver-closed");
    }
}

#[tauri::command]
async fn restart_server(app: AppHandle) {
    start_server(app).await;
}

#[tauri::command]
fn get_settings(state: tauri::State<'_, AppState>) -> Settings {
    state.settings.lock().unwrap().clone()
}

#[tauri::command]
async fn set_settings(app: AppHandle, settings: Settings) -> Result<Settings, String> {
    let settings = settings.sanitized();
    let (path, name_changed) = {
        let state = app.state::<AppState>();
        let mut current = state.settings.lock().unwrap();
        let changed = current.receiver_name != settings.receiver_name;
        *current = settings.clone();
        (state.settings_path.clone(), changed)
    };
    settings.save(&path).map_err(|e| e.to_string())?;
    let _ = app.emit("openscreen://settings", &settings);
    // The name is part of the QR code; restart only when no phone is connected.
    if name_changed && !server(&app).is_some_and(|s| s.has_session()) {
        start_server(app).await;
    } else {
        update(&app, |s| s.receiver_name = settings.receiver_name.clone());
    }
    Ok(settings)
}

/// Saves a PNG (base64) from the viewer into Pictures\OpenScreen and returns the path.
#[tauri::command]
fn save_screenshot(app: AppHandle, png_base64: String, stem: String) -> Result<String, String> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(png_base64.as_bytes())
        .map_err(|e| e.to_string())?;
    if bytes.len() > 64 * 1024 * 1024 || !bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
        return Err("not a PNG image".into());
    }
    let dir = app
        .path()
        .picture_dir()
        .map_err(|e| e.to_string())?
        .join("OpenScreen");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = settings::screenshot_path(&dir, &stem);
    std::fs::write(&path, bytes).map_err(|e| e.to_string())?;
    Ok(path.display().to_string())
}

#[tauri::command]
fn open_screenshots_folder(app: AppHandle) -> Result<(), String> {
    let dir = app
        .path()
        .picture_dir()
        .map_err(|e| e.to_string())?
        .join("OpenScreen");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::process::Command::new("explorer.exe")
        .arg(dir)
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let settings_path = app.path().app_config_dir()?.join("settings.json");
            let settings = Settings::load(&settings_path);
            app.manage(AppState {
                server: Mutex::new(None),
                status: Mutex::new(Status {
                    phase: "starting".into(),
                    pairing_uri: None,
                    expires_at_ms: None,
                    port: None,
                    interfaces: vec![],
                    sender: None,
                    error: None,
                    last_rejection: None,
                    last_end_reason: None,
                    receiver_name: settings.receiver_name.clone(),
                }),
                viewer: Mutex::new(Viewer {
                    ready: false,
                    queue: vec![],
                }),
                settings: Mutex::new(settings),
                settings_path,
            });
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(start_server(handle));
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::Destroyed = event {
                let app = window.app_handle();
                match window.label() {
                    // Closing the viewer ends the session; the phone gets `bye`.
                    VIEWER => {
                        if let Some(s) = server(app) {
                            s.end_session("receiver-closed");
                        }
                    }
                    "main" => {
                        if let Some(s) = server(app) {
                            s.shutdown();
                        }
                        // Give the bye a moment to flush before the process exits.
                        std::thread::sleep(Duration::from_millis(150));
                        app.exit(0);
                    }
                    _ => {}
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            app_info,
            get_status,
            viewer_ready,
            signal_send,
            end_session,
            restart_server,
            get_settings,
            set_settings,
            save_screenshot,
            open_screenshots_folder,
        ])
        .run(tauri::generate_context!())
        .expect("error while running OpenScreen");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_info_serializes_in_camel_case() {
        let json = serde_json::to_value(app_info()).unwrap();
        assert_eq!(json["protocolVersion"], 1);
        assert_eq!(json["defaultPort"], 47820);
        assert_eq!(json["appVersion"], env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn browser_args_match_tauri_conf() {
        let conf: serde_json::Value =
            serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        assert_eq!(
            conf["app"]["windows"][0]["additionalBrowserArgs"],
            BROWSER_ARGS
        );
    }

    #[test]
    fn server_events_serialize_with_kind_tags() {
        let e = serde_json::to_value(ServerEvent::Ended {
            reason: "timeout".into(),
        })
        .unwrap();
        assert_eq!(e, serde_json::json!({"kind": "ended", "reason": "timeout"}));
    }
}
