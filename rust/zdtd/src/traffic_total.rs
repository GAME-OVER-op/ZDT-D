use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashMap},
    fs,
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::{shell::{self, Capture}, xtables_lock};

const IPT_SAVE_TIMEOUT: Duration = Duration::from_secs(8);
const PKG_LIST_TIMEOUT: Duration = Duration::from_secs(5);
const PROC_NET_DEV: &str = "/proc/net/dev";
const ROUTING_CACHE: &str = "/data/adb/modules/ZDT-D/working_folder/runtime_refresh/routing.json";
const VPN_NETD_APPLIED: &str = "/data/adb/modules/ZDT-D/working_folder/vpn_netd/applied.json";
const XT_WAIT_SECS: &str = "5";

static TRAFFIC_COLLECTING: AtomicBool = AtomicBool::new(false);

/// Read-only, on-demand ZDT-D rule traffic collector.
///
/// This module is intentionally passive:
/// - no background thread;
/// - no periodic polling;
/// - no iptables/netd/runtime mutations;
/// - no counter reset (`iptables -Z` is never used).
///
/// The snapshot is collected only when the API endpoint requests it.  The main
/// purpose is to show whether traffic actually matched a concrete ZDT-D rule
/// (DNAT/NFQUEUE/DROP/REJECT/etc.) after the user assigns an app/profile.
#[derive(Debug, Clone, Serialize, Default)]
pub struct TrafficRuleReport {
    pub updated_at_unix: u64,
    pub source: &'static str,
    pub rules: Vec<TrafficRuleCounter>,
    pub chains: Vec<TrafficChainSummary>,
    pub vpn: Vec<VpnTraffic>,
    pub interfaces: Vec<InterfaceTraffic>,
    pub warnings: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub proxy_endpoints: Vec<TrafficBackendPort>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub t2s_instances: Vec<T2sInstanceMeta>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct TrafficRuleCounter {
    pub family: String,
    pub table: String,
    pub chain: String,
    pub semantic: String,
    pub target: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub program_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub profile: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub slot: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uid_file: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub uid: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub package: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub packages: Vec<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub proto: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub dest_ports: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub redirect_port: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub queue: Option<u16>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub backend_ports: Vec<TrafficBackendPort>,

    pub packets: u64,
    pub bytes: u64,
    pub active: bool,
    pub action_counter: bool,

    pub raw_rule: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct TrafficBackendPort {
    pub port: u16,
    pub label: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub host: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub program_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub profile: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub server: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wrapped_host: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wrapped_port: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wrapped_label: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wrapped_program_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wrapped_profile: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wrapped_server: Option<String>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct TrafficChainSummary {
    pub family: String,
    pub table: String,
    pub chain: String,
    pub kind: String,
    pub rule_count: u64,
    pub action_packets: u64,
    pub action_bytes: u64,
    pub return_packets: u64,
    pub return_bytes: u64,
    pub pass_packets: u64,
    pub pass_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct InterfaceTraffic {
    pub iface: String,
    pub rx_bytes: u64,
    pub rx_packets: u64,
    pub tx_bytes: u64,
    pub tx_packets: u64,
    pub total_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct VpnTraffic {
    pub owner_program: String,
    pub profile: String,
    pub netid: u32,
    pub tun: String,
    pub rx_bytes: u64,
    pub rx_packets: u64,
    pub tx_bytes: u64,
    pub tx_packets: u64,
    pub total_bytes: u64,
    #[serde(default)]
    pub uid_ranges: Vec<String>,
    #[serde(default)]
    pub apps: Vec<VpnApp>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct VpnApp {
    pub uid: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub package: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub packages: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "kind")]
enum RoutingSnapshot {
    NfqV1 {
        uid_file: String,
        mode: String,
        queue: u16,
        iface: Option<String>,
        filter: Option<serde_json::Value>,
    },
    NfqV2 {
        uid_file: String,
        port: u16,
        filter: Option<serde_json::Value>,
    },
    Nat {
        uid_file: String,
        dest_port: u16,
        proto_choice: String,
        ifaces_raw: Option<String>,
        port_preference: u8,
        dpi_ports: String,
    },
}

#[derive(Debug, Clone, Default)]
struct RouteMeta {
    kind: String,
    program_id: Option<String>,
    profile: Option<String>,
    slot: Option<String>,
    uid_file: Option<String>,
    dest_port: Option<u16>,
    queue: Option<u16>,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct AppliedSnapshot {
    #[serde(default)]
    profiles: Vec<AppliedProfile>,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct AppliedProfile {
    owner_program: String,
    profile: String,
    netid: u32,
    tun: String,
    #[serde(default)]
    uid_ranges: Vec<String>,
}

#[derive(Debug, Clone)]
struct ParsedSaveRule {
    family: String,
    table: String,
    chain: String,
    packets: u64,
    bytes: u64,
    raw_rule: String,
}


struct TrafficCollectGuard;

impl Drop for TrafficCollectGuard {
    fn drop(&mut self) {
        TRAFFIC_COLLECTING.store(false, Ordering::Release);
    }
}

/// Try to collect a traffic snapshot without allowing overlapping collectors.
///
/// The Android UI polls this endpoint while Construction Studio is open.  If a
/// previous snapshot is still preparing, return `Ok(None)` so the API can tell
/// the app to wait instead of stacking multiple iptables-save/cmd/pm calls.
pub fn try_collect_rule_snapshot() -> Result<Option<TrafficRuleReport>> {
    if TRAFFIC_COLLECTING
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return Ok(None);
    }
    let _guard = TrafficCollectGuard;
    collect_rule_snapshot().map(Some)
}

/// Collect a single on-demand traffic snapshot for ZDT-D rules.
pub fn collect_rule_snapshot() -> Result<TrafficRuleReport> {
    let mut report = TrafficRuleReport {
        updated_at_unix: now_unix(),
        source: "on_demand",
        ..Default::default()
    };

    let route_meta = load_route_meta(&mut report.warnings);
    let uid_packages = load_uid_package_map(&mut report.warnings);
    let uid_file_packages = load_uid_file_packages(&route_meta);
    let interfaces = read_interfaces(&mut report.warnings);
    report.vpn = read_vpn_traffic(&interfaces, &uid_packages, &uid_file_packages, &mut report.warnings);
    report.proxy_endpoints = build_local_port_registry(Path::new("/data/adb/modules/ZDT-D/working_folder")).into_values().collect();
    report.proxy_endpoints.sort_by(|a, b| a.label.cmp(&b.label).then(a.port.cmp(&b.port)));
    report.t2s_instances = load_t2s_instances(&mut report.warnings);
    report.interfaces = interfaces.into_values().collect();
    report.interfaces.sort_by(|a, b| a.iface.cmp(&b.iface));

    let mut parsed_rules = Vec::new();
    for (family, cmd, tables) in [
        ("ipv4", "iptables-save", &["nat", "mangle", "filter"][..]),
        ("ipv6", "ip6tables-save", &["mangle", "filter"][..]),
    ] {
        for table in tables {
            match run_iptables_save(cmd, table) {
                Ok(out) => parsed_rules.extend(parse_iptables_save(family, table, &out)),
                Err(e) => report.warnings.push(format!("{cmd} -t {table} failed: {e:#}")),
            }
        }
    }

    let mut chain_acc: BTreeMap<(String, String, String), ChainAccumulator> = BTreeMap::new();
    for parsed in parsed_rules {
        if !is_relevant_rule(&parsed) {
            continue;
        }
        let counter = build_counter(parsed, &route_meta, &uid_packages, &uid_file_packages);
        let key = (counter.family.clone(), counter.table.clone(), counter.chain.clone());
        chain_acc.entry(key).or_default().add(&counter);
        report.rules.push(counter);
    }

    report.rules.sort_by(|a, b| {
        (&a.family, &a.table, &a.chain, &a.program_id, &a.profile, &a.uid, &a.proto, &a.target)
            .cmp(&(&b.family, &b.table, &b.chain, &b.program_id, &b.profile, &b.uid, &b.proto, &b.target))
    });

    report.chains = chain_acc.into_iter().map(|((family, table, chain), acc)| {
        TrafficChainSummary {
            family,
            table,
            kind: classify_chain(&chain).to_string(),
            chain,
            rule_count: acc.rule_count,
            action_packets: acc.action_packets,
            action_bytes: acc.action_bytes,
            return_packets: acc.return_packets,
            return_bytes: acc.return_bytes,
            pass_packets: acc.pass_packets,
            pass_bytes: acc.pass_bytes,
        }
    }).collect();

    Ok(report)
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_secs()
}

fn run_iptables_save(cmd: &str, table: &str) -> Result<String> {
    // Keep the read-only collector in the same xtables critical section as the
    // rule writers.  `iptables-save -c` does not mutate counters, but it still
    // touches xtables state and may collide with apply/restore scripts.
    let _xtables_guard = xtables_lock::lock();

    let wait_args = ["-w", XT_WAIT_SECS, "-c", "-t", table];
    let (mut rc, mut out) = xtables_lock::run_timeout_retry(cmd, &wait_args, Capture::Both, IPT_SAVE_TIMEOUT)?;

    // Some old Android iptables-save builds do not support -w.  In that case we
    // still keep the in-process lock and fall back to the classic arguments.
    if rc != 0 && looks_like_wait_unsupported(&out) {
        let fallback = xtables_lock::run_timeout_retry(cmd, &["-c", "-t", table], Capture::Both, IPT_SAVE_TIMEOUT)?;
        rc = fallback.0;
        out = fallback.1;
    }

    if rc != 0 {
        anyhow::bail!("exit={rc}: {}", out.trim());
    }
    Ok(out)
}

fn looks_like_wait_unsupported(out: &str) -> bool {
    let s = out.to_ascii_lowercase();
    // Android/busybox iptables-save may print either "invalid option -- w"
    // or a shorter "unknown option" without echoing "-w" back.  In this
    // helper the only extra option we add before the known-good fallback is
    // `-w`, so any option-parser failure means we should retry without it.
    s.contains("unknown option")
        || s.contains("unrecognized option")
        || s.contains("invalid option")
        || s.contains("illegal option")
}

fn parse_iptables_save(family: &str, table: &str, out: &str) -> Vec<ParsedSaveRule> {
    let mut rules = Vec::new();
    for line in out.lines().map(str::trim) {
        if !line.starts_with('[') {
            continue;
        }
        let Some(end) = line.find(']') else { continue; };
        let Some((packets_s, bytes_s)) = line[1..end].split_once(':') else { continue; };
        let Ok(packets) = packets_s.parse::<u64>() else { continue; };
        let Ok(bytes) = bytes_s.parse::<u64>() else { continue; };
        let rest = line[end + 1..].trim();
        let Some(rest) = rest.strip_prefix("-A ") else { continue; };
        let mut parts = rest.splitn(2, char::is_whitespace);
        let Some(chain) = parts.next() else { continue; };
        let raw_rule = parts.next().unwrap_or("").trim().to_string();
        rules.push(ParsedSaveRule {
            family: family.to_string(),
            table: table.to_string(),
            chain: chain.to_string(),
            packets,
            bytes,
            raw_rule,
        });
    }
    rules
}

fn is_relevant_rule(rule: &ParsedSaveRule) -> bool {
    is_relevant_chain(&rule.chain) || rule.raw_rule.split_whitespace().any(is_relevant_target)
}

fn is_relevant_chain(chain: &str) -> bool {
    chain == "MANGLE_APP"
        || chain == "NAT_DPI"
        || chain == "NAT_DPI_LOCAL"
        || chain.starts_with("ZDT")
}

fn is_relevant_target(tok: &str) -> bool {
    tok == "MANGLE_APP" || tok == "NAT_DPI" || tok == "NAT_DPI_LOCAL" || tok.starts_with("ZDT")
}

fn build_counter(
    parsed: ParsedSaveRule,
    route_meta: &HashMap<String, RouteMeta>,
    uid_packages: &HashMap<u32, Vec<String>>,
    uid_file_packages: &HashMap<String, HashMap<u32, Vec<String>>>,
) -> TrafficRuleCounter {
    let tokens: Vec<&str> = parsed.raw_rule.split_whitespace().collect();
    let target = value_after(&tokens, "-j").unwrap_or("").to_string();
    let proto = value_after(&tokens, "-p").map(str::to_string);
    let uid = value_after(&tokens, "--uid-owner").and_then(parse_uid_owner);
    let queue = value_after(&tokens, "--queue-num").and_then(|s| s.parse::<u16>().ok());
    let dest_ports = parse_dest_ports(&tokens);
    let redirect_port = parse_redirect_port(&tokens);
    let semantic = classify_semantic(&parsed.chain, &target, &dest_ports, redirect_port, &tokens).to_string();
    let action_counter = is_action_semantic(&semantic);

    let mut meta = route_meta.get(&parsed.chain).cloned().unwrap_or_default();
    if meta.program_id.is_none() {
        meta = infer_builtin_meta(&parsed.chain, &target);
    }

    let packages = uid
        .map(|u| packages_for_uid(u, &meta, uid_packages, uid_file_packages))
        .unwrap_or_default();
    let package = packages.first().cloned();

    let note = match semantic.as_str() {
        "nat_redirect" | "dns_redirect" => Some("rule counter shows traffic matched the NAT/redirect rule; it is not a full-flow app total".to_string()),
        "chain_jump" => Some("jump/pass-through counter; do not sum as processed traffic".to_string()),
        "chain_return" | "guard_return" => Some("return/pass-through counter; traffic was not processed by an action rule here".to_string()),
        _ => None,
    };
    let backend_ports = resolve_backend_ports(&meta);

    TrafficRuleCounter {
        family: parsed.family,
        table: parsed.table,
        chain: parsed.chain,
        semantic,
        target,
        program_id: meta.program_id,
        profile: meta.profile,
        slot: meta.slot,
        uid_file: meta.uid_file,
        uid,
        package,
        packages,
        proto,
        dest_ports,
        redirect_port,
        queue: queue.or(meta.queue),
        backend_ports,
        packets: parsed.packets,
        bytes: parsed.bytes,
        active: parsed.packets > 0 || parsed.bytes > 0,
        action_counter,
        raw_rule: parsed.raw_rule,
        note,
    }
}


#[derive(Debug, Clone, Serialize, Default)]
pub struct T2sInstanceMeta {
    pub instance_id: String,
    pub program: String,
    pub profile: String,
    pub scope: String,
    pub pid: u32,
    pub web_addr: String,
    pub web_port: u16,
    pub listen_addr: String,
    pub listen_port: u16,
    pub backend_mode: String,
    pub priority_speed_aware: bool,
    pub updated_at: u64,
}

fn resolve_backend_ports(meta: &RouteMeta) -> Vec<TrafficBackendPort> {
    if meta.program_id.as_deref() != Some("myproxy") {
        return Vec::new();
    }
    let Some(profile) = meta.profile.as_deref() else { return Vec::new(); };
    let root = Path::new("/data/adb/modules/ZDT-D/working_folder");
    let proxy_path = root.join("myproxy/profile").join(profile).join("proxy.json");
    let Ok(raw) = fs::read_to_string(&proxy_path) else { return Vec::new(); };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return Vec::new(); };
    let ports = parse_proxy_ports(&v);
    if ports.is_empty() {
        return Vec::new();
    }
    let registry = build_local_port_registry(root);
    let proxy_host = json_str_value(&v, "host").unwrap_or_else(|| "127.0.0.1".to_string());
    let wrapped = parse_wrapped_socks(&v, &registry);
    ports.into_iter().map(|port| {
        let mut item = registry.get(&port).cloned().unwrap_or_else(|| TrafficBackendPort {
            port,
            label: format!("{}:{}", proxy_host, port),
            host: Some(proxy_host.clone()),
            program_id: Some("myproxy".to_string()),
            profile: meta.profile.clone(),
            server: Some(format!("{}:{}", proxy_host, port)),
            wrapped_host: None,
            wrapped_port: None,
            wrapped_label: None,
            wrapped_program_id: None,
            wrapped_profile: None,
            wrapped_server: None,
        });
        if let Some(w) = &wrapped {
            item.wrapped_host = Some(w.host.clone());
            item.wrapped_port = Some(w.port);
            item.wrapped_label = Some(w.label.clone());
            item.wrapped_program_id = w.program_id.clone();
            item.wrapped_profile = w.profile.clone();
            item.wrapped_server = w.server.clone();
        }
        item
    }).collect()
}

#[derive(Debug, Clone)]
struct WrappedBackendView {
    host: String,
    port: u16,
    label: String,
    program_id: Option<String>,
    profile: Option<String>,
    server: Option<String>,
}

fn parse_wrapped_socks(v: &serde_json::Value, registry: &HashMap<u16, TrafficBackendPort>) -> Option<WrappedBackendView> {
    let w = v.get("wrapped_socks")?.as_object()?;
    let host = w.get("host").and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
    let port = w.get("port").and_then(json_u16).unwrap_or(0);
    if host.is_empty() || port == 0 { return None; }
    let local = matches!(host.as_str(), "127.0.0.1" | "localhost" | "::1" | "0.0.0.0");
    if local {
        if let Some(found) = registry.get(&port) {
            return Some(WrappedBackendView {
                host,
                port,
                label: found.label.clone(),
                program_id: found.program_id.clone(),
                profile: found.profile.clone(),
                server: found.server.clone(),
            });
        }
    }
    Some(WrappedBackendView {
        host: host.clone(),
        port,
        label: format!("SOCKS5 {}:{}", host, port),
        program_id: None,
        profile: None,
        server: Some(format!("{}:{}", host, port)),
    })
}

fn parse_proxy_ports(v: &serde_json::Value) -> Vec<u16> {
    let mut out = Vec::new();
    if let Some(arr) = v.get("ports").and_then(|x| x.as_array()) {
        for item in arr {
            push_json_port(item, &mut out);
        }
    }
    if out.is_empty() {
        if let Some(port_value) = v.get("port") {
            push_json_port(port_value, &mut out);
        }
    }
    normalize_port_vec(out)
}

fn push_json_port(v: &serde_json::Value, out: &mut Vec<u16>) {
    match v {
        serde_json::Value::Number(n) => {
            if let Some(raw) = n.as_u64().and_then(|x| u16::try_from(x).ok()).filter(|x| *x > 0) {
                out.push(raw);
            }
        }
        serde_json::Value::String(s) => {
            for part in s.split(',') {
                if let Ok(port) = part.trim().parse::<u16>() {
                    if port > 0 { out.push(port); }
                }
            }
        }
        serde_json::Value::Array(items) => {
            for item in items { push_json_port(item, out); }
        }
        _ => {}
    }
}

fn normalize_port_vec(ports: Vec<u16>) -> Vec<u16> {
    let mut out = Vec::new();
    for port in ports {
        if port > 0 && !out.contains(&port) {
            out.push(port);
        }
    }
    out
}

fn build_local_port_registry(root: &Path) -> HashMap<u16, TrafficBackendPort> {
    let mut out = HashMap::new();
    collect_singbox_ports(root, &mut out);
    collect_wireproxy_ports(root, &mut out);
    collect_operaproxy_ports(root, &mut out);
    collect_tor_socks_port(root, &mut out);
    collect_mihomo_profile_ports(root, &mut out);
    collect_mieru_profile_ports(root, &mut out);
    collect_myprogram_t2s_ports(root, &mut out);
    out
}

fn collect_singbox_ports(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    let profile_root = root.join("singbox/profile");
    let Ok(profiles) = fs::read_dir(&profile_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = file_name_string(&profile_dir) else { continue; };
        let server_root = profile_dir.join("server");
        let Ok(servers) = fs::read_dir(&server_root) else { continue; };
        for server_ent in servers.flatten() {
            let server_dir = server_ent.path();
            if !server_dir.is_dir() { continue; }
            let server = file_name_string(&server_dir);
            collect_simple_json_port(server_dir.join("setting.json"), "singbox", Some(profile.clone()), server, &["port", "listen_port"], out);
        }
    }
}

fn collect_wireproxy_ports(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    let profile_root = root.join("wireproxy/profile");
    let Ok(profiles) = fs::read_dir(&profile_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = file_name_string(&profile_dir) else { continue; };
        let server_root = profile_dir.join("server");
        let Ok(servers) = fs::read_dir(&server_root) else { continue; };
        for server_ent in servers.flatten() {
            let server_dir = server_ent.path();
            if !server_dir.is_dir() { continue; }
            let server = file_name_string(&server_dir);
            let config = server_dir.join("config.conf");
            if let Ok(raw) = fs::read_to_string(&config) {
                if let Some(port) = parse_bind_address_port(&raw) {
                    put_registry_port(out, port, "wireproxy", Some(profile.clone()), server.clone());
                }
            }
        }
    }
}

fn collect_operaproxy_ports(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    let path = root.join("operaproxy/port.json");
    let Ok(raw) = fs::read_to_string(path) else { return; };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return; };
    let Some(start) = v.get("opera_start_port").and_then(json_u16) else { return; };
    let count = operaproxy_server_count(root).max(1).min(12);
    for idx in 0..count {
        if let Some(port) = start.checked_add(idx as u16) {
            put_registry_port(out, port, "operaproxy", None, Some(format!("opera-proxy-{}", idx + 1)));
        }
    }
}

fn operaproxy_server_count(root: &Path) -> usize {
    let path = root.join("operaproxy/config/sni.json");
    let Ok(raw) = fs::read_to_string(path) else { return 1; };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return 1; };
    v.as_array().map(|a| a.len()).or_else(|| v.get("items").and_then(|x| x.as_array()).map(|a| a.len())).unwrap_or(1)
}

fn collect_tor_socks_port(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    let path = root.join("tor/torrc");
    let Ok(raw) = fs::read_to_string(path) else { return; };
    if let Some(port) = parse_tor_socks_port(&raw) {
        put_registry_port(out, port, "tor", None, Some("SocksPort".to_string()));
    }
}

fn collect_mihomo_profile_ports(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    collect_profile_setting_ports(root, "mihomo", &["mixed_port"], out);
}

fn collect_mieru_profile_ports(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    collect_profile_setting_ports(root, "mieru", &["socks5_port"], out);
}

fn collect_profile_setting_ports(root: &Path, program: &str, keys: &[&str], out: &mut HashMap<u16, TrafficBackendPort>) {
    let profile_root = root.join(program).join("profile");
    let Ok(profiles) = fs::read_dir(&profile_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = file_name_string(&profile_dir) else { continue; };
        for key in keys {
            collect_simple_json_port(profile_dir.join("setting.json"), program, Some(profile.clone()), Some((*key).to_string()), &[*key], out);
        }
    }
}

fn collect_myprogram_t2s_ports(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    let profile_root = root.join("myprogram/profile");
    let Ok(profiles) = fs::read_dir(&profile_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = file_name_string(&profile_dir) else { continue; };
        let Ok(raw) = fs::read_to_string(profile_dir.join("t2s_ports.txt")) else { continue; };
        for port in parse_port_list_text(&raw) {
            put_registry_port(out, port, "myprogram", Some(profile.clone()), Some("t2s_ports".to_string()));
        }
    }
}

fn parse_port_list_text(raw: &str) -> Vec<u16> {
    let mut out = Vec::new();
    for part in raw.replace('\n', ",").split(',') {
        if let Ok(port) = part.trim().parse::<u16>() {
            if port > 0 && !out.contains(&port) { out.push(port); }
        }
    }
    out
}

fn parse_tor_socks_port(raw: &str) -> Option<u16> {
    for line in raw.lines() {
        let s = line.trim();
        if !s.to_ascii_lowercase().starts_with("socksport") { continue; }
        let value = s.split_whitespace().nth(1)?;
        let port_s = value.rsplit(':').next().unwrap_or(value);
        if let Ok(port) = port_s.trim().parse::<u16>() {
            if port > 0 { return Some(port); }
        }
    }
    None
}

fn collect_simple_json_port(path: PathBuf, program: &str, profile: Option<String>, server: Option<String>, keys: &[&str], out: &mut HashMap<u16, TrafficBackendPort>) {
    let Ok(raw) = fs::read_to_string(path) else { return; };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return; };
    for key in keys {
        if let Some(port) = v.get(*key).and_then(json_u16) {
            put_registry_port(out, port, program, profile.clone(), server.clone());
        }
    }
}

fn json_u16(v: &serde_json::Value) -> Option<u16> {
    v.as_u64().and_then(|x| u16::try_from(x).ok()).filter(|x| *x > 0)
        .or_else(|| v.as_str().and_then(|s| s.trim().parse::<u16>().ok()).filter(|x| *x > 0))
}

fn parse_bind_address_port(raw: &str) -> Option<u16> {
    for line in raw.lines() {
        let s = line.trim();
        if !s.to_ascii_lowercase().starts_with("bindaddress") { continue; }
        let Some((_, value)) = s.split_once('=') else { continue; };
        if let Some(port_s) = value.trim().rsplit(':').next() {
            if let Ok(port) = port_s.trim().parse::<u16>() {
                if port > 0 { return Some(port); }
            }
        }
    }
    None
}

fn put_registry_port(out: &mut HashMap<u16, TrafficBackendPort>, port: u16, program: &str, profile: Option<String>, server: Option<String>) {
    let label = match (&profile, &server) {
        (Some(p), Some(s)) => format!("{program}/{p}/{s}:{port}"),
        (Some(p), None) => format!("{program}/{p}:{port}"),
        (None, Some(s)) => format!("{program}/{s}:{port}"),
        (None, None) => format!("{program}:{port}"),
    };
    out.entry(port).or_insert_with(|| TrafficBackendPort {
        port,
        label,
        host: Some("127.0.0.1".to_string()),
        program_id: Some(program.to_string()),
        profile,
        server,
        wrapped_host: None,
        wrapped_port: None,
        wrapped_label: None,
        wrapped_program_id: None,
        wrapped_profile: None,
        wrapped_server: None,
    });
}

fn json_str_value(v: &serde_json::Value, key: &str) -> Option<String> {
    v.get(key).and_then(|x| x.as_str()).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string)
}

fn file_name_string(path: &Path) -> Option<String> {
    path.file_name().and_then(|s| s.to_str()).map(str::to_string).filter(|s| !s.starts_with('.'))
}

fn load_t2s_instances(warnings: &mut Vec<String>) -> Vec<T2sInstanceMeta> {
    let dir = Path::new("/data/adb/modules/ZDT-D/api/t2s/instances");
    let Ok(entries) = fs::read_dir(dir) else { return Vec::new(); };
    let mut out = Vec::new();
    for ent in entries.flatten() {
        let path = ent.path();
        if path.extension().and_then(|s| s.to_str()) != Some("json") { continue; }
        let Ok(raw) = fs::read_to_string(&path) else { continue; };
        let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else {
            warnings.push(format!("parse t2s metadata failed: {}", path.display()));
            continue;
        };
        let web_port = v.get("web_port").and_then(json_u16).unwrap_or(0);
        let listen_port = v.get("listen_port").and_then(json_u16).unwrap_or(0);
        if web_port == 0 || listen_port == 0 { continue; }
        out.push(T2sInstanceMeta {
            instance_id: json_str(&v, "instance_id"),
            program: json_str(&v, "program"),
            profile: json_str(&v, "profile"),
            scope: json_str(&v, "scope"),
            pid: v.get("pid").and_then(|x| x.as_u64()).and_then(|x| u32::try_from(x).ok()).unwrap_or(0),
            web_addr: json_str(&v, "web_addr"),
            web_port,
            listen_addr: json_str(&v, "listen_addr"),
            listen_port,
            backend_mode: json_str(&v, "backend_mode"),
            priority_speed_aware: v.get("priority_speed_aware").and_then(|x| x.as_bool()).unwrap_or(false),
            updated_at: v.get("updated_at").and_then(|x| x.as_u64()).unwrap_or(0),
        });
    }
    out.sort_by(|a, b| a.scope.cmp(&b.scope).then(a.listen_port.cmp(&b.listen_port)));
    out
}

fn json_str(v: &serde_json::Value, key: &str) -> String {
    v.get(key).and_then(|x| x.as_str()).unwrap_or("").to_string()
}

fn value_after<'a>(tokens: &[&'a str], key: &str) -> Option<&'a str> {
    tokens.windows(2).find_map(|w| if w[0] == key { Some(w[1]) } else { None })
}

