use anyhow::{bail, Context, Result};
use reqwest::{header::{HeaderName, HeaderValue}, Url};
use serde_json::{json, Value as JsonValue};
use sha2::{Digest, Sha256};
use std::{fs};

use super::model::*;
use super::storage::*;
use super::render::*;
use super::import::*;
use super::provider::*;
use super::worker::*;

pub(super) fn normalize_input(input: SubscriptionInput) -> Result<SubscriptionInput> {
    let mut i = input;
    i.name = i.name.trim().to_string();
    i.url = i.url.trim().to_string();
    i.basic_username = i.basic_username.trim().to_string();
    i.hwid = i.hwid.trim().to_string();
    i.hwid_mode = i.hwid_mode.trim().to_ascii_lowercase();
    i.user_agent = i.user_agent.trim().to_string();
    i.device_locale = i.device_locale.trim().to_string();
    i.device_os = i.device_os.trim().to_string();
    i.os_version = i.os_version.trim().to_string();
    i.device_model = i.device_model.trim().to_string();
    i.update_interval_minutes = i.update_interval_minutes.clamp(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES);
    i.custom_headers = i.custom_headers.into_iter()
        .map(|(k, v)| (k.trim().to_string(), v.trim().to_string()))
        .filter(|(k, v)| !k.is_empty() && !v.is_empty())
        .collect();

    if i.name.is_empty() || i.name.chars().count() > 80 { bail!("subscription name must be 1..80 characters"); }
    if i.url.len() > 8192 { bail!("subscription URL is too long"); }
    let url = Url::parse(&i.url).context("invalid subscription URL")?;
    if !matches!(url.scheme(), "http" | "https") { bail!("subscription URL must use http or https"); }
    if i.hwid_enabled && i.hwid.is_empty() { bail!("HWID is enabled but empty"); }
    if !matches!(i.hwid_mode.as_str(), "header" | "cookie") { bail!("hwid_mode must be header or cookie"); }
    if i.basic_enabled && i.basic_username.is_empty() { bail!("Basic auth username is empty"); }
    if i.device_os.is_empty() { i.device_os = default_device_os(); }
    for key in i.custom_headers.keys() {
        let lower = key.to_ascii_lowercase();
        if matches!(lower.as_str(), "host" | "content-length" | "connection") {
            bail!("custom header is managed by the HTTP client: {key}");
        }
        if i.hwid_enabled && i.hwid_mode == "header" && lower == "x-hwid" {
            bail!("X-HWID is already managed by the HWID settings");
        }
        if i.hwid_enabled && i.hwid_mode == "cookie" && lower == "cookie" {
            bail!("Cookie is already managed by the HWID settings");
        }
        HeaderName::from_bytes(key.as_bytes()).with_context(|| format!("invalid HTTP header name: {key}"))?;
        let value = i.custom_headers.get(key).map(String::as_str).unwrap_or("");
        HeaderValue::from_str(value).with_context(|| format!("invalid HTTP header value for {key}"))?;
    }
    Ok(i)
}

pub(super) fn generate_id(name: &str, url: &str) -> String {
    let mut h = Sha256::new();
    h.update(name.as_bytes());
    h.update(b"\0");
    h.update(url.as_bytes());
    h.update(b"\0");
    h.update(now_unix().to_le_bytes());
    h.update(std::process::id().to_le_bytes());
    let digest = hex::encode(h.finalize());
    format!("sub_{}", &digest[..16])
}

pub fn create(input: SubscriptionInput) -> Result<Subscription> {
    let _store_guard = store_lock().lock().map_err(|_| anyhow::anyhow!("subscription store lock poisoned"))?;
    let input = normalize_input(input)?;
    let mut store = read_store()?;
    let mut id = generate_id(&input.name, &input.url);
    let mut suffix = 0u32;
    while store.subscriptions.contains_key(&id) {
        suffix += 1;
        id = format!("{}_{}", generate_id(&input.name, &input.url), suffix);
    }
    let now = now_unix();
    let item = Subscription {
        id: id.clone(), name: input.name, url: input.url, enabled: input.enabled,
        basic_enabled: input.basic_enabled, basic_username: input.basic_username, basic_password: input.basic_password,
        hwid_enabled: input.hwid_enabled, hwid: input.hwid, hwid_mode: input.hwid_mode,
        user_agent: input.user_agent, send_device_headers: input.send_device_headers,
        device_locale: input.device_locale, device_os: input.device_os, os_version: input.os_version, device_model: input.device_model,
        custom_headers: input.custom_headers, use_remote_interval: input.use_remote_interval,
        update_interval_minutes: input.update_interval_minutes, created_at: now, modified_at: now,
    };
    store.subscriptions.insert(id.clone(), item.clone());
    write_store(&store)?;
    fs::create_dir_all(data_root().join(&id))?;
    let _ = enqueue_refresh(&id);
    Ok(item)
}

