package com.android.zdtd.service.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.LocalWebPanelActivity
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

private const val SINGBOX_MODE_T2S = "t2s"
private const val SINGBOX_MODE_VPN = "vpn"
private const val SINGBOX_VPN_DEFAULT_TUN_ADDRESS = "172.31.240.1/30"

private data class SingBoxProfileSettingUi(
  val mode: String = SINGBOX_MODE_T2S,
  val t2sPort: Int? = 12345,
  val t2sWebPort: Int? = 8001,
  val tun: String = "sbtun0",
  val dns: List<String> = listOf("8.8.8.8"),
) {
  val isVpn: Boolean get() = false
  val isT2s: Boolean get() = true
}

private fun defaultSingBoxProfileSettingUi(): SingBoxProfileSettingUi = SingBoxProfileSettingUi()

private data class SingBoxServerUi(
  val name: String,
  val enabled: Boolean,
  val port: Int?,
)

private data class ServerConfigPortPlan(
  val serverName: String,
  val originalConfig: String,
  val detectedPort: Int,
  val applyPortToServer: Boolean,
  val conflictServerName: String? = null,
  val replacementPort: Int? = null,
)

private data class SingBoxPortRegistry(
  val labelsByPort: Map<Int, List<String>> = emptyMap(),
)

private fun parseSingBoxProfileSettingUi(obj: JSONObject?): SingBoxProfileSettingUi {
  val dns = buildList<String> {
    val arr = obj?.optJSONArray("dns")
    if (arr != null) {
      for (i in 0 until arr.length()) {
        val item = arr.optString(i, "").trim()
        if (item.isNotBlank()) add(item)
      }
    } else {
      obj?.optString("dns", "")
        ?.split(',', '\n', ';', ' ')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.let { addAll(it) }
    }
  }.ifEmpty { listOf("8.8.8.8") }

  return SingBoxProfileSettingUi(
    t2sPort = obj?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 } ?: 12345,
    t2sWebPort = obj?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 } ?: 8001,
    tun = obj?.optString("tun", "sbtun0")?.trim()?.ifBlank { "sbtun0" } ?: "sbtun0",
    dns = dns,
  )
}

private fun SingBoxProfileSettingUi.toJson(): JSONObject {
  return JSONObject()
    .put("t2s_port", t2sPort ?: 12345)
    .put("t2s_web_port", t2sWebPort ?: 8001)
}

private fun singBoxWebPanelUrl(port: Int): String = "http://127.0.0.1:$port/"

private fun singBoxWebPanelScope(profile: String): String = "profile/sing-box/${profile.ifBlank { "main" }}"

private suspend fun isSingBoxWebPanelPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
  runCatching {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", port), 850)
    }
    true
  }.getOrDefault(false)
}

@Composable
private fun SingBoxWebPanelCard(
  checking: Boolean,
  onOpen: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = stringResource(R.string.web_panel_open),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      FilledTonalIconButton(
        onClick = onOpen,
        enabled = !checking,
      ) {
        if (checking) {
          CircularProgressIndicator(
            modifier = Modifier.width(20.dp).height(20.dp),
            strokeWidth = 2.dp,
          )
        } else {
          Icon(
            imageVector = Icons.Filled.Public,
            contentDescription = stringResource(R.string.web_panel_open),
          )
        }
      }
    }
  }
}

private fun parseDnsText(text: String): List<String> = text
  .split(',', '\n', ';', ' ')
  .map { it.trim() }
  .filter { it.isNotBlank() }
  .distinct()
  .ifEmpty { listOf("8.8.8.8") }

private fun parseSingBoxServersUi(obj: JSONObject?): List<SingBoxServerUi> {
  val arr = obj?.optJSONArray("servers") ?: JSONArray()
  return buildList {
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val name = item.optString("name", "").trim()
      if (name.isBlank()) continue
      val setting = item.optJSONObject("setting")
      add(
        SingBoxServerUi(
          name = name,
          enabled = setting?.optBoolean("enabled", false) ?: false,
          port = setting?.optInt("port", 0)?.takeIf { it in 1..65535 },
        )
      )
    }
  }.sortedBy { it.name.lowercase() }
}

private fun normalizeSingBoxServerName(input: String): String {
  val sb = StringBuilder(10)
  for (ch in input.lowercase()) {
    if (sb.length >= 10) break
    val c = when {
      ch.isWhitespace() -> '_'
      ch in 'a'..'z' -> ch
      ch in '0'..'9' -> ch
      ch == '_' || ch == '-' -> ch
      else -> null
    }
    if (c != null) sb.append(c)
  }
  return sb.toString()
}

private fun findSingBoxProxyInboundPort(configText: String): Int? {
  val root = runCatching { JSONObject(configText) }.getOrNull() ?: return null
  val inbounds = root.optJSONArray("inbounds") ?: return null

  fun isProxyInbound(obj: JSONObject): Boolean {
    val type = obj.optString("type").trim().lowercase()
    return type == "mixed" || type == "socks"
  }

  fun extractPort(obj: JSONObject?): Int? = obj?.optInt("listen_port", 0)?.takeIf { it in 1..65535 }

  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (isProxyInbound(inbound) && inbound.optString("tag") in listOf("mixed-in", "socks-in")) {
      return extractPort(inbound)
    }
  }
  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (isProxyInbound(inbound)) return extractPort(inbound)
  }
  return null
}

private fun rewriteSingBoxConfigInboundsForT2s(configText: String, port: Int, setting: SingBoxProfileSettingUi): String? {
  if (port !in 1..65535) return null
  val root = runCatching { JSONObject(configText) }.getOrNull() ?: return null
  val inbound = JSONObject()
    .put("type", "mixed")
    .put("tag", "mixed-in")
    .put("listen", "127.0.0.1")
    .put("listen_port", port)
  root.put("inbounds", JSONArray().put(inbound))
  normalizeSingBoxModernDnsRouteAndDial(root, setting.dns)
  return root.toString(2)
}

private fun extractSingBoxTunFromConfig(configText: String, fallback: String): String {
  val root = runCatching { JSONObject(configText) }.getOrNull() ?: return fallback.trim().ifBlank { "sbtun0" }
  val inbounds = root.optJSONArray("inbounds") ?: return fallback.trim().ifBlank { "sbtun0" }
  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (!inbound.optString("type", "").equals("tun", ignoreCase = true)) continue
    val name = inbound.optString("interface_name", "").trim()
    if (name.isNotBlank()) return name.take(15)
  }
  return fallback.trim().ifBlank { "sbtun0" }
}