fn parse_uid_owner(s: &str) -> Option<u32> {
    // Most ZDT-D rules use a single UID. If a future rule uses a range, expose
    // the first UID as a best-effort key while keeping raw_rule for exact data.
    let first = s.split('-').next().unwrap_or(s).trim();
    first.parse::<u32>().ok().filter(|v| *v > 0)
}

fn parse_dest_ports(tokens: &[&str]) -> Vec<String> {
    let mut out = Vec::new();
    for key in ["--dports", "--dport", "--sports", "--sport"] {
        if let Some(v) = value_after(tokens, key) {
            for part in v.split(',') {
                let p = part.trim();
                if !p.is_empty() && !out.iter().any(|x| x == p) {
                    out.push(p.to_string());
                }
            }
        }
    }
    out
}

fn parse_redirect_port(tokens: &[&str]) -> Option<u16> {
    for key in ["--to-destination", "--to-ports", "--to"] {
        if let Some(v) = value_after(tokens, key) {
            if let Some(port) = v.rsplit(':').next().and_then(|p| p.parse::<u16>().ok()) {
                return Some(port);
            }
            if let Ok(port) = v.parse::<u16>() {
                return Some(port);
            }
        }
    }
    None
}

fn classify_semantic<'a>(chain: &str, target: &'a str, dest_ports: &[String], redirect_port: Option<u16>, tokens: &[&str]) -> &'a str {
    match target {
        "NFQUEUE" => "nfqueue",
        "DNAT" | "REDIRECT" => {
            let dns_ports = dest_ports.iter().any(|p| p == "53" || p.contains("53")) || redirect_port == Some(53) || redirect_port == Some(853) || redirect_port == Some(5353);
            if chain == "NAT_DPI" && dns_ports { "dns_redirect" } else { "nat_redirect" }
        }
        "DROP" => "drop",
        "REJECT" => "reject",
        "MASQUERADE" => "masquerade",
        "ACCEPT" => "accept",
        "RETURN" => {
            if tokens.iter().any(|t| *t == "-o" || *t == "-d") { "guard_return" } else { "chain_return" }
        }
        _ if is_relevant_target(target) => "chain_jump",
        _ => "unknown",
    }
}

