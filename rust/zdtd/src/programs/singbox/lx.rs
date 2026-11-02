#![allow(dead_code)]
use anyhow::{bail, Context, Result};

pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::info;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    io::Write,
    net::{IpAddr, Ipv4Addr},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use crate::network::vpn::netd::VpnNetdProfile;


const MASQUE_ACCOUNT_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/singbox-lx/warp/masque_account.json";
// Стабильный netid: индекс профиля в полном списке профилей движка (включая
// выключенные), чтобы включение/выключение одного профиля не сдвигало netid
// и подсеть туннеля у соседей (см. common/network/netid.rs::stable_netid).
fn all_netd_profile_names() -> Vec<String> {
    crate::programs::singbox::family::all_profile_names(VARIANT)
}

fn stable_netid_for(profile: &str) -> Result<u32> {
    crate::programs::singbox::family::stable_netid_for(VARIANT, profile)
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MasqueAccountRecord {
    pub priv_key_der: String,
    pub server_pub_der: String,
    #[serde(default)]
    pub client_v4: String,
    #[serde(default)]
    pub client_v6: String,
    pub server: String,
    pub port: u16,
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub token: String,
    #[serde(default)]
    pub created_at: String,
}

impl MasqueAccountRecord {
    fn validate(&self) -> Result<()> {
        use base64::{engine::general_purpose::STANDARD, Engine as _};

        if self.priv_key_der.len() > 4096 || self.server_pub_der.len() > 4096 {
            bail!("MASQUE key is too large");
        }
        let priv_der = STANDARD.decode(self.priv_key_der.trim())
            .context("invalid MASQUE private key base64")?;
        let server_der = STANDARD.decode(self.server_pub_der.trim())
            .context("invalid MASQUE server public key base64")?;
        if priv_der.len() < 64 || priv_der.len() > 512 {
            bail!("invalid MASQUE private key DER length");
        }
        if server_der.len() < 64 || server_der.len() > 512 {
            bail!("invalid MASQUE server public key DER length");
        }
        if self.client_v4.trim().is_empty() && self.client_v6.trim().is_empty() {
            bail!("MASQUE account is missing interface addresses");
        }
        let server = self.server.trim();
        if server.is_empty() || server.len() > 255 || server.chars().any(char::is_whitespace) {
            bail!("invalid MASQUE endpoint server");
        }
        if self.port == 0 {
            bail!("invalid MASQUE endpoint port");
        }
        if self.device_id.len() > 512 || self.token.len() > 4096 || self.created_at.len() > 128 {
            bail!("MASQUE account metadata is too large");
        }
        Ok(())
    }
}

pub fn read_masque_account() -> Result<Option<MasqueAccountRecord>> {
    let path = Path::new(MASQUE_ACCOUNT_JSON);
    if !path.is_file() {
        return Ok(None);
    }
    let account: MasqueAccountRecord = read_json(path)
        .with_context(|| format!("read MASQUE account {}", path.display()))?;
    account.validate()?;
    Ok(Some(account))
}

pub fn write_masque_account(account: &MasqueAccountRecord) -> Result<()> {
    account.validate()?;
    let path = Path::new(MASQUE_ACCOUNT_JSON);
    let parent = path.parent().context("MASQUE account path has no parent")?;
    fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    fs::set_permissions(parent, fs::Permissions::from_mode(0o700))
        .with_context(|| format!("chmod {}", parent.display()))?;

    let tmp = unique_tmp_path(path);
    let text = serde_json::to_string_pretty(account)?;
    let write_result = (|| -> Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&tmp)
            .with_context(|| format!("create {}", tmp.display()))?;
        file.write_all(text.as_bytes())
            .with_context(|| format!("write {}", tmp.display()))?;
        file.sync_all().with_context(|| format!("sync {}", tmp.display()))?;
        fs::rename(&tmp, path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))
            .with_context(|| format!("chmod {}", path.display()))?;
        Ok(())
    })();
    if write_result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    write_result
}

pub fn clear_masque_account() -> Result<()> {
    let path = Path::new(MASQUE_ACCOUNT_JSON);
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e).with_context(|| format!("remove {}", path.display())),
    }
}

pub use crate::programs::singbox::family::{ProfileSetting, ServerSetting, SingboxMode};
use crate::programs::singbox::family::VariantSpec;
use crate::programs::singbox::family::{ConfigHooks, ServerPlan, T2sProfilePlan, VpnProfilePlan};

