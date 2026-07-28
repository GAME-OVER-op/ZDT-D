# qwdtt-cli roadmap — remaining M4/M5 work

Consolidated list of what's left to finalize the project. M0–M3 are complete and
verified on-device (S23 Ultra, KernelSU Next, whitelisted SIM): a `myvpn`-bound
app egresses at the VPS through VK TURN with no manual interface binding.

## Done (for context)

- **M0** open questions resolved (`docs/M0-findings.md`).
- **M1** headless transport supervisor — verified on-device.
- **M2** amneziawg-go bring-up of `zdtdqw0` — verified on-device.
- **M3** `myprogram` + `myvpn` integration, automatic per-app routing — verified
  on-device (`docs/M3-integration.md`).
- Upstream deltas (atomic `wg-turn.conf` write, `STATS|` marker) as pinned
  patches (`upstream/qwdtt/`); CI builds both binaries.
- Hardening already landed: skip hash re-validation on local wg failure; local-time
  logging with a configurable UTC offset and no duplicate timestamps; suppressed
  the config-box PrivateKey log leak; optional `mtu` override.

---

## M4 — hardening (stays on the myprogram/myvpn deployment)

Ordered by value. Each is independent.

### 1. Interface stealth / Zygisk hiding  *(core design goal)*
- **Rename `zdtdqw0` → `zdtdvpn0`** — *DEFERRED (user postponed)*. The interface is
  hidden today via the **exact-name** match from `vpn_netd/applied.json` (which
  `myvpn` writes), but the name does **not** match Zygisk's *fallback* matcher
  (`tun*/tap*/wg*/awg*/utun*/ppp*/ipsec*/xfrm*/l2tp*/gre*/amneziawg*/*vpn*/if<n>`,
  see `zygisk/src/main.cpp:looks_like_tunnel_interface_name`). A name containing
  `vpn` gets the fallback too, closing the window where `applied.json` is stale.
  Touch points: `Tun` default, example config, `myvpn`/`amneziawg` tun-name
  guards, M3 docs.
- **Turn hiding on for the target app** — separate from `myvpn` routing. Requires:
  Zygisk module installed (setup marker present pre-install); `proxyInfo/enabled.json`
  and `setting/start.json` both `{"enabled": true}`; and the app's UID present in
  `proxyInfo/out_program` (`package=uid`). Populate/enable via the ZDT-D app; note
  in docs that the routing list and the Zygisk target list are distinct.
- **Verify** from inside the target UID that `zdtdqw0` is absent from `getifaddrs`,
  `if_nametoindex`, `/proc/net/*`, `/sys/class/net`, and `NETLINK_ROUTE`.

### 2. Watchdog enablement
- `STATS|` is proven emitting on-device. Set a conservative `watchdog_min_active`
  (e.g. one worker group = 9), field-test the restart trigger, then document a
  recommended value. Currently `0` (disabled).

### 3. Clean TURN teardown vs the 300 ms group-kill
- `myprogram` stops with `kill -15 -- -<pgid>` then `kill -9` after 300 ms — too
  short for the transport to always release TURN allocations. Decide: accept
  best-effort (relays time out anyway), lengthen the stop grace, or let the M5
  module own its own stop timing. Documented in `docs/M3-integration.md`.

### 4. Captcha token-feed field test
- The `CAPTCHA_RESULT|<token>` path (config `captcha_token_file`) is built but
  untested live — captcha never fired in captured sessions. Exercise it once
  captcha actually triggers; document the operator workflow (write token → file).

### 5. Energy-saver / Doze exemption
- Ensure Android/ZDT-D energy-saver policy doesn't freeze or kill the qwdtt-cli
  process (the transport supports `PAUSE`/`RESUME` on stdin — wire or exempt).

### 6. proxyInfo port protection
- Protect the local WG endpoint `127.0.0.1:9000` from other app UIDs probing local
  listeners (proxyInfo can block/observe). Stealth defense-in-depth.

### 7. vk_profile.json persistence
- Confirm on-device that the browser identity stays stable across restarts (the
  `seed_profile`/`seed_captcha_fp` path exists but is untested live). A
  fingerprint that changes every launch is itself a signal.

### 8. Throughput / MTU  *(effectively done)*
- Confirmed fine at 1280 by speed tests; `mtu` is now tunable. No action unless a
  future SIM/relay path fragments.

### 9. Secret rotation *(pre-production)*
- Rotate the WG keypair and tunnel password before production (they left the
  device in `reference/`). Ensure on-device `qwdtt.conf` perms are `600`.

---

## M5 — first-class `qwdtt` program module in the ZDT-D fork

Replaces the generic `myprogram` + `myvpn` wiring with a native module. Follow the
nine-step developer guide in `docs/PROGRAMS.md`. Do not start until M4 stealth is
settled, since the module should own hiding and stop-timing.

1. **Daemon module** `rust/zdtd/src/programs/qwdtt.rs` — own the supervisor
   lifecycle natively (spawn/validate/watchdog/stop), mirroring what qwdtt-cli does
   now. Model on `amneziawg.rs`/`hysteria2.rs`.
2. **Registration** in `programs/mod.rs` + daemon start/stop wiring.
3. **`settings.rs` layout** — the `qwdtt.conf` surface as typed settings.
4. **Port / conflict handling** — register `9000` (and the tun) with the conflict
   checker.
5. **App-list validation** — package→UID with the `excluded_apps`/root guards
   built in; re-validate on app install/clone/user changes.
6. **Android API models** — data classes for the app↔daemon API.
7. **Compose UI** — a qWDTT profile screen.
8. **`strings.xml`** — EN and RU.
9. **Docs** — `PROGRAMS.md` entry + user-facing docs.

Decision for M5: whether the module keeps invoking the `qwdtt-cli` binary or
absorbs the supervisor logic into Rust. Invoking the binary reuses proven code;
absorbing gives tighter lifecycle control (esp. stop timing, item M4.3).

---

## Finalization

- Open a PR for the branch once M4 stealth + watchdog land.
- Keep `reference/` and any filled-in `qwdtt.conf` out of git (already gitignored).
