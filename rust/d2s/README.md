# D2S — DNS to SOCKS transport helper

`D2S` is an autonomous Rust service designed to sit between `dnscrypt-proxy`
and a pool of local passwordless SOCKS5 transports.

```text
dnscrypt-proxy -> D2S local SOCKS5 -> next healthy SOCKS5 backend
                                      -> DIRECT if all backends are unavailable
```

D2S does not parse or modify DNS packets. It does not use `t2s`, does not touch
`iptables`, routing tables, TUN interfaces, or Android DNS settings. It reads
the active local SOCKS5 listener from `dnscrypt-proxy.toml` and never rewrites
that file or its own configuration.

## Features

- local SOCKS5 server with `NO AUTH` and `CONNECT` only;
- listener address taken from the active `proxy = 'socks5://127.0.0.1:PORT'`
  entry in `dnscrypt-proxy.toml`;
- IPv4, IPv6, and domain destinations;
- round-robin balancing across healthy SOCKS5 backends;
- immediate retry through the next backend on failure;
- active and passive backend health tracking;
- automatic recovery of previously failed backends;
- DIRECT fallback when every SOCKS5 backend is unavailable or the pool is empty;
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

## ZDT-D configuration compatibility

The ZDT-D integration keeps the stable transport behaviour unchanged. The
listener is read from the active local `socks5://` proxy in
`dnscrypt-proxy.toml`. A missing `d2s.toml` may be created by `zdtd` with the
minimal valid configuration:

```toml
backends = []
direct_fallback = true
```

Older experimental configurations may still contain `idle_after_secs`,
`route_timeout_ms`, `max_backend_attempts`, or `max_connecting`. These keys are
accepted only for upgrade compatibility and do not affect the stable routing or
health state machine.

## Tests

```bash
cargo test --all-targets
cargo clippy --all-targets --all-features
cargo fmt --all -- --check
```

## DNSCrypt-aware reliability

When `proxy = 'socks5://127.0.0.1:PORT'` is enabled, dnscrypt-proxy switches its
main upstream protocol to TCP. DNSCrypt protocol queries commonly create a new
SOCKS5 CONNECT per query, while DoH can keep HTTP/1.1 or HTTP/2 connections
alive. D2S therefore keeps the relay itself timeout-free and only bounds route
establishment.

D2S reads the `timeout` value from `dnscrypt-proxy.toml` (5000 ms when omitted,
matching dnscrypt-proxy's default) and keeps SOCKS/backend/DIRECT route setup
inside that deadline with a 500 ms safety margin. This is important because the
SOCKS dialer used by dnscrypt-proxy can perform a plain `Dial()` that is not
always cancelled by the HTTP/request context.

SOCKS5 CONNECT reply codes `0x02` through `0x06` are treated as target/path
failures. A single such error no longer removes a GREEN backend immediately;
`failure_threshold` must actually be reached. Hard local backend failures
(connection refused, SOCKS handshake timeout/protocol failure, unsupported
SOCKS behavior) still remove the backend immediately.

After D2S has successfully routed real DNSCrypt traffic, health probes prefer a
small in-memory set of recently successful DNSCrypt targets. The static
`probe_targets` list is used for startup, before any real target is known. This
avoids declaring a working backend dead just because an unrelated public probe
address is unreachable through that transport.


### Idle health sleep

Synthetic health probes run only while D2S is active. After `idle_after_secs`
(default 60 seconds) with no active client connections and no new accepts, the
health scheduler sleeps on a notification instead of polling. A new DNSCrypt
connection wakes it immediately; routing itself never waits for a health probe.
If no backend is GREEN, recovery probes remain active even during client idle so
D2S can recover before the next DNS request. Set `idle_after_secs = 0` to disable
this optimization.

Health probes are single-flight per backend. If connecting to the local SOCKS5
listener itself fails before the handshake (for example `Connection refused`),
D2S stops that probe immediately because trying additional external targets
cannot change the result.
