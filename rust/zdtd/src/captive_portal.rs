use anyhow::{Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs,
    io::{Read, Write},
    net::{IpAddr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc, Mutex, OnceLock,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use crate::{settings, shell::{self, Capture}};

pub const HOTSPOT_IFACE: &str = "wlan1";
pub const PORTAL_PORT: u16 = 1007;
pub const STORAGE_DIR: &str = "/data/adb/modules/ZDT-D/api/hotspot";
pub const DEVICES_FILE: &str = "/data/adb/modules/ZDT-D/api/hotspot/devices.json";

const MAX_HEADER_BYTES: usize = 4 * 1024;
const MAX_PATH_BYTES: usize = 256;
const MAX_INFLIGHT: usize = 24;
const READ_TIMEOUT: Duration = Duration::from_secs(1);
const WRITE_TIMEOUT: Duration = Duration::from_secs(1);
const ACCEPT_SLEEP: Duration = Duration::from_millis(100);
const RATE_WINDOW: Duration = Duration::from_secs(10);
const RATE_MAX_REQUESTS: u32 = 40;
const RATE_MAX_TRACKED_IPS: usize = 256;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaptiveDevice {
    pub id: String,
    pub short_id: String,
    pub ip: String,
    #[serde(default)]
    pub mac: String,
    #[serde(default)]
    pub model: String,
    #[serde(default)]
    pub user_agent: String,
    #[serde(default)]
    pub allowed: bool,
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub first_seen: u64,
    #[serde(default)]
    pub last_seen: u64,
    #[serde(default)]
    pub allowed_at: Option<u64>,
    #[serde(default)]
    pub denied_at: Option<u64>,
    #[serde(default)]
    pub notified_at: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DeviceStore {
    version: u32,
    devices: Vec<CaptiveDevice>,
}

impl Default for DeviceStore {
    fn default() -> Self {
        Self { version: 1, devices: Vec::new() }
    }
}

struct ServerRuntime {
    stop: Arc<AtomicBool>,
}

struct RateEntry {
    window_start: Instant,
    count: u32,
}

static SERVER: OnceLock<Mutex<Option<ServerRuntime>>> = OnceLock::new();
static INFLIGHT: AtomicUsize = AtomicUsize::new(0);
static RATE: OnceLock<Mutex<HashMap<String, RateEntry>>> = OnceLock::new();

fn server_slot() -> &'static Mutex<Option<ServerRuntime>> {
    SERVER.get_or_init(|| Mutex::new(None))
}

fn rate_map() -> &'static Mutex<HashMap<String, RateEntry>> {
    RATE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_secs()
}

fn devices_path() -> PathBuf {
    PathBuf::from(DEVICES_FILE)
}

pub fn ensure_layout() -> Result<()> {
    fs::create_dir_all(STORAGE_DIR).with_context(|| format!("mkdir {STORAGE_DIR}"))?;
    let path = devices_path();
    if !path.exists() {
        write_store(&DeviceStore::default())?;
    }
    Ok(())
}

fn read_store() -> Result<DeviceStore> {
    ensure_layout()?;
    let path = devices_path();
    let raw = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    let mut store: DeviceStore = serde_json::from_str(&raw).unwrap_or_default();
    if store.version == 0 {
        store.version = 1;
    }
    for d in &mut store.devices {
        if d.status.is_empty() {
            d.status = if d.allowed { "allowed" } else { "pending" }.to_string();
        }
        if d.short_id.is_empty() {
            d.short_id = d.id.chars().take(12).collect();
        }
    }
    Ok(store)
}

fn write_store(store: &DeviceStore) -> Result<()> {
    fs::create_dir_all(STORAGE_DIR).with_context(|| format!("mkdir {STORAGE_DIR}"))?;
    let path = devices_path();
    let tmp = path.with_extension("tmp");
    let text = serde_json::to_string_pretty(store)?;
    fs::write(&tmp, text).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, &path).with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

fn active_from_settings(st: &settings::ApiSettings) -> bool {
    st.captive_portal_enabled && st.hotspot_mode_proxy() && !st.hotspot_program.trim().is_empty()
}

pub fn sync_from_settings_best_effort() {
    match sync_from_settings() {
        Ok(active) => info!("captive portal sync active={active}"),
        Err(e) => warn!("captive portal sync failed: {e:#}"),
    }
}

pub fn sync_from_settings() -> Result<bool> {
    let st = settings::load_api_settings().unwrap_or_default();
    if !active_from_settings(&st) {
        stop();
        crate::iptables::captive_portal::cleanup()?;
        return Ok(false);
    }

    ensure_layout()?;
    start()?;
    refresh_rules()?;
    Ok(true)
}

pub fn refresh_rules_best_effort() {
    if let Err(e) = refresh_rules() {
        warn!("captive portal rules refresh failed: {e:#}");
    }
}

pub fn refresh_rules() -> Result<()> {
    let st = settings::load_api_settings().unwrap_or_default();
    if !active_from_settings(&st) {
        return Ok(());
    }
    let ips = allowed_ips()?;
    crate::iptables::captive_portal::apply(&ips)
}

pub fn start() -> Result<()> {
    let mut guard = match server_slot().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if guard.is_some() {
        return Ok(());
    }

    let stop = Arc::new(AtomicBool::new(false));
    let stop_thread = stop.clone();
    let bind = format!("0.0.0.0:{PORTAL_PORT}");
    let listener = TcpListener::bind(&bind).with_context(|| format!("bind captive portal {bind}"))?;
    listener.set_nonblocking(true)?;

    thread::spawn(move || serve_loop(listener, stop_thread));
    *guard = Some(ServerRuntime { stop });
    info!("captive portal server started on {bind}");
    Ok(())
}

pub fn stop() {
    let mut guard = match server_slot().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if let Some(rt) = guard.take() {
        rt.stop.store(true, Ordering::Release);
        info!("captive portal server stop requested");
    }
}

fn serve_loop(listener: TcpListener, stop: Arc<AtomicBool>) {
    while !stop.load(Ordering::Acquire) {
        match listener.accept() {
            Ok((stream, _)) => {
                let now = INFLIGHT.fetch_add(1, Ordering::AcqRel) + 1;
                if now > MAX_INFLIGHT {
                    INFLIGHT.fetch_sub(1, Ordering::AcqRel);
                    let mut s = stream;
                    let _ = write_response(&mut s, 429, "text/plain; charset=utf-8", b"Too Many Requests", false);
                    continue;
                }

                thread::spawn(move || {
                    struct Guard;
                    impl Drop for Guard {
                        fn drop(&mut self) {
                            INFLIGHT.fetch_sub(1, Ordering::AcqRel);
                        }
                    }
                    let _guard = Guard;
                    if let Err(e) = handle_client(stream) {
                        warn!("captive portal request failed: {e:#}");
                    }
                });
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => thread::sleep(ACCEPT_SLEEP),
            Err(e) => {
                warn!("captive portal accept failed: {e}");
                thread::sleep(ACCEPT_SLEEP);
            }
        }
    }
    info!("captive portal server stopped");
}

fn handle_client(mut stream: TcpStream) -> Result<()> {
    stream.set_read_timeout(Some(READ_TIMEOUT)).ok();
    stream.set_write_timeout(Some(WRITE_TIMEOUT)).ok();
    stream.set_nodelay(true).ok();

    let peer_ip = stream.peer_addr().ok().map(|a| a.ip());
    if let Some(ip) = peer_ip {
        if !rate_allow(&ip.to_string()) {
            return write_response(&mut stream, 429, "text/plain; charset=utf-8", b"Too Many Requests", false);
        }
    }

    let req = parse_request(&mut stream)?;
    if req.path.len() > MAX_PATH_BYTES {
        return write_response(&mut stream, 414, "text/plain; charset=utf-8", b"URI Too Long", false);
    }
    if !matches!(req.method.as_str(), "GET" | "HEAD") {
        return write_response(&mut stream, 405, "text/plain; charset=utf-8", b"Method Not Allowed", req.method == "HEAD");
    }
    if !safe_path(&req.path) {
        return write_response(&mut stream, 400, "text/plain; charset=utf-8", b"Bad Request", req.method == "HEAD");
    }

    let ip = peer_ip
        .map(|x| x.to_string())
        .unwrap_or_else(|| "unknown".to_string());
    let user_agent = req.headers.get("user-agent").cloned().unwrap_or_default();
    let device = register_or_touch_device(&ip, &user_agent).unwrap_or_else(|e| {
        warn!("captive device registration failed: {e:#}");
        fallback_device(&ip, &user_agent)
    });

    if req.path == "/favicon.ico" {
        return write_response(&mut stream, 204, "image/x-icon", b"", req.method == "HEAD");
    }

    let body = render_page(&device).into_bytes();
    write_response(&mut stream, 200, "text/html; charset=utf-8", &body, req.method == "HEAD")
}

struct HttpRequest {
    method: String,
    path: String,
    headers: HashMap<String, String>,
}

fn parse_request(stream: &mut TcpStream) -> Result<HttpRequest> {
    let mut data = Vec::with_capacity(1024);
    let mut buf = [0_u8; 512];
    loop {
        let n = stream.read(&mut buf)?;
        if n == 0 {
            break;
        }
        data.extend_from_slice(&buf[..n]);
        if data.windows(4).any(|w| w == b"\r\n\r\n") {
            break;
        }
        if data.len() > MAX_HEADER_BYTES {
            anyhow::bail!("header too large");
        }
    }
    if data.len() > MAX_HEADER_BYTES {
        anyhow::bail!("header too large");
    }

    let text = String::from_utf8_lossy(&data);
    let head = text.split("\r\n\r\n").next().unwrap_or("");
    let mut lines = head.split("\r\n");
    let first = lines.next().unwrap_or("");
    let mut first_it = first.split_whitespace();
    let method = first_it.next().unwrap_or("").to_string();
    let raw_path = first_it.next().unwrap_or("/");
    let path_only = raw_path.split('?').next().unwrap_or("/").to_string();

    let mut headers = HashMap::new();
    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
        }
    }

    Ok(HttpRequest { method, path: path_only, headers })
}

