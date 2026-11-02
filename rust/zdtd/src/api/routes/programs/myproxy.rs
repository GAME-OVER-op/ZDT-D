use crate::api::support::profile_crud::{profile_list_response, set_profile_enabled, remove_profile_from_active, archive_profile_dir};
use anyhow::{Result};
use serde::{Deserialize};
use serde_json::json;
use std::{collections::{HashMap}, net::TcpStream};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{
    create_myproxy_profile_named, create_myproxy_profile_next, default_myproxy_profile_setting_value, default_myproxy_proxy_value, ensure_myproxy_profile_layout, ensure_valid_singbox_profile_name,
    myproxy_active_path, myproxy_deleted_profiles_root, myproxy_profile_root, normalize_myproxy_proxy_json, refresh_apps_after_save_if_running, suggest_myproxy_profile_ports,
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
        // --- myproxy profile API
        ("GET", ["api", "programs", "myproxy", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                Ok(profile_list_response(&myproxy_active_path()))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "myproxy", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)] struct CreateReq { #[serde(default)] name: Option<String> }
                let req: CreateReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_myproxy_profile_named(name)?,
                    None => create_myproxy_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myproxy", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                set_profile_enabled(&myproxy_active_path(), profile, req.enabled)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("DELETE", ["api", "programs", "myproxy", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                remove_profile_from_active(&myproxy_active_path(), profile, false)?;
                invalidate_assignment_cache();
                archive_profile_dir(&myproxy_profile_root(profile), &myproxy_deleted_profiles_root(), profile);
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myproxy", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myproxy_profile_layout(profile)?;
                let p = myproxy_profile_root(profile).join("setting.json");
                if !p.exists() {
                    let (t2s_port, t2s_web_port) = suggest_myproxy_profile_ports()?;
                    write_json_pretty(&p, &default_myproxy_profile_setting_value(t2s_port, t2s_web_port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myproxy", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myproxy_profile_layout(profile)?;
                let v: serde_json::Value = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let t2s_port = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).ok_or_else(|| anyhow::anyhow!("t2s_port is required"))?;
                let t2s_web_port = v.get("t2s_web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).ok_or_else(|| anyhow::anyhow!("t2s_web_port is required"))?;
                if t2s_port == 0 || t2s_web_port == 0 || t2s_port == t2s_web_port { anyhow::bail!("invalid ports"); }
                let proxy_path = myproxy_profile_root(profile).join("proxy.json");
                if let Ok(proxy_cfg) = read_json::<crate::programs::myproxy::ProxyConfig>(&proxy_path) {
                    let upstream_ports = proxy_cfg.effective_ports()?;
                    if upstream_ports.iter().any(|p| *p == t2s_port || *p == t2s_web_port) {
                        anyhow::bail!("t2s ports must not match upstream port");
                    }
                }
                let p = myproxy_profile_root(profile).join("setting.json");
                write_json_pretty(&p, &v)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myproxy", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myproxy_profile_layout(profile)?;
                let p = myproxy_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myproxy", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myproxy_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/myproxy/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "myproxy", "common")?;
                let p = myproxy_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "myproxy", Some(profile), "common")?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myproxy", "profiles", profile, "proxy"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myproxy_profile_layout(profile)?;
                let p = myproxy_profile_root(profile).join("proxy.json");
                if !p.exists() { write_json_pretty(&p, &default_myproxy_proxy_value())?; }
                let v: serde_json::Value = read_json(&p)?;
                let normalized = normalize_myproxy_proxy_json(v)?;
                write_json_pretty(&p, &normalized)?;
                Ok(json!({"ok": true, "data": normalized}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myproxy", "profiles", profile, "proxy"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myproxy_profile_layout(profile)?;
                let proxy_cfg: crate::programs::myproxy::ProxyConfig = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let proxy_cfg = crate::programs::myproxy::normalize_proxy_config_defaults(proxy_cfg)?;
                crate::programs::myproxy::validate_proxy_config(&proxy_cfg)?;
                let setting_path = myproxy_profile_root(profile).join("setting.json");
                if let Ok(setting_v) = read_json::<serde_json::Value>(&setting_path) {
                    let t2s_port = setting_v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
                    let t2s_web_port = setting_v.get("t2s_web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
                    let upstream_ports = proxy_cfg.effective_ports()?;
                    if upstream_ports.iter().any(|p| *p == t2s_port || *p == t2s_web_port) {
                        anyhow::bail!("upstream port must not match t2s ports");
                    }
                }
                let p = myproxy_profile_root(profile).join("proxy.json");
                write_json_pretty(&p, &proxy_cfg)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }

        _ => write_empty_404(stream),
    }
}
