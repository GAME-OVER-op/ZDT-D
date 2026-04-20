use anyhow::{Context, Result};
use std::time::Duration;
use std::sync::{Arc, Mutex};

use crate::{api, config::Config, logging, protector, runtime, settings, shell, stats};

#[derive(Debug, Clone)]
pub struct State {
    pub token: String,
    pub services_running: bool,
    /// A start sequence is currently running.
    pub start_in_progress: bool,
    /// A stop sequence is currently running.
    pub stop_in_progress: bool,
    pub start: settings::StartSettings,
}

pub type SharedState = Arc<Mutex<State>>;

/// Lock shared daemon state, tolerating poisoned mutexes.
///
/// A poisoned mutex can happen if a thread panics while holding the lock.
/// For this daemon it's safer to keep working than to crash.
pub fn lock_state<'a>(state: &'a SharedState) -> std::sync::MutexGuard<'a, State> {
    match state.lock() {
        Ok(g) => g,
        Err(poisoned) => {
            crate::logging::warn("state mutex poisoned; recovering");
            poisoned.into_inner()
        }
    }
}


/// Main entrypoint: runs the local-only API server.
///
/// If `setting/start.json` has `enabled=true`, we also try to start services
/// in "full" mode on daemon boot. Otherwise we start only the API.
pub fn run(_cfg: &Config) -> Result<()> {
    if let Err(e) = settings::ensure_minimal_program_layouts() {
        logging::warn(&format!("failed to restore minimal working_folder layout: {e:#}"));
    }

    // Ensure token exists and write api info (no token in API responses).
    let token = settings::read_or_create_token()?;
    settings::write_api_info(&settings::api_info_path(), "127.0.0.1:1006")?;
    if let Err(e) = settings::load_api_settings() {
        logging::warn(&format!("failed to init api/setting.json: {e:#}"));
    }
    if let Err(e) = crate::proxyinfo::ensure_layout() {
        logging::warn(&format!("failed to init proxyInfo files: {e:#}"));
    }
    if let Err(e) = crate::blockedquic::ensure_layout() {
        logging::warn(&format!("failed to init blockedquic files: {e:#}"));
    }
    if let Err(e) = crate::programs::tor::ensure_layout() {
        logging::warn(&format!("failed to init tor files: {e:#}"));
    }

    // Truncate main log at each daemon start.
    logging::truncate_main_log()?;

    crate::iptables::caps::init_multiport_caps();

    // Load start settings.
    let start = settings::read_start_settings().unwrap_or_default();
    let state: SharedState = Arc::new(Mutex::new(State {
        token,
        services_running: false,
        start_in_progress: false,
        stop_in_progress: false,
        start: start.clone(),
    }));

    // Start API server immediately, and perform autostart in background if enabled=true.
    if start.enabled {
        let st = state.clone();
        std::thread::spawn(move || {
            let _ = handle_start_async(&st);
        });
    }

    api::serve(state.clone(), "127.0.0.1:1006")
}

/// Schedule start in background and return immediately.
///
/// If a start is already running, this is a no-op.
pub fn handle_start_async(state: &SharedState) -> Result<bool> {
    {
        let mut st = lock_state(state);
        // Do not allow start/stop to overlap. If an operation is running, ignore.
        if st.start_in_progress || st.stop_in_progress {
            logging::info("start ignored: another operation is in progress");
            return Ok(false);
        }
        if st.services_running {
            // Already running; nothing to do.
            logging::info("start ignored: services already running");
            return Ok(false);
        }
        // Mark busy first so concurrent requests are ignored.
        st.start_in_progress = true;
    }

    // Persist enabled=true (so reboot will retry if user wants).
    // Important: if the start request is ignored, we must NOT touch settings.
    let mut start = settings::read_start_settings().unwrap_or_default();
    start.enabled = true;
    if let Err(e) = settings::write_start_settings(&start) {
        let mut st = lock_state(state);
        st.start_in_progress = false;
        return Err(e);
    }
    {
        let mut st = lock_state(state);
        // Update cached start settings.
        st.start = start.clone();
    }

    logging::info("start requested -> scheduling start_full in background");

    let st_arc = state.clone();
    std::thread::spawn(move || {
        let res = runtime::start_full().context("start_full");
        match res {
            Ok(()) => {
                let start_now = settings::read_start_settings().unwrap_or_default();
                {
                    let mut st = lock_state(&st_arc);
                    st.services_running = true;
                    st.start = start_now;
                }

                protector::activate();

                // Notify the Android app (app-owned notification).
                let _ = crate::android::notification::send_app_state(true);
                spawn_network_guard(st_arc.clone());
            }
            Err(e) => {
                logging::warn(&format!("start_full failed: {e:#}"));
                crate::scan_detector::stop();
                let mut st = lock_state(&st_arc);
                st.services_running = false;
            }
        }
        let mut st = lock_state(&st_arc);
        st.start_in_progress = false;
    });

    Ok(true)
}

#[derive(Debug, Clone, Copy)]
struct NetworkProbe {
    ok: bool,
    ping_ms: Option<f64>,
    speed_mbps: Option<f64>,
}

