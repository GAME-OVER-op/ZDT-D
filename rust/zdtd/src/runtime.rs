use anyhow::Result;

use std::{
    collections::BTreeMap,
    panic::{self, AssertUnwindSafe},
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::Duration,
};
use crate::{
    android::{boot, selinux::SelinuxGuard},
    iptables_backup,
    programs::{byedpi, dnscrypt, dpitunnel, myproxy, myprogram, nfqws, nfqws2, openvpn, operaproxy, tor, tun2socks, myvpn, mihomo},
    programs::{singbox, wireproxy},
    stats,
    settings,
    shell,
    stop,
};

static START_PARTIAL: AtomicBool = AtomicBool::new(false);

fn reset_start_partial() {
    START_PARTIAL.store(false, Ordering::SeqCst);
}

fn mark_start_partial() {
    START_PARTIAL.store(true, Ordering::SeqCst);
}

pub fn last_start_partial() -> bool {
    START_PARTIAL.load(Ordering::SeqCst)
}

/// Start all enabled services and apply iptables rules.
///
/// Note: iptables backup should already exist (created by daemon on first run).
pub fn start_full() -> Result<()> {
    reset_start_partial();
    // Clean only the main user-facing log before the first visible startup line.
    // Do not delete profile logs yet: on hot daemon replacement we may adopt an
    // already-running runtime and should not erase active process logs.
    let _ = crate::logging::truncate_log_file();

    crate::logging::user_info("Запуск: инициализация");
    log::info!("startup initialization: waiting for sys.boot_completed=1 ...");
    boot::wait_for_boot_completed()?;
    log::info!("startup initialization: boot completed");
    wait_android_runtime_ready_best_effort();

    settings::ensure_minimal_program_layouts()?;

    if can_adopt_existing_runtime() {
        crate::runtime_state::write_running(false, true).ok();
        crate::logging::user_info("Инициализация завершена");
        return Ok(());
    }

    truncate_profile_logs();
    crate::logging::user_info("Подготовка: запуск");

    // Backup baseline iptables before any rule changes (only once on first launch).
    iptables_backup::ensure_backup_if_first_launch()?;

    // Disable captive portal checks while running.
    // Some firmwares honor different knobs; set all of them best-effort.
    let _ = shell::ok_sh("settings put global captive_portal_detection_enabled 0");
    let _ = shell::ok_sh("settings put global captive_portal_server localhost");
    let _ = shell::ok_sh("settings put global captive_portal_mode 0");

    // Ensure ports/queue numbers do not collide across programs.
    crate::ports::normalize_ports()?;

    // Optional temporary SELinux Permissive while we touch iptables and start daemons.
    // Controlled by setting/setting.json: selinux_permissive_enabled (default: false).
    let _selinux = SelinuxGuard::enter_permissive_if_enforcing()?;

    validate_start_plan_best_effort();

    crate::logging::user_info("Подготовка: восстановление базовых iptables");
    // Restore baseline iptables before applying rules (prevents leftovers between starts).
    if settings::iptables_backup_path().exists() {
        iptables_backup::reset_tables_then_restore_backup()?;
    } else {
        log::warn!("iptables backup missing -> skipping restore baseline");
    }

    // Start dnscrypt first (must be before other programs).
        std::thread::spawn(|| {
            if let Err(e) = dnscrypt::start_if_enabled() {
                log::error!("dnscrypt start failed: {:#}", e);
            }
        });

    // Run NFQUEUE-based programs together first, then wait until both are done.
    let nfqws_handle = thread::spawn(nfqws::start_active_profiles);
    let nfqws2_handle = thread::spawn(nfqws2::start_active_profiles);
    wait_start_group(
        "nfqueue",
        vec![("nfqws", nfqws_handle), ("nfqws2", nfqws2_handle)],
    );

    // Then start DPI/tunnel stack in parallel and wait for all of them.
    let dpitunnel_handle = thread::spawn(dpitunnel::start_active_profiles);
    let byedpi_handle = thread::spawn(byedpi::start_active_profiles);
    let singbox_handle = thread::spawn(singbox::start_if_enabled);
    wait_start_group(
        "dpi-stack",
        vec![
            ("dpitunnel", dpitunnel_handle),
            ("byedpi", byedpi_handle),
            ("sing-box", singbox_handle),
        ],
    );

    // wireproxy (may start multiple profiles + optional t2s)
    start_best_effort("wireproxy", wireproxy::start_if_enabled);

    // myproxy (profile-based upstream socks5 via t2s)
    start_best_effort("myproxy", myproxy::start_if_enabled);

    // myprogram (custom launched program profiles)
    start_best_effort("myprogram", myprogram::start_if_enabled);

    // VPN/netd profile programs: launch VPN engines, wait for TUN, then apply Android netd routing once.
    // VPN profile failures are best-effort: log to console/profile logs and continue the rest of startup.
    let vpn_expected = openvpn::has_enabled_profiles() || tun2socks::has_enabled_profiles() || myvpn::has_enabled_profiles() || mihomo::has_enabled_profiles();
    let mut vpn_profiles = Vec::new();
    match validate_vpn_tun_claims_unique() {
        Ok(()) => {
            match openvpn::start_profiles_for_netd() {
                Ok(items) => vpn_profiles.extend(items),
                Err(e) => {
                    log::warn!("openvpn startup failed, continuing: {e:#}");
                    mark_start_partial();
                    crate::logging::user_warn("OpenVPN: ошибка запуска, запуск продолжен");
                }
            }
            match tun2socks::start_profiles_for_netd() {
                Ok(items) => vpn_profiles.extend(items),
                Err(e) => {
                    log::warn!("tun2socks startup failed, continuing: {e:#}");
                    mark_start_partial();
                    crate::logging::user_warn("tun2socks: ошибка запуска, запуск продолжен");
                }
            }
            match myvpn::start_profiles_for_netd() {
                Ok(items) => vpn_profiles.extend(items),
                Err(e) => {
                    log::warn!("myvpn startup failed, continuing: {e:#}");
                    mark_start_partial();
                    crate::logging::user_warn("myvpn: ошибка запуска, запуск продолжен");
                }
            }
            match mihomo::start_profiles_for_netd() {
                Ok(items) => vpn_profiles.extend(items),
                Err(e) => {
                    log::warn!("mihomo startup failed, continuing: {e:#}");
                    mark_start_partial();
                    crate::logging::user_warn("mihomo: ошибка запуска, запуск продолжен");
                }
            }
            if let Err(e) = crate::vpn_netd::start_profiles(vpn_profiles) {
                log::warn!("vpn_netd apply failed, continuing: {e:#}");
                mark_start_partial();
                if vpn_expected {
                    crate::logging::user_warn("VPN/netd: ошибка применения, запуск продолжен");
                }
            }
        }
        Err(e) => {
            log::warn!("vpn tun conflict, skipping VPN/netd profiles and continuing: {e:#}");
            mark_start_partial();
            if vpn_expected {
                crate::logging::user_warn("VPN/netd: конфликт tun, запуск продолжен");
            }
        }
    }

    // Tor (single socks5 service + t2s)
    start_best_effort("tor", tor::start_if_enabled);

    // Opera-proxy pipeline (may use local dnscrypt port if running).
    // Start it last to avoid interfering with other startup logic.
    start_best_effort("operaproxy", operaproxy::start_if_enabled);



// Post-start sanity check:
// The Android app infers "running" from dpitunnel/byedpi/zapret/zapret2/opera-proxy.
// If none of these are running after startup, treat it as a failed start:
// log an error and stop everything (return to OFF state).
if !any_main_service_running() {
    crate::logging::user_error("Ошибка запуска: проверьте настройки");
    log::error!("startup sanity check failed: no main services are running after start");

    // Persist enabled=false so reboot won't autostart a broken config.
    let mut st = settings::read_start_settings().unwrap_or_default();
    st.enabled = false;
    let _ = settings::write_start_settings(&st);

    // Stop services and restore baseline iptables; always restore captive portal settings.
    crate::runtime_state::clear();
    let stop_res = stop::stop_services();
    crate::runtime_state::clear();
    let _ = shell::ok_sh(
        "settings delete global captive_portal_detection_enabled; \
         settings delete global captive_portal_server; \
         settings delete global captive_portal_mode",
    );
    if let Err(e) = stop_res {
        log::warn!("stop after failed start returned error: {e:#}");
    }

    anyhow::bail!("no main services started");
}

    let proxyinfo_enabled = crate::proxyinfo::load_enabled_json()
        .map(|v| v.is_enabled())
        .unwrap_or(false);
    if proxyinfo_enabled {
        crate::logging::user_info("Настройка защиты");
    }
    log::info!("startup: applying proxyinfo");
    let proxyinfo_active = match crate::proxyinfo::refresh_runtime(true) {
        Ok(active) => {
            log::info!("startup: proxyinfo applied active={active}");
            if active {
                crate::logging::user_info("proxyInfo: защита применена");
            } else if proxyinfo_enabled {
                crate::logging::user_warn("proxyInfo: защита не активировалась");
            }
            active
        }
        Err(e) => {
            log::warn!("proxyInfo apply failed after start: {e:#}");
            crate::logging::user_warn("proxyInfo: не удалось применить защиту");
            false
        }
    };

    let blockedquic_enabled = crate::blockedquic::load_enabled_json()
        .map(|v| v.is_enabled())
        .unwrap_or(false);
    if blockedquic_enabled {
        crate::logging::user_info("Применение BlockedQUIC");
    }
    log::info!("startup: applying blockedquic");
    match crate::blockedquic::refresh_runtime(true) {
        Ok(active) => {
            log::info!("startup: blockedquic applied active={active}");
            if active {
                crate::logging::user_info("BlockedQUIC: правила применены");
            } else if blockedquic_enabled {
                crate::logging::user_warn("BlockedQUIC: не активирован после запуска");
            }
        }
        Err(e) => {
            log::warn!("blockedquic apply failed after start: {e:#}");
            crate::logging::user_warn("BlockedQUIC: ошибка применения");
        }
    }

    if proxyinfo_active {
        log::info!("startup: starting proxyinfo scan detector");
        crate::scan_detector::start();
    }

    crate::logging::user_info("Запуск завершён");
    if let Err(e) = crate::runtime_state::write_running(last_start_partial(), false) {
        log::warn!("failed to write runtime state marker: {e:#}");
    }

    Ok(())
}

