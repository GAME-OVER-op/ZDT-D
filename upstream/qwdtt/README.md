# upstream/qwdtt — pinned, minimally-patched qWDTT transport

This directory does **not** vendor upstream source. It carries only:

- `UPSTREAM` — the pinned repository URL and commit,
- `patches/` — our two minimal deltas as `git apply`-able patches,
- `fetch-and-build.sh` — clones upstream at the pin, applies the patches, and
  cross-compiles the transport for Android.

The fetched third-party tree and build outputs (`.work/`, `out/`) are gitignored
and regenerated on demand. This keeps our repo's diff to exactly the two lines of
behavior we change, and keeps the GPL-3.0 upstream where it belongs.

## Build

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
./fetch-and-build.sh                     # arm64-v8a -> out/qwdtt-transport
./fetch-and-build.sh arm64-v8a /data/adb/ZDT-D/bin/qwdtt-transport
```

The result is a **standalone ELF executable**, not a shared library. Upstream's
own build names it `libclient.so` purely so the Android APK packages it with exec
permission; it is built with a plain `go build` (no `-buildmode=c-shared`), so it
has a real `func main()` and a full CLI flag surface. qwdtt-cli supervises it as a
child process — see `../../qwdtt-cli/docs/M0-findings.md`.

The supervisor itself needs no NDK (pure Go); only this transport does, because it
links C via CGO.

## The pin

Pinned to `2dd5d37` (see `UPSTREAM` for the full SHA) — upstream HEAD, tagged
`v1.3.8`, `v1.3.9`, `v1.4.0` and `v1.4.0-beta` (all the same commit). Verified:
`go_client` compiles and both patches below apply cleanly.

We were previously pinned to `7b5dcec` because the then-HEAD `4791f8c` did not
build: `go_client/group.go` called `workerErrorHint`, which was defined nowhere in
that commit. Upstream has since added the helper (`go_client/errhint.go`), so the
pin moves forward.

What the newer code brings us:

- **SOCKS5 client mode** (`-mode socks`, `-socks host:port`): the transport runs
  WireGuard in a userspace netstack inside its own process and serves SOCKS5, so
  no TUN, `amneziawg-go` or `awg` is involved at all. qwdtt-cli exposes this as
  the `mode` config key. Caveat: it is **TCP CONNECT only** (no UDP ASSOCIATE,
  IPv6 rejected), so app UDP traffic — QUIC, direct DNS — is not carried; names
  passed through SOCKS are resolved inside the tunnel.
- Clearer worker/WRAP error hints.

Note the "RU IP bypass" that landed in `4791f8c` is present in this commit too. We
never enable it: our invariants forbid any direct or RU-direct path, and nothing in
qwdtt-cli passes such a flag.

The patches target stable code paths (config write, stats loop) and should
re-apply across minor upstream movement.

## The patches

Both are surgical and independently reviewable.

### `0001-atomic-wg-turn-conf-write.patch`

Upstream writes `wg-turn.conf` with a single `os.WriteFile`, so a WireGuard
consumer watching the file can observe a partial/empty state. The patch writes a
sibling `wg-turn.conf.tmp` and `os.Rename`s it into place — an atomic swap on the
target filesystem. Honors the project invariant "atomic config writes — tmp +
rename()" at the source rather than working around it in the supervisor.

### `0002-machine-readable-stats-marker.patch`

Upstream only logs the active-worker count in a localized Russian line
(`[СТАТИСТИКА] Активных: N`). The patch additionally prints a stable
`STATS|active|bytesUp|bytesDown` marker to stdout every stats tick, so the
supervisor's watchdog can read the active count structurally. This satisfies the
invariant "do not parse the Russian log strings for supervision" — the human log
is left untouched, and control decisions use the marker.

## License

Upstream is GPL-3.0 (individual files may carry their own SPDX headers, e.g.
`obfs.go`/`wrap.go` are MIT). Our patches are GPL-3.0-or-later. Nothing here
redistributes upstream source; the build fetches it at the pinned commit.
