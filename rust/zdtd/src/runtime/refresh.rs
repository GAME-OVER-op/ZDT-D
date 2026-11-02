use anyhow::{bail, Result};
use crate::programs::registry::{self as program, AppListLayout, ProgramId, RuntimeRefreshKind};
use serde::{Deserialize, Serialize};
use std::{fs, path::{Path, PathBuf}};

const ROUTING_CACHE: &str = "/data/adb/modules/ZDT-D/working_folder/runtime_refresh/routing.json";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RefreshOutcome {
    Applied,
    NoActiveRuntime,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RangeSnapshot {
    pub start: u16,
    pub end: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
pub struct FilterSnapshot {
    #[serde(default)]
    pub tcp: Vec<RangeSnapshot>,
    #[serde(default)]
    pub udp: Vec<RangeSnapshot>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "kind")]
pub enum RoutingSnapshot {
    NfqV1 {
        uid_file: String,
        mode: String,
        queue: u16,
        iface: Option<String>,
        filter: Option<FilterSnapshot>,
    },
    NfqV2 {
        uid_file: String,
        port: u16,
        filter: Option<FilterSnapshot>,
    },
    Nat {
        uid_file: String,
        dest_port: u16,
        proto_choice: String,
        ifaces_raw: Option<String>,
        port_preference: u8,
        dpi_ports: String,
    },
    Tproxy {
        uid_file: String,
        dest_port: u16,
        proto_choice: String,
        ifaces_raw: Option<String>,
        port_preference: u8,
        dpi_ports: String,
        mark: u32,
        table: u32,
    },
}

impl RoutingSnapshot {
    fn uid_file(&self) -> &str {
        match self {
            RoutingSnapshot::NfqV1 { uid_file, .. } => uid_file,
            RoutingSnapshot::NfqV2 { uid_file, .. } => uid_file,
            RoutingSnapshot::Nat { uid_file, .. } => uid_file,
            RoutingSnapshot::Tproxy { uid_file, .. } => uid_file,
        }
    }

    fn same_runtime_slot(&self, other: &RoutingSnapshot) -> bool {
        self == other
    }

}

fn filter_to_snapshot(filter: Option<&crate::iptables::port_filter::ProtoPortFilter>) -> Option<FilterSnapshot> {
    filter
        .map(|f| FilterSnapshot {
            tcp: f.tcp.iter().map(|r| RangeSnapshot { start: r.start, end: r.end }).collect(),
            udp: f.udp.iter().map(|r| RangeSnapshot { start: r.start, end: r.end }).collect(),
        })
        .filter(|f| !f.tcp.is_empty() || !f.udp.is_empty())
}

fn filter_from_snapshot(filter: Option<FilterSnapshot>) -> Option<crate::iptables::port_filter::ProtoPortFilter> {
    filter
        .map(|f| crate::iptables::port_filter::ProtoPortFilter {
            tcp: f.tcp.into_iter().map(|r| crate::iptables::port_filter::PortRange { start: r.start, end: r.end }).collect(),
            udp: f.udp.into_iter().map(|r| crate::iptables::port_filter::PortRange { start: r.start, end: r.end }).collect(),
        })
        .filter(|f| !f.is_empty())
}

fn read_routing_cache() -> Vec<RoutingSnapshot> {
    let path = Path::new(ROUTING_CACHE);
    if !path.is_file() {
        return Vec::new();
    }
    fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str::<Vec<RoutingSnapshot>>(&s).ok())
        .unwrap_or_default()
}


pub fn clear_routing_cache() {
    let path = Path::new(ROUTING_CACHE);
    if let Err(e) = fs::remove_file(path) {
        if e.kind() != std::io::ErrorKind::NotFound {
            log::warn!("runtime_refresh: failed to clear routing cache: {e:#}");
        }
    }
}

fn write_routing_cache(items: &[RoutingSnapshot]) -> Result<()> {
    let path = Path::new(ROUTING_CACHE);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, serde_json::to_string_pretty(items)?)?;
    Ok(())
}

fn register_snapshot(snapshot: RoutingSnapshot) {
    let mut items = read_routing_cache();
    items.retain(|item| !item.same_runtime_slot(&snapshot));
    items.push(snapshot);
    if let Err(e) = write_routing_cache(&items) {
        log::warn!("runtime_refresh: failed to write routing cache: {e:#}");
    }
}

pub fn register_nfqueue_v1(
    uid_file: &Path,
    mode: &str,
    queue: u16,
    iface: Option<&str>,
    filter: Option<&crate::iptables::port_filter::ProtoPortFilter>,
) {
    register_snapshot(RoutingSnapshot::NfqV1 {
        uid_file: uid_file.display().to_string(),
        mode: mode.to_string(),
        queue,
        iface: iface.map(str::to_string).filter(|s| !s.is_empty()),
        filter: filter_to_snapshot(filter),
    });
}

