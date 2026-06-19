use crate::cli::{Args, BackendMode};
use crate::socks5::TargetAddr;
use anyhow::{anyhow, Context, Result};
use parking_lot::Mutex;
use rand::RngCore;
use serde::Serialize;
use serde_json::Value;
use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use once_cell::sync::Lazy;
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;

// Split into subfiles with include! to preserve the original single-module scope and private visibility.
include!("stats/runtime.rs");
include!("stats/target.rs");
include!("stats/backend.rs");
include!("stats/registry.rs");
include!("stats/events.rs");
include!("stats/health.rs");
