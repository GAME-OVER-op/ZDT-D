# zdtd — ZDT-D daemon

`zdtd` is the central Rust daemon of ZDT-D. It is the component that turns the
Android application settings and module files into a running root networking
runtime.

The daemon is designed for the ZDT-D Android root module layout. It is not a
generic desktop Linux service and it should not be treated as a standalone VPN
client. It expects Android, root access, the module filesystem, Android `netd`,
`iptables` / `ip6tables`, and helper binaries installed by the module.

## Responsibilities

`zdtd` is responsible for:

- exposing the local authenticated API used by the Android app;
- preparing the runtime directory layout under `/data/adb/modules/ZDT-D`;
- reading global settings and per-program profile settings;
- resolving Android package names into Linux UIDs;
- validating enabled app-list runtime overlaps across enabled profiles;
- starting and stopping supported helper programs;
- applying and removing `iptables` / `ip6tables` rules;
- applying NFQUEUE routing for zapret-style engines;
- applying Android `netd` UID bindings for TUN/VPN-style profiles;
- writing status files, runtime state and user-facing logs;
- providing protection helpers such as `proxyInfo` and `blockedquic`;
- coordinating boot-time startup and graceful shutdown.

## Runtime assumptions

Expected environment:

```text
Android device
root access
ZDT-D module installed under /data/adb/modules/ZDT-D
helper binaries under /data/adb/modules/ZDT-D/bin
settings and profile data under /data/adb/modules/ZDT-D/working_folder
```

The daemon uses Android-specific tools and paths. Examples include:

- `getprop sys.boot_completed`;
- Android package/UID discovery;
- Android `netd` commands;
- `iptables` and `ip6tables`;
- `/proc` process inspection;
- module paths under `/data/adb/modules/ZDT-D`;
- optional Android notification commands.

## Important fixed paths

Main module paths:

```text
/data/adb/modules/ZDT-D
/data/adb/modules/ZDT-D/bin
/data/adb/modules/ZDT-D/setting
/data/adb/modules/ZDT-D/api
/data/adb/modules/ZDT-D/log
/data/adb/modules/ZDT-D/working_folder
/data/adb/modules/ZDT-D/strategic
```

Important files:

```text
/data/adb/modules/ZDT-D/setting/start.json
/data/adb/modules/ZDT-D/setting/setting.json
/data/adb/modules/ZDT-D/setting/iptables_backup.rules
/data/adb/modules/ZDT-D/setting/ip6tables_backup.rules
/data/adb/modules/ZDT-D/api/token
/data/adb/modules/ZDT-D/api/info.json
/data/adb/modules/ZDT-D/api/status.json
/data/adb/modules/ZDT-D/log/zdtd.log
/data/adb/modules/ZDT-D/working_folder/flag.sha256
```

`flag.sha256` is a shared assignment-change marker. It is intentionally shared
between components that depend on app-list state. Do not split it into separate
per-feature checksum files unless the daemon assignment model is redesigned.

## Command line

```bash
zdtd
zdtd --config /path/to/config.json
zdtd --help
```

Without arguments the daemon uses built-in defaults for the ZDT-D module.
`--config` currently provides optional JSON configuration mainly for logging and
runtime-level settings.

Example config shape:

```json
{
  "base_dir": "/data/adb/modules/ZDT-D/working_folder",
  "log": {
    "level": "info",
    "file": "/data/adb/modules/ZDT-D/log/zdtd.log"
  },
  "runtime": {
    "mode": "daemon",
    "tick_ms": 1000
  },
  "notifications": {
    "enabled": false
  }
}
```

## Local API

The Android app talks to `zdtd` through the local API server:

```text
http://127.0.0.1:1006/api/...
```

The API is intentionally local-only. Requests outside `/api/*` return an empty
404. API requests require the token stored in:

```text
/data/adb/modules/ZDT-D/api/token
```

The app sends the token through one of these headers:

```text
Authorization: Bearer <token>
X-Api-Key: <token>
```

Major API groups include:

- `/api/status` — daemon/service status;
- `/api/start` and `/api/stop` — runtime lifecycle;
- `/api/setting` — global daemon/module settings;
- `/api/programs` and `/api/programs/...` — program/profile management;
- `/api/apps/assignments` — app-list ownership view across every program and profile, enabled or not;
- `/api/blockedquic/...` — QUIC blocking helper;
- `/api/proxyinfo/...` — local proxy protection helper;
- `/api/strategic/...` — strategic files;
- `/api/strategicvar/...` — strategy application helpers;
- `/api/energy-saver/...` — profile/process energy saver settings;
- `/api/fs/...` — restricted text file read/write helpers used by the app.

