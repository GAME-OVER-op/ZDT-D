use anyhow::Result;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{collections::BTreeSet, fs, net::TcpStream, path::{Path, PathBuf}, time::Duration};

use crate::settings;
use super::super::http::{write_empty_404, write_err, write_json};
use super::super::support::fs::{read_json, read_text_or_empty};
use super::super::support::model::{EnabledActive, ProfilesActive};
use super::super::support::paths::ensure_safe_segment;
use super::programs::shared::{hysteria2_active_path, is_profile_enabled, mieru_active_path, mihomo_active_path, myprogram_active_path, myproxy_active_path, program_root, singbox_active_path, singbox_lx_active_path, wireproxy_active_path};

#[allow(dead_code)]
const CONSTRUCTOR_TRIGGER_PACKAGE: &str = "com.android.zdtd.service";

#[derive(Debug, Clone, Serialize)]
struct ConstructionProxyEndpointCandidate {
    key: String,
    program_id: String,
    profile: Option<String>,
    server: Option<String>,
    slot: String,
    host: String,
    port: u16,
    label: String,
    kind: String,
    enabled: bool,
    running: bool,
    app_list_path: Option<String>,
}


#[derive(Debug, Deserialize)]
struct ConstructionReleaseEndpointReq {
    program_id: String,
    #[serde(default)]
    profile: Option<String>,
    #[serde(default)]
    server: Option<String>,
    #[serde(default)]
    #[allow(dead_code)]
    slot: Option<String>,
    #[serde(default)]
    port: Option<u16>,
}

fn working_root() -> PathBuf {
    crate::programs::registry::working_root()
}

