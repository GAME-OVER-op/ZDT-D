use anyhow::Result;
use std::{collections::HashMap, io::Read, net::TcpStream, time::Duration};

const MAX_HEADER: usize = 16 * 1024;
// Allow uploading strategic files (including binaries). The API is local-only and authenticated,
// but we still cap body size to avoid accidental memory blowups.
const MAX_BODY: usize = 512 * 1024 * 1024;

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

pub(in crate::api) fn parse_http_request(
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

