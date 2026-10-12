struct ProbeResult {
    status: String,
    detail: String,
    bytes: usize,
    elapsed_ms: u128,
    rtt_ms: Option<u128>,
    break_kb: Option<usize>,
    size_label: String,
    checks: Vec<Value>,
}

async fn tcp_connect_probe(target: &TcpTarget, read_timeout: Duration, proxy: Option<&str>) -> ProbeResult {
    let start = Instant::now();
    let port = target.port();
    let result: Result<StatusCode> = async {
        if port == 80 {
            let client = http_client(read_timeout, false, proxy);
            let url = format!("http://{}:{}/", target.ip, port);
            let resp = client.head(url).header("Host", target.host_sni()).send().await?;
            Ok(resp.status())
        } else {
            let host = target.host_sni();
            let ip_addr = IpAddr::from_str(&target.ip)?;
            let mut builder = Client::builder()
                .timeout(read_timeout)
                .connect_timeout(Duration::from_millis(CONNECT_TIMEOUT_MS))
                .redirect(reqwest::redirect::Policy::none())
                .danger_accept_invalid_certs(true)
                .user_agent(USER_AGENT)
                .resolve(&host, SocketAddr::new(ip_addr, port))
                .use_rustls_tls();
            if let Some(proxy_url) = proxy.filter(|p| !p.trim().is_empty()) {
                if let Ok(px) = reqwest::Proxy::all(proxy_url) { builder = builder.proxy(px); }
            }
            let client = builder.build()?;
            let url = format!("https://{host}/");
            let resp = client.head(url).send().await?;
            Ok(resp.status())
        }
    }.await;
    let elapsed_ms = start.elapsed().as_millis();
    match result {
        Ok(status) => ProbeResult { status: "ok".to_string(), detail: format!("HTTP response {status}"), bytes: 0, elapsed_ms, rtt_ms: Some(elapsed_ms), break_kb: None, size_label: "TLS connect".to_string(), checks: Vec::new() },
        Err(e) => {
            let detail = e.to_string();
            let status = classify_error(&detail).to_string();
            ProbeResult { status, detail, bytes: 0, elapsed_ms, rtt_ms: None, break_kb: None, size_label: "TLS connect".to_string(), checks: Vec::new() }
        }
    }
}

