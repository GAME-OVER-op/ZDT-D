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
// Dedicated routing table for ZDT-D TPROXY delivery. Android numbers its
// per-interface tables as ifindex+1000 (>=1000) and reserves 253..255 for
// local/main/default, so a value in the 256..999 gap avoids those tables.
const ROUTE_TABLE: u32 = 787;
// The rule must run before Android's VPN/network rules. The priority is shared
// only by exact, disjoint ZDT-D marks; cleanup never deletes by priority alone.
const ROUTE_PREF: &str = "100";

// Android Fwmark uses bits 0..20, reserves bits 21..28, gives bits 29..30 to
// vendors and bit 31 to ingress wakeup accounting. Use only the eight reserved
// bits and preserve every other bit already attached by Android/netd/OEM code.
//
// Slot 0 is reserved for socket DIVERT. Slots 1..127 identify scoped TPROXY
// listeners. Policy routing matches the complete eight-bit value, never a
// single common bit, so unrelated Android/OEM marks cannot enter table 787.
const ROUTE_MARK: u32 = 0x0020_0000; // bit 21, slot 0 / DIVERT mark
const SLOT_SHIFT: u32 = 22;          // bits 22..28, seven-bit scope slot
const SCOPE_MASK: u32 = 0x1fe0_0000; // bits 21..28 only

// Previous/current and older schemes are retained only for surgical upgrade
// cleanup. Never delete rules by preference alone and never flush their whole
// routing tables: table 1057 can be an Android ifindex+1000 table.
const PREVIOUS_ROUTE_MARK: u32 = 0x0100_0000;
const PREVIOUS_ROUTE_MASK: u32 = 0x0100_0000;
const OLD_ROUTE_MARK: u32 = 0x5000_0000;
const OLD_ROUTE_MASK: u32 = 0xf000_0000;
const OLD_ROUTE_TABLE: u32 = 1057;
const LEGACY_ROUTE_MARK: u32 = 0x5d70_0000;
const LEGACY_ROUTE_MASK: u32 = 0xffff_0000;
const TPROXY_NO_FILE: &str = "tproxy_no";
const V6_BLOCK_PREFIX: &str = "ZDTV6";

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
// The fwmark carries the per-scope identity in reserved bits 22..28 on top of
// the bit-21 route marker. Android/netd bits 0..20 and vendor/wakeup bits 29..31
// must stay untouched.  Deriving that slot from a hash let two
// different scopes share a mark: PREROUTING TPROXY delivery is selected purely
// by mark (--uid-owner is not available there), so a collision routed one
// app's packets to another app's proxy port.  That is the "split-tunnel paths
// cross" bug.  We now assign each scope a unique slot from a persisted
// registry so marks never collide.
const SLOT_REGISTRY_FILE: &str = "tproxy_slots";
const SLOT_MIN: u32 = 1;
const SLOT_MAX: u32 = 127;

