use anyhow::{bail, Result};
use percent_encoding::percent_decode_str;
use serde_json::{json, Value as JsonValue};
use std::{collections::{BTreeMap}, fs, path::{PathBuf}};

use super::model::*;
use super::parse::*;

pub(super) fn safe_server_name(raw: &str) -> String {
    let mut out: String = raw.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' }).collect();
    while out.contains("__") { out = out.replace("__", "_"); }
    out = out.trim_matches('_').chars().take(56).collect();
    if out.is_empty() { "subscription_server".to_string() } else { out }
}

pub(super) fn target_server_root(target: &str, profile: &str, server: &str) -> Result<PathBuf> {
    crate::programs::singbox::ensure_valid_profile_name(profile)?;
    crate::programs::singbox::ensure_valid_profile_name(server)?;
    let program = match target {
        "sing-box" => "singbox",
        "sing-box-lx" => "singbox-lx",
        "hysteria2" => "hysteria2",
        "wireproxy" => "wireproxy",
        _ => bail!("unsupported subscription import target"),
    };
    let profile_root = PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/{program}/profile/{profile}"));
    if !profile_root.is_dir() { bail!("target profile not found"); }
    Ok(profile_root.join("server").join(server))
}

pub(super) fn ensure_target_accepts_new_server(target: &str, profile: &str) -> Result<()> {
    if target == "sing-box-lx" && !crate::programs::singbox::lx::is_installed() {
        bail!("sing-box-lx optional component is not installed");
    }
    if !matches!(target, "sing-box" | "sing-box-lx" | "hysteria2") { return Ok(()); }
    let program = match target {
        "sing-box" => "singbox",
        "sing-box-lx" => "singbox-lx",
        _ => "hysteria2",
    };
    let root = PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/{program}/profile/{profile}"));
    let vpn_mode = fs::read_to_string(root.join("setting.json")).ok()
        .and_then(|raw| serde_json::from_str::<JsonValue>(&raw).ok())
        .and_then(|value| value.get("mode").and_then(JsonValue::as_str).map(str::to_string))
        .map(|mode| mode.eq_ignore_ascii_case("vpn"))
        .unwrap_or(false);
    let has_server = fs::read_dir(root.join("server")).ok().map(|entries| {
        entries.flatten().any(|entry| entry.path().is_dir() && !entry.file_name().to_string_lossy().starts_with('.'))
    }).unwrap_or(false);
    if vpn_mode && has_server { bail!("target VPN profile already contains a server"); }
    Ok(())
}

pub(super) fn next_local_port(start: u16) -> u16 {
    let used = crate::programs::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    (start..=u16::MAX).find(|port| !used.contains(port)).unwrap_or(start)
}

pub(super) fn endpoint_text(host: &str, port: u16) -> String {
    if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    }
}

pub(super) fn tls_json(definition: &JsonValue, default_enabled: bool) -> Option<JsonValue> {
    if let Some(tls) = definition.get("tls").filter(|value| value.is_object()) {
        return Some(tls.clone());
    }
    let security = json_string_any(definition, &["security", "tls"]);
    let enabled = default_enabled || security.eq_ignore_ascii_case("tls") || security.eq_ignore_ascii_case("reality") || bool_any(definition, &["tls"]);
    if !enabled { return None; }
    let sni = json_string_any(definition, &["sni", "servername", "serverName", "server-name", "server_name", "peer"]);
    let mut tls = json!({"enabled": true, "insecure": bool_any(definition, &["skip-cert-verify", "allowInsecure", "insecure"])});
    if !sni.is_empty() { tls["server_name"] = json!(sni); }
    let mut alpn_values = Vec::<String>::new();
    if let Some(alpn) = definition.get("alpn") {
        if let Some(raw) = alpn.as_str() {
            alpn_values.extend(raw.split(|c| c == ',' || c == '\n').map(str::trim).filter(|value| !value.is_empty()).map(str::to_string));
        } else if let Some(values) = alpn.as_array() {
            alpn_values.extend(values.iter().filter_map(JsonValue::as_str).map(str::trim).filter(|value| !value.is_empty()).map(str::to_string));
        }
    }
    if !alpn_values.is_empty() { tls["alpn"] = json!(alpn_values); }
    let reality = definition.get("reality-opts").or_else(|| definition.get("reality_opts"));
    let public_key = reality.map(|v| json_string_any(v, &["public-key", "public_key"]))
        .filter(|v| !v.is_empty()).unwrap_or_else(|| json_string_any(definition, &["pbk", "public-key", "public_key"]));
    let mut fp = json_string_any(definition, &["client-fingerprint", "client_fingerprint", "fingerprint", "fp"]);
    let fp_disabled = fp.eq_ignore_ascii_case("none");
    if fp_disabled { fp.clear(); }
    if fp.is_empty() && !fp_disabled && !public_key.is_empty() { fp = "chrome".to_string(); }
    if !fp.is_empty() { tls["utls"] = json!({"enabled": true, "fingerprint": fp}); }
    if !public_key.is_empty() {
        let short_id = reality.map(|v| json_string_any(v, &["short-id", "short_id"]))
            .filter(|v| !v.is_empty()).unwrap_or_else(|| json_string_any(definition, &["sid", "short-id"]));
        tls["reality"] = json!({"enabled": true, "public_key": public_key, "short_id": short_id});
    }
    Some(tls)
}