/// Stop all services and restore baseline iptables.
pub fn stop_full() -> Result<()> {
    crate::logging::user_info("Остановка: начало");
    // Temporary Permissive while we touch iptables. Keep stop best-effort even if
    // SELinux cannot be toggled on a specific firmware.
    let _selinux = match SelinuxGuard::enter_permissive_if_enforcing() {
        Ok(g) => Some(g),
        Err(e) => {
            log::warn!("stop: SELinux guard failed, continuing: {e:#}");
            None
        }
    };

    // Stop services first, but always try to restore captive portal settings even
    // if the stop sequence partially fails.
    let stop_res = stop::stop_services();
    crate::runtime_state::clear();
    let _ = shell::ok_sh(
        "settings delete global captive_portal_detection_enabled; \
         settings delete global captive_portal_server; \
         settings delete global captive_portal_mode",
    );
    if let Err(e) = stop_res {
        log::warn!("stop_services partially failed, continuing cleanup: {e:#}");
    }
    crate::runtime_state::clear();
    crate::logging::user_info("Остановка завершена");
    Ok(())
}



fn can_adopt_existing_runtime() -> bool {
    let marker = match crate::runtime_state::read() {
        Ok(Some(st)) => st,
        Ok(None) => {
            log::info!("runtime adoption: no runtime state marker");
            return false;
        }
        Err(e) => {
            log::warn!("runtime adoption: failed to read runtime state marker: {e:#}");
            return false;
        }
    };

    if marker.state != "running" {
        log::info!("runtime adoption: marker state is not running: {}", marker.state);
        return false;
    }

    if !actual_runtime_has_services() {
        log::info!("runtime adoption: no active services detected");
        return false;
    }

    if !enabled_runtime_processes_look_complete() {
        log::info!("runtime adoption: at least one enabled service/profile is not running");
        return false;
    }

    let vpn_expected = openvpn::has_enabled_profiles()
        || tun2socks::has_enabled_profiles()
        || myvpn::has_enabled_profiles()
        || mihomo::has_enabled_profiles();
    if vpn_expected && !crate::vpn_netd::applied_snapshot_path().is_file() {
        log::info!("runtime adoption: VPN profiles are expected but vpn_netd/applied.json is missing");
        return false;
    }

    if runtime_uses_iptables_paths() && !iptables_runtime_anchors_present() {
        log::info!("runtime adoption: iptables runtime anchors are missing");
        return false;
    }

    log::info!(
        "runtime adoption: existing runtime accepted old_daemon_pid={} partial={} adopted={}",
        marker.daemon_pid,
        marker.partial,
        marker.adopted
    );
    true
}

