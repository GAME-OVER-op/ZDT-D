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

**ZDT-D** is a root module (Magisk / KernelSU / APatch and forks) designed to help handle networks with DPI or aggressive filtering.
It ships a local daemon and an **Android app** for managing profiles, app lists, and services.

> Note: Some texts / defaults are currently oriented for Russian-speaking users (temporary).

## Key Features

- Manage multiple DPI-circumvention tools from a single Android app (profiles, per-app routing lists)
- Quick toggling (including Quick Settings tile)
- Module update flow with **settings migration**
- **Backup / restore / import** of configuration
- Optional **service state notifications** (toggle in app settings)
- Manual program updates from official upstream repositories (on demand)

## Included Tools

| Program | Description | Repository |
|---|---|---|
| zapret (nfqws) | DPI circumvention tool | https://github.com/bol-van/zapret |
| zapret2 (nfqws2 + lua) | zapret2 engine + lua scripts | https://github.com/bol-van/zapret2 |
| byedpi | DPI circumvention utility | https://github.com/hufrea/byedpi |
| DPITunnel-cli | Desync-based DPI tool | https://github.com/nomoresat/DPITunnel-cli |
| dnscrypt-proxy | Encrypted DNS proxy | https://github.com/DNSCrypt/dnscrypt-proxy |
| opera-proxy | Standalone Opera VPN client/proxy | https://github.com/Snawoot/opera-proxy |

## Requirements

- **Root**: Magisk / KernelSU / APatch (or forks)
- **Architecture**: **arm64-v8a only**
- **Android**: **11+ supported**
  - Earlier Android versions may be forced to install, but **functionality is not guaranteed**
- Device/ROM limitations may vary depending on the root manager and its environment.

## Installation

1. Install the Android app.
2. Install/Update the module from the app (the app will guide you).
3. Reboot when prompted (required after module updates).

⚠️ Do not modify release archives manually unless you know what you are doing.

## Updates

- **Module update**: triggered from the Android app. A reboot is required after installation.
- **Program updates (zapret / zapret2)**: manual, on demand, from official GitHub releases via the app.
  - zapret: updates `nfqws`
  - zapret2: updates `nfqws2` + `lua` scripts

## Privacy & Safety

- ZDT-D does not collect personal data.
- Some antivirus apps may flag DPI-related tools due to their behavior. This project is intended for network compatibility/management and is open source.

## License

MIT License — see [LICENSE](https://github.com/GAME-OVER-op/ZDT-D/blob/main/LICENSE)

## Downloads

- [Releases](https://github.com/GAME-OVER-op/ZDT-D/releases)
