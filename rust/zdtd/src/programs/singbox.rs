use anyhow::{Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeSet,
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::Duration,
};

use crate::iptables::iptables_port::{self, DpiTunnelOptions, ProtoChoice};
use crate::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";

const SINGBOX_BIN: &str = "/data/adb/modules/ZDT-D/bin/sing-box";
const SINGBOX_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/singbox";
const SETTING_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/singbox/setting.json";

const T2S_LOG: &str = "/data/adb/modules/ZDT-D/working_folder/singbox/t2s.log";
const SHA_FLAG_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/flag.sha256";

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    Socks5,
    Transparent,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum CaptureMode {
    Tcp,
    TcpUdp,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Profile {
    pub name: String,
    #[serde(default)]
    pub enabled: bool,

    /// Single port used for both socks5 and transparent modes for this profile.
    /// (User-managed sing-box config.json expects a fixed port.)
    #[serde(default)]
    pub port: Option<u16>,

    #[serde(default)]
    pub capture: Option<CaptureMode>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Setting {
    #[serde(default)]
    pub enabled: bool,
    pub mode: Mode,
    #[serde(default)]
    pub t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    pub t2s_web_port: u16,
    #[serde(default)]
    pub active_transparent_profile: String,
    #[serde(default)]
    pub profiles: Vec<Profile>,
}

fn default_t2s_web_port() -> u16 {
    8001
}

pub fn start_if_enabled() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(SINGBOX_ROOT)?;
    ensure_file(SINGBOX_BIN)?;

    let setting_path = Path::new(SETTING_JSON);
    let setting: Setting = match read_json(setting_path) {
        Ok(v) => v,
        Err(e) => {
            warn!("sing-box: failed to read setting.json (skip): {e:#}");
            return Ok(());
        }
    };

    if !setting.enabled {
        info!("sing-box: disabled via setting.json");
        return Ok(());
    }

    // Validate settings and build plan first. If anything is wrong -> skip without partial actions.
    let plan = match build_plan(&setting).with_context(|| "validate sing-box setting.json") {
        Ok(p) => p,
        Err(e) => {
            warn!("sing-box: invalid setting.json -> skip: {e:#}");
            return Ok(());
        }
    };

    // Port intersection / conflicts with other programs.
    if let Err(e) = ensure_no_port_conflicts(&setting, &plan).with_context(|| "sing-box port conflict") {
        warn!("sing-box: port conflict -> skip: {e:#}");
        return Ok(());
    }

    // Resolve apps list -> uids (like other programs). If no apps selected/resolved -> skip start.
    let tracker = Sha256Tracker::new(SHA_FLAG_FILE);
    let uid_in_dir = Path::new(SINGBOX_ROOT).join("app/uid");
    let uid_out_dir = Path::new(SINGBOX_ROOT).join("app/out");
    fs::create_dir_all(&uid_in_dir).ok();
    fs::create_dir_all(&uid_out_dir).ok();
    let uid_in = uid_in_dir.join("user_program");
    let uid_out = uid_out_dir.join("user_program");
    ensure_file_empty(&uid_in)?;
    let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &uid_out, &uid_in)
        .with_context(|| "pkg_uid processing")?;
    let resolved = count_valid_uid_pairs(&uid_out).unwrap_or(0);
    if resolved == 0 {
        warn!("sing-box: no apps resolved -> skip start/iptables");
        return Ok(());
    }

    // --- Start processes + apply iptables.
    match plan {
        StartPlan::Socks5 { enabled_profiles, socks_ports } => {
            crate::logging::user_info("sing-box: socks5");

            // 1) start sing-box per profile
            for p in &enabled_profiles {
                let cfg = profile_config_path(&p.name);
                let logp = profile_log_path(&p.name);
                ensure_parent_dir(&logp)?;
                truncate_file(&logp)?;
                spawn_singbox(&cfg, &logp)
                    .with_context(|| format!("spawn sing-box profile={}", p.name))?;
            }

            // 2) start t2s (one instance)
            let t2s_bin = find_bin("t2s")?;
            let ports_csv = socks_ports
                .iter()
                .map(|p| p.to_string())
                .collect::<Vec<_>>()
                .join(",");

            let t2s_logp = Path::new(T2S_LOG);
            truncate_file(t2s_logp)?;
            spawn_t2s(&t2s_bin, setting.t2s_port, setting.t2s_web_port, &ports_csv, t2s_logp)?;

            // 3) iptables: redirect selected apps -> t2s_port (TCP only)
            // We keep app selection file compatible with other programs:
            // /working_folder/singbox/app/uid/user_program
            let uid_file = Path::new(SINGBOX_ROOT).join("app/out/user_program");
            crate::logging::user_info("sing-box: iptables (tcp)");
            iptables_port::apply(
                &uid_file,
                setting.t2s_port,
                ProtoChoice::Tcp,
                None,
                DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
            )?;

            info!(
                "sing-box: socks5 started profiles={} t2s_port={} socks_ports={} web_port={}",
                enabled_profiles.len(),
                setting.t2s_port,
                ports_csv,
                setting.t2s_web_port
            );
        }
        StartPlan::Transparent { profile, proto_choice } => {
            crate::logging::user_info("sing-box: transparent");

            let cfg = profile_config_path(&profile.name);
            let logp = profile_log_path(&profile.name);
            ensure_parent_dir(&logp)?;
            truncate_file(&logp)?;
            spawn_singbox(&cfg, &logp)
                .with_context(|| format!("spawn sing-box profile={}", profile.name))?;

            // iptables: redirect apps -> profile port (TCP or TCP+UDP)
            let uid_file = Path::new(SINGBOX_ROOT).join("app/out/user_program");
            crate::logging::user_info("sing-box: iptables (transparent)");
            iptables_port::apply(
                &uid_file,
                profile.port.unwrap_or(0),
                proto_choice,
                None,
                DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
            )?;

            info!(
                "sing-box: transparent started profile={} port={} proto={:?}",
                profile.name,
                profile.port.unwrap_or(0),
                proto_choice
            );
        }
    }

    Ok(())
}

