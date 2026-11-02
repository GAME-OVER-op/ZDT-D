use anyhow::{Context, Result};
use crate::common::config::deserialize_boolish;
use serde::{Deserialize, Serialize};
use std::{fs};

use crate::{
    platform::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker},
    logging,
    settings,
    common::process::shell::Capture,
    iptables::lock as xtables_lock,
};

const QUIC_CHAIN: &str = "ZDT_BLOCKEDQUIC";
const IPT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
// IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
// Never introduce module-specific *.flag.sha256 files here.
const UID_TRACKER_FILE: &str = settings::SHARED_SHA_FLAG_FILE;



fn serialize_bool_as_bool<S>(value: &bool, serializer: S) -> std::result::Result<S::Ok, S::Error>
where
    S: serde::Serializer,
{
    serializer.serialize_bool(*value)
}


#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnabledJson {
    #[serde(default, deserialize_with = "deserialize_boolish", serialize_with = "serialize_bool_as_bool")]
    pub enabled: bool,
}

impl Default for EnabledJson {
    fn default() -> Self {
        Self { enabled: false }
    }
}

impl EnabledJson {
    pub fn normalized(&self) -> Self {
        Self {
            enabled: self.enabled,
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }
}

#[derive(Debug, Clone, Copy)]
enum Backend {
    Iptables,
    Ip6tables,
}

impl Backend {
    fn cmd(self) -> &'static str {
        match self {
            Self::Iptables => "iptables",
            Self::Ip6tables => "ip6tables",
        }
    }
}






pub fn ensure_layout() -> Result<()> {
    let root = settings::blockedquic_root_path();
    fs::create_dir_all(&root).with_context(|| format!("mkdir {}", root.display()))?;

    let enabled_path = settings::blockedquic_enabled_json_path();
    if !enabled_path.exists() {
        crate::common::fs::write_json_pretty_atomic_context(&enabled_path, &EnabledJson::default())?;
    } else {
        let current = match fs::read_to_string(&enabled_path) {
            Ok(raw) => serde_json::from_str::<EnabledJson>(&raw).unwrap_or_default().normalized(),
            Err(_) => EnabledJson::default(),
        };
        crate::common::fs::write_json_pretty_atomic_context(&enabled_path, &current)?;
    }

    crate::common::fs::ensure_empty_file_atomic_context(&settings::blockedquic_uid_program_path())?;
    crate::common::fs::ensure_empty_file_atomic_context(&settings::blockedquic_out_program_path())?;
    Ok(())
}

pub fn load_enabled_json() -> Result<EnabledJson> {
    ensure_layout()?;
    let path = settings::blockedquic_enabled_json_path();
    let raw = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    match serde_json::from_str::<EnabledJson>(&raw) {
        Ok(v) => Ok(v.normalized()),
        Err(_) => Ok(EnabledJson::default()),
    }
}

pub fn save_enabled_value(enabled: bool) -> Result<EnabledJson> {
    ensure_layout()?;
    let v = EnabledJson { enabled }.normalized();
    crate::common::fs::write_json_pretty_atomic_context(&settings::blockedquic_enabled_json_path(), &v)?;
    Ok(v)
}

pub fn read_uid_program_text() -> Result<String> {
    ensure_layout()?;
    crate::common::apps::read_optional_uid_program_text(&settings::blockedquic_uid_program_path())
}

pub fn write_uid_program_text(content: &str) -> Result<()> {
    ensure_layout()?;
    crate::common::fs::write_text_atomic_context(&settings::blockedquic_uid_program_path(), content)
}

pub fn rebuild_out_program() -> Result<Vec<u32>> {
    ensure_layout()?;
    let tracker = Sha256Tracker::new(UID_TRACKER_FILE);
    let input = settings::blockedquic_uid_program_path();
    let output = settings::blockedquic_out_program_path();
    let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &output, &input)
        .with_context(|| "blockedquic uid parsing")?;
    read_out_uids()
}

pub fn read_out_uids() -> Result<Vec<u32>> {
    ensure_layout()?;
    crate::common::apps::read_out_uids(&settings::blockedquic_out_program_path())
}

pub fn is_active() -> bool {
    let _guard = xtables_lock::lock();
    any_hook_active(Backend::Iptables) || any_hook_active(Backend::Ip6tables)
}

fn table_cmd_ok(backend: Backend, args: &[&str]) -> bool {
    match xtables_lock::run_timeout_retry(backend.cmd(), args, Capture::Both, IPT_TIMEOUT) {
        Ok((rc, _)) => rc == 0,
        Err(_) => false,
    }
}

fn any_hook_active(backend: Backend) -> bool {
    table_cmd_ok(backend, &["-C", "OUTPUT", "-j", QUIC_CHAIN])
        || table_cmd_ok(backend, &["-C", "OUTPUT", "-p", "udp", "-j", QUIC_CHAIN])
}

fn remove_known_hooks(backend: Backend) {
    let hooks = [
        vec!["-D", "OUTPUT", "-j", QUIC_CHAIN],
        vec!["-D", "OUTPUT", "-p", "udp", "-j", QUIC_CHAIN],
    ];
    for hook in hooks {
        while table_cmd_ok(backend, &hook) {}
    }
}

