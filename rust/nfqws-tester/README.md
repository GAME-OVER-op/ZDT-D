# nfqws-tester

`nfqws-tester` is a native ZDT-D diagnostic helper for trying `nfqws` and
`nfqws2` strategy files in an isolated test session.

It is not the main ZDT-D daemon and it is not a replacement for `nfqws`. It is a
small controller that starts a selected NFQUEUE engine with a selected strategy,
applies temporary NFQUEUE rules, reports session state and cleans up after the
test.

## Purpose

The helper is used by the Android diagnostics UI to let the user test zapret
strategy files without permanently changing normal profile configuration.

It can:

- list available strategy files;
- start `nfqws` or `nfqws2` with a selected config;
- normalize config arguments before launch;
- extract TCP/UDP port filters from the strategy text when possible;
- apply temporary NFQUEUE rules;
- write a session state file;
- report status and process usage;
- stop the test session and clean up rules/processes.

## Runtime paths

Fixed module paths used by the helper:

```text
/data/adb/modules/ZDT-D
/data/adb/modules/ZDT-D/bin
/data/adb/modules/ZDT-D/strategic/strategicvar
/data/adb/modules/ZDT-D/working_folder/nfqws_tester
/data/adb/modules/ZDT-D/working_folder/nfqws_tester/session.json
```

Default NFQUEUE number:

```text
200
```

The helper expects to run as root on Android. It needs access to module binaries,
strategy files, process control and `iptables`/`ip6tables` commands.

## Commands

```bash
nfqws-tester --version
nfqws-tester list --program nfqws|nfqws2
nfqws-tester start --program nfqws|nfqws2 --config /path/to/file.txt [--qnum 200]
nfqws-tester stop
nfqws-tester cleanup
nfqws-tester status
nfqws-tester usage --pid 1234
```

`stop` and `cleanup` are aliases for the same cleanup operation.

## list

Lists available `.txt` strategy files for the selected program.

```bash
nfqws-tester list --program nfqws
```

Output is JSON:

```json
{
  "ok": true,
  "program": "nfqws",
  "dir": "/data/adb/modules/ZDT-D/strategic/strategicvar/nfqws",
  "strategies": ["example.txt"]
}
```

## start

Starts a temporary strategy test.

```bash
nfqws-tester start \
  --program nfqws \
  --config /data/adb/modules/ZDT-D/strategic/strategicvar/nfqws/example.txt \
  --qnum 200
```

Flow:

1. Validate program name (`nfqws` or `nfqws2`).
2. Validate that the engine binary exists.
3. Validate that the config file exists.
4. Ensure the tester work directory exists.
5. Stop any previous tester session.
6. Read and normalize strategy arguments.
7. Extract optional TCP/UDP port filters.
8. Spawn the selected engine.
9. Apply NFQUEUE rules for the selected queue number.
10. Write `session.json`.
11. Print a JSON result.

Example output:

```json
{
  "ok": true,
  "program": "nfqws",
  "config_path": "/data/adb/modules/ZDT-D/strategic/strategicvar/nfqws/example.txt",
  "config_name": "example.txt",
  "pid": 12345,
  "qnum": 200,
  "filter": {
    "tcp": "80,443",
    "udp": "443"
  }
}
```

If rule application fails after spawning the engine, the helper attempts to kill
the spawned process and remove program-specific rules before returning an error.

## status

Reports the current session:

```bash
nfqws-tester status
```

If active:

```json
{
  "ok": true,
  "active": true,
  "program": "nfqws",
  "config_path": "/path/to/file.txt",
  "config_name": "file.txt",
  "pid": 12345,
  "qnum": 200,
  "started_at_unix_ms": 1710000000000
}
```

If inactive:

```json
{"ok": true, "active": false}
```

## usage

Reads process usage for a PID and prints JSON:

```bash
nfqws-tester usage --pid 12345
```

Output fields include:

- `active`;
- `pid`;
- `cpu_percent`;
- `rss_mb`.

This is used by the Android overlay/UI to display lightweight runtime usage.

## stop / cleanup

Stops the current tester session, removes temporary rules and prints:

```json
{"ok": true, "active": false}
```

Cleanup should be called when the diagnostic screen is closed or when the user
stops testing.

## What nfqws-tester does not do

- It does not manage normal ZDT-D profiles.
- It does not replace the main `zdtd` daemon.
- It does not install strategy files.
- It does not permanently enable `nfqws` or `nfqws2` profiles.
- It does not validate whether a strategy is safe or optimal for every network.
- It does not perform complete DPI diagnostics by itself; use `dpi-detector` for
  broader network checks.

## Safety notes

`nfqws-tester` temporarily changes NFQUEUE/firewall behavior for testing. It must
clean up after itself. If the process is killed externally, the Android app or
user should run:

```bash
nfqws-tester cleanup
```

before starting another test or normal profile run.

## Build

```bash
cargo build --release -p nfqws-tester
```
