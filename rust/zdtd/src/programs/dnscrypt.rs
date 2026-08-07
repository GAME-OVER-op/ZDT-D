use anyhow::{Context, Result};
use log::{info, warn};
use serde::Deserialize;
use std::{
    fs,
    io::Write,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket},
    path::Path,
    process::{Child, Command, ExitStatus, Stdio},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use std::os::unix::process::CommandExt;

use crate::{iptables::caps, shell::{self, Capture}, xtables_lock};

use std::sync::atomic::{AtomicBool, Ordering};

static DNSCRYPT_STOP_REQUESTED: AtomicBool = AtomicBool::new(false);

pub fn request_stop() {
    DNSCRYPT_STOP_REQUESTED.store(true, Ordering::SeqCst);
}

pub fn reset_stop() {
    DNSCRYPT_STOP_REQUESTED.store(false, Ordering::SeqCst);
}

fn stop_requested() -> bool {
    DNSCRYPT_STOP_REQUESTED.load(Ordering::SeqCst)
}

fn set_ipv6_disabled_resetprops() {
    let pairs = [
        ("net.ipv6.conf.all.accept_redirects", "0"),
        ("net.ipv6.conf.all.disable_ipv6", "1"),
        ("net.ipv6.conf.default.accept_redirects", "0"),
        ("net.ipv6.conf.default.disable_ipv6", "1"),
    ];
    for (k, v) in pairs {
        match shell::run("resetprop", &[k, v], Capture::Stdout) {
            Ok((0, _)) => {}
            Ok((code, out)) => warn!("dnscrypt: resetprop set {}={} failed with code {}: {}", k, v, code, out.trim()),
            Err(e) => warn!("dnscrypt: resetprop set {}={} failed: {e:#}", k, v),
        }
    }
}

pub fn clear_ipv6_resetprops() {
    for k in IPV6_RESETPROP_KEYS {
        match shell::run("resetprop", &[k], Capture::Stdout) {
            Ok((0, _)) => {}
            Ok((code, out)) => warn!("dnscrypt: resetprop clear {} failed with code {}: {}", k, code, out.trim()),
            Err(e) => warn!("dnscrypt: resetprop clear {} failed: {e:#}", k),
        }
    }
}

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const DNSCRYPT_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/dnscrypt";
const BIN_DIR: &str = "/data/adb/modules/ZDT-D/bin";

const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/dnscrypt/active.json";
const DNSCRYPT_TOML: &str =
    "/data/adb/modules/ZDT-D/working_folder/dnscrypt/setting/dnscrypt-proxy.toml";
const D2S_TOML: &str =
    "/data/adb/modules/ZDT-D/working_folder/dnscrypt/d2set/d2s.toml";
const D2S_LOG: &str =
    "/data/adb/modules/ZDT-D/working_folder/dnscrypt/log/d2s.log";
const D2S_MINIMAL_CONFIG: &str = "backends = []\ndirect_fallback = true\n";

const IPV6_RESETPROP_KEYS: [&str; 4] = [
    "net.ipv6.conf.all.accept_redirects",
    "net.ipv6.conf.all.disable_ipv6",
    "net.ipv6.conf.default.accept_redirects",
    "net.ipv6.conf.default.disable_ipv6",
];

#[derive(Debug, Deserialize)]
struct ActiveJson {
    enabled: bool,
}



pub fn ensure_d2s_config_exists() -> Result<()> {
    let path = Path::new(D2S_TOML);
    if path.is_file() {
        return Ok(());
    }

    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create D2S config directory {}", parent.display()))?;
    }

    match fs::OpenOptions::new().write(true).create_new(true).open(path) {
        Ok(mut file) => {
            file.write_all(D2S_MINIMAL_CONFIG.as_bytes())
                .with_context(|| format!("write minimal D2S config {}", path.display()))?;
            info!("created minimal D2S config: {}", path.display());
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists && path.is_file() => Ok(()),
        Err(error) => Err(error)
            .with_context(|| format!("create minimal D2S config {}", path.display())),
    }
}

pub fn active_listen_port() -> Result<Option<u16>> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(DNSCRYPT_ROOT)?;

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;

    if !active.enabled {
        return Ok(None);
    }

    let toml_path = Path::new(DNSCRYPT_TOML);
    if !toml_path.is_file() {
        return Ok(None);
    }

    let port = parse_listen_port(toml_path)?;
    if port == 0 {
        return Ok(None);
    }
    Ok(Some(port))
}

