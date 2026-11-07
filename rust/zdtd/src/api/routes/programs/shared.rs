use anyhow::Result;
use serde_json::json;
use std::{collections::BTreeSet, fs, path::{Path, PathBuf}};

use crate::programs::registry::ProgramId;
use super::super::super::support::fs::{read_json, write_json_pretty, write_text_atomic};
use super::super::super::support::model::{ProfileState, ProfilesActive};
use super::super::super::support::paths::ensure_safe_segment;
use super::super::super::support::profile_crud::{
    create_profile_named_generic, create_profile_next_generic, create_standard_profile_named,
    create_standard_profile_next, list_validated_child_dirs, next_prefixed_child_dir,
    ProfileCreateSpec, StandardProfileCreateSpec,
};

pub(in crate::api) fn refresh_apps_after_save_if_running(services_running: bool, program: &str, profile: Option<&str>, slot: &str) -> Result<()> {
    if matches!(program, "proxyinfo" | "blockedquic") {
        if services_running {
            let _ = crate::runtime::refresh::refresh_apps(program, profile, slot)?;
        }
        return Ok(());
    }
    let _ = crate::runtime::apply::schedule_after_app_save(services_running, program, profile, slot);
    Ok(())
}


pub(in crate::api) fn program_display_name<'a>(id: &'a str) -> &'a str {
    match crate::programs::registry::ProgramId::parse(id) {
        Some(program) => program.display_name(),
        None => id,
    }
}



pub(in crate::api) fn program_root(program_name: &str) -> PathBuf {
    crate::programs::registry::working_program_dir(program_name)
}

pub(in crate::api) fn active_json_path(id: &str) -> PathBuf {
    program_root(id).join("active.json")
}

pub(in crate::api) fn profile_root(id: &str, profile: &str) -> PathBuf {
    program_root(id).join(profile)
}

pub(in crate::api) fn ensure_profile_layout(id: &str, profile: &str) -> Result<()> {
    let root = profile_root(id, profile);
    // Base folders
    fs::create_dir_all(root.join("config"))?;
    fs::create_dir_all(root.join("app/uid"))?;

    // Config (args)
    let cfg = root.join("config/config.txt");
    if !cfg.exists() {
        write_text_atomic(&cfg, "")?;
    }

    // App lists
    match id {
        "nfqws" | "nfqws2" => {
            for f in ["user_program", "mobile_program", "wifi_program"] {
                let p = root.join(format!("app/uid/{f}"));
                if !p.exists() {
                    write_text_atomic(&p, "")?;
                }
            }
        }
        "byedpi" => {
            let p = root.join("app/uid/user_program");
            if !p.exists() {
                write_text_atomic(&p, "")?;
            }
        }
        "dpitunnel" => {
            for f in ["user_program", "mobile_program", "wifi_program"] {
                let p = root.join(format!("app/uid/{f}"));
                if !p.exists() {
                    write_text_atomic(&p, "")?;
                }
            }
        }
        _ => {}
    }
    Ok(())
}

#[allow(dead_code)]
pub(in crate::api) fn next_port_from_existing(root: &Path, default_port: u16) -> u16 {
    // Best effort: scan */port.json and pick max(port)+1.
    let mut max_port: Option<u16> = None;
    if let Ok(rd) = fs::read_dir(root) {
        for ent in rd.flatten() {
            let p = ent.path().join("port.json");
            if !p.is_file() {
                continue;
            }
            if let Ok(txt) = fs::read_to_string(&p) {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(&txt) {
                    if let Some(port) = v.get("port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                        max_port = Some(max_port.map(|m| m.max(port)).unwrap_or(port));
                    }
                }
            }
        }
    }
    match max_port {
        Some(p) => p.saturating_add(1),
        None => default_port,
    }
}

pub(in crate::api) fn write_default_port_json(id: &str, profile: &str) -> Result<()> {
    let root = profile_root(id, profile);
    let p = root.join("port.json");
    if p.exists() {
        return Ok(());
    }

    match id {
        "nfqws" => {
            let port = crate::programs::ports::suggest_port_for_new_profile(id)?;
            let wifi = crate::common::network::detect_iface_by_prefix(&["wlan", "wifi"]).unwrap_or_else(|| "wlan0".to_string());
            let mobile = crate::common::network::detect_iface_by_prefix(&["rmnet", "ccmni", "pdp"]).unwrap_or_else(|| "rmnet_data0".to_string());
            let v = json!({
                "port": port,
                "iface_mobile": mobile,
                "iface_wifi": wifi
            });
            write_json_pretty(&p, &v)?;
        }
        "nfqws2" => {
            let port = crate::programs::ports::suggest_port_for_new_profile(id)?;
            let wifi = crate::common::network::normalize_wifi_iface("");
            let mobile = crate::common::network::normalize_mobile_iface("");
            let v = json!({
                "port": port,
                "iface_mobile": mobile,
                "iface_wifi": wifi
            });
            write_json_pretty(&p, &v)?;
        }
        "byedpi" => {
            let port = crate::programs::ports::suggest_port_for_new_profile(id)?;
            let v = json!({"port": port});
            write_json_pretty(&p, &v)?;
        }
        "dpitunnel" => {
            let port = crate::programs::ports::suggest_port_for_new_profile(id)?;
            let wifi = crate::common::network::detect_iface_by_prefix(&["wlan", "wifi"]).unwrap_or_else(|| "wlan0".to_string());
            let mobile = crate::common::network::detect_iface_by_prefix(&["rmnet", "ccmni", "pdp"]).unwrap_or_else(|| "rmnet_data0".to_string());
            let v = json!({
                "port": port,
                "iface_mobile": mobile,
                "iface_wifi": wifi
            });
            write_json_pretty(&p, &v)?;
        }
        _ => {}
    }
    Ok(())
}

pub(in crate::api) fn normalize_profile_name(input: &str) -> Result<String> {
    // Match the Android app rules:
    // - trim
    // - spaces -> '_'
    // - lowercase
    // - max len 10
    // - allow only [a-z0-9_-]
    let mut s = input.trim().replace(' ', "_").to_ascii_lowercase();
    if s.len() > 10 {
        s.truncate(10);
    }
    if s.is_empty() {
        anyhow::bail!("profile name is empty");
    }
    if !s.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-') {
        anyhow::bail!("invalid profile name");
    }
    Ok(s)
}

pub(in crate::api) fn is_valid_singbox_profile_name(name: &str) -> bool {
    if name.is_empty() || name.len() > 64 {
        return false;
    }
    name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub(in crate::api) fn ensure_valid_singbox_profile_name(name: &str) -> Result<()> {
    if !is_valid_singbox_profile_name(name) {
        anyhow::bail!("invalid profile name");
    }
    Ok(())
}

pub(in crate::api) fn singbox_active_path() -> PathBuf {
    program_root("singbox").join("active.json")
}

pub(in crate::api) fn singbox_profiles_root() -> PathBuf {
    program_root("singbox").join("profile")
}

pub(in crate::api) fn singbox_deleted_root() -> PathBuf {
    program_root("singbox").join(".deleted")
}

pub(in crate::api) fn singbox_deleted_profiles_root() -> PathBuf {
    singbox_deleted_root().join("profiles")
}

pub(in crate::api) fn singbox_deleted_servers_root(profile: &str) -> PathBuf {
    singbox_deleted_root().join("servers").join(profile)
}

pub(in crate::api) fn singbox_profile_root(profile: &str) -> PathBuf {
    singbox_profiles_root().join(profile)
}

pub(in crate::api) fn singbox_server_root(profile: &str, server: &str) -> PathBuf {
    singbox_profile_root(profile).join("server").join(server)
}

pub(in crate::api) fn default_singbox_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({
        "mode": "t2s",
        "t2s_port": t2s_port,
        "t2s_web_port": t2s_web_port,
        "tun": "sbtun0",
        "dns": ["8.8.8.8"],
        "tun2socks_loglevel": "info",
        "proto_mode": "tcp_udp",
        "endpoint_resolve": true
    })
}

