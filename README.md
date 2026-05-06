# ZDT-D Root Module

<div align="center">
  <img src="images/module_icon.png" alt="ZDT-D Logo" width="300" />
</div>

<h3 align="center">
  Android root network orchestration module for DPI bypass, proxy chaining, DNS control, and per-app routing
</h3>

<p align="center">
  <a href="https://github.com/GAME-OVER-op/ZDT-D/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/GAME-OVER-op/ZDT-D?style=flat-square&label=License" alt="License" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/stargazers">
    <img src="https://img.shields.io/github/stars/GAME-OVER-op/ZDT-D?style=flat-square&logo=github&label=Stars" alt="GitHub Stars" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/network/members">
    <img src="https://img.shields.io/github/forks/GAME-OVER-op/ZDT-D?style=flat-square&logo=github&label=Forks" alt="GitHub Forks" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/releases/latest">
    <img src="https://img.shields.io/github/v/release/GAME-OVER-op/ZDT-D?style=flat-square&label=Release" alt="Latest Release" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/releases">
    <img src="https://img.shields.io/github/downloads/GAME-OVER-op/ZDT-D/total?style=flat-square&label=Downloads" alt="Downloads" />
  </a>
  <a href="https://github.com/GAME-OVER-op/ZDT-D/commits/main">
    <img src="https://img.shields.io/github/last-commit/GAME-OVER-op/ZDT-D?style=flat-square&label=Last%20Commit" alt="Last Commit" />
  </a>
  <a href="https://t.me/module_ggover">
    <img src="https://img.shields.io/badge/Telegram-Join%20Group-229ED9?style=flat-square&logo=telegram&logoColor=white" alt="Telegram Group" />
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-Root%20Module-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android Root Module" />
  <img src="https://img.shields.io/badge/Magisk-supported-00AF9C?style=flat-square" alt="Magisk Supported" />
  <img src="https://img.shields.io/badge/KernelSU-supported-4285F4?style=flat-square" alt="KernelSU Supported" />
  <img src="https://img.shields.io/badge/APatch-supported-8A2BE2?style=flat-square" alt="APatch Supported" />
  <img src="https://img.shields.io/badge/Kotlin-Android-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin Android" />
  <img src="https://img.shields.io/badge/Rust-Daemon-B7410E?style=flat-square&logo=rust&logoColor=white" alt="Rust Daemon" />
</p>

<p align="center">
  <img src="https://img.shields.io/badge/DPI-Bypass-E53935?style=flat-square" alt="DPI Bypass" />
  <img src="https://img.shields.io/badge/Per--App-Routing-F57C00?style=flat-square" alt="Per-App Routing" />
  <img src="https://img.shields.io/badge/DNS-Control-1976D2?style=flat-square" alt="DNS Control" />
  <img src="https://img.shields.io/badge/NFQUEUE-supported-6A1B9A?style=flat-square" alt="NFQUEUE Supported" />
  <img src="https://img.shields.io/badge/TUN-binding-3949AB?style=flat-square" alt="TUN Binding" />
  <img src="https://img.shields.io/badge/No%20Telemetry-local%20only-2E7D32?style=flat-square" alt="No Telemetry" />
</p>

<p align="center">
  <b>ZDT-D</b> is an Android root module for traffic routing, DPI bypass, proxy chaining, DNS control, and per-app network management.
</p>

---

## Description

**ZDT-D** is a root-based Android network orchestration project for advanced traffic routing, DPI circumvention, DNS handling, local proxy pipelines, and selective VPN/TUN binding.

It is not a classic Android VPN application and it is not limited to one bundled engine. ZDT-D uses a local root daemon, Android application UIDs, `iptables` / `ip6tables`, NFQUEUE, local loopback services, and Android `netd` to route selected applications through different processing paths.

The project includes:

- a local Rust daemon (`zdtd`)
- an Android application for configuration and status control
- bundled networking tools for different routing and compatibility scenarios
- internal builders for UID-based redirection and Android `netd`-based TUN binding

> The Android app is available in Russian and English.

---

## What makes ZDT-D different

Most Android VPN or proxy applications use a single `VpnService` instance, create one virtual TUN interface, and route all or selected traffic through one global pipeline.

ZDT-D uses a different model:

- it works with root privileges
- it does not depend on Android `VpnService` as its main traffic engine
- it can route traffic by Android application UID
- it can apply `iptables` / `ip6tables` rules
- it can send traffic to NFQUEUE-based DPI engines
- it can redirect selected applications to local proxy services on `127.0.0.1`
- it can bind selected applications to existing or generated TUN interfaces through Android `netd`
- it can run several engines and profiles at the same time

Because of this, ZDT-D is closer to a root-based traffic management platform than to a traditional VPN client.

