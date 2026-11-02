use crate::api::support::profile_crud::{profile_list_response, remove_profile_from_active, archive_profile_dir};
use anyhow::{Result};
use serde::{Deserialize};
use serde_json::json;
use std::{collections::{HashMap}, net::TcpStream};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{
    create_mieru_profile_named, create_mieru_profile_next, ensure_mieru_profile_layout, is_profile_enabled, mieru_active_path, mieru_deleted_profiles_root,
    mieru_profile_root, refresh_apps_after_save_if_running, validate_cross_vpn_tun_claim,
};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::super::cache::{invalidate_assignment_cache};
use super::super::super::support::apps::{validate_program_apps_content};
use super::super::super::support::fs::{read_json, read_text_or_empty, write_json_pretty, write_text_atomic};
use super::super::super::support::model::{ProfilesActive};

pub(in crate::api) fn handle(
    stream: TcpStream,
    method: &str,
    seg: &[&str],
    _headers: &HashMap<String, String>,
    body: &[u8],
    services_running: bool,
) -> Result<()> {
    match (method, seg) {
        // --- mieru profile API
        ("GET", ["api", "programs", "mieru", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::mieru::ensure_root_layout()?;
                Ok(profile_list_response(&mieru_active_path()))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "mieru", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                let req: Req = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_mieru_profile_named(name)?,
                    None => create_mieru_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "mieru", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                crate::programs::mieru::ensure_valid_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = mieru_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                let st = active.profiles.get_mut(*profile)
                    .ok_or_else(|| anyhow::anyhow!("profile not found"))?;
                st.enabled = req.enabled;
                crate::programs::mieru::validate_enabled_tun_uniqueness_with_override(Some(profile), None, Some(req.enabled))?;
                if req.enabled {
                    let setting = crate::programs::mieru::read_setting(profile).unwrap_or_default();
                    validate_cross_vpn_tun_claim("mieru", profile, &setting.tun)?;
                    crate::programs::mieru::validate_port_uniqueness_with_override(Some(profile), None)?;
                }
                write_json_pretty(&p, &active)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("DELETE", ["api", "programs", "mieru", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                crate::programs::mieru::ensure_valid_profile_name(profile)?;
                remove_profile_from_active(&mieru_active_path(), profile, true)?;
                invalidate_assignment_cache();
                archive_profile_dir(&mieru_profile_root(profile), &mieru_deleted_profiles_root(), profile);
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "mieru", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::mieru::ensure_valid_profile_name(profile)?;
                ensure_mieru_profile_layout(profile)?;
                let p = mieru_profile_root(profile).join("setting.json");
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "mieru", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                crate::programs::mieru::ensure_valid_profile_name(profile)?;
                ensure_mieru_profile_layout(profile)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let setting = crate::programs::mieru::normalize_setting_value(v)?;
                crate::programs::mieru::validate_enabled_tun_uniqueness_with_override(Some(profile), Some(&setting), None)?;
                crate::programs::mieru::validate_port_uniqueness_with_override(Some(profile), Some(&setting))?;
                if is_profile_enabled(&mieru_active_path(), profile) {
                    validate_cross_vpn_tun_claim("mieru", profile, &setting.tun)?;
                }
                crate::programs::mieru::write_setting(profile, &setting)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "mieru", "profiles", profile, "config"]) => {
            let res = (|| -> Result<String> {
                crate::programs::mieru::ensure_valid_profile_name(profile)?;
                ensure_mieru_profile_layout(profile)?;
                let p = mieru_profile_root(profile).join("config.json");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "mieru", "profiles", profile, "config"]) => {
            let res = (|| -> Result<()> {
                crate::programs::mieru::ensure_valid_profile_name(profile)?;
                ensure_mieru_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = mieru_profile_root(profile).join("config.json");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "mieru", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                crate::programs::mieru::ensure_valid_profile_name(profile)?;
                ensure_mieru_profile_layout(profile)?;
                let p = mieru_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "mieru", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                crate::programs::mieru::ensure_valid_profile_name(profile)?;
                ensure_mieru_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/mieru/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "mieru", "common")?;
                let p = mieru_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "mieru", Some(profile), "common")?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }

        _ => write_empty_404(stream),
    }
}
