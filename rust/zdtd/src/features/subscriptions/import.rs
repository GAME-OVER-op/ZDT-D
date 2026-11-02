use anyhow::{bail, Context, Result};
use serde_json::{json, Value as JsonValue};
use sha2::{Digest, Sha256};
use std::{collections::{BTreeMap}, fs};

use super::model::*;
use super::storage::*;
use super::crud::*;
use super::parse::*;
use super::render::*;
use super::provider::*;

pub(super) struct ParsedManualSource {
    pub(super) node: SubscriptionNode,
    pub(super) exact_content: Option<String>,
    pub(super) exact_target: Option<&'static str>,
    pub(super) format: &'static str,
}

pub(super) fn endpoint_parts(raw: &str) -> Option<(String, u16)> {
    let value = raw.trim();
    if let Some(rest) = value.strip_prefix('[') {
        let end = rest.find(']')?;
        let host = rest[..end].to_string();
        let port = rest[end + 1..].strip_prefix(':')?.parse().ok()?;
        return Some((host, port));
    }
    let (host, port) = value.rsplit_once(':')?;
    Some((host.trim().to_string(), port.trim().parse().ok()?))
}

pub(super) fn node_from_manual_definition(mut definition: JsonValue) -> Result<SubscriptionNode> {
    if definition.get("port").is_none() {
        if let Some(port) = definition.get("server_port").cloned() {
            definition["port"] = port;
        }
    }
    node_from_definition("manual", definition, 0)
        .ok_or_else(|| anyhow::anyhow!("configuration does not contain a supported server node"))
}

pub(super) fn parse_wireguard_conf(text: &str) -> Option<SubscriptionNode> {
    let mut section = String::new();
    let mut interface = BTreeMap::<String, String>::new();
    let mut peer = BTreeMap::<String, String>::new();
    let mut socks_bind = String::new();
    for raw in text.lines() {
        let line = raw.split('#').next().unwrap_or("").trim();
        if line.starts_with('[') && line.ends_with(']') {
            section = line.trim_matches(['[', ']']).trim().to_ascii_lowercase();
            continue;
        }
        let Some((key, value)) = line.split_once('=') else { continue; };
        let key = key.trim().to_ascii_lowercase();
        let value = value.trim().to_string();
        match section.as_str() {
            "interface" => { interface.insert(key, value); }
            "peer" => { peer.insert(key, value); }
            "socks5" if key == "bindaddress" => socks_bind = value,
            _ => {}
        }
    }
    let endpoint = peer.get("endpoint")?;
    let (server, port) = endpoint_parts(endpoint)?;
    let private_key = interface.get("privatekey")?.clone();
    let public_key = peer.get("publickey")?.clone();
    let address = interface.get("address")?.split(',').map(str::trim).filter(|v| !v.is_empty()).collect::<Vec<_>>();
    if address.is_empty() { return None; }
    let mut definition = json!({
        "type":"wireguard", "name":"WireGuard", "server":server, "port":port,
        "private-key":private_key, "public-key":public_key, "address":address,
    });
    if let Some(psk) = peer.get("presharedkey") { definition["pre-shared-key"] = json!(psk); }
    if !socks_bind.is_empty() { definition["bind-address"] = json!(socks_bind); }
    node_from_definition("manual", definition, 0)
}

