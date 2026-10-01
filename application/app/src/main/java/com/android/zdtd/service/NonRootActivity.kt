package com.android.zdtd.service

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.ui.NonRootApp
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.android.zdtd.service.ui.theme.ZdtdThemeMode

class NonRootActivity : AppCompatActivity() {
  private val vm: NonRootViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    AppLanguageSupport.applyPersistedAppLocale(applicationContext)
    CrashLogger.install(applicationContext)
    NonRootRuntimeStore(applicationContext).ensureLayout()
    applyInitialStatusBarAppearance()

    setContent {
      val languageMode by vm.languageMode.collectAsStateWithLifecycle()
      val themeMode by vm.themeMode.collectAsStateWithLifecycle()
      ZdtdTheme(themeMode = ZdtdThemeMode.fromStorage(themeMode)) {
        val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
          WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = lightBars
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
          }
        }
        Surface {
          NonRootApp(
            languageMode = languageMode,
            themeMode = themeMode,
            onLanguageModeChange = vm::setLanguageMode,
            onThemeModeChange = vm::setThemeMode,
          )
        }
      }
    }
  }

  private fun applyInitialStatusBarAppearance() {
    val mode = ZdtdThemeMode.fromStorage(RootConfigManager(applicationContext).getThemeMode())
    val useDark = when (mode) {
      ZdtdThemeMode.LIGHT -> false
      ZdtdThemeMode.DARK -> true
      ZdtdThemeMode.SYSTEM -> {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        nightMode == Configuration.UI_MODE_NIGHT_YES
      }
    }
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !useDark
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isStatusBarContrastEnforced = false
    }
  }
}
