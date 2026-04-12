package com.android.zdtd.service.ui

enum class Tab {
  HOME,
  STATS,
  APPS,
  SUPPORT,
}

sealed class AppsRoute {
  data object List : AppsRoute()
  data class Program(val programId: String) : AppsRoute()
  data class Profile(val programId: String, val profile: String) : AppsRoute()
}


internal fun isProfileProgramType(type: String?): Boolean = type == "profiles" || type == "singbox_profiles" || type == "wireproxy_profiles"
