use anyhow::Result;
use log::{info, warn};
use std::{fs, path::Path, time::Duration};

use crate::iptables::{caps, port_filter};
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

fn runv_timeout_retry(cmd: &str, args: &[String], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    let mut a: Vec<String> = Vec::with_capacity(args.len() + 2);
    a.push("-w".into());
    a.push(XT_WAIT_SECS.into());
    a.extend_from_slice(args);
    xtables_lock::runv_timeout_retry(cmd, &a, capture, timeout)
}

/// Rust port of `full_id_iptables()` from shell.
///
/// mode: "full" | "no_full"
/// queue: NFQUEUE queue-num
/// iface: Some("wlan0") -> adds `-o wlan0`, None -> no `-o`
/// uid_file: optional path to file with lines `app=uid`
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

    let filter_present = filter.map(|f| !f.is_empty()).unwrap_or(false);
    let use_filter_v4 = filter_present && caps::multiport_v4();
    let use_filter_v6 = filter_present && caps::multiport_v6();

    // Per-UID rules if file exists
    if let Some(p) = uid_file {
        if p.is_file() {
            let s = fs::read_to_string(p)?;
            let mut uids: Vec<String> = Vec::new();
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

            let total = uids.len();
            let label = p
                .file_name()
                .and_then(|s| s.to_str())
                .unwrap_or("uids");

            for (idx, uid) in uids.iter().enumerate() {
                if mode == "full" {
                    // v4
                    if use_filter_v4 {
                        if let Some(f) = filter {
                            if !f.tcp.is_empty() {
                                add_multiport_rules("iptables", &iopt, Some(uid), queue, "tcp", &f.tcp)?;
                            }
                            if !f.udp.is_empty() {
                                add_multiport_rules("iptables", &iopt, Some(uid), queue, "udp", &f.udp)?;
                            }
                        }
                    } else {
                        let mut args: Vec<String> =
                            vec!["-t".into(), "mangle".into(), "-I".into(), "OUTPUT".into()];
                        args.extend(iopt.clone());
                        args.extend(vec![
                            "-m".into(),
                            "owner".into(),
                            "--uid-owner".into(),
                            uid.into(),
                            "-j".into(),
                            "NFQUEUE".into(),
                            "--queue-num".into(),
                            queue.to_string(),
                            "--queue-bypass".into(),
                        ]);
                        let (rc, out) = runv_timeout_retry("iptables", &args, Capture::Both, IPT_CMD_TIMEOUT)?;
                        if rc != 0 {
                            anyhow::bail!("iptables NFQUEUE rule failed (uid={}): {}", uid, out.trim());
                        }
                    }

                    // v6
                    if ipv6_avail {
                        if use_filter_v6 {
                            if let Some(f) = filter {
                                if !f.tcp.is_empty() {
                                    if let Err(e) =
                                        add_multiport_rules("ip6tables", &iopt, Some(uid), queue, "tcp", &f.tcp)
                                    {
                                        warn!("ip6tables NFQUEUE multiport rule failed (uid={}): {e}", uid);
                                    }
                                }
                                if !f.udp.is_empty() {
                                    if let Err(e) =
                                        add_multiport_rules("ip6tables", &iopt, Some(uid), queue, "udp", &f.udp)
                                    {
                                        warn!("ip6tables NFQUEUE multiport rule failed (uid={}): {e}", uid);
                                    }
                                }
                            }
                        } else {
                            let mut args: Vec<String> =
                                vec!["-t".into(), "mangle".into(), "-I".into(), "OUTPUT".into()];
                            args.extend(iopt.clone());
                            args.extend(vec![
                                "-m".into(),
                                "owner".into(),
                                "--uid-owner".into(),
                                uid.into(),
                                "-j".into(),
                                "NFQUEUE".into(),
                                "--queue-num".into(),
                                queue.to_string(),
                                "--queue-bypass".into(),
                            ]);
                            match runv_timeout_retry("ip6tables", &args, Capture::Both, IPT_CMD_TIMEOUT) {
                                Ok((0, _)) => {}
                                Ok((_rc, out)) => warn!("ip6tables NFQUEUE rule failed (uid={}): {}", uid, out.trim()),
                                Err(e) => warn!("ip6tables NFQUEUE rule failed (uid={}): {e}", uid),
                            }
                        }
                    }
                } else {
                    // no_full (legacy behaviour): only tcp 80/443
                    for port in ["80", "443"] {
                        let mut args: Vec<String> =
                            vec!["-t".into(), "mangle".into(), "-I".into(), "OUTPUT".into()];
                        args.extend(iopt.clone());
                        args.extend(vec![
                            "-p".into(),
                            "tcp".into(),
                            "--dport".into(),
                            port.into(),
                            "-m".into(),
                            "owner".into(),
                            "--uid-owner".into(),
                            uid.into(),
                            "-j".into(),
                            "NFQUEUE".into(),
                            "--queue-num".into(),
                            queue.to_string(),
                            "--queue-bypass".into(),
                        ]);
                        let (rc, out) = runv_timeout_retry("iptables", &args, Capture::Both, IPT_CMD_TIMEOUT)?;
                        if rc != 0 {
                            anyhow::bail!("iptables NFQUEUE rule failed (uid={}): {}", uid, out.trim());
                        }
                        if ipv6_avail {
                            match runv_timeout_retry("ip6tables", &args, Capture::Both, IPT_CMD_TIMEOUT) {
                                Ok((0, _)) => {}
                                Ok((_rc, out)) => warn!("ip6tables NFQUEUE rule failed (uid={}): {}", uid, out.trim()),
                                Err(e) => warn!("ip6tables NFQUEUE rule failed (uid={}): {e}", uid),
                            }
                        }
                    }
                }
            }
            info!("full_id_iptables applied mode={} queue={} iface={:?} label={} uids={}", mode, queue, iface, label, total);
            return Ok(());
        }
    }

    // Global rules
    match mode {
        "full" => {
            // v4
            if use_filter_v4 {
                if let Some(f) = filter {
                    if !f.tcp.is_empty() {
                        add_multiport_rules("iptables", &iopt, None, queue, "tcp", &f.tcp)?;
                    }
                    if !f.udp.is_empty() {
                        add_multiport_rules("iptables", &iopt, None, queue, "udp", &f.udp)?;
                    }
                }
            } else {
                let mut args: Vec<String> =
                    vec!["-t".into(), "mangle".into(), "-I".into(), "OUTPUT".into()];
                args.extend(iopt.clone());
                args.extend(vec![
                    "-j".into(),
                    "NFQUEUE".into(),
                    "--queue-num".into(),
                    queue.to_string(),
                    "--queue-bypass".into(),
                ]);
                let (rc, out) = runv_timeout_retry("iptables", &args, Capture::Both, IPT_CMD_TIMEOUT)?;
                if rc != 0 {
                    anyhow::bail!("iptables NFQUEUE rule failed (global): {}", out.trim());
                }
            }

            // v6
            if ipv6_avail {
                if use_filter_v6 {
                    if let Some(f) = filter {
                        if !f.tcp.is_empty() {
                            if let Err(e) = add_multiport_rules("ip6tables", &iopt, None, queue, "tcp", &f.tcp) {
                                warn!("ip6tables NFQUEUE multiport rule failed (global): {e}");
                            }
                        }
                        if !f.udp.is_empty() {
                            if let Err(e) = add_multiport_rules("ip6tables", &iopt, None, queue, "udp", &f.udp) {
                                warn!("ip6tables NFQUEUE multiport rule failed (global): {e}");
                            }
                        }
                    }
                } else {
                    let mut args: Vec<String> =
                        vec!["-t".into(), "mangle".into(), "-I".into(), "OUTPUT".into()];
                    args.extend(iopt.clone());
                    args.extend(vec![
                        "-j".into(),
                        "NFQUEUE".into(),
                        "--queue-num".into(),
                        queue.to_string(),
                        "--queue-bypass".into(),
                    ]);
                    match runv_timeout_retry("ip6tables", &args, Capture::Both, IPT_CMD_TIMEOUT) {
                        Ok((0, _)) => {}
                        Ok((_rc, out)) => warn!("ip6tables NFQUEUE rule failed (global): {}", out.trim()),
                        Err(e) => warn!("ip6tables NFQUEUE rule failed (global): {e}"),
                    }
                }
            }
        }
        "no_full" => {
            for port in ["80", "443"] {
                let mut args: Vec<String> =
                    vec!["-t".into(), "mangle".into(), "-I".into(), "OUTPUT".into()];
                args.extend(iopt.clone());
                args.extend(vec![
                    "-p".into(),
                    "tcp".into(),
                    "--dport".into(),
                    port.into(),
                    "-j".into(),
                    "NFQUEUE".into(),
                    "--queue-num".into(),
                    queue.to_string(),
                    "--queue-bypass".into(),
                ]);
                let (rc, out) = runv_timeout_retry("iptables", &args, Capture::Both, IPT_CMD_TIMEOUT)?;
                if rc != 0 {
                    anyhow::bail!("iptables NFQUEUE rule failed (global): {}", out.trim());
                }
                if ipv6_avail {
                    match runv_timeout_retry("ip6tables", &args, Capture::Both, IPT_CMD_TIMEOUT) {
                        Ok((0, _)) => {}
                        Ok((_rc, out)) => warn!("ip6tables NFQUEUE rule failed (global): {}", out.trim()),
                        Err(e) => warn!("ip6tables NFQUEUE rule failed (global): {e}"),
                    }
                }
            }
        }
        _ => unreachable!(),
    }

    info!("full_id_iptables applied mode={} queue={} iface={:?}", mode, queue, iface);
    Ok(())
}

