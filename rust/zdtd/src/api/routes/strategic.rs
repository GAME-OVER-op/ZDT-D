use anyhow::{Context, Result};
use serde::Deserialize;
use serde_json::json;
use std::{collections::HashMap, fs, net::TcpStream, path::{Path, PathBuf}};

use crate::settings;
use super::super::http::write_json;
use super::super::support::fs::{
    chmod_best_effort, read_text, sha256_hex_bytes, write_bytes_atomic, write_text_atomic,
};
use super::super::support::multipart::parse_multipart_file;
use super::super::support::paths::{decode_url_component, ensure_safe_filename, ensure_safe_segment};
use super::super::ContentReq;
use super::programs::shared::profile_root;

fn strategic_root() -> PathBuf {
    Path::new(settings::MODULE_DIR).join("strategic")
}

fn strategicvar_root() -> PathBuf {
    strategic_root().join("strategicvar")
}

fn is_allowed_strategicvar_program(id: &str) -> bool {
    matches!(id, "nfqws" | "nfqws2" | "dpitunnel" | "byedpi")
}

fn list_txt_files_only(dir: &Path) -> Result<Vec<String>> {
    let mut out = Vec::new();
    if !dir.exists() {
        // Don't create by default: variants are shipped with the module.
        return Ok(out);
    }
    for ent in fs::read_dir(dir)
        .map_err(|e| anyhow::anyhow!("readdir failed {}: {e}", dir.display()))?
    {
        let ent = ent?;
        let p = ent.path();
        if p.is_file() {
            let name = ent.file_name();
            let name = name.to_string_lossy();
            if name.ends_with(".txt") {
                out.push(name.to_string());
            }
        }
    }
    out.sort();
    Ok(out)
}




fn list_files_only(dir: &Path) -> Result<Vec<String>> {
    let mut out = Vec::new();
    if !dir.exists() {
        fs::create_dir_all(dir).ok();
    }
    for ent in fs::read_dir(dir)
        .map_err(|e| anyhow::anyhow!("readdir failed {}: {e}", dir.display()))? {
        let ent = ent?;
        let p = ent.path();
        if p.is_file() {
            if let Some(name) = ent.file_name().to_str() {
                out.push(name.to_string());
            }
        }
    }
    out.sort();
    Ok(out)
}

pub(in crate::api) fn handle_strategic(stream: TcpStream, method: &str, path: &str, headers: &HashMap<String, String>, body: &[u8]) -> Result<()> {
    // Routes:
    //   GET    /api/strategic/{list|lua|bin}
    //   GET    /api/strategic/{list|lua}/{name}
    //   PUT    /api/strategic/{list|lua}/{name}   (JSON {content})
    //   DELETE /api/strategic/{list|lua|bin}/{name}
    //   POST   /api/strategic/{list|lua|bin}/upload   (multipart/form-data)
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    let res = (|| -> Result<serde_json::Value> {
        let kind = seg.get(2).copied().ok_or_else(|| anyhow::anyhow!("bad path"))?;
        if !matches!(kind, "list" | "lua" | "bin") {
            anyhow::bail!("unknown strategic dir");
        }

        let base = strategic_root().join(kind);
        // Ensure base exists (best effort).
        fs::create_dir_all(&base).ok();

        const STRATEGIC_TEXT_LIMIT: u64 = 200 * 1024; // 200KB safeguard for app/UI

        fn file_len(p: &Path) -> Result<u64> {
            Ok(fs::metadata(p)?.len())
        }

        match (method, seg.as_slice()) {
            ("GET", ["api", "strategic", _]) => {
                let files = list_files_only(&base)?;
                // Provide sizes for UI so it can avoid loading huge files.
                let mut sizes = serde_json::Map::new();
                for name in &files {
                    let p = base.join(name);
                    if let Ok(len) = file_len(&p) {
                        sizes.insert(name.clone(), json!(len));
                    }
                }
                Ok(json!({"ok": true, "files": files, "sizes": sizes, "limit": STRATEGIC_TEXT_LIMIT}))
            }
            ("POST", ["api", "strategic", _, "upload"]) => {
                // Upload a file. International display names are allowed, but
                // path separators/control characters are still rejected by the
                // multipart parser. Text assets are converted to canonical UTF-8
                // so later editing/saving never depends on the source encoding.
                let f = parse_multipart_file(headers, body)?;
                let dst = base.join(&f.filename);
                if dst.parent() != Some(base.as_path()) {
                    anyhow::bail!("invalid destination");
                }
                if kind == "bin" {
                    write_bytes_atomic(&dst, &f.data)?;
                    chmod_best_effort(&dst, 0o755);
                } else {
                    let content = crate::common::fs::external_text::decode_external_text(&f.data)
                        .with_context(|| format!("decode imported strategic file {}", f.filename))?;
                    write_text_atomic(&dst, &content)?;
                    chmod_best_effort(&dst, 0o644);
                }
                Ok(json!({"ok": true, "filename": f.filename}))
            }
            ("GET", ["api", "strategic", "bin", _name]) => {
                anyhow::bail!("bin files are not text-readable via API");
            }
            ("GET", ["api", "strategic", _, name]) => {
                if kind == "bin" {
                    anyhow::bail!("bin files are not text-readable via API");
                }
                let name = decode_url_component(name)?;
                ensure_safe_filename(&name)?;
                let p = base.join(&name);
                if !p.is_file() {
                    anyhow::bail!("file not found");
                }
                // Safety: don't read huge files into memory.
                let len = file_len(&p).unwrap_or(0);
                if len > STRATEGIC_TEXT_LIMIT {
                    return Ok(json!({"ok": false, "error": "too_large", "size": len, "limit": STRATEGIC_TEXT_LIMIT}));
                }
                let content = read_text(&p)?;
                Ok(json!({"ok": true, "content": content}))
            }
            ("PUT", ["api", "strategic", _, name]) => {
                if kind == "bin" {
                    anyhow::bail!("bin files cannot be edited as text");
                }
                let name = decode_url_component(name)?;
                ensure_safe_filename(&name)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let content = crate::common::fs::external_text::normalize_text(&req.content)?;
                let content_len = content.as_bytes().len() as u64;
                if content_len > STRATEGIC_TEXT_LIMIT {
                    return Ok(json!({"ok": false, "error": "too_large", "size": content_len, "limit": STRATEGIC_TEXT_LIMIT}));
                }
                let p = base.join(&name);
                write_text_atomic(&p, &content)?;
                chmod_best_effort(&p, 0o644);
                Ok(json!({"ok": true}))
            }
            ("DELETE", ["api", "strategic", _, name]) => {
                let name = decode_url_component(name)?;
                ensure_safe_filename(&name)?;
                let p = base.join(&name);
                if p.exists() {
                    fs::remove_file(&p)
                        .map_err(|e| anyhow::anyhow!("remove failed {}: {e}", p.display()))?;
                }
                Ok(json!({"ok": true}))
            }
            _ => anyhow::bail!("not found"),
        }
    })();

    match res {
        Ok(v) => write_json(stream, 200, v),
        Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
    }
}

