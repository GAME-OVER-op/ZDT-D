use anyhow::{Context, Result};
use log::{info, warn};
use std::{collections::BTreeSet, fs, path::Path, time::Duration};

use crate::shell::{self, Capture};

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const IPT_SLOW_TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug, Clone, Copy)]
pub enum ProtoChoice { Tcp, Udp, TcpUdp }

impl ProtoChoice {
    pub fn from_str(s: &str) -> Self {
        match s {
            "tcp" => ProtoChoice::Tcp,
            "udp" => ProtoChoice::Udp,
            "tcp_udp" => ProtoChoice::TcpUdp,
            _ => ProtoChoice::Tcp,
        }
    }
    pub fn protos(self) -> &'static [&'static str] {
        match self {
            ProtoChoice::Tcp => &["tcp"],
            ProtoChoice::Udp => &["udp"],
            ProtoChoice::TcpUdp => &["tcp","udp"],
        }
    }
}

/// Options equivalent to external vars in shell (`port_preference`, `dpi_ports`).
#[derive(Debug, Clone)]
pub struct DpiTunnelOptions {
    pub port_preference: u8, // 0 -> dpi_ports, 1 -> all ports
    pub dpi_ports: String,
}

impl Default for DpiTunnelOptions {
    fn default() -> Self {
        Self {
            port_preference: 0,
            dpi_ports: "80 443 2710 6969 51413 6771 6881-6999 49152-65535".to_string(),
        }
    }
}

static mut MANGLE_INIT_DONE: bool = false;

/// Rust port of `load_config_dpi_tunnel()`.
///
/// - Creates NAT_DPI chain and hooks OUTPUT -> NAT_DPI (nat table).
/// - Creates MANGLE_APP chain once and hooks OUTPUT -> MANGLE_APP (mangle table).
/// - Adds mangle exclusions RETURN for each UID.
/// - Adds DNAT rules into NAT_DPI to 127.0.0.1:<dest_port> for selected ports/protocols,
///   optionally per-interface `-o iface`.
pub fn apply(uid_file: &Path, dest_port: u16, proto_choice: ProtoChoice, ifaces_raw: Option<&str>, opt: DpiTunnelOptions) -> Result<()> {
    let (mode, ifaces, invalid) = normalize_ifaces(ifaces_raw)?;
    if !invalid.is_empty() {
        warn!("DPI: invalid ifaces skipped: {:?}", invalid);
    }

    info!("DPI: port_preference={} proto_choice={:?} dpi_ports='{}'", opt.port_preference, proto_choice, opt.dpi_ports);

    ensure_nat_chain_nat_dpi()?;
    ensure_mangle_chain_app_once()?;

    let uids = read_uids(uid_file)?;
    if uids.is_empty() {
        log::warn!("DPI: no valid UIDs in file: {} (skip iptables_port)", uid_file.display());
        return Ok(());
    }

    // mangle exclusions
    for uid in &uids {
        ensure_rule_mangle_return(uid)?;
    }

    let protos = proto_choice.protos();

    // If all ports for each uid
    if opt.port_preference == 1 {
        info!("DPI: applying DNAT for ALL ports");
        for uid in &uids {
            for proto in protos {
                add_nat_rule_idempotent(uid, proto, None, &mode, &ifaces, dest_port)?;
            }
        }
        return Ok(());
    }

    // Ports parsing + multiport decision
    let ports_csv = normalize_ports_csv(&opt.dpi_ports);
    let (parts_count, has_range) = analyze_ports(&ports_csv);

    let multiport_supported = test_multiport_supported()?;
    info!("DPI: parts_count={} has_range={} multiport_supported={}", parts_count, has_range, multiport_supported);

    let use_multiport = !has_range && parts_count <= 15 && multiport_supported;
    if use_multiport {
        let ports_for_multi = ports_csv.replace(' ', "").replace('\t', "");
        for uid in &uids {
            for proto in protos {
                ensure_nat_multiport(uid, proto, &ports_for_multi, &mode, &ifaces, dest_port)?;
            }
        }
    } else {
        if !multiport_supported {
            warn!("DPI: device iptables without multiport: fallback will be slower");
        }

        // Pre-parse port tokens so we can iterate by UID (progress per app).
        let mut dport_args: Vec<String> = Vec::new();
        for token in ports_csv.split(',').map(|t| t.trim()).filter(|t| !t.is_empty()) {
            if let Some((a,b)) = parse_range(token) {
                dport_args.push(format!("--dport {}:{}", a, b));
            } else if token.chars().all(|c| c.is_ascii_digit()) {
                dport_args.push(format!("--dport {}", token));
            } else {
                warn!("DPI: skipping invalid port token: {}", token);
            }
        }

        for uid in &uids {
            for proto in protos {
                for dp in &dport_args {
                    add_nat_rule_idempotent(uid, proto, Some(dp.as_str()), &mode, &ifaces, dest_port)?;
                }
            }
        }
    }

    Ok(())
}

