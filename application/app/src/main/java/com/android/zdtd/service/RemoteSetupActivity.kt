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
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    vm.refreshCapabilities()
  }

}
