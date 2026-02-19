use anyhow::{Context, Result};
use log::info;
use serde::Deserialize;
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::Duration,
};
use std::fs::OpenOptions;
use std::os::unix::process::CommandExt;

use crate::android::pkg_uid::{self, Mode, Sha256Tracker};
use crate::iptables::iptables_port::{self, DpiTunnelOptions, ProtoChoice};
use crate::shell;

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const DPITUNNEL_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/dpitunnel";
// NOTE: the binary is expected to be renamed to `dpitunnel-cli`.
// We always use this exact name and do not try to auto-detect alternatives.
const DPITUNNEL_BIN: &str = "/data/adb/modules/ZDT-D/bin/dpitunnel-cli";
const SHA_FLAG_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/flag.sha256";

#[derive(Debug, Deserialize)]
struct ActiveJson {
    profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Deserialize)]
struct ProfileState {
    enabled: bool,
}

#[derive(Debug, Deserialize)]
struct PortJson {
    port: u16,

    // Per-interface app lists (same idea as nfqws):
    // - mobile_program -> iface_mobile
    // - wifi_program   -> iface_wifi
    // Backwards compatible defaults.
    #[serde(default = "default_iface")]
    iface_mobile: String,
    #[serde(default = "default_iface")]
    iface_wifi: String,
}

fn default_iface() -> String {
    "auto".to_string()
}

pub fn start_active_profiles() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(DPITUNNEL_ROOT)?;
    ensure_file(DPITUNNEL_BIN)?;

    let active_path = Path::new(DPITUNNEL_ROOT).join("active.json");
    let active = read_json::<ActiveJson>(&active_path)
        .with_context(|| format!("read {}", active_path.display()))?;

    let tracker = Sha256Tracker::new(SHA_FLAG_FILE);

    for (profile_name, st) in active.profiles.iter() {
        if !st.enabled {
            continue;
        }
        start_profile(profile_name, &tracker)?;
    }
    Ok(())
}

fn start_profile(profile_name: &str, tracker: &Sha256Tracker) -> Result<()> {
    let profile_dir = Path::new(DPITUNNEL_ROOT).join(profile_name);
    ensure_dir(profile_dir.to_string_lossy().as_ref())?;

    let port_path = profile_dir.join("port.json");
    let port_cfg = read_json::<PortJson>(&port_path)
        .with_context(|| format!("read {}", port_path.display()))?;

    // App lists: user + mobile + wifi (same as nfqws)
    let uid_dir = profile_dir.join("app/uid");
    let out_dir = profile_dir.join("app/out");
    fs::create_dir_all(&uid_dir).with_context(|| format!("mkdir {}", uid_dir.display()))?;
    fs::create_dir_all(&out_dir).with_context(|| format!("mkdir {}", out_dir.display()))?;

    let in_user = uid_dir.join("user_program");
    let in_mobile = uid_dir.join("mobile_program");
    let in_wifi = uid_dir.join("wifi_program");

    let out_user = out_dir.join("user_program");
    let out_mobile = out_dir.join("mobile_program");
    let out_wifi = out_dir.join("wifi_program");

    // Backwards compatible: create missing list files as empty.
    ensure_file_empty(&in_user)?;
    ensure_file_empty(&in_mobile)?;
    ensure_file_empty(&in_wifi)?;

    // Convert package list -> package=uid (sha256 gated, but will rebuild if output empty/missing)
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_user, &in_user)?;
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_mobile, &in_mobile)?;
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_wifi, &in_wifi)?;

    let resolved_user = count_valid_uid_pairs(&out_user)?;
    let resolved_mobile = count_valid_uid_pairs(&out_mobile)?;
    let resolved_wifi = count_valid_uid_pairs(&out_wifi)?;
    let resolved_total = resolved_user + resolved_mobile + resolved_wifi;
    if resolved_total == 0 {
        log::warn!("dpitunnel: no apps resolved for {} -> skip start/iptables", profile_dir.display());
        return Ok(());
    }

    // Config args come from config/config.txt content (split into argv tokens)
    let config_path = profile_dir.join("config/config.txt");
    let raw = fs::read_to_string(&config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let config_args = normalize_config_args(&raw);

    // If the port is already in use, skip this profile without failing the whole start sequence.
    // This keeps other profiles/services running normally.
    if is_tcp_port_in_use(port_cfg.port)? {
        crate::logging::user(&format!(
            "dpitunnel: порт {} уже занят. Пропускаю профиль {}",
            port_cfg.port, profile_name
        ));
        return Ok(());
    }

    // Spawn dpitunnel (detached) and truncate log each start
    let log_dir = profile_dir.join("log");
    fs::create_dir_all(&log_dir).with_context(|| format!("mkdir {}", log_dir.display()))?;
    let log_path = log_dir.join("dpitunnel.log");

    let bin = Path::new(DPITUNNEL_BIN);
    crate::logging::user_info(&format!("dpitunnel[{profile_name}]: запуск"));
    let running = spawn_dpitunnel(&profile_dir, bin, port_cfg.port, &config_args, &log_path)?;
    if !running {
        crate::logging::user(&format!(
            "dpitunnel: профиль {} не запустился (проверь dpitunnel.log). Пропускаю iptables",
            profile_name
        ));
        return Ok(());
    }

    crate::logging::user_info(&format!("dpitunnel[{profile_name}]: iptables"));

    // USER (all interfaces)
    iptables_port::apply(
        &out_user,
        port_cfg.port,
        ProtoChoice::TcpUdp,
        None,
        DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
    )?;

    // MOBILE (per-interface)
    iptables_port::apply(
        &out_mobile,
        port_cfg.port,
        ProtoChoice::TcpUdp,
        Some(port_cfg.iface_mobile.as_str()),
        DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
    )?;

    // WIFI (per-interface)
    iptables_port::apply(
        &out_wifi,
        port_cfg.port,
        ProtoChoice::TcpUdp,
        Some(port_cfg.iface_wifi.as_str()),
        DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
    )?;

    info!("dpitunnel profile started: {} port={}", profile_name, port_cfg.port);
    Ok(())
}

