use anyhow::Result;
use std::{collections::BTreeMap, path::Path, sync::atomic::{AtomicBool, Ordering}, time::{Duration, SystemTime, UNIX_EPOCH}};
use super::{
    backends::{build_local_port_registry, collect_dnscrypt_layer, load_t2s_instances},
    counters::{build_counter, ChainAccumulator},
    interfaces::{read_interfaces, read_vpn_traffic},
    iptables::run_iptables_save,
    model::{TrafficChainSummary, TrafficRuleReport},
    parser::{classify_chain, is_relevant_rule, parse_iptables_save},
    state::{load_route_meta, load_uid_file_packages, load_uid_package_map},
};

static TRAFFIC_COLLECTING: AtomicBool = AtomicBool::new(false);
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
    let working_root = Path::new("/data/adb/modules/ZDT-D/working_folder");
    let local_registry = build_local_port_registry(working_root);
    report.vpn = read_vpn_traffic(&interfaces, &uid_packages, &uid_file_packages, &local_registry, &mut report.warnings);
    report.proxy_endpoints = local_registry.values().cloned().collect();
    report.proxy_endpoints.sort_by(|a, b| a.label.cmp(&b.label).then(a.port.cmp(&b.port)));
    report.t2s_instances = load_t2s_instances(&mut report.warnings);
    report.dnscrypt = collect_dnscrypt_layer(&mut report.warnings);
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
