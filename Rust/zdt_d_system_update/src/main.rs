use anyhow::{anyhow, Context, Result};
use chrono::{Datelike, Local, NaiveDate, NaiveDateTime};
use reqwest::blocking::Client;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    env,
    ffi::OsStr,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    process::Command,
    thread,
    time::Duration,
};

const LOGTAG: &str = "ZDT-D-update-daemon";

// Paths (как в скрипте)
const DAM: &str = "/data/adb/modules/ZDT-D";
const UPDATE_MOD: &str = "/data/adb/modules/ZDT-D-UPDATE";

const REMOTE_MANIFEST_URL: &str =
    "https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/Update/system_update.json";
const LOCAL_MANIFEST: &str = "/data/adb/modules/ZDT-D/files/system_update.json";

const INDEX_REMOTE_URL_DEFAULT: &str =
    "https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/Update/index.html";
const INDEX_LOCAL_PATH_DEFAULT: &str = "/data/adb/modules/ZDT-D-UPDATE/webroot/index.html";

const DEFAULT_MODE_SH: &str = "0755";

// --- Config from env (как в скрипте) ---
#[derive(Clone, Debug)]
struct Cfg {
    run_hhmm: String,              // "0200"
    ping_target: String,           // "8.8.8.8"
    net_retry_count: u32,          // 2
    net_retry_sleep: u64,          // 1800
    wait_off_after_install_sec: u64,// 300
    on_stable_sec: u64,            // 7
    open_after_sec: u64,           // 15
    delete_update_mod_after_open_sec: u64, // 15
    wait_off_max_sec: u64,         // 43200
    wait_on_max_sec: u64,          // 43200
    log_to_stderr: bool,           // 1
}

impl Cfg {
    fn from_env() -> Self {
        let run_hhmm = env::var("RUN_HHMM").unwrap_or_else(|_| "0200".to_string());
        let ping_target = env::var("PING_TARGET").unwrap_or_else(|_| "8.8.8.8".to_string());

        let net_retry_count = env::var("NET_RETRY_COUNT")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(2);

        let net_retry_sleep = env::var("NET_RETRY_SLEEP")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(1800);

        let wait_off_after_install_sec = env::var("WAIT_OFF_AFTER_INSTALL_SEC")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(300);

        let on_stable_sec = env::var("ON_STABLE_SEC")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(7);

        let open_after_sec = env::var("OPEN_AFTER_SEC")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(15);

        let delete_update_mod_after_open_sec = env::var("DELETE_UPDATE_MOD_AFTER_OPEN_SEC")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(15);

        let wait_off_max_sec = env::var("WAIT_OFF_MAX_SEC")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(43200);

        let wait_on_max_sec = env::var("WAIT_ON_MAX_SEC")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(43200);

        let log_to_stderr = env::var("LOG_TO_STDERR").unwrap_or_else(|_| "1".into()) == "1";

        Self {
            run_hhmm,
            ping_target,
            net_retry_count,
            net_retry_sleep,
            wait_off_after_install_sec,
            on_stable_sec,
            open_after_sec,
            delete_update_mod_after_open_sec,
            wait_off_max_sec,
            wait_on_max_sec,
            log_to_stderr,
        }
    }
}

// -------- Logging --------
fn now_string() -> String {
    Local::now().format("%Y-%m-%d %H:%M:%S").to_string()
}

fn mkdir_p(p: &Path) {
    let _ = fs::create_dir_all(p);
}

fn log_line(cfg: &Cfg, logfile: &Path, msg: &str) {
    mkdir_p(logfile.parent().unwrap_or(Path::new("/")));
    let line = format!("{} [{}] {}", now_string(), LOGTAG, msg);
    let _ = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(logfile)
        .and_then(|mut f| writeln!(f, "{}", line));
    if cfg.log_to_stderr {
        eprintln!("{}", line);
    }
}

// -------- Lock dir (mkdir atomic-ish) --------
struct LockGuard {
    lockdir: PathBuf,
}
impl Drop for LockGuard {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.lockdir);
    }
}

fn acquire_lock(cfg: &Cfg, logfile: &Path, working: &Path) -> Option<LockGuard> {
    mkdir_p(working);
    let lockdir = working.join(".update_lock");
    match fs::create_dir(&lockdir) {
        Ok(_) => {
            let _ = fs::write(lockdir.join("pid"), format!("{}", std::process::id()));
            log_line(cfg, logfile, &format!("LOCK acquired: {} (pid={})", lockdir.display(), std::process::id()));
            Some(LockGuard { lockdir })
        }
        Err(_) => {
            let oldpid = fs::read_to_string(lockdir.join("pid")).ok();
            log_line(cfg, logfile, &format!("LOCK busy: {} (pid={}) -> skip", lockdir.display(), oldpid.unwrap_or_else(|| "unknown".into())));
            None
        }
    }
}

