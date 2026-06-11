use anyhow::{Context, Result};
use log::{info, warn};
use std::{collections::BTreeSet, fs, path::Path, time::Duration};

use crate::{settings, shell::{self, Capture}, xtables_lock};

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const IPT_SLOW_TIMEOUT: Duration = Duration::from_secs(15);
const XT_WAIT_SECS: &str = "5";

// IMPORTANT: All iptables/ip6tables work in this module MUST go through the
// shared xtables_lock helpers and the global lock below.
// Do not call shell::run_timeout("iptables", ...) here directly, otherwise
// concurrent programs can race, hit xtables lock contention and partially
// apply rules.
fn ipt_run_timeout(args: &[&str], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    let mut a: Vec<&str> = Vec::with_capacity(args.len() + 2);
    a.push("-w");
    a.push(XT_WAIT_SECS);
    a.extend_from_slice(args);
    xtables_lock::run_timeout_retry("iptables", &a, capture, timeout)
}

fn ipt_runv_timeout(args: &[String], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    let mut a: Vec<String> = Vec::with_capacity(args.len() + 2);
    a.push("-w".into());
    a.push(XT_WAIT_SECS.into());
    a.extend_from_slice(args);
    xtables_lock::runv_timeout_retry("iptables", &a, capture, timeout)
}

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

fn allow_loopback_redirect_enabled() -> bool {
    match settings::load_api_settings() {
        Ok(st) => st.allow_loopback_redirect,
        Err(e) => {
            warn!("DPI: failed to load setting/setting.json, loopback redirect disabled: {e:#}");
            false
        }
    }
}

