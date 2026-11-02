use anyhow::Result;
use serde::Deserialize;
use serde_json::json;
use std::{fs, net::TcpStream, path::Path};

use crate::{
    api_status,
    common::apps::parse_package_set,
    features::{energy_saver, protector, traffic_total},
    runtime::daemon::{self, SharedState},
    settings,
};
use super::super::cache::{collect_assignment_files, get_status_snapshot, invalidate_assignment_cache};
use super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::support::apps::{
    assignment_to_view, find_proxyinfo_conflicts, flatten_conflicts, remove_packages_from_program_lists,
    validate_launch_marker_usage, validate_program_apps_content, validate_proxyinfo_apps_content,
    ProxyInfoSaveReq,
};
use super::super::support::paths::{safe_module_path, FsReadReq, FsWriteReq};
use super::super::{ContentReq, EnabledReq, NewProfileReq};
use super::programs::shared::{
    create_myprogram_profile_named, create_myprogram_profile_next, create_myproxy_profile_named, create_myproxy_profile_next, create_named_profile, create_next_profile,
    create_singbox_lx_profile_named, create_singbox_lx_profile_next, create_singbox_profile_named, create_singbox_profile_next, create_wireproxy_profile_named, create_wireproxy_profile_next,
    refresh_apps_after_save_if_running,
};

pub(in crate::api) fn handle_global_routes(
    stream: TcpStream,
    method: &str,
    path: &str,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
    match (method, path) {
        ("GET", "/api/system/capabilities") => get_system_capabilities(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/system/components") => get_system_components(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/runtime-apply/status") => get_runtime_apply_status(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/status") => get_status(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/traffic/rules") | ("GET", "/api/traffic/total") => get_traffic(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/setting") => get_setting(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/setting") => post_setting(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/hotspot/captive/status") => get_captive_status(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/hotspot/captive/devices") => get_captive_devices(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/hotspot/captive/allow") => post_captive_allow(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/hotspot/captive/deny") => post_captive_deny(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/hotspot/captive/rename") => post_captive_rename(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/energy-saver") | ("GET", "/api/energy-saver/programs") => get_energy_saver(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/energy-saver") | ("PUT", "/api/energy-saver") => update_energy_saver(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/energy-saver/apply") => apply_energy_saver(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/fs/read_text") => fs_read_text(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/fs/write_text") => fs_write_text(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/fs/list_dir") => fs_list_dir(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/new/profile") => new_profile(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/start") => start_services(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/stop") => stop_services(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/blockedquic") => get_blockedquic(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/blockedquic/enabled") => get_blockedquic_enabled(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("PUT", "/api/blockedquic/enabled") => put_blockedquic_enabled(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/blockedquic/apps") => get_blockedquic_apps(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("PUT", "/api/blockedquic/apps") => put_blockedquic_apps(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/blockedquic/save") => save_blockedquic(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/blockedquic/apply") => apply_blockedquic(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/hiding/status") => get_hiding_status(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/proxyinfo") => get_proxyinfo(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/proxyinfo/enabled") => get_proxyinfo_enabled(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("PUT", "/api/proxyinfo/enabled") => put_proxyinfo_enabled(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/proxyinfo/apps") => get_proxyinfo_apps(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("PUT", "/api/proxyinfo/apps") => put_proxyinfo_apps(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("GET", "/api/apps/assignments") => get_app_assignments(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/proxyinfo/save") => save_proxyinfo(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        ("POST", "/api/proxyinfo/apply") => apply_proxyinfo(stream, body, state, services_running, runtime_state, start_in_progress, stop_in_progress, services_partial, api_status_snapshot),
        _ => write_empty_404(stream),
    }
}

#[allow(unused_variables)]
fn get_system_capabilities(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, crate::platform::capabilities::collect())
        }

#[allow(unused_variables)]
fn get_system_components(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, crate::programs::components::collect())
        }

#[allow(unused_variables)]
fn get_runtime_apply_status(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, crate::runtime::apply::status_json())
        }

