use anyhow::{Context, Result};
use log::{info, warn};
use std::time::Duration;

use crate::{
    iptables::{caps, port_filter},
    shell::Capture,
    xtables_lock,
};

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";

pub const CHAIN: &str = "ZDT_HOTSPOT_REDIRECT";
pub const DEFAULT_HOTSPOT_BYPASS_PORTS: &str = concat!(
    "1,7,9,11,13,15,17,19,20,21,22,23,25,37,42,",
    "43,53,69,77,79,87,95,101,102,103,104,109,110,111,113,",
    "115,117,119,123,135,137,139,143,161,179,389,427,465,512,513,",
    "514,515,526,530,531,532,540,548,554,556,563,587,601,636,989,",
    "990,993,995,1719,1720,1723,2049,3659,4045,4190,5060,5061,",
    "6000,6566,6665,6666,6667,6668,6669,6679,6697,10080",
);

#[derive(Debug, Clone, Copy)]
pub struct HotspotRedirectConfig<'a> {
    pub owner: &'a str,
    pub listen_port: u16,
    pub capture_all: bool,
    pub bypass_ports: &'a str,
}

pub fn ensure_prerouting_redirect(cfg: HotspotRedirectConfig<'_>) -> Result<()> {
    let _guard = xtables_lock::lock();

    ensure_chain()?;
    flush_chain()?;
    remove_legacy_direct_redirect(cfg.listen_port);

    if !cfg.capture_all {
        add_bypass_returns(cfg.bypass_ports)?;
    }
    add_final_redirect(cfg.listen_port)?;
    ensure_prerouting_jump()?;

    info!(
        "{}: hotspot redirect prepared chain={} port={} capture_all={} multiport_v4={}",
        cfg.owner,
        CHAIN,
        cfg.listen_port,
        cfg.capture_all,
        caps::multiport_v4(),
    );

    Ok(())
}

fn ipt_runv_timeout(args: &[String], capture: Capture) -> Result<(i32, String)> {
    let mut full = Vec::with_capacity(args.len() + 2);
    full.push("-w".to_string());
    full.push(XT_WAIT_SECS.to_string());
    full.extend_from_slice(args);
    xtables_lock::runv_timeout_retry("iptables", &full, capture, IPT_CMD_TIMEOUT)
}

fn ensure_chain() -> Result<()> {
    let check = vec![
        "-t".into(),
        "nat".into(),
        "-L".into(),
        CHAIN.into(),
        "-n".into(),
    ];
    let (rc, _) = ipt_runv_timeout(&check, Capture::None)?;
    if rc == 0 {
        return Ok(());
    }

    let create = vec![
        "-t".into(),
        "nat".into(),
        "-N".into(),
        CHAIN.into(),
    ];
    let (create_rc, out) = ipt_runv_timeout(&create, Capture::Both)?;
    if create_rc != 0 {
        anyhow::bail!("create {CHAIN} failed rc={} out={}", create_rc, out.trim());
    }
    Ok(())
}

fn flush_chain() -> Result<()> {
    let flush = vec![
        "-t".into(),
        "nat".into(),
        "-F".into(),
        CHAIN.into(),
    ];
    let (rc, out) = ipt_runv_timeout(&flush, Capture::Both)?;
    if rc != 0 {
        anyhow::bail!("flush {CHAIN} failed rc={} out={}", rc, out.trim());
    }
    Ok(())
}

fn ensure_prerouting_jump() -> Result<()> {
    let check = vec![
        "-t".into(),
        "nat".into(),
        "-C".into(),
        "PREROUTING".into(),
        "-p".into(),
        "tcp".into(),
        "-j".into(),
        CHAIN.into(),
    ];
    let (rc, _) = ipt_runv_timeout(&check, Capture::None)?;
    if rc == 0 {
        return Ok(());
    }

    let add = vec![
        "-t".into(),
        "nat".into(),
        "-I".into(),
        "PREROUTING".into(),
        "1".into(),
        "-p".into(),
        "tcp".into(),
        "-j".into(),
        CHAIN.into(),
    ];
    let (add_rc, out) = ipt_runv_timeout(&add, Capture::Both)?;
    if add_rc != 0 {
        anyhow::bail!("hook PREROUTING -> {CHAIN} failed rc={} out={}", add_rc, out.trim());
    }
    Ok(())
}

fn remove_legacy_direct_redirect(listen_port: u16) {
    let listen_port_s = listen_port.to_string();
    loop {
        let delete = vec![
            "-t".into(),
            "nat".into(),
            "-D".into(),
            "PREROUTING".into(),
            "-p".into(),
            "tcp".into(),
            "-j".into(),
            "REDIRECT".into(),
            "--to-ports".into(),
            listen_port_s.clone(),
        ];

        match ipt_runv_timeout(&delete, Capture::Both) {
            Ok((0, _)) => continue,
            Ok(_) => break,
            Err(e) => {
                warn!("remove legacy hotspot direct REDIRECT failed: {e:#}");
                break;
            }
        }
    }
}

fn add_bypass_returns(spec: &str) -> Result<()> {
    let ranges = port_filter::merge_ranges(port_filter::parse_ranges(spec));
    if ranges.is_empty() {
        return Ok(());
    }

    if caps::multiport_v4() {
        let elems = port_filter::to_multiport_elements(&ranges);
        for chunk in port_filter::chunk_multiport(&elems, 15) {
            let ports_csv = port_filter::join_elems_csv(&chunk);
            let args = vec![
                "-t".into(),
                "nat".into(),
                "-A".into(),
                CHAIN.into(),
                "-p".into(),
                "tcp".into(),
                "-m".into(),
                "multiport".into(),
                "--dports".into(),
                ports_csv,
                "-j".into(),
                "RETURN".into(),
            ];
            let (rc, out) = ipt_runv_timeout(&args, Capture::Both)
                .context("add hotspot multiport bypass RETURN")?;
            if rc != 0 {
                anyhow::bail!("add {CHAIN} multiport RETURN failed rc={} out={}", rc, out.trim());
            }
        }
        return Ok(());
    }

    for range in ranges {
        let dport = if range.start == range.end {
            range.start.to_string()
        } else {
            format!("{}:{}", range.start, range.end)
        };
        let args = vec![
            "-t".into(),
            "nat".into(),
            "-A".into(),
            CHAIN.into(),
            "-p".into(),
            "tcp".into(),
            "--dport".into(),
            dport,
            "-j".into(),
            "RETURN".into(),
        ];
        let (rc, out) = ipt_runv_timeout(&args, Capture::Both)
            .context("add hotspot per-port bypass RETURN")?;
        if rc != 0 {
            anyhow::bail!("add {CHAIN} per-port RETURN failed rc={} out={}", rc, out.trim());
        }
    }

    Ok(())
}

fn add_final_redirect(listen_port: u16) -> Result<()> {
    let listen_port_s = listen_port.to_string();
    let args = vec![
        "-t".into(),
        "nat".into(),
        "-A".into(),
        CHAIN.into(),
        "-p".into(),
        "tcp".into(),
        "-j".into(),
        "REDIRECT".into(),
        "--to-ports".into(),
        listen_port_s,
    ];
    let (rc, out) = ipt_runv_timeout(&args, Capture::Both)
        .with_context(|| format!("add {CHAIN} final REDIRECT"))?;
    if rc != 0 {
        anyhow::bail!("add {CHAIN} final REDIRECT failed rc={} out={}", rc, out.trim());
    }
    Ok(())
}
