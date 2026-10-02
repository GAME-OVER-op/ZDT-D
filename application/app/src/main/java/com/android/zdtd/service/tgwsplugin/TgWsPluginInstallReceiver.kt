package com.android.zdtd.service.tgwsplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class TgWsPluginInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
      }
      if (confirmation != null) {
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(confirmation)
        return
      }
    }
    when (status) {
      PackageInstaller.STATUS_SUCCESS -> {
        TgWsPluginManager(context).refreshLocal(clearError = true)
      }
      else -> {
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
          ?: "PackageInstaller status $status"
        TgWsPluginStateBus.update {
          it.copy(downloading = false, installing = false, removing = false, errorMessage = message)
        }
        TgWsPluginManager(context).refreshLocal()
      }
    }
  }

  companion object {
    const val ACTION_INSTALL_RESULT = "com.android.zdtd.service.action.TGWS_PLUGIN_INSTALL_RESULT"
    const val ACTION_UNINSTALL_RESULT = "com.android.zdtd.service.action.TGWS_PLUGIN_UNINSTALL_RESULT"
  }
}
