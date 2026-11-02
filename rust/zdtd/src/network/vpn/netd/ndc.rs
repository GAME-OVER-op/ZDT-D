use anyhow::{bail, Result};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::common::process::shell::{self, Capture};

use super::{
    state::{ensure_working_dir, last_ndc_out_path, ndc_history_path},
    NDC_TIMEOUT,
};

pub(super) fn trim_ndc_output(out: &str) -> String {
    out.replace('\r', "").trim().to_string()
}

pub(super) fn ndc_command_for_log(args: &[String]) -> String {
    format!("ndc {}", args.join(" "))
}

pub(super) fn remember_ndc_output(args: &[String], code: i32, out: &str) {
    if ensure_working_dir().is_ok() {
        let cmd = ndc_command_for_log(args);
        let trimmed = trim_ndc_output(out);
        let last = if trimmed.is_empty() {
            format!("cmd={cmd}\nrc={code}\nout=<empty>\n")
        } else {
            format!("cmd={cmd}\nrc={code}\nout={trimmed}\n")
        };
        let _ = fs::write(last_ndc_out_path(), &last);

        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(ndc_history_path()) {
            let ts = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or(Duration::from_secs(0))
                .as_secs();
            let _ = writeln!(f, "[{ts}] {cmd}");
            let _ = writeln!(f, "rc={code}");
            if trimmed.is_empty() {
                let _ = writeln!(f, "out=<empty>");
            } else {
                let _ = writeln!(f, "out={trimmed}");
            }
            let _ = writeln!(f);
        }
    }
}

pub(super) fn ndc_capture(args: &[String]) -> Result<(i32, String)> {
    let refs = args.iter().map(String::as_str).collect::<Vec<_>>();
    let (code, out) = shell::run_timeout("ndc", &refs, Capture::Both, NDC_TIMEOUT)?;
    remember_ndc_output(args, code, &out);
    Ok((code, trim_ndc_output(&out)))
}

pub(super) fn ndc_failure_reason(code: i32, out: &str) -> Option<String> {
    if code != 0 {
        return Some(format!("rc={code}"));
    }

    for line in out.lines() {
        let line = line.trim();
        let Some(first) = line.split_whitespace().next() else { continue; };
        if let Ok(status) = first.parse::<i32>() {
            if (400..=599).contains(&status) {
                return Some(line.to_string());
            }
        }
    }

    let lower = out.to_ascii_lowercase();
    for needle in [
        "unknown argument",
        "command not recognized",
        "permission denied",
        "invalid argument",
        "operation not permitted",
        "failed",
        "failure",
    ] {
        if lower.contains(needle) {
            return Some(needle.to_string());
        }
    }

    None
}

pub(super) fn ndc_has_success_status(out: &str) -> bool {
    out.lines().any(|line| {
        let line = line.trim();
        let Some(first) = line.split_whitespace().next() else { return false; };
        first
            .parse::<i32>()
            .map(|status| (200..=299).contains(&status))
            .unwrap_or(false)
    })
}

pub(super) fn ndc_is_ok(code: i32, out: &str) -> bool {
    if ndc_failure_reason(code, out).is_some() {
        return false;
    }
    if code != 0 {
        return false;
    }
    if out.trim().is_empty() {
        return true;
    }
    ndc_has_success_status(out)
}

pub(super) fn ndc_ok(args: Vec<String>, what: &str) -> Result<()> {
    let (code, out) = ndc_capture(&args)?;
    if ndc_is_ok(code, &out) {
        return Ok(());
    }
    let reason = ndc_failure_reason(code, &out).unwrap_or_else(|| "unknown ndc failure".to_string());
    bail!("{what} failed ({reason}) rc={code} out={out}");
}

fn ndc_cleanup_already_absent(out: &str) -> bool {
    out.to_ascii_lowercase().contains("machine is not on the network")
}

pub(super) fn ndc_quiet(args: Vec<String>) {
    match ndc_capture(&args) {
        Ok((code, out)) if !ndc_is_ok(code, &out) => {
            if ndc_cleanup_already_absent(&out) {
                log::debug!(
                    "vpn_netd: cleanup target already absent: {} rc={} out={}",
                    ndc_command_for_log(&args),
                    code,
                    out
                );
                return;
            }
            log::warn!(
                "vpn_netd: cleanup ndc command failed: {} rc={} out={}",
                ndc_command_for_log(&args),
                code,
                out
            );
        }
        _ => {}
    }
}