pub(super) fn parse_manual_source(raw: &str) -> Result<ParsedManualSource> {
    let text = raw.trim();
    if text.is_empty() { bail!("configuration is empty"); }
    if text.len() > 4 * 1024 * 1024 { bail!("configuration is too large"); }

    if !text.contains('\n') && is_supported_uri_line(text) {
        let definition = definition_from_uri(text)
            .ok_or_else(|| anyhow::anyhow!("unable to parse share link"))?;
        let node = node_from_manual_definition(definition)?;
        if node.targets.is_empty() {
            bail!("{}{} is not supported for local import", node.protocol, if node.transport.is_empty() { String::new() } else { format!("/{}", node.transport) });
        }
        return Ok(ParsedManualSource { node, exact_content: None, exact_target: None, format: "uri" });
    }

    if text.starts_with('{') {
        let value: JsonValue = serde_json::from_str(text).context("invalid JSON configuration")?;
        if let Some(outbounds) = value.get("outbounds").and_then(JsonValue::as_array) {
            let proxy_outbounds = outbounds.iter().filter(|item| {
                matches!(normalized_protocol(&json_string_any(item, &["type"])).as_str(),
                    "vless" | "vmess" | "trojan" | "shadowsocks" | "socks")
            }).collect::<Vec<_>>();
            if proxy_outbounds.len() != 1 {
                bail!("sing-box JSON must contain exactly one supported proxy outbound");
            }
            let outbound = (*proxy_outbounds[0]).clone();
            let node = node_from_manual_definition(outbound)?;
            let standard = node.targets.iter().any(|target| target == "sing-box");
            let lx = node.targets.iter().any(|target| target == "sing-box-lx");
            if !standard && !lx {
                bail!("sing-box JSON uses an unsupported transport");
            }
            let exact_target = if lx && !standard { Some("sing-box-lx") } else { None };
            return Ok(ParsedManualSource {
                node,
                exact_content: Some(serde_json::to_string_pretty(&value)?),
                exact_target,
                format: "json",
            });
        }

        if value.get("auth").is_some() && value.get("server").and_then(JsonValue::as_str).is_some() {
            let endpoint = value.get("server").and_then(JsonValue::as_str).unwrap_or("");
            let (server, port) = endpoint_parts(endpoint).ok_or_else(|| anyhow::anyhow!("invalid Hysteria2 server endpoint"))?;
            let tls = value.get("tls").cloned().unwrap_or_else(|| json!({}));
            let name = value.get("name").and_then(JsonValue::as_str).unwrap_or("Hysteria2");
            let definition = json!({
                "type":"hysteria2", "name":name, "server":server, "port":port,
                "password":value.get("auth").cloned().unwrap_or(JsonValue::Null), "tls":tls,
            });
            let node = node_from_manual_definition(definition)?;
            return Ok(ParsedManualSource {
                node,
                exact_content: Some(serde_json::to_string_pretty(&value)?),
                exact_target: Some("hysteria2"),
                format: "json",
            });
        }

        if value.get("type").is_some() {
            let node = node_from_manual_definition(value)?;
            if node.targets.is_empty() { bail!("JSON node type is not supported for local import"); }
            return Ok(ParsedManualSource { node, exact_content: None, exact_target: None, format: "json" });
        }
        bail!("JSON format is not recognized");
    }

    if text.contains("[Interface]") && text.contains("[Peer]") {
        let node = parse_wireguard_conf(text).ok_or_else(|| anyhow::anyhow!("invalid WireGuard configuration"))?;
        return Ok(ParsedManualSource {
            node,
            // Re-render as WireProxy config so a compatible, conflict-free
            // local Socks5 BindAddress is always present.
            exact_content: None,
            exact_target: None,
            format: "conf",
        });
    }

    bail!("unsupported configuration format")
}

pub fn preview_manual_import(request: ManualImportPreviewRequest) -> Result<JsonValue> {
    let parsed = parse_manual_source(&request.content)?;
    Ok(json!({"ok":true, "node":parsed.node, "format":parsed.format}))
}

pub fn import_manual(request: ManualImportRequest) -> Result<JsonValue> {
    let parsed = parse_manual_source(&request.content)?;
    let target = request.target.trim().to_ascii_lowercase();
    if !targets_for_node(&parsed.node).iter().any(|value| value == &target) {
        bail!("configuration cannot be imported into this target");
    }
    if let Some(exact_target) = parsed.exact_target {
        if exact_target != target { bail!("configuration belongs to a different target"); }
    }
    let profile = request.profile.trim().to_string();
    let requested_name = request.server_name.trim();
    let server_name = if requested_name.is_empty() { safe_server_name(&parsed.node.name) } else { safe_server_name(requested_name) };
    crate::programs::singbox::ensure_valid_profile_name(&profile)?;
    crate::programs::singbox::ensure_valid_profile_name(&server_name)?;
    ensure_target_accepts_new_server(&target, &profile)?;
    let root = target_server_root(&target, &profile, &server_name)?;
    if root.exists() { bail!("target server already exists"); }
    fs::create_dir_all(root.join("log"))?;
    let local_port = match target.as_str() {
        "sing-box" => next_local_port(2080),
        "sing-box-lx" => next_local_port(1180),
        "hysteria2" => next_local_port(11590),
        "wireproxy" => next_local_port(1167),
        _ => bail!("unsupported import target"),
    };
    let result = (|| -> Result<()> {
        match target.as_str() {
            "sing-box" => {
                let content = match parsed.exact_content.as_deref() {
                    Some(content) => content.to_string(),
                    None => render_singbox_config(&parsed.node, local_port)?,
                };
                write_text_atomic(&root.join("config.json"), &content)?;
                write_json_atomic(&root.join("setting.json"), &json!({"enabled":false,"port":local_port}))?;
                write_text_atomic(&root.join("log/sing-box.log"), "")?;
                crate::programs::singbox::normalize_config_for_profile_server(&profile, &server_name)?;
            }
            "sing-box-lx" => {
                let content = match parsed.exact_content.as_deref() {
                    Some(content) => content.to_string(),
                    None => render_singbox_lx_config(&parsed.node, local_port)?,
                };
                write_text_atomic(&root.join("config.json"), &content)?;
                write_json_atomic(&root.join("setting.json"), &json!({"enabled":false,"port":local_port}))?;
                write_text_atomic(&root.join("log/sing-box.log"), "")?;
                crate::programs::singbox::lx::normalize_config_for_profile_server(&profile, &server_name)?;
            }
            "hysteria2" => {
                let content = match parsed.exact_content.as_deref() {
                    Some(content) => content.to_string(),
                    None => render_hysteria2_config(&parsed.node, local_port)?,
                };
                write_text_atomic(&root.join("config.json"), &content)?;
                write_json_atomic(&root.join("setting.json"), &json!({"enabled":false,"socks5_port":local_port,"log_level":"info"}))?;
                write_text_atomic(&root.join("log/hysteria2.log"), "")?;
                crate::programs::hysteria2::normalize_config_for_profile_server(&profile, &server_name)?;
            }
            "wireproxy" => {
                let content = match parsed.exact_content.as_deref() {
                    Some(content) => content.to_string(),
                    None => render_wireproxy_config(&parsed.node, local_port)?,
                };
                write_text_atomic(&root.join("config.conf"), &content)?;
                write_json_atomic(&root.join("setting.json"), &json!({"enabled":false}))?;
                write_text_atomic(&root.join("log/wireproxy.log"), "")?;
            }
            _ => unreachable!(),
        }
        Ok(())
    })();
    if let Err(error) = result {
        let _ = fs::remove_dir_all(&root);
        return Err(error);
    }
    Ok(json!({"ok":true, "target":target, "profile":profile, "server_name":server_name}))
}

