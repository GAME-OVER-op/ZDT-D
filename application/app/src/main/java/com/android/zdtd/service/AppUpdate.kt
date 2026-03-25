package com.android.zdtd.service

/**
 * State for online app updates (APK downloaded from GitHub releases).
 */
data class AppUpdateUiState(
  val enabled: Boolean = true,
  /** App UI language mode: auto | ru | en */
  val languageMode: String = "auto",
  // App-owned notification about daemon status (running/stopped).
  val daemonStatusNotificationEnabled: Boolean = false,
  val checking: Boolean = false,
  val bannerVisible: Boolean = false,
  val urgent: Boolean = false,

  // Local (installed) app version
  val localVersionName: String = BuildConfig.VERSION_NAME,
  val localVersionCode: Int = BuildConfig.VERSION_CODE,

  // Remote (GitHub) version, derived from module.prop on main
  val remoteVersionName: String? = null,
  val remoteVersionCode: Int? = null,

  // Release info
  val releaseTag: String? = null,
  val releaseHtmlUrl: String? = null,
  val downloadUrl: String? = null,

  // Download UI
  val downloading: Boolean = false,
  val downloadPercent: Int = 0,
  val downloadSpeedBytesPerSec: Long = 0,
  val downloadedPath: String? = null,
  val needsUnknownSourcesPermission: Boolean = false,

  val errorText: String? = null,
)

sealed class AppUpdateEvent {
  data class OpenUrl(val url: String) : AppUpdateEvent()
  object OpenUnknownSourcesSettings : AppUpdateEvent()
  data class InstallApk(val filePath: String) : AppUpdateEvent()
}
