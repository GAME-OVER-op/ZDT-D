use anyhow::{Result};
use crate::common::network::{NETID_OPENVPN};
use crate::programs::registry::{ProgramId};
use crate::network::vpn::{VpnProfileCore};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use serde::{Deserialize, Deserializer, Serialize};
use std::{path::{PathBuf}, time::{Duration}};


use super::profile::*;

pub(super) const OPENVPN_BIN: &str = "/data/adb/modules/ZDT-D/bin/openvpn";
pub(super) const OPENVPN_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/openvpn";
pub(super) const VPN_CORE: VpnProfileCore = VpnProfileCore::new(ProgramId::OpenVpn, NETID_OPENVPN);

pub(super) fn stable_netid_for(profile: &str) -> Result<u32> {
    VPN_CORE.stable_netid(&read_active().unwrap_or_default(), profile)
}
pub(super) const TUN_WAIT: Duration = Duration::from_secs(25);

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    pub tun: String,
    #[serde(default, deserialize_with = "deserialize_dns")]
    pub dns: Vec<String>,
    #[serde(default = "default_endpoint_resolve")]
    pub endpoint_resolve: bool,
}

pub(super) fn default_endpoint_resolve() -> bool { true }

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "tun1".to_string(),
            dns: vec!["94.140.14.14".to_string(), "94.140.15.15".to_string()],
            endpoint_resolve: default_endpoint_resolve(),
        }
    }
}

#[derive(Debug, Clone)]
pub(super) struct ProfilePlan {
    pub(super) name: String,
    pub(super) setting: ProfileSetting,
    pub(super) profile_dir: PathBuf,
    pub(super) config_path: PathBuf,
    pub(super) runtime_config_path: PathBuf,
    pub(super) endpoint_escape_ips: Vec<String>,
    pub(super) app_in: PathBuf,
    pub(super) app_out: PathBuf,
    pub(super) log_path: PathBuf,
}

#[derive(Debug, Clone)]
pub(super) struct TunInfo {
    pub(super) cidr: String,
    pub(super) gateway: Option<String>,
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
                    for part in crate::common::config::split_comma_whitespace(s) {
                        out.push(part);
                    }
                }
            }
        }
        serde_json::Value::String(s) => {
            for part in crate::common::config::split_comma_whitespace(&s) {
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


