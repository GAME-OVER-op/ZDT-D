use anyhow::Result;
use serde::{Deserialize, Serialize};
use serde::de::DeserializeOwned;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, BTreeMap, BTreeSet},
    fs,
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    path::{Component, Path, PathBuf},
    sync::{Arc, OnceLock, Mutex, atomic::{AtomicUsize, Ordering}},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use crate::{daemon, daemon::SharedState, protector, settings, stats};

const MAX_HEADER: usize = 16 * 1024;
// Allow uploading strategic files (including binaries). The API is local-only and authenticated,
// but we still cap body size to avoid accidental memory blowups.
const MAX_BODY: usize = 512 * 1024 * 1024;

// Safety guard for dnscrypt setting files (some lists can be enormous and will crash the app/UI if returned whole).
// Per Danil: limit reads/edits to ~200KB.
const DNSCRYPT_SETTING_MAX_BYTES: u64 = 200 * 1024;

const STATUS_CACHE_TTL: Duration = Duration::from_secs(2);
const ASSIGNMENT_CACHE_TTL: Duration = Duration::from_secs(2);

#[derive(Clone)]
struct StatusCacheEntry {
    created: Instant,
    report: stats::Report,
}

#[derive(Clone)]
struct AssignmentCacheEntry {
    created: Instant,
    items: Vec<AppAssignmentFile>,
}

static STATUS_CACHE: OnceLock<Mutex<Option<StatusCacheEntry>>> = OnceLock::new();
static ASSIGNMENT_CACHE: OnceLock<Mutex<Option<AssignmentCacheEntry>>> = OnceLock::new();

fn status_cache() -> &'static Mutex<Option<StatusCacheEntry>> {
    STATUS_CACHE.get_or_init(|| Mutex::new(None))
}

fn assignment_cache() -> &'static Mutex<Option<AssignmentCacheEntry>> {
    ASSIGNMENT_CACHE.get_or_init(|| Mutex::new(None))
}

fn invalidate_assignment_cache() {
    if let Ok(mut guard) = assignment_cache().lock() {
        *guard = None;
    }
}

fn get_status_cached(services_running: bool) -> Result<stats::Report> {
    if let Ok(guard) = status_cache().lock() {
        if let Some(entry) = &*guard {
            if entry.created.elapsed() < STATUS_CACHE_TTL {
                return Ok(entry.report.clone());
            }
        }
    }

    let report = stats::collect_report(services_running)?;
    if let Ok(mut guard) = status_cache().lock() {
        *guard = Some(StatusCacheEntry {
            created: Instant::now(),
            report: report.clone(),
        });
    }
    Ok(report)
}


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


fn deserialize_boolish<'de, D>(deserializer: D) -> std::result::Result<bool, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::{Error as _, Unexpected};
    let v = serde_json::Value::deserialize(deserializer)?;
    match v {
        serde_json::Value::Bool(b) => Ok(b),
        serde_json::Value::Number(n) => match n.as_i64() {
            Some(0) => Ok(false),
            Some(1) => Ok(true),
            _ => Err(D::Error::invalid_value(Unexpected::Other("number"), &"bool or 0/1")),
        },
        serde_json::Value::String(s) => match s.trim().to_ascii_lowercase().as_str() {
            "true" | "1" => Ok(true),
            "false" | "0" => Ok(false),
            _ => Err(D::Error::invalid_value(Unexpected::Str(&s), &"bool or 0/1")),
        },
        _ => Err(D::Error::invalid_value(Unexpected::Other("non-bool"), &"bool or 0/1")),
    }
}

#[derive(Debug, Deserialize)]
struct EnabledReq {
    #[serde(deserialize_with = "deserialize_boolish")]
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

fn is_valid_singbox_profile_name(name: &str) -> bool {
    if name.is_empty() || name.len() > 64 {
        return false;
    }
    name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

fn ensure_valid_singbox_profile_name(name: &str) -> Result<()> {
    if !is_valid_singbox_profile_name(name) {
        anyhow::bail!("invalid profile name");
    }
    Ok(())
}

fn singbox_active_path() -> PathBuf {
    program_root("singbox").join("active.json")
}

fn singbox_profiles_root() -> PathBuf {
    program_root("singbox").join("profile")
}

fn singbox_deleted_root() -> PathBuf {
    program_root("singbox").join(".deleted")
}

fn singbox_deleted_profiles_root() -> PathBuf {
    singbox_deleted_root().join("profiles")
}

fn singbox_deleted_servers_root(profile: &str) -> PathBuf {
    singbox_deleted_root().join("servers").join(profile)
}

fn singbox_profile_root(profile: &str) -> PathBuf {
    singbox_profiles_root().join(profile)
}

fn singbox_server_root(profile: &str, server: &str) -> PathBuf {
    singbox_profile_root(profile).join("server").join(server)
}

fn default_singbox_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({
        "t2s_port": t2s_port,
        "t2s_web_port": t2s_web_port
    })
}

fn default_singbox_server_setting_value(port: u16) -> serde_json::Value {
    json!({
        "enabled": false,
        "port": port
    })
}

fn ensure_singbox_profile_layout(profile: &str) -> Result<()> {
    let root = singbox_profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("server"))?;

    let app_list = root.join("app/uid/user_program");
    if !app_list.exists() {
        write_text_atomic(&app_list, "")?;
    }
    let out_list = root.join("app/out/user_program");
    if !out_list.exists() {
        write_text_atomic(&out_list, "")?;
    }
    let t2s_log = root.join("log/t2s.log");
    if !t2s_log.exists() {
        write_text_atomic(&t2s_log, "")?;
    }
    Ok(())
}

fn collect_existing_singbox_ports() -> BTreeSet<u16> {
    let mut used = BTreeSet::new();
    let root = singbox_profiles_root();
    if let Ok(rd) = fs::read_dir(&root) {
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() {
                continue;
            }
            if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                continue;
            }
            let setting_path = profile_dir.join("setting.json");
            if let Ok(v) = read_json::<serde_json::Value>(&setting_path) {
                if let Some(port) = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                    if port > 0 {
                        used.insert(port);
                    }
                }
                if let Some(port) = v.get("t2s_web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                    if port > 0 {
                        used.insert(port);
                    }
                }
            }

            let server_root = profile_dir.join("server");
            if let Ok(server_rd) = fs::read_dir(&server_root) {
                for server_ent in server_rd.flatten() {
                    let server_dir = server_ent.path();
                    if !server_dir.is_dir() {
                        continue;
                    }
                    if server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                        continue;
                    }
                    let setting_path = server_dir.join("setting.json");
                    if let Ok(v) = read_json::<serde_json::Value>(&setting_path) {
                        if let Some(port) = v.get("port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                            if port > 0 {
                                used.insert(port);
                            }
                        }
                    }
                }
            }
        }
    }
    used
}

fn next_free_port_from_used(start: u16, used: &BTreeSet<u16>) -> Result<u16> {
    let mut p = if start == 0 { 1u32 } else { start as u32 };
    while p <= u16::MAX as u32 {
        let port = p as u16;
        if !used.contains(&port) {
            return Ok(port);
        }
        p += 1;
    }
    anyhow::bail!("no free port available starting from {start}")
}

fn suggest_singbox_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_singbox_ports());
    let t2s = next_free_port_from_used(12345, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8001, &used)?;
    Ok((t2s, web))
}

fn suggest_singbox_server_port() -> Result<u16> {
    let mut used = crate::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_singbox_ports());
    next_free_port_from_used(1080, &used)
}