pub(in crate::api) fn default_singbox_server_setting_value(port: u16) -> serde_json::Value {
    json!({
        "enabled": false,
        "port": port
    })
}

pub(in crate::api) fn ensure_singbox_profile_layout(profile: &str) -> Result<()> {
    let root = singbox_profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("server"))?;

    let app_list = root.join("app/uid/user_program");
    if !app_list.exists() {
        write_text_atomic(&app_list, "")?;
    }
    let out_list = root.join("app/out/user_program");
    if !out_list.exists() {
        write_text_atomic(&out_list, "")?;
    }
    let t2s_log = root.join("log/t2s.log");
    if !t2s_log.exists() {
        write_text_atomic(&t2s_log, "")?;
    }
    let tun2socks_log = root.join("log/tun2socks.log");
    if !tun2socks_log.exists() {
        write_text_atomic(&tun2socks_log, "")?;
    }
    Ok(())
}


pub(in crate::api) fn singbox_raw_mode_is_vpn(mode: &str) -> bool {
    matches!(
        mode.trim().to_ascii_lowercase().as_str(),
        "vpn" | "tun2socks" | "t2s-vpn" | "t2s_vpn"
    )
}

/// Where a per-server port comes from when scanning a program's existing profile ports.
#[derive(Clone, Copy)]
pub(in crate::api) enum ServerPortSource {
    /// The program has no per-server ports at all (myproxy).
    None,
    /// Read this key from `server/<name>/setting.json` (sing-box: "port", hysteria2: "socks5_port").
    SettingKey(&'static str),
    /// Parse `server/<name>/config.conf` (wireproxy).
    WireproxyConf,
}

/// Shared "which ports are already taken by this program's profiles" walk.
///
/// singbox / wireproxy / myproxy / hysteria2 each carried their own copy of this loop.
/// The copies differed only in the fields below, so the differences are now data instead of
/// four separate loops. Per-program behavior is preserved, including the quirks:
///   * only singbox and hysteria2 skip t2s ports of VPN-mode profiles;
///   * the base hysteria2 scan does not skip '.'-prefixed directories (skip_hidden: false);
///   * only hysteria2 uses the exclude_* fields (its "_excluding" variant).
pub(in crate::api) struct ProfilePortScan {
    profiles_root: PathBuf,
    /// When set, profile t2s ports are skipped for VPN-mode profiles.
    mode_is_vpn: Option<fn(&str) -> bool>,
    /// Skip '.'-prefixed profile and server directories.
    skip_hidden: bool,
    server_port: ServerPortSource,
    /// Profile whose own ports may be ignored (caller is editing it).
    exclude_profile: Option<String>,
    /// Server whose own ports are ignored (caller is editing it).
    exclude_server: Option<String>,
}

impl ProfilePortScan {
    fn collect(&self) -> BTreeSet<u16> {
        let mut used = BTreeSet::new();
        let Ok(rd) = fs::read_dir(&self.profiles_root) else { return used; };
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() {
                continue;
            }
            let profile_name = profile_dir.file_name().and_then(|s| s.to_str()).unwrap_or("");
            if self.skip_hidden && profile_name.starts_with('.') {
                continue;
            }
            let is_excluded_profile = self.exclude_profile.as_deref() == Some(profile_name);
            // Keeps the previous hysteria2 rule: the edited profile's own t2s ports are ignored
            // only while editing the profile itself, not while editing one of its servers.
            let skip_profile_ports = is_excluded_profile && self.exclude_server.is_none();
            if let Ok(v) = read_json::<serde_json::Value>(&profile_dir.join("setting.json")) {
                let mode = v.get("mode").and_then(|x| x.as_str()).unwrap_or("t2s").trim().to_ascii_lowercase();
                let vpn_mode = self.mode_is_vpn.map(|is_vpn| is_vpn(&mode)).unwrap_or(false);
                if !vpn_mode && !skip_profile_ports {
                    for key in ["t2s_port", "t2s_web_port"] {
                        if let Some(port) = v.get(key).and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).filter(|p| *p != 0) {
                            used.insert(port);
                        }
                    }
                }
            }

            let server_setting_key = match self.server_port {
                ServerPortSource::None => continue,
                ServerPortSource::SettingKey(key) => Some(key),
                ServerPortSource::WireproxyConf => None,
            };
            let Ok(server_rd) = fs::read_dir(profile_dir.join("server")) else { continue; };
            for server_ent in server_rd.flatten() {
                let server_dir = server_ent.path();
                if !server_dir.is_dir() {
                    continue;
                }
                let server_name = server_dir.file_name().and_then(|s| s.to_str()).unwrap_or("");
                if self.skip_hidden && server_name.starts_with('.') {
                    continue;
                }
                if is_excluded_profile && self.exclude_server.as_deref() == Some(server_name) {
                    continue;
                }
                match server_setting_key {
                    Some(key) => {
                        if let Ok(v) = read_json::<serde_json::Value>(&server_dir.join("setting.json")) {
                            if let Some(port) = v.get(key).and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).filter(|p| *p != 0) {
                                used.insert(port);
                            }
                        }
                    }
                    None => {
                        let Ok(raw) = fs::read_to_string(server_dir.join("config.conf")) else { continue; };
                        let Ok(bind) = crate::programs::wireproxy::parse_socks5_bind_address_str(&raw) else { continue; };
                        if bind.port > 0 {
                            used.insert(bind.port);
                        }
                    }
                }
            }
        }
        used
    }
}

/// hysteria2 VPN-mode names, previously inlined in its two port scans.
pub(in crate::api) fn hysteria2_raw_mode_is_vpn(mode: &str) -> bool {
    matches!(mode, "vpn" | "tun2proxy" | "tun2socks")
}

pub(in crate::api) fn singbox_profile_create_spec() -> ProfileCreateSpec {
    ProfileCreateSpec {
        validate_name: ensure_valid_singbox_profile_name,
        active_path: singbox_active_path,
        ensure_layout: ensure_singbox_profile_layout,
        profile_root: singbox_profile_root,
        suggest_ports: suggest_singbox_profile_ports,
        default_setting: default_singbox_profile_setting_value,
    }
}

pub(in crate::api) fn wireproxy_profile_create_spec() -> ProfileCreateSpec {
    ProfileCreateSpec {
        validate_name: ensure_valid_singbox_profile_name,
        active_path: wireproxy_active_path,
        ensure_layout: ensure_wireproxy_profile_layout,
        profile_root: wireproxy_profile_root,
        suggest_ports: suggest_wireproxy_profile_ports,
        default_setting: default_wireproxy_profile_setting_value,
    }
}

