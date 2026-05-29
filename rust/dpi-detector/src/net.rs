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

#[derive(Debug, Clone)]
struct DnsSelection {
    udp: Option<(&'static str, &'static str)>,
    doh_json: Option<(&'static str, &'static str)>,
    doh_wire: Option<(&'static str, &'static str)>,
    logs: Vec<String>,
}

#[derive(Debug, Clone)]
enum DnsAnswer {
    Ips(Vec<String>),
    Nxdomain,
    Empty,
    Timeout,
    Blocked,
    Unavailable,
}

impl DnsAnswer {
    fn label(&self) -> &'static str {
        match self {
            Self::Ips(_) => "ok",
            Self::Nxdomain => "nxdomain",
            Self::Empty => "empty",
            Self::Timeout => "timeout",
            Self::Blocked => "blocked",
            Self::Unavailable => "unavailable",
        }
    }

    fn as_ips(&self) -> Option<&[String]> {
        match self {
            Self::Ips(ips) => Some(ips),
            _ => None,
        }
    }

    fn short(&self) -> String {
        match self {
            Self::Ips(ips) => ips.iter().take(3).cloned().collect::<Vec<_>>().join(", "),
            Self::Nxdomain => "NXDOMAIN".to_string(),
            Self::Empty => "EMPTY".to_string(),
            Self::Timeout => "TIMEOUT".to_string(),
            Self::Blocked => "BLOCKED".to_string(),
            Self::Unavailable => "UNAVAILABLE".to_string(),
        }
    }
}

