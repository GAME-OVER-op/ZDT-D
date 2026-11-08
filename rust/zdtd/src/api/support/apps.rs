use anyhow::{Context, Result};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
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

pub(in crate::api) fn validate_program_apps_content(content: &str, _current_api_path: &str, program_id: &str, _slot: &str) -> Result<()> {
    validate_launch_marker_usage(content, program_id)?;
    let candidate = parse_package_set(content);
    if candidate.is_empty() { return Ok(()); }

    let blocked = crate::features::proxyinfo::read_proxy_packages().unwrap_or_default();
    let mut overlap: Vec<String> = candidate.intersection(&blocked).cloned().collect();
    overlap.sort();
    if !overlap.is_empty() {
        anyhow::bail!("apps are blocked by proxyInfo: {}", overlap.join(", "));
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

// --- global enabled app-list rule ---------------------------------------
//
// One application may be assigned to as many profiles and programs as the
// user likes, but two *different* enabled lists must never claim it at the
// same time, because then the daemon would drive the same traffic twice.

#[derive(Debug, Clone, Default)]
struct AppListSlots {
    common: BTreeSet<String>,
    mobile: BTreeSet<String>,
    wifi: BTreeSet<String>,
}

impl AppListSlots {
    fn is_empty(&self) -> bool {
        self.common.is_empty() && self.mobile.is_empty() && self.wifi.is_empty()
    }

    fn extend_overlap(&self, other: &Self, out: &mut BTreeSet<String>) {
        for (left, right) in [
            (&self.common, &other.common),
            (&self.common, &other.mobile),
            (&self.common, &other.wifi),
            (&self.mobile, &other.common),
            (&self.wifi, &other.common),
            (&self.mobile, &other.mobile),
            (&self.wifi, &other.wifi),
        ] {
            out.extend(left.intersection(right).cloned());
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct EnabledAppList {
    pub(crate) program_id: String,
    /// Empty for single-instance programs (tor, operaproxy), else the profile name.
    pub(crate) profile: String,
    slots: AppListSlots,
}

impl EnabledAppList {
    pub(crate) fn label(&self) -> String {
        if self.profile.is_empty() {
            self.program_id.clone()
        } else {
            format!("{}/{}", self.program_id, self.profile)
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct EnabledAppOverlap {
    pub(crate) left: EnabledAppList,
    pub(crate) right: EnabledAppList,
    pub(crate) packages: Vec<String>,
}

pub(crate) fn format_enabled_app_overlaps(overlaps: &[EnabledAppOverlap]) -> String {
    overlaps
        .iter()
        .map(|item| format!("{} ↔ {}: {}", item.left.label(), item.right.label(), item.packages.join(", ")))
        .collect::<Vec<_>>()
        .join("; ")
}

fn metadata_if_present(path: &Path) -> Result<Option<fs::Metadata>> {
    match fs::metadata(path) {
        Ok(metadata) => Ok(Some(metadata)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => match fs::symlink_metadata(path) {
            Ok(_) => anyhow::bail!("metadata failed {}: dangling symlink or entry changed during validation", path.display()),
            Err(link_error) if link_error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(link_error) => Err(link_error).with_context(|| format!("read link metadata {}", path.display())),
        },
        Err(e) => Err(e).with_context(|| format!("read metadata {}", path.display())),
    }
}

fn read_json_if_present<T: DeserializeOwned>(path: &Path) -> Result<Option<T>> {
    if metadata_if_present(path)?.is_none() {
        return Ok(None);
    }
    super::fs::read_json(path).map(Some)
}

fn read_text_if_present(path: &Path) -> Result<String> {
    if metadata_if_present(path)?.is_none() {
        return Ok(String::new());
    }
    super::fs::read_text(path)
}

/// Collect enabled lists from an arbitrary `working_folder` scratch tree.
fn collect_enabled_app_lists_under(root: &Path) -> Result<Vec<EnabledAppList>> {
    let mut out = Vec::new();
    let entries = match std::fs::read_dir(root) {
        Ok(entries) => entries,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(out),
        Err(e) => return Err(e).with_context(|| format!("read directory {}", root.display())),
    };
    for program in entries {
        let program = program.with_context(|| format!("read directory entry in {}", root.display()))?;
        let program_id = program.file_name().to_string_lossy().to_string();
        let program_root = program.path();
        if !metadata_if_present(&program_root)?.is_some_and(|metadata| metadata.is_dir()) || program_id.starts_with('.') {
            continue;
        }
        collect_program_app_lists(&program_id, &program_root, &mut out)?;
    }
    out.sort_by(|a, b| a.label().cmp(&b.label()));
    Ok(out)
}

fn read_app_list_dir(uid_dir: &Path, interface_slots: bool) -> Result<AppListSlots> {
    let mut slots = AppListSlots::default();
    for (file, packages) in [
        ("user_program", &mut slots.common),
        ("mobile_program", &mut slots.mobile),
        ("wifi_program", &mut slots.wifi),
    ] {
        if !interface_slots && file != "user_program" {
            continue;
        }
        let content = read_text_if_present(&uid_dir.join(file))?;
        packages.extend(parse_package_set(&content));
    }
    Ok(slots)
}

fn collect_program_app_lists(program_id: &str, root: &Path, out: &mut Vec<EnabledAppList>) -> Result<()> {
    // Only these programs write per-interface app lists; a stale mobile/wifi
    // file under any other program never reaches the runtime.
    let interface_slots = matches!(program_id, "nfqws" | "nfqws2" | "dpitunnel" | "operaproxy");
    let active_path = root.join("active.json");
    let (active, mut single_enabled) = match read_json_if_present::<serde_json::Value>(&active_path)? {
        Some(value) if value.get("profiles").is_some() => {
            let active = serde_json::from_value::<super::model::ProfilesActive>(value)
                .with_context(|| format!("bad JSON {}", active_path.display()))?;
            (Some(active), false)
        }
        Some(value) => {
            let active = serde_json::from_value::<super::model::EnabledActive>(value)
                .with_context(|| format!("bad JSON {}", active_path.display()))?;
            (None, active.enabled)
        }
        None => (None, false),
    };
    let enabled = |name: &str| -> bool {
        active.as_ref().is_some_and(|a| a.profiles.get(name).is_some_and(|st| st.enabled))
    };

    // Single-instance program: the whole program is one list, enabled through
    // its own flag file. Both names in use on disk are accepted so that no
    // program id has to be named here.
    if !single_enabled {
        single_enabled = read_json_if_present::<super::model::EnabledActive>(&root.join("enabled.json"))?
            .is_some_and(|v| v.enabled);
    }
    if single_enabled {
        let uid_dir = root.join("app/uid");
        let slots = if metadata_if_present(&uid_dir)?.is_some_and(|metadata| metadata.is_dir()) {
            read_app_list_dir(&uid_dir, interface_slots)?
        } else {
            AppListSlots::default()
        };
        if !slots.is_empty() {
            out.push(EnabledAppList {
                program_id: program_id.to_string(),
                profile: String::new(),
                slots,
            });
        }
    }

    // Profile based: `<program>/<profile>/app/uid` (nfqws, nfqws2, dpitunnel)
    // and `<program>/profile/<profile>/app/uid` (the rest). Both are walked.
    for container in [root.to_path_buf(), root.join("profile")] {
        if !metadata_if_present(&container)?.is_some_and(|metadata| metadata.is_dir()) {
            continue;
        }
        let profiles = match std::fs::read_dir(&container) {
            Ok(profiles) => profiles,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e).with_context(|| format!("read directory {}", container.display())),
        };
        for entry in profiles {
            let entry = entry.with_context(|| format!("read directory entry in {}", container.display()))?;
            let profile = entry.file_name().to_string_lossy().to_string();
            if profile.starts_with('.') {
                continue;
            }
            let profile_dir = entry.path();
            if !metadata_if_present(&profile_dir)?.is_some_and(|metadata| metadata.is_dir()) {
                continue;
            }
            let uid_dir = profile_dir.join("app/uid");
            if !metadata_if_present(&uid_dir)?.is_some_and(|metadata| metadata.is_dir()) || !enabled(&profile) {
                continue;
            }
            if program_id == "myprogram" {
                let setting = read_json_if_present::<crate::programs::myprogram::ProfileSetting>(
                    &profile_dir.join("setting.json"),
                )?;
                if !setting.is_some_and(|value| value.apps_mode) {
                    continue;
                }
            }
            let slots = read_app_list_dir(&uid_dir, interface_slots)?;
            if slots.is_empty() {
                continue;
            }
            out.push(EnabledAppList {
                program_id: program_id.to_string(),
                profile,
                slots,
            });
        }
    }
    Ok(())
}

/// Find every pair of distinct enabled profiles that claims the same package
/// in a compatible routing slot. Re-enabling one list never compares it to itself.
pub(crate) fn find_enabled_app_overlaps() -> Result<Vec<EnabledAppOverlap>> {
    find_enabled_app_overlaps_under(&crate::programs::registry::working_root())
}

/// [`find_enabled_app_overlaps`] against an arbitrary `working_folder`, so the
/// rule can be exercised on a scratch tree.
fn find_enabled_app_overlaps_under(root: &Path) -> Result<Vec<EnabledAppOverlap>> {
    let lists = collect_enabled_app_lists_under(root)?;
    let mut out = Vec::new();
    for i in 0..lists.len() {
        for j in (i + 1)..lists.len() {
            let mut packages = BTreeSet::new();
            lists[i].slots.extend_overlap(&lists[j].slots, &mut packages);
            if !packages.is_empty() {
                out.push(EnabledAppOverlap {
                    left: lists[i].clone(),
                    right: lists[j].clone(),
                    packages: packages.into_iter().collect(),
                });
            }
        }
    }
    Ok(out)
}


#[cfg(test)]
mod app_list_rule_tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_ID: AtomicU64 = AtomicU64::new(0);

    struct Scratch(PathBuf);

    impl Scratch {
        fn new(name: &str) -> Self {
            let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "zdtd-app-list-rule-{name}-{}-{id}",
                std::process::id(),
            ));
            let _ = fs::remove_dir_all(&path);
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }

        fn put(&self, relative: &str, content: &str) {
            let path = self.0.join(relative);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, content).unwrap();
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn malformed_active_json_is_an_error() {
        let scratch = Scratch::new("malformed-active");
        scratch.put("nfqws2/active.json", "not json");
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");

        assert!(find_enabled_app_overlaps_under(&scratch.0).is_err());
    }

    #[test]
    fn mobile_and_wifi_lists_do_not_overlap() {
        let scratch = Scratch::new("split-interfaces");
        scratch.put(
            "nfqws2/active.json",
            r#"{"profiles":{"one":{"enabled":true},"two":{"enabled":true}}}"#,
        );
        scratch.put("nfqws2/one/app/uid/mobile_program", "com.example.app\n");
        scratch.put("nfqws2/two/app/uid/wifi_program", "com.example.app\n");

        let overlaps = find_enabled_app_overlaps_under(&scratch.0).unwrap();
        assert!(overlaps.is_empty(), "mobile and Wi-Fi routes use different interfaces: {overlaps:?}");
    }

    #[test]
    fn myprogram_without_apps_mode_does_not_claim_packages() {
        let scratch = Scratch::new("myprogram-disabled-apps");
        scratch.put("myprogram/active.json", r#"{"profiles":{"custom":{"enabled":true}}}"#);
        scratch.put("myprogram/profile/custom/setting.json", r#"{"apps_mode":false}"#);
        scratch.put("myprogram/profile/custom/app/uid/user_program", "com.example.app\n");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");

        let overlaps = find_enabled_app_overlaps_under(&scratch.0).unwrap();
        assert!(overlaps.is_empty(), "apps_mode=false does not install UID routing: {overlaps:?}");
    }

    #[test]
    fn reports_every_pair_and_shared_package() {
        let scratch = Scratch::new("conflict-details");
        scratch.put(
            "nfqws2/active.json",
            r#"{"profiles":{"one":{"enabled":true},"two":{"enabled":true}}}"#,
        );
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");
        scratch.put("nfqws2/two/app/uid/user_program", "com.example.app\n");
        scratch.put("mihomo/active.json", r#"{"profiles":{"vpn":{"enabled":true}}}"#);
        scratch.put("mihomo/profile/vpn/app/uid/user_program", "com.example.app\n");

        let overlaps = find_enabled_app_overlaps_under(&scratch.0).unwrap();
        assert_eq!(overlaps.len(), 3);
        assert!(overlaps.iter().all(|item| item.packages == ["com.example.app"]));
        assert_eq!(format_enabled_app_overlaps(&overlaps), "mihomo/vpn ↔ nfqws2/one: com.example.app; mihomo/vpn ↔ nfqws2/two: com.example.app; nfqws2/one ↔ nfqws2/two: com.example.app");
    }

    #[test]
    fn disabled_duplicate_lists_are_allowed() {
        let scratch = Scratch::new("disabled-duplicate");
        scratch.put(
            "nfqws2/active.json",
            r#"{"profiles":{"one":{"enabled":true},"two":{"enabled":false}}}"#,
        );
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");
        scratch.put("nfqws2/two/app/uid/user_program", "com.example.app\n");

        assert!(find_enabled_app_overlaps_under(&scratch.0).unwrap().is_empty());
    }

    #[test]
    fn common_and_matching_slots_conflict_without_cross_interface_false_positive() {
        let scratch = Scratch::new("slot-matrix");
        scratch.put(
            "nfqws2/active.json",
            r#"{"profiles":{"base":{"enabled":true},"mobile":{"enabled":true},"wifi":{"enabled":true}}}"#,
        );
        scratch.put("nfqws2/base/app/uid/user_program", "com.example.app\n");
        scratch.put("nfqws2/mobile/app/uid/mobile_program", "com.example.app\n");
        scratch.put("nfqws2/wifi/app/uid/wifi_program", "com.example.app\n");

        let overlaps = find_enabled_app_overlaps_under(&scratch.0).unwrap();
        let labels = overlaps.iter().map(|item| (item.left.label(), item.right.label())).collect::<Vec<_>>();
        assert_eq!(labels, vec![
            ("nfqws2/base".into(), "nfqws2/mobile".into()),
            ("nfqws2/base".into(), "nfqws2/wifi".into()),
        ]);
    }

    #[test]
    fn one_profile_is_not_compared_to_itself() {
        let scratch = Scratch::new("self-list");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");
        scratch.put("nfqws2/one/app/uid/mobile_program", "com.example.app\n");

        assert!(find_enabled_app_overlaps_under(&scratch.0).unwrap().is_empty());
    }

    #[test]
    fn single_instance_layout_participates() {
        let scratch = Scratch::new("single-instance");
        scratch.put("tor/enabled.json", r#"{"enabled":true}"#);
        scratch.put("tor/app/uid/user_program", "com.example.app\n");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");

        let overlaps = find_enabled_app_overlaps_under(&scratch.0).unwrap();
        assert_eq!(overlaps.len(), 1);
        assert_eq!(overlaps[0].left.label(), "nfqws2/one");
        assert_eq!(overlaps[0].right.label(), "tor");
    }

    #[test]
    fn myprogram_with_apps_mode_claims_packages() {
        let scratch = Scratch::new("myprogram-enabled-apps");
        scratch.put("myprogram/active.json", r#"{"profiles":{"custom":{"enabled":true}}}"#);
        scratch.put("myprogram/profile/custom/setting.json", r#"{"apps_mode":true}"#);
        scratch.put("myprogram/profile/custom/app/uid/user_program", "com.example.app\n");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");

        assert_eq!(find_enabled_app_overlaps_under(&scratch.0).unwrap().len(), 1);
    }

    #[test]
    fn app_list_read_failures_are_errors() {
        let scratch = Scratch::new("unreadable-list");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        fs::create_dir_all(scratch.0.join("nfqws2/one/app/uid/user_program")).unwrap();

        assert!(find_enabled_app_overlaps_under(&scratch.0).is_err());
    }


    #[test]
    fn byedpi_does_not_claim_nfqws_only_interface_slots() {
        let scratch = Scratch::new("byedpi-stale-slots");
        scratch.put("byedpi/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        scratch.put("byedpi/one/app/uid/mobile_program", "com.example.app\n");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");

        assert!(find_enabled_app_overlaps_under(&scratch.0).unwrap().is_empty());
    }

    #[test]
    fn operaproxy_active_json_is_single_instance_state() {
        let scratch = Scratch::new("operaproxy-active");
        scratch.put("operaproxy/active.json", r#"{"enabled":true}"#);
        scratch.put("operaproxy/app/uid/user_program", "com.example.app\n");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");

        let overlaps = find_enabled_app_overlaps_under(&scratch.0).unwrap();
        assert_eq!(overlaps.len(), 1);
        assert_eq!(overlaps[0].right.label(), "operaproxy");
    }

    #[test]
    fn invalid_profile_enabled_value_is_an_error() {
        let scratch = Scratch::new("invalid-enabled-value");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":"yes"}}}"#);
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");

        assert!(find_enabled_app_overlaps_under(&scratch.0).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn dangling_active_symlink_is_an_error() {
        let scratch = Scratch::new("dangling-active");
        fs::create_dir_all(scratch.0.join("nfqws2/one/app/uid")).unwrap();
        scratch.put("nfqws2/one/app/uid/user_program", "com.example.app\n");
        std::os::unix::fs::symlink("missing-active.json", scratch.0.join("nfqws2/active.json")).unwrap();

        assert!(find_enabled_app_overlaps_under(&scratch.0).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn dangling_app_list_symlink_is_an_error() {
        let scratch = Scratch::new("dangling-list");
        scratch.put("nfqws2/active.json", r#"{"profiles":{"one":{"enabled":true}}}"#);
        fs::create_dir_all(scratch.0.join("nfqws2/one/app/uid")).unwrap();
        std::os::unix::fs::symlink(
            "missing-user-program",
            scratch.0.join("nfqws2/one/app/uid/user_program"),
        )
        .unwrap();

        assert!(find_enabled_app_overlaps_under(&scratch.0).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn dangling_single_instance_uid_dir_symlink_is_an_error() {
        let scratch = Scratch::new("dangling-single-uid-dir");
        scratch.put("tor/enabled.json", r#"{"enabled":true}"#);
        fs::create_dir_all(scratch.0.join("tor/app")).unwrap();
        std::os::unix::fs::symlink("missing-uid", scratch.0.join("tor/app/uid")).unwrap();

        assert!(find_enabled_app_overlaps_under(&scratch.0).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn dangling_profile_container_symlink_is_an_error() {
        let scratch = Scratch::new("dangling-profile-container");
        scratch.put("mihomo/active.json", r#"{"profiles":{"vpn":{"enabled":true}}}"#);
        std::os::unix::fs::symlink("missing-profiles", scratch.0.join("mihomo/profile")).unwrap();

        assert!(find_enabled_app_overlaps_under(&scratch.0).is_err());
    }
}