pub(super) fn link_id_for(target: &str, profile: &str, server: &str) -> String {
    let mut h = Sha256::new();
    h.update(target.as_bytes()); h.update(b"\0");
    h.update(profile.as_bytes()); h.update(b"\0");
    h.update(server.as_bytes());
    format!("link_{}", &hex::encode(h.finalize())[..20])
}

pub(super) fn ensure_link_id(id: &str) -> Result<()> {
    if id.len() < 10 || id.len() > 64 || !id.starts_with("link_") || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        bail!("invalid subscription link id");
    }
    Ok(())
}

pub(super) fn local_port_for_link(link: &SubscriptionLink) -> u16 {
    let root = target_server_root(&link.target, &link.profile, &link.server_name).ok();
    match (link.target.as_str(), root) {
        ("sing-box", Some(root)) => fs::read_to_string(root.join("setting.json")).ok()
            .and_then(|raw| serde_json::from_str::<JsonValue>(&raw).ok())
            .map(|v| json_u16_any(&v, &["port"])).filter(|v| *v > 0).unwrap_or(2080),
        ("sing-box-lx", Some(root)) => fs::read_to_string(root.join("setting.json")).ok()
            .and_then(|raw| serde_json::from_str::<JsonValue>(&raw).ok())
            .map(|v| json_u16_any(&v, &["port"])).filter(|v| *v > 0).unwrap_or(1180),
        ("hysteria2", Some(root)) => fs::read_to_string(root.join("setting.json")).ok()
            .and_then(|raw| serde_json::from_str::<JsonValue>(&raw).ok())
            .map(|v| json_u16_any(&v, &["socks5_port"])).filter(|v| *v > 0).unwrap_or(11590),
        ("wireproxy", Some(root)) => fs::read_to_string(root.join("config.conf")).ok()
            .and_then(|raw| raw.lines().find_map(|line| {
                let (key, value) = line.split_once('=')?;
                if key.trim().eq_ignore_ascii_case("BindAddress") { value.trim().rsplit(':').next()?.parse::<u16>().ok() } else { None }
            })).unwrap_or(1167),
        _ => 0,
    }
}

pub(super) fn write_link_config(link: &SubscriptionLink, node: &SubscriptionNode) -> Result<()> {
    if !targets_for_node(node).iter().any(|target| target == &link.target) {
        bail!("linked node is no longer compatible with target {}", link.target);
    }
    let root = target_server_root(&link.target, &link.profile, &link.server_name)?;
    if !root.is_dir() { bail!("linked local server no longer exists"); }
    let port = local_port_for_link(link);
    let (path, content) = match link.target.as_str() {
        "sing-box" => (root.join("config.json"), render_singbox_config(node, port)?),
        "sing-box-lx" => (root.join("config.json"), render_singbox_lx_config(node, port)?),
        "hysteria2" => (root.join("config.json"), render_hysteria2_config(node, port)?),
        "wireproxy" => (root.join("config.conf"), render_wireproxy_config(node, port)?),
        _ => bail!("unsupported subscription import target"),
    };
    write_text_atomic(&path, &content)
}

