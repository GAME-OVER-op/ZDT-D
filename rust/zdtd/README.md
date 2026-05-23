# ZDTD — ZDT-D daemon

`zdtd` is the Rust daemon used by the Android/Magisk module **ZDT-D**.

The daemon runs as **root**, prepares the module runtime layout, starts and stops network helper components, applies and restores iptables/ip6tables rules, exposes a local authenticated HTTP API for the Android application, and keeps runtime/status files in sync with the app UI.

This project is tightly coupled to the ZDT-D module layout on Android. It is **not** a generic Linux desktop/server daemon.

## Current scope

The daemon is responsible for:

- local API server on `127.0.0.1:1006`;
- token-based API protection;
- start/stop orchestration for supported programs;
- runtime state tracking for the Android app;
- creation of the minimal `working_folder` structure on startup;
- profile management for supported tools;
- app/UID assignment files and conflict checks;
- iptables/ip6tables baseline backup and restore;
- port collection/normalization to reduce collisions;
- proxy/app protection helpers such as `proxyInfo` and `blockedquic`;
- Android-specific integration such as boot wait, notifications, SELinux guard logic and package UID helpers.

## Important runtime warning

### sing-box VPN mode uses tun2socks

Native sing-box `tun` inbound mode is intentionally not used. The daemon keeps sing-box as a local mixed proxy backend and starts the shared `tun2socks` helper for VPN/netd routing.

The supported VPN chain is: app UID routing via Android netd -> external `tun2socks` TUN -> `socks5://127.0.0.1:<sing-box server port>` -> sing-box outbounds/rules.

UI can expose sing-box VPN only as the managed tun2socks mode, not as native sing-box TUN mode.

## Supported components

The daemon contains integration code for the following ZDT-D components.

### DPI / proxy helpers

- `dnscrypt`
- `nfqws`
- `nfqws2`
- `byedpi`
- `dpitunnel`
- `opera-proxy`

### Profile-based proxy/tunnel/VPN tools

- `sing-box` — T2S proxy mode and VPN mode via external `tun2socks`
- `wireproxy`
- `mihomo`
- `mieru`
- `tor`
- `openvpn`
- `amneziawg`
- `tun2socks`
- `myvpn`
- `myproxy`
- `myprogram`

### App-level protection / filtering helpers

- `proxyInfo`
- `blockedquic`

## Runtime environment

Expected environment:

- Android device;
- root access;
- Magisk/module-style filesystem layout;
- ZDT-D module installed under `/data/adb/modules/ZDT-D`;
- required helper binaries present under `/data/adb/modules/ZDT-D/bin`;
- daemon started from a root context.

The daemon expects Android tools and paths. Examples include:

- `iptables` / `ip6tables`;
- Android boot property checks;
- Android notification commands;
- package UID inspection;
- SELinux mode handling;
- fixed module paths under `/data/adb/modules/ZDT-D`.

## Fixed module paths

Main paths used by the daemon:

```text
/data/adb/modules/ZDT-D
/data/adb/modules/ZDT-D/bin
/data/adb/modules/ZDT-D/setting
/data/adb/modules/ZDT-D/api
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
/data/adb/modules/ZDT-D/working_folder/flag.sha256
```

`flag.sha256` is intentionally shared between assignment-related modules. Do not create separate SHA tracker files for `blockedquic`, `proxyInfo`, or other app-list users unless the daemon logic is intentionally redesigned.

## Minimal working layout

On startup the daemon creates the minimal runtime structure expected by the Android app.

Main runtime roots:

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

Profile-capable programs store profiles under:

```text
working_folder/<program>/profile/<profile_name>
```

Common profile files/directories may include:

```text
active.json
profile/<name>/setting.json
profile/<name>/config.json
profile/<name>/config.yaml
profile/<name>/client.ovpn
profile/<name>/app/uid/user_program
profile/<name>/app/out/user_program
profile/<name>/log
profile/<name>/work
```

