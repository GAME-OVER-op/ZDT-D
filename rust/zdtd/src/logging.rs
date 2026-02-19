use anyhow::Result;
use simplelog::*;
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
};

use crate::config::Config;

pub fn init(cfg: &Config) -> Result<()> {
    let level = match cfg.log.level.to_lowercase().as_str() {
        "trace" => LevelFilter::Trace,
        "debug" => LevelFilter::Debug,
        "warn"  => LevelFilter::Warn,
        "error" => LevelFilter::Error,
        _       => LevelFilter::Info,
    };

    // We keep terminal logging for debugging.
    // User-facing logs (for the Android app) are written separately via `user()`
    // into `/data/adb/modules/ZDT-D/log/zdtd.log` and capped to 25 KB.
    let mut loggers: Vec<Box<dyn SharedLogger>> = Vec::new();
    loggers.push(TermLogger::new(
        level,
        simplelog::Config::default(),
        TerminalMode::Mixed,
        ColorChoice::Auto,
    ));

    // Optional extra file logger (developer use). Keep it minimal if enabled.
    if let Some(path) = cfg.log.file.clone() {
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let file = fs::OpenOptions::new().create(true).append(true).open(path)?;
        let cfg = simplelog::ConfigBuilder::new()
            .set_time_level(LevelFilter::Off)
            // NOTE: simplelog's ConfigBuilder doesn't provide a setter to hide
            // the log level prefix itself (INFO/WARN/...). We keep it.
            .set_target_level(LevelFilter::Off)
            .set_location_level(LevelFilter::Off)
            .set_thread_level(LevelFilter::Off)
            .build();
        loggers.push(WriteLogger::new(level, cfg, file));
    }

    CombinedLogger::init(loggers)?;
    Ok(())
}

fn truncate_path(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    // Create or truncate.
    let _ = fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(path)?;
    Ok(())
}

/// Truncates the main log file (used by daemon startup).
///
/// If `Config.log.file` is used, prefer setting that path. Otherwise we fall back
/// to a conventional module log path.
pub fn truncate_main_log() -> Result<()> {
    let path = std::env::var("ZDTD_LOG_FILE")
        .ok()
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/data/adb/modules/ZDT-D/log/zdtd.log"));
    truncate_path(&path)
}

/// Legacy name kept for compatibility with older code.
pub fn truncate_log_file() -> Result<()> {
    truncate_main_log()
}

// --- user-facing (Android app) log -----------------------------------------

const USER_LOG_MAX_BYTES: usize = 25 * 1024;

// Serialize user-log writes to avoid races that can cause duplicated lines
// (e.g. two concurrent start/stop requests both writing the same message).
static USER_LOG_LOCK: Mutex<()> = Mutex::new(());

fn user_log_path() -> PathBuf {
    std::env::var("ZDTD_LOG_FILE")
        .ok()
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/data/adb/modules/ZDT-D/log/zdtd.log"))
}

/// Append a short human-readable line to the main module log.
///
/// Requirements:
/// - no timestamps
/// - no extra details (call sites control the content)
/// - keep last 25 KB
pub fn user(msg: &str) {
    // Prevent concurrent read-check-write races.
    let _guard = match USER_LOG_LOCK.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    let path = user_log_path();
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    // De-duplicate consecutive identical user-facing lines.
    // This protects the UI log from accidental double writes (e.g., a client
    // retrying the same request quickly).
    if let Ok(data) = fs::read(&path) {
        let s = String::from_utf8_lossy(&data);
        if let Some(last) = s.lines().last() {
            if last.trim_end() == msg {
                return;
            }
        }
    }

    if let Ok(mut f) = fs::OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(f, "{}", msg);
        let _ = f.flush();
    }

    let _ = trim_file_keep_last_utf8(&path, USER_LOG_MAX_BYTES);
}

/// Update (replace) the last user-log line that matches `prefix`, otherwise append.
///
/// This is used for progress reporting so we don't spam the log with many lines.
/// The log file is small (capped to 25 KB), so rewriting it is acceptable.
pub fn user_update_line(prefix: &str, msg: &str) {
    // Prevent concurrent read-modify-write races.
    let _guard = match USER_LOG_LOCK.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    let path = user_log_path();
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    let mut lines: Vec<String> = Vec::new();
    if let Ok(data) = fs::read(&path) {
        let s = String::from_utf8_lossy(&data);
        lines.extend(s.lines().map(|l| l.to_string()));
    }

    if let Some(pos) = lines.iter().rposition(|l| l.starts_with(prefix)) {
        lines[pos] = msg.to_string();
    } else {
        lines.push(msg.to_string());
    }

    // Ensure trailing newline for better UX when the UI reads the file.
    let mut out = lines.join("\n");
    out.push('\n');
    let _ = fs::write(&path, out);

    let _ = trim_file_keep_last_utf8(&path, USER_LOG_MAX_BYTES);
}

fn trim_file_keep_last_utf8(path: &Path, max_bytes: usize) -> Result<()> {
    let meta = match fs::metadata(path) {
        Ok(m) => m,
        Err(_) => return Ok(()),
    };
    if meta.len() as usize <= max_bytes {
        return Ok(());
    }
    let data = fs::read(path)?;
    if data.len() <= max_bytes {
        return Ok(());
    }
    let mut start = data.len().saturating_sub(max_bytes);

    // Shift start forward until we get valid UTF-8.
    while start < data.len() {
        if let Ok(_s) = std::str::from_utf8(&data[start..]) {
            break;
        }
        start += 1;
    }

    // Prefer starting from the next newline to keep whole lines.
    if start < data.len() {
        if let Some(pos) = data[start..].iter().position(|b| *b == b'\n') {
            // Avoid dropping everything if the newline is too far.
            if pos < 1024 {
                start = start + pos + 1;
            }
        }
    }

    fs::write(path, &data[start..])?;
    Ok(())
}

// --- thin wrappers used by newer modules -----------------------------------

pub fn info(msg: &str) {
    log::info!("{msg}");
}

pub fn warn(msg: &str) {
    log::warn!("{msg}");
}


// --- user-facing convenience wrappers --------------------------------------

pub fn user_info(msg: &str) {
    user(&format!("INFO  {}", msg));
}

pub fn user_warn(msg: &str) {
    user(&format!("WARN  {}", msg));
}

pub fn user_error(msg: &str) {
    user(&format!("ERROR {}", msg));
}
