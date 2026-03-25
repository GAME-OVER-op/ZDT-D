package com.android.zdtd.service

import com.android.zdtd.service.R

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * App-owned notification for the daemon status (running / stopped).
 */
object DaemonStatusNotifier {

  private const val CHANNEL_ID = "zdtd_state"
  private const val CHANNEL_NAME = "ZDT-D"
  private const val NOTIFICATION_ID = 1006

  fun cancel(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
  }

  fun show(context: Context, running: Boolean) {
    // Android 13+: if permission isn't granted, we cannot show notifications.
    if (Build.VERSION.SDK_INT >= 33) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }

    ensureChannel(context)

    val text = if (running) context.getString(R.string.notif_service_running) else context.getString(R.string.notif_service_stopped)

    val openIntent = Intent(context, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val piFlags = if (Build.VERSION.SDK_INT >= 23) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }

    val pi = PendingIntent.getActivity(context, 0, openIntent, piFlags)

    val n = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle("ZDT-D")
      .setContentText(text)
      .setContentIntent(pi)
      .setOnlyAlertOnce(true)
      .setAutoCancel(false)
      .setOngoing(running)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()

    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
    val ch = NotificationChannel(
      CHANNEL_ID,
      CHANNEL_NAME,
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = context.getString(R.string.notif_channel_desc)
      setShowBadge(false)
    }
    nm.createNotificationChannel(ch)
  }
}