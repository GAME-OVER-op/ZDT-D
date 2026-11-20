use anyhow::{bail, Result};
use base64::{engine::general_purpose, Engine as _};
use percent_encoding::percent_decode_str;
use reqwest::{Url};
use serde_json::{json, Value as JsonValue};
use serde_yaml::{Value as YamlValue};
use sha2::{Digest, Sha256};
use std::{collections::{BTreeMap}};

use super::model::*;
use super::provider::*;

pub(super) fn json_string_any(value: &JsonValue, keys: &[&str]) -> String {
    for key in keys {
        if let Some(v) = value.get(*key) {
            if let Some(s) = v.as_str() { return s.trim().to_string(); }
            if let Some(n) = v.as_u64() { return n.to_string(); }
        }
    }
    String::new()
}

pub(super) fn json_u16_any(value: &JsonValue, keys: &[&str]) -> u16 {
    for key in keys {
        if let Some(v) = value.get(*key) {
            let parsed = v.as_u64().and_then(|n| u16::try_from(n).ok())
                .or_else(|| v.as_str().and_then(|s| s.parse::<u16>().ok()));
            if let Some(port) = parsed { return port; }
        }
    }
    0
}

pub(super) fn normalized_protocol(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "hy2" => "hysteria2".to_string(),
        "ss" => "shadowsocks".to_string(),
        "wg" => "wireguard".to_string(),
        "socks4" | "socks4a" | "socks5" => "socks".to_string(),
        value => value.to_string(),
    }
}

pub(super) fn decode_node_name(raw: &str) -> String {
    let decoded = percent_decode_str(raw).decode_utf8_lossy();
    decoded.chars()
        .filter(|c| !c.is_control() || matches!(c, '\t' | '\n'))
        .collect::<String>()
        .trim()
        .to_string()
}

pub(super) fn transport_from_definition(definition: &JsonValue) -> String {
    let normalized = |raw: &str| {
        raw.trim()
            .to_ascii_lowercase()
            .replace("websocket", "ws")
            .replace("none", "tcp")
            .replace("splithttp", "xhttp")
    };
    if let Some(value) = definition.get("transport") {
        if let Some(raw) = value.as_str() { return normalized(raw); }
        if value.is_object() {
            let raw = json_string_any(value, &["type"]);
            if !raw.is_empty() { return normalized(&raw); }
        }
    }
    normalized(&json_string_any(definition, &["network", "net"]))
}

pub(super) fn security_from_definition(definition: &JsonValue) -> String {
    if definition.get("reality-opts").is_some()
        || definition.get("reality_opts").is_some()
        || !json_string_any(definition, &["pbk", "public-key", "public_key"]).is_empty()
        || definition.get("tls").and_then(|tls| tls.get("reality")).is_some()
    {
        return "reality".to_string();
    }
    let raw = json_string_any(definition, &["security"]);
    if !raw.is_empty() && !raw.eq_ignore_ascii_case("none") { return raw.to_ascii_lowercase(); }
    if definition.get("tls").map(|value| value.is_object() || value.as_bool() == Some(true)).unwrap_or(false) {
        return "tls".to_string();
    }
    String::new()
}

pub(super) fn targets_for_protocol(protocol: &str, transport: &str) -> Vec<String> {
    let normalized_transport = transport.trim().to_ascii_lowercase();
    let transport = if normalized_transport == "splithttp" { "xhttp" } else { normalized_transport.as_str() };
    match protocol {
        "hysteria2" => vec!["hysteria2".to_string()],
        "wireguard" => vec!["wireproxy".to_string()],
        // Standard sing-box keeps exactly the transports it supported before
        // the LX integration. sing-box-lx is offered as a second compatible
        // target for those nodes, while native XHTTP is LX-only.
        "vless" | "vmess" | "trojan"
            if matches!(transport, "" | "tcp" | "ws" | "grpc" | "http" | "httpupgrade") =>
                vec!["sing-box".to_string(), "sing-box-lx".to_string()],
        "vless" | "vmess" | "trojan" if transport == "xhttp" =>
            vec!["sing-box-lx".to_string()],
        "shadowsocks" | "socks" =>
            vec!["sing-box".to_string(), "sing-box-lx".to_string()],
        _ => Vec::new(),
    }
}