fn ensure_file_empty(p: &Path) -> Result<()> {
    if p.is_file() {
        return Ok(());
    }
    if let Some(parent) = p.parent() {
        let _ = fs::create_dir_all(parent);
    }
    fs::write(p, b"")
        .with_context(|| format!("create empty file {}", p.display()))?;
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

fn normalize_config_args(raw: &str) -> Vec<String> {
    // Convert multiline config into argv tokens.
    // - Treat '\' immediately followed by newline as a line continuation (removed)
    // - Other newlines/CR become spaces
    // - Collapse whitespace via split_whitespace
    // - Drop standalone "\" tokens
    // Quotes (") are preserved; this is NOT a full shell-quoting parser.
    let mut s = String::with_capacity(raw.len());
    let mut it = raw.chars().peekable();

    while let Some(c) = it.next() {
        if c == '\\' {
            match it.peek().copied() {
                Some('\n') => {
                    it.next();
                    // line continuation: remove \ + newline without inserting space (shell-like)
                    continue;
                }
                Some('\r') => {
                    it.next();
                    if matches!(it.peek().copied(), Some('\n')) {
                        it.next();
                    }
                    // line continuation: remove \ + CRLF without inserting space (shell-like)
                    continue;
                }
                _ => {}
            }
        }

        if c == '\n' || c == '\r' {
            s.push(' ');
        } else {
            s.push(c);
        }
    }

    let mut out: Vec<String> = Vec::new();
    for tok in s.split_whitespace() {
        if tok == "\\" {
            continue;
        }
        out.push(tok.to_string());
    }
    out
}



fn spawn_dpitunnel(
    workdir: &Path,
    bin: &Path,
    port: u16,
    extra_args: &[String],
    log_path: &Path,
) -> Result<bool> {
    // Command template (dpitunnel-cli):
    //   dpitunnel-cli --port <PORT> <ARGS_FROM_config.txt...>
    //
    // Notes:
    // - Some dpitunnel-cli builds treat short flags like `-p` as ambiguous.
    //   Use the long option `--port`.
    // - dpitunnel-cli defaults to binding 0.0.0.0, which can conflict with
    //   other localhost-only services using the same port. Unless the user
    //   explicitly sets --ip in config.txt, bind to 127.0.0.1.
    let port_s = port.to_string();

    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(bin);
    cmd.current_dir(workdir);
    if !args_contain_ip(extra_args) {
        cmd.arg("--ip").arg("127.0.0.1");
    }
    cmd.arg("--port")
        .arg(&port_s)
        .args(extra_args)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            unsafe {
                let _ = libc::setsid();
            }
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    let pid = child.id();
    info!(
        "spawned dpitunnel pid={} port={} log={}",
        pid,
        port,
        log_path.display()
    );

    std::thread::sleep(Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(pid.to_string());
    let running = proc_path.is_dir();
    if !running {
        info!(
            "dpitunnel pid={} is not running after spawn (check log {})",
            pid,
            log_path.display()
        );
    }

    Ok(running)
}

fn args_contain_ip(args: &[String]) -> bool {
    // Accept both: "--ip" "X" and "--ip=X"
    let mut it = args.iter();
    while let Some(a) = it.next() {
        if a == "--ip" {
            return true;
        }
        if a.starts_with("--ip=") {
            return true;
        }
    }
    false
}

fn is_tcp_port_in_use(port: u16) -> Result<bool> {
    // Use `ss -lntp` if available.
    // We intentionally keep it best-effort to avoid breaking devices without ss.
    let pat = format!(":{port} ");
    let line = format!(
        "ss -lntp 2>/dev/null | grep -F '{pat}' || true"
    );
    match shell::capture(&line) {
        Ok(out) => Ok(!out.trim().is_empty()),
        Err(_) => Ok(false),
    }
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let v = serde_json::from_str::<T>(&s).with_context(|| format!("parse json {}", path.display()))?;
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
