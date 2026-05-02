# ZDT-D Programs and Components

This document describes the main programs and internal components used by ZDT-D.

The goal of this file is to explain what each component is, how ZDT-D uses it, what routing model it belongs to, and where to read the original upstream documentation when an external project is involved.

ZDT-D combines external networking tools with its own Android-specific daemon, routing builders, profile logic, and app-list management.

---

## Core components

### `zdtd` daemon

#### What it is

`zdtd` is the central Rust daemon of ZDT-D.

It runs with root privileges and controls the runtime state of the module.

#### How ZDT-D uses it

The daemon is responsible for:

- waiting for Android boot readiness
- preparing runtime directories
- checking Android package manager readiness
- checking Android `netd` readiness
- backing up and restoring baseline firewall state
- starting and stopping enabled programs
- resolving Android package names into Linux UIDs
- applying `iptables` / `ip6tables` rules
- applying Android `netd` VPN bindings
- exposing a local API for the Android app
- writing runtime logs and user-facing logs

#### Component type

Internal core component.

#### Notes

`zdtd` is not a VPN protocol implementation and not a proxy by itself. It orchestrates other engines and internal routing builders.

---

### Android app

#### What it is

The Android app is the user interface for ZDT-D.

#### How ZDT-D uses it

The app is used to:

- start and stop services
- create and edit program profiles
- select applications for routing
- view runtime status
- view logs
- install or update the module
- import and export settings

#### Component type

Configuration and control UI.

#### Notes

The app does not directly build firewall or `netd` rules. It talks to the local daemon API, and the daemon performs root operations.

---

### UID app parser

#### What it is

The UID app parser is the internal layer that turns Android package names into Linux UIDs.

#### How ZDT-D uses it

The user selects applications in the UI. ZDT-D stores package names and resolves them to Linux UIDs. Those UIDs are then used by different routing systems.

#### Used by

- `iptables` owner match rules
- NFQUEUE routing
- transparent redirect rules
- Android `netd` VPN binding
- app-list conflict validation

#### Component type

Internal app-list and UID resolver.

#### Notes

ZDT-D routes traffic by UID, not by package name directly. If apps are installed, removed, cloned, or moved between Android users, UID resolution may need to be refreshed.

---

### `vpn_netd_builder`

#### What it is

`vpn_netd_builder` is an internal ZDT-D builder for binding selected Android application UIDs to a TUN interface through Android `netd`.

#### How ZDT-D uses it

It receives prepared VPN profile data from programs such as OpenVPN, tun2socks, or myvpn:

- owner program
- profile name
- generated netId
- TUN interface name
- IPv4 CIDR
- optional gateway
- DNS list
- UID ranges

It then creates a `netd` VPN network and assigns selected UIDs to it.

#### Routing model

Android `netd` UID binding to a TUN interface.

#### Used by

- OpenVPN
- tun2socks
- myvpn
- future TUN/VPN-based integrations

#### Notes

`vpn_netd_builder` does not launch VPN clients and does not keep user profiles. It only stores runtime state so that networks it created can be removed on service stop.

---

## DPI and NFQUEUE engines

### `nfqws`

#### What it is

`nfqws` is a userspace packet processor from the zapret project. zapret is a multi-platform DPI bypass toolkit that includes `nfqws` for NFQUEUE-based processing.

#### How ZDT-D uses it

ZDT-D resolves selected applications into UIDs, creates `mangle` table rules, sends matching traffic to NFQUEUE, and runs `nfqws` as the userspace packet processor.

#### Routing model

`iptables` `mangle` table -> NFQUEUE -> `nfqws`.

#### Purpose

- DPI circumvention
- packet-level manipulation
- compatibility with NFQUEUE-based strategies

#### Upstream

- https://github.com/bol-van/zapret

---

### `nfqws2`

#### What it is

`nfqws2` is part of zapret2. zapret2 moves part of the packet manipulation logic into Lua scripts while keeping NFQUEUE interception as the traffic entry point.

#### How ZDT-D uses it

ZDT-D can route selected application traffic to `nfqws2` and load Lua-based logic for more flexible DPI-bypass strategies.

#### Routing model

`iptables` `mangle` table -> NFQUEUE -> `nfqws2` + Lua.

#### Purpose

- extended DPI circumvention
- Lua-based strategy logic
- more flexible traffic processing than a fixed command line

#### Upstream

- https://github.com/bol-van/zapret2

---

### byedpi

