# D2S — DNS to SOCKS transport helper

`D2S` is an autonomous Rust service designed to sit between `dnscrypt-proxy`
and a pool of local passwordless SOCKS5 transports.

```text
dnscrypt-proxy -> D2S local SOCKS5 -> healthy/local SOCKS5 backend
                                      -> next backend on failure
                                      -> DIRECT when allowed
```

D2S does not parse or modify DNS packets. It does not use `t2s`, does not touch
`iptables`, routing tables, TUN interfaces, or Android DNS settings. It reads
the active local SOCKS5 listener from `dnscrypt-proxy.toml` and never rewrites
that file or its own configuration.

## Reliability model

- D2S accepts traffic immediately; startup never waits for backend probes.
- Routing preference is `GREEN -> UNKNOWN -> degraded half-open -> DIRECT`.
- A failed backend is skipped immediately inside the same client request.
- `failure_threshold` controls transition from `YELLOW` to `RED`.
- `YELLOW`/`RED` recovery is single-flight: only one recovery attempt is active
  for a degraded backend at a time.
- SOCKS5 destination-specific replies (`0x02..=0x06`) do not poison global
  backend health; transport/protocol failures still do.
- SOCKS failover is bounded by `route_timeout_ms` and
  `max_backend_attempts`, preventing long DNS stalls when many backends fail.
- `max_connecting` limits simultaneous outbound connect/handshake work after a
  network change or reconnect burst.
- RED recovery uses bounded exponential backoff while the service is active.
- Health checks use deadlines rather than one-second polling.
- After `idle_after_secs` without client traffic, synthetic health checks sleep;
  the SOCKS listener remains ready and real traffic continues to work instantly.
- Repeated DIRECT fallback messages are collapsed to state transitions instead
  of being logged once per DNSCrypt connection.

## Features

- local SOCKS5 server with `NO AUTH` and `CONNECT` only;
- listener address taken from the active `proxy = 'socks5://127.0.0.1:PORT'`
  entry in `dnscrypt-proxy.toml`;
- IPv4, IPv6, and domain destinations;
- round-robin balancing across healthy backends;
- startup use of unchecked backends to avoid unnecessary DIRECT traffic;
- active and passive backend health tracking;
- automatic recovery of previously failed backends;
- DIRECT fallback when allowed and no SOCKS route succeeds;
- graceful SIGTERM/SIGINT shutdown;
- optional atomic JSON status file.

## Build

```bash
cargo build --release
```

The binary is written to `target/release/d2s`.

## Usage

```bash
# Validate both configurations
d2s --config ./d2s.toml --dnscrypt-config ./dnscrypt-proxy.toml check

# Probe configured backends once
d2s --config ./d2s.toml --dnscrypt-config ./dnscrypt-proxy.toml probe

# Run D2S
d2s --config ./d2s.toml --dnscrypt-config ./dnscrypt-proxy.toml run

# Print an example D2S configuration to stdout
d2s example-config
```

Copy `d2s.example.toml` manually and edit the SOCKS5 backend list. An empty
`backends = []` list is valid when `direct_fallback = true`.

## Tests

```bash
cargo test --all-targets
cargo clippy --all-targets --all-features
cargo fmt --all -- --check
```