// -------- Time: sleep until next run (энергоэффективно) --------
fn parse_hhmm(hhmm: &str) -> Result<(u32, u32)> {
    if hhmm.len() != 4 {
        return Err(anyhow!("RUN_HHMM must be 4 digits, got {}", hhmm));
    }
    let hh: u32 = hhmm[0..2].parse()?;
    let mm: u32 = hhmm[2..4].parse()?;
    if hh > 23 || mm > 59 {
        return Err(anyhow!("RUN_HHMM invalid: {}", hhmm));
    }
    Ok((hh, mm))
}

fn today_date() -> NaiveDate {
    Local::now().naive_local().date()
}

fn local_now_naive() -> NaiveDateTime {
    Local::now().naive_local()
}

fn next_run_datetime(run_hhmm: &str) -> Result<NaiveDateTime> {
    let (hh, mm) = parse_hhmm(run_hhmm)?;
    let now = local_now_naive();
    let today = now.date();
    let today_run = today.and_hms_opt(hh, mm, 0).ok_or_else(|| anyhow!("bad run time"))?;
    if now <= today_run {
        Ok(today_run)
    } else {
        let tomorrow = today.succ_opt().ok_or_else(|| anyhow!("date overflow"))?;
        Ok(tomorrow.and_hms_opt(hh, mm, 0).unwrap())
    }
}

fn should_run_now(run_hhmm: &str, last_run_date: Option<NaiveDate>) -> Result<bool> {
    let (hh, mm) = parse_hhmm(run_hhmm)?;
    let now = local_now_naive();
    let run_today = now.date().and_hms_opt(hh, mm, 0).unwrap();
    let already = last_run_date == Some(now.date());
    Ok(!already && now >= run_today)
}

// -------- Utilities --------
fn is_ext_text_like(name: &str) -> bool {
    matches!(
        Path::new(name).extension().and_then(OsStr::to_str).unwrap_or("").to_ascii_lowercase().as_str(),
        "sh" | "py" | "json" | "conf" | "ini" | "txt" | "list" | "html"
    )
}

fn looks_like_html_or_error(bytes: &[u8]) -> bool {
    let head = &bytes[..bytes.len().min(256)];
    let s = String::from_utf8_lossy(head).replace('\r', "").to_ascii_lowercase();
    let pats = [
        "<!doctype html", "<html", "<head", "<body",
        "404: not found", "access denied", "captcha", "cloudflare", "just a moment",
    ];
    pats.iter().any(|p| s.contains(p))
}

fn strip_bom_and_sanitize_json(mut bytes: Vec<u8>) -> Vec<u8> {
    // remove NUL and CR
    bytes.retain(|&b| b != 0u8 && b != b'\r');
    // strip UTF-8 BOM
    if bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
        bytes.drain(0..3);
    }
    bytes
}