fn safe_path(path: &str) -> bool {
    if !path.starts_with('/') || path.contains("..") || path.contains('\\') || path.contains('\0') {
        return false;
    }
    path.bytes().all(|b| b.is_ascii_graphic() || b == b' ')
}

fn write_response(stream: &mut TcpStream, code: u16, content_type: &str, body: &[u8], head_only: bool) -> Result<()> {
    let reason = match code {
        200 => "OK",
        204 => "No Content",
        400 => "Bad Request",
        405 => "Method Not Allowed",
        414 => "URI Too Long",
        429 => "Too Many Requests",
        _ => "OK",
    };
    let len = if head_only { 0 } else { body.len() };
    let header = format!(
        "HTTP/1.1 {code} {reason}\r\nContent-Type: {content_type}\r\nContent-Length: {len}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
    );
    stream.write_all(header.as_bytes())?;
    if !head_only && code != 204 {
        stream.write_all(body)?;
    }
    Ok(())
}

fn rate_allow(ip: &str) -> bool {
    let mut guard = match rate_map().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let now = Instant::now();
    if guard.len() > RATE_MAX_TRACKED_IPS {
        guard.retain(|_, v| now.duration_since(v.window_start) <= RATE_WINDOW);
    }
    let entry = guard.entry(ip.to_string()).or_insert(RateEntry { window_start: now, count: 0 });
    if now.duration_since(entry.window_start) > RATE_WINDOW {
        entry.window_start = now;
        entry.count = 0;
    }
    entry.count = entry.count.saturating_add(1);
    entry.count <= RATE_MAX_REQUESTS
}

