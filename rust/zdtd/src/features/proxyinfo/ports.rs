use crate::common::config::deserialize_boolish;
use anyhow::Result;
use serde::Deserialize;
use serde_json::Value;
use std::{collections::{BTreeMap, BTreeSet}, fs, path::Path};

use crate::{logging, settings};
use super::state::read_json_file;

const API_PORT: u16 = 1006;
const RESERVED_SYSTEM_PORT_LIMIT: u16 = 1024;

pub(super) fn is_reserved_system_port_for_proxyinfo(port: u16) -> bool {
    port != API_PORT && port > 0 && port < RESERVED_SYSTEM_PORT_LIMIT
}

fn drop_reserved_system_ports(label: &str, ports: &mut BTreeSet<u16>) {
    let skipped: Vec<u16> = ports
        .iter()
        .copied()
        .filter(|&port| is_reserved_system_port_for_proxyinfo(port))
        .collect();
    for port in skipped {
        ports.remove(&port);
        logging::warn(&format!(
            "proxyInfo: skip {} protected port {}: ports below 1024 are reserved by the system and will not be blocked",
            label, port
        ));
    }
}
#[derive(Debug, Deserialize, Default)]
struct ProfileState {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct ProfilesActive {
    #[serde(default)]
    profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Deserialize, Default)]
#[allow(dead_code)]
struct EnabledActive {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct ProfilePortJson {
    #[serde(default)]
    port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct OperaActive {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct OperaPortJson {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default)]
    opera_start_port: u16,
    #[serde(default)]
    byedpi_port: u16,
}

fn default_operaproxy_t2s_web_port() -> u16 {
    8000
}

#[derive(Debug, Deserialize, Default)]
struct SingboxProfileSetting {
    #[serde(default)]
    #[allow(dead_code)]
    mode: String,
    #[serde(default)]
    t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    t2s_web_port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct SingboxServerSetting {
    #[serde(default)]
    enabled: bool,
    #[serde(default)]
    port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct WireproxyProfileSetting {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    t2s_web_port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct WireproxyServerSetting {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct TorEnabledJson {
    #[serde(default, deserialize_with = "deserialize_boolish")]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct TorSetting {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default)]
    t2s_web_port: u16,
}
fn default_t2s_web_port() -> u16 {
    8001
}
pub fn collect_protected_port_sets() -> Result<(BTreeSet<u16>, BTreeSet<u16>)> {
    let mut local = BTreeSet::new();
    let mut global = BTreeSet::new();
    local.insert(API_PORT);
    collect_byedpi_ports(&mut local)?;
    collect_dpitunnel_ports(&mut local)?;
    collect_operaproxy_ports(&mut local)?;
    collect_dnscrypt_d2s_ports(&mut local)?;
    collect_singbox_ports(&mut local)?;
    collect_singbox_lx_ports(&mut local)?;
    collect_hysteria2_ports(&mut local)?;
    collect_wireproxy_ports(&mut local)?;
    collect_tor_ports(&mut local)?;
    collect_myproxy_ports(&mut local, &mut global)?;
    collect_myprogram_ports(&mut local)?;
    collect_mihomo_ports(&mut local)?;
    collect_mieru_ports(&mut local)?;
    collect_tgwsproxy_ports(&mut local)?;
    drop_reserved_system_ports("local", &mut local);
    drop_reserved_system_ports("global", &mut global);
    local.insert(API_PORT);
    Ok((local, global))
}

#[allow(dead_code)]
pub fn collect_protected_ports() -> Result<BTreeSet<u16>> {
    let (mut local, global) = collect_protected_port_sets()?;
    local.extend(global);
    Ok(local)
}




fn collect_dnscrypt_d2s_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    match crate::programs::dnscrypt::active_d2s_listen_port() {
        Ok(Some(port)) if port > 0 => {
            out.insert(port);
        }
        Ok(_) => {}
        Err(error) => log::warn!("proxyInfo: failed to read D2S listener: {error:#}"),
    }
    Ok(())
}

fn collect_tgwsproxy_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    if let Some(port) = crate::programs::tgwsproxy::protected_local_port() {
        out.insert(port);
    }
    Ok(())
}

fn collect_byedpi_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("byedpi").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("byedpi");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let p = root.join(&name).join("port.json");
        if !p.is_file() {
            continue;
        }
        let port: ProfilePortJson = read_json_file(&p).unwrap_or_default();
        if port.port > 0 {
            out.insert(port.port);
        }
    }
    Ok(())
}

fn collect_dpitunnel_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("dpitunnel").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("dpitunnel");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let p = root.join(&name).join("port.json");
        if !p.is_file() {
            continue;
        }
        let port: ProfilePortJson = read_json_file(&p).unwrap_or_default();
        if port.port > 0 {
            out.insert(port.port);
        }
    }
    Ok(())
}