async fn tcp_connect_probe(target: &TcpTarget, read_timeout: Duration, proxy: Option<&str>) -> ProbeResult {
    let start = Instant::now();
    let port = target.port();
    let result: Result<StatusCode> = async {
        let client = http_client_for_target(target, read_timeout, proxy, 1, 1)?;
        let request = build_target_request(&client, target, None, true)?;
        let resp = request.send().await?;
        Ok(resp.status())
    }
    .await;
    let elapsed_ms = start.elapsed().as_millis();
    match result {
        Ok(status) => ProbeResult {
            status: "ok".to_string(),
            detail: format!("HTTP response {status}"),
            bytes: 0,
            elapsed_ms,
            rtt_ms: Some(elapsed_ms),
            break_kb: None,
            size_label: if port == 80 { "HTTP HEAD".to_string() } else { "TLS connect".to_string() },
            checks: Vec::new(),
        },
        Err(e) => {
            let detail = e.to_string();
            let status = classify_error(&detail).to_string();
            ProbeResult {
                status,
                detail,
                bytes: 0,
                elapsed_ms,
                rtt_ms: None,
                break_kb: None,
                size_label: if port == 80 { "HTTP HEAD".to_string() } else { "TLS connect".to_string() },
                checks: Vec::new(),
            }
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
    let stage_size_label = format!(
        "{}-{} KB",
        TCP_BLOCK_MIN_KB,
        TCP_PAYLOAD_STEPS_KB
            .iter()
            .copied()
            .filter(|kb| *kb <= TCP_BLOCK_MAX_KB)
            .max()
            .unwrap_or(TCP_BLOCK_MAX_KB)
    );
    let mut checks: Vec<Value> = Vec::new();

    let client = match http_client_for_target(target, read_timeout, proxy, 1, 1) {
        Ok(client) => client,
        Err(e) => {
            let detail = e.to_string();
            return ProbeResult {
                status: classify_error(&detail).to_string(),
                detail,
                bytes: 0,
                elapsed_ms: start_all.elapsed().as_millis(),
                rtt_ms: None,
                break_kb: None,
                size_label: stage_size_label,
                checks,
            };
        }
    };

    let baseline = target_request_step(&client, target, None, Duration::from_millis(CONNECT_TIMEOUT_MS)).await;
    let mut rtt_samples = Vec::new();
    if baseline.status == "ok" {
        rtt_samples.push(baseline.elapsed_ms);
    }
    checks.push(json!({
        "name": "Baseline",
        "status": if baseline.status == "ok" { "available" } else { classify_probe_status(&baseline.status) },
        "detail": baseline.detail.clone(),
        "value": baseline.rtt_ms.map(|r| format!("{r} ms")).unwrap_or_default(),
        "size_label": "connect"
    }));

    if baseline.status != "ok" {
        return ProbeResult {
            status: baseline.status,
            detail: format!(
                "baseline connection failed: {}",
                checks
                    .last()
                    .and_then(|v| v.get("detail"))
                    .and_then(Value::as_str)
                    .unwrap_or("unknown")
            ),
            bytes: 0,
            elapsed_ms: start_all.elapsed().as_millis(),
            rtt_ms: baseline.rtt_ms,
            break_kb: None,
            size_label: stage_size_label,
            checks,
        };
    }

    let second = target_request_step(&client, target, Some(4), Duration::from_millis(CONNECT_TIMEOUT_MS)).await;
    if second.status == "ok" {
        rtt_samples.push(second.elapsed_ms);
    }
    let dynamic_timeout_ms = rtt_samples
        .iter()
        .copied()
        .max()
        .map(|r| ((r as u64).saturating_mul(3)).max(1500).min(read_timeout.as_millis() as u64))
        .unwrap_or(read_timeout.as_millis() as u64)
        .max(1200);
    checks.push(json!({
        "name": "4 KB",
        "status": if second.status == "ok" { "available" } else if 4 >= TCP_BLOCK_MIN_KB { "tcp16" } else { classify_probe_status(&second.status) },
        "detail": second.detail.clone(),
        "value": format!("{} ms", second.elapsed_ms),
        "size_label": "4 KB"
    }));

    if second.status != "ok" {
        let detected = 4 >= TCP_BLOCK_MIN_KB && 4 <= TCP_BLOCK_MAX_KB;
        return ProbeResult {
            status: if detected { "tcp16".to_string() } else { second.status },
            detail: if detected {
                format!("possible TCP threshold block at 4 KB: {}", second.detail)
            } else {
                second.detail
            },
            bytes: 4 * 1024,
            elapsed_ms: start_all.elapsed().as_millis(),
            rtt_ms: baseline.rtt_ms,
            break_kb: Some(4),
            size_label: stage_size_label,
            checks,
        };
    }

    let mut last_ok_kb = 4usize;
    for kb in TCP_PAYLOAD_STEPS_KB.iter().copied().filter(|kb| *kb > 4 && *kb <= TCP_BLOCK_MAX_KB) {
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
                    "rtt_ms": baseline.rtt_ms,
                    "dynamic_timeout_ms": dynamic_timeout_ms,
                    "size_label": stage_size_label.clone(),
                    "checks": running_checks
                }),
            );
        }

        let step = target_request_step(&client, target, Some(kb), Duration::from_millis(dynamic_timeout_ms)).await;
        let check_status = if step.status == "ok" {
            "available"
        } else if kb >= TCP_BLOCK_MIN_KB {
            "tcp16"
        } else {
            classify_probe_status(&step.status)
        };
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
            detail: if detected {
                format!(
                    "possible TCP threshold block at {kb} KB after last OK {last_ok_kb} KB: {}",
                    checks
                        .last()
                        .and_then(|v| v.get("detail"))
                        .and_then(Value::as_str)
                        .unwrap_or("failed")
                )
            } else {
                checks
                    .last()
                    .and_then(|v| v.get("detail"))
                    .and_then(Value::as_str)
                    .unwrap_or("failed")
                    .to_string()
            },
            bytes: kb * 1024,
            elapsed_ms: start_all.elapsed().as_millis(),
            rtt_ms: baseline.rtt_ms,
            break_kb: Some(kb),
            size_label: stage_size_label,
            checks,
        };
    }

    ProbeResult {
        status: "ok".to_string(),
        detail: format!(
            "all payload steps passed up to {} KB",
            TCP_PAYLOAD_STEPS_KB.iter().copied().max().unwrap_or(TCP_PAYLOAD_KB)
        ),
        bytes: last_ok_kb * 1024,
        elapsed_ms: start_all.elapsed().as_millis(),
        rtt_ms: baseline.rtt_ms,
        break_kb: None,
        size_label: stage_size_label,
        checks,
    }
}

