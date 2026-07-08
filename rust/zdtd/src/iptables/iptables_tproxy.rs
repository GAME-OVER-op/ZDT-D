use anyhow::{Context, Result};
use log::{info, warn};
use std::{collections::BTreeSet, fs, path::Path, time::Duration};

use crate::{settings, shell::Capture, xtables_lock};
use super::iptables_port::{DpiTunnelOptions, ProtoChoice};

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const IPT_SLOW_TIMEOUT: Duration = Duration::from_secs(15);
const IP_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";

const OUT_CHAIN: &str = "ZDT_TPROXY_OUT";
const PRE_CHAIN: &str = "ZDT_TPROXY_PRE";
const DIVERT_CHAIN: &str = "ZDT_TPROXY_DIVERT";
const ROUTE_TABLE: u32 = 1057;
const ROUTE_PREF: &str = "9999";

// Android uses the low fwmark bits for netId/permission/VPN routing.
// Do not overwrite the whole mark.  Keep ZDT-D TPROXY metadata in the high
// nibble/byte area only, preserving the lower 20 bits used by Android rules.
const ROUTE_MARK: u32 = 0x5000_0000;
const ROUTE_MASK: u32 = 0xf000_0000;
const SCOPE_MASK: u32 = 0xfff0_0000;
const LEGACY_ROUTE_MARK: u32 = 0x5d70_0000;
const LEGACY_ROUTE_MASK: u32 = 0xffff_0000;
const TPROXY_NO_FILE: &str = "tproxy_no";

#[derive(Debug)]
pub enum TproxyApplyError {
    Unsupported(String),
    Failed(anyhow::Error),
}

impl std::fmt::Display for TproxyApplyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TproxyApplyError::Unsupported(s) => write!(f, "TPROXY unsupported: {s}"),
            TproxyApplyError::Failed(e) => write!(f, "TPROXY failed: {e:#}"),
        }
    }
}

impl std::error::Error for TproxyApplyError {}

fn unsupported(msg: impl Into<String>) -> TproxyApplyError { TproxyApplyError::Unsupported(msg.into()) }
fn failed(e: anyhow::Error) -> TproxyApplyError { TproxyApplyError::Failed(e) }

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

fn ip_run_timeout(args: &[&str], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    crate::shell::run_timeout("ip", args, capture, timeout)
}

fn tproxy_no_path() -> std::path::PathBuf {
    Path::new(settings::SETTING_DIR).join(TPROXY_NO_FILE)
}

fn tproxy_disabled_by_flag() -> bool { tproxy_no_path().is_file() }

fn disable_tproxy_persistently(reason: &str) {
    let path = tproxy_no_path();
    if let Some(parent) = path.parent() {
        if let Err(e) = fs::create_dir_all(parent) {
            warn!("TPROXY disabled in memory, but failed to create setting dir: {e:#}");
            return;
        }
    }
    let body = format!("disabled_by=zdtd\nreason={}\n", reason.trim());
    if let Err(e) = fs::write(&path, body) {
        warn!("TPROXY disabled in memory, but failed to write {}: {e:#}", path.display());
        return;
    }
    warn!("TPROXY disabled persistently: {} ({})", path.display(), reason.trim());
}

