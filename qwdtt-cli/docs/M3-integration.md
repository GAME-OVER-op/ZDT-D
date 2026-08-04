# M3 — running qwdtt-cli as a ZDT-D myprogram + myvpn stack

M3 wires the supervisor into ZDT-D through the two generic profiles, then runs it
end-to-end on the whitelisted SIM with a test app. No first-class program module
yet — that is M5.

> **Status: verified on-device** (Samsung S23 Ultra, KernelSU Next, whitelisted
> SIM). Hashes validate, the transport reaches the VPS via VK TURN, `zdtdqw0`
> comes up, and a `myvpn`-bound app (Termux) egresses at the VPS with no manual
> interface binding. The two things that trip first-time bring-up are the TUN
> timing race (see "Start" below) and testing from the wrong UID (see
> Troubleshooting).

## Architecture

```
myprogram profile "qwdtt"
  └─ launches qwdtt-cli (root) ─┬─ transport ──▶ VK TURN ──▶ VPS   (stays direct, root uid)
                                └─ amneziawg-go ──▶ creates TUN zdtdqw0

myvpn profile "qwdtt"
  └─ binds the test app's UID to zdtdqw0 via Android netd
```

- **myprogram** is a universal launcher: it runs our binary and nothing else
  (`apps_mode=false`, so it does no iptables/t2s routing of its own). qwdtt-cli
  owns the transport and creates the TUN.
- **myvpn** binds selected app packages to an *already existing* TUN. It waits for
  `zdtdqw0`, learns its CIDR (`cidr_mode=auto` → `10.66.0.1/32`), and hands the UID
  binding to `vpn_netd`.

They are separate profiles with no ordering guarantee between them — which is
exactly why the transport and the WireGuard client both live inside qwdtt-cli
(one supervisor, one restart path), and only the *app binding* is delegated to
myvpn. myvpn simply retries until the TUN appears.

## Prerequisites (deploy to the device)

Recommended: upload the two binaries and the config into the **myprogram
profile's `bin/` dir** using the ZDT-D app's per-profile file uploader (it stores
them `chmod 755`, and myprogram runs the command with `cwd` = that dir).

| What | Where | Source |
|---|---|---|
| `qwdtt-cli` | `.../myprogram/profile/qwdtt/bin/qwdtt-cli` | `make arm64`, or the `qwdtt-cli-arm64` CI artifact |
| `qwdtt-transport` | `.../myprogram/profile/qwdtt/bin/qwdtt-transport` | `upstream/qwdtt/fetch-and-build.sh`, or the `qwdtt-transport-arm64` CI artifact |
| `qwdtt.conf` | `.../myprogram/profile/qwdtt/bin/qwdtt.conf` | from `qwdtt.example.conf`, filled in |
| `amneziawg-go`, `awg` | `/data/adb/modules/ZDT-D/bin/` | shipped with ZDT-D (see note) |

where `...` = `/data/adb/modules/ZDT-D/working_folder`. In `qwdtt.conf`, point
`transport_binary` at the uploaded transport (same bin dir) and set `state_dir` to
a persistent dir **outside** `bin/` (the example uses `.../profile/qwdtt/state`) so
runtime artifacts don't mix with the uploaded files. The install helper below
prints these exact paths.

(Placing the binaries under `/data/adb/ZDT-D/bin/` and the config under
`/data/adb/ZDT-D/etc/` also works — just set `QWDTT_CLI`/`QWDTT_CONF` when running
the helper and match `transport_binary` in the config.)

