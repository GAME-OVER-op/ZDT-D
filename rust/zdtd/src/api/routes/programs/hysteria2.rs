use crate::api::support::profile_crud::{profile_list_response, set_profile_enabled, remove_profile_from_active, archive_profile_dir};
use anyhow::{Result};
use serde::{Deserialize};
use serde_json::json;
use std::{collections::{HashMap}, fs, net::TcpStream, time::{SystemTime, UNIX_EPOCH}};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{
    create_hysteria2_profile_named, create_hysteria2_profile_next, create_hysteria2_server_named, create_hysteria2_server_next, default_hysteria2_profile_setting_value, default_hysteria2_server_setting_value,
    ensure_hysteria2_port_free, ensure_hysteria2_profile_layout, hysteria2_active_path, hysteria2_deleted_profiles_root, hysteria2_deleted_servers_root, hysteria2_profile_mode_is_vpn,
    hysteria2_profile_root, hysteria2_server_names, hysteria2_server_root, normalize_and_write_hysteria2_profile_setting, normalize_hysteria2_log_level, refresh_apps_after_save_if_running,
    suggest_hysteria2_profile_ports, suggest_hysteria2_server_port,
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
        // --- hysteria2 profile/server API
        ("GET", ["api", "programs", "hysteria2", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                Ok(profile_list_response(&hysteria2_active_path()))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "hysteria2", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                let req: Req = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_hysteria2_profile_named(name)?,
                    None => create_hysteria2_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "hysteria2", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                set_profile_enabled(&hysteria2_active_path(), profile, req.enabled)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "hysteria2", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                remove_profile_from_active(&hysteria2_active_path(), profile, true)?;
                invalidate_assignment_cache();
                archive_profile_dir(&hysteria2_profile_root(profile), &hysteria2_deleted_profiles_root(), profile);
                crate::features::subscriptions::remove_links_for_profile("hysteria2", profile);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "hysteria2", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                ensure_hysteria2_profile_layout(profile)?;
                let p = hysteria2_profile_root(profile).join("setting.json");
                if !p.exists() {
                    let (t2s_port, t2s_web_port) = suggest_hysteria2_profile_ports()?;
                    write_json_pretty(&p, &default_hysteria2_profile_setting_value(t2s_port, t2s_web_port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                let setting = crate::programs::hysteria2::normalize_setting_value(v)?;
                let normalized = serde_json::to_value(&setting)?;
                write_json_pretty(&p, &normalized)?;
                Ok(json!({"ok": true, "data": normalized}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "hysteria2", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                ensure_hysteria2_profile_layout(profile)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                normalize_and_write_hysteria2_profile_setting(profile, v)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "hysteria2", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                ensure_hysteria2_profile_layout(profile)?;
                let p = hysteria2_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "hysteria2", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                ensure_hysteria2_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/hysteria2/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "hysteria2", "common")?;
                let p = hysteria2_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "hysteria2", Some(profile), "common")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "hysteria2", "profiles", profile, "servers"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                ensure_hysteria2_profile_layout(profile)?;
                let root = hysteria2_profile_root(profile).join("server");
                let mut servers = Vec::new();
                if let Ok(rd) = fs::read_dir(&root) {
                    for ent in rd.flatten() {
                        let path = ent.path();
                        if !path.is_dir() { continue; }
                        let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue; };
                        if name.starts_with('.') { continue; }
                        let setting_path = path.join("setting.json");
                        let data: serde_json::Value = read_json(&setting_path).unwrap_or_else(|_| default_hysteria2_server_setting_value(11590));
                        let subscription_link = crate::features::subscriptions::link_for_target("hysteria2", profile, name);
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
        ("POST", ["api", "programs", "hysteria2", "profiles", profile, "servers"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                let req: Req = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let server = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_hysteria2_server_named(profile, name)?,
                    None => create_hysteria2_server_next(profile)?,
                };
                Ok(json!({"ok": true, "server": server}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "hysteria2", "profiles", profile, "servers", server]) => {
            let res = (|| -> Result<()> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                crate::programs::hysteria2::ensure_valid_profile_name(server)?;
                let src = hysteria2_server_root(profile, server);
                if !src.exists() {
                    anyhow::bail!("server not found");
                }
                let deleted_dir = hysteria2_deleted_servers_root(profile);
                fs::create_dir_all(&deleted_dir).ok();
                let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                let dst = deleted_dir.join(format!("{server}.{ts}"));
                let _ = fs::rename(&src, &dst);
                crate::features::subscriptions::remove_link_for_target("hysteria2", profile, server);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "hysteria2", "profiles", profile, "servers", server, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                crate::programs::hysteria2::ensure_valid_profile_name(server)?;
                ensure_hysteria2_profile_layout(profile)?;
                let root = hysteria2_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("setting.json");
                if !p.exists() {
                    let port = suggest_hysteria2_server_port()?;
                    write_json_pretty(&p, &default_hysteria2_server_setting_value(port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "hysteria2", "profiles", profile, "servers", server, "setting"]) => {
            let res = (|| -> Result<()> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                crate::programs::hysteria2::ensure_valid_profile_name(server)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let existing_path = hysteria2_server_root(profile, server).join("setting.json");
                let existing: serde_json::Value = read_json(&existing_path).unwrap_or_else(|_| default_hysteria2_server_setting_value(11590));
                let enabled = crate::common::fs::json::json_enabled_opt(v.get("enabled"))
                    .or_else(|| crate::common::fs::json::json_enabled_opt(existing.get("enabled")))
                    .unwrap_or(false);
                let mode_vpn = hysteria2_profile_mode_is_vpn(profile);
                if mode_vpn {
                    let names = hysteria2_server_names(profile)?;
                    if names.len() != 1 || names[0].as_str() != *server {
                        anyhow::bail!("hysteria2_vpn_requires_single_server: VPN-режим hysteria2 поддерживает только один сервер.");
                    }
                    if !enabled {
                        anyhow::bail!("hysteria2_vpn_requires_single_server: VPN-режим hysteria2 требует один включённый сервер.");
                    }
                }
                let port = v.get("socks5_port")
                    .and_then(|x| x.as_u64())
                    .and_then(|x| u16::try_from(x).ok())
                    .or_else(|| existing.get("socks5_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()))
                    .unwrap_or(11590);
                ensure_hysteria2_port_free(port, Some(profile), Some(server), "SOCKS5")?;
                let root = hysteria2_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("setting.json");
                let log_level = normalize_hysteria2_log_level(v.get("log_level").and_then(|x| x.as_str()).or_else(|| existing.get("log_level").and_then(|x| x.as_str())).unwrap_or("info"));
                write_json_pretty(&p, &json!({"enabled": enabled, "socks5_port": port, "log_level": log_level}))?;
                let cfg = root.join("config.json");
                if read_text_or_empty(&cfg).map(|t| !t.trim().is_empty()).unwrap_or(false) {
                    crate::programs::hysteria2::normalize_config_for_profile_server(profile, server)?;
                }
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "hysteria2", "profiles", profile, "servers", server, "config"]) => {
            let res = (|| -> Result<String> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                crate::programs::hysteria2::ensure_valid_profile_name(server)?;
                let root = hysteria2_server_root(profile, server);
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
        ("PUT", ["api", "programs", "hysteria2", "profiles", profile, "servers", server, "config"]) => {
            let res = (|| -> Result<()> {
                crate::programs::hysteria2::ensure_valid_profile_name(profile)?;
                crate::programs::hysteria2::ensure_valid_profile_name(server)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let root = hysteria2_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("config.json");
                write_text_atomic(&p, &req.content)?;
                if !req.content.trim().is_empty() {
                    crate::programs::hysteria2::normalize_config_for_profile_server(profile, server)?;
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
