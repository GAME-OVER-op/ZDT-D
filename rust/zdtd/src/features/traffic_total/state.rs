use std::{collections::HashMap, fs, path::Path, time::Duration};
use serde::Deserialize;
use crate::common::process::shell::{self, Capture};
use super::model::RouteMeta;

const PKG_LIST_TIMEOUT: Duration = Duration::from_secs(5);
const ROUTING_CACHE: &str = "/data/adb/modules/ZDT-D/working_folder/runtime_refresh/routing.json";
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "kind")]
enum RoutingSnapshot {
    NfqV1 {
        uid_file: String,
        mode: String,
        queue: u16,
        iface: Option<String>,
        #[allow(dead_code)]
        filter: Option<serde_json::Value>,
    },
    NfqV2 {
        uid_file: String,
        port: u16,
        #[allow(dead_code)]
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
    Tproxy {
        uid_file: String,
        dest_port: u16,
        proto_choice: String,
        ifaces_raw: Option<String>,
        port_preference: u8,
        dpi_ports: String,
        mark: u32,
        table: u32,
    },
}
pub(super) fn load_route_meta(warnings: &mut Vec<String>) -> HashMap<String, RouteMeta> {
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
            RoutingSnapshot::Tproxy { uid_file, dest_port, proto_choice, ifaces_raw, port_preference, dpi_ports, mark, table } => {
                let mut meta = meta_from_uid_file(&uid_file);
                meta.kind = "tproxy".to_string();
                meta.uid_file = Some(uid_file.clone());
                meta.dest_port = Some(dest_port);
                meta.mark = Some(mark);
                meta.table = Some(table);
                let scope = format!(
                    "tproxy:uid={}:dest={}:proto={}:ifaces={}:pref={}:ports={}",
                    uid_file,
                    dest_port,
                    proto_choice_debug(&proto_choice),
                    ifaces_raw.unwrap_or_default(),
                    port_preference,
                    dpi_ports,
                );
                out.insert(scoped_tproxy_out_chain_name(&scope), meta.clone());
                out.insert(scoped_tproxy_pre_chain_name(&scope), meta);
            }
        }
    }
    out
}

pub(super) fn proto_choice_debug(s: &str) -> &'static str {
    match s {
        "udp" => "Udp",
        "tcp_udp" => "TcpUdp",
        _ => "Tcp",
    }
}

pub(super) fn scoped_mangle_chain_name(label: &str) -> String { scoped_hash_name("ZDTM", label) }
pub(super) fn scoped_nat_chain_name(label: &str) -> String { scoped_hash_name("ZDTN", label) }
pub(super) fn scoped_tproxy_out_chain_name(label: &str) -> String { scoped_hash_name("ZDTP", label) }
pub(super) fn scoped_tproxy_pre_chain_name(label: &str) -> String { scoped_hash_name("ZDTPP", label) }

pub(super) fn scoped_hash_name(prefix: &str, label: &str) -> String {
    let mut hash: u64 = 0xcbf29ce484222325;
    for b in label.as_bytes() {
        hash ^= *b as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    format!("{prefix}_{hash:016x}")
}

pub(super) fn meta_from_uid_file(uid_file: &str) -> RouteMeta {
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

pub(super) fn infer_builtin_meta(chain: &str, target: &str) -> RouteMeta {
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

pub(super) fn load_uid_file_packages(route_meta: &HashMap<String, RouteMeta>) -> HashMap<String, HashMap<u32, Vec<String>>> {
    let mut out = HashMap::new();
    for uid_file in route_meta.values().filter_map(|m| m.uid_file.as_deref()) {
        if out.contains_key(uid_file) {
            continue;
        }
        out.insert(uid_file.to_string(), read_uid_file_packages(uid_file));
    }
    out
}

pub(super) fn read_uid_file_packages(uid_file: &str) -> HashMap<u32, Vec<String>> {
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

pub(super) fn load_uid_package_map(warnings: &mut Vec<String>) -> HashMap<u32, Vec<String>> {
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

pub(super) fn packages_for_uid(
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
