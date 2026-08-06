use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use d2s::{backend::BackendPool, config::Config, server::start};
use std::{path::PathBuf, sync::Arc};
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;

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
            print!("{}", include_str!("../d2s.example.toml"));
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
        .context("initialize logging")?;
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
