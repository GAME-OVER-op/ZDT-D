# ZDT-D Root Module (Magisk / KernelSU / APatch)

<div align="center">
  <img src="https://github.com/GAME-OVER-op/ZDT-D/blob/main/images/module_icon.png" alt="ZDT-D Logo" width="300" />
</div>

<p align="center">
  <a href="https://github.com/GAME-OVER-op/ZDT-D/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/GAME-OVER-op/ZDT-D?style=flat-square" alt="License" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/releases/latest">
    <img src="https://img.shields.io/github/v/release/GAME-OVER-op/ZDT-D?style=flat-square" alt="Latest Release" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/releases">
    <img src="https://img.shields.io/github/downloads/GAME-OVER-op/ZDT-D/total?style=flat-square" alt="Downloads" />
  </a>
  <a href="https://t.me/module_ggover">
    <img src="https://img.shields.io/badge/Telegram–Join%20Group-blue?style=flat-square&logo=telegram" alt="Telegram Group" />
  </a>
</p>

## Description

**ZDT-D** is a root module for Android designed for advanced network compatibility, DPI circumvention, DNS handling, and local proxy orchestration.

Unlike a classic Android VPN app, ZDT-D does **not** rely on `VpnService` as its primary traffic engine.  
Instead, it uses a **root daemon**, **iptables/ip6tables**, **UID-based routing**, **NFQUEUE**, and **local transparent redirection** to selectively pass traffic into different processing pipelines.

The project includes:

- a local Rust daemon (`zdtd`)
- an Android application for configuration and status control
- bundled networking tools for different routing and DPI-bypass scenarios

> The app is available in **Russian** and **English**.

---

## What makes ZDT-D different from a classic VPN

A traditional Android VPN usually works through `VpnService`, creates a virtual `TUN` interface, and routes traffic through a single VPN pipeline.

**ZDT-D uses a different model:**

- works with **root privileges**
- applies rules through **iptables / ip6tables**
- routes traffic **by Android app UID**
- supports **NFQUEUE-based packet processing**
- supports **transparent redirect to local services on `127.0.0.1`**
- can combine multiple engines for different scenarios instead of forcing one global VPN tunnel

This makes ZDT-D closer to a **root-based transparent traffic manager** than to a standard VPN client.

---

## Architecture overview

### 1. Android app
The Android application is responsible for:

- editing settings
- enabling/disabling services
- selecting apps for routing
- importing/exporting backups
- starting module update flow
- viewing service state and logs

### 2. `zdtd` daemon
`zdtd` is the central runtime component.

It:

- waits for Android boot completion
- prepares runtime environment
- backs up and restores baseline iptables state
- starts enabled services in a controlled order
- exposes a local API on loopback
- monitors service state
- can protect critical processes from sleep/termination

### 3. External bundled tools
ZDT-D orchestrates multiple external engines instead of implementing every protocol inside one binary.

---

## Included Tools

| Program | Purpose | Upstream |
|---|---|---|
| zapret (`nfqws`) | DPI circumvention via NFQUEUE-based packet processing | https://github.com/bol-van/zapret |
| zapret2 (`nfqws2` + lua) | Extended DPI bypass engine with lua scripts | https://github.com/bol-van/zapret2 |
| byedpi | DPI circumvention utility | https://github.com/hufrea/byedpi |
| DPITunnel-cli | Desync-based DPI tool | https://github.com/nomoresat/DPITunnel-cli |
| dnscrypt-proxy | Encrypted DNS proxy | https://github.com/DNSCrypt/dnscrypt-proxy |
| opera-proxy | Opera proxy/VPN pipeline component | https://github.com/Snawoot/opera-proxy |
| sing-box | Multi-protocol proxy engine | https://github.com/SagerNet/sing-box |
| t2s | Transparent-to-socks helper used in selected modes | bundled in project |

---

## Traffic routing model

ZDT-D supports more than one traffic handling path.

### A. NFQUEUE path
Used for packet-level processing.