pub(super) fn vless_encryption_from_definition(definition: &JsonValue) -> Option<String> {
    let encryption = json_string_any(definition, &["encryption"]).trim().to_string();
    if encryption.is_empty() || encryption.eq_ignore_ascii_case("none") {
        None
    } else {
        Some(encryption)
    }
}

pub(super) fn valid_vless_encryption_shape(encryption: &str) -> bool {
    let mut parts = encryption.split('.');
    if parts.next() != Some("mlkem768x25519plus") {
        return false;
    }
    let tail = parts.collect::<Vec<_>>();
    tail.len() >= 3 && tail.iter().all(|part| !part.is_empty())
}

pub(super) fn targets_for_definition(protocol: &str, transport: &str, definition: &JsonValue) -> Vec<String> {
    if protocol == "vless" {
        if let Some(encryption) = vless_encryption_from_definition(definition) {
            // Never silently downgrade a PQ VLESS node by dropping encryption.
            // Invalid shapes get no target and are rejected before rendering.
            return if valid_vless_encryption_shape(&encryption) {
                vec!["sing-box-lx".to_string()]
            } else {
                Vec::new()
            };
        }
    }
    targets_for_protocol(protocol, transport)
}

pub(super) fn targets_for_node(node: &SubscriptionNode) -> Vec<String> {
    targets_for_definition(&node.protocol, &node.transport, &node.definition)
}

pub(super) fn normalize_stored_node(mut node: SubscriptionNode) -> SubscriptionNode {
    node.name = decode_node_name(&node.name);
    node.protocol = normalized_protocol(&node.protocol);

    // Compatibility repair for nodes written by the first library build: a
    // share-link query such as `type=xhttp` overwrote `type=vless`.
    if matches!(node.protocol.as_str(), "xhttp" | "grpc" | "ws" | "httpupgrade")
        && node.definition.get("uuid").is_some()
    {
        if node.transport.is_empty() { node.transport = node.protocol.clone(); }
        let encryption = json_string_any(&node.definition, &["encryption"]);
        node.protocol = if encryption.eq_ignore_ascii_case("none")
            || node.definition.get("flow").is_some()
            || node.definition.get("pbk").is_some()
        { "vless".to_string() } else { "vmess".to_string() };
    }
    if node.transport.is_empty() { node.transport = transport_from_definition(&node.definition); }
    if node.security.is_empty() { node.security = security_from_definition(&node.definition); }
    if node.security.is_empty() && matches!(node.protocol.as_str(), "hysteria2" | "trojan") {
        node.security = "tls".to_string();
    }
    node.targets = if node.server.is_empty() || node.port == 0 {
        Vec::new()
    } else {
        targets_for_definition(&node.protocol, &node.transport, &node.definition)
    };
    node
}

pub(super) fn stable_node_id(subscription_id: &str, protocol: &str, name: &str, occurrence: usize) -> String {
    let mut h = Sha256::new();
    h.update(subscription_id.as_bytes());
    h.update(b"\0");
    h.update(protocol.as_bytes());
    h.update(b"\0");
    h.update(name.trim().to_lowercase().as_bytes());
    h.update(b"\0");
    h.update(occurrence.to_le_bytes());
    let digest = hex::encode(h.finalize());
    format!("node_{}", &digest[..20])
}