The exact profile layout depends on the program.

## High-level architecture

```text
src/main.rs
  -> src/daemon.rs
      -> src/api.rs
      -> src/runtime.rs
      -> src/runtime_state.rs
      -> src/api_status.rs
      -> src/settings.rs
      -> src/stats.rs
      -> src/stop.rs
      -> src/proxyinfo.rs
      -> src/blockedquic.rs
      -> src/vpn_netd.rs
      -> src/iptables/*
      -> src/programs/*
      -> src/android/*
```

## Source tree overview

```text
src/
  main.rs              Entry point. Checks root and starts daemon mode.
  daemon.rs            Shared daemon state, async start/stop, API lifecycle.
  api.rs               Local HTTP API server, request parsing and route handling.
  api_status.rs        Status file used by the app while start/stop is in progress.
  runtime.rs           Full service startup/shutdown orchestration.
  runtime_state.rs     Runtime state persistence and adoption helpers.
  settings.rs          Fixed paths, start settings, API token/info files, minimal layout.
  stats.rs             Runtime process/status collection.
  stop.rs              Best-effort service/process stop logic.
  logging.rs           Log initialization and helpers.
  shell.rs             Shell command wrappers.
  ports.rs             Port collection and normalization helpers.
  protector.rs         Runtime protection helper.
  proxyinfo.rs         proxyInfo app routing/protection rules.
  blockedquic.rs       Per-app QUIC blocking helper.
  scan_detector.rs     Scan/protection related helper.
  screen.rs            Screen/status helper.
  vpn_netd.rs          Android netd/TUN routing helper for VPN-like profiles.
  xtables_lock.rs      iptables lock retry handling.

src/android/
  boot.rs              Android boot completion wait.
  notification.rs      Android notification integration.
  pkg_uid.rs           Package-to-UID helpers.
  selinux.rs           SELinux guard helpers.
  wake_lock.rs         Wake lock helper.

src/iptables/
  caps.rs              iptables capability checks.
  iptables_v1.rs       First-generation iptables rules.
  iptables_v2.rs       Second-generation iptables rules.
  iptables_iplist.rs   IP list rule helpers.
  iptables_port.rs     Port rule helpers.
  port_filter.rs       Port filter helpers.
  mod.rs               iptables module exports.

src/programs/
  dnscrypt.rs
  nfqws.rs
  nfqws2.rs
  nfqws_filters.rs
  byedpi.rs
  dpitunnel.rs
  operaproxy.rs
  singbox.rs
  wireproxy.rs
  tor.rs
  openvpn.rs
  amneziawg.rs
  tun2socks.rs
  myvpn.rs
  myproxy.rs
  myprogram.rs
  mihomo.rs
  mieru.rs
  mod.rs
```

## Startup flow

When the app requests start or autostart is enabled, the daemon performs a guarded full startup.

Simplified flow:

1. Wait for Android boot completion when needed.
2. Ensure required module/API/settings directories exist.
3. Ensure minimal `working_folder` program layouts exist.
4. Load `start.json` and API settings.
5. Create or load API token.
6. Write `/data/adb/modules/ZDT-D/api/info.json`.
7. Prepare runtime status for the app.
8. Create iptables/ip6tables baseline backups if missing.
9. Restore baseline firewall rules before applying new rules.
10. Normalize ports and collect configured ports from supported components.
11. Start enabled programs/profiles.
12. Apply routing, app assignment and protection rules.
13. Refresh runtime status and expose the result through `/api/status`.

`dnscrypt` is started early because other components may depend on DNS availability.

## Stop flow

Simplified stop flow:

1. Mark stop as in progress.
2. Stop known services and helper processes.
3. Clear program-specific runtime rules.
4. Restore iptables/ip6tables baseline rules.
5. Restore Android/network settings where the daemon changed them.
6. Clear runtime state/status flags.
7. Mark services as stopped.

Stop is best-effort. The daemon tries to restore firewall state even if a component stop command fails.

## API

