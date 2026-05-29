async fn check_dns_integrity<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "dns_integrity";
    let title = "DNS interception / substitution";
    let timeout_d = Duration::from_millis(options.timeout_ms);
    let selection = select_working_dns_servers(timeout_d, options.proxy.as_deref()).await;
    let stub_ips = collect_stub_ips_silently(timeout_d).await;
    let _ = writer.started(
        id,
        title,
        json!({
            "domains": DNS_CHECK_DOMAINS.len(),
            "total_probes": DNS_CHECK_DOMAINS.len(),
            "udp_server": selection.udp.map(|(s,n)| json!({"server": s, "name": n})),
            "doh_json_server": selection.doh_json.map(|(s,n)| json!({"server": s, "name": n})),
            "doh_wire_server": selection.doh_wire.map(|(s,n)| json!({"server": s, "name": n})),
            "selection_log": selection.logs.clone(),
        }),
    );

    let mut suspicious = 0usize;
    let mut fake_local = 0usize;
    let mut compared = 0usize;
    let mut doh_blocked = 0usize;
    let mut details = Vec::new();

    for domain in DNS_CHECK_DOMAINS {
        let key = format!("dns-integrity:{domain}");
        let _ = writer.probe(
            id,
            &key,
            "DNS comparison",
            domain,
            "checking",
            "comparing UDP DNS with trusted DoH answers",
            json!({"domain": domain, "phase": "compare", "size_label": "DNS query"}),
        );
        let _ = writer.progress(id, "running", &format!("checking {domain}"), json!({"domain": domain}));

        let udp = if let Some((server, _)) = selection.udp {
            classify_dns_answer(resolve_udp_retry(server, domain, timeout_d).await)
        } else {
            DnsAnswer::Unavailable
        };
        let doh_json = if let Some((server, _)) = selection.doh_json {
            classify_dns_answer(resolve_doh_json_retry(server, domain, timeout_d, options.proxy.as_deref()).await)
        } else {
            DnsAnswer::Unavailable
        };
        let doh_wire = if let Some((server, _)) = selection.doh_wire {
            classify_dns_answer(resolve_doh_wire_retry(server, domain, timeout_d, options.proxy.as_deref()).await)
        } else {
            DnsAnswer::Unavailable
        };

        let mut trusted = Vec::<String>::new();
        if let Some(ips) = doh_json.as_ips() {
            trusted.extend(ips.iter().cloned());
        }
        if let Some(ips) = doh_wire.as_ips() {
            for ip in ips {
                if !trusted.contains(ip) {
                    trusted.push(ip.clone());
                }
            }
        }
        let udp_ips = udp.as_ips().map(|ips| ips.to_vec()).unwrap_or_default();
        let udp_has_fake = udp_ips.iter().any(|ip| fake_ip_type(ip).is_some());
        let udp_has_stub = udp_ips.iter().any(|ip| stub_ips.contains(ip));
        let mut status = "ok";
        let mut diagnosis = "clean";
        let reason: String;

        if !trusted.is_empty() && !udp_ips.is_empty() {
            compared += 1;
            if answers_overlap(&udp_ips, &trusted) {
                reason = format!("UDP and DoH overlap: {}", trusted.iter().take(3).cloned().collect::<Vec<_>>().join(", "));
            } else if udp_has_fake {
                fake_local += 1;
                status = "suspicious";
                diagnosis = "possible_dns_injection";
                reason = "UDP DNS returned fake/local/provider address".to_string();
            } else if udp_has_stub {
                suspicious += 1;
                status = "suspicious";
                diagnosis = "possible_dns_intercept";
                reason = "UDP DNS returned repeated provider stub IP".to_string();
            } else {
                suspicious += 1;
                status = "suspicious";
                diagnosis = "possible_dns_intercept";
                reason = "UDP and trusted DoH answers do not overlap".to_string();
            }
        } else if !trusted.is_empty() && udp_ips.is_empty() {
            suspicious += 1;
            status = "blocked";
            diagnosis = "possible_dns_intercept";
            reason = match udp {
                DnsAnswer::Timeout => "UDP DNS timed out while DoH works".to_string(),
                DnsAnswer::Nxdomain => "UDP returned fake NXDOMAIN while DoH resolves".to_string(),
                DnsAnswer::Empty => "UDP returned empty answer while DoH resolves".to_string(),
                DnsAnswer::Unavailable => "UDP server unavailable while DoH works".to_string(),
                _ => "UDP DNS failed while DoH works".to_string(),
            };
        } else if trusted.is_empty() && !udp_ips.is_empty() {
            doh_blocked += 1;
            status = if udp_has_fake || udp_has_stub { "suspicious" } else { "partial" };
            diagnosis = "possible_doh_block";
            reason = "DoH unavailable but UDP still returns answers".to_string();
        } else {
            doh_blocked += 1;
            status = "blocked";
            diagnosis = "possible_dns_block";
            reason = "both UDP and DoH paths are unavailable".to_string();
        }

        let probe_status = if status == "ok" { "available" } else { status };
        let technical = json!({
            "domain": domain,
            "udp": udp.short(),
            "doh_json": doh_json.short(),
            "doh_wire": doh_wire.short(),
            "stub_ips": stub_ips.iter().cloned().collect::<Vec<_>>().join(", "),
        });
        let _ = writer.probe(
            id,
            &key,
            "DNS comparison",
            domain,
            probe_status,
            &reason,
            json!({
                "domain": domain,
                "status": status,
                "diagnosis": diagnosis,
                "size_label": "DNS query",
                "technical": technical,
                "checks": [
                    {"name": "UDP", "status": if udp.as_ips().is_some() { "available" } else { classify_probe_status(udp.label()) }, "detail": udp.short(), "value": udp.short(), "size_label": "DNS query"},
                    {"name": "DoH JSON", "status": if doh_json.as_ips().is_some() { "available" } else { classify_probe_status(doh_json.label()) }, "detail": doh_json.short(), "value": doh_json.short(), "size_label": "DoH query"},
                    {"name": "DoH Wire", "status": if doh_wire.as_ips().is_some() { "available" } else { classify_probe_status(doh_wire.label()) }, "detail": doh_wire.short(), "value": doh_wire.short(), "size_label": "DoH query"}
                ]
            }),
        );
        let _ = writer.progress(id, status, &reason, json!({"domain": domain, "diagnosis": diagnosis}));
        details.push(json!({
            "domain": domain,
            "status": status,
            "diagnosis": diagnosis,
            "udp": udp.short(),
            "doh_json": doh_json.short(),
            "doh_wire": doh_wire.short(),
            "reason": reason,
        }));
    }

    let risk = if suspicious + fake_local >= 2 || doh_blocked >= 2 { "high" } else if suspicious + fake_local + doh_blocked > 0 { "medium" } else { "low" };
    let status = if risk == "low" { "ok" } else if suspicious + fake_local > 0 { "suspicious" } else { "partial" };
    let detail = format!(
        "checked {}, compared {}, suspicious {}, fake/local {}, DoH unavailable {}",
        DNS_CHECK_DOMAINS.len(), compared, suspicious, fake_local, doh_blocked
    );
    let diagnosis = if suspicious + fake_local > 0 { "possible_dns_intercept" } else if doh_blocked > 0 { "possible_doh_block" } else { "clean" };
    let _ = writer.result(
        id,
        status,
        &detail,
        json!({
            "udp_server": selection.udp,
            "doh_json_server": selection.doh_json,
            "doh_wire_server": selection.doh_wire,
            "items": details,
            "stub_ips": stub_ips.into_iter().collect::<Vec<_>>(),
            "risk": risk,
            "diagnosis": diagnosis
        }),
    );
    summary(id, title, status, detail, risk)
}

