use crate::common::{network::is_ipv4, process::any_live_ps_ef_where};
use anyhow::{bail, Context, Result};

#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use std::{fs::{self, OpenOptions}, io::{Read}, os::unix::process::CommandExt, path::{Path, PathBuf}, process::{Command, Stdio}, thread, time::{Duration, Instant}};

use crate::{platform::android::dns as android_dns, common::process::shell::{self, Capture}};

use super::model::*;
use super::profile::*;
use super::network::*;
use super::health::*;

pub(super) fn normalize_config(raw: &str, mut setting: ProfileSetting) -> Result<ImportedConfig> {
    let mut kept = Vec::<String>::new();
    let mut in_interface = false;
    let mut saw_interface = false;
    let mut saw_peer = false;
    let mut extracted_address = Vec::<String>::new();
    let mut extracted_dns = Vec::<String>::new();
    let mut extracted_mtu = None::<u32>;

    let normalized_raw = raw.replace('\r', "");
    for line in normalized_raw.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            let section = trimmed.trim_start_matches('[').trim_end_matches(']').trim().to_ascii_lowercase();
            in_interface = section == "interface";
            if in_interface { saw_interface = true; }
            if section == "peer" { saw_peer = true; }
            kept.push(trimmed.to_string());
            continue;
        }

        if in_interface {
            if let Some((key_raw, val_raw)) = trimmed.split_once('=') {
                let key = key_raw.trim().to_ascii_lowercase();
                let val = strip_inline_comment(val_raw.trim());
                match key.as_str() {
                    "address" => {
                        extracted_address.extend(split_address_text(&val));
                        continue;
                    }
                    "dns" => {
                        extracted_dns.extend(split_dns_text(&val));
                        continue;
                    }
                    "mtu" => {
                        let mtu = val.trim().parse::<u32>()
                            .with_context(|| format!("bad MTU value: {val}"))?;
                        extracted_mtu = Some(mtu);
                        continue;
                    }
                    "table" | "preup" | "postup" | "predown" | "postdown" | "saveconfig" => {
                        continue;
                    }
                    "fwmark" if setting.strip_fwmark => {
                        continue;
                    }
                    _ => {}
                }
            }
        }

        kept.push(line.to_string());
    }

    while kept.last().map(|s| s.trim().is_empty()).unwrap_or(false) {
        kept.pop();
    }

    if !extracted_address.is_empty() {
        extracted_address.sort();
        extracted_address.dedup();
        setting.address = extracted_address;
    }
    if !extracted_dns.is_empty() {
        extracted_dns.sort();
        extracted_dns.dedup();
        setting.dns = extracted_dns;
    }
    if let Some(mtu) = extracted_mtu {
        setting.mtu = mtu;
    }

    validate_setting(&setting)?;
    let normalized = format!("{}\n", kept.join("\n"));
    if !saw_interface || !saw_peer {
        bail!("config must contain [Interface] and [Peer] sections");
    }
    validate_config_minimum(&normalized)?;
    Ok(ImportedConfig { config: normalized, setting })
}

pub(super) fn validate_config_minimum(raw: &str) -> Result<()> {
    let mut section = String::new();
    let mut has_interface = false;
    let mut has_peer = false;
    let mut has_private_key = false;
    let mut has_public_key = false;
    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('#') || trimmed.starts_with(';') || trimmed.is_empty() {
            continue;
        }
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            section = trimmed.trim_start_matches('[').trim_end_matches(']').trim().to_ascii_lowercase();
            if section == "interface" { has_interface = true; }
            if section == "peer" { has_peer = true; }
            continue;
        }
        let Some((key_raw, _)) = trimmed.split_once('=') else { continue; };
        let key = key_raw.trim().to_ascii_lowercase();
        if section == "interface" && key == "privatekey" {
            has_private_key = true;
        }
        if section == "peer" && key == "publickey" {
            has_public_key = true;
        }
        if section == "interface" && matches!(key.as_str(), "address" | "dns" | "mtu" | "table" | "preup" | "postup" | "predown" | "postdown" | "saveconfig") {
            bail!("client.conf must be normalized before awg setconf; unsupported [Interface] key: {key}");
        }
    }
    if !has_interface || !has_peer || !has_private_key || !has_public_key {
        bail!("client.conf must contain [Interface] PrivateKey and [Peer] PublicKey");
    }
    Ok(())
}

pub(super) fn strip_inline_comment(s: &str) -> String {
    let mut out = s.trim().to_string();
    for marker in [" #", " ;"] {
        if let Some(pos) = out.find(marker) {
            out.truncate(pos);
        }
    }
    out.trim().trim_matches('"').trim_matches('\'').to_string()
}

