# M0 findings — qwdtt-cli open questions resolved

All five open questions from `CLAUDE.md` are answered below with direct evidence
from the upstream transport. Findings are pinned to a specific upstream commit so
they can be re-verified as upstream drifts.

- **Upstream:** `SpaceNeuroX/proxy-turn-vk-android`
- **Commit inspected:** `4791f8c08b3bf592686f3cf443d4f17005ce87ef` (2026-07-20)
- **Package under test:** `go_client/` (module `wg-turn-client`, Go 1.25.0)

> **Build note (added after investigation):** the inspected HEAD `4791f8c` does
> **not compile** — `go_client/group.go` references `workerErrorHint`, defined
> nowhere in that commit. We build against its parent `7b5dcec` (the newest
> compiling commit), pinned in `upstream/qwdtt/UPSTREAM`. The two deltas below are
> implemented as patches in `upstream/qwdtt/patches/` and confirmed to build.

---

## Headline correction — the transport is already a standalone CLI

CLAUDE.md frames the transport as `libclient.so`, a JNI/`c-shared` library that a
`main` wrapper must call into ("open question 1: what is the minimal entry point").
**That framing is obsolete for the current upstream.** `go_client/` is
`package main` with a real `func main()` (`go_client/main.go:109`) and **zero
`//export` directives** anywhere in the package. The JNI `.so` is just one build
output of this same `package main`; it also builds as an ordinary executable.

It already ships everything the CLI milestones need:

| Capability | Where | Notes |
|---|---|---|
| Full flag surface | `main.go:163-179` | see table below — no config parsing to reverse-engineer |
| Writes `wg-turn.conf` itself | `main.go:367` | injects `MTU = 1280` if absent (`main.go:350`) |
| Hash validation mode | `main.go:179`, `runHashChecks` `main.go:59` | `-check-hashes`, structured output |
| Structured stdout markers | `HASH_CHECK\|`, `PING_RESULT\|`, `PING_ERROR\|` | machine-parseable, **not** the Russian logs |
| Stdin control channel | `main.go:137-161` | `PAUSE` / `RESUME` / `STOP` / `CAPTCHA_RESULT\|` / `TURN_CREDS\|` |
| Signal handling | `main.go:116-132` | SIGTERM/SIGINT → graceful cancel; second signal → `os.Exit(1)` |
| Non-zero exit paths | `log.Fatal*` throughout, `os.Exit(1)` | satisfies the "non-zero exit on death" invariant |

**Architectural consequence:** `qwdtt-cli` does **not** need cgo, does **not** need
to embed or link a library, and does **not** need to reimplement any transport
logic. It builds `go_client` as an `arm64-v8a` executable and **supervises it as a
child process**, driving it over flags + stdin and consuming its structured stdout.
This is simpler than the CLAUDE.md "Go `main` wrapper around c-shared" plan and
removes the whole "minimal entry point" question.

### Complete flag surface (`main.go:163-179`)

| Flag | Default | Maps to settings key | Purpose |
|---|---|---|---|
| `-peer` | *(required)* | `peer`+`server_dtls_port` | `IP:port` of VPS DTLS (e.g. `2.27.36.231:56000`) |
| `-vk` | *(required)* | `vk_hashes` | comma/space/`;`-separated VK call hashes |
| `-listen` | `127.0.0.1:9000` | `listen_port` | local UDP WG endpoint |
| `-password` | *(required)* | plaintext of `connection_password_encrypted` | HKDF → WRAP key (`wrap.go:16`) |
| `-n` | `24` | `workers_per_hash` | total workers, rounded to a multiple of 9 |
| `-obfs` | `audio` | `obfs_mode` | `audio`/`video` RTP masking — **captured device uses `video`** |
| `-vk-auth` | `anonymous` | `vk_auth_mode` | `anonymous`/`account` |
| `-vk-anon-path` | `vkcalls` | `vk_anon_path` | `vkcalls`/`legacy` |
| `-go-dns` | `yandex` | `go_dns_preset` | resolver used to *reach* VK (`custom:IP`, `doh:URL`, …) |
| `-captcha-mode` | `auto` | `captcha_mode` | `auto`/`wv`/`rjs` — headless must avoid `wv` (WebView) |
| `-device-id` | `unknown` | device id | 8-byte hex, stable per device |
| `-turn`/`-port` | empty | — | override TURN relay IP/port (normally learned) |
| `-vk-creds-file` | empty | — | inject account-mode TURN creds from file |
| `-check-hashes` | false | — | validate hashes and exit |
| `-ping-only` | false | — | measure RTT and exit |

