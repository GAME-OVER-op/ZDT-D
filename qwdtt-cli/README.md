# qwdtt-cli

Headless supervisor for the qWDTT WireGuard-over-VK-TURN transport, for use as a
ZDT-D program. It runs the transport without the qWDTT Android app: no
`VpnService`, no VPN key icon, no notification — a single root-side process that
ZDT-D routes on top of.

See [`docs/M0-findings.md`](docs/M0-findings.md) for the upstream investigation
this design is built on. In short: the upstream `go_client` is already a
standalone `package main` CLI, so `qwdtt-cli` **supervises it as a child process**
rather than linking a library.

## What it does (current state — M1 + M2, M3 integration ready)

- Loads a single gitignored config file (`qwdtt.conf`) mirroring the transport's
  flag schema; validates it before spawning anything.
- Prepares a persistent state directory and optionally seeds the browser identity
  (`vk_profile.json`, `captcha_browser_fp`).
- **Validates every VK hash** via the transport's `-check-hashes` mode and drops
  dead ones before launch, so a dead room cannot strand downstream worker groups
  (the "9 of 45 workers, reported healthy" failure).
- Runs the transport under a restart loop with backoff; waits for `wg-turn.conf`
  by watching the file (language-independent), not by parsing localized logs.
- **Brings up the WireGuard interface (M2):** once `wg-turn.conf` lands, it splits
  the config into an `awg setconf` body plus interface settings, spawns
  `amneziawg-go`, applies it, and configures `zdtdqw0` (`ip addr`/`ip link`) —
  mirroring the fork's proven amneziawg driver. Installs no routes and binds no
  UIDs (routing is ZDT-D's job); tears the interface down on every restart/stop so
  no orphaned TUN points at a dead loopback port.
- Feeds manually-solved captcha tokens from a watched file to the transport as
  `CAPTCHA_RESULT|<token>` — the headless fallback with no WebView.
- Stops cleanly on SIGTERM/SIGINT (STOP → SIGTERM → SIGKILL escalation) so TURN
  allocations are released; exits non-zero when it gives up, so ZDT-D restarts the
  whole stack rather than orphaning a TUN.

- **ZDT-D integration (M3):** `deploy/install-zdtd-profiles.sh` provisions the
  `myprogram` profile that launches qwdtt-cli and the `myvpn` profile that binds a
  test app's UID to `zdtdqw0`. See `docs/M3-integration.md`. amneziawg-go is kept
  in the supervisor's process group so ZDT-D's `kill -15 -- -<pgid>` group-stop
  reaps it and the TUN auto-removes.

The two upstream deltas (atomic `wg-turn.conf` write, `STATS|` marker) are
implemented as patches in `../upstream/qwdtt/`.

Verified on-device (S23 Ultra, KernelSU Next, whitelisted SIM): hashes validate,
the transport reaches the VPS via VK TURN, `zdtdqw0` comes up, and a `myvpn`-bound
app egresses at the VPS with no manual interface binding. See
`docs/M3-integration.md`. Remaining work is M4 hardening (throughput/MTU under
load, energy-saver exemption, proxyInfo, Zygisk interface hiding) and the M5
first-class program module.

## Layout

```
cmd/qwdtt-cli/      entry point, signal handling
internal/config/    config file loader + validation (the invariants)
internal/transport/ argv builder + structured-stdout marker parsers (pure)
internal/wg/        wg-turn.conf -> setconf split (pure) + amneziawg-go bring-up
internal/supervisor/ process orchestration: validate, run, wg bring-up, watchdog, stop
deploy/            install-zdtd-profiles.sh — provision the myprogram/myvpn profiles
docs/              M0-findings, M3-integration
qwdtt.example.conf  copy, fill in, keep out of git
```

## Build

The supervisor is pure Go (no cgo) and cross-compiles without the NDK:

```bash
make arm64      # -> out/qwdtt-cli for android/arm64
make host       # native build for development
make test vet   # checks
```

The upstream `go_client` transport it drives is built separately and **does** need
the NDK (`CGO_ENABLED=1`). Deploy both to `/data/adb/ZDT-D/bin/` and point
`transport_binary` at the transport.

## Configuration

Copy `qwdtt.example.conf`, fill it in, and keep it out of version control:

```bash
cp qwdtt.example.conf /data/adb/ZDT-D/etc/qwdtt.conf && chmod 600 /data/adb/ZDT-D/etc/qwdtt.conf
qwdtt-cli -config /data/adb/ZDT-D/etc/qwdtt.conf
```

Secrets (VK hashes, tunnel password, VPS host, WG keys, `vk_profile.json`) live
only in that file and the state directory — never in the repo.

## License

GPL-3.0-or-later. Derived from GPL-3.0 upstream sources.