pub(in crate::api) fn myproxy_profile_create_spec() -> ProfileCreateSpec {
    ProfileCreateSpec {
        validate_name: ensure_valid_singbox_profile_name,
        active_path: myproxy_active_path,
        ensure_layout: ensure_myproxy_profile_layout,
        profile_root: myproxy_profile_root,
        suggest_ports: suggest_myproxy_profile_ports,
        default_setting: default_myproxy_profile_setting_value,
    }
}

pub(in crate::api) fn collect_existing_singbox_ports() -> BTreeSet<u16> {
    ProfilePortScan {
        profiles_root: singbox_profiles_root(),
        mode_is_vpn: Some(singbox_raw_mode_is_vpn),
        skip_hidden: true,
        server_port: ServerPortSource::SettingKey("port"),
        exclude_profile: None,
        exclude_server: None,
    }
    .collect()
}

pub(in crate::api) fn next_free_port_from_used(start: u16, used: &BTreeSet<u16>) -> Result<u16> {
    let mut p = if start == 0 { 1u32 } else { start as u32 };
    while p <= u16::MAX as u32 {
        let port = p as u16;
        if !used.contains(&port) {
            return Ok(port);
        }
        p += 1;
    }
    anyhow::bail!("no free port available starting from {start}")
}

pub(in crate::api) fn suggest_singbox_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_singbox_ports());
    let t2s = next_free_port_from_used(12345, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8001, &used)?;
    Ok((t2s, web))
}

pub(in crate::api) fn suggest_singbox_server_port() -> Result<u16> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_singbox_ports());
    next_free_port_from_used(1080, &used)
}

pub(in crate::api) fn next_free_port_simple(start: u16, used: &BTreeSet<u16>) -> u16 {
    let mut p = start;
    while used.contains(&p) && p < u16::MAX { p = p.saturating_add(1); }
    p
}

pub(in crate::api) fn create_singbox_profile_named(requested: &str) -> Result<String> {
    create_profile_named_generic(requested, &singbox_profile_create_spec())
}

pub(in crate::api) fn create_singbox_profile_next() -> Result<String> {
    create_profile_next_generic(&singbox_profile_create_spec())
}

pub(in crate::api) fn create_singbox_server_named(profile: &str, requested: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;
    ensure_singbox_profile_layout(profile)?;
    if singbox_profile_mode_is_vpn(profile) && !singbox_server_names(profile)?.is_empty() {
        anyhow::bail!("singbox_vpn_requires_single_server: VPN-режим sing-box поддерживает только один сервер. Переключите профиль в proxy или используйте существующий сервер.");
    }

    let root = singbox_server_root(profile, name);
    if root.exists() {
        anyhow::bail!("server already exists");
    }
    fs::create_dir_all(root.join("log"))?;
    let cfg = root.join("config.json");
    if !cfg.exists() {
        write_text_atomic(&cfg, "")?;
    }
    let log = root.join("log/sing-box.log");
    if !log.exists() {
        write_text_atomic(&log, "")?;
    }
    let setting = root.join("setting.json");
    if !setting.exists() {
        let port = suggest_singbox_server_port()?;
        write_json_pretty(&setting, &default_singbox_server_setting_value(port))?;
    }
    Ok(name.to_string())
}

pub(in crate::api) fn create_singbox_server_next(profile: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let root = singbox_profile_root(profile).join("server");
    let next = next_prefixed_child_dir(&root, "server")?;
    create_singbox_server_named(profile, &next)?;
    Ok(next)
}

pub(in crate::api) fn singbox_profile_setting_or_default(profile: &str) -> serde_json::Value {
    let p = singbox_profile_root(profile).join("setting.json");
    read_json::<serde_json::Value>(&p).unwrap_or_else(|_| default_singbox_profile_setting_value(12345, 8001))
}

pub(in crate::api) fn singbox_profile_mode_is_vpn(profile: &str) -> bool {
    let v = singbox_profile_setting_or_default(profile);
    crate::programs::singbox::normalize_setting_value(v)
        .map(|setting| setting.mode.is_vpn())
        .unwrap_or(false)
}

pub(in crate::api) fn singbox_server_names(profile: &str) -> Result<Vec<String>> {
    list_validated_child_dirs(
        &singbox_profile_root(profile).join("server"),
        ensure_valid_singbox_profile_name,
        true,
    )
}

pub(in crate::api) fn ensure_singbox_vpn_single_server(profile: &str) -> Result<String> {
    let names = singbox_server_names(profile)?;
    if names.len() != 1 {
        anyhow::bail!("singbox_vpn_requires_single_server: VPN-режим sing-box поддерживает только один сервер. Удалите лишние серверы или оставьте режим proxy.");
    }
    Ok(names[0].clone())
}

pub(in crate::api) fn normalize_and_write_singbox_profile_setting(profile: &str, v: serde_json::Value) -> Result<serde_json::Value> {
    let setting = crate::programs::singbox::normalize_setting_value(v)?;
    if setting.mode.is_vpn() && singbox_server_names(profile)?.len() > 1 {
        anyhow::bail!("singbox_vpn_requires_single_server: VPN-режим sing-box поддерживает только один сервер. Удалите лишние серверы или оставьте режим proxy.");
    }
    let normalized = serde_json::to_value(&setting)?;
    let p = singbox_profile_root(profile).join("setting.json");
    write_json_pretty(&p, &normalized)?;
    Ok(normalized)
}


pub(in crate::api) fn singbox_lx_active_path() -> PathBuf {
    program_root("singbox-lx").join("active.json")
}

pub(in crate::api) fn singbox_lx_profiles_root() -> PathBuf {
    program_root("singbox-lx").join("profile")
}

pub(in crate::api) fn singbox_lx_deleted_root() -> PathBuf {
    program_root("singbox-lx").join(".deleted")
}

pub(in crate::api) fn singbox_lx_deleted_profiles_root() -> PathBuf {
    singbox_lx_deleted_root().join("profiles")
}

pub(in crate::api) fn singbox_lx_deleted_servers_root(profile: &str) -> PathBuf {
    singbox_lx_deleted_root().join("servers").join(profile)
}

pub(in crate::api) fn singbox_lx_profile_root(profile: &str) -> PathBuf {
    singbox_lx_profiles_root().join(profile)
}

pub(in crate::api) fn singbox_lx_server_root(profile: &str, server: &str) -> PathBuf {
    singbox_lx_profile_root(profile).join("server").join(server)
}

pub(in crate::api) fn default_singbox_lx_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({
        "mode": "t2s",
        "t2s_port": t2s_port,
        "t2s_web_port": t2s_web_port,
        "tun": "sblxtun0",
        "dns": ["8.8.8.8"],
        "tun2socks_loglevel": "info",
        "proto_mode": "tcp_udp",
        "endpoint_resolve": true
    })
}

pub(in crate::api) fn default_singbox_lx_server_setting_value(port: u16) -> serde_json::Value {
    json!({"enabled": false, "port": port})
}

pub(in crate::api) fn ensure_singbox_lx_profile_layout(profile: &str) -> Result<()> {
    let root = singbox_lx_profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("server"))?;
    for rel in ["app/uid/user_program", "app/out/user_program", "log/t2s.log", "log/tun2socks.log"] {
        let p = root.join(rel);
        if !p.exists() { write_text_atomic(&p, "")?; }
    }
    Ok(())
}

pub(in crate::api) fn singbox_lx_profile_create_spec() -> ProfileCreateSpec {
    ProfileCreateSpec {
        validate_name: ensure_valid_singbox_profile_name,
        active_path: singbox_lx_active_path,
        ensure_layout: ensure_singbox_lx_profile_layout,
        profile_root: singbox_lx_profile_root,
        suggest_ports: suggest_singbox_lx_profile_ports,
        default_setting: default_singbox_lx_profile_setting_value,
    }
}