pub fn import_node(subscription_id: &str, node_id: &str, request: ImportNodeRequest) -> Result<SubscriptionLink> {
    ensure_id(subscription_id)?;
    if !node_id.starts_with("node_") { bail!("invalid node id"); }
    let _store_guard = store_lock().lock().map_err(|_| anyhow::anyhow!("subscription store lock poisoned"))?;
    let _ = get(subscription_id)?;
    let node = read_nodes(subscription_id).into_iter().find(|node| node.id == node_id)
        .ok_or_else(|| anyhow::anyhow!("subscription node not found"))?;
    if node.server.is_empty() || node.port == 0 { bail!("subscription node endpoint is incomplete"); }
    let target = request.target.trim().to_ascii_lowercase();
    if !targets_for_node(&node).iter().any(|value| value == &target) {
        bail!("node cannot be imported into this target");
    }
    let profile = request.profile.trim().to_string();
    let requested_name = request.server_name.trim();
    let server_name = if requested_name.is_empty() { safe_server_name(&node.name) } else { safe_server_name(requested_name) };
    crate::programs::singbox::ensure_valid_profile_name(&profile)?;
    crate::programs::singbox::ensure_valid_profile_name(&server_name)?;
    ensure_target_accepts_new_server(&target, &profile)?;
    let root = target_server_root(&target, &profile, &server_name)?;
    if root.exists() { bail!("target server already exists"); }
    fs::create_dir_all(root.join("log"))?;
    let local_port = match target.as_str() {
        "sing-box" => next_local_port(2080),
        "sing-box-lx" => next_local_port(1180),
        "hysteria2" => next_local_port(11590),
        "wireproxy" => next_local_port(1167),
        _ => bail!("unsupported subscription import target"),
    };
    let create_result = (|| -> Result<()> {
        match target.as_str() {
            "sing-box" => {
                write_text_atomic(&root.join("config.json"), &render_singbox_config(&node, local_port)?)?;
                write_json_atomic(&root.join("setting.json"), &json!({"enabled":false, "port":local_port}))?;
                write_text_atomic(&root.join("log/sing-box.log"), "")?;
            }
            "sing-box-lx" => {
                write_text_atomic(&root.join("config.json"), &render_singbox_lx_config(&node, local_port)?)?;
                write_json_atomic(&root.join("setting.json"), &json!({"enabled":false, "port":local_port}))?;
                write_text_atomic(&root.join("log/sing-box.log"), "")?;
            }
            "hysteria2" => {
                write_text_atomic(&root.join("config.json"), &render_hysteria2_config(&node, local_port)?)?;
                write_json_atomic(&root.join("setting.json"), &json!({"enabled":false, "socks5_port":local_port, "log_level":"info"}))?;
                write_text_atomic(&root.join("log/hysteria2.log"), "")?;
            }
            "wireproxy" => {
                write_text_atomic(&root.join("config.conf"), &render_wireproxy_config(&node, local_port)?)?;
                write_json_atomic(&root.join("setting.json"), &json!({"enabled":false}))?;
                write_text_atomic(&root.join("log/wireproxy.log"), "")?;
            }
            _ => unreachable!(),
        }
        Ok(())
    })();
    if let Err(error) = create_result {
        let _ = fs::remove_dir_all(&root);
        return Err(error);
    }

    let _links_guard = links_lock().lock().map_err(|_| anyhow::anyhow!("subscription links lock poisoned"))?;
    let mut links = read_links()?;
    let id = link_id_for(&target, &profile, &server_name);
    let link = SubscriptionLink {
        id: id.clone(), subscription_id: subscription_id.to_string(), node_id: node_id.to_string(),
        target, profile, server_name, missing: false, last_synced_at: now_unix(),
    };
    links.links.insert(id, link.clone());
    if let Err(error) = write_links(&links) {
        let _ = fs::remove_dir_all(&root);
        return Err(error);
    }
    Ok(link)
}

