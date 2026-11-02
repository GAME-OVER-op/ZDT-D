use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose, Engine as _};
use reqwest::{
    blocking::{Client, Response},
    header::{HeaderMap, HeaderName, HeaderValue, LOCATION, USER_AGENT},
    redirect::Policy,
    Url,
};
use std::{io::Read, time::{Duration}};

use super::model::*;
use super::storage::*;
use super::crud::*;
use super::parse::*;
use super::import::*;
use super::provider::*;

pub(super) fn make_client() -> Result<Client> {
    Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(25))
        .redirect(Policy::none())
        .build()
        .context("build subscription HTTP client")
}

pub(super) fn local_day_of_month() -> u32 {
    std::process::Command::new("date")
        .arg("+%d")
        .output()
        .ok()
        .filter(|o| o.status.success())
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .and_then(|s| s.trim().parse::<u32>().ok())
        .filter(|d| (1..=31).contains(d))
        .unwrap_or(1)
}

pub(super) fn happ_compatible_user_agent(item: &Subscription) -> String {
    // Happ 4.3.0 alternates one digit by local day-of-month parity.
    // Keep this generated only when the user did not provide a custom UA.
    let parity_digit = if local_day_of_month() % 2 == 0 { '6' } else { '5' };
    let os = if item.device_os.eq_ignore_ascii_case("AndroidTV") { "AndroidTV" } else { "Android" };
    format!("Happ/4.3.0/{os}/17878408622471643{parity_digit}38")
}

pub(super) fn build_headers(item: &Subscription) -> Result<HeaderMap> {
    let mut headers = HeaderMap::new();
    let generated_ua;
    let ua = if item.user_agent.trim().is_empty() {
        generated_ua = happ_compatible_user_agent(item);
        generated_ua.as_str()
    } else {
        item.user_agent.trim()
    };
    headers.insert(USER_AGENT, HeaderValue::from_str(ua).context("invalid User-Agent")?);
    headers.insert("connection", HeaderValue::from_static("close"));
    if item.send_device_headers {
        if !item.device_locale.is_empty() { headers.insert("x-device-locale", HeaderValue::from_str(&item.device_locale)?); }
        if item.hwid_enabled && item.hwid_mode == "header" { headers.insert("x-hwid", HeaderValue::from_str(&item.hwid)?); }
        headers.insert("x-device-os", HeaderValue::from_str(if item.device_os.is_empty() { "Android" } else { &item.device_os })?);
        if !item.os_version.is_empty() { headers.insert("x-ver-os", HeaderValue::from_str(&item.os_version)?); }
        if !item.device_model.is_empty() { headers.insert("x-device-model", HeaderValue::from_str(&item.device_model)?); }
    } else if item.hwid_enabled && item.hwid_mode == "header" {
        headers.insert("x-hwid", HeaderValue::from_str(&item.hwid)?);
    }
    if item.hwid_enabled && item.hwid_mode == "cookie" {
        headers.insert("cookie", HeaderValue::from_str(&item.hwid)?);
    }
    for (k, v) in &item.custom_headers {
        let name = HeaderName::from_bytes(k.as_bytes())?;
        let value = HeaderValue::from_str(v)?;
        headers.insert(name, value);
    }
    Ok(headers)
}

pub(super) fn send_once(client: &Client, item: &Subscription, url: &Url) -> Result<Response> {
    let mut request_url = url.clone();
    let url_username = request_url.username().to_string();
    let url_password = request_url.password().map(str::to_string);
    if !url_username.is_empty() {
        let _ = request_url.set_username("");
        let _ = request_url.set_password(None);
    }
    let mut req = client.get(request_url).headers(build_headers(item)?);
    if item.basic_enabled {
        req = req.basic_auth(&item.basic_username, Some(&item.basic_password));
    } else if !url_username.is_empty() {
        req = req.basic_auth(url_username, url_password);
    }
    req.send().with_context(|| format!("subscription request failed: {}", url.host_str().unwrap_or("host")))
}

