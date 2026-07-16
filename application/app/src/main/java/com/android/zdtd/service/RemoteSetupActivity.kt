package com.android.zdtd.service

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.remote.RemoteSetupViewModel
import com.android.zdtd.service.ui.remote.RemoteSetupScreen
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.google.android.gms.code.scanner.GmsBarcodeScannerOptions
import com.google.android.gms.code.scanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

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
      .addOnSuccessListener { barcode ->
        val raw = barcode.rawValue.orEmpty()
        if (raw.isNotBlank()) vm.connectQr(raw)
      }
      .addOnFailureListener { e ->
        vm.setError(e.message ?: "Не удалось открыть сканер QR")
      }
  }
}