async fn target_request_step(client: &Client, target: &TcpTarget, kb: Option<usize>, request_timeout: Duration) -> ProbeResult {
    let start = Instant::now();
    let result: Result<StatusCode> = async {
        let request = build_target_request(client, target, kb, true)?;
        let response = timeout(request_timeout, request.send()).await??;
        Ok(response.status())
    }
    .await;
    let elapsed_ms = start.elapsed().as_millis();
    match result {
        Ok(status) => ProbeResult {
            status: "ok".to_string(),
            detail: format!("HTTP response {status}"),
            bytes: kb.unwrap_or(0) * 1024,
            elapsed_ms,
            rtt_ms: Some(elapsed_ms),
            break_kb: kb,
            size_label: kb.map(|v| format!("{v} KB")).unwrap_or_else(|| "connect".to_string()),
            checks: Vec::new(),
        },
        Err(err) => {
            let detail = err.to_string();
            ProbeResult {
                status: classify_error(&detail).to_string(),
                detail,
                bytes: kb.unwrap_or(0) * 1024,
                elapsed_ms,
                rtt_ms: None,
                break_kb: kb,
                size_label: kb.map(|v| format!("{v} KB")).unwrap_or_else(|| "connect".to_string()),
                checks: Vec::new(),
            }
        }
    }
}

fn build_target_request(client: &Client, target: &TcpTarget, kb: Option<usize>, keep_alive: bool) -> Result<reqwest::RequestBuilder> {
    let port = target.port();
    let host = target.host_sni();
    let mut request = if port == 80 {
        let url = format!("http://{}:{}/", target.ip, port);
        client.head(url).header("Host", host.clone())
    } else if target.wants_no_sni() {
        let url = format!("https://{}:{}/", target.ip, port);
        client.head(url)
    } else {
        let url = format!("https://{host}/");
        client.head(url)
    }
    .header("User-Agent", USER_AGENT)
    .header("Accept", "*/*");

    if keep_alive {
        request = request.header("Connection", "keep-alive");
    } else {
        request = request.header("Connection", "close");
    }
    if let Some(kb) = kb {
        request = request.header("X-Pad", random_payload(kb * 1024));
    }
    Ok(request)
}

fn http_client_for_target(target: &TcpTarget, timeout_d: Duration, proxy: Option<&str>, max_keepalive: usize, max_conn: usize) -> Result<Client> {
    let port = target.port();
    let host = target.host_sni();
    let ip_addr = IpAddr::from_str(&target.ip)?;
    let mut builder = Client::builder()
        .timeout(timeout_d)
        .connect_timeout(Duration::from_millis(CONNECT_TIMEOUT_MS))
        .pool_max_idle_per_host(max_keepalive)
        .redirect(reqwest::redirect::Policy::none())
        .danger_accept_invalid_certs(true)
        .user_agent(USER_AGENT)
        .use_rustls_tls();

    if port != 80 && !target.wants_no_sni() {
        builder = builder.resolve(&host, SocketAddr::new(ip_addr, port));
    }
    if let Some(proxy_url) = proxy.filter(|p| !p.trim().is_empty()) {
        if let Ok(px) = reqwest::Proxy::all(proxy_url) {
            builder = builder.proxy(px);
        }
    }
    let _ = max_conn; // reserved to keep builder intent explicit
    Ok(builder.build()?)
}