fn register_or_touch_device(ip: &str, user_agent: &str) -> Result<CaptiveDevice> {
    let mac = lookup_mac(ip).unwrap_or_default();
    let model = guess_model(user_agent);
    let id = device_id(ip, &mac, user_agent);
    let short_id: String = id.chars().take(12).collect();
    let now = now_unix();
    let mut store = read_store()?;

    if let Some(existing) = store.devices.iter_mut().find(|d| d.id == id || (!mac.is_empty() && d.mac == mac)) {
        existing.ip = ip.to_string();
        existing.mac = mac;
        existing.model = model;
        existing.user_agent = user_agent.to_string();
        existing.last_seen = now;
        if existing.status.is_empty() {
            existing.status = if existing.allowed { "allowed" } else { "pending" }.to_string();
        }
        // Fire-once notify: the daemon knocks the app a single time per pending
        // device. No retries even if it stays pending (design decision).
        // Deferred until a meaningful device model is known so the notification
        // shows the same name as the web authorization page (not a generic
        // "unknown" produced by system captive-portal probe requests).
        if !existing.allowed && existing.notified_at.is_none() && !is_generic_model(&existing.model) {
            notify_pending(&existing.short_id, &existing.model);
            existing.notified_at = Some(now);
        }
        let out = existing.clone();
        write_store(&store)?;
        return Ok(out);
    }

    let mut device = CaptiveDevice {
        id,
        short_id,
        ip: ip.to_string(),
        mac,
        model,
        user_agent: user_agent.to_string(),
        allowed: false,
        status: "pending".to_string(),
        first_seen: now,
        last_seen: now,
        allowed_at: None,
        denied_at: None,
        notified_at: None,
    };
    // Fire-once notify for the freshly seen device, but only once we have a
    // meaningful device name (same value the web portal page shows). Generic
    // probe requests (system captive checks with a poor User-Agent) are skipped
    // so the notification carries the real device model, not "unknown". A later
    // request from the real browser will fire it with the proper name.
    if !is_generic_model(&device.model) {
        notify_pending(&device.short_id, &device.model);
        device.notified_at = Some(now);
    }
    store.devices.push(device.clone());
    write_store(&store)?;
    Ok(device)
}