#[derive(Debug, Clone)]
enum StartPlan {
    Socks5 {
        enabled_profiles: Vec<Profile>,
        socks_ports: Vec<u16>,
    },
    Transparent {
        profile: Profile,
        proto_choice: ProtoChoice,
    },
}

fn build_plan(setting: &Setting) -> Result<StartPlan> {
    // Basic field validation.
    if setting.t2s_web_port == 0 {
        anyhow::bail!("t2s_web_port is required");
    }
    if setting.t2s_web_port > 65535 {
        anyhow::bail!("t2s_web_port out of range");
    }
    if setting.t2s_port > 65535 {
        anyhow::bail!("t2s_port out of range");
    }

    // Profile name validation.
    for p in &setting.profiles {
        ensure_valid_profile_name(&p.name)?;
    }

    // Profile ports must be unique across profiles (even if disabled), when provided.
    let mut ports_seen = BTreeSet::<u16>::new();
    for p in &setting.profiles {
        if let Some(port) = p.port {
            if port == 0 {
                anyhow::bail!("profile '{}' port invalid", p.name);
            }
            if !ports_seen.insert(port) {
                anyhow::bail!("duplicate profile port {port}");
            }
        }
    }

    match setting.mode {
        Mode::Socks5 => {
            if setting.t2s_port == 0 {
                anyhow::bail!("t2s_port is required in socks5 mode");
            }
            // Enabled profiles may still be skipped if they are not "ready" (e.g. empty config).
            // We must not partially start with bad data.
            let mut enabled_profiles = Vec::<Profile>::new();
            let mut socks_ports = Vec::<u16>::new();

            for p in setting.profiles.iter().filter(|p| p.enabled) {
                // Port must be present and valid.
                let port = match p.port {
                    Some(v) if v > 0 => v,
                    _ => {
                        warn!("sing-box: socks5 skip profile='{}' (missing/invalid port)", p.name);
                        continue;
                    }
                };

                // config.json is user-managed and may be empty; empty -> profile is skipped.
                let cfg = profile_config_path(&p.name);
                if !cfg.is_file() {
                    warn!("sing-box: socks5 skip profile='{}' (config.json missing)", p.name);
                    continue;
                }
                if !is_nonempty_file(&cfg).unwrap_or(false) {
                    warn!("sing-box: socks5 skip profile='{}' (config.json empty)", p.name);
                    continue;
                }

                enabled_profiles.push(p.clone());
                socks_ports.push(port);
            }

            if enabled_profiles.is_empty() {
                anyhow::bail!("no runnable enabled profiles in socks5 mode");
            }

            // Ports must be unique among runnable profiles.
            let mut set = BTreeSet::new();
            for p in &socks_ports {
                if !set.insert(*p) {
                    anyhow::bail!("duplicate profile port {p}");
                }
            }

            Ok(StartPlan::Socks5 { enabled_profiles, socks_ports })
        }
        Mode::Transparent => {
            if setting.active_transparent_profile.trim().is_empty() {
                anyhow::bail!("active_transparent_profile is required in transparent mode");
            }
            let prof = setting
                .profiles
                .iter()
                .find(|p| p.name == setting.active_transparent_profile)
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("active_transparent_profile not found"))?;

            let port = match prof.port {
                Some(v) if v > 0 => v,
                _ => anyhow::bail!("port required for active transparent profile"),
            };
            let cfg = profile_config_path(&prof.name);
            if !cfg.is_file() {
                anyhow::bail!("profile '{}' config.json not found: {}", prof.name, cfg.display());
            }
            if !is_nonempty_file(&cfg).unwrap_or(false) {
                anyhow::bail!("profile '{}' config.json empty", prof.name);
            }

            let cap = prof.capture.clone().unwrap_or(CaptureMode::Tcp);
            let proto = match cap {
                CaptureMode::Tcp => ProtoChoice::Tcp,
                CaptureMode::TcpUdp => ProtoChoice::TcpUdp,
            };
            // Ensure port is set (already validated above).
            let mut prof2 = prof;
            prof2.port = Some(port);
            Ok(StartPlan::Transparent { profile: prof2, proto_choice: proto })
        }
    }
}