private fun rewriteSingBoxConfigInboundsForVpn(configText: String, setting: SingBoxProfileSettingUi): String? {
  val root = runCatching { JSONObject(configText) }.getOrNull() ?: return null
  val effectiveTun = extractSingBoxTunFromConfig(configText, setting.tun)
  val inbound = JSONObject()
    .put("type", "tun")
    .put("tag", "tun-in")
    .put("interface_name", effectiveTun.ifBlank { "sbtun0" })
    .put("address", JSONArray().put(SINGBOX_VPN_DEFAULT_TUN_ADDRESS))
    .put("auto_route", false)
    .put("auto_redirect", false)
    .put("strict_route", false)
    .put("stack", "mixed")
  root.put("inbounds", JSONArray().put(inbound))
  normalizeSingBoxModernDnsRouteAndDial(root, setting.dns)
  return root.toString(2)
}

private fun normalizeSingBoxModernDnsRouteAndDial(root: JSONObject, dns: List<String>) {
  val primaryDns = dns
    .map { it.trim() }
    .firstOrNull { it.matches(Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")) }
    ?: "8.8.8.8"

  val directDomains = linkedSetOf("dns.google")
  root.optJSONObject("dns")
    ?.optJSONArray("rules")
    ?.let { rules ->
      for (i in 0 until rules.length()) {
        val rule = rules.optJSONObject(i) ?: continue
        if (rule.optString("server") != "dns-direct") continue
        collectSingBoxDomains(rule.opt("domain"), directDomains)
      }
    }
  root.optJSONArray("outbounds")?.let { outbounds ->
    for (i in 0 until outbounds.length()) {
      val server = outbounds.optJSONObject(i)?.optString("server", "")?.trim().orEmpty()
      if (server.isNotBlank() && !server.contains(':') && !server.matches(Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$"))) {
        directDomains += server
      }
    }
  }

  val inboundTags = mutableListOf<String>()
  root.optJSONArray("inbounds")?.let { inbounds ->
    for (i in 0 until inbounds.length()) {
      val tag = inbounds.optJSONObject(i)?.optString("tag", "")?.trim().orEmpty()
      if (tag.isNotBlank() && tag !in inboundTags) inboundTags += tag
    }
  }

  val dnsRules = JSONArray()
  dnsRules.put(
    JSONObject()
      .put("domain", JSONArray().also { arr -> directDomains.forEach { arr.put(it) } })
      .put("server", "dns-direct")
  )
  if (inboundTags.isNotEmpty()) {
    dnsRules.put(
      JSONObject()
        .put("inbound", JSONArray().also { arr -> inboundTags.forEach { arr.put(it) } })
        .put("query_type", JSONArray().put("A").put("AAAA"))
        .put("server", "dns-fake")
        .put("disable_cache", true)
    )
  }

  val dnsObj = JSONObject()
    .put(
      "servers",
      JSONArray()
        .put(JSONObject().put("type", "local").put("tag", "dns-local"))
        .put(
          JSONObject()
            .put("type", "udp")
            .put("tag", "dns-direct")
            .put("server", primaryDns)
            .put("server_port", 53)
        )
        .put(
          JSONObject()
            .put("type", "https")
            .put("tag", "dns-remote")
            .put("server", "dns.google")
            .put("server_port", 443)
            .put("path", "/dns-query")
            .put(
              "domain_resolver",
              JSONObject()
                .put("server", "dns-direct")
                .put("strategy", "ipv4_only")
            )
        )
        .put(
          JSONObject()
            .put("type", "fakeip")
            .put("tag", "dns-fake")
            .put("inet4_range", "198.18.0.0/15")
            .put("inet6_range", "fc00::/18")
        )
    )
    .put("rules", dnsRules)
    .put("final", "dns-remote")
    .put("strategy", "ipv4_only")
  root.put("dns", dnsObj)

  val route = root.optJSONObject("route") ?: JSONObject()
  route.put("auto_detect_interface", true)
  route.put(
    "default_domain_resolver",
    JSONObject()
      .put("server", "dns-direct")
      .put("strategy", "ipv4_only")
  )
  val routeRules = JSONArray()
  inboundTags.forEach { tag ->
    routeRules.put(JSONObject().put("inbound", JSONArray().put(tag)).put("action", "sniff"))
  }
  routeRules
    .put(JSONObject().put("action", "hijack-dns").put("port", JSONArray().put(53)))
    .put(JSONObject().put("action", "hijack-dns").put("protocol", JSONArray().put("dns")))
    .put(
      JSONObject()
        .put("action", "reject")
        .put("ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
        .put("source_ip_cidr", JSONArray().put("224.0.0.0/3").put("ff00::/8"))
    )
  route.put("rules", routeRules)
  if (!route.has("rule_set")) route.put("rule_set", JSONArray())
  if (!route.has("final") && hasSingBoxOutboundTag(root, "proxy")) route.put("final", "proxy")
  root.put("route", route)
  migrateSingBoxLegacyDomainStrategy(root)
}

private fun collectSingBoxDomains(value: Any?, out: MutableSet<String>) {
  when (value) {
    is String -> value.trim().takeIf { it.isNotBlank() }?.let { out += it }
    is JSONArray -> for (i in 0 until value.length()) collectSingBoxDomains(value.opt(i), out)
  }
}

private fun hasSingBoxOutboundTag(root: JSONObject, tag: String): Boolean {
  val outbounds = root.optJSONArray("outbounds") ?: return false
  for (i in 0 until outbounds.length()) {
    if (outbounds.optJSONObject(i)?.optString("tag") == tag) return true
  }
  return false
}

private fun migrateSingBoxLegacyDomainStrategy(value: Any?) {
  when (value) {
    is JSONObject -> {
      val rawStrategy = when (val v = value.opt("domain_strategy")) {
        is String -> v.trim()
        null -> ""
        else -> v.toString().trim()
      }
      value.remove("domain_strategy")
      if (rawStrategy.isNotBlank() && looksLikeSingBoxDialObject(value) && !value.has("domain_resolver")) {
        value.put(
          "domain_resolver",
          JSONObject()
            .put("server", "dns-direct")
            .put("strategy", normalizeSingBoxDomainStrategy(rawStrategy))
        )
      }
      val keys = value.keys().asSequence().toList()
      keys.forEach { key -> migrateSingBoxLegacyDomainStrategy(value.opt(key)) }
    }
    is JSONArray -> {
      for (i in 0 until value.length()) migrateSingBoxLegacyDomainStrategy(value.opt(i))
    }
  }
}

private fun looksLikeSingBoxDialObject(obj: JSONObject): Boolean {
  if (obj.has("detour") || obj.has("bind_interface") || obj.has("routing_mark")) return true
  val type = obj.optString("type", "").trim().lowercase()
  val knownOutbound = type in setOf(
    "direct", "socks", "http", "shadowsocks", "vmess", "vless", "trojan",
    "hysteria", "hysteria2", "tuic", "wireguard", "ssh", "shadowtls", "anytls", "tor"
  )
  return knownOutbound || (obj.has("server") && (obj.has("server_port") || obj.has("port")))
}

private fun normalizeSingBoxDomainStrategy(strategy: String): String {
  return when (strategy.trim().lowercase()) {
    "prefer_ipv6" -> "prefer_ipv6"
    "ipv4_only" -> "ipv4_only"
    "ipv6_only" -> "ipv6_only"
    else -> "prefer_ipv4"
  }
}

private fun rewriteSingBoxConfigInboundsForMode(
  configText: String,
  setting: SingBoxProfileSettingUi,
  serverPort: Int?,
): String? {
  val port = serverPort ?: return null
  return rewriteSingBoxConfigInboundsForT2s(configText, port, setting)
}


private fun singBoxServerPortLabel(profile: String, server: String): String = "$profile / $server"

private fun singBoxT2sPortLabel(profile: String): String = "$profile / t2s"

private fun singBoxT2sWebPortLabel(profile: String): String = "$profile / t2s web"

private fun buildSingBoxPortRegistry(
  settingsByProfile: Map<String, SingBoxProfileSettingUi?>,
  serversByProfile: Map<String, List<SingBoxServerUi>>,
): SingBoxPortRegistry {
  val labels = linkedMapOf<Int, MutableList<String>>()

  fun add(port: Int?, label: String) {
    val safePort = port ?: return
    if (safePort !in 1..65535) return
    labels.getOrPut(safePort) { mutableListOf() }.add(label)
  }

  settingsByProfile.forEach { (profile, setting) ->
    if (setting?.isVpn == true) return@forEach
    add(setting?.t2sPort, singBoxT2sPortLabel(profile))
    add(setting?.t2sWebPort, singBoxT2sWebPortLabel(profile))
  }
  serversByProfile.forEach { (profile, profileServers) ->
    if (settingsByProfile[profile]?.isVpn == true) return@forEach
    profileServers.forEach { server ->
      add(server.port, singBoxServerPortLabel(profile, server.name))
    }
  }

  return SingBoxPortRegistry(labelsByPort = labels.mapValues { it.value.toList() })
}

private fun findSingBoxPortConflictLabel(
  registry: SingBoxPortRegistry,
  port: Int,
  ignoredLabel: String? = null,
): String? {
  if (port !in 1..65535) return null
  return registry.labelsByPort[port]
    .orEmpty()
    .firstOrNull { it != ignoredLabel }
}

private fun findNextAvailableSingBoxPort(
  registry: SingBoxPortRegistry,
  preferredPort: Int,
  ignoredLabel: String? = null,
): Int {
  fun isFree(port: Int): Boolean {
    if (port !in 1..65535) return false
    return registry.labelsByPort[port].orEmpty().none { it != ignoredLabel }
  }

  if (isFree(preferredPort)) return preferredPort
  var port = preferredPort.coerceIn(1, 65535)
  while (port < 65535 && !isFree(port)) port += 1
  if (isFree(port)) return port
  port = 1
  while (port < 65535 && !isFree(port)) port += 1
  return port.coerceIn(1, 65535)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingBoxProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val program = programs.firstOrNull { it.id == "sing-box" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val encodedProfile = remember(profile) { URLEncoder.encode(profile, "UTF-8") }
  val basePath = remember(encodedProfile) { "/api/programs/sing-box/profiles/$encodedProfile" }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  var setting by remember(profile) { mutableStateOf<SingBoxProfileSettingUi?>(null) }
  var settingLoading by remember(profile) { mutableStateOf(false) }
  var settingSaving by remember(profile) { mutableStateOf(false) }
  var servers by remember(profile) { mutableStateOf<List<SingBoxServerUi>>(emptyList()) }
  var serversLoading by remember(profile) { mutableStateOf(false) }
  var globalPortRegistry by remember(profile, program?.profiles) { mutableStateOf(SingBoxPortRegistry()) }
  var globalPortRefreshSeq by remember(profile, program?.profiles) { mutableStateOf(0) }

  var showCreateServer by remember(profile) { mutableStateOf(false) }
  var showImportServer by remember(profile) { mutableStateOf(false) }
  var importTargetServer by remember(profile) { mutableStateOf<String?>(null) }
  var editServer by remember(profile) { mutableStateOf<String?>(null) }
  var editText by remember(profile) { mutableStateOf("") }
  var editLastLoadedText by remember(profile) { mutableStateOf("") }
  var editLoading by remember(profile) { mutableStateOf(false) }
  var pendingConfigPlan by remember(profile) { mutableStateOf<ServerConfigPortPlan?>(null) }
  var singBoxWebPanelChecking by remember(profile) { mutableStateOf(false) }
  var selectedApps by remember(profile) { mutableStateOf(emptySet<String>()) }

  fun refreshApps() {
    actions.loadText("$basePath/apps/user") { content ->
      selectedApps = parsePkgList(content)
    }
  }

  fun refreshSetting() {
    settingLoading = true
    actions.loadJsonData("$basePath/setting") { obj ->
      setting = parseSingBoxProfileSettingUi(obj)
      settingLoading = false
    }
  }

  fun refreshServers() {
    serversLoading = true
    actions.loadJsonData("$basePath/servers") { obj ->
      servers = parseSingBoxServersUi(obj)
      serversLoading = false
    }
  }

  fun refreshGlobalPortRegistry() {
    val profileNames = program?.profiles?.map { it.name }?.distinct().orEmpty()
    val requestSeq = globalPortRefreshSeq + 1
    globalPortRefreshSeq = requestSeq
    if (profileNames.isEmpty()) {
      globalPortRegistry = SingBoxPortRegistry()
      return
    }

    val settingsByProfile = linkedMapOf<String, SingBoxProfileSettingUi?>()
    val serversByProfile = linkedMapOf<String, List<SingBoxServerUi>>()
    var remaining = profileNames.size * 2

    fun finishOne() {
      remaining -= 1
      if (remaining == 0 && globalPortRefreshSeq == requestSeq) {
        globalPortRegistry = buildSingBoxPortRegistry(settingsByProfile, serversByProfile)
      }
    }

    profileNames.forEach { profileName ->
      val encoded = URLEncoder.encode(profileName, "UTF-8")
      val profileBasePath = "/api/programs/sing-box/profiles/$encoded"
      actions.loadJsonData("$profileBasePath/setting") { obj ->
        settingsByProfile[profileName] = parseSingBoxProfileSettingUi(obj)
        finishOne()
      }
      actions.loadJsonData("$profileBasePath/servers") { obj ->
        serversByProfile[profileName] = parseSingBoxServersUi(obj)
        finishOne()
      }
    }
  }

  fun rememberGeneratedServer(serverName: String, port: Int) {
    servers = (servers.filterNot { it.name == serverName } + SingBoxServerUi(name = serverName, enabled = true, port = port))
      .sortedBy { it.name.lowercase() }

    val label = singBoxServerPortLabel(profile, serverName)
    val nextLabels = linkedMapOf<Int, List<String>>()
    globalPortRegistry.labelsByPort.forEach { (existingPort, labels) ->
      if (existingPort == port) {
        nextLabels[existingPort] = (labels.filter { it != label } + label).distinct()
      } else {
        nextLabels[existingPort] = labels
      }
    }
    if (!nextLabels.containsKey(port)) {
      nextLabels[port] = listOf(label)
    }
    globalPortRegistry = SingBoxPortRegistry(labelsByPort = nextLabels)
  }

  fun refreshAll() {
    refreshSetting()
    refreshServers()
    refreshApps()
    refreshGlobalPortRegistry()
  }

  LaunchedEffect(profile, program?.profiles) {
    refreshAll()
  }

  fun openEditor(serverName: String) {
    editServer = serverName
    editLoading = true
    val encodedServer = URLEncoder.encode(serverName, "UTF-8")
    actions.loadText("$basePath/servers/$encodedServer/config") { txt ->
      editText = txt ?: ""
      editLastLoadedText = editText
      editLoading = false
    }
  }

  fun currentProfileSetting(): SingBoxProfileSettingUi = setting ?: defaultSingBoxProfileSettingUi()

  fun saveProfileSetting(next: SingBoxProfileSettingUi, onDone: ((Boolean) -> Unit)? = null) {
    val normalized = next.copy(
      mode = SINGBOX_MODE_T2S,
      t2sPort = next.t2sPort ?: 12345,
      t2sWebPort = next.t2sWebPort ?: 8001,
      dns = next.dns.map { it.trim() }.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("8.8.8.8") },
    )
    when {
      normalized.t2sPort !in 1..65535 -> {
        showSnack(context.getString(R.string.singbox_fill_t2s_port))
        onDone?.invoke(false)
        return
      }
      normalized.t2sWebPort !in 1..65535 -> {
        showSnack(context.getString(R.string.singbox_fill_t2s_web_port))
        onDone?.invoke(false)
        return
      }
      normalized.t2sPort == normalized.t2sWebPort -> {
        showSnack(context.getString(R.string.singbox_profile_ports_must_differ))
        onDone?.invoke(false)
        return
      }
    }

    settingSaving = true
    actions.saveJsonData("$basePath/setting", normalized.toJson()) { ok ->
      settingSaving = false
      if (ok) {
        setting = normalized
        refreshGlobalPortRegistry()
        refreshServers()
      } else {
        showSnack(context.getString(R.string.singbox_auto_save_failed))
      }
      onDone?.invoke(ok)
    }
  }

  fun saveProfileSetting(t2sPortText: String, t2sWebPortText: String) {
    val current = currentProfileSetting()
    saveProfileSetting(
      current.copy(
        mode = SINGBOX_MODE_T2S,
        t2sPort = t2sPortText.trim().toIntOrNull(),
        t2sWebPort = t2sWebPortText.trim().toIntOrNull(),
      )
    )
  }

  fun rewriteCurrentServerConfigForMode(modeSetting: SingBoxProfileSettingUi, onDone: ((Boolean) -> Unit)? = null) {
    val onlyServer = servers.singleOrNull()
    if (onlyServer == null) {
      onDone?.invoke(false)
      return
    }
    val encodedServer = URLEncoder.encode(onlyServer.name, "UTF-8")
    actions.loadText("$basePath/servers/$encodedServer/config") { txt ->
      val source = txt?.takeIf { it.trim().isNotBlank() } ?: JSONObject().toString(2)
      val rewritten = rewriteSingBoxConfigInboundsForMode(source, modeSetting, onlyServer.port ?: 1080)
      if (rewritten == null) {
        onDone?.invoke(false)
        return@loadText
      }
      actions.saveText("$basePath/servers/$encodedServer/config", rewritten) { ok ->
        onDone?.invoke(ok)
      }
    }
  }

  fun switchSingBoxMode(nextMode: String) {
    val current = currentProfileSetting()
    if (nextMode == SINGBOX_MODE_VPN && servers.size != 1) {
      showSnack(context.getString(R.string.singbox_vpn_single_server_required))
      return
    }
    val next = current.copy(mode = if (nextMode == SINGBOX_MODE_VPN) SINGBOX_MODE_VPN else SINGBOX_MODE_T2S)
    saveProfileSetting(next) { ok ->
      if (ok) {
        rewriteCurrentServerConfigForMode(next)
      }
    }
  }

  fun applyConfigPlan(plan: ServerConfigPortPlan) {
    val encodedServer = URLEncoder.encode(plan.serverName, "UTF-8")
    val activeSetting = currentProfileSetting()
    val updatedConfig = if (activeSetting.isVpn) {
      rewriteSingBoxConfigInboundsForVpn(plan.originalConfig, activeSetting)
    } else {
      when {
        plan.replacementPort != null -> rewriteSingBoxConfigInboundsForT2s(plan.originalConfig, plan.replacementPort, activeSetting)
        plan.applyPortToServer -> rewriteSingBoxConfigInboundsForT2s(plan.originalConfig, plan.detectedPort, activeSetting)
        else -> plan.originalConfig
      }
    }
    if (updatedConfig == null) {
      showSnack(context.getString(R.string.singbox_listen_port_update_failed))
      return
    }
    editLoading = true
    actions.saveText("$basePath/servers/$encodedServer/config", updatedConfig) { ok ->
      if (!ok) {
        editLoading = false
        showSnack(context.getString(R.string.save_failed))
        return@saveText
      }
      val current = servers.firstOrNull { it.name == plan.serverName }
      val finalPort = when {
        plan.replacementPort != null -> plan.replacementPort
        plan.applyPortToServer -> plan.detectedPort
        else -> null
      }
      if (current == null || finalPort == null) {
        editLoading = false
        editText = updatedConfig
        editLastLoadedText = updatedConfig
        editServer = null
        showSnack(context.getString(R.string.common_saved))
        refreshServers()
        refreshGlobalPortRegistry()
        return@saveText
      }
      val payload = JSONObject()
        .put("enabled", current.enabled)
        .put("port", finalPort)
      actions.saveJsonData("$basePath/servers/$encodedServer/setting", payload) { settingOk ->
        editLoading = false
        if (settingOk) {
          editText = updatedConfig
          editLastLoadedText = updatedConfig
          editServer = null
          showSnack(context.getString(R.string.common_saved))
          refreshServers()
          refreshGlobalPortRegistry()
        } else {
          showSnack(context.getString(R.string.singbox_config_saved_port_update_failed))
        }
      }
    }
  }

  if (showCreateServer) {
    SingBoxCreateServerDialog(
      existing = servers.map { it.name },
      onDismiss = { showCreateServer = false },
      onCreate = { name ->
        showCreateServer = false
        actions.createSingBoxServer(profile, name) { created ->
          if (created != null) {
            showSnack(context.getString(R.string.singbox_server_created_fmt, created))
            refreshServers()
            refreshGlobalPortRegistry()
          } else {
            showSnack(context.getString(R.string.create_failed))
          }
        }
      },
      snackHost = snackHost,
    )
  }

  if (showImportServer) {
    val activeSetting = currentProfileSetting()
    val targetServer = importTargetServer
    SingBoxImportToProfileDialog(
      existing = servers.map { it.name },
      suggestedPort = findNextAvailableSingBoxPort(globalPortRegistry, 2080),
      lockedServerName = targetServer,
      onDismiss = {
        showImportServer = false
        importTargetServer = null
      },
      onGenerate = generate@ { serverName, sourceText ->
        val preferredPort = findNextAvailableSingBoxPort(globalPortRegistry, 2080)
        val importedResult = runCatching { com.android.zdtd.service.singbox.importer.SingBoxOneLineImporter.import(sourceText, preferredPort) }
        val imported = importedResult.getOrNull()
        if (imported == null) {
          val error = importedResult.exceptionOrNull()
          showSnack(context.getString(R.string.singbox_import_failed_fmt, error?.message ?: context.getString(R.string.singbox_parse_error)))
          return@generate
        }

        fun prepareConfig(serverPort: Int?): Pair<String, Int> {
          var configToSave = imported.configJson
          val detectedPort = findSingBoxProxyInboundPort(configToSave)
          val resolvedPort = when {
            activeSetting.isVpn -> serverPort ?: detectedPort ?: preferredPort
            detectedPort == null -> preferredPort
            findSingBoxPortConflictLabel(globalPortRegistry, detectedPort, ignoredLabel = targetServer?.let { singBoxServerPortLabel(profile, it) }) == null -> detectedPort
            else -> findNextAvailableSingBoxPort(globalPortRegistry, detectedPort, ignoredLabel = targetServer?.let { singBoxServerPortLabel(profile, it) })
          }
          configToSave = rewriteSingBoxConfigInboundsForMode(configToSave, activeSetting, resolvedPort) ?: configToSave
          return configToSave to resolvedPort
        }

        fun saveGeneratedToServer(server: String, port: Int, configText: String, updateSetting: Boolean) {
          val encodedServer = URLEncoder.encode(server, "UTF-8")
          actions.saveText("$basePath/servers/$encodedServer/config", configText) { configOk ->
            if (!configOk) {
              showSnack(context.getString(R.string.save_failed))
              refreshServers()
              return@saveText
            }
            if (!updateSetting) {
              showSnack(context.getString(R.string.common_saved))
              refreshServers()
              refreshGlobalPortRegistry()
              return@saveText
            }
            val current = servers.firstOrNull { it.name == server }
            val payload = JSONObject()
              .put("enabled", current?.enabled ?: true)
              .put("port", port)
            actions.saveJsonData("$basePath/servers/$encodedServer/setting", payload) { settingOk ->
              if (settingOk) {
                rememberGeneratedServer(server, port)
                showSnack(context.getString(R.string.singbox_import_created_fmt, server, port))
                scope.launch {
                  delay(250)
                  refreshServers()
                  refreshGlobalPortRegistry()
                }
              } else {
                showSnack(context.getString(R.string.singbox_config_saved_port_update_failed))
                refreshServers()
                refreshGlobalPortRegistry()
              }
            }
          }
        }

        showImportServer = false
        importTargetServer = null
        if (targetServer != null) {
          val current = servers.firstOrNull { it.name == targetServer }
          val (configToSave, resolvedPort) = prepareConfig(current?.port)
          saveGeneratedToServer(targetServer, resolvedPort, configToSave, updateSetting = activeSetting.isT2s)
          return@generate
        }

        if (activeSetting.isVpn && servers.isNotEmpty()) {
          showSnack(context.getString(R.string.singbox_vpn_add_server_blocked))
          return@generate
        }

        val (configToSave, resolvedPort) = prepareConfig(null)
        actions.createSingBoxServer(profile, serverName) { created ->
          if (created == null) {
            showSnack(context.getString(R.string.create_failed))
            return@createSingBoxServer
          }
          saveGeneratedToServer(created, resolvedPort, configToSave, updateSetting = true)
        }
      },
      snackHost = snackHost,
    )
  }

  if (editServer != null) {
    AlertDialog(
      onDismissRequest = { if (!editLoading) editServer = null },
      title = { Text(stringResource(R.string.singbox_editor_title_fmt, editServer ?: "")) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          if (editLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
          }
          OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            singleLine = false,
            label = { Text(stringResource(R.string.singbox_editor_label)) },
          )
        }
      },
      confirmButton = {
        Button(enabled = !editLoading && editText != editLastLoadedText, onClick = {
          val serverName = editServer ?: return@Button
          val parsed = runCatching { JSONObject(editText.trim()) }.getOrElse {
            showSnack(context.getString(R.string.singbox_invalid_json_fmt, it.message ?: context.getString(R.string.singbox_parse_error)))
            return@Button
          }
          val normalizedText = parsed.toString(2)
          val currentServer = servers.firstOrNull { it.name == serverName }
          if (currentProfileSetting().isVpn) {
            applyConfigPlan(ServerConfigPortPlan(serverName = serverName, originalConfig = normalizedText, detectedPort = currentServer?.port ?: 0, applyPortToServer = false))
            return@Button
          }
          val detectedPort = findSingBoxProxyInboundPort(normalizedText)

          if (detectedPort == null || currentServer == null) {
            applyConfigPlan(ServerConfigPortPlan(serverName = serverName, originalConfig = normalizedText, detectedPort = currentServer?.port ?: 0, applyPortToServer = false))
            return@Button
          }

          val currentServerLabel = singBoxServerPortLabel(profile, serverName)
          val conflictLabel = findSingBoxPortConflictLabel(globalPortRegistry, detectedPort, ignoredLabel = currentServerLabel)
          if (currentServer.port == detectedPort && conflictLabel == null) {
            applyConfigPlan(ServerConfigPortPlan(serverName = serverName, originalConfig = normalizedText, detectedPort = detectedPort, applyPortToServer = false))
            return@Button
          }
          pendingConfigPlan = if (conflictLabel == null) {
            ServerConfigPortPlan(
              serverName = serverName,
              originalConfig = normalizedText,
              detectedPort = detectedPort,
              applyPortToServer = true,
            )
          } else {
            ServerConfigPortPlan(
              serverName = serverName,
              originalConfig = normalizedText,
              detectedPort = detectedPort,
              applyPortToServer = true,
              conflictServerName = conflictLabel,
              replacementPort = findNextAvailableSingBoxPort(globalPortRegistry, detectedPort, ignoredLabel = currentServerLabel),
            )
          }
        }) { Text(stringResource(R.string.action_save)) }
      },
      dismissButton = {
        OutlinedButton(enabled = !editLoading, onClick = { editServer = null }) {
          Text(stringResource(R.string.action_cancel))
        }
      }
    )
  }

  pendingConfigPlan?.let { plan ->
    val message = if (plan.conflictServerName == null) {
      context.getString(R.string.singbox_port_sync_found_fmt, plan.detectedPort, plan.serverName)
    } else {
      context.getString(
        R.string.singbox_port_sync_conflict_fmt,
        plan.detectedPort,
        plan.conflictServerName,
        plan.replacementPort ?: plan.detectedPort,
        plan.serverName,
      )
    }
    AlertDialog(
      onDismissRequest = { pendingConfigPlan = null },
      title = { Text(stringResource(R.string.singbox_port_sync_title)) },
      text = { Text(message) },
      confirmButton = {
        Button(onClick = {
          pendingConfigPlan = null
          applyConfigPlan(plan)
        }) {
          Text(
            if (plan.conflictServerName == null) stringResource(R.string.singbox_port_sync_apply)
            else stringResource(R.string.singbox_port_sync_replace_and_apply)
          )
        }
      },
      dismissButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = {
            pendingConfigPlan = null
            applyConfigPlan(plan.copy(applyPortToServer = false, replacementPort = null))
          }) {
            Text(
              if (plan.conflictServerName == null) stringResource(R.string.singbox_port_sync_skip_profile)
              else stringResource(R.string.singbox_port_sync_save_config_only)
            )
          }
          OutlinedButton(onClick = { pendingConfigPlan = null }) {
            Text(stringResource(R.string.action_cancel))
          }
        }
      }
    )
  }

  if (program == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(stringResource(R.string.program_not_found))
    }
    return
  }

  val activeSetting = currentProfileSetting()
  val activeWebPanelPort = activeSetting.t2sWebPort?.takeIf { it in 1..65535 }
  val singBoxPanelUrl = remember(activeWebPanelPort) { activeWebPanelPort?.let { singBoxWebPanelUrl(it) } }
  val singBoxWebPanelVisible = prof?.enabled == true && activeSetting.isT2s && activeWebPanelPort != null && !isOnlyZdtdAppSelected(selectedApps)

  Column(
    Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding()
      .animateContentSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("sing-box / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Text(
      toolDescription("sing-box"),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = stringResource(R.string.enabled_card_profile_title),
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("sing-box", profile, v) },
    )

    AnimatedVisibility(
      visible = activeSetting.isT2s,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      SingBoxProfileSettingCard(
        setting = activeSetting,
        loading = settingLoading,
        saving = settingSaving,
        onSave = ::saveProfileSetting,
      )
    }

    AnimatedVisibility(
      visible = singBoxWebPanelVisible,
      enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
      exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(180)),
    ) {
      SingBoxWebPanelCard(
        checking = singBoxWebPanelChecking,
        onOpen = {
          val port = activeWebPanelPort
          val url = singBoxPanelUrl
          if (port == null || url == null) {
            showSnack(context.getString(R.string.web_panel_unavailable))
          } else if (!singBoxWebPanelChecking) {
            scope.launch {
              singBoxWebPanelChecking = true
              val available = isSingBoxWebPanelPortOpen(port)
              singBoxWebPanelChecking = false
              if (available) {
                context.startActivity(
                  Intent(context, LocalWebPanelActivity::class.java)
                    .putExtra(LocalWebPanelActivity.EXTRA_SCOPE_KEY, singBoxWebPanelScope(profile))
                    .putExtra(LocalWebPanelActivity.EXTRA_DEFAULT_URL, url)
                )
              } else {
                showSnack(context.getString(R.string.web_panel_unavailable))
              }
            }
          }
        },
      )
    }

    AnimatedVisibility(
      visible = false,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      SingBoxModeSwitchCard(
        saving = settingSaving,
        onSwitchToVpn = { switchSingBoxMode(SINGBOX_MODE_VPN) },
      )
    }

    AnimatedVisibility(
      visible = false,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      SingBoxVpnProfileCard(
        setting = activeSetting,
        server = servers.singleOrNull(),
        loading = settingLoading || serversLoading,
        saving = settingSaving,
        onSave = { next -> saveProfileSetting(next) },
        onSwitchToT2s = { switchSingBoxMode(SINGBOX_MODE_T2S) },
        onServerEnabledChange = { enabled ->
          val server = servers.singleOrNull()
          if (server != null) {
            val encodedServer = URLEncoder.encode(server.name, "UTF-8")
            val payload = JSONObject().put("enabled", enabled).put("port", server.port ?: 1080)
            actions.saveJsonData("$basePath/servers/$encodedServer/setting", payload) { ok ->
              if (ok) refreshServers() else showSnack(context.getString(R.string.singbox_auto_save_failed))
            }
          }
        },
        onEditConfig = { servers.singleOrNull()?.name?.let(::openEditor) },
        onGenerateConfig = {
          servers.singleOrNull()?.name?.let { target ->
            importTargetServer = target
            showImportServer = true
          }
        },
      )
    }

    AnimatedVisibility(
      visible = false,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      SingBoxVpnStructureWarningCard(onSwitchToT2s = { switchSingBoxMode(SINGBOX_MODE_T2S) })
    }

    AppListPickerCard(
      title = stringResource(R.string.singbox_apps_title),
      desc = stringResource(R.string.apps_common_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
      onSavedSelection = { selectedApps = it },
    )

    SingBoxServersSection(
      profile = profile,
      basePath = basePath,
      setting = activeSetting,
      servers = servers,
      loading = serversLoading,
      actions = actions,
      snackHost = snackHost,
      onCreateServer = {
        if (activeSetting.isVpn && servers.isNotEmpty()) {
          showSnack(context.getString(R.string.singbox_vpn_add_server_blocked))
        } else {
          showCreateServer = true
        }
      },
      onGenerateServer = {
        if (activeSetting.isVpn && servers.isNotEmpty()) {
          showSnack(context.getString(R.string.singbox_vpn_add_server_blocked))
        } else {
          importTargetServer = null
          showImportServer = true
        }
      },
      onRefresh = { refreshServers() },
      onEditConfig = ::openEditor,
    )

    Spacer(Modifier.height(80.dp))
  }
}