pub(super) const XHTTP_MODES: &[&str] = &["auto", "packet-up", "stream-up", "stream-one"];
pub(super) const XHTTP_PLACEMENTS: &[&str] = &["path", "query", "header", "cookie"];
pub(super) const XHTTP_UPLINK_PLACEMENTS: &[&str] = &["body", "auto", "header", "cookie"];
pub(super) const XHTTP_PADDING_PLACEMENTS: &[&str] = &["cookie", "header", "query", "queryInHeader"];
pub(super) const XHTTP_PADDING_METHODS: &[&str] = &["repeat-x", "tokenish"];

pub(super) fn json_value_fold<'a>(value: &'a JsonValue, keys: &[&str]) -> Option<&'a JsonValue> {
    let object = value.as_object()?;
    keys.iter().find_map(|wanted| {
        object.iter().find(|(key, _)| key.eq_ignore_ascii_case(wanted)).map(|(_, value)| value)
    })
}

pub(super) fn json_scalar_string(value: &JsonValue) -> Option<String> {
    match value {
        JsonValue::String(value) => {
            let value = value.trim();
            if value.is_empty() { None } else { Some(value.to_string()) }
        }
        JsonValue::Number(value) => {
            let raw = value.to_string();
            if let Ok(parsed) = raw.parse::<f64>() {
                if parsed.is_finite() && parsed.fract() == 0.0 {
                    return Some(format!("{:.0}", parsed));
                }
            }
            Some(raw)
        }
        JsonValue::Bool(value) => Some(value.to_string()),
        _ => None,
    }
}

pub(super) fn xhttp_query_map(share_link: &str) -> BTreeMap<String, String> {
    let mut out = BTreeMap::new();
    let Some((_, raw_query)) = share_link.split_once('?') else { return out; };
    let raw_query = raw_query.split('#').next().unwrap_or(raw_query);
    for pair in raw_query.split('&') {
        if pair.is_empty() { continue; }
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        let key = percent_decode_str(key).decode_utf8_lossy().into_owned();
        if key.trim().is_empty() { continue; }
        // percent_decode_str deliberately keeps '+' literal, matching the
        // Xray/LxBox XHTTP path contract instead of form-url decoding it.
        let value = percent_decode_str(value).decode_utf8_lossy().into_owned();
        out.insert(key, value);
    }
    out
}

pub(super) fn map_string_fold(map: &BTreeMap<String, String>, keys: &[&str]) -> Option<String> {
    keys.iter().find_map(|wanted| {
        map.iter().find(|(key, value)| key.eq_ignore_ascii_case(wanted) && !value.trim().is_empty())
            .map(|(_, value)| value.trim().to_string())
    })
}

pub(super) fn map_raw_fold(map: &BTreeMap<String, String>, keys: &[&str]) -> Option<String> {
    keys.iter().find_map(|wanted| {
        map.iter().find(|(key, _)| key.eq_ignore_ascii_case(wanted))
            .map(|(_, value)| value.to_string())
    })
}

pub(super) fn parse_bool_spelled(raw: &str) -> Option<bool> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "1" | "true" => Some(true),
        "0" | "false" => Some(false),
        _ => None,
    }
}

