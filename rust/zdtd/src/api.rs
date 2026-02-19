use anyhow::Result;
use serde::{Deserialize, Serialize};
use serde::de::DeserializeOwned;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, BTreeMap},
    fs,
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    path::{Component, Path, PathBuf},
    sync::{Arc, atomic::{AtomicUsize, Ordering}},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::{daemon, daemon::SharedState, settings, stats};

const MAX_HEADER: usize = 16 * 1024;
// Allow uploading strategic files (including binaries). The API is local-only and authenticated,
// but we still cap body size to avoid accidental memory blowups.
const MAX_BODY: usize = 32 * 1024 * 1024;

// Safety guard for dnscrypt setting files (some lists can be enormous and will crash the app/UI if returned whole).
// Per Danil: limit reads/edits to ~200KB.
const DNSCRYPT_SETTING_MAX_BYTES: u64 = 200 * 1024;

fn find_header_end(data: &[u8]) -> Option<usize> {
    if data.len() < 4 {
        return None;
    }
    for i in 0..=(data.len() - 4) {
        if &data[i..i + 4] == b"\r\n\r\n" {
            return Some(i + 4);
        }
    }
    None
}

fn parse_http_request(
    s: &mut TcpStream,
) -> Result<(String, String, HashMap<String, String>, Vec<u8>)> {
    s.set_read_timeout(Some(Duration::from_secs(2)))?;
    let mut data = Vec::with_capacity(2048);
    let mut buf = [0_u8; 1024];

    // Read until we have headers.
    loop {
        let n = s.read(&mut buf)?;
        if n == 0 {
            break;
        }
        data.extend_from_slice(&buf[..n]);
        if find_header_end(&data).is_some() {
            break;
        }
        if data.len() >= MAX_HEADER {
            anyhow::bail!("HTTP header too large");
        }
    }

    let header_end = find_header_end(&data).unwrap_or(data.len());
    let head_bytes = &data[..header_end];
    let mut body = if header_end < data.len() {
        data[header_end..].to_vec()
    } else {
        Vec::new()
    };

    // Parse head.
    let text = String::from_utf8_lossy(head_bytes);
    let head_end_text = text.find("\r\n\r\n").unwrap_or(text.len());
    let head = &text[..head_end_text];
    let mut lines = head.split("\r\n");
    let req = lines.next().unwrap_or("");
    let mut it = req.split_whitespace();
    let method = it.next().unwrap_or("").to_string();
    let path = it.next().unwrap_or("").to_string();

    let mut headers = HashMap::new();
    for l in lines {
        if let Some((k, v)) = l.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
        }
    }

    // Read body, if Content-Length is present.
    if let Some(cl) = headers.get("content-length") {
        if let Ok(len) = cl.trim().parse::<usize>() {
            if len > MAX_BODY {
                anyhow::bail!("HTTP body too large");
            }
            while body.len() < len {
                let n = s.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                body.extend_from_slice(&buf[..n]);
                if body.len() > MAX_BODY {
                    anyhow::bail!("HTTP body too large");
                }
            }
            if body.len() > len {
                body.truncate(len);
            }
        }
    }

    Ok((method, path, headers, body))
}

fn safe_module_path(rel: &str) -> Result<PathBuf> {
    let rel = rel.trim();
    if rel.is_empty() {
        anyhow::bail!("path is empty");
    }
    if rel.starts_with('/') {
        anyhow::bail!("absolute paths are not allowed");
    }
    if !(rel.starts_with("working_folder/") || rel.starts_with("setting/")) {
        anyhow::bail!("path must start with working_folder/ or setting/");
    }

    let p = Path::new(rel);
    for c in p.components() {
        match c {
            Component::Normal(_) => {}
            _ => anyhow::bail!("invalid path component"),
        }
    }

    Ok(PathBuf::from(settings::MODULE_DIR).join(p))
}

#[derive(Deserialize)]
struct FsReadReq {
    path: String,
}

#[derive(Deserialize)]
struct FsWriteReq {
    path: String,
    content: String,
}

#[derive(Debug, Deserialize)]
struct NewProfileReq {
    program: String,
    #[serde(default)]
    profile: Option<String>,
}


#[derive(Debug, Deserialize)]
struct EnabledReq {
    enabled: bool,
}

