//! ZDT-D TPROXY backend for t2s-aware routing.
//! The production t2s path now calls the real `apply` function through
//! `programs::common::apply_t2s_routing`; if the device/kernel cannot support
//! TPROXY, the caller falls back to the standard DNAT backend.
#![allow(dead_code)]

use anyhow::{Context, Result};
use log::{info, warn};
use std::{collections::{BTreeMap, BTreeSet}, fs, path::Path, time::Duration};

use crate::{settings, shell::Capture, xtables_lock};
use super::iptables_port::{DpiTunnelOptions, ProtoChoice};

/// Legacy compatibility entrypoint for stale callers.
/// New t2s routing should use `programs::common::apply_t2s_routing`, which
/// tries the real TPROXY backend and then falls back to DNAT when unsupported.
pub fn apply_or_fallback(
    uid_file: &Path,
    dest_port: u16,
    proto_choice: ProtoChoice,
    ifaces_raw: Option<&str>,
    opt: &DpiTunnelOptions,
) -> Result<()> {
    warn!(
        "TPROXY: legacy fallback entrypoint called; use apply_t2s_routing for real TPROXY, \
         continuing with DNAT (uid_file={} dest_port={} proto={:?} ifaces={} port_preference={} dpi_ports='{}')",
        uid_file.display(),
        dest_port,
        proto_choice,
        ifaces_raw.unwrap_or(""),
        opt.port_preference,
        opt.dpi_ports,
    );
    Ok(())
}

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const IPT_SLOW_TIMEOUT: Duration = Duration::from_secs(15);
const IP_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";

const OUT_CHAIN: &str = "ZDT_TPROXY_OUT";
const PRE_CHAIN: &str = "ZDT_TPROXY_PRE";
const DIVERT_CHAIN: &str = "ZDT_TPROXY_DIVERT";

/// IPv4 ranges that must never be TPROXY'd: they have to reach the local stack
/// or the LAN directly.  Covers CGNAT/RFC1918 private space, link-local,
/// multicast and reserved blocks.  Matches the proven box_for_magisk bypass
/// list.  Loopback 127.0.0.0/8 is handled separately (see ensure_local_bypass).
const INTRANET_V4: &[&str] = &[
    "0.0.0.0/8",
    "10.0.0.0/8",
    "100.64.0.0/10",
    "169.254.0.0/16",
    "172.16.0.0/12",
    "192.0.0.0/24",
    "192.168.0.0/16",
    "224.0.0.0/4",
    "240.0.0.0/4",
    "255.255.255.255/32",
];
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

// Persistent, collision-free per-scope slot registry.
//
// The fwmark carries only 8 bits of per-scope identity (bits 20..27) plus a
// route nibble (bits 28..31); the lower 20 Android fwmark bits must stay
// untouched.  Deriving that slot from a hash (hash & 0xff) let two different
// scopes share a mark: PREROUTING TPROXY delivery is selected purely by mark
// (--uid-owner is not available there), so a collision routed one app's
// packets to another app's proxy port.  That is the "split-tunnel paths
// cross" bug.  We now assign each scope a unique slot from a persisted
// registry so marks never collide.
const SLOT_REGISTRY_FILE: &str = "tproxy_slots";
const SLOT_MIN: u32 = 1;
const SLOT_MAX: u32 = 254;

fn mark_from_slot(slot: u32) -> u32 { ROUTE_MARK | (slot << 20) }

fn slot_registry_path() -> std::path::PathBuf {
    Path::new(settings::SETTING_DIR).join(SLOT_REGISTRY_FILE)
}

// Registry format: one `slot\tscope_label` line per scope.  scope_label is
// always single-line, so a tab separator is unambiguous and needs no escaping.
fn load_slot_registry() -> BTreeMap<String, u32> {
    let mut map = BTreeMap::new();
    let Ok(body) = fs::read_to_string(slot_registry_path()) else {
        return map;
    };
    for line in body.lines() {
        let line = line.trim_end_matches('\r');
        if line.is_empty() {
            continue;
        }
        let Some((slot_str, label)) = line.split_once('\t') else {
            continue;
        };
        let Ok(slot) = slot_str.trim().parse::<u32>() else {
            continue;
        };
        if !(SLOT_MIN..=SLOT_MAX).contains(&slot) || label.is_empty() {
            continue;
        }
        map.insert(label.to_string(), slot);
    }
    map
}