fn normalize_ifaces(ifaces_raw: Option<&str>) -> Result<(String, Vec<String>, Vec<String>)> {
    let raw_opt = ifaces_raw.map(|s| s.trim()).filter(|s| !s.is_empty());
    let mut mode: String;
    let mut ifaces: Vec<String> = Vec::new();
    let mut invalid: Vec<String> = Vec::new();

    match raw_opt {
        None => { mode = "all".into(); }
        Some(s) => {
            let tmp = s.replace(',', " ");
            let tmp = tmp.split_whitespace().collect::<Vec<_>>().join(" ");
            match tmp.as_str() {
                "all" | "ALL" => mode = "all".into(),
                "auto" | "AUTO" | "detect" | "DETECT" => mode = "detect".into(),
                _ => mode = "user".into(),
            }
            if mode == "detect" {
                if let Some(d) = detect_default_iface()? {
                    info!("DPI: detected iface: {}", d);
                    ifaces.push(d);
                } else {
                    warn!("DPI: detect failed -> switching to ALL");
                    mode = "all".into();
                }
            } else if mode == "user" {
                for f in tmp.replace(',', " ").split_whitespace() {
                    let mut f = f.trim();
                    if f.is_empty() { continue; }

                    // Allow pasting interface names from `ip link show` output, e.g. `rmnet_data0@rmnet_ipa0:`
                    // or even tokens like `16:`. We normalize:
                    // - strip trailing ':'
                    // - keep part before '@'
                    // - if token looks like '<digits>:' keep the suffix after ':'
                    f = f.trim_end_matches(':');
                    if let Some(pos) = f.find('@') {
                        f = &f[..pos];
                    }
                    if let Some(pos) = f.rfind(':') {
                        if !f.is_empty() && f[..pos].chars().all(|c| c.is_ascii_digit()) {
                            f = &f[pos + 1..];
                        }
                    }
                    let f = f.trim();
                    if f.is_empty() { continue; }

                    if iface_exists(f) {
                        ifaces.push(f.to_string());
                    } else {
                        invalid.push(f.to_string());
                    }
                }
                if ifaces.is_empty() {
                    warn!("DPI: none of specified ifaces exist -> trying detect");
                    if let Some(d) = detect_default_iface()? {
                        info!("DPI: fallback detected iface: {}", d);
                        ifaces.push(d);
                    } else {
                        warn!("DPI: detect also failed -> switching to ALL");
                        mode = "all".into();
                    }
                }
            }
        }
    }

    if mode == "all" {
        ifaces.clear();
        info!("DPI: interface: ALL (no -o)");
    } else {
        info!("DPI: interface(s): {:?}", ifaces);
    }

    Ok((mode, ifaces, invalid))
}