fn http_client(timeout_d: Duration, danger: bool, proxy: Option<&str>) -> Client {
    let mut builder = Client::builder()
        .timeout(timeout_d)
        .connect_timeout(Duration::from_millis(CONNECT_TIMEOUT_MS))
        .redirect(reqwest::redirect::Policy::none())
        .user_agent(USER_AGENT)
        .use_rustls_tls();
    if danger {
        builder = builder.danger_accept_invalid_certs(true);
    }
    if let Some(proxy_url) = proxy.filter(|p| !p.trim().is_empty()) {
        if let Ok(px) = reqwest::Proxy::all(proxy_url) {
            builder = builder.proxy(px);
        }
    }
    builder.build().expect("reqwest client")
}

async fn tls_http_probe(domain: &str, version: reqwest::tls::Version, timeout_d: Duration, proxy: Option<&str>, resolved_ip: Option<&str>) -> String {
    let mut builder = Client::builder()
        .timeout(timeout_d)
        .connect_timeout(Duration::from_millis(CONNECT_TIMEOUT_MS))
        .redirect(reqwest::redirect::Policy::none())
        .min_tls_version(version)
        .max_tls_version(version)
        .danger_accept_invalid_certs(true)
        .user_agent(USER_AGENT)
        .pool_max_idle_per_host(0)
        .use_rustls_tls();
    if let Some(ip) = resolved_ip {
        if let Ok(addr) = IpAddr::from_str(ip) {
            builder = builder.resolve(domain, SocketAddr::new(addr, 443));
        }
    }
    if let Some(proxy_url) = proxy.filter(|p| !p.trim().is_empty()) {
        if let Ok(px) = reqwest::Proxy::all(proxy_url) {
            builder = builder.proxy(px);
        }
    }
    let Ok(client) = builder.build() else {
        return "client_error".to_string();
    };
    http_status_probe(&client, &format!("https://{domain}/"), timeout_d).await
}

async fn http_status_probe_direct(domain: &str, https: bool, timeout_d: Duration, proxy: Option<&str>, resolved_ip: Option<&str>) -> String {
    let scheme = if https { "https" } else { "http" };
    let mut builder = Client::builder()
        .timeout(timeout_d)
        .connect_timeout(Duration::from_millis(CONNECT_TIMEOUT_MS))
        .redirect(reqwest::redirect::Policy::none())
        .user_agent(USER_AGENT)
        .pool_max_idle_per_host(0)
        .use_rustls_tls();
    if https {
        builder = builder.danger_accept_invalid_certs(true);
    }
    if let Some(ip) = resolved_ip {
        if let Ok(addr) = IpAddr::from_str(ip) {
            builder = builder.resolve(domain, SocketAddr::new(addr, if https { 443 } else { 80 }));
        }
    }
    if let Some(proxy_url) = proxy.filter(|p| !p.trim().is_empty()) {
        if let Ok(px) = reqwest::Proxy::all(proxy_url) {
            builder = builder.proxy(px);
        }
    }
    let Ok(client) = builder.build() else {
        return "client_error".to_string();
    };
    http_status_probe(&client, &format!("{scheme}://{domain}/"), timeout_d).await
}

async fn http_status_probe(client: &Client, url: &str, timeout_d: Duration) -> String {
    match timeout(timeout_d, client.head(url).header("Connection", "close").send()).await {
        Ok(Ok(resp)) => classify_http_response(
            url,
            resp.status(),
            resp.headers()
                .get(reqwest::header::LOCATION)
                .and_then(|v| v.to_str().ok()),
        ),
        Ok(Err(e)) => classify_error(&e.to_string()).to_string(),
        Err(_) => "timeout".to_string(),
    }
}

async fn resolve_system_ips(domain: &str, timeout_d: Duration) -> Result<Vec<String>> {
    match timeout(timeout_d, lookup_host((domain, 443))).await {
        Ok(Ok(addrs)) => {
            let mut ips = Vec::new();
            let mut seen = HashSet::new();
            for addr in addrs {
                let ip = addr.ip().to_string();
                if seen.insert(ip.clone()) {
                    ips.push(ip);
                }
            }
            if ips.is_empty() {
                Err(anyhow!("empty"))
            } else {
                Ok(ips)
            }
        }
        Ok(Err(e)) => Err(anyhow!(e.to_string())),
        Err(_) => Err(anyhow!("timeout")),
    }
}

