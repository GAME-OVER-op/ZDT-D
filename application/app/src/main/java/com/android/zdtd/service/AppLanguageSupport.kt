package com.android.zdtd.service

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguageSupport {

  private val languageTagsByMode: Map<String, String> = mapOf(
    "en" to "en",
    "ru" to "ru",
    "fa" to "fa",
    "tr" to "tr",
    "ar" to "ar",
    "zh-cn" to "zh-CN",
    "es" to "es",
    "pt-br" to "pt-BR",
    "id" to "id",
    "hi" to "hi",
    "uk" to "uk",
    "de" to "de",
    "fr" to "fr",
    "vi" to "vi",
    "ko" to "ko",
    "ja" to "ja",
  )

  fun normalizeLanguageMode(mode: String): String =
    mode.trim().lowercase(Locale.ROOT).replace("_", "-")
      .takeIf { it == "auto" || it in languageTagsByMode }
      ?: "auto"

  fun languageTagForMode(mode: String): String? = languageTagsByMode[normalizeLanguageMode(mode)]

  fun applyPersistedAppLocale(context: Context) {
    val mode = normalizeLanguageMode(RootConfigManager(context.applicationContext).getAppLanguageMode())
    val tag = languageTagForMode(mode)
    if (tag.isNullOrBlank()) {
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    } else {
      AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
  }

  fun localizedAppContext(base: Context): Context = applyLocale(base, preferredLocale(base))

  fun preferredLocale(base: Context): Locale? {
    val tag = languageTagForMode(RootConfigManager(base.applicationContext).getAppLanguageMode())
    return tag?.let { Locale.forLanguageTag(it) }
  }

  fun resolveAppLabel(base: Context, packageName: String): String {
    val pm = base.packageManager
    return runCatching {
      val info = if (Build.VERSION.SDK_INT >= 33) {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
      }
      info.nonLocalizedLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: resolveLocalizedLabel(base, info.packageName, info.labelRes)
        ?: info.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: packageName
    }.getOrDefault(packageName)
  }

  private fun resolveLocalizedLabel(base: Context, packageName: String, labelRes: Int): String? {
    if (labelRes == 0) return null
    return runCatching {
      val packageContext = base.createPackageContext(packageName, 0)
      val localizedPackageContext = applyLocale(packageContext, preferredLocale(base))
      localizedPackageContext.resources.getText(labelRes).toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
  }

  private fun applyLocale(base: Context, locale: Locale?): Context {
    if (locale == null) return base
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    if (Build.VERSION.SDK_INT >= 24) {
      config.setLocales(android.os.LocaleList(locale))
    }
    return base.createConfigurationContext(config)
  }
}