#[derive(Debug, Deserialize)]
struct ContentReq {
    content: String,
}

#[derive(Debug, Deserialize, Serialize, Default)]
struct ProfileState {
    enabled: bool,
}

#[derive(Debug, Deserialize, Serialize, Default)]
struct ProfilesActive {
    profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Deserialize, Serialize, Default)]
struct EnabledActive {
    enabled: bool,
}

fn program_display_name<'a>(id: &'a str) -> &'a str {
    match id {
        "nfqws" => "zapret",
        "nfqws2" => "zapret2",
        "operaproxy" => "opera-proxy",
        _ => id,
    }
}

fn is_safe_segment(s: &str) -> bool {
    if s.is_empty() || s.len() > 64 {
        return false;
    }
    s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-' || c == '.')
}

fn ensure_safe_segment(s: &str, what: &str) -> Result<()> {
    if !is_safe_segment(s) {
        anyhow::bail!("invalid {what}");
    }
    Ok(())
}

fn is_safe_filename(s: &str) -> bool {
    if s.is_empty() || s.len() > 128 {
        return false;
    }
    // Disallow dot segments and any path separators.
    if s == "." || s == ".." || s.contains('/') || s.contains('\\') {
        return false;
    }
    // Conservative ASCII allowlist.
    s.chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-' || c == '.' || c == '+')
}

fn ensure_safe_filename(s: &str) -> Result<()> {
    if !is_safe_filename(s) {
        anyhow::bail!("invalid filename");
    }
    Ok(())
}

fn working_root() -> PathBuf {
    Path::new(settings::MODULE_DIR).join("working_folder")
}

fn program_root(id: &str) -> PathBuf {
    working_root().join(id)
}

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

fn sha256_hex_bytes(data: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(data);
    hex::encode(h.finalize())
}

#[derive(Debug, Clone)]
struct MultipartFile {
    filename: String,
    data: Vec<u8>,
}

fn parse_multipart_file(headers: &HashMap<String, String>, body: &[u8]) -> Result<MultipartFile> {
    // Expect: Content-Type: multipart/form-data; boundary=...
    let ct = headers
        .get("content-type")
        .ok_or_else(|| anyhow::anyhow!("missing content-type"))?;
    let boundary = ct
        .split(';')
        .find_map(|p| {
            let p = p.trim();
            p.strip_prefix("boundary=")
        })
        .map(|b| b.trim_matches('"'))
        .filter(|b| !b.is_empty())
        .ok_or_else(|| anyhow::anyhow!("missing multipart boundary"))?;

    let marker = format!("--{boundary}").into_bytes();
    let end_marker = format!("--{boundary}--").into_bytes();

    // Body must start with boundary marker.
    if !body.starts_with(&marker) {
        anyhow::bail!("bad multipart body");
    }

    let mut pos = 0usize;
    while pos < body.len() {
        // Find the next boundary.
        if body[pos..].starts_with(&end_marker) {
            break;
        }
        if !body[pos..].starts_with(&marker) {
            // Try to resync by searching for marker.
            if let Some(i) = body[pos..].windows(marker.len()).position(|w| w == marker) {
                pos += i;
            } else {
                break;
            }
        }
        pos += marker.len();
        // Optional: boundary line ends with CRLF.
        if body.get(pos..pos + 2) == Some(b"\r\n") {
            pos += 2;
        }

        // Parse part headers until CRLFCRLF.
        let hdr_end = body[pos..]
            .windows(4)
            .position(|w| w == b"\r\n\r\n")
            .ok_or_else(|| anyhow::anyhow!("bad multipart headers"))?;
        let hdr_bytes = &body[pos..pos + hdr_end];
        pos += hdr_end + 4;
        let hdr_text = String::from_utf8_lossy(hdr_bytes);
        let mut part_headers = HashMap::<String, String>::new();
        for line in hdr_text.split("\r\n") {
            if let Some((k, v)) = line.split_once(':') {
                part_headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
            }
        }

        let cd = part_headers
            .get("content-disposition")
            .ok_or_else(|| anyhow::anyhow!("missing content-disposition"))?
            .to_string();

        // Part data is until the next boundary marker, preceded by CRLF.
        let next = body[pos..]
            .windows(marker.len())
            .position(|w| w == marker)
            .or_else(|| body[pos..].windows(end_marker.len()).position(|w| w == end_marker))
            .ok_or_else(|| anyhow::anyhow!("bad multipart body (no closing boundary)"))?;
        let mut data_end = pos + next;
        // Trim trailing CRLF.
        if data_end >= 2 && &body[data_end - 2..data_end] == b"\r\n" {
            data_end -= 2;
        }

        // We accept the first *file* part we see. If a part has no filename (regular form field),
        // skip it.
        let filename_opt = cd
            .split(';')
            .find_map(|p| {
                let p = p.trim();
                p.strip_prefix("filename=")
            })
            .map(|v| v.trim().trim_matches('"'))
            .filter(|v| !v.is_empty())
            .map(|v| v.to_string());

        if let Some(filename) = filename_opt {
            ensure_safe_filename(&filename)?;
            let data = body[pos..data_end].to_vec();
            return Ok(MultipartFile { filename, data });
        }

        // Skip to the next boundary and continue.
        pos = pos + next;
    }

    anyhow::bail!("no file part found")
}