#### What it is

byedpi is a DPI circumvention utility.

#### How ZDT-D uses it

ZDT-D starts byedpi as a local processing engine and attaches selected application traffic to it through the module routing layer.

#### Routing model

Local DPI-bypass pipeline, usually combined with `iptables` redirect or related local routing logic.

#### Purpose

- DPI circumvention
- alternative strategies to NFQUEUE-based engines
- profile-based bypass setups

#### Upstream

- https://github.com/hufrea/byedpi

---

### DPITunnel-cli

#### What it is

DPITunnel-cli is a DPI/censorship bypass tool. Its upstream project describes DPI Tunnel as a proxy server for bypassing censorship and explicitly notes that it is not a VPN.

#### How ZDT-D uses it

ZDT-D runs DPITunnel-cli as a local engine and routes selected application traffic into it depending on the selected profile.

#### Routing model

Local proxy or transparent proxy pipeline.

#### Purpose

- desync-based DPI bypass
- HTTP or transparent proxy scenarios
- per-profile bypass tuning

#### Upstream

- https://github.com/nomoresat/DPITunnel-cli

---

## DNS component

### dnscrypt-proxy

#### What it is

dnscrypt-proxy is a flexible DNS proxy with support for DNSCrypt v2, DNS-over-HTTPS, Anonymized DNSCrypt, and ODoH.

#### How ZDT-D uses it

ZDT-D can run dnscrypt-proxy locally and route DNS-related traffic to the local resolver in supported scenarios.

#### Component type

Local DNS resolver and encrypted DNS proxy.

#### Purpose

- encrypted DNS
- local DNS control
- consistent DNS behavior for selected routing scenarios

#### Notes

DNS behavior on Android depends on ROM implementation, Private DNS settings, IPv6 support, and firewall capabilities.

#### Upstream

- https://github.com/DNSCrypt/dnscrypt-proxy

---

## Proxy and local pipeline engines

### opera-proxy

#### What it is

opera-proxy is a standalone client for Opera VPN/proxy endpoints. It starts a local proxy server and forwards traffic through Opera proxy infrastructure.

#### How ZDT-D uses it

ZDT-D uses opera-proxy as part of a local proxy pipeline. Depending on profile settings, ZDT-D can start opera-proxy, optionally combine it with helper engines, and route selected applications into the local pipeline.

#### Routing model

Selected UID -> local redirect / t2s -> opera-proxy.

#### Purpose

- Opera proxy endpoint scenarios
- local proxy pipeline routing
- server/SNI-based profile control

#### Upstream

- https://github.com/Snawoot/opera-proxy
- https://pkg.go.dev/github.com/Snawoot/opera-proxy

---

### sing-box

#### What it is

sing-box is a universal proxy platform.

#### How ZDT-D uses it

ZDT-D can run sing-box profiles and route selected application traffic to the relevant local inbound or helper pipeline.

#### Routing model

Transparent redirect, local proxy pipeline, or helper-based forwarding depending on the profile.

#### Purpose

- multi-protocol proxy configurations
- profile-based proxy routing
- local inbound/outbound proxy scenarios

#### Notes

This document only describes sing-box as one supported engine. Detailed sing-box configuration should be read from the upstream documentation.

#### Upstream

- https://github.com/SagerNet/sing-box
- https://sing-box.sagernet.org

---

### wireproxy

#### What it is

wireproxy is a userspace application that connects to a WireGuard peer and exposes SOCKS5, HTTP proxy, or tunnel endpoints.

#### How ZDT-D uses it

ZDT-D uses wireproxy as a proxy backend, not as a system WireGuard interface manager. The daemon starts wireproxy profiles and can route selected applications through the local proxy pipeline.

#### Routing model

Selected UID -> local redirect / t2s -> wireproxy SOCKS5 or HTTP endpoint.

#### Purpose

- access a WireGuard peer through a local proxy interface
- route selected applications through a WireGuard-like upstream without creating a system WireGuard interface
- profile-based proxy backend

#### Notes

This is not the same as WireGuard tools or wireguard-go. ZDT-D currently documents wireproxy as a proxy engine only.

#### Upstream

- https://github.com/windtf/wireproxy

---

### t2s

#### What it is

`t2s` is a ZDT-D bundled transparent-to-SOCKS helper.

#### How ZDT-D uses it

`t2s` receives transparent redirected traffic and forwards it to one or more SOCKS5 upstreams.

