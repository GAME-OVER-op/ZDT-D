use serde::{Deserialize, Serialize};
use serde_json::{Value as JsonValue};
use std::{collections::{BTreeMap, BTreeSet}, sync::{atomic::{AtomicBool}, Mutex, OnceLock}};


// This is the global ZDT-D subscription library.  It deliberately lives outside
// every program directory: Mihomo is only one consumer of the library.
pub(super) const ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/subscriptions";
pub(super) const STORE: &str = "/data/adb/modules/ZDT-D/working_folder/subscriptions/subscriptions.json";
pub(super) const DATA_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/subscriptions/data";
pub(super) const LINKS: &str = "/data/adb/modules/ZDT-D/working_folder/subscriptions/links.json";
pub(super) const DEFAULT_INTERVAL_MINUTES: u64 = 60;
pub(super) const MIN_INTERVAL_MINUTES: u64 = 15;
pub(super) const MAX_INTERVAL_MINUTES: u64 = 7 * 24 * 60;
pub(super) const MAX_RESPONSE_BYTES: u64 = 24 * 1024 * 1024;
pub(super) const PROVIDER_RELOAD_INTERVAL_SECS: i64 = 60;
pub(super) const RETRY_AFTER_ERROR_MINUTES: u64 = 15;
pub(super) static WORKER_STARTED: AtomicBool = AtomicBool::new(false);
pub(super) static REFRESHING: OnceLock<Mutex<BTreeSet<String>>> = OnceLock::new();
pub(super) static STORE_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
pub(super) static LINKS_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

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
    pub transport: String,
    #[serde(default)]
    pub security: String,
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
    /// Original one-line share URI when the provider supplied one.  Keeping it
    /// intact avoids losing uncommon query parameters when copying the node.
    #[serde(default)]
    pub share_link: String,
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

#[derive(Debug, Deserialize)]
pub struct BulkImportNodesRequest {
    pub target: String,
    pub profile: String,
}

#[derive(Debug, Serialize)]
pub struct BulkImportFailure {
    pub node_id: String,
    pub name: String,
    pub error: String,
}

#[derive(Debug, Serialize)]
pub struct BulkImportResult {
    pub target: String,
    pub profile: String,
    pub compatible: usize,
    pub imported: usize,
    pub skipped: usize,
    pub failed: usize,
    pub failures: Vec<BulkImportFailure>,
}

#[derive(Debug, Deserialize)]
pub struct ManualImportPreviewRequest {
    pub content: String,
}

#[derive(Debug, Deserialize)]
pub struct ManualImportRequest {
    pub content: String,
    pub target: String,
    pub profile: String,
    pub server_name: String,
}

pub(super) fn default_true() -> bool { true }
pub(super) fn default_interval() -> u64 { DEFAULT_INTERVAL_MINUTES }
pub(super) fn default_hwid_mode() -> String { "header".to_string() }
pub(super) fn default_device_os() -> String { "Android".to_string() }

