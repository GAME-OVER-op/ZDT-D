use std::path::Path;

use anyhow::{Context, Result};

use crate::iptables::{
    hotspot,
    iptables_port::{DpiTunnelOptions, ProtoChoice},
};

use super::{apply_t2s_routing_ext, spawn_t2s_proxy, T2sSpawnConfig};

#[derive(Debug, Clone, Copy)]
pub struct T2sProfileRouting<'a> {
    uid_file: &'a Path,
    tproxy_proto_choice: ProtoChoice,
    requested_proto: ProtoChoice,
    ifaces_raw: Option<&'a str>,
}

impl<'a> T2sProfileRouting<'a> {
    /// Preserve the legacy `apply_t2s_routing` behavior: TPROXY covers both
    /// TCP+UDP while the caller's requested protocol is retained for logging;
    /// the DNAT fallback remains TCP-only.
    pub fn legacy(uid_file: &'a Path, requested_proto: ProtoChoice) -> Self {
        Self {
            uid_file,
            tproxy_proto_choice: ProtoChoice::TcpUdp,
            requested_proto,
            ifaces_raw: None,
        }
    }

    /// Use the same protocol selection for TPROXY and the requested profile
    /// semantics. This matches callers that historically used
    /// `apply_t2s_routing_ext` with identical protocol arguments.
    pub fn exact(uid_file: &'a Path, proto_choice: ProtoChoice) -> Self {
        Self {
            uid_file,
            tproxy_proto_choice: proto_choice,
            requested_proto: proto_choice,
            ifaces_raw: None,
        }
    }

}

#[derive(Debug, Clone, Copy)]
pub struct T2sHotspotRouting<'a> {
    pub owner: &'a str,
    pub capture_all: bool,
}

/// Start one profile-scoped t2s runtime and register its routing.
///
/// The caller still owns all backend-specific decisions: upstream servers,
/// authentication, ports, whether t2s is needed, and log preparation. This
/// function only centralizes the repeated lifecycle that several backends
/// previously duplicated verbatim:
///
/// `spawn t2s -> optional hotspot redirect -> app routing`.
///
/// Error contexts intentionally match the profile backends that already used
/// `spawn t2s profile=<name>` and `iptables profile=<name>`.
pub fn launch_profile_t2s(
    spawn: T2sSpawnConfig<'_>,
    routing: T2sProfileRouting<'_>,
    hotspot_routing: Option<T2sHotspotRouting<'_>>,
) -> Result<()> {
    let profile = spawn.profile.to_string();
    let listen_port = spawn.listen_port;

    spawn_t2s_proxy(spawn)
        .with_context(|| format!("spawn t2s profile={profile}"))?;

    if let Some(hotspot_routing) = hotspot_routing {
        hotspot::ensure_prerouting_redirect(hotspot::HotspotRedirectConfig {
            owner: hotspot_routing.owner,
            listen_port,
            capture_all: hotspot_routing.capture_all,
            bypass_ports: hotspot::DEFAULT_HOTSPOT_BYPASS_PORTS,
        })?;
    }

    apply_t2s_routing_ext(
        routing.uid_file,
        listen_port,
        routing.tproxy_proto_choice,
        routing.requested_proto,
        routing.ifaces_raw,
        DpiTunnelOptions {
            port_preference: 1,
            ..DpiTunnelOptions::default()
        },
    )
    .with_context(|| format!("iptables profile={profile}"))?;

    Ok(())
}

pub fn profile_scope(program: &str, profile: &str) -> String {
    format!("profile/{program}/{profile}")
}

pub fn format_ports_csv<I>(ports: I) -> String
where
    I: IntoIterator<Item = u16>,
{
    ports
        .into_iter()
        .map(|port| port.to_string())
        .collect::<Vec<_>>()
        .join(",")
}
