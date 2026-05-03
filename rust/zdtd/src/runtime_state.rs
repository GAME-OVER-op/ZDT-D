use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{fs, path::{Path, PathBuf}, time::{SystemTime, UNIX_EPOCH}};

const STATE_PATH: &str = "/data/adb/modules/ZDT-D/working_folder/zdtd_runtime_state.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeStateFile {
    pub version: u32,
    pub state: String,
    pub daemon_pid: u32,
    pub updated_at_unix: u64,
    #[serde(default)]
    pub adopted: bool,
    #[serde(default)]
    pub partial: bool,
}

pub fn path() -> PathBuf {
    PathBuf::from(STATE_PATH)
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn unique_tmp_path(target: &Path) -> PathBuf {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let pid = std::process::id();
    let name = target.file_name().and_then(|s| s.to_str()).unwrap_or("runtime_state");
    target.with_file_name(format!(".{name}.{pid}.{ts}.tmp"))
}

fn write_atomic(path: &Path, text: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tmp = unique_tmp_path(path);
    fs::write(&tmp, text).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path).with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

pub fn read() -> Result<Option<RuntimeStateFile>> {
    let p = path();
    if !p.is_file() {
        return Ok(None);
    }
    let raw = fs::read_to_string(&p).with_context(|| format!("read {}", p.display()))?;
    let st: RuntimeStateFile = serde_json::from_str(&raw).with_context(|| format!("parse {}", p.display()))?;
    Ok(Some(st))
}

pub fn write_running(partial: bool, adopted: bool) -> Result<()> {
    let st = RuntimeStateFile {
        version: 1,
        state: "running".to_string(),
        daemon_pid: std::process::id(),
        updated_at_unix: now_unix(),
        adopted,
        partial,
    };
    let text = serde_json::to_string_pretty(&st)?;
    write_atomic(&path(), &text)
}

pub fn clear() {
    let _ = fs::remove_file(path());
}
