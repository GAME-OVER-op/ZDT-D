use std::path::Path;

use anyhow::Result;
use log::{info, warn};

use crate::iptables::{
    iptables_port::{self, DpiTunnelOptions, ProtoChoice},
    iptables_tproxy,
};

use super::spawn::t2s_tproxy_enabled;


pub fn apply_t2s_routing(
    uid_file: &Path,
    dest_port: u16,
    proto_choice: ProtoChoice,
    ifaces_raw: Option<&str>,
    opt: DpiTunnelOptions,
) -> Result<()> {
    // Legacy entrypoint: the TPROXY path always covers TCP+UDP regardless of the
    // requested `proto_choice` (kept for back-compat and logging). All existing
    // callers rely on this behaviour. myproxy uses `apply_t2s_routing_ext` to
    // honor its per-profile TCP / TCP+UDP selection.
    apply_t2s_routing_ext(
        uid_file,
        dest_port,
        ProtoChoice::TcpUdp,
        proto_choice,
        ifaces_raw,
        opt,
    )
}

/// Like `apply_t2s_routing`, but the caller chooses which protocols the TPROXY
/// path covers via `tproxy_proto_choice`. The DNAT fallback stays TCP-only, so
/// when TPROXY is unavailable the selection is ignored and TCP DNAT is used.
/// `requested_proto` is used for logging only.
pub fn apply_t2s_routing_ext(
    uid_file: &Path,
    dest_port: u16,
    tproxy_proto_choice: ProtoChoice,
    requested_proto: ProtoChoice,
    ifaces_raw: Option<&str>,
    opt: DpiTunnelOptions,
) -> Result<()> {
    let dnat_fallback_proto_choice = ProtoChoice::Tcp;

    if t2s_tproxy_enabled() {
        match iptables_tproxy::apply(uid_file, dest_port, tproxy_proto_choice, ifaces_raw, &opt) {
            Ok(()) => {
                info!(
                    "t2s routing: TPROXY applied uid_file={} dest_port={} proto={:?} requested_proto={:?}",
                    uid_file.display(),
                    dest_port,
                    tproxy_proto_choice,
                    requested_proto,
                );
                return Ok(());
            }
            Err(iptables_tproxy::TproxyApplyError::Unsupported(reason)) => {
                warn!(
                    "t2s routing: TPROXY unsupported, falling back to TCP DNAT: {reason}"
                );
            }
            Err(iptables_tproxy::TproxyApplyError::Failed(err)) => {
                warn!(
                    "t2s routing: TPROXY failed, falling back to TCP DNAT: {err:#}"
                );
            }
        }
    }

    iptables_port::apply(
        uid_file,
        dest_port,
        dnat_fallback_proto_choice,
        ifaces_raw,
        opt,
    )
}