async fn check_dns_availability<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "dns_availability";
    let title = "DNS server availability";
    let timeout_d = Duration::from_millis(options.timeout_ms);
    let dns_total = (if options.quick { 4 } else { DNS_UDP_SERVERS.len() })
        + (if options.quick { 4 } else { DNS_DOH_WIRE_SERVERS.len() })
        + (if options.quick { 3 } else { DNS_DOH_JSON_SERVERS.len() });
    let _ = writer.started(id, title, json!({"domains": DNS_AVAILABILITY_DOMAINS.len(), "total_probes": dns_total}));

    let mut ok = 0usize;
    let mut failed = 0usize;
    let mut rows = Vec::new();

    for (server, name) in DNS_UDP_SERVERS.iter().take(if options.quick { 4 } else { DNS_UDP_SERVERS.len() }) {
        let key = format!("dns-udp:{server}");
        let probe_name = format!("UDP DNS {name}");
        let _ = writer.probe(id, &key, &probe_name, server, "checking", "sending UDP DNS queries", json!({"server": server, "kind": "udp", "size_label": "DNS query"}));
        let start = Instant::now();
        let count = probe_dns_udp_many(server, DNS_AVAILABILITY_DOMAINS, timeout_d).await;
        let elapsed_ms = start.elapsed().as_millis();
        let status = if count > 0 { ok += 1; "ok" } else { failed += 1; "blocked" };
        let detail = format!("UDP {server} ({name}) answered {count}/{} domains", DNS_AVAILABILITY_DOMAINS.len());
        rows.push(json!({"server": server, "name": name, "kind": "udp", "ok_domains": count, "status": status, "elapsed_ms": elapsed_ms}));
        let probe_status = if status == "ok" { "available" } else { status };
        let _ = writer.probe(id, &key, &probe_name, server, probe_status, &detail, json!({"server": server, "kind": "udp", "ok_domains": count, "elapsed_ms": elapsed_ms, "size_label": "DNS query"}));
        let _ = writer.progress(id, status, &detail, json!({"server": server, "kind": "udp"}));
    }

    for (server, name) in DNS_DOH_WIRE_SERVERS.iter().take(if options.quick { 4 } else { DNS_DOH_WIRE_SERVERS.len() }) {
        let key = format!("doh-wire:{server}");
        let probe_name = format!("DoH Wire {name}");
        let _ = writer.probe(id, &key, &probe_name, server, "checking", "sending DNS-over-HTTPS wire queries", json!({"server": server, "kind": "doh_wire", "size_label": "DoH query"}));
        let start = Instant::now();
        let count = probe_doh_wire_many(server, DNS_AVAILABILITY_DOMAINS, timeout_d, options.proxy.as_deref()).await;
        let elapsed_ms = start.elapsed().as_millis();
        let status = if count > 0 { ok += 1; "ok" } else { failed += 1; "blocked" };
        let detail = format!("DoH {server} ({name}) answered {count}/{} domains", DNS_AVAILABILITY_DOMAINS.len());
        rows.push(json!({"server": server, "name": name, "kind": "doh_wire", "ok_domains": count, "status": status, "elapsed_ms": elapsed_ms}));
        let probe_status = if status == "ok" { "available" } else { status };
        let _ = writer.probe(id, &key, &probe_name, server, probe_status, &detail, json!({"server": server, "kind": "doh_wire", "ok_domains": count, "elapsed_ms": elapsed_ms, "size_label": "DoH query"}));
        let _ = writer.progress(id, status, &detail, json!({"server": server, "kind": "doh_wire"}));
    }

    for (server, name) in DNS_DOH_JSON_SERVERS.iter().take(if options.quick { 3 } else { DNS_DOH_JSON_SERVERS.len() }) {
        let key = format!("doh-json:{server}");
        let probe_name = format!("DoH JSON {name}");
        let _ = writer.probe(id, &key, &probe_name, server, "checking", "sending DNS-over-HTTPS JSON queries", json!({"server": server, "kind": "doh_json", "size_label": "DoH query"}));
        let start = Instant::now();
        let count = probe_doh_json_many(server, DNS_AVAILABILITY_DOMAINS, timeout_d, options.proxy.as_deref()).await;
        let elapsed_ms = start.elapsed().as_millis();
        let status = if count > 0 { ok += 1; "ok" } else { failed += 1; "blocked" };
        let detail = format!("DoH JSON {server} ({name}) answered {count}/{} domains", DNS_AVAILABILITY_DOMAINS.len());
        rows.push(json!({"server": server, "name": name, "kind": "doh_json", "ok_domains": count, "status": status, "elapsed_ms": elapsed_ms}));
        let probe_status = if status == "ok" { "available" } else { status };
        let _ = writer.probe(id, &key, &probe_name, server, probe_status, &detail, json!({"server": server, "kind": "doh_json", "ok_domains": count, "elapsed_ms": elapsed_ms, "size_label": "DoH query"}));
        let _ = writer.progress(id, status, &detail, json!({"server": server, "kind": "doh_json"}));
    }

    let risk = if ok == 0 { "high" } else if failed > ok { "medium" } else { "low" };
    let status = if ok == 0 { "blocked" } else if failed > 0 { "partial" } else { "ok" };
    let detail = format!("available DNS endpoints: {ok}, unavailable: {failed}");
    let diagnosis = if ok == 0 { "possible_dns_block" } else if failed > 0 { "partial_unavailable" } else { "clean" };
    let _ = writer.result(id, status, &detail, json!({"ok": ok, "failed": failed, "items": rows, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

async fn check_domains<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "domains";
    let title = "Domain DNS/TLS/HTTP availability";
    let mut domains = if options.domains_override.is_empty() {
        load_lines(DOMAINS_TXT, options.max_domains)
    } else {
        options.domains_override.iter().map(|d| clean_hostname(d)).filter(|d| !d.is_empty()).collect()
    };
    domains.truncate(options.max_domains.max(1));
    let _ = writer.started(id, title, json!({"domains": domains.len(), "total_probes": domains.len(), "mode": "dns_tls_http", "concurrency": options.concurrency}));
    let timeout_d = Duration::from_millis(options.timeout_ms);
    let stub_ips = collect_stub_ips_silently(timeout_d).await;

    let mut ok = 0usize;
    let mut suspicious = 0usize;
    let mut blocked = 0usize;
    let mut rows = Vec::new();

    for domain in domains {
        let domain = clean_hostname(&domain);
        let key = format!("domain:{domain}");
        let mut checks: Vec<Value> = Vec::new();
        let _ = writer.progress(id, "running", &format!("checking {domain}"), json!({"domain": domain, "size_label": "DNS/TLS/HTTP"}));
        let _ = writer.probe(id, &key, "Domain reachability", &domain, "checking", "starting DNS/TLS/HTTP checks", json!({"domain": domain, "size_label": "DNS/TLS/HTTP", "checks": checks}));

        let resolved_ips = resolve_system_ips(&domain, timeout_d).await;
        let (dns, resolved_ip_opt, dns_status, dns_detail) = match resolved_ips {
            Ok(ips) => {
                let first = ips.first().cloned().unwrap_or_else(|| "empty".to_string());
                let status = match fake_ip_type(&first) {
                    Some("fakeip") | Some("local") | Some("isp") => "suspicious",
                    _ if stub_ips.contains(&first) => "suspicious",
                    _ => "available",
                };
                let detail = if status == "available" {
                    format!("resolved to {}", ips.join(", "))
                } else if stub_ips.contains(&first) {
                    format!("resolved to provider stub {}", ips.join(", "))
                } else {
                    format!("DNS result: {}", ips.join(", "))
                };
                (first.clone(), Some(first), status, detail)
            }
            Err(err) => {
                let value = classify_error(&err.to_string()).to_string();
                let status = if value == "timeout" || value == "unreachable" { "blocked" } else { "suspicious" };
                (value.clone(), None, status, format!("DNS result: {value}"))
            }
        };
        checks.push(json!({"name": "DNS", "status": dns_status, "detail": dns_detail, "value": dns, "size_label": "DNS query"}));
        let _ = writer.probe(id, &key, "Domain reachability", &domain, "checking", "DNS check completed, checking TLS 1.3", json!({"domain": domain, "dns": dns, "size_label": "DNS/TLS/HTTP", "checks": checks}));

        let tls13 = tls_http_probe(&domain, reqwest::tls::Version::TLS_1_3, timeout_d, options.proxy.as_deref(), resolved_ip_opt.as_deref()).await;
        let tls13_status = classify_probe_status(&tls13);
        checks.push(json!({"name": "TLS 1.3", "status": tls13_status, "detail": format_probe_detail(&tls13), "value": compact(&tls13), "size_label": "TLS handshake"}));
        let _ = writer.probe(id, &key, "Domain reachability", &domain, "checking", "TLS 1.3 completed, checking TLS 1.2", json!({"domain": domain, "dns": dns, "tls13": tls13, "size_label": "DNS/TLS/HTTP", "checks": checks}));

        let tls12 = tls_http_probe(&domain, reqwest::tls::Version::TLS_1_2, timeout_d, options.proxy.as_deref(), resolved_ip_opt.as_deref()).await;
        let tls12_status = classify_probe_status(&tls12);
        checks.push(json!({"name": "TLS 1.2", "status": tls12_status, "detail": format_probe_detail(&tls12), "value": compact(&tls12), "size_label": "TLS handshake"}));
        let _ = writer.probe(id, &key, "Domain reachability", &domain, "checking", "TLS 1.2 completed, checking HTTP", json!({"domain": domain, "dns": dns, "tls12": tls12, "tls13": tls13, "size_label": "DNS/TLS/HTTP", "checks": checks}));

        let http = http_status_probe_direct(&domain, false, timeout_d, options.proxy.as_deref(), resolved_ip_opt.as_deref()).await;
        let http_status = classify_probe_status(&http);
        checks.push(json!({"name": "HTTP", "status": http_status, "detail": format_probe_detail(&http), "value": compact(&http), "size_label": "HTTP HEAD"}));
        let _ = writer.probe(id, &key, "Domain reachability", &domain, "checking", "HTTP completed, checking HTTPS", json!({"domain": domain, "dns": dns, "tls12": tls12, "tls13": tls13, "http": http, "size_label": "DNS/TLS/HTTP", "checks": checks}));

        let https = http_status_probe_direct(&domain, true, timeout_d, options.proxy.as_deref(), resolved_ip_opt.as_deref()).await;
        let https_status = classify_probe_status(&https);
        checks.push(json!({"name": "HTTPS", "status": https_status, "detail": format_probe_detail(&https), "value": compact(&https), "size_label": "HTTPS HEAD"}));

        let status = classify_domain(&dns, &tls12, &tls13, &http, &https);
        match status {
            "ok" => ok += 1,
            "blocked" => blocked += 1,
            _ => suspicious += 1,
        }
        let diagnosis = domain_diagnosis(&dns, &tls12, &tls13, &http, &https, status);
        let detail = format!("{domain}: dns={}, tls12={}, tls13={}, http={}, https={}", compact(&dns), compact(&tls12), compact(&tls13), compact(&http), compact(&https));
        let probe_status = if status == "ok" { "available" } else { status };
        let _ = writer.probe(id, &key, "Domain reachability", &domain, probe_status, &detail, json!({
            "domain": domain,
            "dns": dns,
            "tls12": tls12,
            "tls13": tls13,
            "http": http,
            "https": https,
            "diagnosis": diagnosis,
            "size_label": "DNS/TLS/HTTP",
            "checks": checks
        }));
        let _ = writer.progress(id, status, &detail, json!({"domain": domain, "diagnosis": diagnosis, "size_label": "DNS/TLS/HTTP"}));
        rows.push(json!({"domain": domain, "status": status, "diagnosis": diagnosis, "dns": dns, "tls12": tls12, "tls13": tls13, "http": http, "https": https}));
    }

    let risk = if blocked > 3 { "high" } else if suspicious + blocked > 0 { "medium" } else { "low" };
    let status = if blocked > 0 { "blocked" } else if suspicious > 0 { "partial" } else { "ok" };
    let diagnosis = if blocked > 0 { "possible_domain_block" } else if suspicious > 0 { "partial_unavailable" } else { "clean" };
    let detail = format!("domains ok={ok}, suspicious={suspicious}, blocked={blocked}");
    let _ = writer.result(id, status, &detail, json!({"ok": ok, "suspicious": suspicious, "blocked": blocked, "items": rows, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

fn domain_diagnosis(dns: &str, tls12: &str, tls13: &str, http: &str, https: &str, status: &str) -> &'static str {
    let dns_text = compact(dns).to_lowercase();
    let tls12_text = compact(tls12).to_lowercase();
    let tls13_text = compact(tls13).to_lowercase();
    let http_text = compact(http).to_lowercase();
    let https_text = compact(https).to_lowercase();
    let tls_bad = [tls12_text.as_str(), tls13_text.as_str(), https_text.as_str()].iter().any(|v| {
        v.contains("blocked") || v.contains("rst") || v.contains("alert") || v.contains("timeout") || v.contains("tls_") || v.contains("refused") || v.contains("unreachable")
    });
    if dns_text.contains("fake") || dns_text.contains("local") || dns_text.contains("isp") || dns_text.contains("dns_fail") || dns_text.contains("nxdomain") || dns_text.contains("timeout") {
        "possible_dns_block"
    } else if tls_bad {
        "possible_tls_sni_block"
    } else if http_text.contains("451") || http_text.contains("redirect_suspicious") || https_text.contains("451") || https_text.contains("redirect_suspicious") {
        "possible_http_filtering"
    } else if status == "blocked" {
        "blocked_unknown"
    } else if status == "ok" {
        "clean"
    } else {
        "partial_unavailable"
    }
}

async fn check_tcp16<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "tcp16";
    let title = "TCP 12-64KB payload threshold";
    let targets = load_tcp_targets(options.max_tcp_targets);
    let _ = writer.started(id, title, json!({"targets": targets.len(), "total_probes": targets.len(), "min_kb": TCP_BLOCK_MIN_KB, "max_kb": TCP_BLOCK_MAX_KB, "steps_kb": TCP_PAYLOAD_STEPS_KB}));
    let mut ok = 0usize;
    let mut detected = 0usize;
    let mut failed = 0usize;
    let mut rows = Vec::new();

    for target in targets {
        let key = format!("tcp16:{}", target.id);
        let target_addr = format!("{}:{}", target.ip, target.port());
        let _ = writer.probe(id, &key, "TCP payload threshold", &target_addr, "checking", "measuring RTT and sending increasing X-Pad payloads", json!({"target": target.id, "provider": target.provider, "size_label": format!("{}-{} KB", TCP_BLOCK_MIN_KB, TCP_PAYLOAD_STEPS_KB.iter().copied().filter(|kb| *kb <= TCP_BLOCK_MAX_KB).max().unwrap_or(TCP_BLOCK_MAX_KB)), "checks": []}));
        let _ = writer.progress(id, "running", &format!("{} {}:{}", target.id, target.ip, target.port()), json!({"target": target.id}));
        let res = tcp_payload_probe_staged(&target, Duration::from_millis(READ_TIMEOUT_MS), options.proxy.as_deref(), Some((writer, id, &key))).await;
        match res.status.as_str() {
            "ok" => ok += 1,
            "tcp16" | "reset" | "timeout" | "tls_rst" | "tcp_rst" => detected += 1,
            _ => failed += 1,
        }
        let detail = format!("{} {} {}:{} => {} ({})", target.id, target.provider, target.ip, target.port(), res.status, res.detail);
        let probe_status = match res.status.as_str() { "ok" => "available", "tcp16" | "reset" | "timeout" | "tls_rst" | "tcp_rst" => "suspicious", "refused" | "unreachable" => "blocked", other => other };
        let _ = writer.probe(id, &key, "TCP payload threshold", &target_addr, probe_status, &detail, json!({"target": target.id, "provider": target.provider, "bytes": res.bytes, "elapsed_ms": res.elapsed_ms, "rtt_ms": res.rtt_ms, "break_kb": res.break_kb, "size_label": res.size_label.clone(), "checks": res.checks.clone()}));
        let _ = writer.progress(id, &res.status, &detail, json!({"target": target.id, "bytes": res.bytes, "break_kb": res.break_kb}));
        rows.push(json!({"target": target, "status": res.status.clone(), "detail": res.detail.clone(), "bytes": res.bytes, "elapsed_ms": res.elapsed_ms, "rtt_ms": res.rtt_ms, "break_kb": res.break_kb, "checks": res.checks.clone()}));
    }

    let risk = if detected >= 3 { "high" } else if detected > 0 || failed > ok { "medium" } else { "low" };
    let status = if detected > 0 { "suspicious" } else if ok > 0 { "ok" } else { "blocked" };
    let detail = format!("tcp threshold targets ok={ok}, detected={detected}, failed={failed}");
    let diagnosis = if detected > 0 { "possible_tcp_block" } else if failed > ok { "partial_unavailable" } else { "clean" };
    let _ = writer.result(id, status, &detail, json!({"ok": ok, "detected": detected, "failed": failed, "items": rows, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

async fn check_whitelist_sni<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "whitelist_sni";
    let title = "Whitelist SNI probing";
    let targets = load_tcp_targets(if options.quick { 12 } else { options.max_tcp_targets.min(40) });
    let sni_list = load_lines(WHITELIST_SNI_TXT, options.max_sni.max(SNI_TOP_N));
    let sni_total = targets.iter().filter(|t| t.port() == 443).count().saturating_mul(sni_list.len().saturating_add(1));
    let _ = writer.started(id, title, json!({"targets": targets.len(), "sni": sni_list.len(), "total_probes": sni_total, "phase_model": "baseline_detected_as_then_batched_sni"}));

    let mut candidates: HashMap<String, (TcpTarget, Option<u128>, ProbeResult)> = HashMap::new();
    for target in targets.into_iter().filter(|t| t.port() == 443) {
        let baseline_key = format!("sni-baseline:{}", target.id);
        let target_addr = format!("{}:{}", target.ip, target.port());
        let _ = writer.probe(id, &baseline_key, "Baseline AS/IP probe", &target_addr, "checking", "checking whether target is blocked without alternate SNI", json!({"target": target.id, "provider": target.provider, "asn": target.asn_label(), "size_label": "TLS connect"}));
        let baseline = tcp_payload_probe_staged::<W>(&target, Duration::from_millis(READ_TIMEOUT_MS), options.proxy.as_deref(), None).await;
        let baseline_status = match baseline.status.as_str() { "ok" => "available", "timeout" | "reset" | "tls_rst" | "tcp_rst" | "tcp16" => "suspicious", other => other };
        let _ = writer.probe(id, &baseline_key, "Baseline AS/IP probe", &target_addr, baseline_status, &baseline.detail, json!({"target": target.id, "provider": target.provider, "asn": target.asn_label(), "status": baseline.status.clone(), "rtt_ms": baseline.rtt_ms, "break_kb": baseline.break_kb, "checks": baseline.checks.clone(), "size_label": "TLS connect"}));

        if matches!(baseline.status.as_str(), "tcp16" | "timeout" | "tcp_rst" | "tls_rst" | "reset") {
            let asn_key = target.normalized_asn();
            let should_replace = match candidates.get(&asn_key) {
                Some((_, prev_rtt, _)) => baseline.rtt_ms.unwrap_or(u128::MAX) < prev_rtt.unwrap_or(u128::MAX),
                None => true,
            };
            if should_replace {
                candidates.insert(asn_key, (target, baseline.rtt_ms, baseline));
            }
        }
    }

    let mut success = 0usize;
    let mut total = 0usize;
    let mut skipped = 0usize;
    let mut rows = Vec::new();

    if candidates.is_empty() {
        let detail = "no blocked AS/IP baseline candidates detected".to_string();
        let _ = writer.result(id, "skipped", &detail, json!({"success": success, "total": total, "skipped": skipped, "items": rows, "risk": "low", "diagnosis": "clean"}));
        return summary(id, title, "skipped", detail, "low");
    }

    for (_, (target, hint_rtt, _baseline)) in candidates.into_iter() {
        let target_label = format!("{} {}", target.provider, target.asn_label());
        let mut found = Vec::<String>::new();
        let mut ban_after = false;
        let mut ban_detail = String::new();

        let mut no_sni_target = target.clone();
        no_sni_target.sni = Some(String::new());
        let no_sni_key = format!("sni:{}:<none>", no_sni_target.id);
        let _ = writer.probe(id, &no_sni_key, "Whitelist SNI", &target_label, "checking", "probing blocked target without SNI", json!({"target": no_sni_target.id, "sni": "", "size_label": "TLS connect"}));
        let res0 = tcp_payload_probe_staged::<W>(&no_sni_target, Duration::from_millis(READ_TIMEOUT_MS), options.proxy.as_deref(), None).await;
        if res0.status == "ok" {
            found.push("(no SNI)".to_string());
            success += 1;
        } else if !matches!(res0.status.as_str(), "tcp16" | "timeout" | "tcp_rst" | "tls_rst" | "reset") {
            ban_after = true;
            ban_detail = res0.status.clone();
        }
        let _ = writer.probe(id, &no_sni_key, "Whitelist SNI", &target_label, if res0.status == "ok" { "available" } else { classify_probe_status(&res0.status) }, &res0.detail, json!({"target": no_sni_target.id, "sni": "", "rtt_ms": res0.rtt_ms, "break_kb": res0.break_kb, "checks": res0.checks.clone(), "size_label": "TLS connect"}));

        if found.len() < SNI_TOP_N && !ban_after {
            for batch in sni_list.chunks(SNI_BATCH_SIZE) {
                if found.len() >= SNI_TOP_N { break; }
                let mut batch_failures = 0usize;
                for sni in batch {
                    total += 1;
                    let key = format!("sni:{}:{}", target.id, sni);
                    let label = format!("{} -> {}", target.ip, sni);
                    let _ = writer.probe(id, &key, "Whitelist SNI", &label, "checking", "probing blocked target with alternate SNI", json!({"target": target.id, "sni": sni, "hint_rtt_ms": hint_rtt, "size_label": "TLS connect"}));
                    let mut probe_target = target.clone();
                    probe_target.sni = Some(sni.clone());
                    let res = tcp_payload_probe_staged::<W>(&probe_target, Duration::from_millis(READ_TIMEOUT_MS), options.proxy.as_deref(), None).await;
                    if res.status == "ok" {
                        if found.len() < SNI_TOP_N {
                            found.push(sni.clone());
                            success += 1;
                        }
                    } else if !matches!(res.status.as_str(), "tcp16" | "timeout" | "tcp_rst" | "tls_rst" | "reset") {
                        batch_failures += 1;
                        if ban_detail.is_empty() { ban_detail = res.status.clone(); }
                    }
                    let detail = format!("{} with SNI {} => {} ({})", target.id, sni, res.status, res.detail);
                    let probe_status = if res.status == "ok" { "available" } else { classify_probe_status(&res.status) };
                    let _ = writer.probe(id, &key, "Whitelist SNI", &label, probe_status, &detail, json!({"target": target.id, "sni": sni, "rtt_ms": res.rtt_ms, "break_kb": res.break_kb, "checks": res.checks.clone(), "size_label": "TLS connect"}));
                    let _ = writer.progress(id, &res.status, &detail, json!({"target": target.id, "sni": sni, "rtt_ms": res.rtt_ms, "size_label": "TLS connect"}));
                    rows.push(json!({"target": target.id, "provider": target.provider, "asn": target.asn_label(), "ip": target.ip, "sni": sni, "status": res.status, "detail": res.detail, "rtt_ms": res.rtt_ms, "break_kb": res.break_kb}));
                }
                if batch_failures == batch.len() {
                    ban_after = true;
                    break;
                }
            }
        }

        if found.is_empty() && !ban_after {
            skipped += 1;
        }
        rows.push(json!({"target": target.id, "provider": target.provider, "asn": target.asn_label(), "ip": target.ip, "found": found, "ban_after": ban_after, "ban_detail": ban_detail}));
    }

    let risk = if success == 0 && total > 0 { "medium" } else { "low" };
    let status = if success > 0 { "ok" } else if skipped > 0 && total == 0 { "skipped" } else { "not_found" };
    let detail = format!("working SNI combinations {success}/{total}, baseline reachable/skipped {skipped}");
    let diagnosis = if success > 0 { "possible_sni_bypass_found" } else if total > 0 { "possible_tls_sni_block" } else { "clean" };
    let _ = writer.result(id, status, &detail, json!({"success": success, "total": total, "skipped": skipped, "items": rows, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

async fn check_telegram<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> TestSummary {
    let id = "telegram";
    let title = "Telegram DC/download/upload";
    let _ = writer.started(id, title, json!({"dc_count": TELEGRAM_DC_IPS.len(), "total_probes": TELEGRAM_DC_IPS.len() + 2}));

    let dc = telegram_dc_probe(writer).await;
    let download = telegram_download_probe(writer, options.quick, options.proxy.as_deref()).await;
    let upload = telegram_upload_probe(writer, options.quick, options.proxy.as_deref()).await;

    let bad_dc = dc.iter().filter(|d| d["status"] != "ok").count();
    let bad_media = download["status"] != "ok" || upload["status"] != "ok";
    let risk = if bad_dc == TELEGRAM_DC_IPS.len() || (download["status"] == "blocked" && upload["status"] == "blocked") {
        "high"
    } else if bad_dc > 0 || bad_media {
        "medium"
    } else {
        "low"
    };
    let status = if risk == "low" { "ok" } else if risk == "high" { "blocked" } else { "partial" };
    let detail = format!("dc blocked {}/{}, download={}, upload={}", bad_dc, TELEGRAM_DC_IPS.len(), download["status"], upload["status"]);
    let diagnosis = if risk == "high" { "possible_telegram_block" } else if risk == "medium" { "partial_unavailable" } else { "clean" };
    let _ = writer.result(id, status, &detail, json!({"dc": dc, "download": download, "upload": upload, "risk": risk, "diagnosis": diagnosis}));
    summary(id, title, status, detail, risk)
}

async fn telegram_dc_probe<W: EventWriter + Send>(writer: &mut W) -> Vec<Value> {
    let mut rows = Vec::new();
    for (ip, label) in TELEGRAM_DC_IPS {
        let key = format!("telegram-dc:{ip}");
        let _ = writer.probe("telegram", &key, &format!("Telegram {label}"), ip, "checking", "opening TCP connection to Telegram DC", json!({"ip": ip, "label": label, "phase": "dc", "size_label": "TCP connect"}));
        let start = Instant::now();
        let addr = format!("{ip}:443");
        let status = match timeout(Duration::from_millis(CONNECT_TIMEOUT_MS), TcpStream::connect(&addr)).await {
            Ok(Ok(stream)) => {
                drop(stream);
                "ok"
            }
            Ok(Err(_)) => "blocked",
            Err(_) => "timeout",
        };
        let elapsed = start.elapsed().as_millis();
        let detail = format!("Telegram {label} {ip}:443 => {status} ({elapsed}ms)");
        let probe_status = if status == "ok" { "available" } else { status };
        let _ = writer.probe("telegram", &key, &format!("Telegram {label}"), ip, probe_status, &detail, json!({"ip": ip, "label": label, "rtt_ms": elapsed, "phase": "dc", "size_label": "TCP connect"}));
        let _ = writer.progress("telegram", status, &detail, json!({"ip": ip, "label": label, "rtt_ms": elapsed}));
        rows.push(json!({"ip": ip, "label": label, "status": status, "rtt_ms": elapsed}));
    }
    rows
}

async fn telegram_download_probe<W: EventWriter + Send>(writer: &mut W, quick: bool, proxy: Option<&str>) -> Value {
    let client = http_client(Duration::from_millis(TOTAL_TIMEOUT_MS + 5000), true, proxy);
    let limit = if quick { 4 * 1024 * 1024 } else { TELEGRAM_MEDIA_LIMIT };
    let _ = writer.probe("telegram", "telegram-download", "Telegram download", TELEGRAM_MEDIA_URL, "checking", "downloading Telegram media test file", json!({"phase":"download", "limit": limit, "size_label": fmt_size(limit as u64)}));

    let start = Instant::now();
    let mut total = 0usize;
    let mut peak = 0usize;
    let mut tick_bytes = 0usize;
    let mut last_data = Instant::now();
    let mut drop_at_sec: Option<u64> = None;

    let result: Result<()> = async {
        let resp = client.get(TELEGRAM_MEDIA_URL).send().await?;
        let mut stream = resp.bytes_stream();
        loop {
            tokio::select! {
                item = stream.next() => {
                    match item {
                        Some(Ok(chunk)) => {
                            if chunk.is_empty() { continue; }
                            total += chunk.len();
                            tick_bytes += chunk.len();
                            last_data = Instant::now();
                            if total >= limit { break; }
                        }
                        Some(Err(err)) => return Err(err.into()),
                        None => break,
                    }
                }
                _ = sleep(Duration::from_secs(1)) => {
                    let sec_bytes = std::mem::take(&mut tick_bytes);
                    peak = peak.max(sec_bytes);
                    let avg = total as f64 / start.elapsed().as_secs_f64().max(0.001);
                    let _ = writer.progress("telegram", "download", &format!("download {} avg {}", fmt_size(total as u64), fmt_speed(avg)), json!({"phase":"download", "bytes": total, "avg_bps": avg, "peak_bps": peak}));
                    if last_data.elapsed() >= Duration::from_millis(STALL_TIMEOUT_MS) {
                        drop_at_sec = Some(start.elapsed().as_secs());
                        break;
                    }
                    if start.elapsed() >= Duration::from_millis(TOTAL_TIMEOUT_MS) { break; }
                }
            }
        }
        Ok(())
    }.await;

    let elapsed = start.elapsed().as_secs_f64();
    let avg = total as f64 / elapsed.max(0.001);
    let status = if total == 0 {
        "blocked"
    } else if total >= limit * 95 / 100 {
        "ok"
    } else if drop_at_sec.is_some() {
        "stalled"
    } else if result.is_err() || start.elapsed() >= Duration::from_millis(TOTAL_TIMEOUT_MS) {
        "slow"
    } else {
        "slow"
    };
    let detail = match status {
        "ok" => format!("Telegram download ok: {} in {:.1}s avg {}", fmt_size(total as u64), elapsed, fmt_speed(avg)),
        "stalled" => format!("Telegram download stalled after {}s: {} avg {}", drop_at_sec.unwrap_or_default(), fmt_size(total as u64), fmt_speed(avg)),
        _ => format!("Telegram download {status}: {} in {:.1}s avg {}", fmt_size(total as u64), elapsed, fmt_speed(avg)),
    };
    let probe_status = match status { "ok" => "available", "slow" | "stalled" => "suspicious", other => other };
    let _ = writer.probe("telegram", "telegram-download", "Telegram download", TELEGRAM_MEDIA_URL, probe_status, &detail, json!({"phase":"download", "bytes": total, "avg_bps": avg, "peak_bps": peak, "drop_at_sec": drop_at_sec, "size_label": fmt_size(limit as u64)}));
    let _ = writer.progress("telegram", status, &detail, json!({"phase":"download", "bytes": total, "avg_bps": avg, "peak_bps": peak, "drop_at_sec": drop_at_sec, "size_label": fmt_size(limit as u64)}));
    json!({"status": status, "bytes": total, "duration": elapsed, "avg_bps": avg, "peak_bps": peak, "drop_at_sec": drop_at_sec})
}

async fn telegram_upload_probe<W: EventWriter + Send>(writer: &mut W, quick: bool, proxy: Option<&str>) -> Value {
    let size = if quick { 1024 * 1024 } else { TELEGRAM_UPLOAD_SIZE };
    let _ = writer.probe("telegram", "telegram-upload", "Telegram upload", TELEGRAM_UPLOAD_IP, "checking", "uploading Telegram media probe payload", json!({"phase":"upload", "bytes": size, "size_label": fmt_size(size as u64)}));

    let sent = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let sent_stream = sent.clone();
    let stop_stream = stop.clone();
    let chunk_size = 16 * 1024usize;
    let stream = futures_util::stream::unfold(0usize, move |offset| {
        let sent_stream = sent_stream.clone();
        let stop_stream = stop_stream.clone();
        async move {
            if offset >= size || stop_stream.load(std::sync::atomic::Ordering::Relaxed) {
                None
            } else {
                let remaining = size - offset;
                let len = remaining.min(chunk_size);
                sent_stream.fetch_add(len, std::sync::atomic::Ordering::Relaxed);
                tokio::task::yield_now().await;
                Some((Ok::<bytes::Bytes, std::io::Error>(bytes::Bytes::from(vec![0u8; len])), offset + len))
            }
        }
    });

    let client = http_client(Duration::from_millis(TOTAL_TIMEOUT_MS + 5000), true, proxy);
    let body = reqwest::Body::wrap_stream(stream);
    let url = format!("https://{TELEGRAM_UPLOAD_IP}:443/upload");
    let start = Instant::now();
    let mut peak_bps = 0.0f64;
    let mut last_data = Instant::now();
    let mut last_sent = 0usize;
    let mut drop_at_sec: Option<u64> = None;

    let mut post_handle = tokio::spawn(async move {
        client.post(url).header("Host", "telegram.org").body(body).send().await
    });

    loop {
        tokio::select! {
            _res = &mut post_handle => {
                break;
            }
            _ = sleep(Duration::from_millis(500)) => {
                let now_sent = sent.load(std::sync::atomic::Ordering::Relaxed);
                let delta = now_sent.saturating_sub(last_sent);
                last_sent = now_sent;
                if delta > 0 {
                    last_data = Instant::now();
                }
                let current_bps = delta as f64 * 2.0;
                peak_bps = peak_bps.max(current_bps);
                let avg = now_sent as f64 / start.elapsed().as_secs_f64().max(0.001);
                let _ = writer.progress("telegram", "upload", &format!("upload {} avg {}", fmt_size(now_sent as u64), fmt_speed(avg)), json!({"phase":"upload", "bytes": now_sent, "avg_bps": avg, "peak_bps": peak_bps}));
                if start.elapsed() >= Duration::from_millis(TOTAL_TIMEOUT_MS) {
                    stop.store(true, std::sync::atomic::Ordering::Relaxed);
                    break;
                }
                if last_data.elapsed() >= Duration::from_millis(STALL_TIMEOUT_MS) {
                    drop_at_sec = Some(start.elapsed().as_secs());
                    stop.store(true, std::sync::atomic::Ordering::Relaxed);
                    break;
                }
                if now_sent >= size { break; }
            }
        }
    }
    stop.store(true, std::sync::atomic::Ordering::Relaxed);
    if !post_handle.is_finished() {
        post_handle.abort();
    }

    let sent_total = sent.load(std::sync::atomic::Ordering::Relaxed);
    let elapsed = start.elapsed().as_secs_f64();
    let avg = sent_total as f64 / elapsed.max(0.001);
    let fully = sent_total >= size * 98 / 100;
    let status = if sent_total == 0 {
        "blocked"
    } else if fully {
        "ok"
    } else if drop_at_sec.is_some() {
        "stalled"
    } else {
        "slow"
    };
    let detail = match status {
        "ok" => format!("Telegram upload ok: {} in {:.1}s avg {}", fmt_size(sent_total as u64), elapsed, fmt_speed(avg)),
        "stalled" => format!("Telegram upload stalled after {}s: {} avg {}", drop_at_sec.unwrap_or_default(), fmt_size(sent_total as u64), fmt_speed(avg)),
        _ => format!("Telegram upload {status}: {} in {:.1}s avg {}", fmt_size(sent_total as u64), elapsed, fmt_speed(avg)),
    };
    let probe_status = match status { "ok" => "available", "slow" | "stalled" => "suspicious", other => other };
    let _ = writer.probe("telegram", "telegram-upload", "Telegram upload", TELEGRAM_UPLOAD_IP, probe_status, &detail, json!({"phase":"upload", "bytes": sent_total, "avg_bps": avg, "peak_bps": peak_bps, "drop_at_sec": drop_at_sec, "size_label": fmt_size(size as u64)}));
    let _ = writer.progress("telegram", status, &detail, json!({"phase":"upload", "bytes": sent_total, "avg_bps": avg, "peak_bps": peak_bps, "drop_at_sec": drop_at_sec, "size_label": fmt_size(size as u64)}));
    json!({"status": status, "bytes": sent_total, "duration": elapsed, "avg_bps": avg, "peak_bps": peak_bps, "drop_at_sec": drop_at_sec})
}