fn iface_exists(name: &str) -> bool {
    if shell::run("ip", &["link","show",name], Capture::None).map(|(c,_)| c==0).unwrap_or(false) { return true; }
    if shell::run("ifconfig", &[name], Capture::None).map(|(c,_)| c==0).unwrap_or(false) { return true; }
    std::path::Path::new("/sys/class/net").join(name).is_dir()
}

fn detect_default_iface() -> Result<Option<String>> {
    if let Ok((c,out)) = shell::run("ip", &["route","get","8.8.8.8"], Capture::Stdout) {
        if c == 0 {
            if let Some(dev) = parse_dev_from_route(&out) { return Ok(Some(dev)); }
        }
    }
    if let Ok((c,out)) = shell::run("ip", &["route"], Capture::Stdout) {
        if c == 0 {
            if let Some(dev) = parse_default_dev(&out) { return Ok(Some(dev)); }
        }
    }
    if let Ok((c,out)) = shell::run("route", &["-n"], Capture::Stdout) {
        if c == 0 {
            if let Some(dev) = parse_route_n_default(&out) { return Ok(Some(dev)); }
        }
    }
    if let Ok(rd) = fs::read_dir("/sys/class/net") {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if n == "lo" { continue; }
            return Ok(Some(n));
        }
    }
    Ok(None)
}

fn parse_dev_from_route(out: &str) -> Option<String> {
    let toks: Vec<&str> = out.split_whitespace().collect();
    for i in 0..toks.len() {
        if toks[i] == "dev" && i+1 < toks.len() {
            return Some(toks[i+1].to_string());
        }
    }
    None
}
fn parse_default_dev(out: &str) -> Option<String> {
    for line in out.lines() {
        if !line.contains("default") { continue; }
        let toks: Vec<&str> = line.split_whitespace().collect();
        for i in 0..toks.len() {
            if toks[i] == "dev" && i+1 < toks.len() {
                return Some(toks[i+1].to_string());
            }
        }
    }
    None
}
fn parse_route_n_default(out: &str) -> Option<String> {
    for line in out.lines() {
        let line = line.trim();
        if line.starts_with("0.0.0.0") {
            let toks: Vec<&str> = line.split_whitespace().collect();
            if toks.len() >= 8 {
                return Some(toks[7].to_string());
            }
        }
    }
    None
}

fn ensure_nat_chain_nat_dpi() -> Result<()> {
    let (c, _) = shell::run_timeout("iptables", &["-t","nat","-nL","NAT_DPI"], Capture::None, IPT_SLOW_TIMEOUT)?;
    if c != 0 {
        let _ = shell::run_timeout("iptables", &["-t","nat","-N","NAT_DPI"], Capture::Both, IPT_CMD_TIMEOUT)?;
    }
    let (c2, _) = shell::run_timeout("iptables", &["-t","nat","-C","OUTPUT","-j","NAT_DPI"], Capture::None, IPT_CMD_TIMEOUT)?;
    if c2 != 0 {
        let _ = shell::run_timeout("iptables", &["-t","nat","-I","OUTPUT","1","-j","NAT_DPI"], Capture::Both, IPT_CMD_TIMEOUT)?;
    }

    // Must be at the very top to avoid feedback loops on loopback/localhost.
    ensure_loopback_return_nat()?;

    info!("DPI: NAT_DPI ready");
    Ok(())
}