fn enabled_runtime_processes_look_complete() -> bool {
    let Ok(r) = stats::collect_status() else {
        return false;
    };

    let mut expected_any = false;

    macro_rules! require_profile_program {
        ($program:literal, $count:expr) => {{
            if active_profiles_enabled($program) {
                expected_any = true;
                if $count == 0 {
                    log::info!("runtime adoption: enabled {program} profiles exist but process count is 0", program = $program);
                    return false;
                }
            }
        }};
    }

    require_profile_program!("nfqws", r.zapret.count);
    require_profile_program!("nfqws2", r.zapret2.count);
    require_profile_program!("byedpi", r.byedpi.count);
    require_profile_program!("dpitunnel", r.dpitunnel.count);
    require_profile_program!("singbox", r.sing_box.count);
    require_profile_program!("wireproxy", r.wireproxy.count);
    require_profile_program!("myproxy", r.myproxy.count);
    require_profile_program!("myprogram", r.myprogram.count);
    require_profile_program!("openvpn", r.openvpn.count);
    require_profile_program!("tun2socks", r.tun2socks.count);
    require_profile_program!("mihomo", r.mihomo.count);

    if myvpn::has_enabled_profiles() {
        expected_any = true;
        if !vpn_netd_has_applied_owner("myvpn") {
            log::info!("runtime adoption: enabled myvpn profiles exist but vpn_netd snapshot has no myvpn owner");
            return false;
        }
    }

    if tor_enabled() {
        expected_any = true;
        if r.tor.count == 0 {
            log::info!("runtime adoption: tor is enabled but process count is 0");
            return false;
        }
    }

    if dnscrypt_enabled() {
        expected_any = true;
        if r.dnscrypt.count == 0 {
            log::info!("runtime adoption: dnscrypt is enabled but process count is 0");
            return false;
        }
    }

    if operaproxy_enabled() {
        expected_any = true;
        if r.opera.opera.count == 0 {
            log::info!("runtime adoption: operaproxy is enabled but process count is 0");
            return false;
        }
    }

    expected_any
}

