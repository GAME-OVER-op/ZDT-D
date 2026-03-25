# t2s (transparent -> SOCKS5)

Best-effort Rust port of `zdt-d_tanspanent_proxy-sosck5.py`.

## What works
- TCP transparent proxy: incoming connections get their original destination (Linux `SO_ORIGINAL_DST`)
- Upstream via SOCKS5 (optional username/password) with direct fallback
- Multiple SOCKS5 backends (round-robin among healthy)
- Simple web endpoints: `/health`, `/ready`, `/metrics`, `/debug/*`, `/events` (SSE), `/api/kill?cid=<id>`

## Notes
This port focuses on the core transparent TCP proxying and management endpoints.
Some advanced Python features (HTTP/2 handler, DoH/DoQ, advanced cache, deep system stats) are not implemented.

## Build
```bash
cargo build --release
```

## Run
```bash
./target/release/t2s \
  --mode tcp \
  --listen-addr 127.0.0.1 --listen-port 11290 \
  --external-port 11291 \
  --socks-host 127.0.0.1 --socks-port 1080 \
  --web-socket --web-addr 0.0.0.0 --web-port 8000
```

### Traffic rules (`TRAFFIC_RULES`)
JSON example:

```json
{
  "rules": [
    { "when": { "proto": "https", "port": 443 }, "action": "socks" },
    { "when": { "host_regex": ".*\\.example\\.com$" }, "action": "direct" },
    { "when": { "socks_available": false }, "action": "direct" }
  ]
}
```
