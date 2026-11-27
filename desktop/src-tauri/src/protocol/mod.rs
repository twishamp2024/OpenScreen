//! Wire-protocol definitions (docs/PROTOCOL.md). Must stay in step with
//! `shared/protocol/`; the tests in this module load those files directly.

pub mod constants;
pub mod messages;
pub mod pairing_uri;

#[cfg(test)]
pub(crate) mod shared_fixtures {
    use std::path::PathBuf;

    /// `desktop/src-tauri/../../shared/protocol`
    pub fn dir() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("..")
            .join("shared")
            .join("protocol")
    }

    pub fn json(relative: &str) -> serde_json::Value {
        let path = dir().join(relative);
        let text = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        serde_json::from_str(&text)
            .unwrap_or_else(|e| panic!("bad JSON in {}: {e}", path.display()))
    }
}
