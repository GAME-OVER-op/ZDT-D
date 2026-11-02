use std::{sync::{Mutex, OnceLock}, time::{Duration, Instant}};

use crate::platform::android::power as power_mode;
use super::super::support::apps::{collect_assignment_files_uncached, AppAssignmentFile};

const ASSIGNMENT_CACHE_TTL: Duration = Duration::from_secs(2);
#[derive(Clone)]
struct AssignmentCacheEntry {
    created: Instant,
    items: Vec<AppAssignmentFile>,
}

static ASSIGNMENT_CACHE: OnceLock<Mutex<Option<AssignmentCacheEntry>>> = OnceLock::new();
fn assignment_cache() -> &'static Mutex<Option<AssignmentCacheEntry>> {
    ASSIGNMENT_CACHE.get_or_init(|| Mutex::new(None))
}

pub(in crate::api) fn invalidate_assignment_cache() {
    if let Ok(mut guard) = assignment_cache().lock() {
        *guard = None;
    }
}

pub(in crate::api) fn collect_assignment_files() -> Vec<AppAssignmentFile> {
    if let Ok(guard) = assignment_cache().lock() {
        if let Some(entry) = &*guard {
            if entry.created.elapsed() < power_mode::status_cache_ttl(ASSIGNMENT_CACHE_TTL) {
                return entry.items.clone();
            }
        }
    }

    let items = collect_assignment_files_uncached();
    if let Ok(mut guard) = assignment_cache().lock() {
        *guard = Some(AssignmentCacheEntry {
            created: Instant::now(),
            items: items.clone(),
        });
    }
    items
}

