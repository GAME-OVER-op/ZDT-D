# ZDT-D Zygisk native layer

This directory contains the source code for the ZDT-D Zygisk `arm64-v8a.so` module.

The shared library is built locally and is not stored in the repository. `build.sh module` copies the generated output into the temporary Magisk module tree as:

```text
zygisk/arm64-v8a.so
```

## Runtime model

Zygisk target applications are read once per process from:

```text
working_folder/proxyInfo/out_program
```

For target processes, hooks remain installed and the active runtime config is refreshed dynamically from:

```text
setting/start.json
working_folder/proxyInfo/enabled.json
working_folder/vpn_netd/applied.json
```

Filtering is active only when both `enabled` files contain `true` and `applied.json` contains exact ZDT-D VPN interface names.

The refresh cache is adaptive: it starts at 30 seconds and grows to 400 seconds while the runtime state is unchanged. Any detected change resets it to 30 seconds.

Only exact interface names from ZDT-D runtime are hidden. Broad prefixes such as `tun*`, `awg*`, or `wg*` are intentionally not used.
