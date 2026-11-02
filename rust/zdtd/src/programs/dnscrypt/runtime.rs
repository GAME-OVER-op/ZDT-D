use anyhow::{Context, Result};
use log::{info, warn};
use std::{fs, net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket}, path::Path, process::{Child, Command, ExitStatus, Stdio}, time::{Duration, Instant, SystemTime, UNIX_EPOCH}};
use std::os::unix::process::CommandExt;



use super::control::*;
use super::config::*;
use super::iptables::*;
use super::storage::*;

pub fn start_if_enabled() -> Result<()> {
    reset_stop();

    crate::common::fs::require_dir_str(MODULE_DIR)?;
    crate::common::fs::require_dir_str(WORKING_DIR)?;
    crate::common::fs::require_dir_str(DNSCRYPT_ROOT)?;

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;

    if !active.enabled {
        info!("dnscrypt disabled (active.json enabled=false) -> skip");
        return Ok(());
    }

    crate::logging::user_info("DNSCrypt: запуск");

    let toml_path = Path::new(DNSCRYPT_TOML);
    if !toml_path.is_file() {
        warn!("dnscrypt enabled but config missing: {} -> skip", toml_path.display());
        return Ok(());
    }

    let listen_port = parse_listen_port(toml_path)?;
    if listen_port == 0 {
        warn!("dnscrypt enabled but failed to parse listen port -> skip");
        return Ok(());
    }

    let dnscrypt_bin = Path::new(BIN_DIR).join("dnscrypt");
    if !dnscrypt_bin.is_file() {
        warn!("dnscrypt enabled but binary not found: {} -> skip", dnscrypt_bin.display());
        crate::programs::components::user_warn_missing_component("dnscrypt");
        return Ok(());
    }

    let mut d2s_child = match parse_active_d2s_listener(toml_path) {
        Ok(Some(listener)) => match spawn_d2s(toml_path, listener) {
            Ok(child) => Some(child),
            Err(error) => {
                warn!("d2s start failed: {error:#}");
                if !crate::programs::components::report_missing_components(&error) {
                    crate::logging::user_warn("D2S: ошибка запуска — запуск DNSCrypt продолжен");
                }
                None
            }
        },
        Ok(None) => None,
        Err(error) => {
            warn!("d2s proxy detection failed: {error:#}");
            None
        }
    };

    let mut dnscrypt_child = match spawn_dnscrypt(toml_path, listen_port) {
        Ok(Some(child)) => child,
        Ok(None) => {
            crate::logging::user_error("DNSCrypt: ошибка запуска — ожидание готовности прекращено");
            stop_started_d2s(&mut d2s_child);
            return Ok(());
        }
        Err(error) => {
            warn!("dnscrypt spawn failed: {error:#}");
            if !crate::programs::components::report_missing_components(&error) {
                crate::logging::user_error("DNSCrypt: ошибка запуска — ожидание готовности прекращено");
            }
            stop_started_d2s(&mut d2s_child);
            return Ok(());
        }
    };

    if stop_requested() {
        warn!("dnscrypt start aborted after spawn (stop requested)");
        stop_started_d2s(&mut d2s_child);
        return Ok(());
    }

    match wait_dnscrypt_ready(listen_port, &mut dnscrypt_child)? {
        DnscryptWaitResult::Ready => {}
        DnscryptWaitResult::Exited(status) => {
            warn!("dnscrypt exited during startup with status {status}");
            crate::logging::user_error("DNSCrypt: ошибка запуска — ожидание готовности прекращено");
            stop_started_d2s(&mut d2s_child);
            return Ok(());
        }
        DnscryptWaitResult::StopRequested => {
            stop_started_d2s(&mut d2s_child);
            return Ok(());
        }
    }

    if stop_requested() {
        warn!("dnscrypt start aborted before iptables (stop requested)");
        stop_started_d2s(&mut d2s_child);
        return Ok(());
    }

    crate::logging::user_info("DNSCrypt: правила iptables");
    apply_dns_iptables(listen_port)?;

    if stop_requested() {
        warn!("dnscrypt stopped right after iptables");
        return Ok(());
    }

    info!("dnscrypt started and iptables rules applied (listen port={})", listen_port);
    Ok(())
}

