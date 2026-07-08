package com.android.zdtd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.zdtd.service.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the Allow / Deny action buttons on the captive-device notification.
 * The decision is sent to the local API off the main thread, then the
 * originating notification is cleared.
 */
class CaptiveActionReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != CaptiveDeviceContract.ACTION_CAPTIVE_ALLOW &&
      action != CaptiveDeviceContract.ACTION_CAPTIVE_DENY
    ) {
      return
    }

    val cfg = RootConfigManager(context)
    // Respect the common notification/permission toggle.
    if (!cfg.isDaemonStatusNotificationEnabled()) return

    val shortId = intent.getStringExtra(CaptiveDeviceContract.EXTRA_SHORT_ID)?.trim().orEmpty()
    val notificationId = intent.getIntExtra(CaptiveDeviceContract.EXTRA_NOTIFICATION_ID, -1)
    if (shortId.isEmpty()) {
      if (notificationId > 0) CaptiveDeviceNotifier.cancel(context, notificationId)
      return
    }

    val allow = action == CaptiveDeviceContract.ACTION_CAPTIVE_ALLOW
    val appContext = context.applicationContext
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val api = ApiClient(
          rootManager = cfg,
          baseUrlProvider = { "http://127.0.0.1:1006" },
          tokenProvider = { cfg.readApiToken() },
        )
        runCatching {
          if (allow) api.allowCaptiveDevice(shortId) else api.denyCaptiveDevice(shortId)
        }
      } finally {
        if (notificationId > 0) CaptiveDeviceNotifier.cancel(appContext, notificationId)
        pendingResult.finish()
      }
    }
  }
}