/// Best-effort, fire-once notification to the Android app that a captive-portal
/// client is waiting for approval. Failures are swallowed on purpose: per design
/// we never retry, the request simply stays `pending` in the device list.
fn notify_pending(short_id: &str, model: &str) {
    if let Err(e) = crate::android::notification::send_captive_device_pending(short_id, model) {
        warn!("captive pending notify failed: {e:#}");
    }
}

/// A model is "generic" when `guess_model` could only infer a platform bucket
/// (or nothing) from a poor User-Agent — typical for system captive-portal
/// probe requests. We defer the fire-once notification until a real device
/// model is known so the notification shows the same name as the web
/// authorization page.
fn is_generic_model(model: &str) -> bool {
    matches!(
        model.trim(),
        "" | "unknown" | "Android" | "iPhone" | "iPad" | "Windows" | "macOS" | "Linux"
    )
}

fn fallback_device(ip: &str, user_agent: &str) -> CaptiveDevice {
    let id = device_id(ip, "", user_agent);
    CaptiveDevice {
        short_id: id.chars().take(12).collect(),
        id,
        ip: ip.to_string(),
        mac: String::new(),
        model: guess_model(user_agent),
        user_agent: user_agent.to_string(),
        allowed: false,
        status: "pending".to_string(),
        first_seen: now_unix(),
        last_seen: now_unix(),
        allowed_at: None,
        denied_at: None,
        notified_at: None,
    }
}

fn device_id(ip: &str, mac: &str, user_agent: &str) -> String {
    let base = if mac.trim().is_empty() { ip } else { mac.trim() };
    let mut h = Sha256::new();
    h.update(base.as_bytes());
    h.update(b"\n");
    h.update(user_agent.as_bytes());
    hex::encode(h.finalize())
}

fn lookup_mac(ip: &str) -> Option<String> {
    lookup_mac_proc_arp(ip).or_else(|| lookup_mac_ip_neigh(ip))
}

fn lookup_mac_proc_arp(ip: &str) -> Option<String> {
    let raw = fs::read_to_string("/proc/net/arp").ok()?;
    for line in raw.lines().skip(1) {
        let cols: Vec<&str> = line.split_whitespace().collect();
        if cols.len() >= 4 && cols[0] == ip {
            let mac = cols[3].trim().to_ascii_lowercase();
            if mac != "00:00:00:00:00:00" {
                return Some(mac);
            }
        }
    }
    None
}

fn lookup_mac_ip_neigh(ip: &str) -> Option<String> {
    let (rc, out) = shell::run_timeout("ip", &["neigh", "show", ip], Capture::Stdout, Duration::from_secs(1)).ok()?;
    if rc != 0 {
        return None;
    }
    parse_lladdr(&out)
}