pub fn register_nfqueue_v2(
    uid_file: &Path,
    port: u16,
    filter: Option<&crate::iptables::port_filter::ProtoPortFilter>,
) {
    register_snapshot(RoutingSnapshot::NfqV2 {
        uid_file: uid_file.display().to_string(),
        port,
        filter: filter_to_snapshot(filter),
    });
}

pub fn register_nat(
    uid_file: &Path,
    dest_port: u16,
    proto_choice: crate::iptables::iptables_port::ProtoChoice,
    ifaces_raw: Option<&str>,
    opt: &crate::iptables::iptables_port::DpiTunnelOptions,
) {
    let proto_choice = match proto_choice {
        crate::iptables::iptables_port::ProtoChoice::Tcp => "tcp",
        crate::iptables::iptables_port::ProtoChoice::Udp => "udp",
        crate::iptables::iptables_port::ProtoChoice::TcpUdp => "tcp_udp",
    };
    register_snapshot(RoutingSnapshot::Nat {
        uid_file: uid_file.display().to_string(),
        dest_port,
        proto_choice: proto_choice.to_string(),
        ifaces_raw: ifaces_raw.map(str::to_string).filter(|s| !s.trim().is_empty()),
        port_preference: opt.port_preference,
        dpi_ports: opt.dpi_ports.clone(),
    });
}


pub fn register_tproxy(
    uid_file: &Path,
    dest_port: u16,
    proto_choice: crate::iptables::iptables_port::ProtoChoice,
    ifaces_raw: Option<&str>,
    opt: &crate::iptables::iptables_port::DpiTunnelOptions,
    mark: u32,
    table: u32,
) {
    let proto_choice = match proto_choice {
        crate::iptables::iptables_port::ProtoChoice::Tcp => "tcp",
        crate::iptables::iptables_port::ProtoChoice::Udp => "udp",
        crate::iptables::iptables_port::ProtoChoice::TcpUdp => "tcp_udp",
    };
    register_snapshot(RoutingSnapshot::Tproxy {
        uid_file: uid_file.display().to_string(),
        dest_port,
        proto_choice: proto_choice.to_string(),
        ifaces_raw: ifaces_raw.map(str::to_string).filter(|s| !s.trim().is_empty()),
        port_preference: opt.port_preference,
        dpi_ports: opt.dpi_ports.clone(),
        mark,
        table,
    });
}

pub fn refresh_routing_by_uid_file(uid_file: &Path) -> Result<RefreshOutcome> {
    let key = uid_file.display().to_string();
    let snapshots = read_routing_cache()
        .into_iter()
        .filter(|item| item.uid_file() == key)
        .collect::<Vec<_>>();
    if snapshots.is_empty() {
        log::info!("runtime_refresh: no active routing cache for {}", uid_file.display());
        return Ok(RefreshOutcome::NoActiveRuntime);
    }

    for snapshot in snapshots {
        match snapshot {
            RoutingSnapshot::NfqV1 { uid_file, mode, queue, iface, filter } => {
                let filter = filter_from_snapshot(filter);
                crate::iptables::iptables_v1::apply(&mode, queue, iface.as_deref(), Some(Path::new(&uid_file)), filter.as_ref())?;
            }
            RoutingSnapshot::NfqV2 { uid_file, port, filter } => {
                let filter = filter_from_snapshot(filter);
                crate::iptables::iptables_v2::apply(port, Some(Path::new(&uid_file)), filter.as_ref())?;
            }
            RoutingSnapshot::Nat { uid_file, dest_port, proto_choice, ifaces_raw, port_preference, dpi_ports } => {
                let proto_choice = crate::iptables::iptables_port::ProtoChoice::from_str(&proto_choice);
                let opt = crate::iptables::iptables_port::DpiTunnelOptions { port_preference, dpi_ports };
                crate::iptables::iptables_port::apply(Path::new(&uid_file), dest_port, proto_choice, ifaces_raw.as_deref(), opt)?;
            }
            RoutingSnapshot::Tproxy { uid_file, dest_port, proto_choice, ifaces_raw, port_preference, dpi_ports, mark: _, table: _ } => {
                let proto_choice = crate::iptables::iptables_port::ProtoChoice::from_str(&proto_choice);
                let opt = crate::iptables::iptables_port::DpiTunnelOptions { port_preference, dpi_ports };
                // Preserve the exact TPROXY protocol coverage captured in the
                // snapshot (TCP+UDP for most tools, per-profile TCP/TCP+UDP for
                // myproxy) when replaying on network refresh or live app changes.
                crate::network::t2s::apply_t2s_routing_ext(
                    Path::new(&uid_file),
                    dest_port,
                    proto_choice,
                    proto_choice,
                    ifaces_raw.as_deref(),
                    opt,
                )?;
            }
        }
    }
    Ok(RefreshOutcome::Applied)
}

