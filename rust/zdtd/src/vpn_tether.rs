use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{fs, net::Ipv4Addr, path::{Path, PathBuf}, str::FromStr, time::Duration};

use crate::{settings, shell::{self, Capture}, xtables_lock};

const RUNTIME_DIR: &str = "/data/adb/modules/ZDT-D/working_folder/vpn_tether";
const TABLE_ID: u32 = 28600;
const RULE_PREF: u32 = 28600;
const IP_TIMEOUT: Duration = Duration::from_secs(3);
const IPT_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";

const FWD_CHAIN: &str = "ZDT_VPN_TETHER_FWD";
const NAT_CHAIN: &str = "ZDT_VPN_TETHER_POST";
const DNS_CHAIN: &str = "ZDT_VPN_TETHER_DNS";
const MSS_CHAIN: &str = "ZDT_VPN_TETHER_MSS";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VpnTetherProfile {
    pub owner_program: String,
    pub profile: String,
    pub tun: String,
    pub cidr: String,
    #[serde(default)]
    pub gateway: Option<String>,
    #[serde(default)]
    pub dns: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct AppliedState {
    profile: Option<VpnTetherProfile>,
    ifaces: Vec<String>,
    table_id: u32,
    rule_pref: u32,
}

pub fn applied_state_path() -> PathBuf {
    Path::new(RUNTIME_DIR).join("applied.json")
}

fn state_path() -> PathBuf {
    applied_state_path()
}

fn ensure_dir() -> Result<()> {
    fs::create_dir_all(RUNTIME_DIR).with_context(|| format!("mkdir {RUNTIME_DIR}"))
}

fn write_state(state: &AppliedState) -> Result<()> {
    ensure_dir()?;
    fs::write(state_path(), serde_json::to_string_pretty(state)?).context("write vpn_tether state")
}

fn ip(args: &[&str], capture: Capture) -> Result<(i32, String)> {
    shell::run_timeout("ip", args, capture, IP_TIMEOUT)
}

fn ipt(args: &[String], capture: Capture) -> Result<(i32, String)> {
    let mut full = Vec::with_capacity(args.len() + 2);
    full.push("-w".to_string());
    full.push(XT_WAIT_SECS.to_string());
    full.extend_from_slice(args);
    xtables_lock::runv_timeout_retry("iptables", &full, capture, IPT_TIMEOUT)
}

fn ipt_ok(args: Vec<String>, what: &str) -> Result<()> {
    let (rc, out) = ipt(&args, Capture::Both).with_context(|| what.to_string())?;
    if rc != 0 {
        bail!("{what} failed rc={} out={}", rc, out.trim());
    }
    Ok(())
}

fn ensure_chain(table: Option<&str>, chain: &str) -> Result<()> {
    let mut check = Vec::<String>::new();
    if let Some(t) = table { check.extend(["-t".into(), t.into()]); }
    check.extend(["-L".into(), chain.into(), "-n".into()]);
    let (rc, _) = ipt(&check, Capture::None)?;
    if rc != 0 {
        let mut create = Vec::<String>::new();
        if let Some(t) = table { create.extend(["-t".into(), t.into()]); }
        create.extend(["-N".into(), chain.into()]);
        ipt_ok(create, &format!("create {chain}"))?;
    }
    let mut flush = Vec::<String>::new();
    if let Some(t) = table { flush.extend(["-t".into(), t.into()]); }
    flush.extend(["-F".into(), chain.into()]);
    ipt_ok(flush, &format!("flush {chain}"))
}

fn ensure_hook(table: Option<&str>, from: &str, chain: &str) -> Result<()> {
    let mut check = Vec::<String>::new();
    if let Some(t) = table { check.extend(["-t".into(), t.into()]); }
    check.extend(["-C".into(), from.into(), "-j".into(), chain.into()]);
    let (rc, _) = ipt(&check, Capture::None)?;
    if rc == 0 { return Ok(()); }
    let mut add = Vec::<String>::new();
    if let Some(t) = table { add.extend(["-t".into(), t.into()]); }
    add.extend(["-I".into(), from.into(), "1".into(), "-j".into(), chain.into()]);
    ipt_ok(add, &format!("hook {from} -> {chain}"))
}

fn delete_hook_loop(table: Option<&str>, from: &str, chain: &str) {
    loop {
        let mut del = Vec::<String>::new();
        if let Some(t) = table { del.extend(["-t".into(), t.into()]); }
        del.extend(["-D".into(), from.into(), "-j".into(), chain.into()]);
        match ipt(&del, Capture::Both) {
            Ok((0, _)) => continue,
            _ => break,
        }
    }
}

fn delete_chain(table: Option<&str>, chain: &str) {
    let mut f = Vec::<String>::new();
    if let Some(t) = table { f.extend(["-t".into(), t.into()]); }
    f.extend(["-F".into(), chain.into()]);
    let _ = ipt(&f, Capture::Both);
    let mut x = Vec::<String>::new();
    if let Some(t) = table { x.extend(["-t".into(), t.into()]); }
    x.extend(["-X".into(), chain.into()]);
    let _ = ipt(&x, Capture::Both);
}

pub fn sync(profile: Option<VpnTetherProfile>) -> Result<()> {
    cleanup()?;
    let Some(profile) = profile else {
        info!("vpn_tether: disabled");
        return Ok(());
    };

    let result = (|| -> Result<()> {
        let ifaces = detect_tether_ifaces()?;
        if ifaces.is_empty() {
            warn!("vpn_tether: no active tether interface detected; rules were not applied");
            return Ok(());
        }

        ensure_vpn_tun_ready(&profile.tun)?;
        apply_route_table(&profile)?;
        apply_ip_rules(&ifaces)?;

        let _guard = xtables_lock::lock();
        ensure_chain(None, FWD_CHAIN)?;
        ensure_chain(Some("nat"), NAT_CHAIN)?;
        ensure_chain(Some("nat"), DNS_CHAIN)?;
        ensure_chain(Some("mangle"), MSS_CHAIN)?;

        ensure_hook(None, "FORWARD", FWD_CHAIN)?;
        ensure_hook(Some("nat"), "POSTROUTING", NAT_CHAIN)?;
        ensure_hook(Some("nat"), "PREROUTING", DNS_CHAIN)?;
        ensure_hook(Some("mangle"), "FORWARD", MSS_CHAIN)?;

        for iface in &ifaces {
            ipt_ok(vec!["-A".into(), FWD_CHAIN.into(), "-i".into(), iface.clone(), "-o".into(), profile.tun.clone(), "-j".into(), "ACCEPT".into()], "vpn_tether forward client to vpn")?;
            ipt_ok(vec!["-A".into(), FWD_CHAIN.into(), "-i".into(), profile.tun.clone(), "-o".into(), iface.clone(), "-m".into(), "conntrack".into(), "--ctstate".into(), "ESTABLISHED,RELATED".into(), "-j".into(), "ACCEPT".into()], "vpn_tether forward vpn to client")?;
        }

        ipt_ok(vec!["-t".into(), "nat".into(), "-A".into(), NAT_CHAIN.into(), "-o".into(), profile.tun.clone(), "-j".into(), "MASQUERADE".into()], "vpn_tether masquerade")?;
        ipt_ok(vec!["-t".into(), "mangle".into(), "-A".into(), MSS_CHAIN.into(), "-o".into(), profile.tun.clone(), "-p".into(), "tcp".into(), "--tcp-flags".into(), "SYN,RST".into(), "SYN".into(), "-j".into(), "TCPMSS".into(), "--clamp-mss-to-pmtu".into()], "vpn_tether mss clamp")?;

        let dns = first_ipv4_dns(&profile).unwrap_or_else(|| "1.1.1.1".to_string());
        for iface in &ifaces {
            ipt_ok(vec!["-t".into(), "nat".into(), "-A".into(), DNS_CHAIN.into(), "-i".into(), iface.clone(), "-p".into(), "udp".into(), "--dport".into(), "53".into(), "-j".into(), "DNAT".into(), "--to-destination".into(), dns.clone()], "vpn_tether DNS udp redirect")?;
            ipt_ok(vec!["-t".into(), "nat".into(), "-A".into(), DNS_CHAIN.into(), "-i".into(), iface.clone(), "-p".into(), "tcp".into(), "--dport".into(), "53".into(), "-j".into(), "DNAT".into(), "--to-destination".into(), dns.clone()], "vpn_tether DNS tcp redirect")?;
        }

        write_state(&AppliedState { profile: Some(profile.clone()), ifaces: ifaces.clone(), table_id: TABLE_ID, rule_pref: RULE_PREF })?;
        info!("vpn_tether: applied {}/{} tun={} ifaces={} table={} dns={}", profile.owner_program, profile.profile, profile.tun, ifaces.join(","), TABLE_ID, dns);
        Ok(())
    })();

    if result.is_err() {
        let _ = cleanup();
    }
    result
}

pub fn cleanup() -> Result<()> {
    cleanup_ip_rules();
    let _ = ip(&["-4", "route", "flush", "table", &TABLE_ID.to_string()], Capture::Both);
    let _ = ip(&["route", "flush", "cache"], Capture::Both);

    let _guard = xtables_lock::lock();
    delete_hook_loop(None, "FORWARD", FWD_CHAIN);
    delete_hook_loop(Some("nat"), "POSTROUTING", NAT_CHAIN);
    delete_hook_loop(Some("nat"), "PREROUTING", DNS_CHAIN);
    delete_hook_loop(Some("mangle"), "FORWARD", MSS_CHAIN);
    delete_chain(None, FWD_CHAIN);
    delete_chain(Some("nat"), NAT_CHAIN);
    delete_chain(Some("nat"), DNS_CHAIN);
    delete_chain(Some("mangle"), MSS_CHAIN);
    let _ = fs::remove_file(state_path());
    let _ = fs::remove_dir(RUNTIME_DIR);
    Ok(())
}

fn cleanup_ip_rules() {
    let Ok((rc, out)) = ip(&["-4", "rule", "show"], Capture::Stdout) else { return; };
    if rc != 0 { return; }
    for line in out.lines() {
        if !(line.contains(&format!("lookup {TABLE_ID}")) && line.contains(" iif ")) { continue; }
        let pref = line.split(':').next().unwrap_or("").trim().to_string();
        let iface = line.split_whitespace().collect::<Vec<_>>().windows(2)
            .find_map(|w| if w[0] == "iif" { Some(w[1].to_string()) } else { None });
        if let Some(iface) = iface {
            if !pref.is_empty() {
                let _ = ip(&["-4", "rule", "del", "pref", &pref, "iif", &iface, "lookup", &TABLE_ID.to_string()], Capture::Both);
            }
            let _ = ip(&["-4", "rule", "del", "iif", &iface, "lookup", &TABLE_ID.to_string()], Capture::Both);
        }
    }
}

fn apply_route_table(profile: &VpnTetherProfile) -> Result<()> {
    let table = TABLE_ID.to_string();

    let cidr = profile.cidr.trim();
    if !cidr.is_empty() {
        let (rc, out) = ip(&["-4", "route", "replace", cidr, "dev", &profile.tun, "table", &table], Capture::Both)?;
        if rc != 0 {
            warn!(
                "vpn_tether: connected route setup failed cidr={} dev={} table={}: {}",
                cidr,
                profile.tun,
                table,
                out.trim()
            );
        }
    }

    if let Some(gw) = profile.gateway.as_deref().map(str::trim).filter(|v| !v.is_empty()) {
        let (rc, out) = ip(&["-4", "route", "replace", "default", "via", gw, "dev", &profile.tun, "table", &table], Capture::Both)?;
        if rc == 0 {
            let _ = ip(&["route", "flush", "cache"], Capture::Both);
            return Ok(());
        }

        warn!(
            "vpn_tether: default route via gateway failed gw={} dev={} table={}: {}; fallback to default dev",
            gw,
            profile.tun,
            table,
            out.trim()
        );
    }

    let (rc, out) = ip(&["-4", "route", "replace", "default", "dev", &profile.tun, "table", &table], Capture::Both)?;
    if rc != 0 {
        bail!("vpn_tether: route table setup failed: {}", out.trim());
    }

    let _ = ip(&["route", "flush", "cache"], Capture::Both);
    Ok(())
}

fn apply_ip_rules(ifaces: &[String]) -> Result<()> {
    for iface in ifaces {
        let pref = RULE_PREF.to_string();
        let table = TABLE_ID.to_string();
        let (rc, out) = ip(&["-4", "rule", "add", "pref", &pref, "iif", iface, "lookup", &table], Capture::Both)?;
        if rc != 0 && !out.to_ascii_lowercase().contains("file exists") {
            bail!("vpn_tether: ip rule add failed iface={iface}: {}", out.trim());
        }
    }
    Ok(())
}

fn ensure_vpn_tun_ready(tun: &str) -> Result<()> {
    let (rc, out) = ip(&["-o", "-4", "addr", "show", "dev", tun], Capture::Stdout)?;
    if rc != 0 || out.trim().is_empty() {
        bail!("vpn_tether: VPN tun {tun} has no IPv4 address");
    }
    Ok(())
}

fn first_ipv4_dns(profile: &VpnTetherProfile) -> Option<String> {
    profile.dns.iter()
        .map(|s| s.trim())
        .find(|s| s.parse::<Ipv4Addr>().is_ok())
        .map(|s| s.to_string())
}

fn detect_tether_ifaces() -> Result<Vec<String>> {
    let (rc, out) = ip(&["-o", "-4", "addr", "show", "up", "scope", "global"], Capture::Stdout)?;
    if rc != 0 { return Ok(Vec::new()); }
    let mut out_ifaces = Vec::<String>::new();
    for line in out.lines() {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 4 { continue; }
        let iface = parts[1].trim_end_matches(':').split('@').next().unwrap_or(parts[1]).to_string();
        if !looks_like_tether_iface(&iface) { continue; }
        let Some(pos) = parts.iter().position(|p| *p == "inet") else { continue; };
        let Some(cidr) = parts.get(pos + 1) else { continue; };
        let ip_s = cidr.split('/').next().unwrap_or("");
        let Ok(ip) = Ipv4Addr::from_str(ip_s) else { continue; };
        if !is_private_ipv4(ip) { continue; }
        if !out_ifaces.contains(&iface) { out_ifaces.push(iface); }
    }
    Ok(out_ifaces)
}

fn is_private_ipv4(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    o[0] == 10 || (o[0] == 172 && (16..=31).contains(&o[1])) || (o[0] == 192 && o[1] == 168)
}

fn looks_like_tether_iface(iface: &str) -> bool {
    let lower = iface.to_ascii_lowercase();
    if lower == "lo"
        || lower.starts_with("tun")
        || lower.starts_with("wg")
        || lower.starts_with("awg")
        || lower.starts_with("vpn")
        || lower.starts_with("rmnet")
        || lower.starts_with("ccmni")
        || lower.starts_with("clat")
        || lower.starts_with("v4-")
        || lower.starts_with("ipsec")
        || lower.starts_with("docker")
        || lower.starts_with("veth")
    {
        return false;
    }
    matches!(
        lower.as_str(),
        "ap0" | "ap1" | "hotspot" | "rndis0" | "usb0" | "bt-pan" | "bnep0" | "ncm0" | "eth0" | "tether0"
    ) || lower.starts_with("wlan") || lower.starts_with("swlan") || lower.starts_with("rndis") || lower.starts_with("usb") || lower.starts_with("bnep")
}

pub fn selected_profile_from_settings() -> Result<Option<(String, String)>> {
    let st = settings::load_api_settings()?;
    Ok(st.hotspot_vpn_selection().map(|(p, prof)| (p.to_string(), prof.to_string())))
}
