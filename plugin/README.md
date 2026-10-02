# ZDT-D TGWS Plugin

Headless Android companion package for non-root Telegram WS Proxy runtime.

- Package: `com.android.zdtd.service.plugin.com`
- No `MAIN` / `LAUNCHER` activity, so it has no launcher icon.
- The Binder service is protected by the signature permission declared by the main ZDT-D APK:
  `com.android.zdtd.service.plugin.com.permission.CONTROL_TGWS`.
- The APK must be signed with the same release certificate as ZDT-D.
- `tg-ws-proxy` is packaged as `lib/<abi>/libzdt_tgwsproxy.so` and executed only from the plugin's `nativeLibraryDir`.
- The main ZDT-D foreground service binds to the plugin, passes TGWS argv and receives live stdout/stderr through AIDL.

GitHub Actions builds this project separately, signs it from `ZDT_KEYSTORE_*` secrets and publishes:

- `zdt-d-tgws-plugin.apk`
- `tgws-plugin.json`

to the `Technical_Assets` release. The root-mode `tg_ws_proxy.zip` asset remains separate and unchanged.
