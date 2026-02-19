use anyhow::{Context, Result};
use std::sync::{Arc, Mutex};

use crate::{api, config::Config, logging, runtime, settings, stats};

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
    // Ensure token exists and write api info (no token in API responses).
    let token = settings::read_or_create_token()?;
    settings::write_api_info(&settings::api_info_path(), "127.0.0.1:1006")?;

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

                // Notify the Android app (app-owned notification).
                let _ = crate::android::notification::send_app_state(true);
            }
            Err(e) => {
                logging::warn(&format!("start_full failed: {e:#}"));
                let mut st = lock_state(&st_arc);
                st.services_running = false;
            }
        }
        let mut st = lock_state(&st_arc);
        st.start_in_progress = false;
    });

    Ok(true)
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
        let res = runtime::stop_full().context("stop_full");
        match res {
            Ok(()) => {
                {
                    let mut st = lock_state(&st_arc);
                    st.services_running = false;
                }

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
