
use anyhow::{Context, Result};
use crate::common::apps::{count_valid_uid_pairs};
use crate::common::config::{normalize_config_args};
use crate::network::t2s::{apply_t2s_routing, T2sSpawnConfig, spawn_t2s_proxy};
use log::{info, warn};
use std::{fs::{self}, path::{Path, PathBuf}, time::{Duration}};

use crate::{platform::android::pkg_uid::{self, Sha256Tracker, Mode as UidMode}, iptables::{hotspot, iptables_port::{DpiTunnelOptions, ProtoChoice}}, settings, common::process::shell::{self, Capture}};

use super::model::*;
use super::settings::*;
use super::dns::*;
use super::api_proxy::*;
use super::process::*;
use super::storage::*;

pub fn start_if_enabled() -> Result<()> {
    let api_settings = settings::load_api_settings().unwrap_or_default();
    start_if_enabled_with_settings(&api_settings)
}

pub(crate) fn start_if_enabled_with_settings(api_settings: &settings::ApiSettings) -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(OPERA_ROOT)?;

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;
    if !active.enabled {
        info!("operaproxy: disabled via active.json");
        return Ok(());
    }

    crate::logging::user_info("Opera: prepare");

    // Ensure app dirs exist
    let uid_dir = Path::new(OPERA_ROOT).join("app/uid");
    let out_dir = Path::new(OPERA_ROOT).join("app/out");
    ensure_dir(&uid_dir)?;
    ensure_dir(&out_dir)?;

    // Ensure list files exist (backwards compatible: create empty wifi/mobile lists if missing)
    ensure_file_empty(Path::new(APP_UID_USER))?;
    ensure_file_empty(Path::new(APP_UID_MOBILE))?;
    ensure_file_empty(Path::new(APP_UID_WIFI))?;

    // Resolve app uid map -> out (sha256 gated, but rebuild if output empty/missing)
    // IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
    // Never introduce module-specific *.flag.sha256 files here.
    let tracker = Sha256Tracker::new(settings::SHARED_SHA_FLAG_FILE);
    let _ = pkg_uid::unified_processing(
        UidMode::Default,
        &tracker,
        Path::new(APP_OUT_USER),
        Path::new(APP_UID_USER),
    )?;
    let _ = pkg_uid::unified_processing(
        UidMode::Default,
        &tracker,
        Path::new(APP_OUT_MOBILE),
        Path::new(APP_UID_MOBILE),
    )?;
    let _ = pkg_uid::unified_processing(
        UidMode::Default,
        &tracker,
        Path::new(APP_OUT_WIFI),
        Path::new(APP_UID_WIFI),
    )?;

    let resolved_user = count_valid_uid_pairs(Path::new(APP_OUT_USER))?;
    let resolved_mobile = count_valid_uid_pairs(Path::new(APP_OUT_MOBILE))?;
    let resolved_wifi = count_valid_uid_pairs(Path::new(APP_OUT_WIFI))?;
    let resolved_total = resolved_user + resolved_mobile + resolved_wifi;
    let has_launch_marker = crate::common::apps::file_has_launch_marker(Path::new(APP_UID_USER)).unwrap_or(false)
        || crate::common::apps::file_has_launch_marker(Path::new(APP_UID_MOBILE)).unwrap_or(false)
        || crate::common::apps::file_has_launch_marker(Path::new(APP_UID_WIFI)).unwrap_or(false);

    let hotspot_t2s = api_settings.hotspot_t2s_for_operaproxy();
    info!(
        "operaproxy: hotspot settings enabled={} mode='{}' program='{}' profile='{}' legacy_target='{}' hotspot_t2s={}",
        api_settings.hotspot_t2s_enabled,
        api_settings.hotspot_mode,
        api_settings.hotspot_program,
        api_settings.hotspot_profile,
        api_settings.hotspot_t2s_target,
        hotspot_t2s,
    );

    if resolved_total == 0 && !has_launch_marker && !hotspot_t2s {
        warn!("operaproxy: no apps resolved and hotspot disabled -> skip start/iptables");
        return Ok(());
    }
    if resolved_total == 0 && (has_launch_marker || hotspot_t2s) {
        info!("operaproxy: starting without routing app UIDs (launch_marker={} hotspot_t2s={})", has_launch_marker, hotspot_t2s);
    }

    // sni list required
    let sni_path = Path::new(SNI_JSON);
    if !sni_path.is_file() {
        warn!("operaproxy: sni.json not found -> skip");
        return Ok(());
    }
    let sni_list = read_sni_entries(sni_path)?;
    if sni_list.is_empty() {
        warn!("operaproxy: no valid SNI entries -> skip");
        return Ok(());
    }

    let port_cfg: PortJson = read_json(Path::new(PORT_JSON))
        .with_context(|| format!("read {}", PORT_JSON))?;

    let needs_t2s = resolved_total > 0 || hotspot_t2s;

    // opera-proxy region (EU/AS/AM) from config/server.txt, validated against whitelist
    let country = read_server_region(Path::new(SERVER_TXT));

    // Load configurable opera-proxy args (falls back to defaults if file missing/invalid)
    let opera_args = read_opera_args();
    info!(
        "operaproxy: args loaded: api_proxy='{}' verbosity={} server_selection='{}' init_retry='{}'",
        opera_args.api_proxy,
        opera_args.verbosity,
        opera_args.server_selection,
        opera_args.init_retry_interval,
    );

    let service_count = sni_list.len();
    let any_uses_byedpi = sni_list.iter().any(|x| opera_upstream_mode(x) == OperaUpstreamMode::ByeDpi);

    // Port intersection guard: byedpi, optional t2s, and all opera ports must be unique
    if has_port_intersection(&port_cfg, service_count, needs_t2s) {
        warn!(
            "operaproxy: port intersection detected (t2s_port={}, byedpi_port={}, opera_start_port={}) -> skip",
            port_cfg.t2s_port, port_cfg.byedpi_port, port_cfg.opera_start_port
        );
        return Ok(());
    }

    // Prepare log dir (cleaned globally by runtime, but ensure exists)
    let log_dir = Path::new(OPERA_ROOT).join("log");
    fs::create_dir_all(&log_dir).with_context(|| format!("mkdir {}", log_dir.display()))?;

    // Resolve -api-proxy before spawning opera-proxy instances.
    // The user may provide one proxy URL, several proxy URLs separated by commas,
    // or an http(s) URL to a plain text proxy list. URL lists are downloaded
    // again on every start and saved to config/api_proxy_list.txt. We only pass
    // one checked working proxy to opera-proxy; if none works or the list cannot
    // be downloaded, opera-proxy is started as if api_proxy was empty.
    let api_proxy_check_log = log_dir.join("api_proxy_check.log");
    truncate_file(&api_proxy_check_log)?;
    let selected_api_proxy = select_working_api_proxy(&opera_args.api_proxy, &api_proxy_check_log);

    // 1) Start byedpi (initial) only if at least one SNI entry requests it.
    let mut byedpi_bin_opt: Option<PathBuf> = None;
    let bye_log = log_dir.join("bye_opera.log");
    let mut byedpi_start_pid: Option<u32> = None;
    if any_uses_byedpi {
        let byedpi_bin = find_bin("byedpi")?;
        byedpi_bin_opt = Some(byedpi_bin.clone());
        truncate_file(&bye_log)?;
        let start_args = normalize_config_args(&fs::read_to_string(BYEDPI_START_TXT)
            .with_context(|| format!("read {}", BYEDPI_START_TXT))?);

        let pid = spawn_byedpi(&byedpi_bin, port_cfg.byedpi_port, &start_args, &bye_log)?;
        byedpi_start_pid = Some(pid);
        info!("operaproxy: byedpi started on port {}", port_cfg.byedpi_port);

        // warmup pause (reduced)
        std::thread::sleep(Duration::from_millis(1500));
    } else {
        info!("operaproxy: no SNI entries require the local ByeDPI upstream");
    }

    crate::logging::user_info("Opera: opera-proxy");
    // 2) Start opera-proxy instances
    let opera_bin = find_bin("opera-proxy")?;
    let mut socks_ports: Vec<u16> = Vec::new();

    let bootstrap_dns = build_bootstrap_dns_list()?;
    for (idx, entry) in sni_list.iter().take(service_count).enumerate() {
        let port = port_cfg.opera_start_port.saturating_add(idx as u16);
        socks_ports.push(port);

        let safe = sanitize_for_filename(&entry.sni);
        let log_path = log_dir.join(format!("opera_proxy{}_{}.log", idx, safe));
        truncate_file(&log_path)?;

        let upstream_proxy = opera_upstream_proxy(entry, port_cfg.byedpi_port)?;
        spawn_opera_proxy(
            &opera_bin,
            port,
            &entry.sni,
            upstream_proxy.as_deref(),
            entry.override_proxy_address.as_deref(),
            &country,
            &bootstrap_dns,
            selected_api_proxy.as_deref(),
            &opera_args,
            &log_path,
        )?;

        std::thread::sleep(Duration::from_millis(2500));
    }

    let ports_csv = socks_ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(",");
    info!("operaproxy: started {} opera-proxy instances ports={}", socks_ports.len(), ports_csv);

    // 3) SOCKS check loop (up to 20s) with more frequent polling and shorter per-check time.
    let min_ok = if socks_ports.len() <= 2 { 1 } else { 2 };
    let check_log = log_dir.join("socks_check.log");
    truncate_file(&check_log)?;
    let ok = wait_for_socks(
        min_ok,
        &socks_ports,
        Duration::from_secs(20),
        Duration::from_millis(500),
        Duration::from_millis(500),
        &check_log,
    )?;
    if !ok {
        warn!("operaproxy: required working socks servers not reached (min_ok={}) -> continue", min_ok);
    }

    if any_uses_byedpi {
        crate::logging::user_info("Opera: byedpi (restart)");
        let kill_log = log_dir.join("bye_opera_kill.log");
        truncate_file(&kill_log)?;
        if let Some(start_pid) = byedpi_start_pid {
            if let Ok(Some(ss_pid)) = find_pid_by_listen_port(port_cfg.byedpi_port, &kill_log) {
                if ss_pid != start_pid.to_string() {
                    warn!("operaproxy: ss reports pid={} on :{}, but our started pid={} -> will kill only started pid",
                        ss_pid, port_cfg.byedpi_port, start_pid);
                }
            }

            let _ = shell::run("kill", &["-9", &start_pid.to_string()], Capture::None);
            std::thread::sleep(Duration::from_millis(200));
            let _ = find_pid_by_listen_port(port_cfg.byedpi_port, &kill_log);

            if let Some(ref byedpi_bin) = byedpi_bin_opt {
                let restart_args = normalize_config_args(&fs::read_to_string(BYEDPI_RESTART_TXT)
                    .with_context(|| format!("read {}", BYEDPI_RESTART_TXT))?);
                let _byedpi_restart_pid = spawn_byedpi(byedpi_bin, port_cfg.byedpi_port, &restart_args, &bye_log)?;
                info!("operaproxy: byedpi restarted");
            }
        }
    } else {
        info!("operaproxy: skipping byedpi restart (no sni entries require byedpi)");
    }

    if needs_t2s {
        crate::logging::user_info("Opera: t2s");
        // 5) Start t2s (hardcoded args + dynamic listen port + socks ports)
        let t2s_listen_addr = if hotspot_t2s { "0.0.0.0" } else { "127.0.0.1" };

        let t2s_bin = find_bin("t2s")?;
        let t2s_log = log_dir.join("t2s.log");
        truncate_file(&t2s_log)?;

        spawn_t2s_proxy(T2sSpawnConfig {
            bin: &t2s_bin,
            listen_addr: t2s_listen_addr,
            listen_port: port_cfg.t2s_port,
            socks_host: "127.0.0.1",
            socks_ports_csv: &ports_csv,
            web_port: None,
            program: "operaproxy",
            profile: "main",
            scope: "program/operaproxy",
            log_path: &t2s_log,
            ..Default::default()
        })?;
        info!(
            "operaproxy: t2s started listen_addr={} listen_port={} socks_ports={}",
            t2s_listen_addr,
            port_cfg.t2s_port,
            ports_csv
        );
        if hotspot_t2s {
            hotspot::ensure_prerouting_redirect(hotspot::HotspotRedirectConfig {
                owner: "operaproxy",
                listen_port: port_cfg.t2s_port,
                capture_all: api_settings.hotspot_t2s_capture_all,
                bypass_ports: hotspot::DEFAULT_HOTSPOT_BYPASS_PORTS,
            })?;
        }
    } else {
        info!("operaproxy: launch marker only and hotspot disabled; skipping t2s");
    }

    crate::logging::user_info("Opera: iptables");
    // 6) Apply iptables_port for selected apps -> t2s_port, proto tcp
    // - user list applies to ALL interfaces (no -o)
    // - mobile/wifi lists apply to specified interfaces from port.json
    let opt = DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() };

    apply_t2s_routing(
        Path::new(APP_OUT_USER),
        port_cfg.t2s_port,
        ProtoChoice::Tcp,
        None,
        opt.clone(),
    )?;
    apply_t2s_routing(
        Path::new(APP_OUT_MOBILE),
        port_cfg.t2s_port,
        ProtoChoice::Tcp,
        Some(port_cfg.iface_mobile.as_str()),
        opt.clone(),
    )?;
    apply_t2s_routing(
        Path::new(APP_OUT_WIFI),
        port_cfg.t2s_port,
        ProtoChoice::Tcp,
        Some(port_cfg.iface_wifi.as_str()),
        opt,
    )?;

    info!("operaproxy: started successfully");
    Ok(())
}

