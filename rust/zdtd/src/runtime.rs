use anyhow::{Result, bail};

use std::time::Duration;
use crate::{
    android::{boot, selinux::SelinuxGuard},
    iptables_backup,
    programs::{byedpi, dnscrypt, dpitunnel, nfqws, nfqws2, operaproxy},
    programs::singbox,
    stats,
    settings,
    shell,
    stop,
};

/// Start all enabled services and apply iptables rules.
///
/// Note: iptables backup should already exist (created by daemon on first run).
pub fn start_full() -> Result<()> {
    crate::logging::user_info("Подготовка: запуск");
    crate::logging::user_info("Подготовка: ожидание загрузки Android");
    log::info!("waiting for sys.boot_completed=1 ...");
    boot::wait_for_boot_completed()?;
    log::info!("boot completed");

    // Backup baseline iptables before any rule changes (only once on first launch).
    iptables_backup::ensure_backup_if_first_launch()?;

    // Disable captive portal checks while running.
    // Some firmwares honor different knobs; set all of them best-effort.
    let _ = shell::ok_sh("settings put global captive_portal_detection_enabled 0");
    let _ = shell::ok_sh("settings put global captive_portal_server localhost");
    let _ = shell::ok_sh("settings put global captive_portal_mode 0");

    // Clean logs each run.
    truncate_all_logs();

    // Ensure ports/queue numbers do not collide across programs.
    crate::ports::normalize_ports()?;

    // Temporary Permissive while we touch iptables and start daemons.
    let _selinux = SelinuxGuard::enter_permissive_if_enforcing()?;

    crate::logging::user_info("Подготовка: восстановление базовых iptables");
    // Restore baseline iptables before applying rules (prevents leftovers between starts).
    if settings::iptables_backup_path().exists() {
        iptables_backup::reset_tables_then_restore_backup()?;
    } else {
        log::warn!("iptables backup missing -> skipping restore baseline");
    }

    // Start dnscrypt first (must be before other programs).
        let _dns = dnscrypt::start_if_enabled()?;

    // Other profiles.
        nfqws::start_active_profiles()?;
    nfqws2::start_active_profiles()?;
    byedpi::start_active_profiles()?;
    dpitunnel::start_active_profiles()?;

    // sing-box (may start multiple profiles + optional t2s)
    singbox::start_if_enabled()?;

    // Opera-proxy pipeline (may use local dnscrypt port if running).
    // Start it last to avoid interfering with other startup logic.
        operaproxy::start_if_enabled()?;



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
    let stop_res = stop::stop_services();
    let _ = shell::ok_sh(
        "settings delete global captive_portal_detection_enabled; \
         settings delete global captive_portal_server; \
         settings delete global captive_portal_mode",
    );
    if let Err(e) = stop_res {
        log::warn!("stop after failed start returned error: {e:#}");
    }

    bail!("no main services started");
}

    crate::logging::user_info("Запуск завершён");

    Ok(())
}

/// Stop all services and restore baseline iptables.
pub fn stop_full() -> Result<()> {
    crate::logging::user_info("Остановка: начало");
    // Temporary Permissive while we touch iptables.
    let _selinux = SelinuxGuard::enter_permissive_if_enforcing()?;

    // Stop services first, but always try to restore captive portal settings even
    // if the stop sequence partially fails.
    let stop_res = stop::stop_services();
    let _ = shell::ok_sh(
        "settings delete global captive_portal_detection_enabled; \
         settings delete global captive_portal_server; \
         settings delete global captive_portal_mode",
    );
    stop_res?;
    crate::logging::user_info("Остановка завершена");
    Ok(())
}



fn any_main_service_running() -> bool {
    // The Android app infers "running" from dpitunnel/byedpi/zapret/zapret2/opera-proxy.
    // However, some users intentionally run only dnscrypt. In that case, consider startup
    // successful if dnscrypt is enabled and running.
    let dnscrypt_expected = dnscrypt::active_listen_port().ok().flatten().is_some();

    // Give processes a short moment to initialize; some binaries may exit immediately on bad args.
    for _ in 0..20 {
        if let Ok(r) = stats::collect_status() {
            if r.zapret.count > 0
                || r.zapret2.count > 0
                || r.byedpi.count > 0
                || r.dpitunnel.count > 0
                || r.sing_box.count > 0
                || r.opera.opera.count > 0
                || (dnscrypt_expected && r.dnscrypt.count > 0)
            {
                return true;
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    false
}

fn truncate_all_logs() {
    // Best effort: ignore errors.
    let _ = crate::logging::truncate_log_file();
    let _ = shell::ok_sh(
        "find /data/adb/modules/ZDT-D/working_folder -type f -path '*/log/*' -delete 2>/dev/null || true",
    );
}