#[allow(unused_variables)]
fn get_status(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let (report, cached, degraded) = get_status_snapshot(services_running);
            let mut value = match serde_json::to_value(report) {
                Ok(v) => v,
                Err(e) => {
                    log::warn!("api status serialize failed: {e:#}");
                    json!({})
                }
            };
            if let Some(obj) = value.as_object_mut() {
                obj.insert("ok".to_string(), json!(true));
                obj.insert("cached".to_string(), json!(cached));
                obj.insert("degraded".to_string(), json!(degraded));
                obj.insert(
                    "runtime_state".to_string(),
                    json!(if degraded && (start_in_progress || stop_in_progress) { "busy" } else { runtime_state.as_str() }),
                );
                obj.insert("actual_runtime_state".to_string(), json!(runtime_state));
                obj.insert("start_in_progress".to_string(), json!(start_in_progress));
                obj.insert("stop_in_progress".to_string(), json!(stop_in_progress));
                obj.insert("services_partial".to_string(), json!(services_partial));
                if let Some(api_st) = &api_status_snapshot {
                    obj.insert("ui_status".to_string(), json!(api_st));
                }
            } else {
                value = json!({
                    "ok": true,
                    "cached": cached,
                    "degraded": degraded,
                    "runtime_state": if degraded && (start_in_progress || stop_in_progress) { "busy" } else { runtime_state.as_str() },
                    "actual_runtime_state": runtime_state,
                    "start_in_progress": start_in_progress,
                    "stop_in_progress": stop_in_progress,
                    "services_partial": services_partial,
                    "ui_status": api_status_snapshot,
                });
            }
            write_json(stream, 200, value)
        }

#[allow(unused_variables)]
fn get_traffic(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = traffic_total::try_collect_rule_snapshot();
            match res {
                Ok(Some(report)) => write_json(stream, 200, json!({"ok": true, "busy": false, "preparing": false, "traffic": report})),
                Ok(None) => write_json(stream, 200, json!({
                    "ok": false,
                    "busy": true,
                    "preparing": true,
                    "message": "Traffic snapshot is still preparing. Please wait."
                })),
                Err(e) => write_json(stream, 200, json!({"ok": false, "busy": false, "preparing": false, "error": format!("{e:#}")})),
            }
        }

#[allow(unused_variables)]
fn get_setting(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let setting = settings::load_api_settings()?;
            write_json(stream, 200, json!({"ok": true, "setting": setting}))
        }

#[allow(unused_variables)]
fn post_setting(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            #[derive(Deserialize, Default)]
            struct SettingPatch {
                #[serde(default)]
                protector_mode: Option<settings::ProtectorMode>,
                #[serde(default)]
                hotspot_t2s_enabled: Option<bool>,
                #[serde(default)]
                hotspot_mode: Option<String>,
                #[serde(default)]
                hotspot_program: Option<String>,
                #[serde(default)]
                hotspot_profile: Option<String>,
                #[serde(default)]
                hotspot_t2s_target: Option<String>,
                #[serde(default)]
                hotspot_t2s_singbox_profile: Option<String>,
                #[serde(default)]
                hotspot_t2s_wireproxy_profile: Option<String>,
                #[serde(default)]
                hotspot_t2s_capture_all: Option<bool>,
                #[serde(default)]
                captive_portal_enabled: Option<bool>,
                #[serde(default)]
                allow_loopback_redirect: Option<bool>,
                #[serde(default)]
                tproxy_enabled: Option<bool>,
                #[serde(default)]
                selinux_permissive_enabled: Option<bool>,
                #[serde(default)]
                ip_forward_enabled: Option<bool>,
            }

            let patch: SettingPatch = serde_json::from_slice(&body)
                .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
            let mut setting = settings::load_api_settings().unwrap_or_default();
            if let Some(mode) = patch.protector_mode {
                setting.protector_mode = mode;
            }
            let mut apply_ip_forward: Option<bool> = None;
            if let Some(enabled) = patch.hotspot_t2s_enabled {
                setting.hotspot_t2s_enabled = enabled;
                // Enabling/disabling hotspot starts from a clean selection. The UI then sets
                // exactly one mode/program/profile explicitly.
                setting.hotspot_program.clear();
                setting.hotspot_profile.clear();
                setting.hotspot_t2s_target.clear();
                setting.hotspot_t2s_singbox_profile.clear();
                setting.hotspot_t2s_wireproxy_profile.clear();
                // Hotspot/tethering requires IPv4 forwarding. Persist the existing
                // advanced setting when hotspot is enabled, but do not disable it
                // automatically when hotspot is turned off.
                if enabled {
                    setting.ip_forward_enabled = true;
                    apply_ip_forward = Some(true);
                }
            }
            if let Some(mode) = patch.hotspot_mode {
                setting.hotspot_mode = mode;
                setting.hotspot_program.clear();
                setting.hotspot_profile.clear();
                setting.hotspot_t2s_target.clear();
                setting.hotspot_t2s_singbox_profile.clear();
                setting.hotspot_t2s_wireproxy_profile.clear();
            }
            if let Some(program) = patch.hotspot_program {
                setting.hotspot_program = program;
                setting.hotspot_t2s_target.clear();
                setting.hotspot_t2s_singbox_profile.clear();
                setting.hotspot_t2s_wireproxy_profile.clear();
            }
            if let Some(profile) = patch.hotspot_profile {
                setting.hotspot_profile = profile;
            }
            if let Some(target) = patch.hotspot_t2s_target {
                setting.hotspot_mode = "proxy".to_string();
                setting.hotspot_program = target.clone();
                setting.hotspot_profile.clear();
                setting.hotspot_t2s_target = target;
            }
            if let Some(profile) = patch.hotspot_t2s_singbox_profile {
                if !profile.trim().is_empty() {
                    setting.hotspot_mode = "proxy".to_string();
                    setting.hotspot_program = "singbox".to_string();
                    setting.hotspot_profile = profile.clone();
                }
                setting.hotspot_t2s_singbox_profile = profile;
            }
            if let Some(profile) = patch.hotspot_t2s_wireproxy_profile {
                if !profile.trim().is_empty() {
                    setting.hotspot_mode = "proxy".to_string();
                    setting.hotspot_program = "wireproxy".to_string();
                    setting.hotspot_profile = profile.clone();
                }
                setting.hotspot_t2s_wireproxy_profile = profile;
            }
            if let Some(capture_all) = patch.hotspot_t2s_capture_all {
                setting.hotspot_t2s_capture_all = capture_all;
            }
            if let Some(enabled) = patch.captive_portal_enabled {
                setting.captive_portal_enabled = enabled;
            }
            if let Some(enabled) = patch.allow_loopback_redirect {
                setting.allow_loopback_redirect = enabled;
            }
            if let Some(enabled) = patch.tproxy_enabled {
                setting.tproxy_enabled = enabled;
            }
            if let Some(enabled) = patch.selinux_permissive_enabled {
                setting.selinux_permissive_enabled = enabled;
            }
            if let Some(enabled) = patch.ip_forward_enabled {
                setting.ip_forward_enabled = enabled;
                apply_ip_forward = Some(enabled);
            }
            if setting.hotspot_t2s_enabled && !setting.ip_forward_enabled {
                setting.ip_forward_enabled = true;
                apply_ip_forward = Some(true);
            }
            settings::save_api_settings(&setting)?;
            if let Some(enabled) = apply_ip_forward {
                crate::platform::android::sysctl::set_ipv4_forward(enabled)?;
            }
            let saved = settings::load_api_settings().unwrap_or(setting);
            if services_running {
                crate::features::captive_portal::sync_from_settings_best_effort();
            }
            protector::refresh(services_running);
            write_json(stream, 200, json!({"ok": true, "setting": saved}))
        }

