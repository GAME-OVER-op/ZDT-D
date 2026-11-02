use anyhow::{bail, Context, Result};
use crate::common::network::{is_ipv4, normalize_cidr_network, IP_TIMEOUT, first_host_for_cidr};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use std::{thread, time::{Duration, Instant}};

use crate::{common::process::shell::{self, Capture}};

use super::model::*;

pub(super) fn wait_tun_ready(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT {
            bail!("tun {tun} is not ready after {:?}", TUN_WAIT);
        }
        if inspect_tun(tun).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(400));
    }
}

pub(super) fn inspect_tun(tun: &str) -> Result<TunInfo> {
    let (code, out) = shell::run_timeout("ip", &["-o", "-4", "addr", "show", "dev", tun], Capture::Stdout, IP_TIMEOUT)
        .with_context(|| format!("ip addr show {tun}"))?;
    if code != 0 {
        bail!("ip addr show failed for {tun}");
    }

    let mut cidr = None::<String>;
    let mut gateway = None::<String>;
    for line in out.lines() {
        let tokens = line.split_whitespace().collect::<Vec<_>>();
        for i in 0..tokens.len() {
            if tokens[i] == "inet" {
                if let Some(ip_cidr) = tokens.get(i + 1) {
                    cidr = derive_cidr(ip_cidr);
                }
            }
            if tokens[i] == "peer" {
                if let Some(peer) = tokens.get(i + 1) {
                    let peer_ip = peer.split('/').next().unwrap_or(peer).to_string();
                    if is_ipv4(&peer_ip) {
                        gateway = Some(peer_ip);
                    }
                }
            }
        }
    }

    let cidr = cidr.ok_or_else(|| anyhow::anyhow!("tun {tun} has no IPv4 CIDR"))?;

    if gateway.is_none() {
        gateway = crate::common::network::route_gateway_for_tun(tun).ok();
    }
    if gateway.is_none() {
        gateway = first_host_for_cidr(&cidr);
    }

    Ok(TunInfo { cidr, gateway })
}

pub(super) fn derive_cidr(ip_cidr: &str) -> Option<String> {
    normalize_cidr_network(ip_cidr).ok()
}