pub fn configured_d2s_listen_addr() -> Result<Option<SocketAddr>> {
    let toml_path = Path::new(DNSCRYPT_TOML);
    if !toml_path.is_file() {
        return Ok(None);
    }
    parse_active_d2s_listener(toml_path)
}

pub fn active_d2s_listen_addr() -> Result<Option<SocketAddr>> {
    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;
    if !active.enabled {
        return Ok(None);
    }

    configured_d2s_listen_addr()
}

pub fn active_d2s_listen_port() -> Result<Option<u16>> {
    Ok(active_d2s_listen_addr()?.map(|addr| addr.port()))
}

pub fn start_if_enabled() -> Result<()> {
    reset_stop();

    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(DNSCRYPT_ROOT)?;

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

    let mut d2s_child = match parse_active_d2s_listener(toml_path) {
        Ok(Some(listener)) => match spawn_d2s(toml_path, listener) {
            Ok(child) => Some(child),
            Err(error) => {
                warn!("d2s start failed: {error:#}");
                crate::logging::user_warn("D2S: ошибка запуска — запуск DNSCrypt продолжен");
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
            crate::logging::user_error("DNSCrypt: ошибка запуска — ожидание готовности прекращено");
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

fn spawn_d2s(dnscrypt_toml: &Path, listener: SocketAddr) -> Result<Child> {
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
        .stdin(Stdio::null())
        .stdout(Stdio::from(log_file))
        .stderr(Stdio::from(log_stderr));

    unsafe {
        cmd.pre_exec(|| {
            unsafe {
                let _ = libc::setsid();
            }
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!("spawned d2s pid={} (listen={})", child.id(), listener);
    Ok(child)
}

fn spawn_dnscrypt(toml_path: &Path, listen_port: u16) -> Result<Option<Child>> {
    let bin = Path::new(BIN_DIR).join("dnscrypt");
    if !bin.is_file() {
        warn!("dnscrypt enabled but binary not found: {} -> skip", bin.display());
        return Ok(None);
    }

    let mut cmd = Command::new(&bin);
    cmd.arg("-config")
        .arg(toml_path)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    unsafe {
        cmd.pre_exec(|| {
            unsafe {
                let _ = libc::setsid();
            }
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!("spawned dnscrypt pid={} (listen port={})", child.id(), listen_port);
    Ok(Some(child))
}

#[derive(Debug)]
enum DnscryptWaitResult {
    Ready,
    Exited(ExitStatus),
    StopRequested,
}

fn wait_dnscrypt_ready(listen_port: u16, child: &mut Child) -> Result<DnscryptWaitResult> {
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

fn sleep_while_dnscrypt_running(child: &mut Child, delay: Duration) -> Result<Option<ExitStatus>> {
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

fn stop_started_d2s(child: &mut Option<Child>) {
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

fn parse_active_d2s_listener(toml_path: &Path) -> Result<Option<SocketAddr>> {
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

fn parse_local_socks5_listener(proxy: &str) -> Result<Option<SocketAddr>> {
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

fn dnscrypt_ready_once(listen_port: u16) -> Result<bool> {
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

fn dns_probe(host: &str, listen_port: u16) -> Result<bool> {
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

fn build_dns_query() -> (u16, Vec<u8>) {
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

fn fresh_dns_id() -> u16 {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .subsec_nanos();
    (nanos as u16) ^ ((nanos >> 16) as u16)
}

fn fresh_label() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!("probe-{}", nanos)
}

fn parse_listen_port(toml_path: &Path) -> Result<u16> {
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

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";

fn supports_wait_fallback(out: &str) -> bool {
    let s = out.to_ascii_lowercase();
    s.contains("unknown option")
        || s.contains("unrecognized option")
        || s.contains("invalid option")
        || s.contains("illegal option")
        || s.contains("option requires an argument")
}

fn ipt_run(iptables: &str, args: &[&str], capture: Capture) -> Result<(i32, String)> {
    let effective_capture = match capture {
        Capture::None | Capture::Stdout | Capture::Stderr | Capture::Both => Capture::Both,
    };

    let mut with_wait: Vec<&str> = Vec::with_capacity(args.len() + 2);
    with_wait.push("-w");
    with_wait.push(XT_WAIT_SECS);
    with_wait.extend_from_slice(args);

    let first = xtables_lock::run_timeout_retry(iptables, &with_wait, effective_capture, IPT_CMD_TIMEOUT)?;
    if first.0 == 0 || !supports_wait_fallback(&first.1) {
        return Ok(first);
    }

    xtables_lock::run_timeout_retry(iptables, args, effective_capture, IPT_CMD_TIMEOUT)
}

fn add_per_port_rules(iptables: &str, table: &str, chain: &str, insert_first: bool, proto: &str, dns_ports: &[u16], tail: &[&str]) -> Result<()> {
    for p in dns_ports {
        let ps = p.to_string();
        let mut rule: Vec<String> = vec!["-p".into(), proto.into(), "--dport".into(), ps];
        rule.extend(tail.iter().map(|s| (*s).to_string()));
        let refs: Vec<&str> = rule.iter().map(|s| s.as_str()).collect();
        if !rule_exists(iptables, table, chain, &refs)? {
            let _ = add_rule(iptables, table, chain, insert_first, &refs)
                .or_else(|_| add_rule(iptables, table, chain, false, &refs));
        }
    }
    Ok(())
}

fn apply_dns_iptables(listen_port: u16) -> Result<()> {
    let _xtables_guard = xtables_lock::lock();
    let dns_ports = [53u16, 853u16, 5353u16];
    let dports_multi = "53,853,5353";
    let prots = ["udp", "tcp", "sctp"];
    let use_multiport = caps::multiport_v4();

    let ipt = find_iptables();

    // Ensure MANGLE_APP exists and OUTPUT jumps to it
    ensure_chain(&ipt, "mangle", "MANGLE_APP")?;
    ensure_jump(&ipt, "mangle", "OUTPUT", "MANGLE_APP", false)?;

    // Ensure NAT_DPI exists and OUTPUT jumps to it (needed if no other programs created it)
    ensure_chain(&ipt, "nat", "NAT_DPI")?;
    ensure_jump(&ipt, "nat", "OUTPUT", "NAT_DPI", true)?;

    // MANGLE DNS RETURN order is asserted after all NAT insertions below.
    // NAT rules are inserted at the beginning of NAT_DPI, so loopback/DNS
    // ordering is repaired once at the end instead of doing it twice.

    // 2) remove possible global DNAT in nat OUTPUT for our listen port
    for p in dns_ports {
        let ps = p.to_string();
        let to = format!("127.0.0.1:{}", listen_port);
        for proto in prots {
            let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
            if rule_exists(&ipt, "nat", "OUTPUT", &r)? {
                let _ = del_rule(&ipt, "nat", "OUTPUT", &r);
            }
        }
    }

    // Also remove legacy DNAT rules in PREROUTING (we no longer use PREROUTING in dnscrypt).
    for proto in prots {
        let to = format!("127.0.0.1:{}", listen_port);
        let mp = [
            "-p",
            proto,
            "-m",
            "multiport",
            "--dports",
            dports_multi,
            "-j",
            "DNAT",
            "--to-destination",
            &to,
        ];
        if rule_exists(&ipt, "nat", "PREROUTING", &mp)? {
            let _ = del_rule(&ipt, "nat", "PREROUTING", &mp);
        }
        for p in dns_ports {
            let ps = p.to_string();
            let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
            if rule_exists(&ipt, "nat", "PREROUTING", &r)? {
                let _ = del_rule(&ipt, "nat", "PREROUTING", &r);
            }
        }
    }

    // 3) add DNAT inside NAT_DPI only (no PREROUTING)
    for proto in prots {
        for chain in ["NAT_DPI"] {
            let to = format!("127.0.0.1:{}", listen_port);
            if use_multiport {
                let mp = [
                    "-p",
                    proto,
                    "-m",
                    "multiport",
                    "--dports",
                    dports_multi,
                    "-j",
                    "DNAT",
                    "--to-destination",
                    &to,
                ];
                if !rule_exists(&ipt, "nat", chain, &mp)? {
                    if add_rule(&ipt, "nat", chain, true, &mp).is_ok() {
                        info!("dns: nat added multiport DNAT ({}) {} -> {} in {}", dports_multi, proto, to, chain);
                    } else {
                        add_per_port_rules(&ipt, "nat", chain, true, proto, &dns_ports, &["-j", "DNAT", "--to-destination", &to])?;
                        info!("dns: nat multiport failed -> added per-port DNAT for {} in {}", proto, chain);
                    }
                }
            } else {
                add_per_port_rules(&ipt, "nat", chain, true, proto, &dns_ports, &["-j", "DNAT", "--to-destination", &to])?;
                info!("dns: nat multiport unsupported -> added per-port DNAT for {} in {}", proto, chain);
            }
        }
    }

    // Keep loopback first and DNS immediately after it in MANGLE_APP.
    // This is the primary owner of DNS ordering; nfqws mangle code only
    // keeps a safety guard before inserting NFQUEUE rules.
    ensure_loopback_returns(&ipt)?;
    ensure_dns_mangle_returns_ordered(&ipt, use_multiport, &dns_ports, dports_multi, &prots)?;

    // IPv6 (ip6tables) is best-effort: not all Android builds have ip6tables nat.
    if let Err(e) = apply_dns_ip6tables(listen_port) {
        warn!("dns: ip6tables rules skipped: {e:#}");
    }

    Ok(())
}

fn find_iptables() -> String {
    if let Ok((code, out)) = shell::run("sh", &["-c", "command -v iptables"], Capture::Stdout) {
        if code == 0 {
            let s = out.trim();
            if !s.is_empty() {
                return s.to_string();
            }
        }
    }
    for p in [
        "/system/bin/iptables",
        "/system/xbin/iptables",
        "/sbin/iptables",
        "/bin/iptables",
        "/usr/sbin/iptables",
    ] {
        if Path::new(p).is_file() {
            return p.to_string();
        }
    }
    "iptables".to_string()
}

fn find_ip6tables() -> String {
    if let Ok((code, out)) = shell::run("sh", &["-c", "command -v ip6tables"], Capture::Stdout) {
        if code == 0 {
            let s = out.trim();
            if !s.is_empty() {
                return s.to_string();
            }
        }
    }
    for p in [
        "/system/bin/ip6tables",
        "/system/xbin/ip6tables",
        "/sbin/ip6tables",
        "/bin/ip6tables",
        "/usr/sbin/ip6tables",
    ] {
        if Path::new(p).is_file() {
            return p.to_string();
        }
    }
    "ip6tables".to_string()
}

fn ip6_nat_supported(ip6t: &str) -> bool {
    match ipt_run(ip6t, &["-t", "nat", "-nL"], Capture::None) {
        Ok((c, _)) => c == 0,
        Err(_) => false,
    }
}

fn apply_dns_ip6tables(listen_port: u16) -> Result<()> {
    let dns_ports = [53u16, 853u16, 5353u16];
    let dports_multi = "53,853,5353";
    let prots = ["udp", "tcp", "sctp"];
    let use_multiport = caps::multiport_v6();

    let ip6t = find_ip6tables();

    // Ensure MANGLE_APP exists and OUTPUT jumps to it
    ensure_chain(&ip6t, "mangle", "MANGLE_APP")?;
    ensure_jump(&ip6t, "mangle", "OUTPUT", "MANGLE_APP", false)?;

    let nat_ok = ip6_nat_supported(&ip6t);
    if nat_ok {
        // Ensure NAT_DPI exists and OUTPUT jumps to it
        ensure_chain(&ip6t, "nat", "NAT_DPI")?;
        ensure_jump(&ip6t, "nat", "OUTPUT", "NAT_DPI", true)?;
    }

    // Keep loopback before position-3 IPv6 block rules only when the NAT table is
    // unavailable. For the NAT path loopback ordering is repaired once after all
    // NAT_DPI insertions, so we do not do the same work twice.
    if !nat_ok {
        ensure_loopback_returns_v6(&ip6t, false)?;
    }

    if nat_ok {
        // If we previously had a non-NAT build (blocked IPv6 DNS), remove those block rules now.
        for proto in prots {
            let mp_rej = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "REJECT"];
            if rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_rej)? {
                let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &mp_rej);
            }
            let mp_drop = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "DROP"];
            if rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_drop)? {
                let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &mp_drop);
            }
            for p in dns_ports {
                let ps = p.to_string();
                let r_rej = ["-p", proto, "--dport", &ps, "-j", "REJECT"];
                if rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_rej)? {
                    let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &r_rej);
                }
                let r_drop = ["-p", proto, "--dport", &ps, "-j", "DROP"];
                if rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_drop)? {
                    let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &r_drop);
                }
            }
        }

        // MANGLE DNS RETURN order is asserted after all NAT insertions below.

        let to = format!("[::1]:{}", listen_port);

        // 2) remove possible global DNAT in nat OUTPUT for our listen port
        for p in dns_ports {
            let ps = p.to_string();
            for proto in prots {
                let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
                if rule_exists(&ip6t, "nat", "OUTPUT", &r)? {
                    let _ = del_rule(&ip6t, "nat", "OUTPUT", &r);
                }
            }
        }

        // Also remove legacy DNAT rules in PREROUTING (we no longer use PREROUTING in dnscrypt).
        for proto in prots {
            let mp = [
                "-p",
                proto,
                "-m",
                "multiport",
                "--dports",
                dports_multi,
                "-j",
                "DNAT",
                "--to-destination",
                &to,
            ];
            if rule_exists(&ip6t, "nat", "PREROUTING", &mp)? {
                let _ = del_rule(&ip6t, "nat", "PREROUTING", &mp);
            }
            for p in dns_ports {
                let ps = p.to_string();
                let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
                if rule_exists(&ip6t, "nat", "PREROUTING", &r)? {
                    let _ = del_rule(&ip6t, "nat", "PREROUTING", &r);
                }
            }
        }

        // 3) add DNAT inside NAT_DPI only (no PREROUTING)
        for proto in prots {
            if use_multiport {
                let mp = [
                    "-p",
                    proto,
                    "-m",
                    "multiport",
                    "--dports",
                    dports_multi,
                    "-j",
                    "DNAT",
                    "--to-destination",
                    &to,
                ];
                if !rule_exists(&ip6t, "nat", "NAT_DPI", &mp)? {
                    if add_rule(&ip6t, "nat", "NAT_DPI", true, &mp).is_ok() {
                        info!(
                            "dns6: nat added multiport DNAT ({}) {} -> {} in NAT_DPI",
                            dports_multi, proto, to
                        );
                    } else {
                        add_per_port_rules(&ip6t, "nat", "NAT_DPI", true, proto, &dns_ports, &["-j", "DNAT", "--to-destination", &to])?;
                        info!(
                            "dns6: nat multiport failed -> added per-port DNAT for {} in NAT_DPI",
                            proto
                        );
                    }
                }
            } else {
                add_per_port_rules(&ip6t, "nat", "NAT_DPI", true, proto, &dns_ports, &["-j", "DNAT", "--to-destination", &to])?;
                info!(
                    "dns6: nat multiport unsupported -> added per-port DNAT for {} in NAT_DPI",
                    proto
                );
            }
        }
    } else {
        warn!("dns: ip6tables nat table unsupported; disabling IPv6 DNS ports (53/853/5353)");
        crate::logging::user_warn("DNSCrypt: IPv6 NAT не поддерживается — IPv6 DNS-порты 53/853/5353 отключены");
        set_ipv6_disabled_resetprops();

        // Remove RETURN exceptions for 53 (otherwise they would bypass the block).
        for proto in prots {
            let mp_ret = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "RETURN"];
            if rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_ret)? {
                let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &mp_ret);
            }
            for p in dns_ports {
                let ps = p.to_string();
                let r = ["-p", proto, "--dport", &ps, "-j", "RETURN"];
                if rule_exists(&ip6t, "mangle", "MANGLE_APP", &r)? {
                    let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &r);
                }
            }
        }

        // Add blocking rules at position 3 (after loopback RETURN rules).
        for proto in prots {
            if use_multiport {
                let mp_rej = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "REJECT"];
                let mp_drop = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "DROP"];

                if !rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_rej)? && !rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_drop)? {
                    let res = add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &mp_rej)
                        .or_else(|_| add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &mp_drop));

                    if res.is_ok() {
                        info!("dns6: blocked IPv6 DNS {} ports {}", proto, dports_multi);
                    } else {
                        for p in dns_ports {
                            let ps = p.to_string();
                            let r_rej = ["-p", proto, "--dport", &ps, "-j", "REJECT"];
                            let r_drop = ["-p", proto, "--dport", &ps, "-j", "DROP"];
                            if !rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_rej)? && !rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_drop)? {
                                let _ = add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &r_rej)
                                    .or_else(|_| add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &r_drop));
                            }
                        }
                        info!("dns6: blocked IPv6 DNS {} ports (per-port)", proto);
                    }
                }
            } else {
                for p in dns_ports {
                    let ps = p.to_string();
                    let r_rej = ["-p", proto, "--dport", &ps, "-j", "REJECT"];
                    let r_drop = ["-p", proto, "--dport", &ps, "-j", "DROP"];
                    if !rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_rej)? && !rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_drop)? {
                        let _ = add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &r_rej)
                            .or_else(|_| add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &r_drop));
                    }
                }
                info!("dns6: multiport unsupported -> blocked IPv6 DNS {} ports (per-port)", proto);
            }
        }
    }

    ensure_loopback_returns_v6(&ip6t, nat_ok)?;
    if nat_ok {
        ensure_dns_mangle_returns_ordered(&ip6t, use_multiport, &dns_ports, dports_multi, &prots)?;
    }
    Ok(())
}




