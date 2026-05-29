package com.android.zdtd.service.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import com.android.zdtd.service.MainActivity
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.api.ApiClient
import com.android.zdtd.service.api.ApiModels

internal object ZdtdWidgetUpdater {
  private const val PULSE_INTERVAL_MS = 30_000L
  private const val PULSE_REQUEST_CODE = 1701

  fun updateAll(context: Context) {
    val appContext = context.applicationContext
    val manager = AppWidgetManager.getInstance(appContext)
    updateProvider(appContext, manager, ZdtdMiniDashboardWidgetProvider::class.java)
    scheduleRunningPulse(appContext, readSnapshot(appContext).serviceOn)
  }

  fun updateMiniDashboard(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
    val snapshot = readSnapshot(context)
    appWidgetIds.forEach { id ->
      manager.updateAppWidget(id, buildMiniDashboardViews(context, snapshot, manager.getAppWidgetOptions(id)))
    }
    scheduleRunningPulse(context.applicationContext, snapshot.serviceOn)
  }

  fun toggleService(context: Context) {
    val root = RootConfigManager(context.applicationContext)
    val api = apiClient(root)
    val testerActive = isTesterActive(root)
    val status = runCatching { api.getStatus() }.getOrNull()
    val serviceOn = ApiModels.isServiceOn(status)
    if (!serviceOn && testerActive) {
      // Do not start ZDT-D while the strategy tester owns temporary iptables rules.
      return
    }
    val ok = runCatching { if (serviceOn) api.stopService() else api.startService() }.getOrDefault(false)
    if (ok) root.setCachedServiceOn(!serviceOn)
  }

  private fun updateProvider(
    context: Context,
    manager: AppWidgetManager,
    provider: Class<*>,
  ) {
    val ids = manager.getAppWidgetIds(ComponentName(context, provider))
    if (ids.isEmpty()) return
    if (provider == ZdtdMiniDashboardWidgetProvider::class.java) {
      updateMiniDashboard(context, manager, ids)
    }
  }

  private fun readSnapshot(context: Context): ZdtdWidgetSnapshot {
    val root = RootConfigManager(context.applicationContext)
    val testerActive = isTesterActive(root)
    val report = runCatching { apiClient(root).getStatus() }.getOrNull()
    return report.toWidgetSnapshot(testerActive = testerActive)
  }

  private fun apiClient(root: RootConfigManager): ApiClient = ApiClient(
    rootManager = root,
    baseUrlProvider = { "http://127.0.0.1:1006" },
    tokenProvider = { root.readApiToken() },
  )

  private fun isTesterActive(root: RootConfigManager): Boolean {
    return runCatching {
      root.execRootSh("test -f /data/adb/modules/ZDT-D/working_folder/nfqws_tester/session.json").isSuccess
    }.getOrDefault(false)
  }

  private fun buildMiniDashboardViews(context: Context, snapshot: ZdtdWidgetSnapshot, options: Bundle?): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_mini_dashboard)
    views.setImageViewBitmap(R.id.widget_mini_render, ZdtdWidgetHudRenderer.renderMiniDashboard(context, snapshot, options))
    views.setOnClickPendingIntent(R.id.widget_mini_action_area, pendingBroadcast(context, ZdtdWidgetActions.ACTION_TOGGLE, 1201))
    views.setOnClickPendingIntent(R.id.widget_mini_root, pendingActivity(context, 1202))
    return views
  }

  private fun scheduleRunningPulse(context: Context, running: Boolean) {
    val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val pulse = pendingBroadcast(context, ZdtdWidgetActions.ACTION_REFRESH, PULSE_REQUEST_CODE)
    if (!running) {
      alarm.cancel(pulse)
      return
    }
    alarm.set(
      AlarmManager.ELAPSED_REALTIME,
      SystemClock.elapsedRealtime() + PULSE_INTERVAL_MS,
      pulse,
    )
  }

  private fun pendingBroadcast(context: Context, action: String, requestCode: Int): PendingIntent {
    val intent = Intent(context, ZdtdMiniDashboardWidgetProvider::class.java).apply {
      this.action = action
    }
    return PendingIntent.getBroadcast(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun pendingActivity(context: Context, requestCode: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
