use std::path::{Path, PathBuf};

use crate::common::network::{
    NETID_AMNEZIAWG, NETID_HYSTERIA2, NETID_MIERU, NETID_MIHOMO, NETID_MYVPN,
    NETID_OPENVPN, NETID_SINGBOX, NETID_SINGBOX_LX, NETID_TUN2SOCKS,
};

use super::id::ProgramId;

pub const WORKING_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StateKind {
    ProfilesActive,
    EnabledActive,
    EnabledJson,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileLayout {
    None,
    Flat,
    ProfileDir,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AppListLayout {
    None,
    FlatProfile,
    ProfileDir,
    SingleUser,
    SingleSlots,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeRefreshKind {
    None,
    UidRouting,
    VpnNetd,
    UidRoutingAndVpnNetd,
    Special,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProgramCapabilities {
    pub profile_based: bool,
    pub vpn_netd: bool,
    pub t2s: bool,
    pub hotspot_proxy: bool,
    pub hotspot_vpn: bool,
}

impl ProgramCapabilities {
    pub const fn new(
        profile_based: bool,
        vpn_netd: bool,
        t2s: bool,
        hotspot_proxy: bool,
        hotspot_vpn: bool,
    ) -> Self {
        Self { profile_based, vpn_netd, t2s, hotspot_proxy, hotspot_vpn }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct ProgramDescriptor {
    pub id: ProgramId,
    /// Internal/storage/netd owner id. Never infer this from display or binary names.
    pub canonical_id: &'static str,
    /// Public API id. For most programs it equals canonical_id; sing-box is the important exception.
    pub api_id: &'static str,
    pub display_name: &'static str,
    /// Directory name below WORKING_ROOT.
    pub working_dir: &'static str,
    pub state_file: &'static str,
    pub state_kind: StateKind,
    pub profile_layout: ProfileLayout,
    /// Preserve the exact minimal-layout behavior that existed before the registry.
    pub eager_profile_dir: bool,
    pub app_list_layout: AppListLayout,
    pub runtime_refresh: RuntimeRefreshKind,
    pub capabilities: ProgramCapabilities,
    pub netid_range: Option<(u32, u32)>,
    pub adjustable_port_base: Option<u16>,
    /// Exact simple process name where one stable name exists. Complex engines still own PID matching.
    pub process_name: Option<&'static str>,
    /// Historical/external spellings accepted by normalizers. These are explicit, never guessed.
    pub aliases: &'static [&'static str],
}

const PROFILE: ProgramCapabilities = ProgramCapabilities::new(true, false, false, false, false);
const SINGLE: ProgramCapabilities = ProgramCapabilities::new(false, false, false, false, false);

pub static PROGRAMS: &[ProgramDescriptor] = &[
    ProgramDescriptor {
        id: ProgramId::Nfqws, canonical_id: "nfqws", api_id: "nfqws", display_name: "zapret",
        working_dir: "nfqws", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::Flat, eager_profile_dir: false, app_list_layout: AppListLayout::FlatProfile,
        runtime_refresh: RuntimeRefreshKind::UidRouting, capabilities: PROFILE, netid_range: None,
        adjustable_port_base: Some(200), process_name: Some("nfqws"), aliases: &[],
    },
    ProgramDescriptor {
        id: ProgramId::Nfqws2, canonical_id: "nfqws2", api_id: "nfqws2", display_name: "zapret2",
        working_dir: "nfqws2", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::Flat, eager_profile_dir: false, app_list_layout: AppListLayout::FlatProfile,
        runtime_refresh: RuntimeRefreshKind::UidRouting, capabilities: PROFILE, netid_range: None,
        adjustable_port_base: Some(200), process_name: Some("nfqws2"), aliases: &[],
    },
    ProgramDescriptor {
        id: ProgramId::ByeDpi, canonical_id: "byedpi", api_id: "byedpi", display_name: "byedpi",
        working_dir: "byedpi", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::Flat, eager_profile_dir: false, app_list_layout: AppListLayout::FlatProfile,
        runtime_refresh: RuntimeRefreshKind::UidRouting, capabilities: PROFILE, netid_range: None,
        adjustable_port_base: Some(1130), process_name: Some("byedpi"), aliases: &["bye-dpi", "bye_dpi"],
    },
    ProgramDescriptor {
        id: ProgramId::DpiTunnel, canonical_id: "dpitunnel", api_id: "dpitunnel", display_name: "dpitunnel",
        working_dir: "dpitunnel", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::Flat, eager_profile_dir: false, app_list_layout: AppListLayout::FlatProfile,
        runtime_refresh: RuntimeRefreshKind::UidRouting, capabilities: PROFILE, netid_range: None,
        adjustable_port_base: Some(1840), process_name: None, aliases: &["dpi-tunnel", "dpi_tunnel"],
    },
    ProgramDescriptor {
        id: ProgramId::DnsCrypt, canonical_id: "dnscrypt", api_id: "dnscrypt", display_name: "dnscrypt",
        working_dir: "dnscrypt", state_file: "active.json", state_kind: StateKind::EnabledActive,
        profile_layout: ProfileLayout::None, eager_profile_dir: false, app_list_layout: AppListLayout::None,
        runtime_refresh: RuntimeRefreshKind::None, capabilities: SINGLE, netid_range: None,
        adjustable_port_base: None, process_name: Some("dnscrypt"), aliases: &["dnscrypt-proxy"],
    },
    ProgramDescriptor {
        id: ProgramId::OperaProxy, canonical_id: "operaproxy", api_id: "operaproxy", display_name: "opera-proxy",
        working_dir: "operaproxy", state_file: "active.json", state_kind: StateKind::EnabledActive,
        profile_layout: ProfileLayout::None, eager_profile_dir: false, app_list_layout: AppListLayout::SingleSlots,
        runtime_refresh: RuntimeRefreshKind::UidRouting,
        capabilities: ProgramCapabilities::new(false, false, true, true, false), netid_range: None,
        adjustable_port_base: None, process_name: Some("opera-proxy"), aliases: &["opera-proxy", "opera_proxy"],
    },
    ProgramDescriptor {
        id: ProgramId::TgwsProxy, canonical_id: "tgwsproxy", api_id: "tgwsproxy", display_name: "Telegram WS Proxy",
        working_dir: "tgwsproxy", state_file: "active.json", state_kind: StateKind::EnabledActive,
        profile_layout: ProfileLayout::None, eager_profile_dir: false, app_list_layout: AppListLayout::None,
        runtime_refresh: RuntimeRefreshKind::None, capabilities: SINGLE, netid_range: None,
        adjustable_port_base: None, process_name: Some("tg-ws-proxy"), aliases: &["tg-ws-proxy", "tg_ws_proxy"],
    },
    ProgramDescriptor {
        id: ProgramId::SingBox, canonical_id: "singbox", api_id: "sing-box", display_name: "sing-box",
        working_dir: "singbox", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: false, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::UidRoutingAndVpnNetd,
        capabilities: ProgramCapabilities::new(true, true, true, true, false), netid_range: Some(NETID_SINGBOX),
        adjustable_port_base: None, process_name: Some("sing-box"), aliases: &["sing_box"],
    },
    ProgramDescriptor {
        id: ProgramId::SingBoxLx, canonical_id: "singbox-lx", api_id: "sing-box-lx", display_name: "sing-box-lx",
        working_dir: "singbox-lx", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: false, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::UidRoutingAndVpnNetd,
        capabilities: ProgramCapabilities::new(true, true, true, false, false), netid_range: Some(NETID_SINGBOX_LX),
        adjustable_port_base: None, process_name: Some("sing-box-lx"), aliases: &["singbox_lx", "sing_box_lx"],
    },
    ProgramDescriptor {
        id: ProgramId::Hysteria2, canonical_id: "hysteria2", api_id: "hysteria2", display_name: "hysteria2",
        working_dir: "hysteria2", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: true, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::UidRoutingAndVpnNetd,
        capabilities: ProgramCapabilities::new(true, true, true, false, false), netid_range: Some(NETID_HYSTERIA2),
        adjustable_port_base: None, process_name: Some("hysteria2"), aliases: &["hysteria-2", "hysteria_2"],
    },
    ProgramDescriptor {
        id: ProgramId::WireProxy, canonical_id: "wireproxy", api_id: "wireproxy", display_name: "wireproxy",
        working_dir: "wireproxy", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: false, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::UidRouting,
        capabilities: ProgramCapabilities::new(true, false, true, true, false), netid_range: None,
        adjustable_port_base: None, process_name: Some("wireproxy"), aliases: &["wire-proxy", "wire_proxy"],
    },
    ProgramDescriptor {
        id: ProgramId::Tor, canonical_id: "tor", api_id: "tor", display_name: "tor",
        working_dir: "tor", state_file: "enabled.json", state_kind: StateKind::EnabledJson,
        profile_layout: ProfileLayout::None, eager_profile_dir: false, app_list_layout: AppListLayout::SingleUser,
        runtime_refresh: RuntimeRefreshKind::UidRouting,
        capabilities: ProgramCapabilities::new(false, false, true, false, false), netid_range: None,
        adjustable_port_base: None, process_name: None, aliases: &[],
    },
    ProgramDescriptor {
        id: ProgramId::MyProxy, canonical_id: "myproxy", api_id: "myproxy", display_name: "myproxy",
        working_dir: "myproxy", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: false, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::UidRouting,
        capabilities: ProgramCapabilities::new(true, false, true, false, false), netid_range: None,
        adjustable_port_base: None, process_name: None, aliases: &["my-proxy", "my_proxy"],
    },
    ProgramDescriptor {
        id: ProgramId::MyProgram, canonical_id: "myprogram", api_id: "myprogram", display_name: "myprogram",
        working_dir: "myprogram", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: false, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::UidRouting,
        capabilities: ProgramCapabilities::new(true, false, true, false, false), netid_range: None,
        adjustable_port_base: None, process_name: None, aliases: &["my-program", "my_program"],
    },
    ProgramDescriptor {
        id: ProgramId::OpenVpn, canonical_id: "openvpn", api_id: "openvpn", display_name: "openvpn",
        working_dir: "openvpn", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: true, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::VpnNetd,
        capabilities: ProgramCapabilities::new(true, true, false, false, true), netid_range: Some(NETID_OPENVPN),
        adjustable_port_base: None, process_name: Some("openvpn"), aliases: &["open-vpn", "open_vpn"],
    },
    ProgramDescriptor {
        id: ProgramId::AmneziaWg, canonical_id: "amneziawg", api_id: "amneziawg", display_name: "amneziawg",
        working_dir: "amneziawg", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: true, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::VpnNetd,
        capabilities: ProgramCapabilities::new(true, true, false, false, true), netid_range: Some(NETID_AMNEZIAWG),
        adjustable_port_base: None, process_name: None, aliases: &["amnezia-wg", "amnezia_wg", "awg"],
    },
    ProgramDescriptor {
        id: ProgramId::Tun2Socks, canonical_id: "tun2socks", api_id: "tun2socks", display_name: "tun2socks",
        working_dir: "tun2socks", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: true, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::VpnNetd,
        capabilities: ProgramCapabilities::new(true, true, false, false, false), netid_range: Some(NETID_TUN2SOCKS),
        adjustable_port_base: None, process_name: None, aliases: &["tun2-socks", "tun2_socks"],
    },
    ProgramDescriptor {
        id: ProgramId::MyVpn, canonical_id: "myvpn", api_id: "myvpn", display_name: "myvpn",
        working_dir: "myvpn", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: true, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::VpnNetd,
        capabilities: ProgramCapabilities::new(true, true, false, false, false), netid_range: Some(NETID_MYVPN),
        adjustable_port_base: None, process_name: None, aliases: &["my-vpn", "my_vpn"],
    },
    ProgramDescriptor {
        id: ProgramId::Mihomo, canonical_id: "mihomo", api_id: "mihomo", display_name: "mihomo",
        working_dir: "mihomo", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: true, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::VpnNetd,
        capabilities: ProgramCapabilities::new(true, true, false, false, true), netid_range: Some(NETID_MIHOMO),
        adjustable_port_base: None, process_name: Some("mihomo"), aliases: &[],
    },
    ProgramDescriptor {
        id: ProgramId::Mieru, canonical_id: "mieru", api_id: "mieru", display_name: "mieru",
        working_dir: "mieru", state_file: "active.json", state_kind: StateKind::ProfilesActive,
        profile_layout: ProfileLayout::ProfileDir, eager_profile_dir: true, app_list_layout: AppListLayout::ProfileDir,
        runtime_refresh: RuntimeRefreshKind::VpnNetd,
        capabilities: ProgramCapabilities::new(true, true, false, false, true), netid_range: Some(NETID_MIERU),
        adjustable_port_base: None, process_name: Some("mieru"), aliases: &[],
    },
    ProgramDescriptor {
        id: ProgramId::ProxyInfo, canonical_id: "proxyInfo", api_id: "proxyInfo", display_name: "proxyInfo",
        working_dir: "proxyInfo", state_file: "enabled.json", state_kind: StateKind::EnabledJson,
        profile_layout: ProfileLayout::None, eager_profile_dir: false, app_list_layout: AppListLayout::None,
        runtime_refresh: RuntimeRefreshKind::Special, capabilities: SINGLE, netid_range: None,
        adjustable_port_base: None, process_name: None, aliases: &["proxyinfo"],
    },
    ProgramDescriptor {
        id: ProgramId::BlockedQuic, canonical_id: "blockedquic", api_id: "blockedquic", display_name: "blockedquic",
        working_dir: "blockedquic", state_file: "enabled.json", state_kind: StateKind::EnabledJson,
        profile_layout: ProfileLayout::None, eager_profile_dir: false, app_list_layout: AppListLayout::None,
        runtime_refresh: RuntimeRefreshKind::Special, capabilities: SINGLE, netid_range: None,
        adjustable_port_base: None, process_name: None, aliases: &["blocked-quic", "blocked_quic"],
    },
];

pub fn descriptor(id: ProgramId) -> &'static ProgramDescriptor {
    PROGRAMS
        .iter()
        .find(|descriptor| descriptor.id == id)
        .expect("ProgramId is missing from PROGRAMS registry")
}

pub fn working_root() -> PathBuf {
    PathBuf::from(WORKING_ROOT)
}

pub fn working_program_root(id: ProgramId) -> PathBuf {
    Path::new(WORKING_ROOT).join(id.working_dir())
}

pub fn profile_root(id: ProgramId, profile: &str) -> Option<PathBuf> {
    let root = working_program_root(id);
    match id.descriptor().profile_layout {
        ProfileLayout::None => None,
        ProfileLayout::Flat => Some(root.join(profile)),
        ProfileLayout::ProfileDir => Some(root.join("profile").join(profile)),
    }
}

pub fn state_path(id: ProgramId) -> PathBuf {
    working_program_root(id).join(id.descriptor().state_file)
}

/// Resolves any known canonical/API/alias id through the registry, preserving
/// the legacy fallback for unknown working-folder names.
pub fn working_program_dir(raw: &str) -> PathBuf {
    ProgramId::parse(raw)
        .map(working_program_root)
        .unwrap_or_else(|| Path::new(WORKING_ROOT).join(raw))
}
