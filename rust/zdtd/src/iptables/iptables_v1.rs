use anyhow::Result;
use log::{info, warn};
use std::{fs, path::Path, time::Duration};

use crate::iptables::{caps, mangle_app, port_filter};
use crate::shell::Capture;
use crate::xtables_lock;

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";

fn run_timeout_retry(cmd: &str, args: &[&str], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    let mut a: Vec<&str> = Vec::with_capacity(args.len() + 2);
    a.push("-w");
    a.push(XT_WAIT_SECS);
    a.extend_from_slice(args);
    xtables_lock::run_timeout_retry(cmd, &a, capture, timeout)
}

/// Rust port of `full_id_iptables()` from shell.
///
/// mode: "full" | "no_full"
/// queue: NFQUEUE queue-num
/// iface: Some("wlan0") -> adds `-o wlan0`, None -> no `-o`
/// uid_file: optional path to file with lines `app=uid`
///
/// Important: nfqws/nfqws2 NFQUEUE rules are inserted into MANGLE_APP, not
/// directly into mangle OUTPUT. OUTPUT should only jump to MANGLE_APP so the
/// shared RETURN exclusions run before NFQUEUE.
pub fn apply(
    mode: &str,
    queue: u16,
    iface: Option<&str>,
    uid_file: Option<&Path>,
    filter: Option<&crate::iptables::port_filter::ProtoPortFilter>,
) -> Result<()> {
    let _xtables_guard = xtables_lock::lock();

    if mode != "full" && mode != "no_full" {
        anyhow::bail!("Usage: full_id_iptables <full|no_full> <queue-num> [iface] [uid_file]");
    }

    let mut iopt: Vec<String> = Vec::new();
    if let Some(iface) = iface {
        if !iface.is_empty() {
            iopt.push("-o".into());
            iopt.push(iface.into());
        }
    }

    let ipv6_avail = run_timeout_retry(
        "ip6tables",
        &["-t", "mangle", "-nL", "OUTPUT"],
        Capture::None,
        IPT_CMD_TIMEOUT,
    )
    .map(|(c, _)| c == 0)
    .unwrap_or(false);

    let scope = format!(
        "nfqueue:v1:mode={}:queue={}:iface={}:uid={}",
        mode,
        queue,
        iface.unwrap_or(""),
        uid_file
            .map(|p| p.display().to_string())
            .unwrap_or_else(|| "global".to_string()),
    );

    if let Some(p) = uid_file {
        if p.is_file() {
            crate::runtime_refresh::register_nfqueue_v1(p, mode, queue, iface, filter);
            let uids = read_uid_file(p)?;
            let total = uids.len();
            let label = p.file_name().and_then(|s| s.to_str()).unwrap_or("uids");
            if uids.is_empty() {
                mangle_app::remove_scoped("iptables", &scope)?;
                let _ = mangle_app::remove_scoped("ip6tables", &format!("{scope}:v6"));
                info!("full_id_iptables applied mode={} queue={} iface={:?} label={} empty uid list, removed scoped NFQUEUE chain", mode, queue, iface, label);
                return Ok(());
            }

            let mut mangle_v4 = mangle_app::prepare_scoped("iptables", &scope)?;
            let mut mangle_v6 = if ipv6_avail {
                match mangle_app::prepare_scoped("ip6tables", &format!("{scope}:v6")) {
                    Ok(prepared) => Some(prepared),
                    Err(e) => {
                        warn!("ip6tables MANGLE_APP prepare failed: {e}");
                        None
                    }
                }
            } else {
                None
            };

            let filter_present = filter.map(|f| !f.is_empty()).unwrap_or(false);
            let use_filter_v4 = filter_present && caps::multiport_v4();
            let use_filter_v6 = filter_present && caps::multiport_v6();

            for uid in &uids {
                apply_uid_or_global(&mut mangle_v4, &iopt, Some(uid.as_str()), queue, mode, filter, use_filter_v4)?;
                if let Some(mangle_v6) = mangle_v6.as_mut() {
                    if let Err(e) = apply_uid_or_global(mangle_v6, &iopt, Some(uid.as_str()), queue, mode, filter, use_filter_v6) {
                        warn!("ip6tables NFQUEUE rule failed (uid={}): {e}", uid);
                    }
                }
            }

            mangle_app::finish_scoped(&mangle_v4)?;
            if let Some(mangle_v6) = mangle_v6.as_ref() {
                if let Err(e) = mangle_app::finish_scoped(mangle_v6) {
                    warn!("ip6tables scoped NFQUEUE final RETURN failed: {e}");
                }
            }
            info!("full_id_iptables applied mode={} queue={} iface={:?} label={} uids={}", mode, queue, iface, label, total);
            return Ok(());
        }
    }

    let mut mangle_v4 = mangle_app::prepare_scoped("iptables", &scope)?;
    let mut mangle_v6 = if ipv6_avail {
        match mangle_app::prepare_scoped("ip6tables", &format!("{scope}:v6")) {
            Ok(prepared) => Some(prepared),
            Err(e) => {
                warn!("ip6tables MANGLE_APP prepare failed: {e}");
                None
            }
        }
    } else {
        None
    };
    let filter_present = filter.map(|f| !f.is_empty()).unwrap_or(false);
    let use_filter_v4 = filter_present && caps::multiport_v4();
    let use_filter_v6 = filter_present && caps::multiport_v6();

    apply_uid_or_global(&mut mangle_v4, &iopt, None, queue, mode, filter, use_filter_v4)?;
    if let Some(mangle_v6) = mangle_v6.as_mut() {
        if let Err(e) = apply_uid_or_global(mangle_v6, &iopt, None, queue, mode, filter, use_filter_v6) {
            warn!("ip6tables NFQUEUE rule failed (global): {e}");
        }
    }

    mangle_app::finish_scoped(&mangle_v4)?;
    if let Some(mangle_v6) = mangle_v6.as_ref() {
        if let Err(e) = mangle_app::finish_scoped(mangle_v6) {
            warn!("ip6tables scoped NFQUEUE final RETURN failed: {e}");
        }
    }
    if let Some(p) = uid_file {
        crate::runtime_refresh::register_nfqueue_v1(p, mode, queue, iface, filter);
    }

    info!("full_id_iptables applied mode={} queue={} iface={:?}", mode, queue, iface);
    Ok(())
}

