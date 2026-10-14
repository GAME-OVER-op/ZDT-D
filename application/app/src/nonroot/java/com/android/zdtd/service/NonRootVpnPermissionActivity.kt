package com.android.zdtd.service

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Transparent one-shot activity used only when Android still needs VPN consent.
 * It never shows the ZDT-D UI; after the system dialog it starts the non-root
 * VPN directly and immediately finishes.
 */
class NonRootVpnPermissionActivity : AppCompatActivity() {
  private val vpnPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      startVpnAndFinish()
    } else {
      finishWithoutTransition()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (savedInstanceState != null) return

    val prepareIntent = VpnService.prepare(this)
    if (prepareIntent == null) {
      startVpnAndFinish()
    } else {
      vpnPermissionLauncher.launch(prepareIntent)
    }
  }

  private fun startVpnAndFinish() {
    runCatching {
      ContextCompat.startForegroundService(this, NonRootVpnService.startIntent(this))
    }
    finishWithoutTransition()
  }

  private fun finishWithoutTransition() {
    finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)
  }
}
