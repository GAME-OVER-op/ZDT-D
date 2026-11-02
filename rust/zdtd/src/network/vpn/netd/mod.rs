mod apply;
mod cleanup;
mod model;
mod ndc;
mod reconcile;
mod state;
mod uid;

use std::time::Duration;

const WORKING_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder";
const VPN_NETD_DIR: &str = "/data/adb/modules/ZDT-D/working_folder/vpn_netd";
const NDC_TIMEOUT: Duration = Duration::from_secs(5);
const IP_TIMEOUT: Duration = Duration::from_secs(3);
const IPT_TIMEOUT: Duration = Duration::from_secs(5);
// Цепочка ip6tables, которой временно закрывается IPv6 у приложений, привязанных
// к VPN-профилям: netd умеет заводить в туннель только IPv4, и без этого запрета
// IPv6-трафик выбранных приложений уходит мимо туннеля.
const V6_CHAIN: &str = "ZDT_VPN_NETD_V6";

pub use apply::{start_from_registered_programs, start_profiles};
pub use cleanup::stop_applied;
pub use model::{AppliedProfile, AppliedSnapshot, VpnNetdProfile};
pub use state::{applied_snapshot_path, last_ndc_out_path, profiles_tmp_path, read_applied_snapshot};
pub use uid::refresh_profile_users;
