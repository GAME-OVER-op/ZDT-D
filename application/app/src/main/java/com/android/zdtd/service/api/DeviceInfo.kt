package com.android.zdtd.service.api

data class DeviceInfo(
  val cpuName: String = "Unknown CPU",
  val totalRamMb: Long? = null,
)
