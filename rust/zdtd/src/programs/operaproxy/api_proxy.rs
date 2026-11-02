
use anyhow::{Context, Result};

use log::{info, warn};
use std::{collections::BTreeSet, fs::{self, OpenOptions}, io::{Read, Write}, net::{TcpStream, ToSocketAddrs}, path::{Path}, time::{Duration, Instant}, sync::mpsc};


use super::model::*;


#[derive(Debug, Clone)]
pub(super) struct ApiProxyCandidate {
    pub(super) original: String,
    pub(super) scheme: String,
    pub(super) host: String,
    pub(super) port: u16,
    pub(super) username: Option<String>,
    pub(super) password: Option<String>,
}

pub(super) fn select_working_api_proxy(raw: &str, log_path: &Path) -> Option<String> {
    let candidates = collect_api_proxy_candidates(raw, log_path);
    if candidates.is_empty() {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            append_log_line(log_path, "api_proxy is empty -> start without -api-proxy");
        } else {
            append_log_line(log_path, "no valid api_proxy candidates -> start without -api-proxy");
        }
        return None;
    }

    append_log_line(
        log_path,
        &format!(
            "checking {} api_proxy candidate(s), parallel={}",
            candidates.len(),
            API_PROXY_CHECK_PARALLEL
        ),
    );

    for chunk in candidates.chunks(API_PROXY_CHECK_PARALLEL.max(1)) {
        let (tx, rx) = mpsc::channel();
        let mut handles = Vec::new();
        for (idx, cand) in chunk.iter().cloned().enumerate() {
            let tx = tx.clone();
            handles.push(std::thread::spawn(move || {
                let ok = check_api_proxy_candidate(&cand, API_PROXY_CHECK_TIMEOUT);
                let _ = tx.send((idx, ok, cand));
            }));
        }
        drop(tx);

        let deadline = Instant::now() + API_PROXY_CHECK_TIMEOUT + Duration::from_millis(750);
        let mut results: Vec<(usize, bool, ApiProxyCandidate)> = Vec::new();
        while results.len() < chunk.len() {
            let now = Instant::now();
            if now >= deadline {
                break;
            }
            let timeout = deadline.saturating_duration_since(now);
            match rx.recv_timeout(timeout) {
                Ok(item) => results.push(item),
                Err(_) => break,
            }
        }

        for handle in handles {
            let _ = handle.join();
        }

        results.sort_by_key(|(idx, _, _)| *idx);
        for (_, ok, cand) in &results {
            append_log_line(
                log_path,
                &format!(
                    "{} {}",
                    if *ok { "OK" } else { "FAIL" },
                    mask_proxy_for_log(&cand.original)
                ),
            );
        }

        if let Some((_, _, cand)) = results.into_iter().find(|(_, ok, _)| *ok) {
            append_log_line(
                log_path,
                &format!("selected {}", mask_proxy_for_log(&cand.original)),
            );
            info!("operaproxy: selected api_proxy {}", mask_proxy_for_log(&cand.original));
            return Some(cand.original);
        }
    }

    append_log_line(log_path, "no working api_proxy found -> start without -api-proxy");
    warn!("operaproxy: no working api_proxy found -> start without -api-proxy");
    None
}

pub(super) fn collect_api_proxy_candidates(raw: &str, log_path: &Path) -> Vec<ApiProxyCandidate> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }

    if is_api_proxy_list_file_path(trimmed) {
        append_log_line(log_path, "api_proxy list file mode: 1 file");
        return collect_api_proxy_candidates_from_files(&[trimmed.to_string()], log_path);
    }

    let raw_tokens = split_api_proxy_tokens(trimmed);
    let list_files: Vec<String> = raw_tokens
        .iter()
        .filter(|token| is_api_proxy_list_file_path(token))
        .cloned()
        .collect();
    let list_urls: Vec<String> = raw_tokens
        .iter()
        .filter(|token| is_api_proxy_list_url(token))
        .cloned()
        .collect();

    if !list_files.is_empty() {
        append_log_line(
            log_path,
            &format!("api_proxy list file mode: {} file(s)", list_files.len()),
        );
        return collect_api_proxy_candidates_from_files(&list_files, log_path);
    }

    if !list_urls.is_empty() {
        append_log_line(
            log_path,
            &format!("api_proxy list url mode: {} URL(s)", list_urls.len()),
        );
        return collect_api_proxy_candidates_from_urls(&list_urls, log_path);
    }

    parse_api_proxy_candidates_from_text(trimmed, log_path)
}

