package com.android.zdtd.service

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NonRootViewModel(application: Application) : AndroidViewModel(application) {
  private val config = RootConfigManager(application.applicationContext)
  private val nonRootSettings = NonRootSettingsStore(application.applicationContext)
  private val portRegistry = NonRootPortRegistry(application.applicationContext)
  private val directConfigStore = NonRootDirectConfigStore(application.applicationContext)
  private val cascadeStore = NonRootCascadeStore(application.applicationContext)

  private val _languageMode = MutableStateFlow(config.getAppLanguageMode())
  val languageMode: StateFlow<String> = _languageMode.asStateFlow()

  private val _themeMode = MutableStateFlow(config.getThemeMode())
  val themeMode: StateFlow<String> = _themeMode.asStateFlow()

  private val _workMode = MutableStateFlow(nonRootSettings.getWorkMode())
  val workMode: StateFlow<NonRootWorkMode> = _workMode.asStateFlow()

  private val _directOperaPort = MutableStateFlow(portRegistry.getOrAllocate(NonRootPortRegistry.DIRECT_OPERA_KEY))
  val directOperaPort: StateFlow<Int> = _directOperaPort.asStateFlow()

  private val _directByeDpiPort = MutableStateFlow(portRegistry.getOrAllocate(NonRootPortRegistry.DIRECT_BYEDPI_KEY))
  val directByeDpiPort: StateFlow<Int> = _directByeDpiPort.asStateFlow()

  private val _directOperaConfig = MutableStateFlow(directConfigStore.load())
  val directOperaConfig: StateFlow<NonRootDirectOperaConfig> = _directOperaConfig.asStateFlow()

  private val _cascadeState = MutableStateFlow(cascadeStore.load())
  val cascadeState: StateFlow<NonRootCascadeState> = _cascadeState.asStateFlow()

  private val _t2sListenPort = MutableStateFlow(portRegistry.getOrAllocate(NonRootPortRegistry.T2S_LISTEN_KEY))
  val t2sListenPort: StateFlow<Int> = _t2sListenPort.asStateFlow()

  private val _t2sApiPort = MutableStateFlow(portRegistry.getOrAllocate(NonRootPortRegistry.T2S_API_KEY))
  val t2sApiPort: StateFlow<Int> = _t2sApiPort.asStateFlow()

  val vpnState: StateFlow<NonRootVpnState> = NonRootVpnRuntime.state
  val vpnLastError: StateFlow<String?> = NonRootVpnRuntime.lastError
  val vpnLogs: StateFlow<List<NonRootRuntimeLogEntry>> = NonRootVpnRuntime.logs

  init {
    // Create the private runtime/token now so T2S and future proxy backends can
    // rely on one application-owned identity as soon as non-root mode is chosen.
    NonRootRuntimeStore(application.applicationContext).ensureLayout()
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

  fun setDirectOperaPort(port: Int): Boolean {
    val saved = portRegistry.set(NonRootPortRegistry.DIRECT_OPERA_KEY, port)
    if (saved) _directOperaPort.value = port
    return saved
  }

  fun setDirectByeDpiPort(port: Int): Boolean {
    val saved = portRegistry.set(NonRootPortRegistry.DIRECT_BYEDPI_KEY, port)
    if (saved) _directByeDpiPort.value = port
    return saved
  }

  fun setDirectOperaConfig(config: NonRootDirectOperaConfig) {
    directConfigStore.save(config)
    _directOperaConfig.value = config
  }

  fun createCascadeProfile(name: String) {
    _cascadeState.value = cascadeStore.createProfile(name)
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
}

