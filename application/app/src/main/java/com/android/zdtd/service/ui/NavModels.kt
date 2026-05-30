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
  data object DpiDetector : AppsRoute()
  data object NfqwsTester : AppsRoute()
  data class Program(val programId: String) : AppsRoute()
  data class Profile(val programId: String, val profile: String) : AppsRoute()
}


internal fun isProfileProgramType(type: String?): Boolean = type in setOf(
  "profiles",
  "singbox_profiles",
  "wireproxy_profiles",
  "myproxy_profiles",
  "myprogram_profiles",
  "openvpn_profiles",
  "tun2socks_profiles",
  "myvpn_profiles",
  "mihomo_profiles",
  "mieru_profiles",
  "amneziawg_profiles",
)