fn is_action_semantic(s: &str) -> bool {
    matches!(s, "nfqueue" | "nat_redirect" | "dns_redirect" | "drop" | "reject" | "masquerade" | "accept")
}

fn classify_chain(chain: &str) -> &'static str {
    if chain.starts_with("ZDTN_") { "scoped_nat" }
    else if chain.starts_with("ZDTM_") { "scoped_mangle" }
    else if chain == "NAT_DPI" || chain == "NAT_DPI_LOCAL" { "base_nat" }
    else if chain == "MANGLE_APP" { "base_mangle" }
    else if chain.starts_with("ZDT_VPN_TETHER") { "vpn_tether" }
    else if chain == "ZDT_BLOCKEDQUIC" { "blocked_quic" }
    else if chain == "ZDT_PROXYINFO" { "proxyinfo" }
    else { "zdt" }
}

#[derive(Default)]
struct ChainAccumulator {
    rule_count: u64,
    action_packets: u64,
    action_bytes: u64,
    return_packets: u64,
    return_bytes: u64,
    pass_packets: u64,
    pass_bytes: u64,
}

impl ChainAccumulator {
    fn add(&mut self, c: &TrafficRuleCounter) {
        self.rule_count += 1;
        if c.action_counter {
            self.action_packets = self.action_packets.saturating_add(c.packets);
            self.action_bytes = self.action_bytes.saturating_add(c.bytes);
        } else if c.semantic == "chain_return" || c.semantic == "guard_return" {
            self.return_packets = self.return_packets.saturating_add(c.packets);
            self.return_bytes = self.return_bytes.saturating_add(c.bytes);
        } else {
            self.pass_packets = self.pass_packets.saturating_add(c.packets);
            self.pass_bytes = self.pass_bytes.saturating_add(c.bytes);
        }
    }
}