fn mark_from_slot(slot: u32) -> u32 { ROUTE_MARK | (slot << SLOT_SHIFT) }

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
fn route_mask_hex() -> String { mark_mask_hex(ROUTE_MARK) }
fn previous_route_mask_hex() -> String { format!("0x{PREVIOUS_ROUTE_MARK:08x}/0x{PREVIOUS_ROUTE_MASK:08x}") }
fn old_route_mask_hex() -> String { format!("0x{OLD_ROUTE_MARK:08x}/0x{OLD_ROUTE_MASK:08x}") }
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
    let uids = read_uids(uid_file).map_err(failed)?;
    let slot = alloc_slot_for_scope(&scope).map_err(failed)?;
    let mark = mark_from_slot(slot);

    if uids.is_empty() {
        warn!("TPROXY: no valid UIDs in file: {} (remove scoped chains)", uid_file.display());
        cleanup_scope_by_label(&scope).map_err(failed)?;
        crate::runtime_refresh::register_tproxy(uid_file, dest_port, proto_choice, ifaces_raw, opt, mark, ROUTE_TABLE);
        return Ok(());
    }

    let apply_result = (|| -> Result<()> {
        ensure_policy_route(mark)?;
        ensure_base_chains()?;

        let out_chain = prepare_scoped_chain(OUT_CHAIN, &scoped_out_chain_name(&scope))?;
        let pre_chain = prepare_scoped_chain(PRE_CHAIN, &scoped_pre_chain_name(&scope))?;

        let protos = proto_choice.protos();
        if opt.port_preference == 1 {
            for uid in &uids {
                for proto in protos {
                    add_mark_rule(&out_chain, uid, proto, None, &mode, &ifaces, mark)?;
                    add_tproxy_rule(&pre_chain, proto, None, mark, dest_port)?;
                }
            }
        } else {
            let ports_csv = normalize_ports_csv(&opt.dpi_ports);
            let dport_args = parse_dport_args(&ports_csv);
            if dport_args.is_empty() {
                anyhow::bail!("TPROXY: no valid dpi_ports tokens");
            }
            for uid in &uids {
                for proto in protos {
                    for dp in &dport_args {
                        add_mark_rule(&out_chain, uid, proto, Some(dp.as_str()), &mode, &ifaces, mark)?;
                    }
                }
            }
            for proto in protos {
                for dp in &dport_args {
                    add_tproxy_rule(&pre_chain, proto, Some(dp.as_str()), mark, dest_port)?;
                }
            }
        }

        finish_scoped_chain(&out_chain)?;
        finish_scoped_chain(&pre_chain)?;
        Ok(())
    })();

    if let Err(e) = apply_result {
        if let Err(cleanup_err) = cleanup_scope_by_label(&scope) {
            warn!("TPROXY: rollback failed for scope after apply error: {cleanup_err:#}");
        }
        return Err(failed(e));
    }
    // TPROXY is IPv4-only.  Block IPv6 for exactly these UIDs so their traffic
    // cannot leak straight out over IPv6 and instead falls back to IPv4 (which
    // is what gets TPROXY'd).  Best-effort: never fail the whole apply on it.
    apply_ipv6_block(&scope, &uids);
    crate::runtime_refresh::register_tproxy(uid_file, dest_port, proto_choice, ifaces_raw, opt, mark, ROUTE_TABLE);
    info!("TPROXY applied uid_file={} dest_port={} mark={} table={}", uid_file.display(), dest_port, mark_hex(mark), ROUTE_TABLE);
    Ok(())
}

fn probe_tproxy_runtime() -> Result<()> {
    // Test only the xt_TPROXY target. Do not add or remove production policy
    // rules here: apply() can be called while other scoped routes are active.
    let _ = ipt_run_timeout(&["-t", "mangle", "-F", "ZDT_TPROXY_TEST"], Capture::None, IPT_CMD_TIMEOUT);
    let _ = ipt_run_timeout(&["-t", "mangle", "-X", "ZDT_TPROXY_TEST"], Capture::None, IPT_CMD_TIMEOUT);
    let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-N", "ZDT_TPROXY_TEST"], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("create test chain failed: {}", out.trim()); }
    let tp_mark = route_mask_hex();
    let (rc, out) = ipt_run_timeout(&[
        "-t", "mangle", "-A", "ZDT_TPROXY_TEST",
        "-p", "tcp", "-j", "TPROXY", "--on-ip", "127.0.0.1", "--on-port", "1", "--tproxy-mark", tp_mark.as_str(),
    ], Capture::Both, IPT_CMD_TIMEOUT)?;
    let _ = ipt_run_timeout(&["-t", "mangle", "-F", "ZDT_TPROXY_TEST"], Capture::None, IPT_CMD_TIMEOUT);
    let _ = ipt_run_timeout(&["-t", "mangle", "-X", "ZDT_TPROXY_TEST"], Capture::None, IPT_CMD_TIMEOUT);
    if rc != 0 { anyhow::bail!("TPROXY target test failed: {}", out.trim()); }
    Ok(())
}

#[derive(Debug, Clone, Copy)]
struct PolicyRule {
    pref: u32,
    mark: u32,
    mask: u32,
    table: u32,
}

fn parse_u32_token(token: &str) -> Option<u32> {
    let token = token.trim().trim_end_matches(':');
    if let Some(hex) = token.strip_prefix("0x").or_else(|| token.strip_prefix("0X")) {
        u32::from_str_radix(hex, 16).ok()
    } else {
        token.parse::<u32>().ok()
    }
}

fn parse_policy_rule(line: &str) -> Option<PolicyRule> {
    let tokens = line.split_whitespace().collect::<Vec<_>>();
    let pref = parse_u32_token(tokens.first().copied()?)?;
    let fwmark_pos = tokens.iter().position(|t| *t == "fwmark")?;
    let mark_spec = *tokens.get(fwmark_pos + 1)?;
    let (mark_s, mask_s) = mark_spec.split_once('/').unwrap_or((mark_spec, "0xffffffff"));
    let mark = parse_u32_token(mark_s)?;
    let mask = parse_u32_token(mask_s)?;
    let table_pos = tokens.iter().position(|t| *t == "lookup" || *t == "table")?;
    let table = parse_u32_token(tokens.get(table_pos + 1).copied()?)?;
    Some(PolicyRule { pref, mark, mask, table })
}