pub(in crate::api) fn collect_existing_singbox_lx_ports() -> BTreeSet<u16> {
    ProfilePortScan {
        profiles_root: singbox_lx_profiles_root(),
        mode_is_vpn: Some(singbox_raw_mode_is_vpn),
        skip_hidden: true,
        server_port: ServerPortSource::SettingKey("port"),
        exclude_profile: None,
        exclude_server: None,
    }.collect()
}

pub(in crate::api) fn suggest_singbox_lx_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_singbox_lx_ports());
    let t2s = next_free_port_from_used(12355, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8011, &used)?;
    Ok((t2s, web))
}

pub(in crate::api) fn suggest_singbox_lx_server_port() -> Result<u16> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_singbox_lx_ports());
    next_free_port_from_used(1180, &used)
}

pub(in crate::api) fn create_singbox_lx_profile_named(requested: &str) -> Result<String> {
    create_profile_named_generic(requested, &singbox_lx_profile_create_spec())
}

pub(in crate::api) fn create_singbox_lx_profile_next() -> Result<String> {
    create_profile_next_generic(&singbox_lx_profile_create_spec())
}

pub(in crate::api) fn create_singbox_lx_server_named(profile: &str, requested: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;
    ensure_singbox_lx_profile_layout(profile)?;
    if singbox_lx_profile_mode_is_vpn(profile) && !singbox_lx_server_names(profile)?.is_empty() {
        anyhow::bail!("singbox_lx_vpn_requires_single_server: VPN mode supports only one server");
    }
    let root = singbox_lx_server_root(profile, name);
    if root.exists() { anyhow::bail!("server already exists"); }
    fs::create_dir_all(root.join("log"))?;
    write_text_atomic(&root.join("config.json"), "")?;
    write_text_atomic(&root.join("log/sing-box.log"), "")?;
    let port = suggest_singbox_lx_server_port()?;
    write_json_pretty(&root.join("setting.json"), &default_singbox_lx_server_setting_value(port))?;
    Ok(name.to_string())
}

pub(in crate::api) fn create_singbox_lx_server_next(profile: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let root = singbox_lx_profile_root(profile).join("server");
    let next = next_prefixed_child_dir(&root, "server")?;
    create_singbox_lx_server_named(profile, &next)?;
    Ok(next)
}

pub(in crate::api) fn singbox_lx_profile_setting_or_default(profile: &str) -> serde_json::Value {
    read_json::<serde_json::Value>(&singbox_lx_profile_root(profile).join("setting.json"))
        .unwrap_or_else(|_| default_singbox_lx_profile_setting_value(12355, 8011))
}

pub(in crate::api) fn singbox_lx_profile_mode_is_vpn(profile: &str) -> bool {
    crate::programs::singbox::lx::normalize_setting_value(singbox_lx_profile_setting_or_default(profile))
        .map(|setting| setting.mode.is_vpn())
        .unwrap_or(false)
}

pub(in crate::api) fn singbox_lx_server_names(profile: &str) -> Result<Vec<String>> {
    list_validated_child_dirs(
        &singbox_lx_profile_root(profile).join("server"),
        ensure_valid_singbox_profile_name,
        true,
    )
}

pub(in crate::api) fn ensure_singbox_lx_vpn_single_server(profile: &str) -> Result<String> {
    let names = singbox_lx_server_names(profile)?;
    if names.len() != 1 {
        anyhow::bail!("singbox_lx_vpn_requires_single_server: VPN mode supports only one server");
    }
    Ok(names[0].clone())
}

pub(in crate::api) fn normalize_and_write_singbox_lx_profile_setting(profile: &str, v: serde_json::Value) -> Result<serde_json::Value> {
    let setting = crate::programs::singbox::lx::normalize_setting_value(v)?;
    if setting.mode.is_vpn() && singbox_lx_server_names(profile)?.len() > 1 {
        anyhow::bail!("singbox_lx_vpn_requires_single_server: VPN mode supports only one server");
    }
    let normalized = serde_json::to_value(&setting)?;
    write_json_pretty(&singbox_lx_profile_root(profile).join("setting.json"), &normalized)?;
    Ok(normalized)
}

pub(in crate::api) fn normalize_myproxy_proxy_json(v: serde_json::Value) -> Result<serde_json::Value> {
    let proxy: crate::programs::myproxy::ProxyConfig = serde_json::from_value(v)
        .map_err(|e| anyhow::anyhow!("bad proxy.json: {e}"))?;
    let proxy = crate::programs::myproxy::normalize_proxy_config_defaults(proxy)?;
    Ok(serde_json::to_value(&proxy)?)
}





pub(in crate::api) fn hysteria2_active_path() -> PathBuf { crate::programs::hysteria2::active_path() }
pub(in crate::api) fn hysteria2_profiles_root() -> PathBuf { crate::programs::hysteria2::profiles_root() }
pub(in crate::api) fn hysteria2_deleted_root() -> PathBuf { program_root("hysteria2").join(".deleted") }
pub(in crate::api) fn hysteria2_deleted_profiles_root() -> PathBuf { hysteria2_deleted_root().join("profiles") }
pub(in crate::api) fn hysteria2_deleted_servers_root(profile: &str) -> PathBuf { hysteria2_deleted_root().join("servers").join(profile) }
pub(in crate::api) fn hysteria2_profile_root(profile: &str) -> PathBuf { crate::programs::hysteria2::profile_root(profile) }
pub(in crate::api) fn hysteria2_server_root(profile: &str, server: &str) -> PathBuf { crate::programs::hysteria2::server_root(profile, server) }

pub(in crate::api) fn default_hysteria2_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({"mode":"t2s","t2s_port":t2s_port,"t2s_web_port":t2s_web_port,"tun":"hytun0","dns":["8.8.8.8"],"tun2socks_loglevel":"info","proto_mode":"tcp_udp"})
}
pub(in crate::api) fn default_hysteria2_server_setting_value(port: u16) -> serde_json::Value {
    json!({"enabled": false, "socks5_port": port, "log_level": "info"})
}
pub(in crate::api) fn normalize_hysteria2_log_level(raw: &str) -> &'static str {
    match raw.trim().to_ascii_lowercase().as_str() {
        "trace" => "trace",
        "debug" => "debug",
        "info" => "info",
        "warn" => "warn",
        "error" => "error",
        "silent" => "silent",
        _ => "info",
    }
}
pub(in crate::api) fn ensure_hysteria2_profile_layout(profile: &str) -> Result<()> { crate::programs::hysteria2::ensure_profile_layout(profile) }
pub(in crate::api) fn hysteria2_profile_mode_is_vpn(profile: &str) -> bool {
    let p = hysteria2_profile_root(profile).join("setting.json");
    let v: serde_json::Value = read_json(&p).unwrap_or_else(|_| default_hysteria2_profile_setting_value(12590, 8059));
    crate::programs::hysteria2::normalize_setting_value(v).map(|s| s.mode.is_vpn()).unwrap_or(false)
}
pub(in crate::api) fn collect_existing_hysteria2_ports() -> BTreeSet<u16> {
    ProfilePortScan {
        profiles_root: hysteria2_profiles_root(),
        mode_is_vpn: Some(hysteria2_raw_mode_is_vpn),
        // Kept as before: this variant did not filter '.'-prefixed directories.
        skip_hidden: false,
        server_port: ServerPortSource::SettingKey("socks5_port"),
        exclude_profile: None,
        exclude_server: None,
    }
    .collect()
}

