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

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const BYEDPI_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/byedpi";
const BIN_DIR: &str = "/data/adb/modules/ZDT-D/bin";
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
}

pub fn start_active_profiles() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(BYEDPI_ROOT)?;

    let active_path = Path::new(BYEDPI_ROOT).join("active.json");
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
    let profile_dir = Path::new(BYEDPI_ROOT).join(profile_name);
    ensure_dir(profile_dir.to_string_lossy().as_ref())?;

    let port_path = profile_dir.join("port.json");
    let port_cfg = read_json::<PortJson>(&port_path)
        .with_context(|| format!("read {}", port_path.display()))?;

    // app lists: only one (no wifi/mobile)
    let uid_dir = profile_dir.join("app/uid");
    let out_dir = profile_dir.join("app/out");
    fs::create_dir_all(&out_dir).with_context(|| format!("mkdir {}", out_dir.display()))?;

    // Convention: user_program file in byedpi profile
    let in_user = uid_dir.join("user_program");
    let out_user = out_dir.join("user_program");

    // Convert package list -> package=uid (sha256 gated, but will rebuild if output empty/missing)
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_user, &in_user)?;

    let resolved = count_valid_uid_pairs(&out_user)?;
    if resolved == 0 {
        log::warn!("byedpi: no apps resolved for {} -> skip start/iptables", profile_dir.display());
        return Ok(());
    }

    // Config args come from config/config.txt content (split into argv tokens)
    let config_path = profile_dir.join("config/config.txt");
    let raw = fs::read_to_string(&config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let config_args = normalize_config_args(&raw);

    // Spawn byedpi/ciadpi
    let log_dir = profile_dir.join("log");
    fs::create_dir_all(&log_dir).with_context(|| format!("mkdir {}", log_dir.display()))?;
    let log_path = log_dir.join("byedpi.log");

    let bin = find_byedpi_bin().with_context(|| "byedpi binary not found in /data/adb/modules/ZDT-D/bin")?;
    crate::logging::user_info(&format!("byedpi[{profile_name}]: запуск"));
    spawn_byedpi(&profile_dir, &bin, port_cfg.port, &config_args, &log_path)?;

    crate::logging::user_info(&format!("byedpi[{profile_name}]: iptables"));
    // Apply iptables_port: uid_file, port, proto=tcp_udp, ifaces=None
    iptables_port::apply(
        &out_user,
        port_cfg.port,
        ProtoChoice::TcpUdp,
        None,
        DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
    )?;

    info!("byedpi profile started: {} port={}", profile_name, port_cfg.port);
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



fn spawn_byedpi(workdir: &Path, bin: &Path, port: u16, extra_args: &[String], log_path: &Path) -> Result<()> {
    // ciadpi-zdt -i 127.0.0.1 -p "$DPI_PORT" -x 2 -E "$@"
    let port_s = port.to_string();

    // Truncate log each start
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(bin);
    cmd.current_dir(workdir);
    cmd.arg("-i").arg("127.0.0.1")
        .arg("-p").arg(&port_s)
        .arg("-x").arg("2")
        .arg("-E")
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
    info!("spawned byedpi pid={} port={} log={}", pid, port, log_path.display());

    // quick liveness check (best-effort)
    std::thread::sleep(Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(pid.to_string());
    if !proc_path.is_dir() {
        info!("byedpi pid={} is not running after spawn (check log {})", pid, log_path.display());
    }

    Ok(())
}

fn find_byedpi_bin() -> Result<PathBuf> {
    // Try common names in order
    let candidates = [
        "ciadpi-zdt",
        "ciadpi",
        "byedpi",
        "bye_dpi",
    ];

    for name in candidates {
        let p = Path::new(BIN_DIR).join(name);
        if p.is_file() {
            return Ok(p);
        }
    }
    anyhow::bail!("no byedpi binary candidates found in {}", BIN_DIR);
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