The HTTP API is local-only and intended for the Android app.

```text
Bind: 127.0.0.1:1006
Token file: /data/adb/modules/ZDT-D/api/token
Info file:  /data/adb/modules/ZDT-D/api/info.json
```

Authentication is required for `/api/*` routes.

Supported auth headers:

```text
x-api-key: <token>
Authorization: Bearer <token>
```

Unauthenticated requests intentionally receive an empty `404` response so the API is not easily discoverable by local clients.

### Main API groups

Core daemon routes:

```text
GET  /api/status
POST /api/start
POST /api/stop
GET  /api/setting
POST /api/setting
GET  /api/programs
```

Safe file helpers inside the module path:

```text
POST /api/fs/read_text
POST /api/fs/write_text
POST /api/fs/list_dir
```

Profile/program routes:

```text
/api/programs/<program>/...
```

Strategic file routes:

```text
/api/strategic/...
/api/strategicvar/...
```

App assignment overview:

```text
GET /api/apps/assignments
```

proxyInfo routes:

```text
GET  /api/proxyinfo
GET  /api/proxyinfo/enabled
PUT  /api/proxyinfo/enabled
GET  /api/proxyinfo/apps
PUT  /api/proxyinfo/apps
POST /api/proxyinfo/save
POST /api/proxyinfo/apply
```

blockedquic routes:

```text
GET  /api/blockedquic
GET  /api/blockedquic/enabled
PUT  /api/blockedquic/enabled
GET  /api/blockedquic/apps
PUT  /api/blockedquic/apps
POST /api/blockedquic/save
POST /api/blockedquic/apply
```

The API is primarily app-facing. Treat routes and JSON payloads as internal ZDT-D contracts, not as a public stable API.

## Program notes

### dnscrypt

Manages the DNSCrypt runtime configuration and starts DNS early in the runtime sequence. Its default config path is under:

```text
working_folder/dnscrypt/setting/dnscrypt-proxy.toml
```

### nfqws / nfqws2

Profile-style DPI bypass components using ZDT-D strategic files and iptables rules. `nfqws2` has additional strategic/Lua-related support.

### byedpi / dpitunnel

DPI/helper components started from the module runtime with program-specific configs under `working_folder`.

### opera-proxy

Uses `opera-proxy` from the module `bin` directory and stores settings under:

```text
working_folder/operaproxy
```

It has support files for SNI, server selection, API proxy list, bootstrap DNS, app UID/out lists, and optional byedpi integration files.

### sing-box

Profile-based component stored under:

```text
working_folder/singbox/profile
```

Current daemon behavior:

```text
Supported: T2S proxy mode
Supported: VPN mode via external tun2socks
```

Native sing-box TUN mode is not used. VPN mode is implemented as `sing-box mixed inbound + tun2socks + vpn_netd`, similar to the managed Mihomo path.

### wireproxy

Profile-based WireGuard proxy component stored under:

```text
working_folder/wireproxy/profile
```

### tor

Uses `torproxy` and `lyrebird` binaries from the module `bin` directory. Default files are created under:

```text
working_folder/tor
working_folder/tor/torrc
working_folder/tor/setting.json
```

Default `torrc` includes a `DataDirectory`, `SocksPort`, logging to stdout, bridge usage and lyrebird transport plugin configuration.

### openvpn

Profile-based OpenVPN component. Profiles are stored under:

```text
working_folder/openvpn/profile/<profile>
```

Expected client config file:

```text
client.ovpn
```

The daemon validates TUN naming and cross-profile TUN conflicts before enabling profiles.

### amneziawg

Profile-based AmneziaWG component using:

```text
/data/adb/modules/ZDT-D/bin/amneziawg-go
/data/adb/modules/ZDT-D/bin/awg
```

Profiles are stored under:

```text
working_folder/amneziawg/profile/<profile>
```

### tun2socks

Profile-based `tun2socks` integration. Profiles are stored under:

```text
working_folder/tun2socks/profile/<profile>
```

