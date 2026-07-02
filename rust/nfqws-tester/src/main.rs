use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::env;
use std::fs;
use std::fs::File;
use std::io::{self, Write};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode, Stdio};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const VERSION: &str = env!("CARGO_PKG_VERSION");
const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const STRATEGIC_DIR: &str = "/data/adb/modules/ZDT-D/strategic/strategicvar";
const WORK_DIR: &str = "/data/adb/modules/ZDT-D/working_folder/nfqws_tester";
const SESSION_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/nfqws_tester/session.json";
const SETTING_DIR: &str = "/data/adb/modules/ZDT-D/setting";
const MULTIPORT_NO_FILE: &str = "multiport_no";
const DEFAULT_QNUM: u16 = 200;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct SessionState {
    program: String,
    config_path: String,
    config_name: String,
    pid: u32,
    qnum: u16,
    started_at_unix_ms: u64,
}

#[derive(Debug, Clone)]
struct StartOptions {
    program: String,
    config_path: String,
    qnum: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PortRange {
    start: u16,
    end: u16,
}

#[derive(Debug, Clone, Default)]
struct ProtoPortFilter {
    tcp: Vec<PortRange>,
    udp: Vec<PortRange>,
}

impl ProtoPortFilter {
    fn is_empty(&self) -> bool {
        self.tcp.is_empty() && self.udp.is_empty()
    }
}

fn main() -> ExitCode {
    match entry() {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            let _ = writeln!(io::stderr(), "{err:#}");
            ExitCode::from(2)
        }
    }
}

fn entry() -> Result<()> {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.is_empty() || args.iter().any(|it| it == "-h" || it == "--help") {
        print_help();
        return Ok(());
    }

    match args[0].as_str() {
        "--version" | "version" => {
            println!("nfqws-tester {VERSION}");
            Ok(())
        }
        "list" => {
            let program = parse_named_value(&args[1..], "--program")?;
            list_strategies(&normalize_program(&program)?)
        }
        "start" => {
            let options = parse_start_options(&args[1..])?;
            start_strategy(options)
        }
        "stop" | "cleanup" => stop_session(),
        "status" => print_status(),
        "usage" => {
            let pid_raw = parse_named_value(&args[1..], "--pid")?;
            let pid = pid_raw.parse::<u32>().context("invalid --pid")?;
            print_usage(pid)
        }
        other => Err(anyhow!("unknown command: {other}")),
    }
}

fn print_help() {
    println!("nfqws-tester {VERSION}");
    println!("Usage:");
    println!("  nfqws-tester --version");
    println!("  nfqws-tester list --program nfqws|nfqws2");
    println!("  nfqws-tester start --program nfqws|nfqws2 --config /path/to/file.txt [--qnum 200]");
    println!("  nfqws-tester stop");
    println!("  nfqws-tester status");
    println!("  nfqws-tester usage --pid 1234");
}

fn parse_named_value(args: &[String], key: &str) -> Result<String> {
    let mut i = 0usize;
    while i < args.len() {
        let arg = &args[i];
        if arg == key {
            return args.get(i + 1).cloned().with_context(|| format!("{key} requires value"));
        }
        let prefix = format!("{key}=");
        if let Some(v) = arg.strip_prefix(&prefix) {
            return Ok(v.to_string());
        }
        i += 1;
    }
    bail!("missing required option: {key}")
}

fn parse_start_options(args: &[String]) -> Result<StartOptions> {
    let program = normalize_program(&parse_named_value(args, "--program")?)?;
    let config_path = parse_named_value(args, "--config")?;
    let qnum = match parse_named_value(args, "--qnum") {
        Ok(v) => v.parse::<u16>().context("invalid --qnum")?,
        Err(_) => DEFAULT_QNUM,
    };
    Ok(StartOptions { program, config_path, qnum })
}

fn normalize_program(program: &str) -> Result<String> {
    let p = program.trim().to_lowercase();
    match p.as_str() {
        "nfqws" | "nfqws2" => Ok(p),
        _ => bail!("unsupported program: {program}"),
    }
}

fn list_strategies(program: &str) -> Result<()> {
    let dir = strategic_dir(program);
    let mut items: Vec<String> = Vec::new();
    if dir.is_dir() {
        for entry in fs::read_dir(&dir).with_context(|| format!("read {}", dir.display()))? {
            let entry = entry?;
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else { continue; };
            if !name.ends_with(".txt") {
                continue;
            }
            items.push(name.to_string());
        }
    }
    items.sort();
    println!("{}", json!({"ok": true, "program": program, "dir": dir, "strategies": items}));
    Ok(())
}