fn add_multiport_rules(
    cmd: &str,
    iopt: &[String],
    uid: Option<&str>,
    queue: u16,
    proto: &str,
    ranges: &[port_filter::PortRange],
) -> Result<()> {
    let elems = port_filter::to_multiport_elements(ranges);
    for chunk in port_filter::chunk_multiport(&elems, 15) {
        let ports_csv = port_filter::join_elems_csv(&chunk);
        let mut args: Vec<String> =
            vec!["-t".into(), "mangle".into(), "-I".into(), "OUTPUT".into()];
        args.extend_from_slice(iopt);
        args.extend(vec![
            "-p".into(),
            proto.into(),
            "-m".into(),
            "multiport".into(),
            "--dports".into(),
            ports_csv,
        ]);
        if let Some(uid) = uid {
            args.extend(vec![
                "-m".into(),
                "owner".into(),
                "--uid-owner".into(),
                uid.into(),
            ]);
        }
        args.extend(vec![
            "-j".into(),
            "NFQUEUE".into(),
            "--queue-num".into(),
            queue.to_string(),
            "--queue-bypass".into(),
        ]);
        let (rc, out) = runv_timeout_retry(cmd, &args, Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("{cmd} NFQUEUE multiport rule failed: {}", out.trim());
        }
    }
    Ok(())
}