fn ensure_no_port_conflicts(setting: &Setting, plan: &StartPlan) -> Result<()> {
    // Collect all ports used by other programs/services.
    let mut used = crate::ports::collect_used_ports_for_conflict_check()
        .unwrap_or_else(|_| BTreeSet::new());

    // Also guard against collisions inside sing-box itself.
    let mut own = BTreeSet::<u16>::new();
    match plan {
        StartPlan::Socks5 { socks_ports, .. } => {
            if setting.t2s_port == 0 || setting.t2s_web_port == 0 {
                anyhow::bail!("t2s ports are required");
            }
            for p in [setting.t2s_port, setting.t2s_web_port] {
                if !own.insert(p) {
                    anyhow::bail!("duplicate port inside sing-box: {p}");
                }
            }
            for p in socks_ports {
                if !own.insert(*p) {
                    anyhow::bail!("duplicate port inside sing-box: {p}");
                }
            }
        }
        StartPlan::Transparent { profile, .. } => {
            let p = profile.port.unwrap_or(0);
            if p == 0 {
                anyhow::bail!("profile port is required");
            }
            own.insert(p);
        }
    }

    // Collision with other programs.
    for p in &own {
        if used.contains(p) {
            anyhow::bail!("port conflict detected: {p}");
        }
        used.insert(*p);
    }
    Ok(())
}

fn is_nonempty_file(p: &Path) -> Result<bool> {
    let md = fs::metadata(p).with_context(|| format!("stat {}", p.display()))?;
    Ok(md.len() > 0)
}