@Composable
private fun SingBoxProfileSettingCard(
  setting: SingBoxProfileSettingUi?,
  loading: Boolean,
  saving: Boolean,
  onSave: (String, String) -> Unit,
) {
  val currentT2sPort = setting?.t2sPort?.toString() ?: "0"
  val currentT2sWebPort = setting?.t2sWebPort?.toString() ?: "0"
  var t2sPortText by remember(setting?.t2sPort) { mutableStateOf(currentT2sPort) }
  var t2sWebPortText by remember(setting?.t2sWebPort) { mutableStateOf(currentT2sWebPort) }

  val parsedT2sPort = t2sPortText.trim().toIntOrNull()
  val parsedT2sWebPort = t2sWebPortText.trim().toIntOrNull()
  val changed = t2sPortText.trim() != currentT2sPort || t2sWebPortText.trim() != currentT2sWebPort
  val portsValid = parsedT2sPort in 1..65535 && parsedT2sWebPort in 1..65535 && parsedT2sPort != parsedT2sWebPort

  LaunchedEffect(t2sPortText, t2sWebPortText, loading, saving, currentT2sPort, currentT2sWebPort) {
    if (loading || saving || !changed || !portsValid) return@LaunchedEffect
    delay(700)
    if (t2sPortText.trim() != currentT2sPort || t2sWebPortText.trim() != currentT2sWebPort) {
      onSave(t2sPortText, t2sWebPortText)
    }
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.singbox_profile_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.singbox_profile_settings_desc),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        style = MaterialTheme.typography.bodySmall,
      )

      if (loading || saving) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }

      OutlinedTextField(
        value = t2sPortText,
        onValueChange = { t2sPortText = it.filter(Char::isDigit) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading,
        label = { Text(stringResource(R.string.singbox_t2s_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
      OutlinedTextField(
        value = t2sWebPortText,
        onValueChange = { t2sWebPortText = it.filter(Char::isDigit) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading,
        label = { Text(stringResource(R.string.singbox_t2s_web_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )

      val footerText = when {
        parsedT2sPort == null || parsedT2sWebPort == null -> stringResource(R.string.singbox_profile_autosave_hint)
        parsedT2sPort == parsedT2sWebPort -> stringResource(R.string.singbox_profile_ports_must_differ)
        parsedT2sPort !in 1..65535 || parsedT2sWebPort !in 1..65535 -> stringResource(R.string.singbox_profile_autosave_hint)
        saving -> stringResource(R.string.common_loading)
        else -> stringResource(R.string.singbox_profile_autosave_hint)
      }
      Text(
        footerText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
      )
    }
  }
}

@Composable
private fun SingBoxModeSwitchCard(
  saving: Boolean,
  onSwitchToVpn: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.singbox_mode_switch_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.singbox_mode_switch_desc),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        style = MaterialTheme.typography.bodySmall,
      )
      Button(enabled = !saving, onClick = onSwitchToVpn, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.singbox_switch_to_vpn))
      }
    }
  }
}