---

## Q1 — `main` package vs `c-shared` export surface? **RESOLVED**

`go_client` is `package main` with `func main()` at `main.go:109`. No `//export`
in the package. Build it directly:

```bash
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
  CC=$NDK/.../aarch64-linux-android29-clang \
  go build -o qwdtt-transport ./go_client
```

No wrapper entry point is required. `qwdtt-cli` is a *supervisor*, not a linker.

## Q2 — Which hash source wins at runtime? **RESOLVED (moot for headless)**

The settings-vs-profile precedence was an **Android/Kotlin storage concern**. The
Go layer has no such logic: it takes hashes only from `-vk` (`main.go:191` →
`ParseHashes`, `group.go:254`). `ParseHashes` splits on `, ; \n \r \t space`,
normalizes VK join-link URLs down to the bare hash (`normalizeVKJoinHash`,
`group.go:272`), and de-duplicates. **Our config file is the single source of
truth**; we pass its hash list straight through to `-vk`.

## Q3 — How is group count derived? **RESOLVED**

- `workersPerGroup = 9` (constant, `group.go:16`).
- `numGroups = ceil(numW / 9)` (`main.go:301`).
- `-n` is clamped: `≤ 108` (`main.go:234`), rounded **down** to a multiple of 9 in
  anonymous mode (`main.go:251`), floored to 9 (`main.go:248`). Account mode caps at
  4 workers (`main.go:239`).
- Group→hash mapping is **modulo, not 1:1**: group `g` uses
  `hashes[g % len(hashes)]` (`group.go:62`).

This reproduces the captured session exactly: `workers_per_hash = 45` → `numW = 45`
→ **5 groups × 9 = 45**, spread over 4 hashes as `0,1,2,3,0`.

**Watchdog signal:** the expected steady-state worker count is `numW`. The live
count is exposed structurally via `Stats.ActiveConnections` (`stats.go:12`), logged
every 3 s as `[СТАТИСТИКА] Активных: N` (`stats.go:33`). The count is an `atomic.Int32`
— cleaner to surface than parsing the localized line, but today it is *only* logged,
not printed as a stable marker. **Delta needed:** add a machine-readable stats line
(e.g. `STATS|active|bytesUp|bytesDown`) so the watchdog never parses Russian text
(CLAUDE.md invariant). See deltas below.

## Q4 — Is `vk_profile.json` regenerated or rotated? **RESOLVED: both, self-managed**

- **Load if present:** `getTokenChain` calls `LoadProfileFromDisk()` and uses the
  saved profile only when its UA is non-empty; otherwise `getRandomProfile()`
  (`creds.go:375`, `profiles.go:30`).
- **Generated when absent:** random pick from a built-in table of **Chrome/Edge
  144–146** desktop profiles (`profiles.go:74-136`). The TLS fingerprint is pinned
  to `Chrome_146` regardless (`creds.go:384`, `creds_vkcalls.go:28`).
- **Rotated on captcha:** `rotateCaptchaProfile` overwrites **both**
  `vk_profile.json` (0644) and `captcha_browser_fp` (0644) with a fresh
  fp+UA+device_json (`profiles.go:47-71`).
- **All paths are relative to CWD** (`profileFile = "vk_profile.json"`,
  `captchaBrowserFpFile = "captcha_browser_fp"`, `profiles.go:26-27`).

**Implication for the CLI:** the supervisor must run the transport child in a fixed
working directory that persists across restarts, and seed `vk_profile.json` /
`captcha_browser_fp` there. The captured artifact says *Chrome 130* only because it
came from an **older APK**; current upstream emits 144–146. We persist the file to
keep a stable identity (CLAUDE.md invariant: a fingerprint that changes every launch
is itself a signal) and let upstream rotate it on captcha. We do **not** need to
generate it ourselves.

## Q5 — Stop signal & clean TURN teardown? **RESOLVED**

Two clean-stop paths, both trap-able:

1. **Signals:** SIGTERM/SIGINT → `cancel()` (graceful); a *second* signal → forced
   `os.Exit(1)` (`main.go:116-132`).
2. **Stdin:** a line `STOP` calls `cancel()` and closes stdin reader
   (`main.go:149`).

Teardown then cascades: `ctx.Done()` closes the local UDP conn (`main.go:293`),
`WorkerGroup` returns, `wg.Wait()` completes, `configCh` closes, `main` returns.