/// Rust port of `load_config_dpi_tunnel()`.
///
/// - Creates NAT_DPI chain and hooks OUTPUT -> NAT_DPI (nat table).
/// - Creates MANGLE_APP chain once and hooks OUTPUT -> MANGLE_APP (mangle table).
/// - Adds DNAT rules into NAT_DPI to 127.0.0.1:<dest_port> for selected ports/protocols,
///   optionally per-interface `-o iface`.
pub fn apply(uid_file: &Path, dest_port: u16, proto_choice: ProtoChoice, ifaces_raw: Option<&str>, opt: DpiTunnelOptions) -> Result<()> {
    let _xtables_guard = xtables_lock::lock();
    let allow_loopback_redirect = allow_loopback_redirect_enabled();
    let (mode, ifaces, invalid) = normalize_ifaces(ifaces_raw)?;
    if !invalid.is_empty() {
        warn!("DPI: invalid ifaces skipped: {:?}", invalid);
    }

    info!("DPI: port_preference={} proto_choice={:?} dpi_ports='{}'", opt.port_preference, proto_choice, opt.dpi_ports);

    ensure_nat_chain_nat_dpi(allow_loopback_redirect)?;
    ensure_mangle_chain_app_once()?;

    let scope = format!(
        "nat:uid={}:dest={}:proto={:?}:ifaces={}:pref={}:ports={}",
        uid_file.display(),
        dest_port,
        proto_choice,
        ifaces_raw.unwrap_or(""),
        opt.port_preference,
        opt.dpi_ports,
    );
    let scoped_chain = prepare_nat_scoped_chain(&scope)?;
    let scoped_local_chain = if allow_loopback_redirect {
        Some(prepare_nat_local_scoped_chain(&scope)?)
    } else {
        None
    };

    let uids = read_uids(uid_file)?;
    if uids.is_empty() {
        log::warn!("DPI: no valid UIDs in file: {} (empty scoped NAT chain)", uid_file.display());
        finish_nat_scoped_chain(&scoped_chain)?;
        if let Some(chain) = scoped_local_chain.as_deref() {
            finish_nat_scoped_chain(chain)?;
        }
        crate::runtime_refresh::register_nat(uid_file, dest_port, proto_choice, ifaces_raw, &opt);
        return Ok(());
    }

    // MANGLE_APP is UID-specific on the NFQUEUE side, so separate owner RETURN
    // exclusions are intentionally not added here. Non-targeted UIDs naturally
    // pass through to the final RETURN in MANGLE_APP.

    let protos = proto_choice.protos();

    // If all ports for each uid
    if opt.port_preference == 1 {
        info!("DPI: applying DNAT for ALL ports");
        for uid in &uids {
            for proto in protos {
                if allow_loopback_redirect {
                    ensure_nat_local_dest_port_return(scoped_local_chain.as_deref().unwrap(), uid, proto, dest_port)?;
                    add_nat_local_rule_idempotent(scoped_local_chain.as_deref().unwrap(), uid, proto, None, dest_port)?;
                }
                add_nat_rule_idempotent(&scoped_chain, uid, proto, None, &mode, &ifaces, dest_port)?;
            }
        }
        finish_nat_scoped_chain(&scoped_chain)?;
        if let Some(chain) = scoped_local_chain.as_deref() {
            finish_nat_scoped_chain(chain)?;
        }
        crate::runtime_refresh::register_nat(uid_file, dest_port, proto_choice, ifaces_raw, &opt);
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
                if allow_loopback_redirect {
                    ensure_nat_local_dest_port_return(scoped_local_chain.as_deref().unwrap(), uid, proto, dest_port)?;
                    let extra = format!("-m multiport --dports {}", ports_for_multi);
                    add_nat_local_rule_idempotent(scoped_local_chain.as_deref().unwrap(), uid, proto, Some(extra.as_str()), dest_port)?;
                }
                ensure_nat_multiport(&scoped_chain, uid, proto, &ports_for_multi, &mode, &ifaces, dest_port)?;
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
                if allow_loopback_redirect {
                    ensure_nat_local_dest_port_return(scoped_local_chain.as_deref().unwrap(), uid, proto, dest_port)?;
                }
                for dp in &dport_args {
                    if allow_loopback_redirect {
                        add_nat_local_rule_idempotent(scoped_local_chain.as_deref().unwrap(), uid, proto, Some(dp.as_str()), dest_port)?;
                    }
                    add_nat_rule_idempotent(&scoped_chain, uid, proto, Some(dp.as_str()), &mode, &ifaces, dest_port)?;
                }
            }
        }
    }

    finish_nat_scoped_chain(&scoped_chain)?;
    if let Some(chain) = scoped_local_chain.as_deref() {
        finish_nat_scoped_chain(chain)?;
    }
    crate::runtime_refresh::register_nat(uid_file, dest_port, proto_choice, ifaces_raw, &opt);
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

fn ensure_nat_chain_nat_dpi(allow_loopback_redirect: bool) -> Result<()> {
    let (c, _) = ipt_run_timeout(&["-t","nat","-nL","NAT_DPI"], Capture::None, IPT_SLOW_TIMEOUT)?;
    if c != 0 {
        let _ = ipt_run_timeout(&["-t","nat","-N","NAT_DPI"], Capture::Both, IPT_CMD_TIMEOUT)?;
    }
    let (c2, _) = ipt_run_timeout(&["-t","nat","-C","OUTPUT","-j","NAT_DPI"], Capture::None, IPT_CMD_TIMEOUT)?;
    if c2 != 0 {
        let _ = ipt_run_timeout(&["-t","nat","-I","OUTPUT","1","-j","NAT_DPI"], Capture::Both, IPT_CMD_TIMEOUT)?;
    }

    ensure_nat_local_chain(allow_loopback_redirect)?;

    // Must be at the very top to avoid feedback loops on loopback/localhost.
    ensure_loopback_return_nat()?;

    info!("DPI: NAT_DPI ready");
    Ok(())
}

