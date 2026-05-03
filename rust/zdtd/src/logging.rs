use anyhow::Result;
use simplelog::*;
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    process::Command,
    sync::{
        atomic::{AtomicU8, Ordering},
        Mutex,
    },
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


// --- user-facing language ---------------------------------------------------

const LANG_UNKNOWN: u8 = 0;
const LANG_EN: u8 = 1;
const LANG_RU: u8 = 2;

static USER_LOG_LANG: AtomicU8 = AtomicU8::new(LANG_UNKNOWN);

/// Detect Android UI locale once and keep user-facing file logs in that language.
///
/// Rule:
/// - ru* locale -> Russian
/// - anything else / detection failure -> English
pub fn init_user_locale() {
    let locale = detect_android_locale().unwrap_or_default();
    let lang = if looks_like_russian_locale(&locale) {
        LANG_RU
    } else {
        LANG_EN
    };
    USER_LOG_LANG.store(lang, Ordering::SeqCst);
    log::info!(
        "user log language initialized: locale={:?} lang={}",
        locale,
        if lang == LANG_RU { "ru" } else { "en" }
    );
}

fn current_user_lang() -> u8 {
    let lang = USER_LOG_LANG.load(Ordering::SeqCst);
    if lang != LANG_UNKNOWN {
        return lang;
    }
    // Fallback for safety if a future entrypoint writes user logs before main init.
    init_user_locale();
    USER_LOG_LANG.load(Ordering::SeqCst)
}

fn command_stdout(cmd: &str, args: &[&str]) -> Option<String> {
    let out = Command::new(cmd).args(args).output().ok()?;
    if !out.status.success() {
        return None;
    }
    let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if s.is_empty() { None } else { Some(s) }
}

fn detect_android_locale() -> Option<String> {
    if let Some(s) = command_stdout("getprop", &["persist.sys.locale"]) {
        return Some(s);
    }

    // Common Android fallbacks.
    let lang = command_stdout("getprop", &["persist.sys.language"]);
    let country = command_stdout("getprop", &["persist.sys.country"]);
    if let Some(l) = lang {
        if !l.trim().is_empty() {
            if let Some(c) = country.filter(|v| !v.trim().is_empty()) {
                return Some(format!("{}-{}", l.trim(), c.trim().to_uppercase()));
            }
            return Some(l);
        }
    }
    if let Some(s) = command_stdout("getprop", &["ro.product.locale"]) {
        return Some(s);
    }

    // Last Android-native fallback similar to the shell helper:
    // `am get-config` often has hyphen-separated config fields where the
    // language and region are the 3rd/4th fields on Android ROMs.
    if let Some(config) = command_stdout("am", &["get-config"]) {
        let mut parts = config.split('-');
        let _ = parts.next();
        let _ = parts.next();
        if let Some(lang) = parts.next() {
            if !lang.trim().is_empty() {
                let region = parts
                    .next()
                    .unwrap_or("")
                    .trim()
                    .trim_start_matches('r')
                    .to_ascii_uppercase();
                if region.is_empty() {
                    return Some(lang.trim().to_ascii_lowercase());
                }
                return Some(format!("{}-{}", lang.trim().to_ascii_lowercase(), region));
            }
        }
        return Some(config);
    }

    None
}

fn looks_like_russian_locale(s: &str) -> bool {
    let lower = s.trim().to_ascii_lowercase().replace('_', "-");
    if lower.starts_with("ru") {
        return true;
    }
    lower
        .split(|c: char| !c.is_ascii_alphanumeric())
        .any(|part| part == "ru")
}

fn user_localize(msg: &str) -> String {
    match current_user_lang() {
        LANG_RU => user_localize_ru(msg),
        _ => user_localize_en(msg),
    }
}

