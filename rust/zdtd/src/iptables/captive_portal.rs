use anyhow::{Context, Result};
use log::{info, warn};
use std::time::Duration;

use crate::{captive_portal, shell::Capture, xtables_lock};

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";

const PRE_CHAIN: &str = "ZDT_CAPTIVE_PRE";
const IN_CHAIN: &str = "ZDT_CAPTIVE_IN";
const FWD_CHAIN: &str = "ZDT_CAPTIVE_FWD";

fn ipt_runv_timeout(args: &[String], capture: Capture) -> Result<(i32, String)> {
    let mut full = Vec::with_capacity(args.len() + 2);
    full.push("-w".to_string());
    full.push(XT_WAIT_SECS.to_string());
    full.extend_from_slice(args);
    xtables_lock::runv_timeout_retry("iptables", &full, capture, IPT_CMD_TIMEOUT)
}

fn ensure_chain(table: Option<&str>, chain: &str) -> Result<()> {
    let mut check = Vec::<String>::new();
    if let Some(t) = table {
        check.extend(["-t".into(), t.into()]);
    }
    check.extend(["-L".into(), chain.into(), "-n".into()]);
    let (rc, _) = ipt_runv_timeout(&check, Capture::None)?;
    if rc != 0 {
        let mut create = Vec::<String>::new();
        if let Some(t) = table {
            create.extend(["-t".into(), t.into()]);
        }
        create.extend(["-N".into(), chain.into()]);
        let (create_rc, out) = ipt_runv_timeout(&create, Capture::Both)?;
        if create_rc != 0 {
            anyhow::bail!("create {chain} failed rc={} out={}", create_rc, out.trim());
        }
    }

    let mut flush = Vec::<String>::new();
    if let Some(t) = table {
        flush.extend(["-t".into(), t.into()]);
    }
    flush.extend(["-F".into(), chain.into()]);
    let (flush_rc, out) = ipt_runv_timeout(&flush, Capture::Both)?;
    if flush_rc != 0 {
        anyhow::bail!("flush {chain} failed rc={} out={}", flush_rc, out.trim());
    }
    Ok(())
}

fn ensure_hook(table: Option<&str>, from: &str, args: &[&str], chain: &str) -> Result<()> {
    let mut check = Vec::<String>::new();
    if let Some(t) = table {
        check.extend(["-t".into(), t.into()]);
    }
    check.extend(["-C".into(), from.into()]);
    check.extend(args.iter().map(|s| s.to_string()));
    check.extend(["-j".into(), chain.into()]);
    let (rc, _) = ipt_runv_timeout(&check, Capture::None)?;
    if rc == 0 {
        return Ok(());
    }

    let mut add = Vec::<String>::new();
    if let Some(t) = table {
        add.extend(["-t".into(), t.into()]);
    }
    add.extend(["-I".into(), from.into(), "1".into()]);
    add.extend(args.iter().map(|s| s.to_string()));
    add.extend(["-j".into(), chain.into()]);
    let (add_rc, out) = ipt_runv_timeout(&add, Capture::Both)?;
    if add_rc != 0 {
        anyhow::bail!("hook {from} -> {chain} failed rc={} out={}", add_rc, out.trim());
    }
    Ok(())
}

fn append_rule(table: Option<&str>, chain: &str, args: &[String]) -> Result<()> {
    let mut rule = Vec::<String>::new();
    if let Some(t) = table {
        rule.extend(["-t".into(), t.into()]);
    }
    rule.extend(["-A".into(), chain.into()]);
    rule.extend_from_slice(args);
    let (rc, out) = ipt_runv_timeout(&rule, Capture::Both)
        .with_context(|| format!("append {chain} rule"))?;
    if rc != 0 {
        anyhow::bail!("append {chain} failed rc={} out={}", rc, out.trim());
    }
    Ok(())
}

