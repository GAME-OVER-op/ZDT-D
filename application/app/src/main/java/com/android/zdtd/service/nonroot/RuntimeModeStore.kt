package com.android.zdtd.service.nonroot

import android.content.Context

enum class RuntimeMode { ROOT, NON_ROOT }

class RuntimeModeStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("zdtd_runtime_mode", Context.MODE_PRIVATE)

    fun runtimeMode(): RuntimeMode = when (prefs.getString(KEY_MODE, MODE_ROOT)) {
        MODE_NON_ROOT -> RuntimeMode.NON_ROOT
        else -> RuntimeMode.ROOT
    }

    fun isNonRootMode(): Boolean = runtimeMode() == RuntimeMode.NON_ROOT
    fun isNonRootWarningAccepted(): Boolean = prefs.getBoolean(KEY_NON_ROOT_WARNING_ACCEPTED, false)

    fun enableNonRootMode() {
        prefs.edit()
            .putString(KEY_MODE, MODE_NON_ROOT)
            .putBoolean(KEY_NON_ROOT_WARNING_ACCEPTED, true)
            .commit()
    }

    fun switchToRootMode() {
        prefs.edit().putString(KEY_MODE, MODE_ROOT).commit()
    }

    fun setNonRootRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_NON_ROOT_RUNNING, running).apply()
    }

    fun isNonRootRunning(): Boolean = prefs.getBoolean(KEY_NON_ROOT_RUNNING, false)

    companion object {
        private const val KEY_MODE = "runtime_mode"
        private const val KEY_NON_ROOT_WARNING_ACCEPTED = "non_root_warning_accepted"
        private const val KEY_NON_ROOT_RUNNING = "non_root_running"
        private const val MODE_ROOT = "root"
        private const val MODE_NON_ROOT = "non_root"
    }
}
