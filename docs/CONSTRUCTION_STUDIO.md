# Construction Studio — Visual Design Brief

This document describes the **visual design** of the Construction Studio screen
(`ConstructionStudioScreen.kt`) and the interaction model we want to build on top
of it. It is written for the model/developer that will implement the UI.

**Scope split:** This brief covers the *visual layer only* (layout, cards, ports,
lines, gestures, animations, persistence). The actual routing/backend wiring is
handled by a separate model. Where a feature needs a backend mechanism, it is
called out explicitly.

**Domain scope:** The interactive part is about **proxy programs and t2s**.
VPN/netd and zapret (`nfqws`/`nfqws2`) nodes are shown **for visualization only** —
their logic is not changed here.

---

## 1. Chain structure (new)

Today the graph renders, e.g.: `App list -> program (wireproxy) -> Internet`, and
t2s is hidden.

We want **t2s shown as its own card, right after the app list**. t2s is the
"advanced node" / hub from which routing rules are pulled toward backends.

```text
App list  ->  t2s (advanced node)  ->  Program / backends  ->  Internet
                  \......... bypass mode .........> Internet
```

This matches the real daemon chain:

```text
app (UID) -> iptables REDIRECT -> t2s -> SOCKS5 backend -> internet
```

So t2s genuinely sits **between the app list and the program**; the card belongs
in that position. For myproxy specifically, the "program/backend" stage is the
set of external SOCKS5 servers t2s forwards to.

---

## 2. Cards and icons

- Program icons come from the **tools section** (same logos: zapret, sing-box,
  tor, openvpn, mihomo, etc.). Reuse `programIconRes(id): Int?` from
  `AppsListScreen.kt` (returns `R.drawable.ic_tool_*`); fall back to
  `programIcon(id)` / the current Material icon when there is no logo. Never
  leave an empty slot.
- The `App list`, `t2s`, and `Internet` cards keep their own icons
  (apps / node / cloud).
- t2s is a dedicated **hub card** — visually more prominent than a normal
  program card, because rules are pulled from it.

---

## 3. Connection ports (the convex circles)

- **Where:** on the card edge at the point where a line attaches (output =
  right side).
- **Look:** a small convex circle, protruding about half-way past the edge,
  with a light shadow/outline, **colored to match the route line**.
- **Minimum size:** when the canvas is zoomed out, the circle must not collapse
  into an invisible dot — enforce a minimum visible size.
- **States:**
  - active route — bright/saturated circle;
  - inactive route — dimmed;
  - **backend health** (for t2s -> backends): alive = green, dead = red.

---

## 4. Connections: one port, two gestures, one result

Tap and drag are **the same "connect" action**; only the gesture differs:

- **Tap** the circle -> open a "where to connect" picker -> choose -> link created.
- **Press & drag** a wire to another card -> release -> the same link is created.

**Magnet snap:** when the wire end is brought near a **compatible** card, the card
attracts the wire and highlights; releasing nearby connects it. An
**incompatible** card does not highlight / shows a "not allowed" state — the wire
does not stick to it.

**Where connecting is meaningful (proxy focus):**

1. **App list -> program profile** — assigning apps (with magnet snap).
2. **t2s -> upstream backends** — the main goal: t2s can point to several SOCKS
   backends, each with a mode and health indicator.

**Compatibility (for target highlighting):** one app cannot be assigned to two
`tunnel`-domain proxies at once (the `tunnel` domain conflicts with itself, see
`app_domain` / `app_domains_conflict` in `zdtd/src/api.rs`). Incompatible target
cards must not highlight.

---

## 5. Line states

| Situation | What we show |
| --- | --- |
| App list is empty (profile will not start) | **Exclamation mark** on the app-list card; lines are **grey and stopped** (static, no flow animation) |
| Program is misconfigured (will not start) | Lines reach **up to the program**, but the **program -> Internet** segment is **grey and stopped** (no flow) |
| All backends alive | Normal animated flow along the whole chain |
| All servers dead -> t2s bypass mode | A direct `t2s -> Internet` line is **drawn in smoothly** (bypassing the program) |
| Servers recover | The bypass line **fades/disconnects smoothly**; flow returns through the program |

**Stopped-line styling:** grey, static, no moving dash/flow animation. This is the
single visual language for "this segment is not carrying traffic / not running".

---

## 6. t2s — the advanced node

t2s is a flexible bridge between iptables redirection and any SOCKS5 engine. Its
capabilities are the "rules we pull" from the hub:

- **Multiple backends at once** (`--socks-host a,b --socks-port 1080,1081`).
- **Backend selection modes:** `balance` (spread across alive) and `priority`
  (priority groups with fallback `1145,1146;1147`, plus soft
  `--priority-speed-aware`).
  - Visual: balance -> equal-weight lines; priority -> numbered/ordered lines.
- **Backend health:** GREEN/dead classification, dead backends auto-excluded.
- **Bypass mode (direct fallback):** when no alive backend exists, traffic goes
  straight to the internet; when backends recover, direct connections are
  revoked. This state is observable via the t2s API and drives the bypass-line
  animation in section 5.
