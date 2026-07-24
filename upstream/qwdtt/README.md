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

Pinned to `7b5dcec` (see `UPSTREAM` for the full SHA). This is the newest upstream
commit whose `go_client` **compiles**. Upstream HEAD at investigation time
(`4791f8c`) does not build — `go_client/group.go` references `workerErrorHint`,
which is defined nowhere in that commit (it shipped the caller of a "worker error
hint" helper without the helper). `7b5dcec` is its parent, builds cleanly, and
predates the "RU IP bypass" feature that HEAD adds — a feature our invariants
forbid anyway (no direct or RU-direct path is ever allowed).

Re-evaluate the pin when upstream fixes HEAD; the patches target stable code paths
(config write, stats loop) and should re-apply across minor upstream movement.

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