pub(super) fn spawn_amneziawg(plan: &ProfilePlan) -> Result<()> {
    if amneziawg_process_running(&plan.setting.tun) {
        info!("amneziawg: profile={} tun={} already running, skip spawn", plan.name, plan.setting.tun);
        append_start_log(plan, "amneziawg-go already running; skip spawn");
        return Ok(());
    }

    cleanup_interface(&plan.setting.tun);
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    fs::create_dir_all(Path::new(SOCK_DIR)).ok();

    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.go_log_path)
        .with_context(|| format!("open log {}", plan.go_log_path.display()))?;
    let logf_err = logf.try_clone().context("clone amneziawg-go log")?;

    let mut cmd = Command::new(AWG_GO_BIN);
    cmd.arg("-f")
        .arg(&plan.setting.tun)
        .current_dir(AMNEZIAWG_ROOT)
        .env("WG_PROCESS_FOREGROUND", "1")
        .env("LOG_LEVEL", "error")
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {AWG_GO_BIN}"))?;
    info!("amneziawg: spawned profile={} pid={} tun={} log={}", plan.name, child.id(), plan.setting.tun, plan.go_log_path.display());
    append_start_log(plan, &format!("spawned amneziawg-go pid={}", child.id()));

    thread::sleep(Duration::from_millis(250));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("amneziawg: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.go_log_path.display());
    }
    Ok(())
}

pub(super) fn amneziawg_process_running(tun: &str) -> bool {
    let pattern = format!("{} -f {}", AWG_GO_BIN, tun);
    any_live_ps_ef_where(|line| line.contains(&pattern))
}

pub(super) fn apply_awg_config(plan: &ProfilePlan) -> Result<()> {
    let setconf_path = match prepare_setconf_config(plan) {
        Ok(path) => path,
        Err(e) => {
            append_start_log(plan, &format!("prepare awg setconf failed: {e:#}"));
            cleanup_interface(&plan.setting.tun);
            return Err(e);
        }
    };

    append_start_log(plan, &format!("awg setconf config={}", setconf_path.display()));
    let setconf_s = setconf_path.to_string_lossy().to_string();
    let args = ["setconf", plan.setting.tun.as_str(), setconf_s.as_str()];
    let setconf_res = run_command_timeout_with_env(
        AWG_BIN,
        &args,
        &[("WG_ENDPOINT_RESOLUTION_RETRIES", "1")],
        AWG_TIMEOUT,
    );

    let (code, out) = match setconf_res {
        Ok(v) => v,
        Err(e) => {
            append_awg_log(plan, &format!("$ WG_ENDPOINT_RESOLUTION_RETRIES=1 {} setconf {} {}\nERROR: {e:#}\n", AWG_BIN, plan.setting.tun, setconf_path.display()));
            cleanup_interface(&plan.setting.tun);
            bail!("awg setconf failed: {e:#}");
        }
    };

    append_awg_log(plan, &format!("$ WG_ENDPOINT_RESOLUTION_RETRIES=1 {} setconf {} {}\nrc={}\n{}\n", AWG_BIN, plan.setting.tun, setconf_path.display(), code, out));
    if code != 0 {
        cleanup_interface(&plan.setting.tun);
        bail!("awg setconf failed rc={code}: {}", out.trim());
    }
    let (show_code, show_out) = shell::run_timeout(AWG_BIN, &["show", &plan.setting.tun], Capture::Both, AWG_TIMEOUT)
        .unwrap_or((1, String::new()));
    append_awg_log(plan, &format!("$ {} show {}\nrc={}\n{}\n", AWG_BIN, plan.setting.tun, show_code, show_out));
    Ok(())
}

pub(super) fn prepare_setconf_config(plan: &ProfilePlan) -> Result<PathBuf> {
    if !plan.setting.endpoint_resolve {
        return Ok(plan.config_path.clone());
    }
    let raw = read_amneziawg_config_text(&plan.config_path)?;
    let resolved = resolve_endpoint_lines(&raw)
        .with_context(|| format!("resolve Endpoint for {}", plan.config_path.display()))?;
    let tmp = plan.profile_dir.join("tmp/client.resolved.conf");
    crate::common::fs::write_text_tmp_rename(&tmp, &resolved)?;
    append_start_log(plan, &format!("endpoint_resolve enabled; using {}", tmp.display()));
    Ok(tmp)
}

pub(super) fn resolve_endpoint_lines(raw: &str) -> Result<String> {
    let mut out = Vec::new();
    let mut endpoint_count = 0usize;
    for line in raw.lines() {
        let trimmed = line.trim();
        if let Some((key_raw, val_raw)) = trimmed.split_once('=') {
            if key_raw.trim().eq_ignore_ascii_case("Endpoint") {
                endpoint_count += 1;
                let val = strip_inline_comment(val_raw.trim());
                let Some((host, port)) = parse_host_port(&val) else {
                    bail!("unsupported Endpoint format for userspace IPv4 mode: {val}");
                };
                if is_ipv4(&host) {
                    out.push(line.to_string());
                    continue;
                }
                let ip = android_dns::resolve_ipv4(&host)
                    .ok_or_else(|| anyhow::anyhow!("cannot resolve Endpoint host {host} to IPv4"))?;
                out.push(format!("Endpoint = {ip}:{port}"));
                continue;
            }
        }
        out.push(line.to_string());
    }
    if endpoint_count == 0 {
        return Ok(format!("{}\n", out.join("\n")));
    }
    Ok(format!("{}\n", out.join("\n")))
}