fn create_singbox_profile_named(requested: &str) -> Result<String> {
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;

    let active_path = singbox_active_path();
    let mut active: ProfilesActive = read_json(&active_path).unwrap_or_default();
    if active.profiles.contains_key(name) {
        anyhow::bail!("profile already exists");
    }
    active
        .profiles
        .insert(name.to_string(), ProfileState { enabled: false });
    write_json_pretty(&active_path, &active)?;

    ensure_singbox_profile_layout(name)?;
    let profile_setting = singbox_profile_root(name).join("setting.json");
    if !profile_setting.exists() {
        let (t2s_port, t2s_web_port) = suggest_singbox_profile_ports()?;
        write_json_pretty(
            &profile_setting,
            &default_singbox_profile_setting_value(t2s_port, t2s_web_port),
        )?;
    }

    Ok(name.to_string())
}

fn create_singbox_profile_next() -> Result<String> {
    let active: ProfilesActive = read_json(&singbox_active_path()).unwrap_or_default();
    let mut max_n = 0u32;
    for k in active.profiles.keys() {
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
    let next = format!("profile{}", max_n + 1);
    create_singbox_profile_named(&next)?;
    Ok(next)
}

fn create_singbox_server_named(profile: &str, requested: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;
    ensure_singbox_profile_layout(profile)?;

    let root = singbox_server_root(profile, name);
    if root.exists() {
        anyhow::bail!("server already exists");
    }
    fs::create_dir_all(root.join("log"))?;
    let cfg = root.join("config.json");
    if !cfg.exists() {
        write_text_atomic(&cfg, "")?;
    }
    let log = root.join("log/sing-box.log");
    if !log.exists() {
        write_text_atomic(&log, "")?;
    }
    let setting = root.join("setting.json");
    if !setting.exists() {
        let port = suggest_singbox_server_port()?;
        write_json_pretty(&setting, &default_singbox_server_setting_value(port))?;
    }
    Ok(name.to_string())
}

fn create_singbox_server_next(profile: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let root = singbox_profile_root(profile).join("server");
    fs::create_dir_all(&root)?;
    let mut max_n = 0u32;
    if let Ok(rd) = fs::read_dir(&root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue };
            if name.starts_with('.') { continue; }
            if let Some(rest) = name.strip_prefix("server") {
                if let Ok(n) = rest.parse::<u32>() {
                    max_n = max_n.max(n);
                }
            }
        }
    }
    let next = format!("server{}", max_n + 1);
    create_singbox_server_named(profile, &next)?;
    Ok(next)
}



fn wireproxy_active_path() -> PathBuf {
    program_root("wireproxy").join("active.json")
}

fn wireproxy_profiles_root() -> PathBuf {
    program_root("wireproxy").join("profile")
}

fn wireproxy_deleted_root() -> PathBuf {
    program_root("wireproxy").join(".deleted")
}

fn wireproxy_deleted_profiles_root() -> PathBuf {
    wireproxy_deleted_root().join("profiles")
}

fn wireproxy_deleted_servers_root(profile: &str) -> PathBuf {
    wireproxy_deleted_root().join("servers").join(profile)
}

fn wireproxy_profile_root(profile: &str) -> PathBuf {
    wireproxy_profiles_root().join(profile)
}

fn wireproxy_server_root(profile: &str, server: &str) -> PathBuf {
    wireproxy_profile_root(profile).join("server").join(server)
}

fn default_wireproxy_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({
        "t2s_port": t2s_port,
        "t2s_web_port": t2s_web_port
    })
}

fn default_wireproxy_server_setting_value() -> serde_json::Value {
    json!({
        "enabled": false
    })
}

fn ensure_wireproxy_profile_layout(profile: &str) -> Result<()> {
    let root = wireproxy_profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("server"))?;

    let app_list = root.join("app/uid/user_program");
    if !app_list.exists() {
        write_text_atomic(&app_list, "")?;
    }
    let out_list = root.join("app/out/user_program");
    if !out_list.exists() {
        write_text_atomic(&out_list, "")?;
    }
    let t2s_log = root.join("log/t2s.log");
    if !t2s_log.exists() {
        write_text_atomic(&t2s_log, "")?;
    }
    Ok(())
}

fn collect_existing_wireproxy_ports() -> BTreeSet<u16> {
    let mut used = BTreeSet::new();
    let root = wireproxy_profiles_root();
    if let Ok(rd) = fs::read_dir(&root) {
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() {
                continue;
            }
            if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                continue;
            }
            let setting_path = profile_dir.join("setting.json");
            if let Ok(v) = read_json::<serde_json::Value>(&setting_path) {
                if let Some(port) = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                    if port > 0 {
                        used.insert(port);
                    }
                }
                if let Some(port) = v.get("t2s_web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                    if port > 0 {
                        used.insert(port);
                    }
                }
            }

            let server_root = profile_dir.join("server");
            if let Ok(server_rd) = fs::read_dir(&server_root) {
                for server_ent in server_rd.flatten() {
                    let server_dir = server_ent.path();
                    if !server_dir.is_dir() {
                        continue;
                    }
                    if server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                        continue;
                    }
                    let config_path = server_dir.join("config.conf");
                    let Ok(raw) = fs::read_to_string(&config_path) else { continue; };
                    let Ok(bind) = crate::programs::wireproxy::parse_socks5_bind_address_str(&raw) else { continue; };
                    if bind.port > 0 {
                        used.insert(bind.port);
                    }
                }
            }
        }
    }
    used
}

fn suggest_wireproxy_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_wireproxy_ports());
    let t2s = next_free_port_from_used(12345, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8001, &used)?;
    Ok((t2s, web))
}

fn create_wireproxy_profile_named(requested: &str) -> Result<String> {
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;

    let active_path = wireproxy_active_path();
    let mut active: ProfilesActive = read_json(&active_path).unwrap_or_default();
    if active.profiles.contains_key(name) {
        anyhow::bail!("profile already exists");
    }
    active.profiles.insert(name.to_string(), ProfileState { enabled: false });
    write_json_pretty(&active_path, &active)?;

    ensure_wireproxy_profile_layout(name)?;
    let profile_setting = wireproxy_profile_root(name).join("setting.json");
    if !profile_setting.exists() {
        let (t2s_port, t2s_web_port) = suggest_wireproxy_profile_ports()?;
        write_json_pretty(
            &profile_setting,
            &default_wireproxy_profile_setting_value(t2s_port, t2s_web_port),
        )?;
    }

    Ok(name.to_string())
}

fn create_wireproxy_profile_next() -> Result<String> {
    let active: ProfilesActive = read_json(&wireproxy_active_path()).unwrap_or_default();
    let mut max_n = 0u32;
    for k in active.profiles.keys() {
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
    let next = format!("profile{}", max_n + 1);
    create_wireproxy_profile_named(&next)?;
    Ok(next)
}

fn create_wireproxy_server_named(profile: &str, requested: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;
    ensure_wireproxy_profile_layout(profile)?;

    let root = wireproxy_server_root(profile, name);
    if root.exists() {
        anyhow::bail!("server already exists");
    }
    fs::create_dir_all(root.join("log"))?;
    let cfg = root.join("config.conf");
    if !cfg.exists() {
        write_text_atomic(&cfg, "")?;
    }
    let log = root.join("log/wireproxy.log");
    if !log.exists() {
        write_text_atomic(&log, "")?;
    }
    let setting = root.join("setting.json");
    if !setting.exists() {
        write_json_pretty(&setting, &default_wireproxy_server_setting_value())?;
    }
    Ok(name.to_string())
}

fn create_wireproxy_server_next(profile: &str) -> Result<String> {
    ensure_valid_singbox_profile_name(profile)?;
    let root = wireproxy_profile_root(profile).join("server");
    fs::create_dir_all(&root)?;
    let mut max_n = 0u32;
    if let Ok(rd) = fs::read_dir(&root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue };
            if name.starts_with('.') { continue; }
            if let Some(rest) = name.strip_prefix("server") {
                if let Ok(n) = rest.parse::<u32>() {
                    max_n = max_n.max(n);
                }
            }
        }
    }
    let next = format!("server{}", max_n + 1);
    create_wireproxy_server_named(profile, &next)?;
    Ok(next)
}