pub(super) fn collect_api_proxy_candidates_from_urls(urls: &[String], log_path: &Path) -> Vec<ApiProxyCandidate> {
    let list_path = Path::new(API_PROXY_LIST_TXT);
    // Always reset the cached list before downloading so every start uses a fresh file.
    // If downloading fails, the stale previous list is not reused.
    if let Err(e) = write_api_proxy_list_file(list_path, "") {
        append_log_line(
            log_path,
            &format!("failed to reset api_proxy list {}: {}", list_path.display(), e),
        );
    }
    let mut combined = String::new();

    for url in urls {
        append_log_line(
            log_path,
            &format!("downloading api_proxy list: {}", mask_proxy_for_log(url)),
        );
        match download_api_proxy_list(url) {
            Ok(text) => {
                append_log_line(
                    log_path,
                    &format!(
                        "downloaded {} byte(s) from {}",
                        text.as_bytes().len(),
                        mask_proxy_for_log(url)
                    ),
                );
                if !combined.is_empty() {
                    combined.push('\n');
                }
                combined.push_str(&text);
            }
            Err(e) => {
                append_log_line(
                    log_path,
                    &format!(
                        "failed to download api_proxy list from {}: {}",
                        mask_proxy_for_log(url),
                        e
                    ),
                );
            }
        }
    }

    if combined.trim().is_empty() {
        append_log_line(log_path, "api_proxy list download failed or empty -> start without -api-proxy");
        warn!("operaproxy: api_proxy list download failed or empty -> start without -api-proxy");
        return Vec::new();
    }

    if let Err(e) = write_api_proxy_list_file(list_path, &combined) {
        append_log_line(
            log_path,
            &format!("failed to save api_proxy list {}: {}", list_path.display(), e),
        );
        warn!("operaproxy: failed to save api_proxy list {}: {}", list_path.display(), e);
    } else {
        append_log_line(log_path, &format!("saved api_proxy list: {}", list_path.display()));
    }

    let candidates = parse_api_proxy_candidates_from_text(&combined, log_path);
    append_log_line(
        log_path,
        &format!("parsed {} valid candidate(s) from downloaded api_proxy list", candidates.len()),
    );
    candidates
}

pub(super) fn collect_api_proxy_candidates_from_files(paths: &[String], log_path: &Path) -> Vec<ApiProxyCandidate> {
    let list_path = Path::new(API_PROXY_LIST_TXT);
    // Always reset the cached list before reading so every start uses current file contents.
    if let Err(e) = write_api_proxy_list_file(list_path, "") {
        append_log_line(
            log_path,
            &format!("failed to reset api_proxy list {}: {}", list_path.display(), e),
        );
    }
    let mut combined = String::new();

    for path in paths {
        append_log_line(log_path, &format!("reading api_proxy list file: {}", path));
        match read_api_proxy_list_file(path) {
            Ok(text) => {
                append_log_line(
                    log_path,
                    &format!("read {} byte(s) from {}", text.as_bytes().len(), path),
                );
                if !combined.is_empty() {
                    combined.push('\n');
                }
                combined.push_str(&text);
            }
            Err(e) => {
                append_log_line(
                    log_path,
                    &format!("failed to read api_proxy list file {}: {}", path, e),
                );
            }
        }
    }

    if combined.trim().is_empty() {
        append_log_line(log_path, "api_proxy list file read failed or empty -> start without -api-proxy");
        warn!("operaproxy: api_proxy list file read failed or empty -> start without -api-proxy");
        return Vec::new();
    }

    if let Err(e) = write_api_proxy_list_file(list_path, &combined) {
        append_log_line(
            log_path,
            &format!("failed to save api_proxy list {}: {}", list_path.display(), e),
        );
        warn!("operaproxy: failed to save api_proxy list {}: {}", list_path.display(), e);
    } else {
        append_log_line(log_path, &format!("saved api_proxy list: {}", list_path.display()));
    }

    let candidates = parse_api_proxy_candidates_from_text(&combined, log_path);
    append_log_line(
        log_path,
        &format!("parsed {} valid candidate(s) from local api_proxy list", candidates.len()),
    );
    candidates
}