async fn tcp_payload_probe_staged<W: EventWriter + Send>(
    target: &TcpTarget,
    read_timeout: Duration,
    proxy: Option<&str>,
    mut writer_ctx: Option<(&mut W, &str, &str)>,
) -> ProbeResult {
    let start_all = Instant::now();
    let target_addr = format!("{}:{}", target.ip, target.port());
    let stage_size_label = format!("{}-{} KB", TCP_BLOCK_MIN_KB, TCP_PAYLOAD_STEPS_KB.iter().copied().filter(|kb| *kb <= TCP_BLOCK_MAX_KB).max().unwrap_or(TCP_BLOCK_MAX_KB));
    let mut checks: Vec<Value> = Vec::new();

    let rtt_probe = tcp_connect_probe(target, Duration::from_millis(CONNECT_TIMEOUT_MS), proxy).await;
    let rtt_ms = rtt_probe.rtt_ms;
    checks.push(json!({
        "name": "Baseline",
        "status": rtt_probe.status.clone(),
        "detail": rtt_probe.detail.clone(),
        "value": rtt_ms.map(|r| format!("{r} ms")).unwrap_or_default(),
        "size_label": "connect"
    }));

    if rtt_probe.status != "ok" {
        return ProbeResult {
            status: rtt_probe.status,
            detail: format!("baseline connection failed: {}", checks.last().and_then(|v| v.get("detail")).and_then(Value::as_str).unwrap_or("unknown")),
            bytes: 0,
            elapsed_ms: start_all.elapsed().as_millis(),
            rtt_ms,
            break_kb: None,
            size_label: stage_size_label,
            checks,
        };
    }

    let adaptive_timeout = rtt_ms
        .map(|r| Duration::from_millis(((r as u64).saturating_mul(3)).max(1500)).max(read_timeout))
        .unwrap_or(read_timeout);

    let mut last_ok_kb = 0usize;
    for kb in TCP_PAYLOAD_STEPS_KB.iter().copied().filter(|kb| *kb <= TCP_BLOCK_MAX_KB) {
        if let Some((writer, stage, key)) = writer_ctx.as_mut() {
            let mut running_checks = checks.clone();
            running_checks.push(json!({
                "name": format!("{kb} KB"),
                "status": "checking",
                "detail": "sending X-Pad payload",
                "value": "",
                "size_label": format!("{kb} KB")
            }));
            let _ = (*writer).probe(
                *stage,
                *key,
                "TCP payload threshold",
                &target_addr,
                "checking",
                &format!("sending {kb} KB X-Pad payload"),
                json!({
                    "target": target.id,
                    "provider": target.provider,
                    "kb": kb,
                    "rtt_ms": rtt_ms,
                    "size_label": stage_size_label.clone(),
                    "checks": running_checks
                }),
            );
        }

        let step = tcp_payload_step(target, kb, adaptive_timeout, proxy).await;
        let check_status = if step.status == "ok" { "ok" } else if kb >= TCP_BLOCK_MIN_KB { "tcp16" } else { step.status.as_str() };
        checks.push(json!({
            "name": format!("{kb} KB"),
            "status": check_status,
            "detail": step.detail.clone(),
            "value": format!("{} ms", step.elapsed_ms),
            "size_label": format!("{kb} KB")
        }));

        if step.status == "ok" {
            last_ok_kb = kb;
            continue;
        }
        let detected = kb >= TCP_BLOCK_MIN_KB && kb <= TCP_BLOCK_MAX_KB;
        return ProbeResult {
            status: if detected { "tcp16".to_string() } else { step.status },
            detail: if detected { format!("possible TCP threshold block at {kb} KB after last OK {last_ok_kb} KB: {}", checks.last().and_then(|v| v.get("detail")).and_then(Value::as_str).unwrap_or("failed")) } else { checks.last().and_then(|v| v.get("detail")).and_then(Value::as_str).unwrap_or("failed").to_string() },
            bytes: kb * 1024,
            elapsed_ms: start_all.elapsed().as_millis(),
            rtt_ms,
            break_kb: Some(kb),
            size_label: stage_size_label,
            checks,
        };
    }

    ProbeResult {
        status: "ok".to_string(),
        detail: format!("all payload steps passed up to {} KB", TCP_PAYLOAD_STEPS_KB.iter().copied().max().unwrap_or(TCP_PAYLOAD_KB)),
        bytes: last_ok_kb * 1024,
        elapsed_ms: start_all.elapsed().as_millis(),
        rtt_ms,
        break_kb: None,
        size_label: stage_size_label,
        checks,
    }
}

async fn tcp_payload_step(target: &TcpTarget, kb: usize, read_timeout: Duration, proxy: Option<&str>) -> ProbeResult {
    let start = Instant::now();
    let port = target.port();
    let pad = random_payload(kb * 1024);
    let result: Result<StatusCode> = async {
        if port == 80 {
            let client = http_client(read_timeout, false, proxy);
            let url = format!("http://{}:{}/", target.ip, port);
            let resp = client.get(url).header("Host", target.host_sni()).header("X-Pad", pad).send().await?;
            Ok(resp.status())
        } else {
            let host = target.host_sni();
            let ip_addr = IpAddr::from_str(&target.ip)?;
            let mut builder = Client::builder()
                .timeout(read_timeout)
                .connect_timeout(Duration::from_millis(CONNECT_TIMEOUT_MS))
                .redirect(reqwest::redirect::Policy::none())
                .danger_accept_invalid_certs(true)
                .user_agent(USER_AGENT)
                .resolve(&host, SocketAddr::new(ip_addr, port))
                .use_rustls_tls();
            if let Some(proxy_url) = proxy.filter(|p| !p.trim().is_empty()) {
                if let Ok(px) = reqwest::Proxy::all(proxy_url) { builder = builder.proxy(px); }
            }
            let client = builder.build()?;
            let url = format!("https://{host}/");
            let resp = client.get(url).header("X-Pad", pad).send().await?;
            Ok(resp.status())
        }
    }.await;
    let elapsed_ms = start.elapsed().as_millis();
    match result {
        Ok(status) => ProbeResult { status: "ok".to_string(), detail: format!("HTTP response {status}"), bytes: kb * 1024, elapsed_ms, rtt_ms: Some(elapsed_ms), break_kb: None, size_label: format!("{kb} KB"), checks: Vec::new() },
        Err(e) => {
            let detail = e.to_string();
            let status = classify_error(&detail).to_string();
            ProbeResult { status, detail, bytes: kb * 1024, elapsed_ms, rtt_ms: None, break_kb: Some(kb), size_label: format!("{kb} KB"), checks: Vec::new() }
        }
    }
}

