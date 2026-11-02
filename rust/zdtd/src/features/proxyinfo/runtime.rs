use anyhow::{Context, Result};
use serde::Deserialize;
use std::{collections::BTreeSet, fs, net::Ipv4Addr, path::{Path, PathBuf}};

use crate::{logging, settings, common::process::shell::{self, Capture}};
use super::{
    ports::collect_protected_port_sets,
    rules::{clear_rules, install_rules, IPT_TIMEOUT},
    settings::{ensure_layout, load_enabled_json},
    uid::{read_out_uids, rebuild_out_program},
};

#[derive(Debug, Deserialize, Default)]
struct VpnNetdAppliedSnapshotLite {
    #[serde(default)]
    profiles: Vec<VpnNetdAppliedProfileLite>,
}

#[derive(Debug, Deserialize, Default)]
struct VpnNetdAppliedProfileLite {
    #[serde(default)]
    owner_program: String,
    #[serde(default)]
    profile: String,
    #[serde(default)]
    tun: String,
}
pub fn refresh_runtime(services_running: bool) -> Result<bool> {
    ensure_layout()?;
    let enabled = load_enabled_json()?.is_enabled();
    if !enabled {
        clear_rules()?;
        crate::features::scan_detector::stop();
        return Ok(false);
    }

    let _uids = rebuild_out_program()?;
    if !services_running {
        clear_rules()?;
        crate::features::scan_detector::stop();
        return Ok(false);
    }

    let uids = read_out_uids()?;
    if uids.is_empty() {
        clear_rules()?;
        crate::features::scan_detector::stop();
        return Ok(false);
    }
    let (local_ports, global_ports) = collect_protected_port_sets()?;
    let project_interface_ips = collect_project_interface_ips_v4()?;
    let local_list: Vec<u16> = local_ports.into_iter().collect();
    let global_list: Vec<u16> = global_ports.into_iter().collect();
    let project_interface_ip_list: Vec<Ipv4Addr> = project_interface_ips.into_iter().collect();
    install_rules(&uids, &local_list, &global_list, &project_interface_ip_list)?;
    logging::info(&format!(
        "proxyInfo active: {} uid(s), {} local IPv4 protected port(s), {} global upstream port(s), {} project interface IP block(s), IPv6 deny=full",
        uids.len(),
        local_list.len(),
        global_list.len(),
        project_interface_ip_list.len()
    ));
    Ok(true)
}

pub(super) fn is_protectable_project_ipv4(ip: Ipv4Addr) -> bool {
    // Never treat 0.0.0.0 as "all interfaces" here. If an owner program exposes
    // only 0.0.0.0/0, proxyInfo skips the interface-address protection for it.
    !ip.is_unspecified() && ip != Ipv4Addr::new(255, 255, 255, 255) && !ip.is_multicast() && !ip.is_loopback()
}

fn is_proxyinfo_project_vpn_owner(owner: &str) -> bool {
    matches!(
        owner.trim().to_ascii_lowercase().as_str(),
        "openvpn" | "myvpn" | "tun2socks" | "tun2proxy" | "mihomo" | "mieru" | "amneziawg"
    )
}

fn vpn_netd_applied_path() -> PathBuf {
    Path::new(settings::MODULE_DIR)
        .join("working_folder")
        .join("vpn_netd")
        .join("applied.json")
}

fn collect_project_interface_ips_v4() -> Result<BTreeSet<Ipv4Addr>> {
    let path = vpn_netd_applied_path();
    if !path.is_file() {
        return Ok(BTreeSet::new());
    }

    let raw = match fs::read_to_string(&path) {
        Ok(s) => s,
        Err(e) => {
            logging::warn(&format!(
                "proxyInfo: failed to read vpn_netd applied snapshot {}, project interface IP protection skipped: {e}",
                path.display()
            ));
            return Ok(BTreeSet::new());
        }
    };
    let snapshot: VpnNetdAppliedSnapshotLite = match serde_json::from_str(&raw) {
        Ok(v) => v,
        Err(e) => {
            logging::warn(&format!(
                "proxyInfo: failed to parse vpn_netd applied snapshot {}, project interface IP protection skipped: {e}",
                path.display()
            ));
            return Ok(BTreeSet::new());
        }
    };

    let mut out = BTreeSet::new();
    for profile in snapshot.profiles {
        if !is_proxyinfo_project_vpn_owner(&profile.owner_program) {
            continue;
        }
        let tun = profile.tun.trim();
        if tun.is_empty() {
            continue;
        }
        match collect_interface_ipv4_addrs(tun) {
            Ok(addrs) => {
                let mut added = 0usize;
                for ip in addrs {
                    if is_protectable_project_ipv4(ip) {
                        out.insert(ip);
                        added += 1;
                    } else {
                        logging::warn(&format!(
                            "proxyInfo: project interface IP {} for {}/{} ({}) is not protected",
                            ip, profile.owner_program, profile.profile, tun
                        ));
                    }
                }
                if added == 0 {
                    logging::warn(&format!(
                        "proxyInfo: no usable IPv4 address found for project interface {}/{} ({})",
                        profile.owner_program, profile.profile, tun
                    ));
                }
            }
            Err(e) => {
                logging::warn(&format!(
                    "proxyInfo: failed to read IPv4 address for project interface {}/{} ({}): {e:#}",
                    profile.owner_program, profile.profile, tun
                ));
            }
        }
    }

    if !out.is_empty() {
        logging::info(&format!(
            "proxyInfo: project interface IPv4 targets: {}",
            out.iter().map(|ip| ip.to_string()).collect::<Vec<_>>().join(",")
        ));
    }
    Ok(out)
}

fn collect_interface_ipv4_addrs(iface: &str) -> Result<Vec<Ipv4Addr>> {
    let (rc, out) = shell::run_timeout("ip", &["-o", "-4", "addr", "show", "dev", iface], Capture::Both, IPT_TIMEOUT)
        .with_context(|| format!("ip -o -4 addr show dev {iface}"))?;
    if rc != 0 {
        anyhow::bail!("ip -o -4 addr show dev {iface} failed rc={rc}: {out}");
    }
    Ok(parse_ip_o_addr_v4(&out))
}

fn parse_ip_o_addr_v4(raw: &str) -> Vec<Ipv4Addr> {
    let mut out = BTreeSet::new();
    for line in raw.lines() {
        let mut tokens = line.split_whitespace();
        while let Some(tok) = tokens.next() {
            if tok != "inet" {
                continue;
            }
            let Some(addr) = tokens.next() else { continue; };
            let ip_s = addr.split_once('/').map(|(ip, _)| ip).unwrap_or(addr);
            if let Ok(ip) = ip_s.parse::<Ipv4Addr>() {
                out.insert(ip);
            }
        }
    }
    out.into_iter().collect()
}