#[derive(Debug, Deserialize)]
struct ApplyStrategicVarReq {
    program: String,
    profile: String,
    file: String,
}

pub(in crate::api) fn handle_strategicvar(stream: TcpStream, method: &str, path: &str, body: &[u8]) -> Result<()> {
    // Routes:
    //   GET  /api/strategicvar/{program}
    //   POST /api/strategicvar/apply   (JSON {program, profile, file})
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();
    let res = (|| -> Result<serde_json::Value> {
        match (method, seg.as_slice()) {
            ("GET", ["api", "strategicvar", program]) => {
                ensure_safe_segment(program, "program")?;
                if !is_allowed_strategicvar_program(program) {
                    anyhow::bail!("unknown program");
                }
                let base = strategicvar_root().join(program);
                let files = list_txt_files_only(&base)?;

                // Provide optional metadata (sha256) so the app can label configs
                // as built-in strategy vs user config, without downloading file contents.
                let mut meta = Vec::new();
                for name in &files {
                    let p = base.join(name);
                    if let Ok(data) = fs::read(&p) {
                        meta.push(json!({
                            "name": name,
                            "sha256": sha256_hex_bytes(&data),
                        }));
                    }
                }

                Ok(json!({"ok": true, "files": files, "meta": meta}))
            }
            ("POST", ["api", "strategicvar", "apply"]) => {
                let req: ApplyStrategicVarReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;

                ensure_safe_segment(&req.program, "program")?;
                ensure_safe_segment(&req.profile, "profile")?;
                ensure_safe_filename(&req.file)?;
                if !req.file.ends_with(".txt") {
                    anyhow::bail!("strategy file must be .txt");
                }
                if !is_allowed_strategicvar_program(&req.program) {
                    anyhow::bail!("unknown program");
                }

                // Ensure profile exists.
                let prof_root = profile_root(&req.program, &req.profile);
                if !prof_root.exists() {
                    anyhow::bail!("profile not found");
                }

                // Read strategy.
                let src = strategicvar_root().join(&req.program).join(&req.file);
                if !src.is_file() {
                    anyhow::bail!("strategy not found");
                }
                let data = fs::read(&src)
                    .map_err(|e| anyhow::anyhow!("read failed {}: {e}", src.display()))?;

                // Write to profile config.
                let dst = prof_root.join("config/config.txt");
                write_bytes_atomic(&dst, &data)?;
                Ok(json!({"ok": true}))
            }
            _ => anyhow::bail!("not found"),
        }
    })();

    match res {
        Ok(v) => write_json(stream, 200, v),
        Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
    }
}