pub(super) fn download_api_proxy_list(url: &str) -> Result<String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(API_PROXY_LIST_DOWNLOAD_TIMEOUT)
        .user_agent("ZDT-D/1")
        .redirect(reqwest::redirect::Policy::limited(5))
        .build()
        .with_context(|| "build api_proxy list HTTP client")?;

    let response = client
        .get(url)
        .send()
        .with_context(|| format!("GET {}", mask_proxy_for_log(url)))?;

    let status = response.status();
    if !status.is_success() {
        anyhow::bail!("HTTP status {}", status);
    }

    let mut body = Vec::new();
    let mut limited = response.take((API_PROXY_LIST_MAX_BYTES + 1) as u64);
    limited
        .read_to_end(&mut body)
        .with_context(|| "read api_proxy list response")?;
    if body.len() > API_PROXY_LIST_MAX_BYTES {
        anyhow::bail!("api_proxy list is larger than {} byte(s)", API_PROXY_LIST_MAX_BYTES);
    }

    Ok(String::from_utf8_lossy(&body).into_owned())
}

pub(super) fn read_api_proxy_list_file(path: &str) -> Result<String> {
    let path = Path::new(path.trim());
    if !path.is_absolute() {
        anyhow::bail!("api_proxy list file path must be absolute");
    }
    let file = fs::File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut body = Vec::new();
    let mut limited = file.take((API_PROXY_LIST_MAX_BYTES + 1) as u64);
    limited
        .read_to_end(&mut body)
        .with_context(|| format!("read {}", path.display()))?;
    if body.len() > API_PROXY_LIST_MAX_BYTES {
        anyhow::bail!("api_proxy list is larger than {} byte(s)", API_PROXY_LIST_MAX_BYTES);
    }

    Ok(String::from_utf8_lossy(&body).into_owned())
}