const VARIANT: VariantSpec = VariantSpec::lx();

fn config_hooks() -> ConfigHooks {
    ConfigHooks::new(
        normalize_singbox_config_for_t2s,
        prepare_runtime_config,
        singbox_check_config_with_log,
    )
}

pub fn normalize_setting_value(value: Value) -> Result<ProfileSetting> {
    crate::programs::singbox::family::normalize_setting_value(VARIANT, value)
}

fn normalize_setting_defaults(setting: &mut ProfileSetting) -> Result<()> {
    crate::programs::singbox::family::normalize_setting_defaults(VARIANT, setting)
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    crate::programs::singbox::family::validate_setting(setting)
}

pub fn read_setting(profile: &str) -> Result<ProfileSetting> {
    crate::programs::singbox::family::read_setting(VARIANT, profile)
}

pub fn write_setting(profile: &str, setting: &ProfileSetting) -> Result<()> {
    crate::programs::singbox::family::write_setting(VARIANT, profile, setting)
}

pub fn start_if_enabled() -> Result<()> {
    start_t2s_if_enabled()
}

pub fn start_construction_profile(profile: &str) -> Result<()> {
    crate::programs::singbox::family::start_construction_profile(VARIANT, config_hooks(), profile)
}

pub fn start_t2s_if_enabled() -> Result<()> {
    crate::programs::singbox::family::start_t2s_if_enabled(VARIANT, config_hooks())
}

pub(crate) fn start_t2s_if_enabled_with_settings(api_settings: &crate::settings::ApiSettings) -> Result<()> {
    crate::programs::singbox::family::start_t2s_if_enabled_with_settings(VARIANT, config_hooks(), api_settings)
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    crate::programs::singbox::family::start_profiles_for_netd(VARIANT, config_hooks())
}

fn collect_enabled_t2s_local_ports(enabled_names: &[String]) -> BTreeSet<u16> {
    crate::programs::singbox::family::collect_enabled_t2s_local_ports(VARIANT, enabled_names)
}

fn build_t2s_profile_plan(
    profile: &str,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
    hotspot_profile: Option<&str>,
) -> Result<Option<T2sProfilePlan>> {
    crate::programs::singbox::family::build_t2s_profile_plan(
        VARIANT,
        config_hooks(),
        profile,
        external_used,
        own_used,
        hotspot_profile,
    )
}

fn build_vpn_profile_plan(
    profile: &str,
    used_netids: &BTreeSet<u32>,
    external_used: &BTreeSet<u16>,
    own_used_ports: &BTreeSet<u16>,
) -> Result<Option<VpnProfilePlan>> {
    crate::programs::singbox::family::build_vpn_profile_plan(
        VARIANT,
        config_hooks(),
        profile,
        used_netids,
        external_used,
        own_used_ports,
    )
}

fn app_list_has_real_apps(path: &Path) -> Result<bool> {
    crate::programs::singbox::family::app_list_has_real_apps(path)
}

fn validate_t2s_profile_ports(
    profile: &str,
    setting: &ProfileSetting,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
) -> Result<()> {
    crate::programs::singbox::family::validate_t2s_profile_ports(
        VARIANT,
        profile,
        setting,
        external_used,
        own_used,
    )
}

fn collect_t2s_profile_servers(
    profile: &str,
    profile_setting: &ProfileSetting,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
    reserved: &BTreeSet<u16>,
) -> Result<Vec<ServerPlan>> {
    crate::programs::singbox::family::collect_t2s_profile_servers(
        VARIANT,
        config_hooks(),
        profile,
        profile_setting,
        external_used,
        own_used,
        reserved,
    )
}

pub fn validate_start_plan() -> Result<()> {
    crate::programs::singbox::family::validate_start_plan(VARIANT)
}

pub(crate) fn validate_start_plan_with_settings(api_settings: &crate::settings::ApiSettings) -> Result<()> {
    crate::programs::singbox::family::validate_start_plan_with_settings(VARIANT, api_settings)
}

pub fn is_installed() -> bool {
    crate::programs::singbox::family::is_installed(VARIANT)
}

pub fn has_enabled_profiles() -> bool {
    crate::programs::singbox::family::has_enabled_profiles(VARIANT)
}

pub fn has_enabled_vpn_profiles() -> bool {
    crate::programs::singbox::family::has_enabled_vpn_profiles(VARIANT)
}

