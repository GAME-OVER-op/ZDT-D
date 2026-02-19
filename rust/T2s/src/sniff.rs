#[derive(Clone, Debug)]
pub enum SniffResult {
    /// Plain HTTP request with a Host header.
    HttpHost(String),
    /// HTTP CONNECT request (proxy-style) with host:port in request line.
    ConnectHost(String),
    /// TLS ClientHello with SNI.
    TlsSni(String),
}

/// Best-effort extraction of a destination host/domain from the first bytes of a TCP stream.
///
/// - HTTP: parses `Host:`
/// - CONNECT: parses `CONNECT host:port`
/// - TLS: parses ClientHello SNI
pub fn sniff_host(buf: &[u8]) -> Option<SniffResult> {
    if buf.is_empty() {
        return None;
    }

    if let Some(h) = sniff_connect(buf) {
        return Some(SniffResult::ConnectHost(h));
    }

    if let Some(h) = sniff_http_host(buf) {
        return Some(SniffResult::HttpHost(h));
    }

    if let Some(sni) = sniff_tls_sni(buf) {
        return Some(SniffResult::TlsSni(sni));
    }

    None
}

fn sniff_connect(buf: &[u8]) -> Option<String> {
    // CONNECT host:port HTTP/1.1\r\n...
    let line = first_line_ascii(buf)?;
    if !line.starts_with("CONNECT ") {
        return None;
    }
    let rest = line.trim_start_matches("CONNECT ");
    let target = rest.split_whitespace().next()?;
    let host = target.rsplit_once(':').map(|(h, _)| h).unwrap_or(target);
    let host = host.trim_matches('[').trim_matches(']');
    let host = host.trim().to_ascii_lowercase();
    if host.is_empty() {
        return None;
    }
    Some(host)
}

fn sniff_http_host(buf: &[u8]) -> Option<String> {
    // Very cheap check: starts with an uppercase method.
    let line = first_line_ascii(buf)?;
    if !(line.starts_with("GET ")
        || line.starts_with("POST ")
        || line.starts_with("HEAD ")
        || line.starts_with("PUT ")
        || line.starts_with("DELETE ")
        || line.starts_with("OPTIONS ")
        || line.starts_with("PATCH "))
    {
        return None;
    }

    // Parse headers up to the first blank line or a small cap.
    let text = std::str::from_utf8(buf).ok()?;
    let mut lines = text.split("\r\n");
    let _req = lines.next()?;
    for _ in 0..64 {
        let l = lines.next()?;
        if l.is_empty() {
            break;
        }
        if let Some(v) = l.strip_prefix("Host:") {
            let host = v.trim().to_ascii_lowercase();
            if host.is_empty() {
                return None;
            }
            // Strip optional port
            let host = match host.rsplit_once(':') {
                Some((h, p)) if p.chars().all(|c| c.is_ascii_digit()) => h.to_string(),
                _ => host,
            };
            return Some(host);
        }
    }
    None
}

fn first_line_ascii(buf: &[u8]) -> Option<String> {
    let end = buf.windows(2).position(|w| w == b"\r\n").unwrap_or_else(|| buf.len().min(512));
    let line = &buf[..end];
    if line.is_empty() {
        return None;
    }
    // Avoid allocating on non-ASCII
    if !line.iter().all(|b| b.is_ascii_graphic() || *b == b' ' || *b == b'\t') {
        return None;
    }
    Some(String::from_utf8_lossy(line).to_string())
}

fn sniff_tls_sni(buf: &[u8]) -> Option<String> {
    // TLS record header: type(1)=0x16, version(2)=0x03xx, len(2)
    if buf.len() < 5 {
        return None;
    }
    if buf[0] != 0x16 {
        return None;
    }
    if buf[1] != 0x03 {
        return None;
    }
    let rec_len = u16::from_be_bytes([buf[3], buf[4]]) as usize;
    if buf.len() < 5 + rec_len {
        // Not enough bytes yet.
        return None;
    }
    let mut i = 5;
    if i + 4 > buf.len() {
        return None;
    }
    // Handshake: msg_type(1)=1, len(3)
    if buf[i] != 0x01 {
        return None;
    }
    let hs_len = ((buf[i + 1] as usize) << 16) | ((buf[i + 2] as usize) << 8) | (buf[i + 3] as usize);
    i += 4;
    if i + hs_len > buf.len() {
        return None;
    }
    // client_version(2) + random(32)
    if i + 34 > buf.len() {
        return None;
    }
    i += 34;

    // session id
    if i + 1 > buf.len() {
        return None;
    }
    let sid_len = buf[i] as usize;
    i += 1 + sid_len;
    if i + 2 > buf.len() {
        return None;
    }

    // cipher suites
    let cs_len = u16::from_be_bytes([buf[i], buf[i + 1]]) as usize;
    i += 2 + cs_len;
    if i + 1 > buf.len() {
        return None;
    }

    // compression methods
    let comp_len = buf[i] as usize;
    i += 1 + comp_len;
    if i + 2 > buf.len() {
        return None;
    }

    // extensions
    let ext_len = u16::from_be_bytes([buf[i], buf[i + 1]]) as usize;
    i += 2;
    if i + ext_len > buf.len() {
        return None;
    }
    let end = i + ext_len;

    while i + 4 <= end {
        let et = u16::from_be_bytes([buf[i], buf[i + 1]]);
        let el = u16::from_be_bytes([buf[i + 2], buf[i + 3]]) as usize;
        i += 4;
        if i + el > end {
            break;
        }
        if et == 0x0000 {
            // server_name
            if el < 2 {
                return None;
            }
            let mut j = i;
            let list_len = u16::from_be_bytes([buf[j], buf[j + 1]]) as usize;
            j += 2;
            let list_end = (j + list_len).min(i + el);
            while j + 3 <= list_end {
                let name_type = buf[j];
                let name_len = u16::from_be_bytes([buf[j + 1], buf[j + 2]]) as usize;
                j += 3;
                if j + name_len > list_end {
                    break;
                }
                if name_type == 0x00 {
                    let name_bytes = &buf[j..j + name_len];
                    if let Ok(name) = std::str::from_utf8(name_bytes) {
                        let host = name.trim().trim_end_matches('.').to_ascii_lowercase();
                        if !host.is_empty() {
                            return Some(host);
                        }
                    }
                }
                j += name_len;
            }
        }
        i += el;
    }

    None
}
