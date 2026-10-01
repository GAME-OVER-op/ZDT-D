package com.android.zdtd.service

import android.app.Activity
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.ui.NonRootApp
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.android.zdtd.service.ui.theme.ZdtdThemeMode

class NonRootActivity : AppCompatActivity() {
  private val vm: NonRootViewModel by viewModels()
  private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) startNonRootVpnService()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    AppLanguageSupport.applyPersistedAppLocale(applicationContext)
    CrashLogger.install(applicationContext)
    NonRootRuntimeStore(applicationContext).ensureLayout()
    applyInitialStatusBarAppearance()

    setContent {
      val languageMode by vm.languageMode.collectAsStateWithLifecycle()
      val themeMode by vm.themeMode.collectAsStateWithLifecycle()
      val workMode by vm.workMode.collectAsStateWithLifecycle()
      val directOperaPort by vm.directOperaPort.collectAsStateWithLifecycle()
      val directByeDpiPort by vm.directByeDpiPort.collectAsStateWithLifecycle()
      val directOperaConfig by vm.directOperaConfig.collectAsStateWithLifecycle()
      val cascadeState by vm.cascadeState.collectAsStateWithLifecycle()
      val t2sApiPort by vm.t2sApiPort.collectAsStateWithLifecycle()
      val vpnState by vm.vpnState.collectAsStateWithLifecycle()
      val vpnLastError by vm.vpnLastError.collectAsStateWithLifecycle()
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
            workMode = workMode,
            directOperaPort = directOperaPort,
            directByeDpiPort = directByeDpiPort,
            directOperaConfig = directOperaConfig,
            cascadeState = cascadeState,
            t2sApiPort = t2sApiPort,
            vpnState = vpnState,
            vpnLastError = vpnLastError,
            onVpnStart = ::requestNonRootVpnStart,
            onVpnStop = { NonRootVpnService.stop(this@NonRootActivity) },
            onLanguageModeChange = vm::setLanguageMode,
            onThemeModeChange = vm::setThemeMode,
            onWorkModeChange = vm::setWorkMode,
            onDirectOperaPortChange = vm::setDirectOperaPort,
            onDirectByeDpiPortChange = vm::setDirectByeDpiPort,
            onDirectOperaConfigChange = vm::setDirectOperaConfig,
            onCreateCascadeProfile = vm::createCascadeProfile,
            onUpdateCascadeProfile = vm::updateCascadeProfile,
            onCascadeProfilePortChange = vm::setCascadeProfilePort,
            onCascadeProfileByeDpiPortChange = vm::setCascadeProfileByeDpiPort,
            onDeleteCascadeProfile = vm::deleteCascadeProfile,
            onCascadeBackendModeChange = vm::setCascadeBackendMode,
            onT2sConfigChange = vm::setT2sConfig,
            onCascadeRouteChange = vm::setCascadeRoute,
          )
        }
      }
    }
  }

  private fun requestNonRootVpnStart() {
    val prepareIntent = VpnService.prepare(this)
    if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent)
    else startNonRootVpnService()
  }

  private fun startNonRootVpnService() {
    ContextCompat.startForegroundService(this, NonRootVpnService.startIntent(this))
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
