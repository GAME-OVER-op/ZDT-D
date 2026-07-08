// Shared helper utilities for programs/* engines.
// These were previously duplicated verbatim across multiple program modules;
// consolidated here to remove copy-paste drift. Behavior is unchanged.

use std::fs;
use std::path::Path;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::collections::BTreeSet;
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{bail, Context, Result};

use crate::shell::{self, Capture};

/// Timeout for short-lived `ip` invocations (identical across all engines).
pub const IP_TIMEOUT: Duration = Duration::from_secs(3);

// from programs/amneziawg.rs
pub fn u32_to_ipv4(v: u32) -> String {
    format!("{}.{}.{}.{}", (v >> 24) & 0xff, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff)
}

// from programs/amneziawg.rs
pub fn ipv4_to_u32(s: &str) -> Option<u32> {
    let mut out = 0u32;
    let mut count = 0usize;
    for part in s.split('.') {
        let n = part.parse::<u8>().ok()? as u32;
        out = (out << 8) | n;
        count += 1;
    }
    if count == 4 { Some(out) } else { None }
}

// from programs/amneziawg.rs
pub fn is_valid_ifname(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 15
        && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
}

// from programs/byedpi.rs
pub fn count_valid_uid_pairs(path: &Path) -> Result<usize> {
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

// from programs/myprogram.rs
pub fn ensure_parent_dir(p: &Path) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    Ok(())
}

// from programs/amneziawg.rs
pub fn parse_pid_lines(out: &str) -> Vec<i32> {
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<i32>().ok())
        .filter(|p| *p > 1)
        .collect()
}

// from programs/singbox.rs
pub fn is_nonempty_file(p: &Path) -> Result<bool> {
    let md = fs::metadata(p).with_context(|| format!("stat {}", p.display()))?;
    Ok(md.len() > 0)
}

// from programs/myvpn.rs
pub fn enabled_app_list_empty(path: &Path) -> bool {
    fs::read_to_string(path)
        .map(|raw| raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')))
        .unwrap_or(true)
}

// from programs/dpitunnel.rs
pub fn default_iface() -> String {
    "auto".to_string()
}

// from programs/mihomo.rs
pub fn validate_loglevel(v: &str, field: &str) -> Result<()> {
    match v {
        "debug" | "info" | "warn" | "error" | "silent" => Ok(()),
        _ => bail!("{field} must be debug/info/warn/error/silent"),
    }
}

// from programs/amneziawg.rs
pub fn first_host_for_cidr(cidr: &str) -> Option<String> {
    let (ip, prefix_s) = cidr.split_once('/')?;
    let prefix = prefix_s.parse::<u8>().ok()?;
    if prefix > 30 { return None; }
    let net = ipv4_to_u32(ip)?;
    Some(u32_to_ipv4(net.saturating_add(1)))
}

// from programs/amneziawg.rs
pub fn cidr_network_mask(cidr: &str) -> Result<(u32, u32)> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 { bail!("bad cidr prefix {cidr}"); }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok((addr & mask, mask))
}

// from programs/byedpi.rs
pub fn normalize_config_args(raw: &str) -> Vec<String> {
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

// from programs/mieru.rs
pub fn configure_tun_addr(tun: &str, tun_addr: &str) -> Result<()> {
    let (code, out) = shell::run_timeout("ip", &["addr", "replace", tun_addr, "dev", tun], Capture::Both, IP_TIMEOUT)
        .with_context(|| format!("ip addr replace {tun_addr} dev {tun}"))?;
    if code != 0 { bail!("ip addr replace {tun_addr} dev {tun} failed: {}", out.trim()); }
    let (code, out) = shell::run_timeout("ip", &["link", "set", tun, "up"], Capture::Both, IP_TIMEOUT)
        .with_context(|| format!("ip link set {tun} up"))?;
    if code != 0 { bail!("ip link set {tun} up failed: {}", out.trim()); }
    Ok(())
}

// merged: identical body, per-engine range passed as params (was NETID_BASE/NETID_MAX)
pub fn generate_netid(used: &BTreeSet<u32>, base: u32, max: u32) -> Result<u32> {
    for id in base..=max {
        if !used.contains(&id) {
            return Ok(id);
        }
    }
    bail!("no free netid in range {base}..={max}")
}

// merged: identical body, per-engine timeout passed as param (was TUN_WAIT)
pub fn wait_tun_link(tun: &str, timeout: Duration) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= timeout {
            bail!("tun {tun} was not created after {:?}", timeout);
        }
        let (code, _) = shell::run_timeout("ip", &["link", "show", tun], Capture::Both, IP_TIMEOUT)
            .unwrap_or((1, String::new()));
        if code == 0 {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(300));
    }
}

// merged: identical body, per-engine timeout passed as param (was PORT_WAIT)
pub fn wait_tcp_port(host: &str, port: u16, timeout: Duration) -> Result<()> {
    let ip: IpAddr = host.parse().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
    let addr = SocketAddr::new(ip, port);
    let start = Instant::now();
    loop {
        if start.elapsed() >= timeout {
            bail!("127.0.0.1:{port} is not listening after {:?}", timeout);
        }
        if TcpStream::connect_timeout(&addr, Duration::from_millis(250)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(300));
    }
}

// merged: identical across all engines (mieru had it as a one-liner)
pub fn shell_quote_for_sh(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}
