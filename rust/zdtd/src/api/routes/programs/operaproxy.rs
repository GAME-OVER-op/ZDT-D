use anyhow::{Result};
use serde_json::json;
use std::{collections::{HashMap}, net::TcpStream};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{active_json_path, program_root, refresh_apps_after_save_if_running};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::super::cache::{invalidate_assignment_cache};
use super::super::super::support::apps::{validate_program_apps_content};
use super::super::super::support::fs::{
    read_json, read_text, read_text_or_empty, write_json_pretty, write_text_atomic,
};
use super::super::super::support::model::{EnabledActive};

pub(in crate::api) fn handle(
    stream: TcpStream,
    method: &str,
    seg: &[&str],
    _headers: &HashMap<String, String>,
    body: &[u8],
    services_running: bool,
) -> Result<()> {
    match (method, seg) {
        // --- operaproxy enabled
        ("GET", ["api", "programs", "operaproxy", "enabled"]) => {
            let p = active_json_path("operaproxy");
            let active: EnabledActive = read_json(&p).unwrap_or_default();
            write_json(stream, 200, json!({"ok": true, "enabled": active.enabled}))
        }
        ("PUT", ["api", "programs", "operaproxy", "enabled"]) => {
            let res = (|| -> Result<()> {
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = active_json_path("operaproxy");
                let mut active: EnabledActive = read_json(&p).unwrap_or_default();
                active.enabled = req.enabled;
                write_json_pretty(&p, &active)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- operaproxy apps lists
        ("GET", ["api", "programs", "operaproxy", "apps", "user"]) => {
            let p = program_root("operaproxy").join("app/uid/user_program");
            let res = read_text_or_empty(&p);
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "apps", "user"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_program_apps_content(&req.content, "/api/programs/operaproxy/apps/user", "operaproxy", "common")?;
                let p = program_root("operaproxy").join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "operaproxy", None, "common")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        ("GET", ["api", "programs", "operaproxy", "apps", "mobile"]) => {
            let p = program_root("operaproxy").join("app/uid/mobile_program");
            let res = read_text_or_empty(&p);
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "apps", "mobile"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_program_apps_content(&req.content, "/api/programs/operaproxy/apps/mobile", "operaproxy", "mobile")?;
                let p = program_root("operaproxy").join("app/uid/mobile_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "operaproxy", None, "mobile")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        ("GET", ["api", "programs", "operaproxy", "apps", "wifi"]) => {
            let p = program_root("operaproxy").join("app/uid/wifi_program");
            let res = read_text_or_empty(&p);
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "apps", "wifi"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_program_apps_content(&req.content, "/api/programs/operaproxy/apps/wifi", "operaproxy", "wifi")?;
                let p = program_root("operaproxy").join("app/uid/wifi_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, "operaproxy", None, "wifi")?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- operaproxy sni.json
        ("GET", ["api", "programs", "operaproxy", "sni"]) => {
            let p = program_root("operaproxy").join("config/sni.json");
            let res = read_text(&p);
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "sni"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = program_root("operaproxy").join("config/sni.json");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- operaproxy server (config/server.txt, one line: EU | AS | AM)
        ("GET", ["api", "programs", "operaproxy", "server"]) => {
            let p = program_root("operaproxy").join("config/server.txt");
            let res = (|| -> Result<String> {
                // If missing or invalid -> default EU
                let raw = read_text_or_empty(&p)?;
                let tok = raw
                    .split_whitespace()
                    .next()
                    .unwrap_or("")
                    .to_ascii_uppercase();
                let server = match tok.as_str() {
                    "EU" | "AS" | "AM" => tok,
                    _ => "EU".to_string(),
                };
                // Keep file normalized on disk (optional but nice).
                write_text_atomic(&p, &format!("{}\n", server))?;
                Ok(format!("{}\n", server))
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "server"]) => {
            let p = program_root("operaproxy").join("config/server.txt");
            let res = (|| -> Result<String> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let tok = req
                    .content
                    .split_whitespace()
                    .next()
                    .unwrap_or("")
                    .to_ascii_uppercase();
                let server = match tok.as_str() {
                    "EU" | "AS" | "AM" => tok,
                    _ => "EU".to_string(),
                };
                write_text_atomic(&p, &format!("{}\n", server))?;
                Ok(format!("{}\n", server))
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }

        // --- operaproxy byedpi args
        ("GET", ["api", "programs", "operaproxy", "byedpi", "start_args"]) => {
            let p = program_root("operaproxy").join("byedpi/config/start.txt");
            let res = read_text(&p);
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "byedpi", "start_args"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = program_root("operaproxy").join("byedpi/config/start.txt");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "operaproxy", "byedpi", "restart_args"]) => {
            let p = program_root("operaproxy").join("byedpi/config/restart.txt");
            let res = read_text(&p);
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "byedpi", "restart_args"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = program_root("operaproxy").join("byedpi/config/restart.txt");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- operaproxy ports (port.json)
        ("GET", ["api", "programs", "operaproxy", "ports"]) => {
            let p = program_root("operaproxy").join("port.json");
            let res: Result<serde_json::Value> = read_json(&p);
            match res {
                Ok(v) => write_json(stream, 200, json!({"ok": true, "data": v})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "ports"]) => {
            let res = (|| -> Result<()> {
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = program_root("operaproxy").join("port.json");
                write_json_pretty(&p, &v)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- operaproxy args (config/args.json) ---
        ("GET", ["api", "programs", "operaproxy", "args"]) => {
            let args = crate::programs::operaproxy::read_opera_args();
            match serde_json::to_value(&args) {
                Ok(v) => write_json(stream, 200, json!({"ok": true, "data": v})),
                Err(e) => write_err(stream, anyhow::anyhow!("serialize args: {e}")),
            }
        }
        ("PUT", ["api", "programs", "operaproxy", "args"]) => {
            let res = (|| -> Result<()> {
                let args: crate::programs::operaproxy::OperaArgs =
                    serde_json::from_slice(body)
                        .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                // Basic sanity checks
                if args.init_retry_interval.trim().is_empty() {
                    anyhow::bail!("init_retry_interval must not be empty");
                }
                if args.server_selection.trim().is_empty() {
                    anyhow::bail!("server_selection must not be empty");
                }
                if args.server_selection_test_url.trim().is_empty() {
                    anyhow::bail!("server_selection_test_url must not be empty");
                }
                crate::programs::operaproxy::write_opera_args(&args)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- operaproxy bootstrap DNS (config/bootstrap_dns.json) ---
        ("GET", ["api", "programs", "operaproxy", "bootstrap_dns"]) => {
            let resolvers = crate::programs::operaproxy::read_bootstrap_dns();
            // Return as JSON array split on comma for easy UI consumption
            let arr: serde_json::Value = resolvers
                .split(',')
                .map(|s| serde_json::Value::String(s.trim().to_string()))
                .collect::<Vec<_>>()
                .into();
            write_json(stream, 200, json!({"ok": true, "data": arr}))
        }
        ("PUT", ["api", "programs", "operaproxy", "bootstrap_dns"]) => {
            let res = (|| -> Result<()> {
                let arr: Vec<String> = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body (expected array of strings): {e}"))?;
                let clean: Vec<String> = arr.iter()
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect();
                if clean.is_empty() {
                    anyhow::bail!("resolver list must not be empty");
                }
                crate::programs::operaproxy::write_bootstrap_dns(&clean)?;
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
