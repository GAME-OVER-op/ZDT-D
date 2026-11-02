use anyhow::{Context, Result};
use std::{
    fs::OpenOptions,
    os::unix::process::CommandExt,
    path::Path,
    process::{Child, Command, Stdio},
};

/// How a process log file is opened before stdout/stderr are attached.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogMode {
    Truncate,
    Append,
}

/// Attach one log file to stdout/stderr, detach the child into a new session,
/// and spawn it. The caller remains responsible for constructing the exact
/// binary, argv, cwd and environment before calling this helper.
pub fn spawn_detached_logged<F>(
    cmd: &mut Command,
    log_path: &Path,
    log_mode: LogMode,
    spawn_context: F,
) -> Result<Child>
where
    F: FnOnce() -> String,
{
    spawn_detached_logged_with_clone_context(
        cmd,
        log_path,
        log_mode,
        || "clone log file".to_string(),
        spawn_context,
    )
}

/// Same process lifecycle as [`spawn_detached_logged`], but lets a caller preserve
/// an existing backend-specific clone error context while still sharing the spawn mechanics.
pub fn spawn_detached_logged_with_clone_context<C, F>(
    cmd: &mut Command,
    log_path: &Path,
    log_mode: LogMode,
    clone_context: C,
    spawn_context: F,
) -> Result<Child>
where
    C: FnOnce() -> String,
    F: FnOnce() -> String,
{
    let mut options = OpenOptions::new();
    options.create(true).write(true);
    match log_mode {
        LogMode::Truncate => {
            options.truncate(true);
        }
        LogMode::Append => {
            options.append(true);
        }
    }

    let logf = options
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(clone_context)?;

    cmd.stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    cmd.spawn().with_context(spawn_context)
}
