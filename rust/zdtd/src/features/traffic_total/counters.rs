use std::collections::HashMap;
use super::{
    backends::resolve_backend_ports,
    model::{ParsedSaveRule, RouteMeta, TrafficRuleCounter},
    parser::{classify_semantic, is_action_semantic, parse_dest_ports, parse_redirect_port, parse_uid_owner, value_after},
    state::{infer_builtin_meta, packages_for_uid},
};

pub(super) fn build_counter(
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
        "tproxy" => Some("rule counter shows traffic delivered by TPROXY; it is not a full-flow app total".to_string()),
        "mark" => Some("rule counter shows traffic marked for TPROXY policy routing".to_string()),
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
        mark: meta.mark,
        route_table: meta.table,
        backend_ports,
        packets: parsed.packets,
        bytes: parsed.bytes,
        active: parsed.packets > 0 || parsed.bytes > 0,
        action_counter,
        raw_rule: parsed.raw_rule,
        note,
    }
}
#[derive(Default)]
pub(super) struct ChainAccumulator {
    pub(super) rule_count: u64,
    pub(super) action_packets: u64,
    pub(super) action_bytes: u64,
    pub(super) return_packets: u64,
    pub(super) return_bytes: u64,
    pub(super) pass_packets: u64,
    pub(super) pass_bytes: u64,
}

impl ChainAccumulator {
    pub(super) fn add(&mut self, c: &TrafficRuleCounter) {
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
