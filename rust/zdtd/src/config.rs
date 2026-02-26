use anyhow::{Context, Result};
use serde::Deserialize;
use std::{fs, path::{Path, PathBuf}};

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Config {
    /// Base directory for runtime data/configs. Optional: you can leave it empty and
    /// set everything via absolute paths in your future configs.
    #[serde(default)]
    pub base_dir: Option<PathBuf>,

    #[serde(default)]
    pub log: LogConfig,

    #[serde(default)]
    pub runtime: RuntimeConfig,

    #[serde(default)]
    pub notifications: NotificationsConfig,

    /// Extra fields are allowed (future-proofing)
    #[serde(default)]
    pub extra: serde_json::Value,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LogConfig {
    #[serde(default = "default_log_level")]
    pub level: String,

    /// If set, logs will also be appended here.
    #[serde(default)]
    pub file: Option<PathBuf>,
}

impl Default for LogConfig {
    fn default() -> Self {
        Self { level: default_log_level(), file: None }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct RuntimeConfig {
    /// "daemon" or "oneshot"
    #[serde(default = "default_mode")]
    pub mode: String,

    /// Base tick interval for the run loop (ms)
    #[serde(default = "default_tick_ms")]
    pub tick_ms: u64,
}



#[derive(Debug, Clone, Deserialize)]
pub struct NotificationsConfig {
    /// Enable/disable notification sending.
    #[serde(default)]
    pub enabled: bool,

    /// Prefix to prepend to the message body.
    #[serde(default = "default_notify_prefix")]
    pub prefix: String,

    /// Values for `cmd notification post`
    #[serde(default = "default_notify_icon")]
    pub icon1: String,
    #[serde(default = "default_notify_icon")]
    pub icon2: String,
    #[serde(default = "default_notify_style")]
    pub style: String,
    #[serde(default = "default_notify_title")]
    pub title: String,
    #[serde(default = "default_notify_conversation")]
    pub conversation: String,

    /// User id for `su -lp <uid> -c "cmd notification post ..."`
    #[serde(default = "default_notify_su_uid")]
    pub su_uid: u32,
}

impl Default for NotificationsConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            prefix: default_notify_prefix(),
            icon1: default_notify_icon(),
            icon2: default_notify_icon(),
            style: default_notify_style(),
            title: default_notify_title(),
            conversation: default_notify_conversation(),
            su_uid: default_notify_su_uid(),
        }
    }
}

fn default_notify_prefix() -> String { "ZDT-D:".to_string() }
fn default_notify_icon() -> String { "file:///data/local/tmp/icon.png".to_string() }
fn default_notify_style() -> String { "messaging".to_string() }
fn default_notify_title() -> String { "CLI".to_string() }
fn default_notify_conversation() -> String { "System".to_string() }
fn default_notify_su_uid() -> u32 { 2000 }

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self { mode: default_mode(), tick_ms: default_tick_ms() }
    }
}

fn default_log_level() -> String { "info".to_string() }
fn default_mode() -> String { "daemon".to_string() }
fn default_tick_ms() -> u64 { 1000 }

impl Config {
    pub fn load(path: &Path) -> Result<Self> {
        let s = fs::read_to_string(path).with_context(|| format!("read config {}", path.display()))?;
        let mut cfg: Config = serde_json::from_str(&s).with_context(|| format!("parse json {}", path.display()))?;

        // Normalize: if base_dir is present, make it absolute-ish in the future if needed.
        if let Some(b) = &cfg.base_dir {
            if b.as_os_str().is_empty() {
                cfg.base_dir = None;
            }
        }

        Ok(cfg)
    }

    /// Built-in defaults for ZDT-D2 module when binary is started without args.
    pub fn default_fixed() -> Self {
        Self {
            base_dir: Some(PathBuf::from("/data/adb/modules/ZDT-D/working_folder")),
            ..Default::default()
        }
    }

    pub fn base_join<P: AsRef<Path>>(&self, sub: P) -> Option<PathBuf> {
        self.base_dir.as_ref().map(|b| b.join(sub))
    }
}