Typical logic:

- selected Android packages are converted into **UIDs**
- `iptables` rules are added in `mangle`
- matching traffic is sent to **NFQUEUE**
- userspace processor handles the packets

### B. Transparent local redirection
Used for local service pipelines.

Typical logic:

- selected Android app UIDs are resolved
- `iptables` rules are attached to `OUTPUT`
- traffic is redirected to local listeners on `127.0.0.1:<port>`
- local component processes the stream

### C. DNS handling
`dnscrypt-proxy` can be started before the rest of the pipeline so DNS resolution can be handled in a controlled way.

---

## How app selection works

ZDT-D does not blindly route all device traffic.

It can work with **selected applications**:

1. package names are collected from user settings
2. package names are resolved into **Linux UIDs**
3. iptables rules are generated using `-m owner --uid-owner`
4. only traffic belonging to those apps is redirected or queued

This allows more selective routing than a simple “all traffic through one tunnel” design.

---

## iptables / ip6tables usage

ZDT-D actively manages system networking rules.

Main characteristics:

- uses **iptables** and **ip6tables**
- backs up baseline rules on first launch
- restores a clean baseline before applying new runtime rules
- works primarily with **`nat`** and **`mangle`** tables
- applies rules idempotently to reduce duplication
- supports IPv6 on a best-effort basis depending on device capabilities

Examples of rule types used by the module:

- `NFQUEUE` rules in `mangle`
- `owner` match by UID
- `DNAT` to loopback ports
- custom chains for transparent redirection

---

## sing-box integration

ZDT-D supports `sing-box` in two primary modes:

### Socks5 mode
- launches one or more `sing-box` profiles
- starts `t2s`
- redirects selected app traffic into `t2s`
- `t2s` forwards into the selected socks5 pipeline

### Transparent mode
- launches the selected `sing-box` profile
- applies transparent iptables redirect directly to the configured local port
- supports TCP or TCP+UDP capture depending on profile settings

---

## Runtime protection

ZDT-D includes a runtime protector for important service processes.

Depending on configuration, it can:

- acquire a kernel wake lock
- adjust `oom_score_adj`
- work in `Off`, `On`, or `Auto` mode
- react differently to screen-on / screen-off states

This part is intended to improve service stability during idle or power-managed device states.

---

## Installation

1. Install the Android app.
2. Use the app to install or update the module.
3. Reboot when prompted.
4. Open the app and configure the desired programs and app routing lists.

> Do not manually modify release archives unless you know exactly what you are doing.

---

## Updates

### Module update
- performed through the Android app
- requires reboot after installation
- settings migration is supported

### Program updates
Some bundled tools can be updated manually from their official upstream releases through the app.

Examples:
- zapret → updates `nfqws`
- zapret2 → updates `nfqws2` + `lua` scripts

---

## Privacy

ZDT-D does **not** collect, transmit, sell, share, or use personal data.

All configuration, routing, rule management, and runtime control required for the module to work are performed **locally on the installed device**.

The project does **not** require remote telemetry or analytics for core functionality.

If the application connects to external resources, it does so only for actions explicitly requested by the user, such as checking releases or downloading updates from official upstream sources.

---

## Safety

- Some antivirus products may flag DPI-related tools because they work with low-level network behavior.
- ZDT-D is intended for advanced network compatibility, routing control, and research / enthusiast use.
- Device compatibility may vary depending on ROM behavior, SELinux handling, kernel options, and root environment.
- Some features depend on working iptables/ip6tables support on the device.

---

## Notes

- IPv6 behavior may differ across devices and firmware builds.
- Some functions may depend on the behavior of the root environment and firmware implementation.
- For additional usage notes and explanations, see `INSTRUCTIONS.md`.

---

## License

GPL-3.0 License — see [LICENSE](https://github.com/GAME-OVER-op/ZDT-D/blob/main/LICENSE)

## Downloads

- [Releases](https://github.com/GAME-OVER-op/ZDT-D/releases)