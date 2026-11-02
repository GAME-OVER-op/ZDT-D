use std::{collections::HashMap, fs, path::{Path, PathBuf}};
use super::model::{DnscryptLayer, RouteMeta, T2sInstanceMeta, TrafficBackendPort};

pub(super) fn resolve_backend_ports(meta: &RouteMeta) -> Vec<TrafficBackendPort> {
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

pub(super) fn build_local_port_registry(root: &Path) -> HashMap<u16, TrafficBackendPort> {
    let mut out = HashMap::new();
    collect_singbox_ports(root, &mut out);
    collect_singbox_lx_ports(root, &mut out);
    collect_hysteria2_ports(root, &mut out);
    collect_wireproxy_ports(root, &mut out);
    collect_operaproxy_ports(root, &mut out);
    collect_tor_socks_port(root, &mut out);
    collect_mihomo_profile_ports(root, &mut out);
    collect_mieru_profile_ports(root, &mut out);
    collect_myprogram_t2s_ports(root, &mut out);
    collect_tgwsproxy_ports(root, &mut out);
    out
}

fn collect_tgwsproxy_ports(_root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    if !crate::programs::tgwsproxy::is_installed() { return; }
    let Ok(setting) = crate::programs::tgwsproxy::read_setting() else { return; };
    if setting.port == 0 { return; }
    let host = crate::programs::tgwsproxy::effective_host(&setting).to_string();
    out.entry(setting.port).or_insert_with(|| TrafficBackendPort {
        port: setting.port,
        label: format!("tgwsproxy:{}", setting.port),
        host: Some(host),
        program_id: Some("tgwsproxy".to_string()),
        profile: None,
        server: None,
        wrapped_host: None,
        wrapped_port: None,
        wrapped_label: None,
        wrapped_program_id: None,
        wrapped_profile: None,
        wrapped_server: None,
    });
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


fn collect_singbox_lx_ports(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    let profile_root = root.join("singbox-lx/profile");
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
            collect_simple_json_port(server_dir.join("setting.json"), "sing-box-lx", Some(profile.clone()), server, &["port", "listen_port"], out);
        }
    }
}

fn collect_hysteria2_ports(root: &Path, out: &mut HashMap<u16, TrafficBackendPort>) {
    let profile_root = root.join("hysteria2/profile");
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
            collect_simple_json_port(server_dir.join("setting.json"), "hysteria2", Some(profile.clone()), server, &["socks5_port"], out);
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
    let byedpi_port = v.get("byedpi_port").and_then(json_u16);
    let sni_entries = operaproxy_sni_entries(root);
    let count = sni_entries.len().max(1).min(12);
    for idx in 0..count {
        if let Some(port) = start.checked_add(idx as u16) {
            let server = format!("opera-proxy-{}", idx + 1);
            let mut item = TrafficBackendPort {
                port,
                label: format!("operaproxy/{}:{}", server, port),
                host: Some("127.0.0.1".to_string()),
                program_id: Some("operaproxy".to_string()),
                profile: None,
                server: Some(server),
                ..Default::default()
            };
            if sni_entries.get(idx).map(|entry| entry.use_byedpi).unwrap_or(false) {
                if let Some(bp) = byedpi_port {
                    item.wrapped_host = Some("127.0.0.1".to_string());
                    item.wrapped_port = Some(bp);
                    item.wrapped_label = Some(format!("operaproxy/byedpi:{}", bp));
                    item.wrapped_program_id = Some("byedpi".to_string());
                    item.wrapped_server = Some(format!("127.0.0.1:{}", bp));
                }
            }
            out.entry(port).or_insert(item);
        }
    }
}

#[derive(Debug, Clone, Default)]
struct OperaproxySniView {
    use_byedpi: bool,
}

fn operaproxy_sni_entries(root: &Path) -> Vec<OperaproxySniView> {
    let path = root.join("operaproxy/config/sni.json");
    let Ok(raw) = fs::read_to_string(path) else { return Vec::new(); };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return Vec::new(); };
    let arr = v.as_array().or_else(|| v.get("items").and_then(|x| x.as_array()));
    let Some(arr) = arr else { return Vec::new(); };
    arr.iter().map(|item| {
        let legacy = item.get("use_byedpi").and_then(|x| x.as_bool()).unwrap_or(false);
        let mode = item.get("proxy_mode").and_then(|x| x.as_str()).unwrap_or("").trim();
        let use_byedpi = if mode.is_empty() {
            legacy
        } else {
            mode.eq_ignore_ascii_case("byedpi")
        };
        OperaproxySniView { use_byedpi }
    }).collect()
}