fn options_proxy_none() -> Option<&'static str> { None }

fn http_client(timeout_d: Duration, danger: bool, proxy: Option<&str>) -> Client {
    let mut builder = Client::builder()
        .timeout(timeout_d)
        .connect_timeout(Duration::from_millis(CONNECT_TIMEOUT_MS))
        .redirect(reqwest::redirect::Policy::none())
        .user_agent(USER_AGENT)
        .use_rustls_tls();
    if danger { builder = builder.danger_accept_invalid_certs(true); }
    if let Some(proxy_url) = proxy.filter(|p| !p.trim().is_empty()) {
        if let Ok(px) = reqwest::Proxy::all(proxy_url) { builder = builder.proxy(px); }
    }
    builder.build().expect("reqwest client")
}

async fn tls_http_probe(domain: &str, version: reqwest::tls::Version, timeout_d: Duration, proxy: Option<&str>) -> String {
    let mut builder = Client::builder()
        .timeout(timeout_d)
        .connect_timeout(Duration::from_millis(CONNECT_TIMEOUT_MS))
        .redirect(reqwest::redirect::Policy::none())
        .min_tls_version(version)
        .max_tls_version(version)
        .danger_accept_invalid_certs(true)
        .user_agent(USER_AGENT)
        .use_rustls_tls();
    if let Some(proxy_url) = proxy.filter(|p| !p.trim().is_empty()) {
        if let Ok(px) = reqwest::Proxy::all(proxy_url) { builder = builder.proxy(px); }
    }
    let Ok(client) = builder.build() else { return "client_error".to_string(); };
    http_status_probe(&client, &format!("https://{domain}/"), timeout_d).await
}

async fn http_status_probe(client: &Client, url: &str, timeout_d: Duration) -> String {
    match timeout(timeout_d, client.head(url).send()).await {
        Ok(Ok(resp)) => classify_http_response(url, resp.status(), resp.headers().get(reqwest::header::LOCATION).and_then(|v| v.to_str().ok())),
        Ok(Err(e)) => classify_error(&e.to_string()).to_string(),
        Err(_) => "timeout".to_string(),
    }
}

async fn resolve_system(domain: &str, timeout_d: Duration) -> String {
    match timeout(timeout_d, lookup_host((domain, 443))).await {
        Ok(Ok(mut addrs)) => match addrs.next() {
            Some(addr) => addr.ip().to_string(),
            None => "empty".to_string(),
        },
        Ok(Err(e)) => classify_error(&e.to_string()).to_string(),
        Err(_) => "timeout".to_string(),
    }
}

fn classify_domain(dns: &str, tls12: &str, tls13: &str, http: &str, https: &str) -> &'static str {
    let dns_bad = matches_blocked_signal(dns) || matches_suspicious_signal(dns);
    let tls_bad = [tls12, tls13].iter().any(|s| matches_blocked_signal(s) || matches_suspicious_signal(s));
    let https_bad = matches_blocked_signal(https) || matches_suspicious_signal(https);
    let http_bad = matches_blocked_signal(http) || matches_suspicious_signal(http);
    let tls_ok = is_http_okish(tls12) || is_http_okish(tls13);
    let https_ok = is_http_okish(https);
    let http_ok = is_http_okish(http);

    if dns_bad {
        "blocked"
    } else if http.starts_with("http_451") || https.starts_with("http_451") {
        "blocked"
    } else if tls_bad || https_bad || http_bad {
        "suspicious"
    } else if (tls_ok || https_ok) && (http_ok || https_ok) {
        "ok"
    } else {
        "suspicious"
    }
}

fn compact(s: &str) -> &str { s }

fn classify_http_response(request_url: &str, status: StatusCode, location: Option<&str>) -> String {
    if status.as_u16() == 451 { return "http_451_blocked".to_string(); }
    if status.is_redirection() {
        if let Some(location) = location {
            if is_same_domain_redirect(request_url, location) {
                return format!("redirect_ok:{}", status.as_u16());
            }
            return format!("redirect_suspicious:{}", status.as_u16());
        }
        return format!("redirect_ok:{}", status.as_u16());
    }
    format!("http_{}", status.as_u16())
}