- **Runtime API** (`--web-socket`): add/remove/recheck a backend, kill a
  connection, set download limit, poll state. A future drag-to-connect maps onto
  this 1:1.
- **Traffic rules (`TRAFFIC_RULES`):** match on
  `proto / port / port_range / host_regex / socks_available / is_udp` ->
  action `socks / direct / drop / reset / wait`.

t2s API endpoints (web server, when enabled):
`/api/v1/state`, `/api/v1/poll`, `/api/v1/backends/add|remove|recheck`,
`/api/v1/connections/kill`, `/api/v1/limits`, `/api/v1/download-limit`.

Each proxy program reaches the internet through t2s the same way
(`app UID -> iptables REDIRECT -> t2s -> program's local SOCKS -> internet`):

- **myproxy** — t2s points directly at the user's external SOCKS5 servers; most
  flexible (multi-port, balance/priority, auth). Config in profile
  `proxy.json` + `setting.json` (`t2s_port`, `t2s_web_port`).
- **operaproxy** — spawns opera-proxy instances (sequential SOCKS ports),
  optional byedpi as an extra upstream, t2s fronts them.
- **wireproxy** — spawns wireproxy (WireGuard->SOCKS), t2s fronts it.
- **tor** — spawns Tor (`SocksPort 127.0.0.1:9050`, bridges via lyrebird),
  t2s fronts Tor's SOCKS.
- **dpitunnel / byedpi** — apps are redirected directly into their own port
  (byedpi tcp+udp), not necessarily via t2s; byedpi can also be an upstream for
  operaproxy.
- **myprogram** — "bring your own program" exposing a SOCKS endpoint; with
  `route_mode = t2s` and `apps_mode`, t2s fronts it.

---

## 7. Interaction & behavior

- **Card dragging:** currently, pressing on a card pans the **whole canvas**.
  We need a gesture on a card to **move the card**, while empty-background pan
  keeps moving the canvas. (Today pan/zoom is done via `detectTransformGestures`
  over the whole canvas — card drag must be disambiguated from canvas pan.)
- **Layout persistence:** after the user moves cards, **save card positions in
  the app** so the next open restores the user's layout instead of rebuilding
  it from scratch.
- **Search button:**
  - tapping opens a **list of program cards**; tapping again hides it;
  - selecting an item **focuses the canvas on that card** (centers/highlights it).

---

## 8. Phased plan

1. Tool icons + dedicated t2s card after the app list + cosmetic port circles.
2. Line states (exclamation mark on empty list, grey stopped segments, bypass
   mode with animation) + card dragging + layout persistence + search button.
3. Clickable ports (tap -> "where to connect") + magnet snap.
4. Drag-to-connect via the t2s runtime API (backend-model territory).

---

## 9. Code references

- **Screen:** `application/app/src/main/java/com/android/zdtd/service/ui/ConstructionStudioScreen.kt`
  (read-only canvas; pan/zoom via `detectTransformGestures`; nodes `StudioNode`,
  edges `StudioEdge`; polls `actions.loadTrafficRules()` ~every 5s).
- **Icons:** `programIconRes(id): Int?` and `programIcon(id): ImageVector` in
  `AppsListScreen.kt`; tool PNGs in `res/drawable-nodpi/ic_tool_*.png`.
- **Conflict domains:** `app_domain` / `app_domains_conflict` in
  `rust/zdtd/src/api.rs` (`tunnel` domain self-conflicts).
- **t2s:** `rust/T2s/` (see `README.md`); backends/modes/rules described above.

> NOTE: Construction Studio is currently **read-only**. Writing routes
> (drag-to-connect, changing backends) requires a separate write mechanism on
> the backend-model side. The visual layer only prepares the handles and states.


---

## Fixes round 2

- **Colored tool icons:** `ic_tool_*` PNGs are single-color (black) glyphs. The tools list tints them via content color, so the Studio must tint too. The Studio program/backend/VPN icon now uses `Icon(painterResource(node.iconRes), tint = node.accent)` instead of `Color.Unspecified` (which rendered them black).
- **All cards use tool icons:** `iconRes` is now set on program nodes, VPN nodes (`programIconRes(normalizeRouteProgramId(vpn.ownerProgram))`), and backend/server nodes (`programIconRes(normalizeRouteProgramId(backend.programId))`). Falls back to the vector icon when no logo exists for that id.
- **sing-box now goes through t2s:** added `"sing-box"` to `t2sFronted` (covers both `singbox` and `sing-box` via `normalizeRouteProgramId`). Chain becomes App list -> t2s -> sing-box -> servers -> Internet. Multiple servers render as multiple backend nodes from `group.rules.flatMap { it.backendPorts }`; if the daemon does not yet expose sing-box's servers as backend ports, the backend model must surface them for the cards to appear.
- **Smooth lines / no straight-under-card lines:** edge control offset is now `dx = max(abs(ex - sx) * 0.5f, 90f)`, so lines always keep a horizontal bow (curve around cards) instead of collapsing into a straight line under a card when cards are dragged close or vertically aligned. Full obstacle avoidance is not implemented; this guarantees curvature.
