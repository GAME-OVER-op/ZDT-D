use anyhow::{Context, Result};
use crate::logging;
use serde::{Deserialize, Serialize};
use std::{fs, path::{Path, PathBuf}};
use std::os::unix::fs::PermissionsExt;

pub const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
pub const SETTING_DIR: &str = "/data/adb/modules/ZDT-D/setting";
pub const API_DIR: &str = "/data/adb/modules/ZDT-D/api";

pub fn start_json_path() -> PathBuf {
    Path::new(SETTING_DIR).join("start.json")
}

pub fn iptables_backup_path() -> PathBuf {
    Path::new(SETTING_DIR).join("iptables_backup.rules")
}

pub fn ip6tables_backup_path() -> PathBuf {
    Path::new(SETTING_DIR).join("ip6tables_backup.rules")
}

pub fn api_token_path() -> PathBuf {
    Path::new(API_DIR).join("token")
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StartSettings {
    pub enabled: bool,
    pub first_launch: bool,
}

impl Default for StartSettings {
    fn default() -> Self {
        Self { enabled: false, first_launch: false }
    }
}

pub fn ensure_dirs() -> Result<()> {
    fs::create_dir_all(SETTING_DIR).with_context(|| format!("mkdir {}", SETTING_DIR))?;
    fs::create_dir_all(API_DIR).with_context(|| format!("mkdir {}", API_DIR))?;
    Ok(())
}

pub fn load_start_settings() -> Result<StartSettings> {
    ensure_dirs()?;
    let path = start_json_path();
    if !path.exists() {
        let s = serde_json::to_string_pretty(&StartSettings::default())?;
        fs::write(&path, s).with_context(|| format!("write {}", path.display()))?;
    }
    let raw = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    let st: StartSettings = serde_json::from_str(&raw).with_context(|| format!("parse {}", path.display()))?;
    Ok(st)
}

pub fn save_start_settings(st: &StartSettings) -> Result<()> {
    ensure_dirs()?;
    let path = start_json_path();
    let s = serde_json::to_string_pretty(st)?;
    fs::write(&path, s).with_context(|| format!("write {}", path.display()))?;
    Ok(())
}


fn chmod_private(path: &Path) {
    // Best-effort: do not fail daemon startup if chmod fails.
    match fs::set_permissions(path, fs::Permissions::from_mode(0o600)) {
        Ok(()) => {}
        Err(e) => logging::warn(&format!("chmod 600 failed {}: {e}", path.display())),
    }
}

// --- API token helpers ------------------------------------------------------

/// Backward-compatible name expected by the daemon.
pub fn token_path() -> PathBuf {
    api_token_path()
}

/// Generate a random token as a lowercase hex string.
pub fn generate_token_hex(len_bytes: usize) -> Result<String> {
    use std::io::Read;

    let mut buf = vec![0u8; len_bytes];

    // Prefer /dev/urandom (available on Android).
    let mut f = std::fs::File::open("/dev/urandom").context("open /dev/urandom")?;
    f.read_exact(&mut buf).context("read /dev/urandom")?;

    let mut out = String::with_capacity(len_bytes * 2);
    for b in buf {
        out.push_str(&format!("{:02x}", b));
    }
    Ok(out)
}

/// Read token from file or create a new one and persist it.
pub fn read_or_create_token() -> Result<String> {
    ensure_dirs()?;
    let path = api_token_path();

    if let Ok(s) = fs::read_to_string(&path) {
        let t = s.trim().to_string();
        if !t.is_empty() {
            chmod_private(&path);
            return Ok(t);
        }
    }

    let t = generate_token_hex(32)?;
    fs::write(&path, &t).with_context(|| format!("write {}", path.display()))?;
    chmod_private(&path);
    Ok(t)
}

// --- Compatibility helpers (older call sites expect read_/write_ naming) -----

pub fn read_start_settings() -> Result<StartSettings> {
    load_start_settings()
}

pub fn write_start_settings(st: &StartSettings) -> Result<()> {
    save_start_settings(st)
}

// --- API info file (no token is ever written here) ---------------------------

pub fn api_info_path() -> PathBuf {
    PathBuf::from("/data/adb/modules/ZDT-D/api/info.json")
}

#[derive(Debug, Serialize)]
struct ApiInfo<'a> {
    bind: &'a str,
    token_file: &'a str,
}

pub fn write_api_info(path: &Path, bind: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).ok();
    }
    let info = ApiInfo {
        bind,
        token_file: "/data/adb/modules/ZDT-D/api/token",
    };
    let json = serde_json::to_string_pretty(&info)?;
    fs::write(path, json).with_context(|| format!("write api info: {}", path.display()))
}
