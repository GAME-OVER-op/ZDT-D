use anyhow::{Context, Result};
use std::fs;

use crate::settings;
use super::model::EnabledJson;

pub fn ensure_layout() -> Result<()> {
    let root = settings::proxyinfo_root_path();
    fs::create_dir_all(&root).with_context(|| format!("mkdir {}", root.display()))?;

    let enabled_path = settings::proxyinfo_enabled_json_path();
    if !enabled_path.exists() {
        crate::common::fs::write_json_pretty_atomic_context(&enabled_path, &EnabledJson::default())?;
    } else {
        // Normalize existing file; invalid JSON falls back to default and gets rewritten.
        let current = match fs::read_to_string(&enabled_path) {
            Ok(raw) => serde_json::from_str::<EnabledJson>(&raw).unwrap_or_default().normalized(),
            Err(_) => EnabledJson::default(),
        };
        crate::common::fs::write_json_pretty_atomic_context(&enabled_path, &current)?;
    }

    crate::common::fs::ensure_empty_file_atomic_context(&settings::proxyinfo_uid_program_path())?;
    crate::common::fs::ensure_empty_file_atomic_context(&settings::proxyinfo_out_program_path())?;
    Ok(())
}

pub fn load_enabled_json() -> Result<EnabledJson> {
    ensure_layout()?;
    let path = settings::proxyinfo_enabled_json_path();
    let raw = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    match serde_json::from_str::<EnabledJson>(&raw) {
        Ok(v) => Ok(v.normalized()),
        Err(_) => Ok(EnabledJson::default()),
    }
}

pub fn save_enabled_value(enabled: bool) -> Result<EnabledJson> {
    ensure_layout()?;
    let v = EnabledJson { enabled }.normalized();
    crate::common::fs::write_json_pretty_atomic_context(&settings::proxyinfo_enabled_json_path(), &v)?;
    Ok(v)
}
