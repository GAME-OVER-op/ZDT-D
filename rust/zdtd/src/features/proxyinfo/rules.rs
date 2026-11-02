use super::ports::is_reserved_system_port_for_proxyinfo;
use super::runtime::is_protectable_project_ipv4;
use anyhow::Result;
use std::net::Ipv4Addr;

use crate::{
    iptables::lock as xtables_lock,
    logging,
    common::process::shell::Capture,
};
use super::{
    model::{Backend, LocalMatchMode, PortRuleMode, ProxyRuleStrategy, RejectMode, TransportProtocol},
    state::{clear_cached_ipv4_strategies, load_cached_ipv4_strategies, store_cached_ipv4_strategies},
};

const PROXY_CHAIN: &str = "ZDT_PROXYINFO";
pub(super) const IPT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
pub fn is_active() -> bool {
    let _guard = xtables_lock::lock();
    any_hook_active(Backend::Iptables) || any_hook_active(Backend::Ip6tables)
}

fn table_cmd_ok(backend: Backend, args: &[&str]) -> bool {
    match xtables_lock::run_timeout_retry(backend.cmd(), args, Capture::Both, IPT_TIMEOUT) {
        Ok((rc, _)) => rc == 0,
        Err(_) => false,
    }
}

fn any_hook_active(backend: Backend) -> bool {
    let hooks: &[&[&str]] = match backend {
        Backend::Iptables => &[
            &["-C", "OUTPUT", "-j", PROXY_CHAIN],
            &["-C", "OUTPUT", "-p", "tcp", "-j", PROXY_CHAIN],
            &["-C", "OUTPUT", "-p", "udp", "-j", PROXY_CHAIN],
        ],
        Backend::Ip6tables => &[
            &["-C", "OUTPUT", "-j", PROXY_CHAIN],
            &["-C", "OUTPUT", "-p", "tcp", "-j", PROXY_CHAIN],
            &["-C", "OUTPUT", "-p", "udp", "-j", PROXY_CHAIN],
        ],
    };
    hooks.iter().any(|args| table_cmd_ok(backend, args))
}

