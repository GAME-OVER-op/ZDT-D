use anyhow::{anyhow, Context, Result};
use clap::{Parser, ValueEnum};
use std::net::{SocketAddr, ToSocketAddrs};

#[derive(Clone, Copy, Debug, PartialEq, Eq, ValueEnum)]
pub enum BackendMode {
    /// Balance traffic across all GREEN SOCKS5 backends (current/default behavior).
    Balance,
    /// Use backend priority groups; fall back to the next group only when the previous group has no GREEN backend.
    Priority,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PriorityZeroMode {
    /// No special direct marker in the priority list.
    None,
    /// `--socks-port 0`: run without SOCKS backends and use only direct access.
    DirectOnly,
    /// `--socks-port 0,1145,...`: try direct first, then fall back to SOCKS priority.
    DirectFirst,
    /// `--socks-port 1145,...,0`: use SOCKS priority only; block direct fallback when servers are dead.
    BlockDirectFallback,
}

#[derive(Clone, Debug, Parser)]
#[command(
    name = "t2s",
    about = "Transparent -> SOCKS5 proxy (Rust port)",
    long_about = "t2s routes TCP traffic to one or more upstream SOCKS5 backends. It can work in explicit target mode (\
--target-host/--target-port) or in transparent mode (SO_ORIGINAL_DST via iptables REDIRECT/TPROXY).",
    after_help = r#"QUICK START (Android, transparent mode)
  1) Run t2s (transparent mode usually requires root):
       t2s --socks-host 1.2.3.4 --socks-port 1080 --web-socket

  2) Redirect local traffic to the internal listener (example; adapt to your setup):
       iptables -t nat -A OUTPUT -p tcp -j REDIRECT --to-ports 11290

PORTS
  Internal listener:
    --listen-addr/--listen-port   (default 127.0.0.1:11290)
  External listener (optional):
    --external-port <PORT>        (binds 0.0.0.0:<PORT>)

UI
  Web panel:
    http://<web_addr>:<web_port>/ (default 127.0.0.1:8000)

NOTES
  * Domain in Connections is best-effort from HTTP Host / CONNECT / TLS SNI.
  * Power save: when there are no active connections and no UI clients, background checks go to sleep
    and poll backends every 1-3 minutes (wakes instantly on new connection).
  * This build is TCP-only (no UDP and no DNS handling inside t2s).
"#,
    arg_required_else_help = true
)]
pub struct Args {
    #[arg(long, default_value="127.0.0.1")]
    pub listen_addr: String,
    #[arg(long, default_value_t=11290)]
    pub listen_port: u16,

    /// Optional external TCP listener on 0.0.0.0:<external_port> (0 disables).
    /// Useful when you want to accept traffic from other devices or when local listen_addr must stay 127.0.0.1.
    #[arg(long, default_value_t=0)]
    pub external_port: u16,

    #[arg(long, default_value="", help="Upstream SOCKS5 host(s), comma-separated. Not required for priority direct-only mode (--socks-port 0).")]
    pub socks_host: String,
    #[arg(long, required=true, help="Upstream SOCKS5 port(s), comma-separated")]
    pub socks_port: String,
    #[arg(long)]
    pub socks_user: Option<String>,
    #[arg(long)]
    pub socks_pass: Option<String>,

    #[arg(long, default_value="", help="Optional SOCKS5 wrapper host. Empty disables Wrapped SOCKS5.")]
    pub wrapped_socks_host: String,
    #[arg(long, default_value_t=0, help="Optional SOCKS5 wrapper port. 0 disables Wrapped SOCKS5.")]
    pub wrapped_socks_port: u16,
    #[arg(long)]
    pub wrapped_socks_user: Option<String>,
    #[arg(long)]
    pub wrapped_socks_pass: Option<String>,

    #[arg(long, value_enum, default_value = "balance", help="SOCKS5 backend selection mode: balance or priority")]
    pub backend_mode: BackendMode,
    #[arg(long, help="Priority groups for --backend-mode priority. Example: 1145,1146;1147. If omitted, --socks-port order is used as 1145;1146;1147")]
    pub backend_priority: Option<String>,
    #[arg(long, default_value_t=false, help="Enable speed-aware soft fallback in priority mode. Keeps normal priority unchanged unless a higher-priority GREEN backend is throughput-limited, then probes lower GREEN backends with real new connections and temporarily shifts new connections without killing existing ones.")]
    pub priority_speed_aware: bool,

    #[arg(long)]
    pub target_host: Option<String>,
    #[arg(long)]
    pub target_port: Option<u16>,

    #[arg(long, default_value_t=131072)]
    pub buffer_size: u32,

    #[arg(long, default_value_t=600, help="Idle timeout seconds (0 disables)")]
    pub idle_timeout: u32,
    #[arg(long, default_value_t=8)]
    pub connect_timeout: u32,
    /// Compatibility flag (kept for parity with the Python version). Currently a no-op in the Rust port.
    #[arg(long, default_value_t=false)]
    pub enable_http2: bool,

    #[arg(long, default_value_t=100)]
    pub max_conns: u32,

    #[arg(long, default_value_t=false)]
    pub web_socket: bool,
    #[arg(long, default_value="127.0.0.1")]
    pub web_addr: String,
    #[arg(long, default_value_t=8000)]
    pub web_port: u16,

    #[arg(long, default_value_t=0.0, help="Download throttling in Mbit/s (0 disables)")]
    pub download_limit_mbit: f64,