pub(super) fn spawn_d2s(dnscrypt_toml: &Path, listener: SocketAddr) -> Result<Child> {
    ensure_d2s_config_exists()?;

    let bin = Path::new(BIN_DIR).join("d2s");
    if !bin.is_file() {
        anyhow::bail!("D2S binary not found: {}", bin.display());
    }

    let d2s_config = Path::new(D2S_TOML);
    let log_path = Path::new(D2S_LOG);
    if let Some(parent) = log_path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create D2S log directory {}", parent.display()))?;
    }
    let log_file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_path)
        .with_context(|| format!("open D2S log {}", log_path.display()))?;
    let log_stderr = log_file
        .try_clone()
        .with_context(|| format!("clone D2S log {}", log_path.display()))?;

    let mut cmd = Command::new(&bin);
    cmd.arg("--config")
        .arg(d2s_config)
        .arg("--dnscrypt-config")
        .arg(dnscrypt_toml)
        .arg("run")
        // D2S and T2S coordinate only through advisory files/flocks in the
        // same runtime tree used by root T2S. Derive it from the canonical
        // ZDT-D API directory so a future root-path change cannot split the
        // two coordinators. No D2S HTTP/API server is involved.
        .env(
            "ZDTD_BACKEND_COORD_DIR",
            Path::new(crate::settings::API_DIR).join("t2s").join("locks"),
        )
        .stdin(Stdio::null())
        .stdout(Stdio::from(log_file))
        .stderr(Stdio::from(log_stderr));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!("spawned d2s pid={} (listen={})", child.id(), listener);
    Ok(child)
}

pub(super) fn spawn_dnscrypt(toml_path: &Path, listen_port: u16) -> Result<Option<Child>> {
    let bin = Path::new(BIN_DIR).join("dnscrypt");
    if !bin.is_file() {
        anyhow::bail!("dnscrypt binary not found: {}", bin.display());
    }

    let mut cmd = Command::new(&bin);
    cmd.arg("-config")
        .arg(toml_path)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!("spawned dnscrypt pid={} (listen port={})", child.id(), listen_port);
    Ok(Some(child))
}

#[derive(Debug)]
pub(super) enum DnscryptWaitResult {
    Ready,
    Exited(ExitStatus),
    StopRequested,
}

pub(super) fn wait_dnscrypt_ready(listen_port: u16, child: &mut Child) -> Result<DnscryptWaitResult> {
    let mut delay = Duration::from_secs(5);

    loop {
        if stop_requested() {
            warn!("dnscrypt start aborted (stop requested)");
            return Ok(DnscryptWaitResult::StopRequested);
        }
        if let Some(status) = child.try_wait().context("check dnscrypt startup process")? {
            return Ok(DnscryptWaitResult::Exited(status));
        }

        let probe = dnscrypt_ready_once(listen_port);
        if let Some(status) = child.try_wait().context("check dnscrypt process after readiness probe")? {
            return Ok(DnscryptWaitResult::Exited(status));
        }

        match probe {
            Ok(true) => {
                info!("dnscrypt readiness confirmed on port {}", listen_port);
                return Ok(DnscryptWaitResult::Ready);
            }
            Ok(false) => {
                warn!(
                    "dnscrypt not ready yet on port {} -> retry in {:?}",
                    listen_port, delay
                );
                crate::logging::user_warn(&format!(
                    "DNSCrypt: ещё не отвечает, повтор через {:?}",
                    delay
                ));
            }
            Err(error) => {
                warn!(
                    "dnscrypt readiness probe failed on port {}: {error:#} -> retry in {:?}",
                    listen_port, delay
                );
                crate::logging::user_warn(&format!(
                    "DNSCrypt: ошибка проверки готовности, повтор через {:?}",
                    delay
                ));
            }
        }

        if let Some(status) = sleep_while_dnscrypt_running(child, delay)? {
            return Ok(DnscryptWaitResult::Exited(status));
        }
        delay += Duration::from_secs(10);
    }
}