#[allow(unused_variables)]
fn get_captive_status(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, crate::features::captive_portal::api_status())
        }

#[allow(unused_variables)]
fn get_captive_devices(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, crate::features::captive_portal::api_devices())
        }

#[allow(unused_variables)]
fn post_captive_allow(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, crate::features::captive_portal::api_allow(&body, services_running))
        }

#[allow(unused_variables)]
fn post_captive_deny(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, crate::features::captive_portal::api_deny(&body, services_running))
        }

#[allow(unused_variables)]
fn post_captive_rename(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, crate::features::captive_portal::api_rename(&body))
        }

#[allow(unused_variables)]
fn get_energy_saver(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            write_json(stream, 200, energy_saver::api_snapshot())
        }

#[allow(unused_variables)]
fn update_energy_saver(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let wrapper: serde_json::Value = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let cfg: energy_saver::EnergySaverSettings = if let Some(settings) = wrapper.get("settings") {
                    serde_json::from_value(settings.clone())
                        .map_err(|e| anyhow::anyhow!("bad JSON settings: {e}"))?
                } else {
                    serde_json::from_value(wrapper)
                        .map_err(|e| anyhow::anyhow!("bad JSON settings: {e}"))?
                };
                let _saved = energy_saver::save_settings(cfg)?;
                energy_saver::refresh(services_running);
                Ok(energy_saver::api_snapshot())
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
            }
        }

#[allow(unused_variables)]
fn apply_energy_saver(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = energy_saver::apply_affinity_now();
            match res {
                Ok(applied) => write_json(stream, 200, json!({"ok": true, "applied": applied, "active": energy_saver::active()})),
                Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
            }
        }

#[allow(unused_variables)]
fn fs_read_text(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let req: FsReadReq = serde_json::from_slice(&body)
                .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
            let p = safe_module_path(&req.path)?;
            let content = fs::read_to_string(&p)
                .map_err(|e| anyhow::anyhow!("read failed {}: {e}", p.display()))?;
            write_json(stream, 200, json!({"ok": true, "content": content}))
        }