fn start_strategy(options: StartOptions) -> Result<()> {
    let bin = program_bin(&options.program);
    if !bin.is_file() {
        bail!("binary not found: {}", bin.display());
    }
    let config_path = PathBuf::from(&options.config_path);
    if !config_path.is_file() {
        bail!("config not found: {}", config_path.display());
    }

    ensure_work_dir()?;
    cleanup_all()?;

    let raw = fs::read_to_string(&config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let args = normalize_config_args(&raw);
    let filter = extract_proto_port_filter(&raw);
    let pid = spawn_program(&options.program, &bin, config_path.parent().unwrap_or(Path::new("/")), options.qnum, &args)?;
    if let Err(err) = apply_nfqueue_rules(&options.program, options.qnum, &filter) {
        let _ = kill_program(&options.program);
        let _ = cleanup_rules_for_program(&options.program);
        return Err(err);
    }

    let state = SessionState {
        program: options.program.clone(),
        config_path: config_path.display().to_string(),
        config_name: config_path.file_name().and_then(|s| s.to_str()).unwrap_or_default().to_string(),
        pid,
        qnum: options.qnum,
        started_at_unix_ms: now_unix_ms(),
    };
    write_session(&state)?;

    println!("{}", json!({
        "ok": true,
        "program": state.program,
        "config_path": state.config_path,
        "config_name": state.config_name,
        "pid": state.pid,
        "qnum": state.qnum,
        "filter": {
            "tcp": format_ranges(&filter.tcp),
            "udp": format_ranges(&filter.udp),
        }
    }));
    Ok(())
}

fn stop_session() -> Result<()> {
    cleanup_all()?;
    println!("{}", json!({"ok": true, "active": false}));
    Ok(())
}

fn print_status() -> Result<()> {
    let session = read_session();
    let (active, state) = match session {
        Ok(Some(state)) => {
            let running = process_alive(state.pid);
            (running, Some(state))
        }
        Ok(None) => (false, None),
        Err(err) => return Err(err),
    };
    let response = if let Some(state) = state {
        json!({
            "ok": true,
            "active": active,
            "program": state.program,
            "config_path": state.config_path,
            "config_name": state.config_name,
            "pid": state.pid,
            "qnum": state.qnum,
            "started_at_unix_ms": state.started_at_unix_ms,
        })
    } else {
        json!({"ok": true, "active": false})
    };
    println!("{response}");
    Ok(())
}

fn print_usage(pid: u32) -> Result<()> {
    let proc_path = PathBuf::from("/proc").join(pid.to_string());
    if !proc_path.is_dir() {
        println!("{}", json!({"ok": true, "active": false, "pid": pid, "cpu_percent": 0.0, "rss_mb": 0.0}));
        return Ok(());
    }
    let out = capture(&format!("ps -o pid,%cpu,rss -p {pid}"))?;
    let mut cpu_percent = 0.0f32;
    let mut rss_mb = 0.0f32;
    for line in out.lines().skip(1) {
        let cols: Vec<&str> = line.split_whitespace().collect();
        if cols.len() < 3 {
            continue;
        }
        if cols[0].parse::<u32>().ok() != Some(pid) {
            continue;
        }
        cpu_percent = cols[1].parse::<f32>().unwrap_or(0.0);
        rss_mb = cols[2].parse::<f32>().unwrap_or(0.0) / 1024.0;
    }
    println!("{}", json!({"ok": true, "active": true, "pid": pid, "cpu_percent": cpu_percent, "rss_mb": rss_mb}));
    Ok(())
}

fn program_bin(program: &str) -> PathBuf {
    PathBuf::from(MODULE_DIR).join("bin").join(program)
}

fn strategic_dir(program: &str) -> PathBuf {
    PathBuf::from(STRATEGIC_DIR).join(program)
}

fn ensure_work_dir() -> Result<()> {
    fs::create_dir_all(WORK_DIR).with_context(|| format!("create {WORK_DIR}"))?;
    Ok(())
}

fn write_session(state: &SessionState) -> Result<()> {
    ensure_work_dir()?;
    let text = serde_json::to_string_pretty(state)?;
    fs::write(SESSION_FILE, text).with_context(|| format!("write {SESSION_FILE}"))?;
    Ok(())
}

fn read_session() -> Result<Option<SessionState>> {
    let path = Path::new(SESSION_FILE);
    if !path.is_file() {
        return Ok(None);
    }
    let raw = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let state = serde_json::from_str::<SessionState>(&raw).with_context(|| format!("parse {}", path.display()))?;
    Ok(Some(state))
}

fn cleanup_all() -> Result<()> {
    kill_program("nfqws")?;
    kill_program("nfqws2")?;
    cleanup_rules_for_program("nfqws")?;
    cleanup_rules_for_program("nfqws2")?;
    let _ = fs::remove_file(SESSION_FILE);
    Ok(())
}

fn kill_program(program: &str) -> Result<()> {
    let _ = run("sh", &["-c", &format!("pkill -9 -x {program} 2>/dev/null || true")])?;
    Ok(())
}

fn chain_name(program: &str) -> &'static str {
    match program {
        "nfqws" => "ZDTNFTST1",
        _ => "ZDTNFTST2",
    }
}