pub(super) fn node_from_definition(subscription_id: &str, mut definition: JsonValue, occurrence: usize) -> Option<SubscriptionNode> {
    let share_link = definition.as_object_mut()
        .and_then(|obj| obj.remove("_zdt_share_link"))
        .and_then(|value| value.as_str().map(str::to_string))
        .unwrap_or_default();
    let protocol = normalized_protocol(&json_string_any(&definition, &["type", "protocol"]));
    if protocol.is_empty() { return None; }
    let name = decode_node_name(&json_string_any(&definition, &["name", "tag", "remarks"]));
    let name = if name.is_empty() { format!("{} {}", protocol, occurrence + 1) } else { name };
    let server = json_string_any(&definition, &["server", "address", "host"]);
    let port = json_u16_any(&definition, &["port", "server_port", "server-port"]);
    let transport = transport_from_definition(&definition);
    let mut security = security_from_definition(&definition);
    if security.is_empty() && matches!(protocol.as_str(), "hysteria2" | "trojan") {
        security = "tls".to_string();
    }
    let targets = if server.is_empty() || port == 0 { Vec::new() } else { targets_for_definition(&protocol, &transport, &definition) };
    Some(normalize_stored_node(SubscriptionNode {
        id: stable_node_id(subscription_id, &protocol, &name, occurrence),
        name,
        protocol: protocol.clone(),
        transport,
        security,
        server,
        port,
        definition,
        targets,
        share_link,
    }))
}

pub(super) fn definition_from_uri(line: &str) -> Option<JsonValue> {
    let original = line.trim();
    let scheme = original.split_once("://")?.0.to_ascii_lowercase();
    if scheme == "vmess" && !line.split_once("vmess://").map(|(_, tail)| tail).unwrap_or("").contains('@') {
        let encoded = line.trim().strip_prefix("vmess://")?.split('#').next()?;
        let bytes = general_purpose::STANDARD.decode(encoded)
            .or_else(|_| general_purpose::STANDARD_NO_PAD.decode(encoded))
            .or_else(|_| general_purpose::URL_SAFE.decode(encoded))
            .or_else(|_| general_purpose::URL_SAFE_NO_PAD.decode(encoded)).ok()?;
        let mut value: JsonValue = serde_json::from_slice(&bytes).ok()?;
        let obj = value.as_object_mut()?;
        obj.insert("type".to_string(), json!("vmess"));
        if let Some(v) = obj.remove("ps") { obj.insert("name".to_string(), v); }
        if let Some(v) = obj.remove("add") { obj.insert("server".to_string(), v); }
        if let Some(v) = obj.remove("id") { obj.insert("uuid".to_string(), v); }
        if let Some(v) = obj.remove("aid") { obj.insert("alterId".to_string(), v); }
        if let Some(v) = obj.remove("net") { obj.insert("network".to_string(), v); }
        obj.insert("_zdt_share_link".to_string(), json!(original));
        return Some(value);
    }
    if scheme == "ss" {
        let body = line.trim().strip_prefix("ss://")?;
        let authority = body.split('#').next()?.split('?').next()?;
        if !authority.contains('@') {
            let decoded = general_purpose::STANDARD.decode(authority)
                .or_else(|_| general_purpose::STANDARD_NO_PAD.decode(authority))
                .or_else(|_| general_purpose::URL_SAFE.decode(authority))
                .or_else(|_| general_purpose::URL_SAFE_NO_PAD.decode(authority)).ok()
                .and_then(|bytes| String::from_utf8(bytes).ok())?;
            if decoded.contains('@') {
                let fragment = line.split_once('#').map(|(_, value)| value).unwrap_or("");
                let query = body.split_once('?').map(|(_, value)| value.split('#').next().unwrap_or(""));
                let rebuilt = match query.filter(|value| !value.is_empty()) {
                    Some(query) => format!("ss://{decoded}?{query}#{fragment}"),
                    None => format!("ss://{decoded}#{fragment}"),
                };
                let mut value = definition_from_uri(&rebuilt)?;
                value.as_object_mut()?.insert("_zdt_share_link".to_string(), json!(original));
                return Some(value);
            }
        }
    }

    let url = Url::parse(line).ok()?;
    let mut obj = serde_json::Map::<String, JsonValue>::new();
    obj.insert("type".to_string(), json!(normalized_protocol(&scheme)));
    if let Some(host) = url.host_str() { obj.insert("server".to_string(), json!(host)); }
    if let Some(port) = url.port() { obj.insert("port".to_string(), json!(port)); }
    let name = decode_node_name(url.fragment().unwrap_or(""));
    if !name.is_empty() { obj.insert("name".to_string(), json!(name)); }
    let user = percent_decode_str(url.username()).decode_utf8_lossy().into_owned();
    let password = percent_decode_str(url.password().unwrap_or("")).decode_utf8_lossy().into_owned();
    match normalized_protocol(&scheme).as_str() {
        "vless" | "vmess" => { if !user.is_empty() { obj.insert("uuid".to_string(), json!(user)); } }
        "trojan" => {
            if !user.is_empty() { obj.insert("password".to_string(), json!(user)); }
            if !password.is_empty() { obj.insert("password".to_string(), json!(password)); }
        }
        "hysteria2" => {
            let auth = if password.is_empty() { user.clone() } else { format!("{user}:{password}") };
            if !auth.is_empty() { obj.insert("password".to_string(), json!(auth)); }
        }
        "wireguard" => { if !user.is_empty() { obj.insert("private-key".to_string(), json!(user)); } }
        "shadowsocks" => {
            let credential = if !password.is_empty() {
                format!("{user}:{password}")
            } else {
                general_purpose::STANDARD.decode(&user)
                    .or_else(|_| general_purpose::STANDARD_NO_PAD.decode(&user))
                    .or_else(|_| general_purpose::URL_SAFE.decode(&user))
                    .or_else(|_| general_purpose::URL_SAFE_NO_PAD.decode(&user)).ok()
                    .and_then(|bytes| String::from_utf8(bytes).ok())
                    .unwrap_or_else(|| user.to_string())
            };
            if let Some((method, secret)) = credential.split_once(':') {
                obj.insert("cipher".to_string(), json!(method));
                obj.insert("password".to_string(), json!(secret));
            }
        }
        _ => {
            if !user.is_empty() { obj.insert("username".to_string(), json!(user)); }
            if !password.is_empty() { obj.insert("password".to_string(), json!(password)); }
        }
    }
    for (key, value) in url.query_pairs() {
        let key = key.into_owned();
        let value = value.into_owned();
        // Xray-style links use `type` for the transport (tcp/ws/grpc/xhttp).
        // It must never replace the actual protocol derived from the URI scheme.
        if key.eq_ignore_ascii_case("type") && matches!(normalized_protocol(&scheme).as_str(), "vless" | "vmess" | "trojan") {
            obj.insert("network".to_string(), json!(value));
        } else {
            obj.insert(key, json!(value));
        }
    }
    obj.insert("_zdt_share_link".to_string(), json!(original));
    Some(JsonValue::Object(obj))
}