pub(super) fn parse_flexible_i64(raw: &str) -> Option<i64> {
    let raw = raw.trim();
    if let Ok(value) = raw.parse::<i64>() { return Some(value); }
    let value = raw.parse::<f64>().ok()?;
    if !value.is_finite() || value.fract() != 0.0 || value < i64::MIN as f64 || value > i64::MAX as f64 {
        return None;
    }
    Some(value as i64)
}

pub(super) fn normalize_sc_range(raw: &str) -> Option<String> {
    let raw = raw.trim();
    if raw.is_empty() { return None; }
    if let Ok(value) = raw.parse::<f64>() {
        if value.is_finite() && value.fract() == 0.0 { return Some(format!("{:.0}", value)); }
    }
    Some(raw.to_string())
}

pub(super) fn has_percent_escape(raw: &str) -> bool {
    let bytes = raw.as_bytes();
    bytes.windows(3).any(|chunk| {
        chunk[0] == b'%' && chunk[1].is_ascii_hexdigit() && chunk[2].is_ascii_hexdigit()
    })
}

pub(super) fn normalize_xhttp_path(mut path: String) -> String {
    // URI query parsing already performs one percent-decode. Xray/LxBox links
    // are commonly double-encoded, so mirror the Android one-line importer and
    // peel at most two remaining valid percent-escape layers. Literal '+' is
    // preserved because percent_decode_str does not apply form-url semantics.
    for _ in 0..2 {
        if !has_percent_escape(&path) { break; }
        let decoded = percent_decode_str(&path).decode_utf8_lossy().into_owned();
        if decoded == path { break; }
        path = decoded;
    }
    if path.is_empty() { "/".to_string() } else { path }
}