pub fn enabled_tun_claims() -> Vec<(String, String)> {
    crate::programs::singbox::family::enabled_tun_claims(VARIANT)
}

pub fn enabled_cidr_claims() -> Vec<(String, String)> {
    crate::programs::singbox::family::enabled_cidr_claims(VARIANT)
}


pub fn is_running() -> bool {
    !main_pids_exact().is_empty()
}

pub fn normalize_config_for_profile_server(profile: &str, server: &str) -> Result<()> {
    crate::programs::singbox::family::normalize_config_for_profile_server(
        VARIANT,
        config_hooks(),
        profile,
        server,
    )
}

fn validate_vpn_plan_conflicts(plans: &[VpnProfilePlan]) -> Result<()> {
    crate::programs::singbox::family::validate_vpn_plan_conflicts(VARIANT, plans)
}

fn list_enabled_server_settings(profile: &str) -> Result<Vec<(String, ServerSetting)>> {
    crate::programs::singbox::family::list_enabled_server_settings(VARIANT, profile)
}

fn list_server_dirs(profile: &str) -> Result<Vec<String>> {
    crate::programs::singbox::family::list_server_dirs(VARIANT, profile)
}

fn read_sorted_dirs(root: &Path) -> Result<Vec<(String, PathBuf)>> {
    crate::programs::singbox::family::read_sorted_dirs(VARIANT, root)
}

fn normalize_singbox_config_for_t2s(config_path: &Path, port: u16, dns_servers: &[String]) -> Result<()> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;
    {
        let obj = value.as_object_mut().ok_or_else(|| anyhow::anyhow!("sing-box-lx config root must be JSON object"))?;
        obj.insert(
            "inbounds".to_string(),
            Value::Array(vec![json!({
                "type": "mixed",
                "tag": "mixed-in",
                "listen": "127.0.0.1",
                "listen_port": port
            })]),
        );
        normalize_singbox_common(obj, dns_servers);
    }
    let legacy_resolver = preferred_singbox_domain_resolver_tag(&value);
    migrate_legacy_domain_strategy(&mut value, legacy_resolver.as_deref());
    write_json_value_if_changed(config_path, &original, &value)
}

fn normalize_singbox_config_for_vpn(config_path: &Path, setting: &ProfileSetting, tun_address: &str) -> Result<String> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;
    let effective_tun = effective_vpn_tun_from_config_value(&value, &setting.tun)?;
    {
        let obj = value.as_object_mut().ok_or_else(|| anyhow::anyhow!("sing-box-lx config root must be JSON object"))?;

        let mut inbound = Map::new();
        inbound.insert("type".to_string(), Value::String("tun".to_string()));
        inbound.insert("tag".to_string(), Value::String("tun-in".to_string()));
        inbound.insert("interface_name".to_string(), Value::String(effective_tun.clone()));
        inbound.insert(
            "address".to_string(),
            Value::Array(vec![Value::String(tun_address.to_string())]),
        );
        inbound.insert("auto_route".to_string(), Value::Bool(false));
        inbound.insert("auto_redirect".to_string(), Value::Bool(false));
        inbound.insert("strict_route".to_string(), Value::Bool(false));
        inbound.insert("stack".to_string(), Value::String("mixed".to_string()));

        obj.insert("inbounds".to_string(), Value::Array(vec![Value::Object(inbound)]));
        normalize_singbox_common(obj, &setting.dns);
    }
    let legacy_resolver = preferred_singbox_domain_resolver_tag(&value);
    migrate_legacy_domain_strategy(&mut value, legacy_resolver.as_deref());

    write_json_value_if_changed(config_path, &original, &value)?;
    Ok(effective_tun)
}

fn prepare_runtime_config(config_path: &Path, endpoint_resolve: bool) -> Result<(PathBuf, Vec<String>)> {
    crate::programs::singbox::family::prepare_runtime_config_with(
        VARIANT,
        config_path,
        endpoint_resolve,
        collect_singbox_outbound_ipv4s,
        resolve_singbox_outbound_servers,
    )
}

