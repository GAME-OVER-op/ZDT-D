package com.android.zdtd.service

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.remote.RemoteControlCenter
import com.android.zdtd.service.remote.RemoteSetupViewModel
import com.android.zdtd.service.ui.remote.RemoteSetupScreen
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.flow.collect

class RemoteSetupActivity : AppCompatActivity() {
  private val vm: RemoteSetupViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val themeMode = com.android.zdtd.service.ui.theme.ZdtdThemeMode.fromStorage(
        RootConfigManager(applicationContext).getThemeMode()
      )
      ZdtdTheme(themeMode = themeMode) {
        Surface {
          val state = vm.state.collectAsStateWithLifecycle().value
          LaunchedEffect(Unit) {
            var wasConnected = RemoteControlCenter.target.value != null
            RemoteControlCenter.target.collect { target ->
              val connected = target != null
              if (!wasConnected && connected) finish()
              wasConnected = connected
            }
          }
          RemoteSetupScreen(
            state = state,
            onBack = { finish() },
            onStartHost = vm::startHost,
            onStopHost = vm::stopHost,
            onRefreshDiscovery = vm::startDiscovery,
            onManualConnect = vm::connectManual,
            onScanQr = ::scanQr,
            onConnectKnown = vm::connectKnown,
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    vm.refreshCapabilities()
    vm.refreshHistory()
  }

  private fun scanQr() {
    val options = GmsBarcodeScannerOptions.Builder()
      .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
      .enableAutoZoom()
      .build()
    GmsBarcodeScanning.getClient(this, options)
      .startScan()
      .addOnSuccessListener { barcode: Barcode ->
        val raw = barcode.rawValue.orEmpty()
        if (raw.isNotBlank()) vm.connectQr(raw)
      }
      .addOnFailureListener { e: Exception ->
        vm.setError(e.message ?: "Не удалось открыть сканер QR")
      }
  }
}
