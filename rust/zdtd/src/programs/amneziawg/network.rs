use anyhow::{bail, Context, Result};
use crate::common::network::{is_ipv4, IP_TIMEOUT, u32_to_ipv4, ipv4_to_u32, first_host_for_cidr};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use std::{fs::{self}, path::{Path}, thread, time::{Duration, Instant}};

use crate::{common::process::shell::{self, Capture}};

use super::model::*;
use super::health::*;

pub(super) fn apply_interface_settings(plan: &ProfilePlan) -> Result<()> {
    for addr in &plan.setting.address {
        let res = shell::run_timeout("ip", &["addr", "add", addr, "dev", &plan.setting.tun], Capture::Both, IP_TIMEOUT);
        let (code, out) = match res {
            Ok(v) => v,
            Err(e) => {
                cleanup_interface(&plan.setting.tun);
                bail!("ip addr add failed for {addr} dev {}: {e:#}", plan.setting.tun);
            }
        };
        if code != 0 && !out.to_ascii_lowercase().contains("file exists") {
            cleanup_interface(&plan.setting.tun);
            bail!("ip addr add failed for {addr} dev {} rc={} out={}", plan.setting.tun, code, out.trim());
        }
    }

    let mtu = plan.setting.mtu.to_string();
    let link_res = shell::run_timeout("ip", &["link", "set", "dev", &plan.setting.tun, "mtu", &mtu, "up"], Capture::Both, IP_TIMEOUT);
    let (code, out) = match link_res {
        Ok(v) => v,
        Err(e) => {
            cleanup_interface(&plan.setting.tun);
            bail!("ip link set dev {} mtu {} up failed: {e:#}", plan.setting.tun, mtu);
        }
    };
    if code != 0 {
        cleanup_interface(&plan.setting.tun);
        bail!("ip link set up failed for {} rc={} out={}", plan.setting.tun, code, out.trim());
    }
    append_start_log(plan, &format!("interface up tun={} mtu={} address={}", plan.setting.tun, plan.setting.mtu, plan.setting.address.join(",")));
    Ok(())
}

pub(super) fn wait_link_ready(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= LINK_WAIT {
            bail!("tun link {tun} was not created after {:?}", LINK_WAIT);
        }
        if link_exists(tun) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(250));
    }
}

pub(super) fn link_exists(tun: &str) -> bool {
    shell::run_timeout("ip", &["link", "show", "dev", tun], Capture::None, IP_TIMEOUT)
        .map(|(code, _)| code == 0)
        .unwrap_or(false)
}

pub(super) fn wait_tun_ready(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT {
            bail!("tun {tun} is not ready after {:?}", TUN_WAIT);
        }
        if inspect_tun(tun).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(400));
    }
}

pub(super) fn inspect_tun(tun: &str) -> Result<TunInfo> {
    let (code, out) = shell::run_timeout("ip", &["-o", "-4", "addr", "show", "dev", tun], Capture::Stdout, IP_TIMEOUT)
        .with_context(|| format!("ip addr show {tun}"))?;
    if code != 0 {
        bail!("ip addr show failed for {tun}");
    }

    let mut cidr = None::<String>;
    let mut gateway = None::<String>;
    for line in out.lines() {
        let tokens = line.split_whitespace().collect::<Vec<_>>();
        for i in 0..tokens.len() {
            if tokens[i] == "inet" {
                if let Some(ip_cidr) = tokens.get(i + 1) {
                    cidr = derive_cidr(ip_cidr);
                }
            }
            if tokens[i] == "peer" {
                if let Some(peer) = tokens.get(i + 1) {
                    let peer_ip = peer.split('/').next().unwrap_or(peer).to_string();
                    if is_ipv4(&peer_ip) {
                        gateway = Some(peer_ip);
                    }
                }
            }
        }
    }

    let cidr = cidr.ok_or_else(|| anyhow::anyhow!("tun {tun} has no IPv4 CIDR"))?;
    if gateway.is_none() {
        gateway = crate::common::network::route_gateway_for_tun(tun).ok();
    }
    if gateway.is_none() {
        gateway = first_host_for_cidr(&cidr);
    }
    Ok(TunInfo { cidr, gateway })
}

