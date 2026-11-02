use anyhow::Result;
use std::fs;

use crate::common::process::shell::{self, Capture};

use super::{
    model::{AppliedProfile, AppliedSnapshot},
    ndc::ndc_quiet,
    reconcile::remove_endpoint_escape_routes,
    state::{applied_snapshot_path, cleanup_runtime_files, ensure_working_dir, snapshot_text_is_empty_or_nul},
    uid::{remove_uid_ranges, sync_ipv6_block},
    IP_TIMEOUT,
};

pub(super) fn remove_netd_profile(applied: &AppliedProfile) {
    remove_endpoint_escape_routes(applied);
    remove_uid_ranges(applied.netid, &applied.uid_ranges);
    let netid_s = applied.netid.to_string();
    ndc_quiet(vec!["network".into(), "interface".into(), "remove".into(), netid_s.clone(), applied.tun.clone()]);
    ndc_quiet(vec!["network".into(), "destroy".into(), netid_s]);
    let _ = shell::run_timeout("ip", &["route", "flush", "cache"], Capture::None, IP_TIMEOUT);
}


pub fn stop_applied() -> Result<()> {
    ensure_working_dir()?;
    // Снимаем временный IPv6-запрет до разбора снимка, чтобы правила уходили
    // даже когда снимок битый или отсутствует.
    sync_ipv6_block(&[]);
    let path = applied_snapshot_path();
    if !path.is_file() {
        cleanup_runtime_files();
        return Ok(());
    }

    let text = match fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) => {
            log::warn!(
                "vpn_netd: stale applied snapshot is unreadable ({}), removing runtime files and continuing fresh apply: {e}",
                path.display()
            );
            cleanup_runtime_files();
            return Ok(());
        }
    };

    if snapshot_text_is_empty_or_nul(&text) {
        log::warn!(
            "vpn_netd: stale applied snapshot is empty/NUL-filled ({}), removing runtime files and continuing fresh apply",
            path.display()
        );
        cleanup_runtime_files();
        return Ok(());
    }

    let snapshot: AppliedSnapshot = match serde_json::from_str(&text) {
        Ok(snapshot) => snapshot,
        Err(e) => {
            log::warn!(
                "vpn_netd: stale applied snapshot is corrupted ({}), removing runtime files and continuing fresh apply: {e}",
                path.display()
            );
            cleanup_runtime_files();
            return Ok(());
        }
    };

    if !snapshot.profiles.is_empty() {
        log::info!("vpn_netd: cleanup {} applied profile(s)", snapshot.profiles.len());
    }
    for item in snapshot.profiles.iter().rev() {
        log::info!(
            "vpn_netd: removing applied profile {}/{} netid={} tun={}",
            item.owner_program,
            item.profile,
            item.netid,
            item.tun
        );
        remove_netd_profile(item);
    }

    cleanup_runtime_files();
    Ok(())
}
