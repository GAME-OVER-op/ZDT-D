# ZDT-D Zygisk layer

ZDT-D can install an optional Zygisk native component. The component is an
interface-visibility guard for selected Android applications: it hides ZDT-D VPN
or tunnel interfaces from common Java/JNI/native inspection paths while the main
ZDT-D daemon continues to own traffic routing, firewall rules, proxy processes,
and netd integration.

Zygisk is not required for the daemon, the Android application, Rust binaries,
T2s, or ordinary profile execution. It is an additional compatibility/privacy
layer for applications that inspect local network interfaces and react to VPN or
TUN-like adapters.

## Scope

The Zygisk layer is intentionally narrow.

It can hide interface visibility from target app processes through:

- libc network interface enumeration;
- interface name/index lookups;
- interface-related ioctl calls;
- selected `/proc/net` interface and route files;
- selected `/sys/class/net` interface metadata paths;
- `NETLINK_ROUTE` link/address/route replies;
- JNI/native libraries loaded after app specialization.

It does **not** hide or implement:

- root manager presence;
- Magisk, KernelSU, APatch, mount, or module traces;
- package names or installed applications;
- proxy ports or TCP/UDP sockets;
- traffic encryption or traffic routing;
- firewall/netd enforcement;
- daemon status or API access;
- app UID ownership decisions.

Those responsibilities belong to the daemon/root runtime and the profile-specific
program modules.

## Installation model

The installer includes the Zygisk library only when the user requested it from
the Android setup flow. The request is stored as a root-owned marker:

```text
/data/adb/ZDT-D/zygisk
```

During module installation:

- if the marker exists, the installer keeps `zygisk/arm64-v8a.so` in the module;
- if the marker does not exist, the installer removes the `zygisk/` directory
  from the installed module layout;
- if the marker exists but the native library is missing, installation fails with
  a Zygisk-specific error code.

Installed library path:

```text
/data/adb/modules/ZDT-D/zygisk/arm64-v8a.so
```

A reboot is required after changing the installed Zygisk component because it is
loaded by the root manager/Zygisk runtime during app process creation.

## Root manager requirements

### Magisk

- Magisk 26.0+ is required for the Zygisk API used by the module.
- Built-in Zygisk must be enabled in Magisk settings.

### KernelSU

- KernelSU 10940+ is required by the installer checks.
- KernelSU does not provide built-in Zygisk in the same way as Magisk.
- A compatible Zygisk layer must be installed, enabled, and running, for example
  ZygiskNext or ZygiskOnKernelSU.

### APatch / KernelPatch-compatible environments

- APatch 10700+ is required by the installer checks.
- A compatible Zygisk layer must be installed, enabled, and running.

If the root manager accepts the module but the Zygisk environment is missing or
broken, the Zygisk component may not load. The Android setup flow can remove the
marker and retry installation without the Zygisk component.

## Runtime inputs

The native code uses the already installed ZDT-D module files. It does not create
its own runtime configuration.

Primary runtime files:

```text
/data/adb/modules/ZDT-D/working_folder/proxyInfo/out_program
/data/adb/modules/ZDT-D/setting/start.json
/data/adb/modules/ZDT-D/working_folder/proxyInfo/enabled.json
/data/adb/modules/ZDT-D/working_folder/vpn_netd/applied.json
```

### `out_program`

`out_program` decides whether the current app UID is a Zygisk target.

Format:

```text
package.name=uid
```

Target selection happens in `preAppSpecialize` when the app process starts. If a
UID is not listed, the module closes its module directory file descriptor and
requests `DLCLOSE_MODULE_LIBRARY` so it does not remain loaded in that process.

If an app is added to `out_program` after the app is already running, restart the
app process. Existing non-target processes are not converted into targets while
running.

### `start.json` and `proxyInfo/enabled.json`

Both files must contain an enabled state for hiding to become active:

```json
{"enabled": true}
```

`working_folder/proxyInfo/enabled.json` remains compatible with the rest of
ZDT-D. Zygisk uses only the existing `enabled` boolean and does not read
Zygisk-specific feature keys from it.

### `vpn_netd/applied.json`

`applied.json` provides the active interface names created or managed by ZDT-D
VPN/tunnel profiles. Exact interface names from this runtime state are hidden
when the current process is a selected target and both runtime switches are
enabled.

The native layer also has a target-only tunnel-name fallback for cases where
`applied.json` is temporarily stale, incomplete, or uses a field unknown to this
native layer. The fallback is only evaluated inside already selected target UIDs.
It can match common tunnel-like names such as:

```text
tun*, tap*, wg*, awg*, utun*, ppp*, ipsec*, xfrm*, l2tp*, gre*, amneziawg*,
interfaces containing vpn, and compact if<number> names
```

Normal mobile/Wi-Fi interfaces such as `wlan0`, `rmnet_data0`, `ccmni0`, `eth0`,
and `lo` are not part of that fallback policy.

## Read-only policy

The Zygisk layer is designed to be read-only inside app processes.

It does not write:

```text
state.json
debug.json
working_folder/zygisk/*
working_folder/zygisk_status.json
working_folder/zygisk_status_<uid>.json
```

It also does not persist counters, hook state, debug reports, or per-app status
files. Runtime state is kept in memory and rebuilt from existing ZDT-D files.

## Runtime refresh

For target processes, runtime switches and interface data are refreshed through
an adaptive TTL cache.

Current TTL levels:

```text
2 seconds -> 10 seconds -> 30 seconds
```

When parsed runtime state changes, the TTL returns to the fast level. When the
state is stable, the TTL grows to reduce repeated filesystem reads inside app
processes.

If `/data/adb/modules/...` absolute paths are not visible after the module
directory fd is closed, the module keeps the last good in-memory state instead
of disabling protection immediately. The first target load is done through the
module directory fd before it is closed.