pub fn apply(allowed_ips: &[String]) -> Result<()> {
    let _guard = xtables_lock::lock();

    ensure_chain(Some("nat"), PRE_CHAIN)?;
    ensure_chain(None, IN_CHAIN)?;
    ensure_chain(None, FWD_CHAIN)?;

    let portal_port = captive_portal::PORTAL_PORT.to_string();
    let iface = captive_portal::HOTSPOT_IFACE;

    append_rule(
        None,
        IN_CHAIN,
        &[
            "-p".into(), "tcp".into(), "--dport".into(), portal_port.clone(),
            "-j".into(), "ACCEPT".into(),
        ],
    )?;
    append_rule(
        None,
        IN_CHAIN,
        &[
            "-p".into(), "tcp".into(), "--dport".into(), portal_port.clone(),
            "-j".into(), "DROP".into(),
        ],
    )?;

    for ip in allowed_ips.iter().filter(|s| !s.trim().is_empty()) {
        append_rule(
            Some("nat"),
            PRE_CHAIN,
            &["-s".into(), ip.clone(), "-j".into(), "RETURN".into()],
        )?;
        append_rule(
            None,
            FWD_CHAIN,
            &["-s".into(), ip.clone(), "-j".into(), "RETURN".into()],
        )?;
    }

    append_rule(
        Some("nat"),
        PRE_CHAIN,
        &[
            "-p".into(), "tcp".into(), "-j".into(), "REDIRECT".into(),
            "--to-ports".into(), portal_port.clone(),
        ],
    )?;
    append_rule(None, FWD_CHAIN, &["-j".into(), "DROP".into()])?;

    // Older builds hooked by interface (-i wlan1). On some Android tether paths the
    // packets that the existing hotspot REDIRECT sees do not carry that input iface,
    // so keep the captive hook source-scoped instead and remove stale iface hooks.
    delete_hook_loop(Some("nat"), "PREROUTING", &["-i", iface, "-p", "tcp"], PRE_CHAIN);
    delete_hook_loop(Some("nat"), "PREROUTING", &["-p", "tcp"], PRE_CHAIN);
    delete_hook_loop(None, "FORWARD", &["-i", iface], FWD_CHAIN);
    delete_hook_loop(None, "FORWARD", &[], FWD_CHAIN);

    ensure_hook(Some("nat"), "PREROUTING", &["-p", "tcp"], PRE_CHAIN)?;
    ensure_hook(None, "INPUT", &["-p", "tcp", "--dport", portal_port.as_str()], IN_CHAIN)?;
    ensure_hook(None, "FORWARD", &[], FWD_CHAIN)?;

    info!(
        "captive portal rules applied iface={} port={} allowed={}",
        iface,
        captive_portal::PORTAL_PORT,
        allowed_ips.len()
    );
    Ok(())
}

fn delete_hook_loop(table: Option<&str>, from: &str, args: &[&str], chain: &str) {
    loop {
        let mut del = Vec::<String>::new();
        if let Some(t) = table {
            del.extend(["-t".into(), t.into()]);
        }
        del.extend(["-D".into(), from.into()]);
        del.extend(args.iter().map(|s| s.to_string()));
        del.extend(["-j".into(), chain.into()]);
        match ipt_runv_timeout(&del, Capture::Both) {
            Ok((0, _)) => continue,
            Ok(_) => break,
            Err(e) => {
                warn!("captive cleanup: delete hook {from}->{chain} failed: {e:#}");
                break;
            }
        }
    }
}

fn delete_chain(table: Option<&str>, chain: &str) {
    let mut flush = Vec::<String>::new();
    if let Some(t) = table {
        flush.extend(["-t".into(), t.into()]);
    }
    flush.extend(["-F".into(), chain.into()]);
    let _ = ipt_runv_timeout(&flush, Capture::Both);

    let mut delete = Vec::<String>::new();
    if let Some(t) = table {
        delete.extend(["-t".into(), t.into()]);
    }
    delete.extend(["-X".into(), chain.into()]);
    let _ = ipt_runv_timeout(&delete, Capture::Both);
}

pub fn cleanup() -> Result<()> {
    let _guard = xtables_lock::lock();
    let iface = captive_portal::HOTSPOT_IFACE;
    let port = captive_portal::PORTAL_PORT.to_string();

    delete_hook_loop(Some("nat"), "PREROUTING", &["-i", iface, "-p", "tcp"], PRE_CHAIN);
    delete_hook_loop(Some("nat"), "PREROUTING", &["-p", "tcp"], PRE_CHAIN);
    delete_hook_loop(None, "INPUT", &["-p", "tcp", "--dport", &port], IN_CHAIN);
    delete_hook_loop(None, "FORWARD", &["-i", iface], FWD_CHAIN);
    delete_hook_loop(None, "FORWARD", &[], FWD_CHAIN);

    delete_chain(Some("nat"), PRE_CHAIN);
    delete_chain(None, IN_CHAIN);
    delete_chain(None, FWD_CHAIN);

    info!("captive portal rules cleanup completed");
    Ok(())
}