**amneziawg-go / awg come from ZDT-D, not upstream releases.** ZDT-D's build
compiles them from `amnezia-vpn/amneziawg-go` and `amnezia-vpn/amneziawg-tools`
and — critically — patches the UAPI socket path: amneziawg-go uses the
CWD-relative `run/amneziawg`, and awg is built with
`RUNSTATEDIR=/data/adb/modules/ZDT-D/working_folder/amneziawg/run`. They only find
each other's socket when amneziawg-go runs with cwd =
`/data/adb/modules/ZDT-D/working_folder/amneziawg`, which is qwdtt-cli's default
`awg_run_dir`. A stock amneziawg-go release will **not** work — use the ones ZDT-D
installs (or rebuild them via ZDT-D's `build.yml`). Verify they exist:
`ls -l /data/adb/modules/ZDT-D/bin/{amneziawg-go,awg}`.

**SELinux:** the ZDT-D daemon execs our binary; if the device enforces a label on
`/data/adb/.../bin`, give both binaries an exec-capable context before first run
(e.g. `chcon u:object_r:magisk_file:s0 <binary>`, or the label ZDT-D uses for its
own `bin/`). Confirm with `ls -Z`. This is device/root-manager specific.

## Two routing models

| | `mode = vpn` (default, verified) | `mode = socks` (experimental) |
|---|---|---|
| Tunnel exposure | TUN `zdtdqw0` created by qwdtt-cli | SOCKS5 on `127.0.0.1:1080`, WireGuard inside the transport's userspace netstack |
| Needs amneziawg-go/awg | yes | **no** |
| Routing profile | `myvpn` (UID → netd network) | `myproxy` (UID → iptables → t2s → SOCKS5) |
| Carries app UDP (QUIC, DNS) | yes | **no** — TCP CONNECT only |

`mode` in `qwdtt.conf` and `MODE` passed to the installer must agree: the config
decides what the transport does, the installer only picks which routing profile
is provisioned.

## Provision the profiles

Use the helper (root shell on the device):

```bash
sh install-zdtd-profiles.sh org.telegram.messenger              # vpn (default)
MODE=socks sh install-zdtd-profiles.sh org.telegram.messenger   # socks + myproxy
```

It writes, under `/data/adb/modules/ZDT-D/working_folder/`:

```
myprogram/active.json                      {"profiles":{"qwdtt":{"enabled":true}}}
myprogram/profile/qwdtt/setting.json       {"apps_mode": false}
myprogram/profile/qwdtt/command.txt        exec /data/adb/ZDT-D/bin/qwdtt-cli -config /data/adb/ZDT-D/etc/qwdtt.conf
myvpn/active.json                          {"profiles":{"qwdtt":{"enabled":true}}}
myvpn/profile/qwdtt/setting.json           {"tun":"zdtdqw0","dns":["8.8.8.8"],"cidr_mode":"auto","cidr":""}
myvpn/profile/qwdtt/app/uid/user_program   org.telegram.messenger
```

`active.json` is only written when it has no enabled profiles yet, so an existing
setup is never clobbered — otherwise enable "qwdtt" for both programs from the
ZDT-D app. The script refuses system/root entries in the app list.

`command.txt` uses `exec` so qwdtt-cli replaces the launcher shell and becomes the
process-group leader — see the stop semantics below.

## App-list rules (invariants)

- The myvpn app list is a **whitelist of apps to tunnel**. The 34 `excluded_apps`
  (Russian banking/gov/Yandex) must **stay direct** — do not add them here.
- **Never add root/system** (UID 0). Root in the bound range makes the transport
  tunnel itself and the stack deadlocks. The helper hard-rejects obvious cases.
- UID resolution shifts when apps are installed/removed/cloned or moved between
  Android users. Re-validate the binding after any such change — the package name
  stays the same but its UID may not.

## Start and verify

### Mind the TUN timing race

`myvpn` waits only ~20 s for `zdtdqw0` to appear, but a **cold** qwdtt-cli start
validates every hash first (~6 s per hash with VK throttling) before the tunnel
comes up — so with several hashes, starting both profiles together can time
`myvpn` out before the interface exists, and the UID binding is silently skipped.
Two ways to avoid it:

- **Shrink cold-start for the first bring-up:** use a single known-good
  `vk_hashes` entry so startup is well under 20 s, then restore the full set once
  it works.
- **Enable in sequence:** enable `myprogram` first, wait until `ip link show
  zdtdqw0` succeeds, then enable `myvpn` (its wait then completes instantly).

If `myvpn` did time out, just toggle it off/on once the tunnel is up — the binding
applies immediately.

### Start

Restart the ZDT-D daemon (or toggle both profiles in the app) so myprogram
launches qwdtt-cli.

```bash
ip link show zdtdqw0                                   # 1. interface exists
ip -o -4 addr show dev zdtdqw0                         # 2. carries 10.66.0.1/32
tail -f /data/adb/modules/ZDT-D/working_folder/myprogram/profile/qwdtt/log/program.log
#   expect: hashes validated -> transport started -> wg-turn.conf written ->
#           "wg: interface zdtdqw0 up"
cat /data/adb/modules/ZDT-D/working_folder/vpn_netd/applied.json   # 3. UID bound
# 4. From the test app, generate traffic and confirm egress is via the tunnel
#    (e.g. an IP-echo shows the VPS exit, and it works on the whitelist SIM where
#    a direct endpoint would be dropped).
```

The interface (`state/awg/amneziawg-go.log`) and transport logs live under the
supervisor's state dir and the myprogram log respectively.

## Stop semantics — why amneziawg-go is in-group

myprogram stops a profile with `kill -15 -- -<pgid>` (SIGTERM to the whole process
group), waits **300 ms**, then `kill -9 -- -<pgid>`. Consequences the supervisor is
built around:

- amneziawg-go is spawned **in qwdtt-cli's process group** (not a new session), so
  the group kill reaps it and its non-persistent TUN auto-removes — no orphaned
  interface, even if qwdtt-cli is SIGKILLed before its own teardown completes.
- On that SIGTERM qwdtt-cli also runs its normal teardown (STOP + SIGTERM to the
  transport, interface down), but must assume it may be killed at 300 ms.

**Known limitation (M4):** 300 ms is a tight window for the transport to release
its VK TURN allocations. It is best-effort; the relays time the allocation out
regardless. If cleaner TURN teardown proves necessary, options are a longer myvpn/
myprogram stop grace or letting the M5 program module own its own stop timing.

## Troubleshooting

- **No `zdtdqw0`:** check `program.log` — a failed hash validation (all hashes
  dead) or a failed `awg setconf`/`ip` will show there; qwdtt-cli restarts on a
  loop with backoff.
- **Interface up but app has no egress:** check `vpn_netd/applied.json` for the
  UID binding, and confirm the package resolved to a UID (reinstalls/clones shift
  it). Confirm the app is in the myvpn list and enabled.
- **`app/out/user_program` is empty and `zdtd.log` says "myvpn: some profiles were
  not applied":** myvpn skipped the profile *before* UID resolution ran, so the
  empty file is a symptom, not the cause. The usual reason is the **CIDR
  auto-detect race**: `wait_tun_link` returns as soon as the link exists, but
  qwdtt-cli assigns the address a moment later (link → `awg setconf` → `ip addr
  add`), and `cidr_mode=auto` used to inspect only once. Fixes: use
  `cidr_mode=manual` with the tunnel address (what the installer now writes by
  default), and/or run a ZDT-D build containing the myvpn CIDR-wait fix, which
  retries the inspect until the address appears. Look for the real reason with
  `grep -i "myvpn: profile" /data/adb/modules/ZDT-D/log/zdtd.log`.
- **Bound app hangs on a plain request, but `curl --interface zdtdqw0` works:**
  you are almost certainly testing from the **wrong UID**. `myvpn` binds the app's
  UID; a `su`/root shell (UID 0) is not bound (and must not be), so its traffic
  goes direct and the whitelist ISP drops it → hang. Run `id -u` — if it is `0`,
  test from the app's real UID instead (e.g. a normal, non-root Termux shell). A
  hang here is the exact signature of "this UID isn't on the tunnel," not a tunnel
  fault.
- **Immediate re-launch loops:** the transport exits non-zero (e.g. missing
  `-password`, unreachable VK). qwdtt-cli propagates that so ZDT-D restarts the
  whole stack; fix the cause in `qwdtt.conf`.
