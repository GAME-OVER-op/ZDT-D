use anyhow::{Context, Result};
use log::{info, warn};
use std::process::{Command, Stdio};

use crate::config::{Config, NotificationsConfig};

const AM_BIN: &str = "/system/bin/am";
const CMD_BIN: &str = "/system/bin/cmd";
const APP_PACKAGE: &str = "com.android.zdtd.service";

/// Types of notifications (mirrors the shell `case`).
pub enum NotificationType<'a> {
    Processing { file_path: &'a str, percent: u8 },
    ProcessingFinal { msg: &'a str },
    Error { msg: &'a str },
    Info { msg: &'a str },
    Support { msg: &'a str },
}

/// Send a notification using `cfg.notifications`.
pub fn send(cfg: &Config, ty: NotificationType<'_>) -> Result<()> {
    send_with(&cfg.notifications, ty)
}

/// Send a notification using an explicit NotificationsConfig.
pub fn send_with(ncfg: &NotificationsConfig, ty: NotificationType<'_>) -> Result<()> {
    if !ncfg.enabled {
        warn!("notifications disabled (enabled=false)");
        anyhow::bail!("notifications disabled");
    }

    let (tag, body) = build_body(ty);
    let body_prefixed = format!("{}{}", ncfg.prefix, body);

    // Escape for single-quoted shell args
    let body_q = shell_single_quote_escape(&body_prefixed);
    let title_q = shell_single_quote_escape(&ncfg.title);
    let tag_q = shell_single_quote_escape(&tag);
    let conv_q = shell_single_quote_escape(&ncfg.conversation);

    // Build shell command exactly like the original:
    // su -lp 2000 -c "cmd notification post ... '<TAG>' '<BODY>'"
    let cmd = format!(
        "cmd notification post \
         -i {icon1} -I {icon2} \
         -S {style} \
         --conversation '{conv}' \
         --message '{body}' \
         -t '{title}' \
         '{tag}' '{body2}'",
        icon1 = ncfg.icon1,
        icon2 = ncfg.icon2,
        style = ncfg.style,
        conv = conv_q,
        body = body_q,
        title = title_q,
        tag = tag_q,
        body2 = body_q,
    );

    let status = Command::new("su")
        .args(["-lp", &ncfg.su_uid.to_string(), "-c", &cmd])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("run su cmd notification post")?;

    if status.success() {
        info!("notification sent tag={} body={}", tag, body_prefixed);
        Ok(())
    } else {
        warn!("failed to send notification tag={}", tag);
        anyhow::bail!("cmd notification failed (status={:?})", status.code());
    }
}

fn build_body(ty: NotificationType<'_>) -> (String, String) {
    // MSG_PROCESSING came from outer script; keep a stable default for now.
    let msg_processing = "Processing";
    match ty {
        NotificationType::Processing { file_path, percent } => {
            let base = file_path.rsplit('/').next().unwrap_or(file_path);
            ("processing".to_string(), format!("{msg_processing} {base} — {percent}%"))
        }
        NotificationType::ProcessingFinal { msg } => ("processing".to_string(), msg.to_string()),
        NotificationType::Error { msg } => ("error".to_string(), msg.to_string()),
        NotificationType::Info { msg } => ("info".to_string(), msg.to_string()),
        NotificationType::Support { msg } => ("support".to_string(), msg.to_string()),
    }
}

/// Escape a string for a single-quoted shell literal.
/// Example: abc'd -> abc'"'"'d
fn shell_single_quote_escape(s: &str) -> String {
    s.replace('\'', r#"'"'"'"#)
}

/// Send daemon state to the Android app so it can display an app-owned notification.
///
/// Best-effort: failures must NOT affect daemon start/stop.
pub fn send_app_state(running: bool) -> Result<()> {
    let running_s = if running { "true" } else { "false" };

    let am_args = [
        "broadcast",
        "--user",
        "0",
        "-a",
        "com.android.zdtd.service.ACTION_DAEMON_STATE",
        "-n",
        "com.android.zdtd.service/.DaemonStateReceiver",
        "--ez",
        "running",
        running_s,
    ];

    let status = Command::new(AM_BIN)
        .args(am_args)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("run am broadcast")?;

    if status.success() {
        info!("daemon state broadcast sent running={}", running);
        return Ok(());
    }

    // Fallback for environments where `am` wrapper behaves differently.
    let cmd_args = [
        "activity",
        "broadcast",
        "--user",
        "0",
        "-a",
        "com.android.zdtd.service.ACTION_DAEMON_STATE",
        "-n",
        "com.android.zdtd.service/.DaemonStateReceiver",
        "--ez",
        "running",
        running_s,
    ];

    let fallback = Command::new(CMD_BIN)
        .args(cmd_args)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("run cmd activity broadcast")?;

    if fallback.success() {
        info!("daemon state broadcast sent via cmd running={}", running);
        Ok(())
    } else {
        warn!(
            "daemon state broadcast failed am={:?} cmd={:?}",
            status.code(),
            fallback.code()
        );
        anyhow::bail!(
            "broadcast failed (am={:?}, cmd={:?})",
            status.code(),
            fallback.code()
        );
    }
}


/// Send a best-effort broadcast to the Android app when proxyInfo counters suggest
/// that a protected app is probing localhost/system services.
///
/// IMPORTANT: the daemon sends a broadcast only. The Android app should receive
/// ACTION_PROXYINFO_PROBE_DETECTED and decide whether to show a notification,
/// store the event, or launch an Activity itself.
///
/// The app should register a BroadcastReceiver for ACTION_PROXYINFO_PROBE_DETECTED
/// and may choose to show a notification or launch an Activity itself.
pub fn send_proxyinfo_probe_detected(
    event_type: &str,
    package: &str,
    packages_csv: &str,
    uid: u32,
    proto: &str,
    ports_hint: &str,
    hit_count: u32,
    window_secs: u32,
) -> Result<()> {
    let uid_s = uid.to_string();
    let hit_s = hit_count.to_string();
    let window_s = window_secs.to_string();

    let am_args = [
        "broadcast",
        "--user",
        "0",
        "-a",
        "com.android.zdtd.service.ACTION_PROXYINFO_PROBE_DETECTED",
        "-p",
        APP_PACKAGE,
        "--es",
        "event_type",
        event_type,
        "--es",
        "package",
        package,
        "--es",
        "packages_csv",
        packages_csv,
        "--ei",
        "uid",
        uid_s.as_str(),
        "--es",
        "proto",
        proto,
        "--es",
        "ports_hint",
        ports_hint,
        "--ei",
        "hit_count",
        hit_s.as_str(),
        "--ei",
        "window_secs",
        window_s.as_str(),
        "--es",
        "source",
        "proxyinfo",
    ];

    let status = Command::new(AM_BIN)
        .args(am_args)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("run am broadcast proxyinfo probe")?;

    if status.success() {
        info!(
            "proxyinfo probe broadcast sent event_type={} package={} uid={} proto={} ports_hint={} hit_count={}",
            event_type, package, uid, proto, ports_hint, hit_count
        );
        return Ok(());
    }

    let cmd_args = [
        "activity",
        "broadcast",
        "--user",
        "0",
        "-a",
        "com.android.zdtd.service.ACTION_PROXYINFO_PROBE_DETECTED",
        "-p",
        APP_PACKAGE,
        "--es",
        "event_type",
        event_type,
        "--es",
        "package",
        package,
        "--es",
        "packages_csv",
        packages_csv,
        "--ei",
        "uid",
        uid_s.as_str(),
        "--es",
        "proto",
        proto,
        "--es",
        "ports_hint",
        ports_hint,
        "--ei",
        "hit_count",
        hit_s.as_str(),
        "--ei",
        "window_secs",
        window_s.as_str(),
        "--es",
        "source",
        "proxyinfo",
    ];

    let fallback = Command::new(CMD_BIN)
        .args(cmd_args)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("run cmd activity broadcast proxyinfo probe")?;

    if fallback.success() {
        info!(
            "proxyinfo probe broadcast sent via cmd event_type={} package={} uid={} proto={} ports_hint={} hit_count={}",
            event_type, package, uid, proto, ports_hint, hit_count
        );
        Ok(())
    } else {
        warn!(
            "proxyinfo probe broadcast failed am={:?} cmd={:?} package={} uid={}",
            status.code(),
            fallback.code(),
            package,
            uid
        );
        anyhow::bail!(
            "proxyinfo probe broadcast failed (am={:?}, cmd={:?})",
            status.code(),
            fallback.code()
        );
    }
}
