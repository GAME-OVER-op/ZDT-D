use anyhow::{Context, Result};
use log::{info, warn};
use std::{fs, path::Path};

use crate::shell::{self, Capture};
use crate::iptables::{caps, port_filter};

const IPT_CMD_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

/// iptables_v2: точечные правила NFQUEUE по UID без интерфейса (работает для всех).
///
/// Входной файл: `package=uid` (out/user_program)
/// Правило (на каждый UID):
/// `-t mangle -I OUTPUT -m owner --uid-owner <uid> -j NFQUEUE --queue-num <port> --queue-bypass`
///
/// Идемпотентно: сначала `-C`, потом `-I`.
pub fn apply(
    port: u16,
    uid_file: Option<&Path>,
    filter: Option<&crate::iptables::port_filter::ProtoPortFilter>,
) -> Result<()> {
    let uid_file = uid_file.context("uid_file is required for iptables_v2")?;
    if !uid_file.is_file() {
        anyhow::bail!("uid_file missing: {}", uid_file.display());
    }

    let q = port.to_string();
    let s = fs::read_to_string(uid_file)
        .with_context(|| format!("read {}", uid_file.display()))?;

    // Parse UIDs first to know total and to report progress.
    let mut uids: Vec<String> = Vec::new();
    for line in s.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut it = line.split('=');
        let _pkg = it.next().unwrap_or("");
        let uid = it.next().unwrap_or("").trim();
        if uid.is_empty() || !uid.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        uids.push(uid.to_string());
    }

    let total = uids.len() as u64;
    let mut added = 0u64;
    let mut added6 = 0u64;

    let ipv6_avail = shell::run_timeout(
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

fn add_multiport_rule_idempotent(
    cmd: &str,
    uid: &str,
    q: &str,
    proto: &str,
    ranges: &[port_filter::PortRange],
) -> Result<bool> {
    let elems = port_filter::to_multiport_elements(ranges);
    let mut any_added = false;
    for chunk in port_filter::chunk_multiport(&elems, 15) {
        let ports_csv = port_filter::join_elems_csv(&chunk);

        let check = [
            "-t",
            "mangle",
            "-C",
            "OUTPUT",
            "-p",
            proto,
            "-m",
            "multiport",
            "--dports",
            ports_csv.as_str(),
            "-m",
            "owner",
            "--uid-owner",
            uid,
            "-j",
            "NFQUEUE",
            "--queue-num",
            q,
            "--queue-bypass",
        ];
        let (c, _) = shell::run_timeout(cmd, &check, Capture::None, IPT_CMD_TIMEOUT)?;
        if c == 0 {
            continue;
        }

        let add_rule = [
            "-t",
            "mangle",
            "-I",
            "OUTPUT",
            "-p",
            proto,
            "-m",
            "multiport",
            "--dports",
            ports_csv.as_str(),
            "-m",
            "owner",
            "--uid-owner",
            uid,
            "-j",
            "NFQUEUE",
            "--queue-num",
            q,
            "--queue-bypass",
        ];
        let (c2, _) = shell::run_timeout(cmd, &add_rule, Capture::None, IPT_CMD_TIMEOUT)?;
        if c2 == 0 {
            any_added = true;
        }
    }
    Ok(any_added)
}
    for uid in uids {

let mut uid_added_v4 = false;

if use_filter_v4 {
    if let Some(f) = filter {
        if !f.tcp.is_empty() {
	            if add_multiport_rule_idempotent("iptables", uid.as_str(), &q, "tcp", &f.tcp)? {
                uid_added_v4 = true;
            }
        }
        if !f.udp.is_empty() {
	            if add_multiport_rule_idempotent("iptables", uid.as_str(), &q, "udp", &f.udp)? {
                uid_added_v4 = true;
            }
        }
    }
} else {
    let check = [
        "-t", "mangle", "-C", "OUTPUT",
	        "-m", "owner", "--uid-owner", uid.as_str(),
        "-j", "NFQUEUE", "--queue-num", &q, "--queue-bypass",
    ];
    let (c, _) = shell::run_timeout("iptables", &check, Capture::None, IPT_CMD_TIMEOUT)?;
    if c == 0 {
        continue;
    }

    let add_rule = [
        "-t", "mangle", "-I", "OUTPUT",
	        "-m", "owner", "--uid-owner", uid.as_str(),
        "-j", "NFQUEUE", "--queue-num", &q, "--queue-bypass",
    ];
    let (c2, _) = shell::run_timeout("iptables", &add_rule, Capture::None, IPT_CMD_TIMEOUT)?;
    if c2 == 0 {
        uid_added_v4 = true;
    }
}

if uid_added_v4 {
    added += 1;
}

if ipv6_avail {
    let mut uid_added_v6 = false;

    if use_filter_v6 {
        if let Some(f) = filter {
            if !f.tcp.is_empty() {
	                match add_multiport_rule_idempotent("ip6tables", uid.as_str(), &q, "tcp", &f.tcp) {
                    Ok(true) => uid_added_v6 = true,
                    Ok(false) => {}
                    Err(e) => warn!("ip6tables add NFQUEUE multiport rule failed for uid={}: {e}", uid),
                }
            }
            if !f.udp.is_empty() {
	                match add_multiport_rule_idempotent("ip6tables", uid.as_str(), &q, "udp", &f.udp) {
                    Ok(true) => uid_added_v6 = true,
                    Ok(false) => {}
                    Err(e) => warn!("ip6tables add NFQUEUE multiport rule failed for uid={}: {e}", uid),
                }
            }
        }
    } else {
        let check6 = [
            "-t", "mangle", "-C", "OUTPUT",
	            "-m", "owner", "--uid-owner", uid.as_str(),
            "-j", "NFQUEUE", "--queue-num", &q, "--queue-bypass",
        ];
        let (c6, _) = shell::run_timeout("ip6tables", &check6, Capture::None, IPT_CMD_TIMEOUT)
            .unwrap_or((1, String::new()));
        if c6 != 0 {
            let add6 = [
                "-t", "mangle", "-I", "OUTPUT",
	                "-m", "owner", "--uid-owner", uid.as_str(),
                "-j", "NFQUEUE", "--queue-num", &q, "--queue-bypass",
            ];
            let (c62, _) = shell::run_timeout("ip6tables", &add6, Capture::None, IPT_CMD_TIMEOUT)
                .unwrap_or((1, String::new()));
            if c62 == 0 {
                uid_added_v6 = true;
            } else {
                warn!("ip6tables add NFQUEUE rule failed for uid={}", uid);
            }
        }
    }

    if uid_added_v6 {
        added6 += 1;
    }
}
    }

    if ipv6_avail {
        info!("iptables_v2 applied: port={} added_v4={}/{} added_v6={}/{}", port, added, total, added6, total);
    } else {
        info!("iptables_v2 applied: port={} added={}/{}", port, added, total);
    }
    Ok(())
}
