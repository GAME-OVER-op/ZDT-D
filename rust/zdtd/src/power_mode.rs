use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::shell::{self, Capture};

/// Android Battery Saver flag polling interval.
///
/// `settings get global low_power` itself wakes Android services, so keep this
/// cached for 5 minutes and let all background loops share the same value.
const LOW_POWER_CACHE_TTL: Duration = Duration::from_secs(5 * 60);
const SETTINGS_TIMEOUT: Duration = Duration::from_secs(2);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PowerMode {
    Normal,
    LowPower,
}

#[derive(Debug)]
struct Cache {
    checked_at: Option<Instant>,
    mode: PowerMode,
    initialized: bool,
}

impl Default for Cache {
    fn default() -> Self {
        Self {
            checked_at: None,
            mode: PowerMode::Normal,
            initialized: false,
        }
    }
}

fn cache() -> &'static Mutex<Cache> {
    static CACHE: OnceLock<Mutex<Cache>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(Cache::default()))
}

pub fn current() -> PowerMode {
    let now = Instant::now();
    let mut guard = match cache().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    if let Some(checked_at) = guard.checked_at {
        if checked_at.elapsed() < LOW_POWER_CACHE_TTL {
            return guard.mode;
        }
    }

    let next = read_android_low_power_mode();
    if !guard.initialized {
        if next == PowerMode::LowPower {
            log::info!("power_mode: Android low_power=1, lazy polling enabled");
        }
    } else if next != guard.mode {
        match next {
            PowerMode::LowPower => log::info!("power_mode: Android low_power=1, switching to lazy polling"),
            PowerMode::Normal => log::info!("power_mode: Android low_power=0, switching to normal polling"),
        }
    }

    guard.mode = next;
    guard.checked_at = Some(now);
    guard.initialized = true;
    next
}

pub fn is_low_power() -> bool {
    current() == PowerMode::LowPower
}

fn read_android_low_power_mode() -> PowerMode {
    match shell::run_timeout(
        "settings",
        &["get", "global", "low_power"],
        Capture::Stdout,
        SETTINGS_TIMEOUT,
    ) {
        Ok((0, out)) if out.trim() == "1" => PowerMode::LowPower,
        Ok((0, _)) => PowerMode::Normal,
        Ok((code, out)) => {
            log::debug!(
                "power_mode: settings get global low_power returned code={} output={}",
                code,
                out.trim()
            );
            PowerMode::Normal
        }
        Err(e) => {
            log::debug!("power_mode: failed to read Android low_power: {e:#}");
            PowerMode::Normal
        }
    }
}

fn scaled(base: Duration, multiplier: u32, max: Duration) -> Duration {
    if !is_low_power() {
        return base;
    }
    let secs = base.as_secs().saturating_mul(multiplier as u64);
    let nanos = base.subsec_nanos().saturating_mul(multiplier);
    let extra_secs = (nanos / 1_000_000_000) as u64;
    let nanos = nanos % 1_000_000_000;
    let out = Duration::new(secs.saturating_add(extra_secs), nanos);
    out.min(max).max(base)
}

pub fn light_poll(base: Duration) -> Duration {
    scaled(base, 2, Duration::from_secs(120))
}

pub fn medium_poll(base: Duration) -> Duration {
    scaled(base, 3, Duration::from_secs(300))
}

pub fn heavy_poll(base: Duration) -> Duration {
    scaled(base, 4, Duration::from_secs(600))
}

pub fn detector_poll(base: Duration) -> Duration {
    scaled(base, 3, Duration::from_secs(600))
}

pub fn status_cache_ttl(base: Duration) -> Duration {
    scaled(base, 3, Duration::from_secs(10))
}

pub fn package_cache_ttl(base: Duration) -> Duration {
    scaled(base, 3, Duration::from_secs(120))
}

pub fn protector_poll(base: Duration) -> Duration {
    scaled(base, 2, Duration::from_secs(20 * 60))
}

pub fn captive_portal_accept_sleep(base: Duration) -> Duration {
    scaled(base, 4, Duration::from_millis(500))
}

pub fn captive_portal_page_poll_ms(base_ms: u64) -> u64 {
    if is_low_power() {
        base_ms.saturating_mul(3).min(12_000).max(base_ms)
    } else {
        base_ms
    }
}
