# ZDT-D Zygisk native module

This directory contains the native Zygisk component used by ZDT-D. The build
produces the optional module library:

```text
zygisk/arm64-v8a.so
```

The high-level runtime documentation lives in `docs/ZYGISK.md`. This file focuses
on source layout, build behavior, packaging, and development rules for the native
component itself.

## Source layout

```text
zygisk/
├── CMakeLists.txt          guarded CMake configuration
├── README.md               this file
├── build.sh                preferred build helper
├── include/zygisk.hpp      local Zygisk API header
└── src/main.cpp            native implementation
```

The implementation is intentionally self-contained. It avoids C++ exceptions,
RTTI, and the C++ standard library runtime so the final `.so` can be loaded by
Zygisk without an additional `libc++`/`libstdc++` dependency.

## Build outputs

Default checked build:

```bash
./build.sh
```

Default output:

```text
out/arm64-v8a.so
```

Build directly into the module layout:

```bash
./build.sh module
```

Module output:

```text
zygisk/arm64-v8a.so
```

Build to an explicit path:

```bash
./build.sh /path/to/arm64-v8a.so
```

`build.sh` validates that:

- `zygisk_module_entry` is exported;
- the output does not depend on `libc++` or `libstdc++`;
- the output is stripped when a suitable strip tool is available.

## Toolchain selection

`build.sh` chooses a compiler in this order:

1. `ZDT_ZYGISK_CLANG`, when explicitly provided;
2. Termux/Android `clang++` on an arm64 Android host;
3. Android NDK `aarch64-linux-android<API>-clang++` from common NDK locations;
4. `aarch64-linux-android<API>-clang++` from `PATH`;
5. fallback host `clang++ -target aarch64-linux-android<API>` for lightweight
   development checks.

The default Android API level is `24`. Override it with:

```bash
ANDROID_API_LEVEL=26 ./build.sh
```

On Termux, the script can install missing `clang`, `binutils`, and `file` through
`pkg` when the package manager is available.

## CMake usage

`CMakeLists.txt` is guarded against accidental host release builds. Prefer
`build.sh` for real outputs.

A non-release host CMake syntax/configuration check can be forced with:

```bash
ZDT_ZYGISK_ALLOW_HOST_CMAKE=1 cmake -S zygisk -B /tmp/zdt-zygisk-check
```

Release/device artifacts should be produced with the Android NDK or with the
Termux/Android arm64 path handled by `build.sh`.

## Packaging into ZDT-D

The Android setup flow writes this marker when the user requests the optional
component:

```text
/data/adb/ZDT-D/zygisk
```

The module installer checks that marker:

- marker exists: keep `zygisk/arm64-v8a.so` and validate root-manager
  requirements;
- marker does not exist: remove the installed `zygisk/` directory;
- marker exists but library is absent: fail installation with a Zygisk-specific
  error.

Installed runtime path:

```text
/data/adb/modules/ZDT-D/zygisk/arm64-v8a.so
```

## Runtime summary

The native module is a read-only interface visibility guard for selected target
UIDs. It complements the daemon and does not replace daemon-side routing,
firewall, netd, or proxy logic.

Runtime files read by the component:

```text
working_folder/proxyInfo/out_program
setting/start.json
working_folder/proxyInfo/enabled.json
working_folder/vpn_netd/applied.json
```

No Zygisk-specific runtime config file is required.

The component does not write:

```text
state.json
debug.json
working_folder/zygisk/*
working_folder/zygisk_status.json
working_folder/zygisk_status_<uid>.json
```

## Process lifecycle

### `preAppSpecialize`

The module resets process-local state, reads `out_program`, and decides whether
the current app UID is a target. Non-target processes request
`DLCLOSE_MODULE_LIBRARY` and do not keep the module loaded.

For target processes, initial runtime state is loaded through the module
directory fd, then the fd is closed to avoid leaving a visible descriptor into
`/data/adb/modules`.

