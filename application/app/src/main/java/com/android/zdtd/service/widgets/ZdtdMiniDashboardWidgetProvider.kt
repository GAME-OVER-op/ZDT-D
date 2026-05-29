package com.android.zdtd.service.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ZdtdMiniDashboardWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        ZdtdWidgetUpdater.updateMiniDashboard(context.applicationContext, appWidgetManager, appWidgetIds)
      } finally {
        pendingResult.finish()
      }
    }
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ZdtdWidgetActions.ACTION_TOGGLE || intent.action == ZdtdWidgetActions.ACTION_REFRESH) {
      val pendingResult = goAsync()
      CoroutineScope(Dispatchers.IO).launch {
        try {
          if (intent.action == ZdtdWidgetActions.ACTION_TOGGLE) {
            ZdtdWidgetUpdater.toggleService(context.applicationContext)
          }
          ZdtdWidgetUpdater.updateAll(context.applicationContext)
        } finally {
          pendingResult.finish()
        }
      }
      return
    }
    super.onReceive(context, intent)
  }
}
