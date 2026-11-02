use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::{collections::{BTreeMap, BTreeSet}, fs, path::{Path, PathBuf}};

use crate::{common::apps::parse_package_set, settings};
use super::fs::{read_text_or_empty, write_text_atomic};
use super::super::cache::{collect_assignment_files, invalidate_assignment_cache};
use crate::api::routes::programs::shared::{
    amneziawg_profiles_root, hysteria2_profiles_root, mieru_profiles_root, mihomo_profiles_root,
    myprogram_profiles_root, myproxy_profiles_root, myvpn_profiles_root, openvpn_profiles_root,
    program_root, singbox_lx_profiles_root, singbox_profiles_root, tun2socks_profiles_root,
    wireproxy_profiles_root,
};

#[derive(Debug, Clone, Serialize)]
pub(in crate::api) struct AppAssignmentView {
    pub(in crate::api) program_id: String,
    pub(in crate::api) profile: Option<String>,
    pub(in crate::api) slot: String,
    pub(in crate::api) path: String,
    pub(in crate::api) packages: Vec<String>,
}

#[derive(Debug, Clone)]
pub(in crate::api) struct AppAssignmentFile {
    pub(in crate::api) program_id: String,
    pub(in crate::api) profile: Option<String>,
    pub(in crate::api) slot: String,
    pub(in crate::api) path: String,
    pub(in crate::api) fs_path: PathBuf,
    pub(in crate::api) packages: BTreeSet<String>,
}

#[derive(Debug, Clone, Serialize)]
pub(in crate::api) struct AppConflictView {
    pub(in crate::api) package: String,
    pub(in crate::api) program_id: String,
    pub(in crate::api) profile: Option<String>,
    pub(in crate::api) slot: String,
    pub(in crate::api) path: String,
}

#[derive(Debug, Deserialize)]
pub(in crate::api) struct ProxyInfoSaveReq {
    pub(in crate::api) content: String,
    #[serde(default)]
    pub(in crate::api) remove_conflicts: bool,
}

pub(in crate::api) fn slot_from_kind(kind: &str) -> Option<&'static str> {
    match kind {
        "user" => Some("common"),
        "mobile" => Some("mobile"),
        "wifi" => Some("wifi"),
        _ => None,
    }
}

fn app_domain(program_id: &str) -> Option<&'static str> {
    match program_id {
        // AmneziaWG is an exclusive network/VPN backend. A package routed through it
        // must not be routed through other traffic-handling programs at the same time.
        // Intentional exceptions are the ZDT-D launch marker and blockedquic: the
        // marker is ignored by package conflict parsing, and blockedquic has no app
        // routing domain so QUIC blocking may coexist with VPN/netd routing.
        "vpn-netd" | "openvpn" | "amneziawg" | "tun2socks" | "myvpn" | "mihomo" | "mieru" | "sing-box" | "sing-box-lx" | "wireguard" => Some("exclusive_network"),
        "operaproxy" | "wireproxy" | "myproxy" | "myprogram" | "tor" | "dpitunnel" | "byedpi" | "hysteria2" => Some("tunnel"),
        "nfqws" | "nfqws2" => Some("zapret"),
        // blockedquic only conflicts with proxyInfo protection; it must not block VPN/tunnel app lists.
        _ => None,
    }
}

fn app_domains_conflict(a: &str, b: &str) -> bool {
    if a == b {
        return true;
    }
    a == "exclusive_network" || b == "exclusive_network"
}

fn push_assignment_file(
    out: &mut Vec<AppAssignmentFile>,
    program_id: &str,
    profile: Option<String>,
    kind: &str,
    fs_path: PathBuf,
    path: String,
) {
    let Some(slot) = slot_from_kind(kind) else { return; };
    let packages = read_package_set_or_empty(&fs_path).unwrap_or_default();
    out.push(AppAssignmentFile {
        program_id: program_id.to_string(),
        profile,
        slot: slot.to_string(),
        path,
        fs_path,
        packages,
    });
}

