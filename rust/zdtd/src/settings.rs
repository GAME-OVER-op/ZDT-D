use anyhow::{Context, Result};
use crate::{
    programs::registry::{self as program, ProgramId, StateKind},
    logging,
};
use serde::{Deserialize, Serialize};
use std::{fs, path::{Path, PathBuf}};
use std::os::unix::fs::PermissionsExt;

pub const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
pub const SETTING_DIR: &str = "/data/adb/modules/ZDT-D/setting";
pub const API_DIR: &str = "/data/adb/modules/ZDT-D/api";

// IMPORTANT: all modules MUST use this single shared SHA tracker file.
// Do not create per-module files like `blockedquic.flag.sha256`.
// This is intentionally centralized so both humans and other AIs see the rule in one place.
pub const SHARED_SHA_FLAG_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/flag.sha256";

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

pub fn api_setting_json_path() -> PathBuf {
    Path::new(SETTING_DIR).join("setting.json")
}


pub fn proxyinfo_root_path() -> PathBuf {
    program::working_program_root(ProgramId::ProxyInfo)
}

pub fn proxyinfo_enabled_json_path() -> PathBuf {
    proxyinfo_root_path().join("enabled.json")
}

pub fn proxyinfo_uid_program_path() -> PathBuf {
    proxyinfo_root_path().join("uid_program")
}

pub fn proxyinfo_out_program_path() -> PathBuf {
    proxyinfo_root_path().join("out_program")
}

pub fn blockedquic_root_path() -> PathBuf {
    program::working_program_root(ProgramId::BlockedQuic)
}

pub fn blockedquic_enabled_json_path() -> PathBuf {
    blockedquic_root_path().join("enabled.json")
}

pub fn blockedquic_uid_program_path() -> PathBuf {
    blockedquic_root_path().join("uid_program")
}

pub fn blockedquic_out_program_path() -> PathBuf {
    blockedquic_root_path().join("out_program")
}

pub fn tor_root_path() -> PathBuf {
    program::working_program_root(ProgramId::Tor)
}

pub fn tor_enabled_json_path() -> PathBuf {
    tor_root_path().join("enabled.json")
}

pub fn tor_setting_json_path() -> PathBuf {
    tor_root_path().join("setting.json")
}

pub fn tor_torrc_path() -> PathBuf {
    tor_root_path().join("torrc")
}

pub fn tor_uid_program_path() -> PathBuf {
    tor_root_path().join("app/uid/user_program")
}