pub(super) fn target_profile_is_vpn(target: &str, profile: &str) -> bool {
    let program = match target {
        "sing-box" => "singbox",
        "sing-box-lx" => "singbox-lx",
        "hysteria2" => "hysteria2",
        _ => return false,
    };
    fs::read_to_string(format!("/data/adb/modules/ZDT-D/working_folder/{program}/profile/{profile}/setting.json"))
        .ok()
        .and_then(|raw| serde_json::from_str::<JsonValue>(&raw).ok())
        .and_then(|value| value.get("mode").and_then(JsonValue::as_str).map(str::to_string))
        .map(|mode| mode.eq_ignore_ascii_case("vpn"))
        .unwrap_or(false)
}

pub(super) fn unique_target_server_name(target: &str, profile: &str, raw: &str) -> Result<String> {
    let base = safe_server_name(raw);
    if !target_server_root(target, profile, &base)?.exists() { return Ok(base); }
    for suffix in 2..=9999usize {
        let suffix_text = format!("_{suffix}");
        let keep = 56usize.saturating_sub(suffix_text.len());
        let stem: String = base.chars().take(keep).collect();
        let candidate = format!("{stem}{suffix_text}");
        if !target_server_root(target, profile, &candidate)?.exists() { return Ok(candidate); }
    }
    bail!("unable to allocate a unique local server name")
}

pub fn import_nodes_bulk(subscription_id: &str, request: BulkImportNodesRequest) -> Result<BulkImportResult> {
    ensure_id(subscription_id)?;
    let _ = get(subscription_id)?;
    let target = request.target.trim().to_ascii_lowercase();
    let profile = request.profile.trim().to_string();
    crate::programs::singbox::ensure_valid_profile_name(&profile)?;
    // This validates the target/profile and, for the optional LX target,
    // rejects imports while the component is absent.
    ensure_target_accepts_new_server(&target, &profile)?;

    let mut compatible: Vec<SubscriptionNode> = read_nodes(subscription_id).into_iter()
        .filter(|node| node.server.len() > 0 && node.port > 0)
        .filter(|node| targets_for_node(node).iter().any(|value| value == &target))
        .collect();
    compatible.sort_by(|a, b| a.name.to_ascii_lowercase().cmp(&b.name.to_ascii_lowercase()).then(a.id.cmp(&b.id)));

    if compatible.len() > 1 && target_profile_is_vpn(&target, &profile) {
        bail!("bulk import requires a T2S profile because VPN mode supports only one server");
    }

    let existing_links = read_links().unwrap_or_default();
    let mut imported = 0usize;
    let mut skipped = 0usize;
    let mut failed = 0usize;
    let mut failures = Vec::new();

    for node in &compatible {
        let already_linked = existing_links.links.values().any(|link| {
            link.subscription_id == subscription_id
                && link.node_id == node.id
                && link.target == target
                && link.profile == profile
                && target_server_root(&link.target, &link.profile, &link.server_name)
                    .map(|root| root.is_dir())
                    .unwrap_or(false)
        });
        if already_linked {
            skipped += 1;
            continue;
        }

        let server_name = match unique_target_server_name(&target, &profile, &node.name) {
            Ok(value) => value,
            Err(error) => {
                failed += 1;
                if failures.len() < 50 {
                    failures.push(BulkImportFailure { node_id: node.id.clone(), name: node.name.clone(), error: format!("{error:#}") });
                }
                continue;
            }
        };
        let request = ImportNodeRequest { target: target.clone(), profile: profile.clone(), server_name };
        match import_node(subscription_id, &node.id, request) {
            Ok(_) => imported += 1,
            Err(error) => {
                failed += 1;
                if failures.len() < 50 {
                    failures.push(BulkImportFailure { node_id: node.id.clone(), name: node.name.clone(), error: format!("{error:#}") });
                }
            }
        }
    }

    Ok(BulkImportResult {
        target,
        profile,
        compatible: compatible.len(),
        imported,
        skipped,
        failed,
        failures,
    })
}

pub(super) fn sync_imported_nodes(subscription_id: &str, nodes: &[SubscriptionNode]) {
    let Ok(_guard) = links_lock().lock() else { return; };
    let Ok(mut links) = read_links() else { return; };
    let by_id: BTreeMap<&str, &SubscriptionNode> = nodes.iter().map(|node| (node.id.as_str(), node)).collect();
    let mut changed = false;
    for link in links.links.values_mut().filter(|link| link.subscription_id == subscription_id) {
        let Some(node) = by_id.get(link.node_id.as_str()).copied() else {
            if !link.missing { link.missing = true; changed = true; }
            continue;
        };
        match write_link_config(link, node) {
            Ok(()) => {
                link.missing = false;
                link.last_synced_at = now_unix();
                changed = true;
            }
            Err(error) => log::warn!("subscription link sync failed link={}: {error:#}", link.id),
        }
    }
    if changed { let _ = write_links(&links); }
}

