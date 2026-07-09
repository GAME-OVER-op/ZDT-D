use anyhow::{Context, Result};

use std::{
    collections::BTreeMap,
    panic::{self, AssertUnwindSafe},
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::Duration,
};
use crate::{
    android::{boot, selinux::SelinuxGuard},
    iptables_backup,
    programs::{amneziawg, byedpi, dnscrypt, dpitunnel, myproxy, myprogram, nfqws, nfqws2, openvpn, operaproxy, tor, tgwsproxy, tun2socks, myvpn, mihomo, mieru},
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
    crate::runtime_sanitize::sanitize_runtime_files_best_effort();

    if can_adopt_existing_runtime() {
        crate::captive_portal::sync_from_settings_best_effort();
        final_sync_runtime_settings_best_effort("adopted runtime");
        crate::runtime_state::write_running(false, true).ok();
        crate::logging::user_info("Инициализация завершена");
        return Ok(());
    }

    crate::internet_wait::wait_before_start_if_needed();

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

    // Apply persistent IPv4 forwarding state before VPN/TUN and routing programs start.
    crate::android::sysctl::apply_start_settings_best_effort();

    validate_start_plan_best_effort();

    crate::logging::user_info("Подготовка: восстановление базовых iptables");
    // Restore baseline iptables before applying rules (prevents leftovers between starts).
    if settings::iptables_backup_path().exists() {
        iptables_backup::reset_tables_then_restore_backup()?;
    } else {
        log::warn!("iptables backup missing -> skipping restore baseline");
    }
    crate::runtime_refresh::clear_routing_cache();

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
    let singbox_handle = thread::spawn(singbox::start_t2s_if_enabled);
    wait_start_group(
        "dpi-stack",
        vec![
            ("dpitunnel", dpitunnel_handle),
            ("byedpi", byedpi_handle),
            ("sing-box", singbox_handle),
        ],
    );

    // Independent proxy helpers can start together after the DPI stack is ready.
    // VPN/netd and operaproxy still remain ordered after this group.
    let wireproxy_handle = thread::spawn(wireproxy::start_if_enabled);
    let myproxy_handle = thread::spawn(myproxy::start_if_enabled);
    let myprogram_handle = thread::spawn(myprogram::start_if_enabled);
    let tgwsproxy_handle = thread::spawn(tgwsproxy::start_if_enabled);
    wait_start_group(
        "proxy-programs",
        vec![
            ("wireproxy", wireproxy_handle),
            ("myproxy", myproxy_handle),
            ("myprogram", myprogram_handle),
            ("tgwsproxy", tgwsproxy_handle),
        ],
    );

    // VPN/netd profile programs: launch VPN engines, wait for TUN, then apply Android netd routing once.
    // VPN profile failures are best-effort: log to console/profile logs and continue the rest of startup.
    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_vpn_selection = api_settings
        .hotspot_vpn_selection()
        .map(|(program, profile)| (program.to_string(), profile.to_string()));
    let vpn_expected = openvpn::has_profiles_requiring_netd()
        || amneziawg::has_profiles_requiring_netd()
        || tun2socks::has_enabled_profiles()
        || myvpn::has_enabled_profiles()
        || mihomo::has_profiles_requiring_netd()
        || mieru::has_profiles_requiring_netd()
        || singbox::has_enabled_vpn_profiles()
        || hotspot_vpn_selection.is_some();
    let mut vpn_profiles = Vec::new();
    match validate_vpn_claims_unique() {
        Ok(()) => {
            match openvpn::start_profiles_for_netd() {
                Ok(items) => vpn_profiles.extend(items),
                Err(e) => {
                    log::warn!("openvpn startup failed, continuing: {e:#}");
                    mark_start_partial();
                    crate::logging::user_warn("OpenVPN: ошибка запуска, запуск продолжен");
                }
            }
            match amneziawg::start_profiles_for_netd() {
                Ok(items) => vpn_profiles.extend(items),
                Err(e) => {
                    log::warn!("amneziawg startup failed, continuing: {e:#}");
                    mark_start_partial();
                    crate::logging::user_warn("AmneziaWG: ошибка запуска, запуск продолжен");
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
            match mieru::start_profiles_for_netd() {
                Ok(items) => vpn_profiles.extend(items),
                Err(e) => {
                    log::warn!("mieru startup failed, continuing: {e:#}");
                    mark_start_partial();
                    crate::logging::user_warn("mieru: ошибка запуска, запуск продолжен");
                }
            }
            match singbox::start_profiles_for_netd() {
                Ok(items) => vpn_profiles.extend(items),
                Err(e) => {
                    log::warn!("sing-box vpn startup failed, continuing: {e:#}");
                    mark_start_partial();
                    crate::logging::user_warn("sing-box: ошибка запуска, запуск продолжен");
                }
            }
            if let Err(e) = crate::vpn_netd::start_profiles(vpn_profiles) {
                log::warn!("vpn_netd apply failed, continuing: {e:#}");
                mark_start_partial();
                if vpn_expected {
                    crate::logging::user_warn("VPN/netd: ошибка применения, запуск продолжен");
                }
            }

            let vpn_tether_profile = match hotspot_vpn_selection.as_ref().map(|(p, n)| (p.as_str(), n.as_str())) {
                Some(("openvpn", profile)) => match openvpn::start_profile_for_hotspot_vpn(profile) {
                    Ok(item) => item,
                    Err(e) => { log::warn!("vpn_tether openvpn startup failed, continuing: {e:#}"); mark_start_partial(); None }
                },
                Some(("amneziawg", profile)) => match amneziawg::start_profile_for_hotspot_vpn(profile) {
                    Ok(item) => item,
                    Err(e) => { log::warn!("vpn_tether amneziawg startup failed, continuing: {e:#}"); mark_start_partial(); None }
                },
                Some(("mihomo", profile)) => match mihomo::start_profile_for_hotspot_vpn(profile) {
                    Ok(item) => item,
                    Err(e) => { log::warn!("vpn_tether mihomo startup failed, continuing: {e:#}"); mark_start_partial(); None }
                },
                Some(("mieru", profile)) => match mieru::start_profile_for_hotspot_vpn(profile) {
                    Ok(item) => item,
                    Err(e) => { log::warn!("vpn_tether mieru startup failed, continuing: {e:#}"); mark_start_partial(); None }
                },
                Some((program, profile)) => {
                    log::warn!("vpn_tether unsupported selection program={} profile={}", program, profile);
                    None
                }
                None => None,
            };
            if let Err(e) = crate::vpn_tether::sync(vpn_tether_profile) {
                log::warn!("vpn_tether apply failed, continuing: {e:#}");
                mark_start_partial();
                if hotspot_vpn_selection.is_some() {
                    crate::logging::user_warn("VPN-раздача: ошибка применения, запуск продолжен");
                }
            }
        }
        Err(e) => {
            log::warn!("vpn profile claim conflict, skipping VPN/netd profiles and continuing: {e:#}");
            mark_start_partial();
            if vpn_expected {
                crate::logging::user_warn("VPN/netd: конфликт профилей, запуск продолжен");
            }
            let _ = crate::vpn_tether::sync(None);
        }
    }

    // Tor (single socks5 service + t2s)
    start_best_effort("tor", tor::start_if_enabled);

    // Opera-proxy pipeline (may use local dnscrypt port if running).
    // Start it last to avoid interfering with other startup logic.
    start_best_effort("operaproxy", operaproxy::start_if_enabled);

    crate::captive_portal::sync_from_settings_best_effort();



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
    crate::runtime_refresh::clear_routing_cache();
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

    final_sync_runtime_settings_best_effort("startup finalization");

    crate::logging::user_info("Запуск завершён");
    if let Err(e) = crate::runtime_state::write_running(last_start_partial(), false) {
        log::warn!("failed to write runtime state marker: {e:#}");
    }

    Ok(())
}


fn final_sync_runtime_settings_best_effort(context: &str) {
    log::info!("runtime: final settings sync ({})", context);
    crate::android::sysctl::sync_ipv4_forward_from_settings_best_effort();
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
    crate::runtime_refresh::clear_routing_cache();
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

    let vpn_expected = openvpn::has_profiles_requiring_netd()
        || amneziawg::has_profiles_requiring_netd()
        || tun2socks::has_enabled_profiles()
        || myvpn::has_enabled_profiles()
        || mihomo::has_profiles_requiring_netd()
        || mieru::has_profiles_requiring_netd()
        || singbox::has_enabled_vpn_profiles();
    if vpn_expected && !crate::vpn_netd::applied_snapshot_path().is_file() {
        log::info!("runtime adoption: VPN profiles are expected but vpn_netd/applied.json is missing");
        return false;
    }

    let api_settings = settings::load_api_settings().unwrap_or_default();
    if api_settings.hotspot_vpn_selection().is_some() && !crate::vpn_tether::applied_state_path().is_file() {
        log::info!("runtime adoption: hotspot VPN tether is expected but vpn_tether/applied.json is missing");
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
    require_profile_program!("amneziawg", r.amneziawg.count);
    require_profile_program!("tun2socks", r.tun2socks.count);
    require_profile_program!("mihomo", r.mihomo.count);
    require_profile_program!("mieru", r.mieru.count);

    if myvpn::has_enabled_profiles() {
        expected_any = true;
        if !vpn_netd_has_applied_owner("myvpn") {
            log::info!("runtime adoption: enabled myvpn profiles exist but vpn_netd snapshot has no myvpn owner");
            return false;
        }
    }

    if singbox::has_enabled_vpn_profiles() {
        expected_any = true;
        if !vpn_netd_has_applied_owner("singbox") {
            log::info!("runtime adoption: enabled sing-box VPN profiles exist but vpn_netd snapshot has no singbox owner");
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

    if tgwsproxy_enabled() {
        expected_any = true;
        if !tgwsproxy::is_running() {
            log::info!("runtime adoption: tgwsproxy is enabled but process count is 0");
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

fn tgwsproxy_enabled() -> bool {
    tgwsproxy::load_effective_enabled().map(|v| v.enabled).unwrap_or(false)
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
            || r.openvpn.count > 0
            || r.amneziawg.count > 0
            || r.tun2socks.count > 0
            || r.mihomo.count > 0
            || r.mieru.count > 0
        {
            return true;
        }
    }

    openvpn::is_running()
        || amneziawg::is_running()
        || tun2socks::is_running()
        || mihomo::is_running()
        || mieru::is_running()
        || vpn_netd_has_applied_owner("myvpn")
}

fn runtime_uses_iptables_paths() -> bool {
    let app_routing = (operaproxy_enabled() && operaproxy_has_routed_app_outputs())
        || profile_program_has_routed_app_outputs("wireproxy")
        || profile_program_has_routed_app_outputs("singbox")
        || (tor_enabled() && tor_has_routed_app_outputs());

    match stats::collect_status() {
        Ok(r) => {
            // sing-box / wireproxy / operaproxy / tor can run in marker-only
            // server mode without t2s app routing and without NAT_DPI/MANGLE_APP
            // anchors. Only require those anchors when their resolved UID output
            // files contain real app routes. Hotspot-only PREROUTING is not this
            // OUTPUT/MANGLE anchor path.
            r.zapret.count > 0
                || r.zapret2.count > 0
                || r.byedpi.count > 0
                || r.dpitunnel.count > 0
                || r.myproxy.count > 0
                || r.myprogram.count > 0
                || r.dnscrypt.count > 0
                || app_routing
        }
        Err(_) => app_routing,
    }
}

fn tor_has_routed_app_outputs() -> bool {
    count_valid_uid_pairs_runtime(&settings::tor_out_program_path()) > 0
}

fn operaproxy_has_routed_app_outputs() -> bool {
    let root = settings::working_program_root_path("operaproxy");
    [
        root.join("app/out/user_program"),
        root.join("app/out/mobile_program"),
        root.join("app/out/wifi_program"),
    ]
    .iter()
    .any(|p| count_valid_uid_pairs_runtime(p) > 0)
}

fn profile_program_has_routed_app_outputs(program: &str) -> bool {
    let active_path = settings::working_program_root_path(program).join("active.json");
    let Ok(raw) = std::fs::read_to_string(active_path) else { return false; };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { return false; };
    let Some(profiles) = v.get("profiles").and_then(|p| p.as_object()) else { return false; };

    profiles.iter().any(|(name, st)| {
        profile_state_enabled(st)
            && count_valid_uid_pairs_runtime(
                &settings::working_program_root_path(program)
                    .join("profile")
                    .join(name)
                    .join("app/out/user_program"),
            ) > 0
    })
}

fn profile_state_enabled(st: &serde_json::Value) -> bool {
    st.get("enabled")
        .and_then(|x| x.as_bool())
        .unwrap_or_else(|| st.get("enabled").and_then(|x| x.as_i64()).unwrap_or(0) != 0)
}

fn count_valid_uid_pairs_runtime(path: &Path) -> usize {
    let Ok(raw) = std::fs::read_to_string(path) else { return 0; };
    raw.lines()
        .filter(|line| {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                return false;
            }
            line.split_once('=')
                .map(|(_, uid_s)| {
                    let uid_s = uid_s.trim();
                    !uid_s.is_empty() && uid_s.chars().all(|c| c.is_ascii_digit())
                })
                .unwrap_or(false)
        })
        .count()
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
    // responsive, especially on cold boot. Keep this guard soft: probe both
    // services immediately and only wait when they are not ready yet.
    log::info!("boot guard: probing package-manager and netd after sys.boot_completed");

    let package_manager = thread::spawn(|| {
        wait_shell_probe_ready(
            "package-manager",
            &[
                "cmd package list packages >/dev/null 2>&1",
                "pm list packages >/dev/null 2>&1",
            ],
            Duration::from_secs(20),
            Duration::from_millis(500),
            Duration::from_secs(5),
        )
    });

    let netd = thread::spawn(|| {
        wait_shell_probe_ready(
            "netd",
            &["ndc network list >/dev/null 2>&1"],
            Duration::from_secs(20),
            Duration::from_millis(500),
            Duration::from_secs(5),
        )
    });

    if !join_boot_probe("package-manager", package_manager) {
        mark_start_partial();
    }
    if !join_boot_probe("netd", netd) {
        mark_start_partial();
    }
}

fn join_boot_probe(name: &str, handle: thread::JoinHandle<bool>) -> bool {
    match handle.join() {
        Ok(ready) => ready,
        Err(_) => {
            log::warn!("boot guard: {name} probe thread panicked; startup will continue");
            false
        }
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
    if let Err(e) = amneziawg::validate_start_plan() {
        had_warning = true;
        log::warn!("start plan warning: amneziawg: {e:#}");
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
    if let Err(e) = mieru::validate_start_plan() {
        had_warning = true;
        log::warn!("start plan warning: mieru: {e:#}");
    }
    if let Err(e) = singbox::validate_start_plan() {
        had_warning = true;
        log::warn!("start plan warning: sing-box: {e:#}");
    }
    if let Err(e) = validate_vpn_claims_unique() {
        had_warning = true;
        log::warn!("start plan warning: vpn claims: {e:#}");
    }

    if had_warning {
        mark_start_partial();
    }
}

fn validate_vpn_claims_unique() -> Result<()> {
    validate_vpn_tun_claims_unique()?;
    validate_vpn_cidr_claims_unique()?;
    Ok(())
}

fn validate_vpn_tun_claims_unique() -> Result<()> {
    let mut seen = BTreeMap::<String, String>::new();
    for (label, tun) in openvpn::enabled_tun_claims()
        .into_iter()
        .chain(amneziawg::enabled_tun_claims().into_iter())
        .chain(tun2socks::enabled_tun_claims().into_iter())
        .chain(myvpn::enabled_tun_claims().into_iter())
        .chain(mihomo::enabled_tun_claims().into_iter())
        .chain(mieru::enabled_tun_claims().into_iter())
        .chain(singbox::enabled_tun_claims().into_iter())
    {
        if let Some(other) = seen.insert(tun.clone(), label.clone()) {
            anyhow::bail!("VPN tun conflict: tun {tun} is used by {other} and {label}");
        }
    }
    Ok(())
}

fn validate_vpn_cidr_claims_unique() -> Result<()> {
    let claims = amneziawg::enabled_cidr_claims()
        .into_iter()
        .chain(tun2socks::enabled_cidr_claims().into_iter())
        .chain(myvpn::enabled_cidr_claims().into_iter())
        .chain(mihomo::enabled_cidr_claims().into_iter())
        .chain(mieru::enabled_cidr_claims().into_iter())
        .chain(singbox::enabled_cidr_claims().into_iter())
        .collect::<Vec<_>>();
    for i in 0..claims.len() {
        for j in (i + 1)..claims.len() {
            if cidrs_overlap(&claims[i].1, &claims[j].1)? {
                anyhow::bail!(
                    "VPN CIDR conflict: {} {} overlaps with {} {}",
                    claims[i].0,
                    claims[i].1,
                    claims[j].0,
                    claims[j].1
                );
            }
        }
    }
    Ok(())
}

fn cidrs_overlap(a: &str, b: &str) -> Result<bool> {
    let (an, am) = cidr_network_mask(a)?;
    let (bn, bm) = cidr_network_mask(b)?;
    let a_start = an;
    let a_end = an | !am;
    let b_start = bn;
    let b_end = bn | !bm;
    Ok(a_start <= b_end && b_start <= a_end)
}

fn cidr_network_mask(cidr: &str) -> Result<(u32, u32)> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 {
        anyhow::bail!("bad cidr prefix {cidr}");
    }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok((addr & mask, mask))
}

fn ipv4_to_u32(s: &str) -> Option<u32> {
    let mut out = 0u32;
    let mut count = 0usize;
    for part in s.split('.') {
        let n = part.parse::<u8>().ok()? as u32;
        out = (out << 8) | n;
        count += 1;
    }
    if count == 4 { Some(out) } else { None }
}

fn any_main_service_running() -> bool {
    // The Android app infers "running" from dpitunnel/byedpi/zapret/zapret2/opera-proxy.
    // However, some users intentionally run only dnscrypt. In that case, consider startup
    // successful if dnscrypt is enabled and running.
    let dnscrypt_expected = dnscrypt::active_listen_port().ok().flatten().is_some();
    let openvpn_expected = openvpn::has_enabled_profiles();
    let amneziawg_expected = amneziawg::has_enabled_profiles();
    let tun2socks_expected = tun2socks::has_enabled_profiles();
    let myvpn_expected = myvpn::has_enabled_profiles();
    let mihomo_expected = mihomo::has_enabled_profiles();
    let singbox_vpn_expected = singbox::has_enabled_vpn_profiles();
    let tgwsproxy_expected = tgwsproxy_enabled();

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
                || (tgwsproxy_expected && tgwsproxy::is_running())
                || (dnscrypt_expected && r.dnscrypt.count > 0)
                || (openvpn_expected && openvpn::is_running())
                || (amneziawg_expected && amneziawg::is_running())
                || (tun2socks_expected && tun2socks::is_running())
                || (myvpn_expected && vpn_netd_has_applied_owner("myvpn"))
                || (mihomo_expected && mihomo::is_running())
                || (mieru::has_enabled_profiles() && mieru::is_running())
                || (singbox_vpn_expected && singbox::is_running() && vpn_netd_has_applied_owner("singbox"))
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