fn ensure_dns_mangle_returns_ordered(
    iptables: &str,
    use_multiport: bool,
    dns_ports: &[u16],
    dports_multi: &str,
    prots: &[&str],
) -> Result<()> {
    // Keep DNS/DoT/mDNS before service/proxy UID exclusions and before NFQUEUE,
    // but avoid delete/reinsert work when the chain is already in the desired
    // shape. This path is called by DNSCrypt and can also run after nfqws has
    // prepared MANGLE_APP, so the check-first path keeps restarts cheap.
    if dns_mangle_returns_already_ordered(iptables, use_multiport, dns_ports, dports_multi, prots)? {
        return Ok(());
    }

    for proto in prots {
        let mp = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "RETURN"];
        del_rule_all(iptables, "mangle", "MANGLE_APP", &mp)?;
        for p in dns_ports {
            let ps = p.to_string();
            let per_port = ["-p", proto, "--dport", ps.as_str(), "-j", "RETURN"];
            del_rule_all(iptables, "mangle", "MANGLE_APP", &per_port)?;
        }
    }

    let mut pos = 3usize;
    // Match the visible table order used by the diagnostics notes.
    for proto in ["sctp", "tcp", "udp"] {
        if !prots.iter().any(|p| *p == proto) {
            continue;
        }
        if use_multiport {
            let mp = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "RETURN"];
            match add_rule_pos(iptables, "mangle", "MANGLE_APP", pos, &mp) {
                Ok(()) => {
                    pos += 1;
                    continue;
                }
                Err(e) => {
                    warn!("dns: ordered mangle multiport RETURN failed for {proto}: {e:#}; falling back to per-port");
                }
            }
        }

        for p in dns_ports {
            let ps = p.to_string();
            let per_port = ["-p", proto, "--dport", ps.as_str(), "-j", "RETURN"];
            if add_rule_pos(iptables, "mangle", "MANGLE_APP", pos, &per_port).is_ok() {
                pos += 1;
            }
        }
    }
    Ok(())
}