fn collect_operaproxy_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("operaproxy").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: OperaActive = read_json_file(&active_path).unwrap_or_default();
    if !active.enabled {
        return Ok(());
    }
    let port_path = crate::programs::registry::working_program_dir("operaproxy").join("port.json");
    if !port_path.is_file() {
        return Ok(());
    }
    let ports: OperaPortJson = read_json_file(&port_path).unwrap_or_default();
    if ports.byedpi_port > 0 {
        out.insert(ports.byedpi_port);
    }
    if ports.t2s_port > 0 {
        out.insert(ports.t2s_port);
    }
    out.insert(default_operaproxy_t2s_web_port());

    let sni_path = crate::programs::registry::working_program_dir("operaproxy").join("config/sni.json");
    let count = count_operaproxy_sni(&sni_path);
    if ports.opera_start_port > 0 && count > 0 {
        for i in 0..count {
            if let Some(p) = ports.opera_start_port.checked_add(i as u16) {
                out.insert(p);
            }
        }
    }
    Ok(())
}

fn count_operaproxy_sni(p: &Path) -> usize {
    let raw = match fs::read_to_string(p) {
        Ok(s) => s,
        Err(_) => return 0,
    };
    let v: Value = match serde_json::from_str(&raw) {
        Ok(v) => v,
        Err(_) => return 0,
    };
    match v {
        Value::Array(arr) => arr.len(),
        _ => 0,
    }
}

fn collect_singbox_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("singbox").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings.hotspot_t2s_singbox_profile().map(|s| s.to_string());
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("singbox").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let is_hotspot_profile = hotspot_profile.as_deref() == Some(name.as_str());
        let profile_dir = root.join(&name);
        if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
            continue;
        }
        let setting_path = profile_dir.join("setting.json");
        if setting_path.is_file() {
            let setting: SingboxProfileSetting = read_json_file(&setting_path).unwrap_or_default();
            if !is_hotspot_profile && setting.t2s_port > 0 {
                out.insert(setting.t2s_port);
            }
            if !is_hotspot_profile && setting.t2s_web_port > 0 {
                out.insert(setting.t2s_web_port);
            }
        }
        let server_root = profile_dir.join("server");
        if let Ok(rd) = fs::read_dir(&server_root) {
            for ent in rd.flatten() {
                let server_dir = ent.path();
                if !server_dir.is_dir() {
                    continue;
                }
                if server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                    continue;
                }
                let setting_path = server_dir.join("setting.json");
                if !setting_path.is_file() {
                    continue;
                }
                let setting: SingboxServerSetting = read_json_file(&setting_path).unwrap_or_default();
                if !is_hotspot_profile && setting.enabled && setting.port > 0 {
                    out.insert(setting.port);
                }
            }
        }
    }
    Ok(())
}


fn collect_singbox_lx_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("singbox-lx").join("active.json");
    if !active_path.is_file() { return Ok(()); }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("singbox-lx").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let profile_dir = root.join(&name);
        if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) { continue; }
        let setting_path = profile_dir.join("setting.json");
        if setting_path.is_file() {
            let setting: SingboxProfileSetting = read_json_file(&setting_path).unwrap_or_default();
            if setting.t2s_port > 0 { out.insert(setting.t2s_port); }
            if setting.t2s_web_port > 0 { out.insert(setting.t2s_web_port); }
        }
        let server_root = profile_dir.join("server");
        if let Ok(rd) = fs::read_dir(&server_root) {
            for ent in rd.flatten() {
                let server_dir = ent.path();
                if !server_dir.is_dir() { continue; }
                if server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) { continue; }
                let setting_path = server_dir.join("setting.json");
                if !setting_path.is_file() { continue; }
                let setting: SingboxServerSetting = read_json_file(&setting_path).unwrap_or_default();
                if setting.enabled && setting.port > 0 { out.insert(setting.port); }
            }
        }
    }
    Ok(())
}