async fn resolve_system(domain: &str, timeout_d: Duration) -> String {
    match resolve_system_ips(domain, timeout_d).await {
        Ok(ips) => ips.first().cloned().unwrap_or_else(|| "empty".to_string()),
        Err(e) => classify_error(&e.to_string()).to_string(),
    }
}

async fn select_working_dns_servers(timeout_d: Duration, proxy: Option<&str>) -> DnsSelection {
    let probe_domain = DNS_CHECK_DOMAINS.first().copied().unwrap_or("example.com");
    let mut logs = Vec::new();

    let mut udp_working = Vec::new();
    for (server, name) in DNS_UDP_SERVERS {
        if quick_udp_probe(server, probe_domain, timeout_d).await || probe_dns_udp_many(server, DNS_CHECK_DOMAINS, timeout_d).await > 0 {
            udp_working.push((*server, *name));
        } else {
            logs.push(format!("UDP {server} ({name}) unavailable"));
        }
    }

    let mut doh_json_working = Vec::new();
    for (server, name) in DNS_DOH_JSON_SERVERS {
        if quick_doh_json_probe(server, probe_domain, timeout_d, proxy).await
            || probe_doh_json_many(server, DNS_CHECK_DOMAINS, timeout_d, proxy).await > 0
        {
            doh_json_working.push((*server, *name));
        } else {
            logs.push(format!("DoH JSON {server} ({name}) unavailable"));
        }
    }

    let mut doh_wire_working = Vec::new();
    for (server, name) in DNS_DOH_WIRE_SERVERS {
        if quick_doh_wire_probe(server, probe_domain, timeout_d, proxy).await
            || probe_doh_wire_many(server, DNS_CHECK_DOMAINS, timeout_d, proxy).await > 0
        {
            doh_wire_working.push((*server, *name));
        } else {
            logs.push(format!("DoH Wire {server} ({name}) unavailable"));
        }
    }

    DnsSelection {
        udp: pick_preferred_dns(&udp_working, DNS_UDP_SERVERS),
        doh_json: pick_preferred_dns(&doh_json_working, DNS_DOH_JSON_SERVERS),
        doh_wire: pick_preferred_dns(&doh_wire_working, DNS_DOH_WIRE_SERVERS),
        logs,
    }
}

fn pick_preferred_dns(
    working: &[(&'static str, &'static str)],
    all: &[(&'static str, &'static str)],
) -> Option<(&'static str, &'static str)> {
    if working.is_empty() {
        return None;
    }
    let preferred = all.first().copied();
    if let Some(first) = preferred {
        if working.iter().any(|item| *item == first) {
            return Some(first);
        }
    }
    working.first().copied()
}

async fn collect_stub_ips_silently(timeout_d: Duration) -> HashSet<String> {
    for (server, _) in DNS_UDP_SERVERS {
        let mut counts: HashMap<String, usize> = HashMap::new();
        let mut ok = 0usize;
        for domain in DNS_CHECK_DOMAINS {
            if let Ok(ips) = resolve_udp_retry(server, domain, timeout_d).await {
                ok += 1;
                for ip in ips {
                    *counts.entry(ip).or_insert(0) += 1;
                }
            }
        }
        if ok > 0 {
            return counts
                .into_iter()
                .filter_map(|(ip, count)| if count >= STUB_IP_REPEAT_THRESHOLD { Some(ip) } else { None })
                .collect();
        }
    }
    HashSet::new()
}