fn dns_mangle_returns_already_ordered(
    iptables: &str,
    use_multiport: bool,
    dns_ports: &[u16],
    dports_multi: &str,
    prots: &[&str],
) -> Result<bool> {
    let (rc, out) = ipt_run(iptables, &["-t", "mangle", "-S", "MANGLE_APP"], Capture::Stdout)?;
    if rc != 0 {
        return Ok(false);
    }

    let expected = dns_return_lines(true, use_multiport, dns_ports, dports_multi, prots);
    let known = dns_return_lines(false, true, dns_ports, dports_multi, prots)
        .into_iter()
        .chain(dns_return_lines(false, false, dns_ports, dports_multi, prots))
        .collect::<Vec<_>>();
    let chain_lines: Vec<&str> = out
        .lines()
        .map(str::trim)
        .filter(|line| line.starts_with("-A MANGLE_APP "))
        .collect();

    if chain_lines.len() < 2 + expected.len() {
        return Ok(false);
    }
    for (idx, expected_line) in expected.iter().enumerate() {
        if chain_lines.get(idx + 2).copied() != Some(expected_line.as_str()) {
            return Ok(false);
        }
    }
    for line in chain_lines.iter().skip(2 + expected.len()) {
        if known.iter().any(|known_line| *line == known_line.as_str()) {
            return Ok(false);
        }
    }
    Ok(true)
}