#### Used by

- sing-box scenarios
- wireproxy scenarios
- Tor scenarios
- opera-proxy scenarios
- myproxy scenarios
- selected myprogram scenarios

#### Routing model

`iptables` redirect -> t2s -> SOCKS5 upstream.

#### Purpose

- bridge transparent TCP redirection to SOCKS5 backends
- support selected UID-based proxy pipelines
- provide a common helper for multiple profile programs

#### Upstream

Bundled/internal ZDT-D component.

---

## Tor components

### Tor

#### What it is

Tor is software and a network for privacy, anonymity, and censorship circumvention.

#### How ZDT-D uses it

ZDT-D can run Tor as a local SOCKS pipeline for selected applications. The daemon reads Tor settings, validates bridge requirements, and starts Tor with the configured `torrc`.

#### Routing model

Selected UID -> t2s or local SOCKS pipeline -> Tor SOCKS port.

#### Purpose

- route selected applications through Tor
- support bridge-based Tor bootstrap
- support pluggable transports through lyrebird

#### Upstream

- https://gitlab.com/torproject/tor
- https://www.torproject.org

---

### lyrebird

#### What it is

lyrebird is a Tor pluggable transport binary used for bridge transports such as obfs4 and related circumvention transports.

#### How ZDT-D uses it

ZDT-D uses lyrebird as the transport plugin for Tor bridge configurations.

#### Component type

Tor pluggable transport helper.

#### Purpose

- obfs4 bridge support
- Snowflake/WebTunnel-style transport support where configured
- Tor bridge circumvention scenarios

#### Upstream

- https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird

---

## VPN / TUN integrations

### OpenVPN

#### What it is

OpenVPN is an open-source VPN daemon.

#### How ZDT-D uses it

ZDT-D runs OpenVPN in root CLI mode. OpenVPN creates a TUN interface, while ZDT-D binds selected application UIDs to that interface through Android `netd`.

The daemon prepares `client.ovpn` for Android CLI usage by ensuring that OpenVPN only raises the TUN interface and does not apply global routes or DNS changes directly.

#### Routing model

OpenVPN creates TUN -> ZDT-D detects TUN -> `vpn_netd_builder` binds selected UIDs to that TUN.

#### User provides

- profile name
- TUN interface name
- DNS list
- `client.ovpn`
- application list

#### User does not provide

- netId
- CIDR
- gateway
- UID ranges
- routes
- firewall rules

#### Purpose

- selective OpenVPN routing without Android `VpnService`
- OpenVPN profile integration through root CLI
- UID-based split tunneling through Android `netd`

#### Upstream

- https://github.com/OpenVPN/openvpn
- https://openvpn.net/community

---

### tun2socks

#### What it is

tun2socks is a TUN-to-proxy engine powered by a userspace network stack. It can route traffic from a TUN interface through proxy protocols such as SOCKS or HTTP depending on build and configuration.

#### How ZDT-D uses it

In ZDT-D, tun2socks creates the TUN interface. The daemon then assigns an IPv4 address to it, brings it up, and passes the profile to `vpn_netd_builder`.

#### Routing model

tun2socks creates TUN -> ZDT-D assigns IPv4 -> `vpn_netd_builder` binds selected UIDs to that TUN.

#### User provides

- profile name
- TUN interface name
- proxy URL
- log level
- application list

#### User does not provide

- netId
- CIDR
- gateway
- TUN address
- UID ranges

#### Purpose

- TUN-based proxy routing
- selective SOCKS/HTTP proxy routing through Android `netd`
- an alternative to pure `iptables` transparent redirection

#### Upstream

- https://github.com/xjasonlyu/tun2socks

---

### myvpn

#### What it is

myvpn is a universal ZDT-D VPN/netd binding profile for an already existing TUN interface.

It does not start a VPN client, does not start a proxy, and does not create the TUN interface by itself.

#### How ZDT-D uses it

A different program creates a TUN interface. That program can be OpenVPN, tun2socks, myprogram, an external script, or a future engine. myvpn waits for that interface, obtains or receives the CIDR, parses the app list, and passes the profile to `vpn_netd_builder`.

#### Routing model

Existing TUN -> myvpn profile -> Android `netd` UID binding.

#### User provides

- profile name
- TUN interface name
- DNS list
- CIDR mode (`auto` or `manual`)
- manual CIDR when needed
- application list

#### User does not provide