fn load_route_meta(warnings: &mut Vec<String>) -> HashMap<String, RouteMeta> {
    let mut out = HashMap::new();
    let raw = match fs::read_to_string(ROUTING_CACHE) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return out,
        Err(e) => {
            warnings.push(format!("read routing cache failed: {e}"));
            return out;
        }
    };
    let items = match serde_json::from_str::<Vec<RoutingSnapshot>>(&raw) {
        Ok(v) => v,
        Err(e) => {
            warnings.push(format!("parse routing cache failed: {e}"));
            return out;
        }
    };

    for item in items {
        match item {
            RoutingSnapshot::NfqV1 { uid_file, mode, queue, iface, filter: _ } => {
                let mut meta = meta_from_uid_file(&uid_file);
                meta.kind = "nfqueue_v1".to_string();
                meta.uid_file = Some(uid_file.clone());
                meta.queue = Some(queue);
                let scope = format!(
                    "nfqueue:v1:mode={}:queue={}:iface={}:uid={}",
                    mode,
                    queue,
                    iface.as_deref().unwrap_or(""),
                    uid_file,
                );
                out.insert(scoped_mangle_chain_name(&scope), meta.clone());
                out.insert(scoped_mangle_chain_name(&format!("{scope}:v6")), meta);
            }
            RoutingSnapshot::NfqV2 { uid_file, port, filter: _ } => {
                let mut meta = meta_from_uid_file(&uid_file);
                meta.kind = "nfqueue_v2".to_string();
                meta.uid_file = Some(uid_file.clone());
                meta.queue = Some(port);
                let scope = format!("nfqueue:v2:queue={}:uid={}", port, uid_file);
                out.insert(scoped_mangle_chain_name(&scope), meta.clone());
                out.insert(scoped_mangle_chain_name(&format!("{scope}:v6")), meta);
            }
            RoutingSnapshot::Nat { uid_file, dest_port, proto_choice, ifaces_raw, port_preference, dpi_ports } => {
                let mut meta = meta_from_uid_file(&uid_file);
                meta.kind = "nat".to_string();
                meta.uid_file = Some(uid_file.clone());
                meta.dest_port = Some(dest_port);
                let scope = format!(
                    "nat:uid={}:dest={}:proto={}:ifaces={}:pref={}:ports={}",
                    uid_file,
                    dest_port,
                    proto_choice_debug(&proto_choice),
                    ifaces_raw.unwrap_or_default(),
                    port_preference,
                    dpi_ports,
                );
                out.insert(scoped_nat_chain_name(&scope), meta.clone());
                out.insert(scoped_nat_chain_name(&format!("local:{scope}")), meta);
            }
        }
    }
    out
}