The dynamic inotify watcher exists in the native code but is disabled by default.

## Hook lifecycle

### `preAppSpecialize`

The module:

1. resets process-local state;
2. skips child zygote, isolated UID, and forbidden process names;
3. reads `out_program` through the module directory fd;
4. confirms whether the current UID is a target;
5. unloads immediately for non-target processes;
6. loads the initial runtime state for target processes;
7. closes the module directory fd to avoid leaving a visible fd into
   `/data/adb/modules` inside protected apps.

### `postAppSpecialize`

For confirmed target processes, the module:

1. refreshes runtime state;
2. installs libc-level inline hooks where supported;
3. installs libdl inline hooks as load triggers;
4. hooks `RegisterNatives` as a JNI fallback trigger;
5. registers a limited framework PLT fallback through the Zygisk API;
6. refreshes hidden interface indices;
7. scans/patches late-loaded app-owned native libraries when needed.

The primary path is libc-level hooking. PLT/GOT fallback is used to catch paths
not covered by inline hooks and to handle native libraries loaded later from APK
or extracted `.so` mappings.

## Hook coverage

The active profile is built into the native library. There is no separate Zygisk
feature configuration file.

### Interface enumeration and name/index checks

```text
getifaddrs
if_nametoindex
if_indextoname
ioctl
```

The hooks remove hidden interfaces from enumeration, block recovery of hidden
interface indices/names, and guard common interface ioctl probes.

### `/proc/net` reads

```text
open
openat
fopen
fopen64
```

Supported interface/route files include:

```text
/proc/net/dev
/proc/net/route
/proc/net/if_inet6
/proc/net/ipv6_route
/proc/self/net/dev
/proc/self/net/route
/proc/self/net/if_inet6
/proc/self/net/ipv6_route
```

Relative `openat()` cases are handled when an app opens `/proc/net` or
`/proc/self/net` first and then opens files such as `dev`, `route`, `if_inet6`,
or `ipv6_route` relative to that directory fd.

### `/sys/class/net` visibility

```text
open
openat
opendir
readdir
readdir64
closedir
access
faccessat
stat
stat64
lstat
lstat64
fstatat
fstatat64
statx
readlink
readlinkat
```

The module denies direct metadata and existence checks for hidden interfaces,
for example:

```text
/sys/class/net/<hidden_iface>
/sys/class/net/<hidden_iface>/mtu
/sys/class/net/<hidden_iface>/type
/sys/class/net/<hidden_iface>/operstate
/sys/class/net/<hidden_iface>/carrier
/sys/class/net/<hidden_iface>/address
/sys/class/net/<hidden_iface>/ifindex
```

### `NETLINK_ROUTE` replies

```text
recv
recvmsg
recvfrom
read
readv
```

The module filters complete netlink reply buffers only when the fd is proven to
be an `AF_NETLINK` socket. Normal files, TCP, UDP, TLS, and Unix sockets pass
through untouched.

Filtered route/link/address families include interface names and interface
indices from link, address, and route dump replies.

### Late native libraries

Late native probes can appear after `postAppSpecialize` through:

```text
System.loadLibrary(...)
dlopen(...)
android_dlopen_ext(...)
JNI_OnLoad / RegisterNatives
```

The module uses libdl and JNI triggers to rescan process mappings and patch
newly loaded app-owned libraries. This covers both extracted `.so` files and
native libraries mapped directly from `base.apk` or split APKs.

Chromium/WebView libraries, linker mappings, libc/libdl themselves, and the
ZDT-D Zygisk library are excluded from app-owned late patching.

## Safety model

The Zygisk component is limited to selected target UIDs and interface visibility
signals. It avoids broad system-wide hiding and does not keep a module directory
fd open in target app processes after the initial load.

Important constraints:

- non-target processes unload immediately;
- target selection is UID-based;
- runtime is read-only;
- Zygisk-specific settings are not stored in `enabled.json`;
- no debug/status files are written from target app processes;
- netlink buffers are touched only after fd/domain and payload validation;
- late patching avoids WebView/Chromium and core linker/libc/libdl mappings.

## Troubleshooting

### Component is not installed

Check whether the setup marker exists before installation:

```text
/data/adb/ZDT-D/zygisk
```

If the marker is absent, the installer intentionally removes the `zygisk/`
directory from the installed module.

### Component was requested but installation failed

Common causes:

- missing `zygisk/arm64-v8a.so` in the package;
- Magisk version below 26.0;
- KernelSU version below 10940;
- APatch version below 10700;
- KernelSU/APatch without a running compatible Zygisk layer.

The Android setup flow can retry installation without Zygisk by removing the
marker.

### Hooks do not affect an app

Check the target conditions:

1. the app UID is present in `working_folder/proxyInfo/out_program`;
2. the app process was restarted after target changes;
3. `setting/start.json` contains `{"enabled": true}`;
4. `working_folder/proxyInfo/enabled.json` contains `{"enabled": true}`;
5. `working_folder/vpn_netd/applied.json` contains the active interface, or the
   target-only tunnel-name fallback applies;
6. the root manager has actually loaded the Zygisk component.

### A protected app still sees proxy/root signals

That is outside this component's scope. The Zygisk layer hides network interface
visibility, not root state, module state, proxy ports, or traffic routing.

## Related files

```text
zygisk/src/main.cpp                 native implementation
zygisk/include/zygisk.hpp           local Zygisk API header
zygisk/build.sh                     arm64 build helper
zygisk/CMakeLists.txt               guarded CMake configuration
zygisk/README.md                    component build/development notes
module_template/customize.sh        installer-side Zygisk checks
application/.../MainViewModel.kt    setup marker and retry flow
```
