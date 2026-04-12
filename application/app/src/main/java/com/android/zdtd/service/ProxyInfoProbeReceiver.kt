package com.android.zdtd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ProxyInfoProbeReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ProxyInfoProbeContract.ACTION_PROXYINFO_PROBE_DETECTED) return

    val cfg = RootConfigManager(context)
    if (!cfg.isDaemonStatusNotificationEnabled()) return

    val event = ProxyInfoProbeContract.fromIntent(intent) ?: return
    ProxyInfoProbeNotifier.show(context, event)
  }
}