@Composable
private fun SingBoxVpnStructureWarningCard(onSwitchToT2s: () -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.70f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.singbox_vpn_structure_error_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.singbox_vpn_structure_error_body),
        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedButton(onClick = onSwitchToT2s, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.singbox_switch_to_t2s))
      }
    }
  }
}

@Composable
private fun SingBoxVpnProfileCard(
  setting: SingBoxProfileSettingUi,
  server: SingBoxServerUi?,
  loading: Boolean,
  saving: Boolean,
  onSave: (SingBoxProfileSettingUi) -> Unit,
  onSwitchToT2s: () -> Unit,
  onServerEnabledChange: (Boolean) -> Unit,
  onEditConfig: () -> Unit,
  onGenerateConfig: () -> Unit,
) {
  var tunText by remember(setting.tun) { mutableStateOf(setting.tun) }
  var dnsText by remember(setting.dns) { mutableStateOf(setting.dns.joinToString("\n")) }

  val normalizedDns = remember(dnsText) { parseDnsText(dnsText) }
  val changed = tunText.trim() != setting.tun || normalizedDns != setting.dns
  val valid = tunText.trim().isNotBlank() && normalizedDns.isNotEmpty()

  LaunchedEffect(tunText, dnsText, loading, saving, changed, valid) {
    if (loading || saving || !changed || !valid) return@LaunchedEffect
    delay(700)
    onSave(
      setting.copy(
        mode = SINGBOX_MODE_VPN,
        tun = tunText.trim(),
        dns = normalizedDns,
      )
    )
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.singbox_vpn_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            server?.name ?: stringResource(R.string.singbox_profile_no_servers),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
        FilledTonalButton(enabled = !saving, onClick = onSwitchToT2s) {
          Text(stringResource(R.string.singbox_switch_to_t2s_short))
        }
      }

      Text(
        stringResource(R.string.singbox_vpn_card_desc),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        style = MaterialTheme.typography.bodySmall,
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.enabled_card_profile_title), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = server?.enabled ?: false, enabled = server != null && !loading, onCheckedChange = onServerEnabledChange)
      }
      if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())

      OutlinedTextField(
        value = tunText,
        onValueChange = { tunText = it.trim().take(15) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading,
        singleLine = true,
        label = { Text(stringResource(R.string.singbox_vpn_tun_label)) },
      )
      OutlinedTextField(
        value = dnsText,
        onValueChange = { dnsText = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
        enabled = !loading,
        singleLine = false,
        label = { Text(stringResource(R.string.singbox_vpn_dns_label)) },
        supportingText = { Text(stringResource(R.string.singbox_vpn_dns_hint)) },
      )

      Text(
        if (saving) stringResource(R.string.common_loading) else stringResource(R.string.singbox_vpn_autosave_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
      )

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEditConfig, enabled = server != null, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Edit, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_edit))
        }
        OutlinedButton(onClick = onGenerateConfig, enabled = server != null, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.singbox_import_action))
        }
      }
    }
  }
}