fn collect_wireproxy_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("wireproxy").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings.hotspot_t2s_wireproxy_profile().map(|s| s.to_string());
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("wireproxy").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let is_hotspot_profile = hotspot_profile.as_deref() == Some(name.as_str());
        let profile_dir = root.join(&name);
        if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
            continue;
        }
        let setting_path = profile_dir.join("setting.json");
        if setting_path.is_file() {
            let setting: WireproxyProfileSetting = read_json_file(&setting_path).unwrap_or_default();
            if !is_hotspot_profile && setting.t2s_port > 0 {
                out.insert(setting.t2s_port);
            }
            if !is_hotspot_profile && setting.t2s_web_port > 0 {
                out.insert(setting.t2s_web_port);
            }
        }
        let server_root = profile_dir.join("server");
        if let Ok(rd) = fs::read_dir(&server_root) {
            for ent in rd.flatten() {
                let server_dir = ent.path();
                if !server_dir.is_dir() {
                    continue;
                }
                if server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                    continue;
                }
                let setting_path = server_dir.join("setting.json");
                if !setting_path.is_file() {
                    continue;
                }
                let setting: WireproxyServerSetting = read_json_file(&setting_path).unwrap_or_default();
                if !setting.enabled || is_hotspot_profile {
                    continue;
                }
                let config_path = server_dir.join("config.conf");
                let Ok(raw) = fs::read_to_string(&config_path) else { continue; };
                let Ok(addr) = crate::programs::wireproxy::parse_socks5_bind_address_str(&raw) else { continue; };
                if addr.port > 0 {
                    out.insert(addr.port);
                }
            }
        }
    }
    Ok(())
}


fn collect_myproxy_ports(local: &mut BTreeSet<u16>, global: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("myproxy").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("myproxy").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let profile_dir = root.join(&name);
        let setting_path = profile_dir.join("setting.json");
        if let Ok(v) = read_json_file::<Value>(&setting_path) {
            for key in ["t2s_port", "t2s_web_port"] {
                if let Some(port) = v.get(key).and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                    if port != 0 { local.insert(port); }
                }
            }
        }
        let proxy_path = profile_dir.join("proxy.json");
        if let Ok(v) = read_json_file::<Value>(&proxy_path) {
            collect_myproxy_upstream_ports_from_value(&v, global);
        }
    }
    Ok(())
}

fn collect_myproxy_upstream_ports_from_value(v: &Value, out: &mut BTreeSet<u16>) {
    if let Some(ports) = v.get("ports").and_then(|x| x.as_array()) {
        for item in ports {
            collect_proxy_port_value(item, out);
        }
        if !ports.is_empty() { return; }
    }
    if let Some(port_value) = v.get("port") {
        collect_proxy_port_value(port_value, out);
    }
}

fn collect_proxy_port_value(v: &Value, out: &mut BTreeSet<u16>) {
    if let Some(port) = v.as_u64().and_then(|x| u16::try_from(x).ok()) {
        if port != 0 { out.insert(port); }
        return;
    }
    if let Some(s) = v.as_str() {
        for part in s.split(',') {
            if let Ok(port) = part.trim().parse::<u16>() {
                if port != 0 { out.insert(port); }
            }
        }
        return;
    }
    if let Some(items) = v.as_array() {
        for item in items { collect_proxy_port_value(item, out); }
    }
}


fn collect_myprogram_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("myprogram").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("myprogram").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let profile_dir = root.join(&name);
        let setting_path = profile_dir.join("setting.json");
        if let Ok(v) = read_json_file::<Value>(&setting_path) {
            let apps_mode = v.get("apps_mode").and_then(|x| x.as_bool()).unwrap_or(false);
            let route_mode = v.get("route_mode").and_then(|x| x.as_str()).unwrap_or("t2s");
            if apps_mode {
                if route_mode == "t2s" {
                    for key in ["t2s_port", "t2s_web_port"] {
                        if let Some(port) = v.get(key).and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                            if port != 0 { out.insert(port); }
                        }
                    }
                    let t2s_ports_path = profile_dir.join("t2s_ports.txt");
                    if let Ok(raw) = fs::read_to_string(&t2s_ports_path) {
                        if let Ok(ports) = crate::programs::myprogram::parse_port_list_str(&raw) { out.extend(ports); }
                    }
                } else if route_mode == "transparent" {
                    if let Some(port) = v.get("transparent_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                        if port != 0 { out.insert(port); }
                    }
                }
            }
            let protect_ports_path = profile_dir.join("protect_ports.txt");
            if let Ok(raw) = fs::read_to_string(&protect_ports_path) {
                if let Ok(ports) = crate::programs::myprogram::parse_port_list_str(&raw) { out.extend(ports); }
            }
        }
    }
    Ok(())
}