pub(in crate::api) fn collect_assignment_files_uncached() -> Vec<AppAssignmentFile> {
    let mut out = Vec::new();

    for id in ["nfqws", "nfqws2", "byedpi", "dpitunnel"] {
        let root = program_root(id);
        if let Ok(rd) = fs::read_dir(&root) {
            for ent in rd.flatten() {
                let path = ent.path();
                if !path.is_dir() { continue; }
                let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
                match id {
                    "nfqws" | "nfqws2" | "dpitunnel" => {
                        for kind in ["user", "mobile", "wifi"] {
                            let fname = match kind { "user" => "user_program", "mobile" => "mobile_program", "wifi" => "wifi_program", _ => continue };
                            push_assignment_file(
                                &mut out,
                                id,
                                Some(profile.clone()),
                                kind,
                                path.join(format!("app/uid/{fname}")),
                                format!("/api/programs/{id}/profiles/{profile}/apps/{kind}"),
                            );
                        }
                    }
                    "byedpi" => {
                        push_assignment_file(
                            &mut out,
                            id,
                            Some(profile.clone()),
                            "user",
                            path.join("app/uid/user_program"),
                            format!("/api/programs/{id}/profiles/{profile}/apps/user"),
                        );
                    }
                    _ => {}
                }
            }
        }
    }

    for kind in ["user", "mobile", "wifi"] {
        let fname = match kind { "user" => "user_program", "mobile" => "mobile_program", "wifi" => "wifi_program", _ => continue };
        push_assignment_file(
            &mut out,
            "operaproxy",
            None,
            kind,
            program_root("operaproxy").join(format!("app/uid/{fname}")),
            format!("/api/programs/operaproxy/apps/{kind}"),
        );
    }
    push_assignment_file(
        &mut out,
        "tor",
        None,
        "user",
        program_root("tor").join("app/uid/user_program"),
        "/api/programs/tor/apps".to_string(),
    );
    push_assignment_file(
        &mut out,
        "blockedquic",
        None,
        "user",
        settings::blockedquic_uid_program_path(),
        "/api/blockedquic/apps".to_string(),
    );

    let singbox_root = singbox_profiles_root();
    if let Ok(rd) = fs::read_dir(&singbox_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "sing-box",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/sing-box/profiles/{profile}/apps/user"),
            );
        }
    }

    let singbox_lx_root = singbox_lx_profiles_root();
    if let Ok(rd) = fs::read_dir(&singbox_lx_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "sing-box-lx",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/sing-box-lx/profiles/{profile}/apps/user"),
            );
        }
    }

    let hysteria2_root = hysteria2_profiles_root();
    if let Ok(rd) = fs::read_dir(&hysteria2_root) {
        for ent in rd.flatten() {
            let path = ent.path(); if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(&mut out, "hysteria2", Some(profile.clone()), "user", path.join("app/uid/user_program"), format!("/api/programs/hysteria2/profiles/{profile}/apps/user"));
        }
    }

    let wireproxy_root = wireproxy_profiles_root();
    if let Ok(rd) = fs::read_dir(&wireproxy_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "wireproxy",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/wireproxy/profiles/{profile}/apps/user"),
            );
        }
    }

    let myproxy_root = myproxy_profiles_root();
    if let Ok(rd) = fs::read_dir(&myproxy_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "myproxy",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/myproxy/profiles/{profile}/apps/user"),
            );
        }
    }

    let myprogram_root = myprogram_profiles_root();
    if let Ok(rd) = fs::read_dir(&myprogram_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "myprogram",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/myprogram/profiles/{profile}/apps/user"),
            );
        }
    }

    let openvpn_root = openvpn_profiles_root();
    if let Ok(rd) = fs::read_dir(&openvpn_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "openvpn",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/openvpn/profiles/{profile}/apps/user"),
            );
        }
    }

    let amneziawg_root = amneziawg_profiles_root();
    if let Ok(rd) = fs::read_dir(&amneziawg_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "amneziawg",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/amneziawg/profiles/{profile}/apps/user"),
            );
        }
    }

    let tun2socks_root = tun2socks_profiles_root();
    if let Ok(rd) = fs::read_dir(&tun2socks_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "tun2socks",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/tun2socks/profiles/{profile}/apps/user"),
            );
        }
    }

    let myvpn_root = myvpn_profiles_root();
    if let Ok(rd) = fs::read_dir(&myvpn_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "myvpn",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/myvpn/profiles/{profile}/apps/user"),
            );
        }
    }

    let mihomo_root = mihomo_profiles_root();
    if let Ok(rd) = fs::read_dir(&mihomo_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "mihomo",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/mihomo/profiles/{profile}/apps/user"),
            );
        }
    }

    let mieru_root = mieru_profiles_root();
    if let Ok(rd) = fs::read_dir(&mieru_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "mieru",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/mieru/profiles/{profile}/apps/user"),
            );
        }
    }

    out
}