fn proto_choice_debug(s: &str) -> &'static str {
    match s {
        "udp" => "Udp",
        "tcp_udp" => "TcpUdp",
        _ => "Tcp",
    }
}

fn scoped_mangle_chain_name(label: &str) -> String { scoped_hash_name("ZDTM", label) }
fn scoped_nat_chain_name(label: &str) -> String { scoped_hash_name("ZDTN", label) }

fn scoped_hash_name(prefix: &str, label: &str) -> String {
    let mut hash: u64 = 0xcbf29ce484222325;
    for b in label.as_bytes() {
        hash ^= *b as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    format!("{prefix}_{hash:016x}")
}

fn meta_from_uid_file(uid_file: &str) -> RouteMeta {
    let mut meta = RouteMeta::default();
    let path = Path::new(uid_file);
    meta.slot = path.file_name().and_then(|s| s.to_str()).map(str::to_string);

    let parts: Vec<String> = path.components()
        .filter_map(|c| c.as_os_str().to_str().map(str::to_string))
        .collect();
    let Some(idx) = parts.iter().position(|p| p == "working_folder") else { return meta; };
    let Some(program) = parts.get(idx + 1) else { return meta; };
    meta.program_id = Some(program.clone());

    if parts.get(idx + 2).map(String::as_str) == Some("profile") {
        meta.profile = parts.get(idx + 3).cloned();
    } else if parts.get(idx + 2).map(String::as_str) != Some("app") {
        meta.profile = parts.get(idx + 2).cloned();
    }
    meta
}

fn infer_builtin_meta(chain: &str, target: &str) -> RouteMeta {
    let mut meta = RouteMeta::default();
    if chain == "ZDT_BLOCKEDQUIC" {
        meta.kind = "blocked_quic".to_string();
        meta.program_id = Some("blockedquic".to_string());
        meta.slot = Some("common".to_string());
    } else if chain == "ZDT_PROXYINFO" {
        meta.kind = "proxyinfo".to_string();
        meta.program_id = Some("proxyinfo".to_string());
        meta.slot = Some("common".to_string());
    } else if chain.starts_with("ZDT_VPN_TETHER") || target.contains("ZDT_VPN_TETHER") {
        meta.kind = "vpn_tether".to_string();
        meta.program_id = Some("vpn_tether".to_string());
    }
    meta
}

fn load_uid_file_packages(route_meta: &HashMap<String, RouteMeta>) -> HashMap<String, HashMap<u32, Vec<String>>> {
    let mut out = HashMap::new();
    for uid_file in route_meta.values().filter_map(|m| m.uid_file.as_deref()) {
        if out.contains_key(uid_file) {
            continue;
        }
        out.insert(uid_file.to_string(), read_uid_file_packages(uid_file));
    }
    out
}

fn read_uid_file_packages(uid_file: &str) -> HashMap<u32, Vec<String>> {
    let mut out: HashMap<u32, Vec<String>> = HashMap::new();
    let Ok(raw) = fs::read_to_string(uid_file) else { return out; };
    for line in raw.lines() {
        let s = line.trim();
        if s.is_empty() || s.starts_with('#') {
            continue;
        }
        let Some((pkg, uid_s)) = s.rsplit_once('=') else { continue; };
        let Ok(uid) = uid_s.trim().parse::<u32>() else { continue; };
        if uid == 0 {
            continue;
        }
        let pkg = pkg.trim();
        if pkg.is_empty() {
            continue;
        }
        let list = out.entry(uid).or_default();
        if !list.iter().any(|p| p == pkg) {
            list.push(pkg.to_string());
        }
    }
    out
}

fn load_uid_package_map(warnings: &mut Vec<String>) -> HashMap<u32, Vec<String>> {
    let mut out: HashMap<u32, Vec<String>> = HashMap::new();
    let cmd_out = shell::run_timeout(
        "cmd",
        &["package", "list", "packages", "-U"],
        Capture::Stdout,
        PKG_LIST_TIMEOUT,
    ).ok()
        .and_then(|(rc, s)| if rc == 0 && !s.trim().is_empty() { Some(s) } else { None })
        .or_else(|| {
            shell::run_timeout(
                "pm",
                &["list", "packages", "-U"],
                Capture::Stdout,
                PKG_LIST_TIMEOUT,
            ).ok().and_then(|(rc, s)| if rc == 0 && !s.trim().is_empty() { Some(s) } else { None })
        });

    let Some(raw) = cmd_out else {
        warnings.push("package UID map unavailable from cmd/pm".to_string());
        return out;
    };

    for line in raw.lines() {
        let mut pkg: Option<String> = None;
        let mut uid: Option<u32> = None;
        for tok in line.split_whitespace() {
            if let Some(rest) = tok.strip_prefix("package:") {
                if !rest.is_empty() {
                    pkg = Some(rest.to_string());
                }
            } else if let Some(rest) = tok.strip_prefix("uid:") {
                uid = rest.parse::<u32>().ok();
            }
        }
        if let (Some(pkg), Some(uid)) = (pkg, uid) {
            if uid > 0 {
                let list = out.entry(uid).or_default();
                if !list.iter().any(|p| p == &pkg) {
                    list.push(pkg);
                }
            }
        }
    }
    out
}

fn packages_for_uid(
    uid: u32,
    meta: &RouteMeta,
    uid_packages: &HashMap<u32, Vec<String>>,
    uid_file_packages: &HashMap<String, HashMap<u32, Vec<String>>>,
) -> Vec<String> {
    if let Some(uid_file) = meta.uid_file.as_deref() {
        if let Some(by_uid) = uid_file_packages.get(uid_file) {
            if let Some(pkgs) = by_uid.get(&uid) {
                if !pkgs.is_empty() {
                    return pkgs.clone();
                }
            }
        }
    }
    uid_packages.get(&uid).cloned().unwrap_or_default()
}

fn read_interfaces(warnings: &mut Vec<String>) -> HashMap<String, InterfaceTraffic> {
    let raw = match fs::read_to_string(PROC_NET_DEV) {
        Ok(s) => s,
        Err(e) => {
            warnings.push(format!("read {PROC_NET_DEV} failed: {e}"));
            return HashMap::new();
        }
    };
    let mut out = HashMap::new();
    for line in raw.lines() {
        let Some((iface, rest)) = line.split_once(':') else { continue; };
        let vals: Vec<&str> = rest.split_whitespace().collect();
        if vals.len() < 16 {
            continue;
        }
        let rx_bytes = vals[0].parse::<u64>().unwrap_or(0);
        let rx_packets = vals[1].parse::<u64>().unwrap_or(0);
        let tx_bytes = vals[8].parse::<u64>().unwrap_or(0);
        let tx_packets = vals[9].parse::<u64>().unwrap_or(0);
        let iface = iface.trim().to_string();
        out.insert(iface.clone(), InterfaceTraffic {
            iface,
            rx_bytes,
            rx_packets,
            tx_bytes,
            tx_packets,
            total_bytes: rx_bytes.saturating_add(tx_bytes),
        });
    }
    out
}

fn read_vpn_traffic(
    interfaces: &HashMap<String, InterfaceTraffic>,
    uid_packages: &HashMap<u32, Vec<String>>,
    uid_file_packages: &HashMap<String, HashMap<u32, Vec<String>>>,
    warnings: &mut Vec<String>,
) -> Vec<VpnTraffic> {
    let raw = match fs::read_to_string(VPN_NETD_APPLIED) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Vec::new(),
        Err(e) => {
            warnings.push(format!("read vpn_netd applied snapshot failed: {e}"));
            return Vec::new();
        }
    };
    let snapshot = match serde_json::from_str::<AppliedSnapshot>(&raw) {
        Ok(v) => v,
        Err(e) => {
            warnings.push(format!("parse vpn_netd applied snapshot failed: {e}"));
            return Vec::new();
        }
    };

    snapshot.profiles.into_iter().map(|p| {
        let iface = interfaces.get(&p.tun).cloned().unwrap_or_else(|| InterfaceTraffic { iface: p.tun.clone(), ..Default::default() });
        let apps = expand_uid_ranges(&p.uid_ranges)
            .into_iter()
            .map(|uid| {
                let pkgs = uid_packages.get(&uid)
                    .cloned()
                    .or_else(|| uid_file_packages.values().find_map(|m| m.get(&uid).cloned()))
                    .unwrap_or_default();
                VpnApp { uid, package: pkgs.first().cloned(), packages: pkgs }
            })
            .collect();
        VpnTraffic {
            owner_program: p.owner_program,
            profile: p.profile,
            netid: p.netid,
            tun: p.tun,
            rx_bytes: iface.rx_bytes,
            rx_packets: iface.rx_packets,
            tx_bytes: iface.tx_bytes,
            tx_packets: iface.tx_packets,
            total_bytes: iface.total_bytes,
            uid_ranges: p.uid_ranges,
            apps,
        }
    }).collect()
}

fn expand_uid_ranges(ranges: &[String]) -> Vec<u32> {
    let mut out = Vec::new();
    for r in ranges {
        if let Some((a, b)) = r.split_once('-') {
            let Ok(start) = a.trim().parse::<u32>() else { continue; };
            let Ok(end) = b.trim().parse::<u32>() else { continue; };
            if start == 0 || end < start || end.saturating_sub(start) > 1024 {
                continue;
            }
            for uid in start..=end {
                out.push(uid);
            }
        } else if let Ok(uid) = r.trim().parse::<u32>() {
            if uid > 0 {
                out.push(uid);
            }
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}