fn save_slot_registry(map: &BTreeMap<String, u32>) -> Result<()> {
    let path = slot_registry_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create setting dir {}", parent.display()))?;
    }
    let mut body = String::new();
    for (label, slot) in map {
        // scope labels are single-line; skip any that would corrupt the file.
        if label.contains('\t') || label.contains('\n') {
            continue;
        }
        body.push_str(&format!("{slot}\t{label}\n"));
    }
    fs::write(&path, body).with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

fn alloc_slot_for_scope(scope: &str) -> Result<u32> {
    let mut map = load_slot_registry();
    if let Some(&slot) = map.get(scope) {
        return Ok(slot);
    }
    let used: BTreeSet<u32> = map.values().copied().collect();
    let mut chosen: Option<u32> = None;
    for slot in SLOT_MIN..=SLOT_MAX {
        if used.contains(&slot) {
            continue;
        }
        // Never hand out the slot whose mark equals the legacy route mark, to
        // avoid ambiguity with legacy 0x5d700000/0xffff0000 policy rules.
        if mark_from_slot(slot) == LEGACY_ROUTE_MARK {
            continue;
        }
        chosen = Some(slot);
        break;
    }
    let slot = chosen.ok_or_else(|| {
        anyhow::anyhow!(
            "TPROXY: no free scope slot (all {}..={} in use, {} scopes registered)",
            SLOT_MIN,
            SLOT_MAX,
            map.len()
        )
    })?;
    map.insert(scope.to_string(), slot);
    save_slot_registry(&map)?;
    Ok(slot)
}

fn free_slot_for_scope(scope: &str) {
    let mut map = load_slot_registry();
    if map.remove(scope).is_some() {
        if let Err(e) = save_slot_registry(&map) {
            warn!("TPROXY: failed to persist slot registry after freeing scope: {e:#}");
        }
    }
}

fn clear_slot_registry() {
    let path = slot_registry_path();
    if path.exists() {
        if let Err(e) = fs::remove_file(&path) {
            warn!("TPROXY: failed to remove slot registry {}: {e:#}", path.display());
        }
    }
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
    let slot = alloc_slot_for_scope(&scope).map_err(failed)?;
    let mark = mark_from_slot(slot);
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
        "-p", "tcp", "-j", "TPROXY", "--on-ip", "127.0.0.1", "--on-port", "1", "--tproxy-mark", "0x50000000/0xf0000000",
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

    // Older Android releases and vendor kernels can keep stale route-cache
    // decisions after policy-route changes.  On newer kernels this is harmless
    // or a no-op, so keep it best-effort for Android 11..future releases.
    let _ = ip_run_timeout(&["route", "flush", "cache"], Capture::None, IP_CMD_TIMEOUT);
    Ok(())
}

