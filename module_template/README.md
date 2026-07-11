# module_template

`module_template` is the base filesystem template used to build the ZDT-D root module ZIP.

During packaging, files from this directory are copied into the final module archive and are installed on the device under:

```text
/data/adb/modules/ZDT-D/
```

This directory is a template, not the live runtime directory. Runtime changes happen on the device after installation.

## Main installer files

- `customize.sh` — the module installer script used by Magisk, KernelSU, and APatch-compatible installers. It performs Android version checks, CPU architecture checks, optional Zygisk compatibility checks, file verification, and permission preparation.
- `service.sh` — boot-time startup script. It waits for Android boot completion, starts `bin/zdtd`, writes daemon logs, applies network sysctl defaults, and disables system Private DNS.
- `uninstall.sh` — uninstall cleanup script. It resets global proxy and captive portal settings and removes ZDT-D external installer markers.
- `verify.sh` — installer-side SHA-256 verification helper.
- `verify_sum/` — generated checksum files used by `verify.sh` during installation.

## Directories

### `META-INF/`

Android/Magisk ZIP installer entry files:

```text
META-INF/com/google/android/update-binary
META-INF/com/google/android/updater-script
```

Checksum files for these entries may also be present.

### `setting/`

Initial module settings. For example:

```text
setting/start.json
```

This file stores the initial service state used on fresh installs.

### `strategic/`

DPI bypass resources used by ZDT-D strategies.

- `strategic/bin/` — binary fake packets and protocol payloads, such as TLS ClientHello, QUIC Initial, WireGuard, STUN, DNS, NTP, and other payload samples.
- `strategic/certificate/` — certificate resources, including the CA bundle.
- `strategic/list/` — domain, host, IP, hostlist, and ipset lists used by strategies.
- `strategic/lua/` — Lua helper scripts for zapret/nfqws logic.
- `strategic/strategicvar/` — predefined strategy presets for `byedpi`, `dpitunnel`, `nfqws`, and `nfqws2`.
- `strategic/instructions.md` — notes and guidance for strategy resources.

### `working_folder/`

Initial runtime configuration and default profiles for ZDT-D programs.

Examples include:

- `working_folder/dnscrypt/` — default dnscrypt-proxy configuration, resolver lists, allowlists, blocklists, and related settings.
- `working_folder/nfqws/` — default nfqws profiles, including the default YouTube profile.
- `working_folder/nfqws2/` — initial nfqws2 profile state.
- `working_folder/operaproxy/` — default opera-proxy settings, byedpi arguments, SNI configuration, bootstrap DNS list, and port configuration.
- `working_folder/singbox/` — initial sing-box profile state.
- `working_folder/byedpi/` and `working_folder/dpitunnel/` — initial profile state for those programs.
- `working_folder/proxyInfo/` — reserved runtime location for proxy information features.

### `zygisk/`

Reserved module-side location for optional Zygisk files.

## Maintenance notes

Be careful when editing this directory:

- Changes here affect fresh installs and the final module ZIP package.
- Installer scripts are high-risk because they run with root privileges during module installation.
- Default configs in `working_folder/` define the initial state users receive after installing the module.
- Strategy resources in `strategic/` are used by bypass profiles and should not be reformatted or renamed casually.
- If files are added, removed, or changed, make sure the packaging and verification logic still handles them correctly.
