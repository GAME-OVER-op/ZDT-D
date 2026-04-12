package com.android.zdtd.service

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

object ProxyInfoProbeNotifier {

  private const val CHANNEL_ID = "zdtd_proxyinfo_alerts"

  fun show(context: Context, event: ProxyInfoProbeEvent) {
    if (Build.VERSION.SDK_INT >= 33) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }

    val localizedContext = AppLanguageSupport.localizedAppContext(context)
    ensureChannel(localizedContext)

    val appName = AppLanguageSupport.resolveAppLabel(context, event.packageName)
    val title = if (event.isConfirmed) {
      localizedContext.getString(R.string.probe_alert_title_confirmed)
    } else {
      localizedContext.getString(R.string.probe_alert_title_suspicion)
    }
    val text = if (event.isConfirmed) {
      localizedContext.getString(R.string.probe_alert_content_confirmed, appName)
    } else {
      localizedContext.getString(R.string.probe_alert_content_suspicion, appName)
    }

    val openIntent = Intent(context, ProxyInfoProbeDetailsActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    ProxyInfoProbeContract.writeToIntent(openIntent, event)

    val piFlags = if (Build.VERSION.SDK_INT >= 23) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    val pi = PendingIntent.getActivity(context, event.notificationId, openIntent, piFlags)

    val notification = NotificationCompat.Builder(localizedContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(pi)
      .setAutoCancel(true)
      .setPriority(if (event.isConfirmed) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setOnlyAlertOnce(false)
      .build()

    NotificationManagerCompat.from(localizedContext).notify(event.notificationId, notification)
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
      CHANNEL_ID,
      context.getString(R.string.probe_alert_channel_name),
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = context.getString(R.string.probe_alert_channel_desc)
      setShowBadge(true)
    }
    nm.createNotificationChannel(channel)
  }
}