---

## Feature overview

| Area | Status | Description |
|---|---:|---|
| Root module | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Works as a root-level Android module |
| Per-app routing | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Routes selected Android applications by UID |
| DPI bypass | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Supports DPI circumvention engines and traffic processing |
| NFQUEUE | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Sends selected traffic to userspace packet processors |
| Local proxy redirection | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Redirects selected traffic to local loopback services |
| Android `netd` binding | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Binds selected app UIDs to TUN interfaces |
| DNS control | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Supports local DNS handling and DNS-related routing |
| Conflict checks | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Prevents incompatible app-list intersections |
| Custom programs | ![Supported](https://img.shields.io/badge/supported-yes-2E7D32?style=flat-square) | Allows user-provided binaries and scripts |

---

## Split tunneling and app-based control

ZDT-D does not blindly route the whole device through one tunnel.

The user selects Android applications, the daemon resolves package names into Linux UIDs, and those UIDs are used by the routing layer. Depending on the selected program, traffic can be sent through `iptables`, NFQUEUE, a local transparent proxy pipeline, or an Android `netd` VPN binding.

This makes it possible to build flexible scenarios such as:

- one application through OpenVPN + Android `netd`
- another application through tun2socks + Android `netd`
- another application through a local sing-box or wireproxy pipeline
- selected applications through NFQUEUE-based DPI circumvention
- selected applications through an Opera proxy pipeline
- selected applications through a custom TUN interface exposed by `myvpn`

ZDT-D is designed for selective routing. It does not force every application into the same path.

---

## Routing models

ZDT-D supports multiple independent traffic handling paths.

| Routing model | Description |
|---|---|
| NFQUEUE path | Selected application traffic can be matched by UID and sent into NFQUEUE. A userspace DPI engine can then inspect or modify packets. |
| Transparent local redirection | Selected application traffic can be redirected to a local listener on `127.0.0.1:<port>`. Local helper programs then forward or process the stream. |
| Android `netd` / TUN binding | When a supported program creates or exposes a TUN interface, ZDT-D can bind selected application UIDs to that interface through Android `netd`. |
| DNS handling | ZDT-D can manage local DNS components and route DNS-related traffic in controlled scenarios. |
| Custom program path | User-provided binaries or scripts can be launched and combined with ZDT-D routing features. |

---

## Supported components

| Component | Type | Purpose |
|---|---|---|
| `zdtd` | Rust daemon | Local root daemon, API, profile handling, rule application |
| Android app | Kotlin / Jetpack Compose | User interface, configuration, status control |
| `iptables` / `ip6tables` | System firewall | UID-based matching, NAT, redirection, packet filtering |
| NFQUEUE engines | DPI path | Userspace packet processing |
| `dnscrypt-proxy` | DNS | Local DNS resolver |
| `sing-box` | Proxy / TUN engine | Advanced proxy and routing scenarios |
| `wireproxy` | SOCKS5 bridge | WireGuard userspace SOCKS5 proxy |
| `tor` + `lyrebird` | Tor / bridges | Tor connectivity with pluggable transports |
| `opera-proxy` | Local proxy | Opera proxy pipeline |
| `byedpi` | DPI bypass | DPI circumvention helper |
| `tun2socks` | TUN to proxy | TUN interface to proxy pipeline |
| `openvpn` | VPN / TUN | OpenVPN profile support |
| `myprogram` | Custom launcher | User-defined binary/script launcher |
| `myvpn` | TUN binding | Bind selected apps to existing TUN interfaces |

---

## Flexible program architecture

ZDT-D is built around profile-based programs rather than a single fixed binary.

Different programs can have their own profiles, settings, app lists, logs, and runtime behavior. The daemon collects enabled profiles, validates conflicts, starts the required engines, and applies the correct routing model for each one.

The project supports several categories of components:

- DPI and NFQUEUE engines
- transparent proxy engines
- local proxy pipelines
- DNS components
- VPN/TUN + Android `netd` binding
- user-defined process launchers
- port protection and diagnostic helpers

This architecture makes the project flexible: new engines can be added without redesigning the entire routing system.

---

## Custom programs and extensibility

A major goal of ZDT-D is extensibility.

The project is not limited to pre-defined tools. Users can add their own network programs and combine them with ZDT-D routing features.

For example:

- `myprogram` can launch a user-provided binary or script
- that binary can create a local proxy, a service, or a TUN interface
- `myvpn` can bind selected applications to an already existing TUN interface
- the daemon can still handle UID parsing, conflict checks, and Android `netd` binding

This allows ZDT-D to be used as a base for custom Android networking setups, not only as a ready-made module with fixed behavior.

---

## Conflict control

Because several programs can target the same applications, ZDT-D checks app-list conflicts.

An application should not be assigned to multiple incompatible network pipelines at the same time. This reduces broken routing, duplicated redirection, and hard-to-debug conflicts between profiles.

Some helper features, such as QUIC blocking, can be used alongside other routing modes when they do not conflict with the main traffic path.

---

## Documentation

| Document | Purpose |
|---|---|
| [`README.md`](README.md) | Main project overview |
| [`docs/PROGRAMS.md`](docs/PROGRAMS.md) | Supported programs and internal components |
| `INSTRUCTIONS.md` | Practical usage notes, troubleshooting, and advanced examples |
| [`LICENSE`](LICENSE) | GPL-3.0 license |
| [Releases](https://github.com/GAME-OVER-op/ZDT-D/releases) | Stable builds and changelogs |
| [Issues](https://github.com/GAME-OVER-op/ZDT-D/issues) | Bug reports and feature requests |

---

## Privacy

ZDT-D does not collect, transmit, sell, share, or use personal data.

All configuration, routing, rule management, and runtime control required for the module to work are performed locally on the installed device.

The project does not require remote telemetry or analytics for core functionality.

If the application connects to external resources, it does so only for actions explicitly requested by the user, such as checking releases or downloading updates from official upstream sources.

---

## Safety and compatibility

ZDT-D works with low-level Android networking components. Compatibility may vary depending on:

- ROM behavior
- root implementation
- SELinux behavior
- kernel features
- `iptables` / `ip6tables` support
- Android `netd` behavior
- bundled binary compatibility

Some antivirus products may flag DPI-related tools because they work with low-level network traffic. This does not mean that ZDT-D collects data or performs remote telemetry.

ZDT-D is intended for advanced users, network compatibility research, routing control, and enthusiast use.

---

## Downloads

The latest builds are available on the GitHub Releases page:

- [Download latest release](https://github.com/GAME-OVER-op/ZDT-D/releases/latest)
- [View all releases](https://github.com/GAME-OVER-op/ZDT-D/releases)

---

## Project status

| Metric | Badge |
|---|---|
| Stars | ![GitHub Stars](https://img.shields.io/github/stars/GAME-OVER-op/ZDT-D?style=flat-square&logo=github&label=Stars) |
| Forks | ![GitHub Forks](https://img.shields.io/github/forks/GAME-OVER-op/ZDT-D?style=flat-square&logo=github&label=Forks) |
| Watchers | ![GitHub Watchers](https://img.shields.io/github/watchers/GAME-OVER-op/ZDT-D?style=flat-square&logo=github&label=Watchers) |
| Total downloads | ![Downloads](https://img.shields.io/github/downloads/GAME-OVER-op/ZDT-D/total?style=flat-square&label=Downloads) |
| Latest release | ![Latest Release](https://img.shields.io/github/v/release/GAME-OVER-op/ZDT-D?style=flat-square&label=Release) |
| Release date | ![Release Date](https://img.shields.io/github/release-date/GAME-OVER-op/ZDT-D?style=flat-square&display_date=published_at&label=Published) |
| Last commit | ![Last Commit](https://img.shields.io/github/last-commit/GAME-OVER-op/ZDT-D?style=flat-square&label=Last%20Commit) |
| Open issues | ![Issues](https://img.shields.io/github/issues/GAME-OVER-op/ZDT-D?style=flat-square&label=Issues) |
| Open pull requests | ![Pull Requests](https://img.shields.io/github/issues-pr/GAME-OVER-op/ZDT-D?style=flat-square&label=Pull%20Requests) |
| Repository size | ![Repository Size](https://img.shields.io/github/repo-size/GAME-OVER-op/ZDT-D?style=flat-square&label=Repo%20Size) |
| Contributors | ![Contributors](https://img.shields.io/github/contributors/GAME-OVER-op/ZDT-D?style=flat-square&label=Contributors) |
| Top language | ![Top Language](https://img.shields.io/github/languages/top/GAME-OVER-op/ZDT-D?style=flat-square&label=Top%20Language) |

<p align="center">
  <img src="https://img.shields.io/github/stars/GAME-OVER-op/ZDT-D?style=for-the-badge&logo=github&label=Stars" alt="Stars" />
  <img src="https://img.shields.io/github/downloads/GAME-OVER-op/ZDT-D/total?style=for-the-badge&label=Downloads" alt="Downloads" />
  <img src="https://img.shields.io/github/v/release/GAME-OVER-op/ZDT-D?style=for-the-badge&label=Release" alt="Release" />
</p>

<p align="center">
  <b>Development is active.</b><br/>
  ZDT-D is continuously evolving as a root-based Android traffic management platform.
</p>

---

## License

GPL-3.0 License — see [LICENSE](LICENSE).