fn remove_known_hooks(backend: Backend) {
    let mut legacy_hooks: Vec<Vec<&str>> = vec![
        vec!["-D", "OUTPUT", "-j", PROXY_CHAIN],
        vec!["-D", "OUTPUT", "-p", "tcp", "-j", PROXY_CHAIN],
        vec!["-D", "OUTPUT", "-p", "udp", "-j", PROXY_CHAIN],
    ];

    match backend {
        Backend::Iptables => {
            legacy_hooks.push(vec!["-D", "OUTPUT", "-o", "lo", "-p", "tcp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-p", "tcp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-o", "lo", "-p", "udp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-p", "udp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]);
        }
        Backend::Ip6tables => {
            legacy_hooks.push(vec!["-D", "OUTPUT", "-o", "lo", "-p", "tcp", "-d", "::1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-p", "tcp", "-d", "::1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-o", "lo", "-p", "udp", "-d", "::1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-p", "udp", "-d", "::1", "-j", PROXY_CHAIN]);
        }
    }

    for hook in legacy_hooks {
        while table_cmd_ok(backend, &hook) {}
    }
}

fn clear_chain_unlocked(backend: Backend) {
    remove_known_hooks(backend);
    let _ = xtables_lock::run_timeout_retry(backend.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT);
    let _ = xtables_lock::run_timeout_retry(backend.cmd(), &["-X", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT);
}

fn clear_rules_unlocked() {
    clear_chain_unlocked(Backend::Iptables);
    clear_chain_unlocked(Backend::Ip6tables);
}

pub fn clear_rules() -> Result<()> {
    let _guard = xtables_lock::lock();
    clear_rules_unlocked();
    Ok(())
}

fn ensure_chain(backend: Backend) -> Result<()> {
    if !table_cmd_ok(backend, &["-L", PROXY_CHAIN]) {
        let (rc, out) = xtables_lock::run_timeout_retry(backend.cmd(), &["-N", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("{} -N {} failed: {}", backend.cmd(), PROXY_CHAIN, out);
        }
    }
    let (rc, out) = xtables_lock::run_timeout_retry(backend.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("{} -F {} failed: {}", backend.cmd(), PROXY_CHAIN, out);
    }
    remove_known_hooks(backend);
    let (rc, out) = xtables_lock::run_timeout_retry(
        backend.cmd(),
        &["-I", "OUTPUT", "1", "-j", PROXY_CHAIN],
        Capture::Both,
        IPT_TIMEOUT,
    )?;
    if rc != 0 {
        anyhow::bail!("{} -I OUTPUT -> {} failed: {}", backend.cmd(), PROXY_CHAIN, out);
    }
    Ok(())
}

fn build_rule_args_v4(
    uid: u32,
    proto: TransportProtocol,
    ports: &[u16],
    strategy: ProxyRuleStrategy,
) -> Vec<String> {
    let mut args = vec![
        "-A".to_string(),
        PROXY_CHAIN.to_string(),
        "-p".to_string(),
        proto.name().to_string(),
    ];
    strategy.local_match.push_args(Backend::Iptables, &mut args);
    args.push("-m".into());
    args.push("owner".into());
    args.push("--uid-owner".into());
    args.push(uid.to_string());

    match strategy.port_mode {
        PortRuleMode::Multiport => {
            let ports_csv = ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(",");
            args.push("-m".into());
            args.push("multiport".into());
            args.push("--dports".into());
            args.push(ports_csv);
        }
        PortRuleMode::PerPort => {
            let port = ports.first().copied().unwrap_or_default();
            args.push("--dport".into());
            args.push(port.to_string());
        }
    }

    strategy.reject_mode.push_args(&mut args);
    args
}

fn add_rule_for_ports_v4(
    uid: u32,
    proto: TransportProtocol,
    ports: &[u16],
    strategy: ProxyRuleStrategy,
) -> Result<()> {
    let args = build_rule_args_v4(uid, proto, ports, strategy);
    let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Iptables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!(
            "{} add proxyInfo rule failed uid={} strategy={} args='{}': {}",
            Backend::Iptables.cmd(),
            uid,
            strategy.describe(Backend::Iptables, proto),
            args.join(" "),
            out
        );
    }
    Ok(())
}

fn install_proto_rules_v4_with_strategy(
    uids: &[u32],
    ports: &[u16],
    strategy: ProxyRuleStrategy,
    proto: TransportProtocol,
) -> Result<()> {
    for &uid in uids {
        match strategy.port_mode {
            PortRuleMode::Multiport => {
                for chunk in ports.chunks(15) {
                    add_rule_for_ports_v4(uid, proto, chunk, strategy)?;
                }
            }
            PortRuleMode::PerPort => {
                for &port in ports {
                    add_rule_for_ports_v4(uid, proto, &[port], strategy)?;
                }
            }
        }
    }
    Ok(())
}

fn candidate_strategies_for_proto(proto: TransportProtocol) -> Vec<ProxyRuleStrategy> {
    let local_modes = [
        LocalMatchMode::LoAndDestLoopback,
        LocalMatchMode::DestLoopback,
        LocalMatchMode::DstTypeLocal,
        LocalMatchMode::LoopbackIface,
    ];
    let port_modes_with_multiport = [PortRuleMode::Multiport, PortRuleMode::PerPort];
    let port_modes_per_port_only = [PortRuleMode::PerPort];
    let port_modes: &[PortRuleMode] = if crate::iptables::caps::multiport_v4() {
        &port_modes_with_multiport
    } else {
        &port_modes_per_port_only
    };
    let reject_modes: &[RejectMode] = match proto {
        TransportProtocol::Tcp => &[RejectMode::RejectTcpReset, RejectMode::Reject, RejectMode::Drop],
        TransportProtocol::Udp => &[RejectMode::Reject, RejectMode::Drop],
    };

    let mut out = Vec::new();
    for local_match in local_modes {
        for &port_mode in port_modes {
            for &reject_mode in reject_modes {
                out.push(ProxyRuleStrategy {
                    local_match,
                    port_mode,
                    reject_mode,
                });
            }
        }
    }
    out
}

fn select_ipv4_strategy(
    uids: &[u32],
    ports: &[u16],
    proto: TransportProtocol,
    prerequisite: Option<(TransportProtocol, ProxyRuleStrategy)>,
) -> Result<ProxyRuleStrategy> {
    let mut errors = Vec::new();
    for strategy in candidate_strategies_for_proto(proto) {
        let (rc, out) = xtables_lock::run_timeout_retry(Backend::Iptables.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("{} -F {} failed before strategy install: {}", Backend::Iptables.cmd(), PROXY_CHAIN, out);
        }

        if let Some((prev_proto, prev_strategy)) = prerequisite {
            if let Err(e) = install_proto_rules_v4_with_strategy(uids, ports, prev_strategy, prev_proto) {
                anyhow::bail!(
                    "failed to reinstall prerequisite proxyInfo {} strategy {}: {e:#}",
                    prev_proto.name(),
                    prev_strategy.describe(Backend::Iptables, prev_proto)
                );
            }
        }

        match install_proto_rules_v4_with_strategy(uids, ports, strategy, proto) {
            Ok(()) => {
                logging::info(&format!(
                    "proxyInfo {} strategy selected: {}",
                    proto.name(),
                    strategy.describe(Backend::Iptables, proto)
                ));
                return Ok(strategy);
            }
            Err(e) => {
                let msg = format!("{} => {e:#}", strategy.describe(Backend::Iptables, proto));
                logging::warn(&format!("proxyInfo strategy failed: {}", msg));
                errors.push(msg);
            }
        }
    }

    anyhow::bail!(
        "all proxyInfo {} strategies failed: {}",
        proto.name(),
        errors.join(" | ")
    )
}

fn try_install_cached_ipv4_rules(uids: &[u32], ports: &[u16]) -> Result<bool> {
    if uids.is_empty() || ports.is_empty() {
        return Ok(false);
    }

    let cached = load_cached_ipv4_strategies();
    let (Some(tcp_strategy), Some(udp_strategy)) = (cached.tcp, cached.udp) else {
        return Ok(false);
    };

    if !crate::iptables::caps::multiport_v4()
        && (matches!(tcp_strategy.port_mode, PortRuleMode::Multiport)
            || matches!(udp_strategy.port_mode, PortRuleMode::Multiport))
    {
        logging::info("proxyInfo cached IPv4 strategy uses multiport but multiport is unsupported; reprobe per-port only");
        clear_cached_ipv4_strategies();
        return Ok(false);
    }

    if let Err(e) = install_proto_rules_v4_with_strategy(uids, ports, tcp_strategy, TransportProtocol::Tcp) {
        logging::warn(&format!(
            "proxyInfo cached TCP strategy failed, reprobe required: {} => {e:#}",
            tcp_strategy.describe(Backend::Iptables, TransportProtocol::Tcp)
        ));
        clear_cached_ipv4_strategies();
        let _ = xtables_lock::run_timeout_retry(Backend::Iptables.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT);
        return Ok(false);
    }

    if let Err(e) = install_proto_rules_v4_with_strategy(uids, ports, udp_strategy, TransportProtocol::Udp) {
        logging::warn(&format!(
            "proxyInfo cached UDP strategy failed, reprobe required: {} => {e:#}",
            udp_strategy.describe(Backend::Iptables, TransportProtocol::Udp)
        ));
        clear_cached_ipv4_strategies();
        let _ = xtables_lock::run_timeout_retry(Backend::Iptables.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT);
        return Ok(false);
    }

    logging::info(&format!(
        "proxyInfo reused cached IPv4 strategies: tcp=[{}], udp=[{}]",
        tcp_strategy.describe(Backend::Iptables, TransportProtocol::Tcp),
        udp_strategy.describe(Backend::Iptables, TransportProtocol::Udp)
    ));
    Ok(true)
}

fn install_ipv4_rules(
    uids: &[u32],
    ports: &[u16],
    global_ports: &[u16],
    project_interface_ips: &[Ipv4Addr],
) -> Result<()> {
    if uids.is_empty() || (ports.is_empty() && global_ports.is_empty() && project_interface_ips.is_empty()) {
        return Ok(());
    }

    ensure_chain(Backend::Iptables)?;
    let (rc, out) = xtables_lock::run_timeout_retry(Backend::Iptables.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("{} -F {} failed: {}", Backend::Iptables.cmd(), PROXY_CHAIN, out);
    }
    if !ports.is_empty() && !try_install_cached_ipv4_rules(uids, ports)? {
        let tcp_strategy = select_ipv4_strategy(uids, ports, TransportProtocol::Tcp, None)?;
        let udp_strategy = select_ipv4_strategy(
            uids,
            ports,
            TransportProtocol::Udp,
            Some((TransportProtocol::Tcp, tcp_strategy)),
        )?;
        store_cached_ipv4_strategies(tcp_strategy, udp_strategy);
    }
    install_global_tcp_rules_v4(uids, global_ports)?;
    install_project_interface_ip_rules_v4(uids, project_interface_ips)?;
    Ok(())
}

fn install_project_interface_ip_rules_v4(uids: &[u32], ips: &[Ipv4Addr]) -> Result<()> {
    if uids.is_empty() || ips.is_empty() {
        return Ok(());
    }

    for &uid in uids {
        for ip in ips {
            if !is_protectable_project_ipv4(*ip) {
                continue;
            }
            let dst = format!("{}/32", ip);
            let args = vec![
                "-A".to_string(),
                PROXY_CHAIN.to_string(),
                "-d".to_string(),
                dst.clone(),
                "-m".to_string(),
                "owner".to_string(),
                "--uid-owner".to_string(),
                uid.to_string(),
                "-j".to_string(),
                "REJECT".to_string(),
            ];
            let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Iptables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
            if rc != 0 {
                anyhow::bail!(
                    "iptables add proxyInfo project interface IP block failed uid={} dst={} args='{}': {}",
                    uid,
                    dst,
                    args.join(" "),
                    out
                );
            }
        }
    }
    Ok(())
}

fn install_global_tcp_rules_v4(uids: &[u32], ports: &[u16]) -> Result<()> {
    if uids.is_empty() || ports.is_empty() {
        return Ok(());
    }
    for &uid in uids {
        for &port in ports {
            if is_reserved_system_port_for_proxyinfo(port) {
                logging::warn(&format!(
                    "proxyInfo: skip global protected port {}: ports below 1024 are reserved by the system and will not be blocked",
                    port
                ));
                continue;
            }
            let args = vec![
                "-A".to_string(),
                PROXY_CHAIN.to_string(),
                "-p".to_string(),
                "tcp".to_string(),
                "--dport".to_string(),
                port.to_string(),
                "-m".to_string(),
                "owner".to_string(),
                "--uid-owner".to_string(),
                uid.to_string(),
                "-j".to_string(),
                "REJECT".to_string(),
                "--reject-with".to_string(),
                "tcp-reset".to_string(),
            ];
            let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Iptables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
            if rc != 0 {
                anyhow::bail!("iptables add proxyInfo myproxy upstream block failed uid={} port={} args='{}': {}", uid, port, args.join(" "), out);
            }
        }
    }
    Ok(())
}

fn install_ipv6_rules(uids: &[u32]) -> Result<()> {
    if uids.is_empty() {
        return Ok(());
    }

    ensure_chain(Backend::Ip6tables)?;
    for &uid in uids {
        let args = vec![
            "-A".to_string(),
            PROXY_CHAIN.to_string(),
            "-m".to_string(),
            "owner".to_string(),
            "--uid-owner".to_string(),
            uid.to_string(),
            "-j".to_string(),
            "DROP".to_string(),
        ];
        let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Ip6tables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("ip6tables add proxyInfo IPv6 deny rule failed uid={} args='{}': {}", uid, args.join(" "), out);
        }
    }
    logging::info("proxyInfo IPv6 strategy selected: full OUTPUT deny by uid (DROP)");
    Ok(())
}

pub(super) fn install_rules(uids: &[u32], local_ports: &[u16], global_ports: &[u16], project_interface_ips: &[Ipv4Addr]) -> Result<()> {
    let _guard = xtables_lock::lock();
    clear_rules_unlocked();
    if uids.is_empty() {
        return Ok(());
    }

    let result = (|| {
        if !local_ports.is_empty() || !global_ports.is_empty() || !project_interface_ips.is_empty() {
            install_ipv4_rules(uids, local_ports, global_ports, project_interface_ips)?;
        }
        if let Err(e) = install_ipv6_rules(uids) {
            logging::warn(&format!(
                "proxyInfo IPv6 rules unavailable, keeping IPv4 rules active: {e:#}"
            ));
        }
        Ok(())
    })();

    if result.is_err() {
        clear_rules_unlocked();
    }
    result
}
