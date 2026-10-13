package com.android.zdtd.service

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.android.zdtd.service.singbox.importer.SingBoxOneLineImporter
import com.android.zdtd.service.vps.VpsConfigResult
import com.android.zdtd.service.vps.VpsServiceKind
import com.android.zdtd.service.tgwsplugin.TgWsPluginManager
import com.android.zdtd.service.tgwsplugin.TgWsPluginState
import com.android.zdtd.service.tgwsplugin.TgWsPluginStateBus

class NonRootViewModel(application: Application) : AndroidViewModel(application) {
  private val config = RootConfigManager(application.applicationContext)
  private val nonRootSettings = NonRootSettingsStore(application.applicationContext)
  private val portRegistry = NonRootPortRegistry(application.applicationContext)
  private val legacyDirectConfigStore = NonRootDirectConfigStore(application.applicationContext)
  private val cascadeStore = NonRootCascadeStore(application.applicationContext)
  private val tgWsStore = NonRootTgWsStore(application.applicationContext)
  private val tgWsPluginManager = TgWsPluginManager(application.applicationContext)
  private val fullAppUpgradeManager = FullAppUpgradeManager(application.applicationContext)

  private val _languageMode = MutableStateFlow(config.getAppLanguageMode())
  val languageMode: StateFlow<String> = _languageMode.asStateFlow()

  private val _themeMode = MutableStateFlow(config.getThemeMode())
  val themeMode: StateFlow<String> = _themeMode.asStateFlow()

  private val _workMode = MutableStateFlow(nonRootSettings.getWorkMode())
  val workMode: StateFlow<NonRootWorkMode> = _workMode.asStateFlow()

  private val _appRoutingMode = MutableStateFlow(nonRootSettings.getAppRoutingMode())
  val appRoutingMode: StateFlow<NonRootAppRoutingMode> = _appRoutingMode.asStateFlow()

  private val _appRoutingPackages = MutableStateFlow(nonRootSettings.getAppRoutingPackages())
  val appRoutingPackages: StateFlow<Set<String>> = _appRoutingPackages.asStateFlow()

  private val initialCascadeState = cascadeStore.importLegacyDirectProfileIfNeeded(
    legacyDirectConfigStore.load().takeIf { legacyDirectConfigStore.hasSavedConfig() }
  )

  private val _cascadeState = MutableStateFlow(initialCascadeState)
  val cascadeState: StateFlow<NonRootCascadeState> = _cascadeState.asStateFlow()

  private val _t2sListenPort = MutableStateFlow(portRegistry.getOrAllocate(NonRootPortRegistry.T2S_LISTEN_KEY))
  val t2sListenPort: StateFlow<Int> = _t2sListenPort.asStateFlow()

  private val _t2sApiPort = MutableStateFlow(portRegistry.getOrAllocate(NonRootPortRegistry.T2S_API_KEY))
  val t2sApiPort: StateFlow<Int> = _t2sApiPort.asStateFlow()

  private val _tgWsConfig = MutableStateFlow(tgWsStore.load())
  val tgWsConfig: StateFlow<NonRootTgWsConfig> = _tgWsConfig.asStateFlow()
  val tgWsPluginState: StateFlow<TgWsPluginState> = TgWsPluginStateBus.state
  val tgWsRuntimeState: StateFlow<NonRootTgWsRuntimeState> = NonRootTgWsRuntime.state
  val tgWsRuntimeLastError: StateFlow<String?> = NonRootTgWsRuntime.lastError
  val fullAppUpgradeState: StateFlow<FullAppUpgradeState> = fullAppUpgradeManager.state

  val vpnState: StateFlow<NonRootVpnState> = NonRootVpnRuntime.state
  val vpnLastError: StateFlow<String?> = NonRootVpnRuntime.lastError
  val vpnLogs: StateFlow<List<NonRootRuntimeLogEntry>> = NonRootVpnRuntime.logs
  val vpnProfileFailures: StateFlow<List<NonRootProfileFailure>> = NonRootVpnRuntime.profileFailures

