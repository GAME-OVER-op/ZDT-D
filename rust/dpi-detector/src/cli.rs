async fn run(args: Vec<String>) -> Result<()> {
    if args.is_empty() || args.iter().any(|arg| arg == "-h" || arg == "--help") {
        print_help();
        return Ok(());
    }

    match args[0].as_str() {
        "--version" | "version" => {
            println!("dpi-detector {VERSION}");
            Ok(())
        }
        "self-test" => self_test(),
        "list-tests" => list_tests(),
        "run" => run_scan(parse_run_options(&args[1..])?).await,
        other => Err(anyhow!("unknown command: {other}\n\nUse: dpi-detector --help")),
    }
}

fn parse_run_options(args: &[String]) -> Result<RunOptions> {
    let default_tests = test_catalog()
        .iter()
        .map(|(id, _)| id.to_string())
        .collect();
    let mut options = RunOptions {
        format: OutputFormat::Text,
        tests: default_tests,
        timeout_ms: DNS_TIMEOUT_MS,
        quick: false,
        max_domains: 40,
        max_tcp_targets: 40,
        max_sni: 25,
        domains_override: Vec::new(),
        proxy: None,
        concurrency: 100,
    };
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--format" => {
                let value = args.get(i + 1).context("--format requires value: text or ndjson")?;
                options.format = parse_format_value(value)?;
                i += 2;
            }
            value if value.starts_with("--format=") => {
                options.format = parse_format_value(value.trim_start_matches("--format="))?;
                i += 1;
            }
            "--tests" => {
                let value = args.get(i + 1).context("--tests requires a comma-separated value")?;
                options.tests = parse_tests(value);
                i += 2;
            }
            value if value.starts_with("--tests=") => {
                options.tests = parse_tests(value.trim_start_matches("--tests="));
                i += 1;
            }
            "--timeout" => {
                let value = args.get(i + 1).context("--timeout requires milliseconds")?;
                options.timeout_ms = value.parse().context("invalid --timeout value")?;
                i += 2;
            }
            value if value.starts_with("--timeout=") => {
                options.timeout_ms = value.trim_start_matches("--timeout=").parse().context("invalid --timeout value")?;
                i += 1;
            }
            "--quick" => {
                options.quick = true;
                options.max_domains = 12;
                options.max_tcp_targets = 12;
                options.max_sni = 8;
                i += 1;
            }
            "--max-domains" => {
                let value = args.get(i + 1).context("--max-domains requires number")?;
                options.max_domains = value.parse().context("invalid --max-domains")?;
                i += 2;
            }
            "--max-tcp-targets" => {
                let value = args.get(i + 1).context("--max-tcp-targets requires number")?;
                options.max_tcp_targets = value.parse().context("invalid --max-tcp-targets")?;
                i += 2;
            }
            "--max-sni" => {
                let value = args.get(i + 1).context("--max-sni requires number")?;
                options.max_sni = value.parse().context("invalid --max-sni")?;
                i += 2;
            }
            "--domain" => {
                let value = args.get(i + 1).context("--domain requires hostname")?;
                options.domains_override.push(value.trim().to_string());
                i += 2;
            }
            value if value.starts_with("--domain=") => {
                options.domains_override.push(value.trim_start_matches("--domain=").trim().to_string());
                i += 1;
            }
            "--proxy" => {
                let value = args.get(i + 1).context("--proxy requires URL")?;
                options.proxy = Some(value.trim().to_string());
                i += 2;
            }
            value if value.starts_with("--proxy=") => {
                options.proxy = Some(value.trim_start_matches("--proxy=").trim().to_string());
                i += 1;
            }
            "--concurrency" => {
                let value = args.get(i + 1).context("--concurrency requires number")?;
                options.concurrency = value.parse().context("invalid --concurrency")?;
                i += 2;
            }
            value if value.starts_with("--concurrency=") => {
                options.concurrency = value.trim_start_matches("--concurrency=").parse().context("invalid --concurrency")?;
                i += 1;
            }
            other => return Err(anyhow!("unknown run option: {other}")),
        }
    }
    Ok(options)
}

fn parse_format_value(value: &str) -> Result<OutputFormat> {
    match value {
        "text" => Ok(OutputFormat::Text),
        "ndjson" | "jsonl" => Ok(OutputFormat::Ndjson),
        other => Err(anyhow!("unsupported format: {other}")),
    }
}

fn parse_tests(value: &str) -> HashSet<String> {
    value
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .collect()
}

fn self_test() -> Result<()> {
    println!("dpi-detector {VERSION}");
    println!("status: ok");
    println!("tests registered: 6");
    for (id, title) in test_catalog() {
        println!("- {title} ({id})");
    }
    Ok(())
}

fn list_tests() -> Result<()> {
    for (id, title) in test_catalog() {
        println!("{id}\t{title}");
    }
    Ok(())
}

fn test_catalog() -> Vec<(&'static str, &'static str)> {
    vec![
        ("dns_integrity", "DNS interception / substitution"),
        ("dns_availability", "DNS server availability"),
        ("domains", "Domain DNS/TLS/HTTP availability"),
        ("tcp16", "TCP 12-64KB payload threshold"),
        ("whitelist_sni", "Whitelist SNI probing"),
        ("telegram", "Telegram DC/download/upload"),
    ]
}
