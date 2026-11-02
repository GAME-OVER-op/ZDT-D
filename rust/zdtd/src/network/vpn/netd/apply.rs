use anyhow::{bail, Result};
use std::fs;

use super::{
    cleanup::{remove_netd_profile, stop_applied},
    model::{AppliedProfile, AppliedSnapshot, VpnNetdProfile},
    ndc::{add_route_universal, create_vpn_network_universal, ndc_ok, set_dns_universal},
    reconcile::{
        check_tun_ready, drop_conflicting_profiles, ensure_endpoint_escape_routes,
        unique_endpoint_escape_ips, validate_profile, verify_post_apply,
    },
    state::{applied_snapshot_path, ensure_working_dir, profiles_tmp_path, write_json_atomic},
    uid::{add_uid_ranges, resolve_uid_ranges, sync_ipv6_block},
};

pub(super) fn apply_one_profile(profile: &VpnNetdProfile, uid_ranges: &[String]) -> Result<AppliedProfile> {
    check_tun_ready(&profile.tun)?;

    create_vpn_network_universal(profile.netid)?;

    let netid_s = profile.netid.to_string();
    ndc_ok(
        vec!["network".into(), "interface".into(), "add".into(), netid_s.clone(), profile.tun.clone()],
        &format!("vpn_netd: interface add netid={} tun={}", profile.netid, profile.tun),
    )?;

    if let Err(e) = add_route_universal(profile.netid, &profile.tun, &profile.cidr, None) {
        log::warn!("vpn_netd: profile {}/{} route {} skipped: {e:#}", profile.owner_program, profile.profile, profile.cidr);
    }

    add_route_universal(profile.netid, &profile.tun, "0.0.0.0/0", profile.gateway.as_deref())?;
    ensure_endpoint_escape_routes(profile);

    if let Err(e) = set_dns_universal(profile.netid, &profile.tun, &profile.dns) {
        log::warn!("vpn_netd: profile {}/{} DNS was not applied: {e:#}", profile.owner_program, profile.profile);
    }

    add_uid_ranges(profile.netid, uid_ranges, &format!("{}/{}", profile.owner_program, profile.profile))?;

    verify_post_apply(profile, uid_ranges);

    Ok(AppliedProfile {
        owner_program: profile.owner_program.clone(),
        profile: profile.profile.clone(),
        netid: profile.netid,
        tun: profile.tun.clone(),
        uid_ranges: uid_ranges.to_vec(),
        endpoint_escape_ips: unique_endpoint_escape_ips(profile),
    })
}

pub fn start_from_registered_programs() -> Result<()> {
    start_profiles(Vec::<VpnNetdProfile>::new())
}

pub fn start_profiles(profiles: Vec<VpnNetdProfile>) -> Result<()> {
    ensure_working_dir()?;
    let requested_count = profiles.len();

    // Rebuild only VPN/netd state created by this builder. This does not touch iptables,
    // ip rules, or any profile configuration owned by future VPN programs.
    stop_applied()?;

    if profiles.is_empty() {
        let _ = fs::remove_file(profiles_tmp_path());
        log::info!("vpn_netd: no profiles registered; nothing to apply");
        return Ok(());
    }

    write_json_atomic(&profiles_tmp_path(), &profiles)?;

    let mut prepared = Vec::new();
    let mut had_error = false;
    for profile in profiles {
        let label = format!("{}/{}", profile.owner_program, profile.profile);
        match (|| -> Result<(VpnNetdProfile, Vec<String>)> {
            validate_profile(&profile)?;
            let ranges = resolve_uid_ranges(&profile.app_list_path, &profile.app_out_path)?;
            Ok((profile, ranges))
        })() {
            Ok(item) => prepared.push(item),
            Err(e) => {
                had_error = true;
                log::warn!("vpn_netd: profile {label} skipped before apply, startup continues: {e:#}");
            }
        }
    }

    if prepared.is_empty() {
        let _ = fs::remove_file(applied_snapshot_path());
        if had_error {
            crate::logging::user_warn("VPN/netd: ошибка применения, запуск продолжен");
            bail!("vpn_netd: no profiles prepared out of {requested_count}");
        }
        return Ok(());
    }

    let (prepared, rejected) = drop_conflicting_profiles(prepared);
    if !rejected.is_empty() {
        had_error = true;
        log::warn!("vpn_netd: conflicting profiles skipped: {}", rejected.join(", "));
        crate::logging::user_warn("VPN/netd: конфликт профилей, конфликтующие профили пропущены");
    }

    if prepared.is_empty() {
        let _ = fs::remove_file(applied_snapshot_path());
        crate::logging::user_warn("VPN/netd: все профили конфликтуют, запуск продолжен");
        bail!("vpn_netd: all {requested_count} prepared profiles were skipped due to conflicts");
    }

    let mut applied = Vec::new();
    for (profile, ranges) in &prepared {
        match apply_one_profile(profile, ranges) {
            Ok(item) => applied.push(item),
            Err(e) => {
                had_error = true;
                log::warn!("vpn_netd: apply failed for {}/{}, startup continues: {e:#}", profile.owner_program, profile.profile);
                // Clean only this failed profile/netid best-effort. Keep already applied profiles.
                remove_netd_profile(&AppliedProfile {
                    owner_program: profile.owner_program.clone(),
                    profile: profile.profile.clone(),
                    netid: profile.netid,
                    tun: profile.tun.clone(),
                    uid_ranges: ranges.clone(),
                    endpoint_escape_ips: unique_endpoint_escape_ips(profile),
                });
            }
        }
    }

    if had_error {
        crate::logging::user_warn("VPN/netd: часть профилей не применена, запуск продолжен");
    }

    if applied.is_empty() {
        let _ = fs::remove_file(applied_snapshot_path());
        bail!("vpn_netd: no prepared profile was applied out of {requested_count}");
    }

    let snapshot = AppliedSnapshot { profiles: applied };
    write_json_atomic(&applied_snapshot_path(), &snapshot)?;
    sync_ipv6_block(&snapshot.profiles);
    log::info!("vpn_netd: applied {} profiles", snapshot.profiles.len());
    Ok(())
}