fn dns_return_lines(
    expected_only: bool,
    use_multiport: bool,
    dns_ports: &[u16],
    dports_multi: &str,
    prots: &[&str],
) -> Vec<String> {
    let mut lines = Vec::new();
    for proto in ["sctp", "tcp", "udp"] {
        if !prots.iter().any(|p| *p == proto) {
            continue;
        }
        if use_multiport {
            lines.push(format!(
                "-A MANGLE_APP -p {proto} -m multiport --dports {dports_multi} -j RETURN"
            ));
            if expected_only {
                continue;
            }
        }
        for p in dns_ports {
            lines.push(format!("-A MANGLE_APP -p {proto} --dport {p} -j RETURN"));
        }
    }
    lines
}

fn del_rule_all(iptables: &str, table: &str, chain: &str, rule: &[&str]) -> Result<()> {
    loop {
        let mut args = vec!["-t", table, "-D", chain];
        args.extend_from_slice(rule);
        let (code, _) = ipt_run(iptables, &args, Capture::Stdout)
            .with_context(|| format!("iptables del repeated rule {} {}", table, chain))?;
        if code != 0 {
            break;
        }
    }
    Ok(())
}

fn ensure_chain(iptables: &str, table: &str, chain: &str) -> Result<()> {
    let (code, _) = ipt_run(iptables, &["-t", table, "-nL", chain], Capture::Stdout)
        .with_context(|| format!("iptables list chain {} {}", table, chain))?;
    if code != 0 {
        let (c2, _) = ipt_run(iptables, &["-t", table, "-N", chain], Capture::Stdout)
            .with_context(|| format!("iptables create chain {} {}", table, chain))?;
        if c2 != 0 {
            anyhow::bail!("failed to create chain {} in table {}", chain, table);
        }
    }
    Ok(())
}

