use anyhow::{Context, Result};
use serde::Serialize;
use std::{
    fs,
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use super::{model::AppliedSnapshot, VPN_NETD_DIR, WORKING_ROOT};

pub(super) fn runtime_file(name: &str) -> PathBuf {
    Path::new(VPN_NETD_DIR).join(name)
}

pub(super) fn legacy_working_file(name: &str) -> PathBuf {
    Path::new(WORKING_ROOT).join(name)
}

pub fn applied_snapshot_path() -> PathBuf {
    runtime_file("applied.json")
}

pub fn profiles_tmp_path() -> PathBuf {
    runtime_file("profiles.tmp")
}

pub fn last_ndc_out_path() -> PathBuf {
    runtime_file("last_ndc.out")
}

pub(super) fn ndc_history_path() -> PathBuf {
    runtime_file("ndc_history.log")
}

pub(super) fn ensure_working_dir() -> Result<()> {
    fs::create_dir_all(VPN_NETD_DIR).with_context(|| format!("mkdir {VPN_NETD_DIR}"))?;
    migrate_legacy_runtime_files();
    Ok(())
}

pub(super) fn migrate_legacy_runtime_files() {
    let pairs = [
        ("vpn_netd_applied.json", applied_snapshot_path()),
        ("vpn_netd_profiles.tmp", profiles_tmp_path()),
        ("vpn_netd_last_ndc.out", last_ndc_out_path()),
    ];
    for (legacy, new_path) in pairs {
        let old_path = legacy_working_file(legacy);
        if old_path.is_file() && !new_path.exists() {
            if let Some(parent) = new_path.parent() {
                let _ = fs::create_dir_all(parent);
            }
            let _ = fs::rename(&old_path, &new_path);
        } else if old_path.exists() {
            let _ = fs::remove_file(&old_path);
        }
    }
}

pub(super) fn unique_tmp_path(target: &Path) -> PathBuf {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_nanos();
    let pid = std::process::id();
    let name = target
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("tmp");
    target.with_file_name(format!(".{name}.{pid}.{ts}.tmp"))
}

pub(super) fn write_json_atomic<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tmp = unique_tmp_path(path);
    let text = serde_json::to_string_pretty(value)?;
    fs::write(&tmp, text).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path).with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

pub(super) fn cleanup_runtime_files() {
    let _ = fs::remove_file(applied_snapshot_path());
    let _ = fs::remove_file(profiles_tmp_path());
    let _ = fs::remove_file(last_ndc_out_path());
    let _ = fs::remove_file(ndc_history_path());

    // Remove legacy top-level files left by older ZDT-D builds.
    let _ = fs::remove_file(legacy_working_file("vpn_netd_applied.json"));
    let _ = fs::remove_file(legacy_working_file("vpn_netd_profiles.tmp"));
    let _ = fs::remove_file(legacy_working_file("vpn_netd_last_ndc.out"));

    // Keep the directory if it still contains future files, but remove it when empty.
    let _ = fs::remove_dir(VPN_NETD_DIR);
}

pub(super) fn snapshot_text_is_empty_or_nul(text: &str) -> bool {
    text.chars().all(|c| c == '\0' || c.is_whitespace())
}

pub fn read_applied_snapshot() -> Result<AppliedSnapshot> {
    ensure_working_dir()?;
    let path = applied_snapshot_path();
    if !path.is_file() {
        return Ok(AppliedSnapshot::default());
    }
    let text = match fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) => {
            log::warn!("vpn_netd: applied snapshot is unreadable ({}), treating as not applied: {e}", path.display());
            return Ok(AppliedSnapshot::default());
        }
    };
    if snapshot_text_is_empty_or_nul(&text) {
        log::warn!("vpn_netd: applied snapshot is empty/NUL-filled ({}), treating as not applied", path.display());
        return Ok(AppliedSnapshot::default());
    }
    match serde_json::from_str(&text) {
        Ok(snapshot) => Ok(snapshot),
        Err(e) => {
            log::warn!("vpn_netd: applied snapshot is corrupted ({}), treating as not applied: {e}", path.display());
            Ok(AppliedSnapshot::default())
        }
    }
}