pub fn update(id: &str, input: SubscriptionInput) -> Result<Subscription> {
    let _store_guard = store_lock().lock().map_err(|_| anyhow::anyhow!("subscription store lock poisoned"))?;
    ensure_id(id)?;
    let input = normalize_input(input)?;
    let mut store = read_store()?;
    let old = store.subscriptions.get(id).cloned().ok_or_else(|| anyhow::anyhow!("subscription not found"))?;
    let item = Subscription {
        id: id.to_string(), name: input.name, url: input.url, enabled: input.enabled,
        basic_enabled: input.basic_enabled, basic_username: input.basic_username, basic_password: input.basic_password,
        hwid_enabled: input.hwid_enabled, hwid: input.hwid, hwid_mode: input.hwid_mode,
        user_agent: input.user_agent, send_device_headers: input.send_device_headers,
        device_locale: input.device_locale, device_os: input.device_os, os_version: input.os_version, device_model: input.device_model,
        custom_headers: input.custom_headers, use_remote_interval: input.use_remote_interval,
        update_interval_minutes: input.update_interval_minutes, created_at: old.created_at, modified_at: now_unix().max(old.modified_at.saturating_add(1)),
    };
    store.subscriptions.insert(id.to_string(), item.clone());
    write_store(&store)?;
    if item.enabled { let _ = enqueue_refresh(id); }
    Ok(item)
}

pub fn set_enabled(id: &str, enabled: bool) -> Result<Subscription> {
    let _store_guard = store_lock().lock().map_err(|_| anyhow::anyhow!("subscription store lock poisoned"))?;
    ensure_id(id)?;
    let mut store = read_store()?;
    let item = store.subscriptions.get_mut(id).ok_or_else(|| anyhow::anyhow!("subscription not found"))?;
    item.enabled = enabled;
    item.modified_at = now_unix().max(item.modified_at.saturating_add(1));
    let out = item.clone();
    write_store(&store)?;
    if enabled { let _ = enqueue_refresh(id); }
    Ok(out)
}

pub fn delete(id: &str) -> Result<()> {
    let _store_guard = store_lock().lock().map_err(|_| anyhow::anyhow!("subscription store lock poisoned"))?;
    ensure_id(id)?;
    let mut store = read_store()?;
    if store.subscriptions.remove(id).is_none() { bail!("subscription not found"); }
    write_store(&store)?;
    // Imported servers are intentionally retained.  Their links are kept as
    // missing so the UI can explain that the local copy is still available.
    {
        let _links_guard = links_lock().lock().map_err(|_| anyhow::anyhow!("subscription links lock poisoned"))?;
        let mut links = read_links().unwrap_or_default();
        for link in links.links.values_mut().filter(|link| link.subscription_id == id) {
            link.missing = true;
        }
        let _ = write_links(&links);
    }
    let dir = data_root().join(id);
    if dir.exists() { let _ = fs::remove_dir_all(dir); }
    crate::programs::mihomo::remove_subscription_from_all_profiles(id)?;
    cleanup_profile_provider_copies(id);
    Ok(())
}

pub fn get(id: &str) -> Result<Subscription> {
    ensure_id(id)?;
    let mut store = read_store()?;
    store.subscriptions.remove(id).ok_or_else(|| anyhow::anyhow!("subscription not found"))
}

pub fn list_view() -> Result<JsonValue> {
    let store = read_store()?;
    let links = read_links().unwrap_or_default();
    let mut items = Vec::<JsonValue>::new();
    for item in store.subscriptions.values() {
        let status = read_status(&item.id);
        let profiles = crate::programs::mihomo::profiles_using_subscription(&item.id);
        let imported_count = links.links.values().filter(|link| link.subscription_id == item.id).count();
        items.push(json!({
            "id": item.id,
            "name": item.name,
            "url": item.url,
            "enabled": item.enabled,
            "basic_enabled": item.basic_enabled,
            "hwid_enabled": item.hwid_enabled,
            "hwid_mode": item.hwid_mode,
            "user_agent": item.user_agent,
            "update_interval_minutes": item.update_interval_minutes,
            "use_remote_interval": item.use_remote_interval,
            "profiles": profiles,
            "imported_count": imported_count,
            "status": status,
            "refreshing": is_refreshing(&item.id),
        }));
    }
    items.sort_by(|a, b| a.get("name").and_then(|v| v.as_str()).unwrap_or("").to_lowercase().cmp(&b.get("name").and_then(|v| v.as_str()).unwrap_or("").to_lowercase()));
    Ok(json!({"ok": true, "items": items}))
}

