use anyhow::{Context, Result};
use log::info;
use std::{fs, path::Path, thread, time::Duration};

use crate::shell::{self, Capture};

#[derive(Debug, Default, Clone)]
pub struct Stats {
    pub success: u64,
    pub fail: u64,
    pub processed: u64,
    pub total_v4: u64,
}

/// Rust port of `mobile_iptables_beta(port, ip_file)` (IPv4-only processing).
pub fn apply(port: u16, ip_file: &Path) -> Result<Stats> {    if !ip_file.is_file() {        anyhow::bail!("ip_file not found: {}", ip_file.display());
    }

    let lines = fs::read_to_string(ip_file).with_context(|| format!("read {}", ip_file.display()))?;
    let v4: Vec<String> = lines
        .lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty())
        .filter(|l| !l.starts_with('#'))
        .filter(|l| !l.contains(':')) // skip IPv6
        .filter(|l| l.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false))
        .map(|l| l.to_string())
        .collect();

    let total = v4.len() as u64;
    let mut st = Stats { total_v4: total, ..Stats::default() };

    let _file_path = ip_file.display().to_string();
    if total == 0 {
        info!("mobile_iplist: no IPv4 -> done");        return Ok(st);
    }

    // ipset mode
    if command_exists("ipset") {
        info!("mobile_iplist: ipset found -> set mode");
        let set_v4 = format!("iplist_v4_{port}");
        let _ = shell::run("ipset", &["destroy", &set_v4], Capture::None);
        let _ = shell::run("ipset", &["create", &set_v4, "hash:ip", "family", "inet"], Capture::None);

        for ip in &v4 {
            let _ = shell::run("ipset", &["add", &set_v4, ip], Capture::None);
        }

        let q = port.to_string();
        shell::ok("iptables", &["-t","mangle","-A","PREROUTING","-m","set","--match-set",&set_v4,"dst","-j","NFQUEUE","--queue-num",&q])?;
        shell::ok("iptables", &["-t","mangle","-A","OUTPUT","-m","set","--match-set",&set_v4,"dst","-j","NFQUEUE","--queue-num",&q])?;

        st.success = total;
        st.processed = total;        return Ok(st);
    }

    info!("mobile_iplist: ipset not found -> line-by-line");
    let thresholds: [u64; 5] = [10,25,50,75,100];
    let mut next_thr_idx = 0usize;

    for ip in &v4 {
        st.processed += 1;
        let q = port.to_string();

        if add_rule("OUTPUT", ip, &q)? {
            st.success += 1;
        } else {            thread::sleep(Duration::from_secs(3));
            if add_rule("OUTPUT", ip, &q)? {
                st.success += 1;
            } else {
                if add_rule("POSTROUTING", ip, &q)? {
                    st.success += 1;
                } else {
                    st.fail += 1;                }
            }
        }

        let pct = (st.processed * 100) / st.total_v4.max(1);
        while next_thr_idx < thresholds.len() && pct >= thresholds[next_thr_idx] {            next_thr_idx += 1;
        }
    }

    if next_thr_idx == 0 || thresholds.get(next_thr_idx.saturating_sub(1)).copied().unwrap_or(0) != 100 {    }

    info!("mobile_iplist: done processed={} total={} success={} fail={}", st.processed, st.total_v4, st.success, st.fail);
    Ok(st)
}

fn command_exists(cmd: &str) -> bool {
    shell::run("sh", &["-c", &format!("command -v {cmd} >/dev/null 2>&1")], Capture::None)
        .map(|(c,_)| c==0)
        .unwrap_or(false)
}

fn add_rule(chain: &str, ip: &str, q: &str) -> Result<bool> {
    let args = ["-t","mangle","-A",chain,"-d",ip,"-j","NFQUEUE","--queue-num",q];
    let (c, _) = shell::run("iptables", &args, Capture::None)?;
    Ok(c == 0)
}
