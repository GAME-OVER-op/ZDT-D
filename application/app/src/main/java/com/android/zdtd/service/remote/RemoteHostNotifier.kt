package com.android.zdtd.service.remote

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
import com.android.zdtd.service.R
import com.android.zdtd.service.RemoteSetupActivity

object RemoteHostNotifier {
  private const val CHANNEL_ID = "zdtd_remote_host"
  private const val NOTIFICATION_ID = 12870

  fun show(context: Context, state: RemoteHostState) {
    if (!state.running) return
    if (Build.VERSION.SDK_INT >= 33) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }

    val appContext = context.applicationContext
    ensureChannel(appContext)

    val openIntent = Intent(appContext, RemoteSetupActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    val piFlags = if (Build.VERSION.SDK_INT >= 23) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val pi = PendingIntent.getActivity(appContext, 0, openIntent, piFlags)

    val connected = state.pairedDevice.takeIf { it.isNotBlank() }
    val text = buildString {
      append(state.host.ifBlank { "0.0.0.0" })
      append(':')
      append(state.port)
      append(" • код ")
      append(state.code)
      if (connected != null) append(" • подключено: ").append(connected)
    }

    val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle("ZDT-D Remote")
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(pi)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .build()

    NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
  }

  fun cancel(context: Context) {
    NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "ZDT-D Remote",
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "Remote control HTTP API status"
      setShowBadge(false)
    }
    nm.createNotificationChannel(channel)
  }
}
