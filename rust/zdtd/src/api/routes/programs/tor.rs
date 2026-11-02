use anyhow::{Result};
use serde_json::json;
use std::{collections::{HashMap}, net::TcpStream};
use crate::features::stats;
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{refresh_apps_after_save_if_running};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::super::cache::{invalidate_assignment_cache};
use super::super::super::support::apps::{validate_program_apps_content};

pub(in crate::api) fn handle(
    stream: TcpStream,
    method: &str,
    seg: &[&str],
    _headers: &HashMap<String, String>,
    body: &[u8],
    services_running: bool,
) -> Result<()> {
    match (method, seg) {
        // --- tor enabled/apps/config
        ("GET", ["api", "programs", "tor"]) => {
            let res = (|| -> Result<serde_json::Value> {
                fn tor_loading_json(stage: &str, err: &anyhow::Error) -> serde_json::Value {
                    json!({
                        "ok": true,
                        "ready": false,
                        "state": "loading",
                        "retry_after_ms": 300,
                        "message": format!("Tor state is preparing ({stage}): {err}"),
                    })
                }

                if let Err(e) = crate::programs::tor::ensure_layout() {
                    return Ok(tor_loading_json("layout", &e));
                }

                let enabled = match crate::programs::tor::load_enabled_json() {
                    Ok(v) => v.is_enabled(),
                    Err(e) => return Ok(tor_loading_json("enabled", &e)),
                };
                let apps = match crate::programs::tor::read_uid_program_text() {
                    Ok(v) => v,
                    Err(e) => return Ok(tor_loading_json("apps", &e)),
                };
                let setting = match crate::programs::tor::load_setting() {
                    Ok(v) => v,
                    Err(e) => return Ok(tor_loading_json("setting", &e)),
                };
                let torrc = match crate::programs::tor::read_torrc_text() {
                    Ok(v) => v,
                    Err(e) => return Ok(tor_loading_json("torrc", &e)),
                };
                let socks_port = crate::programs::tor::parse_socks_port_from_str(&torrc).ok();
                let bridges = crate::programs::tor::bridge_count_from_str(&torrc);
                let active = stats::collect_status().map(|r| r.tor.count > 0).unwrap_or(false);
                Ok(json!({
                    "ok": true,
                    "ready": true,
                    "state": "ready",
                    "enabled": enabled,
                    "apps": apps,
                    "setting": setting,
                    "torrc": torrc,
                    "socks_port": socks_port,
                    "bridge_count": bridges,
                    "active": active,
                }))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "tor", "enabled"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::programs::tor::load_enabled_json()?.is_enabled();
                Ok(json!({"ok": true, "enabled": enabled}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "tor", "enabled"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                if req.enabled {
                    crate::programs::tor::validate_enable_toggle_requirements()?;
                }
                let v = crate::programs::tor::save_enabled_value(req.enabled)?;
                Ok(json!({"ok": true, "enabled": v.is_enabled()}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "tor", "apps"]) => {
            let res = crate::programs::tor::read_uid_program_text();
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "tor", "apps"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_program_apps_content(&req.content, "/api/programs/tor/apps", "tor", "common")?;
                crate::programs::tor::write_uid_program_text(&req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "tor", None, "common")?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "tor", "setting"]) => {
            let res = crate::programs::tor::load_setting();
            match res { Ok(v) => write_json(stream, 200, json!({"ok": true, "data": v})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "tor", "setting"]) => {
            let res = (|| -> Result<()> {
                let setting: crate::programs::tor::Setting = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                if setting.t2s_port == 0 || setting.t2s_web_port == 0 || setting.t2s_port == setting.t2s_web_port {
                    anyhow::bail!("invalid t2s ports");
                }
                if let Ok(torrc) = crate::programs::tor::read_torrc_text() {
                    if let Ok(socks_port) = crate::programs::tor::parse_socks_port_from_str(&torrc) {
                        if setting.t2s_port == socks_port || setting.t2s_web_port == socks_port {
                            anyhow::bail!("t2s ports must not match SocksPort");
                        }
                    }
                }
                crate::programs::tor::save_setting(&setting)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "tor", "torrc"]) => {
            let res = crate::programs::tor::read_torrc_text();
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "tor", "torrc"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::tor::write_torrc_text(&req.content)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "tor", "apply"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::programs::tor::load_enabled_json()?.is_enabled();
                if enabled {
                    crate::programs::tor::validate_enable_requirements()?;
                }
                let _ = crate::programs::tor::rebuild_out_program()?;
                let active = enabled && stats::collect_status().map(|r| r.tor.count > 0).unwrap_or(false);
                Ok(json!({"ok": true, "active": active}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }

        _ => write_empty_404(stream),
    }
}