pub fn disabled_reason() -> Option<String> {
    let path = tproxy_no_path();
    if !path.is_file() { return None; }
    fs::read_to_string(path).ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

pub fn scope_label(uid_file: &Path, dest_port: u16, proto_choice: ProtoChoice, ifaces_raw: Option<&str>, opt: &DpiTunnelOptions) -> String {
    format!(
        "tproxy:uid={}:dest={}:proto={:?}:ifaces={}:pref={}:ports={}",
        uid_file.display(),
        dest_port,
        proto_choice,
        ifaces_raw.unwrap_or(""),
        opt.port_preference,
        opt.dpi_ports,
    )
}

fn scoped_hash(label: &str) -> u64 {
    let mut hash: u64 = 0xcbf29ce484222325;
    for b in label.as_bytes() {
        hash ^= *b as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

pub fn scoped_out_chain_name(label: &str) -> String { format!("ZDTP_{:016x}", scoped_hash(label)) }
pub fn scoped_pre_chain_name(label: &str) -> String { format!("ZDTPP_{:016x}", scoped_hash(label)) }

fn mark_for_scope(label: &str) -> u32 {
    // 8 bits of per-scope identity in bits 20..27, plus a route nibble in
    // bits 28..31.  The lower 20 Android fwmark bits are left untouched by
    // MARK --set-xmark and by TPROXY --tproxy-mark.
    let slot = ((scoped_hash(label) as u32) & 0x0000_00ff).max(1);
    ROUTE_MARK | (slot << 20)
}

fn mark_hex(mark: u32) -> String { format!("0x{mark:08x}") }
fn mark_mask_hex(mark: u32) -> String { format!("0x{mark:08x}/0x{SCOPE_MASK:08x}") }
fn route_mask_hex() -> String { format!("0x{ROUTE_MARK:08x}/0x{ROUTE_MASK:08x}") }
fn legacy_route_mask_hex() -> String { format!("0x{LEGACY_ROUTE_MARK:08x}/0x{LEGACY_ROUTE_MASK:08x}") }

pub fn apply(uid_file: &Path, dest_port: u16, proto_choice: ProtoChoice, ifaces_raw: Option<&str>, opt: &DpiTunnelOptions) -> std::result::Result<(), TproxyApplyError> {
    let _xtables_guard = xtables_lock::lock();
    apply_locked(uid_file, dest_port, proto_choice, ifaces_raw, opt)
}

fn apply_locked(uid_file: &Path, dest_port: u16, proto_choice: ProtoChoice, ifaces_raw: Option<&str>, opt: &DpiTunnelOptions) -> std::result::Result<(), TproxyApplyError> {
    match settings::load_api_settings() {
        Ok(st) if st.tproxy_enabled => {}
        Ok(_) => return Err(unsupported("disabled by setting: tproxy_enabled=false")),
        Err(e) => return Err(unsupported(format!("settings load failed: {e:#}"))),
    }

    if tproxy_disabled_by_flag() {
        return Err(unsupported(disabled_reason().unwrap_or_else(|| "disabled by tproxy_no flag".to_string())));
    }

    probe_tproxy_runtime().map_err(|e| {
        let msg = format!("{e:#}");
        disable_tproxy_persistently(&msg);
        unsupported(msg)
    })?;

    let (mode, ifaces, invalid) = normalize_ifaces(ifaces_raw).map_err(failed)?;
    if !invalid.is_empty() {
        warn!("TPROXY: invalid ifaces skipped: {:?}", invalid);
    }

    let scope = scope_label(uid_file, dest_port, proto_choice, ifaces_raw, opt);
    let mark = mark_for_scope(&scope);
    let uids = read_uids(uid_file).map_err(failed)?;

    ensure_policy_route().map_err(failed)?;
    ensure_base_chains().map_err(failed)?;

    if uids.is_empty() {
        warn!("TPROXY: no valid UIDs in file: {} (remove scoped chains)", uid_file.display());
        cleanup_scope_by_label(&scope).map_err(failed)?;
        crate::runtime_refresh::register_tproxy(uid_file, dest_port, proto_choice, ifaces_raw, opt, mark, ROUTE_TABLE);
        return Ok(());
    }

    let out_chain = prepare_scoped_chain(OUT_CHAIN, &scoped_out_chain_name(&scope)).map_err(failed)?;
    let pre_chain = prepare_scoped_chain(PRE_CHAIN, &scoped_pre_chain_name(&scope)).map_err(failed)?;

    let protos = proto_choice.protos();
    if opt.port_preference == 1 {
        for uid in &uids {
            for proto in protos {
                add_mark_rule(&out_chain, uid, proto, None, &mode, &ifaces, mark).map_err(failed)?;
                add_tproxy_rule(&pre_chain, proto, None, mark, dest_port).map_err(failed)?;
            }
        }
    } else {
        let ports_csv = normalize_ports_csv(&opt.dpi_ports);
        let dport_args = parse_dport_args(&ports_csv);
        if dport_args.is_empty() {
            return Err(failed(anyhow::anyhow!("TPROXY: no valid dpi_ports tokens")));
        }
        for uid in &uids {
            for proto in protos {
                for dp in &dport_args {
                    add_mark_rule(&out_chain, uid, proto, Some(dp.as_str()), &mode, &ifaces, mark).map_err(failed)?;
                }
            }
        }
        for proto in protos {
            for dp in &dport_args {
                add_tproxy_rule(&pre_chain, proto, Some(dp.as_str()), mark, dest_port).map_err(failed)?;
            }
        }
    }

    finish_scoped_chain(&out_chain).map_err(failed)?;
    finish_scoped_chain(&pre_chain).map_err(failed)?;
    crate::runtime_refresh::register_tproxy(uid_file, dest_port, proto_choice, ifaces_raw, opt, mark, ROUTE_TABLE);
    info!("TPROXY applied uid_file={} dest_port={} mark={} table={}", uid_file.display(), dest_port, mark_hex(mark), ROUTE_TABLE);
    Ok(())
}

fn probe_tproxy_runtime() -> Result<()> {
    // Android kernels vary a lot. Help text is not enough; insert real temporary
    // rules and policy-routing entries, then remove them.
    let _ = ipt_run_timeout(&["-t", "mangle", "-F", "ZDT_TPROXY_TEST"], Capture::None, IPT_CMD_TIMEOUT);
    let _ = ipt_run_timeout(&["-t", "mangle", "-X", "ZDT_TPROXY_TEST"], Capture::None, IPT_CMD_TIMEOUT);
    let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-N", "ZDT_TPROXY_TEST"], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("create test chain failed: {}", out.trim()); }
    let (rc, out) = ipt_run_timeout(&[
        "-t", "mangle", "-A", "ZDT_TPROXY_TEST",
        "-p", "tcp", "-j", "TPROXY", "--on-port", "1", "--tproxy-mark", "0x50000000/0xf0000000",
    ], Capture::Both, IPT_CMD_TIMEOUT)?;
    let _ = ipt_run_timeout(&["-t", "mangle", "-F", "ZDT_TPROXY_TEST"], Capture::None, IPT_CMD_TIMEOUT);
    let _ = ipt_run_timeout(&["-t", "mangle", "-X", "ZDT_TPROXY_TEST"], Capture::None, IPT_CMD_TIMEOUT);
    if rc != 0 { anyhow::bail!("TPROXY target test failed: {}", out.trim()); }

    let _ = ip_run_timeout(&["rule", "del", "fwmark", "0x50000000/0xf0000000", "lookup", "1057"], Capture::None, IP_CMD_TIMEOUT);
    let (rc, out) = ip_run_timeout(&["rule", "add", "fwmark", "0x50000000/0xf0000000", "lookup", "1057"], Capture::Both, IP_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("ip rule test failed: {}", out.trim()); }
    let (rc, out) = ip_run_timeout(&["route", "replace", "local", "0.0.0.0/0", "dev", "lo", "table", "1057"], Capture::Both, IP_CMD_TIMEOUT)?;
    let _ = ip_run_timeout(&["rule", "del", "fwmark", "0x50000000/0xf0000000", "lookup", "1057"], Capture::None, IP_CMD_TIMEOUT);
    if rc != 0 { anyhow::bail!("ip local route test failed: {}", out.trim()); }
    Ok(())
}

fn ensure_policy_route() -> Result<()> {
    let fwmark = route_mask_hex();
    let table = ROUTE_TABLE.to_string();

    // Android ip rule allows duplicates.  Keep this idempotent: remove every
    // old ZDT-D TPROXY rule, including the legacy 0x5d700000/0xffff0000 rule,
    // then add exactly one rule at a stable priority.
    cleanup_policy_rules_best_effort();

    let (rc, out) = ip_run_timeout(&["rule", "add", "pref", ROUTE_PREF, "fwmark", fwmark.as_str(), "lookup", table.as_str()], Capture::Both, IP_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("ip rule add failed: {}", out.trim()); }

    let (rc, out) = ip_run_timeout(&["route", "replace", "local", "0.0.0.0/0", "dev", "lo", "table", table.as_str()], Capture::Both, IP_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("ip route local replace failed: {}", out.trim()); }
    Ok(())
}

fn ensure_base_chains() -> Result<()> {
    ensure_chain("mangle", OUT_CHAIN)?;
    ensure_chain("mangle", PRE_CHAIN)?;
    ensure_chain("mangle", DIVERT_CHAIN)?;

    // Keep hooks deterministic and Box-for-Android-like:
    //   PREROUTING #1: existing transparent sockets are accepted via DIVERT
    //   PREROUTING #2: locally re-routed packets enter TPROXY delivery
    //   OUTPUT      #1: selected app traffic gets only high fwmark bits set
    delete_rule_all("mangle", "OUTPUT", &["-j", OUT_CHAIN])?;
    insert_rule_at("mangle", "OUTPUT", 1, &["-j", OUT_CHAIN])?;

    delete_rule_all("mangle", "PREROUTING", &["-p", "tcp", "-m", "socket", "--transparent", "-j", DIVERT_CHAIN])?;
    delete_rule_all("mangle", "PREROUTING", &["-p", "tcp", "-m", "socket", "-j", DIVERT_CHAIN])?;
    delete_rule_all("mangle", "PREROUTING", &["-j", PRE_CHAIN])?;
    insert_rule_at("mangle", "PREROUTING", 1, &["-p", "tcp", "-m", "socket", "-j", DIVERT_CHAIN])?;
    insert_rule_at("mangle", "PREROUTING", 2, &["-j", PRE_CHAIN])?;

    ensure_ordered_return_prefix(OUT_CHAIN, &[
        &["-o", "lo", "-j", "RETURN"],
        &["-d", "127.0.0.0/8", "-j", "RETURN"],
    ])?;
    ensure_ordered_return_prefix(PRE_CHAIN, &[
        &["-d", "127.0.0.0/8", "-j", "RETURN"],
    ])?;
    ensure_divert_chain()?;
    Ok(())
}

fn ensure_divert_chain() -> Result<()> {
    let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-F", DIVERT_CHAIN], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("flush {DIVERT_CHAIN} failed: {}", out.trim()); }
    let fwmark = route_mask_hex();
    add_rule_idempotent(DIVERT_CHAIN, vec!["-j".into(), "MARK".into(), "--set-xmark".into(), fwmark])?;
    add_rule_idempotent(DIVERT_CHAIN, vec!["-j".into(), "ACCEPT".into()])?;
    Ok(())
}

fn ensure_chain(table: &str, chain: &str) -> Result<()> {
    let (rc, _) = ipt_run_timeout(&["-t", table, "-nL", chain], Capture::None, IPT_SLOW_TIMEOUT)?;
    if rc != 0 {
        let (rc, out) = ipt_run_timeout(&["-t", table, "-N", chain], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 { anyhow::bail!("create {chain} failed: {}", out.trim()); }
    }
    Ok(())
}

fn ensure_hook(parent: &str, jump: &str) -> Result<()> {
    let (rc, _) = ipt_run_timeout(&["-t", "mangle", "-C", parent, "-j", jump], Capture::None, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-I", parent, "1", "-j", jump], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 { anyhow::bail!("hook {parent}->{jump} failed: {}", out.trim()); }
    }
    Ok(())
}

fn ensure_ordered_return_prefix(chain: &str, expected: &[&[&str]]) -> Result<()> {
    for rule in expected { delete_rule_all("mangle", chain, rule)?; }
    for (idx, rule) in expected.iter().enumerate() { insert_rule_at("mangle", chain, idx + 1, rule)?; }
    Ok(())
}

fn delete_rule_all(table: &str, chain: &str, rule: &[&str]) -> Result<()> {
    loop {
        let mut args = vec!["-t", table, "-D", chain];
        args.extend_from_slice(rule);
        let (rc, _) = ipt_run_timeout(&args, Capture::None, IPT_CMD_TIMEOUT)?;
        if rc != 0 { break; }
    }
    Ok(())
}

fn insert_rule_at(table: &str, chain: &str, pos: usize, rule: &[&str]) -> Result<()> {
    let pos_s = pos.to_string();
    let mut args = vec!["-t", table, "-I", chain, pos_s.as_str()];
    args.extend_from_slice(rule);
    let (rc, out) = ipt_run_timeout(&args, Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("insert rule failed in {table}/{chain}: {}", out.trim()); }
    Ok(())
}

fn prepare_scoped_chain(parent: &str, chain: &str) -> Result<String> {
    ensure_chain("mangle", chain)?;
    let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-F", chain], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("flush {chain} failed: {}", out.trim()); }
    let (rc, _) = ipt_run_timeout(&["-t", "mangle", "-C", parent, "-j", chain], Capture::None, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-A", parent, "-j", chain], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 { anyhow::bail!("hook {parent}->{chain} failed: {}", out.trim()); }
    }
    Ok(chain.to_string())
}

fn finish_scoped_chain(chain: &str) -> Result<()> {
    let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-A", chain, "-j", "RETURN"], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("add final RETURN failed in {chain}: {}", out.trim()); }
    Ok(())
}

pub fn cleanup_scope(uid_file: &Path, dest_port: u16, proto_choice: ProtoChoice, ifaces_raw: Option<&str>, opt: &DpiTunnelOptions) -> Result<()> {
    let scope = scope_label(uid_file, dest_port, proto_choice, ifaces_raw, opt);
    let _guard = xtables_lock::lock();
    cleanup_scope_by_label(&scope)
}

fn cleanup_scope_by_label(scope: &str) -> Result<()> {
    remove_scoped_chain(OUT_CHAIN, &scoped_out_chain_name(scope))?;
    remove_scoped_chain(PRE_CHAIN, &scoped_pre_chain_name(scope))?;
    Ok(())
}

fn remove_scoped_chain(parent: &str, chain: &str) -> Result<()> {
    delete_rule_all("mangle", parent, &["-j", chain])?;
    let _ = ipt_run_timeout(&["-t", "mangle", "-F", chain], Capture::None, IPT_CMD_TIMEOUT);
    let _ = ipt_run_timeout(&["-t", "mangle", "-X", chain], Capture::None, IPT_CMD_TIMEOUT);
    Ok(())
}

fn cleanup_policy_rules_best_effort() {
    let table = ROUTE_TABLE.to_string();
    for fwmark in [route_mask_hex(), legacy_route_mask_hex()] {
        loop {
            let Ok((rc, _)) = ip_run_timeout(&["rule", "del", "fwmark", fwmark.as_str(), "lookup", table.as_str()], Capture::None, IP_CMD_TIMEOUT) else {
                break;
            };
            if rc != 0 { break; }
        }
    }
    let _ = ip_run_timeout(&["rule", "del", "pref", ROUTE_PREF], Capture::None, IP_CMD_TIMEOUT);
}

pub fn cleanup_all() -> Result<()> {
    let _guard = xtables_lock::lock();
    delete_rule_all("mangle", "OUTPUT", &["-j", OUT_CHAIN])?;
    delete_rule_all("mangle", "PREROUTING", &["-p", "tcp", "-m", "socket", "--transparent", "-j", DIVERT_CHAIN])?;
    delete_rule_all("mangle", "PREROUTING", &["-p", "tcp", "-m", "socket", "-j", DIVERT_CHAIN])?;
    delete_rule_all("mangle", "PREROUTING", &["-j", PRE_CHAIN])?;

    // First flush parents so scoped chains are no longer referenced, then delete
    // every scoped chain explicitly.  This keeps stop correct even if the later
    // iptables backup restore is missing or fails.
    for chain in [OUT_CHAIN, PRE_CHAIN, DIVERT_CHAIN] {
        let _ = ipt_run_timeout(&["-t", "mangle", "-F", chain], Capture::None, IPT_CMD_TIMEOUT);
    }

    for chain in list_mangle_chains_with_prefix("ZDTP") {
        let _ = ipt_run_timeout(&["-t", "mangle", "-F", chain.as_str()], Capture::None, IPT_CMD_TIMEOUT);
        let _ = ipt_run_timeout(&["-t", "mangle", "-X", chain.as_str()], Capture::None, IPT_CMD_TIMEOUT);
    }

    for chain in [OUT_CHAIN, PRE_CHAIN, DIVERT_CHAIN] {
        let _ = ipt_run_timeout(&["-t", "mangle", "-X", chain], Capture::None, IPT_CMD_TIMEOUT);
    }

    let table = ROUTE_TABLE.to_string();
    cleanup_policy_rules_best_effort();
    let _ = ip_run_timeout(&["route", "flush", "table", table.as_str()], Capture::None, IP_CMD_TIMEOUT);
    Ok(())
}

fn list_mangle_chains_with_prefix(prefix: &str) -> Vec<String> {
    let Ok((0, out)) = crate::shell::run_timeout("iptables-save", &["-t", "mangle"], Capture::Stdout, IPT_SLOW_TIMEOUT) else {
        return Vec::new();
    };
    out.lines()
        .filter_map(|line| line.strip_prefix(':'))
        .filter_map(|line| line.split_whitespace().next())
        .filter(|name| name.starts_with(prefix))
        .map(|name| name.to_string())
        .collect()
}

fn add_mark_rule(chain: &str, uid: &str, proto: &str, extra: Option<&str>, mode: &str, ifaces: &[String], mark: u32) -> Result<()> {
    let extra_tokens = extra.map(|s| s.split_whitespace().map(|t| t.to_string()).collect::<Vec<_>>()).unwrap_or_default();
    let iface_list: Vec<Option<&str>> = if mode == "all" { vec![None] } else { ifaces.iter().map(|s| Some(s.as_str())).collect() };
    for iface in iface_list {
        let mut matcher: Vec<String> = Vec::new();
        if let Some(iface) = iface {
            matcher.push("-o".into());
            matcher.push(iface.into());
        }
        matcher.extend(["-p", proto, "-m", proto, "-m", "owner", "--uid-owner", uid].iter().map(|s| s.to_string()));
        matcher.extend(extra_tokens.clone());

        let mut mark_rule = matcher.clone();
        let mark_mask = mark_mask_hex(mark);
        mark_rule.extend(["-j", "MARK", "--set-xmark", mark_mask.as_str()].iter().map(|s| s.to_string()));
        add_rule_idempotent(chain, mark_rule)?;

        // MARK is non-terminating. Add a matching terminating ACCEPT directly
        // after the MARK path so a UID present in multiple scoped TPROXY
        // profiles is not re-marked by a later chain.
        let mut accept_rule = matcher;
        accept_rule.extend(["-j", "ACCEPT"].iter().map(|s| s.to_string()));
        add_rule_idempotent(chain, accept_rule)?;
    }
    Ok(())
}

fn add_tproxy_rule(chain: &str, proto: &str, extra: Option<&str>, mark: u32, dest_port: u16) -> Result<()> {
    let extra_tokens = extra.map(|s| s.split_whitespace().map(|t| t.to_string()).collect::<Vec<_>>()).unwrap_or_default();
    let mark_match = mark_mask_hex(mark);
    let port_s = dest_port.to_string();
    let mut rule: Vec<String> = vec!["-i".into(), "lo".into(), "-m".into(), "mark".into(), "--mark".into(), mark_match.clone(), "-p".into(), proto.into(), "-m".into(), proto.into()];
    rule.extend(extra_tokens);
    rule.extend(vec!["-j".into(), "TPROXY".into(), "--on-port".into(), port_s, "--tproxy-mark".into(), mark_match]);
    add_rule_idempotent(chain, rule)
}

fn add_rule_idempotent(chain: &str, rule: Vec<String>) -> Result<()> {
    let mut check: Vec<String> = vec!["-t".into(), "mangle".into(), "-C".into(), chain.into()];
    check.extend(rule.clone());
    let (rc, _) = ipt_runv_timeout(&check, Capture::None, IPT_CMD_TIMEOUT)?;
    if rc == 0 { return Ok(()); }
    let mut add: Vec<String> = vec!["-t".into(), "mangle".into(), "-A".into(), chain.into()];
    add.extend(rule);
    let (rc, out) = ipt_runv_timeout(&add, Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("add rule failed in {chain}: {}", out.trim()); }
    Ok(())
}

fn read_uids(uid_file: &Path) -> Result<Vec<String>> {
    if !uid_file.is_file() { anyhow::bail!("TPROXY: uid_file not readable: {}", uid_file.display()); }
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
                if parsed > 0 { set.insert(parsed.to_string()); }
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

fn parse_dport_args(ports_csv: &str) -> Vec<String> {
    let mut out = Vec::new();
    for token in ports_csv.split(',').map(|t| t.trim()).filter(|t| !t.is_empty()) {
        if let Some((a,b)) = parse_range(token) {
            out.push(format!("--dport {}:{}", a, b));
        } else if token.chars().all(|c| c.is_ascii_digit()) {
            out.push(format!("--dport {}", token));
        } else {
            warn!("TPROXY: skipping invalid port token: {}", token);
        }
    }
    out
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
                if let Some(d) = detect_default_iface()? { ifaces.push(d); } else { mode = "all".into(); }
            } else if mode == "user" {
                for f in tmp.replace(',', " ").split_whitespace() {
                    let mut f = f.trim().trim_end_matches(':');
                    if let Some(pos) = f.find('@') { f = &f[..pos]; }
                    if let Some(pos) = f.rfind(':') {
                        if !f.is_empty() && f[..pos].chars().all(|c| c.is_ascii_digit()) { f = &f[pos + 1..]; }
                    }
                    let f = f.trim();
                    if f.is_empty() { continue; }
                    if iface_exists(f) { ifaces.push(f.to_string()); } else { invalid.push(f.to_string()); }
                }
                if ifaces.is_empty() {
                    if let Some(d) = detect_default_iface()? { ifaces.push(d); } else { mode = "all".into(); }
                }
            }
        }
    }
    if mode == "all" { ifaces.clear(); }
    Ok((mode, ifaces, invalid))
}

fn iface_exists(name: &str) -> bool {
    if crate::shell::run("ip", &["link","show",name], Capture::None).map(|(c,_)| c==0).unwrap_or(false) { return true; }
    if crate::shell::run("ifconfig", &[name], Capture::None).map(|(c,_)| c==0).unwrap_or(false) { return true; }
    std::path::Path::new("/sys/class/net").join(name).is_dir()
}

fn detect_default_iface() -> Result<Option<String>> {
    if let Ok((c,out)) = crate::shell::run("ip", &["route","get","8.8.8.8"], Capture::Stdout) {
        if c == 0 { if let Some(dev) = parse_dev_from_route(&out) { return Ok(Some(dev)); } }
    }
    if let Ok((c,out)) = crate::shell::run("ip", &["route"], Capture::Stdout) {
        if c == 0 { if let Some(dev) = parse_default_dev(&out) { return Ok(Some(dev)); } }
    }
    if let Ok(rd) = fs::read_dir("/sys/class/net") {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if n != "lo" { return Ok(Some(n)); }
        }
    }
    Ok(None)
}

fn parse_dev_from_route(out: &str) -> Option<String> {
    let toks: Vec<&str> = out.split_whitespace().collect();
    for i in 0..toks.len() { if toks[i] == "dev" && i+1 < toks.len() { return Some(toks[i+1].to_string()); } }
    None
}
fn parse_default_dev(out: &str) -> Option<String> {
    for line in out.lines() {
        if !line.contains("default") { continue; }
        let toks: Vec<&str> = line.split_whitespace().collect();
        for i in 0..toks.len() { if toks[i] == "dev" && i+1 < toks.len() { return Some(toks[i+1].to_string()); } }
    }
    None
}