fn resolve_singbox_outbound_servers(value: &mut Value) -> Result<Vec<String>> {
    let mut cache = BTreeMap::<String, Vec<String>>::new();
    let mut endpoint_ips = BTreeSet::<String>::new();

    if let Some(outbounds) = value
        .as_object_mut()
        .and_then(|obj| obj.get_mut("outbounds"))
        .and_then(Value::as_array_mut)
    {
        for outbound in outbounds {
            let Some(obj) = outbound.as_object_mut() else { continue };
            resolve_singbox_host_field(obj, "server", "outbound server", &mut cache, &mut endpoint_ips)?;
        }
    }

    // Since sing-box 1.12 WireGuard/AmneziaWG uses an endpoint entry.  Resolve
    // peer hostnames in the runtime copy too, otherwise TUN escape routing only
    // knows about classic outbounds and an AWG endpoint can route into itself.
    if let Some(endpoints) = value
        .as_object_mut()
        .and_then(|obj| obj.get_mut("endpoints"))
        .and_then(Value::as_array_mut)
    {
        for endpoint in endpoints {
            let Some(endpoint_obj) = endpoint.as_object_mut() else { continue };
            let Some(peers) = endpoint_obj.get_mut("peers").and_then(Value::as_array_mut) else { continue };
            for peer in peers {
                let Some(peer_obj) = peer.as_object_mut() else { continue };
                resolve_singbox_host_field(peer_obj, "address", "endpoint peer", &mut cache, &mut endpoint_ips)?;
            }
        }
    }

    Ok(endpoint_ips.into_iter().collect())
}

fn resolve_singbox_host_field(
    obj: &mut Map<String, Value>,
    field: &str,
    label: &str,
    cache: &mut BTreeMap<String, Vec<String>>,
    endpoint_ips: &mut BTreeSet<String>,
) -> Result<()> {
    let Some(server) = obj
        .get(field)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(ToOwned::to_owned)
    else {
        return Ok(());
    };

    if server.parse::<Ipv4Addr>().is_ok() {
        endpoint_ips.insert(server);
        return Ok(());
    }
    if server.parse::<IpAddr>().is_ok() || server.contains(':') {
        return Ok(());
    }

    let lookup_host = server.trim_end_matches('.').to_string();
    let ips = if let Some(cached) = cache.get(&lookup_host) {
        cached.clone()
    } else {
        let resolved = crate::platform::android::dns::resolve_ipv4_all(&lookup_host);
        if resolved.is_empty() {
            bail!("cannot resolve sing-box-lx {} host '{}' to IPv4", label, server);
        }
        cache.insert(lookup_host.clone(), resolved.clone());
        resolved
    };

    let selected = ips
        .first()
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("empty IPv4 result for sing-box-lx {} host '{}'", label, server))?;
    obj.insert(field.to_string(), Value::String(selected.clone()));
    for ip in ips {
        if ip.parse::<Ipv4Addr>().is_ok() {
            endpoint_ips.insert(ip);
        }
    }
    info!("sing-box-lx: resolved {} {} -> {}", label, server, selected);
    Ok(())
}

fn collect_singbox_outbound_ipv4s(value: &Value) -> Vec<String> {
    let mut out = BTreeSet::<String>::new();
    if let Some(outbounds) = value.get("outbounds").and_then(Value::as_array) {
        for outbound in outbounds {
            collect_singbox_ipv4_field(outbound.as_object(), "server", &mut out);
        }
    }
    if let Some(endpoints) = value.get("endpoints").and_then(Value::as_array) {
        for endpoint in endpoints {
            let Some(peers) = endpoint
                .as_object()
                .and_then(|obj| obj.get("peers"))
                .and_then(Value::as_array)
            else {
                continue;
            };
            for peer in peers {
                collect_singbox_ipv4_field(peer.as_object(), "address", &mut out);
            }
        }
    }
    out.into_iter().collect()
}

fn collect_singbox_ipv4_field(obj: Option<&Map<String, Value>>, field: &str, out: &mut BTreeSet<String>) {
    let Some(server) = obj
        .and_then(|obj| obj.get(field))
        .and_then(Value::as_str)
        .map(str::trim)
    else {
        return;
    };
    if server.parse::<Ipv4Addr>().is_ok() {
        out.insert(server.to_string());
    }
}

