use crate::api::support::profile_crud::{profile_list_response, remove_profile_from_active, archive_profile_dir};
use anyhow::{Context, Result};
use serde::{Deserialize};
use serde_json::json;
use std::{collections::{HashMap}, net::TcpStream};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{
    amneziawg_active_path, amneziawg_deleted_profiles_root, amneziawg_profile_root, create_amneziawg_profile_named, create_amneziawg_profile_next, ensure_amneziawg_profile_layout,
    is_profile_enabled, refresh_apps_after_save_if_running, validate_cross_vpn_tun_claim,
};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::super::cache::{invalidate_assignment_cache};
use super::super::super::support::apps::{validate_program_apps_content};
use super::super::super::support::fs::{read_json, read_text_or_empty, write_json_pretty, write_text_atomic};
use super::super::super::support::multipart::{parse_multipart_file};
use super::super::super::support::model::{ProfilesActive};

pub(in crate::api) fn handle(
    stream: TcpStream,
    method: &str,
    seg: &[&str],
    headers: &HashMap<String, String>,
    body: &[u8],
    services_running: bool,
) -> Result<()> {
    match (method, seg) {
        // --- amneziawg profile API
        ("GET", ["api", "programs", "amneziawg", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::amneziawg::ensure_root_layout()?;
                Ok(profile_list_response(&amneziawg_active_path()))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "amneziawg", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                let req: Req = if body.is_empty() { Req { name: None } } else {
                    serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?
                };
                let name = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_amneziawg_profile_named(name)?,
                    None => create_amneziawg_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": name}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "amneziawg", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = amneziawg_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                let st = active.profiles.get_mut(*profile)
                    .ok_or_else(|| anyhow::anyhow!("profile not found"))?;
                st.enabled = req.enabled;
                crate::programs::amneziawg::validate_enabled_tun_uniqueness_with_override(
                    Some(profile),
                    None,
                    Some(req.enabled),
                )?;
                crate::programs::amneziawg::validate_enabled_address_uniqueness_with_override(
                    Some(profile),
                    None,
                    Some(req.enabled),
                )?;
                if req.enabled {
                    let setting = crate::programs::amneziawg::read_setting(profile).unwrap_or_default();
                    validate_cross_vpn_tun_claim("amneziawg", profile, &setting.tun)?;
                }
                write_json_pretty(&p, &active)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "amneziawg", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                remove_profile_from_active(&amneziawg_active_path(), profile, true)?;
                invalidate_assignment_cache();
                archive_profile_dir(&amneziawg_profile_root(profile), &amneziawg_deleted_profiles_root(), profile);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "amneziawg", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                ensure_amneziawg_profile_layout(profile)?;
                let p = amneziawg_profile_root(profile).join("setting.json");
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "amneziawg", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                ensure_amneziawg_profile_layout(profile)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let setting = crate::programs::amneziawg::normalize_setting_value(v)?;
                crate::programs::amneziawg::validate_enabled_tun_uniqueness_with_override(
                    Some(profile),
                    Some(&setting),
                    None,
                )?;
                crate::programs::amneziawg::validate_enabled_address_uniqueness_with_override(
                    Some(profile),
                    Some(&setting),
                    None,
                )?;
                if is_profile_enabled(&amneziawg_active_path(), profile) {
                    validate_cross_vpn_tun_claim("amneziawg", profile, &setting.tun)?;
                }
                crate::programs::amneziawg::write_setting(profile, &setting)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "amneziawg", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                ensure_amneziawg_profile_layout(profile)?;
                let p = amneziawg_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "amneziawg", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                ensure_amneziawg_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/amneziawg/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "amneziawg", "common")?;
                let p = amneziawg_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "amneziawg", Some(profile), "common")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "amneziawg", "profiles", profile, "config"]) => {
            let res = (|| -> Result<String> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                ensure_amneziawg_profile_layout(profile)?;
                let p = amneziawg_profile_root(profile).join("client.conf");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "amneziawg", "profiles", profile, "config"]) => {
            let res = (|| -> Result<()> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                ensure_amneziawg_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let content = crate::common::fs::external_text::normalize_text(&req.content)?;
                crate::programs::amneziawg::import_config(profile, &content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "amneziawg", "profiles", profile, "upload-config"]) => {
            let res = (|| -> Result<()> {
                crate::programs::amneziawg::ensure_valid_profile_name(profile)?;
                ensure_amneziawg_profile_layout(profile)?;
                let f = parse_multipart_file(headers, body)?;
                // Like OpenVPN, AWG stores a canonical client.conf regardless of
                // the external document's language, extension case or display name.
                let content = crate::common::fs::external_text::decode_external_text(&f.data)
                    .with_context(|| format!("decode imported AmneziaWG config {}", f.filename))?;
                crate::programs::amneziawg::import_config(profile, &content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        _ => write_empty_404(stream),
    }
}