pub fn tor_out_program_path() -> PathBuf {
    tor_root_path().join("app/out/user_program")
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ProtectorMode {
    Off,
    On,
    Auto,
}

impl Default for ProtectorMode {
    fn default() -> Self {
        Self::Off
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiSettings {
    #[serde(default, alias = "mode", alias = "protection_mode")]
    pub protector_mode: ProtectorMode,
    #[serde(default)]
    pub hotspot_t2s_enabled: bool,
    #[serde(default)]
    pub hotspot_mode: String,
    #[serde(default)]
    pub hotspot_program: String,
    #[serde(default)]
    pub hotspot_profile: String,
    #[serde(default)]
    pub hotspot_t2s_target: String,
    #[serde(default)]
    pub hotspot_t2s_singbox_profile: String,
    #[serde(default)]
    pub hotspot_t2s_wireproxy_profile: String,
    #[serde(default)]
    pub hotspot_t2s_capture_all: bool,
    #[serde(default)]
    pub captive_portal_enabled: bool,
    #[serde(default)]
    pub allow_loopback_redirect: bool,
    #[serde(default)]
    pub tproxy_enabled: bool,
    #[serde(default)]
    pub selinux_permissive_enabled: bool,
    #[serde(default)]
    pub ip_forward_enabled: bool,
}

impl Default for ApiSettings {
    fn default() -> Self {
        Self {
            protector_mode: ProtectorMode::Off,
            hotspot_t2s_enabled: false,
            hotspot_mode: "proxy".to_string(),
            hotspot_program: String::new(),
            hotspot_profile: String::new(),
            hotspot_t2s_target: String::new(),
            hotspot_t2s_singbox_profile: String::new(),
            hotspot_t2s_wireproxy_profile: String::new(),
            hotspot_t2s_capture_all: false,
            captive_portal_enabled: false,
            allow_loopback_redirect: false,
            tproxy_enabled: false,
            selinux_permissive_enabled: false,
            ip_forward_enabled: false,
        }
    }
}

impl ApiSettings {
    pub fn normalize(&mut self) {
        self.hotspot_mode = normalize_hotspot_mode(&self.hotspot_mode);
        self.hotspot_program = self.hotspot_program.trim().to_ascii_lowercase();
        self.hotspot_profile = self.hotspot_profile.trim().to_string();
        self.hotspot_t2s_target = normalize_hotspot_t2s_target(&self.hotspot_t2s_target);
        self.hotspot_t2s_singbox_profile = self.hotspot_t2s_singbox_profile.trim().to_string();
        self.hotspot_t2s_wireproxy_profile = self.hotspot_t2s_wireproxy_profile.trim().to_string();

        // Backward migration from the old proxy-only hotspot fields.
        if self.hotspot_program.is_empty() && !self.hotspot_t2s_target.is_empty() {
            self.hotspot_mode = "proxy".to_string();
            self.hotspot_program = self.hotspot_t2s_target.clone();
            self.hotspot_profile = match ProgramId::parse(&self.hotspot_program) {
                Some(ProgramId::SingBox) => self.hotspot_t2s_singbox_profile.clone(),
                Some(ProgramId::WireProxy) => self.hotspot_t2s_wireproxy_profile.clone(),
                _ => String::new(),
            };
        }

        if !self.hotspot_t2s_enabled {
            self.hotspot_program.clear();
            self.hotspot_profile.clear();
            self.hotspot_t2s_target.clear();
            self.hotspot_t2s_singbox_profile.clear();
            self.hotspot_t2s_wireproxy_profile.clear();
            return;
        }

        if self.hotspot_mode == "vpn" {
            self.hotspot_program = normalize_hotspot_vpn_program(&self.hotspot_program);
            if self.hotspot_program.is_empty() {
                self.hotspot_profile.clear();
            }
            // Proxy compatibility fields must stay empty in VPN mode so old proxy launch paths
            // cannot accidentally build t2s hotspot REDIRECT rules.
            self.hotspot_t2s_target.clear();
            self.hotspot_t2s_singbox_profile.clear();
            self.hotspot_t2s_wireproxy_profile.clear();
            return;
        }

        self.hotspot_mode = "proxy".to_string();
        self.hotspot_program = normalize_hotspot_t2s_target(&self.hotspot_program);
        if self.hotspot_program.is_empty() {
            self.hotspot_profile.clear();
            self.hotspot_t2s_target.clear();
            self.hotspot_t2s_singbox_profile.clear();
            self.hotspot_t2s_wireproxy_profile.clear();
            return;
        }

        if self.hotspot_program == ProgramId::OperaProxy.canonical() {
            self.hotspot_profile.clear();
        }
        self.hotspot_t2s_target = self.hotspot_program.clone();
        self.hotspot_t2s_singbox_profile = if self.hotspot_program == ProgramId::SingBox.canonical() {
            self.hotspot_profile.clone()
        } else {
            String::new()
        };
        self.hotspot_t2s_wireproxy_profile = if self.hotspot_program == ProgramId::WireProxy.canonical() {
            self.hotspot_profile.clone()
        } else {
            String::new()
        };
    }

    pub fn hotspot_enabled(&self) -> bool {
        self.hotspot_t2s_enabled
    }

    pub fn hotspot_mode_proxy(&self) -> bool {
        self.hotspot_t2s_enabled && self.hotspot_mode == "proxy"
    }

    pub fn hotspot_mode_vpn(&self) -> bool {
        self.hotspot_t2s_enabled && self.hotspot_mode == "vpn"
    }

    pub fn hotspot_proxy_for(&self, program: &str) -> bool {
        self.hotspot_mode_proxy() && self.hotspot_program == normalize_hotspot_t2s_target(program)
    }

    pub fn hotspot_proxy_profile_for(&self, program: &str) -> Option<&str> {
        if !self.hotspot_proxy_for(program) {
            return None;
        }
        let profile = self.hotspot_profile.trim();
        if profile.is_empty() { None } else { Some(profile) }
    }

    pub fn hotspot_vpn_for(&self, program: &str) -> bool {
        self.hotspot_mode_vpn() && self.hotspot_program == normalize_hotspot_vpn_program(program)
    }

    pub fn hotspot_vpn_profile_for(&self, program: &str) -> Option<&str> {
        if !self.hotspot_vpn_for(program) {
            return None;
        }
        let profile = self.hotspot_profile.trim();
        if profile.is_empty() { None } else { Some(profile) }
    }

    pub fn hotspot_vpn_selection(&self) -> Option<(&str, &str)> {
        if !self.hotspot_mode_vpn() {
            return None;
        }
        let program = self.hotspot_program.trim();
        let profile = self.hotspot_profile.trim();
        if program.is_empty() || profile.is_empty() { None } else { Some((program, profile)) }
    }

    pub fn hotspot_t2s_for_operaproxy(&self) -> bool {
        self.hotspot_proxy_for(ProgramId::OperaProxy.canonical())
    }

    pub fn hotspot_t2s_for_singbox(&self) -> bool {
        self.hotspot_proxy_for(ProgramId::SingBox.canonical())
    }

    pub fn hotspot_t2s_singbox_profile(&self) -> Option<&str> {
        self.hotspot_proxy_profile_for(ProgramId::SingBox.canonical())
    }

    pub fn hotspot_t2s_for_wireproxy(&self) -> bool {
        self.hotspot_proxy_for(ProgramId::WireProxy.canonical())
    }

    pub fn hotspot_t2s_wireproxy_profile(&self) -> Option<&str> {
        self.hotspot_proxy_profile_for(ProgramId::WireProxy.canonical())
    }
}

fn normalize_hotspot_mode(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "vpn" => "vpn".to_string(),
        _ => "proxy".to_string(),
    }
}

fn normalize_hotspot_t2s_target(raw: &str) -> String {
    ProgramId::parse(raw)
        .filter(|id| id.descriptor().capabilities.hotspot_proxy)
        .map(|id| id.canonical().to_string())
        .unwrap_or_default()
}

fn normalize_hotspot_vpn_program(raw: &str) -> String {
    ProgramId::parse(raw)
        .filter(|id| id.descriptor().capabilities.hotspot_vpn)
        .map(|id| id.canonical().to_string())
        .unwrap_or_default()
}

pub fn load_api_settings() -> Result<ApiSettings> {
    ensure_dirs()?;
    let path = api_setting_json_path();
    if !path.exists() {
        let s = serde_json::to_string_pretty(&ApiSettings::default())?;
        fs::write(&path, s).with_context(|| format!("write {}", path.display()))?;
    }
    let raw = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    let mut st: ApiSettings = serde_json::from_str(&raw).with_context(|| format!("parse {}", path.display()))?;
    st.normalize();
    let normalized = serde_json::to_string_pretty(&st)?;
    if raw.trim() != normalized.trim() {
        fs::write(&path, &normalized).with_context(|| format!("write {}", path.display()))?;
    }
    Ok(st)
}

pub fn save_api_settings(st: &ApiSettings) -> Result<()> {
    ensure_dirs()?;
    let path = api_setting_json_path();
    let mut normalized = st.clone();
    normalized.normalize();
    let s = serde_json::to_string_pretty(&normalized)?;
    fs::write(&path, s).with_context(|| format!("write {}", path.display()))?;
    Ok(())
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

pub fn working_root_path() -> PathBuf {
    program::working_root()
}

pub fn working_program_root_path(program: &str) -> PathBuf {
    working_root_path().join(program)
}

pub fn ensure_minimal_program_layouts() -> Result<()> {
    const PROFILES_DEFAULT_JSON: &str = r#"{
  "profiles": {}
}
"#;
    const ENABLED_FALSE_JSON: &str = r#"{"enabled":false}
"#;
    // Written as a real bool: every setting file zdtd creates uses true/false. Readers still
    // accept the legacy 0/1 form for files created by older versions.
    const PROXYINFO_ENABLED_JSON: &str = r#"{
  "enabled": false
}
"#;
    const TOR_SETTING_JSON: &str = r#"{
  "t2s_port": 12347,
  "t2s_web_port": 8002
}
"#;
    const TOR_TORRC: &str = r#"DataDirectory /data/adb/modules/ZDT-D/working_folder/tor/
SocksPort 127.0.0.1:9050
Log notice stdout

UseBridges 1
ClientTransportPlugin meek_lite,obfs4,snowflake,webtunnel exec /data/adb/modules/ZDT-D/bin/lyrebird
"#;

    fs::create_dir_all(working_root_path())
        .with_context(|| format!("mkdir {}", working_root_path().display()))?;

    // Keep the historical creation order exactly. Registry owns metadata; this typed list
    // owns only initialization order so first-error behavior stays compatible.
    let layout_order = [
        ProgramId::ByeDpi,
        ProgramId::DpiTunnel,
        ProgramId::Nfqws,
        ProgramId::Nfqws2,
        ProgramId::SingBox,
        ProgramId::SingBoxLx,
        ProgramId::Hysteria2,
        ProgramId::WireProxy,
        ProgramId::MyProxy,
        ProgramId::MyProgram,
        ProgramId::OpenVpn,
        ProgramId::AmneziaWg,
        ProgramId::Tun2Socks,
        ProgramId::MyVpn,
        ProgramId::Mihomo,
        ProgramId::Mieru,
        ProgramId::DnsCrypt,
        ProgramId::OperaProxy,
        ProgramId::TgwsProxy,
        ProgramId::ProxyInfo,
        ProgramId::BlockedQuic,
        ProgramId::Tor,
    ];

    for id in layout_order {
        let descriptor = id.descriptor();
        let default_content = match descriptor.state_kind {
            StateKind::ProfilesActive => PROFILES_DEFAULT_JSON,
            StateKind::EnabledActive => ENABLED_FALSE_JSON,
            StateKind::EnabledJson => PROXYINFO_ENABLED_JSON,
        };
        let root = program::working_program_root(descriptor.id);
        fs::create_dir_all(&root).with_context(|| format!("mkdir {}", root.display()))?;

        let state_path = root.join(descriptor.state_file);
        if !state_path.exists() {
            fs::write(&state_path, default_content)
                .with_context(|| format!("write {}", state_path.display()))?;
        }

        // Preserve the historical eager-profile-root set exactly. Other profile programs
        // still create their profile roots through their backend-specific layout code.
        if descriptor.eager_profile_dir {
            fs::create_dir_all(root.join("profile"))
                .with_context(|| format!("mkdir {}", root.join("profile").display()))?;
        }
    }

    let tor_root = program::working_program_root(ProgramId::Tor);
    let tor_setting = tor_root.join("setting.json");
    if !tor_setting.exists() {
        fs::write(&tor_setting, TOR_SETTING_JSON)
            .with_context(|| format!("write {}", tor_setting.display()))?;
    }
    let tor_torrc = tor_root.join("torrc");
    if !tor_torrc.exists() {
        fs::write(&tor_torrc, TOR_TORRC)
            .with_context(|| format!("write {}", tor_torrc.display()))?;
    }

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
#[allow(dead_code)]
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