pub(super) fn create_vpn_network_universal(netid: u32) -> Result<()> {
    let netid_s = netid.to_string();
    let modern = vec!["network", "create", &netid_s, "vpn", "1"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code1, out1) = ndc_capture(&modern)?;
    if ndc_is_ok(code1, &out1) {
        log::info!("vpn_netd: network create netid={netid} mode=modern");
        return Ok(());
    }

    // Legacy netd syntax is: network create <netId> vpn <hasDns> <secure>.
    // Keep the VPN secure first; older builds that reject this form can still fall
    // through to the historical compatibility attempt below.
    let legacy_secure = vec!["network", "create", &netid_s, "vpn", "1", "1"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code2, out2) = ndc_capture(&legacy_secure)?;
    if ndc_is_ok(code2, &out2) {
        log::info!("vpn_netd: network create netid={netid} mode=legacy-secure");
        return Ok(());
    }

    // Some older Android/netd builds use: network create <netId> vpn
    // and reject any trailing secure/hasDns arguments. Try this before the
    // insecure compatibility form so devices with the no-arg syntax do not fall
    // through to a less safe legacy mode.
    let legacy_noargs = vec!["network", "create", &netid_s, "vpn"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code3, out3) = ndc_capture(&legacy_noargs)?;
    if ndc_is_ok(code3, &out3) {
        log::info!("vpn_netd: network create netid={netid} mode=legacy-noargs");
        return Ok(());
    }

    // Last-resort compatibility with the previous ZDT-D behavior. This may allow
    // bypass on legacy Android, so log it loudly and only use it if secure forms
    // and the historical no-argument form fail.
    let legacy_compat = vec!["network", "create", &netid_s, "vpn", "1", "0"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code4, out4) = ndc_capture(&legacy_compat)?;
    if ndc_is_ok(code4, &out4) {
        log::warn!("vpn_netd: network create netid={netid} mode=legacy-compat-insecure");
        return Ok(());
    }

    bail!(
        "vpn_netd: create netd vpn network netid={netid} failed; modern rc={code1} out={out1}; legacy-secure rc={code2} out={out2}; legacy-noargs rc={code3} out={out3}; legacy-compat rc={code4} out={out4}"
    );
}

pub(super) fn add_route_universal(netid: u32, tun: &str, dest: &str, gateway: Option<&str>) -> Result<()> {
    let netid_s = netid.to_string();
    if let Some(gw) = gateway {
        let args = vec!["network", "route", "add", &netid_s, tun, dest, gw]
            .into_iter()
            .map(str::to_string)
            .collect::<Vec<_>>();
        let (code, out) = ndc_capture(&args)?;
        if ndc_is_ok(code, &out) {
            return Ok(());
        }
        log::warn!("vpn_netd: route with gateway failed netid={netid} dest={dest} gw={gw}: rc={code} out={out}");
    }

    let args = vec!["network", "route", "add", &netid_s, tun, dest]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code, out) = ndc_capture(&args)?;
    if ndc_is_ok(code, &out) {
        return Ok(());
    }
    bail!("vpn_netd: route add failed netid={netid} dest={dest}: rc={code} out={out}");
}

pub(super) fn set_dns_universal(netid: u32, tun: &str, dns: &[String]) -> Result<()> {
    let netid_s = netid.to_string();
    let mut setnetdns = vec!["resolver".to_string(), "setnetdns".to_string(), netid_s, String::new()];
    setnetdns.extend(dns.iter().cloned());
    let (code1, out1) = ndc_capture(&setnetdns)?;
    if ndc_is_ok(code1, &out1) {
        log::info!("vpn_netd: resolver setnetdns applied netid={netid}");
        return Ok(());
    }

    // Older netd builds used interface DNS configuration before setnetdns.
    // DNS remains warning-only for vpn_netd, but trying setifdns improves
    // compatibility on these devices and records the result in ndc_history.log.
    let mut setifdns = vec!["resolver".to_string(), "setifdns".to_string(), tun.to_string(), String::new()];
    setifdns.extend(dns.iter().cloned());
    let (code2, out2) = ndc_capture(&setifdns)?;
    if ndc_is_ok(code2, &out2) {
        log::info!("vpn_netd: resolver setifdns applied tun={tun} netid={netid}");
        return Ok(());
    }

    bail!("vpn_netd: resolver DNS setup failed; setnetdns rc={code1} out={out1}; setifdns rc={code2} out={out2}");
}
