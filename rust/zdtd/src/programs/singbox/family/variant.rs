use crate::common::network::{NETID_SINGBOX, NETID_SINGBOX_LX};
use crate::programs::registry::ProgramId;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SingboxVariant {
    Standard,
    Lx,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct VariantSpec {
    pub variant: SingboxVariant,
    pub program_id: ProgramId,
    pub label: &'static str,
    pub binary: &'static str,
    pub root: &'static str,
    pub profiles_root: &'static str,
    pub active_json: &'static str,
    pub netid_base: u32,
    pub netid_max: u32,
    pub network_base: u32,
    pub default_t2s_port: u16,
    pub default_t2s_web_port: u16,
    pub default_tun: &'static str,
    pub optional_component: bool,
}

impl VariantSpec {
    pub const fn standard() -> Self {
        Self {
            variant: SingboxVariant::Standard,
            program_id: ProgramId::SingBox,
            label: "sing-box",
            binary: "/data/adb/modules/ZDT-D/bin/sing-box",
            root: "/data/adb/modules/ZDT-D/working_folder/singbox",
            profiles_root: "/data/adb/modules/ZDT-D/working_folder/singbox/profile",
            active_json: "/data/adb/modules/ZDT-D/working_folder/singbox/active.json",
            netid_base: NETID_SINGBOX.0,
            netid_max: NETID_SINGBOX.1,
            network_base: 0xAC1F_F000,
            default_t2s_port: 12345,
            default_t2s_web_port: 8001,
            default_tun: "sbtun0",
            optional_component: false,
        }
    }

    pub const fn lx() -> Self {
        Self {
            variant: SingboxVariant::Lx,
            program_id: ProgramId::SingBoxLx,
            label: "sing-box-lx",
            binary: "/data/adb/modules/ZDT-D/bin/sing-box-lx",
            root: "/data/adb/modules/ZDT-D/working_folder/singbox-lx",
            profiles_root: "/data/adb/modules/ZDT-D/working_folder/singbox-lx/profile",
            active_json: "/data/adb/modules/ZDT-D/working_folder/singbox-lx/active.json",
            netid_base: NETID_SINGBOX_LX.0,
            netid_max: NETID_SINGBOX_LX.1,
            network_base: 0xAC1E_F000,
            default_t2s_port: 12355,
            default_t2s_web_port: 8011,
            default_tun: "sblxtun0",
            optional_component: true,
        }
    }

    pub const fn is_lx(self) -> bool {
        matches!(self.variant, SingboxVariant::Lx)
    }

    pub fn hotspot_supported(self) -> bool {
        self.program_id.descriptor().capabilities.hotspot_proxy
    }
}