fn apply_nfqueue_rules(program: &str, queue: u16, filter: &ProtoPortFilter) -> Result<()> {
    let chain = chain_name(program);
    apply_nfqueue_rules_family("iptables", chain, queue, filter)?;
    if command_exists("ip6tables") {
        let _ = apply_nfqueue_rules_family("ip6tables", chain, queue, filter);
    }
    Ok(())
}

fn cleanup_rules_for_program(program: &str) -> Result<()> {
    let chain = chain_name(program);
    cleanup_family("iptables", chain)?;
    if command_exists("ip6tables") {
        let _ = cleanup_family("ip6tables", chain);
    }
    Ok(())
}

fn cleanup_family(cmd: &str, chain: &str) -> Result<()> {
    loop {
        let rc = run(cmd, &["-w", "5", "-t", "mangle", "-D", "OUTPUT", "-j", chain])?.0;
        if rc != 0 {
            break;
        }
    }
    let _ = run(cmd, &["-w", "5", "-t", "mangle", "-F", chain]);
    let _ = run(cmd, &["-w", "5", "-t", "mangle", "-X", chain]);
    Ok(())
}

fn apply_nfqueue_rules_family(cmd: &str, chain: &str, queue: u16, filter: &ProtoPortFilter) -> Result<()> {
    cleanup_family(cmd, chain)?;
    let _ = run(cmd, &["-w", "5", "-t", "mangle", "-N", chain]);
    let (rc, out) = run(cmd, &["-w", "5", "-t", "mangle", "-A", "OUTPUT", "-j", chain])?;
    if rc != 0 {
        bail!("{cmd} add OUTPUT jump failed: {out}");
    }
    if filter.is_empty() {
        let (rc, out) = run(
            cmd,
            &["-w", "5", "-t", "mangle", "-A", chain, "-j", "NFQUEUE", "--queue-num", &queue.to_string(), "--queue-bypass"],
        )?;
        if rc != 0 {
            bail!("{cmd} add NFQUEUE rule failed: {out}");
        }
        return Ok(());
    }

    if multiport_disabled_by_flag() {
        add_filter_rules_per_port(cmd, chain, queue, filter)?;
        return Ok(());
    }

    if let Err(err) = add_filter_rules_multiport(cmd, chain, queue, filter) {
        disable_multiport_persistently(&format!("nfqws-tester {cmd} multiport failed: {err:#}"));
        cleanup_family(cmd, chain)?;
        let _ = run(cmd, &["-w", "5", "-t", "mangle", "-N", chain]);
        let (rc, out) = run(cmd, &["-w", "5", "-t", "mangle", "-A", "OUTPUT", "-j", chain])?;
        if rc != 0 {
            bail!("{cmd} add OUTPUT jump after multiport fallback failed: {out}");
        }
        add_filter_rules_per_port(cmd, chain, queue, filter)?;
    }
    Ok(())
}

fn multiport_no_path() -> PathBuf {
    Path::new(SETTING_DIR).join(MULTIPORT_NO_FILE)
}

fn multiport_disabled_by_flag() -> bool {
    multiport_no_path().is_file()
}

fn disable_multiport_persistently(reason: &str) {
    let path = multiport_no_path();
    if let Some(parent) = path.parent() {
        if let Err(err) = fs::create_dir_all(parent) {
            let _ = writeln!(io::stderr(), "nfqws-tester: multiport disabled in memory, but setting dir create failed: {err:#}");
            return;
        }
    }
    let body = format!("disabled_by=nfqws-tester\nreason={}\n", reason.trim());
    if let Err(err) = fs::write(&path, body) {
        let _ = writeln!(io::stderr(), "nfqws-tester: failed to write {}: {err:#}", path.display());
    }
}

