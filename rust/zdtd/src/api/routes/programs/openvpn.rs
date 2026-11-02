use crate::api::support::profile_crud::{profile_list_response, remove_profile_from_active, archive_profile_dir};
use anyhow::{Context, Result};
use serde::{Deserialize};
use serde_json::json;
use std::{collections::{HashMap}, net::TcpStream};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{
    create_openvpn_profile_named, create_openvpn_profile_next, ensure_openvpn_profile_layout, is_profile_enabled, openvpn_active_path, openvpn_deleted_profiles_root,
    openvpn_profile_root, refresh_apps_after_save_if_running, validate_cross_vpn_tun_claim,
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
        // --- openvpn profile API
        ("GET", ["api", "programs", "openvpn", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::openvpn::ensure_root_layout()?;
                Ok(profile_list_response(&openvpn_active_path()))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "openvpn", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                let req: Req = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_openvpn_profile_named(name)?,
                    None => create_openvpn_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "openvpn", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = openvpn_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                let st = active.profiles.get_mut(*profile)
                    .ok_or_else(|| anyhow::anyhow!("profile not found"))?;
                st.enabled = req.enabled;
                crate::programs::openvpn::validate_enabled_tun_uniqueness_with_override(
                    Some(profile),
                    None,
                    Some(req.enabled),
                )?;
                if req.enabled {
                    let setting = crate::programs::openvpn::read_setting(profile).unwrap_or_default();
                    validate_cross_vpn_tun_claim("openvpn", profile, &setting.tun)?;
                }
                write_json_pretty(&p, &active)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "openvpn", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                remove_profile_from_active(&openvpn_active_path(), profile, true)?;
                invalidate_assignment_cache();
                archive_profile_dir(&openvpn_profile_root(profile), &openvpn_deleted_profiles_root(), profile);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "openvpn", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                ensure_openvpn_profile_layout(profile)?;
                let setting = crate::programs::openvpn::read_setting(profile)?;
                Ok(json!({"ok": true, "data": setting}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "openvpn", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                ensure_openvpn_profile_layout(profile)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let setting = crate::programs::openvpn::normalize_setting_value(v)?;
                crate::programs::openvpn::validate_enabled_tun_uniqueness_with_override(
                    Some(profile),
                    Some(&setting),
                    None,
                )?;
                if is_profile_enabled(&openvpn_active_path(), profile) {
                    validate_cross_vpn_tun_claim("openvpn", profile, &setting.tun)?;
                }
                crate::programs::openvpn::write_setting(profile, &setting)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "openvpn", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                ensure_openvpn_profile_layout(profile)?;
                let p = openvpn_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "openvpn", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                ensure_openvpn_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/openvpn/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "openvpn", "common")?;
                let p = openvpn_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "openvpn", Some(profile), "common")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "openvpn", "profiles", profile, "config"]) => {
            let res = (|| -> Result<String> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                ensure_openvpn_profile_layout(profile)?;
                let p = openvpn_profile_root(profile).join("client.ovpn");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "openvpn", "profiles", profile, "config"]) => {
            let res = (|| -> Result<()> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                ensure_openvpn_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let content = crate::common::fs::external_text::normalize_text(&req.content)?;
                let p = openvpn_profile_root(profile).join("client.ovpn");
                write_text_atomic(&p, &content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "openvpn", "profiles", profile, "upload-config"]) => {
            let res = (|| -> Result<()> {
                crate::programs::openvpn::ensure_valid_profile_name(profile)?;
                ensure_openvpn_profile_layout(profile)?;
                let f = parse_multipart_file(headers, body)?;
                // The external document name is only presentation metadata. The
                // profile always stores one canonical client.ovpn file.
                let content = crate::common::fs::external_text::decode_external_text(&f.data)
                    .with_context(|| format!("decode imported OpenVPN config {}", f.filename))?;
                if content.trim().is_empty() {
                    anyhow::bail!("client.ovpn is empty");
                }
                let p = openvpn_profile_root(profile).join("client.ovpn");
                write_text_atomic(&p, &content)?;
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