fn ensure_nat_local_chain(enabled: bool) -> Result<()> {
    if !enabled {
        delete_rule_all("nat", "OUTPUT", &["-j", "NAT_DPI_LOCAL"])?;
        let _ = ipt_run_timeout(&["-t","nat","-F","NAT_DPI_LOCAL"], Capture::None, IPT_CMD_TIMEOUT);
        let _ = ipt_run_timeout(&["-t","nat","-X","NAT_DPI_LOCAL"], Capture::None, IPT_CMD_TIMEOUT);
        return Ok(());
    }

    let (c, _) = ipt_run_timeout(&["-t","nat","-nL","NAT_DPI_LOCAL"], Capture::None, IPT_SLOW_TIMEOUT)?;
    if c != 0 {
        let _ = ipt_run_timeout(&["-t","nat","-N","NAT_DPI_LOCAL"], Capture::Both, IPT_CMD_TIMEOUT)?;
    }

    // NAT_DPI_LOCAL must stay before NAT_DPI in OUTPUT, but do not delete and
    // reinsert the jump on every DPI program apply if it is already correct.
    ensure_jump_at_position("nat", "OUTPUT", "NAT_DPI_LOCAL", 1)?;
    Ok(())
}

fn ensure_mangle_chain_app_once() -> Result<()> {
    // Do not cache this in-process: stop/restore may delete MANGLE_APP while the daemon
    // remains alive. Always verify the real iptables state before applying rules.
    let (c, _) = ipt_run_timeout(&["-t","mangle","-nL","MANGLE_APP"], Capture::None, IPT_SLOW_TIMEOUT)?;
    if c != 0 {
        let (rc, out) = ipt_run_timeout(&["-t","mangle","-N","MANGLE_APP"], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("DPI: create MANGLE_APP failed: {}", out.trim());
        }
    }

    let (c2, _) = ipt_run_timeout(&["-t","mangle","-C","OUTPUT","-j","MANGLE_APP"], Capture::None, IPT_CMD_TIMEOUT)?;
    if c2 != 0 {
        let (rc, out) = ipt_run_timeout(&["-t","mangle","-A","OUTPUT","-j","MANGLE_APP"], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("DPI: hook OUTPUT -> MANGLE_APP failed: {}", out.trim());
        }
    }

    // Prevent our own local connections from being re-processed by NAT_DPI/MANGLE_APP rules.
    // Must be at the very top to avoid feedback loops.
    ensure_loopback_return_mangle()?;
    if let Err(e) = crate::iptables::mangle_app::cleanup_owner_returns("iptables") {
        warn!("DPI: cleanup legacy MANGLE_APP owner RETURN rules failed: {e:#}");
    }

    info!("DPI: MANGLE_APP ready");
    Ok(())
}