fn add_filter_rules_multiport(cmd: &str, chain: &str, queue: u16, filter: &ProtoPortFilter) -> Result<()> {
    add_protocol_rules_multiport(cmd, chain, queue, "tcp", &filter.tcp)?;
    add_protocol_rules_multiport(cmd, chain, queue, "udp", &filter.udp)?;
    Ok(())
}

fn add_filter_rules_per_port(cmd: &str, chain: &str, queue: u16, filter: &ProtoPortFilter) -> Result<()> {
    add_protocol_rules_per_port(cmd, chain, queue, "tcp", &filter.tcp)?;
    add_protocol_rules_per_port(cmd, chain, queue, "udp", &filter.udp)?;
    Ok(())
}

fn add_protocol_rules_multiport(cmd: &str, chain: &str, queue: u16, proto: &str, ranges: &[PortRange]) -> Result<()> {
    if ranges.is_empty() {
        return Ok(());
    }
    for chunk in chunk_multiport(&to_multiport_elements(ranges), 15) {
        let csv = chunk.join(",");
        let (rc, out) = run(
            cmd,
            &[
                "-w", "5", "-t", "mangle", "-A", chain,
                "-p", proto,
                "-m", "multiport",
                "--dports", &csv,
                "-j", "NFQUEUE",
                "--queue-num", &queue.to_string(),
                "--queue-bypass",
            ],
        )?;
        if rc != 0 {
            bail!("{cmd} add {proto} multiport NFQUEUE rule failed: {out}");
        }
    }
    Ok(())
}

fn add_protocol_rules_per_port(cmd: &str, chain: &str, queue: u16, proto: &str, ranges: &[PortRange]) -> Result<()> {
    if ranges.is_empty() {
        return Ok(());
    }
    for range in ranges {
        let dport = if range.start == range.end {
            range.start.to_string()
        } else {
            format!("{}:{}", range.start, range.end)
        };
        let (rc, out) = run(
            cmd,
            &[
                "-w", "5", "-t", "mangle", "-A", chain,
                "-p", proto,
                "--dport", &dport,
                "-j", "NFQUEUE",
                "--queue-num", &queue.to_string(),
                "--queue-bypass",
            ],
        )?;
        if rc != 0 {
            bail!("{cmd} add {proto} per-port NFQUEUE rule failed dport={dport}: {out}");
        }
    }
    Ok(())
}

fn spawn_program(program: &str, bin: &Path, cwd: &Path, qnum: u16, config_args: &[String]) -> Result<u32> {
    let devnull = File::options().read(true).write(true).open("/dev/null").context("open /dev/null")?;
    let devnull_err = devnull.try_clone().context("clone /dev/null")?;
    let mut cmd = Command::new(bin);
    cmd.current_dir(cwd)
        .arg("--uid=0:0")
        .arg(format!("--qnum={qnum}"))
        .args(config_args)
        .stdin(Stdio::null())
        .stdout(Stdio::from(devnull))
        .stderr(Stdio::from(devnull_err));
    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }
    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    let pid = child.id();
    thread::sleep(Duration::from_millis(200));
    if !process_alive(pid) {
        bail!("{program} exited immediately after start")
    }
    Ok(pid)
}

fn process_alive(pid: u32) -> bool {
    PathBuf::from("/proc").join(pid.to_string()).is_dir()
}

fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_else(|_| Duration::from_secs(0))
        .as_millis() as u64
}

fn run(cmd: &str, args: &[&str]) -> Result<(i32, String)> {
    let out = Command::new(cmd)
        .args(args)
        .output()
        .with_context(|| format!("run {cmd}"))?;
    let code = out.status.code().unwrap_or(1);
    let text = String::from_utf8_lossy(&out.stdout).to_string() + &String::from_utf8_lossy(&out.stderr);
    Ok((code, text))
}

fn capture(script: &str) -> Result<String> {
    let (code, out) = run("sh", &["-c", script])?;
    if code != 0 {
        bail!("command failed: {script}: {out}");
    }
    Ok(out)
}

