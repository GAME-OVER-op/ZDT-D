# M3 — running qwdtt-cli as a ZDT-D myprogram + myvpn stack

M3 wires the supervisor into ZDT-D through the two generic profiles, then runs it
end-to-end on the whitelisted SIM with a test app. No first-class program module
yet — that is M5.

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

| What | Where | Source |
|---|---|---|
| `qwdtt-cli` | `/data/adb/ZDT-D/bin/qwdtt-cli` | `make arm64`, or the `qwdtt-cli-arm64` CI artifact |
| `qwdtt-transport` | `/data/adb/ZDT-D/bin/qwdtt-transport` | `upstream/qwdtt/fetch-and-build.sh`, or the `qwdtt-transport-arm64` CI artifact |
| `amneziawg-go`, `awg` | `/data/adb/modules/ZDT-D/bin/` | shipped with ZDT-D (see note) |
| `qwdtt.conf` | `/data/adb/ZDT-D/etc/qwdtt.conf` | from `qwdtt.example.conf`, filled in, `chmod 600` |

```bash
install -m 0755 qwdtt-cli        /data/adb/ZDT-D/bin/qwdtt-cli
install -m 0755 qwdtt-transport  /data/adb/ZDT-D/bin/qwdtt-transport
install -m 0600 qwdtt.conf       /data/adb/ZDT-D/etc/qwdtt.conf
```

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

## Provision the profiles

Use the helper (root shell on the device):

```bash
sh install-zdtd-profiles.sh org.telegram.messenger      # your test app package
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
- **Immediate re-launch loops:** the transport exits non-zero (e.g. missing
  `-password`, unreachable VK). qwdtt-cli propagates that so ZDT-D restarts the
  whole stack; fix the cause in `qwdtt.conf`.