fn is_same_domain_redirect(request_url: &str, location: &str) -> bool {
    let req_host = host_from_url(request_url).unwrap_or_default();
    let loc = if location.starts_with("http://") || location.starts_with("https://") {
        location.to_string()
    } else if location.starts_with("//") {
        format!("https:{location}")
    } else if location.starts_with('/') {
        return true;
    } else {
        format!("https://{location}")
    };
    let loc_host = host_from_url(&loc).unwrap_or_default();
    let req = req_host.trim_start_matches("www.");
    let loc = loc_host.trim_start_matches("www.");
    !req.is_empty() && (loc == req || loc.ends_with(&format!(".{req}")))
}

fn host_from_url(url: &str) -> Option<String> {
    let after_scheme = url.split_once("://").map(|(_, rest)| rest).unwrap_or(url);
    let host_port = after_scheme.split('/').next().unwrap_or("");
    let host = host_port.split('@').last().unwrap_or(host_port).split(':').next().unwrap_or("");
    if host.is_empty() { None } else { Some(host.to_lowercase()) }
}

fn classify_probe_status(value: &str) -> &'static str {
    if is_http_okish(value) { "available" }
    else if matches_blocked_signal(value) { "blocked" }
    else if matches_suspicious_signal(value) { "suspicious" }
    else { "suspicious" }
}

fn format_probe_detail(value: &str) -> String {
    match value {
        v if v.starts_with("http_451") => "HTTP 451 — explicit blocking response".to_string(),
        v if v.starts_with("redirect_suspicious") => format!("suspicious redirect ({v})"),
        v if v.starts_with("redirect_ok") => format!("normal redirect ({v})"),
        v if v.starts_with("http_") => format!("HTTP response {}", v.trim_start_matches("http_")),
        "tls_alert" => "TLS alert, possible SNI/TLS filtering".to_string(),
        "tls_rst" => "TLS handshake reset".to_string(),
        "tcp_rst" | "reset" => "TCP reset / connection abort".to_string(),
        "timeout" => "timeout".to_string(),
        "refused" => "connection refused".to_string(),
        "unreachable" => "network or host unreachable".to_string(),
        other => other.to_string(),
    }
}

fn is_http_okish(value: &str) -> bool {
    value.starts_with("http_2") || value.starts_with("http_3") || value.starts_with("http_4") || value.starts_with("redirect_ok")
}

fn matches_blocked_signal(value: &str) -> bool {
    matches!(value, "timeout" | "tls_rst" | "tcp_rst" | "reset" | "refused" | "unreachable" | "dns_fail")
        || value.starts_with("http_451")
}

fn matches_suspicious_signal(value: &str) -> bool {
    matches!(value, "blocked" | "tls_alert" | "tls_spoof" | "tls_mitm" | "tls_eof" | "protocol_version" | "read_timeout" | "pool_timeout")
        || value.starts_with("redirect_suspicious")
}

fn classify_error(detail: &str) -> &'static str {
    let d = detail.to_lowercase();
    if d.contains("http 451") || d.contains("status 451") { "http_451_blocked" }
    else if d.contains("unrecognized_name") || d.contains("handshake failure") || d.contains("tls alert") || d.contains("alert") { "tls_alert" }
    else if d.contains("wrong version number") || d.contains("record overflow") || d.contains("decode error") || d.contains("illegal parameter") { "tls_spoof" }
    else if d.contains("certificate") || d.contains("unknown ca") || d.contains("self-signed") || d.contains("hostname mismatch") { "tls_mitm" }
    else if d.contains("timed out") || d.contains("timeout") || d.contains("deadline") { "timeout" }
    else if d.contains("pool timeout") { "pool_timeout" }
    else if d.contains("connection reset") || d.contains("reset by peer") || d.contains("broken pipe") { "tcp_rst" }
    else if d.contains("eof") || d.contains("operation did not complete") { "tls_rst" }
    else if d.contains("refused") { "refused" }
    else if d.contains("unreachable") || d.contains("network is unreachable") || d.contains("host unreachable") { "unreachable" }
    else { "blocked" }
}

fn random_payload(len: usize) -> String {
    rand::thread_rng().sample_iter(&Alphanumeric).take(len).map(char::from).collect()
}

macro_rules! probe_many_domains {
    ($resolver:path, $server:expr, $domains:expr, $timeout_d:expr) => {{
        let mut count = 0;
        for domain in $domains {
            if $resolver($server, domain, $timeout_d).await.is_ok() { count += 1; }
        }
        count
    }};
}

