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

Health and traffic selection are separate. GREEN means that the strict Full
probe has proved the route usable. A successful Full Internet probe seeds a
latency estimate for a new or recovered backend, and real DNSCrypt SOCKS
CONNECTs continuously refine that value with an EWMA.

Normal requests use smooth weighted round-robin across measured GREEN backends.
Weight is based on inverse squared latency, so the fastest route receives most
new DNS connections, the next-fastest receives fewer, and slower healthy peers
are still exercised occasionally instead of being starved. A small minimum
weight keeps backup routes warm and lets their runtime EWMA recover naturally if
network conditions improve.

Cold exploration has been removed from the user DNS path. A backend that was
RED/YELLOW is checked by the existing background health loop; once a Full probe
proves the Internet data plane again, that probe latency is enough to return the
backend to weighted selection. Runtime latency does not expire merely because a
fixed timer elapsed: health probes continue to prove reachability, while actual
runtime failures immediately clear the stale warm score and apply the existing
selection cooldown.

For failover within one request, remaining GREEN backends are ordered from the
best known latency to the worst, with unmeasured routes last. When more than
one GREEN backend exists, establishment is additionally hedged: if the first
candidate has not produced a stream within `hedge_stagger_ms` (widened to
twice the candidate's measured runtime latency, so healthy mobile RTT jitter
never spawns duplicate connects), the next selectable candidate dials in
parallel and the first winner is used. The losing attempt is never aborted
mid-handshake: it resolves in the background under its own timeout window,
where a background janitor applies the original per-attempt health bookkeeping
(a success refreshes the runtime EWMA, a timeout marks a soft failure and
schedules the strict Full recheck). At most two attempts run at once, so in
the healthy case traffic volume is identical to the sequential dialer;
duplicate connects only appear when the preferred route is actually stalling,
and they are attempts the sequential dialer would have made anyway — just
later.

### Warm tunnels for repeated targets

DNSCrypt reconnects to the same resolver addresses over and over: native
DNSCrypt opens a fresh SOCKS CONNECT per query burst and DoH pools long-lived
HTTP/1.1 or HTTP/2 connections to the same endpoint. With `warm_tunnels =
true`, every established SOCKS-routed tunnel to such a repeat target is
followed by one background replacement tunnel, and the next request for the
same target is served from that pre-connected tunnel instead of paying the
full backend TCP + SOCKS handshake again.

The replacement dial is fully detached from the request path (it never adds
latency), is bounded by the usual attempt timeouts, and only SOCKS-routed
tunnels are cached — DIRECT is never pre-connected. Tunnels older than
`warm_tunnel_ttl_secs` are closed instead of served, and the `warm_tunnel_hits`
status counter makes the hit rate visible. In steady state the number of
upstream connects per request is unchanged; only the final replacement before
an idle gap can go unused.

On top of the reactive cache, a backend that transitions into GREEN (initial
verification or recovery) immediately pre-connects one tunnel to the hottest
recently used DNS target. Without this, every recovery made the first DNS
requests slow until the transport session warmed up — now the warm path is
ready before the first query arrives. The preconnect re-checks that the
backend is still selectable after its dial and silently gives up otherwise.

### DNS-path fitness of backends

Some backends pass every generic health stage (SOCKS reachable, TLS data-plane
to the probe targets) while their transport cannot actually carry DNS
traffic. Such a backend fails real queries with target/path SOCKS replies or
relays that send bytes upstream and never receive a response — failures the
Full probe cannot see, so it stays GREEN. D2S now escalates a
**selection-level exclusion** from that runtime evidence:

- each target/path SOCKS reply and each zero-downstream relay suspect
  increments the backend's DNS-path failure streak;
- two consecutive signals exclude the backend from weighted selection for an
  escalating cooldown (15 s -> 30 s -> 60 s -> 120 s) and also invalidate its
  cached warm tunnels;
- the exclusion never demotes GREEN/YELLOW/RED — the strict Full probe stays
  the health authority — and never applies when the backend is the only GREEN
  route, so it can never manufacture a DNS outage;
- one relay that actually delivered downstream bytes clears the whole streak
  immediately (`dns_path_failures` / `dns_unfit` are visible in the status
  JSON).

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

### Idle health freshness

Health scheduling stays active even when DNSCrypt has no client traffic. Older
builds stopped probes after `idle_after_secs`; that allowed a previously GREEN
backend to become stale while D2S slept, so the first DNS request after a long
quiet period could spend a full backend timeout discovering that the route had
disappeared. Current builds keep the normal low-cost scheduler running: healthy
routes receive Light checks at `healthy_probe_interval_secs`, while Full Internet
verification keeps its existing long cadence.

`idle_after_secs` is still accepted in `d2s.toml` for upgrade compatibility but
is no longer used to suspend health checks. No configuration migration is
required.

The health scheduler itself is event-driven: instead of waking once per second,
it sleeps until the earliest `next_probe` deadline and is woken immediately by
runtime/relay failure signals, so failure responsiveness is unchanged while a
fully idle D2S no longer burns periodic CPU wakeups. A GREEN backend that is
actively serving real DNSCrypt traffic also skips its scheduled cheap Light
check: a successful runtime CONNECT proves strictly more than a SOCKS-only
reachability probe. Strict Full Internet verification keeps its own long
cadence regardless of traffic.

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

Established tunnels use a supervised bidirectional relay instead of an
unbounded `copy_bidirectional()` call:

- an idle keep-alive is never closed merely for being idle;
- after the client forwards the first real payload bytes, the remote side must
  prove the data-plane within the active DNSCrypt query timeout;
- after the client write half reaches EOF, the remote read half gets the
  DNSCrypt timeout plus a small drain margin to finish a final response; a
  remote EOF closes the relay immediately because no further DNS/DoH response
  can arrive;
- relay copies are child futures of the client task, not detached tasks, so task
  cancellation/shutdown cannot leave orphan relay workers behind;
- client-side reset/read/write errors are treated as client cancellation and do
  not falsely mark a SOCKS backend suspect; remote-side I/O errors still trigger
  the existing strict Full recheck.

D2S reads the DNSCrypt `timeout` value and keeps backend/DIRECT route setup
inside that deadline with a small safety margin. The same runtime value is used
for the first-response and half-close safety windows, so no independent relay
knob can silently drift away from DNSCrypt's own timeout policy.

In single-backend mode, a short one-shot retry remains enabled for SOCKS reply
`0x03`/`0x04` to absorb brief Wi-Fi/mobile route transitions. This only retries
the current DNSCrypt CONNECT; it does not decide backend health.

The optional status JSON also exposes relay-lifecycle counters including active
and peak connections, oldest active connection age, connection-limit drops,
first-response stalls, half-close timeouts, forced closes, EOF counts, and
client-vs-remote I/O errors, plus the warm-tunnel hit counter. These counters
make leaked or repeatedly stalled DNS transports visible without changing
backend selection behavior. When nothing observable changes between two status
intervals, the identical status rewrite is skipped so an idle D2S stops
touching the status file.

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
