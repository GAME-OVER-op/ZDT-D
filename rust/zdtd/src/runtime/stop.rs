use anyhow::Result;
use std::thread;

use crate::{
    common::process::{kill_pids_with_escalation, pidof_any_logged, pidof_logged},
    programs::registry::ProgramId,
    iptables::backup as iptables_backup,
};

fn kill_by_name(name: &str) -> Result<()> {
    kill_pids_with_escalation(&format!("pidof {name}"), &pidof_logged(name))
}

fn kill_exact_pids(label: &str, pids: &[i32]) -> Result<()> {
    kill_pids_with_escalation(label, pids)
}

fn kill_by_any(names: &[&str]) -> Result<()> {
    kill_pids_with_escalation(&format!("pidof {:?}", names), &pidof_any_logged(names))
}

fn stop_process_groups_parallel() -> Result<()> {
    let mut jobs = Vec::new();

    for id in [
        ProgramId::Nfqws,
        ProgramId::Nfqws2,
        ProgramId::DnsCrypt,
        ProgramId::TgwsProxy,
    ] {
        if let Some(name) = id.process_name() {
            jobs.push(thread::spawn(move || kill_by_name(name)));
        }
    }

    jobs.push(thread::spawn(|| kill_by_any(crate::programs::byedpi::PROCESS_NAMES)));
    jobs.push(thread::spawn(|| kill_by_any(crate::programs::operaproxy::PROCESS_NAMES)));

    // Shared helper processes are not standalone ProgramId entries.
    jobs.push(thread::spawn(|| kill_by_name("d2s")));
    jobs.push(thread::spawn(|| kill_by_any(crate::network::t2s::PROCESS_NAMES)));

    jobs.push(thread::spawn(|| kill_by_any(&["DPITunnel-cli", "dpitunnel-cli"])));

    for job in jobs {
        match job.join() {
            Ok(Ok(())) => {}
            Ok(Err(e)) => return Err(e),
            Err(_) => anyhow::bail!("stop worker thread panicked"),
        }
    }

    Ok(())
}

pub fn stop_services_and_restore_iptables() -> Result<()> {
    crate::programs::dnscrypt::request_stop();
    crate::programs::dnscrypt::clear_ipv6_resetprops();
    // Clean routing/iptables hooks before killing services. This prevents clients from
    // being routed to already-stopped t2s/VPN interfaces during shutdown.
    if let Err(e) = crate::network::vpn::tether::cleanup() {
        log::warn!("vpn_tether cleanup failed during stop: {e:#}");
    }
    crate::features::captive_portal::stop();
    if let Err(e) = crate::iptables::captive_portal::cleanup() {
        log::warn!("captive portal cleanup failed during stop: {e:#}");
    }
    if let Err(e) = crate::iptables::hotspot::cleanup() {
        log::warn!("hotspot redirect cleanup failed during stop: {e:#}");
    }
    if let Err(e) = crate::iptables::iptables_tproxy::cleanup_all() {
        log::warn!("TPROXY cleanup failed during stop: {e:#}");
    }
    if let Err(e) = crate::network::vpn::netd::stop_applied() {
        log::warn!("vpn_netd cleanup failed during stop: {e:#}");
    }
    // 1) stop background processes
    // Use `pidof` to avoid killing similarly-named processes.
    stop_process_groups_parallel()?;
    let _ = crate::programs::myprogram::stop_all();
    // Name-only matching is intentionally not used for these engines: another
    // root process may use the same binary name. Match only ZDT-D-owned cmdlines.
    kill_exact_pids("sing-box run -c <our config>", &crate::programs::singbox::main_pids_exact())?;
    kill_exact_pids("sing-box-lx run -c <our config>", &crate::programs::singbox::lx::main_pids_exact())?;
    kill_exact_pids("wireproxy -c <our config>", &crate::programs::wireproxy::main_pids_exact())?;
    // Stop VPN profile engines only when they were launched from this module path.
    kill_exact_pids("openvpn --config <profile>/client.ovpn", &crate::programs::openvpn::main_pids_exact())?;
    kill_exact_pids("amneziawg-go -f <profile tun>", &crate::programs::amneziawg::main_pids_exact())?;
    crate::programs::amneziawg::cleanup_all_interfaces();
    kill_exact_pids("mihomo -d <profile>/work -f config.runtime.yaml", &crate::programs::mihomo::main_pids_exact())?;
    kill_exact_pids("mihomo tun2socks --device tun://<profile tun>", &crate::programs::mihomo::tun2socks_pids_exact())?;
    kill_exact_pids("mieru run <profile config>", &crate::programs::mieru::main_pids_exact())?;
    kill_exact_pids("mieru tun2proxy --device tun://<profile tun>", &crate::programs::mieru::tun2proxy_pids_exact())?;
    kill_exact_pids("hysteria2 client <profile config>", &crate::programs::hysteria2::main_pids_exact())?;
    kill_exact_pids("hysteria2 tun2socks --device tun://<profile tun>", &crate::programs::hysteria2::tun2socks_pids_exact())?;
    kill_exact_pids("tun2socks --device tun://<profile tun>", &crate::programs::tun2socks::main_pids_exact())?;

    // IMPORTANT: do not stop plain substring/name matches for Tor.
    // Some Android systems have unrelated processes containing "tor".
    // Stop only the exact Tor command using our torrc, plus our exact lyrebird.
    kill_exact_pids("torproxy -f <our torrc>", &crate::programs::tor::main_pids_exact())?;
    kill_exact_pids("lyrebird <our binary>", &crate::programs::tor::helper_pids_exact())?;

    // 2) remove runtime guard chains before restore
    let _ = crate::features::proxyinfo::clear_rules();
    let _ = crate::features::blockedquic::clear_rules();

    // 3) flush nat/mangle and restore baseline backups independently for IPv4 and IPv6
    crate::runtime::refresh::clear_routing_cache();
    let restored_v4 = iptables_backup::reset_restore_v4_if_present()?;
    let _restored_v6 = iptables_backup::reset_restore_v6_if_present()?;

    if !restored_v4 {
        log::warn!(
            "iptables backup is missing; baseline restore skipped and existing IPv4 nat/mangle were left untouched"
        );
    }

    Ok(())
}

// Compatibility alias: runtime expects stop::stop_services()
pub fn stop_services() -> Result<()> {
    stop_services_and_restore_iptables()
}
