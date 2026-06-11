use anyhow::{Context, Result};
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

/// iptables_v2: точечные правила NFQUEUE по UID без интерфейса.
///
/// Входной файл: `package=uid` (out/user_program).
///
/// Важно: правила добавляются в MANGLE_APP, а не напрямую в mangle OUTPUT.
/// OUTPUT должен только переходить в MANGLE_APP, чтобы RETURN-исключения
/// отработали до NFQUEUE.
pub fn apply(
    port: u16,
    uid_file: Option<&Path>,
    filter: Option<&crate::iptables::port_filter::ProtoPortFilter>,
) -> Result<()> {
    let _xtables_guard = xtables_lock::lock();

    let uid_file = uid_file.context("uid_file is required for iptables_v2")?;
    if !uid_file.is_file() {
        anyhow::bail!("uid_file missing: {}", uid_file.display());
    }

    let q = port.to_string();
    let s = fs::read_to_string(uid_file)
        .with_context(|| format!("read {}", uid_file.display()))?;

    let mut uids: Vec<String> = Vec::new();
    for line in s.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut it = line.split('=');
        let _pkg = it.next().unwrap_or("");
        let uid = it.next().unwrap_or("").trim();
        let Ok(uid_num) = uid.parse::<u32>() else {
            continue;
        };
        if uid_num == 0 {
            continue;
        }
        uids.push(uid_num.to_string());
    }

    let total = uids.len() as u64;
    let scope = format!("nfqueue:v2:queue={}:uid={}", port, uid_file.display());
    crate::runtime_refresh::register_nfqueue_v2(uid_file, port, filter);
    if uids.is_empty() {
        mangle_app::remove_scoped("iptables", &scope)?;
        let _ = mangle_app::remove_scoped("ip6tables", &format!("{scope}:v6"));
        info!("iptables_v2 applied: port={} empty uid list, removed scoped NFQUEUE chain", port);
        return Ok(());
    }

    let mut added = 0u64;
    let mut added6 = 0u64;

    let ipv6_avail = run_timeout_retry(
        "ip6tables",
        &["-t", "mangle", "-nL", "OUTPUT"],
        Capture::None,
        IPT_CMD_TIMEOUT,
    )
    .map(|(c, _)| c == 0)
    .unwrap_or(false);

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

    for uid in uids {
        let mut uid_added_v4 = false;
        if use_filter_v4 {
            if let Some(f) = filter {
                if !f.tcp.is_empty() && add_multiport_rule(&mut mangle_v4, uid.as_str(), &q, "tcp", &f.tcp)? {
                    uid_added_v4 = true;
                }
                if !f.udp.is_empty() && add_multiport_rule(&mut mangle_v4, uid.as_str(), &q, "udp", &f.udp)? {
                    uid_added_v4 = true;
                }
            }
        } else if add_plain_rule(&mut mangle_v4, uid.as_str(), &q)? {
            uid_added_v4 = true;
        }

        if uid_added_v4 {
            added += 1;
        }

        if let Some(mangle_v6) = mangle_v6.as_mut() {
            let mut uid_added_v6 = false;
            if use_filter_v6 {
                if let Some(f) = filter {
                    if !f.tcp.is_empty() {
                        match add_multiport_rule(mangle_v6, uid.as_str(), &q, "tcp", &f.tcp) {
                            Ok(true) => uid_added_v6 = true,
                            Ok(false) => {}
                            Err(e) => warn!("ip6tables add NFQUEUE multiport rule failed for uid={}: {e}", uid),
                        }
                    }
                    if !f.udp.is_empty() {
                        match add_multiport_rule(mangle_v6, uid.as_str(), &q, "udp", &f.udp) {
                            Ok(true) => uid_added_v6 = true,
                            Ok(false) => {}
                            Err(e) => warn!("ip6tables add NFQUEUE multiport rule failed for uid={}: {e}", uid),
                        }
                    }
                }
            } else {
                match add_plain_rule(mangle_v6, uid.as_str(), &q) {
                    Ok(true) => uid_added_v6 = true,
                    Ok(false) => {}
                    Err(e) => warn!("ip6tables add NFQUEUE rule failed for uid={}: {e}", uid),
                }
            }
            if uid_added_v6 {
                added6 += 1;
            }
        }
    }

    mangle_app::finish_scoped(&mangle_v4)?;
    if let Some(mangle_v6) = mangle_v6.as_ref() {
        if let Err(e) = mangle_app::finish_scoped(mangle_v6) {
            warn!("ip6tables scoped NFQUEUE final RETURN failed: {e}");
        }
    }

    if mangle_v6.is_some() {
        info!("iptables_v2 applied: port={} added_v4={}/{} added_v6={}/{}", port, added, total, added6, total);
    } else {
        info!("iptables_v2 applied: port={} added={}/{}", port, added, total);
    }
    Ok(())
}

fn add_multiport_rule(
    mangle: &mut mangle_app::PreparedScopedMangleApp,
    uid: &str,
    q: &str,
    proto: &str,
    ranges: &[port_filter::PortRange],
) -> Result<bool> {
    let elems = port_filter::to_multiport_elements(ranges);
    let mut any_added = false;
    for chunk in port_filter::chunk_multiport(&elems, 15) {
        let ports_csv = port_filter::join_elems_csv(&chunk);
        let tail: Vec<String> = vec![
            "-p".into(),
            proto.into(),
            "-m".into(),
            "multiport".into(),
            "--dports".into(),
            ports_csv,
            "-m".into(),
            "owner".into(),
            "--uid-owner".into(),
            uid.into(),
            "-j".into(),
            "NFQUEUE".into(),
            "--queue-num".into(),
            q.into(),
            "--queue-bypass".into(),
        ];
        mangle_app::add_scoped_rule(mangle, &tail)?;
        any_added = true;
    }
    Ok(any_added)
}

fn add_plain_rule(mangle: &mut mangle_app::PreparedScopedMangleApp, uid: &str, q: &str) -> Result<bool> {
    let tail: Vec<String> = vec![
        "-m".into(),
        "owner".into(),
        "--uid-owner".into(),
        uid.into(),
        "-j".into(),
        "NFQUEUE".into(),
        "--queue-num".into(),
        q.into(),
        "--queue-bypass".into(),
    ];
    mangle_app::add_scoped_rule(mangle, &tail)?;
    Ok(true)
}
