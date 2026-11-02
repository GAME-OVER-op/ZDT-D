use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize)]
pub struct VpnNetdProfile {
    pub owner_program: String,
    pub profile: String,
    pub netid: u32,
    pub tun: String,
    pub cidr: String,
    pub gateway: Option<String>,
    pub dns: Vec<String>,
    pub app_list_path: PathBuf,
    pub app_out_path: PathBuf,
    pub endpoint_escape_ips: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AppliedSnapshot {
    #[serde(default)]
    pub profiles: Vec<AppliedProfile>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppliedProfile {
    pub owner_program: String,
    pub profile: String,
    pub netid: u32,
    pub tun: String,
    #[serde(default)]
    pub uid_ranges: Vec<String>,
    #[serde(default)]
    pub endpoint_escape_ips: Vec<String>,
}