pub(super) fn sleep_while_dnscrypt_running(child: &mut Child, delay: Duration) -> Result<Option<ExitStatus>> {
    let deadline = Instant::now() + delay;
    while Instant::now() < deadline {
        if stop_requested() {
            return Ok(None);
        }
        if let Some(status) = child.try_wait().context("check dnscrypt process during readiness delay")? {
            return Ok(Some(status));
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        std::thread::sleep(remaining.min(Duration::from_millis(250)));
    }
    Ok(None)
}

pub(super) fn stop_started_d2s(child: &mut Option<Child>) {
    let Some(mut process) = child.take() else { return; };
    let pid = process.id();
    match process.try_wait() {
        Ok(Some(status)) => {
            info!("d2s already exited with status {status}");
        }
        Ok(None) => {
            unsafe {
                let _ = libc::kill(pid as i32, libc::SIGTERM);
            }
            info!("stopping d2s pid={} because dnscrypt is not running", pid);
            crate::logging::user_warn("D2S: остановлен — DNSCrypt не запущен");
        }
        Err(error) => warn!("failed to inspect d2s pid={pid}: {error}"),
    }
}

pub(super) fn parse_active_d2s_listener(toml_path: &Path) -> Result<Option<SocketAddr>> {
    let raw = fs::read_to_string(toml_path)
        .with_context(|| format!("read {}", toml_path.display()))?;
    let value: toml::Value = toml::from_str(&raw)
        .with_context(|| format!("parse {}", toml_path.display()))?;
    let Some(proxy) = value.get("proxy").and_then(toml::Value::as_str) else {
        return Ok(None);
    };
    let proxy = proxy.trim();
    if proxy.is_empty() {
        return Ok(None);
    }
    parse_local_socks5_listener(proxy)
}

pub(super) fn parse_local_socks5_listener(proxy: &str) -> Result<Option<SocketAddr>> {
    let Some(endpoint) = proxy.strip_prefix("socks5://") else {
        return Ok(None);
    };
    if endpoint.is_empty()
        || endpoint.contains('@')
        || endpoint.contains('/')
        || endpoint.contains('?')
        || endpoint.contains('#')
    {
        anyhow::bail!("D2S proxy must be an unauthenticated SOCKS5 host:port");
    }

    let addr = if let Some(port) = endpoint.strip_prefix("localhost:") {
        let port = port.parse::<u16>().context("parse D2S localhost port")?;
        SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port)
    } else {
        endpoint
            .parse::<SocketAddr>()
            .with_context(|| format!("parse D2S SOCKS5 endpoint {endpoint}"))?
    };

    if addr.port() == 0 {
        anyhow::bail!("D2S proxy port must be greater than zero");
    }
    let loopback = matches!(addr.ip(), IpAddr::V4(ip) if ip == Ipv4Addr::LOCALHOST)
        || matches!(addr.ip(), IpAddr::V6(ip) if ip == Ipv6Addr::LOCALHOST);
    if !loopback {
        return Ok(None);
    }
    Ok(Some(addr))
}

pub(super) fn dnscrypt_ready_once(listen_port: u16) -> Result<bool> {
    let mut last_err: Option<anyhow::Error> = None;
    for target in ["127.0.0.1", "::1"] {
        match dns_probe(target, listen_port) {
            Ok(true) => return Ok(true),
            Ok(false) => {}
            Err(e) => last_err = Some(e),
        }
    }

    if let Some(e) = last_err {
        return Err(e);
    }
    Ok(false)
}