pub(in crate::api) fn assignment_to_view(v: &AppAssignmentFile) -> AppAssignmentView {
    AppAssignmentView {
        program_id: v.program_id.clone(),
        profile: v.profile.clone(),
        slot: v.slot.clone(),
        path: v.path.clone(),
        packages: v.packages.iter().cloned().collect(),
    }
}

fn conflict_label(v: &AppConflictView) -> String {
    match &v.profile {
        Some(profile) if !profile.is_empty() => format!("{} / {} / {}", v.program_id, profile, v.slot),
        _ => format!("{} / {}", v.program_id, v.slot),
    }
}

fn packages_to_text(pkgs: &BTreeSet<String>) -> String {
    pkgs.iter().cloned().collect::<Vec<_>>().join("\n")
}

pub(in crate::api) fn flatten_conflicts(map: &BTreeMap<String, Vec<AppConflictView>>) -> Vec<AppConflictView> {
    let mut out = Vec::new();
    for items in map.values() {
        out.extend(items.iter().cloned());
    }
    out
}

fn find_program_conflicts(
    candidate: &BTreeSet<String>,
    current_api_path: &str,
    program_id: &str,
    slot: &str,
) -> BTreeMap<String, Vec<AppConflictView>> {
    let mut out: BTreeMap<String, Vec<AppConflictView>> = BTreeMap::new();
    let Some(domain) = app_domain(program_id) else { return out; };
    let lists = collect_assignment_files();
    let current_existing = lists
        .iter()
        .find(|v| v.path == current_api_path)
        .map(|v| v.packages.clone())
        .unwrap_or_default();
    for item in lists {
        if item.path == current_api_path { continue; }
        let Some(item_domain) = app_domain(&item.program_id) else { continue; };
        let hysteria2_pair = program_id == "hysteria2" || item.program_id == "hysteria2";
        if hysteria2_pair && (domain == "zapret" || item_domain == "zapret") { continue; }
        let cross_slot_conflict = hysteria2_pair;
        if !cross_slot_conflict && item.slot != slot { continue; }
        let domains_conflict = if hysteria2_pair { true } else { app_domains_conflict(domain, item_domain) };
        if !domains_conflict { continue; }
        for pkg in candidate.intersection(&item.packages) {
            if current_existing.contains(pkg) { continue; }
            out.entry(pkg.clone()).or_default().push(AppConflictView {
                package: pkg.clone(),
                program_id: item.program_id.clone(),
                profile: item.profile.clone(),
                slot: item.slot.clone(),
                path: item.path.clone(),
            });
        }
    }
    out
}