fn normalize_singbox_common(obj: &mut Map<String, Value>, dns_servers: &[String]) {
    let primary_dns = dns_servers
        .iter()
        .map(|s| s.trim())
        .find(|s| is_ipv4(s))
        .unwrap_or("8.8.8.8")
        .to_string();

    // Advanced LX configs are user-owned.  Keep an existing DNS object byte-for-
    // byte structurally intact and only install the ZDT-D defaults when DNS is
    // absent.  This prevents future/LX-only DNS transports and fields from being
    // silently replaced by the generic sing-box template.
    let has_user_dns = obj.contains_key("dns");
    if !has_user_dns {
        let mut direct_domains = collect_singbox_direct_dns_domains(obj);
        direct_domains.insert("dns.google".to_string());
        for domain in collect_singbox_outbound_domains(obj) {
            direct_domains.insert(domain);
        }

        let mut dns_rules = Vec::new();
        if !direct_domains.is_empty() {
            dns_rules.push(json!({
                "domain": direct_domains.into_iter().collect::<Vec<_>>(),
                "server": "dns-direct"
            }));
        }

        let inbound_tags = collect_singbox_inbound_tags(obj);
        if !inbound_tags.is_empty() {
            dns_rules.push(json!({
                "inbound": inbound_tags,
                "query_type": ["A", "AAAA"],
                "server": "dns-fake",
                "disable_cache": true
            }));
        }

        obj.insert(
            "dns".to_string(),
            json!({
                "servers": [
                    {
                        "type": "local",
                        "tag": "dns-local"
                    },
                    {
                        "type": "udp",
                        "tag": "dns-direct",
                        "server": primary_dns,
                        "server_port": 53
                    },
                    {
                        "type": "https",
                        "tag": "dns-remote",
                        "server": "dns.google",
                        "server_port": 443,
                        "path": "/dns-query",
                        "domain_resolver": {
                            "server": "dns-direct",
                            "strategy": "ipv4_only"
                        }
                    },
                    {
                        "type": "fakeip",
                        "tag": "dns-fake",
                        "inet4_range": "198.18.0.0/15",
                        "inet6_range": "fc00::/18"
                    }
                ],
                "rules": dns_rules,
                "final": "dns-remote",
                "independent_cache": true,
                "strategy": "ipv4_only"
            }),
        );
    }

    let inbound_tags = collect_singbox_inbound_tags(obj);
    let has_dns_direct = singbox_dns_has_server_tag(obj, "dns-direct");
    let mut route_obj = match obj.remove("route") {
        Some(Value::Object(route)) => route,
        Some(other) => {
            // A future LX core may accept another route representation. Keep it
            // untouched instead of coercing it into today's object schema.
            obj.insert("route".to_string(), other);
            return;
        }
        None => Map::new(),
    };

    route_obj
        .entry("auto_detect_interface".to_string())
        .or_insert(Value::Bool(true));
    if has_dns_direct {
        route_obj
            .entry("default_domain_resolver".to_string())
            .or_insert_with(|| {
                json!({
                    "server": "dns-direct",
                    "strategy": "ipv4_only"
                })
            });
    }

    // Preserve every user rule and its relative ordering.  ZDT-D's integration
    // rules are injected only when an identical rule is not already present.
    match route_obj.remove("rules") {
        Some(Value::Array(existing_rules)) => {
            let mut route_rules = Vec::new();
            for tag in &inbound_tags {
                push_unique_json_rule(
                    &mut route_rules,
                    json!({
                        "inbound": [tag],
                        "action": "sniff"
                    }),
                );
            }
            for rule in existing_rules {
                push_unique_json_rule(&mut route_rules, rule);
            }
            append_singbox_managed_route_rules(&mut route_rules);
            route_obj.insert("rules".to_string(), Value::Array(route_rules));
        }
        Some(other) => {
            // Unknown/future rule container: preserve it verbatim.
            route_obj.insert("rules".to_string(), other);
        }
        None => {
            let mut route_rules = Vec::new();
            for tag in &inbound_tags {
                push_unique_json_rule(
                    &mut route_rules,
                    json!({
                        "inbound": [tag],
                        "action": "sniff"
                    }),
                );
            }
            append_singbox_managed_route_rules(&mut route_rules);
            route_obj.insert("rules".to_string(), Value::Array(route_rules));
        }
    }
    if !route_obj.contains_key("final") && has_singbox_outbound_tag(obj, "proxy") {
        route_obj.insert("final".to_string(), Value::String("proxy".to_string()));
    }

    obj.insert("route".to_string(), Value::Object(route_obj));
}