fn uid_output_from_input(input: &Path) -> PathBuf {
    let Some(file_name) = input.file_name() else {
        return input.to_path_buf();
    };
    if let Some(parent) = input.parent().and_then(|p| p.parent()) {
        parent.join("out").join(file_name)
    } else {
        input.to_path_buf()
    }
}

fn rebuild_uid_file(input: &Path, output: &Path) -> Result<()> {
    let tracker = crate::platform::android::pkg_uid::Sha256Tracker::new(crate::settings::SHARED_SHA_FLAG_FILE);
    let _ = crate::platform::android::pkg_uid::unified_processing(
        crate::platform::android::pkg_uid::Mode::Default,
        &tracker,
        output,
        input,
    )?;
    Ok(())
}

/// UID-only runtime refresh entrypoint.
///
/// This intentionally refreshes only app-list derived UID routing while the
/// service is running. Profile settings changed on disk (ports, interfaces,
/// TUN/netId/CIDR/DNS, hotspot settings, program configs, etc.) are not applied
/// here and become active only after a normal stop/start cycle.
pub fn refresh_apps(program_name: &str, profile: Option<&str>, slot: &str) -> Result<RefreshOutcome> {
    let id = ProgramId::parse(program_name)
        .ok_or_else(|| anyhow::anyhow!("runtime_refresh: unsupported program {program_name}"))?;
    let descriptor = id.descriptor();

    match descriptor.runtime_refresh {
        RuntimeRefreshKind::VpnNetd => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program_name}"))?;
            refresh_vpn_netd_users(id, profile)?;
            Ok(RefreshOutcome::Applied)
        }
        RuntimeRefreshKind::Special => match id {
            ProgramId::BlockedQuic => {
                let _ = crate::features::blockedquic::rebuild_out_program()?;
                let _ = crate::features::blockedquic::refresh_runtime(true)?;
                Ok(RefreshOutcome::Applied)
            }
            ProgramId::ProxyInfo => {
                let _ = crate::features::proxyinfo::refresh_runtime(true)?;
                Ok(RefreshOutcome::Applied)
            }
            _ => bail!("runtime_refresh: unsupported special program {program_name}"),
        },
        RuntimeRefreshKind::UidRouting => {
            let input = app_input_path(id, profile, slot, program_name)?;
            let output = uid_output_from_input(&input);
            rebuild_uid_file(&input, &output)?;
            refresh_routing_by_uid_file(&output)
        }
        RuntimeRefreshKind::UidRoutingAndVpnNetd => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program_name}"))?;
            let input = app_input_path(id, Some(profile), slot, program_name)?;
            let output = uid_output_from_input(&input);
            rebuild_uid_file(&input, &output)?;
            let routing = refresh_routing_by_uid_file(&output)?;
            if slot == "common" || slot == "user" {
                let _ = crate::network::vpn::netd::refresh_profile_users(
                    id.canonical(),
                    profile,
                    &input,
                    &output,
                )?;
            }
            Ok(routing)
        }
        RuntimeRefreshKind::None => bail!("runtime_refresh: unsupported program {program_name}"),
    }
}

fn app_input_path(
    id: ProgramId,
    profile: Option<&str>,
    slot: &str,
    original_program: &str,
) -> Result<PathBuf> {
    let file = match slot {
        "mobile" => "mobile_program",
        "wifi" => "wifi_program",
        _ => "user_program",
    };
    let root = program::working_program_root(id);
    let p = match id.descriptor().app_list_layout {
        AppListLayout::FlatProfile => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {original_program}"))?;
            root.join(profile).join("app/uid").join(file)
        }
        AppListLayout::ProfileDir => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {original_program}"))?;
            root.join("profile").join(profile).join("app/uid").join(file)
        }
        AppListLayout::SingleUser => root.join("app/uid/user_program"),
        AppListLayout::SingleSlots => root.join("app/uid").join(file),
        AppListLayout::None => bail!("runtime_refresh: unsupported app-list program {original_program}"),
    };
    Ok(p)
}

fn refresh_vpn_netd_users(id: ProgramId, profile: &str) -> Result<()> {
    let root = vpn_profile_root(id, profile)?;
    let app_in = root.join("app/uid/user_program");
    let app_out = root.join("app/out/user_program");
    let _ = crate::network::vpn::netd::refresh_profile_users(id.canonical(), profile, &app_in, &app_out)?;
    Ok(())
}

fn vpn_profile_root(id: ProgramId, profile: &str) -> Result<PathBuf> {
    if !id.descriptor().capabilities.vpn_netd {
        bail!("not a vpn/netd program: {}", id.canonical());
    }
    program::profile_root(id, profile)
        .ok_or_else(|| anyhow::anyhow!("not a profile program: {}", id.canonical()))
}
