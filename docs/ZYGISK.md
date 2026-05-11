# ZDT-D Zygisk interface guard

ZDT-D ships an optional Zygisk native layer that complements the daemon-side VPN/netd runtime.

The Android application does not publish hook state. Zygisk reads the runtime data directly from the installed module working directory.

## Runtime inputs

Target applications are read once when an app process starts:

```text
/data/adb/modules/ZDT-D/working_folder/proxyInfo/out_program
```

The format is one app per line:

```text
package.name=uid
```

Only target UIDs stay loaded. Non-target processes ask Zygisk to unload the module.

For an already loaded target process, the following files are re-read dynamically through an adaptive TTL cache:

```text
/data/adb/modules/ZDT-D/setting/start.json
/data/adb/modules/ZDT-D/working_folder/proxyInfo/enabled.json
/data/adb/modules/ZDT-D/working_folder/vpn_netd/applied.json
```

Filtering is active only when both switches are enabled:

```json
{"enabled": true}
```

in `setting/start.json` and `working_folder/proxyInfo/enabled.json`.

Interfaces are extracted from `vpn_netd/applied.json` for ZDT-D VPN programs only:

- `openvpn`
- `tun2socks`
- `tun2proxy`
- `myvpn`
- `mihomo`
- `amneziawg`

Only exact interface names from the `tun` field are hidden. No broad masks such as `tun*`, `awg*`, or `wg*` are used.

## Dynamic refresh

Target selection from `out_program` is process-start only. If an app is added to `out_program` after it is already running, restart that app.

Runtime switches and interfaces are dynamic for already loaded target processes. The cache uses an adaptive TTL:

```text
30 -> 60 -> 120 -> 240 -> 400 seconds
```

When any parsed runtime state changes, TTL resets to 30 seconds. If nothing changes, TTL grows up to 400 seconds to reduce battery and filesystem overhead.

## Hooks

The current conservative Zygisk layer installs hooks during `postAppSpecialize` for target processes only:

- `getifaddrs`
- `open`
- `openat`
- `if_nametoindex`
- `if_indextoname`

Aggressive hooks such as `ioctl`, netlink `recvmsg`, and `/sys/class/net` directory filtering are not enabled by default.

## Diagnostics

Zygisk writes status files into:

```text
/data/adb/modules/ZDT-D/working_folder/zygisk_status.json
/data/adb/modules/ZDT-D/working_folder/zygisk_status_<uid>.json
```

The status includes target state, current runtime switches, interface count, hook count, commit status, adaptive TTL, and basic hit counters.
