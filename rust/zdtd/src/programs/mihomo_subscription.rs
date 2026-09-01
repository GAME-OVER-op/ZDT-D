use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose, Engine as _};
use reqwest::{
    blocking::{Client, Response},
    header::{HeaderMap, HeaderName, HeaderValue, LOCATION, USER_AGENT},
    redirect::Policy,
    Url,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value as JsonValue};
use serde_yaml::{Mapping as YamlMapping, Value as YamlValue};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    io::Read,
    path::{Path, PathBuf},
    str::FromStr,
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex, OnceLock,
    },
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

// This is the global ZDT-D subscription library.  It deliberately lives outside
// every program directory: Mihomo is only one consumer of the library.
const ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/subscriptions";
const STORE: &str = "/data/adb/modules/ZDT-D/working_folder/subscriptions/subscriptions.json";
const DATA_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/subscriptions/data";
const LINKS: &str = "/data/adb/modules/ZDT-D/working_folder/subscriptions/links.json";
const DEFAULT_INTERVAL_MINUTES: u64 = 60;
const MIN_INTERVAL_MINUTES: u64 = 15;
const MAX_INTERVAL_MINUTES: u64 = 7 * 24 * 60;
const MAX_RESPONSE_BYTES: u64 = 24 * 1024 * 1024;
const PROVIDER_RELOAD_INTERVAL_SECS: i64 = 60;
const RETRY_AFTER_ERROR_MINUTES: u64 = 15;
static WORKER_STARTED: AtomicBool = AtomicBool::new(false);
static REFRESHING: OnceLock<Mutex<BTreeSet<String>>> = OnceLock::new();
static STORE_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
static LINKS_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SubscriptionStore {
    #[serde(default)]
    pub subscriptions: BTreeMap<String, Subscription>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Subscription {
    pub id: String,
    pub name: String,
    pub url: String,
    #[serde(default = "default_true")]
    pub enabled: bool,

    #[serde(default)]
    pub basic_enabled: bool,
    #[serde(default)]
    pub basic_username: String,
    #[serde(default)]
    pub basic_password: String,

    #[serde(default)]
    pub hwid_enabled: bool,
    #[serde(default)]
    pub hwid: String,
    #[serde(default = "default_hwid_mode")]
    pub hwid_mode: String,

    #[serde(default)]
    pub user_agent: String,
    #[serde(default = "default_true")]
    pub send_device_headers: bool,
    #[serde(default)]
    pub device_locale: String,
    #[serde(default = "default_device_os")]
    pub device_os: String,
    #[serde(default)]
    pub os_version: String,
    #[serde(default)]
    pub device_model: String,

    #[serde(default)]
    pub custom_headers: BTreeMap<String, String>,
    #[serde(default = "default_true")]
    pub use_remote_interval: bool,
    #[serde(default = "default_interval")]
    pub update_interval_minutes: u64,
    #[serde(default)]
    pub created_at: u64,
    #[serde(default)]
    pub modified_at: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubscriptionInput {
    pub name: String,
    pub url: String,
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default)]
    pub basic_enabled: bool,
    #[serde(default)]
    pub basic_username: String,
    #[serde(default)]
    pub basic_password: String,
    #[serde(default)]
    pub hwid_enabled: bool,
    #[serde(default)]
    pub hwid: String,
    #[serde(default = "default_hwid_mode")]
    pub hwid_mode: String,
    #[serde(default)]
    pub user_agent: String,
    #[serde(default = "default_true")]
    pub send_device_headers: bool,
    #[serde(default)]
    pub device_locale: String,
    #[serde(default = "default_device_os")]
    pub device_os: String,
    #[serde(default)]
    pub os_version: String,
    #[serde(default)]
    pub device_model: String,
    #[serde(default)]
    pub custom_headers: BTreeMap<String, String>,
    #[serde(default = "default_true")]
    pub use_remote_interval: bool,
    #[serde(default = "default_interval")]
    pub update_interval_minutes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SubscriptionStatus {
    #[serde(default)]
    pub last_updated_at: u64,
    #[serde(default)]
    pub next_update_at: u64,
    #[serde(default)]
    pub server_count: usize,
    #[serde(default)]
    pub content_bytes: usize,
    #[serde(default)]
    pub last_error: String,
    #[serde(default)]
    pub remote_title: String,
    #[serde(default)]
    pub remote_interval_minutes: Option<u64>,
    #[serde(default)]
    pub upload: Option<u64>,
    #[serde(default)]
    pub download: Option<u64>,
    #[serde(default)]
    pub total: Option<u64>,
    #[serde(default)]
    pub expire: Option<u64>,
    #[serde(default)]
    pub web_page_url: String,
    #[serde(default)]
    pub support_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubscriptionNode {
    pub id: String,
    pub name: String,
    pub protocol: String,
    #[serde(default)]
    pub server: String,
    #[serde(default)]
    pub port: u16,
    /// Normalized Clash-style object. URI subscriptions are converted to the
    /// same representation so all consumers see one stable model.
    #[serde(default)]
    pub definition: JsonValue,
    #[serde(default)]
    pub targets: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SubscriptionLinks {
    #[serde(default)]
    pub links: BTreeMap<String, SubscriptionLink>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubscriptionLink {
    pub id: String,
    pub subscription_id: String,
    pub node_id: String,
    pub target: String,
    pub profile: String,
    pub server_name: String,
    #[serde(default)]
    pub missing: bool,
    #[serde(default)]
    pub last_synced_at: u64,
}

#[derive(Debug, Deserialize)]
pub struct ImportNodeRequest {
    pub target: String,
    pub profile: String,
    pub server_name: String,
}

fn default_true() -> bool { true }
fn default_interval() -> u64 { DEFAULT_INTERVAL_MINUTES }
fn default_hwid_mode() -> String { "header".to_string() }
fn default_device_os() -> String { "Android".to_string() }

fn refreshing_set() -> &'static Mutex<BTreeSet<String>> {
    REFRESHING.get_or_init(|| Mutex::new(BTreeSet::new()))
}

fn store_lock() -> &'static Mutex<()> {
    STORE_LOCK.get_or_init(|| Mutex::new(()))
}

fn links_lock() -> &'static Mutex<()> {
    LINKS_LOCK.get_or_init(|| Mutex::new(()))
}

pub fn is_refreshing(id: &str) -> bool {
    refreshing_set().lock().map(|set| set.contains(id)).unwrap_or(false)
}

fn now_unix() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

pub fn root_path() -> PathBuf { PathBuf::from(ROOT) }
pub fn store_path() -> PathBuf { PathBuf::from(STORE) }
pub fn data_root() -> PathBuf { PathBuf::from(DATA_ROOT) }
pub fn provider_path(id: &str) -> PathBuf { data_root().join(id).join("provider.yaml") }
fn status_path(id: &str) -> PathBuf { data_root().join(id).join("status.json") }
fn nodes_path(id: &str) -> PathBuf { data_root().join(id).join("nodes.json") }
fn links_path() -> PathBuf { PathBuf::from(LINKS) }

pub fn ensure_layout() -> Result<()> {
    fs::create_dir_all(data_root())?;
    if !store_path().exists() {
        write_json_atomic(&store_path(), &SubscriptionStore::default())?;
    }
    if !links_path().exists() {
        write_json_atomic(&links_path(), &SubscriptionLinks::default())?;
    }
    chmod_600_best_effort(&store_path());
    chmod_600_best_effort(&links_path());
    Ok(())
}

fn read_store() -> Result<SubscriptionStore> {
    ensure_layout()?;
    let raw = fs::read_to_string(store_path()).context("read subscriptions store")?;
    serde_json::from_str(&raw).context("parse subscriptions store")
}

fn read_links() -> Result<SubscriptionLinks> {
    ensure_layout()?;
    let raw = fs::read_to_string(links_path()).context("read subscription links")?;
    serde_json::from_str(&raw).context("parse subscription links")
}

fn write_links(links: &SubscriptionLinks) -> Result<()> {
    write_json_atomic(&links_path(), links)?;
    chmod_600_best_effort(&links_path());
    Ok(())
}

fn read_nodes(id: &str) -> Vec<SubscriptionNode> {
    fs::read_to_string(nodes_path(id))
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

fn write_nodes(id: &str, nodes: &[SubscriptionNode]) -> Result<()> {
    let path = nodes_path(id);
    write_json_atomic(&path, nodes)?;
    chmod_600_best_effort(&path);
    Ok(())
}

fn write_store(store: &SubscriptionStore) -> Result<()> {
    ensure_layout()?;
    write_json_atomic(&store_path(), store)?;
    chmod_600_best_effort(&store_path());
    Ok(())
}

fn read_status(id: &str) -> SubscriptionStatus {
    let p = status_path(id);
    fs::read_to_string(p)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

fn write_status(id: &str, status: &SubscriptionStatus) -> Result<()> {
    let dir = data_root().join(id);
    fs::create_dir_all(&dir)?;
    let p = status_path(id);
    write_json_atomic(&p, status)?;
    chmod_600_best_effort(&p);
    Ok(())
}

fn atomic_tmp_path(path: &Path) -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos();
    let suffix = format!("tmp.{}.{}", std::process::id(), nanos);
    let name = path.file_name().and_then(|v| v.to_str()).unwrap_or("file");
    path.with_file_name(format!("{name}.{suffix}"))
}

fn write_json_atomic<T: Serialize + ?Sized>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    let tmp = atomic_tmp_path(path);
    let data = serde_json::to_vec_pretty(value)?;
    fs::write(&tmp, data)?;
    if let Err(e) = fs::rename(&tmp, path) {
        let _ = fs::remove_file(&tmp);
        return Err(e.into());
    }
    Ok(())
}

fn write_text_atomic(path: &Path, text: &str) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    let tmp = atomic_tmp_path(path);
    fs::write(&tmp, text.as_bytes())?;
    if let Err(e) = fs::rename(&tmp, path) {
        let _ = fs::remove_file(&tmp);
        return Err(e.into());
    }
    Ok(())
}

