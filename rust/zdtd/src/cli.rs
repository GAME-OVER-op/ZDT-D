use anyhow::{bail, Result};

#[derive(Debug, Clone)]
pub struct Args {
    pub config_path: Option<String>,
    pub help: bool,
}

impl Args {
    /// Parse args for this project.
    /// - No args: run oneshot with built-in defaults.
    /// - Optional: `--config <file>`
    /// - Help: `help`, `-h`, `--help`
    pub fn parse(argv: Vec<String>) -> Result<Self> {
        let mut config_path: Option<String> = None;
        let mut help = false;

        let mut it = argv.into_iter().skip(1);
        while let Some(a) = it.next() {
            match a.as_str() {
                "help" | "-h" | "--help" => {
                    help = true;
                }
                "--config" => {
                    let v = it.next().ok_or_else(|| anyhow::anyhow!("missing value for --config"))?;
                    config_path = Some(v);
                }
                other => {
                    bail!("unknown arg: {other} (use --help)");
                }
            }
        }

        Ok(Self { config_path, help })
    }
}

pub fn print_help() {
    println!("zdtd (ZDT-D2) usage:");
    println!("  zdtd              # oneshot: wait boot, set selinux permissive, start nfqws profiles, iptables");
    println!("  zdtd --config <file>   # optional config for logging (json)");
    println!("  zdtd --help       # this help");
}