fn ensure_loopback_return_mangle() -> Result<()> {
    ensure_ordered_return_prefix(
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

fn ensure_loopback_return_nat() -> Result<()> {
    ensure_ordered_return_prefix(
        "nat",
        "NAT_DPI",
        &[
            &["-o", "lo", "-j", "RETURN"],
            &["-d", "127.0.0.0/8", "-j", "RETURN"],
        ],
        &[
            &["-d", "127.0.0.1", "-j", "RETURN"],
        ],
    )
}

fn ensure_ordered_return_prefix(
    table: &str,
    chain: &str,
    expected: &[&[&str]],
    legacy: &[&[&str]],
) -> Result<()> {
    if return_prefix_already_ordered(table, chain, expected, legacy)? {
        return Ok(());
    }

    for rule in legacy {
        delete_rule_all(table, chain, rule)?;
    }
    for rule in expected {
        delete_rule_all(table, chain, rule)?;
    }
    for (idx, rule) in expected.iter().enumerate() {
        insert_rule_at(table, chain, idx + 1, rule)?;
    }
    Ok(())
}

fn return_prefix_already_ordered(
    table: &str,
    chain: &str,
    expected: &[&[&str]],
    legacy: &[&[&str]],
) -> Result<bool> {
    let (rc, out) = ipt_run_timeout(&["-t", table, "-S", chain], Capture::Stdout, IPT_CMD_TIMEOUT)?;
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

fn ensure_jump_at_position(table: &str, chain: &str, jump: &str, pos: usize) -> Result<()> {
    if jump_already_at_position(table, chain, jump, pos)? {
        return Ok(());
    }
    delete_rule_all(table, chain, &["-j", jump])?;
    insert_rule_at(table, chain, pos, &["-j", jump])
}

fn jump_already_at_position(table: &str, chain: &str, jump: &str, pos: usize) -> Result<bool> {
    let (rc, out) = ipt_run_timeout(&["-t", table, "-S", chain], Capture::Stdout, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        return Ok(false);
    }

    let expected = format!("-A {chain} -j {jump}");
    let chain_lines: Vec<&str> = out
        .lines()
        .map(str::trim)
        .filter(|line| line.starts_with("-A "))
        .collect();
    if chain_lines.get(pos.saturating_sub(1)).copied() != Some(expected.as_str()) {
        return Ok(false);
    }
    for (idx, line) in chain_lines.iter().enumerate() {
        if idx != pos.saturating_sub(1) && *line == expected.as_str() {
            return Ok(false);
        }
    }
    Ok(true)
}

fn delete_rule_all(table: &str, chain: &str, rule: &[&str]) -> Result<()> {
    loop {
        let mut args = vec!["-t", table, "-D", chain];
        args.extend_from_slice(rule);
        let (rc, _) = ipt_run_timeout(&args, Capture::None, IPT_CMD_TIMEOUT)?;
        if rc != 0 {
            break;
        }
    }
    Ok(())
}

fn insert_rule_at(table: &str, chain: &str, pos: usize, rule: &[&str]) -> Result<()> {
    let pos_s = pos.to_string();
    let mut args = vec!["-t", table, "-I", chain, pos_s.as_str()];
    args.extend_from_slice(rule);
    let (rc, out) = ipt_run_timeout(&args, Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("DPI: insert rule failed in {table}/{chain}: {}", out.trim());
    }
    Ok(())
}



fn scoped_nat_chain_name(label: &str) -> String {
    let mut hash: u64 = 0xcbf29ce484222325;
    for b in label.as_bytes() {
        hash ^= *b as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    format!("ZDTN_{hash:016x}")
}

fn prepare_nat_scoped_chain(scope_label: &str) -> Result<String> {
    let chain = scoped_nat_chain_name(scope_label);
    let (exists, _) = ipt_run_timeout(&["-t", "nat", "-nL", chain.as_str()], Capture::None, IPT_CMD_TIMEOUT)?;
    if exists != 0 {
        let (rc, out) = ipt_run_timeout(&["-t", "nat", "-N", chain.as_str()], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("DPI: create scoped NAT chain {chain} failed: {}", out.trim());
        }
    }
    let (rc, out) = ipt_run_timeout(&["-t", "nat", "-F", chain.as_str()], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("DPI: flush scoped NAT chain {chain} failed: {}", out.trim());
    }
    let (check, _) = ipt_run_timeout(&["-t", "nat", "-C", "NAT_DPI", "-j", chain.as_str()], Capture::None, IPT_CMD_TIMEOUT)?;
    if check != 0 {
        let (add, out) = ipt_run_timeout(&["-t", "nat", "-A", "NAT_DPI", "-j", chain.as_str()], Capture::Both, IPT_CMD_TIMEOUT)?;
        if add != 0 {
            anyhow::bail!("DPI: hook NAT_DPI -> {chain} failed: {}", out.trim());
        }
    }
    Ok(chain)
}

fn finish_nat_scoped_chain(chain: &str) -> Result<()> {
    let (rc, out) = ipt_run_timeout(&["-t", "nat", "-A", chain, "-j", "RETURN"], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("DPI: add scoped NAT final RETURN failed in {chain}: {}", out.trim());
    }
    Ok(())
}

fn prepare_nat_local_scoped_chain(scope_label: &str) -> Result<String> {
    let chain = scoped_nat_chain_name(&format!("local:{scope_label}"));
    let (exists, _) = ipt_run_timeout(&["-t", "nat", "-nL", chain.as_str()], Capture::None, IPT_CMD_TIMEOUT)?;
    if exists != 0 {
        let (rc, out) = ipt_run_timeout(&["-t", "nat", "-N", chain.as_str()], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("DPI: create scoped local NAT chain {chain} failed: {}", out.trim());
        }
    }
    let (rc, out) = ipt_run_timeout(&["-t", "nat", "-F", chain.as_str()], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("DPI: flush scoped local NAT chain {chain} failed: {}", out.trim());
    }
    let (check, _) = ipt_run_timeout(&["-t", "nat", "-C", "NAT_DPI_LOCAL", "-j", chain.as_str()], Capture::None, IPT_CMD_TIMEOUT)?;
    if check != 0 {
        let (add, out) = ipt_run_timeout(&["-t", "nat", "-A", "NAT_DPI_LOCAL", "-j", chain.as_str()], Capture::Both, IPT_CMD_TIMEOUT)?;
        if add != 0 {
            anyhow::bail!("DPI: hook NAT_DPI_LOCAL -> {chain} failed: {}", out.trim());
        }
    }
    Ok(chain)
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
            if let Ok(parsed) = uid.parse::<u32>() {
                if parsed > 0 {
                    set.insert(parsed.to_string());
                }
            }
        }
    }
    Ok(set.into_iter().collect())
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
    let (c, _) = ipt_run_timeout(&ins, Capture::None, IPT_CMD_TIMEOUT)?;
    if c == 0 {
        let del = ["-t","nat","-D","NAT_DPI","-p","tcp","-m","multiport","--dports","1","-j","RETURN"];
        let _ = ipt_run_timeout(&del, Capture::None, IPT_CMD_TIMEOUT)?;
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

fn ensure_nat_multiport(chain: &str, uid: &str, proto: &str, ports: &str, mode: &str, ifaces: &[String], dest_port: u16) -> Result<()> {
    let to = format!("127.0.0.1:{dest_port}");
    if mode == "all" {
        let check = ["-t","nat","-C",chain,"-p",proto,"-m","owner","--uid-owner",uid,"-m","multiport","--dports",ports,"-j","DNAT","--to-destination",&to];
        let (c, _) = ipt_run_timeout(&check, Capture::None, IPT_CMD_TIMEOUT)?;
        if c != 0 {
            let add = ["-t","nat","-A",chain,"-p",proto,"-m","owner","--uid-owner",uid,"-m","multiport","--dports",ports,"-j","DNAT","--to-destination",&to];
            let _ = ipt_run_timeout(&add, Capture::Both, IPT_CMD_TIMEOUT)?;
        }
        return Ok(());
    }

    for iface in ifaces {
        let check = ["-t","nat","-C",chain,"-o",iface,"-p",proto,"-m","owner","--uid-owner",uid,"-m","multiport","--dports",ports,"-j","DNAT","--to-destination",&to];
        let (c, _) = ipt_run_timeout(&check, Capture::None, IPT_CMD_TIMEOUT)?;
        if c != 0 {
            let add = ["-t","nat","-A",chain,"-o",iface,"-p",proto,"-m","owner","--uid-owner",uid,"-m","multiport","--dports",ports,"-j","DNAT","--to-destination",&to];
            let _ = ipt_run_timeout(&add, Capture::Both, IPT_CMD_TIMEOUT)?;
        }
    }
    Ok(())
}


fn ensure_nat_local_dest_port_return(chain: &str, uid: &str, proto: &str, dest_port: u16) -> Result<()> {
    let dport = dest_port.to_string();
    let check = [
        "-t","nat","-C",chain,
        "-d","127.0.0.0/8",
        "-p",proto,
        "-m","owner","--uid-owner",uid,
        "-m",proto,"--dport",&dport,
        "-j","RETURN",
    ];
    let (c, _) = ipt_run_timeout(&check, Capture::None, IPT_CMD_TIMEOUT)?;
    if c != 0 {
        let add = [
            "-t","nat","-A",chain,
            "-d","127.0.0.0/8",
            "-p",proto,
            "-m","owner","--uid-owner",uid,
            "-m",proto,"--dport",&dport,
            "-j","RETURN",
        ];
        let _ = ipt_run_timeout(&add, Capture::Both, IPT_CMD_TIMEOUT)?;
    }
    Ok(())
}

fn add_nat_local_rule_idempotent(chain: &str, uid: &str, proto: &str, extra: Option<&str>, dest_port: u16) -> Result<()> {
    let extra_tokens = extra
        .map(|s| s.split_whitespace().map(|t| t.to_string()).collect::<Vec<_>>())
        .unwrap_or_default();
    let variants: [&[&str]; 5] = [
        &["-p", "PROTO", "-m", "PROTO", "-m", "owner", "--uid-owner", "UID"],
        &["-p", "PROTO", "-m", "owner", "--uid-owner", "UID", "-m", "PROTO"],
        &["-m", "owner", "--uid-owner", "UID", "-p", "PROTO", "-m", "PROTO"],
        &["-p", "PROTO", "-m", "owner", "--uid-owner", "UID"],
        &["-m", "owner", "--uid-owner", "UID"],
    ];

    let to_dst = format!("127.0.0.1:{dest_port}");

    for v in variants {
        let mut rule: Vec<String> = vec!["-d".into(), "127.0.0.0/8".into()];
        for tok in v {
            match *tok {
                "PROTO" => rule.push(proto.into()),
                "UID" => rule.push(uid.into()),
                other => rule.push(other.into()),
            }
        }
        rule.extend(extra_tokens.clone());
        rule.extend(vec!["-j".into(), "DNAT".into(), "--to-destination".into(), to_dst.clone()]);

        let mut check: Vec<String> = vec!["-t".into(),"nat".into(),"-C".into(),chain.into()];
        check.extend(rule.clone());
        let (c, _) = ipt_runv_timeout(&check, Capture::None, IPT_CMD_TIMEOUT)?;
        if c == 0 { return Ok(()); }

        let mut add: Vec<String> = vec!["-t".into(),"nat".into(),"-A".into(),chain.into()];
        add.extend(rule);
        let (c2, _) = ipt_runv_timeout(&add, Capture::None, IPT_CMD_TIMEOUT)?;
        if c2 == 0 {
            info!("DPI: added localhost rule uid={} proto={}", uid, proto);
            return Ok(());
        }
    }

    anyhow::bail!("DPI: failed to add localhost rule uid={} proto={} extra={:?}", uid, proto, extra);
}

/// Attempt to add a DNAT rule with several argument order variants (iptables quirks).
fn add_nat_rule_idempotent(chain: &str, uid: &str, proto: &str, extra: Option<&str>, mode: &str, ifaces: &[String], dest_port: u16) -> Result<()> {
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

            let mut check: Vec<String> = vec!["-t".into(),"nat".into(),"-C".into(),chain.into()];
            check.extend(rule.clone());
            let (c, _) = ipt_runv_timeout(&check, Capture::None, IPT_CMD_TIMEOUT)?;
            if c == 0 { return Ok(()); }

            let mut add: Vec<String> = vec!["-t".into(),"nat".into(),"-A".into(),chain.into()];
            add.extend(rule);
            let (c2, _) = ipt_runv_timeout(&add, Capture::None, IPT_CMD_TIMEOUT)?;
            if c2 == 0 {
                info!("DPI: added rule uid={} proto={} iface={}", uid, proto, iface.unwrap_or("ALL"));
                return Ok(());
            }
        }
    }

    anyhow::bail!("DPI: failed to add rule uid={} proto={} extra={:?}", uid, proto, extra);
}
