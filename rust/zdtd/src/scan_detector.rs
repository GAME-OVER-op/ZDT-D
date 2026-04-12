use std::{
    collections::{BTreeSet, HashMap, VecDeque},
    sync::{atomic::{AtomicBool, Ordering}, Mutex, OnceLock},
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

use anyhow::Result;

use crate::{
    android::notification,
    logging,
    proxyinfo,
    xtables_lock,
};

const CHAIN_NAME: &str = "ZDT_PROXYINFO";
const IPTABLES_WAIT_SECS: &str = "5";
const IPT_TIMEOUT: Duration = Duration::from_secs(12);
const POLL_ACTIVE_FAST: Duration = Duration::from_secs(30);
const POLL_ACTIVE_MEDIUM: Duration = Duration::from_secs(60);
const POLL_ACTIVE_SLOW: Duration = Duration::from_secs(120);
const POLL_ACTIVE_IDLE: Duration = Duration::from_secs(180);
const POLL_AFTER_CONFIRMED: Duration = Duration::from_secs(300);
const MIN_WINDOW: Duration = Duration::from_secs(120);
const SUSPICION_REMEMBER: Duration = Duration::from_secs(30 * 60);
const SUSPICION_COOLDOWN: Duration = Duration::from_secs(30 * 60);
const CONFIRMED_COOLDOWN: Duration = Duration::from_secs(2 * 60 * 60);

#[derive(Debug, Clone)]
struct RuleCounter {
    signature: String,
    uid: u32,
    proto: String,
    ports_hint: Vec<u16>,
    packets: u64,
}

#[derive(Debug, Clone)]
struct ProbeEvent {
    when: Instant,
    proto: String,
    ports_hint: Vec<u16>,
    packets: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ProbeAlertType {
    Suspicion,
    Confirmed,
}

impl ProbeAlertType {
    fn as_str(self) -> &'static str {
        match self {
            Self::Suspicion => "suspicion",
            Self::Confirmed => "confirmed",
        }
    }
}

#[derive(Debug, Default)]
struct DetectorState {
    last_packets_by_rule: HashMap<String, u64>,
    windows: HashMap<u32, VecDeque<ProbeEvent>>,
    last_suspicion_at: HashMap<u32, Instant>,
    last_confirmed_at: HashMap<u32, Instant>,
    idle_rounds: u32,
    slow_until: Option<Instant>,
}

#[derive(Debug, Default)]
struct TickOutcome {
    had_hits: bool,
    had_confirmed_alert: bool,
}

fn detector_handle() -> &'static Mutex<Option<JoinHandle<()>>> {
    static HANDLE: OnceLock<Mutex<Option<JoinHandle<()>>>> = OnceLock::new();
    HANDLE.get_or_init(|| Mutex::new(None))
}

fn should_run() -> &'static AtomicBool {
    static FLAG: OnceLock<AtomicBool> = OnceLock::new();
    FLAG.get_or_init(|| AtomicBool::new(false))
}

pub fn start() {
    let flag = should_run();
    flag.store(true, Ordering::SeqCst);

    let mut slot = match detector_handle().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if slot.as_ref().is_some_and(|h| !h.is_finished()) {
        return;
    }

    *slot = Some(thread::spawn(move || {
        if let Err(e) = detector_loop() {
            logging::warn(&format!("proxyInfo detector stopped: {e:#}"));
        }
    }));
}

pub fn stop() {
    should_run().store(false, Ordering::SeqCst);
    let mut slot = match detector_handle().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if let Some(handle) = slot.take() {
        let _ = handle.join();
    }
}

fn detector_loop() -> Result<()> {
    let mut state = DetectorState::default();
    while should_run().load(Ordering::SeqCst) {
        let sleep_for = next_poll_interval(&state);
        match detector_tick(&mut state, sleep_for) {
            Ok(outcome) => {
                if outcome.had_hits {
                    state.idle_rounds = 0;
                } else {
                    state.idle_rounds = state.idle_rounds.saturating_add(1);
                }
                if outcome.had_confirmed_alert {
                    state.slow_until = Some(Instant::now() + POLL_AFTER_CONFIRMED);
                } else if state.slow_until.is_some_and(|until| Instant::now() >= until) {
                    state.slow_until = None;
                }
            }
            Err(e) => logging::warn(&format!("proxyInfo detector tick failed: {e:#}")),
        }

        let mut slept = Duration::from_secs(0);
        while should_run().load(Ordering::SeqCst) && slept < sleep_for {
            let remain = sleep_for.saturating_sub(slept);
            let step = if remain > Duration::from_secs(1) { Duration::from_secs(1) } else { remain };
            thread::sleep(step);
            slept += step;
        }
    }
    Ok(())
}