fn myproxy_active_path() -> PathBuf { program_root("myproxy").join("active.json") }
fn myproxy_profiles_root() -> PathBuf { program_root("myproxy").join("profile") }
fn myproxy_deleted_root() -> PathBuf { program_root("myproxy").join(".deleted") }
fn myproxy_deleted_profiles_root() -> PathBuf { myproxy_deleted_root().join("profiles") }
fn myproxy_profile_root(profile: &str) -> PathBuf { myproxy_profiles_root().join(profile) }

fn default_myproxy_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({"t2s_port": t2s_port, "t2s_web_port": t2s_web_port})
}

fn default_myproxy_proxy_value() -> serde_json::Value {
    json!({"host": "127.0.0.1", "port": 1080, "user": "", "pass": ""})
}

fn ensure_myproxy_profile_layout(profile: &str) -> Result<()> {
    let root = myproxy_profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    let app_list = root.join("app/uid/user_program");
    if !app_list.exists() { write_text_atomic(&app_list, "")?; }
    let out_list = root.join("app/out/user_program");
    if !out_list.exists() { write_text_atomic(&out_list, "")?; }
    let t2s_log = root.join("log/t2s.log");
    if !t2s_log.exists() { write_text_atomic(&t2s_log, "")?; }
    let proxy = root.join("proxy.json");
    if !proxy.exists() { write_json_pretty(&proxy, &default_myproxy_proxy_value())?; }
    Ok(())
}

fn collect_existing_myproxy_ports() -> BTreeSet<u16> {
    let mut used = BTreeSet::new();
    let root = myproxy_profiles_root();
    if let Ok(rd) = fs::read_dir(&root) {
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() { continue; }
            if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) { continue; }
            let setting_path = profile_dir.join("setting.json");
            if let Ok(v) = read_json::<serde_json::Value>(&setting_path) {
                if let Some(port) = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) { if port > 0 { used.insert(port); } }
                if let Some(port) = v.get("t2s_web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) { if port > 0 { used.insert(port); } }
            }
        }
    }
    used
}

fn suggest_myproxy_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_myproxy_ports());
    let t2s = next_free_port_from_used(12348, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8004, &used)?;
    Ok((t2s, web))
}

fn create_myproxy_profile_named(requested: &str) -> Result<String> {
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;
    let active_path = myproxy_active_path();
    let mut active: ProfilesActive = read_json(&active_path).unwrap_or_default();
    if active.profiles.contains_key(name) { anyhow::bail!("profile already exists"); }
    active.profiles.insert(name.to_string(), ProfileState { enabled: false });
    write_json_pretty(&active_path, &active)?;
    ensure_myproxy_profile_layout(name)?;
    let profile_setting = myproxy_profile_root(name).join("setting.json");
    if !profile_setting.exists() {
        let (t2s_port, t2s_web_port) = suggest_myproxy_profile_ports()?;
        write_json_pretty(&profile_setting, &default_myproxy_profile_setting_value(t2s_port, t2s_web_port))?;
    }
    Ok(name.to_string())
}

fn create_myproxy_profile_next() -> Result<String> {
    let active: ProfilesActive = read_json(&myproxy_active_path()).unwrap_or_default();
    let mut max_n = 0u32;
    for k in active.profiles.keys() {
        if let Ok(n) = k.parse::<u32>() { max_n = max_n.max(n); continue; }
        if let Some(rest) = k.strip_prefix("profile") {
            if let Ok(n) = rest.parse::<u32>() { max_n = max_n.max(n); }
        }
    }
    let next = format!("profile{}", max_n + 1);
    create_myproxy_profile_named(&next)?;
    Ok(next)
}


fn myprogram_active_path() -> PathBuf { program_root("myprogram").join("active.json") }
fn myprogram_profiles_root() -> PathBuf { program_root("myprogram").join("profile") }
fn myprogram_deleted_root() -> PathBuf { program_root("myprogram").join(".deleted") }
fn myprogram_deleted_profiles_root() -> PathBuf { myprogram_deleted_root().join("profiles") }
fn myprogram_profile_root(profile: &str) -> PathBuf { myprogram_profiles_root().join(profile) }

fn default_myprogram_profile_setting_value(t2s_port: u16, t2s_web_port: u16) -> serde_json::Value {
    json!({"apps_mode": false, "route_mode": "t2s", "proto_mode": "tcp", "transparent_port": 0, "t2s_port": t2s_port, "t2s_web_port": t2s_web_port, "socks_user": "", "socks_pass": ""})
}

fn ensure_myprogram_profile_layout(profile: &str) -> Result<()> {
    crate::programs::myprogram::ensure_profile_layout(profile)
}

fn collect_existing_myprogram_ports() -> BTreeSet<u16> {
    crate::programs::myprogram::collect_defined_ports_for_conflict_check()
}

fn suggest_myprogram_profile_ports() -> Result<(u16, u16)> {
    let mut used = crate::ports::collect_used_ports_for_conflict_check().unwrap_or_default();
    used.extend(collect_existing_myprogram_ports());
    let t2s = next_free_port_from_used(12350, &used)?;
    used.insert(t2s);
    let web = next_free_port_from_used(8006, &used)?;
    Ok((t2s, web))
}

fn create_myprogram_profile_named(requested: &str) -> Result<String> {
    let name = requested.trim();
    ensure_valid_singbox_profile_name(name)?;
    let active_path = myprogram_active_path();
    let mut active: ProfilesActive = read_json(&active_path).unwrap_or_default();
    if active.profiles.contains_key(name) { anyhow::bail!("profile already exists"); }
    active.profiles.insert(name.to_string(), ProfileState { enabled: false });
    write_json_pretty(&active_path, &active)?;
    ensure_myprogram_profile_layout(name)?;
    let profile_setting = myprogram_profile_root(name).join("setting.json");
    if !profile_setting.exists() {
        let (t2s_port, t2s_web_port) = suggest_myprogram_profile_ports()?;
        write_json_pretty(&profile_setting, &default_myprogram_profile_setting_value(t2s_port, t2s_web_port))?;
    }
    Ok(name.to_string())
}