fn append_singbox_managed_route_rules(rules: &mut Vec<Value>) {
    push_unique_json_rule(
        rules,
        json!({
            "action": "hijack-dns",
            "port": [53]
        }),
    );
    push_unique_json_rule(
        rules,
        json!({
            "action": "hijack-dns",
            "protocol": ["dns"]
        }),
    );
    push_unique_json_rule(
        rules,
        json!({
            "action": "reject",
            "ip_cidr": ["224.0.0.0/3", "ff00::/8"],
            "source_ip_cidr": ["224.0.0.0/3", "ff00::/8"]
        }),
    );
}

fn push_unique_json_rule(rules: &mut Vec<Value>, rule: Value) {
    if !rules.iter().any(|existing| existing == &rule) {
        rules.push(rule);
    }
}

fn singbox_dns_has_server_tag(obj: &Map<String, Value>, tag: &str) -> bool {
    obj.get("dns")
        .and_then(Value::as_object)
        .and_then(|dns| dns.get("servers"))
        .and_then(Value::as_array)
        .map(|servers| {
            servers.iter().any(|server| {
                server
                    .as_object()
                    .and_then(|server| server.get("tag"))
                    .and_then(Value::as_str)
                    .map(|value| value == tag)
                    .unwrap_or(false)
            })
        })
        .unwrap_or(false)
}

fn collect_singbox_inbound_tags(obj: &Map<String, Value>) -> Vec<String> {
    crate::programs::singbox::family::collect_singbox_inbound_tags(obj)
}

fn collect_singbox_direct_dns_domains(obj: &Map<String, Value>) -> BTreeSet<String> {
    crate::programs::singbox::family::collect_singbox_direct_dns_domains(obj)
}

fn collect_singbox_outbound_domains(obj: &Map<String, Value>) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    if let Some(outbounds) = obj.get("outbounds").and_then(Value::as_array) {
        for outbound in outbounds {
            collect_singbox_domain_field(outbound.as_object(), "server", &mut out);
        }
    }
    if let Some(endpoints) = obj.get("endpoints").and_then(Value::as_array) {
        for endpoint in endpoints {
            let Some(peers) = endpoint
                .as_object()
                .and_then(|endpoint| endpoint.get("peers"))
                .and_then(Value::as_array)
            else {
                continue;
            };
            for peer in peers {
                collect_singbox_domain_field(peer.as_object(), "address", &mut out);
            }
        }
    }
    out
}

fn collect_singbox_domain_field(obj: Option<&Map<String, Value>>, field: &str, out: &mut BTreeSet<String>) {
    let Some(server) = obj
        .and_then(|obj| obj.get(field))
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
    else {
        return;
    };
    if !is_ipv4(server) && server.parse::<IpAddr>().is_err() && !server.contains(':') {
        out.insert(server.to_string());
    }
}

fn collect_domains_from_value(value: Option<&Value>, out: &mut BTreeSet<String>) {
    crate::programs::singbox::family::collect_domains_from_value(value, out)
}

fn has_singbox_outbound_tag(obj: &Map<String, Value>, tag: &str) -> bool {
    crate::programs::singbox::family::has_singbox_outbound_tag(obj, tag)
}

fn is_vpn_removed_route_rule(rule: &Value) -> bool {
    crate::programs::singbox::family::is_vpn_removed_route_rule(rule)
}

fn preferred_singbox_domain_resolver_tag(value: &Value) -> Option<String> {
    let dns = value.get("dns")?.as_object()?;
    let servers = dns.get("servers")?.as_array()?;

    // Keep the old ZDT-D behavior when its own direct resolver exists.  For an
    // advanced user DNS block, prefer the configured final server, then the
    // first tagged server.  Never synthesize a tag that the user's DNS does not
    // actually define.
    if servers.iter().any(|server| {
        server
            .as_object()
            .and_then(|server| server.get("tag"))
            .and_then(Value::as_str)
            .map(|tag| tag == "dns-direct")
            .unwrap_or(false)
    }) {
        return Some("dns-direct".to_string());
    }

    if let Some(final_tag) = dns
        .get("final")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|tag| !tag.is_empty())
    {
        if servers.iter().any(|server| {
            server
                .as_object()
                .and_then(|server| server.get("tag"))
                .and_then(Value::as_str)
                .map(|tag| tag == final_tag)
                .unwrap_or(false)
        }) {
            return Some(final_tag.to_string());
        }
    }

    servers.iter().find_map(|server| {
        server
            .as_object()
            .and_then(|server| server.get("tag"))
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|tag| !tag.is_empty())
            .map(ToOwned::to_owned)
    })
}

