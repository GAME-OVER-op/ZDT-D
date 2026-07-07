use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};
use std::{fs, path::{Path, PathBuf}};

const ROUTING_CACHE: &str = "/data/adb/modules/ZDT-D/working_folder/runtime_refresh/routing.json";

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
        match (self, other) {
            (
                RoutingSnapshot::Nat { uid_file: a_uid, dest_port: a_dest, proto_choice: a_proto, ifaces_raw: a_ifaces, port_preference: a_pref, dpi_ports: a_ports },
                RoutingSnapshot::Tproxy { uid_file: b_uid, dest_port: b_dest, proto_choice: b_proto, ifaces_raw: b_ifaces, port_preference: b_pref, dpi_ports: b_ports, .. },
            )
            | (
                RoutingSnapshot::Tproxy { uid_file: a_uid, dest_port: a_dest, proto_choice: a_proto, ifaces_raw: a_ifaces, port_preference: a_pref, dpi_ports: a_ports, .. },
                RoutingSnapshot::Nat { uid_file: b_uid, dest_port: b_dest, proto_choice: b_proto, ifaces_raw: b_ifaces, port_preference: b_pref, dpi_ports: b_ports },
            )
            | (
                RoutingSnapshot::Nat { uid_file: a_uid, dest_port: a_dest, proto_choice: a_proto, ifaces_raw: a_ifaces, port_preference: a_pref, dpi_ports: a_ports },
                RoutingSnapshot::Nat { uid_file: b_uid, dest_port: b_dest, proto_choice: b_proto, ifaces_raw: b_ifaces, port_preference: b_pref, dpi_ports: b_ports },
            )
            | (
                RoutingSnapshot::Tproxy { uid_file: a_uid, dest_port: a_dest, proto_choice: a_proto, ifaces_raw: a_ifaces, port_preference: a_pref, dpi_ports: a_ports, .. },
                RoutingSnapshot::Tproxy { uid_file: b_uid, dest_port: b_dest, proto_choice: b_proto, ifaces_raw: b_ifaces, port_preference: b_pref, dpi_ports: b_ports, .. },
            ) => {
                a_uid == b_uid
                    && a_dest == b_dest
                    && a_proto == b_proto
                    && a_ifaces == b_ifaces
                    && a_pref == b_pref
                    && a_ports == b_ports
            }
            _ => self == other,
        }
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

pub fn refresh_routing_by_uid_file(uid_file: &Path) -> Result<bool> {
    let key = uid_file.display().to_string();
    let snapshots = read_routing_cache()
        .into_iter()
        .filter(|item| item.uid_file() == key)
        .collect::<Vec<_>>();
    if snapshots.is_empty() {
        log::info!("runtime_refresh: no active routing cache for {}", uid_file.display());
        return Ok(false);
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
                crate::iptables::iptables_port::apply(Path::new(&uid_file), dest_port, proto_choice, ifaces_raw.as_deref(), opt)?;
            }
        }
    }
    Ok(true)
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
    let tracker = crate::android::pkg_uid::Sha256Tracker::new(crate::settings::SHARED_SHA_FLAG_FILE);
    let _ = crate::android::pkg_uid::unified_processing(
        crate::android::pkg_uid::Mode::Default,
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
pub fn refresh_apps(program: &str, profile: Option<&str>, slot: &str) -> Result<()> {
    match program {
        "openvpn" | "amneziawg" | "tun2socks" | "myvpn" | "mihomo" | "mieru" => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program}"))?;
            refresh_vpn_netd_users(program, profile)
        }
        "blockedquic" => {
            let _ = crate::blockedquic::rebuild_out_program()?;
            let _ = crate::blockedquic::refresh_runtime(true)?;
            Ok(())
        }
        "proxyinfo" => {
            let _ = crate::proxyinfo::refresh_runtime(true)?;
            Ok(())
        }
        "nfqws" | "nfqws2" | "byedpi" | "dpitunnel" | "wireproxy" | "tor" | "operaproxy" | "myproxy" | "myprogram" => {
            let input = app_input_path(program, profile, slot)?;
            let output = uid_output_from_input(&input);
            rebuild_uid_file(&input, &output)?;
            let _ = refresh_routing_by_uid_file(&output)?;
            Ok(())
        }
        "sing-box" => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program}"))?;
            let input = app_input_path(program, Some(profile), slot)?;
            let output = uid_output_from_input(&input);
            rebuild_uid_file(&input, &output)?;
            let _ = refresh_routing_by_uid_file(&output)?;
            if slot == "common" || slot == "user" {
                let _ = crate::vpn_netd::refresh_profile_users("singbox", profile, &input, &output)?;
            }
            Ok(())
        }
        other => bail!("runtime_refresh: unsupported program {other}"),
    }
}

fn app_input_path(program: &str, profile: Option<&str>, slot: &str) -> Result<PathBuf> {
    let file = match slot {
        "mobile" => "mobile_program",
        "wifi" => "wifi_program",
        _ => "user_program",
    };
    let p = match program {
        "nfqws" | "nfqws2" | "byedpi" | "dpitunnel" => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program}"))?;
            PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/{program}/{profile}/app/uid/{file}"))
        }
        "sing-box" => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program}"))?;
            PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/singbox/profile/{profile}/app/uid/{file}"))
        }
        "wireproxy" => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program}"))?;
            PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/wireproxy/profile/{profile}/app/uid/{file}"))
        }
        "myproxy" => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program}"))?;
            PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/myproxy/profile/{profile}/app/uid/{file}"))
        }
        "myprogram" => {
            let profile = profile.ok_or_else(|| anyhow::anyhow!("profile is required for {program}"))?;
            PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/myprogram/profile/{profile}/app/uid/{file}"))
        }
        "tor" => PathBuf::from("/data/adb/modules/ZDT-D/working_folder/tor/app/uid/user_program"),
        "operaproxy" => PathBuf::from(format!("/data/adb/modules/ZDT-D/working_folder/operaproxy/app/uid/{file}")),
        _ => bail!("runtime_refresh: unsupported app-list program {program}"),
    };
    Ok(p)
}

fn refresh_vpn_netd_users(program: &str, profile: &str) -> Result<()> {
    let root = vpn_profile_root(program, profile)?;
    let app_in = root.join("app/uid/user_program");
    let app_out = root.join("app/out/user_program");
    let _ = crate::vpn_netd::refresh_profile_users(program, profile, &app_in, &app_out)?;
    Ok(())
}

fn vpn_profile_root(program: &str, profile: &str) -> Result<PathBuf> {
    let root = match program {
        "openvpn" => crate::programs::openvpn::profile_root(profile),
        "amneziawg" => crate::programs::amneziawg::profile_root(profile),
        "tun2socks" => crate::programs::tun2socks::profile_root(profile),
        "myvpn" => crate::programs::myvpn::profile_root(profile),
        "mihomo" => crate::programs::mihomo::profile_root(profile),
        "mieru" => crate::programs::mieru::profile_root(profile),
        _ => bail!("not a vpn/netd program: {program}"),
    };
    Ok(root)
}
