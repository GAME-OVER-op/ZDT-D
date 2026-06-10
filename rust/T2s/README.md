# t2s — transparent TCP to SOCKS5 helper

`t2s` is a Rust helper bundled with ZDT-D. It receives TCP connections from a
local listener and forwards them through one or more upstream SOCKS5 backends, or
optionally directly when policy allows it.

In ZDT-D, `t2s` is the common bridge between UID-based `iptables` transparent
redirection and proxy engines that expose SOCKS5-compatible local ports.

## What t2s does

`t2s` can:

- listen on an internal TCP port;
- optionally expose an external listener;
- recover the original destination with Linux `SO_ORIGINAL_DST` in transparent mode;
- forward TCP streams to SOCKS5 upstreams;
- use multiple SOCKS5 backends;
- select backends by balance or priority policy;
- monitor backend health;
- fall back to direct connections when policy allows it;
- expose a local web UI/API for status and runtime management;
- keep a connection registry and kill active connections;
- apply download throttling;
- evaluate traffic rules from `TRAFFIC_RULES`;
- sniff HTTP Host, HTTP CONNECT target and TLS SNI on a best-effort basis.

This build is TCP-only. It does not proxy UDP and it does not implement DNS
resolution/routing internally.

## Where ZDT-D uses it

`t2s` is used by several local proxy pipeline integrations, including:

- sing-box proxy scenarios;
- wireproxy profiles;
- Tor SOCKS pipelines;
- opera-proxy pipelines;
- myproxy profiles;
- selected myprogram scenarios that expose a SOCKS5 endpoint.

Typical ZDT-D routing chain:

```text
selected app UID -> iptables REDIRECT -> t2s listener -> SOCKS5 backend -> upstream network
```

## Modes

### Transparent mode

When `--target-host` and `--target-port` are omitted, `t2s` expects traffic that
was redirected by firewall rules. It calls `SO_ORIGINAL_DST` to recover the
original destination address and port.

Example:

```bash
t2s \
  --listen-addr 127.0.0.1 \
  --listen-port 11290 \
  --socks-host 127.0.0.1 \
  --socks-port 1080
```

Example redirect rule:

```bash
iptables -t nat -A OUTPUT -p tcp -j REDIRECT --to-ports 11290
```

### Explicit target mode

When `--target-host` and `--target-port` are provided together, every incoming
connection is forwarded to the fixed target.

```bash
t2s \
  --listen-addr 127.0.0.1 \
  --listen-port 11290 \
  --socks-host 127.0.0.1 \
  --socks-port 1080 \
  --target-host example.com \
  --target-port 443
```

Both target options must be used together. Supplying only one is an error.

## Command-line arguments

### Listeners

- `--listen-addr <ADDR>` — internal listener address. Default: `127.0.0.1`.
- `--listen-port <PORT>` — internal listener port. Default: `11290`.
- `--external-port <PORT>` — optional external listener on `0.0.0.0:<PORT>`.
  `0` disables it. Default: `0`.

### SOCKS5 upstreams

- `--socks-host <HOST[,HOST...]>` — required comma-separated SOCKS5 host list.
- `--socks-port <PORT[,PORT...]>` — required comma-separated SOCKS5 port list.
- `--socks-user <USER>` — optional global SOCKS5 username.
- `--socks-pass <PASS>` — optional global SOCKS5 password.

Startup backends are built from all configured hosts and ports. For example:

```bash
--socks-host 10.0.0.1,10.0.0.2 --socks-port 1080,1081
```

creates combinations for those hosts and ports. Runtime API-added backends can
provide their own username/password override.

### Backend mode

- `--backend-mode balance|priority` — default: `balance`.
- `--backend-priority <GROUPS>` — priority groups for priority mode.
- `--priority-speed-aware` — optional soft fallback for throughput-limited
  priority backends.

#### Balance mode

All GREEN backends participate in balancing. If a backend becomes unhealthy,
connections pinned to it are cancelled so clients can reconnect through another
healthy backend or direct fallback.

#### Priority mode

Backends are grouped by priority. Commas separate backends in the same group;
semicolons separate fallback levels:

```text
1145,1146;1147
```

This means:

1. use 1145 and 1146 as the first priority group;
2. use 1147 only when the first group has no GREEN backend;
3. fall back to direct only when no configured backend is available and policy
   allows direct fallback.

If `--backend-priority` is omitted, the order of `--socks-port` becomes the
priority order. Example:

```text
--socks-port 1145,1146,1147
```

behaves like:

```text
--backend-priority "1145;1146;1147"
```

