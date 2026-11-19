package com.android.zdtd.service.ui

enum class Tab {
  HOME,
  STATS,
  APPS,
  SUPPORT,
}

sealed class AppsRoute {
  data object List : AppsRoute()
  data object AnalysisTools : AppsRoute()
  data object OptionalTools : AppsRoute()
  data object VpsServers : AppsRoute()
  data class VpsServer(val serverId: String) : AppsRoute()
  data class VpsTerminal(val serverId: String) : AppsRoute()
  data class VpsService(val serverId: String, val serviceId: String) : AppsRoute()
  data class VpsProfile(val serverId: String, val serviceId: String, val profileId: String) : AppsRoute()
  data object ConstructionStudio : AppsRoute()
  data object DpiDetector : AppsRoute()
  data object NfqwsTester : AppsRoute()
  data object Subscriptions : AppsRoute()
  data class Program(val programId: String) : AppsRoute()
  data class Profile(val programId: String, val profile: String) : AppsRoute()
}


/**
 * Stable String key for a nested Apps screen.
 *
 * RememberSaveableStateHolder stores keys in a Bundle on Android, so the key
 * must be a Bundle-supported type — passing the AppsRoute object itself crashes
 * with "Type of the key List is not supported".
 */
internal fun AppsRoute.stateKey(): String = when (this) {
  is AppsRoute.Program -> "program:$programId"
  is AppsRoute.Profile -> "profile:$programId:$profile"
  is AppsRoute.VpsServer -> "vps-server:$serverId"
  is AppsRoute.VpsTerminal -> "vps-terminal:$serverId"
  is AppsRoute.VpsService -> "vps-service:$serverId:$serviceId"
  is AppsRoute.VpsProfile -> "vps-profile:$serverId:$serviceId:$profileId"
  else -> "route:$this"
}

internal fun isProfileProgramType(type: String?): Boolean = type == "profiles" || type == "singbox_profiles" || type == "singbox_lx_profiles" || type == "hysteria2_profiles" || type == "wireproxy_profiles" || type == "myproxy_profiles" || type == "myprogram_profiles" || type == "openvpn_profiles" || type == "tun2socks_profiles" || type == "myvpn_profiles" || type == "mihomo_profiles" || type == "mieru_profiles" || type == "amneziawg_profiles"
