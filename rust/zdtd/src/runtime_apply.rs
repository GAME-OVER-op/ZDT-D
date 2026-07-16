use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    collections::BTreeMap,
    fs,
    sync::{Mutex, OnceLock, atomic::{AtomicU64, Ordering}},
    thread,
    time::{SystemTime, UNIX_EPOCH},
};

const STATUS_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/runtime_apply/status.json";

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct RuntimeApplyTask {
    program: String,
    profile: Option<String>,
    slot: String,
}

impl RuntimeApplyTask {
    fn key(&self) -> String {
        format!(
            "{}\t{}\t{}",
            self.program,
            self.profile.as_deref().unwrap_or(""),
            self.slot,
        )
    }

    fn label(&self) -> String {
        match self.profile.as_deref().filter(|s| !s.is_empty()) {
            Some(profile) => format!("{}/{}/{}", self.program, profile, self.slot),
            None => format!("{}/{}", self.program, self.slot),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeApplyStatus {
    pub ok: bool,
    pub state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub program: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub profile: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub slot: Option<String>,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    pub updated_at_unix_ms: u64,
}

impl RuntimeApplyStatus {
    fn idle() -> Self {
        Self {
            ok: true,
            state: "idle".to_string(),
            program: None,
            profile: None,
            slot: None,
            message: "Нет активного применения правил".to_string(),
            error: None,
            updated_at_unix_ms: now_ms(),
        }
    }

    fn for_task(task: &RuntimeApplyTask, state: &str, message: String, error: Option<String>) -> Self {
        Self {
            ok: true,
            state: state.to_string(),
            program: Some(task.program.clone()),
            profile: task.profile.clone(),
            slot: Some(task.slot.clone()),
            message,
            error,
            updated_at_unix_ms: now_ms(),
        }
    }
}

#[derive(Debug)]
struct RuntimeApplyQueue {
    status: RuntimeApplyStatus,
    pending: BTreeMap<String, RuntimeApplyTask>,
    worker_running: bool,
}

impl Default for RuntimeApplyQueue {
    fn default() -> Self {
        Self {
            status: RuntimeApplyStatus::idle(),
            pending: BTreeMap::new(),
            worker_running: false,
        }
    }
}

static QUEUE: OnceLock<Mutex<RuntimeApplyQueue>> = OnceLock::new();
static GENERATION: AtomicU64 = AtomicU64::new(1);

fn queue() -> &'static Mutex<RuntimeApplyQueue> {
    QUEUE.get_or_init(|| Mutex::new(RuntimeApplyQueue::default()))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis().min(u128::from(u64::MAX)) as u64)
        .unwrap_or(0)
}

fn write_status_file(status: &RuntimeApplyStatus) {
    let path = std::path::Path::new(STATUS_FILE);
    if let Some(parent) = path.parent() {
        if let Err(e) = fs::create_dir_all(parent) {
            log::warn!("runtime_apply: failed to create status dir: {e:#}");
            return;
        }
    }
    match serde_json::to_string_pretty(status) {
        Ok(body) => {
            if let Err(e) = fs::write(path, body) {
                log::warn!("runtime_apply: failed to write status: {e:#}");
            }
        }
        Err(e) => log::warn!("runtime_apply: failed to serialize status: {e:#}"),
    }
}

fn set_status(status: RuntimeApplyStatus) -> RuntimeApplyStatus {
    if let Ok(mut guard) = queue().lock() {
        guard.status = status.clone();
    }
    write_status_file(&status);
    status
}

pub fn status() -> RuntimeApplyStatus {
    queue()
        .lock()
        .map(|guard| guard.status.clone())
        .unwrap_or_else(|_| RuntimeApplyStatus::idle())
}

pub fn status_json() -> serde_json::Value {
    serde_json::to_value(status()).unwrap_or_else(|_| json!({"ok": true, "state": "idle"}))
}

pub fn clear() {
    GENERATION.fetch_add(1, Ordering::AcqRel);
    let status = RuntimeApplyStatus::idle();
    if let Ok(mut guard) = queue().lock() {
        guard.pending.clear();
        guard.worker_running = false;
        guard.status = status.clone();
    }
    write_status_file(&status);
}

pub fn schedule_after_app_save(
    services_running: bool,
    program: &str,
    profile: Option<&str>,
    slot: &str,
) -> RuntimeApplyStatus {
    let task = RuntimeApplyTask {
        program: program.to_string(),
        profile: profile.map(str::to_string).filter(|s| !s.is_empty()),
        slot: slot.to_string(),
    };

    if !services_running {
        return set_status(RuntimeApplyStatus::for_task(
            &task,
            "deferred_until_start",
            "Настройка сохранена и применится после запуска службы".to_string(),
            None,
        ));
    }

    let queued_status = RuntimeApplyStatus::for_task(
        &task,
        "queued",
        format!("Настройка сохранена, правила поставлены в очередь: {}", task.label()),
        None,
    );

    let should_spawn = {
        let mut guard = match queue().lock() {
            Ok(guard) => guard,
            Err(_) => return queued_status,
        };
        guard.pending.insert(task.key(), task);
        if guard.status.state != "running" {
            guard.status = queued_status.clone();
            write_status_file(&queued_status);
        }
        if guard.worker_running {
            false
        } else {
            guard.worker_running = true;
            true
        }
    };

    if should_spawn {
        let generation = GENERATION.load(Ordering::Acquire);
        thread::spawn(move || worker_loop(generation));
    }

    queued_status
}

fn pop_task(generation: u64) -> Option<RuntimeApplyTask> {
    if generation != GENERATION.load(Ordering::Acquire) {
        return None;
    }
    let mut guard = queue().lock().ok()?;
    let key = guard.pending.keys().next().cloned()?;
    guard.pending.remove(&key)
}

fn mark_worker_stopped_if_empty(generation: u64) -> bool {
    let mut guard = match queue().lock() {
        Ok(guard) => guard,
        Err(_) => return true,
    };
    if generation != GENERATION.load(Ordering::Acquire) {
        guard.worker_running = false;
        guard.pending.clear();
        return true;
    }
    if guard.pending.is_empty() {
        guard.worker_running = false;
        true
    } else {
        false
    }
}

fn worker_loop(generation: u64) {
    loop {
        let Some(task) = pop_task(generation) else {
            if mark_worker_stopped_if_empty(generation) {
                return;
            }
            continue;
        };

        set_status(RuntimeApplyStatus::for_task(
            &task,
            "running",
            format!("Применяю правила для {}", task.label()),
            None,
        ));

        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            crate::runtime_refresh::refresh_apps(&task.program, task.profile.as_deref(), &task.slot)
        }));

        if generation != GENERATION.load(Ordering::Acquire) {
            return;
        }

        match result {
            Ok(Ok(crate::runtime_refresh::RefreshOutcome::Applied)) => {
                set_status(RuntimeApplyStatus::for_task(
                    &task,
                    "success",
                    format!("Правила применены: {}", task.label()),
                    None,
                ));
            }
            Ok(Ok(crate::runtime_refresh::RefreshOutcome::NoActiveRuntime)) => {
                set_status(RuntimeApplyStatus::for_task(
                    &task,
                    "no_active_runtime",
                    "Настройка сохранена, активные правила для этого профиля появятся после stop/start".to_string(),
                    None,
                ));
            }
            Ok(Err(e)) => {
                let err = format!("{e:#}");
                log::warn!("runtime_apply: hot apply failed for {}: {err}", task.label());
                set_status(RuntimeApplyStatus::for_task(
                    &task,
                    "failed",
                    "Настройка сохранена, но применить правила сейчас не удалось".to_string(),
                    Some(err),
                ));
            }
            Err(_) => {
                log::error!("runtime_apply: worker panicked for {}", task.label());
                set_status(RuntimeApplyStatus::for_task(
                    &task,
                    "failed",
                    "Настройка сохранена, но применение правил аварийно завершилось".to_string(),
                    Some("runtime apply panic".to_string()),
                ));
            }
        }
    }
}
