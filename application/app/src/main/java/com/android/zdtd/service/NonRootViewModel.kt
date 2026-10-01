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

  private val _languageMode = MutableStateFlow(config.getAppLanguageMode())
  val languageMode: StateFlow<String> = _languageMode.asStateFlow()

  private val _themeMode = MutableStateFlow(config.getThemeMode())
  val themeMode: StateFlow<String> = _themeMode.asStateFlow()

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
}