fn sha256_file(p: &Path) -> Result<String> {
    let mut f = fs::File::open(p)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 { break; }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn parse_mode_octal(mode: &str) -> u32 {
    u32::from_str_radix(mode.trim_start_matches('0'), 8).unwrap_or(0o755)
}

#[cfg(unix)]
fn chmod_mode(path: &Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    if let Ok(meta) = fs::metadata(path) {
        let mut perms = meta.permissions();
        perms.set_mode(mode);
        let _ = fs::set_permissions(path, perms);
    }
}

// -------- Download --------
fn http_client() -> Result<Client> {
    Ok(Client::builder()
        .timeout(Duration::from_secs(60))
        .build()?)
}

fn download_url(client: &Client, url: &str) -> Result<Vec<u8>> {
    let resp = client.get(url).send().with_context(|| format!("GET {}", url))?;
    if !resp.status().is_success() {
        return Err(anyhow!("HTTP {} for {}", resp.status(), url));
    }
    let bytes = resp.bytes()?.to_vec();
    if bytes.is_empty() {
        return Err(anyhow!("empty download: {}", url));
    }
    Ok(bytes)
}

// -------- Internet check (ping if available, else HTTP) --------
fn have_cmd(cmd: &str) -> bool {
    Command::new("sh")
        .arg("-c")
        .arg(format!("command -v {} >/dev/null 2>&1", cmd))
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn check_internet_once(cfg: &Cfg, client: &Client) -> bool {
    if have_cmd("ping") {
        let st = Command::new("ping")
            .args(["-c", "1", &cfg.ping_target])
            .status();
        if st.map(|s| s.success()).unwrap_or(false) {
            return true;
        }
    }
    // fallback: lightweight endpoint
    let url = "https://clients3.google.com/generate_204";
    client.get(url).send().map(|r| r.status().is_success()).unwrap_or(false)
}

fn ensure_internet_with_retries(cfg: &Cfg, logfile: &Path, client: &Client) -> bool {
    for i in 1..=cfg.net_retry_count {
        log_line(cfg, logfile, &format!("Проверка интернета (попытка {}/{}) ...", i, cfg.net_retry_count));
        if check_internet_once(cfg, client) {
            log_line(cfg, logfile, "Интернет OK.");
            return true;
        }
        log_line(cfg, logfile, "Интернета нет.");
        if i < cfg.net_retry_count {
            log_line(cfg, logfile, &format!("Жду {}s и повторяю...", cfg.net_retry_sleep));
            thread::sleep(Duration::from_secs(cfg.net_retry_sleep));
        }
    }
    log_line(cfg, logfile, "Интернета нет после попыток -> перенос на следующий день.");
    false
}

// -------- Manifest parsing (поддержка: object.files[], array, single object) --------
#[derive(Debug, Deserialize, Clone)]
struct Entry {
    name: Option<String>,
    deviceDir: Option<String>,
    devicePath: Option<String>,
    githubDir: Option<String>,
    rawUrl: Option<String>,
    sha256: Option<String>,
    mode: Option<String>,

    // optional per-file overrides (в оригинале читается только из manifest/jq, но оставим)
    infoPageGitHub: Option<String>,
    infoPageDevice: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ManifestObject {
    files: Option<Vec<Entry>>,
    infoPageGitHub: Option<String>,
    infoPageDevice: Option<String>,
}

fn parse_entries_and_infopage(manifest_bytes: &[u8]) -> Result<(Vec<Entry>, String, String)> {
    let v: serde_json::Value = serde_json::from_slice(manifest_bytes)
        .context("manifest JSON parse failed")?;

    let mut entries: Vec<Entry> = vec![];
    let mut ipg: Option<String> = None;
    let mut ipd: Option<String> = None;

    match v {
        serde_json::Value::Object(_) => {
            // try object with files
            let mo: ManifestObject = serde_json::from_slice(manifest_bytes)?;
            if let Some(files) = mo.files.clone() {
                entries = files;
            } else {
                // single object as entry
                let e: Entry = serde_json::from_slice(manifest_bytes)?;
                entries = vec![e];
            }
            ipg = mo.infoPageGitHub;
            ipd = mo.infoPageDevice;

            // fallback: first file overrides
            if ipg.is_none() {
                if let Some(f0) = entries.get(0).and_then(|e| e.infoPageGitHub.clone()) {
                    ipg = Some(f0);
                }
            }
            if ipd.is_none() {
                if let Some(f0) = entries.get(0).and_then(|e| e.infoPageDevice.clone()) {
                    ipd = Some(f0);
                }
            }
        }
        serde_json::Value::Array(_) => {
            entries = serde_json::from_slice(manifest_bytes)?;
        }
        _ => return Err(anyhow!("unexpected JSON type")),
    }

    let ipg = ipg.unwrap_or_else(|| INDEX_REMOTE_URL_DEFAULT.to_string());
    let ipd = ipd.unwrap_or_else(|| INDEX_LOCAL_PATH_DEFAULT.to_string());
    Ok((entries, ipg, ipd))
}

fn github_to_raw_url(u: &str) -> String {
    u.replace("https://github.com/", "https://raw.githubusercontent.com/")
        .replace("/blob/", "/")
        .replace("/tree/", "/")
}

fn derive_raw_url(github_dir_or_raw: &str, name: &str) -> String {
    let mut base = if github_dir_or_raw.starts_with("https://raw.githubusercontent.com/")
        || github_dir_or_raw.starts_with("http://raw.githubusercontent.com/")
    {
        github_dir_or_raw.to_string()
    } else {
        github_to_raw_url(github_dir_or_raw)
    };

    if base.ends_with('/') {
        base.push_str(name);
        base
    } else {
        // в оригинале: если не /, то считаем что это уже полный путь
        base
    }
}

fn normalize_infopage_remote(ipg: &str) -> String {
    if ipg.starts_with("https://github.com/") {
        github_to_raw_url(ipg)
    } else {
        ipg.to_string()
    }
}

fn normalize_infopage_local(ipd: &str) -> String {
    let mut p = ipd.trim().to_string();
    if p.ends_with('/') {
        p.push_str("index.html");
    } else if !p.ends_with("index.html") {
        // как в скрипте: ipd -> ".../index.html"
        p = format!("{}/index.html", p.trim_end_matches('/'));
    }
    p
}

// -------- WebUI skeleton & actions --------
fn ensure_update_webui_skeleton(cfg: &Cfg, logfile: &Path, dam: &Path, update_mod: &Path) {
    mkdir_p(&update_mod.join("webroot/data"));

    let module_prop = update_mod.join("module.prop");
    if !module_prop.exists() {
        log_line(cfg, logfile, &format!("Создаю {}", module_prop.display()));
        mkdir_p(update_mod);
        let content = "id=ZDT-D-UPDATE\nname=ZDT-D-UPDATE\nversion=0\nversionCode=0\nauthor=UPDATE\n";
        let _ = fs::write(&module_prop, content);
    }

    let src = dam.join("webroot/kernelsu.js");
    let dst = update_mod.join("webroot/kernelsu.js");
    if src.exists() && !dst.exists() {
        log_line(cfg, logfile, "Копирую kernelsu.js -> ZDT-D-UPDATE/webroot/");
        let _ = fs::copy(src, dst);
    }
}

fn refresh_webui_index(cfg: &Cfg, logfile: &Path, client: &Client, remote: &str, local_path: &Path) {
    log_line(cfg, logfile, &format!("Скачиваю index.html (WebUI) -> {}", local_path.display()));
    match download_url(client, remote) {
        Ok(bytes) => {
            mkdir_p(local_path.parent().unwrap_or(Path::new("/")));
            let _ = fs::write(local_path, bytes);
            log_line(cfg, logfile, &format!("OK: index.html обновлён -> {}", local_path.display()));
        }
        Err(e) => {
            log_line(cfg, logfile, &format!("WARN: не удалось скачать index.html: {}", e));
        }
    }
}

fn esc_single_quotes(s: &str) -> String {
    s.replace('\'', "'\"'\"'")
}

fn send_notification(cfg: &Cfg, logfile: &Path, msg: &str, tag: &str, title: &str) {
    if !have_cmd("su") {
        log_line(cfg, logfile, "WARN: su не найден — уведомление не отправлено");
        return;
    }

    let body = format!("ZDT-D:{}", msg);
    let icon1 = Path::new("/data/local/tmp/icon1.png");
    let icon2 = Path::new("/data/local/tmp/icon2.png");
    let icon_arg = if icon1.exists() && icon2.exists() {
        format!(
            "-i file:///data/local/tmp/icon1.png -I file:///data/local/tmp/icon2.png"
        )
    } else {
        "".to_string()
    };

    let body_esc = esc_single_quotes(&body);
    let tag_esc = esc_single_quotes(tag);
    let title_esc = esc_single_quotes(title);

    let cmd = format!(
        "cmd notification post {} -S messaging --conversation 'ZDT-D' --message '{}' -t '{}' '{}' '{}'",
        icon_arg, body_esc, title_esc, tag_esc, body_esc
    );

    let st = Command::new("su")
        .args(["-lp", "2000", "-c", &cmd])
        .status();

    match st {
        Ok(s) if s.success() => log_line(cfg, logfile, &format!("OK: уведомление отправлено (tag={})", tag)),
        Ok(s) => log_line(cfg, logfile, &format!("WARN: уведомление не отправилось (rc={})", s.code().unwrap_or(-1))),
        Err(e) => log_line(cfg, logfile, &format!("WARN: уведомление не отправилось: {}", e)),
    }
}

// --- Screen state (как в скрипте) ---
fn is_screen_off() -> bool {
    if have_cmd("dumpsys") {
        if let Ok(out) = Command::new("dumpsys").arg("power").output() {
            let s = String::from_utf8_lossy(&out.stdout);
            let sl = s.to_ascii_lowercase();
            if sl.contains("minteractive=false")
                || sl.contains("minteractive=0")
                || sl.contains("state=off")
            {
                return true;
            }
        }
    }

    // /sys/class/graphics/fb*/blank
    if let Ok(entries) = glob_paths("/sys/class/graphics/fb*/blank") {
        for p in entries {
            if let Ok(v) = fs::read_to_string(&p) {
                let v = v.trim();
                if !v.is_empty() && v != "0" {
                    return true;
                }
            }
        }
    }

    // /sys/class/backlight/*/brightness
    if let Ok(entries) = glob_paths("/sys/class/backlight/*/brightness") {
        for p in entries {
            if let Ok(v) = fs::read_to_string(&p) {
                if v.trim() == "0" {
                    return true;
                }
            }
        }
    }

    false
}

// tiny glob helper (без crate)
fn glob_paths(pattern: &str) -> Result<Vec<PathBuf>> {
    // очень простой "glob": только * в одном сегменте
    // достаточно для fb*/blank и backlight/*/brightness
    let parts: Vec<&str> = pattern.split('/').filter(|s| !s.is_empty()).collect();
    let mut cur: Vec<PathBuf> = vec![PathBuf::from("/")];

    for part in parts {
        let mut next = vec![];
        if part.contains('*') {
            let (prefix, suffix) = {
                let mut sp = part.split('*');
                (sp.next().unwrap_or(""), sp.next().unwrap_or(""))
            };
            for base in cur {
                if let Ok(rd) = fs::read_dir(&base) {
                    for e in rd.flatten() {
                        let name = e.file_name().to_string_lossy().to_string();
                        if name.starts_with(prefix) && name.ends_with(suffix) {
                            next.push(e.path());
                        }
                    }
                }
            }
        } else {
            for base in cur {
                next.push(base.join(part));
            }
        }
        cur = next;
    }

    Ok(cur.into_iter().filter(|p| p.exists()).collect())
}

fn wait_screen_off_stable(cfg: &Cfg, logfile: &Path, need: u64, max: u64) -> bool {
    log_line(cfg, logfile, &format!("Жду экран OFF непрерывно {}s (max wait {}s)...", need, max));
    let mut off = 0u64;
    let mut waited = 0u64;
    while waited < max {
        if is_screen_off() {
            off += 5;
            if off == 5 {
                log_line(cfg, logfile, "Экран OFF (таймер пошёл)");
            }
            if off >= need {
                log_line(cfg, logfile, &format!("OFF условие выполнено ({}s)", off));
                return true;
            }
        } else {
            if off > 0 {
                log_line(cfg, logfile, &format!("Экран включился -> сброс OFF (было {}s)", off));
            }
            off = 0;
        }
        thread::sleep(Duration::from_secs(5));
        waited += 5;
    }
    log_line(cfg, logfile, &format!("OFF условие не выполнено за {}s", max));
    false
}

fn wait_screen_on_stable(cfg: &Cfg, logfile: &Path, need: u64, max: u64) -> bool {
    log_line(cfg, logfile, &format!("Жду экран ON непрерывно {}s (max wait {}s)...", need, max));
    let mut on = 0u64;
    let mut waited = 0u64;
    while waited < max {
        if is_screen_off() {
            if on > 0 {
                log_line(cfg, logfile, &format!("Экран снова OFF -> сброс ON (было {}s)", on));
            }
            on = 0;
        } else {
            on += 1;
            if on == 1 {
                log_line(cfg, logfile, "Экран ON (таймер пошёл)");
            }
            if on >= need {
                log_line(cfg, logfile, &format!("ON условие выполнено ({}s)", on));
                return true;
            }
        }
        thread::sleep(Duration::from_secs(1));
        waited += 1;
    }
    log_line(cfg, logfile, &format!("ON условие не выполнено за {}s", max));
    false
}

fn open_webui(cfg: &Cfg, logfile: &Path) {
    let cmd = r#"am start -n io.github.a13e300.ksuwebui/.WebUIActivity --es id "ZDT-D-UPDATE""#;
    log_line(cfg, logfile, &format!("OPEN WebUI: {}", cmd));

    if have_cmd("su") {
        let _ = Command::new("su").args(["-lp", "2000", "-c", cmd]).status();
    } else {
        let _ = Command::new("sh").args(["-c", cmd]).status();
    }
    log_line(cfg, logfile, "OK: команда открытия отправлена");
}

fn is_webui_running() -> bool {
    let pkg = "io.github.a13e300.ksuwebui";

    if have_cmd("pidof") {
        if let Ok(out) = Command::new("pidof").arg(pkg).output() {
            return !String::from_utf8_lossy(&out.stdout).trim().is_empty();
        }
    }
    if have_cmd("pgrep") {
        if let Ok(out) = Command::new("pgrep").args(["-f", pkg]).output() {
            return !String::from_utf8_lossy(&out.stdout).trim().is_empty();
        }
    }
    if have_cmd("ps") {
        if let Ok(out) = Command::new("ps").output() {
            let s = String::from_utf8_lossy(&out.stdout);
            if s.contains(pkg) {
                return true;
            }
        }
        if let Ok(out) = Command::new("ps").arg("-A").output() {
            let s = String::from_utf8_lossy(&out.stdout);
            if s.contains(pkg) {
                return true;
            }
        }
    }
    false
}

fn delete_update_module_after_open_guarded(cfg: &Cfg, logfile: &Path, update_mod: &Path, sec: u64) {
    if !update_mod.exists() {
        log_line(cfg, logfile, &format!("DELETE: {} отсутствует -> пропуск", update_mod.display()));
        return;
    }

    log_line(cfg, logfile, &format!(
        "DELETE: защита — удалю {} через {}s, только если WebUI стартовал",
        update_mod.display(),
        sec
    ));

    let t0 = std::time::Instant::now();
    let mut started = false;

    while t0.elapsed() < Duration::from_secs(sec) {
        if is_webui_running() {
            started = true;
            log_line(cfg, logfile, "DELETE: WebUI процесс обнаружен -> удаление разрешено");
            break;
        }
        thread::sleep(Duration::from_secs(1));
    }

    if !started {
        log_line(cfg, logfile, &format!("DELETE: WebUI не стартовал за {}s -> модуль НЕ удаляю", sec));
        return;
    }

    // дождаться полного интервала sec (как в скрипте)
    let elapsed = t0.elapsed().as_secs();
    if sec > elapsed {
        thread::sleep(Duration::from_secs(sec - elapsed));
    }

    let _ = fs::remove_dir_all(update_mod);
    log_line(cfg, logfile, &format!("DELETE: муляж-модуль удалён: {}", update_mod.display()));
}

// -------- Update phases --------
#[derive(Clone, Debug)]
struct InstallItem {
    name: String,
    device_path: PathBuf,
    mode_octal: u32,
}

fn run_daily_cycle(cfg: &Cfg, client: &Client) -> Result<()> {
    let dam = Path::new(DAM);
    let update_mod = Path::new(UPDATE_MOD);

    let working = dam.join("working_folder");
    let logdir = dam.join("log");
    let logfile = logdir.join("system_update.log");
    let download_dir = dam.join("download");

    // lock
    let _lock = match acquire_lock(cfg, &logfile, &working) {
        Some(g) => g,
        None => return Ok(()),
    };

    mkdir_p(&download_dir);
    let _ = fs::remove_file(download_dir.join("system_update.entries.jsonl"));
    let _ = fs::remove_file(download_dir.join("system_update.toinstall.list"));
    let _ = fs::remove_file(download_dir.join("system_update.json"));
    let _ = fs::remove_file(download_dir.join("index.html"));

    log_line(cfg, &logfile, "=== DAILY CYCLE START ===");

    if !ensure_internet_with_retries(cfg, &logfile, client) {
        log_line(cfg, &logfile, "=== DAILY CYCLE END (no internet) ===");
        return Ok(());
    }

    // download manifest
    let tmp_manifest_path = download_dir.join("system_update.json");
    log_line(cfg, &logfile, &format!("Скачиваю manifest -> {}", tmp_manifest_path.display()));
    let mut manifest_bytes = match download_url(client, REMOTE_MANIFEST_URL) {
        Ok(b) => b,
        Err(e) => {
            log_line(cfg, &logfile, &format!("ERROR: не удалось скачать manifest: {}", e));
            log_line(cfg, &logfile, "=== DAILY CYCLE END (manifest fail) ===");
            return Ok(());
        }
    };

    if looks_like_html_or_error(&manifest_bytes) {
        log_line(cfg, &logfile, "ERROR: manifest похож на HTML/ошибку (подмена/блок)");
        log_line(cfg, &logfile, "=== DAILY CYCLE END (bad manifest content) ===");
        return Ok(());
    }

    manifest_bytes = strip_bom_and_sanitize_json(manifest_bytes);

    // save local manifest
    mkdir_p(Path::new(LOCAL_MANIFEST).parent().unwrap());
    let _ = fs::write(LOCAL_MANIFEST, &manifest_bytes);
    let _ = fs::write(&tmp_manifest_path, &manifest_bytes);
    log_line(cfg, &logfile, &format!("Manifest сохранён локально: {}", LOCAL_MANIFEST));

    // parse entries
    let (entries, ipg_raw, ipd_raw) = match parse_entries_and_infopage(&manifest_bytes) {
        Ok(x) => x,
        Err(e) => {
            log_line(cfg, &logfile, &format!("ERROR: manifest parse failed: {}", e));
            log_line(cfg, &logfile, "=== DAILY CYCLE END (bad manifest) ===");
            return Ok(());
        }
    };

    let index_remote = normalize_infopage_remote(&ipg_raw);
    let index_local = PathBuf::from(normalize_infopage_local(&ipd_raw));

    log_line(cfg, &logfile, &format!("INFO PAGE: remote={}", index_remote));
    log_line(cfg, &logfile, &format!("INFO PAGE: local ={}/", index_local.parent().unwrap_or(Path::new("")).display()));

    // decide what to download
    let mut to_install: Vec<InstallItem> = vec![];
    let mut need = 0u32;
    let mut okdl = 0u32;
    let mut fail = 0u32;

    for item in entries {
        let name = match item.name.clone().filter(|s| !s.is_empty()) {
            Some(n) => n,
            None => continue,
        };

        // devicePath
        let device_path = if let Some(dp) = item.devicePath.clone().filter(|s| !s.is_empty() && s != "null") {
            PathBuf::from(dp)
        } else {
            let dd = item.deviceDir.clone().unwrap_or_default().trim_end_matches('/').to_string();
            PathBuf::from(format!("{}/{}", dd, name))
        };

        // rawUrl
        let raw_url = if let Some(ru) = item.rawUrl.clone().filter(|s| !s.is_empty() && s != "null") {
            ru
        } else {
            let gd = item.githubDir.clone().unwrap_or_default();
            derive_raw_url(&gd, &name)
        };

        let want_sha = item.sha256.clone().unwrap_or_default();
        let local_sha = if device_path.exists() {
            sha256_file(&device_path).ok().unwrap_or_default()
        } else {
            "".into()
        };

        if !want_sha.is_empty() && !local_sha.is_empty() && want_sha == local_sha {
            log_line(cfg, &logfile, &format!("OK: {} (sha совпал) -> не нужно", name));
            continue;
        }

        need += 1;
        log_line(cfg, &logfile, &format!("NEED: {}", name));
        log_line(cfg, &logfile, &format!("  devicePath: {}", device_path.display()));
        log_line(cfg, &logfile, &format!("  rawUrl    : {}", raw_url));

        // download to .part
        let out_part = download_dir.join(format!("{}.part", name));
        let out_ready = download_dir.join(&name);
        let _ = fs::remove_file(&out_part);
        let _ = fs::remove_file(&out_ready);

        let bytes = match download_url(client, &raw_url) {
            Ok(b) => b,
            Err(e) => {
                log_line(cfg, &logfile, &format!("ERROR: download failed for {}: {}", name, e));
                fail += 1;
                continue;
            }
        };

        if is_ext_text_like(&name) && looks_like_html_or_error(&bytes) {
            log_line(cfg, &logfile, &format!("ERROR: скачалось похоже HTML/ошибка -> {}", name));
            let _ = fs::write(download_dir.join(format!("{}.bad", name)), &bytes);
            fail += 1;
            continue;
        }

        // write part then sha
        fs::write(&out_part, &bytes).ok();
        let got_sha = sha256_file(&out_part).unwrap_or_default();
        log_line(cfg, &logfile, &format!("  downloadedSha: {}", got_sha));

        if !want_sha.is_empty() && want_sha != got_sha {
            log_line(cfg, &logfile, &format!("ERROR: sha mismatch for {}", name));
            let _ = fs::rename(&out_part, download_dir.join(format!("{}.bad", name)));
            fail += 1;
            continue;
        }

        let _ = fs::rename(&out_part, &out_ready);

        let mode_str = item.mode.clone().filter(|s| !s.is_empty() && s != "null").unwrap_or_else(|| DEFAULT_MODE_SH.to_string());
        let mode_octal = parse_mode_octal(&mode_str);

        to_install.push(InstallItem { name, device_path, mode_octal });
        okdl += 1;
    }

    log_line(cfg, &logfile, &format!("DOWNLOAD SUMMARY: need={} okdl={} fail={}", need, okdl, fail));

    if need == 0 {
        log_line(cfg, &logfile, "Обновления не требуются.");
        log_line(cfg, &logfile, "=== DAILY CYCLE END (nothing) ===");
        return Ok(());
    }
    if fail != 0 || okdl != need {
        log_line(cfg, &logfile, "ERROR: не удалось скачать все нужные файлы. download/ оставляю для диагностики.");
        log_line(cfg, &logfile, "=== DAILY CYCLE END (download fail) ===");
        return Ok(());
    }

    // install
    let mut installed = 0u32;
    let mut failed = 0u32;

    for it in &to_install {
        let src = download_dir.join(&it.name);
        let dst = it.device_path.clone();
        let dst_dir = dst.parent().unwrap_or(Path::new("/"));

        log_line(cfg, &logfile, &format!("INSTALL: {}", it.name));
        log_line(cfg, &logfile, &format!("  src: {}", src.display()));
        log_line(cfg, &logfile, &format!("  dst: {}", dst.display()));
        log_line(cfg, &logfile, &format!("  mode: {:o}", it.mode_octal));

        if !src.exists() {
            log_line(cfg, &logfile, &format!("ERROR: src missing {}", src.display()));
            failed += 1;
            continue;
        }

        mkdir_p(dst_dir);

        if dst.exists() {
            let bdir = working.join("backup");
            mkdir_p(&bdir);
            let ts = Local::now().format("%Y%m%d_%H%M%S").to_string();
            let backup = bdir.join(format!("{}.{}.bak", it.name, ts));
            let _ = fs::copy(&dst, &backup);
        }

        match fs::rename(&src, &dst) {
            Ok(_) => {
                chmod_mode(&dst, it.mode_octal);
                installed += 1;
                log_line(cfg, &logfile, &format!("OK: installed {}", it.name));
            }
            Err(e) => {
                failed += 1;
                log_line(cfg, &logfile, &format!("ERROR: install failed {}: {}", it.name, e));
            }
        }
    }

    log_line(cfg, &logfile, &format!("INSTALL SUMMARY: installed={} failed={}", installed, failed));
    if failed != 0 || installed == 0 {
        log_line(cfg, &logfile, "ERROR: установка с ошибками. download/ оставляю.");
        log_line(cfg, &logfile, "=== DAILY CYCLE END (install fail) ===");
        return Ok(());
    }

    // WebUI + index
    ensure_update_webui_skeleton(cfg, &logfile, dam, update_mod);
    refresh_webui_index(cfg, &logfile, client, &index_remote, &index_local);

    // remove download dir
    log_line(cfg, &logfile, &format!("Удаляю папку download: {}", download_dir.display()));
    let _ = fs::remove_dir_all(&download_dir);

    // notify done
    send_notification(cfg, &logfile, "Произведена установка важных обновлений", "UpdateDone", "Установка завершена");

    // UX flow (без lock)
    log_line(cfg, &logfile, &format!(
        "Теперь жду OFF {}s (после установки), чтобы не мешать...",
        cfg.wait_off_after_install_sec
    ));

    if !wait_screen_off_stable(cfg, &logfile, cfg.wait_off_after_install_sec, cfg.wait_off_max_sec) {
        log_line(cfg, &logfile, "Не дождался OFF окна -> показ страницы отменён.");
        log_line(cfg, &logfile, "=== DAILY CYCLE END (no OFF window for UX) ===");
        return Ok(());
    }

    if !wait_screen_on_stable(cfg, &logfile, cfg.on_stable_sec, cfg.wait_on_max_sec) {
        log_line(cfg, &logfile, "Не дождался ON окна -> показ страницы отменён.");
        log_line(cfg, &logfile, "=== DAILY CYCLE END (no ON window for UX) ===");
        return Ok(());
    }

    send_notification(
        cfg,
        &logfile,
        "Подождите пожалуйста, сейчас откроется страница с информацией об обновлениях",
        "UpdateOpen",
        "Информация об обновлениях",
    );

    log_line(cfg, &logfile, &format!("Жду {}s перед открытием WebUI...", cfg.open_after_sec));
    thread::sleep(Duration::from_secs(cfg.open_after_sec));
    open_webui(cfg, &logfile);
    delete_update_module_after_open_guarded(cfg, &logfile, update_mod, cfg.delete_update_mod_after_open_sec);

    log_line(cfg, &logfile, "=== DAILY CYCLE END (ok) ===");
    Ok(())
}

fn main() -> Result<()> {
    let cfg = Cfg::from_env();

    let dam = Path::new(DAM);
    let logdir = dam.join("log");
    let logfile = logdir.join("system_update.log");

    let client = http_client()?;

    log_line(&cfg, &logfile, &format!("Daemon start. Run time HHMM={} (daily).", cfg.run_hhmm));

    let mut last_run_date: Option<NaiveDate> = None;

    loop {
        if should_run_now(&cfg.run_hhmm, last_run_date)? {
            let d = today_date();
            log_line(&cfg, &logfile, &format!("Trigger: {} (now >= run time)", d));
            // важно: фиксируем дату до запуска, чтобы при рестарте цикла внутри дня не зациклиться
            last_run_date = Some(d);
            let _ = run_daily_cycle(&cfg, &client);
        }

        // спим до следующего run-time (почти не жрёт батарею/CPU)
        let next = next_run_datetime(&cfg.run_hhmm)?;
        let now = local_now_naive();
        let mut secs = (next - now).num_seconds();
        if secs < 5 {
            secs = 5;
        }
        log_line(&cfg, &logfile, &format!("Sleep until next run: {} seconds (next={})", secs, next));
        thread::sleep(Duration::from_secs(secs as u64));
    }
}