pub(super) fn xhttp_transport(node: &SubscriptionNode) -> JsonValue {
    let d = &node.definition;
    let opts = d.get("xhttp-opts").or_else(|| d.get("xhttp_opts")).filter(|value| value.is_object());
    let flat_json = opts.unwrap_or(d);
    let query = xhttp_query_map(&node.share_link);

    let flat = |keys: &[&str]| -> Option<String> {
        map_string_fold(&query, keys).or_else(|| json_value_fold(flat_json, keys).and_then(json_scalar_string))
    };

    let extra = map_string_fold(&query, &["extra"])
        .and_then(|raw| serde_json::from_str::<JsonValue>(&raw).ok())
        .filter(|value| value.is_object())
        .or_else(|| {
            let value = json_value_fold(flat_json, &["extra"])?;
            if value.is_object() {
                Some(value.clone())
            } else {
                json_scalar_string(value)
                    .and_then(|raw| serde_json::from_str::<JsonValue>(&raw).ok())
                    .filter(|parsed| parsed.is_object())
            }
        })
        .unwrap_or_else(|| json!({}));
    let extra_xmux = json_value_fold(&extra, &["xmux"]).filter(|value| value.is_object());

    let extra_scalar = |keys: &[&str]| -> Option<String> {
        json_value_fold(&extra, keys).and_then(json_scalar_string)
    };
    let extended = |keys: &[&str]| -> Option<String> { extra_scalar(keys).or_else(|| flat(keys)) };
    let extended_bool = |keys: &[&str]| -> Option<bool> { extended(keys).as_deref().and_then(parse_bool_spelled) };
    let extended_int = |keys: &[&str]| -> Option<i64> { extended(keys).as_deref().and_then(parse_flexible_i64) };
    let extended_xmux = |keys: &[&str]| -> Option<String> {
        extra_scalar(keys)
            .or_else(|| extra_xmux.and_then(|xmux| json_value_fold(xmux, keys)).and_then(json_scalar_string))
            .or_else(|| flat(keys))
    };

    let mut transport = serde_json::Map::new();
    transport.insert("type".to_string(), json!("xhttp"));

    if let Some(path) = map_raw_fold(&query, &["path"]) {
        transport.insert("path".to_string(), json!(normalize_xhttp_path(path)));
    } else if let Some(value) = json_value_fold(flat_json, &["path"]) {
        if let Some(path) = value.as_str() {
            transport.insert("path".to_string(), json!(normalize_xhttp_path(path.to_string())));
        }
    }
    if let Some(host) = flat(&["host"]) { transport.insert("host".to_string(), json!(host)); }
    if let Some(headers) = json_value_fold(flat_json, &["headers"]).and_then(JsonValue::as_object) {
        let normalized: serde_json::Map<String, JsonValue> = headers.iter()
            .filter_map(|(key, value)| json_scalar_string(value).map(|value| (key.clone(), json!(value))))
            .collect();
        if !normalized.is_empty() { transport.insert("headers".to_string(), JsonValue::Object(normalized)); }
    }

    let raw_mode = flat(&["mode"]);
    let mut mode = raw_mode.as_deref().filter(|value| XHTTP_MODES.contains(value)).map(str::to_string);

    if let Some(value) = extended(&["xPaddingBytes", "x_padding_bytes"]) { transport.insert("x_padding_bytes".to_string(), json!(value)); }

    if let Some(value) = extended(&["sessionPlacement", "session_placement", "sessionIDPlacement"])
        .filter(|value| XHTTP_PLACEMENTS.contains(&value.as_str()))
    { transport.insert("session_placement".to_string(), json!(value)); }
    if let Some(value) = extended(&["sessionKey", "session_key", "sessionIDKey"]) { transport.insert("session_key".to_string(), json!(value)); }

    if let Some(value) = extended(&["seqPlacement", "seq_placement"])
        .filter(|value| XHTTP_PLACEMENTS.contains(&value.as_str()))
    { transport.insert("seq_placement".to_string(), json!(value)); }
    if let Some(value) = extended(&["seqKey", "seq_key"]) { transport.insert("seq_key".to_string(), json!(value)); }

    let mut uplink_placement = extended(&["uplinkDataPlacement", "uplink_data_placement"])
        .filter(|value| XHTTP_UPLINK_PLACEMENTS.contains(&value.as_str()));
    if matches!(uplink_placement.as_deref(), Some("header" | "cookie")) {
        if raw_mode.as_deref().unwrap_or("").trim().is_empty() {
            mode = Some("packet-up".to_string());
        } else if mode.as_deref() != Some("packet-up") {
            uplink_placement = None;
        }
    }
    if let Some(value) = uplink_placement { transport.insert("uplink_data_placement".to_string(), json!(value)); }
    if let Some(value) = extended(&["uplinkDataKey", "uplink_data_key"]) { transport.insert("uplink_data_key".to_string(), json!(value)); }
    if let Some(value) = extended(&["uplinkChunkSize", "uplink_chunk_size"]) { transport.insert("uplink_chunk_size".to_string(), json!(value)); }
    if let Some(value) = extended(&["uplinkHTTPMethod", "uplink_http_method"]) { transport.insert("uplink_http_method".to_string(), json!(value)); }

    if extended_bool(&["xPaddingObfsMode", "x_padding_obfs_mode"]) == Some(true) { transport.insert("x_padding_obfs_mode".to_string(), json!(true)); }
    if let Some(value) = extended(&["xPaddingKey", "x_padding_key"]) { transport.insert("x_padding_key".to_string(), json!(value)); }
    if let Some(value) = extended(&["xPaddingHeader", "x_padding_header"]) { transport.insert("x_padding_header".to_string(), json!(value)); }
    if let Some(value) = extended(&["xPaddingPlacement", "x_padding_placement"])
        .filter(|value| XHTTP_PADDING_PLACEMENTS.contains(&value.as_str()))
    { transport.insert("x_padding_placement".to_string(), json!(value)); }
    if let Some(value) = extended(&["xPaddingMethod", "x_padding_method"])
        .filter(|value| XHTTP_PADDING_METHODS.contains(&value.as_str()))
    { transport.insert("x_padding_method".to_string(), json!(value)); }

    for (keys, output) in [
        (&["scMaxEachPostBytes", "sc_max_each_post_bytes"][..], "sc_max_each_post_bytes"),
        (&["scMinPostsIntervalMs", "sc_min_posts_interval_ms"][..], "sc_min_posts_interval_ms"),
        (&["scStreamUpServerSecs", "sc_stream_up_server_secs"][..], "sc_stream_up_server_secs"),
    ] {
        if let Some(value) = extended(keys).as_deref().and_then(normalize_sc_range) {
            transport.insert(output.to_string(), json!(value));
        }
    }
    if extended_bool(&["noGRPCHeader", "no_grpc_header"]) == Some(true) { transport.insert("no_grpc_header".to_string(), json!(true)); }
    if extended_bool(&["noSSEHeader", "no_sse_header"]) == Some(true) { transport.insert("no_sse_header".to_string(), json!(true)); }
    if let Some(value) = extended_int(&["scMaxBufferedPosts", "sc_max_buffered_posts"]) { transport.insert("sc_max_buffered_posts".to_string(), json!(value)); }

    let mut xmux = serde_json::Map::new();
    for (keys, output) in [
        (&["maxConcurrency", "max_concurrency"][..], "max_concurrency"),
        (&["maxConnections", "max_connections"][..], "max_connections"),
        (&["cMaxReuseTimes", "c_max_reuse_times"][..], "c_max_reuse_times"),
        (&["hMaxRequestTimes", "h_max_request_times"][..], "h_max_request_times"),
        (&["hMaxReusableSecs", "h_max_reusable_secs"][..], "h_max_reusable_secs"),
    ] {
        if let Some(value) = extended_xmux(keys) { xmux.insert(output.to_string(), json!(value)); }
    }
    if let Some(value) = extended_xmux(&["hKeepAlivePeriod", "h_keep_alive_period"]).as_deref().and_then(parse_flexible_i64) {
        xmux.insert("h_keep_alive_period".to_string(), json!(value));
    }
    if !xmux.is_empty() { transport.insert("xmux".to_string(), JsonValue::Object(xmux)); }
    if let Some(value) = mode { transport.insert("mode".to_string(), json!(value)); }

    JsonValue::Object(transport)
}

