# ZDT-D Programs and Components

This document is the technical map of ZDT-D programs, helpers and routing
components. It explains what each component is, how it is used, which routing
model it belongs to and where its runtime data normally lives.

The root README files describe the project at a high level. This document is for
technical understanding and development.

## System overview

ZDT-D is built from four major layers:

```text
Android app
  -> local daemon API
    -> zdtd root daemon
      -> firewall/netd/runtime builders
        -> external and internal network programs
```

The Android app stores settings and calls the daemon. The daemon runs as root,
starts helper programs, resolves package names into Linux UIDs, applies firewall
rules and creates Android `netd` UID bindings where required.

ZDT-D routes selected applications by UID. The app list starts as Android package
names in the UI, then the daemon resolves those packages into UID files used by
routing rules.

## Routing models

### NFQUEUE packet processing

```text
selected UID -> iptables mangle -> NFQUEUE -> userspace DPI engine
```

Used by:

- `nfqws`;
- `nfqws2`.

This model is used for packet-level DPI bypass strategies. The daemon owns the
UID/NFQUEUE rules; the external engine owns packet processing behavior.

### Transparent local redirect

```text
selected UID -> iptables nat REDIRECT -> local listener -> proxy/helper backend
```

Used by local proxy pipelines such as:

- sing-box proxy mode;
- wireproxy;
- Tor;
- opera-proxy;
- myproxy;
- some myprogram scenarios.

`t2s` is commonly used to convert transparent TCP traffic into SOCKS5 upstream
connections.

### Android netd / TUN binding

```text
TUN interface -> zdtd VPN profile -> Android netd network -> selected UIDs
```

Used by:

- OpenVPN;
- tun2socks;
- myvpn;
- sing-box VPN mode through external tun2socks;
- future TUN/VPN style integrations.

This does not use Android `VpnService` as the main engine. ZDT-D uses root and
Android `netd` to bind selected app UIDs to a TUN network.

### DNS local resolver

```text
local DNS component -> optional routing/firewall integration -> selected scenarios
```

Used by dnscrypt-proxy and DNS-related settings. DNS behavior depends heavily on
Android ROM behavior, Private DNS settings, IPv6 and firewall support.

### Helper/protection model

Some features do not own the main traffic route:

- `blockedquic` blocks UDP/443 for selected apps;
- `proxyInfo` protects local proxy ports and reports suspicious access;
- energy saver manages idle profiles/processes;
- diagnostics run temporary tests.

These can often coexist with a main routing profile.

## Core components

### Android application

The Android app is the controller UI.

It provides:

- start/stop controls;
- profile editors;
- app picker and app-list management;
- logs and status screens;
- diagnostics screens;
- module installation/update helpers;
- settings, widgets and Quick Settings tile.

It communicates with `zdtd` through `http://127.0.0.1:1006/api/...` using the API
token stored in the module directory.

Detailed documentation: `application/README.md`.

### `zdtd` daemon

`zdtd` is the central Rust daemon. It runs as root and owns runtime state,
program orchestration, firewall rules, app UID resolution, Android `netd` binding
and the local API.

Detailed documentation: `rust/zdtd/README.md`.

### Module scripts

The module template contains installation/service/uninstall scripts used by the
root module environment. They install files, start services and provide module
lifecycle integration for Magisk/KernelSU/APatch-style environments.

Important files:

```text
module_template/customize.sh
module_template/service.sh
module_template/uninstall.sh
module_template/verify.sh
```

Do not change service/build/boot logic casually. It affects how the daemon is
started on real devices.

### UID app parser

The app stores package names. The daemon resolves them into Linux UIDs and writes
UID lists for routing components.

UID lists are used by:

- `iptables` owner rules;
- NFQUEUE rules;
- transparent redirects;
- Android `netd` UID bindings;
- conflict validation;
- protection helpers.

If packages are installed/removed, cloned, moved between Android users or hidden
by the ROM, UID resolution can change.

### `vpn_netd_builder`

Internal daemon logic for creating Android `netd` VPN networks and assigning UID
ranges to them.

It receives profile data such as:

- owner program;
- profile name;
- TUN interface name;
- generated netId;
- IPv4 CIDR/gateway;
- DNS list;
- UID ranges.

It does not launch VPN clients. It only binds selected app UIDs to a TUN network
created or exposed by another component.

## Rust binaries

### `zdtd`

Central daemon. See `rust/zdtd/README.md`.

### `t2s`

