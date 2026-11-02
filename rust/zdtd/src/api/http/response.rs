use anyhow::Result;
use serde_json::json;
use std::{io::Write, net::TcpStream};

pub(in crate::api) fn write_ok(stream: TcpStream) -> Result<()> {
    write_json(stream, 200, json!({"ok": true}))
}

pub(in crate::api) fn write_err(stream: TcpStream, e: anyhow::Error) -> Result<()> {
    write_json(stream, 200, json!({"ok": false, "error": format!("{e:#}")}))
}

pub(in crate::api) fn write_json(mut stream: TcpStream, status: u16, body: serde_json::Value) -> Result<()> {
    let body_s = body.to_string();
    let hdr = format!(
        "HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body_s.len()
    );
    stream.write_all(hdr.as_bytes())?;
    stream.write_all(body_s.as_bytes())?;
    Ok(())
}

pub(in crate::api) fn write_empty_404(mut stream: TcpStream) -> Result<()> {
    stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
    Ok(())
}