/// Starts one background health-check cycle after service startup.
///
/// Flow:
/// - first probe after 3 seconds;
/// - if failed, second probe after 10 seconds;
/// - if both fail, auto-stop module to avoid "no internet" state.
fn spawn_network_guard(state: SharedState) {
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(3));
        let first = probe_network();
        log_network_probe("Сетевой тест #1", first);
        if first.ok {
            return;
        }

        std::thread::sleep(Duration::from_secs(10));
        let second = probe_network();
        log_network_probe("Сетевой тест #2", second);
        if second.ok {
            return;
        }

        // Avoid conflict with manual stop and skip if already stopped.
        {
            let st = lock_state(&state);
            if !st.services_running || st.stop_in_progress {
                return;
            }
        }

        logging::user_error("Сеть недоступна после запуска: модуль автоматически остановлен");
        crate::scan_detector::stop();
        if let Err(e) = runtime::stop_full() {
            logging::warn(&format!("auto-stop after failed network probes failed: {e:#}"));
        }

        let mut start = settings::read_start_settings().unwrap_or_default();
        start.enabled = false;
        let _ = settings::write_start_settings(&start);

        {
            let mut st = lock_state(&state);
            st.services_running = false;
            st.stop_in_progress = false;
            st.start_in_progress = false;
            st.start = start;
        }
        protector::deactivate();
        let _ = crate::android::notification::send_app_state(false);
    });
}

/// Emits user-facing network probe result into ordinary module logs.
fn log_network_probe(prefix: &str, probe: NetworkProbe) {
    let ping = probe
        .ping_ms
        .map(|v| format!("{v:.1}ms"))
        .unwrap_or_else(|| "n/a".to_string());
    let speed = probe
        .speed_mbps
        .map(|v| format!("{v:.2} Mbps"))
        .unwrap_or_else(|| "n/a".to_string());
    let status = if probe.ok { "OK" } else { "FAIL" };
    logging::user_info(&format!("{prefix}: ping={ping}, speed={speed}, status={status}"));
}

/// Performs a lightweight availability + quality probe (ping + HTTP + curl speed).
fn probe_network() -> NetworkProbe {
    let ping_ms = ping_ms("1.1.1.1");
    let http_ok = has_http_connectivity();
    let speed_mbps = measure_speed_mbps();
    let ok = ping_ms.is_some() || http_ok;
    NetworkProbe {
        ok,
        ping_ms,
        speed_mbps,
    }
}

/// Runs one ICMP ping and extracts RTT in milliseconds.
fn ping_ms(host: &str) -> Option<f64> {
    let (rc, out) = shell::run_timeout(
        "ping",
        &["-c", "1", "-W", "2", host],
        shell::Capture::Stdout,
        Duration::from_secs(4),
    )
    .ok()?;
    if rc != 0 {
        return None;
    }
    let marker = "time=";
    let idx = out.find(marker)?;
    let tail = &out[idx + marker.len()..];
    let token = tail.split_whitespace().next()?.trim();
    token.parse::<f64>().ok()
}

/// Checks HTTP reachability against Android's common connectivity URL.
fn has_http_connectivity() -> bool {
    let cmd = "curl -m 4 -s -L -o /dev/null https://connectivitycheck.gstatic.com/generate_204";
    shell::run_timeout(
        "sh",
        &["-c", cmd],
        shell::Capture::Stdout,
        Duration::from_secs(6),
    )
    .map(|(rc, _)| rc == 0)
    .unwrap_or(false)
}

/// Measures approximate download speed in Mbps via curl's `speed_download` metric.
fn measure_speed_mbps() -> Option<f64> {
    let cmd = "curl -m 8 -s -L -o /dev/null -w '%{speed_download}' https://speed.cloudflare.com/__down?bytes=200000";
    let out = shell::capture_timeout(cmd, Duration::from_secs(10)).ok()?;
    let bytes_per_sec = out.trim().parse::<f64>().ok()?;
    Some((bytes_per_sec * 8.0) / 1_000_000.0)
}

/// Schedule stop in background and return immediately.
///
/// If a stop is already running, this is a no-op.
pub fn handle_stop_async(state: &SharedState) -> Result<bool> {
    {
        let mut st = lock_state(state);
        // Do not allow start/stop to overlap. If an operation is running, ignore.
        if st.stop_in_progress || st.start_in_progress {
            logging::info("stop ignored: another operation is in progress");
            return Ok(false);
        }
        // Do NOT rely solely on in-memory flags to decide whether we should stop.
        // The daemon can be restarted while services are still running, or a previous
        // start sequence might have partially completed. `stop_full()` is designed to
        // be idempotent, so we allow a stop request whenever we are idle.
        st.services_running = false;
        // Mark busy first so concurrent requests are ignored.
        st.stop_in_progress = true;
    }

    // Persist enabled=false.
    // Important: if the stop request is ignored, we must NOT touch settings.
    let mut start = settings::read_start_settings().unwrap_or_default();
    start.enabled = false;
    if let Err(e) = settings::write_start_settings(&start) {
        let mut st = lock_state(state);
        st.stop_in_progress = false;
        return Err(e);
    }
    {
        let mut st = lock_state(state);
        st.start = start.clone();
    }

    logging::info("stop requested -> scheduling stop_full in background");

    let st_arc = state.clone();
    std::thread::spawn(move || {
        crate::scan_detector::stop();
        let res = runtime::stop_full().context("stop_full");
        match res {
            Ok(()) => {
                {
                    let mut st = lock_state(&st_arc);
                    st.services_running = false;
                }
                protector::deactivate();
                crate::scan_detector::stop();

                // Notify the Android app (app-owned notification).
                let _ = crate::android::notification::send_app_state(false);
            }
            Err(e) => {
                logging::warn(&format!("stop_full failed: {e:#}"));
            }
        }
        let mut st = lock_state(&st_arc);
        st.stop_in_progress = false;
    });

    Ok(true)
}

pub fn collect_status(state: &SharedState) -> Result<stats::Report> {
    let st = lock_state(state).clone();
    stats::collect_report(st.services_running)
}
