package com.android.zdtd.service.ui

import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

private val vpnTunProgramIds = listOf("openvpn", "tun2socks", "myvpn", "mihomo")

private suspend fun awaitLoadJsonVpnTunGuard(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

internal suspend fun awaitSaveJsonVpnTunGuard(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

internal fun vpnProfileApiPath(programId: String, profile: String): String =
  "/api/programs/${URLEncoder.encode(programId, "UTF-8")}/profiles/${URLEncoder.encode(profile, "UTF-8")}"

private fun vpnSettingObject(obj: JSONObject?): JSONObject? =
  obj?.optJSONObject("data") ?: obj?.optJSONObject("setting") ?: obj

private fun defaultTunForVpnProgram(programId: String): String = when (programId) {
  "openvpn" -> "tun1"
  "tun2socks" -> "tun9"
  "myvpn" -> "tun9"
  "mihomo" -> "tun20"
  else -> "tun1"
}

private fun tunFromSetting(programId: String, obj: JSONObject?): String {
  val data = vpnSettingObject(obj)
  return data?.optString("tun", "")?.trim().orEmpty().ifBlank { defaultTunForVpnProgram(programId) }
}

internal fun isVpnTunNameUsed(tun: String, usedTuns: Set<String>): Boolean {
  val value = tun.trim().lowercase(Locale.ROOT)
  return value.isNotEmpty() && value in usedTuns
}

internal suspend fun loadUsedVpnTunNames(
  actions: ZdtdActions,
  programs: List<ApiModels.Program>,
  excludeProgramId: String? = null,
  excludeProfile: String? = null,
): Set<String> {
  val used = linkedSetOf<String>()
  for (programId in vpnTunProgramIds) {
    val program = programs.firstOrNull { it.id == programId } ?: continue
    for (profile in program.profiles) {
      if (programId == excludeProgramId && profile.name == excludeProfile) continue
      val raw = awaitLoadJsonVpnTunGuard(actions, "${vpnProfileApiPath(programId, profile.name)}/setting")
      val tun = tunFromSetting(programId, raw)
      if (tun.isNotBlank()) used += tun.lowercase(Locale.ROOT)
    }
  }
  return used
}

internal fun nextFreeVpnTunName(usedTuns: Set<String>, startAt: Int = 1): String {
  val used = usedTuns.map { it.lowercase(Locale.ROOT) }.toSet()
  for (i in startAt.coerceAtLeast(1)..999) {
    val candidate = "tun$i"
    if (candidate.lowercase(Locale.ROOT) !in used) return candidate
  }
  return "tun${System.currentTimeMillis() % 10000}"
}

internal suspend fun nextFreeVpnTunName(
  actions: ZdtdActions,
  programs: List<ApiModels.Program>,
  excludeProgramId: String? = null,
  excludeProfile: String? = null,
): String = nextFreeVpnTunName(
  loadUsedVpnTunNames(actions, programs, excludeProgramId, excludeProfile),
)

internal fun defaultOpenVpnSettingJson(tun: String): JSONObject {
  val dns = JSONArray().put("94.140.14.14").put("94.140.15.15")
  return JSONObject()
    .put("tun", tun.trim())
    .put("dns", dns)
}

internal fun defaultTun2SocksSettingJson(tun: String): JSONObject = JSONObject()
  .put("tun", tun.trim())
  .put("proxy", "socks5://127.0.0.1:1080")
  .put("loglevel", "info")
  .put("udp_timeout", JSONObject.NULL)
  .put("fwmark", JSONObject.NULL)
  .put("restapi", JSONObject.NULL)

internal fun defaultMyVpnSettingJson(tun: String): JSONObject {
  val dns = JSONArray().put("1.1.1.1").put("1.0.0.1")
  return JSONObject()
    .put("tun", tun.trim())
    .put("dns", dns)
    .put("cidr_mode", "auto")
    .put("cidr", "")
}

internal fun defaultMihomoSettingJson(tun: String, mixedPort: Int = 17890): JSONObject = JSONObject()
  .put("tun", tun.trim())
  .put("mixed_port", mixedPort)
  .put("log_level", "info")
  .put("tun2socks_loglevel", "info")