fn ensure_mangle_chain_app_once() -> Result<()> {
    unsafe {
        if !MANGLE_INIT_DONE {
            let (c, _) = shell::run_timeout("iptables", &["-t","mangle","-nL","MANGLE_APP"], Capture::None, IPT_SLOW_TIMEOUT)?;
            if c != 0 {
                let _ = shell::run_timeout("iptables", &["-t","mangle","-N","MANGLE_APP"], Capture::Both, IPT_CMD_TIMEOUT)?;
            }
            let (c2, _) = shell::run_timeout("iptables", &["-t","mangle","-C","OUTPUT","-j","MANGLE_APP"], Capture::None, IPT_CMD_TIMEOUT)?;
            if c2 != 0 {
                let _ = shell::run_timeout("iptables", &["-t","mangle","-A","OUTPUT","-j","MANGLE_APP"], Capture::Both, IPT_CMD_TIMEOUT)?;
            }
            MANGLE_INIT_DONE = true;
        }
    }

    // Prevent our own local connections from being re-processed by NAT_DPI/MANGLE_APP rules.
    // Must be at the very top to avoid feedback loops.
    ensure_loopback_return_mangle()?;

    info!("DPI: MANGLE_APP ready");
    Ok(())
}

fn ensure_loopback_return_mangle() -> Result<()> {
    // Keep loopback / localhost traffic at the very top to avoid feedback loops.
    // Remove duplicates (including legacy -d 127.0.0.1).
    let mut del_all = |args: &[&str]| -> Result<()> {
        loop {
            let (c, _) = shell::run_timeout("iptables", args, Capture::None, IPT_CMD_TIMEOUT)?;
            if c != 0 { break; }
        }
        Ok(())
    };

    // Legacy + new rules
    del_all(&["-t","mangle","-D","MANGLE_APP","-d","127.0.0.1","-j","RETURN"])?;
    del_all(&["-t","mangle","-D","MANGLE_APP","-o","lo","-j","RETURN"])?;
    del_all(&["-t","mangle","-D","MANGLE_APP","-d","127.0.0.0/8","-j","RETURN"])?;

    // Keep these strictly at the beginning: #1 and #2.
    let _ = shell::run_timeout(
        "iptables",
        &["-t","mangle","-I","MANGLE_APP","1","-o","lo","-j","RETURN"],
        Capture::Both,
        IPT_CMD_TIMEOUT,
    )?;
    let _ = shell::run_timeout(
        "iptables",
        &["-t","mangle","-I","MANGLE_APP","2","-d","127.0.0.0/8","-j","RETURN"],
        Capture::Both,
        IPT_CMD_TIMEOUT,
    )?;
    Ok(())
}

fn ensure_loopback_return_nat() -> Result<()> {
    // Keep loopback / localhost traffic at the very top to avoid feedback loops.
    // Remove duplicates (including legacy -d 127.0.0.1).
    let mut del_all = |args: &[&str]| -> Result<()> {
        loop {
            let (c, _) = shell::run_timeout("iptables", args, Capture::None, IPT_CMD_TIMEOUT)?;
            if c != 0 { break; }
        }
        Ok(())
    };

    // Legacy + new rules
    del_all(&["-t","nat","-D","NAT_DPI","-d","127.0.0.1","-j","RETURN"])?;
    del_all(&["-t","nat","-D","NAT_DPI","-o","lo","-j","RETURN"])?;
    del_all(&["-t","nat","-D","NAT_DPI","-d","127.0.0.0/8","-j","RETURN"])?;

    // Keep these strictly at the beginning: #1 and #2.
    let _ = shell::run_timeout(
        "iptables",
        &["-t","nat","-I","NAT_DPI","1","-o","lo","-j","RETURN"],
        Capture::Both,
        IPT_CMD_TIMEOUT,
    )?;
    let _ = shell::run_timeout(
        "iptables",
        &["-t","nat","-I","NAT_DPI","2","-d","127.0.0.0/8","-j","RETURN"],
        Capture::Both,
        IPT_CMD_TIMEOUT,
    )?;
    Ok(())
}


