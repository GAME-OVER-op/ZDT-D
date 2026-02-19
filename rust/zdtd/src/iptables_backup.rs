use anyhow::{Context, Result};
use std::{fs, path::PathBuf};

use crate::{logging, settings, shell};

const IPT_SAVE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(15);
const IPT_RESTORE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(15);
const IPT_FLUSH_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

fn capture_table_v4(table: &str) -> Result<String> {
    let cmd_c = format!("iptables-save -c -t {table}");
    match shell::capture_timeout(&cmd_c, IPT_SAVE_TIMEOUT) {
        Ok(out) => Ok(out),
        Err(e) => {
            logging::warn(&format!(
                "iptables-save -c for table {table} failed ({e:#}); falling back to iptables-save -t {table}"
            ));
            shell::capture_timeout(&format!("iptables-save -t {table}"), IPT_SAVE_TIMEOUT)
                .with_context(|| format!("iptables-save -t {table}"))
        }
    }
}

fn try_capture_table_v6(table: &str) -> Result<Option<String>> {
    let cmd_c = format!("ip6tables-save -c -t {table}");
    match shell::capture_timeout(&cmd_c, IPT_SAVE_TIMEOUT) {
        Ok(out) => Ok(Some(out)),
        Err(e) => {
            logging::warn(&format!(
                "ip6tables-save -c for table {table} failed ({e:#}); falling back to ip6tables-save -t {table}"
            ));
            match shell::capture_timeout(&format!("ip6tables-save -t {table}"), IPT_SAVE_TIMEOUT) {
                Ok(out2) => Ok(Some(out2)),
                Err(e2) => {
                    logging::warn(&format!(
                        "ip6tables-save for table {table} failed ({e2:#}); IPv6 restore will be best-effort"
                    ));
                    Ok(None)
                }
            }
        }
    }
}

fn write_backup_v4(path: &PathBuf) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create settings dir: {}", parent.display()))?;
    }

    // IMPORTANT: back up ONLY nat+mangle. Full iptables-save may include extensions that
    // iptables-restore on Android "legacy" does not accept (we've seen quota2 inversion errors).
    // So we capture two tables and write a combined restore file.
    let nat = capture_table_v4("nat")?;
    let mangle = capture_table_v4("mangle")?;

    let mut data = String::new();
    data.push_str(nat.trim_end_matches('\n'));
    data.push('\n');
    data.push_str(mangle.trim_end_matches('\n'));
    data.push('\n');

    fs::write(path, data)
        .with_context(|| format!("write iptables backup file: {}", path.display()))?;

    logging::info(&format!(
        "iptables backup saved (tables: nat,mangle): {}",
        path.display()
    ));
    Ok(())
}

fn write_backup_v6(path: &PathBuf) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create settings dir: {}", parent.display()))?;
    }

    // IPv6: nat table may be unavailable on some Android builds. We back up what we can.
    let nat = try_capture_table_v6("nat")?;
    let nat_present = nat.is_some();
    let mangle = try_capture_table_v6("mangle")?;

    let Some(mangle) = mangle else {
        logging::warn("ip6tables mangle table backup failed; IPv6 restore will be best-effort");
        return Ok(());
    };

    let mut data = String::new();
    if let Some(nat) = nat {
        data.push_str(nat.trim_end_matches('\n'));
        data.push('\n');
    }
    data.push_str(mangle.trim_end_matches('\n'));
    data.push('\n');

    fs::write(path, data)
        .with_context(|| format!("write ip6tables backup file: {}", path.display()))?;

    if nat_present {
        logging::info(&format!(
            "ip6tables backup saved (tables: nat,mangle): {}",
            path.display()
        ));
    } else {
        logging::info(&format!(
            "ip6tables backup saved (tables: mangle; nat unsupported): {}",
            path.display()
        ));
    }

    Ok(())
}

fn ip6_nat_supported() -> bool {
    match shell::run_timeout(
        "ip6tables",
        &["-t", "nat", "-nL"],
        shell::Capture::None,
        IPT_FLUSH_TIMEOUT,
    ) {
        Ok((c, _)) => c == 0,
        Err(_) => false,
    }
}

/// Ensure iptables backup exists for the *first* full launch.
///
/// Logic:
/// - If start.json first_launch == false: create backup(s) and set first_launch=true.
/// - If first_launch == true: do nothing, but if backup file is missing, we recreate it as a safety net.
pub fn ensure_backup_if_first_launch() -> Result<()> {
    let mut st = settings::load_start_settings().unwrap_or_default();
    let path_v4 = settings::iptables_backup_path();
    let path_v6 = settings::ip6tables_backup_path();

    if !st.first_launch {
        write_backup_v4(&path_v4)?;
        // IPv6 backup is best-effort (device-dependent).
        let _ = write_backup_v6(&path_v6);

        st.first_launch = true;
        settings::save_start_settings(&st)?;
        logging::info("first_launch=true (iptables/ip6tables backup done)");
        return Ok(());
    }

    if !path_v4.exists() {
        logging::warn("iptables backup missing but first_launch=true; recreating backup");
        write_backup_v4(&path_v4)?;
    }

    if !path_v6.exists() {
        logging::warn("ip6tables backup missing but first_launch=true; recreating backup (best-effort)");
        let _ = write_backup_v6(&path_v6);
    }

    Ok(())
}

/// Flush nat/mangle and restore the previously captured backup.
///
/// Order (IPv4):
/// 1) iptables -t nat -F
/// 2) iptables -t mangle -F
/// 3) iptables-restore < backup
///
/// IPv6 is applied best-effort:
/// - flush mangle always
/// - flush nat only if supported
/// - restore from ip6tables backup if present
pub fn reset_tables_then_restore_backup() -> Result<()> {
    let path_v4 = settings::iptables_backup_path();
    if !path_v4.exists() {
        anyhow::bail!("iptables backup file not found: {}", path_v4.display());
    }

    let _ = shell::ok_sh_timeout("iptables -t nat -F", IPT_FLUSH_TIMEOUT);
    let _ = shell::ok_sh_timeout("iptables -t mangle -F", IPT_FLUSH_TIMEOUT);

    // stdin redirect requires sh -c
    let restore_line = format!("iptables-restore < '{}'", path_v4.display());
    shell::ok_sh_timeout(&restore_line, IPT_RESTORE_TIMEOUT)
        .with_context(|| format!("iptables-restore from {}", path_v4.display()))?;
    logging::info("iptables restored from backup");

    // IPv6 restore (best-effort)
    let path_v6 = settings::ip6tables_backup_path();
    if ip6_nat_supported() {
        let _ = shell::ok_sh_timeout("ip6tables -t nat -F", IPT_FLUSH_TIMEOUT);
    }
    let _ = shell::ok_sh_timeout("ip6tables -t mangle -F", IPT_FLUSH_TIMEOUT);

    if path_v6.exists() {
        let restore6 = format!("ip6tables-restore < '{}'", path_v6.display());
        match shell::ok_sh_timeout(&restore6, IPT_RESTORE_TIMEOUT) {
            Ok(_) => logging::info("ip6tables restored from backup"),
            Err(e) => logging::warn(&format!(
                "ip6tables-restore from {} failed ({e:#}); continuing",
                path_v6.display()
            )),
        }
    } else {
        logging::warn("ip6tables backup missing; IPv6 tables flushed without restore");
    }

    Ok(())
}
