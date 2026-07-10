package com.android.zdtd.service

import com.android.zdtd.service.api.ApiModels


enum class AppReleaseBuildStatus {
  NONE,
  PREPARING,
  READY,
  FAILED,
}

enum class AppReleaseStageStatus {
  WAITING,
  RUNNING,
  DONE,
  FAILED,
}

data class AppReleaseBuildStageUi(
  val id: String,
  val titleRes: Int,
  val status: AppReleaseStageStatus = AppReleaseStageStatus.WAITING,
)

data class AppReleaseBuildUi(
  val status: AppReleaseBuildStatus = AppReleaseBuildStatus.NONE,
  val runId: Long? = null,
  val runUrl: String? = null,
  val messageRes: Int? = null,
  val stages: List<AppReleaseBuildStageUi> = emptyList(),
)

/**
 * State for online app updates (APK downloaded from GitHub releases).
 */
data class AppUpdateUiState(
  val enabled: Boolean = true,
  /** App UI language mode: auto or one of the supported BCP-47 language tags. */
  val languageMode: String = "auto",
  /** App theme mode: system | light | dark */
  val themeMode: String = "system",
  /** Module protector mode: off | on | auto */
  val protectorMode: String = "off",
  /** Advanced energy saver: screen-off freeze and CPU affinity. */
  val energySaver: ApiModels.EnergySaverState = ApiModels.EnergySaverState(),
  val energySaverBusy: Boolean = false,
  /** Hotspot redirect via t2s: enabled flag, target program id and optional sing-box / wireproxy profile. */
  val hotspotT2sEnabled: Boolean = false,
  val hotspotMode: String = "proxy",
  val hotspotProgram: String = "",
  val hotspotProfile: String = "",
  val hotspotT2sTarget: String = "",
  val hotspotT2sSingboxProfile: String = "",
  val hotspotT2sWireproxyProfile: String = "",
  val hotspotT2sCaptureAll: Boolean = false,
  /** Captive authorization portal for hotspot clients (proxy mode). */
  val captivePortalEnabled: Boolean = false,
  val hotspotSingboxProfiles: List<ApiModels.SingBoxProfileChoice> = emptyList(),
  val hotspotWireproxyProfiles: List<ApiModels.SingBoxProfileChoice> = emptyList(),
  val hotspotProxyPrograms: List<ApiModels.Program> = emptyList(),
  val hotspotVpnPrograms: List<ApiModels.Program> = emptyList(),
  /** Advanced daemon/system settings. */
  val selinuxPermissiveEnabled: Boolean = false,
  val ipForwardEnabled: Boolean = false,
  val tproxyEnabled: Boolean = false,
  /** Port scan protection (proxyInfo). */
  val proxyInfoEnabled: Boolean = false,
  val proxyInfoAppsContent: String = "",
  val proxyInfoBusy: Boolean = false,
  val hidingStatus: ApiModels.HidingStatus = ApiModels.HidingStatus(),
  /** Block QUIC traffic for selected apps. */
  val blockedQuicEnabled: Boolean = false,
  val blockedQuicAppsContent: String = "",
  val blockedQuicBusy: Boolean = false,
  /** True while the app automatically stops the daemon, removes flag.sha256 and starts it again. */
  val resettingModuleIdentifier: Boolean = false,
  // App-owned notification about daemon status (running/stopped).
  val daemonStatusNotificationEnabled: Boolean = false,
  val checking: Boolean = false,
  val githubApiOnline: Boolean? = null,
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
  val releaseBuild: AppReleaseBuildUi = AppReleaseBuildUi(),

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
