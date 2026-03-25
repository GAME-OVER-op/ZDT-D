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
use crate::iptables::{iptables_v1, iptables_v2};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const NFQWS_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/nfqws";
const NFQWS_BIN: &str = "/data/adb/modules/ZDT-D/bin/nfqws";
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
    iface_mobile: String,
    iface_wifi: String,
}

pub fn start_active_profiles() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(NFQWS_ROOT)?;
    ensure_file(NFQWS_BIN)?;

    let active_path = Path::new(NFQWS_ROOT).join("active.json");
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
    let profile_dir = Path::new(NFQWS_ROOT).join(profile_name);
    ensure_dir(profile_dir.to_string_lossy().as_ref())?;

    let port_path = profile_dir.join("port.json");
    let port_cfg = read_json::<PortJson>(&port_path)
        .with_context(|| format!("read {}", port_path.display()))?;

    // lists
    let uid_dir = profile_dir.join("app/uid");
    let out_dir = profile_dir.join("app/out");
    fs::create_dir_all(&out_dir).with_context(|| format!("mkdir {}", out_dir.display()))?;

    let in_mobile = uid_dir.join("mobile_program");
    let in_wifi = uid_dir.join("wifi_program");
    let in_user = uid_dir.join("user_program");

    let out_mobile = out_dir.join("mobile_program");
    let out_wifi = out_dir.join("wifi_program");
    let out_user = out_dir.join("user_program");

    // Convert package list -> package=uid (sha256 gated)
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_mobile, &in_mobile)?;
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_wifi, &in_wifi)?;
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_user, &in_user)?;

    
// Pass config file path as argument (nfqws will parse the file itself).

    // Spawn nfqws: nfqws --uid=0:0 --qnum=<port> <config_path>
    let log_dir = profile_dir.join("log");
    fs::create_dir_all(&log_dir).with_context(|| format!("mkdir {}", log_dir.display()))?;
    let log_path = log_dir.join("nfqws.log");
    
let resolved_mobile = count_valid_uid_pairs(&out_mobile)?;
    let resolved_wifi = count_valid_uid_pairs(&out_wifi)?;
    let resolved_user = count_valid_uid_pairs(&out_user)?;
    let resolved_total = resolved_mobile + resolved_wifi + resolved_user;
    if resolved_total == 0 {
        log::warn!("nfqws: no apps resolved for {} -> skip start/iptables", profile_dir.display());
        return Ok(());
    }

    let config_path = profile_dir.join("config/config.txt");
let raw = fs::read_to_string(&config_path)
    .with_context(|| format!("read {}", config_path.display()))?;
let config_args = normalize_config_args(&raw);

let port_filter = crate::programs::nfqws_filters::extract_proto_port_filter(&raw);
let port_filter_ref = if port_filter.is_empty() { None } else { Some(&port_filter) };


    crate::logging::user_info(&format!("zapret[{profile_name}]: запуск"));
spawn_nfqws(&profile_dir, port_cfg.port, &config_args, &log_path)?;

    // Apply iptables:
    crate::logging::user_info(&format!("zapret[{profile_name}]: iptables"));
    // v2 (no iface) for USER
    iptables_v2::apply(port_cfg.port, Some(&out_user), port_filter_ref)?;
    // v1 full for mobile + wifi with iface mapping
    iptables_v1::apply("full", port_cfg.port, Some(port_cfg.iface_mobile.as_str()), Some(&out_mobile), port_filter_ref)?;
    iptables_v1::apply("full", port_cfg.port, Some(port_cfg.iface_wifi.as_str()), Some(&out_wifi), port_filter_ref)?;

    info!("nfqws profile started: {} port={}", profile_name, port_cfg.port);
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



fn spawn_nfqws(workdir: &Path, port: u16, config_args: &[String], log_path: &Path) -> Result<()> {
    let q = format!("--qnum={}", port);

    // Open log file (append) for stdout/stderr so we can debug early exits.
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    // We intentionally pass `config_arg` as ONE argument, exactly like "$config" in shell.
    let mut cmd = Command::new(NFQWS_BIN);
    cmd.current_dir(workdir);
    cmd.arg("--uid=0:0")
        .arg(q)
        .args(config_args)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    // Detach from controlling terminal/session so the process survives when the launcher exits.
    unsafe {
        cmd.pre_exec(|| {
            // If setsid fails, we still proceed; the process may die on terminal close, so we log it later.
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", NFQWS_BIN))?;
    let pid = child.id();
    info!("spawned nfqws pid={} qnum={} log={}", pid, port, log_path.display());

    // Quick liveness check (best-effort)
    std::thread::sleep(Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(pid.to_string());
    if !proc_path.is_dir() {
        info!("nfqws pid={} is not running after spawn (check log {})", pid, log_path.display());
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
