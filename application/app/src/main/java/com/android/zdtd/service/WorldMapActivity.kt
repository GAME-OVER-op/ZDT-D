package com.android.zdtd.service

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.android.zdtd.service.worldmap.ui.dashboard.NetworkDashboardScreen
import com.android.zdtd.service.worldmap.ui.dashboard.NetworkDashboardViewModel

class WorldMapActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      ZdtdTheme {
        Surface(color = Color(0xFF090B1A)) {
          val vm: NetworkDashboardViewModel = viewModel()
          NetworkDashboardScreen(
            viewModel = vm,
            onBack = { finish() },
          )
        }
      }
    }
  }
}
