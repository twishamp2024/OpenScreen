//! Persisted receiver settings (`%APPDATA%\io.github.openscreen.receiver\settings.json`).

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::protocol::messages::is_display_name;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    /// Shown on the phone. Defaults to a generic name rather than the computer
    /// name, which often contains a person's name.
    pub receiver_name: String,
    /// "system" | "light" | "dark"
    pub theme: String,
    pub viewer_always_on_top: bool,
    pub show_stats: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            receiver_name: "Windows PC".to_owned(),
            theme: "system".to_owned(),
            viewer_always_on_top: false,
            show_stats: true,
        }
    }
}

impl Settings {
    pub fn sanitized(mut self) -> Self {
        self.receiver_name = self.receiver_name.trim().to_owned();
        if !is_display_name(&self.receiver_name) {
            self.receiver_name = Settings::default().receiver_name;
        }
        if !["system", "light", "dark"].contains(&self.theme.as_str()) {
            self.theme = "system".to_owned();
        }
        self
    }

    pub fn load(path: &Path) -> Self {
        std::fs::read_to_string(path)
            .ok()
            .and_then(|t| serde_json::from_str::<Settings>(&t).ok())
            .unwrap_or_default()
            .sanitized()
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        std::fs::write(
            path,
            serde_json::to_vec_pretty(self).map_err(std::io::Error::other)?,
        )
    }
}

/// Screenshot file name from a UI-provided stem, restricted to safe characters.
pub fn screenshot_path(dir: &Path, stem: &str) -> PathBuf {
    let clean: String = stem
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, ' ' | '-' | '_'))
        .take(80)
        .collect();
    let clean = if clean.trim().is_empty() {
        "OpenScreen".to_owned()
    } else {
        clean.trim().to_owned()
    };
    let mut path = dir.join(format!("{clean}.png"));
    let mut n = 2;
    while path.exists() {
        path = dir.join(format!("{clean} ({n}).png"));
        n += 1;
    }
    path
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_values_fall_back_to_defaults() {
        let s = Settings {
            receiver_name: "  \n ".into(),
            theme: "neon".into(),
            ..Default::default()
        }
        .sanitized();
        assert_eq!(s.receiver_name, "Windows PC");
        assert_eq!(s.theme, "system");
        let long = Settings {
            receiver_name: "x".repeat(40),
            ..Default::default()
        }
        .sanitized();
        assert_eq!(long.receiver_name, "Windows PC");
    }

    #[test]
    fn round_trips_through_disk_and_tolerates_missing_fields() {
        let dir = std::env::temp_dir().join(format!(
            "openscreen-test-{}",
            crate::pairing::new_session_id()
        ));
        let path = dir.join("settings.json");
        assert_eq!(Settings::load(&path), Settings::default());
        let s = Settings {
            receiver_name: "Study PC".into(),
            theme: "dark".into(),
            viewer_always_on_top: true,
            show_stats: false,
        };
        s.save(&path).unwrap();
        assert_eq!(Settings::load(&path), s);
        std::fs::write(&path, r#"{"receiverName":"Lab"}"#).unwrap();
        assert_eq!(Settings::load(&path).receiver_name, "Lab");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn screenshot_names_are_sanitized_and_unique() {
        let dir = std::env::temp_dir().join(format!(
            "openscreen-shot-{}",
            crate::pairing::new_session_id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let p = screenshot_path(&dir, "..\\..\\evil:name");
        assert_eq!(p.parent().unwrap(), dir);
        assert_eq!(p.file_name().unwrap(), "evilname.png");
        std::fs::write(&p, b"x").unwrap();
        assert_eq!(
            screenshot_path(&dir, "evilname").file_name().unwrap(),
            "evilname (2).png"
        );
        std::fs::remove_dir_all(dir).unwrap();
    }
}
