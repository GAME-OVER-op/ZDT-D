package com.android.zdtd.service

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
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
import androidx.lifecycle.lifecycleScope
import com.android.zdtd.service.ui.NonRootApp
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.android.zdtd.service.ui.theme.ZdtdThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NonRootActivity : AppCompatActivity() {
  private val vm: NonRootViewModel by viewModels()
  private val vpsVm: com.android.zdtd.service.vps.VpsViewModel by viewModels()
  private val pluginInstallPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
      vm.installDownloadedTgWsPlugin()
    } else {
      vm.onTgWsPluginInstallPermissionDenied()
    }
  }
  private val fullAppInstallPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
      vm.installDownloadedFullApp()
    } else {
      vm.onFullAppInstallPermissionDenied()
    }
  }
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
      val appRoutingMode by vm.appRoutingMode.collectAsStateWithLifecycle()
      val appRoutingPackages by vm.appRoutingPackages.collectAsStateWithLifecycle()
      val cascadeState by vm.cascadeState.collectAsStateWithLifecycle()
      val t2sApiPort by vm.t2sApiPort.collectAsStateWithLifecycle()
      val vpnState by vm.vpnState.collectAsStateWithLifecycle()
      val vpnLastError by vm.vpnLastError.collectAsStateWithLifecycle()
      val vpnLogs by vm.vpnLogs.collectAsStateWithLifecycle()
      val tgWsConfig by vm.tgWsConfig.collectAsStateWithLifecycle()
      val tgWsPluginState by vm.tgWsPluginState.collectAsStateWithLifecycle()
      val tgWsRuntimeState by vm.tgWsRuntimeState.collectAsStateWithLifecycle()
      val tgWsRuntimeLastError by vm.tgWsRuntimeLastError.collectAsStateWithLifecycle()
      val fullAppUpgradeState by vm.fullAppUpgradeState.collectAsStateWithLifecycle()
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
            appRoutingMode = appRoutingMode,
            appRoutingPackages = appRoutingPackages,
            cascadeState = cascadeState,
            t2sApiPort = t2sApiPort,
            vpnState = vpnState,
            vpnLastError = vpnLastError,
            vpnLogs = vpnLogs,
            tgWsConfig = tgWsConfig,
            tgWsPluginState = tgWsPluginState,
            tgWsRuntimeState = tgWsRuntimeState,
            tgWsRuntimeLastError = tgWsRuntimeLastError,
            fullAppUpgradeState = fullAppUpgradeState,
            vpsViewModel = vpsVm,
            onVpnStart = ::requestNonRootVpnStart,
            onVpnStop = { NonRootVpnService.stop(this@NonRootActivity) },
            onRequestFullVersion = ::requestFullVersionInstall,
            onLanguageModeChange = vm::setLanguageMode,
            onThemeModeChange = vm::setThemeMode,
            onWorkModeChange = vm::setWorkMode,
            onAppRoutingModeChange = vm::setAppRoutingMode,
            onAppRoutingPackagesChange = vm::setAppRoutingPackages,
            onRestartVpn = ::restartNonRootVpn,
            onDirectSelectedProfileChange = vm::setDirectSelectedProfile,
            onCreateCascadeProfile = vm::createCascadeProfile,
            onUpdateCascadeProfile = vm::updateCascadeProfile,
            onAddCascadeServer = vm::addCascadeServer,
            onUpdateCascadeServer = vm::updateCascadeServer,
            onMoveCascadeServer = vm::moveCascadeServer,
            onDeleteCascadeServer = vm::deleteCascadeServer,
            onCascadeServerPortChange = vm::setCascadeServerPort,
            onCascadeServerAuxPortChange = vm::setCascadeServerAuxPort,
            onCascadeProfilePortChange = vm::setCascadeProfilePort,
            onCascadeProfileByeDpiPortChange = vm::setCascadeProfileByeDpiPort,
            onDeleteCascadeProfile = vm::deleteCascadeProfile,
            onCascadeBackendModeChange = vm::setCascadeBackendMode,
            onT2sConfigChange = vm::setT2sConfig,
            onCascadeRouteChange = vm::setCascadeRoute,
            onTgWsConfigChange = vm::setTgWsConfig,
            onTgWsPortChange = vm::setTgWsPort,
            onInstallOrUpdateTgWsPlugin = ::requestTgWsPluginInstall,
            onRefreshTgWsPlugin = { vm.refreshTgWsPlugin(checkRemote = true) },
            onImportVpsConfig = vm::importVpsConfig,
          )
        }
      }
    }
  }


  override fun onResume() {
    super.onResume()
    vm.refreshTgWsPlugin(checkRemote = false)
  }

  private fun requestTgWsPluginInstall() {
    lifecycleScope.launch {
      if (!vm.downloadTgWsPlugin()) return@launch

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        pluginInstallPermissionLauncher.launch(
          Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        )
        return@launch
      }

      vm.installDownloadedTgWsPlugin()
    }
  }

  private fun requestFullVersionInstall() {
    lifecycleScope.launch {
      if (!vm.downloadLatestFullApp()) return@launch
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        fullAppInstallPermissionLauncher.launch(
          Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        )
      } else {
        vm.installDownloadedFullApp()
      }
    }
  }

  private fun restartNonRootVpn() {
    lifecycleScope.launch {
      NonRootVpnService.stop(this@NonRootActivity)
      var attempts = 0
      while (attempts < 60) {
        val state = NonRootVpnRuntime.state.value
        if (state == NonRootVpnState.STOPPED || state == NonRootVpnState.ERROR) break
        attempts += 1
        delay(100L)
      }
      requestNonRootVpnStart()
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