pub(in crate::api) fn collect_existing_hysteria2_ports_excluding(current_profile: Option<&str>, current_server: Option<&str>) -> BTreeSet<u16> {
    ProfilePortScan {
        profiles_root: hysteria2_profiles_root(),
        mode_is_vpn: Some(hysteria2_raw_mode_is_vpn),
        skip_hidden: true,
        server_port: ServerPortSource::SettingKey("socks5_port"),
        exclude_profile: current_profile.map(|s| s.to_string()),
        exclude_server: current_server.map(|s| s.to_string()),
    }
    .collect()
}

pub(in crate::api) fn ensure_hysteria2_port_free(port: u16, current_profile: Option<&str>, current_server: Option<&str>, label: &str) -> Result<()> {
    if port == 0 { anyhow::bail!("invalid port"); }
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_hysteria2().unwrap_or_default();
    used.extend(collect_existing_hysteria2_ports_excluding(current_profile, current_server));
    if used.contains(&port) {
        anyhow::bail!("hysteria2_port_conflict: {label} port {port} уже занят");
    }
    Ok(())
}

pub(in crate::api) fn hysteria2_enabled_server_count(profile: &str) -> Result<usize> {
    let mut count = 0usize;
    for name in hysteria2_server_names(profile)? {
        let v: serde_json::Value = read_json(&hysteria2_server_root(profile, &name).join("setting.json")).unwrap_or_else(|_| default_hysteria2_server_setting_value(11590));
        if crate::common::fs::json::json_enabled(v.get("enabled")) {
            count += 1;
        }
    }
    Ok(count)
}

pub(in crate::api) fn suggest_hysteria2_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_hysteria2().unwrap_or_default();
    used.extend(collect_existing_hysteria2_ports());
    let t2s = next_free_port_simple(12590, &used); used.insert(t2s);
    let web = next_free_port_simple(8059, &used);
    Ok((t2s, web))
}
pub(in crate::api) fn suggest_hysteria2_server_port() -> Result<u16> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_hysteria2().unwrap_or_default();
    used.extend(collect_existing_hysteria2_ports());
    Ok(next_free_port_simple(11590, &used))
}
pub(in crate::api) fn create_hysteria2_profile_named(requested: &str) -> Result<String> {
    let name = requested.trim(); crate::programs::hysteria2::ensure_valid_profile_name(name)?; crate::programs::hysteria2::ensure_root_layout()?;
    let p = hysteria2_active_path(); let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
    if active.profiles.contains_key(name) { anyhow::bail!("profile already exists"); }
    active.profiles.insert(name.to_string(), ProfileState { enabled: false }); write_json_pretty(&p, &active)?; ensure_hysteria2_profile_layout(name)?;
    let (a,b)=suggest_hysteria2_profile_ports()?; write_json_pretty(&hysteria2_profile_root(name).join("setting.json"), &default_hysteria2_profile_setting_value(a,b))?;
    Ok(name.to_string())
}
pub(in crate::api) fn create_hysteria2_profile_next() -> Result<String> { let active: ProfilesActive = read_json(&hysteria2_active_path()).unwrap_or_default(); for i in 1..1000 { let n=i.to_string(); if !active.profiles.contains_key(&n) { return create_hysteria2_profile_named(&n); } } anyhow::bail!("no free hysteria2 profile name") }
pub(in crate::api) fn hysteria2_server_names(profile: &str) -> Result<Vec<String>> {
    let mut out=Vec::new(); let root=hysteria2_profile_root(profile).join("server");
    if let Ok(rd)=fs::read_dir(root) { for ent in rd.flatten() { let p=ent.path(); if p.is_dir() { if let Some(n)=p.file_name().and_then(|s| s.to_str()) { if !n.starts_with('.') { crate::programs::hysteria2::ensure_valid_profile_name(n)?; out.push(n.to_string()); } } } } }
    out.sort(); Ok(out)
}
pub(in crate::api) fn create_hysteria2_server_named(profile: &str, requested: &str) -> Result<String> {
    crate::programs::hysteria2::ensure_valid_profile_name(profile)?; let name=requested.trim(); crate::programs::hysteria2::ensure_valid_profile_name(name)?; ensure_hysteria2_profile_layout(profile)?;
    if hysteria2_profile_mode_is_vpn(profile) && !hysteria2_server_names(profile)?.is_empty() { anyhow::bail!("hysteria2_vpn_requires_single_server: VPN-режим hysteria2 поддерживает только один сервер."); }
    let root=hysteria2_server_root(profile,name); if root.exists() { anyhow::bail!("server already exists"); }
    fs::create_dir_all(root.join("log"))?; write_text_atomic(&root.join("config.json"), "")?; let port=suggest_hysteria2_server_port()?; write_json_pretty(&root.join("setting.json"), &default_hysteria2_server_setting_value(port))?; Ok(name.to_string())
}
pub(in crate::api) fn create_hysteria2_server_next(profile: &str) -> Result<String> { let names=hysteria2_server_names(profile)?; for i in 1..1000 { let n=i.to_string(); if !names.contains(&n) { return create_hysteria2_server_named(profile,&n); } } anyhow::bail!("no free hysteria2 server name") }
pub(in crate::api) fn normalize_and_write_hysteria2_profile_setting(profile: &str, v: serde_json::Value) -> Result<serde_json::Value> {
    let setting = crate::programs::hysteria2::normalize_setting_value(v)?;
    if setting.mode.is_vpn() {
        if hysteria2_server_names(profile)?.len() != 1 || hysteria2_enabled_server_count(profile)? != 1 {
            anyhow::bail!("hysteria2_vpn_requires_single_server: VPN-режим hysteria2 поддерживает ровно один включённый сервер.");
        }
    } else {
        ensure_hysteria2_port_free(setting.t2s_port, Some(profile), None, "t2s")?;
        ensure_hysteria2_port_free(setting.t2s_web_port, Some(profile), None, "t2s web")?;
        if setting.t2s_port == setting.t2s_web_port { anyhow::bail!("hysteria2_port_conflict: t2s и t2s web должны отличаться"); }
    }
    let normalized=serde_json::to_value(&setting)?; write_json_pretty(&hysteria2_profile_root(profile).join("setting.json"), &normalized)?; Ok(normalized)
}

pub(in crate::api) fn wireproxy_active_path() -> PathBuf {
    program_root("wireproxy").join("active.json")
}

pub(in crate::api) fn wireproxy_profiles_root() -> PathBuf {
    program_root("wireproxy").join("profile")
}

pub(in crate::api) fn wireproxy_deleted_root() -> PathBuf {
    program_root("wireproxy").join(".deleted")
}

pub(in crate::api) fn wireproxy_deleted_profiles_root() -> PathBuf {
    wireproxy_deleted_root().join("profiles")
}

pub(in crate::api) fn wireproxy_deleted_servers_root(profile: &str) -> PathBuf {
    wireproxy_deleted_root().join("servers").join(profile)
}

pub(in crate::api) fn wireproxy_profile_root(profile: &str) -> PathBuf {
    wireproxy_profiles_root().join(profile)
}

