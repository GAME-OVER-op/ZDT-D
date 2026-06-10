# ZDT-D Android application

This directory contains the Android application used to control the ZDT-D root
module and the `zdtd` daemon.

The app is the user-facing controller. It is not the traffic engine itself. Root
operations, firewall rules, `netd` bindings and process orchestration are
performed by the module and daemon.

## Main responsibilities

The application provides:

- start/stop controls for the daemon runtime;
- profile management for supported programs;
- app selection and app-list editing;
- logs and live status views;
- configuration editors for program files;
- diagnostics screens;
- module installation/update helpers;
- backup/export helpers;
- settings such as language, theme and protection options;
- Quick Settings tile and widgets.

## Runtime relationship

```text
Android app -> local daemon API -> zdtd -> root helpers / iptables / netd / programs
```

The app talks to:

```text
http://127.0.0.1:1006/api/...
```

The API token is read from:

```text
/data/adb/modules/ZDT-D/api/token
```

Requests are sent with:

```text
Authorization: Bearer <token>
X-Api-Key: <token>
```

The app can also use root-assisted access when direct local HTTP access needs a
fallback.

## What the app does not do directly

The app does not directly:

- build final firewall routing rules;
- bind UIDs to Android `netd` networks;
- run NFQUEUE engines by itself;
- route traffic without the daemon;
- act as a standard Android `VpnService` VPN client;
- replace the Magisk/KernelSU/APatch module service scripts.

It writes settings and calls daemon APIs. The daemon applies root-level changes.

## Important paths

Module paths expected by the app and daemon:

```text
/data/adb/modules/ZDT-D
/data/adb/modules/ZDT-D/bin
/data/adb/modules/ZDT-D/api/token
/data/adb/modules/ZDT-D/api/status.json
/data/adb/modules/ZDT-D/log/zdtd.log
/data/adb/modules/ZDT-D/setting/setting.json
/data/adb/modules/ZDT-D/working_folder
/data/adb/modules/ZDT-D/strategic
```

App private binary extraction path is under:

```text
/data/data/com.android.zdtd.service/no_backup/bin
```

## Bundled assets

The APK can bundle generated assets such as:

- `zdt_module.zip` — module archive used by installer/update flows;
- `module.prop` — module metadata copied from the repository root;
- `dpi-detector` — native DPI diagnostic helper;
- `nfqws-tester` — native NFQWS strategy tester helper;
- BusyBox asset and checksums used by installer/runtime helpers.

The Gradle task `prepareZdtGeneratedAssets` prepares these files before Android
build tasks use them.

## Source tree overview

```text
MainActivity.kt                 Activity entry point
MainViewModel.kt                Main state, API calls, app actions and UI coordination
ZdtdActions.kt                  Action interface used by UI composables
RootConfigManager.kt            Root/module file access helpers
ConfigBridge.kt                 Settings/profile bridge helpers
AppUpdate.kt                    App/module update metadata
CrashLogger.kt                  Crash capture helper
DaemonStateReceiver.kt          Broadcast/status integration
DaemonStatusNotifier.kt         Notifications for daemon state
LocalWebPanelActivity.kt        Embedded local web panel wrapper
WorldMapActivity.kt             Network/world-map activity
api/ApiClient.kt                Local daemon API client
api/ApiModels.kt                API DTOs and helpers
api/DeviceInfo.kt               Device metadata helpers
tile/ZdtdTileService.kt         Quick Settings tile
ui/*                            Compose UI screens and components
widgets/*                       Home screen/widget dashboard
worldmap/*                      Network map models, root repository and UI
```

## UI areas

### Home

Shows daemon status, service power control, current state hints and daemon log
tail. The home screen is optimized for portrait and landscape layouts.

### Programs

Shows supported programs and opens their profile/configuration screens.
Program screens call daemon APIs to read/write settings and app lists.

### Apps

Shows installed applications and lets the user choose which packages belong to a
program/profile app list. Package names are later resolved to UIDs by the daemon.

### Stats

Displays runtime status, process/resource information and program state reported
by the daemon.

### Analysis tools

Hosts diagnostics such as DPI detector and NFQWS strategy tester.

### Settings

Contains app/theme/language settings and daemon-level options such as protection
or hotspot-related settings.

### Support

Shows project support links and related user information.

## Supported program/profile screens

The UI has dedicated screens for:

- `nfqws` / `nfqws2` profiles;
- byedpi;
- DPITunnel;
- dnscrypt;
- opera-proxy;
- sing-box;
- wireproxy;
- Tor;
- OpenVPN;
- AmneziaWG;
- tun2socks;
- myvpn;
- myproxy;
- myprogram;
- mihomo;
- mieru;
- blockedquic;
- proxyInfo;
- strategic files and strategic variables.

## Theme and language

The app supports theme mode selection:

```text
system
light
dark
```

Theme state is persisted through app/root settings and applied by the Compose
`ZdtdTheme` wrapper.

The app includes Russian and English resources. Russian strings live in:

```text
application/app/src/main/res/values-ru/strings.xml
```

Default strings live in:

```text
application/app/src/main/res/values/strings.xml
```

## Diagnostics integration

### dpi-detector

The app launches `dpi-detector` and reads NDJSON stdout to update the diagnostic
UI in real time. The helper is used for DNS, domain, TCP threshold, whitelist SNI
and Telegram connectivity checks.

### nfqws-tester

The app launches `nfqws-tester` to list strategy files, start temporary NFQUEUE
strategy sessions, stop them and display process usage/status.

## Logs

Main daemon log:

```text
/data/adb/modules/ZDT-D/log/zdtd.log
```

Program-specific logs are stored under each program/profile runtime directory
when supported. The UI reads logs through the daemon API or root file helpers.

## Permissions

The app uses permissions such as:

- `INTERNET` for local HTTP API access;
- cleartext traffic for `127.0.0.1` daemon API;
- package visibility permissions to list installed apps on Android 11+;
- notification/widget/tile related capabilities where applicable.

Root access is required for module-level operations.

## Build behavior

The Android build reads version information from root `module.prop`:

```text
version
versionCode
```

The app uses Android Gradle Plugin configuration in:

```text
application/build.gradle
application/app/build.gradle
```

Important build settings:

- min SDK: 26;
- target/compile SDK controlled by Gradle properties/environment;
- Compose enabled;
- debug and release are both minified/shrunk;
- signing config can be supplied by `application/keystore.properties`;
- fallback debug keystore path is `application/.tools/signing/debug.keystore`.

## Developer notes

When changing the app:

- keep API models compatible with `zdtd`;
- do not make UI write daemon runtime files behind the daemon's back unless the
  existing flow already does so;
- keep profile/app-list paths consistent with daemon code;
- preserve root/module fallback paths;
- verify Russian and default string resources together;
- be careful with profile screens that generate config files used by external
  engines.