fn read_uids(uid_file: &Path) -> Result<Vec<String>> {
    if !uid_file.is_file() {
        anyhow::bail!("DPI: uid_file not readable: {}", uid_file.display());
    }
    let s = fs::read_to_string(uid_file).with_context(|| format!("read {}", uid_file.display()))?;
    let mut set = BTreeSet::<String>::new();
    for line in s.lines() {
        let line = line.trim();
        if line.is_empty() { continue; }
        let mut it = line.split('=');
        let _app = it.next().unwrap_or("");
        let uid = it.next().unwrap_or("").trim();
        if uid.chars().all(|c| c.is_ascii_digit()) {
            set.insert(uid.to_string());
        }
    }
    Ok(set.into_iter().collect())
}

fn ensure_rule_mangle_return(uid: &str) -> Result<()> {
    let check = ["-t","mangle","-C","MANGLE_APP","-m","owner","--uid-owner",uid,"-j","RETURN"];
    let (c, _) = shell::run_timeout("iptables", &check, Capture::None, IPT_CMD_TIMEOUT)?;
    if c != 0 {
        // Keep the loopback / localhost RETURN rules at position #1/#2.
        // Insert UID RETURN right after them.
        let add3 = ["-t","mangle","-I","MANGLE_APP","3","-m","owner","--uid-owner",uid,"-j","RETURN"];
        let (c2, _) = shell::run_timeout("iptables", &add3, Capture::Both, IPT_CMD_TIMEOUT)?;
        if c2 != 0 {
            // Some builds may not support positional "-I <chain> <num>".
            // Fall back to "-I <chain>" and then re-assert loopback rules on top.
            let add1 = ["-t","mangle","-I","MANGLE_APP","-m","owner","--uid-owner",uid,"-j","RETURN"];
            let (c3, _) = shell::run_timeout("iptables", &add1, Capture::Both, IPT_CMD_TIMEOUT)?;
            if c3 == 0 {
                let _ = ensure_loopback_return_mangle();
            }
        }
    }
    Ok(())
}


fn normalize_ports_csv(dpi_ports: &str) -> String {
    let mut s = dpi_ports.replace(' ', ",").replace('\t', ",");
    while s.contains(",,") { s = s.replace(",,", ","); }
    s.trim_matches(',').to_string()
}

fn analyze_ports(ports_csv: &str) -> (usize, bool) {
    let mut count = 0usize;
    let mut has_range = false;
    for token in ports_csv.split(',') {
        let t = token.trim();
        if t.is_empty() { continue; }
        if t.contains('-') { has_range = true; count += 1; continue; }
        if t.chars().all(|c| c.is_ascii_digit()) { count += 1; }
    }
    (count, has_range)
}

fn test_multiport_supported() -> Result<bool> {
    let ins = ["-t","nat","-I","NAT_DPI","1","-p","tcp","-m","multiport","--dports","1","-j","RETURN"];
    let (c, _) = shell::run_timeout("iptables", &ins, Capture::None, IPT_CMD_TIMEOUT)?;
    if c == 0 {
        let del = ["-t","nat","-D","NAT_DPI","-p","tcp","-m","multiport","--dports","1","-j","RETURN"];
        let _ = shell::run_timeout("iptables", &del, Capture::None, IPT_CMD_TIMEOUT)?;
        return Ok(true);
    }
    Ok(false)
}

fn parse_range(token: &str) -> Option<(u16,u16)> {
    let mut it = token.split('-');
    let a = it.next()?;
    let b = it.next()?;
    if it.next().is_some() { return None; }
    let mut a: u16 = a.parse().ok()?;
    let mut b: u16 = b.parse().ok()?;
    if a > b { std::mem::swap(&mut a, &mut b); }
    Some((a,b))
}

