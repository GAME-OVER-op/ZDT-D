package com.android.zdtd.service

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import com.android.zdtd.service.ui.WelcomeScreen
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.android.zdtd.service.ui.theme.ZdtdThemeMode

/** Entry point used only by the non-root distribution. */
class NonRootLauncherActivity : AppCompatActivity() {
  private lateinit var config: RootConfigManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    config = RootConfigManager(applicationContext)
    AppLanguageSupport.applyPersistedAppLocale(applicationContext)
    CrashLogger.install(applicationContext)
    applyInitialStatusBarAppearance()

    if (config.isWelcomeAccepted()) {
      openNonRootApp()
      return
    }

    setContent {
      ZdtdTheme(themeMode = ZdtdThemeMode.fromStorage(config.getThemeMode())) {
        Surface {
          WelcomeScreen(
            onAccept = {
              config.setWelcomeAccepted(true)
              openNonRootApp()
            },
            welcomeBodyRes = R.string.setup_non_root_warning_body,
            featuresBodyRes = R.string.non_root_home_body,
            notesBodyRes = R.string.non_root_home_restrictions,
            requireArm64 = false,
          )
        }
      }
    }
  }

  private fun openNonRootApp() {
    config.setRuntimeMode("non_root")
    NonRootRuntimeStore(applicationContext).ensureLayout()
    startActivity(Intent(this, NonRootActivity::class.java))
    finish()
  }

  private fun applyInitialStatusBarAppearance() {
    val mode = ZdtdThemeMode.fromStorage(config.getThemeMode())
    val useDark = when (mode) {
      ZdtdThemeMode.LIGHT -> false
      ZdtdThemeMode.DARK -> true
      ZdtdThemeMode.SYSTEM -> {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        nightMode == Configuration.UI_MODE_NIGHT_YES
      }
    }
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !useDark
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced = false
  }
}