fn command_exists(cmd: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {cmd} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn normalize_config_args(raw: &str) -> Vec<String> {
    let mut s = String::with_capacity(raw.len());
    let mut it = raw.chars().peekable();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.peek().copied() {
                Some('\n') => {
                    it.next();
                    continue;
                }
                Some('\r') => {
                    it.next();
                    if matches!(it.peek().copied(), Some('\n')) {
                        it.next();
                    }
                    continue;
                }
                _ => {}
            }
        }
        if c == '\n' || c == '\r' {
            s.push(' ');
        } else {
            s.push(c);
        }
    }
    s.split_whitespace()
        .filter(|token| *token != "\\")
        .map(|token| token.to_string())
        .collect()
}

fn extract_proto_port_filter(raw: &str) -> ProtoPortFilter {
    let mut tcp_specs = Vec::new();
    let mut udp_specs = Vec::new();
    let s = raw.replace("\\\r\n", "").replace("\\\n", "");
    for line in s.lines() {
        let mut l = line;
        if let Some(pos) = l.find('#') {
            l = &l[..pos];
        }
        let l = l.trim();
        if l.is_empty() {
            continue;
        }
        collect_specs_from_line(l, "--filter-tcp=", &mut tcp_specs);
        collect_specs_from_line(l, "--filter-udp=", &mut udp_specs);
    }
    ProtoPortFilter {
        tcp: parse_and_merge_specs(&tcp_specs),
        udp: parse_and_merge_specs(&udp_specs),
    }
}

fn collect_specs_from_line(line: &str, key: &str, out: &mut Vec<String>) {
    let mut start = 0usize;
    while let Some(rel) = line[start..].find(key) {
        let pos = start + rel + key.len();
        let tail = &line[pos..];
        let mut end = tail.len();
        for (i, ch) in tail.char_indices() {
            if ch.is_whitespace() {
                let rest_trim = tail[i..].trim_start();
                if rest_trim.starts_with("--") {
                    end = i;
                    break;
                }
            }
        }
        let val = tail[..end].trim();
        if !val.is_empty() {
            out.push(val.to_string());
        }
        start = pos;
    }
}

fn parse_and_merge_specs(specs: &[String]) -> Vec<PortRange> {
    let mut all = Vec::new();
    for spec in specs {
        all.extend(parse_ranges(spec));
    }
    merge_ranges(all)
}

fn parse_ranges(spec: &str) -> Vec<PortRange> {
    let mut out = Vec::new();
    let cleaned = spec.replace(' ', "").replace('\t', "");
    for token in cleaned.split(',') {
        let t = token.trim();
        if t.is_empty() {
            continue;
        }
        if let Some((a, b)) = t.split_once('-').or_else(|| t.split_once(':')) {
            if let (Ok(sa), Ok(sb)) = (a.parse::<u16>(), b.parse::<u16>()) {
                if (1..=65535).contains(&sa) && (1..=65535).contains(&sb) {
                    let (start, end) = if sa <= sb { (sa, sb) } else { (sb, sa) };
                    out.push(PortRange { start, end });
                }
            }
            continue;
        }
        if let Ok(port) = t.parse::<u16>() {
            if (1..=65535).contains(&port) {
                out.push(PortRange { start: port, end: port });
            }
        }
    }
    out
}

fn merge_ranges(mut ranges: Vec<PortRange>) -> Vec<PortRange> {
    if ranges.is_empty() {
        return ranges;
    }
    ranges.sort_by_key(|r| (r.start, r.end));
    let mut merged = Vec::new();
    let mut cur = ranges[0];
    for r in ranges.into_iter().skip(1) {
        if r.start <= cur.end.saturating_add(1) {
            cur.end = cur.end.max(r.end);
        } else {
            merged.push(cur);
            cur = r;
        }
    }
    merged.push(cur);
    merged
}

fn to_multiport_elements(ranges: &[PortRange]) -> Vec<String> {
    ranges
        .iter()
        .map(|r| {
            if r.start == r.end {
                r.start.to_string()
            } else {
                format!("{}:{}", r.start, r.end)
            }
        })
        .collect()
}

fn chunk_multiport(elements: &[String], max_elems: usize) -> Vec<Vec<String>> {
    if elements.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < elements.len() {
        let end = (i + max_elems).min(elements.len());
        out.push(elements[i..end].to_vec());
        i = end;
    }
    out
}

fn format_ranges(ranges: &[PortRange]) -> String {
    ranges
        .iter()
        .map(|r| if r.start == r.end { r.start.to_string() } else { format!("{}-{}", r.start, r.end) })
        .collect::<Vec<_>>()
        .join(",")
}