fn ensure_nat_multiport(uid: &str, proto: &str, ports: &str, mode: &str, ifaces: &[String], dest_port: u16) -> Result<()> {
    let to = format!("127.0.0.1:{dest_port}");
    if mode == "all" {
        let check = ["-t","nat","-C","NAT_DPI","-p",proto,"-m","owner","--uid-owner",uid,"-m","multiport","--dports",ports,"-j","DNAT","--to-destination",&to];
        let (c, _) = shell::run_timeout("iptables", &check, Capture::None, IPT_CMD_TIMEOUT)?;
        if c != 0 {
            let add = ["-t","nat","-A","NAT_DPI","-p",proto,"-m","owner","--uid-owner",uid,"-m","multiport","--dports",ports,"-j","DNAT","--to-destination",&to];
            let _ = shell::run_timeout("iptables", &add, Capture::Both, IPT_CMD_TIMEOUT)?;
        }
        return Ok(());
    }

    for iface in ifaces {
        let check = ["-t","nat","-C","NAT_DPI","-o",iface,"-p",proto,"-m","owner","--uid-owner",uid,"-m","multiport","--dports",ports,"-j","DNAT","--to-destination",&to];
        let (c, _) = shell::run_timeout("iptables", &check, Capture::None, IPT_CMD_TIMEOUT)?;
        if c != 0 {
            let add = ["-t","nat","-A","NAT_DPI","-o",iface,"-p",proto,"-m","owner","--uid-owner",uid,"-m","multiport","--dports",ports,"-j","DNAT","--to-destination",&to];
            let _ = shell::run_timeout("iptables", &add, Capture::Both, IPT_CMD_TIMEOUT)?;
        }
    }
    Ok(())
}

/// Attempt to add a DNAT rule with several argument order variants (iptables quirks).
fn add_nat_rule_idempotent(uid: &str, proto: &str, extra: Option<&str>, mode: &str, ifaces: &[String], dest_port: u16) -> Result<()> {
    let extra_tokens = extra.map(|s| s.split_whitespace().map(|t| t.to_string()).collect::<Vec<_>>()).unwrap_or_default();
    let variants: [&[&str]; 5] = [
        &["-p", "PROTO", "-m", "PROTO", "-m", "owner", "--uid-owner", "UID"],
        &["-p", "PROTO", "-m", "owner", "--uid-owner", "UID", "-m", "PROTO"],
        &["-m", "owner", "--uid-owner", "UID", "-p", "PROTO", "-m", "PROTO"],
        &["-p", "PROTO", "-m", "owner", "--uid-owner", "UID"],
        &["-m", "owner", "--uid-owner", "UID"],
    ];

    let to_dst = format!("127.0.0.1:{dest_port}");

    let iface_list: Vec<Option<&str>> = if mode == "all" {
        vec![None]
    } else {
        ifaces.iter().map(|s| Some(s.as_str())).collect()
    };

    for iface in iface_list {
        for v in variants {
            let mut rule: Vec<String> = Vec::new();
            if let Some(iface) = iface {
                rule.push("-o".into());
                rule.push(iface.into());
            }
            for tok in v {
                match *tok {
                    "PROTO" => rule.push(proto.into()),
                    "UID" => rule.push(uid.into()),
                    other => rule.push(other.into()),
                }
            }
            rule.extend(extra_tokens.clone());
            rule.extend(vec!["-j".into(), "DNAT".into(), "--to-destination".into(), to_dst.clone()]);

            let mut check: Vec<String> = vec!["-t".into(),"nat".into(),"-C".into(),"NAT_DPI".into()];
            check.extend(rule.clone());
            let (c, _) = shell::runv_timeout("iptables", &check, Capture::None, IPT_CMD_TIMEOUT)?;
            if c == 0 { return Ok(()); }

            let mut add: Vec<String> = vec!["-t".into(),"nat".into(),"-A".into(),"NAT_DPI".into()];
            add.extend(rule);
            let (c2, _) = shell::runv_timeout("iptables", &add, Capture::None, IPT_CMD_TIMEOUT)?;
            if c2 == 0 {
                info!("DPI: added rule uid={} proto={} iface={}", uid, proto, iface.unwrap_or("ALL"));
                return Ok(());
            }
        }
    }

    anyhow::bail!("DPI: failed to add rule uid={} proto={} extra={:?}", uid, proto, extra);
}