fn create_myprogram_profile_next() -> Result<String> {
    let active: ProfilesActive = read_json(&myprogram_active_path()).unwrap_or_default();
    let mut max_n = 0u32;
    for k in active.profiles.keys() {
        if let Ok(n) = k.parse::<u32>() { max_n = max_n.max(n); continue; }
        if let Some(rest) = k.strip_prefix("profile") {
            if let Ok(n) = rest.parse::<u32>() { max_n = max_n.max(n); }
        }
    }
    let next = format!("profile{}", max_n + 1);
    create_myprogram_profile_named(&next)?;
    Ok(next)
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


fn parse_package_set(raw: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for line in raw.lines() {
        let mut s = line.trim();
        if let Some((left, _)) = s.split_once('#') {
            s = left.trim();
        }
        if s.is_empty() {
            continue;
        }
        out.insert(s.to_string());
    }
    out
}

#[derive(Debug, Clone, Serialize)]
struct AppAssignmentView {
    program_id: String,
    profile: Option<String>,
    slot: String,
    path: String,
    packages: Vec<String>,
}

#[derive(Debug, Clone)]
struct AppAssignmentFile {
    program_id: String,
    profile: Option<String>,
    slot: String,
    path: String,
    fs_path: PathBuf,
    packages: BTreeSet<String>,
}

#[derive(Debug, Clone, Serialize)]
struct AppConflictView {
    package: String,
    program_id: String,
    profile: Option<String>,
    slot: String,
    path: String,
}

#[derive(Debug, Deserialize)]
struct ProxyInfoSaveReq {
    content: String,
    #[serde(default)]
    remove_conflicts: bool,
}

fn slot_from_kind(kind: &str) -> Option<&'static str> {
    match kind {
        "user" => Some("common"),
        "mobile" => Some("mobile"),
        "wifi" => Some("wifi"),
        _ => None,
    }
}

fn app_domain(program_id: &str) -> Option<&'static str> {
    match program_id {
        "operaproxy" | "sing-box" | "wireproxy" | "myproxy" | "myprogram" | "tor" | "dpitunnel" | "byedpi" => Some("tunnel"),
        "nfqws" | "nfqws2" => Some("zapret"),
        _ => None,
    }
}

fn push_assignment_file(
    out: &mut Vec<AppAssignmentFile>,
    program_id: &str,
    profile: Option<String>,
    kind: &str,
    fs_path: PathBuf,
    path: String,
) {
    let Some(slot) = slot_from_kind(kind) else { return; };
    let packages = read_package_set_or_empty(&fs_path).unwrap_or_default();
    out.push(AppAssignmentFile {
        program_id: program_id.to_string(),
        profile,
        slot: slot.to_string(),
        path,
        fs_path,
        packages,
    });
}

fn collect_assignment_files_uncached() -> Vec<AppAssignmentFile> {
    let mut out = Vec::new();

    for id in ["nfqws", "nfqws2", "byedpi", "dpitunnel"] {
        let root = program_root(id);
        if let Ok(rd) = fs::read_dir(&root) {
            for ent in rd.flatten() {
                let path = ent.path();
                if !path.is_dir() { continue; }
                let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
                match id {
                    "nfqws" | "nfqws2" | "dpitunnel" => {
                        for kind in ["user", "mobile", "wifi"] {
                            let fname = match kind { "user" => "user_program", "mobile" => "mobile_program", "wifi" => "wifi_program", _ => continue };
                            push_assignment_file(
                                &mut out,
                                id,
                                Some(profile.clone()),
                                kind,
                                path.join(format!("app/uid/{fname}")),
                                format!("/api/programs/{id}/profiles/{profile}/apps/{kind}"),
                            );
                        }
                    }
                    "byedpi" => {
                        push_assignment_file(
                            &mut out,
                            id,
                            Some(profile.clone()),
                            "user",
                            path.join("app/uid/user_program"),
                            format!("/api/programs/{id}/profiles/{profile}/apps/user"),
                        );
                    }
                    _ => {}
                }
            }
        }
    }

    for kind in ["user", "mobile", "wifi"] {
        let fname = match kind { "user" => "user_program", "mobile" => "mobile_program", "wifi" => "wifi_program", _ => continue };
        push_assignment_file(
            &mut out,
            "operaproxy",
            None,
            kind,
            program_root("operaproxy").join(format!("app/uid/{fname}")),
            format!("/api/programs/operaproxy/apps/{kind}"),
        );
    }
    push_assignment_file(
        &mut out,
        "tor",
        None,
        "user",
        program_root("tor").join("app/uid/user_program"),
        "/api/programs/tor/apps".to_string(),
    );

    let singbox_root = singbox_profiles_root();
    if let Ok(rd) = fs::read_dir(&singbox_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "sing-box",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/sing-box/profiles/{profile}/apps/user"),
            );
        }
    }

    let wireproxy_root = wireproxy_profiles_root();
    if let Ok(rd) = fs::read_dir(&wireproxy_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "wireproxy",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/wireproxy/profiles/{profile}/apps/user"),
            );
        }
    }

    let myproxy_root = myproxy_profiles_root();
    if let Ok(rd) = fs::read_dir(&myproxy_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "myproxy",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/myproxy/profiles/{profile}/apps/user"),
            );
        }
    }

    let myprogram_root = myprogram_profiles_root();
    if let Ok(rd) = fs::read_dir(&myprogram_root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()).map(|s| s.to_string()) else { continue; };
            push_assignment_file(
                &mut out,
                "myprogram",
                Some(profile.clone()),
                "user",
                path.join("app/uid/user_program"),
                format!("/api/programs/myprogram/profiles/{profile}/apps/user"),
            );
        }
    }

    out
}

fn collect_assignment_files() -> Vec<AppAssignmentFile> {
    if let Ok(guard) = assignment_cache().lock() {
        if let Some(entry) = &*guard {
            if entry.created.elapsed() < ASSIGNMENT_CACHE_TTL {
                return entry.items.clone();
            }
        }
    }

    let items = collect_assignment_files_uncached();
    if let Ok(mut guard) = assignment_cache().lock() {
        *guard = Some(AssignmentCacheEntry {
            created: Instant::now(),
            items: items.clone(),
        });
    }
    items
}

fn assignment_to_view(v: &AppAssignmentFile) -> AppAssignmentView {
    AppAssignmentView {
        program_id: v.program_id.clone(),
        profile: v.profile.clone(),
        slot: v.slot.clone(),
        path: v.path.clone(),
        packages: v.packages.iter().cloned().collect(),
    }
}

fn conflict_label(v: &AppConflictView) -> String {
    match &v.profile {
        Some(profile) if !profile.is_empty() => format!("{} / {} / {}", v.program_id, profile, v.slot),
        _ => format!("{} / {}", v.program_id, v.slot),
    }
}

fn packages_to_text(pkgs: &BTreeSet<String>) -> String {
    pkgs.iter().cloned().collect::<Vec<_>>().join("\n")
}

fn flatten_conflicts(map: &BTreeMap<String, Vec<AppConflictView>>) -> Vec<AppConflictView> {
    let mut out = Vec::new();
    for items in map.values() {
        out.extend(items.iter().cloned());
    }
    out
}

fn find_program_conflicts(
    candidate: &BTreeSet<String>,
    current_api_path: &str,
    program_id: &str,
    slot: &str,
) -> BTreeMap<String, Vec<AppConflictView>> {
    let mut out: BTreeMap<String, Vec<AppConflictView>> = BTreeMap::new();
    let Some(domain) = app_domain(program_id) else { return out; };
    let lists = collect_assignment_files();
    let current_existing = lists
        .iter()
        .find(|v| v.path == current_api_path)
        .map(|v| v.packages.clone())
        .unwrap_or_default();
    for item in lists {
        if item.path == current_api_path { continue; }
        if item.slot != slot { continue; }
        if app_domain(&item.program_id) != Some(domain) { continue; }
        for pkg in candidate.intersection(&item.packages) {
            if current_existing.contains(pkg) { continue; }
            out.entry(pkg.clone()).or_default().push(AppConflictView {
                package: pkg.clone(),
                program_id: item.program_id.clone(),
                profile: item.profile.clone(),
                slot: item.slot.clone(),
                path: item.path.clone(),
            });
        }
    }
    out
}