pub(in crate::api) fn wireproxy_server_root(profile: &str, server: &str) -> PathBuf {
    wireproxy_profile_root(profile).join("server").join(server)
}

pub(in crate::api) fn default_wireproxy_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({
        "t2s_port": t2s_port,
        "t2s_web_port": t2s_web_port
    })
}

pub(in crate::api) fn default_wireproxy_server_setting_value() -> serde_json::Value {
    json!({
        "enabled": false
    })
}

pub(in crate::api) fn ensure_wireproxy_profile_layout(profile: &str) -> Result<()> {
    let root = wireproxy_profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("server"))?;

    let app_list = root.join("app/uid/user_program");
    if !app_list.exists() {
        write_text_atomic(&app_list, "")?;
    }
    let out_list = root.join("app/out/user_program");
    if !out_list.exists() {
        write_text_atomic(&out_list, "")?;
    }
    let t2s_log = root.join("log/t2s.log");
    if !t2s_log.exists() {
        write_text_atomic(&t2s_log, "")?;
    }
    Ok(())
}

pub(in crate::api) fn collect_existing_wireproxy_ports() -> BTreeSet<u16> {
    ProfilePortScan {
        profiles_root: wireproxy_profiles_root(),
        mode_is_vpn: None,
        skip_hidden: true,
        server_port: ServerPortSource::WireproxyConf,
        exclude_profile: None,
        exclude_server: None,
    }
    .collect()
}

pub(in crate::api) fn suggest_wireproxy_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_wireproxy_ports());
    let t2s = next_free_port_from_used(12345, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8001, &used)?;
    Ok((t2s, web))
}

pub(in crate::api) fn create_wireproxy_profile_named(requested: &str) -> Result<String> {
    create_profile_named_generic(requested, &wireproxy_profile_create_spec())
}

pub(in crate::api) fn create_wireproxy_profile_next() -> Result<String> {
    create_profile_next_generic(&wireproxy_profile_create_spec())
}

pub(in crate::api) fn create_wireproxy_server_named(profile: &str, requested: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;
    ensure_wireproxy_profile_layout(profile)?;

    let root = wireproxy_server_root(profile, name);
    if root.exists() {
        anyhow::bail!("server already exists");
    }
    fs::create_dir_all(root.join("log"))?;
    let cfg = root.join("config.conf");
    if !cfg.exists() {
        write_text_atomic(&cfg, "")?;
    }
    let log = root.join("log/wireproxy.log");
    if !log.exists() {
        write_text_atomic(&log, "")?;
    }
    let setting = root.join("setting.json");
    if !setting.exists() {
        write_json_pretty(&setting, &default_wireproxy_server_setting_value())?;
    }
    Ok(name.to_string())
}

pub(in crate::api) fn create_wireproxy_server_next(profile: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let root = wireproxy_profile_root(profile).join("server");
    let next = next_prefixed_child_dir(&root, "server")?;
    create_wireproxy_server_named(profile, &next)?;
    Ok(next)
}


pub(in crate::api) fn myproxy_active_path() -> PathBuf { program_root("myproxy").join("active.json") }
pub(in crate::api) fn myproxy_profiles_root() -> PathBuf { program_root("myproxy").join("profile") }
pub(in crate::api) fn myproxy_deleted_root() -> PathBuf { program_root("myproxy").join(".deleted") }
pub(in crate::api) fn myproxy_deleted_profiles_root() -> PathBuf { myproxy_deleted_root().join("profiles") }
pub(in crate::api) fn myproxy_profile_root(profile: &str) -> PathBuf { myproxy_profiles_root().join(profile) }

pub(in crate::api) fn default_myproxy_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({"t2s_port": t2s_port, "t2s_web_port": t2s_web_port})
}

pub(in crate::api) fn default_myproxy_proxy_value() -> serde_json::Value {
    json!({
        "host": "127.0.0.1",
        "port": 1080,
        "ports": [1080],
        "backend_mode": "balance",
        "backend_priority": "",
        "priority_speed_aware": false,
        "user": "",
        "pass": "",
        "wrapped_socks": { "host": "", "port": 0, "user": "", "pass": "" },
        "proto_mode": "tcp_udp"
    })
}

pub(in crate::api) fn ensure_myproxy_profile_layout(profile: &str) -> Result<()> {
    let root = myproxy_profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    let app_list = root.join("app/uid/user_program");
    if !app_list.exists() { write_text_atomic(&app_list, "")?; }
    let out_list = root.join("app/out/user_program");
    if !out_list.exists() { write_text_atomic(&out_list, "")?; }
    let t2s_log = root.join("log/t2s.log");
    if !t2s_log.exists() { write_text_atomic(&t2s_log, "")?; }
    let proxy = root.join("proxy.json");
    if !proxy.exists() { write_json_pretty(&proxy, &default_myproxy_proxy_value())?; }
    Ok(())
}

pub(in crate::api) fn collect_existing_myproxy_ports() -> BTreeSet<u16> {
    ProfilePortScan {
        profiles_root: myproxy_profiles_root(),
        mode_is_vpn: None,
        skip_hidden: true,
        server_port: ServerPortSource::None,
        exclude_profile: None,
        exclude_server: None,
    }
    .collect()
}

pub(in crate::api) fn suggest_myproxy_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_myproxy_ports());
    let t2s = next_free_port_from_used(12348, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8004, &used)?;
    Ok((t2s, web))
}

pub(in crate::api) fn create_myproxy_profile_named(requested: &str) -> Result<String> {
    create_profile_named_generic(requested, &myproxy_profile_create_spec())
}

pub(in crate::api) fn create_myproxy_profile_next() -> Result<String> {
    create_profile_next_generic(&myproxy_profile_create_spec())
}


pub(in crate::api) fn myprogram_active_path() -> PathBuf { program_root("myprogram").join("active.json") }
pub(in crate::api) fn myprogram_profiles_root() -> PathBuf { program_root("myprogram").join("profile") }
pub(in crate::api) fn myprogram_deleted_root() -> PathBuf { program_root("myprogram").join(".deleted") }
pub(in crate::api) fn myprogram_deleted_profiles_root() -> PathBuf { myprogram_deleted_root().join("profiles") }
pub(in crate::api) fn myprogram_profile_root(profile: &str) -> PathBuf { myprogram_profiles_root().join(profile) }

pub(in crate::api) fn default_myprogram_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({"apps_mode": false, "route_mode": "t2s", "proto_mode": "tcp", "transparent_port": 0, "t2s_port": t2s_port, "t2s_web_port": t2s_web_port, "socks_user": "", "socks_pass": ""})
}

pub(in crate::api) fn ensure_myprogram_profile_layout(profile: &str) -> Result<()> {
    crate::programs::myprogram::ensure_profile_layout(profile)
}

pub(in crate::api) fn collect_existing_myprogram_ports() -> BTreeSet<u16> {
    crate::programs::myprogram::collect_defined_ports_for_conflict_check()
}

pub(in crate::api) fn suggest_myprogram_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::programs::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_myprogram_ports());
    let t2s = next_free_port_from_used(12350, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8006, &used)?;
    Ok((t2s, web))
}

pub(in crate::api) fn myprogram_profile_create_spec() -> ProfileCreateSpec {
    ProfileCreateSpec {
        validate_name: ensure_valid_singbox_profile_name,
        active_path: myprogram_active_path,
        ensure_layout: ensure_myprogram_profile_layout,
        profile_root: myprogram_profile_root,
        suggest_ports: suggest_myprogram_profile_ports,
        default_setting: default_myprogram_profile_setting_value,
    }
}