@Composable
private fun SingBoxServersSection(
  profile: String,
  basePath: String,
  setting: SingBoxProfileSettingUi,
  servers: List<SingBoxServerUi>,
  loading: Boolean,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  onCreateServer: () -> Unit,
  onGenerateServer: () -> Unit,
  onRefresh: () -> Unit,
  onEditConfig: (String) -> Unit,
) {
  if (setting.isVpn && servers.size == 1) return
  val createEnabled = setting.isT2s || servers.isEmpty()
  ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.singbox_servers_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(if (setting.isVpn) R.string.singbox_vpn_servers_desc else R.string.singbox_servers_desc),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          style = MaterialTheme.typography.bodySmall,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilledTonalButton(onClick = onCreateServer, enabled = createEnabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.singbox_profile_add_server))
          }
          OutlinedButton(onClick = onGenerateServer, enabled = createEnabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.singbox_import_action))
          }
        }
      }

      if (loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      } else if (servers.isEmpty()) {
        Text(
          stringResource(R.string.singbox_profile_no_servers),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          servers.forEach { server ->
            SingBoxServerCard(
              profile = profile,
              basePath = basePath,
              server = server,
              actions = actions,
              snackHost = snackHost,
              onRefresh = onRefresh,
              onEditConfig = { onEditConfig(server.name) },
              showPort = setting.isT2s,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SingBoxServerCard(
  profile: String,
  basePath: String,
  server: SingBoxServerUi,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  onRefresh: () -> Unit,
  onEditConfig: () -> Unit,
  showPort: Boolean = true,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var enabled by remember(server.name, server.enabled) { mutableStateOf(server.enabled) }
  var portText by remember(server.name, server.port) { mutableStateOf((server.port ?: 0).toString()) }
  var saving by remember(server.name) { mutableStateOf(false) }
  var askDelete by remember(server.name) { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun autoSave() {
    val port = if (showPort) portText.trim().toIntOrNull() else (server.port ?: 1080)
    if (port !in 1..65535) return
    saving = true
    val encodedServer = URLEncoder.encode(server.name, "UTF-8")
    val payload = JSONObject().put("enabled", enabled).put("port", port)
    actions.saveJsonData("$basePath/servers/$encodedServer/setting", payload) { ok ->
      saving = false
      if (ok) {
        onRefresh()
      } else {
        showSnack(context.getString(R.string.singbox_auto_save_failed))
      }
    }
  }

  if (askDelete) {
    AlertDialog(
      onDismissRequest = { askDelete = false },
      title = { Text(stringResource(R.string.singbox_delete_server_title)) },
      text = { Text(context.getString(R.string.singbox_delete_server_message_fmt, profile, server.name)) },
      confirmButton = {
        Button(onClick = {
          askDelete = false
          actions.deleteSingBoxServer(profile, server.name) { ok ->
            if (ok) {
              showSnack(context.getString(R.string.deleted))
              onRefresh()
            } else {
              showSnack(context.getString(R.string.delete_failed))
            }
          }
        }) { Text(stringResource(R.string.action_delete)) }
      },
      dismissButton = {
        OutlinedButton(onClick = { askDelete = false }) { Text(stringResource(R.string.action_cancel)) }
      }
    )
  }

  val currentPortText = server.port?.toString() ?: "0"
  val changed = enabled != server.enabled || portText.trim() != currentPortText
  val validPort = !showPort || portText.trim().toIntOrNull() in 1..65535

  LaunchedEffect(enabled, portText, server.enabled, currentPortText, saving, showPort) {
    if (saving || !changed || !validPort) return@LaunchedEffect
    delay(700)
    if (enabled != server.enabled || portText.trim() != currentPortText) {
      autoSave()
    }
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            if (saving) stringResource(R.string.common_loading) else stringResource(R.string.singbox_server_autosave_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
        Switch(checked = enabled, onCheckedChange = { enabled = it })
      }

      AnimatedVisibility(
        visible = showPort,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        OutlinedTextField(
          value = portText,
          onValueChange = { portText = it.filter(Char::isDigit) },
          modifier = Modifier.fillMaxWidth(),
          enabled = !saving,
          singleLine = true,
          label = { Text(stringResource(R.string.singbox_server_port_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEditConfig, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Edit, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_edit))
        }
        OutlinedButton(onClick = { askDelete = true }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Delete, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_delete))
        }
      }
    }
  }
}

@Composable
private fun SingBoxCreateServerDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val existingNorm = remember(existing) { existing.map { normalizeSingBoxServerName(it) }.toSet() }
  var raw by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val name = remember(raw) { normalizeSingBoxServerName(raw) }

  fun snack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.singbox_create_server_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          stringResource(R.string.singbox_create_server_rules),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = name,
          onValueChange = {
            raw = it
            error = null
          },
          label = { Text(stringResource(R.string.singbox_server_name_label)) },
          singleLine = false,
          maxLines = 2,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          supportingText = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(stringResource(R.string.allowed_chars_hint))
              Text(stringResource(R.string.profile_name_len_fmt, name.length))
            }
          },
          isError = error != null,
        )
        if (error != null) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(onClick = {
        val n = name.trim()
        when {
          n.isEmpty() -> {
            val msg = context.getString(R.string.enter_a_name)
            error = msg
            snack(msg)
          }
          existingNorm.contains(n) -> {
            val msg = context.getString(R.string.singbox_server_already_exists)
            error = msg
            snack(msg)
          }
          else -> onCreate(n)
        }
      }, enabled = name.isNotBlank()) {
        Text(stringResource(R.string.action_create))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    }
  )
}