fn write_bytes_atomic(p: &Path, data: &[u8]) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, data)
        .map_err(|e| anyhow::anyhow!("write failed {}: {e}", tmp.display()))?;
    fs::rename(&tmp, p)
        .map_err(|e| anyhow::anyhow!("rename failed {} -> {}: {e}", tmp.display(), p.display()))?;
    Ok(())
}

fn chmod_best_effort(path: &Path, mode: u32) {
    // On Android, chmod is available; use std::process::Command to avoid pulling extra deps.
    let _ = std::process::Command::new("chmod")
        .arg(format!("{:o}", mode))
        .arg(path)
        .status();
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

fn handle_strategic(stream: TcpStream, method: &str, path: &str, headers: &HashMap<String, String>, body: &[u8]) -> Result<()> {
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
                // Upload a file. Filename comes from multipart Content-Disposition.
                let f = parse_multipart_file(headers, body)?;
                let dst = base.join(&f.filename);
                // Ensure destination is within base.
                if dst.parent() != Some(base.as_path()) {
                    anyhow::bail!("invalid destination");
                }
                write_bytes_atomic(&dst, &f.data)?;
                // Apply default permissions.
                match kind {
                    "bin" => chmod_best_effort(&dst, 0o755),
                    _ => chmod_best_effort(&dst, 0o644),
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
                ensure_safe_filename(name)?;
                let p = base.join(name);
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
                ensure_safe_filename(name)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let content_len = req.content.as_bytes().len() as u64;
                if content_len > STRATEGIC_TEXT_LIMIT {
                    return Ok(json!({"ok": false, "error": "too_large", "size": content_len, "limit": STRATEGIC_TEXT_LIMIT}));
                }
                let p = base.join(name);
                write_text_atomic(&p, &req.content)?;
                chmod_best_effort(&p, 0o644);
                Ok(json!({"ok": true}))
            }
            ("DELETE", ["api", "strategic", _, name]) => {
                ensure_safe_filename(name)?;
                let p = base.join(name);
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

fn handle_strategicvar(stream: TcpStream, method: &str, path: &str, body: &[u8]) -> Result<()> {
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

fn active_json_path(id: &str) -> PathBuf {
    program_root(id).join("active.json")
}

fn profile_root(id: &str, profile: &str) -> PathBuf {
    program_root(id).join(profile)
}

fn ensure_profile_layout(id: &str, profile: &str) -> Result<()> {
    let root = profile_root(id, profile);
    // Base folders
    fs::create_dir_all(root.join("config"))?;
    fs::create_dir_all(root.join("app/uid"))?;

    // Config (args)
    let cfg = root.join("config/config.txt");
    if !cfg.exists() {
        write_text_atomic(&cfg, "")?;
    }

    // App lists
    match id {
        "nfqws" | "nfqws2" => {
            for f in ["user_program", "mobile_program", "wifi_program"] {
                let p = root.join(format!("app/uid/{f}"));
                if !p.exists() {
                    write_text_atomic(&p, "")?;
                }
            }
        }
        "byedpi" => {
            let p = root.join("app/uid/user_program");
            if !p.exists() {
                write_text_atomic(&p, "")?;
            }
        }
        "dpitunnel" => {
            for f in ["user_program", "mobile_program", "wifi_program"] {
                let p = root.join(format!("app/uid/{f}"));
                if !p.exists() {
                    write_text_atomic(&p, "")?;
                }
            }
        }
        _ => {}
    }
    Ok(())
}

fn detect_iface(prefixes: &[&str]) -> Option<String> {
    // Prefer sysfs existence checks (fast, no shell).
    if let Ok(entries) = fs::read_dir("/sys/class/net") {
        let mut names: Vec<String> = entries
            .filter_map(|e| e.ok())
            .filter_map(|e| e.file_name().to_str().map(|s| s.to_string()))
            .collect();
        names.sort();
        for pref in prefixes {
            if let Some(x) = names.iter().find(|n| n.starts_with(pref)) {
                return Some(x.clone());
            }
        }
    }
    None
}

fn next_port_from_existing(root: &Path, default_port: u16) -> u16 {
    // Best effort: scan */port.json and pick max(port)+1.
    let mut max_port: Option<u16> = None;
    if let Ok(rd) = fs::read_dir(root) {
        for ent in rd.flatten() {
            let p = ent.path().join("port.json");
            if !p.is_file() {
                continue;
            }
            if let Ok(txt) = fs::read_to_string(&p) {
                if let Ok(v) = serde_json::from_str::<serde_json::Value>(&txt) {
                    if let Some(port) = v.get("port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                        max_port = Some(max_port.map(|m| m.max(port)).unwrap_or(port));
                    }
                }
            }
        }
    }
    match max_port {
        Some(p) => p.saturating_add(1),
        None => default_port,
    }
}

fn write_default_port_json(id: &str, profile: &str) -> Result<()> {
    let root = profile_root(id, profile);
    let p = root.join("port.json");
    if p.exists() {
        return Ok(());
    }

    match id {
        "nfqws" => {
            let port = crate::ports::suggest_port_for_new_profile(id)?;
            let wifi = detect_iface(&["wlan", "wifi"]).unwrap_or_else(|| "wlan0".to_string());
            let mobile = detect_iface(&["rmnet", "ccmni", "pdp"]).unwrap_or_else(|| "rmnet_data0".to_string());
            let v = json!({
                "port": port,
                "iface_mobile": mobile,
                "iface_wifi": wifi
            });
            write_json_pretty(&p, &v)?;
        }
        "nfqws2" => {
            let port = crate::ports::suggest_port_for_new_profile(id)?;
            let wifi = detect_iface(&["wlan", "wifi"]).unwrap_or_else(|| "wlan0".to_string());
            let mobile = detect_iface(&["rmnet", "ccmni", "pdp"]).unwrap_or_else(|| "rmnet_data0".to_string());
            let v = json!({
                "port": port,
                "iface_mobile": mobile,
                "iface_wifi": wifi
            });
            write_json_pretty(&p, &v)?;
        }
        "byedpi" => {
            let port = crate::ports::suggest_port_for_new_profile(id)?;
            let v = json!({"port": port});
            write_json_pretty(&p, &v)?;
        }
        "dpitunnel" => {
            let port = crate::ports::suggest_port_for_new_profile(id)?;
            let wifi = detect_iface(&["wlan", "wifi"]).unwrap_or_else(|| "wlan0".to_string());
            let mobile = detect_iface(&["rmnet", "ccmni", "pdp"]).unwrap_or_else(|| "rmnet_data0".to_string());
            let v = json!({
                "port": port,
                "iface_mobile": mobile,
                "iface_wifi": wifi
            });
            write_json_pretty(&p, &v)?;
        }
        _ => {}
    }
    Ok(())
}

fn normalize_profile_name(input: &str) -> Result<String> {
    // Match the Android app rules:
    // - trim
    // - spaces -> '_'
    // - lowercase
    // - max len 10
    // - allow only [a-z0-9_-]
    let mut s = input.trim().replace(' ', "_").to_ascii_lowercase();
    if s.len() > 10 {
        s.truncate(10);
    }
    if s.is_empty() {
        anyhow::bail!("profile name is empty");
    }
    if !s.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-') {
        anyhow::bail!("invalid profile name");
    }
    Ok(s)
}

fn create_named_profile(program_id: &str, requested: &str) -> Result<String> {
    ensure_safe_segment(program_id, "program id")?;
    if !matches!(program_id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
        anyhow::bail!("program has no profiles");
    }

    let name = normalize_profile_name(requested)?;
    let p = active_json_path(program_id);
    let mut active: ProfilesActive = read_json(&p).unwrap_or_default();

    if active.profiles.contains_key(&name) {
        anyhow::bail!("profile already exists");
    }

    active
        .profiles
        .insert(name.clone(), ProfileState { enabled: false });
    write_json_pretty(&p, &active)?;

    ensure_profile_layout(program_id, &name)?;
    write_default_port_json(program_id, &name)?;
    crate::ports::normalize_ports()?;

    Ok(name)
}

fn create_next_profile(program_id: &str) -> Result<String> {
    ensure_safe_segment(program_id, "program id")?;
    if !matches!(program_id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
        anyhow::bail!("program has no profiles");
    }

    let p = active_json_path(program_id);
    let mut active: ProfilesActive = read_json(&p).unwrap_or_default();

    // Next numeric name (1..N). Support both "1" and legacy "profile1" keys.
    let mut max_n = 0u32;
    let mut saw_profile_prefix = false;
    for k in active.profiles.keys() {
        if k.starts_with("profile") {
            saw_profile_prefix = true;
        }
        if let Ok(n) = k.parse::<u32>() {
            max_n = max_n.max(n);
            continue;
        }
        if let Some(rest) = k.strip_prefix("profile") {
            if let Ok(n) = rest.parse::<u32>() {
                max_n = max_n.max(n);
            }
        }
    }
    let next = if saw_profile_prefix {
        format!("profile{}", max_n + 1)
    } else {
        (max_n + 1).to_string()
    };

    active.profiles.insert(next.clone(), ProfileState { enabled: false });
    write_json_pretty(&p, &active)?;

    ensure_profile_layout(program_id, &next)?;
    write_default_port_json(program_id, &next)?;
    crate::ports::normalize_ports()?;

    Ok(next)
}

fn read_text(p: &Path) -> Result<String> {
    fs::read_to_string(p).map_err(|e| anyhow::anyhow!("read failed {}: {e}", p.display()))
}

fn read_text_or_empty(p: &Path) -> Result<String> {
    match fs::read_to_string(p) {
        Ok(s) => Ok(s),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(anyhow::anyhow!("read failed {}: {e}", p.display())),
    }
}

fn write_text_atomic(p: &Path, content: &str) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, content.as_bytes())
        .map_err(|e| anyhow::anyhow!("write failed {}: {e}", tmp.display()))?;
    fs::rename(&tmp, p)
        .map_err(|e| anyhow::anyhow!("rename failed {} -> {}: {e}", tmp.display(), p.display()))?;
    Ok(())
}

fn read_json<T: DeserializeOwned>(p: &Path) -> Result<T> {
    let txt = read_text(p)?;
    serde_json::from_str(&txt).map_err(|e| anyhow::anyhow!("bad JSON {}: {e}", p.display()))
}

fn write_json_pretty<T: Serialize>(p: &Path, v: &T) -> Result<()> {
    let txt = serde_json::to_string_pretty(v)?;
    write_text_atomic(p, &txt)
}

fn write_ok(mut stream: TcpStream) -> Result<()> {
    write_json(stream, 200, json!({"ok": true}))
}

fn write_err(mut stream: TcpStream, e: anyhow::Error) -> Result<()> {
    write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")}))
}

/// GET /api/programs
fn handle_get_programs(stream: TcpStream) -> Result<()> {
    // Profile-based programs
    let profile_ids = ["nfqws", "nfqws2", "byedpi", "dpitunnel"];
    let mut out = Vec::new();

    for id in profile_ids {
        let p = active_json_path(id);
        let active: ProfilesActive = read_json(&p).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": id,
            "name": program_display_name(id),
            "type": "profiles",
            "profiles": profiles
        }));
    }

    // Single programs
    for id in ["dnscrypt", "operaproxy"] {
        let p = active_json_path(id);
        let active: EnabledActive = read_json(&p).unwrap_or_default();
        out.push(json!({
            "id": id,
            "name": program_display_name(id),
            "type": "single",
            "enabled": active.enabled
        }));
    }

    write_json(stream, 200, json!({"ok": true, "data": out}))
}