#[allow(unused_variables)]
fn fs_write_text(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let req: FsWriteReq = serde_json::from_slice(&body)
                .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
            let p = safe_module_path(&req.path)?;
            if let Some(parent) = p.parent() {
                fs::create_dir_all(parent).ok();
            }
            // Atomic-ish write: write to temp, then rename.
            let tmp = p.with_extension("tmp");
            fs::write(&tmp, req.content.as_bytes())
                .map_err(|e| anyhow::anyhow!("write failed {}: {e}", tmp.display()))?;
            fs::rename(&tmp, &p)
                .map_err(|e| anyhow::anyhow!("rename failed {} -> {}: {e}", tmp.display(), p.display()))?;
            write_json(stream, 200, json!({"ok": true}))
        }

#[allow(unused_variables)]
fn fs_list_dir(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let req: FsReadReq = serde_json::from_slice(&body)
                .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
            let p = safe_module_path(&req.path)?;
            let mut out = Vec::new();
            for ent in fs::read_dir(&p)
                .map_err(|e| anyhow::anyhow!("readdir failed {}: {e}", p.display()))? {
                let ent = ent?;
                if let Some(name) = ent.file_name().to_str() {
                    out.push(name.to_string());
                }
            }
            out.sort();
            write_json(stream, 200, json!({"ok": true, "entries": out}))
        }

#[allow(unused_variables)]
fn new_profile(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let req: NewProfileReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let program = req.program.trim();
                let name = if program == "sing-box" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_singbox_profile_named(p)?,
                        None => create_singbox_profile_next()?,
                    }
                } else if program == "sing-box-lx" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_singbox_lx_profile_named(p)?,
                        None => create_singbox_lx_profile_next()?,
                    }
                } else if program == "wireproxy" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_wireproxy_profile_named(p)?,
                        None => create_wireproxy_profile_next()?,
                    }
                } else if program == "myproxy" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_myproxy_profile_named(p)?,
                        None => create_myproxy_profile_next()?,
                    }
                } else if program == "myprogram" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_myprogram_profile_named(p)?,
                        None => create_myprogram_profile_next()?,
                    }
                } else {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_named_profile(program, p)?,
                        None => create_next_profile(program)?,
                    }
                };
                Ok(json!({"ok": true, "profile": name}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
            }
        }

#[allow(unused_variables)]
fn start_services(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = daemon::handle_start_async(&state);
            match res {
                Ok(accepted) => write_json(stream, 200, json!({"ok": true, "accepted": accepted})),
                Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
            }
        }

#[allow(unused_variables)]
fn stop_services(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = daemon::handle_stop_async(&state);
            match res {
                Ok(accepted) => write_json(stream, 200, json!({"ok": true, "accepted": accepted})),
                Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
            }
        }

