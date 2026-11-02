use anyhow::{Result};
use serde_json::json;
use std::{collections::{HashMap}, fs, net::TcpStream, time::{SystemTime, UNIX_EPOCH}};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{active_json_path, profile_root, program_root, refresh_apps_after_save_if_running};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::super::cache::{invalidate_assignment_cache};
use super::super::super::support::apps::{slot_from_kind, validate_program_apps_content};
use super::super::super::support::fs::{
    read_json, read_text, read_text_or_empty, write_json_pretty, write_text_atomic,
};
use super::super::super::support::paths::{ensure_safe_segment};
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
        // --- profiles: enable/disable
        ("PUT", ["api", "programs", id @ ("nfqws" | "nfqws2" | "byedpi" | "dpitunnel"), "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                ensure_safe_segment(id, "program id")?;
                ensure_safe_segment(profile, "profile name")?;
                if !matches!(*id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
                    anyhow::bail!("program has no profiles");
                }
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = active_json_path(id);
                let mut active: ProfilesActive = read_json(&p)?;
                let st = active.profiles.get_mut(*profile)
                    .ok_or_else(|| anyhow::anyhow!("profile not found"))?;
                st.enabled = req.enabled;
                write_json_pretty(&p, &active)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- profiles: delete (soft delete by moving to .deleted/)
        ("DELETE", ["api", "programs", id @ ("nfqws" | "nfqws2" | "byedpi" | "dpitunnel"), "profiles", profile]) => {
            let res = (|| -> Result<()> {
                ensure_safe_segment(id, "program id")?;
                ensure_safe_segment(profile, "profile name")?;
                if !matches!(*id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
                    anyhow::bail!("program has no profiles");
                }
                let p = active_json_path(id);
                let mut active: ProfilesActive = read_json(&p)?;
                if active.profiles.remove(*profile).is_none() {
                    anyhow::bail!("profile not found");
                }
                write_json_pretty(&p, &active)?;

                let src = profile_root(id, profile);
                if src.exists() {
                    let deleted_dir = program_root(id).join(".deleted");
                    fs::create_dir_all(&deleted_dir).ok();
                    let ts = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_secs();
                    let dst = deleted_dir.join(format!("{profile}.{ts}"));
                    fs::rename(&src, &dst)
                        .map_err(|e| anyhow::anyhow!("move failed {} -> {}: {e}", src.display(), dst.display()))?;
                }
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- profiles: config (text)
        ("GET", ["api", "programs", id @ ("nfqws" | "nfqws2" | "byedpi" | "dpitunnel"), "profiles", profile, "config"]) => {
            let res = (|| -> Result<String> {
                ensure_safe_segment(id, "program id")?;
                ensure_safe_segment(profile, "profile name")?;
                if !matches!(*id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
                    anyhow::bail!("program has no profiles");
                }
                let p = profile_root(id, profile).join("config/config.txt");
                read_text(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", id @ ("nfqws" | "nfqws2" | "byedpi" | "dpitunnel"), "profiles", profile, "config"]) => {
            let res = (|| -> Result<()> {
                ensure_safe_segment(id, "program id")?;
                ensure_safe_segment(profile, "profile name")?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                if !matches!(*id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
                    anyhow::bail!("program has no profiles");
                }
                let p = profile_root(id, profile).join("config/config.txt");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- profiles: apps lists (text)
        ("GET", ["api", "programs", id @ ("nfqws" | "nfqws2" | "byedpi" | "dpitunnel"), "profiles", profile, "apps", kind]) => {
            let res = (|| -> Result<String> {
                ensure_safe_segment(id, "program id")?;
                ensure_safe_segment(profile, "profile name")?;
                ensure_safe_segment(kind, "apps kind")?;
                if !matches!(*id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
                    anyhow::bail!("program has no profiles");
                }
                let fname = match (*id, *kind) {
                    ("nfqws", "user") => "user_program",
                    ("nfqws", "mobile") => "mobile_program",
                    ("nfqws", "wifi") => "wifi_program",
                    ("nfqws2", "user") => "user_program",
                    ("nfqws2", "mobile") => "mobile_program",
                    ("nfqws2", "wifi") => "wifi_program",
                    ("byedpi", "user") => "user_program",
                    ("dpitunnel", "user") => "user_program",
                    ("dpitunnel", "mobile") => "mobile_program",
                    ("dpitunnel", "wifi") => "wifi_program",
                    _ => anyhow::bail!("invalid apps kind for program"),
                };
                let p = profile_root(id, profile).join(format!("app/uid/{fname}"));
                if *id == "dpitunnel" {
                    read_text_or_empty(&p)
                } else {
                    read_text(&p)
                }
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", id @ ("nfqws" | "nfqws2" | "byedpi" | "dpitunnel"), "profiles", profile, "apps", kind]) => {
            let res = (|| -> Result<()> {
                ensure_safe_segment(id, "program id")?;
                ensure_safe_segment(profile, "profile name")?;
                ensure_safe_segment(kind, "apps kind")?;
                if !matches!(*id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
                    anyhow::bail!("program has no profiles");
                }
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let fname = match (*id, *kind) {
                    ("nfqws", "user") => "user_program",
                    ("nfqws", "mobile") => "mobile_program",
                    ("nfqws", "wifi") => "wifi_program",
                    ("nfqws2", "user") => "user_program",
                    ("nfqws2", "mobile") => "mobile_program",
                    ("nfqws2", "wifi") => "wifi_program",
                    ("byedpi", "user") => "user_program",
                    ("dpitunnel", "user") => "user_program",
                    ("dpitunnel", "mobile") => "mobile_program",
                    ("dpitunnel", "wifi") => "wifi_program",
                    _ => anyhow::bail!("invalid apps kind for program"),
                };
                let api_path = format!("/api/programs/{}/profiles/{}/apps/{}", id, profile, kind);
                let slot = slot_from_kind(kind).ok_or_else(|| anyhow::anyhow!("invalid apps kind"))?;
                validate_program_apps_content(&req.content, &api_path, id, slot)?;
                let p = profile_root(id, profile).join(format!("app/uid/{fname}"));
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                refresh_apps_after_save_if_running(services_running, id, Some(profile), slot)?;
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