pub(in crate::api) fn create_myprogram_profile_named(requested: &str) -> Result<String> {
    create_profile_named_generic(requested, &myprogram_profile_create_spec())
}

pub(in crate::api) fn create_myprogram_profile_next() -> Result<String> {
    create_profile_next_generic(&myprogram_profile_create_spec())
}

pub(in crate::api) fn openvpn_active_path() -> PathBuf { crate::programs::openvpn::active_path() }
pub(in crate::api) fn openvpn_profiles_root() -> PathBuf { crate::programs::openvpn::profiles_root() }
pub(in crate::api) fn openvpn_deleted_root() -> PathBuf { program_root("openvpn").join(".deleted") }
pub(in crate::api) fn openvpn_deleted_profiles_root() -> PathBuf { openvpn_deleted_root().join("profiles") }
pub(in crate::api) fn openvpn_profile_root(profile: &str) -> PathBuf { crate::programs::openvpn::profile_root(profile) }

pub(in crate::api) fn ensure_openvpn_profile_layout(profile: &str) -> Result<()> {
    crate::programs::openvpn::ensure_profile_layout(profile)
}

pub(in crate::api) fn standard_vpn_profile_create_spec(program: ProgramId) -> StandardProfileCreateSpec {
    match program {
        ProgramId::OpenVpn => StandardProfileCreateSpec {
            label: "openvpn",
            validate_name: crate::programs::openvpn::ensure_valid_profile_name,
            ensure_root: crate::programs::openvpn::ensure_root_layout,
            active_path: crate::programs::openvpn::active_path,
            ensure_layout: crate::programs::openvpn::ensure_profile_layout,
            after_create: None,
        },
        ProgramId::AmneziaWg => StandardProfileCreateSpec {
            label: "amneziawg",
            validate_name: crate::programs::amneziawg::ensure_valid_profile_name,
            ensure_root: crate::programs::amneziawg::ensure_root_layout,
            active_path: crate::programs::amneziawg::active_path,
            ensure_layout: crate::programs::amneziawg::ensure_profile_layout,
            after_create: None,
        },
        ProgramId::Tun2Socks => StandardProfileCreateSpec {
            label: "tun2socks",
            validate_name: crate::programs::tun2socks::ensure_valid_profile_name,
            ensure_root: crate::programs::tun2socks::ensure_root_layout,
            active_path: crate::programs::tun2socks::active_path,
            ensure_layout: crate::programs::tun2socks::ensure_profile_layout,
            after_create: None,
        },
        ProgramId::MyVpn => StandardProfileCreateSpec {
            label: "myvpn",
            validate_name: crate::programs::myvpn::ensure_valid_profile_name,
            ensure_root: crate::programs::myvpn::ensure_root_layout,
            active_path: crate::programs::myvpn::active_path,
            ensure_layout: crate::programs::myvpn::ensure_profile_layout,
            after_create: None,
        },
        ProgramId::Mihomo => StandardProfileCreateSpec {
            label: "mihomo",
            validate_name: crate::programs::mihomo::ensure_valid_profile_name,
            ensure_root: crate::programs::mihomo::ensure_root_layout,
            active_path: crate::programs::mihomo::active_path,
            ensure_layout: crate::programs::mihomo::ensure_profile_layout,
            after_create: Some(crate::programs::mihomo::assign_free_ports_for_profile),
        },
        ProgramId::Mieru => StandardProfileCreateSpec {
            label: "mieru",
            validate_name: crate::programs::mieru::ensure_valid_profile_name,
            ensure_root: crate::programs::mieru::ensure_root_layout,
            active_path: crate::programs::mieru::active_path,
            ensure_layout: crate::programs::mieru::ensure_profile_layout,
            after_create: Some(crate::programs::mieru::assign_free_ports_for_profile),
        },
        _ => unreachable!("standard VPN profile CRUD requested for unsupported program"),
    }
}

pub(in crate::api) fn create_openvpn_profile_named(requested: &str) -> Result<String> {
    create_standard_profile_named(requested, &standard_vpn_profile_create_spec(ProgramId::OpenVpn))
}

pub(in crate::api) fn create_openvpn_profile_next() -> Result<String> {
    create_standard_profile_next(&standard_vpn_profile_create_spec(ProgramId::OpenVpn))
}

pub(in crate::api) fn amneziawg_active_path() -> PathBuf { crate::programs::amneziawg::active_path() }
pub(in crate::api) fn amneziawg_profiles_root() -> PathBuf { crate::programs::amneziawg::profiles_root() }
pub(in crate::api) fn amneziawg_deleted_root() -> PathBuf { program_root("amneziawg").join(".deleted") }
pub(in crate::api) fn amneziawg_deleted_profiles_root() -> PathBuf { amneziawg_deleted_root().join("profiles") }
pub(in crate::api) fn amneziawg_profile_root(profile: &str) -> PathBuf { crate::programs::amneziawg::profile_root(profile) }

pub(in crate::api) fn ensure_amneziawg_profile_layout(profile: &str) -> Result<()> {
    crate::programs::amneziawg::ensure_profile_layout(profile)
}

pub(in crate::api) fn create_amneziawg_profile_named(requested: &str) -> Result<String> {
    create_standard_profile_named(requested, &standard_vpn_profile_create_spec(ProgramId::AmneziaWg))
}

pub(in crate::api) fn create_amneziawg_profile_next() -> Result<String> {
    create_standard_profile_next(&standard_vpn_profile_create_spec(ProgramId::AmneziaWg))
}

pub(in crate::api) fn tun2socks_active_path() -> PathBuf { crate::programs::tun2socks::active_path() }
pub(in crate::api) fn tun2socks_profiles_root() -> PathBuf { crate::programs::tun2socks::profiles_root() }
pub(in crate::api) fn tun2socks_deleted_root() -> PathBuf { program_root("tun2socks").join(".deleted") }
pub(in crate::api) fn tun2socks_deleted_profiles_root() -> PathBuf { tun2socks_deleted_root().join("profiles") }
pub(in crate::api) fn tun2socks_profile_root(profile: &str) -> PathBuf { crate::programs::tun2socks::profile_root(profile) }

pub(in crate::api) fn ensure_tun2socks_profile_layout(profile: &str) -> Result<()> {
    crate::programs::tun2socks::ensure_profile_layout(profile)
}

pub(in crate::api) fn create_tun2socks_profile_named(requested: &str) -> Result<String> {
    create_standard_profile_named(requested, &standard_vpn_profile_create_spec(ProgramId::Tun2Socks))
}

pub(in crate::api) fn create_tun2socks_profile_next() -> Result<String> {
    create_standard_profile_next(&standard_vpn_profile_create_spec(ProgramId::Tun2Socks))
}

pub(in crate::api) fn myvpn_active_path() -> PathBuf { crate::programs::myvpn::active_path() }
pub(in crate::api) fn myvpn_profiles_root() -> PathBuf { crate::programs::myvpn::profiles_root() }
pub(in crate::api) fn myvpn_deleted_root() -> PathBuf { program_root("myvpn").join(".deleted") }
pub(in crate::api) fn myvpn_deleted_profiles_root() -> PathBuf { myvpn_deleted_root().join("profiles") }
pub(in crate::api) fn myvpn_profile_root(profile: &str) -> PathBuf { crate::programs::myvpn::profile_root(profile) }