    #[arg(long, default_value="/data/adb/modules/ZDT-D/api", help="ZDT-D API root directory. t2s metadata is written under <api-dir>/t2s.")]
    pub api_dir: String,
    #[arg(long, default_value="", help="Stable t2s instance id for metadata/API responses. Auto-generated when omitted.")]
    pub instance_id: String,
    #[arg(long, default_value="", help="Owning ZDT-D program id, e.g. sing-box, wireproxy, myproxy.")]
    pub program: String,
    #[arg(long, default_value="", help="Owning ZDT-D profile name, if any.")]
    pub profile: String,
    #[arg(long, default_value="", help="Owning ZDT-D scope, e.g. profile/sing-box/main. Auto-derived when omitted.")]
    pub scope: String,

}

impl Args {
    pub fn parse_and_normalize() -> Result<Self> {
        let a = Args::parse();
        if (a.target_host.is_some() && a.target_port.is_none()) || (a.target_host.is_none() && a.target_port.is_some()) {
            return Err(anyhow!("--target-host and --target-port must be used together"));
        }
        let wrapped_host_set = !a.wrapped_socks_host.trim().is_empty();
        let wrapped_port_set = a.wrapped_socks_port != 0;
        if wrapped_host_set ^ wrapped_port_set {
            return Err(anyhow!("--wrapped-socks-host and --wrapped-socks-port must be used together, or both left empty"));
        }
        if a.wrapped_socks_user.as_ref().map(|s| !s.trim().is_empty()).unwrap_or(false)
            ^ a.wrapped_socks_pass.as_ref().map(|s| !s.is_empty()).unwrap_or(false)
        {
            return Err(anyhow!("--wrapped-socks-user and --wrapped-socks-pass must both be set or both empty"));
        }
        a.validate_priority_zero_mode()?;
        if !a.socks_ports().is_empty() && a.socks_hosts().is_empty() {
            return Err(anyhow!("--socks-host is required when --socks-port contains SOCKS5 backend ports"));
        }
        Ok(a)
    }

    fn socks_port_tokens(&self) -> Vec<&str> {
        self.socks_port
            .split(',')
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .collect()
    }

    fn validate_priority_zero_mode(&self) -> Result<()> {
        let tokens = self.socks_port_tokens();
        let zero_positions: Vec<usize> = tokens
            .iter()
            .enumerate()
            .filter_map(|(idx, p)| if *p == "0" { Some(idx) } else { None })
            .collect();

        if zero_positions.is_empty() {
            return Ok(());
        }

        if self.backend_mode != BackendMode::Priority {
            return Err(anyhow!("port 0 is allowed only with --backend-mode priority"));
        }
        if zero_positions.len() > 1 {
            return Err(anyhow!("port 0 may appear only once in --socks-port"));
        }

        let zero_idx = zero_positions[0];
        let last_idx = tokens.len().saturating_sub(1);
        if zero_idx != 0 && zero_idx != last_idx {
            return Err(anyhow!("port 0 is allowed only at the beginning or at the end of --socks-port in priority mode"));
        }

        if self.backend_priority
            .as_deref()
            .map(|s| s.split([',', ';']).any(|p| p.trim() == "0"))
            .unwrap_or(false)
        {
            return Err(anyhow!("port 0 is supported only in --socks-port, not in --backend-priority"));
        }

        Ok(())
    }

    pub fn priority_zero_mode(&self) -> PriorityZeroMode {
        if self.backend_mode != BackendMode::Priority {
            return PriorityZeroMode::None;
        }

        let tokens = self.socks_port_tokens();
        if tokens.len() == 1 && tokens[0] == "0" {
            return PriorityZeroMode::DirectOnly;
        }
        if tokens.first().copied() == Some("0") {
            return PriorityZeroMode::DirectFirst;
        }
        if tokens.last().copied() == Some("0") {
            return PriorityZeroMode::BlockDirectFallback;
        }
        PriorityZeroMode::None
    }

    pub fn socks_hosts(&self) -> Vec<String> {
        self.socks_host.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
    }

    pub fn socks_ports(&self) -> Vec<u16> {
        self.socks_port.split(',')
            .filter_map(|p| p.trim().parse::<u16>().ok())
            .filter(|p| *p != 0)
            .collect()
    }

    pub fn wrapped_socks_addr(&self) -> Result<Option<SocketAddr>> {
        let host = self.wrapped_socks_host.trim();
        if host.is_empty() || self.wrapped_socks_port == 0 {
            return Ok(None);
        }
        let mut first: Option<SocketAddr> = None;
        for sa in (host, self.wrapped_socks_port)
            .to_socket_addrs()
            .with_context(|| format!("resolve wrapped SOCKS5 {}:{}", host, self.wrapped_socks_port))?
        {
            if first.is_none() { first = Some(sa); }
            if sa.is_ipv4() { return Ok(Some(sa)); }
        }
        first.map(Some).ok_or_else(|| anyhow!("no addr for wrapped SOCKS5 {}:{}", host, self.wrapped_socks_port))
    }

    pub fn wrapped_socks_auth(&self) -> Option<(String, String)> {
        match (self.wrapped_socks_user.clone(), self.wrapped_socks_pass.clone()) {
            (Some(u), Some(p)) if !u.trim().is_empty() && !p.is_empty() => Some((u.trim().to_string(), p)),
            _ => None,
        }
    }
}
