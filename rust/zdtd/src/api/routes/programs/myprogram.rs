use crate::api::support::profile_crud::{profile_list_response, set_profile_enabled, remove_profile_from_active, archive_profile_dir};
use anyhow::{Result};
use serde::{Deserialize};
use serde_json::json;
use std::{collections::{HashMap}, net::TcpStream};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{
    create_myprogram_profile_named, create_myprogram_profile_next, default_myprogram_profile_setting_value, ensure_myprogram_profile_layout, ensure_valid_singbox_profile_name, myprogram_active_path,
    myprogram_deleted_profiles_root, myprogram_profile_root, refresh_apps_after_save_if_running, suggest_myprogram_profile_ports,
};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::super::cache::{invalidate_assignment_cache};
use super::super::super::support::apps::{validate_program_apps_content};
use super::super::super::support::fs::{
    read_json, read_text_or_empty, sha256_hex_bytes, write_json_pretty, write_text_atomic,
};
use super::super::super::support::paths::{decode_url_component, ensure_safe_filename};
use super::super::super::support::multipart::{parse_multipart_file};

pub(in crate::api) fn handle(
    stream: TcpStream,
    method: &str,
    seg: &[&str],
    headers: &HashMap<String, String>,
    body: &[u8],
    services_running: bool,
) -> Result<()> {
    match (method, seg) {
        // --- myprogram profile API
        ("GET", ["api", "programs", "myprogram", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                Ok(profile_list_response(&myprogram_active_path()))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "myprogram", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)] struct CreateReq { #[serde(default)] name: Option<String> }
                let req: CreateReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_myprogram_profile_named(name)?,
                    None => create_myprogram_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                set_profile_enabled(&myprogram_active_path(), profile, req.enabled)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("DELETE", ["api", "programs", "myprogram", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                remove_profile_from_active(&myprogram_active_path(), profile, false)?;
                invalidate_assignment_cache();
                archive_profile_dir(&myprogram_profile_root(profile), &myprogram_deleted_profiles_root(), profile);
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myprogram_profile_layout(profile)?;
                let p = myprogram_profile_root(profile).join("setting.json");
                if !p.exists() {
                    let (t2s_port, t2s_web_port) = suggest_myprogram_profile_ports()?;
                    write_json_pretty(&p, &default_myprogram_profile_setting_value(t2s_port, t2s_web_port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myprogram_profile_layout(profile)?;
                let setting: crate::programs::myprogram::ProfileSetting = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myprogram::save_setting(profile, &setting)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myprogram_profile_layout(profile)?;
                let p = myprogram_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myprogram_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/myprogram/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "myprogram", "common")?;
                let p = myprogram_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "myprogram", Some(profile), "common")?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "command"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                crate::programs::myprogram::read_command_text(profile)
            })();
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "command"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myprogram::write_command_text(profile, &req.content)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "t2s_ports"]) => {
            let res = crate::programs::myprogram::read_t2s_ports_text(profile);
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "t2s_ports"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myprogram::write_t2s_ports_text(profile, &req.content)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "protect_ports"]) => {
            let res = crate::programs::myprogram::read_protect_ports_text(profile);
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "protect_ports"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myprogram::write_protect_ports_text(profile, &req.content)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "bin"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                let files = crate::programs::myprogram::list_bin_files(profile)?;
                Ok(json!({"ok": true, "files": files.iter().map(|(name,size)| json!({"name": name, "size": size})).collect::<Vec<_>>() }))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "myprogram", "profiles", profile, "bin", "upload"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                let ct = headers.get("content-type").cloned().unwrap_or_default();
                let (filename, data) = if ct.starts_with("multipart/form-data") {
                    let f = parse_multipart_file(headers, body)?;
                    (f.filename, f.data)
                } else {
                    let filename = headers.get("x-filename").cloned().ok_or_else(|| anyhow::anyhow!("missing x-filename header"))?;
                    ensure_safe_filename(&filename)?;
                    (filename, body.to_vec())
                };
                crate::programs::myprogram::save_bin_file(profile, &filename, &data)?;
                Ok(json!({"ok": true, "file": filename, "size": data.len(), "sha256": sha256_hex_bytes(&data)}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("DELETE", ["api", "programs", "myprogram", "profiles", profile, "bin", filename]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let filename = decode_url_component(filename)?;
                ensure_safe_filename(&filename)?;
                crate::programs::myprogram::delete_bin_file(profile, &filename)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "myprogram", "profiles", profile, "apply"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                let setting = crate::programs::myprogram::load_setting(profile)?;
                let _ = if setting.apps_mode && setting.route_mode == "t2s" { Some(crate::programs::myprogram::parse_port_list_str(&crate::programs::myprogram::read_t2s_ports_text(profile)?)?) } else { None };
                let _ = crate::programs::myprogram::parse_port_list_str(&crate::programs::myprogram::read_protect_ports_text(profile)?)?;
                if setting.apps_mode {
                    let _ = crate::platform::android::pkg_uid::unified_processing(crate::platform::android::pkg_uid::Mode::Default, &crate::platform::android::pkg_uid::Sha256Tracker::new(crate::settings::SHARED_SHA_FLAG_FILE), &myprogram_profile_root(profile).join("app/out/user_program"), &myprogram_profile_root(profile).join("app/uid/user_program"))?;
                }
                let runtime = crate::programs::myprogram::load_runtime(profile).unwrap_or_default();
                let active = runtime.running && runtime.pid > 1 && std::path::Path::new("/proc").join(runtime.pid.to_string()).is_dir();
                Ok(json!({"ok": true, "active": active}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }


        _ => write_empty_404(stream),
    }
}