fn ensure_jump(iptables: &str, table: &str, from_chain: &str, to_chain: &str, insert_first: bool) -> Result<()> {
    let (code, _) = ipt_run(iptables, &["-t", table, "-C", from_chain, "-j", to_chain], Capture::Stdout)
        .unwrap_or((1, String::new()));
    if code == 0 {
        return Ok(());
    }
    let args: Vec<&str> = if insert_first {
        vec!["-t", table, "-I", from_chain, "1", "-j", to_chain]
    } else {
        vec!["-t", table, "-A", from_chain, "-j", to_chain]
    };
    let (c2, _) = ipt_run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables add jump {}:{} -> {}", table, from_chain, to_chain))?;
    if c2 != 0 {
        anyhow::bail!("failed to add jump {}:{} -> {}", table, from_chain, to_chain);
    }
    Ok(())
}

fn rule_exists(iptables: &str, table: &str, chain: &str, rule: &[&str]) -> Result<bool> {
    let mut args = vec!["-t", table, "-C", chain];
    args.extend_from_slice(rule);
    let (code, _) = ipt_run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables -C {} {}", table, chain))?;
    Ok(code == 0)
}

fn add_rule(iptables: &str, table: &str, chain: &str, insert_first: bool, rule: &[&str]) -> Result<()> {
    let mut args = vec!["-t", table, if insert_first { "-I" } else { "-A" }, chain];
    if insert_first {
        args.push("1");
    }
    args.extend_from_slice(rule);
    let (code, _) = ipt_run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables add rule {} {}", table, chain))?;
    if code != 0 {
        anyhow::bail!("iptables add rule failed {} {}", table, chain);
    }
    Ok(())
}