async fn probe_dns_udp_many(server: &str, domains: &[&str], timeout_d: Duration) -> usize {
    probe_many_domains!(resolve_udp, server, domains, timeout_d)
}

async fn probe_doh_wire_many(server: &str, domains: &[&str], timeout_d: Duration) -> usize {
    probe_many_domains!(resolve_doh_wire, server, domains, timeout_d)
}

async fn probe_doh_json_many(server: &str, domains: &[&str], timeout_d: Duration) -> usize {
    probe_many_domains!(resolve_doh_json, server, domains, timeout_d)
}

async fn resolve_udp(server: &str, domain: &str, timeout_d: Duration) -> Result<Vec<String>> {
    let query = build_dns_query(domain)?;
    let tx_id = [query[0], query[1]];
    let socket = UdpSocket::bind("0.0.0.0:0").await?;
    socket.connect((server, 53)).await?;
    socket.send(&query).await?;
    let mut buf = vec![0u8; 2048];
    let n = timeout(timeout_d, socket.recv(&mut buf)).await??;
    parse_dns_response(&buf[..n], tx_id)
}

async fn resolve_doh_wire(server: &str, domain: &str, timeout_d: Duration) -> Result<Vec<String>> {
    let query = build_dns_query(domain)?;
    let tx_id = [query[0], query[1]];
    let client = http_client(timeout_d, true, options_proxy_none());
    let post = client
        .post(server)
        .header("Content-Type", "application/dns-message")
        .header("Accept", "application/dns-message")
        .body(query.clone())
        .send()
        .await;
    let bytes = match post {
        Ok(resp) if resp.status().is_success() => resp.bytes().await?.to_vec(),
        _ => {
            let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&query);
            let url = format!("{server}?dns={encoded}");
            let resp = client.get(url).header("Accept", "application/dns-message").send().await?;
            if !resp.status().is_success() { return Err(anyhow!("DoH status {}", resp.status())); }
            resp.bytes().await?.to_vec()
        }
    };
    parse_dns_response(&bytes, tx_id)
}

