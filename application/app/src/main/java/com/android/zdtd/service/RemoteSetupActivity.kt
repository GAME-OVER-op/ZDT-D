package com.android.zdtd.service

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.remote.RemoteSetupViewModel
import com.android.zdtd.service.ui.remote.RemoteSetupScreen
import com.android.zdtd.service.ui.theme.ZdtdTheme

class RemoteSetupActivity : AppCompatActivity() {
  private val vm: RemoteSetupViewModel by viewModels()

  private val cameraPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) {
    vm.updateCameraPermission()
  }

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
            onQrScanned = vm::connectQr,
            onConnectKnown = vm::connectKnown,
            onRequestCameraPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    vm.updateCameraPermission()
    vm.refreshCapabilities()
    vm.refreshHistory()
  }
}