fn migrate_legacy_domain_strategy(value: &mut Value, resolver_tag: Option<&str>) {
    match value {
        Value::Object(obj) => {
            // Only consume the legacy field on objects that are known dial
            // options. Unknown LX/future objects keep their field untouched so
            // ZDT-D never destroys an extension merely because it reused the name.
            if looks_like_dial_object(obj) {
                let strategy = match obj.get("domain_strategy") {
                    Some(Value::String(s)) => s.trim().to_string(),
                    Some(Value::Number(n)) => n.to_string(),
                    Some(Value::Bool(b)) => b.to_string(),
                    _ => String::new(),
                };
                if !strategy.is_empty() {
                    if let Some(resolver_tag) = resolver_tag.filter(|tag| !tag.is_empty()) {
                        obj.remove("domain_strategy");
                        if !obj.contains_key("domain_resolver") {
                            obj.insert(
                                "domain_resolver".to_string(),
                                json!({
                                    "server": resolver_tag,
                                    "strategy": normalize_domain_strategy(&strategy)
                                }),
                            );
                        }
                    }
                }
            }
            let keys = obj.keys().cloned().collect::<Vec<_>>();
            for key in keys {
                if let Some(child) = obj.get_mut(&key) {
                    migrate_legacy_domain_strategy(child, resolver_tag);
                }
            }
        }
        Value::Array(arr) => {
            for child in arr {
                migrate_legacy_domain_strategy(child, resolver_tag);
            }
        }
        _ => {}
    }
}

fn looks_like_dial_object(obj: &Map<String, Value>) -> bool {
    if obj.contains_key("detour") || obj.contains_key("bind_interface") || obj.contains_key("routing_mark") {
        return true;
    }
    let ty = obj
        .get("type")
        .and_then(Value::as_str)
        .map(|s| s.trim().to_ascii_lowercase())
        .unwrap_or_default();
    matches!(
        ty.as_str(),
        "direct" | "socks" | "http" | "https" | "shadowsocks" | "vmess" | "vless" | "trojan" |
        "hysteria" | "hysteria2" | "tuic" | "wireguard" | "ssh" | "shadowtls" | "anytls" |
        "tor" | "masque" | "trusttunnel" | "naive" | "quic" | "tls" | "openvpn" | "openconnect"
    )
}

fn normalize_domain_strategy(strategy: &str) -> String {
    crate::programs::singbox::family::normalize_domain_strategy(strategy)
}

fn effective_vpn_tun_from_config_value(value: &Value, fallback: &str) -> Result<String> {
    crate::programs::singbox::family::effective_vpn_tun_from_config_value(VARIANT, value, fallback)
}

fn write_json_value_if_changed(config_path: &Path, original: &str, value: &Value) -> Result<()> {
    crate::programs::singbox::family::write_json_value_if_changed(VARIANT, config_path, original, value)
}

fn singbox_check_config(config_path: &Path) -> Result<()> {
    crate::programs::singbox::family::singbox_check_config(VARIANT, config_path)
}

pub fn validate_config_text(config_text: &str) -> Result<()> {
    if !is_installed() {
        bail!("sing-box-lx optional component is not installed");
    }
    let parsed: Value = serde_json::from_str(config_text).context("invalid sing-box-lx JSON")?;
    if !parsed.is_object() {
        bail!("sing-box-lx config root must be a JSON object");
    }
    let tmp_dir = Path::new(VARIANT.root).join("tmp");
    fs::create_dir_all(&tmp_dir).with_context(|| format!("create {}", tmp_dir.display()))?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let tmp = tmp_dir.join(format!("validate.{}.{}.json", std::process::id(), stamp));
    fs::write(&tmp, config_text).with_context(|| format!("write {}", tmp.display()))?;
    let result = singbox_check_config(&tmp);
    let _ = fs::remove_file(&tmp);
    result
}

fn singbox_check_config_with_vpn_retry(config_path: &Path, log_path: &Path) -> Result<()> {
    crate::programs::singbox::family::singbox_check_config_with_vpn_retry(VARIANT, config_path, log_path)
}

fn singbox_check_config_with_log(config_path: &Path, log_path: &Path) -> Result<()> {
    crate::programs::singbox::family::singbox_check_config_with_log(VARIANT, config_path, log_path)
}

fn run_singbox_check(config_path: &Path) -> Result<(i32, String)> {
    crate::programs::singbox::family::run_singbox_check(VARIANT, config_path)
}

