use anyhow::Result;
use std::collections::HashMap;

use super::paths::{decode_url_component, ensure_safe_filename};

#[derive(Debug, Clone)]
pub(in crate::api) struct MultipartFile {
    pub(in crate::api) filename: String,
    pub(in crate::api) data: Vec<u8>,
}

fn extract_multipart_filename(content_disposition: &str) -> Result<Option<String>> {
    // OkHttp sends filename="...". Parse the quoted value directly instead of
    // splitting on ';', because a legal display name may itself contain ';'.
    if let Some(start) = content_disposition.find("filename=\"") {
        let rest = &content_disposition[start + "filename=\"".len()..];
        let mut escaped = false;
        let mut out = String::new();
        for ch in rest.chars() {
            if escaped {
                out.push(ch);
                escaped = false;
                continue;
            }
            if ch == '\\' {
                escaped = true;
                continue;
            }
            if ch == '"' {
                return Ok((!out.is_empty()).then_some(out));
            }
            out.push(ch);
        }
        anyhow::bail!("unterminated multipart filename");
    }

    // RFC 5987 fallback: filename*=UTF-8''percent-encoded-name
    if let Some(part) = content_disposition
        .split(';')
        .map(str::trim)
        .find(|p| p.starts_with("filename*="))
    {
        let raw = part.trim_start_matches("filename*=").trim_matches('"');
        let encoded = raw.split_once("''").map(|(_, value)| value).unwrap_or(raw);
        let decoded = decode_url_component(encoded)?;
        return Ok((!decoded.is_empty()).then_some(decoded));
    }

    // Legacy unquoted filename=value.
    let value = content_disposition
        .split(';')
        .map(str::trim)
        .find_map(|p| p.strip_prefix("filename="))
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_string);
    Ok(value)
}

pub(in crate::api) fn parse_multipart_file(headers: &HashMap<String, String>, body: &[u8]) -> Result<MultipartFile> {
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
        let filename_opt = extract_multipart_filename(&cd)?;

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