fn read_policy_rules() -> Result<Vec<PolicyRule>> {
    let (rc, out) = ip_run_timeout(&["rule", "show"], Capture::Stdout, IP_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("ip rule show failed"); }
    Ok(out.lines().filter_map(parse_policy_rule).collect())
}

fn policy_rule_exists(mark: u32, mask: u32, table: u32) -> Result<bool> {
    Ok(read_policy_rules()?.into_iter().any(|r| {
        r.pref == ROUTE_PREF.parse::<u32>().unwrap_or(100)
            && r.mark == mark
            && r.mask == mask
            && r.table == table
    }))
}

fn ensure_policy_rule(mark: u32) -> Result<()> {
    if policy_rule_exists(mark, SCOPE_MASK, ROUTE_TABLE)? {
        return Ok(());
    }
    let fwmark = mark_mask_hex(mark);
    let table = ROUTE_TABLE.to_string();
    let (rc, out) = ip_run_timeout(
        &["rule", "add", "pref", ROUTE_PREF, "fwmark", fwmark.as_str(), "lookup", table.as_str()],
        Capture::Both,
        IP_CMD_TIMEOUT,
    )?;
    if rc != 0 { anyhow::bail!("ip rule add failed: {}", out.trim()); }
    Ok(())
}

fn ensure_policy_route(scope_mark: u32) -> Result<()> {
    let table = ROUTE_TABLE.to_string();

    // Install the local route before exposing any matching policy rule, so a
    // live packet can never observe a rule that points at an empty table.
    let (rc, out) = ip_run_timeout(
        &["route", "replace", "local", "0.0.0.0/0", "dev", "lo", "table", table.as_str()],
        Capture::Both,
        IP_CMD_TIMEOUT,
    )?;
    if rc != 0 { anyhow::bail!("ip route local replace failed: {}", out.trim()); }

    cleanup_legacy_policy_rules_best_effort();
    ensure_policy_rule(ROUTE_MARK)?;
    ensure_policy_rule(scope_mark)?;

    // Older Android releases and vendor kernels can keep stale route-cache
    // decisions after policy-route changes. On newer kernels this is harmless.
    let _ = ip_run_timeout(&["route", "flush", "cache"], Capture::None, IP_CMD_TIMEOUT);
    Ok(())
}