/// Convert one Xray outbound into the Clash-style definition that the rest of
/// this module already understands. Happ subscriptions commonly deliver whole
/// Xray JSON configs instead of Clash YAML or bare share links.
fn xray_outbound_to_definition(outbound: &JsonValue) -> Option<JsonValue> {
    let protocol = normalized_protocol(&json_string_any(outbound, &["protocol", "type"]));
    if !matches!(protocol.as_str(), "vless" | "vmess" | "trojan" | "shadowsocks" | "socks" | "hysteria2" | "tuic") {
        return None;
    }
    let settings = outbound.get("settings");
    let stream = outbound.get("streamSettings");
    let first_setting = |keys: &[&str]| -> Option<&JsonValue> {
        settings.and_then(|s| s.get(keys[0])).and_then(JsonValue::as_array)
            .and_then(|arr| arr.first())
    };
    let (server, port, user) = match protocol.as_str() {
        "vless" | "vmess" => {
            let vnext = first_setting(&["vnext"])?;
            let user = vnext.get("users").and_then(JsonValue::as_array).and_then(|arr| arr.first()).cloned().unwrap_or_else(|| json!({}));
            (json_string_any(vnext, &["address"]), json_u16_any(vnext, &["port"]), user)
        }
        "trojan" | "shadowsocks" | "socks" | "hysteria2" | "tuic" => {
            let entry = first_setting(&["servers"])?;
            (json_string_any(entry, &["address"]), json_u16_any(entry, &["port"]), entry.clone())
        }
        _ => return None,
    };
    if server.is_empty() || port == 0 { return None; }

    let name = json_string_any(outbound, &["tag", "name", "remarks"]);
    let mut definition = json!({
        "type": protocol,
        "name": name,
        "server": server,
        "port": port,
    });
    let obj = definition.as_object_mut()?;

    // Credentials.
    let uuid = json_string_any(&user, &["id", "uuid"]);
    if !uuid.is_empty() { obj.insert("uuid".into(), json!(uuid)); }
    let password = json_string_any(&user, &["password", "pass"]);
    if !password.is_empty() { obj.insert("password".into(), json!(password)); }
    let method = json_string_any(&user, &["method", "cipher"]);
    if !method.is_empty() { obj.insert("cipher".into(), json!(method)); }
    let alter_id = json_u16_any(&user, &["alterId", "alterid"]);
    if alter_id > 0 { obj.insert("alterId".into(), json!(alter_id)); }
    let flow = json_string_any(&user, &["flow"]);
    if !flow.is_empty() { obj.insert("flow".into(), json!(flow)); }
    let encryption = json_string_any(&user, &["encryption"]);
    if !encryption.is_empty() && !encryption.eq_ignore_ascii_case("none") {
        obj.insert("encryption".into(), json!(encryption));
    }
    let username = json_string_any(&user, &["user", "username"]);
    if !username.is_empty() { obj.insert("username".into(), json!(username)); }

    // Transport and security from streamSettings.
    let network = stream.map(|v| json_string_any(v, &["network"])).unwrap_or_default();
    if !network.is_empty() { obj.insert("network".into(), json!(network)); }
    let security = stream.map(|v| json_string_any(v, &["security"])).unwrap_or_default();
    if !security.is_empty() && !security.eq_ignore_ascii_case("none") {
        obj.insert("security".into(), json!(security));
    }

    let tls = stream.and_then(|v| v.get("tlsSettings"));
    let reality = stream.and_then(|v| v.get("realitySettings"));
    let mut sni = json_string_any(tls.unwrap_or(&json!({})), &["serverName"]);
    if sni.is_empty() {
        sni = json_string_any(reality.unwrap_or(&json!({})), &["serverName"]);
    }
    if !sni.is_empty() { obj.insert("servername".into(), json!(sni)); }
    if let Some(public_key) = reality.map(|v| json_string_any(v, &["publicKey"])).filter(|s| !s.is_empty()) {
        obj.insert("pbk".into(), json!(public_key));
        obj.insert("security".into(), json!("reality"));
    }
    if let Some(short_id) = reality.map(|v| json_string_any(v, &["shortId"])).filter(|s| !s.is_empty()) {
        obj.insert("sid".into(), json!(short_id));
    }
    let insecure = tls.map(|v| v.get("allowInsecure").and_then(JsonValue::as_bool) == Some(true)).unwrap_or(false);
    if insecure { obj.insert("skip-cert-verify".into(), json!(true)); }
    if tls.is_some() && security.is_empty() && reality.is_none() {
        obj.insert("tls".into(), json!(true));
    }

    // Per-transport settings.
    for (transport_key, _path_key, _host_key) in [
        ("wsSettings", "path", "host"),
        ("httpupgradeSettings", "path", "host"),
        ("splithttpSettings", "path", "host"),
        ("xhttpSettings", "path", "host"),
    ] {
        if let Some(section) = stream.and_then(|v| v.get(transport_key)) {
            let path = json_string_any(section, &["path"]);
            if !path.is_empty() { obj.insert("path".into(), json!(path)); }
            let mut host = json_string_any(section, &["host"]);
            if host.is_empty() {
                host = section.get("headers").map(|h| json_string_any(h, &["Host", "host"])).unwrap_or_default();
            }
            if !host.is_empty() { obj.insert("host".into(), json!(host)); }
            let mode = json_string_any(section, &["mode"]);
            if !mode.is_empty() { obj.insert("mode".into(), json!(mode)); }
            break;
        }
    }
    if let Some(grpc) = stream.and_then(|v| v.get("grpcSettings")) {
        let service = json_string_any(grpc, &["serviceName"]);
        if !service.is_empty() { obj.insert("path".into(), json!(service)); }
    }

    Some(definition)
}