### mihomo

Profile-based Mihomo integration using:

```text
/data/adb/modules/ZDT-D/bin/mihomo
/data/adb/modules/ZDT-D/bin/tun2socks
```

Profiles are stored under:

```text
working_folder/mihomo/profile/<profile>
```

Runtime configs are generated inside the profile runtime/work layout.

### mieru

Profile-based Mieru integration using:

```text
/data/adb/modules/ZDT-D/bin/mieru
/data/adb/modules/ZDT-D/bin/tun2socks
```

Profiles are stored under:

```text
working_folder/mieru/profile/<profile>
```

### myproxy / myprogram / myvpn

Custom/user-defined integration layers. They are profile-based and store user configuration under their own `working_folder/<program>` roots.

## App assignment files

Several components use package/UID assignment lists.

Common paths include:

```text
app/uid/user_program
app/out/user_program
app/uid/mobile_program
app/uid/wifi_program
app/out/mobile_program
app/out/wifi_program
```

The daemon validates app assignment content and tries to prevent incompatible cross-program assignments. This is especially important for components that alter per-app routing or firewall behavior.

## proxyInfo

`proxyInfo` stores its state under:

```text
working_folder/proxyInfo
```

Important files:

```text
enabled.json
uid_program
out_program
```

When enabled, `proxyInfo` applies protection/routing rules for selected applications. It may also affect IPv6 behavior for selected UIDs, depending on the active rule set.

## blockedquic

`blockedquic` stores its state under:

```text
working_folder/blockedquic
```

Important files:

```text
enabled.json
uid_program
out_program
```

When enabled and applied, it rebuilds app lists and applies QUIC-blocking rules for selected applications.

## iptables baseline handling

The daemon keeps baseline firewall backups in:

```text
setting/iptables_backup.rules
setting/ip6tables_backup.rules
```

Before starting runtime rules, the daemon tries to restore the baseline. On stop, it restores the baseline again.

This design reduces rule accumulation after repeated starts/stops or failed runtime sessions.

## Runtime status

The Android app can read daemon state through `/api/status` and status files in the API directory.

The daemon tracks:

- full start in progress;
- stop in progress;
- services running;
- partial runtime state;
- degraded/cached status;
- process-level status where available;
- app-facing UI status.

## Building

Install a Rust toolchain suitable for the target platform, then build:

```bash
cargo build --release
```

Output:

```text
target/release/zdtd
```

For Android deployment, the binary must be built for the correct target/ABI and placed into the ZDT-D module environment expected by the rest of the project.

## Running

Run the daemon from a root context:

```bash
/path/to/zdtd
```

Running without root is rejected by the daemon.

The daemon is expected to run inside the installed ZDT-D module environment, not from an arbitrary desktop directory.

## Development notes

- `api.rs` is intentionally central right now, but it is large and should be edited carefully.
- The HTTP server is implemented manually on top of `TcpListener`/`TcpStream`.
- API route matching is path-based and app-facing.
- Many operations are best-effort because Android firmware and root environments differ between devices.
- Be careful when adding new programs: update runtime start/stop, stats, port collection, app assignment conflict checks, API exposure and minimal layout creation together.
- Expose sing-box VPN only as managed tun2socks mode; do not route it through native sing-box TUN inbound.
- Keep module paths consistent with `/data/adb/modules/ZDT-D` unless the daemon is intentionally refactored for configurable roots.

## Safety notes for contributors

- Do not expose the API token through `info.json`; only the token file path should be written there.
- Do not replace the empty unauthenticated `404` behavior unless the local API exposure model is redesigned.
- Do not create independent SHA flag files for modules that share app assignment state.
- Do not enable native sing-box TUN mode from UI-only changes. The supported sing-box VPN path is daemon-managed tun2socks mode.
- When adding profile-based VPN/TUN tools, validate TUN name conflicts across existing profile systems.
- When adding iptables rules, make sure stop/restore paths are also updated.