#[allow(dead_code)]
fn operaproxy_server_count(root: &Path) -> usize {
    operaproxy_sni_entries(root).len().max(1)
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

pub(super) fn load_t2s_instances(_warnings: &mut Vec<String>) -> Vec<T2sInstanceMeta> {
    let dir = Path::new("/data/adb/modules/ZDT-D/api/t2s/instances");
    let Ok(entries) = fs::read_dir(dir) else { return Vec::new(); };
    let mut out = Vec::new();
    for ent in entries.flatten() {
        let path = ent.path();
        if path.extension().and_then(|s| s.to_str()) != Some("json") { continue; }
        let Ok(raw) = fs::read(&path) else { continue; };
        if crate::runtime::sanitize::json_bytes_are_corrupt(&raw) {
            let _ = fs::remove_file(&path);
            log::warn!("traffic_total: removed corrupt t2s metadata {}", path.display());
            continue;
        }
        let Ok(v) = serde_json::from_slice::<serde_json::Value>(&raw) else { continue; };
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

pub(super) fn collect_dnscrypt_layer(warnings: &mut Vec<String>) -> Option<DnscryptLayer> {
    match crate::programs::dnscrypt::active_listen_port() {
        Ok(Some(port)) => Some(DnscryptLayer {
            enabled: true,
            listen_port: port,
            label: format!("DNSCrypt :{}", port),
        }),
        Ok(None) => None,
        Err(e) => {
            warnings.push(format!("dnscrypt status failed: {e:#}"));
            None
        }
    }
}

pub(super) fn resolve_vpn_proxy_endpoint(owner_program: &str, profile: &str, registry: &HashMap<u16, TrafficBackendPort>) -> Option<TrafficBackendPort> {
    let root = Path::new("/data/adb/modules/ZDT-D/working_folder");
    match normalize_program_id(owner_program).as_str() {
        "tun2socks" => {
            let path = root.join("tun2socks/profile").join(profile).join("setting.json");
            let raw = fs::read_to_string(path).ok()?;
            let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
            let proxy = v.get("proxy").and_then(|x| x.as_str()).unwrap_or("");
            traffic_backend_from_proxy_url(proxy, registry)
        }
        "mihomo" => profile_setting_endpoint(root, "mihomo", profile, "mixed_port", registry),
        "mieru" => profile_setting_endpoint(root, "mieru", profile, "socks5_port", registry),
        "singbox" | "sing-box" => singbox_vpn_endpoint(root, profile, registry),
        "singbox-lx" | "sing-box-lx" => singbox_lx_vpn_endpoint(root, profile, registry),
        "hysteria2" => hysteria2_vpn_endpoint(root, profile, registry),
        _ => None,
    }
}

fn profile_setting_endpoint(root: &Path, program: &str, profile: &str, key: &str, registry: &HashMap<u16, TrafficBackendPort>) -> Option<TrafficBackendPort> {
    let path = root.join(program).join("profile").join(profile).join("setting.json");
    let raw = fs::read_to_string(path).ok()?;
    let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
    let port = v.get(key).and_then(json_u16)?;
    Some(registry.get(&port).cloned().unwrap_or_else(|| TrafficBackendPort {
        port,
        label: format!("{program}/{profile}/{key}:{port}"),
        host: Some("127.0.0.1".to_string()),
        program_id: Some(program.to_string()),
        profile: Some(profile.to_string()),
        server: Some(key.to_string()),
        ..Default::default()
    }))
}

fn singbox_vpn_endpoint(root: &Path, profile: &str, registry: &HashMap<u16, TrafficBackendPort>) -> Option<TrafficBackendPort> {
    let server_root = root.join("singbox/profile").join(profile).join("server");
    let entries = fs::read_dir(server_root).ok()?;
    for ent in entries.flatten() {
        let dir = ent.path();
        if !dir.is_dir() { continue; }
        let raw = fs::read_to_string(dir.join("setting.json")).ok()?;
        let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
        if !crate::common::fs::json::json_enabled(v.get("enabled")) { continue; }
        let Some(port) = v.get("port").and_then(json_u16) else { continue; };
        return Some(registry.get(&port).cloned().unwrap_or_else(|| TrafficBackendPort {
            port,
            label: format!("sing-box/{profile}:{}", port),
            host: Some("127.0.0.1".to_string()),
            program_id: Some("sing-box".to_string()),
            profile: Some(profile.to_string()),
            server: file_name_string(&dir),
            ..Default::default()
        }));
    }
    None
}
fn singbox_lx_vpn_endpoint(root: &Path, profile: &str, registry: &HashMap<u16, TrafficBackendPort>) -> Option<TrafficBackendPort> {
    let server_root = root.join("singbox-lx/profile").join(profile).join("server");
    let entries = fs::read_dir(server_root).ok()?;
    for ent in entries.flatten() {
        let dir = ent.path();
        if !dir.is_dir() { continue; }
        let raw = fs::read_to_string(dir.join("setting.json")).ok()?;
        let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
        if !crate::common::fs::json::json_enabled(v.get("enabled")) { continue; }
        let Some(port) = v.get("port").and_then(json_u16) else { continue; };
        return Some(registry.get(&port).cloned().unwrap_or_else(|| TrafficBackendPort {
            port,
            label: format!("sing-box-lx/{profile}:{}", port),
            host: Some("127.0.0.1".to_string()),
            program_id: Some("sing-box-lx".to_string()),
            profile: Some(profile.to_string()),
            server: file_name_string(&dir),
            ..Default::default()
        }));
    }
    None
}

fn hysteria2_vpn_endpoint(root: &Path, profile: &str, registry: &HashMap<u16, TrafficBackendPort>) -> Option<TrafficBackendPort> {
    let server_root = root.join("hysteria2/profile").join(profile).join("server");
    let entries = fs::read_dir(server_root).ok()?;
    for ent in entries.flatten() {
        let dir = ent.path();
        if !dir.is_dir() { continue; }
        let raw = fs::read_to_string(dir.join("setting.json")).ok()?;
        let v: serde_json::Value = serde_json::from_str(&raw).ok()?;
        if !crate::common::fs::json::json_enabled(v.get("enabled")) { continue; }
        let Some(port) = v.get("socks5_port").and_then(json_u16) else { continue; };
        return Some(registry.get(&port).cloned().unwrap_or_else(|| TrafficBackendPort {
            port,
            label: format!("hysteria2/{profile}:{}", port),
            host: Some("127.0.0.1".to_string()),
            program_id: Some("hysteria2".to_string()),
            profile: Some(profile.to_string()),
            server: file_name_string(&dir),
            ..Default::default()
        }));
    }
    None
}

fn traffic_backend_from_proxy_url(proxy: &str, registry: &HashMap<u16, TrafficBackendPort>) -> Option<TrafficBackendPort> {
    let (host, port) = parse_proxy_host_port(proxy)?;
    let local = matches!(host.as_str(), "127.0.0.1" | "localhost" | "::1" | "0.0.0.0");
    if local {
        if let Some(found) = registry.get(&port) {
            return Some(found.clone());
        }
    }
    Some(TrafficBackendPort {
        port,
        label: format!("{}:{}", host, port),
        host: Some(host.clone()),
        program_id: Some("external-proxy".to_string()),
        profile: None,
        server: Some(format!("{}:{}", host, port)),
        ..Default::default()
    })
}

fn parse_proxy_host_port(proxy: &str) -> Option<(String, u16)> {
    let mut value = proxy.trim();
    if let Some((_, rest)) = value.split_once("://") {
        value = rest;
    }
    let hostport = value.rsplit_once('@').map(|(_, hp)| hp).unwrap_or(value);
    if let Some(rest) = hostport.strip_prefix('[') {
        let (host, tail) = rest.split_once(']')?;
        let port = tail.strip_prefix(':')?.parse::<u16>().ok()?;
        return Some((host.to_string(), port));
    }
    let (host, port_raw) = hostport.rsplit_once(':')?;
    let port = port_raw.parse::<u16>().ok()?;
    if host.trim().is_empty() || port == 0 { return None; }
    Some((host.trim().to_string(), port))
}

fn normalize_program_id(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "singbox" => "sing-box".to_string(),
        "singbox-lx" | "singbox_lx" | "sing-box-lx" => "sing-box-lx".to_string(),
        other => other.to_string(),
    }
}