pub(super) fn singbox_outbound(node: &SubscriptionNode, lx: bool) -> Result<JsonValue> {
    let d = &node.definition;
    let mut out = json!({"type": node.protocol, "tag": "proxy", "server": node.server, "server_port": node.port});
    match node.protocol.as_str() {
        "vless" => {
            out["uuid"] = json!(json_string_any(d, &["uuid", "id"]));
            let encryption = vless_encryption_from_definition(d);
            if let Some(encryption) = encryption.as_deref() {
                if !valid_vless_encryption_shape(encryption) {
                    bail!("VLESS encryption string is malformed");
                }
                if !lx {
                    bail!("VLESS encryption requires sing-box-lx");
                }
                out["encryption"] = json!(encryption);
            }
            let flow = json_string_any(d, &["flow"]);
            let has_transport = !node.transport.trim().is_empty() && !node.transport.eq_ignore_ascii_case("tcp");
            // Current LX supports Vision above the VLESS encryption layer. Without
            // encryption, keep the LX mapper conservative and drop Vision whenever
            // a transport is present instead of producing a core-rejected node.
            if !flow.is_empty() && !(lx && has_transport && encryption.is_none()) { out["flow"] = json!(flow); }
            let packet_encoding = json_string_any(d, &["packetEncoding", "packet_encoding", "packet-encoding"]);
            if !packet_encoding.is_empty() { out["packet_encoding"] = json!(packet_encoding); }
            if let Some(tls) = tls_json(d, false) { out["tls"] = tls; }
        }
        "vmess" => {
            out["uuid"] = json!(json_string_any(d, &["uuid", "id"]));
            out["security"] = json!(json_string_any(d, &["cipher", "security", "scy"]).trim().to_string());
            out["alter_id"] = json!(json_string_any(d, &["alterId", "alter-id", "aid"]).parse::<u64>().unwrap_or(0));
            let packet_encoding = json_string_any(d, &["packetEncoding", "packet_encoding", "packet-encoding"]);
            if !packet_encoding.is_empty() { out["packet_encoding"] = json!(packet_encoding); }
            if let Some(tls) = tls_json(d, false) { out["tls"] = tls; }
        }
        "trojan" => {
            out["password"] = json!(json_string_any(d, &["password"]));
            if let Some(tls) = tls_json(d, true) { out["tls"] = tls; }
        }
        "shadowsocks" => {
            out["method"] = json!(json_string_any(d, &["cipher", "method"]));
            out["password"] = json!(json_string_any(d, &["password"]));
            let plugin = json_string_any(d, &["plugin"]);
            if !plugin.is_empty() { out["plugin"] = json!(plugin); }
            let plugin_opts = json_string_any(d, &["plugin_opts", "plugin-opts"]);
            if !plugin_opts.is_empty() { out["plugin_opts"] = json!(plugin_opts); }
        }
        "socks" => {
            let username = json_string_any(d, &["username"]); if !username.is_empty() { out["username"] = json!(username); }
            let password = json_string_any(d, &["password"]); if !password.is_empty() { out["password"] = json!(password); }
        }
        "hysteria2" => {
            out["password"] = json!(json_string_any(d, &["password", "auth", "auth-str", "auth_str"]));
            if let Some(tls) = tls_json(d, true) { out["tls"] = tls; }
            let obfs = json_string_any(d, &["obfs"]);
            let obfs_password = json_string_any(d, &["obfs-password", "obfs_password"]);
            if !obfs.is_empty() { out["obfs"] = json!({"type": obfs, "password": obfs_password}); }
        }
        "wireguard" => {
            out["private_key"] = json!(json_string_any(d, &["private-key", "private_key", "privatekey"]));
            out["peer_public_key"] = json!(json_string_any(d, &["public-key", "public_key", "publickey", "peer_public_key"]));
            let psk = json_string_any(d, &["pre-shared-key", "pre_shared_key", "preshared-key", "presharedkey"]);
            if !psk.is_empty() { out["pre_shared_key"] = json!(psk); }
            let mut addresses = Vec::<String>::new();
            for key in ["ip", "ipv6", "address", "local_address"] {
                if let Some(v) = d.get(key) {
                    if let Some(s) = v.as_str() { if !s.trim().is_empty() { addresses.push(s.trim().to_string()); } }
                    if let Some(a) = v.as_array() { addresses.extend(a.iter().filter_map(JsonValue::as_str).map(str::to_string)); }
                }
            }
            if addresses.is_empty() { bail!("wireguard node has no local address"); }
            out["local_address"] = json!(addresses);
        }
        _ => bail!("node type is not supported by sing-box import"),
    }

    if let Some(transport) = d.get("transport").filter(|value| value.is_object()) {
        let mut transport = transport.clone();
        let transport_type = json_string_any(&transport, &["type"]).to_ascii_lowercase();
        if matches!(transport_type.as_str(), "xhttp" | "splithttp") {
            if !lx { bail!("XHTTP requires sing-box-lx"); }
            if let Some(object) = transport.as_object_mut() { object.insert("type".to_string(), json!("xhttp")); }
        }
        out["transport"] = transport;
        return Ok(out);
    }
    let network = match json_string_any(d, &["network", "type"]).to_ascii_lowercase().as_str() {
        "splithttp" => "xhttp".to_string(),
        value => value.to_string(),
    };
    if network == "ws" {
        let opts = d.get("ws-opts").or_else(|| d.get("ws_opts"));
        let path = opts.map(|v| json_string_any(v, &["path"])).filter(|v| !v.is_empty())
            .unwrap_or_else(|| json_string_any(d, &["ws-path", "path"]));
        out["transport"] = json!({"type":"ws", "path": path});
        if let Some(headers) = opts.and_then(|v| v.get("headers")).or_else(|| d.get("ws-headers")) {
            out["transport"]["headers"] = headers.clone();
        }
    } else if network == "grpc" {
        let opts = d.get("grpc-opts").or_else(|| d.get("grpc_opts"));
        let service = opts.map(|v| json_string_any(v, &["grpc-service-name", "service-name", "service_name"]))
            .filter(|v| !v.is_empty()).unwrap_or_else(|| json_string_any(d, &["grpc-service-name", "serviceName"]));
        out["transport"] = json!({"type":"grpc", "service_name": service});
    } else if network == "http" {
        let opts = d.get("http-opts").or_else(|| d.get("http_opts"));
        let path = opts.map(|v| json_string_any(v, &["path"]))
            .filter(|v| !v.is_empty()).unwrap_or_else(|| json_string_any(d, &["path"]));
        let host = opts.and_then(|v| v.get("headers")).map(|v| json_string_any(v, &["Host", "host"]))
            .filter(|v| !v.is_empty()).unwrap_or_else(|| json_string_any(d, &["host"]));
        out["transport"] = json!({"type":"http", "path": path});
        if !host.is_empty() { out["transport"]["host"] = json!([host]); }
    } else if network == "httpupgrade" {
        let path = json_string_any(d, &["path"]);
        let host = json_string_any(d, &["host"]);
        out["transport"] = json!({"type":"httpupgrade", "path": path});
        if !host.is_empty() { out["transport"]["host"] = json!(host); }
    } else if network == "xhttp" {
        if !lx { bail!("XHTTP requires sing-box-lx"); }
        out["transport"] = xhttp_transport(node);
    }
    Ok(out)
}