fn parse_lladdr(raw: &str) -> Option<String> {
    let mut iter = raw.split_whitespace();
    while let Some(tok) = iter.next() {
        if tok == "lladdr" {
            return iter.next().map(|s| s.to_ascii_lowercase());
        }
    }
    None
}

fn guess_model(user_agent: &str) -> String {
    let ua = user_agent.trim();
    if ua.is_empty() {
        return "unknown".to_string();
    }
    let lower = ua.to_ascii_lowercase();
    if let Some(start) = ua.find('(') {
        if let Some(end_rel) = ua[start + 1..].find(')') {
            let inside = &ua[start + 1..start + 1 + end_rel];
            for part in inside.split(';').map(|s| s.trim()).rev() {
                if part.is_empty() || part.eq_ignore_ascii_case("wv") || part.to_ascii_lowercase().starts_with("linux") {
                    continue;
                }
                if part.len() <= 80 {
                    return part.to_string();
                }
            }
        }
    }
    if lower.contains("android") { "Android".to_string() }
    else if lower.contains("iphone") { "iPhone".to_string() }
    else if lower.contains("ipad") { "iPad".to_string() }
    else if lower.contains("windows") { "Windows".to_string() }
    else if lower.contains("mac os") { "macOS".to_string() }
    else { "unknown".to_string() }
}

