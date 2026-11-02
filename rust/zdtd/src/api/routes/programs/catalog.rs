use anyhow::Result;
use serde_json::json;
use std::net::TcpStream;

use super::super::super::http::write_json;
use super::super::super::support::fs::read_json;
use super::super::super::support::model::{EnabledActive, ProfilesActive};
use super::shared::{
    active_json_path, amneziawg_active_path, hysteria2_active_path, mieru_active_path, mihomo_active_path, myprogram_active_path,
    myproxy_active_path, myvpn_active_path, openvpn_active_path, program_display_name, singbox_active_path, singbox_lx_active_path,
    tun2socks_active_path, wireproxy_active_path,
};

pub(in crate::api) fn handle_get_programs(stream: TcpStream) -> Result<()> {
    // Profile-based programs
    let profile_ids = ["nfqws", "nfqws2", "byedpi", "dpitunnel"];
    let mut out = Vec::new();

    for id in profile_ids {
        let p = active_json_path(id);
        let active: ProfilesActive = read_json(&p).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": id,
            "name": program_display_name(id),
            "type": "profiles",
            "profiles": profiles
        }));
    }

    // Single programs
    for id in ["dnscrypt", "operaproxy"] {
        let p = active_json_path(id);
        let active: EnabledActive = read_json(&p).unwrap_or_default();
        out.push(json!({
            "id": id,
            "name": program_display_name(id),
            "type": "single",
            "enabled": active.enabled
        }));
    }

    {
        let installed = crate::programs::tgwsproxy::is_installed();
        let enabled = installed && crate::programs::tgwsproxy::load_effective_enabled().map(|v| v.enabled).unwrap_or(false);
        out.push(json!({
            "id": "tgwsproxy",
            "name": program_display_name("tgwsproxy"),
            "type": "single",
            "enabled": enabled
        }));
    }

    {
        let enabled = crate::programs::tor::load_enabled_json().map(|v| v.is_enabled()).unwrap_or(false);
        out.push(json!({
            "id": "tor",
            "name": "tor",
            "type": "single",
            "enabled": enabled
        }));
    }

    // sing-box (profile-based, socks5-only)
    {
        let active: ProfilesActive = read_json(&singbox_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "sing-box",
            "name": "sing-box",
            "type": "singbox_profiles",
            "profiles": profiles
        }));
    }

    {
        let active: ProfilesActive = read_json(&singbox_lx_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "sing-box-lx",
            "name": "sing-box-lx",
            "type": "singbox_lx_profiles",
            "profiles": profiles
        }));
    }

    // hysteria2 (profile-based, SOCKS5 backend)
    {
        let active: ProfilesActive = read_json(&hysteria2_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles { profiles.push(json!({"name": name, "enabled": st.enabled})); }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({"id": "hysteria2", "name": "hysteria2", "type": "hysteria2_profiles", "profiles": profiles}));
    }

    // wireproxy (profile-based, socks5-only)
    {
        let active: ProfilesActive = read_json(&wireproxy_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "wireproxy",
            "name": "wireproxy",
            "type": "wireproxy_profiles",
            "profiles": profiles
        }));
    }

    // myproxy (profile-based, single socks5 upstream per profile)
    {
        let active: ProfilesActive = read_json(&myproxy_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "myproxy",
            "name": "myproxy",
            "type": "myproxy_profiles",
            "profiles": profiles
        }));
    }

    // myprogram (custom launched program profiles)
    {
        let active: ProfilesActive = read_json(&myprogram_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "myprogram",
            "name": "myprogram",
            "type": "myprogram_profiles",
            "profiles": profiles
        }));
    }

    // openvpn (VPN/netd profiles)
    {
        let active: ProfilesActive = read_json(&openvpn_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "openvpn",
            "name": "openvpn",
            "type": "openvpn_profiles",
            "profiles": profiles
        }));
    }

    // amneziawg (AmneziaWG userspace + VPN/netd profiles)
    {
        let active: ProfilesActive = read_json(&amneziawg_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "amneziawg",
            "name": "amneziawg",
            "type": "amneziawg_profiles",
            "profiles": profiles
        }));
    }

    // tun2socks (VPN/netd profiles)
    {
        let active: ProfilesActive = read_json(&tun2socks_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "tun2socks",
            "name": "tun2socks",
            "type": "tun2socks_profiles",
            "profiles": profiles
        }));
    }

    // myvpn (universal VPN/netd binder profiles)
    {
        let active: ProfilesActive = read_json(&myvpn_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "myvpn",
            "name": "myvpn",
            "type": "myvpn_profiles",
            "profiles": profiles
        }));
    }

    // mihomo (Mihomo + tun2socks + VPN/netd profiles)
    {
        let active: ProfilesActive = read_json(&mihomo_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "mihomo",
            "name": "mihomo",
            "type": "mihomo_profiles",
            "profiles": profiles
        }));
    }

    // mieru (mieru SOCKS5 backend + tun2proxy/tun2socks + VPN/netd profiles)
    {
        let active: ProfilesActive = read_json(&mieru_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "mieru",
            "name": "mieru",
            "type": "mieru_profiles",
            "profiles": profiles
        }));
    }

    // Component-bound tools are exposed only when their complete executable set exists.
    // Core/internal programs are not present in components.rs and therefore remain visible.
    out.retain(|program| {
        program["id"]
            .as_str()
            .map(crate::programs::components::is_program_installed)
            .unwrap_or(true)
    });

    write_json(stream, 200, json!({"ok": true, "data": out}))
}

