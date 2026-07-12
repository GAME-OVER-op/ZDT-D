use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Condvar, Mutex, OnceLock,
    },
    thread,
    time::{Duration, Instant},
};

const INITIAL_CHECK: Duration = Duration::from_secs(10);
const FALLBACK_WAIT: Duration = Duration::from_secs(120);
const PROBE_INTERVAL: Duration = Duration::from_secs(3);
const LOG_INTERVAL: Duration = Duration::from_secs(1);
const CONNECT_TIMEOUT: Duration = Duration::from_millis(600);

fn probe_interval() -> Duration {
    crate::power_mode::medium_poll(PROBE_INTERVAL)
}

fn log_interval() -> Duration {
    crate::power_mode::medium_poll(LOG_INTERVAL)
}

static WAIT_ACTIVE: AtomicBool = AtomicBool::new(false);
static COUNTDOWN_ACTIVE: AtomicBool = AtomicBool::new(false);
static SKIP_REQUESTED: AtomicBool = AtomicBool::new(false);
static WAIT_SIGNAL: OnceLock<(Mutex<()>, Condvar)> = OnceLock::new();

fn wait_signal() -> &'static (Mutex<()>, Condvar) {
    WAIT_SIGNAL.get_or_init(|| (Mutex::new(()), Condvar::new()))
}

/// Request skipping the active internet startup wait.
///
/// Returns true while any internet wait phase is active: the initial 10-second
/// probe window or the fallback countdown. This lets a repeated Start request
/// immediately continue startup instead of being ignored as a duplicate start.
pub fn request_skip() -> bool {
    if !WAIT_ACTIVE.load(Ordering::SeqCst) {
        return false;
    }
    SKIP_REQUESTED.store(true, Ordering::SeqCst);
    let (lock, cvar) = wait_signal();
    let _guard = match lock.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    cvar.notify_all();
    true
}

struct WaitActiveGuard;

impl WaitActiveGuard {
    fn enter() -> Self {
        WAIT_ACTIVE.store(true, Ordering::SeqCst);
        Self
    }
}

impl Drop for WaitActiveGuard {
    fn drop(&mut self) {
        WAIT_ACTIVE.store(false, Ordering::SeqCst);
        COUNTDOWN_ACTIVE.store(false, Ordering::SeqCst);
        SKIP_REQUESTED.store(false, Ordering::SeqCst);
    }
}

enum InitialCheckResult {
    Online,
    Offline,
    Skipped,
}

/// Startup gate for programs that need real internet before they can create
/// their VPN/TUN interface.
///
/// Behavior:
/// - probe internet every 3 seconds for up to 10 seconds;
/// - if still offline, show a dynamic 2-minute countdown;
/// - continue immediately if internet appears, user presses Start again, or the
///   countdown expires.
pub fn wait_before_start_if_needed() {
    SKIP_REQUESTED.store(false, Ordering::SeqCst);
    COUNTDOWN_ACTIVE.store(false, Ordering::SeqCst);
    let _active = WaitActiveGuard::enter();

    match wait_initial_online_check() {
        InitialCheckResult::Online => {
            log::info!("internet wait: internet is available during initial check");
            return;
        }
        InitialCheckResult::Skipped => {
            log::info!("internet wait: skipped by repeated start request during initial check");
            crate::logging::user_internet_wait_skipped();
            return;
        }
        InitialCheckResult::Offline => {}
    }

    log::info!("internet wait: no internet after initial check, starting fallback countdown");
    run_fallback_countdown();
}

fn wait_initial_online_check() -> InitialCheckResult {
    let deadline = Instant::now() + INITIAL_CHECK;
    let mut next_probe = Instant::now();

    loop {
        if SKIP_REQUESTED.swap(false, Ordering::SeqCst) {
            return InitialCheckResult::Skipped;
        }

        let now = Instant::now();
        if now >= deadline {
            return InitialCheckResult::Offline;
        }

        if now >= next_probe {
            if has_internet() {
                return InitialCheckResult::Online;
            }
            next_probe = now + probe_interval();
            continue;
        }

        wait_until(next_probe.min(deadline));
    }
}

fn run_fallback_countdown() {
    COUNTDOWN_ACTIVE.store(true, Ordering::SeqCst);
    SKIP_REQUESTED.store(false, Ordering::SeqCst);

    let deadline = Instant::now() + FALLBACK_WAIT;
    let mut next_probe = Instant::now() + probe_interval();
    let mut next_log = Instant::now();

    loop {
        if SKIP_REQUESTED.swap(false, Ordering::SeqCst) {
            log::info!("internet wait: skipped by repeated start request");
            crate::logging::user_internet_wait_skipped();
            break;
        }

        let now = Instant::now();
        if now >= deadline {
            log::info!("internet wait: countdown expired, startup continues");
            crate::logging::user_internet_wait_timeout();
            break;
        }

        if now >= next_log {
            crate::logging::user_internet_wait_countdown(deadline.saturating_duration_since(now));
            next_log = now + log_interval();
        }

        if now >= next_probe {
            if has_internet() {
                log::info!("internet wait: internet appeared during countdown");
                crate::logging::user_internet_wait_finished();
                break;
            }
            next_probe = now + probe_interval();
        }

        let wake_at = next_log.min(next_probe).min(deadline);
        wait_until(wake_at);
    }

    COUNTDOWN_ACTIVE.store(false, Ordering::SeqCst);
    SKIP_REQUESTED.store(false, Ordering::SeqCst);
}

fn wait_until(instant: Instant) {
    let now = Instant::now();
    if instant <= now {
        return;
    }
    wait_for(instant.saturating_duration_since(now));
}

fn wait_for(duration: Duration) {
    let (lock, cvar) = wait_signal();
    let guard = match lock.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let _wait_result = cvar.wait_timeout(guard, duration);
}

fn has_internet() -> bool {
    let addrs = probe_addrs();
    let probe_count = addrs.len();
    let (tx, rx) = mpsc::channel();

    for addr in addrs.iter().copied() {
        let tx = tx.clone();
        thread::spawn(move || {
            let ok = TcpStream::connect_timeout(&addr, CONNECT_TIMEOUT).is_ok();
            let _ = tx.send(ok);
        });
    }
    drop(tx);

    let deadline = Instant::now() + CONNECT_TIMEOUT + Duration::from_millis(150);
    for _ in 0..probe_count {
        let now = Instant::now();
        if now >= deadline {
            break;
        }
        match rx.recv_timeout(deadline.saturating_duration_since(now)) {
            Ok(true) => return true,
            Ok(false) => {}
            Err(_) => break,
        }
    }
    false
}

fn probe_addrs() -> [SocketAddr; 4] {
    [
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 443),
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1)), 443),
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)), 443),
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9)), 443),
    ]
}