fn escape_html(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

fn render_page(device: &CaptiveDevice) -> String {
    let status_ru = if device.allowed { "разрешено" } else { "ожидает разрешения" };
    let status_en = if device.allowed { "allowed" } else { "waiting for approval" };
    let mut page = r##"<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ZDT-D Captive Gate</title>
<style>
:root{--bg:#050000;--bg2:#0d0303;--card:rgba(18,7,7,.82);--card2:rgba(30,7,7,.9);--text:#fff;--muted:#bdaaaa;--red:#ff1e1e;--red2:#8d0000;--soft:rgba(255,30,30,.16);--border:rgba(255,30,30,.38);--line:rgba(255,255,255,.08);--shadow:0 0 46px rgba(255,0,0,.2)}
*{box-sizing:border-box}html{background:var(--bg)}body{margin:0;min-height:100vh;color:var(--text);font-family:Arial,Helvetica,sans-serif;background:radial-gradient(circle at 16% 8%,rgba(255,0,0,.24),transparent 30%),radial-gradient(circle at 86% 12%,rgba(255,0,0,.16),transparent 27%),radial-gradient(circle at 50% 88%,rgba(255,0,0,.1),transparent 34%),linear-gradient(180deg,#000 0%,#0a0101 50%,#000 100%);overflow:hidden;display:grid;place-items:center;padding:22px}
body:before{content:"";position:fixed;inset:0;pointer-events:none;background-image:linear-gradient(rgba(255,255,255,.026) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.026) 1px,transparent 1px);background-size:42px 42px;mask-image:linear-gradient(to bottom,rgba(0,0,0,.78),transparent 82%)}
body:after{content:"";position:fixed;inset:0;pointer-events:none;background:linear-gradient(transparent 0 96%,rgba(255,0,0,.075) 97% 100%);background-size:100% 5px;mix-blend-mode:screen;opacity:.18}
.gate{position:relative;z-index:1;width:min(94vw,620px);border:1px solid var(--border);border-radius:24px;background:linear-gradient(135deg,rgba(255,0,0,.18),rgba(255,255,255,.025)),var(--card);box-shadow:var(--shadow);overflow:hidden;padding:24px}
.gate:before{content:"";position:absolute;inset:-1px;pointer-events:none;background:linear-gradient(120deg,transparent,rgba(255,255,255,.12),transparent);transform:translateX(-100%);animation:shine 5s infinite}.gate:after{content:"";position:absolute;width:210px;height:210px;right:-92px;bottom:-96px;border-radius:50%;background:rgba(255,0,0,.16);filter:blur(10px);pointer-events:none}
.top{position:relative;z-index:2;display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:24px}.brand{display:flex;align-items:center;font-weight:900;letter-spacing:.08em;text-transform:uppercase;color:#fff;text-shadow:0 0 18px rgba(255,0,0,.32)}.lang{min-width:48px;min-height:34px;border:1px solid var(--border);border-radius:999px;color:#fff;background:rgba(16,0,0,.74);font-size:12px;font-weight:900;letter-spacing:.08em;cursor:pointer;-webkit-tap-highlight-color:transparent;outline:none;user-select:none;touch-action:manipulation}.lang:hover{background:rgba(255,0,0,.2);border-color:rgba(255,90,90,.86)}.lang:focus,.lang:active{outline:none;background:rgba(16,0,0,.74);box-shadow:none}.lang:focus-visible{border-color:rgba(255,90,90,.86);box-shadow:0 0 18px rgba(255,0,0,.22)}
.eyebrow{position:relative;z-index:2;display:inline-flex;align-items:center;gap:10px;color:var(--red);font-size:12px;font-weight:900;letter-spacing:.14em;text-transform:uppercase;margin-bottom:16px}.pulse{width:9px;height:9px;border-radius:50%;background:var(--red);box-shadow:0 0 0 0 rgba(255,30,30,.7);animation:pulse 1.8s infinite}
h1{position:relative;z-index:2;margin:0;font-size:clamp(30px,7vw,56px);line-height:.95;letter-spacing:-.05em;text-transform:uppercase}.outline{color:transparent;-webkit-text-stroke:1px rgba(255,255,255,.5)}.lead{position:relative;z-index:2;margin:18px 0 22px;color:var(--muted);line-height:1.55;font-size:16px}.grid{position:relative;z-index:2;display:grid;grid-template-columns:1fr 1fr;gap:10px}.item{border:1px solid var(--line);border-radius:16px;background:rgba(0,0,0,.26);padding:13px}.label{display:block;color:#a98e8e;font-size:11px;font-weight:900;letter-spacing:.1em;text-transform:uppercase}.value{display:block;margin-top:7px;font-size:17px;font-weight:900;word-break:break-all}.status{color:#ffdada;text-shadow:0 0 16px rgba(255,0,0,.26)}.foot{position:relative;z-index:2;margin:20px 0 0;padding:14px;border:1px solid var(--border);border-radius:16px;background:rgba(255,0,0,.1);color:#ffe2e2;line-height:1.45;font-weight:700}@keyframes pulse{0%{box-shadow:0 0 0 0 rgba(255,30,30,.7)}70%{box-shadow:0 0 0 12px rgba(255,30,30,0)}100%{box-shadow:0 0 0 0 rgba(255,30,30,0)}}@keyframes shine{0%{transform:translateX(-100%)}45%,100%{transform:translateX(100%)}}@media(max-width:520px){.gate{padding:20px;border-radius:20px}.grid{grid-template-columns:1fr}h1{font-size:34px}.top{align-items:flex-start}.brand{font-size:13px}}
</style>
</head>
<body>
<main class="gate">
  <div class="top">
    <div class="brand">ZDT-D Gate</div>
    <button class="lang" id="langBtn" type="button">EN</button>
  </div>
  <div class="eyebrow"><span class="pulse"></span><span data-ru="Доступ ограничен" data-en="Access locked">Доступ ограничен</span></div>
  <h1><span data-ru="Требуется" data-en="Network">Требуется</span><br><span class="outline" data-ru="авторизация" data-en="authorization">авторизация</span></h1>
  <p class="lead" data-ru="Ваше устройство подключено к точке доступа ZDT-D, но доступ в интернет пока не разрешён." data-en="Your device is connected to the ZDT-D hotspot, but internet access is not approved yet.">Ваше устройство подключено к точке доступа ZDT-D, но доступ в интернет пока не разрешён.</p>
  <section class="grid" aria-label="Device authorization data">
    <div class="item"><span class="label" data-ru="Локальный IP" data-en="Local IP">Локальный IP</span><span class="value">%IP%</span></div>
    <div class="item"><span class="label" data-ru="ID устройства" data-en="Device ID">ID устройства</span><span class="value">%ID%</span></div>
    <div class="item"><span class="label" data-ru="Модель" data-en="Device model">Модель</span><span class="value">%MODEL%</span></div>
    <div class="item"><span class="label" data-ru="Статус" data-en="Status">Статус</span><span class="value status" data-ru="%STATUS_RU%" data-en="%STATUS_EN%">%STATUS_RU%</span></div>
  </section>
  <p class="foot" data-ru="Обратитесь к администратору сети для разрешения доступа." data-en="Contact the network administrator to approve access.">Обратитесь к администратору сети для разрешения доступа.</p>
</main>
<script>
(function(){
  var lang=(navigator.language||'ru').toLowerCase().indexOf('ru')===0?'ru':'en';
  var btn=document.getElementById('langBtn');
  function apply(next){
    lang=next; document.documentElement.lang=lang; btn.textContent=lang==='ru'?'EN':'RU';
    var nodes=document.querySelectorAll('[data-ru][data-en]');
    for(var i=0;i<nodes.length;i++){nodes[i].textContent=nodes[i].getAttribute('data-'+lang)||nodes[i].textContent;}
  }
  btn.onclick=function(){apply(lang==='ru'?'en':'ru')};
  apply(lang);
})();
</script>
</body>
</html>"##.to_string();
    page = page.replace("%IP%", &escape_html(&device.ip));
    page = page.replace("%ID%", &escape_html(&device.short_id));
    page = page.replace("%MODEL%", &escape_html(&device.model));
    page = page.replace("%STATUS_RU%", &escape_html(status_ru));
    page.replace("%STATUS_EN%", &escape_html(status_en))
}

fn allowed_ips() -> Result<Vec<String>> {
    let store = read_store()?;
    let mut out: Vec<String> = store
        .devices
        .into_iter()
        .filter(|d| d.allowed && valid_ipv4(&d.ip))
        .map(|d| d.ip)
        .collect();
    out.sort();
    out.dedup();
    Ok(out)
}

fn valid_ipv4(ip: &str) -> bool {
    matches!(ip.parse::<IpAddr>(), Ok(IpAddr::V4(_)))
}

pub fn api_status() -> Value {
    let setting = settings::load_api_settings().unwrap_or_default();
    let running = match server_slot().lock() {
        Ok(g) => g.is_some(),
        Err(p) => p.into_inner().is_some(),
    };
    json!({
        "ok": true,
        "enabled": setting.captive_portal_enabled,
        "active": active_from_settings(&setting) && running,
        "hotspot_proxy": setting.hotspot_mode_proxy(),
        "interface": HOTSPOT_IFACE,
        "port": PORTAL_PORT,
        "storage_dir": STORAGE_DIR,
        "devices_file": DEVICES_FILE,
        "server_running": running,
    })
}

pub fn api_devices() -> Value {
    match read_store() {
        Ok(store) => json!({"ok": true, "devices": store.devices}),
        Err(e) => json!({"ok": false, "error": format!("{e:#}")}),
    }
}

#[derive(Debug, Deserialize)]
struct DeviceActionRequest {
    #[serde(default)]
    id: String,
    #[serde(default)]
    ip: String,
}

pub fn api_allow(body: &[u8], services_running: bool) -> Value {
    api_set_allowed(body, true, services_running)
}

pub fn api_deny(body: &[u8], services_running: bool) -> Value {
    api_set_allowed(body, false, services_running)
}

fn api_set_allowed(body: &[u8], allowed: bool, services_running: bool) -> Value {
    let res = (|| -> Result<CaptiveDevice> {
        let req: DeviceActionRequest = serde_json::from_slice(body).context("bad JSON body")?;
        let mut store = read_store()?;
        let now = now_unix();
        let Some(device) = store.devices.iter_mut().find(|d| {
            (!req.id.trim().is_empty() && d.id == req.id.trim())
                || (!req.id.trim().is_empty() && d.short_id == req.id.trim())
                || (!req.ip.trim().is_empty() && d.ip == req.ip.trim())
        }) else {
            anyhow::bail!("device not found");
        };
        device.allowed = allowed;
        if allowed {
            device.status = "allowed".to_string();
            device.allowed_at = Some(now);
            device.denied_at = None;
        } else {
            device.status = "denied".to_string();
            device.denied_at = Some(now);
            device.allowed_at = None;
        }
        let out = device.clone();
        write_store(&store)?;
        if services_running {
            refresh_rules_best_effort();
        }
        Ok(out)
    })();

    match res {
        Ok(device) => json!({"ok": true, "device": device}),
        Err(e) => json!({"ok": false, "error": format!("{e:#}")}),
    }
}