fn write_check_failure_log(log_path: &Path, config_path: &Path, code: i32, out: &str) -> Result<()> {
    crate::programs::singbox::family::write_check_failure_log(VARIANT, log_path, config_path, code, out)
}

fn append_check_log(log_path: &Path, text: &str) -> Result<()> {
    crate::programs::singbox::family::append_check_log(log_path, text)
}

fn output_mentions_dns_mode(out: &str) -> bool {
    crate::programs::singbox::family::output_mentions_dns_mode(out)
}

fn remove_tun_dns_mode(config_path: &Path) -> Result<()> {
    crate::programs::singbox::family::remove_tun_dns_mode(VARIANT, config_path)
}

fn singbox_supports_tun_dns_mode() -> bool {
    crate::programs::singbox::family::singbox_supports_tun_dns_mode(VARIANT)
}

fn first_version_token(text: &str) -> Option<String> {
    crate::programs::singbox::family::first_version_token(text)
}

fn version_supports_tun_dns_mode(version: &str) -> bool {
    crate::programs::singbox::family::version_supports_tun_dns_mode(version)
}

fn spawn_singbox(config_path: &Path, log_path: &Path) -> Result<()> {
    crate::programs::singbox::family::spawn_singbox(VARIANT, config_path, log_path)
}

fn spawn_tun2socks_for_vpn(bin: &Path, plan: &VpnProfilePlan) -> Result<()> {
    crate::programs::singbox::family::spawn_tun2socks_for_vpn(VARIANT, bin, plan)
}


fn singbox_profile_process_running(config_path: &Path) -> bool {
    crate::programs::singbox::family::singbox_profile_process_running(VARIANT, config_path)
}

fn wait_tun_ready(tun: &str) -> Result<()> {
    crate::programs::singbox::family::wait_tun_ready(tun)
}







pub fn generated_tun_address_for_index(index: u32) -> Result<(String, String, String)> {
    crate::programs::singbox::family::generated_tun_address_for_index(VARIANT, index)
}


fn normalize_cidr_network(cidr: &str) -> Result<String> {
    crate::programs::singbox::family::normalize_cidr(cidr)
}



fn cidrs_overlap(a: &str, b: &str) -> Result<bool> {
    crate::programs::singbox::family::cidr_overlap(a, b)
}

fn is_ipv4(s: &str) -> bool {
    crate::programs::singbox::family::ipv4(s)
}

fn is_forbidden_tun_name(s: &str) -> bool {
    crate::programs::singbox::family::forbidden_tun(s)
}


pub fn main_pids_exact() -> Vec<i32> {
    crate::programs::singbox::family::main_pids_exact(VARIANT)
}

fn read_active_profiles() -> Result<ActiveProfiles> {
    crate::programs::singbox::family::read_active_profiles(VARIANT)
}

fn ensure_base_layout() -> Result<()> {
    crate::programs::singbox::family::ensure_base_layout(VARIANT)
}

fn profile_root(profile: &str) -> PathBuf {
    crate::programs::singbox::family::profile_root(VARIANT, profile)
}

fn singbox_server_root(profile: &str, server: &str) -> PathBuf {
    crate::programs::singbox::family::server_root(VARIANT, profile, server)
}

fn profile_t2s_log_path(profile: &str) -> PathBuf {
    crate::programs::singbox::family::profile_t2s_log_path(VARIANT, profile)
}

fn profile_tun2socks_log_path(profile: &str) -> PathBuf {
    crate::programs::singbox::family::profile_tun2socks_log_path(VARIANT, profile)
}

pub fn is_valid_profile_name(name: &str) -> bool {
    crate::programs::singbox::family::is_valid_profile_name(name)
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    crate::programs::singbox::family::ensure_valid_profile_name(name)
}


fn ensure_file_empty(path: &Path) -> Result<()> {
    crate::programs::singbox::family::ensure_file_empty(path)
}

fn unique_tmp_path(target: &Path) -> PathBuf {
    crate::programs::singbox::family::unique_tmp_path(target)
}

fn write_json_atomic<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    crate::programs::singbox::family::write_json_atomic(path, value)
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    crate::programs::singbox::family::read_json(path)
}

fn ensure_dir(path: &str) -> Result<()> {
    crate::programs::singbox::family::ensure_dir(path)
}

fn ensure_file(path: &str) -> Result<()> {
    crate::programs::singbox::family::ensure_file(path)
}
