use anyhow::{Result};
use serde_json::json;
use std::{collections::{HashMap}, net::TcpStream};
use super::super::super::{EnabledReq};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};

pub(in crate::api) fn handle(
    stream: TcpStream,
    method: &str,
    seg: &[&str],
    _headers: &HashMap<String, String>,
    body: &[u8],
    _services_running: bool,
) -> Result<()> {
    match (method, seg) {
        // --- tg-ws-proxy optional utility settings
        ("GET", ["api", "programs", "tgwsproxy", "enabled"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::programs::tgwsproxy::load_effective_enabled()?.enabled;
                Ok(json!({"ok": true, "enabled": enabled}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "tgwsproxy", "enabled"]) => {
            let res = (|| -> Result<()> {
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::tgwsproxy::save_enabled(req.enabled)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "tgwsproxy", "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let setting = crate::programs::tgwsproxy::read_setting()?;
                Ok(json!({"ok": true, "data": setting}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "tgwsproxy", "setting"]) => {
            let res = (|| -> Result<()> {
                let setting: crate::programs::tgwsproxy::Setting = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::tgwsproxy::write_setting(&setting)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "tgwsproxy", "command"]) => {
            let res = crate::programs::tgwsproxy::command_preview_json();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }

        _ => write_empty_404(stream),
    }
}
