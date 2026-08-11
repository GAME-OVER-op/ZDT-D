# D2S — DNS to SOCKS transport helper

`D2S` is a local SOCKS5 transport helper between `dnscrypt-proxy` and a pool of
local passwordless SOCKS5 transports.

```text
dnscrypt-proxy -> D2S local SOCKS5 -> verified GREEN SOCKS5 backend
                                      -> next GREEN backend on connect failure
                                      -> DIRECT fallback when allowed
```

D2S does not parse DNS packets. It does not modify resolver answers, DNSSEC,
cache, routing tables, TUN state, iptables, or Android DNS settings. The D2S
listener is read from the active local `proxy = 'socks5://127.0.0.1:PORT'`
entry in `dnscrypt-proxy.toml`.

## Health model adapted from T2S

A local SOCKS listener being alive is not enough to become GREEN. D2S uses two
health stages:

1. **Light / SOCKS reachability** — TCP connect to the local backend and a full
   SOCKS5 NO-AUTH greeting.
2. **Full / Internet data-plane** — SOCKS reachability plus SOCKS CONNECT to a
   dedicated TLS-capable `probe_targets` endpoint, then a real TLS ClientHello
   and at least one byte returned from the remote side.

States mean:

- **GREEN** — a Full probe has confirmed real Internet data-plane through the
  SOCKS backend.
- **YELLOW** — the SOCKS server is reachable, but a Full Internet probe did not
  confirm the data-plane.
- **RED** — the local SOCKS listener/greeting itself is unavailable or invalid.
- **UNKNOWN** — not yet checked.

Only GREEN backends receive DNSCrypt traffic.

A successful normal DNSCrypt SOCKS CONNECT does **not** promote YELLOW/RED to
GREEN. GREEN is granted only by the strict Full probe. A normal successful
connection can keep runtime counters healthy, but health authority remains the
probe state machine.

### Runtime errors and suspect rechecks

Runtime errors are treated as signals rather than immediate final health
verdicts:

- SOCKS reply `0x02..0x06` is treated as target/path-specific and triggers an
  immediate coalesced Full recheck without directly evicting a verified GREEN
  backend.
- transient handshake/attempt timeouts use wider soft hysteresis;
- hard listener/protocol failures use `failure_threshold` as the hysteresis
  baseline;
- relay I/O errors after an already successful CONNECT mark the backend
  *suspect* and trigger a Full recheck;
- because D2S only carries DNSCrypt/DoH traffic, a closed relay that sent client
  bytes but received zero bytes from upstream is also treated as a suspect
  data-plane event and forces a Full recheck.

Forced suspect rechecks are coalesced by `runtime_cooldown_ms` and are
single-flight per backend so a DNS burst cannot create a probe storm.

Soft/hard runtime failures also apply a short T2S-style selection cooldown (3s
for soft, 6s for hard). When other GREEN backends exist they are preferred
during this window. If the cooling backend is the only GREEN route, it remains
selectable so cooldown can never manufacture a DNS outage. Target/path-specific
failures do not apply this global backend cooldown.

### Warm runtime selection

Health and traffic selection are separate. GREEN only means that the strict Full
probe has proved the route usable. D2S additionally learns an EWMA latency from
real successful DNSCrypt SOCKS CONNECTs and marks recently measured GREEN
backends as runtime-warm.

Normal requests are balanced only across the fast warm band instead of pure
round-robin across every GREEN backend. The hot band contains warm backends close
to the best observed runtime latency, so several genuinely fast transports share
traffic while a much slower GREEN transport does not delay normal DNS requests.

A soft/hard backend runtime failure immediately clears that backend's warm score
and applies the existing cooldown, while GREEN/YELLOW/RED remains controlled by
the health state machine. At startup, each verified GREEN backend gets one runtime
sample so the warm pool is learned quickly. A previously sampled backend that
recovers later re-enters as cold; when a warm pool already exists, D2S gives it
only a sparse exploration request (about one selection in 32) so it can prove
current real-world latency and rejoin without putting every request through
discovery. Runtime warmth expires after two minutes without a successful real
connection, allowing an old slow measurement to be periodically re-evaluated.