Transparent TCP to SOCKS5 helper. It receives redirected TCP traffic and sends it
to SOCKS5 backends. It supports multiple backends, backend health, balance and
priority modes, optional web UI/API and runtime connection control.

Used by:

- sing-box proxy mode;
- wireproxy;
- Tor;
- opera-proxy;
- myproxy;
- selected myprogram scenarios.

Detailed documentation: `rust/T2s/README.md`.

### `dpi-detector`

Native diagnostic helper launched by the Android app. It checks DNS integrity,
resolver availability, domain/TLS/HTTP symptoms, TCP payload thresholds,
whitelist SNI behavior and Telegram connectivity.

Detailed documentation: `rust/dpi-detector/README.md`.

### `nfqws-tester`

Native diagnostic helper for temporary `nfqws`/`nfqws2` strategy testing. It can
list strategy files, start a selected strategy with temporary NFQUEUE rules,
report status/usage and clean up.

Detailed documentation: `rust/nfqws-tester/README.md`.

## DPI and NFQUEUE engines

### `nfqws`

`nfqws` is a userspace packet processor from the zapret project.

How ZDT-D uses it:

- reads enabled profiles from `working_folder/nfqws`;
- resolves selected apps into UIDs;
- applies `mangle` table NFQUEUE rules;
- starts `nfqws` with the selected strategy/config;
- tracks process/log state;
- cleans rules and process state on stop.

Routing model:

```text
selected UID -> iptables mangle -> NFQUEUE -> nfqws
```

Purpose:

- DPI circumvention;
- packet manipulation;
- zapret strategy execution;
- per-app NFQUEUE routing.

Upstream:

- https://github.com/bol-van/zapret

### `nfqws2`

`nfqws2` is a zapret2-style NFQUEUE engine with Lua-based logic.

Routing model:

```text
selected UID -> iptables mangle -> NFQUEUE -> nfqws2 + Lua/scripts
```

Purpose:

- extended DPI bypass logic;
- Lua strategy support;
- flexible packet processing.

Upstream:

- https://github.com/bol-van/zapret2

### byedpi

byedpi is a DPI circumvention utility.

ZDT-D runs it as a local engine and routes selected application traffic into the
configured processing path.

Routing model:

```text
selected UID -> local DPI/proxy pipeline -> byedpi
```

Purpose:

- alternative DPI bypass strategy;
- profile-based bypass setups;
- compatibility with networks where NFQUEUE strategies are not enough.

Upstream:

- https://github.com/hufrea/byedpi

### DPITunnel-cli

DPITunnel-cli is a censorship/DPI bypass tool. It is not a VPN by itself.

ZDT-D starts it as a local profile engine and routes selected apps through the
configured proxy/transparent pipeline.

Purpose:

- desync-based DPI bypass;
- HTTP or transparent proxy scenarios;
- per-profile bypass tuning.

Upstream:

- https://github.com/nomoresat/DPITunnel-cli

## DNS component

### dnscrypt-proxy

dnscrypt-proxy is a local DNS proxy with DNSCrypt, DNS-over-HTTPS and related
encrypted DNS support.

ZDT-D can run it locally and use it for DNS control in supported scenarios.

Purpose:

- encrypted DNS;
- local resolver control;
- consistent DNS behavior for selected routing modes.

Notes:

- Android Private DNS can affect behavior;
- ROM DNS handling can vary;
- IPv6 support and firewall capabilities matter;
- DNS redirection must be reviewed carefully to avoid breaking connectivity.

Upstream:

- https://github.com/DNSCrypt/dnscrypt-proxy

## Proxy and local pipeline engines

### opera-proxy

opera-proxy is a client for Opera proxy endpoints. It exposes local proxy
services and forwards traffic through Opera infrastructure.

ZDT-D can combine opera-proxy with local helper routing and selected app lists.

Routing model:

```text
selected UID -> local redirect / t2s -> opera-proxy
```

Upstream:

- https://github.com/Snawoot/opera-proxy

### sing-box

sing-box is a universal proxy platform.

ZDT-D supports sing-box as a profile-based proxy engine. In proxy mode, selected
apps are routed to local sing-box inbounds or a helper pipeline.

Routing model examples:

```text
selected UID -> transparent redirect -> sing-box local inbound
selected UID -> redirect -> t2s -> sing-box mixed/SOCKS inbound
```

Important VPN-mode note:

Native sing-box TUN inbound is not the supported ZDT-D VPN model. ZDT-D keeps
sing-box as a local proxy backend and uses external tun2socks for the TUN/netd
chain:

```text
selected UID -> Android netd -> tun2socks TUN -> socks5://127.0.0.1:<sing-box port> -> sing-box
```

Upstream:

- https://github.com/SagerNet/sing-box
- https://sing-box.sagernet.org

### wireproxy

wireproxy connects to a WireGuard peer and exposes local SOCKS5/HTTP proxy or
tunnel endpoints.

In ZDT-D, wireproxy is treated as a proxy backend, not as a system WireGuard
interface manager.

Routing model:

```text
selected UID -> local redirect / t2s -> wireproxy endpoint
```

Upstream:

- https://github.com/windtf/wireproxy

### Tor

Tor is used as a local SOCKS pipeline for selected apps. ZDT-D reads Tor settings,
validates bridge requirements and starts Tor with the configured `torrc`.

Routing model:

```text
selected UID -> t2s / local SOCKS pipeline -> Tor SOCKS port
```

Purpose:

- selective app routing through Tor;
- bridge-based bootstrap;
- pluggable transport support through lyrebird.

Upstream:

- https://gitlab.com/torproject/tor
- https://www.torproject.org

### lyrebird

lyrebird is a Tor pluggable transport helper for bridge transports such as obfs4,
Snowflake/WebTunnel-style configurations where supported.

ZDT-D uses it as Tor's transport plugin, not as an independent routing program.

Upstream:

- https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird

### mihomo

mihomo is a proxy platform derived from the Clash ecosystem.

ZDT-D treats mihomo as a profile-based local proxy engine. Profiles store config
and selected app lists; daemon code starts mihomo and routes selected apps to the
local pipeline.

Routing model:

```text
selected UID -> transparent redirect / t2s -> mihomo local inbound
```

Purpose:

- rule-based proxy profiles;
- subscription/config based proxy setups;
- selected app routing through local mihomo inbounds.

Upstream:

- https://github.com/MetaCubeX/mihomo

### mieru

mieru is a proxy/circumvention tool.

ZDT-D uses mieru as a profile-based local proxy backend. The daemon starts the
configured profile and routes selected applications into the local proxy path.

Purpose:

- profile-based proxy/circumvention scenarios;
- selected app local proxy routing.

Upstream:

- https://github.com/enfein/mieru

## VPN / TUN integrations

### OpenVPN

OpenVPN is started in root CLI mode. OpenVPN creates a TUN interface; ZDT-D then
binds selected app UIDs to that interface through Android `netd`.

Routing model:

```text
OpenVPN creates TUN -> zdtd detects/prepares TUN -> vpn_netd_builder binds selected UIDs
```

User provides:

- profile name;
- TUN interface name;
- DNS list;
- `client.ovpn`;
- selected apps.

ZDT-D provides:

- generated netId;
- UID ranges;
- netd binding;
- cleanup state.

Upstream:

- https://github.com/OpenVPN/openvpn
- https://openvpn.net/community

### AmneziaWG

AmneziaWG is a WireGuard-derived VPN tool with additional anti-censorship
features.

ZDT-D treats it as an exclusive VPN/TUN backend. A package routed through an
AmneziaWG profile should not be assigned to another incompatible main routing
profile at the same time.

Routing model:

```text
AmneziaWG creates/uses TUN -> zdtd prepares VPN profile -> Android netd binds selected UIDs
```

Upstream:

- https://github.com/amnezia-vpn/amneziawg-go
- https://github.com/amnezia-vpn/amnezia-client

### tun2socks

tun2socks creates a TUN interface and forwards TUN traffic to a proxy backend.

Routing model:

```text
tun2socks creates TUN -> zdtd assigns IPv4/prepares profile -> netd binds selected UIDs
```

User provides:

- profile name;
- TUN interface name;
- proxy URL;
- log level;
- selected apps.

ZDT-D provides netId, UID ranges, cleanup and runtime state.

Upstream:

- https://github.com/xjasonlyu/tun2socks

### myvpn

myvpn is an internal ZDT-D profile type for binding selected apps to an already
existing TUN interface.

It does not start a VPN client, create a TUN interface or run a proxy by itself.
Another program must create the TUN interface first.

Routing model:

```text
existing TUN -> myvpn profile -> Android netd UID binding
```

Use cases:

- bind apps to a TUN created by myprogram;
- integrate a custom VPN/TUN tool;
- experiment without adding a dedicated program integration.