fn find_proxyinfo_conflicts(candidate: &BTreeSet<String>) -> BTreeMap<String, Vec<AppConflictView>> {
    let mut out: BTreeMap<String, Vec<AppConflictView>> = BTreeMap::new();
    for item in collect_assignment_files() {
        for pkg in candidate.intersection(&item.packages) {
            out.entry(pkg.clone()).or_default().push(AppConflictView {
                package: pkg.clone(),
                program_id: item.program_id.clone(),
                profile: item.profile.clone(),
                slot: item.slot.clone(),
                path: item.path.clone(),
            });
        }
    }
    out
}

fn validate_program_apps_content(content: &str, current_api_path: &str, program_id: &str, slot: &str) -> Result<()> {
    let candidate = parse_package_set(content);
    if candidate.is_empty() { return Ok(()); }

    let blocked = crate::proxyinfo::read_proxy_packages().unwrap_or_default();
    let mut overlap: Vec<String> = candidate.intersection(&blocked).cloned().collect();
    overlap.sort();
    if !overlap.is_empty() {
        anyhow::bail!("apps are blocked by proxyInfo: {}", overlap.join(", "));
    }

    let conflicts = find_program_conflicts(&candidate, current_api_path, program_id, slot);
    if !conflicts.is_empty() {
        let mut parts = Vec::new();
        for (pkg, uses) in &conflicts {
            let labels = uses.iter().map(conflict_label).collect::<Vec<_>>().join(", ");
            parts.push(format!("{pkg} ({labels})"));
        }
        parts.sort();
        anyhow::bail!("apps are already used in conflicting program lists: {}", parts.join("; "));
    }
    Ok(())
}

fn validate_proxyinfo_apps_content(content: &str) -> Result<()> {
    let candidate = parse_package_set(content);
    if candidate.is_empty() {
        return Ok(());
    }

    let conflicts = find_proxyinfo_conflicts(&candidate);
    if !conflicts.is_empty() {
        let mut parts = Vec::new();
        for (pkg, uses) in &conflicts {
            let labels = uses.iter().map(conflict_label).collect::<Vec<_>>().join(", ");
            parts.push(format!("{pkg} ({labels})"));
        }
        parts.sort();
        anyhow::bail!("apps are already used by ZDT-D programs: {}", parts.join("; "));
    }
    Ok(())
}

fn remove_packages_from_program_lists(packages: &BTreeSet<String>) -> Result<Vec<AppConflictView>> {
    let mut removed = Vec::new();
    if packages.is_empty() { return Ok(removed); }
    for item in collect_assignment_files() {
        let intersection: BTreeSet<String> = item.packages.intersection(packages).cloned().collect();
        if intersection.is_empty() { continue; }
        let updated: BTreeSet<String> = item.packages.difference(packages).cloned().collect();
        write_text_atomic(&item.fs_path, &packages_to_text(&updated))?;
        for pkg in intersection {
            removed.push(AppConflictView {
                package: pkg,
                program_id: item.program_id.clone(),
                profile: item.profile.clone(),
                slot: item.slot.clone(),
                path: item.path.clone(),
            });
        }
    }
    if !removed.is_empty() {
        invalidate_assignment_cache();
    }
    Ok(removed)
}

fn read_package_set_or_empty(p: &Path) -> Result<BTreeSet<String>> {
    Ok(parse_package_set(&read_text_or_empty(p)?))
}

fn validate_sing_box_setting(v: &serde_json::Value) -> Result<()> {
    let profiles = v
        .get("profiles")
        .and_then(|x| x.as_array())
        .ok_or_else(|| anyhow::anyhow!("setting.json: profiles array is required"))?;

    let mut ports = std::collections::HashSet::new();
    for profile in profiles {
        let name = profile
            .get("name")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .trim();
        if name.is_empty() {
            anyhow::bail!("setting.json: profile name is empty");
        }
        let port = profile
            .get("port")
            .and_then(|x| x.as_i64())
            .ok_or_else(|| anyhow::anyhow!("setting.json: profile {name} is missing port"))?;
        if !(1..=65535).contains(&port) {
            anyhow::bail!("setting.json: invalid port {port} for profile {name}");
        }
        if !ports.insert(port) {
            anyhow::bail!("setting.json: duplicate profile port {port}");
        }
    }
    Ok(())
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

    {
        let enabled = crate::programs::tor::load_enabled_json().map(|v| v.is_enabled()).unwrap_or(false);
        out.push(json!({
            "id": "tor",
            "name": "tor",
            "type": "single",
            "enabled": enabled
        }));
    }

    // sing-box (profile-based, socks5-only)
    {
        let active: ProfilesActive = read_json(&singbox_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "sing-box",
            "name": "sing-box",
            "type": "singbox_profiles",
            "profiles": profiles
        }));
    }

    // wireproxy (profile-based, socks5-only)
    {
        let active: ProfilesActive = read_json(&wireproxy_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "wireproxy",
            "name": "wireproxy",
            "type": "wireproxy_profiles",
            "profiles": profiles
        }));
    }

    // myproxy (profile-based, single socks5 upstream per profile)
    {
        let active: ProfilesActive = read_json(&myproxy_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "myproxy",
            "name": "myproxy",
            "type": "myproxy_profiles",
            "profiles": profiles
        }));
    }

    // myprogram (custom launched program profiles)
    {
        let active: ProfilesActive = read_json(&myprogram_active_path()).unwrap_or_default();
        let mut profiles = Vec::new();
        for (name, st) in active.profiles {
            profiles.push(json!({"name": name, "enabled": st.enabled}));
        }
        profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
        out.push(json!({
            "id": "myprogram",
            "name": "myprogram",
            "type": "myprogram_profiles",
            "profiles": profiles
        }));
    }

    write_json(stream, 200, json!({"ok": true, "data": out}))
}