fn add_rule_pos(iptables: &str, table: &str, chain: &str, pos: usize, rule: &[&str]) -> Result<()> {
    let pos_s = pos.to_string();
    let mut args = vec!["-t", table, "-I", chain, &pos_s];
    args.extend_from_slice(rule);
    let (code, _) = ipt_run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables add rule(pos) {} {}", table, chain))?;
    if code != 0 {
        anyhow::bail!("iptables add rule(pos) failed {} {}", table, chain);
    }
    Ok(())
}

fn del_rule(iptables: &str, table: &str, chain: &str, rule: &[&str]) -> Result<()> {
    let mut args = vec!["-t", table, "-D", chain];
    args.extend_from_slice(rule);
    let (code, _) = ipt_run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables del rule {} {}", table, chain))?;
    if code != 0 {
        anyhow::bail!("iptables del rule failed {} {}", table, chain);
    }
    Ok(())
}

fn ensure_loopback_returns(iptables: &str) -> Result<()> {
    ensure_ordered_return_prefix(
        iptables,
        "nat",
        "NAT_DPI",
        &[
            &["-o", "lo", "-j", "RETURN"],
            &["-d", "127.0.0.0/8", "-j", "RETURN"],
        ],
        &[
            &["-d", "127.0.0.1", "-j", "RETURN"],
        ],
    )?;
    ensure_ordered_return_prefix(
        iptables,
        "mangle",
        "MANGLE_APP",
        &[
            &["-o", "lo", "-j", "RETURN"],
            &["-d", "127.0.0.0/8", "-j", "RETURN"],
        ],
        &[
            &["-d", "127.0.0.1", "-j", "RETURN"],
        ],
    )
}

