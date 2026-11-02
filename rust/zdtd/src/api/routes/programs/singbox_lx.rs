use crate::api::support::profile_crud::{profile_list_response, set_profile_enabled, remove_profile_from_active, archive_profile_dir};
use anyhow::{Result};
use serde::{Deserialize};
use serde_json::json;
use std::{collections::{HashMap}, fs, net::TcpStream, time::{SystemTime, UNIX_EPOCH}};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{
    create_singbox_lx_profile_named, create_singbox_lx_profile_next, create_singbox_lx_server_named, create_singbox_lx_server_next, default_singbox_lx_profile_setting_value, default_singbox_lx_server_setting_value,
    ensure_singbox_lx_profile_layout, ensure_singbox_lx_vpn_single_server, ensure_valid_singbox_profile_name, normalize_and_write_singbox_lx_profile_setting, refresh_apps_after_save_if_running, singbox_lx_active_path,
    singbox_lx_deleted_profiles_root, singbox_lx_deleted_servers_root, singbox_lx_profile_mode_is_vpn, singbox_lx_profile_root, singbox_lx_server_root, suggest_singbox_lx_profile_ports,
    suggest_singbox_lx_server_port,
};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::super::cache::{invalidate_assignment_cache};
use super::super::super::support::apps::{validate_program_apps_content};
use super::super::super::support::fs::{read_json, read_text_or_empty, write_json_pretty, write_text_atomic};

