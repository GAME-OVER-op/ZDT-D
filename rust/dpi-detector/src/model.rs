#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OutputFormat {
    Text,
    Ndjson,
}

#[derive(Debug, Clone)]
struct RunOptions {
    format: OutputFormat,
    tests: HashSet<String>,
    timeout_ms: u64,
    quick: bool,
    max_domains: usize,
    max_tcp_targets: usize,
    max_sni: usize,
    domains_override: Vec<String>,
    proxy: Option<String>,
    concurrency: usize,
}

#[derive(Debug, Clone, Serialize)]
struct TestSummary {
    id: String,
    title: String,
    status: String,
    detail: String,
    risk: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct TcpTarget {
    id: String,
    asn: Option<String>,
    provider: String,
    ip: String,
    #[serde(default)]
    port: Option<u16>,
    #[serde(rename = ",port", default)]
    comma_port: Option<u16>,
    #[serde(default)]
    sni: Option<String>,
}

impl TcpTarget {
    fn port(&self) -> u16 {
        self.port.or(self.comma_port).unwrap_or(443)
    }

    fn host_sni(&self) -> String {
        // Returns configured SNI name, filtering empty strings.
        // Falls back to "example.com" which is a safe placeholder
        // for TLS connections when no explicit SNI is provided.
        self.sni
            .as_ref()
            .filter(|s| !s.trim().is_empty())
            .cloned()
            .unwrap_or_else(|| "example.com".to_string())
    }
}
