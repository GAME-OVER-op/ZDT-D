use crate::api::support::profile_crud::{profile_list_response, set_profile_enabled, remove_profile_from_active, archive_profile_dir};
use anyhow::{Result};
use serde::{Deserialize};
use serde_json::json;
use std::{collections::{HashMap}, fs, net::TcpStream, time::{SystemTime, UNIX_EPOCH}};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{
    create_wireproxy_profile_named, create_wireproxy_profile_next, create_wireproxy_server_named, create_wireproxy_server_next, default_wireproxy_profile_setting_value, default_wireproxy_server_setting_value,
    ensure_valid_singbox_profile_name, ensure_wireproxy_profile_layout, refresh_apps_after_save_if_running, suggest_wireproxy_profile_ports, wireproxy_active_path, wireproxy_deleted_profiles_root,
    wireproxy_deleted_servers_root, wireproxy_profile_root, wireproxy_server_root,
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
        // --- wireproxy profile/server API
        ("GET", ["api", "programs", "wireproxy", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                Ok(profile_list_response(&wireproxy_active_path()))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "wireproxy", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct CreateReq {
                    #[serde(default)]
                    name: Option<String>,
                }
                let req: CreateReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_wireproxy_profile_named(name)?,
                    None => create_wireproxy_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "wireproxy", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                set_profile_enabled(&wireproxy_active_path(), profile, req.enabled)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "wireproxy", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                remove_profile_from_active(&wireproxy_active_path(), profile, false)?;
                invalidate_assignment_cache();
                archive_profile_dir(&wireproxy_profile_root(profile), &wireproxy_deleted_profiles_root(), profile);
                crate::features::subscriptions::remove_links_for_profile("wireproxy", profile);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "wireproxy", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_wireproxy_profile_layout(profile)?;
                let p = wireproxy_profile_root(profile).join("setting.json");
                if !p.exists() {
                    let (t2s_port, t2s_web_port) = suggest_wireproxy_profile_ports()?;
                    write_json_pretty(&p, &default_wireproxy_profile_setting_value(t2s_port, t2s_web_port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "wireproxy", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_wireproxy_profile_layout(profile)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let t2s_port = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok())
                    .ok_or_else(|| anyhow::anyhow!("t2s_port is required"))?;
                let t2s_web_port = v.get("t2s_web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok())
                    .ok_or_else(|| anyhow::anyhow!("t2s_web_port is required"))?;
                if t2s_port == 0 || t2s_web_port == 0 || t2s_port == t2s_web_port {
                    anyhow::bail!("invalid ports");
                }
                let p = wireproxy_profile_root(profile).join("setting.json");
                write_json_pretty(&p, &v)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "wireproxy", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_wireproxy_profile_layout(profile)?;
                let p = wireproxy_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "wireproxy", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_wireproxy_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/wireproxy/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "wireproxy", "common")?;
                let p = wireproxy_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "wireproxy", Some(profile), "common")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "wireproxy", "profiles", profile, "servers"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_wireproxy_profile_layout(profile)?;
                let root = wireproxy_profile_root(profile).join("server");
                let mut servers = Vec::new();
                if let Ok(rd) = fs::read_dir(&root) {
                    for ent in rd.flatten() {
                        let path = ent.path();
                        if !path.is_dir() { continue; }
                        let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue; };
                        if name.starts_with('.') { continue; }
                        let setting_path = path.join("setting.json");
                        let data: serde_json::Value = read_json(&setting_path).unwrap_or_else(|_| default_wireproxy_server_setting_value());
                        let config_path = path.join("config.conf");
                        let bind = fs::read_to_string(&config_path)
                            .ok()
                            .and_then(|raw| crate::programs::wireproxy::parse_socks5_bind_address_str(&raw).ok())
                            .map(|v| json!({"host": v.host, "port": v.port}));
                        let subscription_link = crate::features::subscriptions::link_for_target("wireproxy", profile, name);
                        servers.push(json!({"name": name, "data": data, "bind": bind, "subscription_link": subscription_link}));
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
        ("POST", ["api", "programs", "wireproxy", "profiles", profile, "servers"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct CreateReq {
                    #[serde(default)]
                    name: Option<String>,
                }
                ensure_valid_singbox_profile_name(profile)?;
                let req: CreateReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let server = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_wireproxy_server_named(profile, name)?,
                    None => create_wireproxy_server_next(profile)?,
                };
                Ok(json!({"ok": true, "server": server}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "wireproxy", "profiles", profile, "servers", server]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let src = wireproxy_server_root(profile, server);
                if !src.exists() {
                    anyhow::bail!("server not found");
                }
                let deleted_dir = wireproxy_deleted_servers_root(profile);
                fs::create_dir_all(&deleted_dir).ok();
                let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                let dst = deleted_dir.join(format!("{server}.{ts}"));
                let _ = fs::rename(&src, &dst);
                crate::features::subscriptions::remove_link_for_target("wireproxy", profile, server);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "wireproxy", "profiles", profile, "servers", server, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                ensure_wireproxy_profile_layout(profile)?;
                let root = wireproxy_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("setting.json");
                if !p.exists() {
                    write_json_pretty(&p, &default_wireproxy_server_setting_value())?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "wireproxy", "profiles", profile, "servers", server, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let enabled = crate::common::fs::json::json_enabled_opt(v.get("enabled"))
                    .ok_or_else(|| anyhow::anyhow!("enabled is required"))?;
                let root = wireproxy_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("setting.json");
                write_json_pretty(&p, &json!({"enabled": enabled}))?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "wireproxy", "profiles", profile, "servers", server, "config"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let root = wireproxy_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("config.conf");
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
        ("PUT", ["api", "programs", "wireproxy", "profiles", profile, "servers", server, "config"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let root = wireproxy_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let raw = req.content.trim();
                if !raw.is_empty() {
                    let bind = crate::programs::wireproxy::parse_socks5_bind_address_str(raw)?;
                    if bind.host != "127.0.0.1" {
                        anyhow::bail!("Socks5 BindAddress host must be 127.0.0.1");
                    }
                }
                let p = root.join("config.conf");
                write_text_atomic(&p, &req.content)?;
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
