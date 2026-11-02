use anyhow::{bail, Context, Result};
use crate::common::network::{IP_TIMEOUT};
use crate::common::process::{filter_live_pids, pgrep_full_regex, ps_ef_pids_where, shell_quote_for_sh};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use std::{collections::{BTreeMap, BTreeSet}, fs::{self, OpenOptions}, io::{Write}, path::{Path, PathBuf}, sync::atomic::{Ordering}, thread, time::{Duration, SystemTime, UNIX_EPOCH}};

use crate::{common::process::shell::{self, Capture}};

use super::model::*;
use super::profile::*;
use super::runtime::*;
use super::network::*;

#[derive(Debug, Clone, Default)]
pub(super) struct HealthRuntimeState {
    pub(super) first_seen: u64,
    pub(super) last_check: u64,
    pub(super) last_stale_log: u64,
    pub(super) stale_count: u64,
}

pub(super) fn start_health_supervisor_once() {
    if HEALTH_SUPERVISOR_RUNNING.swap(true, Ordering::SeqCst) {
        return;
    }
    thread::spawn(|| {
        info!("amneziawg health: supervisor started");
        let res = std::panic::catch_unwind(health_supervisor_loop);
        if res.is_err() {
            warn!("amneziawg health: supervisor panicked");
        }
        HEALTH_SUPERVISOR_RUNNING.store(false, Ordering::SeqCst);
        info!("amneziawg health: supervisor stopped");
    });
}

pub(super) fn health_supervisor_loop() {
    let mut states = BTreeMap::<String, HealthRuntimeState>::new();
    loop {
        let plans = health_enabled_plans();
        if plans.is_empty() {
            info!("amneziawg health: no enabled health profiles -> exit supervisor");
            return;
        }

        let mut known = BTreeSet::<String>::new();
        let mut checked_any = false;
        for plan in plans {
            let key = health_key(&plan);
            known.insert(key.clone());
            let now = now_epoch_secs();
            let state = states.entry(key.clone()).or_insert_with(|| HealthRuntimeState {
                first_seen: now,
                ..HealthRuntimeState::default()
            });
            let interval = HEALTH_INTERVAL_SEC.max(5);
            if state.last_check != 0 && now.saturating_sub(state.last_check) < interval {
                continue;
            }
            state.last_check = now;
            checked_any = true;
            if let Err(e) = check_health_profile(&plan, state) {
                warn!("amneziawg health: profile={} check failed: {e:#}", plan.name);
                append_health_log(&plan, &format!("check_error error={}", one_line(&format!("{e:#}"))));
                write_health_state(
                    &plan,
                    "check_error",
                    None,
                    state.stale_count,
                    Some(&format!("{e:#}")),
                );
            }
            thread::sleep(Duration::from_millis(250));
        }
        states.retain(|key, _| known.contains(key));
        if !checked_any {
            thread::sleep(HEALTH_IDLE_SLEEP);
        }
    }
}

pub(super) fn health_enabled_plans() -> Vec<ProfilePlan> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let Ok(setting) = read_setting(&name) else { continue; };
        if !link_exists(&setting.tun) { continue; }
        match build_profile_plan(&name, true) {
            Ok(plan) => out.push(plan),
            Err(e) => warn!("amneziawg health: profile={name} plan failed: {e:#}"),
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

pub(super) fn check_health_profile(plan: &ProfilePlan, state: &mut HealthRuntimeState) -> Result<()> {
    let latest = latest_handshake_ts(&plan.setting.tun)?;
    let now = now_epoch_secs();
    let age = latest.map(|ts| now.saturating_sub(ts));
    let lifetime = HEALTH_LIFETIME_SEC.max(30);
    let grace = HEALTH_GRACE_SEC.max(10);
    let cooldown = HEALTH_STALE_LOG_COOLDOWN_SEC.max(10);

    if latest.is_none() && now.saturating_sub(state.first_seen) < grace {
        append_health_log(
            plan,
            &format!(
                "warming_up tun={} grace_left={}s",
                plan.setting.tun,
                grace.saturating_sub(now.saturating_sub(state.first_seen))
            ),
        );
        write_health_state(plan, "warming_up", age, state.stale_count, None);
        return Ok(());
    }

    if let Some(age) = age {
        if age <= lifetime {
            write_health_state(plan, "healthy", Some(age), state.stale_count, None);
            return Ok(());
        }
    }

    let stale_reason = if let Some(age) = age {
        format!("stale_handshake age={}s lifetime={}s", age, lifetime)
    } else {
        format!("no_handshake lifetime={}s grace={}s", lifetime, grace)
    };

    if state.last_stale_log != 0 && now.saturating_sub(state.last_stale_log) < cooldown {
        append_health_log(
            plan,
            &format!(
                "stale cooldown tun={} reason={} cooldown_left={}s",
                plan.setting.tun,
                stale_reason,
                cooldown.saturating_sub(now.saturating_sub(state.last_stale_log))
            ),
        );
        write_health_state(plan, "stale_cooldown", age, state.stale_count, None);
        return Ok(());
    }

    state.last_stale_log = now;
    state.stale_count = state.stale_count.saturating_add(1);
    append_health_log(
        plan,
        &format!(
            "stale_detected tun={} reason={} action=none",
            plan.setting.tun,
            stale_reason
        ),
    );
    write_health_state(plan, "stale", age, state.stale_count, None);
    Ok(())
}

pub(super) fn latest_handshake_ts(tun: &str) -> Result<Option<u64>> {
    let (code, out) = shell::run_timeout(AWG_BIN, &["show", tun, "latest-handshakes"], Capture::Both, AWG_TIMEOUT)
        .with_context(|| format!("awg show {tun} latest-handshakes"))?;
    if code != 0 {
        bail!("awg show {tun} latest-handshakes failed rc={code}: {}", out.trim());
    }
    let latest = out
        .lines()
        .filter_map(|line| line.split_whitespace().nth(1))
        .filter_map(|ts| ts.parse::<u64>().ok())
        .filter(|ts| *ts > 0)
        .max();
    Ok(latest)
}

pub(super) fn health_key(plan: &ProfilePlan) -> String {
    format!("{}|{}", plan.name, plan.setting.tun)
}

pub(super) fn now_epoch_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_secs()
}

