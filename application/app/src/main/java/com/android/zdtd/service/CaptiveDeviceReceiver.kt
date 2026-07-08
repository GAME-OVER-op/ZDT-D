package com.android.zdtd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the daemon's fire-once "captive device pending" broadcast and, when
 * app notifications are enabled, shows an app-owned notification with
 * Allow / Deny actions. Several pending devices produce several notifications
 * (distinct notification ids).
 */
class CaptiveDeviceReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != CaptiveDeviceContract.ACTION_CAPTIVE_DEVICE_PENDING) return

    // Gated by the common app-notification toggle. When off: no notification.
    val cfg = RootConfigManager(context)
    if (!cfg.isDaemonStatusNotificationEnabled()) return

    val event = CaptiveDeviceContract.fromIntent(intent) ?: return
    CaptiveDeviceNotifier.show(context, event)
  }
}