pub(super) fn collect_endpoint_escape_ips(plan: &ProfilePlan) -> Vec<String> {
    let raw = match read_amneziawg_config_text(&plan.config_path) {
        Ok(raw) => raw,
        Err(e) => {
            warn!(
                "amneziawg: profile={} cannot read config for endpoint escape: {e:#}",
                plan.name
            );
            return Vec::new();
        }
    };
    let mut ips = Vec::new();
    for line in raw.lines() {
        let trimmed = line.trim();
        let Some((key_raw, val_raw)) = trimmed.split_once('=') else { continue; };
        if !key_raw.trim().eq_ignore_ascii_case("Endpoint") {
            continue;
        }
        let val = strip_inline_comment(val_raw.trim());
        let Some((host, _port)) = parse_host_port(&val) else {
            warn!(
                "amneziawg: profile={} skip endpoint escape for unsupported Endpoint format: {}",
                plan.name,
                val
            );
            continue;
        };
        if is_ipv4(&host) {
            ips.push(host);
        } else if plan.setting.endpoint_resolve {
            match android_dns::resolve_ipv4(&host) {
                Some(ip) => ips.push(ip),
                None => warn!(
                    "amneziawg: profile={} cannot resolve Endpoint host for endpoint escape: {}",
                    plan.name,
                    host
                ),
            }
        } else {
            warn!(
                "amneziawg: profile={} Endpoint host is not IPv4 and endpoint_resolve=false; endpoint escape route skipped for {}",
                plan.name,
                host
            );
        }
    }
    ips.sort();
    ips.dedup();
    if !ips.is_empty() {
        info!(
            "amneziawg: profile={} endpoint escape ips={}",
            plan.name,
            ips.join(",")
        );
        append_start_log(plan, &format!("endpoint escape ips={}", ips.join(",")));
    }
    ips
}

pub(super) fn parse_host_port(s: &str) -> Option<(String, String)> {
    if s.contains('[') || s.matches(':').count() != 1 {
        return None;
    }
    let (host, port) = s.rsplit_once(':')?;
    let host = host.trim().trim_end_matches('.');
    let port = port.trim();
    if host.is_empty() || port.is_empty() || !port.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    if !host.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-') {
        return None;
    }
    Some((host.to_string(), port.to_string()))
}


pub(super) fn run_command_timeout_with_env(
    cmd: &str,
    args: &[&str],
    envs: &[(&str, &str)],
    timeout: Duration,
) -> Result<(i32, String)> {
    info!("exec(timeout={:?}): {} {}", timeout, cmd, args.join(" "));

    let tmp_dir = if Path::new("/data/local/tmp").is_dir() {
        PathBuf::from("/data/local/tmp")
    } else {
        std::env::temp_dir()
    };
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_nanos();
    let stamp = format!("zdtd_amneziawg_{}_{}", std::process::id(), nanos);
    let out_path = tmp_dir.join(format!("{stamp}.out"));
    let err_path = tmp_dir.join(format!("{stamp}.err"));

    let out_file = fs::File::create(&out_path)
        .with_context(|| format!("create stdout temp {}", out_path.display()))?;
    let err_file = fs::File::create(&err_path)
        .with_context(|| format!("create stderr temp {}", err_path.display()))?;

    let mut command = Command::new(cmd);
    command.args(args);
    for (k, v) in envs {
        command.env(k, v);
    }
    command.stdout(Stdio::from(out_file));
    command.stderr(Stdio::from(err_file));

    let mut child = command.spawn().with_context(|| format!("spawn {cmd}"))?;
    let start = Instant::now();
    loop {
        if let Some(status) = child.try_wait().with_context(|| format!("wait {cmd}"))? {
            let code = status.code().unwrap_or(-1);
            let mut out = read_and_remove(&out_path);
            out.push_str(&read_and_remove(&err_path));
            return Ok((code, out));
        }
        if start.elapsed() >= timeout {
            let _ = child.kill();
            let _ = child.wait();
            let mut out = read_and_remove(&out_path);
            out.push_str(&read_and_remove(&err_path));
            bail!("command timeout after {:?}: {} {}\n{}", timeout, cmd, args.join(" "), out);
        }
        thread::sleep(Duration::from_millis(50));
    }
}

pub(super) fn read_and_remove(path: &Path) -> String {
    let mut s = String::new();
    if let Ok(mut f) = fs::File::open(path) {
        let _ = f.read_to_string(&mut s);
    }
    let _ = fs::remove_file(path);
    s
}

