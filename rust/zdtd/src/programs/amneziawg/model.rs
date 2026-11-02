use anyhow::{Result};
use crate::common::network::{is_ipv4, NETID_AMNEZIAWG};
use crate::programs::registry::{ProgramId};
use crate::network::vpn::{VpnProfileCore};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use serde::{Deserialize, Deserializer, Serialize};
use std::{path::{PathBuf}, sync::atomic::{AtomicBool}, time::{Duration}};


use super::profile::*;
use super::network::*;

pub(super) const AWG_GO_BIN: &str = "/data/adb/modules/ZDT-D/bin/amneziawg-go";
pub(super) const AWG_BIN: &str = "/data/adb/modules/ZDT-D/bin/awg";
pub(super) const AMNEZIAWG_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/amneziawg";
pub(super) const SOCK_DIR: &str = "/data/adb/modules/ZDT-D/working_folder/amneziawg/run/amneziawg";
pub(super) const VPN_CORE: VpnProfileCore = VpnProfileCore::new(ProgramId::AmneziaWg, NETID_AMNEZIAWG);

pub(super) fn stable_netid_for(profile: &str) -> Result<u32> {
    let active = read_active().unwrap_or_default();
    VPN_CORE.stable_netid(&active, profile)
}
pub(super) const LINK_WAIT: Duration = Duration::from_secs(15);
pub(super) const TUN_WAIT: Duration = Duration::from_secs(25);
pub(super) const AWG_TIMEOUT: Duration = Duration::from_secs(10);
pub(super) const HEALTH_IDLE_SLEEP: Duration = Duration::from_secs(2);
pub(super) const HEALTH_INTERVAL_SEC: u64 = 30;
pub(super) const HEALTH_LIFETIME_SEC: u64 = 180;
pub(super) const HEALTH_GRACE_SEC: u64 = 120;
pub(super) const HEALTH_STALE_LOG_COOLDOWN_SEC: u64 = 60;

pub(super) static HEALTH_SUPERVISOR_RUNNING: AtomicBool = AtomicBool::new(false);

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    pub tun: String,
    #[serde(default, deserialize_with = "deserialize_address")]
    pub address: Vec<String>,
    #[serde(default, deserialize_with = "deserialize_dns")]
    pub dns: Vec<String>,
    #[serde(default = "default_mtu")]
    pub mtu: u32,
    #[serde(default = "default_endpoint_resolve")]
    pub endpoint_resolve: bool,
    #[serde(default)]
    pub strip_fwmark: bool,
}

pub(super) fn default_mtu() -> u32 { 1280 }
pub(super) fn default_endpoint_resolve() -> bool { true }
impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "awg0".to_string(),
            address: Vec::new(),
            dns: vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()],
            mtu: default_mtu(),
            endpoint_resolve: default_endpoint_resolve(),
            strip_fwmark: false,
        }
    }
}

#[derive(Debug, Clone)]
pub(super) struct ProfilePlan {
    pub(super) name: String,
    pub(super) setting: ProfileSetting,
    pub(super) profile_dir: PathBuf,
    pub(super) config_path: PathBuf,
    pub(super) app_in: PathBuf,
    pub(super) app_out: PathBuf,
    pub(super) go_log_path: PathBuf,
    pub(super) awg_log_path: PathBuf,
    pub(super) start_log_path: PathBuf,
    pub(super) health_log_path: PathBuf,
    pub(super) health_state_path: PathBuf,
}

#[derive(Debug, Clone)]
pub(super) struct TunInfo {
    pub(super) cidr: String,
    pub(super) gateway: Option<String>,
}

#[derive(Debug, Clone)]
pub(super) struct ImportedConfig {
    pub(super) config: String,
    pub(super) setting: ProfileSetting,
}

pub(super) fn deserialize_address<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = serde_json::Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        serde_json::Value::Array(items) => {
            for item in items {
                if let Some(s) = item.as_str() {
                    out.extend(split_address_text(s));
                }
            }
        }
        serde_json::Value::String(s) => out.extend(split_address_text(&s)),
        serde_json::Value::Null => {}
        _ => return Err(serde::de::Error::custom("address must be array or string")),
    }
    out.sort();
    out.dedup();
    Ok(out)
}

pub(super) fn deserialize_dns<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = serde_json::Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        serde_json::Value::Array(items) => {
            for item in items {
                if let Some(s) = item.as_str() {
                    for part in split_dns_text(s) {
                        out.push(part);
                    }
                }
            }
        }
        serde_json::Value::String(s) => {
            for part in split_dns_text(&s) {
                out.push(part);
            }
        }
        serde_json::Value::Null => {}
        _ => return Err(serde::de::Error::custom("dns must be array or string")),
    }
    out.sort();
    out.dedup();
    Ok(out)
}

pub(super) fn split_address_text(s: &str) -> Vec<String> {
    s.split(|c: char| c == ',' || c.is_ascii_whitespace())
        .filter_map(|x| normalize_ipv4_cidr_token(x.trim()))
        .collect()
}

pub(super) fn split_dns_text(s: &str) -> Vec<String> {
    s.split(|c: char| c == ',' || c.is_ascii_whitespace())
        .map(str::trim)
        .filter(|x| !x.is_empty() && is_ipv4(x))
        .map(ToOwned::to_owned)
        .collect()
}

