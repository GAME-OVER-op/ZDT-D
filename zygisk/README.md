# ZDT-D Zygisk native layer

This directory contains the source code for the ZDT-D Zygisk `arm64-v8a.so` module.

## Build

Default checked build:

```bash
./build.sh
```

Default output:

```text
out/arm64-v8a.so
```

Build directly into the Magisk module layout:

```bash
./build.sh module
```

Module output:

```text
zygisk/arm64-v8a.so
```

You can also pass an explicit output path:

```bash
./build.sh /path/to/arm64-v8a.so
```

`build.sh` validates that `zygisk_module_entry` is exported and that the output does not depend on `libc++`/`libstdc++`.

`CMakeLists.txt` is intentionally guarded against accidental host release builds. Use the Android/NDK toolchain or `build.sh`. A non-release host CMake syntax check can be forced with `ZDT_ZYGISK_ALLOW_HOST_CMAKE=1`.

## Runtime model

Zygisk is read-only. It does not create files, does not update counters on disk, and does not write debug reports.

The module uses only the existing ZDT-D runtime files:

```text
setting/start.json
working_folder/proxyInfo/enabled.json
working_folder/proxyInfo/out_program
working_folder/vpn_netd/applied.json
```

No additional runtime files or directories are required.

`out_program` decides whether the current app UID should receive hooks.

`start.json` and `proxyInfo/enabled.json` decide whether interface hiding is enabled.

`applied.json` provides the active interface names. The parser accepts existing `tun` fields and also understands future interface-style fields such as `interface` and `ifname`. `owner_program` remains compatibility metadata only.

Known legacy owners include:

```text
openvpn
tun2socks
tun2proxy
myvpn
mihomo
amneziawg
mieru
```

Only exact interface names from ZDT-D runtime are hidden. Broad prefixes such as `tun*`, `awg*`, or `wg*` are intentionally not used.

The Zygisk layer hides network interface visibility only. Traffic protection, proxy protection, routing restrictions, and UID firewall behavior remain the responsibility of the daemon/root iptables layer.

## Dynamic read-only refresh

For target app processes, the module keeps the runtime state dynamic without writing anything to disk.

The existing files are re-read from the module directory fd or from the absolute fallback paths:

```text
setting/start.json
working_folder/proxyInfo/enabled.json
working_folder/proxyInfo/out_program
working_folder/vpn_netd/applied.json
```

Refresh behavior:

```text
1. Initial target check happens in preAppSpecialize using out_program.
2. If the UID is a target, hooks are installed and the existing runtime files are read.
3. By default, no inotify watcher is left inside the target app process.
4. The module keeps the last good in-memory state if absolute runtime paths are not visible after the module fd is closed.
5. Optional dynamic refresh can be enabled through the existing enabled.json file.
6. Non-target processes still unload immediately and require app restart if they are later added to out_program.
```

No `state.json`, no debug files, and no new runtime directories are used.

## Stage 03 interface-read hardening

This stage keeps the same read-only model and does not introduce new runtime files.

Additional interface-only concealment:

```text
/proc/net/ipv6_route
/proc/self/net/ipv6_route
```

Relative proc openings are handled when an app opens `/proc/net` or `/proc/self/net` first and then uses `openat()` for:

```text
dev
route
if_inet6
ipv6_route
```

Native `fopen()` / `fopen64()` reads are also filtered for the supported interface proc files. This is needed for native probes that read `/proc/net/*` through stdio rather than directly calling `open()` or `openat()`.

Direct reads of hidden interface sysfs entries are denied with `ENOENT` for paths such as:

```text
/sys/class/net/<hidden_iface>
/sys/class/net/<hidden_iface>/mtu
/sys/class/net/<hidden_iface>/type
/sys/class/net/<hidden_iface>/operstate
/sys/class/net/<hidden_iface>/carrier
/sys/class/net/<hidden_iface>/address
/sys/class/net/<hidden_iface>/ifindex
```

`SIOCGIFNAME` now checks `ifr_ifindex` before calling the real ioctl, so hidden interface names cannot be recovered from a known interface index.

## Stage 04 netlink route hardening

This stage still uses only the existing ZDT-D runtime files and does not create new runtime files.

`NETLINK_ROUTE` filtering now also handles route dump responses:

```text
RTM_NEWROUTE
```

Routes are hidden when they reference a hidden interface index through:

```text
RTA_OIF
RTA_IIF
```

This closes native probes that request routes with `RTM_GETROUTE` and detect a default route through the protected interface, for example `tun1`.

`RTM_NEWLINK` filtering was also strengthened: if the link message contains a hidden interface index, it is hidden even when the `IFLA_IFNAME` attribute is absent.

## Stage 05 sysfs existence-check hardening

This stage still keeps the Zygisk layer read-only and uses only the existing ZDT-D runtime files.