fn next_poll_interval(state: &DetectorState) -> Duration {
    if state.slow_until.is_some_and(|until| Instant::now() < until) {
        return POLL_AFTER_CONFIRMED;
    }
    match state.idle_rounds {
        0..=1 => POLL_ACTIVE_FAST,
        2..=3 => POLL_ACTIVE_MEDIUM,
        4..=6 => POLL_ACTIVE_SLOW,
        _ => POLL_ACTIVE_IDLE,
    }
}

fn current_window(poll_interval: Duration) -> Duration {
    let scaled = Duration::from_secs(poll_interval.as_secs().saturating_mul(2));
    if scaled > MIN_WINDOW { scaled } else { MIN_WINDOW }
}

fn detector_tick(state: &mut DetectorState, poll_interval: Duration) -> Result<TickOutcome> {
    let enabled = proxyinfo::load_enabled_json().map(|v| v.is_enabled()).unwrap_or(false);
    if !enabled {
        state.last_packets_by_rule.clear();
        state.windows.clear();
        state.idle_rounds = 0;
        state.slow_until = None;
        return Ok(TickOutcome::default());
    }

    // Only watch packages that are explicitly placed into proxyInfo.
    // Any UID outside proxyInfo is ignored by this detector.
    let uid_packages = proxyinfo::read_out_uid_packages()?;
    if uid_packages.is_empty() {
        state.last_packets_by_rule.clear();
        state.windows.clear();
        state.idle_rounds = 0;
        state.slow_until = None;
        return Ok(TickOutcome::default());
    }

    let now = Instant::now();
    let window = current_window(poll_interval);
    let current_rules = read_proxyinfo_rule_counters()?;
    let current_signatures: BTreeSet<String> = current_rules.iter().map(|r| r.signature.clone()).collect();
    state.last_packets_by_rule.retain(|sig, _| current_signatures.contains(sig));

    let mut changed_uids = BTreeSet::new();
    let mut had_hits = false;
    for rule in current_rules {
        if !uid_packages.contains_key(&rule.uid) {
            state.last_packets_by_rule.insert(rule.signature, rule.packets);
            continue;
        }
        let prev = state.last_packets_by_rule.insert(rule.signature.clone(), rule.packets).unwrap_or(rule.packets);
        let delta = rule.packets.saturating_sub(prev);
        if delta == 0 {
            continue;
        }
        had_hits = true;
        changed_uids.insert(rule.uid);
        let entry = state.windows.entry(rule.uid).or_default();
        entry.push_back(ProbeEvent {
            when: now,
            proto: rule.proto,
            ports_hint: rule.ports_hint,
            packets: delta,
        });
    }

    for entry in state.windows.values_mut() {
        while entry.front().is_some_and(|ev| now.duration_since(ev.when) > window) {
            entry.pop_front();
        }
    }

    let mut had_confirmed_alert = false;
    for uid in changed_uids {
        let Some(events) = state.windows.get(&uid) else { continue; };
        if events.is_empty() {
            continue;
        }
        let rule_hits = events.len() as u32;
        let total_packets: u64 = events.iter().map(|e| e.packets).sum();
        let unique_ports = count_unique_ports(events);
        let recent_suspicion = state
            .last_suspicion_at
            .get(&uid)
            .is_some_and(|t| now.duration_since(*t) <= SUSPICION_REMEMBER);

        let event_type = if rule_hits >= 2 || unique_ports >= 2 || total_packets >= 2 || recent_suspicion {
            ProbeAlertType::Confirmed
        } else {
            ProbeAlertType::Suspicion
        };

        let packages = uid_packages.get(&uid).cloned().unwrap_or_default();
        if packages.is_empty() {
            continue;
        }
        if !should_alert(state, uid, event_type, now) {
            continue;
        }

        let package = packages.first().cloned().unwrap_or_default();
        let packages_csv = packages.join(",");
        let proto = summarize_proto(events);
        let ports_hint = summarize_ports(events);

        match event_type {
            ProbeAlertType::Confirmed => {
                had_confirmed_alert = true;
                logging::warn(&format!(
                    "proxyInfo detector: confirmed localhost/system probing package={} uid={} proto={} ports_hint={} hits={} packets={}",
                    package, uid, proto, ports_hint, rule_hits, total_packets
                ));
                state.last_confirmed_at.insert(uid, now);
            }
            ProbeAlertType::Suspicion => {
                logging::warn(&format!(
                    "proxyInfo detector: suspicion of localhost/system probing package={} uid={} proto={} ports_hint={} hits={} packets={}",
                    package, uid, proto, ports_hint, rule_hits, total_packets
                ));
                state.last_suspicion_at.insert(uid, now);
            }
        }

        let _ = notification::send_proxyinfo_probe_detected(
            event_type.as_str(),
            &package,
            &packages_csv,
            uid,
            &proto,
            &ports_hint,
            rule_hits,
            window.as_secs() as u32,
        );
    }

    Ok(TickOutcome { had_hits, had_confirmed_alert })
}