### `postAppSpecialize`

For target processes, hooks are installed in this order:

1. libc-level inline hooks;
2. libdl inline hooks used as late-load triggers;
3. JNI `RegisterNatives` trigger;
4. limited framework PLT fallback through the Zygisk API;
5. late app-owned PLT/GOT fallback for JNI libraries loaded after specialize.

This ordering keeps libc trampolines as the primary original-call path and avoids
having framework/app PLT originals overwrite the same `orig_*` slots.

## Current hook groups

### Interface enumeration

```text
getifaddrs
if_nametoindex
if_indextoname
ioctl
```

### Proc/net and stdio reads

```text
open
openat
fopen
fopen64
```

### Sysfs metadata and existence checks

```text
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

### Netlink route payload reads

```text
recv
recvmsg
recvfrom
read
readv
```

Netlink payloads are modified only when the fd is proven to be `AF_NETLINK` and
the buffer looks like a complete netlink reply. Normal files and non-netlink
sockets are left untouched.

### Late loading triggers

```text
dlopen
android_dlopen_ext
RegisterNatives
```

Late app-owned native mappings can be extracted `.so` files or libraries mapped
directly from `base.apk` / split APKs. The patch tracker keys by device, inode,
and ELF base address so multiple libraries from the same APK inode can be handled
separately.

## Runtime feature profile

The built-in profile enables interface hiding for selected target UIDs:

- `getifaddrs` filtering;
- interface name/index filtering;
- interface `ioctl` guarding;
- `/proc/net` interface and route filtering;
- `/sys/class/net` metadata hiding;
- `NETLINK_ROUTE` link/address/route filtering;
- late app-owned PLT/GOT fallback;
- target-only tunnel-name fallback.

The dynamic inotify watcher is present but disabled by default. Debug log spam is
also disabled by default.

Do not add Zygisk-specific keys to `working_folder/proxyInfo/enabled.json`; the
file must remain compatible with the rest of ZDT-D and is read only for its
existing `enabled` boolean.

## Safety rules for changes

When modifying `src/main.cpp`, keep these constraints:

1. Non-target processes must unload immediately.
2. The module must remain read-only in app processes.
3. Do not introduce new runtime files unless the installer, Android app, and
   documentation are updated together.
4. Do not keep `/data/adb/modules` fds open after target initialization.
5. Do not broaden the component into root/mount/proxy-port hiding without a
   separate design review.
6. Do not patch WebView/Chromium, linker, libc, libdl, or the ZDT-D Zygisk
   library through the app-owned late patcher.
7. Validate socket domain before touching received netlink payloads.
8. Keep `enabled.json` schema-compatible with the existing project format.
9. Preserve the no-STL-runtime build property.

## Quick validation checklist

After a Zygisk source change:

```bash
./zygisk/build.sh
readelf -Ws zygisk/out/arm64-v8a.so | grep ' zygisk_module_entry$'
readelf -d zygisk/out/arm64-v8a.so | grep -E 'libc\+\+|libstdc\+\+' && false || true
```

When packaging into the module layout:

```bash
./zygisk/build.sh module
ls -l zygisk/arm64-v8a.so
```

Runtime validation should cover at least:

- install without the marker: `zygisk/` is removed from the installed module;
- install with the marker: `zygisk/arm64-v8a.so` remains installed;
- non-target app: module unloads and app behavior is unchanged;
- target app: hidden interfaces disappear from `getifaddrs`, `/proc/net`,
  `/sys/class/net`, and netlink route probes;
- target app with late JNI library: late native probe is also filtered;
- KernelSU/APatch environment without compatible Zygisk layer: installer/user
  flow reports that the external layer is required.

## Related documentation

- Main Zygisk documentation: `docs/ZYGISK.md`
- General program architecture: `docs/PROGRAMS.md`
- Android setup flow: `application/README.md`
- Installer logic: `module_template/customize.sh`