pub(in crate::api) fn ensure_myvpn_profile_layout(profile: &str) -> Result<()> {
    crate::programs::myvpn::ensure_profile_layout(profile)
}

pub(in crate::api) fn create_myvpn_profile_named(requested: &str) -> Result<String> {
    create_standard_profile_named(requested, &standard_vpn_profile_create_spec(ProgramId::MyVpn))
}

pub(in crate::api) fn create_myvpn_profile_next() -> Result<String> {
    create_standard_profile_next(&standard_vpn_profile_create_spec(ProgramId::MyVpn))
}

pub(in crate::api) fn mihomo_active_path() -> PathBuf { crate::programs::mihomo::active_path() }
pub(in crate::api) fn mihomo_profiles_root() -> PathBuf { crate::programs::mihomo::profiles_root() }
pub(in crate::api) fn mihomo_deleted_root() -> PathBuf { program_root("mihomo").join(".deleted") }
pub(in crate::api) fn mihomo_deleted_profiles_root() -> PathBuf { mihomo_deleted_root().join("profiles") }
pub(in crate::api) fn mihomo_profile_root(profile: &str) -> PathBuf { crate::programs::mihomo::profile_root(profile) }

pub(in crate::api) fn ensure_mihomo_profile_layout(profile: &str) -> Result<()> {
    crate::programs::mihomo::ensure_profile_layout(profile)
}

pub(in crate::api) fn create_mihomo_profile_named(requested: &str) -> Result<String> {
    create_standard_profile_named(requested, &standard_vpn_profile_create_spec(ProgramId::Mihomo))
}

pub(in crate::api) fn create_mihomo_profile_next() -> Result<String> {
    create_standard_profile_next(&standard_vpn_profile_create_spec(ProgramId::Mihomo))
}

pub(in crate::api) fn mieru_active_path() -> PathBuf { crate::programs::mieru::active_path() }
pub(in crate::api) fn mieru_profiles_root() -> PathBuf { crate::programs::mieru::profiles_root() }
pub(in crate::api) fn mieru_deleted_root() -> PathBuf { program_root("mieru").join(".deleted") }
pub(in crate::api) fn mieru_deleted_profiles_root() -> PathBuf { mieru_deleted_root().join("profiles") }
pub(in crate::api) fn mieru_profile_root(profile: &str) -> PathBuf { crate::programs::mieru::profile_root(profile) }

pub(in crate::api) fn ensure_mieru_profile_layout(profile: &str) -> Result<()> {
    crate::programs::mieru::ensure_profile_layout(profile)
}

pub(in crate::api) fn create_mieru_profile_named(requested: &str) -> Result<String> {
    create_standard_profile_named(requested, &standard_vpn_profile_create_spec(ProgramId::Mieru))
}

pub(in crate::api) fn create_mieru_profile_next() -> Result<String> {
    create_standard_profile_next(&standard_vpn_profile_create_spec(ProgramId::Mieru))
}

pub(in crate::api) fn validate_cross_vpn_tun_claim(program_id: &str, profile: &str, tun: &str) -> Result<()> {
    let this_label = format!("{program_id}/{profile}");
    for (other_label, other_tun) in crate::programs::openvpn::enabled_tun_claims()
        .into_iter()
        .chain(crate::programs::amneziawg::enabled_tun_claims().into_iter())
        .chain(crate::programs::tun2socks::enabled_tun_claims().into_iter())
        .chain(crate::programs::myvpn::enabled_tun_claims().into_iter())
        .chain(crate::programs::mihomo::enabled_tun_claims().into_iter())
        .chain(crate::programs::mieru::enabled_tun_claims().into_iter())
        .chain(crate::programs::singbox::enabled_tun_claims().into_iter())
        .chain(crate::programs::singbox::lx::enabled_tun_claims().into_iter())
        .chain(crate::programs::hysteria2::enabled_tun_claims().into_iter())
    {
        if other_label != this_label && other_tun == tun {
            anyhow::bail!("VPN tun conflict: tun {tun} is already used by {other_label}");
        }
    }
    Ok(())
}

pub(in crate::api) fn is_profile_enabled(active_path: &Path, profile: &str) -> bool {
    read_json::<ProfilesActive>(active_path)
        .map(|a| a.profiles.get(profile).map(|st| st.enabled).unwrap_or(false))
        .unwrap_or(false)
}

pub(in crate::api) fn create_named_profile(program_id: &str, requested: &str) -> Result<String> {
    ensure_safe_segment(program_id, "program id")?;
    if !matches!(program_id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
        anyhow::bail!("program has no profiles");
    }

    let name = normalize_profile_name(requested)?;
    let p = active_json_path(program_id);
    let mut active: ProfilesActive = read_json(&p).unwrap_or_default();

    if active.profiles.contains_key(&name) {
        anyhow::bail!("profile already exists");
    }

    active
        .profiles
        .insert(name.clone(), ProfileState { enabled: false });
    write_json_pretty(&p, &active)?;

    ensure_profile_layout(program_id, &name)?;
    write_default_port_json(program_id, &name)?;
    crate::programs::ports::normalize_ports()?;

    Ok(name)
}

pub(in crate::api) fn create_next_profile(program_id: &str) -> Result<String> {
    ensure_safe_segment(program_id, "program id")?;
    if !matches!(program_id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
        anyhow::bail!("program has no profiles");
    }

    let p = active_json_path(program_id);
    let mut active: ProfilesActive = read_json(&p).unwrap_or_default();

    // Next numeric name (1..N). Support both "1" and legacy "profile1" keys.
    let mut max_n = 0u32;
    let mut saw_profile_prefix = false;
    for k in active.profiles.keys() {
        if k.starts_with("profile") {
            saw_profile_prefix = true;
        }
        if let Ok(n) = k.parse::<u32>() {
            max_n = max_n.max(n);
            continue;
        }
        if let Some(rest) = k.strip_prefix("profile") {
            if let Ok(n) = rest.parse::<u32>() {
                max_n = max_n.max(n);
            }
        }
    }
    let next = if saw_profile_prefix {
        format!("profile{}", max_n + 1)
    } else {
        (max_n + 1).to_string()
    };

    active.profiles.insert(next.clone(), ProfileState { enabled: false });
    write_json_pretty(&p, &active)?;

    ensure_profile_layout(program_id, &next)?;
    write_default_port_json(program_id, &next)?;
    crate::programs::ports::normalize_ports()?;

    Ok(next)
}




#[allow(dead_code)]
pub(in crate::api) fn validate_sing_box_setting(v: &serde_json::Value) -> Result<()> {
    let profiles = v
        .get("profiles")
        .and_then(|x| x.as_array())
        .ok_or_else(|| anyhow::anyhow!("setting.json: profiles array is required"))?;

    let mut ports = std::collections::HashSet::new();
    for profile in profiles {
        let name = profile
            .get("name")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .trim();
        if name.is_empty() {
            anyhow::bail!("setting.json: profile name is empty");
        }
        let port = profile
            .get("port")
            .and_then(|x| x.as_i64())
            .ok_or_else(|| anyhow::anyhow!("setting.json: profile {name} is missing port"))?;
        if !(1..=65535).contains(&port) {
            anyhow::bail!("setting.json: invalid port {port} for profile {name}");
        }
        if !ports.insert(port) {
            anyhow::bail!("setting.json: duplicate profile port {port}");
        }
    }
    Ok(())
}