## Startup lifecycle

A typical startup flow is:

1. Start as root from the module service script or app action.
2. Initialize logging.
3. Ensure module/API/settings directories exist.
4. Create or normalize token, status and info files.
5. Ensure the minimal `working_folder` layout exists.
6. Wait for Android boot readiness when running from boot.
7. Wait for package manager and Android `netd` readiness.
8. Read global settings and enabled program/profile state.
9. Resolve selected packages into UIDs.
10. Validate enabled app-list runtime overlaps with the slot rule.
11. Backup baseline firewall state if needed.
12. Start enabled programs and helper binaries.
13. Apply routing rules: NFQUEUE, redirects, port filters and `netd` bindings.
14. Write runtime state and expose status to the app.

Startup is best-effort at the profile level. A failed profile should not
necessarily hide every other successfully started profile. The app can display a
partial state when some components fail.

## Stop lifecycle

A typical stop flow is:

1. Mark runtime as stopping.
2. Stop profile programs and helper processes.
3. Remove Android `netd` VPN bindings created by ZDT-D.
4. Remove or restore ZDT-D firewall rules.
5. Restore baseline firewall state where supported.
6. Clear runtime snapshots for stopped profiles.
7. Mark status as stopped.

Shutdown code is intentionally conservative: it should prefer cleanup and state
consistency over aggressive process killing that could break unrelated system
networking.

## Minimal working layout

On startup, the daemon creates the directories and default state files expected
by the Android UI. Main roots include:

```text
working_folder/byedpi
working_folder/dpitunnel
working_folder/nfqws
working_folder/nfqws2
working_folder/singbox
working_folder/wireproxy
working_folder/myproxy
working_folder/myprogram
working_folder/openvpn
working_folder/amneziawg
working_folder/tun2socks
working_folder/myvpn
working_folder/mihomo
working_folder/mieru
working_folder/dnscrypt
working_folder/operaproxy
working_folder/proxyInfo
working_folder/blockedquic
working_folder/tor
```

Profile-based programs use:

```text
working_folder/<program>/active.json
working_folder/<program>/profile/<profile_name>/setting.json
working_folder/<program>/profile/<profile_name>/app/uid/user_program
working_folder/<program>/profile/<profile_name>/app/out/user_program
working_folder/<program>/profile/<profile_name>/log
working_folder/<program>/profile/<profile_name>/work
```

The exact files vary by program. Some profiles also use `config.json`,
`config.yaml`, `client.ovpn`, generated configs, runtime state files, local
ports and external tool working directories.

## Routing models managed by zdtd

### NFQUEUE model

Used by `nfqws` and `nfqws2`.

```text
selected app UID -> iptables mangle rule -> NFQUEUE -> userspace engine
```

The daemon prepares UID rules, queue numbers, optional port filters and cleanup
logic. The external engine performs packet-level DPI-bypass behavior.

### Transparent local redirect model

Used by proxy pipelines such as `sing-box`, `wireproxy`, `myproxy`, `opera-proxy`
and Tor-related scenarios.

```text
selected app UID -> iptables nat REDIRECT -> local helper port -> upstream proxy
```

`t2s` is commonly used to bridge transparent TCP redirection to a SOCKS5 backend.

### Android netd / TUN binding model

Used by OpenVPN, tun2socks, myvpn and sing-box VPN mode.

```text
program creates or exposes TUN -> zdtd prepares profile -> Android netd binds selected UIDs
```

ZDT-D uses Android `netd` to bind selected application UIDs to a TUN network. It
does not rely on Android `VpnService` as the primary routing engine.

### Helper-only model

Some components do not own the main traffic path but modify or observe behavior:

- `blockedquic` blocks UDP/443 for selected apps;
- `proxyInfo` protects local service ports and reports probes;
- energy saver freezes/stops idle profiles according to settings;
- diagnostic binaries are launched on demand by the app.

## Program orchestration

The daemon has integration modules for:

- `dnscrypt`;
- `nfqws`;
- `nfqws2`;
- `byedpi`;
- `dpitunnel`;
- `operaproxy`;
- `singbox`;
- `wireproxy`;
- `tor`;
- `openvpn`;
- `amneziawg`;
- `tun2socks`;
- `myvpn`;
- `myproxy`;
- `myprogram`;
- `mihomo`;
- `mieru`.