Direct existence and metadata probes for hidden interfaces under `/sys/class/net` are now denied with `ENOENT`, not only `open()`/`opendir()` reads.

Additional hooked calls:

```text
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

The same hidden-interface path policy is applied to absolute paths and to relative `*at()` calls when the directory fd points to `/sys/class/net` or to a hidden interface directory.

Examples that are now blocked when `<hidden_iface>` is active:

```text
/sys/class/net/<hidden_iface>
/sys/class/net/<hidden_iface>/mtu
/sys/class/net/<hidden_iface>/type
/sys/class/net/<hidden_iface>/operstate
/sys/class/net/<hidden_iface>/carrier
/sys/class/net/<hidden_iface>/address
/sys/class/net/<hidden_iface>/ifindex
```

This only hides interface/sysfs visibility. TCP/UDP proc files, proxy ports, root markers, mounts, and traffic enforcement are still intentionally outside the Zygisk layer.

## Stage 06 late native library hardening

This stage targets Android apps that load native probes after `postAppSpecialize`, for example through:

```text
System.loadLibrary(...)
dlopen(...)
android_dlopen_ext(...)
```

The initial Zygisk PLT registration still handles libraries already present in `/proc/self/maps` during `postAppSpecialize`. Stage 06 adds an additional in-process PLT/GOT patcher for app-owned libraries that appear later.

Behavior:

```text
1. Initial Zygisk hooks also register dlopen/android_dlopen_ext where those imports exist.
2. After a successful late library load, the module re-scans /proc/self/maps.
3. Newly mapped app-owned .so files are patched in memory.
4. The same interface-only hooks are applied to late libraries.
5. No files are created and nothing is written to disk.
```

The late patcher is intentionally scoped:

```text
- app-owned native libraries under /data/app, /data/user, or /mnt/expand
- selected Android framework loader/network libraries needed to catch System.loadLibrary
- no Chromium/WebView libraries
- no libc/libdl/linker patching
- no proxy/tcp/udp/root/mount filtering
```

This is meant to close probes where Java/JVM checks are clean but a late-loaded native library still sees a hidden interface through native calls such as `getifaddrs()`, `ioctl()`, `/proc/net/*`, `/sys/class/net/*`, or `NETLINK_ROUTE`.

## Stage 07/08 built-in runtime profile

The module keeps the same read-only model. No new runtime files are introduced.

`working_folder/proxyInfo/enabled.json` is kept compatible with the rest of ZDT-D and is used only for its existing `enabled` true/false value. Do not add Zygisk-specific keys to this file.

The interface-hiding profile is now built into the native library by default for selected target UIDs:

```text
- getifaddrs hiding
- if_nametoindex / if_indextoname hiding
- interface ioctl hiding
- /proc/net interface-route filtering
- /sys/class/net metadata hiding
- NETLINK_ROUTE link/address/route filtering
- app-owned late PLT/GOT fallback for JNI libraries loaded from APK/.so
- tunnel-name fallback matcher
```

Riskier or noisy parts stay disabled by default:

```text
- libc inline hook engine
- dynamic inotify watcher
- debug logcat spam
```

Zygisk still does not create files, does not write debug reports, and does not use `state.json`.

## Stage 08 stability notes

- Default/legacy behavior now keeps the hook surface closer to core network introspection calls: getifaddrs, interface index/name lookup, interface ioctl, `/proc/net` reads, and NETLINK_ROUTE replies.
- Direct `/sys/class/net/<iface>` metadata hiding is now part of the built-in default for selected target UIDs. No extra `enabled.json` keys are required.
- `getifaddrs()` now has an internal ioctl guard so libc can build its interface list normally before ZDT-D filters the final result.
- `recv`/`recvmsg` now prove the socket domain through `SO_DOMAIN` before touching a payload; non-netlink sockets pass through untouched.

## Stage 09 libc-level interface hooks

Stage 09 changes the stable hook strategy to reduce crashes in protected apps and to catch native checks from libraries loaded after `postAppSpecialize`.

The main stable path now hooks selected `libc.so` entry points in memory:

```text
getifaddrs
ioctl
openat
recvmsg
recv
if_nametoindex
if_indextoname
```

This means a native library loaded later through `System.loadLibrary()` or `dlopen()` still passes through the same interface-hiding logic when it calls these libc functions.

Stage 12 changes this again for visibility: app-owned PLT/GOT fallback is used by default because APK-embedded native libraries can bypass the libc-inline path on some devices. Initial Zygisk PLT hooks remain a small framework fallback; app-owned patching is still interface-only and does not touch proxy/tcp/udp/root signals.

The libc inline hook engine remains disabled by default. No `enabled.json` override is required.


No new runtime files were added:

```text
state.json: not used
debug.json: not used
working_folder/zygisk: not used
```

Default built-in mode is used automatically. Keep `enabled.json` in its existing project format, for example:

```json
{
  "enabled": true
}
```

## Stage 10 hook-engine hardening

Stage 10 keeps the same read-only runtime model and focuses on making the libc-level hook path safer and more useful for late native probes.

Changes:

```text
1. The inline hook engine no longer blindly copies the first bytes of a libc function.
2. Simple exported branch stubs are resolved before patching the real target.
3. AArch64 prologues are checked before patching.
4. Prologues with PC-relative/control-flow instructions are skipped instead of being patched unsafely.
5. BTI landing pads are preserved when present.
6. Trampoline memory is allocated RW first and switched to RX after writing.
7. GNU/SysV dynsym lookup now validates string-table offsets.
8. Hook install diagnostics remain compiled in but disabled by default; no files are written.
9. No debug files are written.
```

Additional libc-level coverage was added for late-loaded native libraries:

```text
open
openat
fopen
fopen64
recv
recvmsg
recvfrom
read
readv
getifaddrs
ioctl
if_nametoindex
if_indextoname
```

`recvfrom`, `read`, and `readv` only filter buffers when the fd is proven to be an `AF_NETLINK` socket and the payload looks like a complete netlink reply. Normal files, TCP, UDP, TLS, and Unix sockets pass through untouched.

No `enabled.json` feature keys are required after this patch. Debug logging remains disabled by default.

## Stage 11 hook ordering fix

Stage 11 fixes the hook installation order so the libc-level path remains the primary path.

Important change:

```text
1. libc inline hooks are installed before framework PLT fallback hooks.
2. If a libc trampoline is already installed for a core symbol, the same symbol is skipped in the Zygisk PLT fallback.
3. This prevents the shared orig_* function pointer slots from being filled or overwritten by framework/app PLT originals.
4. Late native libraries loaded through System.loadLibrary/dlopen are still covered by the libc-level path.
5. Framework PLT hooks remain only as a secondary fallback for symbols not covered by libc inline hooks and for optional sysfs helpers.
```

Why this matters:

```text
If PLT fallback runs first, orig_getifaddrs/orig_ioctl/orig_openat may become non-null before libc inline installation.
The inline installer then sees an existing original pointer and skips installing the real libc hook.
That leaves late JNI/native checks outside the primary protection path.
```

Stage 11 keeps the same restrictions:

```text
No new runtime files.
No state.json.
No debug.json.
No writes from Zygisk.
No proxy/tcp/udp/root hiding.
```


## Stage 12 visibility fallback for APK-loaded native libraries

Stage 12 focuses on the case where a new native tester still sees `tun1` even though the older tester is clean.

The important Android packaging detail is that apps can load native libraries directly from `base.apk` / split APK mappings. In `/proc/self/maps` such a library may appear as an APK path with a non-zero file offset, not as `/data/app/.../libxxx.so`. Earlier stages only patched extracted `.so` mappings with offset `0`, so a late-loaded JNI library packed inside APK could stay unpatched and still call `getifaddrs()`, `ioctl()`, `fopen()`, and `recv()` normally.

Changes:

```text
1. App-owned PLT/GOT fallback is enabled by default again.
2. libc-inline hooks are now opt-in instead of default.
3. normal/compat/stealth mode enables late app-owned fallback.
4. strong mode enables late app-owned fallback plus direct /sys/class/net metadata hiding.
5. APK-backed native mappings are now detected, not only extracted .so files.
6. The late patch tracker now keys by dev + inode + ELF base address, not only dev + inode.
   This matters because several native libraries can be mapped from the same base.apk inode at different offsets.
7. The old offset==0 requirement was removed for valid in-memory ELF mappings.
8. No new runtime files were added.
9. Zygisk still writes nothing to disk.
10. Only interface/proc/sysfs/netlink interface visibility is touched.
```

Maximum interface hiding is now the built-in default for selected target UIDs. `enabled.json` should not contain Zygisk feature keys.


## Stage 13 patch notes

This patch keeps the module read-only and continues to use only existing ZDT-D files.

Important compatibility change:

```text
working_folder/proxyInfo/enabled.json is used only for its original enabled true/false value.
No Zygisk-specific keys are read from it anymore.
No new config file is required.
```

Built-in defaults now enable interface hiding strongly enough for selected target UIDs:

```text
getifaddrs / if_nametoindex / if_indextoname / ioctl
/proc/net dev/route/if_inet6/ipv6_route
/sys/class/net/<hidden_iface> metadata
NETLINK_ROUTE link/address/route replies
late app-owned PLT/GOT fallback for JNI libraries loaded from APK/.so
tunnel-name fallback matcher
```

Still disabled by default:

```text
libc inline hook engine
dynamic inotify watcher
debug logcat
```

No new runtime files were added:

```text
state.json: not used
debug.json: not used
working_folder/zygisk: not used
```