For the CLAUDE.md `myprogram`→child question: whatever signal `myprogram` delivers
on stop, the supervisor should translate it into **one SIGTERM (or `STOP` on stdin)**
to the transport child, then reap it — never SIGKILL first, or TURN allocations leak.

---

## Captcha (headless) — lower risk, path already exists

- Auto mode chains a Go-native v2 solver (`captcha_v2.go`), so **no WebView is
  required** headless. Do not pass `-captcha-mode wv`.
- Manual token feed already exists: write `CAPTCHA_RESULT|<token>` to the child's
  stdin (`main.go:152`). This is the CLAUDE.md "named pipe / watched file" path —
  build the supervisor's captcha feed on top of child stdin from day one.
- Hash-check classifier recognizes `captcha_required` / `captcha_wait_required`
  and reports status `captcha` (`main.go:82`), so validation surfaces captcha demand
  before the race starts.

## Hash validation & the relay-race deadlock (M1 core)

`-check-hashes` runs `GetCreds` per hash and prints, per hash:

```
HASH_CHECK_START|<idx>|<hash>
HASH_CHECK|<idx>|<hash>|<status>|<message>
```

`status ∈ {ok, captcha, dead, limited, network, error}` (`classifyHashCheckError`,
`main.go:76`). **Validate first, drop non-`ok` hashes, then launch.**

Why this is mandatory and not a nicety — the deadlock is structural:

- Groups start as a **baton relay**: group `g` signals group `g+1` only *after*
  `GetCreds` **succeeds** (`group.go:117-123`, fired 2 s after success).
- If `GetCreds` **fails**, the group `return`s **without** signalling
  (`group.go:74-77`) → every later group blocks forever in
  `waitReady` ("Ожидание сигнала от предыдущей группы", `group.go:41`).
- One dead hash therefore strands all downstream groups. The captured session ran
  **9 of 45 workers while reporting healthy** — exactly this.

Even after `-check-hashes`, a hash can die between check and launch, re-triggering
the deadlock. So the watchdog must also compare live `ActiveConnections` against the
expected `numW` and restart if it never reaches target within a deadline.

> **Discrepancy noted:** CLAUDE.md calls out VK `error_code=9008` ("Join link is not
> valid") as the terminal dead-hash marker. The current classifier keys off strings
> like `call not found` / `callunavailable` / `joinconversationbylink` / `9000`
> (`main.go:84-89`), not `9008`. The intent matches; the exact token differs by
> version. The supervisor should treat classifier `status=dead` as terminal for a
> hash rather than pattern-matching a specific numeric code.

---

## Deltas the supervisor needs (upstream is close but not turnkey)

1. **Atomic `wg-turn.conf` write.** ✅ Implemented —
   `upstream/qwdtt/patches/0001-atomic-wg-turn-conf-write.patch`. Upstream used a
   single `os.WriteFile("wg-turn.conf", …)` (`main.go:367`), **not** tmp+`rename()`,
   so a WG consumer could observe a partial/empty state. The patch writes
   `wg-turn.conf.tmp` then `os.Rename`s it into place, honoring the invariant at the
   source.
2. **Machine-readable stats line.** ✅ Implemented —
   `upstream/qwdtt/patches/0002-machine-readable-stats-marker.patch`. Adds a
   `STATS|active|bytesUp|bytesDown` line to `Stats.RunLoop` (`stats.go:33`) on stdout
   every tick, additively — the human log is untouched. The supervisor's watchdog
   reads the marker (`internal/transport.ParseStats`), never the Russian log.
3. **Fixed working directory.** All artifact paths are CWD-relative. The supervisor
   must `chdir` the child into a persistent state dir (holds `wg-turn.conf`,
   `vk_profile.json`, `captcha_browser_fp`).
4. **Runtime hash rotation.** There is no stdin command to swap the hash set live;
   `-vk` is read once at startup. Rotation today = restart with a new `-vk`. If live
   rotation is required (CLAUDE.md risk: "rotation must be a runtime operation"),
   it needs either an upstream stdin verb or accepting a supervised restart as the
   rotation mechanism. **Recommend: supervised restart** for M1–M4; revisit a live
   verb only if restart latency proves unacceptable.

## Licensing note

Repo `LICENSE` is GPL-3.0, but individual files carry their own SPDX headers —
e.g. `go_client/wrap.go` is `SPDX-License-Identifier: MIT`. Anything we vendor or
derive must have its per-file license checked, not assumed from the repo root, to
keep our "GPL-3.0 on everything we ship" invariant accurate (GPL-3.0 and MIT are
compatible when combined under GPL-3.0, but the attribution must be preserved).