Each program module is responsible for normalizing settings, reading active
profiles, preparing command lines, launching helper processes, collecting ports,
parsing app lists and producing cleanup actions.

## sing-box VPN mode warning

Native sing-box `tun` inbound mode is intentionally not used as the ZDT-D VPN
routing model. In ZDT-D, sing-box is kept as a local proxy backend, and the
shared external `tun2socks` helper creates the TUN interface for VPN/netd mode.

Supported chain:

```text
app UID -> Android netd VPN binding -> tun2socks TUN -> socks5://127.0.0.1:<sing-box port> -> sing-box outbounds/rules
```

UI should expose sing-box VPN as managed tun2socks mode, not as native sing-box
TUN mode.

## App-list and conflict model

ZDT-D routes by Linux UID. The UI stores package names; the daemon resolves them
into UID files. These files are used by firewall rules and Android `netd`.

Assignment and runtime application are separate. The same package may be assigned
to any number of profiles and programs, including disabled ones, and every
assignment is kept. What the daemon refuses is an enabled runtime overlap: two
different enabled app lists claiming the same package at the same time would
drive that traffic twice.

Comparison is slot-aware and uses the on-disk app-list files:

```text
common  app/uid/user_program    conflicts with the same package in any slot
mobile  app/uid/mobile_program  conflicts with mobile only
wifi    app/uid/wifi_program    conflicts with wifi only
```

`mobile` and `wifi` lists are compatible with each other because they are applied
under different interface rules.

An enabled overlap is rejected before a cold start, before adopting an already
running runtime, and before a live hot-apply. Rejected adoption stops the old
runtime so it cannot stay active behind an OFF/error API state. Cold-start and
adoption failures are exposed on Home through daemon `last_error`.

A refused hot-apply does not discard the assignment: the new list is saved and
the apply status reports the rejected state. Its `error` field names the
conflicting profile pairs and overlapping package IDs; the app displays that
field in the runtime-apply indicator.

`myprogram` app lists do not claim routing while `apps_mode` is off for the
profile. They are stored, but stay out of the runtime overlap comparison.

Storage and parse failures fail closed: an enabled list or its enablement state
that cannot be read is reported as an error, never treated as conflict-free.

Some helper features can coexist with a main routing profile and keep their own
rules. `blockedquic` can be used alongside other routing modes when it only blocks
UDP/443 for its own app list; `proxyInfo` has a separate port-protection rule that
refuses packages already used by other ZDT-D program lists.

## Source tree overview

```text
src/main.rs                 Entry point and root check
src/cli.rs                  Minimal CLI parsing
src/config.rs               Optional daemon config
src/daemon.rs               API/runtime daemon lifecycle
src/api.rs                  Local HTTP API implementation
src/api_status.rs           API status persistence
src/runtime.rs              Main start/stop orchestration
src/runtime_state.rs        Runtime state files and adoption helpers
src/settings.rs             Fixed paths, tokens, start/global settings, layout creation
src/stats.rs                Process/runtime status collection
src/stop.rs                 Best-effort stop logic
src/logging.rs              Log setup and user-facing log helpers
src/shell.rs                Shell command wrappers
src/ports.rs                Port normalization and collision helpers
src/protector.rs            Runtime protection helper
src/proxyinfo.rs            Local proxy protection rules
src/blockedquic.rs          Per-app QUIC blocking
src/energy_saver.rs         Profile/process energy saver
src/vpn_netd.rs             Android netd VPN binding
src/vpn_tether.rs           Tether/VPN profile state helper
src/iptables/*              Firewall, redirect, NFQUEUE and port-filter logic
src/android/*               Android boot, UID, SELinux, sysctl, notification helpers
src/programs/*              Per-program integration modules
```

## Developer notes

Be careful when changing:

- service boot logic;
- firewall backup/restore logic;
- NFQUEUE queue/rule generation;
- Android `netd` binding and cleanup;
- enabled app-list overlap validation and its slot rule;
- profile runtime state paths;
- token/API authentication behavior;
- module service scripts that start the daemon.

Small UI or documentation changes should not change daemon routing behavior.
Routing changes should be reviewed as compatibility-sensitive changes because
wrong cleanup or wrong UID routing can break networking outside the selected
profiles.
