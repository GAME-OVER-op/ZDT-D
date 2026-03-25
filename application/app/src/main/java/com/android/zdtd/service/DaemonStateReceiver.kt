package com.android.zdtd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives daemon state broadcasts from the Rust service.
 *
 * The daemon runs as root and triggers this receiver via:
 *   am broadcast --user 0 -a com.android.zdtd.service.ACTION_DAEMON_STATE \
 *     -n com.android.zdtd.service/.DaemonStateReceiver --ez running true|false
 */
class DaemonStateReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_DAEMON_STATE) return

    val cfg = RootConfigManager(context)
    val enabled = cfg.isDaemonStatusNotificationEnabled()
    if (!enabled) {
      // If the user turned notifications off, make sure we don't leave a stale notification.
      DaemonStatusNotifier.cancel(context)
      return
    }

    val running = intent.getBooleanExtra(EXTRA_RUNNING, false)
    DaemonStatusNotifier.show(context, running)
  }

  companion object {
    const val ACTION_DAEMON_STATE = "com.android.zdtd.service.ACTION_DAEMON_STATE"
    const val EXTRA_RUNNING = "running"
  }
}