/// Detect Xray-style JSON (a config with `outbounds`, an array of configs, or
/// a bare outbound) and return definitions for all proxy outbounds.
pub(super) fn xray_outbound_definitions(text: &str) -> Option<Vec<JsonValue>> {
    let trimmed = text.trim();
    if !(trimmed.starts_with('{') || trimmed.starts_with('[')) { return None; }
    let value: JsonValue = serde_json::from_str(trimmed).ok()?;
    let mut outbounds = Vec::<JsonValue>::new();
    match &value {
        JsonValue::Array(items) => {
            for item in items {
                if let Some(list) = item.get("outbounds").and_then(JsonValue::as_array) {
                    outbounds.extend(list.iter().cloned());
                } else if item.get("protocol").is_some() || item.get("type").is_some() {
                    outbounds.push(item.clone());
                }
            }
        }
        JsonValue::Object(_) => {
            if let Some(list) = value.get("outbounds").and_then(JsonValue::as_array) {
                outbounds.extend(list.iter().cloned());
            } else if value.get("protocol").is_some() || value.get("type").is_some() {
                outbounds.push(value.clone());
            }
        }
        _ => return None,
    }
    if outbounds.is_empty() { return None; }
    let definitions: Vec<JsonValue> = outbounds.iter().filter_map(xray_outbound_to_definition).collect();
    if definitions.is_empty() { None } else { Some(definitions) }
}

