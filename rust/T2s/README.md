# t2s (transparent -> SOCKS5)

Rust implementation of a transparent **TCP -> SOCKS5** proxy with an optional web panel, backend health checks, runtime statistics, and traffic routing rules.

## Overview

`t2s` accepts incoming TCP connections and forwards them either:

- through one or more upstream **SOCKS5** backends, or
- **directly**, when policy allows it and no healthy SOCKS backend is available.

The program can work in two modes:

- **Transparent mode**: the original destination is recovered with Linux `SO_ORIGINAL_DST`.
- **Explicit target mode**: all incoming traffic is forwarded to a fixed `--target-host/--target-port`.

This build is **TCP-only**. It does not proxy UDP and does not implement DNS handling inside `t2s`.

## Implemented features

- Transparent TCP proxying via Linux `SO_ORIGINAL_DST`
- Forwarding through SOCKS5 with optional username/password authentication
- Multiple SOCKS5 backends
- Backend health checks with runtime state tracking
- Direct fallback when policy allows it and no GREEN backend is available
- Optional web UI and JSON API
- Connection registry with runtime kill support
- Download rate limiting
- Traffic policy rules through `TRAFFIC_RULES`
- Best-effort domain detection from:
  - HTTP `Host`
  - HTTP `CONNECT`
  - TLS SNI

## Important behavior notes

- The web panel is started **only** when `--web-socket` is enabled.
- Backends added through the web/API can carry their own username/password. These credentials override the global CLI `--socks-user/--socks-pass` only for that backend. Startup backends created from CLI still use the global fallback auth only.
- `--enable-http2` is currently a **compatibility flag only** in this Rust port and does not enable a separate HTTP/2 implementation.
- SOCKS backend lists are built from the combination of all values in `--socks-host` and `--socks-port`.
  - Example: `--socks-host 10.0.0.1,10.0.0.2 --socks-port 1080,1081`
  - creates up to 4 backend endpoints.
- Direct connections are not treated as a permanent bypass. When GREEN backends recover, the runtime enforcement loop can terminate direct connections.
- Initial Host / CONNECT / TLS SNI sniffing is adaptive: under normal load it uses a staged budget up to 200 ms (80 -> 120 -> 160 -> 200 ms total budget), under heavier load it falls back to a single fast 80 ms attempt, and under overload it may skip sniffing entirely when no host-based rules depend on that metadata.
- The `reset` action in `TRAFFIC_RULES` is accepted by the parser, but in the current implementation it behaves as an early connection termination rather than a low-level crafted TCP RST.

## Build

```bash
cargo build --release
```

## Run examples

### 1) Transparent mode

```bash
./target/release/t2s \
  --listen-addr 127.0.0.1 \
  --listen-port 11290 \
  --external-port 11291 \
  --socks-host 127.0.0.1 \
  --socks-port 1080 \
  --web-socket \
  --web-addr 0.0.0.0 \
  --web-port 8000
```

Example redirect rule:

```bash
iptables -t nat -A OUTPUT -p tcp -j REDIRECT --to-ports 11290
```

### 2) Explicit target mode

```bash
./target/release/t2s \
  --listen-addr 127.0.0.1 \
  --listen-port 11290 \
  --socks-host 127.0.0.1 \
  --socks-port 1080 \
  --target-host example.com \
  --target-port 443
```

## Command-line arguments

### Listeners

- `--listen-addr <ADDR>`: internal listener address. Default: `127.0.0.1`
- `--listen-port <PORT>`: internal listener port. Default: `11290`
- `--external-port <PORT>`: optional external listener on `0.0.0.0:<PORT>`. Default: `0` (disabled)

### SOCKS5 upstream

- `--socks-host <HOST[,HOST...]>`: required, comma-separated SOCKS5 host list
- `--socks-port <PORT[,PORT...]>`: required, comma-separated SOCKS5 port list
- `--socks-user <USER>`: optional SOCKS5 username used as the global fallback for startup backends
- `--socks-pass <PASS>`: optional SOCKS5 password used as the global fallback for startup backends

Startup backends built from `--socks-host` and `--socks-port` share the same CLI auth fallback. Per-backend credentials are available only for backends added later through the web/API.

### Target selection

- `--target-host <HOST>`: fixed upstream destination host
- `--target-port <PORT>`: fixed upstream destination port

`--target-host` and `--target-port` must be used together.
If they are omitted, `t2s` uses transparent destination recovery.

### Runtime and limits

