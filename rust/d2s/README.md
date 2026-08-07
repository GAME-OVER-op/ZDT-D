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
- Only `GREEN` backends carry DNSCrypt traffic. Until the first probe succeeds,
  D2S uses DIRECT fallback when it is enabled.
- Every GREEN backend is tried with its own bounded attempt timeout; there is no
  second global route deadline and no global outbound-connect semaphore.
- A failed GREEN backend is skipped immediately inside the same client request,
  so the next GREEN backend can be tried before DIRECT fallback.
- SOCKS5 destination-specific replies (`0x02..=0x06`) do not poison global
  backend health; transport/protocol failures still do.
- `failure_threshold` distinguishes degraded (`YELLOW`) from unavailable (`RED`)
  state; recovery is performed by background health probes.
- RED recovery uses bounded backoff while health checks are active.
- Active health checks use a lightweight one-second scheduler with missed ticks skipped;
  after the idle threshold the scheduler stops polling completely.
- After `idle_after_secs` without active client connections, synthetic probes
  sleep. A newly accepted DNSCrypt connection wakes the scheduler immediately;
  routing itself never waits for a probe.
- Repeated DIRECT fallback messages are collapsed to state transitions instead
  of being logged once per DNSCrypt connection.

## Features

- local SOCKS5 server with `NO AUTH` and `CONNECT` only;
- listener address taken from the active `proxy = 'socks5://127.0.0.1:PORT'`
  entry in `dnscrypt-proxy.toml`;
- IPv4, IPv6, and domain destinations;
- round-robin balancing across healthy backends;
- non-blocking startup with DIRECT fallback until backend health is known;
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