async fn resolve_udp_retry(server: &str, domain: &str, timeout_d: Duration) -> Result<Vec<String>> {
    for attempt in 0..2 {
        match resolve_udp(server, domain, timeout_d).await {
            Ok(ips) => return Ok(ips),
            Err(err) if attempt == 0 => {
                let _ = err;
                sleep(Duration::from_millis(400)).await;
            }
            Err(err) => return Err(err),
        }
    }
    Err(anyhow!("udp retry failed"))
}

async fn resolve_doh_wire_retry(server: &str, domain: &str, timeout_d: Duration, proxy: Option<&str>) -> Result<Vec<String>> {
    for attempt in 0..2 {
        match resolve_doh_wire(server, domain, timeout_d, proxy).await {
            Ok(ips) => return Ok(ips),
            Err(err) if attempt == 0 => {
                let _ = err;
                sleep(Duration::from_millis(400)).await;
            }
            Err(err) => return Err(err),
        }
    }
    Err(anyhow!("doh wire retry failed"))
}

async fn resolve_doh_json_retry(server: &str, domain: &str, timeout_d: Duration, proxy: Option<&str>) -> Result<Vec<String>> {
    for attempt in 0..2 {
        match resolve_doh_json(server, domain, timeout_d, proxy).await {
            Ok(ips) => return Ok(ips),
            Err(err) if attempt == 0 => {
                let _ = err;
                sleep(Duration::from_millis(400)).await;
            }
            Err(err) => return Err(err),
        }
    }
    Err(anyhow!("doh json retry failed"))
}

fn classify_dns_answer(result: Result<Vec<String>>) -> DnsAnswer {
    match result {
        Ok(ips) if !ips.is_empty() => DnsAnswer::Ips(ips),
        Ok(_) => DnsAnswer::Empty,
        Err(e) => {
            let message = e.to_string().to_lowercase();
            if message.contains("nxdomain") {
                DnsAnswer::Nxdomain
            } else if message.contains("empty") {
                DnsAnswer::Empty
            } else if message.contains("timeout") || message.contains("timed out") {
                DnsAnswer::Timeout
            } else {
                DnsAnswer::Blocked
            }
        }
    }
}

fn classify_domain(dns: &str, tls12: &str, tls13: &str, http: &str, https: &str) -> &'static str {
    let dns_bad = matches_blocked_signal(dns) || matches_suspicious_signal(dns);
    let tls_bad = [tls12, tls13]
        .iter()
        .any(|s| matches_blocked_signal(s) || matches_suspicious_signal(s));
    let https_bad = matches_blocked_signal(https) || matches_suspicious_signal(https);
    let http_bad = matches_blocked_signal(http) || matches_suspicious_signal(http);
    let tls_ok = is_http_okish(tls12) || is_http_okish(tls13);
    let https_ok = is_http_okish(https);
    let http_ok = is_http_okish(http);

    if dns_bad && !(tls_ok || https_ok || http_ok) {
        "blocked"
    } else if http.starts_with("http_451") || https.starts_with("http_451") {
        "blocked"
    } else if tls_bad || https_bad || http_bad || dns_bad {
        "suspicious"
    } else if (tls_ok || https_ok) && (http_ok || https_ok) {
        "ok"
    } else {
        "suspicious"
    }
}

fn compact(s: &str) -> &str {
    s
}

fn classify_http_response(request_url: &str, status: StatusCode, location: Option<&str>) -> String {
    if status.as_u16() == 451 {
        return "http_451_blocked".to_string();
    }
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
    let host = host_port
        .split('@')
        .last()
        .unwrap_or(host_port)
        .split(':')
        .next()
        .unwrap_or("");
    if host.is_empty() {
        None
    } else {
        Some(host.to_lowercase())
    }
}