fn ensure_base_chains() -> Result<()> {
    ensure_chain("mangle", OUT_CHAIN)?;
    ensure_chain("mangle", PRE_CHAIN)?;
    ensure_chain("mangle", DIVERT_CHAIN)?;

    // Keep hooks deterministic and aligned with the proven box_for_magisk
    // layout that reliably delivers TPROXY traffic to the local t2s sockets:
    //   OUTPUT      #1: selected app traffic gets only the high fwmark bits.
    //   PREROUTING #1: socket DIVERT.  Packets that already belong to an
    //                  established local (t2s) socket are re-marked for the
    //                  policy route and accepted before TPROXY, so existing
    //                  connections are delivered locally instead of being
    //                  re-TPROXY'd.  A plain `-m socket` match (no
    //                  `--transparent`) is used so it also catches sockets
    //                  that are not flagged transparent.
    //   PREROUTING #2: scoped marked packets enter ZDT-D TPROXY delivery.
    delete_rule_all("mangle", "OUTPUT", &["-j", OUT_CHAIN])?;
    insert_rule_at("mangle", "OUTPUT", 1, &["-j", OUT_CHAIN])?;

    delete_rule_all("mangle", "PREROUTING", &["-p", "tcp", "-m", "socket", "--transparent", "-j", DIVERT_CHAIN])?;
    delete_rule_all("mangle", "PREROUTING", &["-p", "tcp", "-m", "socket", "-j", DIVERT_CHAIN])?;
    delete_rule_all("mangle", "PREROUTING", &["-j", PRE_CHAIN])?;

    // Insert PRE first, then DIVERT in front of it, so the resulting order is
    // #1 DIVERT, #2 PRE.
    insert_rule_at("mangle", "PREROUTING", 1, &["-j", PRE_CHAIN])?;
    // Socket DIVERT is an optimization for already-established local sockets and
    // relies on the xt_socket match.  It is present on all mainstream Android
    // 11..17 kernels, but if some vendor kernel lacks it we keep scoped TPROXY
    // working instead of failing the whole apply (which would otherwise drop
    // t2s down to the TCP-only DNAT fallback).  Both steps are best-effort.
    match ensure_divert_chain() {
        Ok(()) => {
            if let Err(e) = insert_rule_at("mangle", "PREROUTING", 1, &["-p", "tcp", "-m", "socket", "-j", DIVERT_CHAIN]) {
                warn!("TPROXY socket DIVERT hook not installed, continuing without it: {e:#}");
            }
        }
        Err(e) => warn!("TPROXY DIVERT chain setup failed, continuing without socket divert: {e:#}"),
    }

    // Never TPROXY loopback or intranet/private-range destinations: those must
    // reach the local stack or the LAN directly.  Keep these RETURN rules at
    // the very top of both hook chains, before any scoped TPROXY jump.
    ensure_local_bypass(OUT_CHAIN, true)?;
    ensure_local_bypass(PRE_CHAIN, false)?;
    Ok(())
}

fn ensure_divert_chain() -> Result<()> {
    let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-F", DIVERT_CHAIN], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("flush {DIVERT_CHAIN} failed: {}", out.trim()); }
    // Mark for the ZDT-D policy route (local dev lo -> t2s) and accept, so an
    // established-socket packet is delivered locally instead of re-TPROXY'd.
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

fn ensure_local_bypass(chain: &str, include_loopback: bool) -> Result<()> {
    // Ordered leading RETURN rules for local/intranet destinations.  Loopback
    // (-o lo and 127.0.0.0/8) is only added to the OUTPUT-side chain; adding a
    // 127/8 RETURN to the PREROUTING chain could skip the scoped TPROXY target
    // on some Android kernels, so PRE_CHAIN gets the intranet ranges only.
    let mut rules: Vec<Vec<&str>> = Vec::new();
    if include_loopback {
        rules.push(vec!["-o", "lo", "-j", "RETURN"]);
        rules.push(vec!["-d", "127.0.0.0/8", "-j", "RETURN"]);
    }
    for &net in INTRANET_V4 {
        rules.push(vec!["-d", net, "-j", "RETURN"]);
    }
    for rule in &rules { delete_rule_all("mangle", chain, rule)?; }
    for (idx, rule) in rules.iter().enumerate() { insert_rule_at("mangle", chain, idx + 1, rule)?; }
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
    // Release the scope's fwmark slot so it can be reused by another profile.
    free_slot_for_scope(scope);
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
    // All scoped chains and policy rules are gone; drop the slot registry too.
    clear_slot_registry();
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
    rule.extend(vec![
        "-j".into(),
        "TPROXY".into(),
        "--on-ip".into(),
        "127.0.0.1".into(),
        "--on-port".into(),
        port_s,
        "--tproxy-mark".into(),
        mark_match,
    ]);
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
