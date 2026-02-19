use anyhow::{anyhow, Result};
use clap::Parser;

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

    #[arg(long, required=true, help="Upstream SOCKS5 host(s), comma-separated")]
    pub socks_host: String,
    #[arg(long, required=true, help="Upstream SOCKS5 port(s), comma-separated")]
    pub socks_port: String,
    #[arg(long)]
    pub socks_user: Option<String>,
    #[arg(long)]
    pub socks_pass: Option<String>,

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

}

impl Args {
    pub fn parse_and_normalize() -> Result<Self> {
        let a = Args::parse();
        if (a.target_host.is_some() && a.target_port.is_none()) || (a.target_host.is_none() && a.target_port.is_some()) {
            return Err(anyhow!("--target-host and --target-port must be used together"));
        }
        Ok(a)
    }

    pub fn socks_hosts(&self) -> Vec<String> {
        self.socks_host.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
    }

    pub fn socks_ports(&self) -> Vec<u16> {
        self.socks_port.split(',')
            .filter_map(|p| p.trim().parse::<u16>().ok())
            .collect()
    }
}