pub(in crate::api) fn handle(
    stream: TcpStream,
    method: &str,
    seg: &[&str],
    _headers: &HashMap<String, String>,
    body: &[u8],
    services_running: bool,
) -> Result<()> {
    match (method, seg) {
        // --- sing-box-lx WARP / MASQUE registration cache
        ("GET", ["api", "programs", "sing-box-lx", "warp", "masque", "account"]) => {
            let res = crate::programs::singbox::lx::read_masque_account();
            match res {
                Ok(account) => write_json(stream, 200, json!({"ok": true, "account": account})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box-lx", "warp", "masque", "account"]) => {
            let res = (|| -> Result<()> {
                if !crate::programs::singbox::lx::is_installed() {
                    anyhow::bail!("sing-box-lx optional component is not installed");
                }
                let account: crate::programs::singbox::lx::MasqueAccountRecord = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad MASQUE account JSON: {e}"))?;
                crate::programs::singbox::lx::write_masque_account(&account)
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "sing-box-lx", "warp", "masque", "account"]) => {
            match crate::programs::singbox::lx::clear_masque_account() {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "sing-box-lx", "validate-config"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::singbox::lx::validate_config_text(&req.content)
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- sing-box-lx profile/server API
        ("GET", ["api", "programs", "sing-box-lx", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                Ok(profile_list_response(&singbox_lx_active_path()))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "sing-box-lx", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                let req: Req = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_singbox_lx_profile_named(name)?,
                    None => create_singbox_lx_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box-lx", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                set_profile_enabled(&singbox_lx_active_path(), profile, req.enabled)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "sing-box-lx", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                remove_profile_from_active(&singbox_lx_active_path(), profile, true)?;
                invalidate_assignment_cache();
                archive_profile_dir(&singbox_lx_profile_root(profile), &singbox_lx_deleted_profiles_root(), profile);
                crate::features::subscriptions::remove_links_for_profile("sing-box-lx", profile);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box-lx", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_lx_profile_layout(profile)?;
                let p = singbox_lx_profile_root(profile).join("setting.json");
                if !p.exists() {
                    let (t2s_port, t2s_web_port) = suggest_singbox_lx_profile_ports()?;
                    write_json_pretty(&p, &default_singbox_lx_profile_setting_value(t2s_port, t2s_web_port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                let setting = crate::programs::singbox::lx::normalize_setting_value(v)?;
                let normalized = serde_json::to_value(&setting)?;
                write_json_pretty(&p, &normalized)?;
                Ok(json!({"ok": true, "data": normalized}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box-lx", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_lx_profile_layout(profile)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                normalize_and_write_singbox_lx_profile_setting(profile, v)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box-lx", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_lx_profile_layout(profile)?;
                let p = singbox_lx_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box-lx", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_lx_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/sing-box-lx/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "sing-box-lx", "common")?;
                let p = singbox_lx_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "sing-box-lx", Some(profile), "common")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box-lx", "profiles", profile, "servers"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_lx_profile_layout(profile)?;
                let root = singbox_lx_profile_root(profile).join("server");
                let mut servers = Vec::new();
                if let Ok(rd) = fs::read_dir(&root) {
                    for ent in rd.flatten() {
                        let path = ent.path();
                        if !path.is_dir() { continue; }
                        let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue; };
                        if name.starts_with('.') { continue; }
                        let setting_path = path.join("setting.json");
                        let data: serde_json::Value = read_json(&setting_path).unwrap_or_else(|_| default_singbox_lx_server_setting_value(1180));
                        let subscription_link = crate::features::subscriptions::link_for_target("sing-box-lx", profile, name);
                        servers.push(json!({"name": name, "setting": data, "subscription_link": subscription_link}));
                    }
                }
                servers.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
                Ok(json!({"ok": true, "servers": servers}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "sing-box-lx", "profiles", profile, "servers"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                ensure_valid_singbox_profile_name(profile)?;
                let req: Req = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let server = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_singbox_lx_server_named(profile, name)?,
                    None => create_singbox_lx_server_next(profile)?,
                };
                Ok(json!({"ok": true, "server": server}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "sing-box-lx", "profiles", profile, "servers", server]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let src = singbox_lx_server_root(profile, server);
                if !src.exists() {
                    anyhow::bail!("server not found");
                }
                let deleted_dir = singbox_lx_deleted_servers_root(profile);
                fs::create_dir_all(&deleted_dir).ok();
                let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                let dst = deleted_dir.join(format!("{server}.{ts}"));
                let _ = fs::rename(&src, &dst);
                crate::features::subscriptions::remove_link_for_target("sing-box-lx", profile, server);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box-lx", "profiles", profile, "servers", server, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                ensure_singbox_lx_profile_layout(profile)?;
                let root = singbox_lx_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("setting.json");
                if !p.exists() {
                    let port = suggest_singbox_lx_server_port()?;
                    write_json_pretty(&p, &default_singbox_lx_server_setting_value(port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box-lx", "profiles", profile, "servers", server, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let existing_path = singbox_lx_server_root(profile, server).join("setting.json");
                let existing: serde_json::Value = read_json(&existing_path).unwrap_or_else(|_| default_singbox_lx_server_setting_value(1180));
                let enabled = crate::common::fs::json::json_enabled_opt(v.get("enabled"))
                    .or_else(|| crate::common::fs::json::json_enabled_opt(existing.get("enabled")))
                    .unwrap_or(false);
                let mode_vpn = singbox_lx_profile_mode_is_vpn(profile);
                if mode_vpn {
                    let only = ensure_singbox_lx_vpn_single_server(profile)?;
                    if only.as_str() != *server {
                        anyhow::bail!("singbox_lx_vpn_requires_single_server: VPN-режим sing-box-lx поддерживает только один сервер.");
                    }
                }
                let port = v.get("port")
                    .and_then(|x| x.as_u64())
                    .and_then(|x| u16::try_from(x).ok())
                    .or_else(|| existing.get("port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()))
                    .unwrap_or(1180);
                if !mode_vpn && port == 0 {
                    anyhow::bail!("invalid port");
                }
                let selected_sni = v.get("sni")
                    .and_then(|x| x.as_str())
                    .map(str::trim)
                    .filter(|x| !x.is_empty())
                    .map(str::to_string)
                    .or_else(|| existing.get("sni")
                        .and_then(|x| x.as_str())
                        .map(str::trim)
                        .filter(|x| !x.is_empty())
                        .map(str::to_string));
                let source_options = if v.get("sni_options").is_some() {
                    v.get("sni_options")
                } else {
                    existing.get("sni_options")
                };
                let mut sni_options = Vec::<String>::new();
                if let Some(values) = source_options.and_then(|x| x.as_array()) {
                    for value in values {
                        let Some(value) = value.as_str().map(str::trim).filter(|x| !x.is_empty()) else { continue; };
                        if !sni_options.iter().any(|existing| existing.as_str() == value) {
                            sni_options.push(value.to_string());
                        }
                    }
                }
                let selected_sni = match (selected_sni, sni_options.is_empty()) {
                    (Some(value), false) if sni_options.iter().any(|item| item == &value) => Some(value),
                    (_, false) => sni_options.first().cloned(),
                    (value, true) => value,
                };

                let root = singbox_lx_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("setting.json");
                let mut normalized = json!({"enabled": enabled, "port": if port == 0 { 1180 } else { port }});
                if let Some(value) = selected_sni {
                    normalized["sni"] = json!(value);
                }
                if !sni_options.is_empty() {
                    normalized["sni_options"] = json!(sni_options);
                }
                write_json_pretty(&p, &normalized)?;
                crate::programs::singbox::lx::normalize_config_for_profile_server(profile, server)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box-lx", "profiles", profile, "servers", server, "config"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let root = singbox_lx_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("config.json");
                if !p.exists() {
                    write_text_atomic(&p, "")?;
                }
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box-lx", "profiles", profile, "servers", server, "config"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let root = singbox_lx_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("config.json");
                write_text_atomic(&p, &req.content)?;
                if !req.content.trim().is_empty() {
                    crate::programs::singbox::lx::normalize_config_for_profile_server(profile, server)?;
                }
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
