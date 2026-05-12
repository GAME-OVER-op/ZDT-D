use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{fs, path::Path, time::{SystemTime, UNIX_EPOCH}};
use std::os::unix::fs::PermissionsExt;

const API_STATUS_PATH: &str = "/data/adb/modules/ZDT-D/api/status.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiStatus {
    pub version: u32,
    pub state: String,
    pub running: bool,
    pub busy: bool,
    pub start_in_progress: bool,
    pub stop_in_progress: bool,
    pub partial: bool,
    pub services_partial: bool,
    pub daemon_pid: u32,
    pub updated_at_unix: u64,
    pub last_error: String,
}

impl Default for ApiStatus {
    fn default() -> Self {
        Self::new("off", false, false, false, false, false, "")
    }
}

impl ApiStatus {
    pub fn new(
        state: &str,
        running: bool,
        start_in_progress: bool,
        stop_in_progress: bool,
        partial: bool,
        services_partial: bool,
        last_error: &str,
    ) -> Self {
        let state = normalize_state(state).to_string();
        let busy = start_in_progress || stop_in_progress || state == "starting" || state == "stopping";
        Self {
            version: 1,
            state,
            running,
            busy,
            start_in_progress,
            stop_in_progress,
            partial,
            services_partial,
            daemon_pid: std::process::id(),
            updated_at_unix: now_unix(),
            last_error: last_error.trim().to_string(),
        }
    }
}

pub fn read() -> Result<Option<ApiStatus>> {
    let path = Path::new(API_STATUS_PATH);
    if !path.exists() {
        return Ok(None);
    }
    let raw = fs::read_to_string(path).with_context(|| format!("read api status: {}", path.display()))?;
    let st: ApiStatus = serde_json::from_str(&raw).context("parse api status")?;
    Ok(Some(st))
}

pub fn write(status: &ApiStatus) -> Result<()> {
    let path = Path::new(API_STATUS_PATH);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = path.with_file_name("status.json.tmp");
    let json = serde_json::to_string_pretty(status)?;
    fs::write(&tmp, json).with_context(|| format!("write api status tmp: {}", tmp.display()))?;
    let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o644));
    fs::rename(&tmp, path).with_context(|| format!("rename api status: {}", path.display()))?;
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o644));
    Ok(())
}

pub fn write_off() {
    let _ = write(&ApiStatus::new("off", false, false, false, false, false, ""));
}

pub fn write_starting() {
    let _ = write(&ApiStatus::new("starting", true, true, false, false, false, ""));
}

pub fn write_on(partial: bool) {
    if partial {
        let _ = write(&ApiStatus::new("partial", true, false, false, true, true, ""));
    } else {
        let _ = write(&ApiStatus::new("on", true, false, false, false, false, ""));
    }
}

pub fn write_stopping() {
    let _ = write(&ApiStatus::new("stopping", true, false, true, false, false, ""));
}

pub fn write_error_off(message: &str) {
    let _ = write(&ApiStatus::new("error", false, false, false, false, false, message));
}

pub fn runtime_state_for_api(status: Option<&ApiStatus>, fallback: &str) -> String {
    match status.map(|s| normalize_state(&s.state)) {
        Some("on") => "running".to_string(),
        Some("partial") => "partial".to_string(),
        Some("starting") => "starting".to_string(),
        Some("stopping") => "stopping".to_string(),
        Some("off") => "stopped".to_string(),
        Some("error") => "error".to_string(),
        Some(other) if !other.is_empty() => other.to_string(),
        _ => fallback.to_string(),
    }
}

fn normalize_state(raw: &str) -> &str {
    match raw.trim().to_ascii_lowercase().as_str() {
        "on" | "running" | "run" | "started" => "on",
        "partial" | "partially_running" => "partial",
        "starting" | "start" | "busy_start" => "starting",
        "stopping" | "stop" | "busy_stop" => "stopping",
        "off" | "stopped" | "down" | "disabled" => "off",
        "error" | "failed" | "fail" => "error",
        _ => "off",
    }
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