fn summarize_proto(events: &VecDeque<ProbeEvent>) -> String {
    let mut set = BTreeSet::new();
    for ev in events {
        set.insert(ev.proto.clone());
    }
    if set.len() == 1 {
        set.into_iter().next().unwrap_or_else(|| "unknown".to_string())
    } else {
        set.into_iter().collect::<Vec<_>>().join(",")
    }
}

fn count_unique_ports(events: &VecDeque<ProbeEvent>) -> usize {
    let mut set = BTreeSet::new();
    for ev in events {
        for port in &ev.ports_hint {
            set.insert(*port);
        }
    }
    set.len()
}

fn should_alert(state: &DetectorState, uid: u32, event_type: ProbeAlertType, now: Instant) -> bool {
    match event_type {
        ProbeAlertType::Confirmed => state
            .last_confirmed_at
            .get(&uid)
            .map_or(true, |t| now.duration_since(*t) >= CONFIRMED_COOLDOWN),
        ProbeAlertType::Suspicion => {
            if state
                .last_confirmed_at
                .get(&uid)
                .is_some_and(|t| now.duration_since(*t) < CONFIRMED_COOLDOWN)
            {
                return false;
            }
            state
                .last_suspicion_at
                .get(&uid)
                .map_or(true, |t| now.duration_since(*t) >= SUSPICION_COOLDOWN)
        }
    }
}

fn summarize_ports(events: &VecDeque<ProbeEvent>) -> String {
    let mut set = BTreeSet::new();
    for ev in events {
        for port in &ev.ports_hint {
            set.insert(*port);
        }
    }
    let ports = set.into_iter().take(24).map(|p| p.to_string()).collect::<Vec<_>>();
    if ports.is_empty() {
        String::new()
    } else {
        ports.join(",")
    }
}

fn read_proxyinfo_rule_counters() -> Result<Vec<RuleCounter>> {
    let _guard = xtables_lock::lock();
    let args = [
        "-w", IPTABLES_WAIT_SECS,
        "-t", "filter",
        "-L", CHAIN_NAME,
        "-v", "-n", "-x", "--line-numbers",
    ];
    let (code, out) = xtables_lock::run_timeout_retry(
        "iptables",
        &args,
        crate::shell::Capture::Both,
        IPT_TIMEOUT,
    )?;
    if code != 0 {
        return Ok(Vec::new());
    }

    let mut out_rules = Vec::new();
    for line in out.lines() {
        let line = line.trim();
        if line.is_empty() || !line.chars().next().is_some_and(|c| c.is_ascii_digit()) {
            continue;
        }
        if let Some(rule) = parse_rule_counter(line) {
            out_rules.push(rule);
        }
    }
    Ok(out_rules)
}

fn parse_rule_counter(line: &str) -> Option<RuleCounter> {
    let toks: Vec<&str> = line.split_whitespace().collect();
    if toks.len() < 5 { return None; }
    let packets = toks.get(1)?.parse::<u64>().ok()?;
    let proto = toks.get(4)?.to_ascii_lowercase();
    if proto != "tcp" && proto != "udp" {
        return None;
    }

    let uid = extract_uid(line)?;
    let ports_hint = extract_ports(line);
    if ports_hint.is_empty() {
        return None;
    }
    let signature = format!("v4|{}|{}|{}", uid, proto, ports_hint.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(","));
    Some(RuleCounter { signature, uid, proto, ports_hint, packets })
}

fn extract_uid(line: &str) -> Option<u32> {
    let marker = "UID match ";
    let idx = line.find(marker)? + marker.len();
    let tail = &line[idx..];
    let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse::<u32>().ok()
}

fn extract_ports(line: &str) -> Vec<u16> {
    if let Some(idx) = line.find("multiport dports ") {
        let tail = &line[idx + "multiport dports ".len()..];
        let token = tail.split_whitespace().next().unwrap_or("");
        return parse_ports_csv(token);
    }
    if let Some(idx) = line.find("dpt:") {
        let tail = &line[idx + "dpt:".len()..];
        let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
        if let Ok(port) = digits.parse::<u16>() {
            return vec![port];
        }
    }
    Vec::new()
}

fn parse_ports_csv(s: &str) -> Vec<u16> {
    let mut out = Vec::new();
    for item in s.split(',') {
        let item = item.trim();
        if item.is_empty() {
            continue;
        }
        if let Ok(port) = item.parse::<u16>() {
            out.push(port);
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}
