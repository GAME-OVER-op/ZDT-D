use anyhow::{Context, Result};
use log::{info, warn};
use std::process::{Command, Stdio};

use crate::config::{Config, NotificationsConfig};

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
    let cmd = format!(
        "am broadcast --user 0 -a com.android.zdtd.service.ACTION_DAEMON_STATE -n com.android.zdtd.service/.DaemonStateReceiver --ez running {running_s}",
    );

    let status = Command::new("su")
        .args(["-lp", "2000", "-c", &cmd])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("run su am broadcast")?;

    if status.success() {
        info!("daemon state broadcast sent running={}", running);
        Ok(())
    } else {
        warn!("daemon state broadcast failed status={:?}", status.code());
        anyhow::bail!("am broadcast failed (status={:?})", status.code());
    }
}
