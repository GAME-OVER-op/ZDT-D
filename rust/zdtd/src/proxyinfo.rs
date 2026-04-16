use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};

use crate::{
    android::pkg_uid::{self, Mode as UidMode, Sha256Tracker},
    logging,
    settings,
    shell::Capture,
    xtables_lock,
};

const PROXY_CHAIN: &str = "ZDT_PROXYINFO";
const IPT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
// IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
// Never introduce module-specific *.flag.sha256 files here.
const UID_TRACKER_FILE: &str = settings::SHARED_SHA_FLAG_FILE;

fn deserialize_boolish<'de, D>(deserializer: D) -> std::result::Result<bool, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::{Error as _, Unexpected};
    let v = serde_json::Value::deserialize(deserializer)?;
    match v {
        serde_json::Value::Bool(b) => Ok(b),
        serde_json::Value::Number(n) => match n.as_i64() {
            Some(0) => Ok(false),
            Some(1) => Ok(true),
            _ => Err(D::Error::invalid_value(Unexpected::Other("number"), &"bool or 0/1")),
        },
        serde_json::Value::String(s) => match s.trim().to_ascii_lowercase().as_str() {
            "true" | "1" => Ok(true),
            "false" | "0" => Ok(false),
            _ => Err(D::Error::invalid_value(Unexpected::Str(&s), &"bool or 0/1")),
        },
        _ => Err(D::Error::invalid_value(Unexpected::Other("non-bool"), &"bool or 0/1")),
    }
}

fn serialize_bool_as_bool<S>(value: &bool, serializer: S) -> std::result::Result<S::Ok, S::Error>
where
    S: serde::Serializer,
{
    serializer.serialize_bool(*value)
}

const API_PORT: u16 = 1006;

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

#[derive(Debug, Deserialize, Default)]
struct ProfileState {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct ProfilesActive {
    #[serde(default)]
    profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Deserialize, Default)]