fn active_profiles_enabled(program: &str) -> bool {
    let path = settings::working_program_root_path(program).join("active.json");
    let Ok(raw) = std::fs::read_to_string(path) else { return false; };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return false; };
    v.get("profiles")
        .and_then(|p| p.as_object())
        .map(|m| {
            m.values().any(|st| {
                st.get("enabled")
                    .and_then(|x| x.as_bool())
                    .unwrap_or_else(|| st.get("enabled").and_then(|x| x.as_i64()).unwrap_or(0) != 0)
            })
        })
        .unwrap_or(false)
}

fn simple_enabled_json(program: &str, file: &str) -> bool {
    let path = settings::working_program_root_path(program).join(file);
    let Ok(raw) = std::fs::read_to_string(path) else { return false; };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return false; };
    v.get("enabled")
        .and_then(|x| x.as_bool())
        .unwrap_or_else(|| v.get("enabled").and_then(|x| x.as_i64()).unwrap_or(0) != 0)
}

fn dnscrypt_enabled() -> bool {
    simple_enabled_json("dnscrypt", "active.json")
}

fn operaproxy_enabled() -> bool {
    simple_enabled_json("operaproxy", "active.json")
}

fn tor_enabled() -> bool {
    simple_enabled_json("tor", "enabled.json")
}


fn actual_runtime_has_services() -> bool {
    if let Ok(r) = stats::collect_status() {
        if r.zapret.count > 0
            || r.zapret2.count > 0
            || r.byedpi.count > 0
            || r.dpitunnel.count > 0
            || r.sing_box.count > 0
            || r.wireproxy.count > 0
            || r.myproxy.count > 0
            || r.myprogram.count > 0
            || r.tor.count > 0
            || r.opera.opera.count > 0
            || r.dnscrypt.count > 0
        {
            return true;
        }
    }

    openvpn::is_running()
        || tun2socks::is_running()
        || mihomo::is_running()
        || vpn_netd_has_applied_owner("myvpn")
}