- `--buffer-size <BYTES>`: socket buffer size. Default: `131072`
- `--idle-timeout <SECONDS>`: idle timeout, `0` disables timeout. Default: `600`
- `--connect-timeout <SECONDS>`: backend connect timeout. Default: `8`
- `--enable-http2`: compatibility flag, currently no-op
- `--max-conns <COUNT>`: maximum concurrent connections. Default: `100`
- `--download-limit-mbit <MBIT>`: download throttling in Mbit/s, `0` disables limit. Default: `0`

### Web UI

- `--web-socket`: enable embedded web UI and API server
- `--web-addr <ADDR>`: web bind address. Default: `127.0.0.1`
- `--web-port <PORT>`: web bind port. Default: `8000`

## Web endpoints

When `--web-socket` is enabled, the following endpoints are available:

- `GET /` - embedded single-page web UI
- `GET /ws` - websocket state stream for the UI
- `GET /api/version` - build/version information
- `GET /api/state` - current runtime snapshot
- `POST /api/download_limit` - update download throttling
- `POST /api/backends/add` - add SOCKS backend at runtime (optional per-backend username/password override)
- `POST /api/backends/remove` - remove SOCKS backend at runtime
- `GET /api/kill?cid=<id>` - kill a connection by connection id
- `POST /api/kill` - kill a connection by JSON payload

### Example API payloads

Set download limit:

```json
{"mbit": 25}
```

Add backend without auth:

```json
{"host": "127.0.0.1", "port": 1080}
```

Add backend with per-backend auth override:

```json
{"host": "127.0.0.1", "port": 1080, "username": "user", "password": "pass"}
```

Rules for backend auth payloads:

- `username` and `password` must be provided together
- empty strings are treated as missing values
- duplicates are detected by resolved `host:port` only; to change credentials for an existing backend, remove it and add it again
- when `username/password` are omitted for an API-added backend, that backend uses no per-backend override and falls back to the global CLI `--socks-user/--socks-pass` if present

Remove backend:

```json
{"host": "127.0.0.1", "port": 1080}
```

Kill connection:

```json
{"cid": "12345"}
```

## API examples with `curl`

Read current state:

```bash
curl http://127.0.0.1:8000/api/state
```

Add backend without auth:

```bash
curl -X POST http://127.0.0.1:8000/api/backends/add \
  -H 'content-type: application/json' \
  -d '{"host":"127.0.0.1","port":1080}'
```

Add backend with per-backend auth:

```bash
curl -X POST http://127.0.0.1:8000/api/backends/add \
  -H 'content-type: application/json' \
  -d '{"host":"127.0.0.1","port":1080,"username":"user","password":"pass"}'
```

Remove backend:

```bash
curl -X POST http://127.0.0.1:8000/api/backends/remove \
  -H 'content-type: application/json' \
  -d '{"host":"127.0.0.1","port":1080}'
```

Update download limit:

```bash
curl -X POST http://127.0.0.1:8000/api/download_limit \
  -H 'content-type: application/json' \
  -d '{"mbit":25}'
```

Kill connection:

```bash
curl -X POST http://127.0.0.1:8000/api/kill \
  -H 'content-type: application/json' \
  -d '{"cid":"12345"}'
```

## Web UI notes

When the web panel is enabled, the **SOCKS backends** section exposes these fields for runtime backend creation:

- `host / ip`
- `port`
- `username (optional)`
- `password (optional)`

UI add/remove operations use the same JSON API described above.

## Traffic rules (`TRAFFIC_RULES`)

`TRAFFIC_RULES` is read from the environment as JSON.
The parser accepts either:

- an object with a top-level `rules` array, or
- a raw JSON array of rules.

Supported actions:

- `socks`
- `direct`
- `drop`
- `reset`
- `wait`

Supported match fields inside `when`:

- `proto`
- `port`
- `port_range`
- `host_regex`
- `socks_available`
- `is_udp`

Example:

```json
{
  "rules": [
    { "when": { "proto": "https", "port": 443 }, "action": "socks" },
    { "when": { "host_regex": ".*\\.example\\.com$" }, "action": "direct" },
    { "when": { "socks_available": false }, "action": "direct" },
    { "when": { "port_range": "1-1024" }, "action": "wait" },
    { "when": { "is_udp": true }, "action": "drop" }
  ]
}
```

### Protocol classification used by rules

Current built-in protocol classification is port-based:

- `80`, `8080`, `8000` -> `http`
- `443`, `8443` -> `https`
- `53` -> `dns`
- everything else -> `tcp`

## Platform notes

- Transparent mode depends on Linux `SO_ORIGINAL_DST`
- The system statistics collector reads from `/proc`, so Linux/Android environments are the intended target

## Project status

This repository already implements the core TCP proxying path, backend monitoring, runtime enforcement, and web management panel.
The README should be treated as documentation for the current Rust implementation, not for the original Python project.