  init {
    // Create the private runtime/token now so T2S and proxy backends can rely on
    // one application-owned identity as soon as non-root mode is chosen.
    NonRootRuntimeStore(application.applicationContext).ensureLayout()
    val pluginInstalled = tgWsPluginManager.refreshLocal().installed
    if (_tgWsConfig.value.enabled && pluginInstalled) {
      NonRootTgWsService.start(application.applicationContext)
    } else if (_tgWsConfig.value.enabled && !pluginInstalled) {
      val disabled = tgWsStore.save(_tgWsConfig.value.copy(enabled = false))
      _tgWsConfig.value = disabled
    }
    viewModelScope.launch { tgWsPluginManager.refreshRemote() }
  }

  fun setLanguageMode(mode: String) {
    config.setAppLanguageMode(mode)
    val persisted = config.getAppLanguageMode()
    _languageMode.value = persisted
    val tag = AppLanguageSupport.languageTagForMode(persisted)
    if (tag.isNullOrBlank()) {
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    } else {
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
  }

  fun setThemeMode(mode: String) {
    config.setThemeMode(mode)
    _themeMode.value = config.getThemeMode()
  }

  fun setWorkMode(mode: NonRootWorkMode) {
    nonRootSettings.setWorkMode(mode)
    _workMode.value = mode
  }

  fun setAppRoutingMode(mode: NonRootAppRoutingMode) {
    nonRootSettings.setAppRoutingMode(mode)
    _appRoutingMode.value = mode
  }

  fun setAppRoutingPackages(packages: Set<String>) {
    nonRootSettings.setAppRoutingPackages(packages)
    _appRoutingPackages.value = nonRootSettings.getAppRoutingPackages()
  }

  fun setDirectSelectedProfile(profileId: String?) {
    _cascadeState.value = cascadeStore.setDirectSelectedProfile(profileId)
  }

  fun createCascadeProfile(name: String, toolId: String) {
    _cascadeState.value = cascadeStore.createProfile(name, toolId)
  }

  fun addCascadeServer(profileId: String, name: String = "Server") {
    _cascadeState.value = cascadeStore.addServer(profileId, name)
  }

  fun updateCascadeServer(profileId: String, server: NonRootBackendServer) {
    _cascadeState.value = cascadeStore.updateServer(profileId, server)
  }

  fun moveCascadeServer(profileId: String, fromIndex: Int, toIndex: Int) {
    _cascadeState.value = cascadeStore.moveServer(profileId, fromIndex, toIndex)
  }

  fun deleteCascadeServer(profileId: String, serverId: String) {
    _cascadeState.value = cascadeStore.deleteServer(profileId, serverId)
  }

  fun setCascadeServerPort(profileId: String, serverId: String, port: Int): Boolean {
    val updated = cascadeStore.setServerPort(profileId, serverId, port) ?: return false
    _cascadeState.value = updated
    return true
  }

  fun setCascadeServerAuxPort(profileId: String, serverId: String, port: Int): Boolean {
    val updated = cascadeStore.setServerAuxPort(profileId, serverId, port) ?: return false
    _cascadeState.value = updated
    return true
  }

  fun updateCascadeProfile(profile: NonRootCascadeProfile) {
    _cascadeState.value = cascadeStore.updateProfile(profile)
  }

  fun setCascadeProfilePort(profileId: String, port: Int): Boolean {
    val updated = cascadeStore.setProfilePort(profileId, port) ?: return false
    _cascadeState.value = updated
    return true
  }

  fun setCascadeProfileByeDpiPort(profileId: String, port: Int): Boolean {
    val updated = cascadeStore.setProfileByeDpiPort(profileId, port) ?: return false
    _cascadeState.value = updated
    return true
  }

  fun setT2sConfig(config: NonRootT2sConfig) {
    _cascadeState.value = cascadeStore.setT2sConfig(config)
  }

  fun deleteCascadeProfile(profileId: String) {
    _cascadeState.value = cascadeStore.deleteProfile(profileId)
  }

  fun setCascadeBackendMode(mode: NonRootCascadeBackendMode) {
    _cascadeState.value = cascadeStore.setBackendMode(mode)
  }

  fun setCascadeRoute(route: List<NonRootCascadeRouteItem>) {
    _cascadeState.value = cascadeStore.setRoute(route)
  }

  fun setTgWsConfig(config: NonRootTgWsConfig) {
    val pluginInstalled = tgWsPluginManager.refreshLocal().installed
    val safeConfig = if (config.enabled && !pluginInstalled) config.copy(enabled = false) else config
    val saved = tgWsStore.save(safeConfig)
    _tgWsConfig.value = saved
    if (saved.enabled) {
      NonRootTgWsService.restart(getApplication<Application>().applicationContext)
    } else {
      NonRootTgWsService.stop(getApplication<Application>().applicationContext, persistDisabled = false)
    }
  }

  fun refreshTgWsPlugin(checkRemote: Boolean = true) {
    tgWsPluginManager.refreshLocal()
    if (checkRemote) viewModelScope.launch { tgWsPluginManager.refreshRemote() }
  }

  suspend fun downloadTgWsPlugin(): Boolean {
    val state = tgWsPluginManager.downloadPlugin()
    return state.errorMessage == null && tgWsPluginManager.hasDownloadedPlugin()
  }

  fun installDownloadedTgWsPlugin(): Boolean =
    tgWsPluginManager.installDownloadedPlugin().let { it.installing && it.errorMessage == null }

  fun onTgWsPluginInstallPermissionDenied() {
    tgWsPluginManager.markInstallPermissionDenied()
  }

  suspend fun downloadLatestFullApp(): Boolean = fullAppUpgradeManager.downloadLatestBundledApk()

  fun installDownloadedFullApp(): Boolean = fullAppUpgradeManager.installDownloadedApk()

  fun onApkInstallersReturned(plugin: Boolean, fullApp: Boolean) {
    if (plugin) tgWsPluginManager.onInstallerReturned()
    if (fullApp) fullAppUpgradeManager.onInstallerReturned()
  }

  fun onFullAppInstallPermissionDenied() {
    fullAppUpgradeManager.markInstallPermissionDenied()
  }

  fun removeTgWsPlugin() {
    if (_tgWsConfig.value.enabled) {
      setTgWsConfig(_tgWsConfig.value.copy(enabled = false))
    }
    tgWsPluginManager.requestUninstall()
  }

  fun setTgWsPort(port: Int): Boolean {
    val updated = tgWsStore.setPort(port) ?: return false
    _tgWsConfig.value = updated
    return true
  }

  fun importVpsConfig(result: VpsConfigResult, sourceName: String, targetProfileId: String?) {
    val toolId = when (result.kind) {
      VpsServiceKind.HYSTERIA2 -> NonRootCascadeProfile.TOOL_HYSTERIA2
      VpsServiceKind.WIREPROXY -> NonRootCascadeProfile.TOOL_WIREPROXY
      VpsServiceKind.XRAY -> NonRootCascadeProfile.TOOL_SING_BOX
      else -> return
    }

    var state = cascadeStore.load()
    val profile = targetProfileId
      ?.let { id -> state.profiles.firstOrNull { it.id == id && it.toolId == toolId } }
      ?: run {
        val beforeIds = state.profiles.mapTo(mutableSetOf()) { it.id }
        state = cascadeStore.createProfile(sourceName.ifBlank { result.clientName }, toolId)
        state.profiles.firstOrNull { it.id !in beforeIds } ?: return
      }

    val targetServer = if (targetProfileId != null) {
      val beforeIds = profile.servers.mapTo(mutableSetOf()) { it.id }
      state = cascadeStore.addServer(profile.id, result.clientName.ifBlank { "Server" })
      state.profiles.firstOrNull { it.id == profile.id }?.servers?.firstOrNull { it.id !in beforeIds } ?: return
    } else {
      profile.servers.firstOrNull() ?: return
    }

    val configText = when (result.kind) {
      VpsServiceKind.XRAY -> {
        val source = result.shareLink?.takeIf { it.isNotBlank() } ?: result.content
        runCatching { SingBoxOneLineImporter.import(source, targetServer.port).configJson }.getOrElse { result.content }
      }
      else -> result.content
    }
    state = cascadeStore.updateServer(
      profile.id,
      targetServer.copy(name = result.clientName.ifBlank { "Server" }, configText = configText),
    )
    _cascadeState.value = state
  }

}
