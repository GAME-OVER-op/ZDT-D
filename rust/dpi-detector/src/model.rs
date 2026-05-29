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

    fn wants_no_sni(&self) -> bool {
        matches!(self.sni.as_deref(), Some(s) if s.is_empty())
    }

    fn host_sni(&self) -> String {
        if self.wants_no_sni() {
            self.ip.clone()
        } else {
            self.sni
                .as_ref()
                .filter(|s| !s.trim().is_empty())
                .cloned()
                .unwrap_or_else(|| "example.com".to_string())
        }
    }

    fn normalized_asn(&self) -> String {
        let raw = self.asn.as_deref().unwrap_or("").trim();
        if raw.is_empty() {
            return self.ip.clone();
        }
        let cleaned: String = raw.chars().filter(|ch| ch.is_ascii_alphanumeric()).collect();
        let upper = cleaned.to_uppercase();
        if upper.is_empty() {
            self.ip.clone()
        } else {
            upper.trim_start_matches("AS").to_string()
        }
    }

    fn asn_label(&self) -> String {
        let key = self.normalized_asn();
        if key == self.ip { "-".to_string() } else { format!("AS{key}") }
    }
}
