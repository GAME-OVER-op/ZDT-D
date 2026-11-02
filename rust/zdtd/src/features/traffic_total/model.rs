use serde::Serialize;


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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dnscrypt: Option<DnscryptLayer>,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mark: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub route_table: Option<u32>,
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
pub struct DnscryptLayer {
    pub enabled: bool,
    pub listen_port: u16,
    pub label: String,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub proxy_endpoint: Option<TrafficBackendPort>,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct VpnApp {
    pub uid: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub package: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub packages: Vec<String>,
}
#[derive(Debug, Clone, Default)]
pub(super) struct RouteMeta {
    pub(super) kind: String,
    pub(super) program_id: Option<String>,
    pub(super) profile: Option<String>,
    pub(super) slot: Option<String>,
    pub(super) uid_file: Option<String>,
    pub(super) dest_port: Option<u16>,
    pub(super) queue: Option<u16>,
    pub(super) mark: Option<u32>,
    pub(super) table: Option<u32>,
}
#[derive(Debug, Clone)]
pub(super) struct ParsedSaveRule {
    pub(super) family: String,
    pub(super) table: String,
    pub(super) chain: String,
    pub(super) packets: u64,
    pub(super) bytes: u64,
    pub(super) raw_rule: String,
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
