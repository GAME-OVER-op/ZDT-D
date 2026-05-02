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
    <img src="https://img.shields.io/badge/Telegram-Join%20Group-blue?style=flat-square&logo=telegram" alt="Telegram Group" />
  </a>
</p>

## Description

**ZDT-D** is a root-based Android network orchestration project for advanced traffic routing, DPI circumvention, DNS handling, local proxy pipelines, and selective VPN/TUN binding.

It is not a classic Android VPN application and it is not limited to one bundled engine. ZDT-D uses a local root daemon, Android application UIDs, `iptables` / `ip6tables`, NFQUEUE, local loopback services, and Android `netd` to route selected applications through different processing paths.

The project includes:

- a local Rust daemon (`zdtd`)
- an Android application for configuration and status control
- bundled networking tools for different routing and compatibility scenarios
- internal builders for UID-based redirection and Android `netd`-based TUN binding

> The Android app is available in Russian and English.

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

## Custom programs and extensibility

A major goal of ZDT-D is extensibility.

The project is not limited to pre-defined tools. Users can add their own network programs and combine them with ZDT-D routing features.

For example:

- `myprogram` can launch a user-provided binary or script
- that binary can create a local proxy, a service, or a TUN interface
- `myvpn` can bind selected applications to an already existing TUN interface
- the daemon can still handle UID parsing, conflict checks, and Android `netd` binding

This allows ZDT-D to be used as a base for custom Android networking setups, not only as a ready-made module with fixed behavior.

## Routing models

ZDT-D supports multiple independent traffic handling paths.

### NFQUEUE path

Selected application traffic can be matched by UID and sent into NFQUEUE. A userspace DPI engine can then inspect or modify packets.

### Transparent local redirection

Selected application traffic can be redirected to a local listener on `127.0.0.1:<port>`. Local helper programs then forward or process the stream.

### Android netd / TUN binding

When a supported program creates or exposes a TUN interface, ZDT-D can bind selected application UIDs to that interface through Android `netd`.

This model is used by OpenVPN, tun2socks, and the universal `myvpn` binding.

### DNS handling

ZDT-D can manage local DNS components such as dnscrypt-proxy and route DNS-related traffic in controlled scenarios.

## Conflict control

Because several programs can target the same applications, ZDT-D checks app-list conflicts.

An application should not be assigned to multiple incompatible network pipelines at the same time. This reduces broken routing, duplicated redirection, and hard-to-debug conflicts between profiles.

Some helper features, such as QUIC blocking, can be used alongside other routing modes when they do not conflict with the main traffic path.

## Documentation

Detailed information about supported programs and internal components is available in:

- [`docs/PROGRAMS.md`](docs/PROGRAMS.md)

Practical usage notes, troubleshooting, and advanced examples may be kept in:

- `INSTRUCTIONS.md`

## Privacy

ZDT-D does not collect, transmit, sell, share, or use personal data.

All configuration, routing, rule management, and runtime control required for the module to work are performed locally on the installed device.

The project does not require remote telemetry or analytics for core functionality.

If the application connects to external resources, it does so only for actions explicitly requested by the user, such as checking releases or downloading updates from official upstream sources.

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

## License

GPL-3.0 License — see [LICENSE](https://github.com/GAME-OVER-op/ZDT-D/blob/main/LICENSE).

## Downloads

- [Releases](https://github.com/GAME-OVER-op/ZDT-D/releases)