pub(in crate::api) fn handle_construction_subroutes(stream: TcpStream, method: &str, path: &str, body: &[u8], _services_running: bool) -> Result<()> {
    match (method, path) {
        ("GET", "/api/construction/proxy-endpoints") => {
            let res = collect_construction_proxy_endpoint_candidates();
            match res {
                Ok(candidates) => write_json(stream, 200, json!({"ok": true, "candidates": candidates})),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", "/api/construction/proxy-endpoints/release") => {
            let res = (|| -> Result<serde_json::Value> {
                let req: ConstructionReleaseEndpointReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                release_construction_proxy_endpoint(req)
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        _ => write_empty_404(stream),
    }
}

fn collect_construction_proxy_endpoint_candidates() -> Result<Vec<ConstructionProxyEndpointCandidate>> {
    let root = working_root();
    let mut out = Vec::<ConstructionProxyEndpointCandidate>::new();
    collect_construction_singbox_candidates(&root, &mut out);
    if crate::programs::singbox::lx::is_installed() {
        collect_construction_singbox_lx_candidates(&root, &mut out);
    }
    collect_construction_wireproxy_candidates(&root, &mut out);
    collect_construction_myproxy_candidates(&root, &mut out);
    collect_construction_profile_setting_candidate(&root, "mihomo", "mixed_port", "mixed", &mihomo_active_path(), &mut out);
    collect_construction_profile_setting_candidate(&root, "mieru", "socks5_port", "socks5", &mieru_active_path(), &mut out);
    collect_construction_hysteria2_candidates(&root, &mut out);
    collect_construction_myprogram_candidates(&root, &mut out);
    collect_construction_tor_candidate(&root, &mut out);
    collect_construction_operaproxy_candidates(&root, &mut out);
    collect_construction_t2s_candidates(&mut out);

    out.sort_by(|a, b| {
        a.program_id.cmp(&b.program_id)
            .then(a.profile.cmp(&b.profile))
            .then(a.server.cmp(&b.server))
            .then(a.port.cmp(&b.port))
    });
    out.dedup_by(|a, b| a.program_id == b.program_id && a.profile == b.profile && a.server == b.server && a.port == b.port);
    Ok(out)
}

fn collect_construction_t2s_candidates(out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let dir = Path::new("/data/adb/modules/ZDT-D/api/t2s/instances");
    let Ok(entries) = fs::read_dir(dir) else { return; };
    for ent in entries.flatten() {
        let path = ent.path();
        if path.extension().and_then(|s| s.to_str()) != Some("json") { continue; }
        let Ok(raw) = fs::read_to_string(&path) else { continue; };
        let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { continue; };
        let port = v.get("listen_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
        if port == 0 { continue; }
        let program = v.get("program").and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
        let profile = v.get("profile").and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
        let web_port = v.get("web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
        let label_parts = ["t2s".to_string(), program.clone(), profile.clone()]
            .into_iter()
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>();
        let label_base = if label_parts.is_empty() { "t2s".to_string() } else { label_parts.join(" / ") };
        out.push(ConstructionProxyEndpointCandidate {
            key: format!("t2s:{}:{}:{}", web_port, profile, port),
            program_id: "t2s".to_string(),
            profile: if profile.is_empty() { None } else { Some(profile) },
            server: if program.is_empty() { None } else { Some(program) },
            slot: "common".to_string(),
            host: "127.0.0.1".to_string(),
            port,
            label: format!("{} :{}", label_base, port),
            kind: "t2s".to_string(),
            enabled: true,
            running: tcp_port_open("127.0.0.1", port),
            app_list_path: None,
        });
    }
}

fn push_construction_candidate(
    out: &mut Vec<ConstructionProxyEndpointCandidate>,
    program_id: &str,
    profile: Option<String>,
    server: Option<String>,
    port: u16,
    kind: &str,
    enabled: bool,
    app_list_path: Option<PathBuf>,
) {
    if port == 0 { return; }
    let app_api_path = app_list_path.map(|_| app_path_to_api_path(program_id, profile.as_deref(), "common"));
    let label = [Some(program_id.to_string()), profile.clone(), server.clone()]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(" / ");
    let key = format!("{}:{}:{}:{}", program_id, profile.clone().unwrap_or_default(), server.clone().unwrap_or_default(), port);
    out.push(ConstructionProxyEndpointCandidate {
        key,
        program_id: program_id.to_string(),
        profile,
        server,
        slot: "common".to_string(),
        host: "127.0.0.1".to_string(),
        port,
        label: if label.is_empty() { format!("127.0.0.1:{port}") } else { format!("{label} :{port}") },
        kind: kind.to_string(),
        enabled,
        running: tcp_port_open("127.0.0.1", port),
        app_list_path: app_api_path,
    });
}

fn collect_construction_singbox_candidates(root: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let active: ProfilesActive = read_json(&singbox_active_path()).unwrap_or_default();
    let profiles_root = root.join("singbox/profile");
    let Ok(profiles) = fs::read_dir(&profiles_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
        let profile_enabled = active.profiles.get(&profile).map(|st| st.enabled).unwrap_or(false);
        let server_root = profile_dir.join("server");
        let Ok(servers) = fs::read_dir(&server_root) else { continue; };
        for server_ent in servers.flatten() {
            let server_dir = server_ent.path();
            if !server_dir.is_dir() { continue; }
            let Some(server) = server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            let setting: serde_json::Value = read_json(&server_dir.join("setting.json")).unwrap_or_else(|_| json!({}));
            let port = setting.get("port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
            let server_enabled = crate::common::fs::json::json_enabled(setting.get("enabled"));
            push_construction_candidate(out, "sing-box", Some(profile.clone()), Some(server), port, "socks5", profile_enabled && server_enabled, Some(profile_dir.join("app/uid/user_program")));
        }
    }
}
fn collect_construction_singbox_lx_candidates(root: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let active: ProfilesActive = read_json(&singbox_lx_active_path()).unwrap_or_default();
    let profiles_root = root.join("singbox-lx/profile");
    let Ok(profiles) = fs::read_dir(&profiles_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
        let profile_enabled = active.profiles.get(&profile).map(|st| st.enabled).unwrap_or(false);
        let server_root = profile_dir.join("server");
        let Ok(servers) = fs::read_dir(&server_root) else { continue; };
        for server_ent in servers.flatten() {
            let server_dir = server_ent.path();
            if !server_dir.is_dir() { continue; }
            let Some(server) = server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            let setting: serde_json::Value = read_json(&server_dir.join("setting.json")).unwrap_or_else(|_| json!({}));
            let port = setting.get("port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
            let server_enabled = crate::common::fs::json::json_enabled(setting.get("enabled"));
            push_construction_candidate(out, "sing-box-lx", Some(profile.clone()), Some(server), port, "socks5", profile_enabled && server_enabled, Some(profile_dir.join("app/uid/user_program")));
        }
    }
}

fn collect_construction_wireproxy_candidates(root: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let active: ProfilesActive = read_json(&wireproxy_active_path()).unwrap_or_default();
    let profiles_root = root.join("wireproxy/profile");
    let Ok(profiles) = fs::read_dir(&profiles_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
        let profile_enabled = active.profiles.get(&profile).map(|st| st.enabled).unwrap_or(false);
        let server_root = profile_dir.join("server");
        let Ok(servers) = fs::read_dir(&server_root) else { continue; };
        for server_ent in servers.flatten() {
            let server_dir = server_ent.path();
            if !server_dir.is_dir() { continue; }
            let Some(server) = server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            let port = read_text_or_empty(&server_dir.join("config.conf")).ok().and_then(|raw| parse_wireproxy_bind_port_for_construction(&raw)).unwrap_or(0);
            let setting: serde_json::Value = read_json(&server_dir.join("setting.json")).unwrap_or_else(|_| json!({}));
            let server_enabled = crate::common::fs::json::json_enabled(setting.get("enabled"));
            push_construction_candidate(out, "wireproxy", Some(profile.clone()), Some(server), port, "socks5", profile_enabled && server_enabled, Some(profile_dir.join("app/uid/user_program")));
        }
    }
}

fn collect_construction_profile_setting_candidate(root: &Path, program_id: &str, port_key: &str, kind: &str, active_path: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let active: ProfilesActive = read_json(active_path).unwrap_or_default();
    let profiles_root = root.join(program_id).join("profile");
    let Ok(profiles) = fs::read_dir(&profiles_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
        let setting: serde_json::Value = read_json(&profile_dir.join("setting.json")).unwrap_or_else(|_| json!({}));
        let port = setting.get(port_key).and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
        let enabled = active.profiles.get(&profile).map(|st| st.enabled).unwrap_or(false);
        push_construction_candidate(out, program_id, Some(profile.clone()), Some(port_key.to_string()), port, kind, enabled, Some(profile_dir.join("app/uid/user_program")));
    }
}


fn collect_construction_myproxy_candidates(root: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let active: ProfilesActive = read_json(&myproxy_active_path()).unwrap_or_default();
    let profiles_root = root.join("myproxy/profile");
    let Ok(profiles) = fs::read_dir(&profiles_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
        let enabled = active.profiles.get(&profile).map(|st| st.enabled).unwrap_or(false);
        let proxy: serde_json::Value = read_json(&profile_dir.join("proxy.json")).unwrap_or_else(|_| json!({}));
        for port in parse_myproxy_ports_for_construction(&proxy) {
            // myproxy upstreams are local SOCKS candidates, but myproxy cannot start the upstream server itself.
            // If the same port belongs to a real project endpoint, the Android picker prefers that real endpoint.
            push_construction_candidate(out, "myproxy", Some(profile.clone()), Some("upstream".to_string()), port, "socks5", enabled, Some(profile_dir.join("app/uid/user_program")));
        }
    }
}


fn collect_construction_hysteria2_candidates(root: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let profile_root = root.join("hysteria2/profile");
    let active_path = hysteria2_active_path();
    let Ok(entries) = fs::read_dir(profile_root) else { return; };
    for ent in entries.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
        let profile_enabled = is_profile_enabled(&active_path, &profile);
        let server_root = profile_dir.join("server");
        let Ok(servers) = fs::read_dir(server_root) else { continue; };
        for sent in servers.flatten() {
            let server_dir = sent.path();
            if !server_dir.is_dir() { continue; }
            let server = server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string());
            let setting_path = server_dir.join("setting.json");
            let Ok(v) = read_json::<serde_json::Value>(&setting_path) else { continue; };
            let enabled = crate::common::fs::json::json_enabled(v.get("enabled"));
            let Some(port) = v.get("socks5_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) else { continue; };
            if port != 0 {
                push_construction_candidate(out, "hysteria2", Some(profile.clone()), server, port, "socks5", profile_enabled && enabled, Some(profile_dir.join("app/uid/user_program")));
            }
        }
    }
}

fn collect_construction_myprogram_candidates(root: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let active: ProfilesActive = read_json(&myprogram_active_path()).unwrap_or_default();
    let profiles_root = root.join("myprogram/profile");
    let Ok(profiles) = fs::read_dir(&profiles_root) else { return; };
    for ent in profiles.flatten() {
        let profile_dir = ent.path();
        if !profile_dir.is_dir() { continue; }
        let Some(profile) = profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
        let enabled = active.profiles.get(&profile).map(|st| st.enabled).unwrap_or(false);
        let raw = read_text_or_empty(&profile_dir.join("t2s_ports.txt")).unwrap_or_default();
        for port in parse_port_list_for_construction(&raw) {
            push_construction_candidate(out, "myprogram", Some(profile.clone()), Some("t2s_ports".to_string()), port, "socks5", enabled, Some(profile_dir.join("app/uid/user_program")));
        }
    }
}

fn collect_construction_tor_candidate(_root: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let enabled = crate::programs::tor::load_enabled_json().map(|v| v.is_enabled()).unwrap_or(false);
    let torrc = crate::programs::tor::read_torrc_text().unwrap_or_default();
    let port = parse_tor_socks_port_for_construction(&torrc).unwrap_or(9050);
    push_construction_candidate(out, "tor", None, Some("SocksPort".to_string()), port, "socks5", enabled, Some(settings::tor_uid_program_path()));
}

fn collect_construction_operaproxy_candidates(root: &Path, out: &mut Vec<ConstructionProxyEndpointCandidate>) {
    let enabled: EnabledActive = read_json(&program_root("operaproxy").join("active.json")).unwrap_or_default();
    let port_json: serde_json::Value = read_json(&root.join("operaproxy/port.json")).unwrap_or_else(|_| json!({}));
    let start = port_json.get("opera_start_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
    let count = construction_operaproxy_server_count(root).max(1).min(12);
    for idx in 0..count {
        let Some(port) = start.checked_add(idx as u16) else { continue; };
        push_construction_candidate(out, "operaproxy", None, Some(format!("opera-proxy-{}", idx + 1)), port, "socks5", enabled.enabled, Some(program_root("operaproxy").join("app/uid/user_program")));
    }
}


fn release_construction_proxy_endpoint(req: ConstructionReleaseEndpointReq) -> Result<serde_json::Value> {
    let program = normalize_construction_program_id(&req.program_id);
    let profile = req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let server = req.server.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let port = req.port.ok_or_else(|| anyhow::anyhow!("port is required"))?;
    ensure_safe_segment(&program, "program id")?;
    if let Some(p) = profile { ensure_safe_segment(p, "profile name")?; }
    if let Some(srv) = server { ensure_safe_segment(srv, "server name")?; }
    let _ = (program, profile, server, port);
    // Non-destructive by design: removing a backend from t2s must not stop/kill
    // the local SOCKS/proxy owner service. Keep the API as a no-op for backward
    // compatibility with older UI calls.
    Ok(json!({
        "ok": true,
        "stopped": false,
    }))
}


fn normalize_construction_program_id(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "singbox" | "sing-box" => "sing-box".to_string(),
        "singbox-lx" | "sing-box-lx" | "sing_box_lx" | "singbox_lx" => "sing-box-lx".to_string(),
        other => other.to_string(),
    }
}

fn app_path_to_api_path(program: &str, profile: Option<&str>, slot: &str) -> String {
    match (program, profile) {
        ("operaproxy", _) => format!("/api/programs/operaproxy/apps/{slot}"),
        ("tor", _) => "/api/programs/tor/apps".to_string(),
        (_, Some(profile)) => format!("/api/programs/{program}/profiles/{profile}/apps/user"),
        _ => format!("/api/programs/{program}/apps/{slot}"),
    }
}

fn tcp_port_open(host: &str, port: u16) -> bool {
    let Ok(addr) = format!("{host}:{port}").parse() else { return false; };
    TcpStream::connect_timeout(&addr, Duration::from_millis(140)).is_ok()
}


fn parse_myproxy_ports_for_construction(v: &serde_json::Value) -> Vec<u16> {
    let mut out = Vec::<u16>::new();
    if let Some(arr) = v.get("ports").and_then(|x| x.as_array()) {
        for item in arr {
            if let Some(port) = item.as_u64().and_then(|x| u16::try_from(x).ok()).filter(|p| *p > 0) {
                out.push(port);
            }
        }
    }
    if out.is_empty() {
        match v.get("port") {
            Some(serde_json::Value::Number(n)) => {
                if let Some(port) = n.as_u64().and_then(|x| u16::try_from(x).ok()).filter(|p| *p > 0) { out.push(port); }
            }
            Some(serde_json::Value::String(s)) => out.extend(parse_port_list_for_construction(s)),
            Some(serde_json::Value::Array(items)) => {
                for item in items {
                    if let Some(port) = item.as_u64().and_then(|x| u16::try_from(x).ok()).filter(|p| *p > 0) { out.push(port); }
                }
            }
            _ => {}
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}

fn parse_port_list_for_construction(raw: &str) -> Vec<u16> {
    raw.split(|c: char| c == ',' || c.is_whitespace())
        .filter_map(|s| s.trim().parse::<u16>().ok())
        .filter(|p| *p > 0)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

fn parse_wireproxy_bind_port_for_construction(raw: &str) -> Option<u16> {
    for line in raw.lines() {
        let s = line.trim();
        if !s.to_ascii_lowercase().starts_with("bindaddress") { continue; }
        let value = s.split_whitespace().nth(1).unwrap_or("");
        if let Some((_, port)) = value.rsplit_once(':') { if let Ok(p) = port.parse::<u16>() { return Some(p); } }
    }
    None
}

fn parse_tor_socks_port_for_construction(raw: &str) -> Option<u16> {
    for line in raw.lines() {
        let s = line.split('#').next().unwrap_or("").trim();
        if !s.to_ascii_lowercase().starts_with("socksport") { continue; }
        for token in s.split_whitespace().skip(1) {
            let port_s = token.rsplit_once(':').map(|(_, p)| p).unwrap_or(token);
            if let Ok(p) = port_s.parse::<u16>() { return Some(p); }
        }
    }
    None
}

fn construction_operaproxy_server_count(root: &Path) -> usize {
    let path = root.join("operaproxy/config/sni.json");
    let Ok(raw) = fs::read_to_string(path) else { return 1; };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return 1; };
    v.as_array().map(|a| a.len()).or_else(|| v.get("items").and_then(|x| x.as_array()).map(|a| a.len())).unwrap_or(1)
}
