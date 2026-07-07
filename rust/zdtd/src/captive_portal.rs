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
        let out = existing.clone();
        write_store(&store)?;
        return Ok(out);
    }

    let device = CaptiveDevice {
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
    };
    store.devices.push(device.clone());
    write_store(&store)?;
    Ok(device)
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
    let status = if device.allowed { "разрешено" } else { "ожидает разрешения" };
    let mut page = r#"<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ZDT-D Captive Portal</title>
<style>
body{margin:0;background:#0b0f14;color:#e8eef5;font-family:Arial,sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center}
.card{width:min(92vw,520px);background:#111923;border:1px solid #26384d;border-radius:16px;padding:24px;box-shadow:0 12px 40px rgba(0,0,0,.35)}
h1{font-size:22px;margin:0 0 12px;color:#7bdcff}p{line-height:1.45;color:#b7c5d4}
.row{margin:12px 0;padding:12px;border-radius:10px;background:#0b121a}
.label{font-size:12px;color:#7f91a6;text-transform:uppercase;letter-spacing:.08em}.value{margin-top:5px;font-size:18px;word-break:break-all}.status{color:#ffd479}
</style>
</head>
<body><main class="card">
<h1>Требуется авторизация сети</h1>
<p>Ваше устройство подключено к точке доступа ZDT-D, но доступ в интернет пока не разрешён. Обратитесь к администратору сети.</p>
<div class="row"><div class="label">Локальный IP</div><div class="value">%IP%</div></div>
<div class="row"><div class="label">Идентификатор устройства</div><div class="value">%ID%</div></div>
<div class="row"><div class="label">Модель</div><div class="value">%MODEL%</div></div>
<div class="row"><div class="label">Статус</div><div class="value status">%STATUS%</div></div>
</main></body></html>"#.to_string();
    page = page.replace("%IP%", &escape_html(&device.ip));
    page = page.replace("%ID%", &escape_html(&device.short_id));
    page = page.replace("%MODEL%", &escape_html(&device.model));
    page.replace("%STATUS%", &escape_html(status))
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