pub(super) fn parse_subscription_nodes(item: &Subscription, text: &str) -> Result<Vec<SubscriptionNode>> {
    let mut definitions = Vec::<JsonValue>::new();
    let mut candidates = vec![text.to_string()];
    if let Some(decoded) = decode_base64_subscription(text) { candidates.push(decoded); }
    for candidate in &candidates {
        if let Ok(root) = serde_yaml::from_str::<YamlValue>(candidate) {
            if let Some(proxies) = root.as_mapping()
                .and_then(|map| map.get(&YamlValue::String("proxies".to_string())))
                .and_then(YamlValue::as_sequence)
            {
                for proxy in proxies {
                    if let Ok(value) = serde_json::to_value(proxy) { definitions.push(value); }
                }
                if !definitions.is_empty() { break; }
            }
        }
        if let Some(mut xray) = xray_outbound_definitions(candidate) {
            definitions.append(&mut xray);
            break;
        }
        for line in candidate.lines().map(str::trim).filter(|line| is_supported_uri_line(line)) {
            if let Some(value) = definition_from_uri(line) { definitions.push(value); }
        }
        if !definitions.is_empty() { break; }
    }
    if definitions.is_empty() { bail!("subscription contains no readable server nodes"); }

    let mut identity_occurrences = BTreeMap::<String, usize>::new();
    let mut nodes = Vec::new();
    for definition in definitions {
        let protocol = normalized_protocol(&json_string_any(&definition, &["type", "protocol"]));
        let name = json_string_any(&definition, &["name", "tag", "remarks"]);
        let identity = format!("{}\0{}", protocol, name.trim().to_lowercase());
        let occurrence = identity_occurrences.entry(identity).or_insert(0);
        if let Some(node) = node_from_definition(&item.id, definition, *occurrence) { nodes.push(node); }
        *occurrence += 1;
    }
    Ok(nodes)
}

pub(super) fn bool_any(value: &JsonValue, keys: &[&str]) -> bool {
    keys.iter().find_map(|key| value.get(*key)).map(|v| {
        v.as_bool().unwrap_or_else(|| matches!(v.as_str().unwrap_or("").to_ascii_lowercase().as_str(), "1" | "true" | "yes"))
    }).unwrap_or(false)
}

