use anyhow::{Context, Result};
use crate::common::process::{filter_live_pids, pgrep_full_regex, ps_ef_pids_where, any_live_ps_ef_where};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use std::{fs::{self, OpenOptions}, os::unix::process::CommandExt, path::{Path, PathBuf}, process::{Command, Stdio}, thread, time::{Duration}};


use super::model::*;

pub(super) fn spawn_openvpn(plan: &ProfilePlan) -> Result<()> {
    if openvpn_profile_process_running(plan) {
        info!(
            "openvpn: profile={} already running, skip spawn",
            plan.name,
        );
        return Ok(());
    }

    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.log_path)
        .with_context(|| format!("open log {}", plan.log_path.display()))?;
    let logf_err = logf.try_clone().context("clone openvpn log")?;

    let mut cmd = Command::new(OPENVPN_BIN);
    cmd.arg("--config")
        .arg(&plan.runtime_config_path)
        .current_dir(&plan.profile_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {OPENVPN_BIN}"))?;
    info!(
        "openvpn: spawned profile={} pid={} config={} log={}",
        plan.name,
        child.id(),
        plan.runtime_config_path.display(),
        plan.log_path.display()
    );

    thread::sleep(Duration::from_millis(250));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("openvpn: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.log_path.display());
    }
    Ok(())
}

pub(super) fn openvpn_profile_process_running(plan: &ProfilePlan) -> bool {
    let mut paths = vec![plan.config_path.as_path(), plan.runtime_config_path.as_path()];
    paths.sort();
    paths.dedup();
    let patterns = paths
        .into_iter()
        .map(|config_path| format!("{} --config {}", OPENVPN_BIN, config_path.display()))
        .collect::<Vec<_>>();
    any_live_ps_ef_where(|line| patterns.iter().any(|pattern| line.contains(pattern)))
}

pub(super) fn write_text_atomic(path: &Path, content: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, content).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path)
        .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

pub fn main_pids_exact() -> Vec<i32> {
    let patterns = [
        r#"^/data/adb/modules/ZDT-D/bin/openvpn --config /data/adb/modules/ZDT-D/working_folder/openvpn/profile/.*/client\.ovpn$"#,
        r#"^/data/adb/modules/ZDT-D/bin/openvpn --config /data/adb/modules/ZDT-D/working_folder/openvpn/profile/.*/tmp/client\.resolved\.ovpn$"#,
    ];
    let mut pids = Vec::new();
    for pattern in patterns {
        pids.extend(pgrep_full_regex(pattern));
    }
    if pids.is_empty() {
        const PREFIX: &str = "/data/adb/modules/ZDT-D/bin/openvpn --config /data/adb/modules/ZDT-D/working_folder/openvpn/profile/";
        pids = ps_ef_pids_where(|line| {
            let line = line.trim_end();
            line.contains(PREFIX)
                && (line.ends_with("/client.ovpn") || line.ends_with("/tmp/client.resolved.ovpn"))
        });
    }
    filter_live_pids(&mut pids);
    pids
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn old_setting_defaults_endpoint_resolution_on() {
        let setting = normalize_setting_value(serde_json::json!({
            "tun": "tun1",
            "dns": ["94.140.14.14", "94.140.15.15"]
        }))
        .unwrap();
        assert!(setting.endpoint_resolve);
    }

    #[test]
    fn remote_domain_is_left_unchanged_when_resolution_is_disabled() {
        let raw = "client\nremote example.com 1194 udp # main\n";
        let (runtime, ips, skipped, resolved_domain) = resolve_remote_lines(raw, false).unwrap();
        assert_eq!(runtime, raw);
        assert!(ips.is_empty());
        assert!(skipped.contains("example.com"));
        assert!(!resolved_domain);
    }

    #[test]
    fn literal_remote_ip_is_preserved_and_collected() {
        let raw = "client\nremote 203.0.113.7 1194 udp\n";
        let (runtime, ips, skipped, resolved_domain) = resolve_remote_lines(raw, true).unwrap();
        assert_eq!(runtime, raw);
        assert_eq!(ips, vec!["203.0.113.7"]);
        assert!(skipped.is_empty());
        assert!(!resolved_domain);
    }

    #[test]
    fn inline_comment_split_respects_quotes() {
        let (code, comment) = split_openvpn_inline_comment("remote \"example.com\" 1194 # primary");
        assert_eq!(code, "remote \"example.com\" 1194");
        assert_eq!(comment, "# primary");
    }
}