fn collect_mihomo_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("mihomo").join("active.json");
    if !active_path.is_file() { return Ok(()); }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("mihomo").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let profile_dir = root.join(&name);
        let setting_path = profile_dir.join("setting.json");
        if let Ok(v) = read_json_file::<Value>(&setting_path) {
            if let Some(port) = v.get("mixed_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                if port != 0 { out.insert(port); }
            }
        }
        collect_mihomo_yaml_ports_from_dir(&profile_dir, out);
    }
    Ok(())
}



fn collect_hysteria2_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("hysteria2").join("active.json");
    if !active_path.is_file() { return Ok(()); }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("hysteria2").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let profile_dir = root.join(&name);
        let setting_path = profile_dir.join("setting.json");
        if let Ok(v) = read_json_file::<Value>(&setting_path) {
            let mode = v.get("mode").and_then(|x| x.as_str()).unwrap_or("t2s");
            if mode != "vpn" {
                for key in ["t2s_port", "t2s_web_port"] {
                    if let Some(port) = v.get(key).and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                        if port != 0 { out.insert(port); }
                    }
                }
            }
        }
        let server_root = profile_dir.join("server");
        if let Ok(rd) = fs::read_dir(&server_root) {
            for ent in rd.flatten() {
                let server_dir = ent.path();
                if !server_dir.is_dir() { continue; }
                let setting_path = server_dir.join("setting.json");
                let Ok(v) = read_json_file::<Value>(&setting_path) else { continue; };
                if !crate::common::fs::json::json_enabled(v.get("enabled")) { continue; }
                if let Some(port) = v.get("socks5_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                    if port != 0 { out.insert(port); }
                }
            }
        }
    }
    Ok(())
}

fn collect_mieru_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = crate::programs::registry::working_program_dir("mieru").join("active.json");
    if !active_path.is_file() { return Ok(()); }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = crate::programs::registry::working_program_dir("mieru").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let setting_path = root.join(&name).join("setting.json");
        if let Ok(v) = read_json_file::<Value>(&setting_path) {
            for key in ["socks5_port", "rpc_port"] {
                if let Some(port) = v.get(key).and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                    if port != 0 { out.insert(port); }
                }
            }
        }
    }
    Ok(())
}

fn collect_mihomo_yaml_ports_from_dir(profile_dir: &Path, out: &mut BTreeSet<u16>) {
    for name in ["config.yaml", "config.runtime.yaml"] {
        let path = profile_dir.join(name);
        if let Ok(raw) = fs::read_to_string(&path) {
            if let Some(port) = crate::common::config::mihomo::external_controller_port(&raw) {
                out.insert(port);
            }
        }
    }
}





fn collect_tor_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let enabled_path = crate::programs::registry::working_program_dir("tor").join("enabled.json");
    if !enabled_path.is_file() {
        return Ok(());
    }
    let enabled: TorEnabledJson = read_json_file(&enabled_path).unwrap_or_default();
    if !enabled.enabled {
        return Ok(());
    }
    let setting_path = crate::programs::registry::working_program_dir("tor").join("setting.json");
    if setting_path.is_file() {
        let setting: TorSetting = read_json_file(&setting_path).unwrap_or_default();
        if setting.t2s_port > 0 {
            out.insert(setting.t2s_port);
        }
        if setting.t2s_web_port > 0 {
            out.insert(setting.t2s_web_port);
        }
    }
    let torrc_path = crate::programs::registry::working_program_dir("tor").join("torrc");
    if let Ok(raw) = fs::read_to_string(&torrc_path) {
        if let Ok(port) = crate::programs::tor::parse_socks_port_from_str(&raw) {
            if port > 0 {
                out.insert(port);
            }
        }
    }
    Ok(())
}