pub(super) fn resolve_happ_redirect(location: &str) -> Option<Url> {
    let u = Url::parse(location).ok()?;
    if u.scheme() != "happ" { return None; }
    for (k, v) in u.query_pairs() {
        if matches!(k.as_ref(), "url" | "link" | "target" | "subscription") {
            if let Ok(target) = Url::parse(v.as_ref()) {
                if matches!(target.scheme(), "http" | "https") { return Some(target); }
            }
        }
    }
    let raw = location.trim_start_matches("happ://");
    let decoded = percent_decode(raw);
    Url::parse(&decoded).ok().filter(|u| matches!(u.scheme(), "http" | "https"))
}

pub(super) fn percent_decode(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let h = (bytes[i + 1] as char).to_digit(16);
            let l = (bytes[i + 2] as char).to_digit(16);
            if let (Some(h), Some(l)) = (h, l) {
                out.push(((h << 4) | l) as u8);
                i += 3;
                continue;
            }
        }
        out.push(if bytes[i] == b'+' { b' ' } else { bytes[i] });
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub(super) fn fetch_response(item: &Subscription) -> Result<(Response, Url)> {
    let client = make_client()?;
    let mut current = Url::parse(&item.url)?;
    for _ in 0..6 {
        let response = send_once(&client, item, &current)?;
        if response.status().is_redirection() {
            let location = response.headers().get(LOCATION).and_then(|v| v.to_str().ok()).ok_or_else(|| anyhow::anyhow!("redirect without Location"))?;
            if location.starts_with("happ://") {
                current = resolve_happ_redirect(location).ok_or_else(|| anyhow::anyhow!("unsupported happ:// redirect; expected a redirect containing an http(s) URL"))?;
            } else {
                current = current.join(location).or_else(|_| Url::parse(location)).context("invalid redirect URL")?;
                if !matches!(current.scheme(), "http" | "https") { bail!("redirect to unsupported scheme"); }
            }
            continue;
        }
        return Ok((response, current));
    }
    bail!("too many subscription redirects")
}

pub fn refresh(id: &str) -> Result<SubscriptionStatus> {
    ensure_id(id)?;
    let item = get(id)?;
    if !item.enabled { bail!("subscription is disabled"); }
    let now = now_unix();
    let old = read_status(id);
    let result = refresh_inner(&item);
    match result {
        Ok((provider_text, mut status, mut nodes)) => {
            // The URL/auth/options can be edited while the network request is in flight.
            // Never let an older request overwrite the provider produced for newer settings.
            let current = match get(id) {
                Ok(current) => current,
                Err(_) => return Ok(old), // deleted while refreshing
            };
            if !current.enabled || current.modified_at != item.modified_at {
                return Ok(old);
            }

            let interval = if current.use_remote_interval {
                status.remote_interval_minutes.unwrap_or(current.update_interval_minutes)
            } else { current.update_interval_minutes };
            let interval = interval.clamp(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES);
            status.last_error.clear();
            status.last_updated_at = now;
            status.next_update_at = now.saturating_add(interval.saturating_mul(60));

            let path = provider_path(id);
            write_text_atomic(&path, &provider_text)?;
            chmod_600_best_effort(&path);
            // Keep links created by older builds valid when only presentation
            // normalization changes (for example percent-decoded node names).
            preserve_existing_node_ids(id, &mut nodes);
            write_nodes(id, &nodes)?;
            write_status(id, &status)?;
            sync_provider_to_profiles(id);
            sync_imported_nodes(id, &nodes);
            Ok(status)
        }
        Err(e) => {
            // Only report the network/parser error if these are still the settings
            // that initiated this refresh. A concurrently edited/deleted subscription
            // will be refreshed again after the current worker releases its slot.
            let unchanged = get(id)
                .map(|current| current.enabled && current.modified_at == item.modified_at)
                .unwrap_or(false);
            if unchanged {
                let mut status = old;
                status.last_error = format!("{e:#}");
                status.next_update_at = now.saturating_add(RETRY_AFTER_ERROR_MINUTES * 60);
                let _ = write_status(id, &status);
            }
            Err(e)
        }
    }
}

