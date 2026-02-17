# ZDT-D Service Controller (Android)

Companion Android app for the **ZDT-D** Magisk module / daemon (**`zdtd`**).
The app talks to the local API on **`http://127.0.0.1:1006`** and provides a UI for:

- **Start / Stop** the daemon services (single button + Quick Settings tile)
- View **live status** (CPU/RAM per process) and **tail logs** (`zdtd.log`)
- Manage programs and profile-based modules (`nfqws`, `byedpi`, `dpitunnel`, …)
- Edit configs (`config.txt`, `ports.json`, `sni.txt`, dnscrypt config, etc.)
- Select apps (package lists) for traffic redirection
  - **Common / Mobile / Wi‑Fi** app lists are supported for:
    - `nfqws` profiles
    - `dpitunnel` profiles
    - `operaproxy` (global)

## Requirements

- **Root** (Magisk): the app reads the API token from the module directory and can fall back to a root-proxy HTTP call.
- ZDT-D module installed at:
  - `/data/adb/modules/ZDT-D/`

## API & authentication

The daemon exposes an API-only server:

- Bind: `127.0.0.1:1006`
- Any request outside `/api/*` returns an empty `404`
- Any `/api/*` request without a valid token returns an empty `404`

Token location (read by the app via root):

- `/data/adb/modules/ZDT-D/api/token`

The app sends the token using both headers (daemon accepts either):

- `Authorization: Bearer <token>`
- `X-Api-Key: <token>`

## Logs

The UI tails the main log file:

- `/data/adb/modules/ZDT-D/log/zdtd.log`

## Permissions

- `INTERNET` + `usesCleartextTraffic=true` (localhost HTTP)
- `QUERY_ALL_PACKAGES` — required to show **all installed apps (incl. system)** in the app picker on Android 11+

## Build

Open the project in **Android Studio** (AGP/Gradle), or build from CLI:

```bash
./gradlew :app:assembleDebug
```

Minimum SDK: **26** (Android 8.0)
