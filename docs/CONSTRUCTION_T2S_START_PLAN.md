# Construction Studio — t2s start/connect plan

Status: implementation approved by user on 2026-06-15.

Hard rule for future continuation:
- Continue from `/data/ZDT-D_studio_v4`.
- Before final response, verify that all planned edits are present and package the full project into a zip archive.
- Do not rely on a full Gradle/Cargo build in this sandbox unless toolchain appears; current sandbox previously had no `gradle`, `gradlew`, `cargo`, `rustc`, `rustfmt`.

## Goal

When tapping a `t2s` card in Construction Studio, the picker should not only show currently running local proxy endpoints. It should also show available project profiles/endpoints that are not running/enabled yet. Selecting a stopped profile should start the proper tool/profile, ensure routing can be built, then connect its local proxy endpoint to the selected t2s instance.

## User decisions / constraints

- t2s is the main router.
- We only work with proxy endpoint programs/cascades for the t2s card.
- Disabled profiles should not be hidden. They should be shown as available and started on selection.
- If the selected tool/profile has an empty app list, add `com.android.zdtd.service` as a trigger package.
- After app list changes, zdtd Rust already rebuilds routing almost immediately; do not add forced Android-side service restart/apply for normal app-list edits.
- For stopped profiles, Rust should start the needed tool/profile and create the needed NAT route/rule path.
- Expected iptables result for a newly routed profile is an additional `NAT_DPI -> ZDTN_####` scoped chain/rule.

## Existing findings

- `runtime_refresh::refresh_apps(program, profile, slot)` rebuilds app uid files and refreshes active routing snapshots.
- `refresh_routing_by_uid_file(uid_file)` only works when a routing snapshot already exists for that uid file.
- If a profile has never been started in current runtime, there may be no snapshot, so `refresh_apps` alone will log `no active routing cache` and cannot create a new `ZDTN_####` chain.
- `iptables_port::apply(uid_file, dest_port, ...)` creates scoped NAT chains from valid UIDs in `app/out/user_program`.
- Therefore stopped profile start must go through the profile/tool start/apply path, not just app-list refresh.
- `wireproxy` already has launch marker logic, but marker-only start may not create routed UID rules. For Construction Studio the trigger should be a real package UID (`com.android.zdtd.service`) when the list is empty.

## Planned implementation stages

### 1. Persist this plan

This file is the continuation anchor.

### 2. Rust endpoint candidates

Expose candidates for the t2s picker, including running and stopped profiles:

- program
- profile
- server/slot if relevant
- host, port
- endpoint kind (`socks5`, `mixed`, etc.)
- enabled/running flags
- app list path / slot
- whether app list is empty
- whether it already has the constructor trigger
- whether it can be started

Candidate programs to cover first:

- `sing-box`
- `wireproxy`
- `myproxy`
- `myprogram`
- `operaproxy`
- `tor`
- `mihomo`
- `mieru`

Important endpoint mapping:

- `mihomo`: `mixed_port`, not external-controller.
- `mieru`: `socks5_port`, not rpc_port.
- `tor`: `SocksPort`.
- Ignore non-SOCKS/non-proxy ports such as byedpi/dpitunnel non-SOCKS ports.

Suggested API:

```text
GET /api/construction/proxy-endpoints
```

or extend traffic report with candidates if simpler.

### 3. Rust start/ensure endpoint

Add API for Construction Studio:

```text
POST /api/construction/proxy-endpoints/start
```

Input shape:

```json
{
  "program": "wireproxy",
  "profile": "profile1",
  "server": "optional",
  "slot": "common",
  "ensure_trigger": true
}
```

Behavior:

1. Resolve candidate and endpoint port.
2. If app list is empty, add `com.android.zdtd.service` to the correct `app/uid/user_program`/slot.
3. Enable the target profile if disabled.
4. Rebuild UID output for the app list.
5. Start only the needed tool/profile if possible; avoid full service restart.
6. Ensure routing is created through the normal profile/tool start path, so `NAT_DPI -> ZDTN_####` appears when applicable.
7. Return endpoint host/port and state.

### 4. Android API models/client

Add data classes and ApiClient calls for endpoint candidates/start response.

### 5. Construction Studio t2s picker UI

Replace/extend current `StudioConnectSheet` behavior:

- Show running endpoints as now.
- Show stopped/disabled available profiles as `Start & connect`.
- Show states: running, stopped, disabled, empty app list, connected.
- When selecting a running endpoint: add/remove backend from t2s as now.
- When selecting a stopped candidate:
  1. call Rust start endpoint;
  2. wait/refresh traffic report and candidates;
  3. call t2s `/api/v1/backends/add` for returned host/port;
  4. `recheckBackends`;
  5. refresh map.

### 6. Verification before final archive

- Verify plan file exists.
- Verify new Rust API route exists.
- Verify Android ApiClient/model references exist.
- Verify Construction Studio uses candidate/start path.
- Verify old running endpoint connect/disconnect still exists.
- Run static bracket checks.
- Attempt build only if toolchain exists.
- Create final zip archive and make it downloadable.

## Current related files

- `docs/CONSTRUCTION_STUDIO.md`
- `docs/CONSTRUCTION_T2S_START_PLAN.md`
- `application/app/src/main/java/com/android/zdtd/service/ui/ConstructionStudioScreen.kt`
- `application/app/src/main/java/com/android/zdtd/service/api/ApiModels.kt`
- `application/app/src/main/java/com/android/zdtd/service/api/ApiClient.kt`
- `application/app/src/main/java/com/android/zdtd/service/ZdtdActions.kt`
- `application/app/src/main/java/com/android/zdtd/service/MainViewModel.kt`
- `rust/zdtd/src/api.rs`
- `rust/zdtd/src/runtime_refresh.rs`
- `rust/zdtd/src/iptables/iptables_port.rs`
- `rust/zdtd/src/programs/wireproxy.rs`
- `rust/zdtd/src/programs/singbox.rs`
- `rust/zdtd/src/programs/mihomo.rs`
- `rust/zdtd/src/programs/mieru.rs`
