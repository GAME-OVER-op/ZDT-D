async fn run_scan(options: RunOptions) -> Result<()> {
    match options.format {
        OutputFormat::Text => run_scan_text(options).await,
        OutputFormat::Ndjson => run_scan_ndjson(options).await,
    }
}

async fn run_scan_text(options: RunOptions) -> Result<()> {
    let mut writer = TextWriter;
    let summaries = execute_scan(&options, &mut writer).await?;
    println!("\nSummary:");
    for summary in summaries {
        println!("- {}: {} — {}", summary.title, summary.status, summary.detail);
    }
    Ok(())
}

async fn run_scan_ndjson(options: RunOptions) -> Result<()> {
    let mut writer = NdjsonWriter::new();
    writer.meta("dpi_detector", "ok", json!({"version": VERSION, "protocol": 4, "features": ["probe_technical", "planned_totals", "grouped_domain_checks", "diagnosis"]}))?;
    let started_at = unix_ms();
    let summaries = execute_scan(&options, &mut writer).await?;
    let risk = overall_risk(&summaries);
    writer.finished("summary", &risk, json!({
        "version": VERSION,
        "duration_ms": unix_ms().saturating_sub(started_at),
        "risk": risk,
        "tests": summaries,
    }))?;
    Ok(())
}

async fn execute_scan<W: EventWriter + Send>(options: &RunOptions, writer: &mut W) -> Result<Vec<TestSummary>> {
    let mut summaries = Vec::new();

    if options.tests.contains("dns_integrity") || options.tests.contains("dns") || options.tests.contains("all") {
        summaries.push(check_dns_integrity(options, writer).await);
    }
    if options.tests.contains("dns_availability") || options.tests.contains("dns") || options.tests.contains("all") {
        summaries.push(check_dns_availability(options, writer).await);
    }
    if options.tests.contains("domains") || options.tests.contains("all") {
        summaries.push(check_domains(options, writer).await);
    }
    if options.tests.contains("tcp16") || options.tests.contains("tcp") || options.tests.contains("all") {
        summaries.push(check_tcp16(options, writer).await);
    }
    if options.tests.contains("whitelist_sni") || options.tests.contains("sni") || options.tests.contains("all") {
        summaries.push(check_whitelist_sni(options, writer).await);
    }
    if options.tests.contains("telegram") || options.tests.contains("all") {
        summaries.push(check_telegram(options, writer).await);
    }

    Ok(summaries)
}

trait EventWriter {
    fn meta(&mut self, test: &str, status: &str, data: Value) -> Result<()>;
    fn started(&mut self, test: &str, title: &str, data: Value) -> Result<()>;
    fn probe(&mut self, test: &str, key: &str, name: &str, target: &str, status: &str, detail: &str, data: Value) -> Result<()>;
    fn progress(&mut self, test: &str, status: &str, detail: &str, data: Value) -> Result<()>;
    fn result(&mut self, test: &str, status: &str, detail: &str, data: Value) -> Result<()>;
    fn finished(&mut self, test: &str, status: &str, data: Value) -> Result<()>;
}

struct TextWriter;
impl EventWriter for TextWriter {
    fn meta(&mut self, _test: &str, _status: &str, _data: Value) -> Result<()> { Ok(()) }
    fn started(&mut self, _test: &str, title: &str, _data: Value) -> Result<()> {
        println!("\n== {title} ==");
        Ok(())
    }
    fn probe(&mut self, _test: &str, _key: &str, name: &str, target: &str, status: &str, detail: &str, _data: Value) -> Result<()> {
        let suffix = if target.is_empty() { String::new() } else { format!(" ({target})") };
        println!("[{status}] {name}{suffix}: {detail}");
        Ok(())
    }
    fn progress(&mut self, _test: &str, status: &str, detail: &str, _data: Value) -> Result<()> {
        println!("[{status}] {detail}");
        Ok(())
    }
    fn result(&mut self, _test: &str, status: &str, detail: &str, _data: Value) -> Result<()> {
        println!("=> {status}: {detail}");
        Ok(())
    }
    fn finished(&mut self, _test: &str, _status: &str, _data: Value) -> Result<()> { Ok(()) }
}