pub(in crate::api) fn find_proxyinfo_conflicts(candidate: &BTreeSet<String>) -> BTreeMap<String, Vec<AppConflictView>> {
    let mut out: BTreeMap<String, Vec<AppConflictView>> = BTreeMap::new();
    for item in collect_assignment_files() {
        for pkg in candidate.intersection(&item.packages) {
            out.entry(pkg.clone()).or_default().push(AppConflictView {
                package: pkg.clone(),
                program_id: item.program_id.clone(),
                profile: item.profile.clone(),
                slot: item.slot.clone(),
                path: item.path.clone(),
            });
        }
    }
    out
}

fn launch_marker_allowed_for_program(program_id: &str) -> bool {
    !matches!(program_id, "blockedquic" | "proxyInfo" | "proxyinfo")
}

pub(in crate::api) fn validate_launch_marker_usage(content: &str, program_id: &str) -> Result<()> {
    if crate::common::apps::content_has_launch_marker(content) && !launch_marker_allowed_for_program(program_id) {
        anyhow::bail!(
            "ZDT-D launch marker {} is not allowed for this program",
            crate::common::apps::LAUNCH_MARKER_PACKAGE
        );
    }
    Ok(())
}

pub(in crate::api) fn validate_program_apps_content(content: &str, current_api_path: &str, program_id: &str, slot: &str) -> Result<()> {
    validate_launch_marker_usage(content, program_id)?;
    let candidate = parse_package_set(content);
    if candidate.is_empty() { return Ok(()); }

    let blocked = crate::features::proxyinfo::read_proxy_packages().unwrap_or_default();
    let mut overlap: Vec<String> = candidate.intersection(&blocked).cloned().collect();
    overlap.sort();
    if !overlap.is_empty() {
        anyhow::bail!("apps are blocked by proxyInfo: {}", overlap.join(", "));
    }

    let conflicts = find_program_conflicts(&candidate, current_api_path, program_id, slot);
    if !conflicts.is_empty() {
        let mut parts = Vec::new();
        for (pkg, uses) in &conflicts {
            let labels = uses.iter().map(conflict_label).collect::<Vec<_>>().join(", ");
            parts.push(format!("{pkg} ({labels})"));
        }
        parts.sort();
        anyhow::bail!("apps are already used in conflicting program lists: {}", parts.join("; "));
    }
    Ok(())
}

pub(in crate::api) fn validate_proxyinfo_apps_content(content: &str) -> Result<()> {
    validate_launch_marker_usage(content, "proxyInfo")?;
    let candidate = parse_package_set(content);
    if candidate.is_empty() {
        return Ok(());
    }

    let conflicts = find_proxyinfo_conflicts(&candidate);
    if !conflicts.is_empty() {
        let mut parts = Vec::new();
        for (pkg, uses) in &conflicts {
            let labels = uses.iter().map(conflict_label).collect::<Vec<_>>().join(", ");
            parts.push(format!("{pkg} ({labels})"));
        }
        parts.sort();
        anyhow::bail!("apps are already used by ZDT-D programs: {}", parts.join("; "));
    }
    Ok(())
}

pub(in crate::api) fn remove_packages_from_program_lists(packages: &BTreeSet<String>) -> Result<Vec<AppConflictView>> {
    let mut removed = Vec::new();
    if packages.is_empty() { return Ok(removed); }
    for item in collect_assignment_files() {
        let intersection: BTreeSet<String> = item.packages.intersection(packages).cloned().collect();
        if intersection.is_empty() { continue; }
        let updated: BTreeSet<String> = item.packages.difference(packages).cloned().collect();
        write_text_atomic(&item.fs_path, &packages_to_text(&updated))?;
        for pkg in intersection {
            removed.push(AppConflictView {
                package: pkg,
                program_id: item.program_id.clone(),
                profile: item.profile.clone(),
                slot: item.slot.clone(),
                path: item.path.clone(),
            });
        }
    }
    if !removed.is_empty() {
        invalidate_assignment_cache();
    }
    Ok(removed)
}

fn read_package_set_or_empty(p: &Path) -> Result<BTreeSet<String>> {
    Ok(parse_package_set(&read_text_or_empty(p)?))
}

