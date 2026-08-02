package com.android.zdtd.service.vps

import java.util.UUID

enum class VpsReachability { UNKNOWN, CHECKING, ONLINE, OFFLINE }

enum class VpsServiceKind(val wireId: String) {
  DNSCRYPT("dnscrypt"),
  OPENVPN("openvpn"),
  XRAY("xray"),
  HYSTERIA2("hysteria2"),
  WIREPROXY("wireproxy");

  companion object {
    fun fromWireId(value: String): VpsServiceKind? = entries.firstOrNull { it.wireId == value }
  }
}

data class VpsServer(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val host: String,
  val port: Int = 22,
  val username: String,
  val password: String,
  val pinnedHostKey: String,
  val fingerprint: String,
  val createdAt: Long = System.currentTimeMillis(),
  val lastSuccessfulCheck: Long = 0L,
)

data class VpsMetrics(
  val reachability: VpsReachability = VpsReachability.UNKNOWN,
  val osName: String = "",
  val osId: String = "",
  val osVersion: String = "",
  val architecture: String = "",
  val cpuModel: String = "",
  val cpuCores: Int = 0,
  val cpuPercent: Double = 0.0,
  val ramUsedBytes: Long = 0L,
  val ramTotalBytes: Long = 0L,
  val diskUsedBytes: Long = 0L,
  val diskTotalBytes: Long = 0L,
  val uptimeSeconds: Long = 0L,
  val networkInterface: String = "",
  val networkRxBytes: Long = 0L,
  val networkTxBytes: Long = 0L,
  val refreshing: Boolean = false,
  val lastUpdatedAt: Long = 0L,
  val error: String? = null,
) {
  val supportedPlatform: Boolean
    get() = architecture in setOf("x86_64", "amd64") &&
      ((osId == "ubuntu" && osVersion in setOf("20.04", "22.04", "24.04")) ||
        (osId == "debian" && osVersion.substringBefore('.').toIntOrNull() in 11..13))
}

data class VpsServiceState(
  val kind: VpsServiceKind,
  val installed: Boolean,
  val active: Boolean,
  val version: String = "",
  val warning: String = "",
)

data class VpsServiceProfile(
  val id: String,
  val name: String,
  val kind: VpsServiceKind,
  val mode: String,
  val protocol: String,
  val port: Int,
  val host: String = "",
  val domain: String = "",
  val clientCount: Int = 0,
  val active: Boolean = false,
)

data class VpsClientConfig(
  val id: String,
  val name: String,
  val profileId: String,
  val kind: VpsServiceKind,
  val createdAt: Long = 0L,
  val active: Boolean = true,
)

data class VpsConfigResult(
  val kind: VpsServiceKind,
  val profileId: String,
  val clientId: String,
  val clientName: String,
  val fileName: String,
  val mimeType: String,
  val content: String,
  val shareLink: String? = null,
)

data class VpsProbeResult(
  val hostKeyBase64: String,
  val fingerprint: String,
  val metrics: VpsMetrics,
  val privilege: String,
)

data class VpsCommandResult(
  val exitCode: Int,
  val output: String,
) {
  val successful: Boolean get() = exitCode == 0
}

data class VpsLoadState(
  val loaded: Boolean = false,
  val loading: Boolean = false,
  val error: String? = null,
)

data class VpsOperationState(
  val running: Boolean = false,
  val title: String = "",
  val stage: String = "",
  val log: List<String> = emptyList(),
  val error: String? = null,
  val rolledBack: Boolean = false,
)
