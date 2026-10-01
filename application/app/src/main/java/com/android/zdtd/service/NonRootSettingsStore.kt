package com.android.zdtd.service

import android.content.Context

enum class NonRootWorkMode {
  DIRECT,
  CASCADE,
}

class NonRootSettingsStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun getWorkMode(): NonRootWorkMode =
    runCatching { NonRootWorkMode.valueOf(prefs.getString(KEY_WORK_MODE, null).orEmpty()) }
      .getOrDefault(NonRootWorkMode.DIRECT)

  fun setWorkMode(mode: NonRootWorkMode) {
    prefs.edit().putString(KEY_WORK_MODE, mode.name).apply()
  }

  companion object {
    private const val PREFS_NAME = "non_root_settings"
    private const val KEY_WORK_MODE = "work_mode"
  }
}