- netId
- gateway
- UID ranges
- binary path
- proxy settings
- routes
- firewall rules

#### Purpose

- bind selected applications to a TUN created by another program
- combine myprogram with custom VPN/TUN tools
- support custom routing setups without adding a dedicated engine to ZDT-D

#### Upstream

Internal ZDT-D component.

---

## User-defined and helper programs

### myproxy

#### What it is

myproxy is an internal ZDT-D profile program for routing selected applications through a user-defined SOCKS5 proxy.

#### How ZDT-D uses it

The user provides proxy host, port, and optional authentication. ZDT-D starts the required local helper and routes selected application traffic to that proxy pipeline.

#### Routing model

Selected UID -> `iptables` redirect -> t2s -> user SOCKS5 proxy.

#### Purpose

- quickly connect selected apps to a custom SOCKS5 proxy
- avoid writing a full sing-box configuration for simple proxy use
- provide a simple profile-based proxy path

#### Upstream

Internal ZDT-D component.

---

### myprogram

#### What it is

myprogram is a universal ZDT-D launcher for a user-defined binary or script.

#### How ZDT-D uses it

The user can define a profile that starts an external process. That process can be a proxy server, a custom network tool, a TUN creator, or an experimental binary.

#### Component type

Custom process launcher.

#### Purpose

- extend ZDT-D without adding a new built-in engine
- test external networking tools
- combine user programs with other ZDT-D routing components

#### Example

A user can start a custom program through myprogram and then use myvpn to bind selected applications to the TUN interface created by that program.

#### Upstream

Internal ZDT-D component.

---

## Protection and diagnostic components

### proxyInfo

#### What it is

proxyInfo is an internal component for observing and protecting access to local proxy or service ports.

#### How ZDT-D uses it

proxyInfo can monitor access attempts, block selected UIDs from reaching protected local ports, and report suspicious activity to the Android app.

#### Component type

Port and local proxy protection helper.

#### Purpose

- protect local proxy endpoints
- detect applications probing local ports
- help identify suspicious access to local services

#### Upstream

Internal ZDT-D component.

---

### blockedquic

#### What it is

blockedquic is an internal component for blocking QUIC traffic for selected applications.

#### How ZDT-D uses it

The daemon applies rules for selected UIDs and UDP/443 so that chosen applications are forced away from QUIC where needed.

#### Routing model

`iptables` / `ip6tables` filtering by UID and UDP port.

#### Purpose

- disable QUIC for selected applications
- force fallback to TCP/TLS in compatible applications
- improve compatibility with DPI and proxy routing scenarios

#### Notes

blockedquic is designed to be used alongside other network programs and should not conflict with OpenVPN, tun2socks, myvpn, or other main routing engines by default.

#### Upstream

Internal ZDT-D component.

---

### scan_detector

#### What it is

scan_detector is an internal detection helper related to suspicious network or port-scanning behavior.

#### How ZDT-D uses it

It supports runtime detection workflows and can help the Android app present warnings or diagnostic information.

#### Component type

Internal detection helper.

#### Upstream

Internal ZDT-D component.

---

## Firewall and runtime layers

### `iptables` / `ip6tables` layer

#### What it is

This is the internal firewall and routing layer used by ZDT-D.

#### How ZDT-D uses it

It manages:

- custom chains
- UID owner matches
- `nat` table redirection
- `mangle` table rules
- NFQUEUE rules
- IPv6 best-effort rules
- baseline backup and restore

#### Component type

Internal firewall/routing layer.

#### Notes

ZDT-D tries to apply rules idempotently and cumulatively so that different programs do not overwrite each other.

---

### boot guard and runtime state

#### What it is

The boot guard is the internal startup preparation logic used by the daemon.

#### How ZDT-D uses it

On startup, the daemon can:

- wait for `sys.boot_completed`
- wait a short stabilization period
- check that the Android package manager responds
- check that Android `netd` responds
- start enabled programs in best-effort mode
- expose runtime state to the UI

#### Runtime states

- `starting`
- `running`
- `partial`
- `stopping`
- `stopped`

#### Purpose

- make boot-time startup more reliable
- avoid failing the whole service because one profile failed
- let the UI show a partial state when some components did not start

---

## Documentation notes

README should stay high-level and describe the project.

This file should describe programs and components.

Detailed profile examples, API endpoints, troubleshooting steps, and Android-specific commands should be placed in practical instruction files such as `INSTRUCTIONS.md` or additional files under `docs/`.
