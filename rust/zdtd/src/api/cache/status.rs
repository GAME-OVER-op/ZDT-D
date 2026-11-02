use std::{sync::{Mutex, OnceLock, atomic::{AtomicBool, Ordering}}, thread, time::{Duration, Instant}};

use crate::{features::stats, platform::android::power as power_mode};

const STATUS_CACHE_TTL: Duration = Duration::from_secs(2);
pub(in crate::api) const API_SLOW_WARN_THRESHOLD: Duration = Duration::from_millis(8000);
#[derive(Clone)]
struct StatusCacheEntry {
    created: Instant,
    report: stats::Report,
}

static STATUS_CACHE: OnceLock<Mutex<Option<StatusCacheEntry>>> = OnceLock::new();
fn status_cache() -> &'static Mutex<Option<StatusCacheEntry>> {
    STATUS_CACHE.get_or_init(|| Mutex::new(None))
}

static STATUS_REFRESHING: AtomicBool = AtomicBool::new(false);

fn store_status_cache(report: stats::Report) {
    if let Ok(mut guard) = status_cache().lock() {
        *guard = Some(StatusCacheEntry {
            created: Instant::now(),
            report,
        });
    }
}

fn spawn_status_refresh(services_running: bool) {
    if STATUS_REFRESHING
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return;
    }

    thread::spawn(move || {
        struct RefreshGuard;
        impl Drop for RefreshGuard {
            fn drop(&mut self) {
                STATUS_REFRESHING.store(false, Ordering::Release);
            }
        }
        let _guard = RefreshGuard;
        let started = Instant::now();
        let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            stats::collect_report(services_running)
        }));
        match res {
            Ok(Ok(report)) => {
                let elapsed = started.elapsed();
                if elapsed > API_SLOW_WARN_THRESHOLD {
                    log::warn!("api status refresh slow: duration_ms={}", elapsed.as_millis());
                }
                store_status_cache(report);
            }
            Ok(Err(e)) => {
                log::warn!("api status refresh failed: {e:#}");
            }
            Err(_) => {
                log::error!("api status refresh panicked");
            }
        }
    });
}

pub(in crate::api) fn get_status_snapshot(services_running: bool) -> (stats::Report, bool, bool) {
    match status_cache().try_lock() {
        Ok(guard) => {
            if let Some(entry) = &*guard {
                let age = entry.created.elapsed();
                if age < power_mode::status_cache_ttl(STATUS_CACHE_TTL) {
                    return (entry.report.clone(), true, false);
                }
                let stale = entry.report.clone();
                drop(guard);
                spawn_status_refresh(services_running);
                return (stale, true, true);
            }
        }
        Err(_) => {
            spawn_status_refresh(services_running);
            return (stats::Report::default(), true, true);
        }
    }

    // First request after daemon start: do not block the HTTP handler on process scans.
    spawn_status_refresh(services_running);
    (stats::Report::default(), true, true)
}


