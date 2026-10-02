package com.android.zdtd.service

import android.content.Context

enum class NonRootWorkMode {
  DIRECT,
  CASCADE,
}

enum class NonRootAppRoutingMode {
  ALL,
  ONLY_SELECTED,
  EXCLUDE_SELECTED,
}

class NonRootSettingsStore(context: Context) {
  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val appPackageName = appContext.packageName

  fun getWorkMode(): NonRootWorkMode =
    runCatching { NonRootWorkMode.valueOf(prefs.getString(KEY_WORK_MODE, null).orEmpty()) }
      .getOrDefault(NonRootWorkMode.DIRECT)

  fun setWorkMode(mode: NonRootWorkMode) {
    prefs.edit().putString(KEY_WORK_MODE, mode.name).apply()
  }

  fun getAppRoutingMode(): NonRootAppRoutingMode =
    runCatching { NonRootAppRoutingMode.valueOf(prefs.getString(KEY_APP_ROUTING_MODE, null).orEmpty()) }
      .getOrDefault(NonRootAppRoutingMode.ALL)

  fun setAppRoutingMode(mode: NonRootAppRoutingMode) {
    prefs.edit().putString(KEY_APP_ROUTING_MODE, mode.name).apply()
  }

  fun getAppRoutingPackages(): Set<String> =
    prefs.getStringSet(KEY_APP_ROUTING_PACKAGES, emptySet())
      .orEmpty()
      .asSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() && it != appPackageName }
      .toSet()

  fun setAppRoutingPackages(packages: Set<String>) {
    val normalized = packages
      .asSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() && it != appPackageName }
      .toSet()
    prefs.edit().putStringSet(KEY_APP_ROUTING_PACKAGES, normalized).apply()
  }

  companion object {
    private const val PREFS_NAME = "non_root_settings"
    private const val KEY_WORK_MODE = "work_mode"
    private const val KEY_APP_ROUTING_MODE = "app_routing_mode"
    private const val KEY_APP_ROUTING_PACKAGES = "app_routing_packages"
  }
}
