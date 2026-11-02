use std::path::PathBuf;

use crate::network::vpn::netd::VpnNetdProfile;

use super::VpnProfileCore;

#[derive(Debug, Clone)]
pub struct VpnNetdProfileSpec {
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

impl VpnProfileCore {
    pub fn netd_profile(self, spec: VpnNetdProfileSpec) -> VpnNetdProfile {
        VpnNetdProfile {
            owner_program: self.owner_program().to_string(),
            profile: spec.profile,
            netid: spec.netid,
            tun: spec.tun,
            cidr: spec.cidr,
            gateway: spec.gateway,
            dns: spec.dns,
            app_list_path: spec.app_list_path,
            app_out_path: spec.app_out_path,
            endpoint_escape_ips: spec.endpoint_escape_ips,
        }
    }
}