/// Handles subroutes under /api/programs/*
fn handle_programs_subroutes(stream: TcpStream, method: &str, path: &str, headers: &HashMap<String, String>, body: &[u8]) -> Result<()> {
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();

    match (method, seg.as_slice()) {
        // --- sing-box profile/server API
        ("GET", ["api", "programs", "sing-box", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let active: ProfilesActive = read_json(&singbox_active_path()).unwrap_or_default();
                let mut profiles = Vec::new();
                for (name, st) in active.profiles {
                    profiles.push(json!({"name": name, "enabled": st.enabled}));
                }
                profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
                Ok(json!({"ok": true, "profiles": profiles}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "programs", "sing-box", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                let req: Req = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_singbox_profile_named(name)?,
                    None => create_singbox_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = singbox_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
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
        ("DELETE", ["api", "programs", "sing-box", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let p = singbox_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                if active.profiles.remove(*profile).is_none() {
                    anyhow::bail!("profile not found");
                }
                write_json_pretty(&p, &active)?;
                invalidate_assignment_cache();
                let src = singbox_profile_root(profile);
                if src.exists() {
                    let deleted_dir = singbox_deleted_profiles_root();
                    fs::create_dir_all(&deleted_dir).ok();
                    let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                    let dst = deleted_dir.join(format!("{profile}.{ts}"));
                    let _ = fs::rename(&src, &dst);
                }
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_profile_layout(profile)?;
                let p = singbox_profile_root(profile).join("setting.json");
                if !p.exists() {
                    let (t2s_port, t2s_web_port) = suggest_singbox_profile_ports()?;
                    write_json_pretty(&p, &default_singbox_profile_setting_value(t2s_port, t2s_web_port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_profile_layout(profile)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let t2s_port = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok())
                    .ok_or_else(|| anyhow::anyhow!("t2s_port is required"))?;
                let t2s_web_port = v.get("t2s_web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok())
                    .ok_or_else(|| anyhow::anyhow!("t2s_web_port is required"))?;
                if t2s_port == 0 || t2s_web_port == 0 || t2s_port == t2s_web_port {
                    anyhow::bail!("invalid t2s ports");
                }
                let p = singbox_profile_root(profile).join("setting.json");
                write_json_pretty(&p, &v)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_profile_layout(profile)?;
                let p = singbox_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/sing-box/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "sing-box", "common")?;
                let p = singbox_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box", "profiles", profile, "servers"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_singbox_profile_layout(profile)?;
                let root = singbox_profile_root(profile).join("server");
                let mut servers = Vec::new();
                if let Ok(rd) = fs::read_dir(&root) {
                    for ent in rd.flatten() {
                        let path = ent.path();
                        if !path.is_dir() { continue; }
                        let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue; };
                        if name.starts_with('.') { continue; }
                        let setting_path = path.join("setting.json");
                        let data: serde_json::Value = read_json(&setting_path).unwrap_or_else(|_| default_singbox_server_setting_value(1080));
                        servers.push(json!({"name": name, "setting": data}));
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
        ("POST", ["api", "programs", "sing-box", "profiles", profile, "servers"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)]
                struct Req { #[serde(default)] name: Option<String> }
                ensure_valid_singbox_profile_name(profile)?;
                let req: Req = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let server = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_singbox_server_named(profile, name)?,
                    None => create_singbox_server_next(profile)?,
                };
                Ok(json!({"ok": true, "server": server}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "programs", "sing-box", "profiles", profile, "servers", server]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let src = singbox_server_root(profile, server);
                if !src.exists() {
                    anyhow::bail!("server not found");
                }
                let deleted_dir = singbox_deleted_servers_root(profile);
                fs::create_dir_all(&deleted_dir).ok();
                let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                let dst = deleted_dir.join(format!("{server}.{ts}"));
                let _ = fs::rename(&src, &dst);
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box", "profiles", profile, "servers", server, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                ensure_singbox_profile_layout(profile)?;
                let root = singbox_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("setting.json");
                if !p.exists() {
                    let port = suggest_singbox_server_port()?;
                    write_json_pretty(&p, &default_singbox_server_setting_value(port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "sing-box", "profiles", profile, "servers", server, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let v: serde_json::Value = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let port = v.get("port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok())
                    .ok_or_else(|| anyhow::anyhow!("port is required"))?;
                if port == 0 {
                    anyhow::bail!("invalid port");
                }
                let root = singbox_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("setting.json");
                write_json_pretty(&p, &v)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "sing-box", "profiles", profile, "servers", server, "config"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let root = singbox_server_root(profile, server);
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
        ("PUT", ["api", "programs", "sing-box", "profiles", profile, "servers", server, "config"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_valid_singbox_profile_name(server)?;
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let root = singbox_server_root(profile, server);
                fs::create_dir_all(root.join("log"))?;
                let p = root.join("config.json");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }


        // --- wireproxy profile/server API
        ("GET", ["api", "programs", "wireproxy", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let active: ProfilesActive = read_json(&wireproxy_active_path()).unwrap_or_default();
                let mut profiles = Vec::new();
                for (name, st) in active.profiles {
                    profiles.push(json!({"name": name, "enabled": st.enabled}));
                }
                profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
                Ok(json!({"ok": true, "profiles": profiles}))
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
                let p = wireproxy_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                if !active.profiles.contains_key(*profile) {
                    anyhow::bail!("profile not found");
                }
                active.profiles.insert(profile.to_string(), ProfileState { enabled: req.enabled });
                write_json_pretty(&p, &active)?;
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
                let p = wireproxy_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                active.profiles.remove(*profile);
                write_json_pretty(&p, &active)?;
                invalidate_assignment_cache();

                let src = wireproxy_profile_root(profile);
                if src.exists() {
                    let deleted_dir = wireproxy_deleted_profiles_root();
                    fs::create_dir_all(&deleted_dir).ok();
                    let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                    let dst = deleted_dir.join(format!("{profile}.{ts}"));
                    let _ = fs::rename(&src, &dst);
                }
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
                        servers.push(json!({"name": name, "data": data, "bind": bind}));
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
                let enabled = v.get("enabled").and_then(|x| x.as_bool())
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
                validate_program_apps_content(&req.content, &api_path, id, slot_from_kind(kind).ok_or_else(|| anyhow::anyhow!("invalid apps kind"))?)?;
                let p = profile_root(id, profile).join(format!("app/uid/{fname}"));
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
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

        // --- myproxy profile API
        ("GET", ["api", "programs", "myproxy", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let active: ProfilesActive = read_json(&myproxy_active_path()).unwrap_or_default();
                let mut profiles = Vec::new();
                for (name, st) in active.profiles {
                    profiles.push(json!({"name": name, "enabled": st.enabled}));
                }
                profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
                Ok(json!({"ok": true, "profiles": profiles}))
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
                let p = myproxy_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                if !active.profiles.contains_key(*profile) { anyhow::bail!("profile not found"); }
                active.profiles.insert(profile.to_string(), ProfileState { enabled: req.enabled });
                write_json_pretty(&p, &active)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("DELETE", ["api", "programs", "myproxy", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let p = myproxy_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                active.profiles.remove(*profile);
                write_json_pretty(&p, &active)?;
                invalidate_assignment_cache();
                let src = myproxy_profile_root(profile);
                if src.exists() {
                    let deleted_dir = myproxy_deleted_profiles_root();
                    fs::create_dir_all(&deleted_dir).ok();
                    let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                    let dst = deleted_dir.join(format!("{profile}.{ts}"));
                    let _ = fs::rename(&src, &dst);
                }
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
                    if t2s_port == proxy_cfg.port || t2s_web_port == proxy_cfg.port { anyhow::bail!("t2s ports must not match upstream port"); }
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
                Ok(json!({"ok": true, "data": v}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myproxy", "profiles", profile, "proxy"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myproxy_profile_layout(profile)?;
                let proxy_cfg: crate::programs::myproxy::ProxyConfig = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myproxy::validate_proxy_config(&proxy_cfg)?;
                let setting_path = myproxy_profile_root(profile).join("setting.json");
                if let Ok(setting_v) = read_json::<serde_json::Value>(&setting_path) {
                    let t2s_port = setting_v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
                    let t2s_web_port = setting_v.get("t2s_web_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()).unwrap_or(0);
                    if proxy_cfg.port == t2s_port || proxy_cfg.port == t2s_web_port { anyhow::bail!("upstream port must not match t2s ports"); }
                }
                let p = myproxy_profile_root(profile).join("proxy.json");
                write_json_pretty(&p, &proxy_cfg)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }

        // --- myprogram profile API
        ("GET", ["api", "programs", "myprogram", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let active: ProfilesActive = read_json(&myprogram_active_path()).unwrap_or_default();
                let mut profiles = Vec::new();
                for (name, st) in active.profiles {
                    profiles.push(json!({"name": name, "enabled": st.enabled}));
                }
                profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
                Ok(json!({"ok": true, "profiles": profiles}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "myprogram", "profiles"]) => {
            let res = (|| -> Result<serde_json::Value> {
                #[derive(Deserialize)] struct CreateReq { #[serde(default)] name: Option<String> }
                let req: CreateReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let profile = match req.name.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(name) => create_myprogram_profile_named(name)?,
                    None => create_myprogram_profile_next()?,
                };
                Ok(json!({"ok": true, "profile": profile}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "enabled"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let req: EnabledReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = myprogram_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                if !active.profiles.contains_key(*profile) { anyhow::bail!("profile not found"); }
                active.profiles.insert(profile.to_string(), ProfileState { enabled: req.enabled });
                write_json_pretty(&p, &active)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("DELETE", ["api", "programs", "myprogram", "profiles", profile]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let p = myprogram_active_path();
                let mut active: ProfilesActive = read_json(&p).unwrap_or_default();
                active.profiles.remove(*profile);
                write_json_pretty(&p, &active)?;
                invalidate_assignment_cache();
                let src = myprogram_profile_root(profile);
                if src.exists() {
                    let deleted_dir = myprogram_deleted_profiles_root();
                    fs::create_dir_all(&deleted_dir).ok();
                    let ts = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
                    let dst = deleted_dir.join(format!("{profile}.{ts}"));
                    let _ = fs::rename(&src, &dst);
                }
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myprogram_profile_layout(profile)?;
                let p = myprogram_profile_root(profile).join("setting.json");
                if !p.exists() {
                    let (t2s_port, t2s_web_port) = suggest_myprogram_profile_ports()?;
                    write_json_pretty(&p, &default_myprogram_profile_setting_value(t2s_port, t2s_web_port))?;
                }
                let v: serde_json::Value = read_json(&p)?;
                Ok(json!({"ok": true, "data": v}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "setting"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myprogram_profile_layout(profile)?;
                let setting: crate::programs::myprogram::ProfileSetting = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myprogram::save_setting(profile, &setting)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myprogram_profile_layout(profile)?;
                let p = myprogram_profile_root(profile).join("app/uid/user_program");
                read_text_or_empty(&p)
            })();
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "apps", "user"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_myprogram_profile_layout(profile)?;
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let api_path = format!("/api/programs/myprogram/profiles/{}/apps/user", profile);
                validate_program_apps_content(&req.content, &api_path, "myprogram", "common")?;
                let p = myprogram_profile_root(profile).join("app/uid/user_program");
                write_text_atomic(&p, &req.content)?;
                invalidate_assignment_cache();
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "command"]) => {
            let res = (|| -> Result<String> {
                ensure_valid_singbox_profile_name(profile)?;
                crate::programs::myprogram::read_command_text(profile)
            })();
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "command"]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myprogram::write_command_text(profile, &req.content)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "t2s_ports"]) => {
            let res = crate::programs::myprogram::read_t2s_ports_text(profile);
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "t2s_ports"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myprogram::write_t2s_ports_text(profile, &req.content)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "protect_ports"]) => {
            let res = crate::programs::myprogram::read_protect_ports_text(profile);
            match res { Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})), Err(e) => write_err(stream, e) }
        }
        ("PUT", ["api", "programs", "myprogram", "profiles", profile, "protect_ports"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body).map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::programs::myprogram::write_protect_ports_text(profile, &req.content)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "programs", "myprogram", "profiles", profile, "bin"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                let files = crate::programs::myprogram::list_bin_files(profile)?;
                Ok(json!({"ok": true, "files": files.iter().map(|(name,size)| json!({"name": name, "size": size})).collect::<Vec<_>>() }))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "myprogram", "profiles", profile, "bin", "upload"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                let ct = headers.get("content-type").cloned().unwrap_or_default();
                let (filename, data) = if ct.starts_with("multipart/form-data") {
                    let f = parse_multipart_file(headers, body)?;
                    (f.filename, f.data)
                } else {
                    let filename = headers.get("x-filename").cloned().ok_or_else(|| anyhow::anyhow!("missing x-filename header"))?;
                    ensure_safe_filename(&filename)?;
                    (filename, body.to_vec())
                };
                crate::programs::myprogram::save_bin_file(profile, &filename, &data)?;
                Ok(json!({"ok": true, "file": filename, "size": data.len(), "sha256": sha256_hex_bytes(&data)}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("DELETE", ["api", "programs", "myprogram", "profiles", profile, "bin", filename]) => {
            let res = (|| -> Result<()> {
                ensure_valid_singbox_profile_name(profile)?;
                ensure_safe_filename(filename)?;
                crate::programs::myprogram::delete_bin_file(profile, filename)?;
                Ok(())
            })();
            match res { Ok(_) => write_ok(stream), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "programs", "myprogram", "profiles", profile, "apply"]) => {
            let res = (|| -> Result<serde_json::Value> {
                ensure_valid_singbox_profile_name(profile)?;
                let setting = crate::programs::myprogram::load_setting(profile)?;
                let _ = if setting.apps_mode && setting.route_mode == "t2s" { Some(crate::programs::myprogram::parse_port_list_str(&crate::programs::myprogram::read_t2s_ports_text(profile)?)?) } else { None };
                let _ = crate::programs::myprogram::parse_port_list_str(&crate::programs::myprogram::read_protect_ports_text(profile)?)?;
                if setting.apps_mode {
                    let _ = crate::android::pkg_uid::unified_processing(crate::android::pkg_uid::Mode::Default, &crate::android::pkg_uid::Sha256Tracker::new(crate::settings::SHARED_SHA_FLAG_FILE), &myprogram_profile_root(profile).join("app/out/user_program"), &myprogram_profile_root(profile).join("app/uid/user_program"))?;
                }
                let runtime = crate::programs::myprogram::load_runtime(profile).unwrap_or_default();
                let active = runtime.running && runtime.pid > 1 && std::path::Path::new("/proc").join(runtime.pid.to_string()).is_dir();
                Ok(json!({"ok": true, "active": active}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }


        // --- tor enabled/apps/config
        ("GET", ["api", "programs", "tor"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::programs::tor::load_enabled_json()?.is_enabled();
                let apps = crate::programs::tor::read_uid_program_text()?;
                let setting = crate::programs::tor::load_setting()?;
                let torrc = crate::programs::tor::read_torrc_text()?;
                let socks_port = crate::programs::tor::parse_socks_port_from_str(&torrc).ok();
                let bridges = crate::programs::tor::bridge_count_from_str(&torrc);
                let active = stats::collect_status().map(|r| r.tor.count > 0).unwrap_or(false);
                Ok(json!({"ok": true, "enabled": enabled, "apps": apps, "setting": setting, "torrc": torrc, "socks_port": socks_port, "bridge_count": bridges, "active": active}))
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
                if args.api_proxy.trim().is_empty() {
                    anyhow::bail!("api_proxy must not be empty");
                }
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

        // --- legacy sing-box routes kept only to explain migration
        ("GET", ["api", "programs", "sing-box", "setting"]) | ("PUT", ["api", "programs", "sing-box", "setting"]) => {
            write_json(stream, 200, json!({
                "ok": false,
                "error": "legacy route removed; use /api/programs/sing-box/profiles/<profile>/setting"
            }))
        }
        ("GET", ["api", "programs", "sing-box", "apps", "user"]) | ("PUT", ["api", "programs", "sing-box", "apps", "user"]) => {
            write_json(stream, 200, json!({
                "ok": false,
                "error": "legacy route removed; use /api/programs/sing-box/profiles/<profile>/apps/user"
            }))
        }
        ("GET", ["api", "programs", "sing-box", "profiles", _, "config"]) | ("PUT", ["api", "programs", "sing-box", "profiles", _, "config"]) => {
            write_json(stream, 200, json!({
                "ok": false,
                "error": "legacy route removed; use /api/programs/sing-box/profiles/<profile>/servers/<server>/config"
            }))
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
        return handle_programs_subroutes(stream, method.as_str(), path.as_str(), &headers, &body);
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
            let report = get_status_cached(services_running)?;
            write_json(stream, 200, serde_json::to_value(report)?)
        }

        ("GET", "/api/setting") => {
            let setting = settings::load_api_settings()?;
            write_json(stream, 200, json!({"ok": true, "setting": setting}))
        }
        ("POST", "/api/setting") => {
            #[derive(Deserialize, Default)]
            struct SettingPatch {
                #[serde(default)]
                protector_mode: Option<settings::ProtectorMode>,
                #[serde(default)]
                hotspot_t2s_enabled: Option<bool>,
                #[serde(default)]
                hotspot_t2s_target: Option<String>,
                #[serde(default)]
                hotspot_t2s_singbox_profile: Option<String>,
                #[serde(default)]
                hotspot_t2s_wireproxy_profile: Option<String>,
            }

            let patch: SettingPatch = serde_json::from_slice(&body)
                .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
            let mut setting = settings::load_api_settings().unwrap_or_default();
            if let Some(mode) = patch.protector_mode {
                setting.protector_mode = mode;
            }
            if let Some(enabled) = patch.hotspot_t2s_enabled {
                setting.hotspot_t2s_enabled = enabled;
            }
            if let Some(target) = patch.hotspot_t2s_target {
                setting.hotspot_t2s_target = target;
            }
            if let Some(profile) = patch.hotspot_t2s_singbox_profile {
                setting.hotspot_t2s_singbox_profile = profile;
            }
            if let Some(profile) = patch.hotspot_t2s_wireproxy_profile {
                setting.hotspot_t2s_wireproxy_profile = profile;
            }
            settings::save_api_settings(&setting)?;
            let saved = settings::load_api_settings().unwrap_or(setting);
            protector::refresh(services_running);
            write_json(stream, 200, json!({"ok": true, "setting": saved}))
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
                let name = if program == "sing-box" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_singbox_profile_named(p)?,
                        None => create_singbox_profile_next()?,
                    }
                } else if program == "wireproxy" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_wireproxy_profile_named(p)?,
                        None => create_wireproxy_profile_next()?,
                    }
                } else if program == "myproxy" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_myproxy_profile_named(p)?,
                        None => create_myproxy_profile_next()?,
                    }
                } else if program == "myprogram" {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_myprogram_profile_named(p)?,
                        None => create_myprogram_profile_next()?,
                    }
                } else {
                    match req.profile.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(p) => create_named_profile(program, p)?,
                        None => create_next_profile(program)?,
                    }
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

        ("GET", "/api/blockedquic") => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::blockedquic::load_enabled_json()?.is_enabled();
                let apps = crate::blockedquic::read_uid_program_text()?;
                Ok(json!({"ok": true, "enabled": enabled, "apps": apps, "active": services_running && crate::blockedquic::is_active()}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", "/api/blockedquic/enabled") => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::blockedquic::load_enabled_json()?.is_enabled();
                Ok(json!({"ok": true, "enabled": enabled}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", "/api/blockedquic/enabled") => {
            let res = (|| -> Result<serde_json::Value> {
                let req: EnabledReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let v = crate::blockedquic::save_enabled_value(req.enabled)?;
                Ok(json!({"ok": true, "enabled": v.is_enabled()}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", "/api/blockedquic/apps") => {
            let res = crate::blockedquic::read_uid_program_text();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", "/api/blockedquic/apps") => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::blockedquic::write_uid_program_text(&req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", "/api/blockedquic/save") => {
            let res = (|| -> Result<serde_json::Value> {
                let req: ContentReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::blockedquic::write_uid_program_text(&req.content)?;
                Ok(json!({"ok": true, "saved": true}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", "/api/blockedquic/apply") => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::blockedquic::load_enabled_json()?.is_enabled();
                if !enabled {
                    crate::blockedquic::clear_rules()?;
                    return Ok(json!({"ok": true, "active": false}));
                }
                let _ = crate::blockedquic::rebuild_out_program()?;
                if services_running {
                    let active = crate::blockedquic::refresh_runtime(true)?;
                    Ok(json!({"ok": true, "active": active}))
                } else {
                    crate::blockedquic::clear_rules()?;
                    Ok(json!({"ok": true, "active": false}))
                }
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", "/api/proxyinfo") => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::proxyinfo::load_enabled_json()?.is_enabled();
                let apps = crate::proxyinfo::read_uid_program_text()?;
                Ok(json!({"ok": true, "enabled": enabled, "apps": apps, "active": services_running && crate::proxyinfo::is_active()}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", "/api/proxyinfo/enabled") => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::proxyinfo::load_enabled_json()?.is_enabled();
                Ok(json!({"ok": true, "enabled": enabled}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", "/api/proxyinfo/enabled") => {
            let res = (|| -> Result<serde_json::Value> {
                let req: EnabledReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let v = crate::proxyinfo::save_enabled_value(req.enabled)?;
                Ok(json!({"ok": true, "enabled": v.is_enabled()}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", "/api/proxyinfo/apps") => {
            let res = crate::proxyinfo::read_uid_program_text();
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", "/api/proxyinfo/apps") => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                validate_proxyinfo_apps_content(&req.content)?;
                crate::proxyinfo::write_uid_program_text(&req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", "/api/apps/assignments") => {
            let res = (|| -> Result<serde_json::Value> {
                let lists = collect_assignment_files()
                    .into_iter()
                    .map(|v| assignment_to_view(&v))
                    .collect::<Vec<_>>();
                let proxyinfo = crate::proxyinfo::read_proxy_packages()?.into_iter().collect::<Vec<_>>();
                Ok(json!({"ok": true, "lists": lists, "proxyinfo_packages": proxyinfo}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", "/api/proxyinfo/save") => {
            let res = (|| -> Result<serde_json::Value> {
                let req: ProxyInfoSaveReq = serde_json::from_slice(&body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let candidate = parse_package_set(&req.content);
                let conflicts = find_proxyinfo_conflicts(&candidate);
                if !conflicts.is_empty() && !req.remove_conflicts {
                    return Ok(json!({"ok": true, "saved": false, "conflicts": flatten_conflicts(&conflicts)}));
                }
                let removed = if req.remove_conflicts {
                    remove_packages_from_program_lists(&candidate)?
                } else {
                    Vec::new()
                };
                crate::proxyinfo::write_uid_program_text(&req.content)?;
                Ok(json!({"ok": true, "saved": true, "removed": removed}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", "/api/proxyinfo/apply") => {
            let res = (|| -> Result<serde_json::Value> {
                let enabled = crate::proxyinfo::load_enabled_json()?.is_enabled();
                if !enabled {
                    crate::proxyinfo::clear_rules()?;
                    return Ok(json!({"ok": true, "active": false}));
                }
                let _ = crate::proxyinfo::rebuild_out_program()?;
                if services_running {
                    let active = crate::proxyinfo::refresh_runtime(true)?;
                    Ok(json!({"ok": true, "active": active}))
                } else {
                    crate::proxyinfo::clear_rules()?;
                    Ok(json!({"ok": true, "active": false}))
                }
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
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

