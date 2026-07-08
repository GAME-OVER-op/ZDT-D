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

/**
 * App-owned notification informing the user that a captive-portal client is
 * waiting for internet access. Shows Allow / Deny actions and, on tap, opens
 * the device-settings screen.
 */
object CaptiveDeviceNotifier {

  private const val CHANNEL_ID = "zdtd_captive_devices"

  fun show(context: Context, event: CaptiveDeviceEvent) {
    if (Build.VERSION.SDK_INT >= 33) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }

    val localizedContext = AppLanguageSupport.localizedAppContext(context)
    ensureChannel(localizedContext)

    val notificationId = event.notificationId
    val title = localizedContext.getString(R.string.captive_notif_title)
    val text = localizedContext.getString(R.string.captive_notif_content, event.displayLabel)

    val piFlags = if (Build.VERSION.SDK_INT >= 23) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }

    // Tapping the notification opens the device-settings screen.
    val openIntent = Intent(context, CaptiveDevicesActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    val openPi = PendingIntent.getActivity(context, notificationId, openIntent, piFlags)

    val allowPi = PendingIntent.getBroadcast(
      context,
      notificationId * 2 + 1,
      actionIntent(context, CaptiveDeviceContract.ACTION_CAPTIVE_ALLOW, event, notificationId),
      piFlags,
    )
    val denyPi = PendingIntent.getBroadcast(
      context,
      notificationId * 2 + 2,
      actionIntent(context, CaptiveDeviceContract.ACTION_CAPTIVE_DENY, event, notificationId),
      piFlags,
    )

    val notification = NotificationCompat.Builder(localizedContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setContentIntent(openPi)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_STATUS)
      .setOnlyAlertOnce(false)
      .addAction(0, localizedContext.getString(R.string.captive_action_allow), allowPi)
      .addAction(0, localizedContext.getString(R.string.captive_action_deny), denyPi)
      .build()

    NotificationManagerCompat.from(localizedContext).notify(notificationId, notification)
  }

  fun cancel(context: Context, notificationId: Int) {
    NotificationManagerCompat.from(context).cancel(notificationId)
  }

  private fun actionIntent(
    context: Context,
    action: String,
    event: CaptiveDeviceEvent,
    notificationId: Int,
  ): Intent = Intent(context, CaptiveActionReceiver::class.java).apply {
    this.action = action
    setPackage(context.packageName)
    putExtra(CaptiveDeviceContract.EXTRA_SHORT_ID, event.shortId)
    putExtra(CaptiveDeviceContract.EXTRA_MODEL, event.model)
    putExtra(CaptiveDeviceContract.EXTRA_NOTIFICATION_ID, notificationId)
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
      CHANNEL_ID,
      context.getString(R.string.captive_notif_channel_name),
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = context.getString(R.string.captive_notif_channel_desc)
      setShowBadge(true)
    }
    nm.createNotificationChannel(channel)
  }
}