fn profile_config_path(profile: &str) -> PathBuf {
    Path::new(SINGBOX_ROOT)
        .join(profile)
        .join("config.json")
}

fn profile_log_path(profile: &str) -> PathBuf {
    Path::new(SINGBOX_ROOT)
        .join(profile)
        .join("log")
        .join("sing-box.log")
}

fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if name.is_empty() {
        anyhow::bail!("profile name is empty");
    }
    if name.len() > 64 {
        anyhow::bail!("profile name too long");
    }
    // English symbols, no spaces.
    if !name
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    {
        anyhow::bail!("profile name must contain only English letters/digits/_/-");
    }
    Ok(())
}

fn spawn_singbox(config_path: &Path, log_path: &Path) -> Result<()> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(SINGBOX_BIN);
    cmd.arg("run")
        .arg("-c")
        .arg(config_path)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd
        .spawn()
        .with_context(|| format!("spawn {}", SINGBOX_BIN))?;
    info!(
        "spawned sing-box pid={} cfg={} log={}",
        child.id(),
        config_path.display(),
        log_path.display()
    );

    // quick liveness check (best-effort)
    std::thread::sleep(Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("sing-box pid={} exited quickly; check log {}", child.id(), log_path.display());
    }
    Ok(())
}

fn spawn_t2s(bin: &Path, listen_port: u16, web_port: u16, socks_ports_csv: &str, log_path: &Path) -> Result<()> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(bin);
    cmd.arg("--listen-addr")
        .arg("127.0.0.1")
        .arg("--listen-port")
        .arg(listen_port.to_string())
        .arg("--socks-host")
        .arg("127.0.0.1")
        .arg("--socks-port")
        .arg(socks_ports_csv)
        .arg("--max-conns")
        .arg("600")
        .arg("--idle-timeout")
        .arg("5000")
        .arg("--connect-timeout")
        .arg("30")
        .arg("--enable-http2")
        .arg("--web-socket")
        .arg("--web-port")
        .arg(web_port.to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!(
        "spawned t2s pid={} listen_port={} socks_ports={} web_port={} log={}",
        child.id(),
        listen_port,
        socks_ports_csv,
        web_port,
        log_path.display()
    );
    Ok(())
}

fn find_bin(name: &str) -> Result<PathBuf> {
    let p = Path::new("/data/adb/modules/ZDT-D/bin").join(name);
    if p.is_file() {
        Ok(p)
    } else {
        anyhow::bail!("binary not found: {}", p.display())
    }
}

fn truncate_file(p: &Path) -> Result<()> {
    let _ = OpenOptions::new().create(true).write(true).truncate(true).open(p)?;
    Ok(())
}

fn ensure_file_empty(p: &Path) -> Result<()> {
    if !p.exists() {
        if let Some(parent) = p.parent() {
            fs::create_dir_all(parent).ok();
        }
        fs::write(p, b"")
            .with_context(|| format!("create {}", p.display()))?;
    }
    Ok(())
}

fn count_valid_uid_pairs(path: &Path) -> Result<usize> {
    if !path.is_file() {
        return Ok(0);
    }
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut n = 0usize;
    for line in s.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((_pkg, uid_s)) = line.split_once('=') {
            let uid_s = uid_s.trim();
            if !uid_s.is_empty() && uid_s.chars().all(|c| c.is_ascii_digit()) {
                n += 1;
            }
        }
    }
    Ok(n)
}

fn ensure_parent_dir(p: &Path) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("mkdir {}", parent.display()))?;
    }
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let s = fs::read_to_string(path)
        .with_context(|| format!("read {}", path.display()))?;
    let v = serde_json::from_str::<T>(&s)
        .with_context(|| format!("parse json {}", path.display()))?;
    Ok(v)
}

fn ensure_dir(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_dir() {
        anyhow::bail!("directory missing: {}", path.display());
    }
    Ok(())
}

fn ensure_file(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_file() {
        anyhow::bail!("file missing: {}", path.display());
    }
    Ok(())
}