async fn resolve_doh_json(server: &str, domain: &str, timeout_d: Duration) -> Result<Vec<String>> {
    let client = http_client(timeout_d, true, options_proxy_none());
    let resp = client.get(server).query(&[("name", domain), ("type", "A")]).header("Accept", "application/dns-json").send().await?;
    if !resp.status().is_success() { return Err(anyhow!("DoH JSON status {}", resp.status())); }
    let data: Value = resp.json().await?;
    if data.get("Status").and_then(Value::as_i64) == Some(3) { return Err(anyhow!("NXDOMAIN")); }
    let ips = data
        .get("Answer")
        .and_then(Value::as_array)
        .map(|answers| {
            answers
                .iter()
                .filter(|item| item.get("type").and_then(Value::as_i64) == Some(1))
                .filter_map(|item| item.get("data").and_then(Value::as_str).map(str::to_string))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    if ips.is_empty() { Err(anyhow!("empty answer")) } else { Ok(ips) }
}

fn build_dns_query(domain: &str) -> Result<Vec<u8>> {
    let tx_id: u16 = rand::random();
    let mut q = Vec::with_capacity(512);
    q.extend_from_slice(&tx_id.to_be_bytes());
    q.extend_from_slice(&0x0100u16.to_be_bytes());
    q.extend_from_slice(&1u16.to_be_bytes());
    q.extend_from_slice(&0u16.to_be_bytes());
    q.extend_from_slice(&0u16.to_be_bytes());
    q.extend_from_slice(&0u16.to_be_bytes());
    for part in domain.split('.') {
        if part.len() > 63 { return Err(anyhow!("domain label too long")); }
        q.push(part.len() as u8);
        q.extend_from_slice(part.as_bytes());
    }
    q.push(0);
    q.extend_from_slice(&1u16.to_be_bytes());
    q.extend_from_slice(&1u16.to_be_bytes());
    Ok(q)
}

fn parse_dns_response(data: &[u8], tx_id: [u8; 2]) -> Result<Vec<String>> {
    if data.len() < 12 || data[0] != tx_id[0] || data[1] != tx_id[1] { return Err(anyhow!("invalid DNS response")); }
    let flags = u16::from_be_bytes([data[2], data[3]]);
    let rcode = flags & 0x000f;
    if rcode == 3 { return Err(anyhow!("NXDOMAIN")); }
    if rcode != 0 { return Err(anyhow!("DNS rcode {rcode}")); }
    let ancount = u16::from_be_bytes([data[6], data[7]]) as usize;
    let mut offset = 12usize;
    skip_name(data, &mut offset)?;
    offset += 4;
    let mut ips = Vec::new();
    for _ in 0..ancount {
        skip_name(data, &mut offset)?;
        if offset + 10 > data.len() { break; }
        let rtype = u16::from_be_bytes([data[offset], data[offset + 1]]);
        let rdlen = u16::from_be_bytes([data[offset + 8], data[offset + 9]]) as usize;
        offset += 10;
        if offset + rdlen > data.len() { break; }
        if rtype == 1 && rdlen == 4 {
            ips.push(Ipv4Addr::new(data[offset], data[offset + 1], data[offset + 2], data[offset + 3]).to_string());
        }
        offset += rdlen;
    }
    if ips.is_empty() { Err(anyhow!("empty DNS answer")) } else { Ok(ips) }
}

fn skip_name(data: &[u8], offset: &mut usize) -> Result<()> {
    let mut jumps = 0;
    loop {
        if *offset >= data.len() { return Err(anyhow!("DNS name out of range")); }
        let len = data[*offset];
        if len == 0 { *offset += 1; return Ok(()); }
        if len & 0xc0 == 0xc0 {
            if *offset + 1 >= data.len() { return Err(anyhow!("DNS pointer out of range")); }
            *offset += 2;
            return Ok(());
        }
        *offset += len as usize + 1;
        jumps += 1;
        if jumps > 128 { return Err(anyhow!("DNS name loop")); }
    }
}

fn answers_overlap(a: &[String], b: &[String]) -> bool {
    let set: HashSet<&String> = a.iter().collect();
    b.iter().any(|ip| set.contains(ip))
}

fn fake_ip_type(ip: &str) -> Option<&'static str> {
    let addr = Ipv4Addr::from_str(ip).ok()?;
    let o = addr.octets();
    if o[0] == 198 && (o[1] == 18 || o[1] == 19) { Some("fakeip") }
    else if o[0] == 100 && (64..=127).contains(&o[1]) { Some("isp") }
    else if o[0] == 10 || (o[0] == 172 && (16..=31).contains(&o[1])) || (o[0] == 192 && o[1] == 168) || o[0] == 127 || o[0] == 0 || (o[0] == 169 && o[1] == 254) { Some("local") }
    else { None }
}

fn is_ip_address(value: &str) -> bool {
    IpAddr::from_str(value).is_ok()
}

fn clean_hostname(value: &str) -> String {
    value
        .trim()
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_end_matches('/')
        .split('/')
        .next()
        .unwrap_or("")
        .trim()
        .to_string()
}

fn load_lines(src: &str, limit: usize) -> Vec<String> {
    src.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .take(limit)
        .map(str::to_string)
        .collect()
}

fn load_tcp_targets(limit: usize) -> Vec<TcpTarget> {
    serde_json::from_str::<Vec<TcpTarget>>(TCP16_JSON)
        .unwrap_or_default()
        .into_iter()
        .filter(|t| !t.ip.trim().is_empty())
        .take(limit)
        .collect()
}

fn fmt_size(bytes: u64) -> String {
    let units = ["B", "KB", "MB", "GB"];
    let mut v = bytes as f64;
    let mut idx = 0usize;
    while v >= 1024.0 && idx + 1 < units.len() {
        v /= 1024.0;
        idx += 1;
    }
    if idx == 0 { format!("{}{}", bytes, units[idx]) } else { format!("{v:.1}{}", units[idx]) }
}

fn print_help() {
    println!("dpi-detector {VERSION}");
    println!("Native ZDT-D DPI diagnostics helper");
    println!();
    println!("Usage:");
    println!("  dpi-detector --version");
    println!("  dpi-detector self-test");
    println!("  dpi-detector list-tests");
    println!("  dpi-detector run [--format text|ndjson] [--tests list] [--timeout ms] [--quick]");
    println!("                   [--domain example.com] [--proxy socks5://127.0.0.1:1080]");
    println!("                   [--concurrency n] [--max-domains n] [--max-tcp-targets n] [--max-sni n]");
    println!();
    println!("Tests:");
    println!("  dns_integrity,dns_availability,domains,tcp16,whitelist_sni,telegram");
}