## User-defined and helper programs

### myproxy

myproxy routes selected apps through a user-defined SOCKS5 proxy.

Routing model:

```text
selected UID -> iptables redirect -> t2s -> user SOCKS5 proxy
```

Purpose:

- simple SOCKS5 profile without writing full sing-box/mihomo config;
- optional authentication;
- app-based routing to a custom proxy.

### myprogram

myprogram is a universal launcher for a user-defined binary or script.

It can be used to start:

- a local proxy server;
- a custom network tool;
- a TUN creator;
- an experimental helper.

myprogram can be combined with other ZDT-D components. Example: myprogram starts
a custom tool that creates a TUN interface, then myvpn binds selected apps to that
interface.

myprogram itself is a launcher. Routing depends on what the launched program
provides and which ZDT-D mode is configured around it.

## Protection and diagnostics

### proxyInfo

proxyInfo protects local proxy/service ports and reports suspicious local access.
It can block selected UIDs from protected local ports and help identify apps that
probe local services.

Component type:

```text
local port protection / access observation helper
```

### blockedquic

blockedquic applies per-app UDP/443 blocking rules to force compatible apps away
from QUIC and toward TCP/TLS.

Routing model:

```text
selected UID + UDP/443 -> iptables/ip6tables filter/drop rules
```

It is designed to coexist with many main routing profiles because it is a helper
filter, not a full traffic path.

### scan detector

Internal helper related to suspicious access or scan detection. It supports
runtime warning/diagnostic workflows used by the app and daemon.

### energy saver

Energy saver settings allow ZDT-D to freeze/stop idle profile processes according
to daemon/app policy. This is a runtime optimization helper and should not be
confused with routing logic.

### dpi-detector

Native diagnostic binary for DNS/DPI/network symptom checks. See
`rust/dpi-detector/README.md`.

### nfqws-tester

Native diagnostic binary for temporary NFQUEUE strategy testing. See
`rust/nfqws-tester/README.md`.

## Runtime files and logs

Common files:

```text
working_folder/<program>/active.json
working_folder/<program>/enabled.json
working_folder/<program>/profile/<profile>/setting.json
working_folder/<program>/profile/<profile>/config.json
working_folder/<program>/profile/<profile>/config.yaml
working_folder/<program>/profile/<profile>/app/uid/user_program
working_folder/<program>/profile/<profile>/app/out/user_program
working_folder/<program>/profile/<profile>/log
working_folder/<program>/profile/<profile>/work
```

Not every program uses every file. Program modules define their own exact layout.

Main daemon log:

```text
/data/adb/modules/ZDT-D/log/zdtd.log
```

API/status files:

```text
/data/adb/modules/ZDT-D/api/token
/data/adb/modules/ZDT-D/api/info.json
/data/adb/modules/ZDT-D/api/status.json
```

## Conflict model

An application should not be assigned to multiple incompatible main traffic paths.
Examples of incompatible combinations include two exclusive VPN/TUN profiles or
two independent transparent proxy profiles that both try to own the same UID.

Helper features may coexist when they do not own the main route. Examples:

- blockedquic with a proxy/VPN profile;
- proxyInfo with local proxy profiles;
- diagnostics outside the normal runtime path.

The daemon validates known conflicts before applying runtime state. Conflict
rules should be updated whenever a new program type or routing model is added.

## Developer guide: adding a program

A new program usually needs changes in several places:

1. daemon program module under `rust/zdtd/src/programs/`;
2. program registration in daemon program/runtime logic;
3. default minimal layout in `settings.rs`;
4. port collection and conflict handling where needed;
5. app-list validation path if the program routes apps;
6. Android API models/actions;
7. Compose UI screen under `application/app/src/main/java/.../ui`;
8. string resources in default and Russian `strings.xml`;
9. documentation in this file and, if native, a Rust README.

Be especially careful with:

- firewall cleanup;
- Android `netd` cleanup;
- app UID ownership;
- profile state migration;
- binary paths and permissions;
- logs and process detection;
- coexistence with helper features.

## Documentation index

- Android app: `application/README.md`
- daemon: `rust/zdtd/README.md`
- transparent TCP to SOCKS helper: `rust/T2s/README.md`
- DPI diagnostics: `rust/dpi-detector/README.md`
- NFQWS strategy tester: `rust/nfqws-tester/README.md`
- Zygisk notes: `docs/ZYGISK.md`