fn ensure_loopback_returns_v6(ip6tables: &str, nat_ok: bool) -> Result<()> {
    if nat_ok {
        ensure_ordered_return_prefix(
            ip6tables,
            "nat",
            "NAT_DPI",
            &[
                &["-o", "lo", "-j", "RETURN"],
                &["-d", "::1/128", "-j", "RETURN"],
            ],
            &[
                &["-d", "::1", "-j", "RETURN"],
            ],
        )?;
    }
    ensure_ordered_return_prefix(
        ip6tables,
        "mangle",
        "MANGLE_APP",
        &[
            &["-o", "lo", "-j", "RETURN"],
            &["-d", "::1/128", "-j", "RETURN"],
        ],
        &[
            &["-d", "::1", "-j", "RETURN"],
        ],
    )
}

fn ensure_ordered_return_prefix(
    iptables: &str,
    table: &str,
    chain: &str,
    expected: &[&[&str]],
    legacy: &[&[&str]],
) -> Result<()> {
    if return_prefix_already_ordered(iptables, table, chain, expected, legacy)? {
        return Ok(());
    }

    for rule in legacy {
        del_rule_all(iptables, table, chain, rule)?;
    }
    for rule in expected {
        del_rule_all(iptables, table, chain, rule)?;
    }
    for (idx, rule) in expected.iter().enumerate() {
        add_rule_pos(iptables, table, chain, idx + 1, rule)?;
    }
    Ok(())
}

fn return_prefix_already_ordered(
    iptables: &str,
    table: &str,
    chain: &str,
    expected: &[&[&str]],
    legacy: &[&[&str]],
) -> Result<bool> {
    let (rc, out) = ipt_run(iptables, &["-t", table, "-S", chain], Capture::Stdout)?;
    if rc != 0 {
        return Ok(false);
    }

    let expected_lines: Vec<String> = expected
        .iter()
        .map(|tail| format!("-A {chain} {}", tail.join(" ")))
        .collect();
    let legacy_lines: Vec<String> = legacy
        .iter()
        .map(|tail| format!("-A {chain} {}", tail.join(" ")))
        .collect();
    let chain_lines: Vec<&str> = out
        .lines()
        .map(str::trim)
        .filter(|line| line.starts_with("-A "))
        .collect();

    if chain_lines.len() < expected_lines.len() {
        return Ok(false);
    }
    for (idx, expected_line) in expected_lines.iter().enumerate() {
        if chain_lines.get(idx).copied() != Some(expected_line.as_str()) {
            return Ok(false);
        }
    }
    for line in chain_lines.iter().skip(expected_lines.len()) {
        if expected_lines.iter().any(|expected_line| *line == expected_line.as_str())
            || legacy_lines.iter().any(|legacy_line| *line == legacy_line.as_str())
        {
            return Ok(false);
        }
    }
    Ok(true)
}


fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    crate::jsonfs::read_json(path)
}

fn ensure_dir(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_dir() {
        anyhow::bail!("directory missing: {}", path.display());
    }
    Ok(())
}