/// Handles subroutes under /api/programs/*
fn handle_programs_subroutes(stream: TcpStream, method: &str, path: &str, body: &[u8]) -> Result<()> {
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();

    match (method, seg.as_slice()) {
        // --- profiles: enable/disable
        ("PUT", ["api", "programs", id, "profiles", profile, "enabled"]) => {
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
        ("DELETE", ["api", "programs", id, "profiles", profile]) => {
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
                // Persist first, so it won't be started after restart even if FS operation fails.
                write_json_pretty(&p, &active)?;

                // Move profile dir away (do not rm -rf to avoid breaking running processes).
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
        ("GET", ["api", "programs", id, "profiles", profile, "config"]) => {
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
        ("PUT", ["api", "programs", id, "profiles", profile, "config"]) => {
            let res = (|| -> Result<()> {
                ensure_safe_segment(id, "program id")?;
                ensure_safe_segment(profile, "profile name")?;
                if !matches!(*id, "nfqws" | "nfqws2" | "byedpi" | "dpitunnel") {
                    anyhow::bail!("program has no profiles");
                }
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
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
        ("GET", ["api", "programs", id, "profiles", profile, "apps", kind]) => {
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
        ("PUT", ["api", "programs", id, "profiles", profile, "apps", kind]) => {
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
                let p = profile_root(id, profile).join(format!("app/uid/{fname}"));
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- dnscrypt enabled/config
        ("GET", ["api", "programs", "dnscrypt", "enabled"]) => {
            let p = active_json_path("dnscrypt");
            let active: EnabledActive = read_json(&p).unwrap_or_default();
            write_json(stream, 200, json!({"ok": true, "enabled": active.enabled}))
        }
        ("PUT", ["api", "programs", "dnscrypt", "enabled"]) => {
            let res = (|| -> Result<()> {
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = active_json_path("dnscrypt");
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
        ("GET", ["api", "programs", "dnscrypt", "config"]) => {
            let p = program_root("dnscrypt").join("setting/dnscrypt-proxy.toml");
            let res = read_text(&p);
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "dnscrypt", "config"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = program_root("dnscrypt").join("setting/dnscrypt-proxy.toml");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- dnscrypt extra setting files (read/write only; no create/delete)
        ("GET", ["api", "programs", "dnscrypt", "setting-files"]) => {
            let dir = program_root("dnscrypt").join("setting");
            let res = (|| -> Result<(Vec<String>, serde_json::Map<String, serde_json::Value>, serde_json::Map<String, serde_json::Value>)> {
                let mut out = Vec::new();
                let mut sizes = serde_json::Map::new();
                let mut editable = serde_json::Map::new();
                for ent in fs::read_dir(&dir)? {
                    let ent = ent?;
                    let ft = ent.file_type()?;
                    if !ft.is_file() {
                        continue;
                    }
                    let name = ent.file_name().to_string_lossy().to_string();
                    // Expose only files that already exist; main toml is handled via /config.
                    if name == "dnscrypt-proxy.toml" {
                        continue;
                    }
                    // Protect against weird names even though read_dir returns local FS entries.
                    if name == "." || name == ".." || !is_safe_segment(&name) {
                        continue;
                    }
                    let sz = fs::metadata(ent.path())?.len();
                    sizes.insert(name.clone(), json!(sz));
                    editable.insert(name.clone(), json!(sz <= DNSCRYPT_SETTING_MAX_BYTES));
                    out.push(name);
                }
                out.sort();
                Ok((out, sizes, editable))
            })();
            match res {
                Ok((files, sizes, editable)) => write_json(
                    stream,
                    200,
                    json!({"ok": true, "files": files, "sizes": sizes, "editable": editable, "limit": DNSCRYPT_SETTING_MAX_BYTES}),
                ),
                Err(e) => write_err(stream, e),
            }
        }

("GET", ["api", "programs", "dnscrypt", "setting-files", fname]) => {
            let res = (|| -> Result<(Option<String>, Option<u64>)> {
                ensure_safe_segment(fname, "filename")?;
                if *fname == "." || *fname == ".." {
                    anyhow::bail!("invalid filename");
                }
                let p = program_root("dnscrypt").join("setting").join(fname);
                // Do not allow creating files via GET; must already exist.
                if !p.is_file() {
                    anyhow::bail!("file not found");
                }
                let sz = fs::metadata(&p)?.len();
                if sz > DNSCRYPT_SETTING_MAX_BYTES {
                    return Ok((None, Some(sz)));
                }
                Ok((Some(read_text(&p)?), None))
            })();
            match res {
                Ok((Some(content), _)) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Ok((None, Some(sz))) => write_json(
                    stream,
                    200,
                    json!({"ok": false, "error": "too_large", "size": sz, "limit": DNSCRYPT_SETTING_MAX_BYTES}),
                ),
                Ok((None, None)) => write_json(stream, 200, json!({"ok": false, "error": "unknown"})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "dnscrypt", "setting-files", fname]) => {
            let res = (|| -> Result<Option<usize>> {
                ensure_safe_segment(fname, "filename")?;
                if *fname == "." || *fname == ".." {
                    anyhow::bail!("invalid filename");
                }
                let p = program_root("dnscrypt").join("setting").join(fname);
                // Do not allow creating new files: only update existing ones.
                if !p.is_file() {
                    anyhow::bail!("file not found");
                }
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;

                let bytes = req.content.as_bytes().len();
                if bytes as u64 > DNSCRYPT_SETTING_MAX_BYTES {
                    return Ok(Some(bytes));
                }
                write_text_atomic(&p, &req.content)?;
                Ok(None)
            })();
            match res {
                Ok(None) => write_ok(stream),
                Ok(Some(bytes)) => write_json(
                    stream,
                    200,
                    json!({"ok": false, "error": "too_large", "size": bytes, "limit": DNSCRYPT_SETTING_MAX_BYTES}),
                ),
                Err(e) => write_err(stream, e),
            }
        }

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
                let p = program_root("operaproxy").join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
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
                let p = program_root("operaproxy").join("app/uid/mobile_program");
                write_text_atomic(&p, &req.content)?;
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
                let p = program_root("operaproxy").join("app/uid/wifi_program");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- operaproxy sni.txt
        ("GET", ["api", "programs", "operaproxy", "sni"]) => {
            let p = program_root("operaproxy").join("config/sni.txt");
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
                let p = program_root("operaproxy").join("config/sni.txt");
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

        _ => write_empty_404(stream),
    }
}
fn is_authorized(headers: &HashMap<String, String>, token: &str) -> bool {
    if let Some(v) = headers.get("x-api-key") {
        return v.trim() == token;
    }
    if let Some(v) = headers.get("authorization") {
        let v = v.trim();
        if let Some(rest) = v.strip_prefix("Bearer ") {
            return rest.trim() == token;
        }
    }
    false
}

fn write_json(mut stream: TcpStream, status: u16, body: serde_json::Value) -> Result<()> {
    let body_s = body.to_string();
    let hdr = format!(
        "HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body_s.len()
    );
    stream.write_all(hdr.as_bytes())?;
    stream.write_all(body_s.as_bytes())?;
    Ok(())
}

fn write_empty_404(mut stream: TcpStream) -> Result<()> {
    stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
    Ok(())
}

fn handle_connection(mut stream: TcpStream, state: SharedState) -> Result<()> {
    let (method, path, headers, body) = parse_http_request(&mut stream)?;
    let (token, services_running) = {
        let st = daemon::lock_state(&state);
        (st.token.clone(), st.services_running)
    };

    // Only /api/* is exposed. Everything else -> empty 404.
    if !path.starts_with("/api/") {
        return write_empty_404(stream);
    }

    if !is_authorized(&headers, &token) {
        // Hide API from unauthenticated clients: empty 404.
        return write_empty_404(stream);
    }

    
    // Settings API (typed, safe)
    if method == "GET" && path == "/api/programs" {
        return handle_get_programs(stream);
    }
    if path.starts_with("/api/programs/") {
        return handle_programs_subroutes(stream, method.as_str(), path.as_str(), &body);
    }

    // Strategic folders API (nfqws/nfqws2 shared lists/binaries and nfqws2 lua scripts)
    if path.starts_with("/api/strategic/") {
        return handle_strategic(stream, method.as_str(), path.as_str(), &headers, &body);
    }

    // Strategy variants API (strategic/strategicvar/<program>/*.txt)
    if path.starts_with("/api/strategicvar/") {
        return handle_strategicvar(stream, method.as_str(), path.as_str(), &body);
    }

match (method.as_str(), path.as_str()) {
        ("GET", "/api/status") => {
            let report = stats::collect_report(services_running)?;
            write_json(stream, 200, serde_json::to_value(report)?)
        }

        ("POST", "/api/fs/read_text") => {
            let req: FsReadReq = serde_json::from_slice(&body)
                .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
            let p = safe_module_path(&req.path)?;
            let content = fs::read_to_string(&p)
                .map_err(|e| anyhow::anyhow!("read failed {}: {e}", p.display()))?;
            write_json(stream, 200, json!({"ok": true, "content": content}))
        }
        ("POST", "/api/fs/write_text") => {
            let req: FsWriteReq = serde_json::from_slice(&body)
                .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
            let p = safe_module_path(&req.path)?;
            if let Some(parent) = p.parent() {
                fs::create_dir_all(parent).ok();
            }
            // Atomic-ish write: write to temp, then rename.
            let tmp = p.with_extension("tmp");
            fs::write(&tmp, req.content.as_bytes())
                .map_err(|e| anyhow::anyhow!("write failed {}: {e}", tmp.display()))?;
            fs::rename(&tmp, &p)
                .map_err(|e| anyhow::anyhow!("rename failed {} -> {}: {e}", tmp.display(), p.display()))?;
            write_json(stream, 200, json!({"ok": true}))
        }
        ("POST", "/api/fs/list_dir") => {
            let req: FsReadReq = serde_json::from_slice(&body)
                .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
            let p = safe_module_path(&req.path)?;
            let mut out = Vec::new();
            for ent in fs::read_dir(&p)
                .map_err(|e| anyhow::anyhow!("readdir failed {}: {e}", p.display()))? {
                let ent = ent?;
                if let Some(name) = ent.file_name().to_str() {
                    out.push(name.to_string());
                }
            }
            out.sort();
            write_json(stream, 200, json!({"ok": true, "entries": out}))
        }

        ("POST", "/api/new/profile") => {
            let res = (|| -> Result<serde_json::Value> {
                let req: NewProfileReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let program = req.program.trim();
                let name = match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(p) => create_named_profile(program, p)?,
                    None => create_next_profile(program)?,
                };
                Ok(json!({"ok": true, "profile": name}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
            }
        }

        ("POST", "/api/start") => {
            let res = daemon::handle_start_async(&state);
            match res {
                Ok(accepted) => write_json(stream, 200, json!({"ok": true, "accepted": accepted})),
                Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
            }
        }
        ("POST", "/api/stop") => {
            let res = daemon::handle_stop_async(&state);
            match res {
                Ok(accepted) => write_json(stream, 200, json!({"ok": true, "accepted": accepted})),
                Err(e) => write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")})),
            }
        }
        _ => write_empty_404(stream),
    }
}

pub fn serve(state: SharedState, bind: &str) -> Result<()> {
    /// Max number of concurrent connection handler threads.
    ///
    /// The API is local-only (127.0.0.1) but we still cap concurrency to avoid
    /// accidental flooding (or a buggy client) spawning unbounded threads.
    const MAX_INFLIGHT: usize = 32;

    let listener = TcpListener::bind(bind)?;
    let inflight = Arc::new(AtomicUsize::new(0));

    for conn in listener.incoming() {
        match conn {
            Ok(mut stream) => {
                // Apply small write timeout so we don't block forever on slow clients.
                let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));

                // Concurrency gate
                let now = inflight.fetch_add(1, Ordering::AcqRel) + 1;
                if now > MAX_INFLIGHT {
                    inflight.fetch_sub(1, Ordering::AcqRel);
                    let _ = stream.write_all(b"HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    continue;
                }

                let st = state.clone();
                let inflight2 = inflight.clone();

                thread::spawn(move || {
                    // Ensure we always decrement inflight even if handler errors.
                    struct ConnGuard(Arc<AtomicUsize>);
                    impl Drop for ConnGuard {
                        fn drop(&mut self) {
                            self.0.fetch_sub(1, Ordering::AcqRel);
                        }
                    }
                    let _guard = ConnGuard(inflight2);

                    let _ = handle_connection(stream, st);
                });
            }
            Err(_) => continue,
        }
    }

    Ok(())
}