fn runtime_uses_iptables_paths() -> bool {
    match stats::collect_status() {
        Ok(r) => {
            r.zapret.count > 0
                || r.zapret2.count > 0
                || r.byedpi.count > 0
                || r.dpitunnel.count > 0
                || r.sing_box.count > 0
                || r.wireproxy.count > 0
                || r.myproxy.count > 0
                || r.myprogram.count > 0
                || r.tor.count > 0
                || r.opera.opera.count > 0
                || r.dnscrypt.count > 0
        }
        Err(_) => false,
    }
}

fn iptables_runtime_anchors_present() -> bool {
    let nat = shell::run_timeout(
        "iptables",
        &["-t", "nat", "-C", "OUTPUT", "-j", "NAT_DPI"],
        shell::Capture::None,
        Duration::from_secs(3),
    )
    .map(|(code, _)| code == 0)
    .unwrap_or(false);

    let mangle = shell::run_timeout(
        "iptables",
        &["-t", "mangle", "-C", "OUTPUT", "-j", "MANGLE_APP"],
        shell::Capture::None,
        Duration::from_secs(3),
    )
    .map(|(code, _)| code == 0)
    .unwrap_or(false);

    nat || mangle
}

fn wait_android_runtime_ready_best_effort() {
    // Android can report sys.boot_completed before package manager/netd are fully
    // responsive, especially on cold boot. Keep this guard soft: it improves
    // startup stability, but a temporary PM/netd issue must not block unrelated
    // programs forever.
    const STABILIZE_SECS: u64 = 3;
    log::info!("boot guard: stabilization sleep {STABILIZE_SECS}s after sys.boot_completed");
    std::thread::sleep(Duration::from_secs(STABILIZE_SECS));

    if !wait_shell_probe_ready(
        "package-manager",
        &[
            "cmd package list packages >/dev/null 2>&1",
            "pm list packages >/dev/null 2>&1",
        ],
        Duration::from_secs(20),
        Duration::from_secs(1),
        Duration::from_secs(5),
    ) {
        mark_start_partial();
    }

    if !wait_shell_probe_ready(
        "netd",
        &["ndc network list >/dev/null 2>&1"],
        Duration::from_secs(20),
        Duration::from_secs(1),
        Duration::from_secs(5),
    ) {
        mark_start_partial();
    }
}

fn wait_shell_probe_ready(
    name: &str,
    probes: &[&str],
    max_wait: Duration,
    step: Duration,
    per_try_timeout: Duration,
) -> bool {
    let started = std::time::Instant::now();
    let mut attempt: u32 = 0;

    loop {
        attempt = attempt.saturating_add(1);
        for probe in probes {
            match shell::ok_sh_timeout(probe, per_try_timeout) {
                Ok(_) => {
                    log::info!("boot guard: {name} ready after attempt={attempt}");
                    return true;
                }
                Err(e) => {
                    log::debug!("boot guard: {name} probe failed attempt={attempt}: {probe}: {e:#}");
                }
            }
        }

        if started.elapsed() >= max_wait {
            log::warn!(
                "boot guard: {name} not ready after {:?}; startup will continue",
                max_wait
            );
            return false;
        }
        std::thread::sleep(step);
    }
}


fn start_best_effort<F>(name: &'static str, f: F)
where
    F: FnOnce() -> Result<()>,
{
    match panic::catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(())) => log::info!("startup service={name} finished"),
        Ok(Err(e)) => {
            mark_start_partial();
            log::warn!("startup service={name} failed, continuing: {e:#}");
        }
        Err(_) => {
            mark_start_partial();
            log::warn!("startup service={name} panicked, continuing");
        }
    }
}