pub(super) fn dns_probe(host: &str, listen_port: u16) -> Result<bool> {
    let ip: IpAddr = host
        .parse()
        .with_context(|| format!("parse dns target host {}", host))?;
    let addr = SocketAddr::new(ip, listen_port);

    let socket = if addr.is_ipv4() {
        UdpSocket::bind(("0.0.0.0", 0)).context("bind udp4 probe socket")?
    } else {
        UdpSocket::bind(("::", 0)).context("bind udp6 probe socket")?
    };

    socket
        .set_read_timeout(Some(Duration::from_secs(2)))
        .context("set probe read timeout")?;

    let (id, packet) = build_dns_query();
    socket
        .send_to(&packet, addr)
        .with_context(|| format!("send dns probe to {}", addr))?;

    let mut buf = [0u8; 512];
    let (n, _) = match socket.recv_from(&mut buf) {
        Ok(v) => v,
        Err(_) => {
            return Ok(false);
        }
    };

    if n < 12 {
        return Ok(false);
    }

    let resp_id = u16::from_be_bytes([buf[0], buf[1]]);
    if resp_id != id {
        return Ok(false);
    }

    let flags = u16::from_be_bytes([buf[2], buf[3]]);
    let rcode = flags & 0x000F;
    if rcode == 0 || rcode == 3 {
        return Ok(true);
    }

    Ok(false)
}

pub(super) fn build_dns_query() -> (u16, Vec<u8>) {
    let id = fresh_dns_id();
    let mut buf = Vec::with_capacity(64);

    buf.extend_from_slice(&id.to_be_bytes());
    buf.extend_from_slice(&0x0100u16.to_be_bytes()); // recursion desired
    buf.extend_from_slice(&1u16.to_be_bytes()); // qdcount
    buf.extend_from_slice(&0u16.to_be_bytes()); // ancount
    buf.extend_from_slice(&0u16.to_be_bytes()); // nscount
    buf.extend_from_slice(&0u16.to_be_bytes()); // arcount

    let qname = format!("{}.example.com", fresh_label());
    for label in qname.split('.') {
        buf.push(label.len() as u8);
        buf.extend_from_slice(label.as_bytes());
    }
    buf.push(0);
    buf.extend_from_slice(&1u16.to_be_bytes()); // QTYPE A
    buf.extend_from_slice(&1u16.to_be_bytes()); // QCLASS IN

    (id, buf)
}

pub(super) fn fresh_dns_id() -> u16 {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .subsec_nanos();
    (nanos as u16) ^ ((nanos >> 16) as u16)
}

pub(super) fn fresh_label() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!("probe-{}", nanos)
}

pub(super) fn parse_listen_port(toml_path: &Path) -> Result<u16> {
    // Supported formats:
    // listen_addresses = ['127.0.0.1:PORT']
    // listen_addresses = ['[::1]:PORT']
    // listen_addresses = ['127.0.0.1:PORT', '[::1]:PORT']
    let s = fs::read_to_string(toml_path)
        .with_context(|| format!("read {}", toml_path.display()))?;

    for line in s.lines() {
        let line = line.trim();
        if !line.starts_with("listen_addresses") {
            continue;
        }

        let mut ports: Vec<u16> = Vec::new();
        let patterns = ["127.0.0.1:", "[::1]:", "::1:"];

        for pat in patterns {
            let mut start = 0usize;
            while let Some(rel) = line[start..].find(pat) {
                let pos = start + rel + pat.len();
                let tail = &line[pos..];
                let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
                if !digits.is_empty() {
                    let port: u16 = digits
                        .parse()
                        .with_context(|| format!("parse dnscrypt listen port from '{}'", line))?;
                    ports.push(port);
                }
                start = pos;
            }
        }

        if ports.is_empty() {
            break;
        }

        let first = ports[0];
        if ports.iter().any(|&p| p != first) {
            warn!(
                "dnscrypt listen_addresses has mismatched ports {:?} in line '{}' -> using {}",
                ports, line, first
            );
        }
        return Ok(first);
    }

    Ok(0)
}

