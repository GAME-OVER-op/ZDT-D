use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use d2s::{backend::BackendPool, config::Config, server::start};
use std::{path::PathBuf, sync::Arc};
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;

const EXAMPLE_CONFIG: &str = r#"# D2S reads its listener address from the active `proxy` entry in
# dnscrypt-proxy.toml. The listener is not configured in this file.

# Local SOCKS5 servers. NO AUTH only.
# Leave the list empty to use DIRECT fallback only.
backends = []

direct_fallback = true

# Connection timeouts.
connect_timeout_ms = 500
upstream_handshake_timeout_ms = 1000
backend_attempt_timeout_ms = 1200
direct_connect_timeout_ms = 2000

client_handshake_timeout_ms = 3000
probe_timeout_ms = 1200

# Health checks are traffic-aware. GREEN backends are checked less often;
# degraded backends recover faster while D2S is active.
healthy_probe_interval_secs = 30
recovery_probe_interval_secs = 5
failure_threshold = 3
runtime_cooldown_ms = 2000

# With no client traffic, synthetic health checks stop after this delay.
# Set to 0 to disable idle sleep.
idle_after_secs = 60

probe_targets = [
  "1.1.1.1:443",
  "8.8.8.8:443",
]

max_connections = 1024
tcp_nodelay = true
log_level = "info"
shutdown_grace_period_ms = 5000
"#;

#[derive(Debug, Parser)]
#[command(name = "d2s", version, about = "DNS-to-SOCKS balancer with failover and DIRECT fallback")]
struct Cli {
    #[arg(short, long, global = true, default_value = "d2s.toml")]
    config: PathBuf,

    #[arg(long, global = true, default_value = "dnscrypt-proxy.toml")]
    dnscrypt_config: PathBuf,

    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Run the D2S SOCKS5 relay.
    Run,
    /// Validate the D2S configuration without starting the listener.
    Check,
    /// Probe all configured SOCKS5 backends once and print JSON status.
    Probe,
    /// Print the example configuration to stdout.
    ExampleConfig,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    match cli.command.unwrap_or(Command::Run) {
        Command::ExampleConfig => {
            print!("{}", EXAMPLE_CONFIG);
            Ok(())
        }
        Command::Check => {
            let config = Config::load(&cli.config, &cli.dnscrypt_config)?;
            init_logging(&config.log_level)?;
            info!(path = %cli.config.display(), backends = config.backends.len(), "configuration is valid");
            println!("OK: {}", cli.config.display());
            Ok(())
        }
        Command::Probe => {
            let config = Config::load(&cli.config, &cli.dnscrypt_config)?;
            init_logging(&config.log_level)?;
            let config = Arc::new(config);
            let pool = BackendPool::new(config)?;
            pool.initial_probe().await;
            println!("{}", serde_json::to_string_pretty(&pool.snapshots().await)?);
            Ok(())
        }
        Command::Run => {
            let config = Config::load(&cli.config, &cli.dnscrypt_config)?;
            init_logging(&config.log_level)?;
            let server = start(config).await?;
            info!(listen = %server.listen_addr, "D2S started");
            wait_for_shutdown_signal().await?;
            server.shutdown().await
        }
    }
}

fn init_logging(level: &str) -> Result<()> {
    let filter = EnvFilter::try_from_default_env()
        .or_else(|_| EnvFilter::try_new(level))
        .with_context(|| format!("invalid log filter {level}"))?;
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .compact()
        .try_init()
        .map_err(|error| anyhow::anyhow!("initialize logging: {error}"))?;
    Ok(())
}

async fn wait_for_shutdown_signal() -> Result<()> {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        let mut terminate = signal(SignalKind::terminate()).context("install SIGTERM handler")?;
        let mut interrupt = signal(SignalKind::interrupt()).context("install SIGINT handler")?;
        tokio::select! {
            _ = terminate.recv() => warn!("received SIGTERM"),
            _ = interrupt.recv() => warn!("received SIGINT"),
        }
        Ok(())
    }

    #[cfg(not(unix))]
    {
        tokio::signal::ctrl_c().await.context("wait for Ctrl-C")
    }
}