pub(super) fn write_api_proxy_list_file(path: &Path, text: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    fs::write(path, text.as_bytes()).with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

pub(super) fn parse_api_proxy_candidates_from_text(raw: &str, log_path: &Path) -> Vec<ApiProxyCandidate> {
    let mut out = Vec::new();
    let mut seen = BTreeSet::new();

    for token in split_api_proxy_tokens(raw) {
        if is_api_proxy_list_url(&token) {
            append_log_line(
                log_path,
                &format!(
                    "skip nested api_proxy list URL inside downloaded/list text: {}",
                    mask_proxy_for_log(&token)
                ),
            );
            continue;
        }
        match parse_api_proxy_candidate(&token) {
            Some(candidate) => {
                if seen.insert(candidate.original.clone()) {
                    out.push(candidate);
                }
            }
            None => append_log_line(
                log_path,
                &format!(
                    "skip invalid api_proxy candidate: {}",
                    mask_proxy_for_log(&token)
                ),
            ),
        }
    }

    out
}

pub(super) fn split_api_proxy_tokens(raw: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    for line in raw.lines() {
        let without_comment = line.split_once('#').map(|(head, _)| head).unwrap_or(line);
        for part in without_comment.split(|c: char| c == ',' || c.is_whitespace()) {
            let token = part.trim();
            if !token.is_empty() {
                tokens.push(token.to_string());
            }
        }
    }
    tokens
}

pub(super) fn is_api_proxy_list_url(token: &str) -> bool {
    let Some((scheme_raw, rest_raw)) = token.trim().split_once("://") else {
        return false;
    };
    let scheme = scheme_raw.trim().to_ascii_lowercase();
    if !matches!(scheme.as_str(), "http" | "https") {
        return false;
    }
    let rest = rest_raw.trim();
    if rest.is_empty() {
        return false;
    }

    let authority_end = rest
        .find(|c| matches!(c, '/' | '?' | '#'))
        .unwrap_or(rest.len());
    let authority = &rest[..authority_end];
    let tail = &rest[authority_end..];
    if authority.is_empty() || tail.is_empty() {
        return false;
    }

    let path_or_query_is_not_root = !matches!(tail, "/" | "" | "?" | "#");
    path_or_query_is_not_root
}

pub(super) fn is_api_proxy_list_file_path(token: &str) -> bool {
    let token = token.trim();
    !token.is_empty() && Path::new(token).is_absolute()
}

pub(super) fn parse_api_proxy_candidate(token: &str) -> Option<ApiProxyCandidate> {
    let (scheme_raw, rest_raw) = token.split_once("://")?;
    let scheme = scheme_raw.trim().to_ascii_lowercase();
    if !matches!(scheme.as_str(), "http" | "https" | "socks5" | "socks5h") {
        return None;
    }

    let rest = rest_raw.trim();
    if rest.is_empty() || rest.contains('/') || rest.contains('?') || rest.contains('#') {
        return None;
    }

    let (userinfo, hostport) = match rest.rsplit_once('@') {
        Some((u, hp)) => (Some(u), hp),
        None => (None, rest),
    };

    let (username, password) = match userinfo {
        Some(u) => {
            if u.is_empty() {
                return None;
            }
            let (name, pass) = u.split_once(':').unwrap_or((u, ""));
            if name.is_empty() {
                return None;
            }
            (Some(name.to_string()), Some(pass.to_string()))
        }
        None => (None, None),
    };

    let (host, port) = parse_host_port(hostport)?;
    Some(ApiProxyCandidate {
        original: token.to_string(),
        scheme,
        host,
        port,
        username,
        password,
    })
}

pub(super) fn parse_host_port(hostport: &str) -> Option<(String, u16)> {
    let hostport = hostport.trim();
    if hostport.is_empty() {
        return None;
    }

    if let Some(rest) = hostport.strip_prefix('[') {
        let (host, tail) = rest.split_once(']')?;
        let port_raw = tail.strip_prefix(':')?;
        let port = port_raw.parse::<u16>().ok()?;
        if host.trim().is_empty() || port == 0 {
            return None;
        }
        return Some((host.trim().to_string(), port));
    }

    let (host, port_raw) = hostport.rsplit_once(':')?;
    let port = port_raw.parse::<u16>().ok()?;
    if host.trim().is_empty() || port == 0 {
        return None;
    }
    Some((host.trim().to_string(), port))
}

pub(super) fn check_api_proxy_candidate(candidate: &ApiProxyCandidate, timeout: Duration) -> bool {
    match candidate.scheme.as_str() {
        "socks5" | "socks5h" => check_socks5_proxy(candidate, timeout),
        "http" => check_http_proxy(candidate, timeout),
        // std does not provide TLS, so HTTPS proxy validation is limited to TCP reachability.
        // The candidate is still passed to opera-proxy only if its proxy endpoint accepts TCP.
        "https" => connect_proxy_tcp(candidate, timeout).is_ok(),
        _ => false,
    }
}

pub(super) fn connect_proxy_tcp(candidate: &ApiProxyCandidate, timeout: Duration) -> std::io::Result<TcpStream> {
    let addrs = (candidate.host.as_str(), candidate.port).to_socket_addrs()?;
    let mut last_err = None;
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, timeout) {
            Ok(stream) => {
                let _ = stream.set_read_timeout(Some(timeout));
                let _ = stream.set_write_timeout(Some(timeout));
                return Ok(stream);
            }
            Err(e) => last_err = Some(e),
        }
    }
    Err(last_err.unwrap_or_else(|| std::io::Error::new(std::io::ErrorKind::Other, "no socket address")))
}