fn chmod_600_best_effort(path: &Path) {
    let _ = std::process::Command::new("chmod").arg("0600").arg(path).status();
}

fn normalize_input(input: SubscriptionInput) -> Result<SubscriptionInput> {
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

fn generate_id(name: &str, url: &str) -> String {
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

fn ensure_id(id: &str) -> Result<()> {
    if id.len() < 5 || id.len() > 64 || !id.starts_with("sub_") || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        bail!("invalid subscription id");
    }
    Ok(())
}

fn make_client() -> Result<Client> {
    Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(25))
        .redirect(Policy::none())
        .build()
        .context("build subscription HTTP client")
}

fn local_day_of_month() -> u32 {
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

fn happ_compatible_user_agent(item: &Subscription) -> String {
    // Happ 4.3.0 alternates one digit by local day-of-month parity.
    // Keep this generated only when the user did not provide a custom UA.
    let parity_digit = if local_day_of_month() % 2 == 0 { '6' } else { '5' };
    let os = if item.device_os.eq_ignore_ascii_case("AndroidTV") { "AndroidTV" } else { "Android" };
    format!("Happ/4.3.0/{os}/17878408622471643{parity_digit}38")
}

fn build_headers(item: &Subscription) -> Result<HeaderMap> {
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

fn send_once(client: &Client, item: &Subscription, url: &Url) -> Result<Response> {
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

fn resolve_happ_redirect(location: &str) -> Option<Url> {
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

fn percent_decode(raw: &str) -> String {
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

fn fetch_response(item: &Subscription) -> Result<(Response, Url)> {
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
        Ok((provider_text, mut status, nodes)) => {
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

fn refresh_inner(item: &Subscription) -> Result<(String, SubscriptionStatus, Vec<SubscriptionNode>)> {
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

fn header_string(headers: &HeaderMap, key: &str) -> Option<String> {
    headers.get(key).and_then(|v| v.to_str().ok()).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string)
}

fn parse_interval_minutes(raw: &str) -> Option<u64> {
    // Clash/Happ profile-update-interval is expressed in hours.
    let hours = raw.trim().parse::<u64>().ok()?;
    Some(hours.saturating_mul(60).clamp(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES))
}

fn decode_profile_title(raw: String) -> String {
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

fn parse_subscription_userinfo(raw: &str, status: &mut SubscriptionStatus) {
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

fn json_string_any(value: &JsonValue, keys: &[&str]) -> String {
    for key in keys {
        if let Some(v) = value.get(*key) {
            if let Some(s) = v.as_str() { return s.trim().to_string(); }
            if let Some(n) = v.as_u64() { return n.to_string(); }
        }
    }
    String::new()
}

fn json_u16_any(value: &JsonValue, keys: &[&str]) -> u16 {
    for key in keys {
        if let Some(v) = value.get(*key) {
            let parsed = v.as_u64().and_then(|n| u16::try_from(n).ok())
                .or_else(|| v.as_str().and_then(|s| s.parse::<u16>().ok()));
            if let Some(port) = parsed { return port; }
        }
    }
    0
}

fn normalized_protocol(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "hy2" => "hysteria2".to_string(),
        "ss" => "shadowsocks".to_string(),
        "wg" => "wireguard".to_string(),
        value => value.to_string(),
    }
}

fn targets_for_protocol(protocol: &str) -> Vec<String> {
    match protocol {
        "hysteria2" => vec!["sing-box".to_string(), "hysteria2".to_string()],
        "wireguard" => vec!["sing-box".to_string(), "wireproxy".to_string()],
        "vless" | "vmess" | "trojan" | "shadowsocks" | "socks" => vec!["sing-box".to_string()],
        _ => Vec::new(),
    }
}

fn stable_node_id(subscription_id: &str, protocol: &str, name: &str, occurrence: usize) -> String {
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

fn node_from_definition(subscription_id: &str, definition: JsonValue, occurrence: usize) -> Option<SubscriptionNode> {
    let protocol = normalized_protocol(&json_string_any(&definition, &["type", "protocol"]));
    if protocol.is_empty() { return None; }
    let name = json_string_any(&definition, &["name", "tag", "remarks"]);
    let name = if name.is_empty() { format!("{} {}", protocol, occurrence + 1) } else { name };
    let server = json_string_any(&definition, &["server", "address", "host"]);
    let port = json_u16_any(&definition, &["port", "server_port", "server-port"]);
    let targets = if server.is_empty() || port == 0 { Vec::new() } else { targets_for_protocol(&protocol) };
    Some(SubscriptionNode {
        id: stable_node_id(subscription_id, &protocol, &name, occurrence),
        name,
        protocol: protocol.clone(),
        server,
        port,
        definition,
        targets,
    })
}

fn definition_from_uri(line: &str) -> Option<JsonValue> {
    let scheme = line.split_once("://")?.0.to_ascii_lowercase();
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
                return definition_from_uri(&format!("ss://{decoded}#{fragment}"));
            }
        }
    }

    let url = Url::parse(line).ok()?;
    let mut obj = serde_json::Map::<String, JsonValue>::new();
    obj.insert("type".to_string(), json!(normalized_protocol(&scheme)));
    if let Some(host) = url.host_str() { obj.insert("server".to_string(), json!(host)); }
    if let Some(port) = url.port() { obj.insert("port".to_string(), json!(port)); }
    let name = url.fragment().unwrap_or("").trim();
    if !name.is_empty() { obj.insert("name".to_string(), json!(name)); }
    let user = url.username();
    let password = url.password().unwrap_or("");
    match normalized_protocol(&scheme).as_str() {
        "vless" | "vmess" => { if !user.is_empty() { obj.insert("uuid".to_string(), json!(user)); } }
        "trojan" | "hysteria2" => {
            if !user.is_empty() { obj.insert("password".to_string(), json!(user)); }
            if !password.is_empty() { obj.insert("password".to_string(), json!(password)); }
        }
        "wireguard" => { if !user.is_empty() { obj.insert("private-key".to_string(), json!(user)); } }
        "shadowsocks" => {
            let credential = if !password.is_empty() {
                format!("{user}:{password}")
            } else {
                general_purpose::STANDARD.decode(user)
                    .or_else(|_| general_purpose::STANDARD_NO_PAD.decode(user))
                    .or_else(|_| general_purpose::URL_SAFE.decode(user))
                    .or_else(|_| general_purpose::URL_SAFE_NO_PAD.decode(user)).ok()
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
        obj.insert(key.into_owned(), json!(value.into_owned()));
    }
    Some(JsonValue::Object(obj))
}

fn parse_subscription_nodes(item: &Subscription, text: &str) -> Result<Vec<SubscriptionNode>> {
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

fn bool_any(value: &JsonValue, keys: &[&str]) -> bool {
    keys.iter().find_map(|key| value.get(*key)).map(|v| {
        v.as_bool().unwrap_or_else(|| matches!(v.as_str().unwrap_or("").to_ascii_lowercase().as_str(), "1" | "true" | "yes"))
    }).unwrap_or(false)
}

fn safe_server_name(raw: &str) -> String {
    let mut out: String = raw.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' }).collect();
    while out.contains("__") { out = out.replace("__", "_"); }
    out = out.trim_matches('_').chars().take(56).collect();
    if out.is_empty() { "subscription_server".to_string() } else { out }
}

fn target_server_root(target: &str, profile: &str, server: &str) -> Result<PathBuf> {
    crate::programs::singbox::ensure_valid_profile_name(profile)?;
    crate::programs::singbox::ensure_valid_profile_name(server)?;
    let program = match target {
        "sing-box" => "singbox",
        "hysteria2" => "hysteria2",
        "wireproxy" => "wireproxy",
        _ => bail!("unsupported subscription import target"),
    };
    let profile_root = PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/{program}/profile/{profile}"));
    if !profile_root.is_dir() { bail!("target profile not found"); }
    Ok(profile_root.join("server").join(server))
}

fn ensure_target_accepts_new_server(target: &str, profile: &str) -> Result<()> {
    if !matches!(target, "sing-box" | "hysteria2") { return Ok(()); }
    let program = if target == "sing-box" { "singbox" } else { "hysteria2" };
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

fn next_local_port(start: u16) -> u16 {
    let used = crate::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    (start..=u16::MAX).find(|port| !used.contains(port)).unwrap_or(start)
}

fn tls_json(definition: &JsonValue, default_enabled: bool) -> Option<JsonValue> {
    let security = json_string_any(definition, &["security", "tls"]);
    let enabled = default_enabled || security.eq_ignore_ascii_case("tls") || security.eq_ignore_ascii_case("reality") || bool_any(definition, &["tls"]);
    if !enabled { return None; }
    let sni = json_string_any(definition, &["sni", "servername", "server-name", "peer"]);
    let mut tls = json!({"enabled": true, "insecure": bool_any(definition, &["skip-cert-verify", "allowInsecure", "insecure"])});
    if !sni.is_empty() { tls["server_name"] = json!(sni); }
    let fp = json_string_any(definition, &["client-fingerprint", "fp"]);
    if !fp.is_empty() { tls["utls"] = json!({"enabled": true, "fingerprint": fp}); }
    let reality = definition.get("reality-opts").or_else(|| definition.get("reality_opts"));
    let public_key = reality.map(|v| json_string_any(v, &["public-key", "public_key"]))
        .filter(|v| !v.is_empty()).unwrap_or_else(|| json_string_any(definition, &["pbk", "public-key"]));
    if !public_key.is_empty() {
        let short_id = reality.map(|v| json_string_any(v, &["short-id", "short_id"]))
            .filter(|v| !v.is_empty()).unwrap_or_else(|| json_string_any(definition, &["sid", "short-id"]));
        tls["reality"] = json!({"enabled": true, "public_key": public_key, "short_id": short_id});
    }
    Some(tls)
}

fn singbox_outbound(node: &SubscriptionNode) -> Result<JsonValue> {
    let d = &node.definition;
    let mut out = json!({"type": node.protocol, "tag": "proxy", "server": node.server, "server_port": node.port});
    match node.protocol.as_str() {
        "vless" => {
            out["uuid"] = json!(json_string_any(d, &["uuid", "id"]));
            let flow = json_string_any(d, &["flow"]); if !flow.is_empty() { out["flow"] = json!(flow); }
            if let Some(tls) = tls_json(d, false) { out["tls"] = tls; }
        }
        "vmess" => {
            out["uuid"] = json!(json_string_any(d, &["uuid", "id"]));
            out["security"] = json!(json_string_any(d, &["cipher", "security", "scy"]).trim().to_string());
            out["alter_id"] = json!(json_string_any(d, &["alterId", "alter-id", "aid"]).parse::<u64>().unwrap_or(0));
            if let Some(tls) = tls_json(d, false) { out["tls"] = tls; }
        }
        "trojan" => {
            out["password"] = json!(json_string_any(d, &["password"]));
            if let Some(tls) = tls_json(d, true) { out["tls"] = tls; }
        }
        "shadowsocks" => {
            out["method"] = json!(json_string_any(d, &["cipher", "method"]));
            out["password"] = json!(json_string_any(d, &["password"]));
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
            out["private_key"] = json!(json_string_any(d, &["private-key", "private_key"]));
            out["peer_public_key"] = json!(json_string_any(d, &["public-key", "public_key", "peer_public_key"]));
            let psk = json_string_any(d, &["pre-shared-key", "pre_shared_key", "preshared-key"]);
            if !psk.is_empty() { out["pre_shared_key"] = json!(psk); }
            let mut addresses = Vec::<String>::new();
            for key in ["ip", "ipv6", "address"] {
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

    let network = json_string_any(d, &["network", "type"]);
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
    }
    Ok(out)
}

fn render_singbox_config(node: &SubscriptionNode, local_port: u16) -> Result<String> {
    let outbound = singbox_outbound(node)?;
    let config = json!({
        "log": {"level": "info"},
        "inbounds": [{"type":"mixed", "tag":"mixed-in", "listen":"127.0.0.1", "listen_port":local_port}],
        "outbounds": [outbound, {"type":"direct", "tag":"direct"}, {"type":"direct", "tag":"bypass"}],
        "route": {"rules":[{"inbound":["mixed-in"], "action":"sniff"}], "final":"proxy", "auto_detect_interface":true}
    });
    Ok(serde_json::to_string_pretty(&config)?)
}

fn render_hysteria2_config(node: &SubscriptionNode, local_port: u16) -> Result<String> {
    if node.protocol != "hysteria2" { bail!("node is not Hysteria2"); }
    let d = &node.definition;
    let sni = json_string_any(d, &["sni", "servername", "server-name", "peer"]);
    let mut config = json!({
        "server": format!("{}:{}", node.server, node.port),
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

fn render_wireproxy_config(node: &SubscriptionNode, local_port: u16) -> Result<String> {
    if node.protocol != "wireguard" { bail!("node is not WireGuard"); }
    let d = &node.definition;
    let private_key = json_string_any(d, &["private-key", "private_key"]);
    let public_key = json_string_any(d, &["public-key", "public_key", "peer_public_key"]);
    if private_key.is_empty() || public_key.is_empty() { bail!("WireGuard keys are incomplete"); }
    let mut addresses = Vec::<String>::new();
    for key in ["ip", "ipv6", "address"] {
        if let Some(v) = d.get(key) {
            if let Some(s) = v.as_str() { addresses.push(s.to_string()); }
            if let Some(a) = v.as_array() { addresses.extend(a.iter().filter_map(JsonValue::as_str).map(str::to_string)); }
        }
    }
    if addresses.is_empty() { bail!("WireGuard local address is missing"); }
    let psk = json_string_any(d, &["pre-shared-key", "pre_shared_key", "preshared-key"]);
    let mut text = format!("[Interface]\nPrivateKey = {private_key}\nAddress = {}\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = {public_key}\n", addresses.join(", "));
    if !psk.is_empty() { text.push_str(&format!("PresharedKey = {psk}\n")); }
    text.push_str(&format!("Endpoint = {}:{}\nAllowedIPs = 0.0.0.0/0, ::/0\n\n[Socks5]\nBindAddress = 127.0.0.1:{local_port}\n", node.server, node.port));
    Ok(text)
}

fn link_id_for(target: &str, profile: &str, server: &str) -> String {
    let mut h = Sha256::new();
    h.update(target.as_bytes()); h.update(b"\0");
    h.update(profile.as_bytes()); h.update(b"\0");
    h.update(server.as_bytes());
    format!("link_{}", &hex::encode(h.finalize())[..20])
}

fn ensure_link_id(id: &str) -> Result<()> {
    if id.len() < 10 || id.len() > 64 || !id.starts_with("link_") || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        bail!("invalid subscription link id");
    }
    Ok(())
}

fn local_port_for_link(link: &SubscriptionLink) -> u16 {
    let root = target_server_root(&link.target, &link.profile, &link.server_name).ok();
    match (link.target.as_str(), root) {
        ("sing-box", Some(root)) => fs::read_to_string(root.join("setting.json")).ok()
            .and_then(|raw| serde_json::from_str::<JsonValue>(&raw).ok())
            .map(|v| json_u16_any(&v, &["port"])).filter(|v| *v > 0).unwrap_or(2080),
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

fn write_link_config(link: &SubscriptionLink, node: &SubscriptionNode) -> Result<()> {
    let root = target_server_root(&link.target, &link.profile, &link.server_name)?;
    if !root.is_dir() { bail!("linked local server no longer exists"); }
    let port = local_port_for_link(link);
    let (path, content) = match link.target.as_str() {
        "sing-box" => (root.join("config.json"), render_singbox_config(node, port)?),
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
    if !node.targets.iter().any(|value| value == &target) { bail!("node cannot be imported into this target"); }
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

fn sync_imported_nodes(subscription_id: &str, nodes: &[SubscriptionNode]) {
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

fn normalize_provider_body(text: &str) -> Result<(String, usize)> {
    // A full Clash YAML subscription may contain groups/rules in addition to `proxies`.
    // A file proxy-provider only needs the proxies collection, so extract it and keep
    // the generated provider deterministic. URI/base64 subscriptions are supported by
    // Mihomo directly and can stay in their native form.
    if let Ok(root) = serde_yaml::from_str::<YamlValue>(text) {
        if let Some(map) = root.as_mapping() {
            let key = YamlValue::String("proxies".to_string());
            if let Some(proxies) = map.get(&key).and_then(YamlValue::as_sequence) {
                let count = proxies.len();
                let mut out = YamlMapping::new();
                out.insert(key, YamlValue::Sequence(proxies.clone()));
                return Ok((serde_yaml::to_string(&YamlValue::Mapping(out))?, count));
            }
        }
    }

    // Some valid Clash YAML uses constructs unsupported by the generic YAML parser.
    // If a top-level proxies section is clearly present, keep the original body and
    // let Mihomo be the final parser rather than rejecting a configuration it accepts.
    let yaml_count = count_yaml_proxy_names(text);
    if yaml_count > 0 { return Ok((text.trim().to_string() + "\n", yaml_count)); }

    let uri_lines: Vec<&str> = text.lines()
        .map(str::trim)
        .filter(|line| is_supported_uri_line(line))
        .collect();
    if !uri_lines.is_empty() { return Ok((uri_lines.join("\n") + "\n", uri_lines.len())); }

    if let Some(decoded) = decode_base64_subscription(text) {
        if let Ok(root) = serde_yaml::from_str::<YamlValue>(&decoded) {
            if let Some(map) = root.as_mapping() {
                let key = YamlValue::String("proxies".to_string());
                if let Some(proxies) = map.get(&key).and_then(YamlValue::as_sequence) {
                    let count = proxies.len();
                    let mut out = YamlMapping::new();
                    out.insert(key, YamlValue::Sequence(proxies.clone()));
                    return Ok((serde_yaml::to_string(&YamlValue::Mapping(out))?, count));
                }
            }
        }
        let decoded_yaml_count = count_yaml_proxy_names(&decoded);
        if decoded_yaml_count > 0 {
            return Ok((decoded.trim().to_string() + "\n", decoded_yaml_count));
        }
        let decoded_uri_count = count_uri_lines(&decoded);
        if decoded_uri_count > 0 {
            // Keep the original base64 representation; Mihomo accepts base64 URI providers.
            return Ok((text.trim().to_string() + "\n", decoded_uri_count));
        }
    }

    bail!("subscription response does not look like a Mihomo/Clash provider or URI subscription")
}

fn count_yaml_proxy_names(text: &str) -> usize {
    let mut in_proxies = false;
    let mut count = 0usize;
    for line in text.lines() {
        if !line.starts_with(' ') && !line.starts_with('\t') {
            let trimmed = line.trim();
            if trimmed == "proxies:" { in_proxies = true; continue; }
            if in_proxies && !trimmed.is_empty() && !trimmed.starts_with('#') { break; }
        }
        if in_proxies {
            let trimmed = line.trim_start();
            if trimmed.starts_with("- name:") || trimmed.starts_with("- {name:") || trimmed.starts_with("- { name:") { count += 1; }
        }
    }
    count
}

fn is_supported_uri_line(line: &str) -> bool {
    const SCHEMES: &[&str] = &[
        "ss://", "ssr://", "vmess://", "vless://", "trojan://",
        "hysteria://", "hysteria2://", "hy2://", "tuic://",
        "socks://", "socks5://", "http://", "https://", "wireguard://",
        "anytls://", "snell://", "ssh://", "mieru://",
    ];
    SCHEMES.iter().any(|scheme| line.starts_with(scheme))
}

fn count_uri_lines(text: &str) -> usize {
    text.lines().map(str::trim).filter(|line| is_supported_uri_line(line)).count()
}

fn decode_base64_subscription(text: &str) -> Option<String> {
    let compact: String = text.chars().filter(|c| !c.is_whitespace()).collect();
    if compact.len() < 16 || compact.len() > 32 * 1024 * 1024 { return None; }
    let bytes = general_purpose::STANDARD.decode(&compact)
        .or_else(|_| general_purpose::STANDARD_NO_PAD.decode(&compact))
        .or_else(|_| general_purpose::URL_SAFE.decode(&compact))
        .or_else(|_| general_purpose::URL_SAFE_NO_PAD.decode(&compact))
        .ok()?;
    String::from_utf8(bytes).ok()
}

fn profile_provider_dir(profile: &str) -> PathBuf {
    crate::programs::mihomo::profile_root(profile).join("work/subscriptions")
}

fn profile_provider_path(profile: &str, id: &str) -> PathBuf {
    profile_provider_dir(profile).join(format!("{id}.yaml"))
}

fn sync_provider_to_profile(profile: &str, id: &str) -> Result<()> {
    crate::programs::mihomo::ensure_valid_profile_name(profile)?;
    ensure_id(id)?;
    let src = provider_path(id);
    if !src.is_file() { bail!("subscription provider is not available"); }
    let dst = profile_provider_path(profile, id);
    if let Some(parent) = dst.parent() { fs::create_dir_all(parent)?; }
    let data = fs::read(&src)?;
    let tmp = atomic_tmp_path(&dst);
    fs::write(&tmp, data)?;
    if let Err(e) = fs::rename(&tmp, &dst) {
        let _ = fs::remove_file(&tmp);
        return Err(e.into());
    }
    chmod_600_best_effort(&dst);
    Ok(())
}

fn sync_provider_to_profiles(id: &str) {
    for profile in crate::programs::mihomo::profiles_using_subscription(id) {
        if let Err(e) = sync_provider_to_profile(&profile, id) {
            log::warn!("mihomo subscription sync failed id={} profile={}: {e:#}", id, profile);
        }
    }
}

fn cleanup_profile_provider_copies(id: &str) {
    if let Ok(active) = crate::programs::mihomo::read_active() {
        for profile in active.profiles.keys() {
            let _ = fs::remove_file(profile_provider_path(profile, id));
        }
    }
}

pub fn enqueue_refresh(id: &str) -> Result<bool> {
    ensure_id(id)?;
    let item = get(id)?;
    if !item.enabled { bail!("subscription is disabled"); }
    {
        let mut set = refreshing_set().lock().map_err(|_| anyhow::anyhow!("subscription refresh lock poisoned"))?;
        if !set.insert(id.to_string()) { return Ok(false); }
    }
    let id_owned = id.to_string();
    let started_modified_at = item.modified_at;
    thread::spawn(move || {
        if let Err(e) = refresh(&id_owned) {
            log::warn!("subscription refresh failed id={}: {e:#}", id_owned);
        }
        if let Ok(mut set) = refreshing_set().lock() { set.remove(&id_owned); }

        // If the subscription was edited/re-enabled while this request was in flight,
        // the enqueue attempt made by update() was intentionally deduplicated. Queue
        // one fresh request now, after the old worker has released the per-ID slot.
        let needs_follow_up = get(&id_owned)
            .map(|current| current.enabled && current.modified_at != started_modified_at)
            .unwrap_or(false);
        if needs_follow_up {
            let _ = enqueue_refresh(&id_owned);
        }
    });
    Ok(true)
}

pub fn enqueue_refresh_all() -> Result<usize> {
    let store = read_store()?;
    let mut queued = 0usize;
    for item in store.subscriptions.values().filter(|s| s.enabled) {
        if enqueue_refresh(&item.id).unwrap_or(false) { queued += 1; }
    }
    Ok(queued)
}

pub fn refresh_due_all() {
    let Ok(store) = read_store() else { return; };
    let now = now_unix();
    for item in store.subscriptions.values().filter(|s| s.enabled) {
        let status = read_status(&item.id);
        let due = !provider_path(&item.id).is_file() || status.next_update_at == 0 || status.next_update_at <= now;
        if due {
            let _ = enqueue_refresh(&item.id);
        }
    }
}

pub fn start_background_worker() {
    if WORKER_STARTED.swap(true, Ordering::AcqRel) { return; }
    thread::spawn(|| loop {
        refresh_due_all();
        thread::sleep(Duration::from_secs(60));
    });
}

pub fn enabled_selected(id: &str) -> bool {
    read_store().ok().and_then(|s| s.subscriptions.get(id).cloned()).map(|s| s.enabled).unwrap_or(false)
}

fn cleanup_profile_subscription_dir(dir: &Path, keep: &BTreeSet<String>) {
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with("sub_") && name.ends_with(".yaml") && !keep.contains(&name) {
                let _ = fs::remove_file(entry.path());
            }
        }
    }
}

pub fn apply_selected_to_runtime_yaml(profile: &str, raw_yaml: &str, selected_ids: &[String]) -> Result<String> {
    crate::programs::mihomo::ensure_valid_profile_name(profile)?;
    let local_dir = profile_provider_dir(profile);
    fs::create_dir_all(&local_dir)?;
    if selected_ids.is_empty() {
        cleanup_profile_subscription_dir(&local_dir, &BTreeSet::new());
        return Ok(raw_yaml.to_string());
    }
    let store = read_store().unwrap_or_default();
    let mut selected = Vec::<(&Subscription, String)>::new();
    let mut seen = BTreeSet::<String>::new();
    for id in selected_ids {
        if !seen.insert(id.clone()) { continue; }
        let Some(item) = store.subscriptions.get(id) else { continue; };
        if !item.enabled { continue; }
        let path = provider_path(id);
        if !path.is_file() {
            // Keep the runtime configuration stable even if the first download is
            // still in progress (or temporarily failed). Mihomo can load an empty
            // file provider now and will see the atomically replaced file later.
            write_text_atomic(&path, "proxies: []\n")
                .with_context(|| format!("create placeholder provider for subscription {id}"))?;
            chmod_600_best_effort(&path);
        }
        sync_provider_to_profile(profile, id)
            .with_context(|| format!("sync subscription {} into Mihomo profile {} HomeDir", id, profile))?;
        selected.push((item, format!("zdt_{}", id)));
    }
    let keep: BTreeSet<String> = selected.iter().map(|(item, _)| format!("{}.yaml", item.id)).collect();
    cleanup_profile_subscription_dir(&local_dir, &keep);
    if selected.is_empty() { return Ok(raw_yaml.to_string()); }

    let mut root: YamlValue = serde_yaml::from_str(raw_yaml).context("parse mihomo runtime YAML for subscriptions")?;
    let root_map = root.as_mapping_mut().ok_or_else(|| anyhow::anyhow!("mihomo config root must be a YAML mapping"))?;

    let providers_key = YamlValue::String("proxy-providers".to_string());
    if !root_map.contains_key(&providers_key) {
        root_map.insert(providers_key.clone(), YamlValue::Mapping(YamlMapping::new()));
    }
    let providers = root_map.get_mut(&providers_key).and_then(YamlValue::as_mapping_mut)
        .ok_or_else(|| anyhow::anyhow!("proxy-providers must be a YAML mapping"))?;

    let mut provider_names = Vec::<String>::new();
    for (item, base_provider_name) in &selected {
        let mut provider_name = base_provider_name.clone();
        let mut suffix = 1u32;
        while providers.contains_key(&YamlValue::String(provider_name.clone())) {
            provider_name = format!("{base_provider_name}_{suffix}");
            suffix += 1;
        }
        provider_names.push(provider_name.clone());
        let mut m = YamlMapping::new();
        m.insert(YamlValue::String("type".to_string()), YamlValue::String("file".to_string()));
        m.insert(YamlValue::String("path".to_string()), YamlValue::String(format!("./subscriptions/{}.yaml", item.id)));
        m.insert(YamlValue::String("interval".to_string()), serde_yaml::to_value(PROVIDER_RELOAD_INTERVAL_SECS)?);
        providers.insert(YamlValue::String(provider_name), YamlValue::Mapping(m));
    }

    let groups_key = YamlValue::String("proxy-groups".to_string());
    if !root_map.contains_key(&groups_key) {
        root_map.insert(groups_key.clone(), YamlValue::Sequence(Vec::new()));
    }
    let groups = root_map.get_mut(&groups_key).and_then(YamlValue::as_sequence_mut)
        .ok_or_else(|| anyhow::anyhow!("proxy-groups must be a YAML sequence"))?;

    let mut found_proxy = false;
    for group in groups.iter_mut() {
        let Some(map) = group.as_mapping_mut() else { continue; };
        let name_key = YamlValue::String("name".to_string());
        let name = map.get(&name_key).and_then(YamlValue::as_str).unwrap_or("");
        if name != "Proxy" { continue; }
        found_proxy = true;
        let use_key = YamlValue::String("use".to_string());
        if !map.contains_key(&use_key) { map.insert(use_key.clone(), YamlValue::Sequence(Vec::new())); }
        let use_seq = map.get_mut(&use_key).and_then(YamlValue::as_sequence_mut)
            .ok_or_else(|| anyhow::anyhow!("Proxy group use must be a YAML sequence"))?;
        let existing: BTreeSet<String> = use_seq.iter().filter_map(YamlValue::as_str).map(str::to_string).collect();
        for name in &provider_names {
            if !existing.contains(name) { use_seq.push(YamlValue::String(name.clone())); }
        }
        break;
    }
    if !found_proxy {
        let mut group = YamlMapping::new();
        group.insert(YamlValue::String("name".to_string()), YamlValue::String("Proxy".to_string()));
        group.insert(YamlValue::String("type".to_string()), YamlValue::String("select".to_string()));
        // A provider-only select group is valid and avoids inventing a proxy name
        // (such as DIRECT-OUT) that a custom user config may not define.
        group.insert(YamlValue::String("use".to_string()), YamlValue::Sequence(provider_names.iter().cloned().map(YamlValue::String).collect()));
        groups.push(YamlValue::Mapping(group));
    }

    let out = serde_yaml::to_string(&root).context("serialize Mihomo runtime YAML with subscriptions")?;
    Ok(out)
}