### Recovery when no GREEN backend exists

When the last GREEN backend is lost, D2S automatically uses an accelerated Full
probe ladder inspired by T2S:

```text
first 30 seconds       -> about every 2 seconds
next 60 seconds        -> about every 5 seconds
after that             -> about every 15 seconds
```

YELLOW and RED can both recover directly to GREEN only after a successful Full
Internet data-plane probe.

When at least one GREEN backend exists, healthy backends receive cheap Light
checks at `healthy_probe_interval_secs`. A GREEN backend also receives a Full
Internet verification approximately every 15 minutes. Non-GREEN backends use
`recovery_probe_interval_secs` outside the no-GREEN recovery ladder.

Repeated failed Full Internet probes use the T2S backoff `30s -> 60s -> 120s ->
300s -> 600s -> 900s`; cheap Light SOCKS reachability checks may continue in
between. Forced suspect rechecks and the no-GREEN recovery ladder bypass this
backoff so actual DNS failure/recovery remains responsive.

### Idle health sleep

After `idle_after_secs` with no active DNSCrypt clients, the health scheduler can
sleep instead of polling. A new client wakes it immediately. Forced suspect
rechecks also wake it immediately. D2S never sleeps while no GREEN backend
exists, so recovery continues even without client traffic.

Routing never waits for an idle wake/probe; the current verified state is used
immediately.

### DIRECT health

DIRECT is tracked independently from SOCKS health. Repeated DIRECT connection or
relay failures trigger a short cooldown so a restricted mobile network cannot
make every DNS query repeatedly spend time on a known-bad DIRECT path. Actual
payload received through DIRECT clears this cooldown.

D2S intentionally does not run extra periodic DIRECT TLS probes because DNS is
the only client and actual DNSCrypt traffic provides a more representative
signal without additional background traffic.

## DNSCrypt-specific routing behavior

`dnscrypt-proxy` can create many short TCP/SOCKS CONNECTs for native DNSCrypt
and long-lived HTTP/1.1 or HTTP/2 connections for DoH. D2S therefore limits
route establishment but does **not** impose an artificial idle timeout on an
established relay.

D2S reads the DNSCrypt `timeout` value and keeps backend/DIRECT route setup
inside that deadline with a small safety margin.

In single-backend mode, a short one-shot retry remains enabled for SOCKS reply
`0x03`/`0x04` to absorb brief Wi-Fi/mobile route transitions. This only retries
the current DNSCrypt CONNECT; it does not decide backend health.

## Probe targets

`probe_targets` are dedicated TLS-capable health endpoints, not DNSCrypt resolver
targets. Full health tries them in configured order and stops at the first
endpoint that proves real TLS data-plane. Default:

```toml
probe_targets = [
  "1.1.1.1:443",
  "8.8.8.8:443",
]
```

Do not replace these with native DNSCrypt-only endpoints merely because they use
port 443/8443. Full health sends a TLS ClientHello and expects real TLS data.
Trying more than one dedicated target prevents a single operator-blocked probe
endpoint from falsely making an otherwise working SOCKS backend YELLOW.

## Build and usage

```bash
cargo build --release
cargo test --all-targets

d2s --config ./d2s.toml --dnscrypt-config ./dnscrypt-proxy.toml check
d2s --config ./d2s.toml --dnscrypt-config ./dnscrypt-proxy.toml probe
d2s --config ./d2s.toml --dnscrypt-config ./dnscrypt-proxy.toml run
```

An empty backend list is valid only with `direct_fallback = true`.

## Compatibility

Legacy experimental keys `route_timeout_ms`, `max_backend_attempts`, and
`max_connecting` remain accepted only so an old `d2s.toml` does not break after
an upgrade. They do not control the current routing state machine.