fn clear_chain_unlocked(backend: Backend) {
    remove_known_hooks(backend);
    let _ = xtables_lock::run_timeout_retry(backend.cmd(), &["-F", QUIC_CHAIN], Capture::Both, IPT_TIMEOUT);
    let _ = xtables_lock::run_timeout_retry(backend.cmd(), &["-X", QUIC_CHAIN], Capture::Both, IPT_TIMEOUT);
}

fn clear_rules_unlocked() {
    clear_chain_unlocked(Backend::Iptables);
    clear_chain_unlocked(Backend::Ip6tables);
}

pub fn clear_rules() -> Result<()> {
    let _guard = xtables_lock::lock();
    clear_rules_unlocked();
    Ok(())
}

fn ensure_chain(backend: Backend) -> Result<()> {
    if !table_cmd_ok(backend, &["-L", QUIC_CHAIN]) {
        let (rc, out) = xtables_lock::run_timeout_retry(backend.cmd(), &["-N", QUIC_CHAIN], Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("{} -N {} failed: {}", backend.cmd(), QUIC_CHAIN, out);
        }
    }
    let (rc, out) = xtables_lock::run_timeout_retry(backend.cmd(), &["-F", QUIC_CHAIN], Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("{} -F {} failed: {}", backend.cmd(), QUIC_CHAIN, out);
    }
    remove_known_hooks(backend);
    let (rc, out) = xtables_lock::run_timeout_retry(
        backend.cmd(),
        &["-I", "OUTPUT", "1", "-j", QUIC_CHAIN],
        Capture::Both,
        IPT_TIMEOUT,
    )?;
    if rc != 0 {
        anyhow::bail!("{} -I OUTPUT -> {} failed: {}", backend.cmd(), QUIC_CHAIN, out);
    }
    Ok(())
}

fn install_ipv4_rules(uids: &[u32]) -> Result<()> {
    if uids.is_empty() {
        return Ok(());
    }
    ensure_chain(Backend::Iptables)?;
    for &uid in uids {
        let args = vec![
            "-A".to_string(),
            QUIC_CHAIN.to_string(),
            "-p".to_string(),
            "udp".to_string(),
            "--dport".to_string(),
            "443".to_string(),
            "-m".to_string(),
            "owner".to_string(),
            "--uid-owner".to_string(),
            uid.to_string(),
            "-j".to_string(),
            "REJECT".to_string(),
            "--reject-with".to_string(),
            "icmp-port-unreachable".to_string(),
        ];
        let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Iptables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("iptables add blockedquic IPv4 rule failed uid={} args='{}': {}", uid, args.join(" "), out);
        }
    }
    Ok(())
}

fn install_ipv6_rules(uids: &[u32]) -> Result<()> {
    if uids.is_empty() {
        return Ok(());
    }
    ensure_chain(Backend::Ip6tables)?;
    for &uid in uids {
        let args = vec![
            "-A".to_string(),
            QUIC_CHAIN.to_string(),
            "-p".to_string(),
            "udp".to_string(),
            "--dport".to_string(),
            "443".to_string(),
            "-m".to_string(),
            "owner".to_string(),
            "--uid-owner".to_string(),
            uid.to_string(),
            "-j".to_string(),
            "REJECT".to_string(),
        ];
        let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Ip6tables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("ip6tables add blockedquic IPv6 rule failed uid={} args='{}': {}", uid, args.join(" "), out);
        }
    }
    Ok(())
}

fn install_rules(uids: &[u32]) -> Result<()> {
    let _guard = xtables_lock::lock();
    clear_rules_unlocked();
    if uids.is_empty() {
        return Ok(());
    }

    let result = (|| {
        install_ipv4_rules(uids)?;
        if let Err(e) = install_ipv6_rules(uids) {
            logging::warn(&format!(
                "blockedquic IPv6 rules unavailable, keeping IPv4 rules active: {e:#}"
            ));
        }
        Ok(())
    })();

    if result.is_err() {
        clear_rules_unlocked();
    }
    result
}

pub fn refresh_runtime(services_running: bool) -> Result<bool> {
    ensure_layout()?;
    let enabled = load_enabled_json()?.is_enabled();
    logging::info(&format!(
        "blockedquic refresh begin: enabled={}, services_running={}",
        enabled, services_running
    ));
    if !enabled {
        logging::info("blockedquic refresh: disabled -> clearing rules");
        clear_rules()?;
        return Ok(false);
    }

    logging::info("blockedquic refresh: rebuilding uid list");
    let _ = rebuild_out_program()?;
    if !services_running {
        logging::info("blockedquic refresh: services not running -> clearing rules");
        clear_rules()?;
        return Ok(false);
    }

    let uids = read_out_uids()?;
    logging::info(&format!("blockedquic refresh: uid count={}", uids.len()));
    if uids.is_empty() {
        logging::info("blockedquic refresh: no uids -> clearing rules");
        clear_rules()?;
        return Ok(false);
    }

    logging::info("blockedquic refresh: installing IPv4/IPv6 UDP/443 rules");
    install_rules(&uids)?;
    logging::info(&format!(
        "blockedquic active: {} uid(s), UDP/443 deny on IPv4+IPv6",
        uids.len()
    ));
    Ok(true)
}
