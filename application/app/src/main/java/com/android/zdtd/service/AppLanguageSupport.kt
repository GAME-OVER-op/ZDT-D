package com.android.zdtd.service

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguageSupport {

  fun applyPersistedAppLocale(context: Context) {
    val mode = RootConfigManager(context.applicationContext).getAppLanguageMode().trim().lowercase()
    when (mode) {
      "auto", "" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
      "ru" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
      "en" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
      else -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }
  }

  fun localizedAppContext(base: Context): Context = applyLocale(base, preferredLocale(base))

  fun preferredLocale(base: Context): Locale? {
    return when (RootConfigManager(base.applicationContext).getAppLanguageMode().trim().lowercase()) {
      "ru" -> Locale("ru")
      "en" -> Locale("en")
      else -> null
    }
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
