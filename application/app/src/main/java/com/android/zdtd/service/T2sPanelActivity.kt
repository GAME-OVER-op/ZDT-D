package com.android.zdtd.service

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import com.android.zdtd.service.api.T2sApiClient
import com.android.zdtd.service.ui.t2s.T2sPanelScreen
import com.android.zdtd.service.ui.theme.ZdtdTheme

class T2sPanelActivity : AppCompatActivity() {
  companion object {
    const val EXTRA_SCOPE = "com.android.zdtd.service.extra.T2S_SCOPE"
    const val EXTRA_PORT = "com.android.zdtd.service.extra.T2S_PORT"
    const val EXTRA_TITLE = "com.android.zdtd.service.extra.T2S_TITLE"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val scope = intent.getStringExtra(EXTRA_SCOPE).orEmpty()
    val port = intent.getIntExtra(EXTRA_PORT, 0).takeIf { it in 1..65535 } ?: 0
    val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { scope.ifBlank { "t2s" } }
    val root = RootConfigManager(applicationContext)
    setContent {
      val client = remember(port) { T2sApiClient(root, port) }
      ZdtdTheme(themeMode = com.android.zdtd.service.ui.theme.ZdtdThemeMode.fromStorage(root.getThemeMode())) {
        Surface {
          T2sPanelScreen(
            title = title,
            scope = scope,
            port = port,
            client = client,
            onClose = { finish() },
          )
        }
      }
    }
  }
}