pub(super) fn append_health_log(plan: &ProfilePlan, line: &str) {
    if let Some(parent) = plan.health_log_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&plan.health_log_path) {
        let _ = writeln!(f, "epoch={} profile={} {}", now_epoch_secs(), plan.name, line);
    }
}

pub(super) fn write_health_state(
    plan: &ProfilePlan,
    status: &str,
    handshake_age: Option<u64>,
    stale_count: u64,
    last_error: Option<&str>,
) {
    if let Some(parent) = plan.health_state_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let latest = handshake_age
        .map(|age| age.to_string())
        .unwrap_or_else(|| "none".to_string());
    let content = format!(
        "status={}\nprofile={}\ntun={}\nlast_check_epoch={}\nlast_handshake_age={}\nstale_count={}\nlast_error={}\n",
        status,
        plan.name,
        plan.setting.tun,
        now_epoch_secs(),
        latest,
        stale_count,
        last_error.map(one_line).unwrap_or_default(),
    );
    let _ = crate::common::fs::write_text_tmp_rename(&plan.health_state_path, &content);
}

pub(super) fn one_line(s: &str) -> String {
    s.replace('\n', " ").replace('\r', " ").trim().to_string()
}

pub(super) fn append_start_log(plan: &ProfilePlan, line: &str) {
    if let Some(parent) = plan.start_log_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&plan.start_log_path) {
        let _ = writeln!(f, "{}", line);
    }
}

pub(super) fn append_awg_log(plan: &ProfilePlan, text: &str) {
    if let Some(parent) = plan.awg_log_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&plan.awg_log_path) {
        let _ = writeln!(f, "{}", text);
    }
}

pub(super) fn socket_path(tun: &str) -> PathBuf {
    Path::new(SOCK_DIR).join(format!("{tun}.sock"))
}

pub(super) fn cleanup_interface(tun: &str) {
    let _ = shell::run_timeout("ip", &["link", "del", tun], Capture::None, IP_TIMEOUT);
    let _ = fs::remove_file(socket_path(tun));
    let pattern = format!("{} -f {}", AWG_GO_BIN, tun);
    let _ = shell::ok_sh(&format!(
        "pkill -f {} 2>/dev/null || true",
        shell_quote_for_sh(&pattern)
    ));
}

pub fn cleanup_all_interfaces() {
    let mut tuns = BTreeSet::<String>::new();
    if let Ok(rd) = fs::read_dir(profiles_root()) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()) else { continue; };
            if let Ok(setting) = read_setting(profile) {
                tuns.insert(setting.tun);
            }
        }
    }
    for tun in tuns {
        cleanup_interface(&tun);
    }
}

pub fn main_pids_exact() -> Vec<i32> {
    let mut pids = pgrep_full_regex(
        r#"^/data/adb/modules/ZDT-D/bin/amneziawg-go -f [A-Za-z0-9_.-]+$"#,
    );
    if pids.is_empty() {
        pids = ps_ef_pids_where(|line| {
            line.contains("/data/adb/modules/ZDT-D/bin/amneziawg-go -f ")
        });
    }
    filter_live_pids(&mut pids);
    pids
}
