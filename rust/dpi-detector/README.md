# dpi-detector

Native Rust helper for ZDT-D DPI/blocking diagnostics.

The helper is bundled into the Android APK as an asset, extracted by the app to:

```text
/data/data/com.android.zdtd.service/no_backup/bin/dpi-detector
```

and executed through root so checks can inspect the network with elevated privileges.

## Commands

```bash
dpi-detector --version
dpi-detector self-test
dpi-detector list-tests
dpi-detector run --format ndjson
dpi-detector run --format ndjson --quick
dpi-detector run --format ndjson --tests dns_integrity,dns_availability,domains,tcp16,whitelist_sni,telegram
dpi-detector run --format ndjson --domain example.com --proxy socks5://127.0.0.1:1080 --concurrency 50
```

## Implemented checks

- DNS interception / substitution by comparing UDP DNS with DoH Wire.
- DNS resolver availability for UDP, DoH Wire and DoH JSON endpoints, including the extended resolver list from the Python reference project.
- Domain availability through system DNS, TLS 1.2, TLS 1.3, HTTP and HTTPS probes.
- HTTP injection and redirect analysis, including HTTP 451 and suspicious cross-domain redirects.
- Extended error classification: TCP reset, TLS reset/alert/spoof/MITM, timeout, refused and unreachable states.
- TCP 16–20 KB threshold probing with baseline RTT and increasing `X-Pad` payload steps up to 64 KB.
- Whitelist SNI probing with a baseline target phase before trying alternate SNI values.
- Telegram DC TCP connectivity, download and upload probes.
- Optional single-domain, proxy and concurrency CLI arguments for Android/future diagnostics flows.

## Realtime NDJSON protocol

The process writes one JSON object per line and flushes stdout after every event:

```json
{"type":"started","test":"dns_integrity","status":"running","detail":"DNS interception / substitution","data":{},"ts":0,"seq":1}
{"type":"probe","test":"tcp16","key":"target:16kb","name":"TCP payload step","target":"1.2.3.4:443","status":"checking","detail":"sending 16 KB X-Pad payload","size_label":"16 KB","data":{},"ts":0,"seq":2}
{"type":"probe","test":"tcp16","key":"target:16kb","name":"TCP payload step","target":"1.2.3.4:443","status":"suspicious","detail":"possible TCP threshold block at 16 KB","size_label":"16 KB","data":{},"ts":0,"seq":3}
{"type":"finished","test":"summary","status":"medium","detail":"","data":{"risk":"medium"},"ts":0,"seq":4}
```

Android reads stdout line-by-line and updates the UI immediately.
