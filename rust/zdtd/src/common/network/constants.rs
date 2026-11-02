use std::time::Duration;

/// Timeout for short-lived `ip` invocations (identical across all engines).
pub const IP_TIMEOUT: Duration = Duration::from_secs(3);