struct NdjsonWriter {
    out: io::BufWriter<io::Stdout>,
    seq: u64,
}
impl NdjsonWriter {
    fn new() -> Self {
        Self { out: io::BufWriter::new(io::stdout()), seq: 0 }
    }
    fn write(&mut self, event_type: &str, test: &str, status: &str, detail: &str, data: Value) -> Result<()> {
        self.seq += 1;
        let event = json!({
            "type": event_type,
            "test": test,
            "status": status,
            "detail": detail,
            "data": data,
            "ts": unix_ms(),
            "seq": self.seq,
        });
        self.write_json(event)
    }
    fn write_probe(&mut self, test: &str, key: &str, name: &str, target: &str, status: &str, detail: &str, data: Value) -> Result<()> {
        self.seq += 1;
        let size_label = data.get("size_label").and_then(Value::as_str).unwrap_or("");
        let technical = technical_from_probe_data(&data);
        let checks = data.get("checks").cloned().unwrap_or_else(|| json!([]));
        let diagnosis = data.get("diagnosis").and_then(Value::as_str).unwrap_or("");
        let event = json!({
            "type": "probe",
            "test": test,
            "stage": test,
            "key": key,
            "name": name,
            "target": target,
            "status": status,
            "detail": detail,
            "size_label": size_label,
            "technical": technical,
            "checks": checks,
            "diagnosis": diagnosis,
            "data": data,
            "ts": unix_ms(),
            "seq": self.seq,
        });
        self.write_json(event)
    }
    fn write_json(&mut self, event: Value) -> Result<()> {
        writeln!(self.out, "{}", serde_json::to_string(&event)?)?;
        self.out.flush()?;
        Ok(())
    }
}
impl EventWriter for NdjsonWriter {
    fn meta(&mut self, test: &str, status: &str, data: Value) -> Result<()> { self.write("meta", test, status, "", data) }
    fn started(&mut self, test: &str, title: &str, data: Value) -> Result<()> { self.write("started", test, "running", title, data) }
    fn probe(&mut self, test: &str, key: &str, name: &str, target: &str, status: &str, detail: &str, data: Value) -> Result<()> { self.write_probe(test, key, name, target, status, detail, data) }
    fn progress(&mut self, test: &str, status: &str, detail: &str, data: Value) -> Result<()> { self.write("progress", test, status, detail, data) }
    fn result(&mut self, test: &str, status: &str, detail: &str, data: Value) -> Result<()> { self.write("result", test, status, detail, data) }
    fn finished(&mut self, test: &str, status: &str, data: Value) -> Result<()> { self.write("finished", test, status, "", data) }
}

fn technical_from_probe_data(data: &Value) -> Value {
    if let Some(value) = data.get("technical") {
        if value.is_object() {
            return value.clone();
        }
    }

    let mut technical = serde_json::Map::new();
    if let Some(object) = data.as_object() {
        for (key, value) in object {
            if key == "technical" || key == "items" || key == "size_label" {
                continue;
            }
            let rendered = match value {
                Value::Null => String::new(),
                Value::String(s) => s.clone(),
                Value::Number(n) => n.to_string(),
                Value::Bool(b) => b.to_string(),
                Value::Array(items) => items.iter().map(compact_json_value).collect::<Vec<_>>().join(", "),
                Value::Object(_) => compact_json_value(value),
            };
            if !rendered.is_empty() {
                technical.insert(key.clone(), Value::String(rendered));
            }
        }
    }
    Value::Object(technical)
}

fn compact_json_value(value: &Value) -> String {
    match value {
        Value::Null => String::new(),
        Value::String(s) => s.clone(),
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        Value::Array(_) | Value::Object(_) => serde_json::to_string(value).unwrap_or_default(),
    }
}

fn unix_ms() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or_default()
}

fn overall_risk(summaries: &[TestSummary]) -> String {
    if summaries.iter().any(|s| s.risk == "high") { "high".to_string() }
    else if summaries.iter().any(|s| s.risk == "medium") { "medium".to_string() }
    else if summaries.iter().any(|s| s.risk == "low") { "low".to_string() }
    else { "unknown".to_string() }
}

fn summary(id: &str, title: &str, status: &str, detail: impl Into<String>, risk: &str) -> TestSummary {
    TestSummary { id: id.to_string(), title: title.to_string(), status: status.to_string(), detail: detail.into(), risk: risk.to_string() }
}
