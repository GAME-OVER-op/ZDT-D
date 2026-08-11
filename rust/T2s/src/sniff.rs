#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SniffResult {
    /// Plain HTTP request with a Host header.
    HttpHost(String),
    /// HTTP CONNECT request (proxy-style) with host:port in request line.
    ConnectHost(String),
    /// TLS ClientHello with SNI.
    TlsSni(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SniffProgress {
    Found(SniffResult),
    /// The prefix is recognized, but more bytes are required to make a safe decision.
    NeedMoreData,
    /// The current bytes do not look like a supported sniffable protocol.
    NotRecognized,
    /// The prefix claims to be a supported protocol, but its framing is malformed.
    Invalid,
}

/// Compatibility helper for callers that only need a best-effort result.
pub fn sniff_host(buf: &[u8]) -> Option<SniffResult> {
    match sniff_host_progressive(buf) {
        SniffProgress::Found(result) => Some(result),
        SniffProgress::NeedMoreData | SniffProgress::NotRecognized | SniffProgress::Invalid => None,
    }
}

/// Progressive host extraction from the first bytes of a TCP stream.
///
/// Unlike the old Option-only parser, this function distinguishes an incomplete
/// but recognizable HTTP/TLS prefix from unsupported/invalid data. That lets the
/// caller wait briefly for the rest of a fragmented ClientHello without adding
/// delay to arbitrary non-HTTP/TLS traffic.
pub fn sniff_host_progressive(buf: &[u8]) -> SniffProgress {
    if buf.is_empty() {
        return SniffProgress::NeedMoreData;
    }

    match sniff_connect_progressive(buf) {
        SniffProgress::NotRecognized => {}
        other => return other,
    }

    match sniff_http_host_progressive(buf) {
        SniffProgress::NotRecognized => {}
        other => return other,
    }

    sniff_tls_sni_progressive(buf)
}

fn sniff_connect_progressive(buf: &[u8]) -> SniffProgress {
    const PREFIX: &[u8] = b"CONNECT ";
    if is_partial_prefix(buf, PREFIX) {
        return SniffProgress::NeedMoreData;
    }
    if !buf.starts_with(PREFIX) {
        return SniffProgress::NotRecognized;
    }

    let Some(line_end) = find_crlf(buf) else {
        return if buf.len() < 512 {
            SniffProgress::NeedMoreData
        } else {
            SniffProgress::Invalid
        };
    };
    let line = &buf[..line_end];
    if !is_http_ascii(line) {
        return SniffProgress::Invalid;
    }
    let Ok(line) = std::str::from_utf8(line) else {
        return SniffProgress::Invalid;
    };
    let target = line
        .strip_prefix("CONNECT ")
        .and_then(|rest| rest.split_whitespace().next());
    let Some(target) = target else {
        return SniffProgress::Invalid;
    };
    let Some(host) = normalize_authority_host(target) else {
        return SniffProgress::Invalid;
    };
    SniffProgress::Found(SniffResult::ConnectHost(host))
}

fn sniff_http_host_progressive(buf: &[u8]) -> SniffProgress {
    const METHODS: [&[u8]; 7] = [
        b"GET ",
        b"POST ",
        b"HEAD ",
        b"PUT ",
        b"DELETE ",
        b"OPTIONS ",
        b"PATCH ",
    ];

    if METHODS.iter().any(|prefix| is_partial_prefix(buf, prefix)) {
        return SniffProgress::NeedMoreData;
    }
    if !METHODS.iter().any(|prefix| buf.starts_with(prefix)) {
        return SniffProgress::NotRecognized;
    }

    // We do not need the entire HTTP header block once a complete Host line
    // is already present. Scan only CRLF-terminated lines so normal HTTP keeps
    // the old fast behavior while fragmented headers can continue.
    let complete_headers = find_double_crlf(buf).is_some();
    let scan_end = if let Some(pos) = find_double_crlf(buf) {
        pos
    } else if let Some(pos) = buf.windows(2).rposition(|w| w == b"\r\n") {
        pos
    } else {
        return if buf.len() < 16 * 1024 {
            SniffProgress::NeedMoreData
        } else {
            SniffProgress::Invalid
        };
    };
    let scan = &buf[..scan_end];
    if !scan.iter().all(|b| b.is_ascii() && (*b == b'\r' || *b == b'\n' || *b == b'\t' || !b.is_ascii_control())) {
        return SniffProgress::Invalid;
    }
    let Ok(text) = std::str::from_utf8(scan) else {
        return SniffProgress::Invalid;
    };

    for line in text.split("\r\n").skip(1) {
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        if !name.eq_ignore_ascii_case("host") {
            continue;
        }
        let Some(host) = normalize_authority_host(value.trim()) else {
            return SniffProgress::Invalid;
        };
        return SniffProgress::Found(SniffResult::HttpHost(host));
    }

    if complete_headers {
        SniffProgress::NotRecognized
    } else if buf.len() < 16 * 1024 {
        SniffProgress::NeedMoreData
    } else {
        SniffProgress::Invalid
    }
}

fn sniff_tls_sni_progressive(buf: &[u8]) -> SniffProgress {
    // TLS handshake messages may span multiple TLS records. Collect complete
    // handshake-record payloads from the current peek window until the complete
    // ClientHello is available; TCP or TLS-record fragmentation therefore does
    // not turn a valid modified stream into an immediate sniff failure.
    let mut pos = 0usize;
    let mut handshake = Vec::with_capacity(buf.len().min(16 * 1024));

    loop {
        if buf.len().saturating_sub(pos) < 1 {
            return SniffProgress::NeedMoreData;
        }
        if buf[pos] != 0x16 {
            return if pos == 0 {
                SniffProgress::NotRecognized
            } else {
                SniffProgress::Invalid
            };
        }
        if buf.len().saturating_sub(pos) < 3 {
            return SniffProgress::NeedMoreData;
        }
        if buf[pos + 1] != 0x03 {
            return if pos == 0 {
                SniffProgress::NotRecognized
            } else {
                SniffProgress::Invalid
            };
        }
        if buf.len().saturating_sub(pos) < 5 {
            return SniffProgress::NeedMoreData;
        }

        let rec_len = u16::from_be_bytes([buf[pos + 3], buf[pos + 4]]) as usize;
        if rec_len > 18 * 1024 {
            return SniffProgress::Invalid;
        }
        let record_end = pos.saturating_add(5).saturating_add(rec_len);
        if buf.len() < record_end {
            return SniffProgress::NeedMoreData;
        }
        handshake.extend_from_slice(&buf[pos + 5..record_end]);

        if handshake.len() >= 4 {
            if handshake[0] != 0x01 {
                return SniffProgress::NotRecognized;
            }
            let hs_len = ((handshake[1] as usize) << 16)
                | ((handshake[2] as usize) << 8)
                | handshake[3] as usize;
            let total = 4usize.saturating_add(hs_len);
            if handshake.len() >= total {
                return parse_client_hello_sni(&handshake[4..total]);
            }
        }

        pos = record_end;
        if pos >= buf.len() {
            return SniffProgress::NeedMoreData;
        }
    }
}

fn parse_client_hello_sni(body: &[u8]) -> SniffProgress {
    let mut i = 0usize;

    // client_version(2) + random(32)
    if i + 34 > body.len() {
        return SniffProgress::Invalid;
    }
    i += 34;

    // session id
    if i + 1 > body.len() {
        return SniffProgress::Invalid;
    }
    let sid_len = body[i] as usize;
    i += 1;
    if i + sid_len > body.len() {
        return SniffProgress::Invalid;
    }
    i += sid_len;

    // cipher suites
    if i + 2 > body.len() {
        return SniffProgress::Invalid;
    }
    let cs_len = u16::from_be_bytes([body[i], body[i + 1]]) as usize;
    i += 2;
    if i + cs_len > body.len() {
        return SniffProgress::Invalid;
    }
    i += cs_len;

    // compression methods
    if i + 1 > body.len() {
        return SniffProgress::Invalid;
    }
    let comp_len = body[i] as usize;
    i += 1;
    if i + comp_len > body.len() {
        return SniffProgress::Invalid;
    }
    i += comp_len;

    // Extensions are optional in old TLS ClientHello forms.
    if i == body.len() {
        return SniffProgress::NotRecognized;
    }
    if i + 2 > body.len() {
        return SniffProgress::Invalid;
    }
    let ext_len = u16::from_be_bytes([body[i], body[i + 1]]) as usize;
    i += 2;
    if i + ext_len > body.len() {
        return SniffProgress::Invalid;
    }
    let end = i + ext_len;

    while i + 4 <= end {
        let et = u16::from_be_bytes([body[i], body[i + 1]]);
        let el = u16::from_be_bytes([body[i + 2], body[i + 3]]) as usize;
        i += 4;
        if i + el > end {
            return SniffProgress::Invalid;
        }
        if et == 0x0000 {
            if el < 2 {
                return SniffProgress::Invalid;
            }
            let mut j = i;
            let list_len = u16::from_be_bytes([body[j], body[j + 1]]) as usize;
            j += 2;
            if j + list_len > i + el {
                return SniffProgress::Invalid;
            }
            let list_end = j + list_len;
            while j + 3 <= list_end {
                let name_type = body[j];
                let name_len = u16::from_be_bytes([body[j + 1], body[j + 2]]) as usize;
                j += 3;
                if j + name_len > list_end {
                    return SniffProgress::Invalid;
                }
                if name_type == 0x00 {
                    let name_bytes = &body[j..j + name_len];
                    let Ok(name) = std::str::from_utf8(name_bytes) else {
                        return SniffProgress::Invalid;
                    };
                    let host = name.trim().trim_end_matches('.').to_ascii_lowercase();
                    if host.is_empty() {
                        return SniffProgress::Invalid;
                    }
                    return SniffProgress::Found(SniffResult::TlsSni(host));
                }
                j += name_len;
            }
            return SniffProgress::NotRecognized;
        }
        i += el;
    }

    SniffProgress::NotRecognized
}

fn normalize_authority_host(authority: &str) -> Option<String> {
    let authority = authority.trim();
    if authority.is_empty() {
        return None;
    }

    let host = if let Some(rest) = authority.strip_prefix('[') {
        let end = rest.find(']')?;
        &rest[..end]
    } else if let Some((h, p)) = authority.rsplit_once(':') {
        if !h.contains(':') && !p.is_empty() && p.chars().all(|c| c.is_ascii_digit()) {
            h
        } else {
            authority
        }
    } else {
        authority
    };

    let host = host.trim().trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() {
        None
    } else {
        Some(host)
    }
}

fn is_partial_prefix(buf: &[u8], prefix: &[u8]) -> bool {
    buf.len() < prefix.len() && prefix.starts_with(buf)
}

fn find_crlf(buf: &[u8]) -> Option<usize> {
    buf.windows(2).position(|w| w == b"\r\n")
}

fn find_double_crlf(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

fn is_http_ascii(line: &[u8]) -> bool {
    !line.is_empty() && line.iter().all(|b| b.is_ascii_graphic() || *b == b' ' || *b == b'\t')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn partial_http_waits_for_more_data() {
        assert_eq!(sniff_host_progressive(b"GET / HTTP/1.1\r\nHo"), SniffProgress::NeedMoreData);
    }

    #[test]
    fn complete_http_extracts_host_case_insensitively() {
        assert_eq!(
            sniff_host_progressive(b"GET / HTTP/1.1\r\nhOsT: Example.COM:443\r\n\r\n"),
            SniffProgress::Found(SniffResult::HttpHost("example.com".into()))
        );
    }

    #[test]
    fn partial_tls_record_waits_for_more_data() {
        assert_eq!(
            sniff_host_progressive(&[0x16, 0x03, 0x01, 0x00, 0x20, 0x01, 0x00]),
            SniffProgress::NeedMoreData
        );
    }

    #[test]
    fn tls_client_hello_split_across_records_is_supported() {
        let host = b"video.example";
        let mut extensions = Vec::new();
        let list_len = 1 + 2 + host.len();
        let sni_len = 2 + list_len;
        extensions.extend_from_slice(&0u16.to_be_bytes());
        extensions.extend_from_slice(&(sni_len as u16).to_be_bytes());
        extensions.extend_from_slice(&(list_len as u16).to_be_bytes());
        extensions.push(0);
        extensions.extend_from_slice(&(host.len() as u16).to_be_bytes());
        extensions.extend_from_slice(host);

        let mut body = Vec::new();
        body.extend_from_slice(&[0x03, 0x03]);
        body.extend_from_slice(&[0u8; 32]);
        body.push(0);
        body.extend_from_slice(&2u16.to_be_bytes());
        body.extend_from_slice(&0x1301u16.to_be_bytes());
        body.push(1);
        body.push(0);
        body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
        body.extend_from_slice(&extensions);

        let mut handshake = Vec::new();
        handshake.push(1);
        let len = body.len() as u32;
        handshake.extend_from_slice(&[((len >> 16) & 0xff) as u8, ((len >> 8) & 0xff) as u8, (len & 0xff) as u8]);
        handshake.extend_from_slice(&body);

        let split = 20;
        let mut first = vec![0x16, 0x03, 0x01];
        first.extend_from_slice(&(split as u16).to_be_bytes());
        first.extend_from_slice(&handshake[..split]);
        assert_eq!(sniff_host_progressive(&first), SniffProgress::NeedMoreData);

        let rest = &handshake[split..];
        let mut both = first;
        both.extend_from_slice(&[0x16, 0x03, 0x01]);
        both.extend_from_slice(&(rest.len() as u16).to_be_bytes());
        both.extend_from_slice(rest);
        assert_eq!(
            sniff_host_progressive(&both),
            SniffProgress::Found(SniffResult::TlsSni("video.example".into()))
        );
    }

    #[test]
    fn arbitrary_binary_is_not_delayed() {
        assert_eq!(sniff_host_progressive(&[0x01, 0x02, 0x03, 0x04]), SniffProgress::NotRecognized);
    }
}