pub(super) fn render_singbox_config_for_target(node: &SubscriptionNode, local_port: u16, lx: bool) -> Result<String> {
    let outbound = singbox_outbound(node, lx)?;
    let config = json!({
        "log": {"level": "info"},
        "inbounds": [{"type":"mixed", "tag":"mixed-in", "listen":"127.0.0.1", "listen_port":local_port}],
        "outbounds": [outbound, {"type":"direct", "tag":"direct"}, {"type":"direct", "tag":"bypass"}],
        "route": {"rules":[{"inbound":["mixed-in"], "action":"sniff"}], "final":"proxy", "auto_detect_interface":true}
    });
    Ok(serde_json::to_string_pretty(&config)?)
}

pub(super) fn render_singbox_config(node: &SubscriptionNode, local_port: u16) -> Result<String> {
    render_singbox_config_for_target(node, local_port, false)
}

pub(super) fn render_singbox_lx_config(node: &SubscriptionNode, local_port: u16) -> Result<String> {
    render_singbox_config_for_target(node, local_port, true)
}

pub(super) fn render_hysteria2_config(node: &SubscriptionNode, local_port: u16) -> Result<String> {
    if node.protocol != "hysteria2" { bail!("node is not Hysteria2"); }
    let d = &node.definition;
    let sni = json_string_any(d, &["sni", "servername", "server-name", "peer"]);
    let mut config = json!({
        "server": endpoint_text(&node.server, node.port),
        "auth": json_string_any(d, &["password", "auth", "auth-str", "auth_str"]),
        "tls": {"insecure": bool_any(d, &["skip-cert-verify", "allowInsecure", "insecure"])},
        "socks5": {"listen": format!("127.0.0.1:{local_port}"), "disableUDP": false}
    });
    if !sni.is_empty() { config["tls"]["sni"] = json!(sni); }
    let obfs = json_string_any(d, &["obfs"]);
    let obfs_password = json_string_any(d, &["obfs-password", "obfs_password"]);
    if !obfs.is_empty() { config["obfs"] = json!({"type":obfs, "salamander":{"password":obfs_password}}); }
    Ok(serde_json::to_string_pretty(&config)?)
}