struct EnabledActive {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct ProfilePortJson {
    #[serde(default)]
    port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct OperaActive {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct OperaPortJson {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default)]
    opera_start_port: u16,
    #[serde(default)]
    byedpi_port: u16,
}

fn default_operaproxy_t2s_web_port() -> u16 {
    8000
}

#[derive(Debug, Deserialize, Default)]
struct SingboxProfileSetting {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    t2s_web_port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct SingboxServerSetting {
    #[serde(default)]
    enabled: bool,
    #[serde(default)]
    port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct WireproxyProfileSetting {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    t2s_web_port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct WireproxyServerSetting {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct TorEnabledJson {
    #[serde(default, deserialize_with = "deserialize_boolish")]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct TorSetting {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default)]
    t2s_web_port: u16,
}

fn default_t2s_web_port() -> u16 {
    8001
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

    fn label(self) -> &'static str {
        match self {
            Self::Iptables => "IPv4",
            Self::Ip6tables => "IPv6",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TransportProtocol {
    Tcp,
    Udp,
}

impl TransportProtocol {
    fn name(self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum LocalMatchMode {
    LoAndDestLoopback,
    DestLoopback,
    LoopbackIface,
    DstTypeLocal,
}

impl LocalMatchMode {
    fn describe(self, backend: Backend) -> &'static str {
        match (self, backend) {
            (Self::LoAndDestLoopback, Backend::Iptables) => "-o lo -d 127.0.0.1",
            (Self::DestLoopback, Backend::Iptables) => "-d 127.0.0.1",
            (Self::LoopbackIface, Backend::Iptables) => "-o lo",
            (Self::DstTypeLocal, Backend::Iptables) => "-m addrtype --dst-type LOCAL",
            (Self::LoAndDestLoopback, Backend::Ip6tables) => "-o lo -d ::1",
            (Self::DestLoopback, Backend::Ip6tables) => "-d ::1",
            (Self::LoopbackIface, Backend::Ip6tables) => "-o lo",
            (Self::DstTypeLocal, Backend::Ip6tables) => "-m addrtype --dst-type LOCAL",
        }
    }

    fn push_args(self, backend: Backend, args: &mut Vec<String>) {
        match (self, backend) {
            (Self::LoAndDestLoopback, Backend::Iptables) => {
                args.push("-o".into());
                args.push("lo".into());
                args.push("-d".into());
                args.push("127.0.0.1".into());
            }
            (Self::DestLoopback, Backend::Iptables) => {
                args.push("-d".into());
                args.push("127.0.0.1".into());
            }
            (Self::LoopbackIface, _) => {
                args.push("-o".into());
                args.push("lo".into());
            }
            (Self::DstTypeLocal, _) => {
                args.push("-m".into());
                args.push("addrtype".into());
                args.push("--dst-type".into());
                args.push("LOCAL".into());
            }
            (Self::LoAndDestLoopback, Backend::Ip6tables) => {
                args.push("-o".into());
                args.push("lo".into());
                args.push("-d".into());
                args.push("::1".into());
            }
            (Self::DestLoopback, Backend::Ip6tables) => {
                args.push("-d".into());
                args.push("::1".into());
            }
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum PortRuleMode {
    Multiport,
    PerPort,
}

impl PortRuleMode {
    fn describe(self) -> &'static str {
        match self {
            Self::Multiport => "multiport",
            Self::PerPort => "per-port",
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum RejectMode {
    RejectTcpReset,
    Reject,
    Drop,
}

impl RejectMode {
    fn describe(self) -> &'static str {
        match self {
            Self::RejectTcpReset => "REJECT tcp-reset",
            Self::Reject => "REJECT",
            Self::Drop => "DROP",
        }
    }

    fn push_args(self, args: &mut Vec<String>) {
        match self {
            Self::RejectTcpReset => {
                args.push("-j".into());
                args.push("REJECT".into());
                args.push("--reject-with".into());
                args.push("tcp-reset".into());
            }
            Self::Reject => {
                args.push("-j".into());
                args.push("REJECT".into());
            }
            Self::Drop => {
                args.push("-j".into());
                args.push("DROP".into());
            }
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct ProxyRuleStrategy {
    local_match: LocalMatchMode,
    port_mode: PortRuleMode,
    reject_mode: RejectMode,
}

impl ProxyRuleStrategy {
    fn describe(self, backend: Backend, proto: TransportProtocol) -> String {
        format!(
            "backend={}, proto={}, local={}, ports={}, action={}",
            backend.label(),
            proto.name(),
            self.local_match.describe(backend),
            self.port_mode.describe(),
            self.reject_mode.describe()
        )
    }
}

fn read_json_file<T: for<'de> Deserialize<'de> + Default>(p: &Path) -> Result<T> {
    let raw = fs::read_to_string(p).with_context(|| format!("read {}", p.display()))?;
    let v: T = serde_json::from_str(&raw).with_context(|| format!("parse {}", p.display()))?;
    Ok(v)
}

fn write_text_atomic(p: &Path, content: &str) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, content).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, p).with_context(|| format!("rename {} -> {}", tmp.display(), p.display()))?;
    Ok(())
}

fn write_json_atomic<T: Serialize>(p: &Path, v: &T) -> Result<()> {
    let txt = serde_json::to_string_pretty(v)?;
    write_text_atomic(p, &txt)
}

fn ensure_empty_file(p: &Path) -> Result<()> {
    if !p.exists() {
        write_text_atomic(p, "")?;
    }
    Ok(())
}

pub fn ensure_layout() -> Result<()> {
    let root = settings::proxyinfo_root_path();
    fs::create_dir_all(&root).with_context(|| format!("mkdir {}", root.display()))?;

    let enabled_path = settings::proxyinfo_enabled_json_path();
    if !enabled_path.exists() {
        write_json_atomic(&enabled_path, &EnabledJson::default())?;
    } else {
        // Normalize existing file; invalid JSON falls back to default and gets rewritten.
        let current = match fs::read_to_string(&enabled_path) {
            Ok(raw) => serde_json::from_str::<EnabledJson>(&raw).unwrap_or_default().normalized(),
            Err(_) => EnabledJson::default(),
        };
        write_json_atomic(&enabled_path, &current)?;
    }

    ensure_empty_file(&settings::proxyinfo_uid_program_path())?;
    ensure_empty_file(&settings::proxyinfo_out_program_path())?;
    Ok(())
}

pub fn load_enabled_json() -> Result<EnabledJson> {
    ensure_layout()?;
    let path = settings::proxyinfo_enabled_json_path();
    let raw = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    match serde_json::from_str::<EnabledJson>(&raw) {
        Ok(v) => Ok(v.normalized()),
        Err(_) => Ok(EnabledJson::default()),
    }
}

pub fn save_enabled_value(enabled: bool) -> Result<EnabledJson> {
    ensure_layout()?;
    let v = EnabledJson { enabled }.normalized();
    write_json_atomic(&settings::proxyinfo_enabled_json_path(), &v)?;
    Ok(v)
}

pub fn read_uid_program_text() -> Result<String> {
    ensure_layout()?;
    match fs::read_to_string(settings::proxyinfo_uid_program_path()) {
        Ok(s) => Ok(s),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(anyhow::anyhow!("read uid_program failed: {e}")),
    }
}

pub fn write_uid_program_text(content: &str) -> Result<()> {
    ensure_layout()?;
    write_text_atomic(&settings::proxyinfo_uid_program_path(), content)
}

pub fn read_proxy_packages() -> Result<BTreeSet<String>> {
    let raw = read_uid_program_text()?;
    Ok(parse_package_lines(&raw))
}

fn parse_package_lines(raw: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for line in raw.lines() {
        let mut s = line.trim();
        if let Some((left, _)) = s.split_once('#') {
            s = left.trim();
        }
        if s.is_empty() {
            continue;
        }
        out.insert(s.to_string());
    }
    out
}

pub fn rebuild_out_program() -> Result<Vec<u32>> {
    ensure_layout()?;
    let tracker = Sha256Tracker::new(UID_TRACKER_FILE);
    let input = settings::proxyinfo_uid_program_path();
    let output = settings::proxyinfo_out_program_path();
    let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &output, &input)
        .with_context(|| "proxyInfo uid parsing")?;
    read_out_uids()
}

pub fn read_out_uids() -> Result<Vec<u32>> {
    ensure_layout()?;
    let raw = match fs::read_to_string(settings::proxyinfo_out_program_path()) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(anyhow::anyhow!("read out_program failed: {e}")),
    };

    let mut out = BTreeSet::new();
    for line in raw.lines() {
        let s = line.trim();
        if s.is_empty() {
            continue;
        }
        let Some((_, rhs)) = s.rsplit_once('=') else {
            continue;
        };
        if let Ok(uid) = rhs.trim().parse::<u32>() {
            if uid > 0 {
                out.insert(uid);
            }
        }
    }
    Ok(out.into_iter().collect())
}

pub fn read_out_uid_packages() -> Result<BTreeMap<u32, Vec<String>>> {
    ensure_layout()?;
    let raw = match fs::read_to_string(settings::proxyinfo_out_program_path()) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(anyhow::anyhow!("read out_program failed: {e}")),
    };

    let mut out: BTreeMap<u32, Vec<String>> = BTreeMap::new();
    for line in raw.lines() {
        let s = line.trim();
        if s.is_empty() {
            continue;
        }
        let Some((pkg, rhs)) = s.rsplit_once('=') else {
            continue;
        };
        let pkg = pkg.trim();
        if pkg.is_empty() {
            continue;
        }
        if let Ok(uid) = rhs.trim().parse::<u32>() {
            if uid > 0 {
                out.entry(uid).or_default().push(pkg.to_string());
            }
        }
    }
    Ok(out)
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
    let hooks: &[&[&str]] = match backend {
        Backend::Iptables => &[
            &["-C", "OUTPUT", "-j", PROXY_CHAIN],
            &["-C", "OUTPUT", "-p", "tcp", "-j", PROXY_CHAIN],
            &["-C", "OUTPUT", "-p", "udp", "-j", PROXY_CHAIN],
        ],
        Backend::Ip6tables => &[
            &["-C", "OUTPUT", "-j", PROXY_CHAIN],
            &["-C", "OUTPUT", "-p", "tcp", "-j", PROXY_CHAIN],
            &["-C", "OUTPUT", "-p", "udp", "-j", PROXY_CHAIN],
        ],
    };
    hooks.iter().any(|args| table_cmd_ok(backend, args))
}

fn remove_known_hooks(backend: Backend) {
    let mut legacy_hooks: Vec<Vec<&str>> = vec![
        vec!["-D", "OUTPUT", "-j", PROXY_CHAIN],
        vec!["-D", "OUTPUT", "-p", "tcp", "-j", PROXY_CHAIN],
        vec!["-D", "OUTPUT", "-p", "udp", "-j", PROXY_CHAIN],
    ];

    match backend {
        Backend::Iptables => {
            legacy_hooks.push(vec!["-D", "OUTPUT", "-o", "lo", "-p", "tcp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-p", "tcp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-o", "lo", "-p", "udp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-p", "udp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]);
        }
        Backend::Ip6tables => {
            legacy_hooks.push(vec!["-D", "OUTPUT", "-o", "lo", "-p", "tcp", "-d", "::1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-p", "tcp", "-d", "::1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-o", "lo", "-p", "udp", "-d", "::1", "-j", PROXY_CHAIN]);
            legacy_hooks.push(vec!["-D", "OUTPUT", "-p", "udp", "-d", "::1", "-j", PROXY_CHAIN]);
        }
    }

    for hook in legacy_hooks {
        while table_cmd_ok(backend, &hook) {}
    }
}

fn clear_chain_unlocked(backend: Backend) {
    remove_known_hooks(backend);
    let _ = xtables_lock::run_timeout_retry(backend.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT);
    let _ = xtables_lock::run_timeout_retry(backend.cmd(), &["-X", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT);
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
    if !table_cmd_ok(backend, &["-L", PROXY_CHAIN]) {
        let (rc, out) = xtables_lock::run_timeout_retry(backend.cmd(), &["-N", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("{} -N {} failed: {}", backend.cmd(), PROXY_CHAIN, out);
        }
    }
    let (rc, out) = xtables_lock::run_timeout_retry(backend.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("{} -F {} failed: {}", backend.cmd(), PROXY_CHAIN, out);
    }
    remove_known_hooks(backend);
    let (rc, out) = xtables_lock::run_timeout_retry(
        backend.cmd(),
        &["-I", "OUTPUT", "1", "-j", PROXY_CHAIN],
        Capture::Both,
        IPT_TIMEOUT,
    )?;
    if rc != 0 {
        anyhow::bail!("{} -I OUTPUT -> {} failed: {}", backend.cmd(), PROXY_CHAIN, out);
    }
    Ok(())
}

fn build_rule_args_v4(
    uid: u32,
    proto: TransportProtocol,
    ports: &[u16],
    strategy: ProxyRuleStrategy,
) -> Vec<String> {
    let mut args = vec![
        "-A".to_string(),
        PROXY_CHAIN.to_string(),
        "-p".to_string(),
        proto.name().to_string(),
    ];
    strategy.local_match.push_args(Backend::Iptables, &mut args);
    args.push("-m".into());
    args.push("owner".into());
    args.push("--uid-owner".into());
    args.push(uid.to_string());

    match strategy.port_mode {
        PortRuleMode::Multiport => {
            let ports_csv = ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(",");
            args.push("-m".into());
            args.push("multiport".into());
            args.push("--dports".into());
            args.push(ports_csv);
        }
        PortRuleMode::PerPort => {
            let port = ports.first().copied().unwrap_or_default();
            args.push("--dport".into());
            args.push(port.to_string());
        }
    }

    strategy.reject_mode.push_args(&mut args);
    args
}

fn add_rule_for_ports_v4(
    uid: u32,
    proto: TransportProtocol,
    ports: &[u16],
    strategy: ProxyRuleStrategy,
) -> Result<()> {
    let args = build_rule_args_v4(uid, proto, ports, strategy);
    let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Iptables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!(
            "{} add proxyInfo rule failed uid={} strategy={} args='{}': {}",
            Backend::Iptables.cmd(),
            uid,
            strategy.describe(Backend::Iptables, proto),
            args.join(" "),
            out
        );
    }
    Ok(())
}

fn install_proto_rules_v4_with_strategy(
    uids: &[u32],
    ports: &[u16],
    strategy: ProxyRuleStrategy,
    proto: TransportProtocol,
) -> Result<()> {
    for &uid in uids {
        match strategy.port_mode {
            PortRuleMode::Multiport => {
                for chunk in ports.chunks(15) {
                    add_rule_for_ports_v4(uid, proto, chunk, strategy)?;
                }
            }
            PortRuleMode::PerPort => {
                for &port in ports {
                    add_rule_for_ports_v4(uid, proto, &[port], strategy)?;
                }
            }
        }
    }
    Ok(())
}

fn candidate_strategies_for_proto(proto: TransportProtocol) -> Vec<ProxyRuleStrategy> {
    let local_modes = [
        LocalMatchMode::LoAndDestLoopback,
        LocalMatchMode::DestLoopback,
        LocalMatchMode::DstTypeLocal,
        LocalMatchMode::LoopbackIface,
    ];
    let port_modes = [PortRuleMode::Multiport, PortRuleMode::PerPort];
    let reject_modes: &[RejectMode] = match proto {
        TransportProtocol::Tcp => &[RejectMode::RejectTcpReset, RejectMode::Reject, RejectMode::Drop],
        TransportProtocol::Udp => &[RejectMode::Reject, RejectMode::Drop],
    };

    let mut out = Vec::new();
    for local_match in local_modes {
        for port_mode in port_modes {
            for &reject_mode in reject_modes {
                out.push(ProxyRuleStrategy {
                    local_match,
                    port_mode,
                    reject_mode,
                });
            }
        }
    }
    out
}

fn select_ipv4_strategy(
    uids: &[u32],
    ports: &[u16],
    proto: TransportProtocol,
    prerequisite: Option<(TransportProtocol, ProxyRuleStrategy)>,
) -> Result<ProxyRuleStrategy> {
    let mut errors = Vec::new();
    for strategy in candidate_strategies_for_proto(proto) {
        let (rc, out) = xtables_lock::run_timeout_retry(Backend::Iptables.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("{} -F {} failed before strategy install: {}", Backend::Iptables.cmd(), PROXY_CHAIN, out);
        }

        if let Some((prev_proto, prev_strategy)) = prerequisite {
            if let Err(e) = install_proto_rules_v4_with_strategy(uids, ports, prev_strategy, prev_proto) {
                anyhow::bail!(
                    "failed to reinstall prerequisite proxyInfo {} strategy {}: {e:#}",
                    prev_proto.name(),
                    prev_strategy.describe(Backend::Iptables, prev_proto)
                );
            }
        }

        match install_proto_rules_v4_with_strategy(uids, ports, strategy, proto) {
            Ok(()) => {
                logging::info(&format!(
                    "proxyInfo {} strategy selected: {}",
                    proto.name(),
                    strategy.describe(Backend::Iptables, proto)
                ));
                return Ok(strategy);
            }
            Err(e) => {
                let msg = format!("{} => {e:#}", strategy.describe(Backend::Iptables, proto));
                logging::warn(&format!("proxyInfo strategy failed: {}", msg));
                errors.push(msg);
            }
        }
    }

    anyhow::bail!(
        "all proxyInfo {} strategies failed: {}",
        proto.name(),
        errors.join(" | ")
    )
}

fn install_ipv4_rules(uids: &[u32], ports: &[u16], global_ports: &[u16]) -> Result<()> {
    if uids.is_empty() || (ports.is_empty() && global_ports.is_empty()) {
        return Ok(());
    }

    ensure_chain(Backend::Iptables)?;
    let (rc, out) = xtables_lock::run_timeout_retry(Backend::Iptables.cmd(), &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("{} -F {} failed: {}", Backend::Iptables.cmd(), PROXY_CHAIN, out);
    }
    if !ports.is_empty() {
        let tcp_strategy = select_ipv4_strategy(uids, ports, TransportProtocol::Tcp, None)?;
        let udp_strategy = select_ipv4_strategy(
            uids,
            ports,
            TransportProtocol::Udp,
            Some((TransportProtocol::Tcp, tcp_strategy)),
        )?;
        install_proto_rules_v4_with_strategy(uids, ports, tcp_strategy, TransportProtocol::Tcp)?;
        install_proto_rules_v4_with_strategy(uids, ports, udp_strategy, TransportProtocol::Udp)?;
    }
    install_global_tcp_rules_v4(uids, global_ports)?;
    Ok(())
}

fn install_global_tcp_rules_v4(uids: &[u32], ports: &[u16]) -> Result<()> {
    if uids.is_empty() || ports.is_empty() {
        return Ok(());
    }
    for &uid in uids {
        for &port in ports {
            let args = vec![
                "-A".to_string(),
                PROXY_CHAIN.to_string(),
                "-p".to_string(),
                "tcp".to_string(),
                "--dport".to_string(),
                port.to_string(),
                "-m".to_string(),
                "owner".to_string(),
                "--uid-owner".to_string(),
                uid.to_string(),
                "-j".to_string(),
                "REJECT".to_string(),
                "--reject-with".to_string(),
                "tcp-reset".to_string(),
            ];
            let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Iptables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
            if rc != 0 {
                anyhow::bail!("iptables add proxyInfo myproxy upstream block failed uid={} port={} args='{}': {}", uid, port, args.join(" "), out);
            }
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
            PROXY_CHAIN.to_string(),
            "-m".to_string(),
            "owner".to_string(),
            "--uid-owner".to_string(),
            uid.to_string(),
            "-j".to_string(),
            "DROP".to_string(),
        ];
        let (rc, out) = xtables_lock::runv_timeout_retry(Backend::Ip6tables.cmd(), &args, Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("ip6tables add proxyInfo IPv6 deny rule failed uid={} args='{}': {}", uid, args.join(" "), out);
        }
    }
    logging::info("proxyInfo IPv6 strategy selected: full OUTPUT deny by uid (DROP)");
    Ok(())
}

fn install_rules(uids: &[u32], local_ports: &[u16], global_ports: &[u16]) -> Result<()> {
    let _guard = xtables_lock::lock();
    clear_rules_unlocked();
    if uids.is_empty() {
        return Ok(());
    }

    let result = (|| {
        if !local_ports.is_empty() || !global_ports.is_empty() {
            install_ipv4_rules(uids, local_ports, global_ports)?;
        }
        install_ipv6_rules(uids)?;
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
    if !enabled {
        clear_rules()?;
        crate::scan_detector::stop();
        return Ok(false);
    }

    let _uids = rebuild_out_program()?;
    if !services_running {
        clear_rules()?;
        crate::scan_detector::stop();
        return Ok(false);
    }

    let uids = read_out_uids()?;
    if uids.is_empty() {
        clear_rules()?;
        crate::scan_detector::stop();
        return Ok(false);
    }
    let (local_ports, global_ports) = collect_protected_port_sets()?;
    let local_list: Vec<u16> = local_ports.into_iter().collect();
    let global_list: Vec<u16> = global_ports.into_iter().collect();
    install_rules(&uids, &local_list, &global_list)?;
    logging::info(&format!(
        "proxyInfo active: {} uid(s), {} local IPv4 protected port(s), {} global upstream port(s), IPv6 deny=full",
        uids.len(),
        local_list.len(),
        global_list.len()
    ));
    crate::scan_detector::start();
    Ok(true)
}

pub fn collect_protected_port_sets() -> Result<(BTreeSet<u16>, BTreeSet<u16>)> {
    let mut local = BTreeSet::new();
    let mut global = BTreeSet::new();
    local.insert(API_PORT);
    collect_byedpi_ports(&mut local)?;
    collect_dpitunnel_ports(&mut local)?;
    collect_operaproxy_ports(&mut local)?;
    collect_singbox_ports(&mut local)?;
    collect_wireproxy_ports(&mut local)?;
    collect_tor_ports(&mut local)?;
    collect_myproxy_ports(&mut local, &mut global)?;
    Ok((local, global))
}

pub fn collect_protected_ports() -> Result<BTreeSet<u16>> {
    let (mut local, global) = collect_protected_port_sets()?;
    local.extend(global);
    Ok(local)
}

fn working_program_dir(program: &str) -> PathBuf {
    Path::new(settings::MODULE_DIR).join("working_folder").join(program)
}

fn collect_byedpi_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("byedpi").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = working_program_dir("byedpi");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let p = root.join(&name).join("port.json");
        if !p.is_file() {
            continue;
        }
        let port: ProfilePortJson = read_json_file(&p).unwrap_or_default();
        if port.port > 0 {
            out.insert(port.port);
        }
    }
    Ok(())
}

fn collect_dpitunnel_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("dpitunnel").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = working_program_dir("dpitunnel");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let p = root.join(&name).join("port.json");
        if !p.is_file() {
            continue;
        }
        let port: ProfilePortJson = read_json_file(&p).unwrap_or_default();
        if port.port > 0 {
            out.insert(port.port);
        }
    }
    Ok(())
}

fn collect_operaproxy_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("operaproxy").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: OperaActive = read_json_file(&active_path).unwrap_or_default();
    if !active.enabled {
        return Ok(());
    }
    let port_path = working_program_dir("operaproxy").join("port.json");
    if !port_path.is_file() {
        return Ok(());
    }
    let ports: OperaPortJson = read_json_file(&port_path).unwrap_or_default();
    if ports.byedpi_port > 0 {
        out.insert(ports.byedpi_port);
    }
    if ports.t2s_port > 0 {
        out.insert(ports.t2s_port);
    }
    out.insert(default_operaproxy_t2s_web_port());

    let sni_path = working_program_dir("operaproxy").join("config/sni.json");
    let count = count_operaproxy_sni(&sni_path);
    if ports.opera_start_port > 0 && count > 0 {
        for i in 0..count {
            if let Some(p) = ports.opera_start_port.checked_add(i as u16) {
                out.insert(p);
            }
        }
    }
    Ok(())
}

fn count_operaproxy_sni(p: &Path) -> usize {
    let raw = match fs::read_to_string(p) {
        Ok(s) => s,
        Err(_) => return 0,
    };
    let v: Value = match serde_json::from_str(&raw) {
        Ok(v) => v,
        Err(_) => return 0,
    };
    match v {
        Value::Array(arr) => arr.len(),
        _ => 0,
    }
}

fn collect_singbox_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("singbox").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings.hotspot_t2s_singbox_profile().map(|s| s.to_string());
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = working_program_dir("singbox").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let is_hotspot_profile = hotspot_profile.as_deref() == Some(name.as_str());
        let profile_dir = root.join(&name);
        if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
            continue;
        }
        let setting_path = profile_dir.join("setting.json");
        if setting_path.is_file() {
            let setting: SingboxProfileSetting = read_json_file(&setting_path).unwrap_or_default();
            if !is_hotspot_profile && setting.t2s_port > 0 {
                out.insert(setting.t2s_port);
            }
            if !is_hotspot_profile && setting.t2s_web_port > 0 {
                out.insert(setting.t2s_web_port);
            }
        }
        let server_root = profile_dir.join("server");
        if let Ok(rd) = fs::read_dir(&server_root) {
            for ent in rd.flatten() {
                let server_dir = ent.path();
                if !server_dir.is_dir() {
                    continue;
                }
                if server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                    continue;
                }
                let setting_path = server_dir.join("setting.json");
                if !setting_path.is_file() {
                    continue;
                }
                let setting: SingboxServerSetting = read_json_file(&setting_path).unwrap_or_default();
                if !is_hotspot_profile && setting.enabled && setting.port > 0 {
                    out.insert(setting.port);
                }
            }
        }
    }
    Ok(())
}


fn collect_wireproxy_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("wireproxy").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings.hotspot_t2s_wireproxy_profile().map(|s| s.to_string());
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = working_program_dir("wireproxy").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let is_hotspot_profile = hotspot_profile.as_deref() == Some(name.as_str());
        let profile_dir = root.join(&name);
        if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
            continue;
        }
        let setting_path = profile_dir.join("setting.json");
        if setting_path.is_file() {
            let setting: WireproxyProfileSetting = read_json_file(&setting_path).unwrap_or_default();
            if !is_hotspot_profile && setting.t2s_port > 0 {
                out.insert(setting.t2s_port);
            }
            if !is_hotspot_profile && setting.t2s_web_port > 0 {
                out.insert(setting.t2s_web_port);
            }
        }
        let server_root = profile_dir.join("server");
        if let Ok(rd) = fs::read_dir(&server_root) {
            for ent in rd.flatten() {
                let server_dir = ent.path();
                if !server_dir.is_dir() {
                    continue;
                }
                if server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                    continue;
                }
                let setting_path = server_dir.join("setting.json");
                if !setting_path.is_file() {
                    continue;
                }
                let setting: WireproxyServerSetting = read_json_file(&setting_path).unwrap_or_default();
                if !setting.enabled || is_hotspot_profile {
                    continue;
                }
                let config_path = server_dir.join("config.conf");
                let Ok(raw) = fs::read_to_string(&config_path) else { continue; };
                let Ok(addr) = crate::programs::wireproxy::parse_socks5_bind_address_str(&raw) else { continue; };
                if addr.port > 0 {
                    out.insert(addr.port);
                }
            }
        }
    }
    Ok(())
}


fn collect_myproxy_ports(local: &mut BTreeSet<u16>, global: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("myproxy").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = working_program_dir("myproxy").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let profile_dir = root.join(&name);
        let setting_path = profile_dir.join("setting.json");
        if let Ok(v) = read_json_file::<Value>(&setting_path) {
            for key in ["t2s_port", "t2s_web_port"] {
                if let Some(port) = v.get(key).and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                    if port != 0 { local.insert(port); }
                }
            }
        }
        let proxy_path = profile_dir.join("proxy.json");
        if let Ok(v) = read_json_file::<Value>(&proxy_path) {
            if let Some(port) = v.get("port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) {
                if port != 0 { global.insert(port); }
            }
        }
    }
    Ok(())
}

fn collect_tor_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let enabled_path = working_program_dir("tor").join("enabled.json");
    if !enabled_path.is_file() {
        return Ok(());
    }
    let enabled: TorEnabledJson = read_json_file(&enabled_path).unwrap_or_default();
    if !enabled.enabled {
        return Ok(());
    }
    let setting_path = working_program_dir("tor").join("setting.json");
    if setting_path.is_file() {
        let setting: TorSetting = read_json_file(&setting_path).unwrap_or_default();
        if setting.t2s_port > 0 {
            out.insert(setting.t2s_port);
        }
        if setting.t2s_web_port > 0 {
            out.insert(setting.t2s_web_port);
        }
    }
    let torrc_path = working_program_dir("tor").join("torrc");
    if let Ok(raw) = fs::read_to_string(&torrc_path) {
        if let Ok(port) = crate::programs::tor::parse_socks_port_from_str(&raw) {
            if port > 0 {
                out.insert(port);
            }
        }
    }
    Ok(())
}