fn classify_probe_status(value: &str) -> &'static str {
    if is_http_okish(value) || value == "ok" {
        "available"
    } else if matches_blocked_signal(value) {
        "blocked"
    } else if matches_suspicious_signal(value) {
        "suspicious"
    } else {
        "suspicious"
    }
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
    if d.contains("http 451") || d.contains("status 451") {
        "http_451_blocked"
    } else if d.contains("unrecognized_name") || d.contains("handshake failure") || d.contains("tls alert") || d.contains("alert") {
        "tls_alert"
    } else if d.contains("wrong version number") || d.contains("record overflow") || d.contains("decode error") || d.contains("illegal parameter") {
        "tls_spoof"
    } else if d.contains("certificate") || d.contains("unknown ca") || d.contains("self-signed") || d.contains("hostname mismatch") {
        "tls_mitm"
    } else if d.contains("timed out") || d.contains("timeout") || d.contains("deadline") {
        "timeout"
    } else if d.contains("pool timeout") {
        "pool_timeout"
    } else if d.contains("connection reset") || d.contains("reset by peer") || d.contains("broken pipe") {
        "tcp_rst"
    } else if d.contains("eof") || d.contains("operation did not complete") {
        "tls_rst"
    } else if d.contains("refused") {
        "refused"
    } else if d.contains("unreachable") || d.contains("network is unreachable") || d.contains("host unreachable") {
        "unreachable"
    } else {
        "blocked"
    }
}

fn random_payload(len: usize) -> String {
    rand::thread_rng().sample_iter(&Alphanumeric).take(len).map(char::from).collect()
}

async fn probe_dns_udp_many(server: &str, domains: &[&str], timeout_d: Duration) -> usize {
    let mut count = 0;
    for domain in domains {
        if resolve_udp_retry(server, domain, timeout_d).await.is_ok() {
            count += 1;
        }
    }
    count
}

async fn probe_doh_wire_many(server: &str, domains: &[&str], timeout_d: Duration, proxy: Option<&str>) -> usize {
    let mut count = 0;
    for domain in domains {
        if resolve_doh_wire_retry(server, domain, timeout_d, proxy).await.is_ok() {
            count += 1;
        }
    }
    count
}

async fn probe_doh_json_many(server: &str, domains: &[&str], timeout_d: Duration, proxy: Option<&str>) -> usize {
    let mut count = 0;
    for domain in domains {
        if resolve_doh_json_retry(server, domain, timeout_d, proxy).await.is_ok() {
            count += 1;
        }
    }
    count
}

async fn quick_udp_probe(server: &str, domain: &str, timeout_d: Duration) -> bool {
    resolve_udp_retry(server, domain, timeout_d).await.is_ok()
}

async fn quick_doh_wire_probe(server: &str, domain: &str, timeout_d: Duration, proxy: Option<&str>) -> bool {
    resolve_doh_wire_retry(server, domain, timeout_d, proxy).await.is_ok()
}

async fn quick_doh_json_probe(server: &str, domain: &str, timeout_d: Duration, proxy: Option<&str>) -> bool {
    resolve_doh_json_retry(server, domain, timeout_d, proxy).await.is_ok()
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

async fn resolve_doh_wire(server: &str, domain: &str, timeout_d: Duration, proxy: Option<&str>) -> Result<Vec<String>> {
    let query = build_dns_query(domain)?;
    let tx_id = [query[0], query[1]];
    let client = http_client(timeout_d, true, proxy);
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
            if !resp.status().is_success() {
                return Err(anyhow!("DoH status {}", resp.status()));
            }
            resp.bytes().await?.to_vec()
        }
    };
    parse_dns_response(&bytes, tx_id)
}

async fn resolve_doh_json(server: &str, domain: &str, timeout_d: Duration, proxy: Option<&str>) -> Result<Vec<String>> {
    let client = http_client(timeout_d, true, proxy);
    let resp = client
        .get(server)
        .query(&[("name", domain), ("type", "A")])
        .header("Accept", "application/dns-json")
        .send()
        .await?;
    if !resp.status().is_success() {
        return Err(anyhow!("DoH JSON status {}", resp.status()));
    }
    let data: Value = resp.json().await?;
    if data.get("Status").and_then(Value::as_i64) == Some(3) {
        return Err(anyhow!("NXDOMAIN"));
    }
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
    if ips.is_empty() {
        Err(anyhow!("empty answer"))
    } else {
        Ok(ips)
    }
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
        if part.len() > 63 {
            return Err(anyhow!("domain label too long"));
        }
        q.push(part.len() as u8);
        q.extend_from_slice(part.as_bytes());
    }
    q.push(0);
    q.extend_from_slice(&1u16.to_be_bytes());
    q.extend_from_slice(&1u16.to_be_bytes());
    Ok(q)
}

