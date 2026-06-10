# dpi-detector

`dpi-detector` is a native Rust diagnostic helper bundled with the ZDT-D Android
application. It runs network checks and streams progress to the UI so the user can
see possible DNS, DPI, blocking or slowdown symptoms.

The helper is a diagnostic binary only. It does not apply firewall rules, does
not start ZDT-D profiles and does not change system routing.

## Android integration

The Android app bundles the binary as an asset and extracts it to a private app
path similar to:

```text
/data/data/com.android.zdtd.service/no_backup/bin/dpi-detector
```

The app launches it through root and reads stdout line by line. For interactive
UI updates the app normally uses NDJSON output.

## Commands

```bash
dpi-detector --version
dpi-detector self-test
dpi-detector list-tests
dpi-detector run [options]
```

Examples:

```bash
dpi-detector run --format ndjson
```

```bash
dpi-detector run --format ndjson --quick
```

```bash
dpi-detector run \
  --format ndjson \
  --tests dns_integrity,dns_availability,domains,tcp16,whitelist_sni,telegram
```

```bash
dpi-detector run \
  --format ndjson \
  --domain example.com \
  --proxy socks5://127.0.0.1:1080 \
  --concurrency 50
```

## Run options

- `--format text|ndjson` — output format. `text` is the default; Android UI uses
  `ndjson`.
- `--tests <LIST>` — comma-separated test IDs.
- `--timeout <MS>` — per-probe timeout in milliseconds.
- `--quick` — smaller target lists and faster scan profile.
- `--max-domains <N>` — limit domain availability targets.
- `--max-tcp-targets <N>` — limit TCP payload threshold targets.
- `--max-sni <N>` — limit whitelist SNI targets.
- `--domain <HOST>` — add an explicit domain target. Can be used more than once.
- `--proxy <URL>` — optional proxy URL for supported HTTP/TCP checks.
- `--concurrency <N>` — concurrency limit for applicable checks.

## Registered tests

`list-tests` prints the registered test IDs:

```text
dns_integrity       DNS interception / substitution
dns_availability    DNS server availability
domains             Domain DNS/TLS/HTTP availability
tcp16               TCP 12-64KB payload threshold
whitelist_sni       Whitelist SNI probing
telegram            Telegram DC/download/upload
```

## Test descriptions

### DNS integrity

Compares DNS results through different resolver paths to detect possible DNS
interception, substitution or resolver inconsistency.

### DNS availability

Checks availability of several DNS methods/endpoints. This helps distinguish
local DNS failure from domain-specific blocking.

### Domain availability

Runs system DNS, TLS and HTTP/HTTPS probes against selected domains. The detector
classifies symptoms such as timeout, refused connection, reset, TLS alert,
HTTP 451 and suspicious redirects.

### TCP 12-64 KB payload threshold

Sends TLS-like requests with increasing padding sizes. This can reveal payload
size thresholds where connections start to reset, stall or time out.

### Whitelist SNI probing

Runs baseline target checks and then tries alternate SNI values to detect cases
where DPI behavior depends on SNI classification.

### Telegram

Checks Telegram endpoint connectivity and selected download/upload symptoms.
This is useful because Telegram routes and datacenters are often affected by
network filtering differently from ordinary websites.

## NDJSON protocol

In `--format ndjson` mode, every line is one JSON object and stdout is flushed
after events. The Android UI reads these events in real time.

Example events:

```json
{"type":"started","test":"dns_integrity","status":"running","detail":"DNS interception / substitution","data":{},"ts":0,"seq":1}
```

```json
{"type":"probe","test":"tcp16","key":"target:16kb","name":"TCP payload step","target":"1.2.3.4:443","status":"checking","detail":"sending 16 KB X-Pad payload","size_label":"16 KB","data":{},"ts":0,"seq":2}
```

```json
{"type":"finished","test":"summary","status":"medium","detail":"","data":{"risk":"medium"},"ts":0,"seq":4}
```

Common fields:

- `type` — event type such as `started`, `probe`, `finished`;
- `test` — test ID;
- `status` — current status/risk marker;
- `detail` — human-readable detail;
- `data` — additional structured data;
- `ts` — event timestamp;
- `seq` — monotonically increasing sequence number.

## Status and risk interpretation

The detector reports symptoms, not absolute legal/network conclusions. Results
are affected by:

- current mobile/Wi-Fi network;
- DNS/private DNS settings;
- proxy/VPN state;
- captive portals;
- IPv6 availability;
- server-side rate limits;
- temporary connectivity problems;
- regional routing.

`--quick` is meant for fast UI feedback and does not replace a full diagnostic
run.

## Build

```bash
cargo build --release -p dpi-detector
```
