package com.android.zdtd.service.ui

import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

private val vpnIpv4ProgramIds = listOf("amneziawg", "myvpn", "mihomo")

internal data class VpnIpv4Cidr(
  val value: String,
  val ip: Long,
  val prefix: Int,
) {
  val mask: Long = if (prefix == 0) 0L else ((0xffffffffL shl (32 - prefix)) and 0xffffffffL)
  val start: Long = ip and mask
  val end: Long = start or (0xffffffffL xor mask)

  fun overlaps(other: VpnIpv4Cidr): Boolean = start <= other.end && other.start <= end
}

internal data class VpnIpv4Use(
  val programId: String,
  val profile: String,
  val cidr: VpnIpv4Cidr,
)

private suspend fun awaitLoadJsonVpnIpv4Guard(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextVpnIpv4Guard(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private fun vpnIpv4SettingObject(obj: JSONObject?): JSONObject? =
  obj?.optJSONObject("data") ?: obj?.optJSONObject("setting") ?: obj

private fun parseVpnIpv4ToLong(value: String): Long? {
  val parts = value.trim().split('.')
  if (parts.size != 4) return null
  var result = 0L
  for (part in parts) {
    if (part.isBlank() || part.length > 3 || !part.all(Char::isDigit)) return null
    val number = part.toIntOrNull() ?: return null
    if (number !in 0..255) return null
    result = (result shl 8) or number.toLong()
  }
  return result and 0xffffffffL
}

internal fun parseVpnIpv4CidrLiteral(value: String): VpnIpv4Cidr? {
  val cleaned = value.trim().trim('"', '\'')
  if (cleaned.isBlank() || cleaned.contains(":") || cleaned.contains("://")) return null
  val parts = cleaned.split('/')
  if (parts.size !in 1..2) return null
  val ipText = parts[0].trim()
  val ip = parseVpnIpv4ToLong(ipText) ?: return null
  val prefix = if (parts.size == 2) parts[1].trim().toIntOrNull() ?: return null else 32
  if (prefix !in 0..32) return null
  return VpnIpv4Cidr("$ipText/$prefix", ip, prefix)
}

internal fun vpnIpv4CidrsOverlapEachOther(values: List<String>): Boolean {
  val cidrs = values.mapNotNull { parseVpnIpv4CidrLiteral(it) }
  for (i in cidrs.indices) {
    for (j in i + 1 until cidrs.size) {
      if (cidrs[i].overlaps(cidrs[j])) return true
    }
  }
  return false
}

internal fun vpnIpv4CidrsConflict(values: List<String>, used: List<VpnIpv4Use>): Boolean {
  val cidrs = values.mapNotNull { parseVpnIpv4CidrLiteral(it) }
  return cidrs.any { candidate -> used.any { candidate.overlaps(it.cidr) } }
}

internal fun vpnIpv4CidrConflict(value: String, used: List<VpnIpv4Use>): Boolean =
  parseVpnIpv4CidrLiteral(value)?.let { candidate -> used.any { candidate.overlaps(it.cidr) } } == true

private fun amneziaWgIpv4Uses(programId: String, profile: String, raw: JSONObject?): List<VpnIpv4Use> {
  val data = vpnIpv4SettingObject(raw)
  val arr = data?.optJSONArray("address") ?: return emptyList()
  return buildList {
    for (i in 0 until arr.length()) {
      val cidr = parseVpnIpv4CidrLiteral(arr.optString(i, "")) ?: continue
      add(VpnIpv4Use(programId, profile, cidr))
    }
  }
}

private fun myVpnIpv4Uses(programId: String, profile: String, raw: JSONObject?): List<VpnIpv4Use> {
  val data = vpnIpv4SettingObject(raw) ?: return emptyList()
  val mode = data.optString("cidr_mode", "auto").trim().lowercase()
  if (mode != "manual") return emptyList()
  val cidr = parseVpnIpv4CidrLiteral(data.optString("cidr", "")) ?: return emptyList()
  return listOf(VpnIpv4Use(programId, profile, cidr))
}

internal fun extractMihomoExplicitIpv4Cidrs(yaml: String): List<VpnIpv4Cidr> {
  val regex = Regex("""(?m)^\s*ip\s*:\s*[\"']?([0-9]{1,3}(?:\.[0-9]{1,3}){3}(?:/[0-9]{1,2})?)[\"']?\s*(?:#.*)?$""")
  return regex.findAll(yaml).mapNotNull { parseVpnIpv4CidrLiteral(it.groupValues[1]) }.toList()
}

private fun mihomoIpv4Uses(programId: String, profile: String, yaml: String): List<VpnIpv4Use> =
  extractMihomoExplicitIpv4Cidrs(yaml).map { VpnIpv4Use(programId, profile, it) }

internal suspend fun loadUsedVpnIpv4Cidrs(
  actions: ZdtdActions,
  programs: List<ApiModels.Program>,
  excludeProgramId: String? = null,
  excludeProfile: String? = null,
): List<VpnIpv4Use> {
  val used = mutableListOf<VpnIpv4Use>()
  for (programId in vpnIpv4ProgramIds) {
    val program = programs.firstOrNull { it.id == programId } ?: continue
    for (profile in program.profiles) {
      if (programId == excludeProgramId && profile.name == excludeProfile) continue
      when (programId) {
        "amneziawg" -> {
          val raw = awaitLoadJsonVpnIpv4Guard(actions, "${vpnProfileApiPath(programId, profile.name)}/setting")
          used += amneziaWgIpv4Uses(programId, profile.name, raw)
        }
        "myvpn" -> {
          val raw = awaitLoadJsonVpnIpv4Guard(actions, "${vpnProfileApiPath(programId, profile.name)}/setting")
          used += myVpnIpv4Uses(programId, profile.name, raw)
        }
        "mihomo" -> {
          val yaml = awaitLoadTextVpnIpv4Guard(actions, "${vpnProfileApiPath(programId, profile.name)}/config").orEmpty()
          used += mihomoIpv4Uses(programId, profile.name, yaml)
        }
      }
    }
  }
  return used
}

private fun vpnIpv4Host(octet3: Int, octet4: Int, withPrefix: Boolean): String {
  val ip = "172.16.$octet3.$octet4"
  return if (withPrefix) "$ip/32" else ip
}

internal fun nextFreeVpnIpv4Literal(used: List<VpnIpv4Use>, withPrefix: Boolean = true): String {
  val usedCidrs = used.map { it.cidr }
  for (third in 0..255) {
    for (fourth in 2..254) {
      val candidateText = vpnIpv4Host(third, fourth, withPrefix = true)
      val candidate = parseVpnIpv4CidrLiteral(candidateText) ?: continue
      if (usedCidrs.none { candidate.overlaps(it) }) return vpnIpv4Host(third, fourth, withPrefix)
    }
  }
  return vpnIpv4Host(255, 254, withPrefix)
}

internal fun nextFreeVpnIpv4Cidr(used: List<VpnIpv4Use>): String = nextFreeVpnIpv4Literal(used, withPrefix = true)

internal fun rewriteMihomoExplicitIpv4Conflicts(yaml: String, used: List<VpnIpv4Use>): String {
  if (yaml.isBlank()) return yaml
  val regex = Regex("""(?m)^(\s*ip\s*:\s*[\"']?)([0-9]{1,3}(?:\.[0-9]{1,3}){3}(?:/[0-9]{1,2})?)([\"']?\s*(?:#.*)?$)""")
  val localUsed = used.toMutableList()
  return regex.replace(yaml) { match ->
    val currentText = match.groupValues[2]
    val current = parseVpnIpv4CidrLiteral(currentText) ?: return@replace match.value
    val hasConflict = localUsed.any { current.overlaps(it.cidr) }
    if (!hasConflict) {
      localUsed += VpnIpv4Use("mihomo", "current", current)
      match.value
    } else {
      val withPrefix = currentText.contains('/')
      val replacement = nextFreeVpnIpv4Literal(localUsed, withPrefix)
      parseVpnIpv4CidrLiteral(replacement)?.let { localUsed += VpnIpv4Use("mihomo", "current", it) }
      match.groupValues[1] + replacement + match.groupValues[3]
    }
  }
}