pub(super) fn render_wireproxy_config(node: &SubscriptionNode, local_port: u16) -> Result<String> {
    if node.protocol != "wireguard" { bail!("node is not WireGuard"); }
    let d = &node.definition;
    let private_key = json_string_any(d, &["private-key", "private_key", "privatekey"]);
    let public_key = json_string_any(d, &["public-key", "public_key", "publickey", "peer_public_key"]);
    if private_key.is_empty() || public_key.is_empty() { bail!("WireGuard keys are incomplete"); }
    let mut addresses = Vec::<String>::new();
    for key in ["ip", "ipv6", "address", "local_address"] {
        if let Some(v) = d.get(key) {
            if let Some(s) = v.as_str() { addresses.push(s.to_string()); }
            if let Some(a) = v.as_array() { addresses.extend(a.iter().filter_map(JsonValue::as_str).map(str::to_string)); }
        }
    }
    if addresses.is_empty() { bail!("WireGuard local address is missing"); }
    let psk = json_string_any(d, &["pre-shared-key", "pre_shared_key", "preshared-key", "presharedkey"]);
    let mut text = format!("[Interface]\nPrivateKey = {private_key}\nAddress = {}\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = {public_key}\n", addresses.join(", "));
    if !psk.is_empty() { text.push_str(&format!("PresharedKey = {psk}\n")); }
    text.push_str(&format!("Endpoint = {}\nAllowedIPs = 0.0.0.0/0, ::/0\n\n[Socks5]\nBindAddress = 127.0.0.1:{local_port}\n", endpoint_text(&node.server, node.port)));
    Ok(text)
}

