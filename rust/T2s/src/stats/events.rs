pub struct Event {
    pub ts: u64,
    pub kind: String,
    pub cid: Option<u64>,
    pub peer: Option<String>,
    pub target: Option<String>,
    pub mode: Option<String>,
}

impl Event {
    pub fn conn_open(cid: u64, peer: SocketAddr) -> Self {
        Self{ ts: now_ts(), kind: "conn_open".into(), cid: Some(cid), peer: Some(peer.to_string()), target: None, mode: None }
    }
    pub fn conn_close(cid: u64) -> Self {
        Self{ ts: now_ts(), kind: "conn_close".into(), cid: Some(cid), peer: None, target: None, mode: None }
    }
    pub fn conn_target(cid: u64, host: String, port: u16, mode: String) -> Self {
        Self{ ts: now_ts(), kind: "conn_target".into(), cid: Some(cid), peer: None, target: Some(format!("{}:{}", host, port)), mode: Some(mode) }
    }
}