pub fn full_view(id: &str) -> Result<JsonValue> {
    let item = get(id)?;
    let status = read_status(id);
    let profiles = crate::programs::mihomo::profiles_using_subscription(id);
    Ok(json!({"ok": true, "subscription": item, "status": status, "profiles": profiles, "refreshing": is_refreshing(id)}))
}

pub fn nodes_view(id: &str) -> Result<JsonValue> {
    ensure_id(id)?;
    let item = get(id)?;
    let nodes = read_nodes(id);
    let links = read_links().unwrap_or_default();
    let imports: Vec<&SubscriptionLink> = links.links.values()
        .filter(|link| link.subscription_id == id)
        .collect();
    Ok(json!({"ok": true, "subscription": {"id": item.id, "name": item.name}, "nodes": nodes, "imports": imports}))
}

pub fn export_node(subscription_id: &str, node_id: &str) -> Result<JsonValue> {
    ensure_id(subscription_id)?;
    if !node_id.starts_with("node_") { bail!("invalid node id"); }
    let node = read_nodes(subscription_id).into_iter()
        .find(|node| node.id == node_id)
        .ok_or_else(|| anyhow::anyhow!("subscription node not found"))?;
    let (content, format) = if !node.share_link.trim().is_empty() {
        (node.share_link.clone(), "uri")
    } else {
        match node.protocol.as_str() {
            "hysteria2" => (render_hysteria2_config(&node, 11590)?, "json"),
            "wireguard" => (render_wireproxy_config(&node, 1167)?, "conf"),
            "vless" | "vmess" | "trojan" | "shadowsocks" | "socks" => {
                let rendered = if node.targets.iter().any(|target| target == "sing-box") {
                    render_singbox_config(&node, 2080)?
                } else {
                    render_singbox_lx_config(&node, 1180)?
                };
                (rendered, "json")
            },
            _ => (serde_json::to_string_pretty(&node.definition)?, "json"),
        }
    };
    Ok(json!({"ok": true, "content": content, "format": format, "name": node.name}))
}

pub fn links_view(target: Option<&str>, profile: Option<&str>) -> Result<JsonValue> {
    let links = read_links()?;
    let mut items: Vec<SubscriptionLink> = links.links.into_values()
        .filter(|link| target.map(|v| link.target == v).unwrap_or(true))
        .filter(|link| profile.map(|v| link.profile == v).unwrap_or(true))
        .collect();
    items.sort_by(|a, b| (&a.target, &a.profile, &a.server_name).cmp(&(&b.target, &b.profile, &b.server_name)));
    Ok(json!({"ok": true, "items": items}))
}

pub fn link_for_target(target: &str, profile: &str, server_name: &str) -> Option<SubscriptionLink> {
    read_links().ok()?.links.into_values().find(|link| {
        link.target == target && link.profile == profile && link.server_name == server_name
    })
}

pub fn remove_link_for_target(target: &str, profile: &str, server_name: &str) {
    let Ok(_guard) = links_lock().lock() else { return; };
    let Ok(mut links) = read_links() else { return; };
    let before = links.links.len();
    links.links.retain(|_, link| !(link.target == target && link.profile == profile && link.server_name == server_name));
    if links.links.len() != before { let _ = write_links(&links); }
}

pub fn remove_links_for_profile(target: &str, profile: &str) {
    let Ok(_guard) = links_lock().lock() else { return; };
    let Ok(mut links) = read_links() else { return; };
    let before = links.links.len();
    links.links.retain(|_, link| !(link.target == target && link.profile == profile));
    if links.links.len() != before { let _ = write_links(&links); }
}

pub fn detach_link(link_id: &str) -> Result<()> {
    ensure_link_id(link_id)?;
    let _links_guard = links_lock().lock().map_err(|_| anyhow::anyhow!("subscription links lock poisoned"))?;
    let mut links = read_links()?;
    if links.links.remove(link_id).is_none() { bail!("subscription link not found"); }
    write_links(&links)
}

pub(super) fn ensure_id(id: &str) -> Result<()> {
    if id.len() < 5 || id.len() > 64 || !id.starts_with("sub_") || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        bail!("invalid subscription id");
    }
    Ok(())
}