fn ensure_base_chains() -> Result<()> {
    ensure_chain("mangle", OUT_CHAIN)?;
    ensure_chain("mangle", PRE_CHAIN)?;
    ensure_chain("mangle", DIVERT_CHAIN)?;

    // Install hooks once and leave active hooks in place on later scope applies.
    // Repeated delete/insert cycles created short routing gaps for every already
    // running TPROXY profile.
    ensure_hook("OUTPUT", OUT_CHAIN)?;

    // Remove only the obsolete transparent variant. Keep the current hooks and
    // create missing ones in deterministic DIVERT -> PRE order without first
    // tearing down working routing.
    delete_rule_all("mangle", "PREROUTING", &["-p", "tcp", "-m", "socket", "--transparent", "-j", DIVERT_CHAIN])?;
    let divert_rule = ["-p", "tcp", "-m", "socket", "-j", DIVERT_CHAIN];
    let pre_rule = ["-j", PRE_CHAIN];
    let divert_exists = rule_exists("mangle", "PREROUTING", &divert_rule)?;
    let pre_exists = rule_exists("mangle", "PREROUTING", &pre_rule)?;

    if !pre_exists {
        insert_rule_at("mangle", "PREROUTING", if divert_exists { 2 } else { 1 }, &pre_rule)?;
    }

    // Socket DIVERT is best-effort; scoped TPROXY remains functional without
    // xt_socket support.
    match ensure_divert_chain() {
        Ok(()) if !divert_exists => {
            if let Err(e) = insert_rule_at("mangle", "PREROUTING", 1, &divert_rule) {
                warn!("TPROXY socket DIVERT hook not installed, continuing without it: {e:#}");
            }
        }
        Ok(()) => {}
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
    let fwmark = route_mask_hex();
    let mark_rule = ["-j", "MARK", "--set-xmark", fwmark.as_str()];
    let accept_rule = ["-j", "ACCEPT"];
    if rule_exists("mangle", DIVERT_CHAIN, &mark_rule)?
        && rule_exists("mangle", DIVERT_CHAIN, &accept_rule)?
    {
        return Ok(());
    }

    let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-F", DIVERT_CHAIN], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("flush {DIVERT_CHAIN} failed: {}", out.trim()); }
    // Slot 0 has its own exact policy rule and cannot collide with scoped marks.
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

fn rule_exists(table: &str, chain: &str, rule: &[&str]) -> Result<bool> {
    let mut args = vec!["-t", table, "-C", chain];
    args.extend_from_slice(rule);
    let (rc, _) = ipt_run_timeout(&args, Capture::None, IPT_CMD_TIMEOUT)?;
    Ok(rc == 0)
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
    if rules.iter().all(|rule| rule_exists("mangle", chain, rule).unwrap_or(false)) {
        return Ok(());
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
    // Remove this scope's per-UID IPv6 leak-block chain, if any.
    if ip6tables_available() {
        remove_v6_block_chain(&scoped_v6_chain_name(scope));
    }
    // Remove only this scope's exact policy rule, then release its slot.
    if let Some(slot) = load_slot_registry().get(scope).copied() {
        delete_policy_rule_exact(mark_from_slot(slot), SCOPE_MASK, ROUTE_TABLE, Some(ROUTE_PREF));
    }
    free_slot_for_scope(scope);
    cleanup_base_if_no_scopes();
    Ok(())
}

fn cleanup_base_if_no_scopes() {
    // The slot registry is recovery metadata, not the source of truth. Keep the
    // shared route while any scoped chain still exists; if iptables-save cannot
    // be read, fail safe and leave the base routing in place.
    let Some(scoped_chains) = try_list_mangle_chains_with_prefix("ZDTP") else {
        return;
    };
    if !scoped_chains.is_empty() {
        return;
    }

    clear_slot_registry();
    let _ = delete_rule_all("mangle", "OUTPUT", &["-j", OUT_CHAIN]);
    let _ = delete_rule_all("mangle", "PREROUTING", &["-p", "tcp", "-m", "socket", "-j", DIVERT_CHAIN]);
    let _ = delete_rule_all("mangle", "PREROUTING", &["-j", PRE_CHAIN]);
    for chain in [OUT_CHAIN, PRE_CHAIN, DIVERT_CHAIN] {
        let _ = ipt_run_timeout(&["-t", "mangle", "-F", chain], Capture::None, IPT_CMD_TIMEOUT);
        let _ = ipt_run_timeout(&["-t", "mangle", "-X", chain], Capture::None, IPT_CMD_TIMEOUT);
    }
    delete_policy_rule_exact(ROUTE_MARK, SCOPE_MASK, ROUTE_TABLE, Some(ROUTE_PREF));
    delete_local_route_best_effort(ROUTE_TABLE);
    let _ = ip_run_timeout(&["route", "flush", "cache"], Capture::None, IP_CMD_TIMEOUT);
}

fn remove_scoped_chain(parent: &str, chain: &str) -> Result<()> {
    delete_rule_all("mangle", parent, &["-j", chain])?;
    let _ = ipt_run_timeout(&["-t", "mangle", "-F", chain], Capture::None, IPT_CMD_TIMEOUT);
    let _ = ipt_run_timeout(&["-t", "mangle", "-X", chain], Capture::None, IPT_CMD_TIMEOUT);
    Ok(())
}

fn del_ip_rules_by_fwmark(fwmark: &str, table: &str) {
    loop {
        let Ok((rc, _)) = ip_run_timeout(&["rule", "del", "fwmark", fwmark, "lookup", table], Capture::None, IP_CMD_TIMEOUT) else {
            break;
        };
        if rc != 0 { break; }
    }
}

fn delete_policy_rule_exact(mark: u32, mask: u32, table: u32, pref: Option<&str>) {
    let fwmark = format!("0x{mark:08x}/0x{mask:08x}");
    let table_s = table.to_string();
    loop {
        let result = if let Some(pref) = pref {
            ip_run_timeout(
                &["rule", "del", "pref", pref, "fwmark", fwmark.as_str(), "lookup", table_s.as_str()],
                Capture::None,
                IP_CMD_TIMEOUT,
            )
        } else {
            ip_run_timeout(
                &["rule", "del", "fwmark", fwmark.as_str(), "lookup", table_s.as_str()],
                Capture::None,
                IP_CMD_TIMEOUT,
            )
        };
        let Ok((rc, _)) = result else { break; };
        if rc != 0 { break; }
    }
}

fn cleanup_current_policy_rules_best_effort() {
    let Ok(rules) = read_policy_rules() else { return; };
    for rule in rules {
        if rule.table == ROUTE_TABLE
            && rule.mask == SCOPE_MASK
            && (rule.mark & ROUTE_MARK) == ROUTE_MARK
        {
            let pref = rule.pref.to_string();
            delete_policy_rule_exact(rule.mark, rule.mask, rule.table, Some(pref.as_str()));
        }
    }
}

fn cleanup_legacy_policy_rules_best_effort() {
    let table = ROUTE_TABLE.to_string();
    // Remove the immediately previous broad one-bit rule exactly. Do not touch
    // any unrelated rule that merely shares preference 100.
    del_ip_rules_by_fwmark(previous_route_mask_hex().as_str(), table.as_str());

    // Remove older exact ZDT-D marks from both historical table 1057 and the
    // current table. Never delete priority 9999 wholesale.
    let old_table = OLD_ROUTE_TABLE.to_string();
    for fwmark in [old_route_mask_hex(), legacy_route_mask_hex()] {
        del_ip_rules_by_fwmark(fwmark.as_str(), old_table.as_str());
        del_ip_rules_by_fwmark(fwmark.as_str(), table.as_str());
    }
}

fn cleanup_policy_rules_best_effort() {
    cleanup_current_policy_rules_best_effort();
    cleanup_legacy_policy_rules_best_effort();
}

fn delete_local_route_best_effort(table: u32) {
    let table_s = table.to_string();
    loop {
        let Ok((rc, _)) = ip_run_timeout(
            &["route", "del", "local", "0.0.0.0/0", "dev", "lo", "table", table_s.as_str()],
            Capture::None,
            IP_CMD_TIMEOUT,
        ) else {
            break;
        };
        if rc != 0 { break; }
    }
}

// --- Per-UID IPv6 leak blocking for TPROXY scopes ---------------------------
//
// TPROXY only intercepts IPv4.  A proxied app that also has working IPv6 would
// send traffic straight out over IPv6 and bypass t2s (an IPv6 leak, and a
// plausible reason a scope looked "broken" on some devices).  For each TPROXY
// scope we therefore REJECT all IPv6 originating from exactly the routed UIDs,
// using ICMPv6 admin-prohibited so the app fails fast and falls back to IPv4
// (which is what actually gets TPROXY'd).  Non-routed apps keep their IPv6.
//
// Everything here is best-effort: if ip6tables is unavailable we log and keep
// the IPv4 TPROXY path working rather than failing the whole apply.

fn ip6t_run_timeout(args: &[&str], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    let mut a: Vec<&str> = Vec::with_capacity(args.len() + 2);
    a.push("-w");
    a.push(XT_WAIT_SECS);
    a.extend_from_slice(args);
    xtables_lock::run_timeout_retry("ip6tables", &a, capture, timeout)
}

fn ip6tables_available() -> bool {
    matches!(
        ip6t_run_timeout(&["-t", "filter", "-nL", "OUTPUT"], Capture::None, IPT_CMD_TIMEOUT),
        Ok((0, _))
    )
}

pub fn scoped_v6_chain_name(label: &str) -> String { format!("{V6_BLOCK_PREFIX}_{:016x}", scoped_hash(label)) }

fn remove_v6_block_chain(chain: &str) {
    loop {
        let Ok((rc, _)) = ip6t_run_timeout(&["-t", "filter", "-D", "OUTPUT", "-j", chain], Capture::None, IPT_CMD_TIMEOUT) else {
            break;
        };
        if rc != 0 { break; }
    }
    let _ = ip6t_run_timeout(&["-t", "filter", "-F", chain], Capture::None, IPT_CMD_TIMEOUT);
    let _ = ip6t_run_timeout(&["-t", "filter", "-X", chain], Capture::None, IPT_CMD_TIMEOUT);
}

fn build_ipv6_block_chain(chain: &str, uids: &[String]) -> Result<()> {
    let (rc, out) = ip6t_run_timeout(&["-t", "filter", "-N", chain], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("create v6 chain {chain} failed: {}", out.trim()); }
    for uid in uids {
        let (rc, out) = ip6t_run_timeout(&[
            "-t", "filter", "-A", chain,
            "-m", "owner", "--uid-owner", uid.as_str(),
            "-j", "REJECT", "--reject-with", "icmp6-adm-prohibited",
        ], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 { anyhow::bail!("v6 reject rule uid={uid} failed: {}", out.trim()); }
    }
    let (rc, out) = ip6t_run_timeout(&["-t", "filter", "-A", chain, "-j", "RETURN"], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 { anyhow::bail!("v6 final RETURN in {chain} failed: {}", out.trim()); }
    // Hook at the very top of OUTPUT so the REJECT wins over later ACCEPT rules.
    let (rc, _) = ip6t_run_timeout(&["-t", "filter", "-C", "OUTPUT", "-j", chain], Capture::None, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        let (rc, out) = ip6t_run_timeout(&["-t", "filter", "-I", "OUTPUT", "1", "-j", chain], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 { anyhow::bail!("hook v6 OUTPUT->{chain} failed: {}", out.trim()); }
    }
    Ok(())
}

fn apply_ipv6_block(scope: &str, uids: &[String]) {
    if uids.is_empty() { return; }
    if !ip6tables_available() {
        warn!("TPROXY: ip6tables unavailable; IPv6 leak protection skipped for scope");
        return;
    }
    let chain = scoped_v6_chain_name(scope);
    // Rebuild from scratch so re-apply is idempotent.
    remove_v6_block_chain(&chain);
    if let Err(e) = build_ipv6_block_chain(&chain, uids) {
        warn!("TPROXY: failed to install IPv6 block for scope, continuing: {e:#}");
        // Never leave a half-installed chain behind.
        remove_v6_block_chain(&chain);
    }
}

fn list_ip6_filter_chains_with_prefix(prefix: &str) -> Vec<String> {
    let Ok((0, out)) = crate::shell::run_timeout("ip6tables-save", &["-t", "filter"], Capture::Stdout, IPT_SLOW_TIMEOUT) else {
        return Vec::new();
    };
    out.lines()
        .filter_map(|line| line.strip_prefix(':'))
        .filter_map(|line| line.split_whitespace().next())
        .filter(|name| name.starts_with(prefix))
        .map(|name| name.to_string())
        .collect()
}

fn cleanup_all_ipv6_blocks() {
    if !ip6tables_available() { return; }
    for chain in list_ip6_filter_chains_with_prefix(V6_BLOCK_PREFIX) {
        remove_v6_block_chain(&chain);
    }
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

    cleanup_policy_rules_best_effort();
    delete_local_route_best_effort(ROUTE_TABLE);
    // Table 1057 may belong to an Android interface (ifindex+1000); remove only
    // the exact legacy local route rather than flushing the whole table.
    delete_local_route_best_effort(OLD_ROUTE_TABLE);

    // Remove every per-scope IPv6 leak-block chain and its OUTPUT hook.
    cleanup_all_ipv6_blocks();

    // All scoped chains and policy rules are gone; drop the slot registry too.
    clear_slot_registry();
    Ok(())
}

fn try_list_mangle_chains_with_prefix(prefix: &str) -> Option<Vec<String>> {
    let Ok((0, out)) = crate::shell::run_timeout("iptables-save", &["-t", "mangle"], Capture::Stdout, IPT_SLOW_TIMEOUT) else {
        return None;
    };
    Some(
        out.lines()
            .filter_map(|line| line.strip_prefix(':'))
            .filter_map(|line| line.split_whitespace().next())
            .filter(|name| name.starts_with(prefix))
            .map(|name| name.to_string())
            .collect(),
    )
}

fn list_mangle_chains_with_prefix(prefix: &str) -> Vec<String> {
    try_list_mangle_chains_with_prefix(prefix).unwrap_or_default()
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
        // Match only packets that do not already carry a ZDT-D scope. This
        // makes the first matching profile win without using ACCEPT in mangle
        // OUTPUT; returning from our chains lets Android/OEM rules later in the
        // hook keep processing the packet.
        let unmarked = format!("0x00000000/0x{SCOPE_MASK:08x}");
        matcher.extend(["-m", "mark", "--mark", unmarked.as_str()].iter().map(|s| s.to_string()));
        matcher.extend(["-p", proto, "-m", proto, "-m", "owner", "--uid-owner", uid].iter().map(|s| s.to_string()));
        matcher.extend(extra_tokens.clone());

        let mark_mask = mark_mask_hex(mark);
        matcher.extend(["-j", "MARK", "--set-xmark", mark_mask.as_str()].iter().map(|s| s.to_string()));
        add_rule_idempotent(chain, matcher)?;
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
