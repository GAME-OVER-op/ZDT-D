use anyhow::{anyhow, Context, Result};
use base64::Engine;
use futures_util::StreamExt;
use rand::{distributions::Alphanumeric, Rng};
use reqwest::{Client, StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    env,
    io::{self, Write},
    net::{IpAddr, Ipv4Addr, SocketAddr},
    process::ExitCode,
    str::FromStr,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{lookup_host, TcpStream, UdpSocket},
    time::{sleep, timeout, Instant},
};

pub async fn entry() -> ExitCode {
    match run(env::args().skip(1).collect()).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            let _ = writeln!(io::stderr(), "{err:#}");
            ExitCode::from(2)
        }
    }
}

include!("config.rs");
include!("model.rs");
include!("cli.rs");
include!("runner.rs");
include!("tests.rs");
include!("net.rs");