pub(super) fn derive_cidr(ip_cidr: &str) -> Option<String> {
    let (ip, prefix_s) = ip_cidr.split_once('/')?;
    let prefix = prefix_s.parse::<u8>().ok()?;
    if !is_ipv4(ip) || prefix > 32 { return None; }
    let addr = ipv4_to_u32(ip)?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    let net = addr & mask;
    Some(format!("{}/{}", u32_to_ipv4(net), prefix))
}








pub(super) fn normalize_ipv4_cidr_token(s: &str) -> Option<String> {
    if s.is_empty() || s.contains(':') { return None; }
    if let Some((ip, prefix)) = s.split_once('/') {
        let prefix = prefix.parse::<u8>().ok()?;
        if is_ipv4(ip) && prefix <= 32 {
            return Some(format!("{ip}/{prefix}"));
        }
        return None;
    }
    if is_ipv4(s) {
        return Some(format!("{s}/32"));
    }
    None
}

pub(super) fn read_amneziawg_config_text(path: &Path) -> Result<String> {
    let data = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    let text = crate::common::fs::external_text::decode_external_text(&data)
        .with_context(|| format!("decode {}", path.display()))?;
    if data.as_slice() != text.as_bytes() {
        let tmp = path.with_file_name("client.conf.encoding.tmp");
        fs::write(&tmp, text.as_bytes())
            .with_context(|| format!("write {}", tmp.display()))?;
        fs::rename(&tmp, path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    }
    Ok(text)
}

pub(super) fn write_import_pair(root: &Path, config: &str, setting: &ProfileSetting) -> Result<()> {
    let config_path = root.join("client.conf");
    let setting_path = root.join("setting.json");
    let config_tmp = root.join("client.conf.import.tmp");
    let setting_tmp = root.join("setting.json.import.tmp");
    let config_backup = root.join("client.conf.import.bak");
    let setting_backup = root.join("setting.json.import.bak");

    let setting_text = serde_json::to_string_pretty(setting)?;
    fs::write(&config_tmp, config.as_bytes())
        .with_context(|| format!("write {}", config_tmp.display()))?;
    if let Err(e) = fs::write(&setting_tmp, setting_text.as_bytes()) {
        let _ = fs::remove_file(&config_tmp);
        return Err(anyhow::anyhow!("write {}: {e}", setting_tmp.display()));
    }

    // Recover a pair left in backup state by an interrupted older import.
    if config_backup.exists() {
        if !config_path.exists() {
            fs::rename(&config_backup, &config_path)
                .with_context(|| format!("recover {}", config_path.display()))?;
        } else {
            let _ = fs::remove_file(&config_backup);
        }
    }
    if setting_backup.exists() {
        if !setting_path.exists() {
            fs::rename(&setting_backup, &setting_path)
                .with_context(|| format!("recover {}", setting_path.display()))?;
        } else {
            let _ = fs::remove_file(&setting_backup);
        }
    }

    let had_config = config_path.exists();
    let had_setting = setting_path.exists();
    if had_config {
        fs::rename(&config_path, &config_backup)
            .with_context(|| format!("backup {}", config_path.display()))?;
    }
    if had_setting {
        if let Err(e) = fs::rename(&setting_path, &setting_backup) {
            if had_config { let _ = fs::rename(&config_backup, &config_path); }
            let _ = fs::remove_file(&config_tmp);
            let _ = fs::remove_file(&setting_tmp);
            return Err(anyhow::anyhow!("backup {}: {e}", setting_path.display()));
        }
    }

    let install_result = (|| -> Result<()> {
        fs::rename(&config_tmp, &config_path)
            .with_context(|| format!("install {}", config_path.display()))?;
        fs::rename(&setting_tmp, &setting_path)
            .with_context(|| format!("install {}", setting_path.display()))?;
        Ok(())
    })();

    if let Err(e) = install_result {
        let _ = fs::remove_file(&config_path);
        let _ = fs::remove_file(&setting_path);
        if had_config { let _ = fs::rename(&config_backup, &config_path); }
        if had_setting { let _ = fs::rename(&setting_backup, &setting_path); }
        let _ = fs::remove_file(&config_tmp);
        let _ = fs::remove_file(&setting_tmp);
        return Err(e);
    }

    let _ = fs::remove_file(&config_backup);
    let _ = fs::remove_file(&setting_backup);
    Ok(())
}