pub(super) fn refresh_inner(item: &Subscription) -> Result<(String, SubscriptionStatus, Vec<SubscriptionNode>)> {
    let (mut response, _) = fetch_response(item)?;
    if !response.status().is_success() { bail!("subscription HTTP status {}", response.status().as_u16()); }
    if let Some(len) = response.content_length() {
        if len > MAX_RESPONSE_BYTES { bail!("subscription response is too large"); }
    }
    let headers = response.headers().clone();
    let mut data = Vec::new();
    response.by_ref().take(MAX_RESPONSE_BYTES + 1).read_to_end(&mut data)?;
    if data.len() as u64 > MAX_RESPONSE_BYTES { bail!("subscription response is too large"); }
    if data.is_empty() { bail!("subscription returned an empty response"); }
    let mut text = String::from_utf8(data).context("subscription response is not UTF-8 text")?;
    if text.starts_with('\u{feff}') { text.remove(0); }
    text = text.replace("\r\n", "\n").replace('\r', "\n");
    if text.contains('\0') { bail!("subscription response contains NUL bytes"); }
    let nodes = parse_subscription_nodes(item, &text)?;
    let (provider_text, count) = normalize_provider_body(&text)?;

    let remote_interval = header_string(&headers, "profile-update-interval")
        .and_then(|s| parse_interval_minutes(&s));
    let mut status = SubscriptionStatus {
        server_count: count,
        content_bytes: provider_text.len(),
        remote_title: header_string(&headers, "profile-title").map(decode_profile_title).unwrap_or_default(),
        remote_interval_minutes: remote_interval,
        web_page_url: header_string(&headers, "profile-web-page-url").unwrap_or_default(),
        support_url: header_string(&headers, "support-url").unwrap_or_default(),
        ..SubscriptionStatus::default()
    };
    if let Some(userinfo) = header_string(&headers, "subscription-userinfo") {
        parse_subscription_userinfo(&userinfo, &mut status);
    }
    Ok((provider_text, status, nodes))
}

pub(super) fn header_string(headers: &HeaderMap, key: &str) -> Option<String> {
    headers.get(key).and_then(|v| v.to_str().ok()).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string)
}

pub(super) fn parse_interval_minutes(raw: &str) -> Option<u64> {
    // Clash/Happ profile-update-interval is expressed in hours.
    let hours = raw.trim().parse::<u64>().ok()?;
    Some(hours.saturating_mul(60).clamp(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES))
}

pub(super) fn decode_profile_title(raw: String) -> String {
    let trimmed = raw.trim();
    let Some(encoded) = trimmed.strip_prefix("base64:") else { return trimmed.to_string(); };
    general_purpose::STANDARD.decode(encoded.trim())
        .or_else(|_| general_purpose::STANDARD_NO_PAD.decode(encoded.trim()))
        .or_else(|_| general_purpose::URL_SAFE.decode(encoded.trim()))
        .or_else(|_| general_purpose::URL_SAFE_NO_PAD.decode(encoded.trim()))
        .ok()
        .and_then(|b| String::from_utf8(b).ok())
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| trimmed.to_string())
}

pub(super) fn parse_subscription_userinfo(raw: &str, status: &mut SubscriptionStatus) {
    for piece in raw.split(';') {
        let Some((k, v)) = piece.trim().split_once('=') else { continue; };
        let val = v.trim().parse::<u64>().ok();
        match k.trim().to_ascii_lowercase().as_str() {
            "upload" => status.upload = val,
            "download" => status.download = val,
            "total" => status.total = val,
            "expire" => status.expire = val,
            _ => {}
        }
    }
}

