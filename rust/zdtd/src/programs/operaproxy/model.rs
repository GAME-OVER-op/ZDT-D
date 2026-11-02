

use serde::{Deserialize, Serialize};
use std::{time::{Duration}};

use crate::{common::{network::default_iface}};


pub(super) const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
pub(super) const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
pub(super) const BIN_DIR: &str = "/data/adb/modules/ZDT-D/bin";

pub(super) const OPERA_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy";
pub(super) const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy/active.json";
pub(super) const PORT_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy/port.json";
pub(super) const SNI_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/sni.json";
pub(super) const SERVER_TXT: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/server.txt";
pub(super) const API_PROXY_CHECK_TARGET_HOST: &str = "api2.sec-tunnel.com";
pub(super) const API_PROXY_CHECK_TARGET_PORT: u16 = 443;
pub(super) const API_PROXY_CHECK_PARALLEL: usize = 5;
pub(super) const API_PROXY_CHECK_TIMEOUT: Duration = Duration::from_secs(4);
pub(super) const API_PROXY_LIST_TXT: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/api_proxy_list.txt";
pub(super) const API_PROXY_LIST_DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(15);
pub(super) const API_PROXY_LIST_MAX_BYTES: usize = 1024 * 1024;
// Opera-proxy CA bundle for certificate verification. We keep it fixed to prevent config tampering.
pub(super) const OPERA_CAFILE: &str = "/data/adb/modules/ZDT-D/strategic/certificate/ca.bundle";

// Configurable opera-proxy arguments stored as JSON (editable from the app UI).
pub(super) const OPERA_ARGS_JSON: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/args.json";

// Configurable bootstrap DNS resolvers stored as a JSON array of strings (editable from the app UI).
pub(super) const BOOTSTRAP_DNS_JSON: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/bootstrap_dns.json";

// Built-in fallback bootstrap DNS resolvers used when bootstrap_dns.json is missing or empty.
pub(super) const DEFAULT_BOOTSTRAP_DNS: &str = "tls://dns.geohide.ru,https://xbox-dns.ru/dns-query,tls://9.9.9.9:853,https://ru-mow.doh.sb/dns-query,https://1.1.1.3/dns-query,https://security.cloudflare-dns.com/dns-query,https://wikimedia-dns.org/dns-query,https://dns.adguard-dns.com/dns-query,https://dns.quad9.net/dns-query,https://dns.comss.one/dns-query,https://router.comss.one/dns-query";

pub(super) const APP_UID_USER: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/uid/user_program";
pub(super) const APP_OUT_USER: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/out/user_program";

// Optional per-interface lists (same naming as nfqws)
pub(super) const APP_UID_MOBILE: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/uid/mobile_program";
pub(super) const APP_UID_WIFI: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/uid/wifi_program";

pub(super) const APP_OUT_MOBILE: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/out/mobile_program";
pub(super) const APP_OUT_WIFI: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/out/wifi_program";

// byedpi args (text file, contents appended after "-i 127.0.0.1 -p <port> -x 1")
pub(super) const BYEDPI_START_TXT: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/byedpi/config/start.txt";
pub(super) const BYEDPI_RESTART_TXT: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/byedpi/config/restart.txt";

#[derive(Debug, Deserialize)]
pub(super) struct ActiveJson {
    pub(super) enabled: bool,
}

#[derive(Debug, Deserialize)]
pub(super) struct PortJson {
    // Port where t2s listens (transparent proxy)
    pub(super) t2s_port: u16,
    // Starting port for opera-proxy socks servers (sequence +1)
    pub(super) opera_start_port: u16,
    // byedpi (ciadpi-zdt) local socks upstream
    pub(super) byedpi_port: u16,

    // Optional interface names for per-interface app lists.
    // Defaults to "auto" for backwards compatibility.
    #[serde(default = "default_iface")]
    pub(super) iface_mobile: String,
    #[serde(default = "default_iface")]
    pub(super) iface_wifi: String,
}

/// Configurable opera-proxy arguments.
/// All fields correspond directly to opera-proxy CLI flags.
/// Stored in config/args.json and edited from the app UI.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OperaArgs {
    /// -api-proxy: HTTP(S)/SOCKS5 proxy used by opera-proxy to reach Opera backend API.
    /// Accepts one proxy URL, several proxy URLs separated by commas,
    /// an http(s) URL to a plain text proxy list, or an absolute local
    /// path to a plain text proxy list stored on the device.
    /// Examples:
    ///   "socks5://10.0.0.1:1080,socks5://10.0.0.1:1081"
    ///   "https://example.com/socks5-list.txt"
    ///   "/sdcard/proxies.txt"
    #[serde(default = "default_api_proxy")]
    pub api_proxy: String,

    /// -api-user-agent: User-Agent sent to Opera backend API.
    #[serde(default = "default_api_user_agent")]
    pub api_user_agent: String,

    /// -init-retry-interval: retry interval on startup failure. Example: "3s", "5s".
    #[serde(default = "default_init_retry_interval")]
    pub init_retry_interval: String,

    /// -server-selection: strategy for choosing the fastest server.
    /// Typical values: "fastest", "random".
    #[serde(default = "default_server_selection")]
    pub server_selection: String,

    /// -server-selection-dl-limit: download limit in bytes for speed test. Example: 204800.
    #[serde(default = "default_server_selection_dl_limit")]
    pub server_selection_dl_limit: u64,

    /// -server-selection-test-url: URL used for the speed test.
    #[serde(default = "default_server_selection_test_url")]
    pub server_selection_test_url: String,

    /// -verbosity: logging verbosity level (integer). Default: 50.
    #[serde(default = "default_verbosity")]
    pub verbosity: u32,
}

// ---- Default value functions required by serde ----

pub(super) fn default_api_proxy() -> String {
    "socks5://193.221.203.192:1080".to_string()
}

pub(super) fn default_api_user_agent() -> String {
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 OPR/98.0.0.0".to_string()
}

pub(super) fn default_init_retry_interval() -> String {
    "3s".to_string()
}

pub(super) fn default_server_selection() -> String {
    "fastest".to_string()
}

pub(super) fn default_server_selection_dl_limit() -> u64 {
    204800
}

pub(super) fn default_server_selection_test_url() -> String {
    "https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js".to_string()
}

pub(super) fn default_verbosity() -> u32 {
    50
}

impl Default for OperaArgs {
    fn default() -> Self {
        Self {
            api_proxy: default_api_proxy(),
            api_user_agent: default_api_user_agent(),
            init_retry_interval: default_init_retry_interval(),
            server_selection: default_server_selection(),
            server_selection_dl_limit: default_server_selection_dl_limit(),
            server_selection_test_url: default_server_selection_test_url(),
            verbosity: default_verbosity(),
        }
    }
}