fn read_uid_file(path: &Path) -> Result<Vec<String>> {
    let s = fs::read_to_string(path)?;
    let mut uids = Vec::new();
    for line in s.lines() {
        let mut it = line.split('=');
        let _app = it.next().unwrap_or("");
        let uid = it.next().unwrap_or("").trim();
        let Ok(uid_num) = uid.parse::<u32>() else {
            continue;
        };
        if uid_num == 0 {
            continue;
        }
        uids.push(uid_num.to_string());
    }
    Ok(uids)
}

fn apply_uid_or_global(
    mangle: &mut mangle_app::PreparedScopedMangleApp,
    iopt: &[String],
    uid: Option<&str>,
    queue: u16,
    mode: &str,
    filter: Option<&port_filter::ProtoPortFilter>,
    use_multiport_filter: bool,
) -> Result<()> {
    match mode {
        "full" => {
            if use_multiport_filter {
                if let Some(f) = filter {
                    if !f.tcp.is_empty() {
                        add_multiport_rules_with_fallback(mangle, iopt, uid, queue, "tcp", &f.tcp)?;
                    }
                    if !f.udp.is_empty() {
                        add_multiport_rules_with_fallback(mangle, iopt, uid, queue, "udp", &f.udp)?;
                    }
                    return Ok(());
                }
            }
            add_nfqueue_rule(mangle, iopt, uid, queue, None, None, None)
        }
        "no_full" => {
            add_nfqueue_rule(mangle, iopt, uid, queue, Some("tcp"), Some("80"), None)?;
            add_nfqueue_rule(mangle, iopt, uid, queue, Some("tcp"), Some("443"), None)
        }
        _ => unreachable!(),
    }
}

fn add_multiport_rules_with_fallback(
    mangle: &mut mangle_app::PreparedScopedMangleApp,
    iopt: &[String],
    uid: Option<&str>,
    queue: u16,
    proto: &str,
    ranges: &[port_filter::PortRange],
) -> Result<()> {
    if !caps::multiport_v4() {
        return add_per_port_rules(mangle, iopt, uid, queue, proto, ranges);
    }
    match add_multiport_rules(mangle, iopt, uid, queue, proto, ranges) {
        Ok(()) => Ok(()),
        Err(e) => {
            warn!("iptables NFQUEUE multiport rule failed for proto={proto} uid={uid:?}: {e:#}; falling back to per-port");
            caps::disable_multiport_persistently(&format!("nfqueue {proto} multiport failed: {e:#}"));
            add_per_port_rules(mangle, iopt, uid, queue, proto, ranges)
        }
    }
}

fn add_multiport_rules(
    mangle: &mut mangle_app::PreparedScopedMangleApp,
    iopt: &[String],
    uid: Option<&str>,
    queue: u16,
    proto: &str,
    ranges: &[port_filter::PortRange],
) -> Result<()> {
    let elems = port_filter::to_multiport_elements(ranges);
    for chunk in port_filter::chunk_multiport(&elems, 15) {
        let ports_csv = port_filter::join_elems_csv(&chunk);
        add_nfqueue_rule(mangle, iopt, uid, queue, Some(proto), None, Some(ports_csv.as_str()))?;
    }
    Ok(())
}

fn add_per_port_rules(
    mangle: &mut mangle_app::PreparedScopedMangleApp,
    iopt: &[String],
    uid: Option<&str>,
    queue: u16,
    proto: &str,
    ranges: &[port_filter::PortRange],
) -> Result<()> {
    for range in ranges {
        let dport = if range.start == range.end {
            range.start.to_string()
        } else {
            format!("{}:{}", range.start, range.end)
        };
        add_nfqueue_rule(mangle, iopt, uid, queue, Some(proto), Some(dport.as_str()), None)?;
    }
    Ok(())
}

fn add_nfqueue_rule(
    mangle: &mut mangle_app::PreparedScopedMangleApp,
    iopt: &[String],
    uid: Option<&str>,
    queue: u16,
    proto: Option<&str>,
    dport: Option<&str>,
    multiport_csv: Option<&str>,
) -> Result<()> {
    let mut tail: Vec<String> = Vec::new();
    tail.extend_from_slice(iopt);
    if let Some(proto) = proto {
        tail.push("-p".into());
        tail.push(proto.into());
    }
    if let Some(ports) = multiport_csv {
        tail.extend(vec![
            "-m".into(),
            "multiport".into(),
            "--dports".into(),
            ports.into(),
        ]);
    } else if let Some(port) = dport {
        tail.push("--dport".into());
        tail.push(port.into());
    }
    if let Some(uid) = uid {
        tail.extend(vec![
            "-m".into(),
            "owner".into(),
            "--uid-owner".into(),
            uid.into(),
        ]);
    }
    tail.extend(vec![
        "-j".into(),
        "NFQUEUE".into(),
        "--queue-num".into(),
        queue.to_string(),
        "--queue-bypass".into(),
    ]);
    mangle_app::add_scoped_rule(mangle, &tail)?;
    Ok(())
}
