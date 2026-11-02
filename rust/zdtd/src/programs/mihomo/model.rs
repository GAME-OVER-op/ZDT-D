use anyhow::{Result};
use crate::common::network::{NETID_MIHOMO};
use crate::programs::registry::{ProgramId};
use crate::network::vpn::{VpnProfileCore, VpnNetdProfileSpec};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use serde::{Deserialize, Serialize};
use std::{path::{PathBuf}, time::Duration};

use crate::{network::vpn::netd::VpnNetdProfile};

use super::profile::*;

pub(super) const MIHOMO_BIN: &str = "/data/adb/modules/ZDT-D/bin/mihomo";
pub(super) const TUN2SOCKS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";
pub(super) const MIHOMO_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/mihomo";
pub(super) const VPN_CORE: VpnProfileCore = VpnProfileCore::new(ProgramId::Mihomo, NETID_MIHOMO);

pub(super) fn stable_netid_for(profile: &str) -> Result<u32> {
    let active = read_active().unwrap_or_default();
    VPN_CORE.stable_netid(&active, profile)
}
pub(super) const MIHOMO_NET_BASE: u32 = 0xC612_8C00; // 198.18.140.0
pub(super) const TUN_WAIT: Duration = Duration::from_secs(18);
pub(super) const PORT_WAIT: Duration = Duration::from_secs(20);

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    pub tun: String,
    #[serde(default = "default_mixed_port")]
    pub mixed_port: u16,
    #[serde(default = "default_loglevel")]
    pub log_level: String,
    #[serde(default = "default_tun2socks_loglevel")]
    pub tun2socks_loglevel: String,
    /// Global ZDT-D subscription IDs selected for this Mihomo profile.
    #[serde(default)]
    pub subscriptions: Vec<String>,
}

pub(super) fn default_mixed_port() -> u16 { 17890 }
pub(super) fn default_loglevel() -> String { "info".to_string() }
pub(super) fn default_tun2socks_loglevel() -> String { "info".to_string() }

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "tun20".to_string(),
            mixed_port: default_mixed_port(),
            log_level: default_loglevel(),
            tun2socks_loglevel: default_tun2socks_loglevel(),
            subscriptions: Vec::new(),
        }
    }
}

#[derive(Debug, Clone)]
pub(super) struct ProfilePlan {
    pub(super) name: String,
    pub(super) setting: ProfileSetting,
    pub(super) profile_dir: PathBuf,
    pub(super) work_dir: PathBuf,
    pub(super) config_path: PathBuf,
    pub(super) runtime_config_path: PathBuf,
    pub(super) app_in: PathBuf,
    pub(super) app_out: PathBuf,
    pub(super) mihomo_log_path: PathBuf,
    pub(super) tun2socks_log_path: PathBuf,
    pub(super) requires_netd: bool,
    pub(super) requires_tun: bool,
    pub(super) controller_port: Option<u16>,
    pub(super) netid: u32,
    pub(super) tun_addr: String,
    pub(super) cidr: String,
}

pub(super) fn netd_profile_for_plan(plan: &ProfilePlan) -> VpnNetdProfile {
    VPN_CORE.netd_profile(VpnNetdProfileSpec {
        profile: plan.name.clone(),
        netid: plan.netid,
        tun: plan.setting.tun.clone(),
        cidr: plan.cidr.clone(),
        gateway: None,
        dns: vec!["8.8.8.8".to_string()],
        app_list_path: plan.app_in.clone(),
        app_out_path: plan.app_out.clone(),
        endpoint_escape_ips: Vec::new(),
    })
}