fn validate_start_plan_best_effort() {
    let mut had_warning = false;

    if let Err(e) = openvpn::validate_start_plan() {
        had_warning = true;
        log::warn!("start plan warning: openvpn: {e:#}");
    }
    if let Err(e) = tun2socks::validate_start_plan() {
        had_warning = true;
        log::warn!("start plan warning: tun2socks: {e:#}");
    }
    if let Err(e) = myvpn::validate_start_plan() {
        had_warning = true;
        log::warn!("start plan warning: myvpn: {e:#}");
    }
    if let Err(e) = mihomo::validate_start_plan() {
        had_warning = true;
        log::warn!("start plan warning: mihomo: {e:#}");
    }
    if let Err(e) = validate_vpn_tun_claims_unique() {
        had_warning = true;
        log::warn!("start plan warning: vpn tun claims: {e:#}");
    }

    if had_warning {
        mark_start_partial();
    }
}

fn validate_vpn_tun_claims_unique() -> Result<()> {
    let mut seen = BTreeMap::<String, String>::new();
    for (label, tun) in openvpn::enabled_tun_claims()
        .into_iter()
        .chain(tun2socks::enabled_tun_claims().into_iter())
        .chain(myvpn::enabled_tun_claims().into_iter())
        .chain(mihomo::enabled_tun_claims().into_iter())
    {
        if let Some(other) = seen.insert(tun.clone(), label.clone()) {
            anyhow::bail!("VPN tun conflict: tun {tun} is used by {other} and {label}");
        }
    }
    Ok(())
}

fn any_main_service_running() -> bool {
    // The Android app infers "running" from dpitunnel/byedpi/zapret/zapret2/opera-proxy.
    // However, some users intentionally run only dnscrypt. In that case, consider startup
    // successful if dnscrypt is enabled and running.
    let dnscrypt_expected = dnscrypt::active_listen_port().ok().flatten().is_some();
    let openvpn_expected = openvpn::has_enabled_profiles();
    let tun2socks_expected = tun2socks::has_enabled_profiles();
    let myvpn_expected = myvpn::has_enabled_profiles();
    let mihomo_expected = mihomo::has_enabled_profiles();

    // Give processes a short moment to initialize; some binaries may exit immediately on bad args.
    for _ in 0..20 {
        if let Ok(r) = stats::collect_status() {
            if r.zapret.count > 0
                || r.zapret2.count > 0
                || r.byedpi.count > 0
                || r.dpitunnel.count > 0
                || r.sing_box.count > 0
                || r.wireproxy.count > 0
                || r.myproxy.count > 0
                || r.myprogram.count > 0
                || r.tor.count > 0
                || r.opera.opera.count > 0
                || (dnscrypt_expected && r.dnscrypt.count > 0)
                || (openvpn_expected && openvpn::is_running())
                || (tun2socks_expected && tun2socks::is_running())
                || (myvpn_expected && vpn_netd_has_applied_owner("myvpn"))
                || (mihomo_expected && mihomo::is_running())
            {
                return true;
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    false
}

fn truncate_profile_logs() {
    // Best effort: ignore errors. Keep zdtd.log intact; this only clears per-profile
    // process logs during a real cold/normal start.
    let _ = shell::ok_sh(
        "find /data/adb/modules/ZDT-D/working_folder -type f -path '*/log/*' -delete 2>/dev/null || true",
    );
}
fn vpn_netd_has_applied_owner(owner: &str) -> bool {
    crate::vpn_netd::read_applied_snapshot()
        .map(|s| s.profiles.iter().any(|p| p.owner_program == owner))
        .unwrap_or(false)
}

fn wait_start_group(stage_name: &str, handles: Vec<(&'static str, thread::JoinHandle<Result<()>>)>) {
    let mut failures: Vec<String> = Vec::new();

    for (name, handle) in handles {
        match handle.join() {
            Ok(Ok(())) => {
                log::info!("startup stage={stage_name} service={name} finished");
            }
            Ok(Err(e)) => {
                log::error!("startup stage={stage_name} service={name} failed: {:#}", e);
                crate::logging::user_warn(&format!("{name}: ошибка запуска"));
                failures.push(name.to_string());
            }
            Err(_) => {
                log::error!("startup stage={stage_name} service={name} panicked");
                crate::logging::user_warn(&format!("{name}: аварийное завершение потока запуска"));
                failures.push(name.to_string());
            }
        }
    }

    if !failures.is_empty() {
        mark_start_partial();
        log::warn!(
            "startup stage={stage_name} completed with failures: {}",
            failures.join(", ")
        );
    }
}