pub(super) fn check_socks5_proxy(candidate: &ApiProxyCandidate, timeout: Duration) -> bool {
    let mut stream = match connect_proxy_tcp(candidate, timeout) {
        Ok(s) => s,
        Err(_) => return false,
    };

    let use_auth = candidate.username.as_deref().map(|s| !s.is_empty()).unwrap_or(false);
    let greeting: &[u8] = if use_auth { &[0x05, 0x02, 0x00, 0x02] } else { &[0x05, 0x01, 0x00] };
    if stream.write_all(greeting).is_err() {
        return false;
    }
    let mut method = [0u8; 2];
    if stream.read_exact(&mut method).is_err() || method[0] != 0x05 || method[1] == 0xff {
        return false;
    }

    if method[1] == 0x02 {
        let user = candidate.username.as_deref().unwrap_or("").as_bytes();
        let pass = candidate.password.as_deref().unwrap_or("").as_bytes();
        if user.is_empty() || user.len() > 255 || pass.len() > 255 {
            return false;
        }
        let mut auth = Vec::with_capacity(3 + user.len() + pass.len());
        auth.push(0x01);
        auth.push(user.len() as u8);
        auth.extend_from_slice(user);
        auth.push(pass.len() as u8);
        auth.extend_from_slice(pass);
        if stream.write_all(&auth).is_err() {
            return false;
        }
        let mut auth_reply = [0u8; 2];
        if stream.read_exact(&mut auth_reply).is_err() || auth_reply[1] != 0x00 {
            return false;
        }
    } else if method[1] != 0x00 {
        return false;
    }

    let target_host = API_PROXY_CHECK_TARGET_HOST.as_bytes();
    if target_host.len() > 255 {
        return false;
    }
    let mut req = Vec::with_capacity(7 + target_host.len());
    req.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, target_host.len() as u8]);
    req.extend_from_slice(target_host);
    req.extend_from_slice(&API_PROXY_CHECK_TARGET_PORT.to_be_bytes());
    if stream.write_all(&req).is_err() {
        return false;
    }

    let mut head = [0u8; 4];
    if stream.read_exact(&mut head).is_err() || head[0] != 0x05 || head[1] != 0x00 {
        return false;
    }

    match head[3] {
        0x01 => read_and_discard(&mut stream, 4 + 2),
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).is_ok() && read_and_discard(&mut stream, len[0] as usize + 2)
        }
        0x04 => read_and_discard(&mut stream, 16 + 2),
        _ => false,
    }
}

pub(super) fn check_http_proxy(candidate: &ApiProxyCandidate, timeout: Duration) -> bool {
    let mut stream = match connect_proxy_tcp(candidate, timeout) {
        Ok(s) => s,
        Err(_) => return false,
    };

    let target = format!("{}:{}", API_PROXY_CHECK_TARGET_HOST, API_PROXY_CHECK_TARGET_PORT);
    let mut req = format!(
        "CONNECT {target} HTTP/1.1\r\nHost: {target}\r\nProxy-Connection: close\r\nUser-Agent: ZDT-D/1\r\n"
    );
    if let Some(user) = candidate.username.as_deref().filter(|s| !s.is_empty()) {
        let pass = candidate.password.as_deref().unwrap_or("");
        let encoded = base64_basic_auth(&format!("{}:{}", user, pass));
        req.push_str(&format!("Proxy-Authorization: Basic {}\r\n", encoded));
    }
    req.push_str("\r\n");

    if stream.write_all(req.as_bytes()).is_err() {
        return false;
    }
    let mut buf = [0u8; 192];
    let n = match stream.read(&mut buf) {
        Ok(n) => n,
        Err(_) => return false,
    };
    if n == 0 {
        return false;
    }
    let status = String::from_utf8_lossy(&buf[..n]);
    status.starts_with("HTTP/1.1 200") || status.starts_with("HTTP/1.0 200")
}

pub(super) fn read_and_discard(stream: &mut TcpStream, len: usize) -> bool {
    let mut buf = vec![0u8; len];
    stream.read_exact(&mut buf).is_ok()
}

pub(super) fn base64_basic_auth(input: &str) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let bytes = input.as_bytes();
    let mut out = String::with_capacity(((bytes.len() + 2) / 3) * 4);
    let mut i = 0;
    while i < bytes.len() {
        let b0 = bytes[i];
        let b1 = if i + 1 < bytes.len() { bytes[i + 1] } else { 0 };
        let b2 = if i + 2 < bytes.len() { bytes[i + 2] } else { 0 };
        out.push(TABLE[(b0 >> 2) as usize] as char);
        out.push(TABLE[(((b0 & 0x03) << 4) | (b1 >> 4)) as usize] as char);
        if i + 1 < bytes.len() {
            out.push(TABLE[(((b1 & 0x0f) << 2) | (b2 >> 6)) as usize] as char);
        } else {
            out.push('=');
        }
        if i + 2 < bytes.len() {
            out.push(TABLE[(b2 & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
        i += 3;
    }
    out
}

pub(super) fn mask_proxy_for_log(value: &str) -> String {
    let Some((scheme, rest)) = value.split_once("://") else {
        return value.to_string();
    };
    let safe_rest = match rest.rsplit_once('@') {
        Some((_userinfo, hostport)) => format!("***@{}", hostport),
        None => rest.to_string(),
    };
    format!("{}://{}", scheme, safe_rest)
}

pub(super) fn append_log_line(path: &Path, line: &str) {
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "{}", line);
    }
}