#[allow(unused_variables)]
fn get_blockedquic(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::features::blockedquic::load_enabled_json()?.is_enabled();
                let apps = crate::features::blockedquic::read_uid_program_text()?;
                Ok(json!({"ok": true, "enabled": enabled, "apps": apps, "active": services_running && crate::features::blockedquic::is_active()}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn get_blockedquic_enabled(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::features::blockedquic::load_enabled_json()?.is_enabled();
                Ok(json!({"ok": true, "enabled": enabled}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn put_blockedquic_enabled(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let req: EnabledReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let v = crate::features::blockedquic::save_enabled_value(req.enabled)?;
                Ok(json!({"ok": true, "enabled": v.is_enabled()}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn get_blockedquic_apps(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = crate::features::blockedquic::read_uid_program_text();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn put_blockedquic_apps(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_program_apps_content(&req.content, "/api/blockedquic/apps", "blockedquic", "common")?;
                crate::features::blockedquic::write_uid_program_text(&req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "blockedquic", None, "common")?;
                invalidate_assignment_cache();
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn save_blockedquic(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let req: ContentReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_program_apps_content(&req.content, "/api/blockedquic/apps", "blockedquic", "common")?;
                crate::features::blockedquic::write_uid_program_text(&req.content)?;
                invalidate_assignment_cache();
                Ok(json!({"ok": true, "saved": true}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn apply_blockedquic(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::features::blockedquic::load_enabled_json()?.is_enabled();
                if !enabled {
                    crate::features::blockedquic::clear_rules()?;
                    return Ok(json!({"ok": true, "active": false}));
                }
                let _ = crate::features::blockedquic::rebuild_out_program()?;
                if services_running {
                    let active = crate::features::blockedquic::refresh_runtime(true)?;
                    Ok(json!({"ok": true, "active": active}))
                } else {
                    crate::features::blockedquic::clear_rules()?;
                    Ok(json!({"ok": true, "active": false}))
                }
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn get_hiding_status(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let proxy_enabled = crate::features::proxyinfo::load_enabled_json()?.is_enabled();
                let proxy_apps = crate::features::proxyinfo::read_proxy_packages()?.len();
                let proxy_active = services_running && crate::features::proxyinfo::is_active();
                let lsposed_configured = proxy_enabled && proxy_apps > 0;
                let zygisk_requested = Path::new("/data/adb/ZDT-D/zygisk").exists();
                let zygisk_installed = Path::new(settings::MODULE_DIR).join("zygisk/arm64-v8a.so").exists()
                    || Path::new(settings::MODULE_DIR).join("zygisk/armeabi-v7a.so").exists();
                Ok(json!({
                    "ok": true,
                    "selected_apps": proxy_apps,
                    "zygisk": {
                        "requested": zygisk_requested,
                        "installed": zygisk_installed,
                        "active": null,
                        "status": if zygisk_installed { "installed" } else if zygisk_requested { "requested" } else { "not_installed" }
                    },
                    "lsposed": {
                        "enabled": lsposed_configured,
                        "active": lsposed_configured,
                        "selected_apps": proxy_apps,
                        "status": if lsposed_configured { "enabled" } else { "unknown" }
                    },
                    "proxyinfo": {
                        "enabled": proxy_enabled,
                        "active": proxy_active,
                        "selected_apps": proxy_apps,
                        "status": if proxy_active { "active" } else if proxy_enabled { "enabled" } else { "off" }
                    }
                }))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn get_proxyinfo(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::features::proxyinfo::load_enabled_json()?.is_enabled();
                let apps = crate::features::proxyinfo::read_uid_program_text()?;
                Ok(json!({"ok": true, "enabled": enabled, "apps": apps, "active": services_running && crate::features::proxyinfo::is_active()}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn get_proxyinfo_enabled(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::features::proxyinfo::load_enabled_json()?.is_enabled();
                Ok(json!({"ok": true, "enabled": enabled}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn put_proxyinfo_enabled(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let req: EnabledReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let v = crate::features::proxyinfo::save_enabled_value(req.enabled)?;
                Ok(json!({"ok": true, "enabled": v.is_enabled()}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn get_proxyinfo_apps(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = crate::features::proxyinfo::read_uid_program_text();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn put_proxyinfo_apps(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_proxyinfo_apps_content(&req.content)?;
                crate::features::proxyinfo::write_uid_program_text(&req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "proxyinfo", None, "common")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn get_app_assignments(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let lists = collect_assignment_files()
                    .into_iter()
                    .map(|v| assignment_to_view(&v))
                    .collect::<Vec<_>>();
                let proxyinfo = crate::features::proxyinfo::read_proxy_packages()?.into_iter().collect::<Vec<_>>();
                Ok(json!({"ok": true, "lists": lists, "proxyinfo_packages": proxyinfo}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn save_proxyinfo(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let req: ProxyInfoSaveReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_launch_marker_usage(&req.content, "proxyInfo")?;
                let candidate = parse_package_set(&req.content);
                let conflicts = find_proxyinfo_conflicts(&candidate);
                if !conflicts.is_empty() && !req.remove_conflicts {
                    return Ok(json!({"ok": true, "saved": false, "conflicts": flatten_conflicts(&conflicts)}));
                }
                let removed = if req.remove_conflicts {
                    remove_packages_from_program_lists(&candidate)?
                } else {
                    Vec::new()
                };
                crate::features::proxyinfo::write_uid_program_text(&req.content)?;
                Ok(json!({"ok": true, "saved": true, "removed": removed}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

#[allow(unused_variables)]
fn apply_proxyinfo(
    stream: TcpStream,
    body: Vec<u8>,
    state: SharedState,
    services_running: bool,
    runtime_state: String,
    start_in_progress: bool,
    stop_in_progress: bool,
    services_partial: bool,
    api_status_snapshot: Option<api_status::ApiStatus>,
) -> Result<()> {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::features::proxyinfo::load_enabled_json()?.is_enabled();
                if !enabled {
                    crate::features::proxyinfo::clear_rules()?;
                    return Ok(json!({"ok": true, "active": false}));
                }
                if services_running {
                    let active = crate::features::proxyinfo::refresh_runtime(true)?;
                    Ok(json!({"ok": true, "active": active}))
                } else {
                    crate::features::proxyinfo::clear_rules()?;
                    Ok(json!({"ok": true, "active": false}))
                }
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