@Composable
private fun SingBoxImportToProfileDialog(
  existing: List<String>,
  suggestedPort: Int,
  lockedServerName: String? = null,
  onDismiss: () -> Unit,
  onGenerate: (String, String) -> Unit,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val existingNorm = remember(existing) { existing.map { normalizeSingBoxServerName(it) }.toSet() }
  var raw by remember { mutableStateOf(lockedServerName.orEmpty()) }
  var source by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val name = remember(raw) { normalizeSingBoxServerName(raw) }

  fun snack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.singbox_import_dialog_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.singbox_import_dialog_beta_warning),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        Text(
          stringResource(R.string.singbox_import_dialog_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        OutlinedTextField(
          value = name,
          onValueChange = {
            if (lockedServerName == null) {
              raw = it
              error = null
            }
          },
          enabled = lockedServerName == null,
          label = { Text(stringResource(R.string.singbox_server_name_label)) },
          singleLine = false,
          maxLines = 2,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          supportingText = {
            Column {
              Text(stringResource(if (lockedServerName == null) R.string.singbox_create_server_rules else R.string.singbox_import_replace_current_hint))
              if (lockedServerName == null) Text(stringResource(R.string.singbox_import_auto_port_hint, suggestedPort))
            }
          },
          isError = error != null,
        )
        if (error != null) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = source,
          onValueChange = { source = it },
          label = { Text(stringResource(R.string.singbox_import_source_label)) },
          modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
          singleLine = false,
          supportingText = { Text(stringResource(R.string.singbox_import_source_support_hint)) },
        )
      }
    },
    confirmButton = {
      Button(onClick = {
        val n = name.trim()
        when {
          n.isEmpty() -> {
            val msg = context.getString(R.string.enter_a_name)
            error = msg
            snack(msg)
          }
          lockedServerName == null && existingNorm.contains(n) -> {
            val msg = context.getString(R.string.singbox_server_already_exists)
            error = msg
            snack(msg)
          }
          source.trim().isEmpty() -> snack(context.getString(R.string.singbox_import_source_required))
          else -> onGenerate(n, source.trim())
        }
      }) {
        Text(stringResource(R.string.singbox_import_action))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    }
  )
}