fn parse_dns_response(data: &[u8], tx_id: [u8; 2]) -> Result<Vec<String>> {
    if data.len() < 12 || data[0] != tx_id[0] || data[1] != tx_id[1] {
        return Err(anyhow!("invalid DNS response"));
    }
    let flags = u16::from_be_bytes([data[2], data[3]]);
    let rcode = flags & 0x000f;
    if rcode == 3 {
        return Err(anyhow!("NXDOMAIN"));
    }
    if rcode != 0 {
        return Err(anyhow!("DNS rcode {rcode}"));
    }
    let ancount = u16::from_be_bytes([data[6], data[7]]) as usize;
    let mut offset = 12usize;
    skip_name(data, &mut offset)?;
    offset += 4;
    let mut ips = Vec::new();
    for _ in 0..ancount {
        skip_name(data, &mut offset)?;
        if offset + 10 > data.len() {
            break;
        }
        let rtype = u16::from_be_bytes([data[offset], data[offset + 1]]);
        let rdlen = u16::from_be_bytes([data[offset + 8], data[offset + 9]]) as usize;
        offset += 10;
        if offset + rdlen > data.len() {
            break;
        }
        if rtype == 1 && rdlen == 4 {
            ips.push(Ipv4Addr::new(data[offset], data[offset + 1], data[offset + 2], data[offset + 3]).to_string());
        }
        offset += rdlen;
    }
    if ips.is_empty() {
        Err(anyhow!("empty DNS answer"))
    } else {
        Ok(ips)
    }
}

fn skip_name(data: &[u8], offset: &mut usize) -> Result<()> {
    let mut jumps = 0;
    loop {
        if *offset >= data.len() {
            return Err(anyhow!("DNS name out of range"));
        }
        let len = data[*offset];
        if len == 0 {
            *offset += 1;
            return Ok(());
        }
        if len & 0xc0 == 0xc0 {
            if *offset + 1 >= data.len() {
                return Err(anyhow!("DNS pointer out of range"));
            }
            *offset += 2;
            return Ok(());
        }
        *offset += len as usize + 1;
        jumps += 1;
        if jumps > 128 {
            return Err(anyhow!("DNS name loop"));
        }
    }
}

fn answers_overlap(a: &[String], b: &[String]) -> bool {
    let set: HashSet<&String> = a.iter().collect();
    b.iter().any(|ip| set.contains(ip))
}

fn fake_ip_type(ip: &str) -> Option<&'static str> {
    let addr = Ipv4Addr::from_str(ip).ok()?;
    let o = addr.octets();
    if o[0] == 198 && (o[1] == 18 || o[1] == 19) {
        Some("fakeip")
    } else if o[0] == 100 && (64..=127).contains(&o[1]) {
        Some("isp")
    } else if o[0] == 10
        || (o[0] == 172 && (16..=31).contains(&o[1]))
        || (o[0] == 192 && o[1] == 168)
        || o[0] == 127
        || o[0] == 0
        || (o[0] == 169 && o[1] == 254)
    {
        Some("local")
    } else {
        None
    }
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
    if idx == 0 {
        format!("{}{unit}", bytes, unit = units[idx])
    } else {
        format!("{v:.1}{unit}", unit = units[idx])
    }
}

fn fmt_speed(bps: f64) -> String {
    format!("{}/s", fmt_size(bps.max(0.0) as u64))
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
