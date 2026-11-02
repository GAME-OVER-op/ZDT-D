use anyhow::{bail, Context, Result};

use crate::common::process::shell::{self, Capture};

use super::IP_TIMEOUT;

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

/// Resolves the IPv4 gateway from routes attached to a TUN interface. The
/// wording and timeout match the historical OpenVPN/AmneziaWG helpers.
pub fn route_gateway_for_tun(tun: &str) -> Result<String> {
    let (code, out) = shell::run_timeout(
        "ip",
        &["-4", "route", "show", "dev", tun],
        Capture::Stdout,
        IP_TIMEOUT,
    )?;
    if code != 0 {
        bail!("ip route show dev {tun} failed");
    }
    for line in out.lines() {
        let tokens = line.split_whitespace().collect::<Vec<_>>();
        for i in 0..tokens.len() {
            if tokens[i] == "via" {
                if let Some(via) = tokens.get(i + 1) {
                    if super::is_ipv4(via) {
                        return Ok((*via).to_string());
                    }
                }
            }
        }
    }
    bail!("no gateway via route for {tun}")
}
