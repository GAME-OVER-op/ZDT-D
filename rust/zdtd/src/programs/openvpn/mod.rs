mod model;
mod profile;
mod runtime;
mod config;
mod process;
mod network;

pub use model::{ProfileSetting};
pub use profile::{root_path, active_path, profiles_root, profile_root, is_valid_profile_name, ensure_valid_profile_name, ensure_profile_layout, ensure_root_layout, read_active, write_active, read_setting, write_setting, validate_setting, normalize_setting_value, validate_enabled_tun_uniqueness_with_override};
pub use runtime::{validate_start_plan, has_enabled_profiles, has_profiles_requiring_netd, is_running, enabled_tun_claims, start_if_enabled, start_profiles_for_netd, start_profile_for_hotspot_vpn};
pub(crate) use runtime::{validate_start_plan_with_settings, start_profiles_for_netd_with_settings};
pub use process::{main_pids_exact};
pub use crate::common::profile::{ActiveProfiles, ProfileState};