In priority mode, the ZDT-D `protector_mode` forced-GREEN behavior is ignored so
failed priority backends do not remain artificially selectable.

#### Priority speed-aware mode

`--priority-speed-aware` keeps strict priority as the default but allows a soft
fallback when real traffic shows that a higher-priority GREEN backend is
throughput-limited. The helper probes a lower GREEN backend with a new real
connection and temporarily sends new connections there if it is clearly faster.
Existing connections are not killed for this speed shift. Later probes can return
new traffic to the higher-priority backend after recovery.

### Target selection

- `--target-host <HOST>` — fixed upstream target host.
- `--target-port <PORT>` — fixed upstream target port.

Both must be used together. If both are omitted, transparent mode is used.

### Runtime and limits

- `--buffer-size <BYTES>` — socket buffer size. Default: `131072`.
- `--idle-timeout <SECONDS>` — idle timeout. `0` disables it. Default: `600`.
- `--connect-timeout <SECONDS>` — backend connect timeout. Default: `8`.
- `--enable-http2` — compatibility flag retained for parity; currently no-op.
- `--max-conns <COUNT>` — maximum concurrent connections. Default: `100`.
- `--download-limit-mbit <MBIT>` — download throttling in Mbit/s. `0` disables
  throttling. Default: `0`.

### Web UI/API

- `--web-socket` — enable web UI/API server.
- `--web-addr <ADDR>` — web bind address. Default: `127.0.0.1`.
- `--web-port <PORT>` — web bind port. Default: `8000`.

The web server starts only when `--web-socket` is enabled.

## Web endpoints

When enabled:

- `GET /` — embedded single-page web UI;
- `GET /ws` — websocket state stream;
- `GET /api/version` — version/build information;
- `GET /api/state` — current runtime snapshot;
- `POST /api/download_limit` — update download throttling;
- `POST /api/backends/add` — add SOCKS backend at runtime;
- `POST /api/backends/remove` — remove SOCKS backend at runtime;
- `GET /api/kill?cid=<id>` — kill a connection by ID;
- `POST /api/kill` — kill a connection by JSON payload.

Example payloads:

```json
{"mbit": 25}
```

```json
{"host": "127.0.0.1", "port": 1080}
```

```json
{"host": "127.0.0.1", "port": 1080, "username": "user", "password": "pass"}
```

```json
{"cid": "12345"}
```

Rules for API-added backend credentials:

- `username` and `password` must be provided together;
- empty strings are treated as missing;
- duplicate detection is by resolved `host:port`;
- to change credentials for an existing backend, remove it and add it again;
- when no per-backend credentials are provided, the global CLI SOCKS auth is used
  if present.

## Traffic rules

`t2s` can load rules from the `TRAFFIC_RULES` environment variable. The value can
be either a JSON array or an object with a `rules` array.

Supported actions:

- `socks` — force SOCKS backend forwarding;
- `direct` — connect directly;
- `drop` — terminate;
- `reset` — accepted by the parser and implemented as early termination;
- `wait` — wait for backend availability/policy conditions.

Supported match fields:

- `proto`;
- `port`;
- `port_range` such as `1000-2000`;
- `host_regex`;
- `socks_available`;
- `is_udp`.

Example:

```json
{
  "rules": [
    {"when": {"host_regex": "(^|\\.)example\\.com$"}, "action": "direct"},
    {"when": {"port": 443, "socks_available": true}, "action": "socks"}
  ]
}
```

Host rules depend on best-effort metadata sniffing. `t2s` can inspect HTTP Host,
HTTP CONNECT and TLS SNI. Under load, sniffing uses a smaller budget or can be
skipped when no host-based rule requires it.

## Health checks and direct fallback

Backends are monitored and classified by runtime health. GREEN backends are
eligible for SOCKS forwarding. When no eligible SOCKS backend is available,
`t2s` can use direct fallback if current rules and policy allow it.

Direct fallback is not treated as a permanent bypass. When healthy backends
recover, direct connections can be terminated so clients reconnect through the
proxy path.

## Connection registry

Each active connection is tracked with metadata such as connection ID, target,
backend, state and traffic counters. The web UI/API uses this registry to display
runtime state and kill selected connections.

## Limitations

- TCP only;
- no UDP proxying;
- no internal DNS server;
- `--enable-http2` is a compatibility flag, not a separate HTTP/2 engine;
- host detection is best-effort and depends on early traffic bytes;
- transparent mode depends on Linux/Android firewall behavior and
  `SO_ORIGINAL_DST` availability.

## Build

```bash
cargo build --release -p t2s
```
