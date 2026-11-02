use std::fs::OpenOptions;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Stdio};

use anyhow::{Context, Result};
use log::info;

use crate::settings;

#[derive(Debug, Clone, Copy)]
pub struct T2sSpawnConfig<'a> {
    pub bin: &'a Path,
    pub listen_addr: &'a str,
    pub listen_port: u16,
    pub socks_host: &'a str,
    pub socks_ports_csv: &'a str,
    pub web_port: Option<u16>,
    pub program: &'a str,
    pub profile: &'a str,
    pub scope: &'a str,
    pub log_path: &'a Path,
    pub backend_mode: Option<&'a str>,
    pub backend_priority: Option<&'a str>,
    pub priority_speed_aware: bool,
    pub socks_user: Option<&'a str>,
    pub socks_pass: Option<&'a str>,
    pub wrapped_socks_host: Option<&'a str>,
    pub wrapped_socks_port: Option<u16>,
    pub wrapped_socks_user: Option<&'a str>,
    pub wrapped_socks_pass: Option<&'a str>,
}

impl<'a> Default for T2sSpawnConfig<'a> {
    fn default() -> Self {
        Self {
            bin: Path::new(""),
            listen_addr: "127.0.0.1",
            listen_port: 0,
            socks_host: "127.0.0.1",
            socks_ports_csv: "",
            web_port: None,
            program: "",
            profile: "main",
            scope: "",
            log_path: Path::new(""),
            backend_mode: None,
            backend_priority: None,
            priority_speed_aware: false,
            socks_user: None,
            socks_pass: None,
            wrapped_socks_host: None,
            wrapped_socks_port: None,
            wrapped_socks_user: None,
            wrapped_socks_pass: None,
        }
    }
}

pub fn t2s_tproxy_enabled() -> bool {
    settings::load_api_settings()
        .map(|st| st.tproxy_enabled)
        .unwrap_or(false)
}

pub fn spawn_t2s_proxy(cfg: T2sSpawnConfig<'_>) -> Result<()> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(cfg.log_path)
        .with_context(|| format!("open log {}", cfg.log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(cfg.bin);
    cmd.arg("--listen-addr")
        .arg(cfg.listen_addr)
        .arg("--listen-port")
        .arg(cfg.listen_port.to_string())
        .arg("--socks-host")
        .arg(cfg.socks_host)
        .arg("--socks-port")
        .arg(cfg.socks_ports_csv)
        .arg("--max-conns")
        .arg("1200")
        .arg("--idle-timeout")
        .arg("400")
        .arg("--connect-timeout")
        .arg("30")
        .arg("--enable-http2")
        .arg("--web-socket");

    if let Some(web_port) = cfg.web_port {
        cmd.arg("--web-port").arg(web_port.to_string());
    }

    cmd.arg("--program")
        .arg(cfg.program)
        .arg("--profile")
        .arg(cfg.profile)
        .arg("--scope")
        .arg(cfg.scope)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    if let Some(mode) = cfg.backend_mode.map(str::trim).filter(|s| !s.is_empty()) {
        cmd.arg("--backend-mode").arg(mode);
    }
    if let Some(priority) = cfg.backend_priority.map(str::trim).filter(|s| !s.is_empty()) {
        cmd.arg("--backend-priority").arg(priority);
    }
    if cfg.priority_speed_aware {
        cmd.arg("--priority-speed-aware");
    }

    if let (Some(user), Some(pass)) = (cfg.socks_user, cfg.socks_pass) {
        if !user.trim().is_empty() || !pass.trim().is_empty() {
            cmd.arg("--socks-user").arg(user.trim())
                .arg("--socks-pass").arg(pass.trim());
        }
    }

    if let (Some(host), Some(port)) = (cfg.wrapped_socks_host, cfg.wrapped_socks_port) {
        if !host.trim().is_empty() && port != 0 {
            cmd.arg("--wrapped-socks-host").arg(host.trim())
                .arg("--wrapped-socks-port").arg(port.to_string());
            if let (Some(user), Some(pass)) = (cfg.wrapped_socks_user, cfg.wrapped_socks_pass) {
                if !user.trim().is_empty() || !pass.is_empty() {
                    cmd.arg("--wrapped-socks-user").arg(user.trim())
                        .arg("--wrapped-socks-pass").arg(pass);
                }
            }
        }
    }

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", cfg.bin.display()))?;
    info!(
        "spawned t2s pid={} program={} profile={} scope={} listen_addr={} listen_port={} socks_host={} socks_ports={} web_port={} tproxy_enabled={} log={}",
        child.id(),
        cfg.program,
        cfg.profile,
        cfg.scope,
        cfg.listen_addr,
        cfg.listen_port,
        cfg.socks_host,
        cfg.socks_ports_csv,
        cfg.web_port.map(|p| p.to_string()).unwrap_or_else(|| "default".to_string()),
        t2s_tproxy_enabled(),
        cfg.log_path.display(),
    );
    Ok(())
}
