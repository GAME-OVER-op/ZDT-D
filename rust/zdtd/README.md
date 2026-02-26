# ZDTD (ZDT-D daemon)

Rust daemon for the Android/Magisk module **ZDT-D**.

It runs as **root**, manages network helper programs (nfqws/nfqws2/byedpi/dpitunnel/dnscrypt/operaproxy), applies/restores iptables rules, and exposes a **local-only HTTP API** for the Android app.

## Overview

This project is designed to run inside a Magisk module environment on Android:

- fixed module paths under `/data/adb/modules/ZDT-D`
- root-only execution
- Android-specific commands (`settings`, `cmd notification`, SELinux handling)
- local API bound to `127.0.0.1:1006`

It is **not** a generic desktop/server Linux daemon.

## Features

- Root-only daemon startup
- Local authenticated HTTP API (`127.0.0.1:1006`)
- Start/stop orchestration for supported programs:
  - `dnscrypt`
  - `nfqws`
  - `nfqws2`
  - `byedpi`
  - `dpitunnel`
  - `operaproxy`
- iptables/ip6tables baseline backup and restore
- Port normalization to avoid collisions
- Android boot wait (`sys.boot_completed`)
- Best-effort Android notifications integration
- Runtime logs + user-facing logs

## Project Structure

```text
src/
  main.rs              # Entry point (root check, daemon start)
  daemon.rs            # Daemon state, async start/stop, API lifecycle
  api.rs               # Local HTTP API server and route handling
  runtime.rs           # Full startup/shutdown orchestration
  stop.rs              # Service/process stop routines
  stats.rs             # Runtime status/process stats collection
  settings.rs          # Module paths, start.json, token/info files
  logging.rs           # Logging helpers
  shell.rs             # Shell command wrappers with timeouts/capture
  ports.rs             # Port normalization and suggestions

  iptables/            # iptables rule builders/helpers
  iptables_backup.rs   # Backup/restore baseline iptables state

  programs/            # Program-specific start logic
    dnscrypt.rs
    nfqws.rs
    nfqws2.rs
    byedpi.rs
    dpitunnel.rs
    operaproxy.rs

  android/             # Android-specific helpers
    boot.rs
    selinux.rs
    notification.rs
    pkg_uid.rs
```

## Requirements

- Android device
- Magisk module environment
- Root privileges
- Required binaries/scripts available in the module/runtime environment
- Rust toolchain (for building)

## Build

```bash
cargo build --release
```

Output binary:
- `target/release/zdtd`

> The binary must be executed with root privileges and inside the expected Android/Magisk environment.

## Run

```bash
su -c /path/to/zdtd
```

If started without root, the daemon exits with an error.

## Runtime Paths (fixed)

The daemon uses fixed module paths (current implementation):

- Module dir: `/data/adb/modules/ZDT-D`
- Settings dir: `/data/adb/modules/ZDT-D/setting`
- API dir: `/data/adb/modules/ZDT-D/api`

Important files:

- `start.json` — start/autostart state
- `api/token` — API auth token
- `api/info.json` — API bind info and token file path
- `iptables_backup.rules` — baseline IPv4 rules backup
- `ip6tables_backup.rules` — baseline IPv6 rules backup (if supported)

## HTTP API

The API is local-only and token-protected.

- Bind: `127.0.0.1:1006`
- Unauthorized requests receive an empty `404` (intentionally)

Observed main endpoints:

- `GET /api/status`
- `POST /api/start`
- `POST /api/stop`
- `GET /api/programs`
- `POST /api/new/profile`
- `POST /api/fs/read_text`
- `POST /api/fs/write_text`
- `POST /api/fs/list_dir`
- `/api/programs/...`
- `/api/strategic/...`
- `/api/strategicvar/...`

## Startup / Shutdown Flow (high level)

### Start (`runtime::start_full`)
- Wait for Android boot completion
- Ensure baseline iptables backup exists (first launch)
- Disable captive portal checks (best-effort)
- Truncate logs
- Normalize ports
- Temporarily switch SELinux to permissive (guarded)
- Restore baseline iptables
- Start services in order (`dnscrypt` first, then profiles, then opera proxy)
- Run sanity check; on failure disables autostart and stops services

### Stop (`runtime::stop_full`)
- Stop services/processes
- Restore baseline iptables
- Restore captive portal settings (best-effort)
- Log stop completion

## Notes for Development

- This codebase is tightly coupled to Android/Magisk paths and tools.
- The HTTP server is implemented manually over `TcpListener`/`TcpStream`, so route changes should be made carefully.
- Many operations are intentionally best-effort to keep the daemon resilient on device-specific firmware variations.