fn user_localize_en(msg: &str) -> String {
    match msg {
        "Запуск: инициализация" => return "Startup: initialization".to_string(),
        "Инициализация завершена" => return "Initialization complete".to_string(),
        "Подготовка: запуск" => return "Preparation: start".to_string(),
        "Подготовка: ожидание загрузки Android" => return "Preparation: waiting for Android boot".to_string(),
        "Подготовка: восстановление базовых iptables" => return "Preparation: restoring baseline iptables".to_string(),
        "Запуск завершён" => return "Startup complete".to_string(),
        "Остановка: начало" => return "Stop: start".to_string(),
        "Остановка завершена" => return "Stop complete".to_string(),
        "Ошибка запуска: проверьте настройки" => return "Startup error: check settings".to_string(),
        "Ошибка запуска: внутренний сбой потока" => return "Startup error: internal thread failure".to_string(),
        "Ошибка остановки: внутренний сбой потока" => return "Stop error: internal thread failure".to_string(),
        "Настройка защиты" => return "Protection: setup".to_string(),
        "Применение BlockedQUIC" => return "BlockedQUIC: applying rules".to_string(),
        "DNSCrypt: запуск" => return "DNSCrypt: start".to_string(),
        "DNSCrypt: правила iptables" => return "DNSCrypt: iptables rules".to_string(),
        "DNSCrypt: IPv6 NAT не поддерживается — IPv6 DNS (53) отключён" => {
            return "DNSCrypt: IPv6 NAT is not supported — IPv6 DNS (53) disabled".to_string();
        }
        "Tor: запуск" => return "Tor: start".to_string(),
        "OpenVPN: запуск" => return "OpenVPN: start".to_string(),
        "OpenVPN: ошибка запуска, запуск продолжен" => return "OpenVPN: startup error, continuing".to_string(),
        "OpenVPN: часть профилей не запущена, запуск продолжен" => return "OpenVPN: some profiles did not start, continuing".to_string(),
        "tun2socks: запуск" => return "tun2socks: start".to_string(),
        "tun2socks: ошибка запуска, запуск продолжен" => return "tun2socks: startup error, continuing".to_string(),
        "tun2socks: часть профилей не запущена, запуск продолжен" => return "tun2socks: some profiles did not start, continuing".to_string(),
        "myvpn: запуск" => return "myvpn: start".to_string(),
        "myvpn: ошибка запуска, запуск продолжен" => return "myvpn: startup error, continuing".to_string(),
        "myvpn: часть профилей не применена, запуск продолжен" => return "myvpn: some profiles were not applied, continuing".to_string(),
        "mihomo: запуск" => return "mihomo: start".to_string(),
        "mihomo: ошибка запуска, запуск продолжен" => return "mihomo: startup error, continuing".to_string(),
        "mihomo: часть профилей не запущена, запуск продолжен" => return "mihomo: some profiles did not start, continuing".to_string(),
        "VPN/netd: ошибка применения, запуск продолжен" => return "VPN/netd: apply error, continuing".to_string(),
        "VPN/netd: конфликт tun, запуск продолжен" => return "VPN/netd: TUN conflict, continuing".to_string(),
        "VPN/netd: конфликт профилей, запуск продолжен" => return "VPN/netd: profile conflict, continuing".to_string(),
        "VPN/netd: часть профилей не применена, запуск продолжен" => return "VPN/netd: some profiles were not applied, continuing".to_string(),
        "proxyInfo: защита применена" => return "proxyInfo: protection applied".to_string(),
        "proxyInfo: защита не активировалась" => return "proxyInfo: protection did not activate".to_string(),
        "proxyInfo: не удалось применить защиту" => return "proxyInfo: failed to apply protection".to_string(),
        "BlockedQUIC: правила применены" => return "BlockedQUIC: rules applied".to_string(),
        "BlockedQUIC: не активирован после запуска" => return "BlockedQUIC: not active after startup".to_string(),
        "BlockedQUIC: ошибка применения" => return "BlockedQUIC: apply error".to_string(),
        _ => {}
    }

    if let Some(rest) = msg.strip_prefix("DNSCrypt: ещё не отвечает, повтор через ") {
        return format!("DNSCrypt: not ready yet, retry in {rest}");
    }
    if let Some(rest) = msg.strip_prefix("DNSCrypt: ошибка проверки готовности, повтор через ") {
        return format!("DNSCrypt: readiness check error, retry in {rest}");
    }
    if let Some(name) = msg.strip_suffix(": ошибка запуска") {
        return format!("{name}: startup error");
    }
    if let Some(name) = msg.strip_suffix(": аварийное завершение потока запуска") {
        return format!("{name}: startup thread panicked");
    }
    msg.replace(": запуск", ": start")
}

fn user_localize_ru(msg: &str) -> String {
    match msg {
        "myprogram: custom profiles" => "myprogram: пользовательские профили".to_string(),
        "myproxy: socks5 profiles" => "myproxy: socks5 профили".to_string(),
        "sing-box: socks5 profiles" => "sing-box: socks5 профили".to_string(),
        "wireproxy: socks5 profiles" => "wireproxy: socks5 профили".to_string(),
        "Startup: initialization" => "Запуск: инициализация".to_string(),
        "Initialization complete" => "Инициализация завершена".to_string(),
        "Preparation: start" => "Подготовка: запуск".to_string(),
        "Preparation: waiting for Android boot" => "Подготовка: ожидание загрузки Android".to_string(),
        "Preparation: restoring baseline iptables" => "Подготовка: восстановление базовых iptables".to_string(),
        "Startup complete" => "Запуск завершён".to_string(),
        "Stop: start" => "Остановка: начало".to_string(),
        "Stop complete" => "Остановка завершена".to_string(),
        "OpenVPN: start" => "OpenVPN: запуск".to_string(),
        "tun2socks: start" => "tun2socks: запуск".to_string(),
        "Tor: start" => "Tor: запуск".to_string(),
        "DNSCrypt: start" => "DNSCrypt: запуск".to_string(),
        _ => msg.to_string(),
    }
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
    user(&format!("INFO  {}", user_localize(msg)));
}

pub fn user_warn(msg: &str) {
    user(&format!("WARN  {}", user_localize(msg)));
}

pub fn user_error(msg: &str) {
    user(&format!("ERROR {}", user_localize(msg)));
}
