package com.android.zdtd.service.ui

import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.Dp
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

private val SINGBOX_TUN2SOCKS_LOG_LEVELS = listOf("trace", "debug", "info", "warn", "error", "silent")

private fun normalizeSingBoxMode(raw: String?): String {
  return when (raw?.trim()?.lowercase()) {
    SINGBOX_MODE_VPN, "tun2socks", "t2s-vpn", "t2s_vpn" -> SINGBOX_MODE_VPN
    else -> SINGBOX_MODE_T2S
  }
}

private fun isSingBoxIpv4(value: String): Boolean {
  val parts = value.trim().split('.')
  return parts.size == 4 && parts.all { part ->
    part.isNotBlank() && part.length <= 3 && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255
  }
}

private fun normalizeSingBoxDns(values: List<String>): List<String> = values
  .map { it.trim() }
  .filter { it.isNotBlank() && isSingBoxIpv4(it) }
  .distinct()
  .ifEmpty { listOf("8.8.8.8") }

private fun normalizeSingBoxTunName(value: String?): String {
  val cleaned = value
    ?.trim()
    ?.filter { it.isLetterOrDigit() || it == '_' }
    ?.take(15)
    .orEmpty()
  if (cleaned.isBlank()) return "sbtun0"
  val lower = cleaned.lowercase()
  return when (lower) {
    "wlan0", "rmnet0", "eth0", "lo", "tunl0" -> "sbtun0"
    else -> cleaned
  }
}

private fun normalizeSingBoxTun2socksLogLevel(value: String?): String {
  val normalized = value?.trim()?.lowercase().orEmpty()
  return if (normalized in SINGBOX_TUN2SOCKS_LOG_LEVELS) normalized else "info"
}

private data class SingBoxProfileSettingUi(
  val mode: String = SINGBOX_MODE_T2S,
  val t2sPort: Int? = 12345,
  val t2sWebPort: Int? = 8001,
  val tun: String = "sbtun0",
  val dns: List<String> = listOf("8.8.8.8"),
  val tun2socksLogLevel: String = "info",
) {
  val isVpn: Boolean get() = mode == SINGBOX_MODE_VPN
  val isT2s: Boolean get() = mode != SINGBOX_MODE_VPN
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
  val rawDns = buildList<String> {
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
  }

  return SingBoxProfileSettingUi(
    mode = normalizeSingBoxMode(obj?.optString("mode", SINGBOX_MODE_T2S)),
    t2sPort = obj?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 } ?: 12345,
    t2sWebPort = obj?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 } ?: 8001,
    tun = normalizeSingBoxTunName(obj?.optString("tun", "sbtun0")),
    dns = normalizeSingBoxDns(rawDns),
    tun2socksLogLevel = normalizeSingBoxTun2socksLogLevel(obj?.optString("tun2socks_loglevel", "info")),
  )
}

private fun SingBoxProfileSettingUi.toJson(): JSONObject {
  return JSONObject()
    .put("mode", normalizeSingBoxMode(mode))
    .put("t2s_port", t2sPort ?: 12345)
    .put("t2s_web_port", t2sWebPort ?: 8001)
    .put("tun", normalizeSingBoxTunName(tun))
    .put("dns", JSONArray().also { arr -> normalizeSingBoxDns(dns).forEach { arr.put(it) } })
    .put("tun2socks_loglevel", normalizeSingBoxTun2socksLogLevel(tun2socksLogLevel))
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
private fun SingBoxSectionCard(
  title: String,
  desc: String? = null,
  accent: Color = Color(0xFFEF4444),
  icon: @Composable (() -> Unit)? = null,
  trailing: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  content: @Composable (() -> Unit)? = null,
) {
  val compact = rememberIsCompactWidth()
  val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = shape,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              accent.copy(alpha = 0.13f),
              MaterialTheme.colorScheme.surface.copy(alpha = 0.05f),
              Color.Transparent,
            )
          ),
          shape = shape,
        )
        .padding(if (compact) 12.dp else 14.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
        ) {
          if (icon != null) {
            Surface(
              modifier = Modifier.size(if (compact) 42.dp else 46.dp),
              shape = CircleShape,
              color = accent.copy(alpha = 0.16f),
              contentColor = accent,
              border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
            ) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon() }
            }
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
              title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.93f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (desc != null) {
              Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          if (trailing != null) trailing()
        }
        if (content != null) content()
      }
    }
  }
}

@Composable
private fun SingBoxProfileEnabledCard(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val accent = if (checked) Color(0xFF22C55E) else Color(0xFFEF4444)
  SingBoxSectionCard(
    title = stringResource(R.string.enabled_card_profile_title),
    desc = stringResource(R.string.enabled_card_apply_hint),
    accent = accent,
    icon = {
      Icon(
        imageVector = Icons.Filled.Public,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
      )
    },
    trailing = {
      Switch(checked = checked, onCheckedChange = onCheckedChange)
    },
  ) {
    Surface(
      shape = RoundedCornerShape(100.dp),
      color = accent.copy(alpha = 0.16f),
      contentColor = accent,
      border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
    ) {
      Text(
        text = stringResource(if (checked) R.string.enabled_state_on else R.string.enabled_state_off),
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun SingBoxWebPanelCard(
  checking: Boolean,
  onOpen: () -> Unit,
) {
  SingBoxSectionCard(
    title = stringResource(R.string.web_panel_open),
    desc = "127.0.0.1 web UI",
    accent = Color(0xFF38BDF8),
    icon = {
      if (checking) {
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp,
        )
      } else {
        Icon(
          imageVector = Icons.Filled.Public,
          contentDescription = null,
          modifier = Modifier.size(22.dp),
        )
      }
    },
    trailing = {
      Surface(
        modifier = Modifier.clickable(enabled = !checking) { onOpen() },
        shape = RoundedCornerShape(100.dp),
        color = Color(0xFF38BDF8).copy(alpha = 0.16f),
        contentColor = Color(0xFF7DD3FC),
        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.34f)),
      ) {
        Text(
          stringResource(R.string.support_open),
          modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
    },
  )
}


private fun parseDnsText(text: String): List<String> = normalizeSingBoxDns(
  text.split(',', '\n', ';', ' ').map { it.trim() }
)

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

private fun rewriteSingBoxConfigInboundsForVpn(configText: String, setting: SingBoxProfileSettingUi, port: Int): String? {
  // VPN mode is implemented by ZDT-D with a tun2socks helper. sing-box still receives
  // traffic through the same local mixed inbound; do not create native sing-box TUN inbound.
  return rewriteSingBoxConfigInboundsForT2s(configText, port, setting)
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
  val port = serverPort?.takeIf { it in 1..65535 } ?: return null
  return if (setting.isVpn) {
    rewriteSingBoxConfigInboundsForVpn(configText, setting, port)
  } else {
    rewriteSingBoxConfigInboundsForT2s(configText, port, setting)
  }
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
    if (setting?.isVpn != true) {
      add(setting?.t2sPort, singBoxT2sPortLabel(profile))
      add(setting?.t2sWebPort, singBoxT2sWebPortLabel(profile))
    }
  }
  serversByProfile.forEach { (profile, profileServers) ->
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
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val effectiveTopContentPadding = topContentPadding + 12.dp
  val effectiveBottomContentPadding = bottomContentPadding + if (compact) 12.dp else 16.dp
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
      mode = normalizeSingBoxMode(next.mode),
      t2sPort = next.t2sPort ?: 12345,
      t2sWebPort = next.t2sWebPort ?: 8001,
      tun = normalizeSingBoxTunName(next.tun),
      dns = normalizeSingBoxDns(next.dns),
      tun2socksLogLevel = normalizeSingBoxTun2socksLogLevel(next.tun2socksLogLevel),
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
      normalized.isVpn && normalized.tun.isBlank() -> {
        showSnack(context.getString(R.string.singbox_vpn_tun_required))
        onDone?.invoke(false)
        return
      }
      normalized.isVpn && normalized.dns.isEmpty() -> {
        showSnack(context.getString(R.string.singbox_vpn_dns_required))
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
      val serverPort = servers.firstOrNull { it.name == plan.serverName }?.port
        ?: plan.detectedPort.takeIf { it in 1..65535 }
        ?: 1080
      rewriteSingBoxConfigInboundsForMode(plan.originalConfig, activeSetting, serverPort)
    } else {
      when {
        plan.replacementPort != null -> rewriteSingBoxConfigInboundsForMode(plan.originalConfig, activeSetting, plan.replacementPort)
        plan.applyPortToServer -> rewriteSingBoxConfigInboundsForMode(plan.originalConfig, activeSetting, plan.detectedPort)
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
          saveGeneratedToServer(targetServer, resolvedPort, configToSave, updateSetting = true)
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
      .verticalScroll(scroll)
      .padding(horizontal = if (compact) 12.dp else 16.dp)
      .animateContentSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    SingBoxProfileEnabledCard(
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("sing-box", profile, v) },
    )

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

    SingBoxModeSwitchCard(
      setting = activeSetting,
      loading = settingLoading || serversLoading,
      saving = settingSaving,
      serverCount = servers.size,
      onSwitchMode = ::switchSingBoxMode,
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
      visible = activeSetting.isVpn,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      SingBoxVpnProfileCard(
        setting = activeSetting,
        loading = settingLoading || serversLoading,
        saving = settingSaving,
        onSave = { next -> saveProfileSetting(next) },
      )
    }

    AnimatedVisibility(
      visible = activeSetting.isVpn && servers.size > 1,
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
      programs = programs,
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

    Spacer(Modifier.height(effectiveBottomContentPadding))
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

  SingBoxSectionCard(
    title = stringResource(R.string.singbox_profile_settings_title),
    desc = stringResource(R.string.singbox_profile_settings_desc),
    accent = Color(0xFF38BDF8),
    icon = {
      Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(21.dp))
    },
  ) {
    if (loading || saving) {
      LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlinedTextField(
        value = t2sPortText,
        onValueChange = { t2sPortText = it.filter(Char::isDigit) },
        modifier = Modifier.weight(1f),
        enabled = !loading,
        label = { Text(stringResource(R.string.singbox_t2s_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
      OutlinedTextField(
        value = t2sWebPortText,
        onValueChange = { t2sWebPortText = it.filter(Char::isDigit) },
        modifier = Modifier.weight(1f),
        enabled = !loading,
        label = { Text(stringResource(R.string.singbox_t2s_web_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
    }

    val footerText = when {
      parsedT2sPort == null || parsedT2sWebPort == null -> stringResource(R.string.singbox_profile_autosave_hint)
      parsedT2sPort == parsedT2sWebPort -> stringResource(R.string.singbox_profile_ports_must_differ)
      parsedT2sPort !in 1..65535 || parsedT2sWebPort !in 1..65535 -> stringResource(R.string.singbox_profile_autosave_hint)
      saving -> stringResource(R.string.common_loading)
      else -> stringResource(R.string.singbox_profile_autosave_hint)
    }
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
      border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.12f)),
    ) {
      Text(
        footerText,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
      )
    }
  }
}

@Composable
private fun SingBoxModeSwitchCard(
  setting: SingBoxProfileSettingUi,
  loading: Boolean,
  saving: Boolean,
  serverCount: Int,
  onSwitchMode: (String) -> Unit,
) {
  val accent = if (setting.isVpn) Color(0xFF22C55E) else Color(0xFF38BDF8)
  SingBoxSectionCard(
    title = stringResource(R.string.singbox_mode_switch_title),
    desc = stringResource(R.string.singbox_mode_switch_desc),
    accent = accent,
    icon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp)) },
  ) {
    if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SingBoxModeOption(
        modifier = Modifier.weight(1f),
        selected = setting.isT2s,
        enabled = !loading && !saving,
        accent = Color(0xFF38BDF8),
        title = stringResource(R.string.singbox_mode_proxy_t2s),
        desc = stringResource(R.string.singbox_mode_proxy_t2s_desc),
        onClick = { onSwitchMode(SINGBOX_MODE_T2S) },
      )
      SingBoxModeOption(
        modifier = Modifier.weight(1f),
        selected = setting.isVpn,
        enabled = !loading && !saving,
        accent = Color(0xFF22C55E),
        title = stringResource(R.string.singbox_mode_vpn_t2socks),
        desc = stringResource(R.string.singbox_mode_vpn_t2socks_desc),
        onClick = { onSwitchMode(SINGBOX_MODE_VPN) },
      )
    }

    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
      border = BorderStroke(1.dp, accent.copy(alpha = 0.14f)),
    ) {
      Text(
        text = if (setting.isVpn && serverCount != 1) {
          stringResource(R.string.singbox_vpn_single_server_required)
        } else if (setting.isVpn) {
          stringResource(R.string.singbox_vpn_pipeline)
        } else {
          stringResource(R.string.singbox_profile_autosave_hint)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      )
    }
  }
}

@Composable
private fun SingBoxModeOption(
  modifier: Modifier = Modifier,
  selected: Boolean,
  enabled: Boolean,
  accent: Color,
  title: String,
  desc: String,
  onClick: () -> Unit,
) {
  val alpha = if (enabled) 1f else 0.46f
  Surface(
    modifier = modifier.clickable(enabled = enabled && !selected) { onClick() },
    shape = RoundedCornerShape(18.dp),
    color = accent.copy(alpha = if (selected) 0.20f else 0.08f),
    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
    border = BorderStroke(1.dp, accent.copy(alpha = if (selected) 0.48f else 0.18f)),
  ) {
    Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), maxLines = 3, overflow = TextOverflow.Ellipsis)
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
  loading: Boolean,
  saving: Boolean,
  onSave: (SingBoxProfileSettingUi) -> Unit,
) {
  var tunText by remember(setting.tun) { mutableStateOf(setting.tun) }
  var dnsText by remember(setting.dns) { mutableStateOf(setting.dns.joinToString("\n")) }
  var logLevel by remember(setting.tun2socksLogLevel) { mutableStateOf(setting.tun2socksLogLevel) }

  val normalizedTun = remember(tunText) { normalizeSingBoxTunName(tunText) }
  val normalizedDns = remember(dnsText) { parseDnsText(dnsText) }
  val normalizedLogLevel = remember(logLevel) { normalizeSingBoxTun2socksLogLevel(logLevel) }
  val changed = normalizedTun != setting.tun || normalizedDns != setting.dns || normalizedLogLevel != setting.tun2socksLogLevel
  val valid = normalizedTun.isNotBlank() && normalizedDns.isNotEmpty()

  LaunchedEffect(normalizedTun, normalizedDns, normalizedLogLevel, loading, saving, changed, valid) {
    if (loading || saving || !changed || !valid) return@LaunchedEffect
    delay(700)
    onSave(
      setting.copy(
        mode = SINGBOX_MODE_VPN,
        tun = normalizedTun,
        dns = normalizedDns,
        tun2socksLogLevel = normalizedLogLevel,
      )
    )
  }

  SingBoxSectionCard(
    title = stringResource(R.string.singbox_vpn_card_title),
    desc = stringResource(R.string.singbox_vpn_card_desc),
    accent = Color(0xFF22C55E),
    icon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp)) },
  ) {
    if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color(0xFF22C55E).copy(alpha = 0.11f),
      border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.22f)),
    ) {
      Text(
        stringResource(R.string.singbox_vpn_pipeline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
      )
    }

    OutlinedTextField(
      value = tunText,
      onValueChange = { tunText = normalizeSingBoxTunName(it) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !loading,
      singleLine = true,
      label = { Text(stringResource(R.string.singbox_vpn_tun_label)) },
      supportingText = { Text(stringResource(R.string.singbox_vpn_tun_hint)) },
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
      stringResource(R.string.singbox_vpn_log_level_label),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      SINGBOX_TUN2SOCKS_LOG_LEVELS.chunked(3).forEach { rowLevels ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          rowLevels.forEach { level ->
            val selected = normalizedLogLevel == level
            Surface(
              modifier = Modifier.weight(1f).clickable(enabled = !loading && !saving) { logLevel = level },
              shape = RoundedCornerShape(100.dp),
              color = Color(0xFF22C55E).copy(alpha = if (selected) 0.20f else 0.07f),
              contentColor = Color.White.copy(alpha = if (selected) 0.94f else 0.68f),
              border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = if (selected) 0.44f else 0.16f)),
            ) {
              Text(
                level,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
              )
            }
          }
        }
      }
    }

    Text(
      if (saving) stringResource(R.string.common_loading) else stringResource(R.string.singbox_vpn_autosave_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
    )
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
  val createEnabled = setting.isT2s || servers.isEmpty()

  SingBoxSectionCard(
    title = stringResource(R.string.singbox_servers_title),
    desc = stringResource(if (setting.isVpn) R.string.singbox_vpn_servers_desc else R.string.singbox_servers_desc),
    accent = Color(0xFFA78BFA),
    icon = {
      Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp))
    },
    trailing = {
      Surface(
        shape = RoundedCornerShape(100.dp),
        color = Color(0xFFA78BFA).copy(alpha = 0.16f),
        contentColor = Color(0xFFC4B5FD),
        border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.32f)),
      ) {
        Text(
          text = servers.size.toString(),
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
        )
      }
    },
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Surface(
        modifier = Modifier
          .weight(1f)
          .clickable(enabled = createEnabled) { onCreateServer() },
        shape = RoundedCornerShape(100.dp),
        color = Color(0xFFA78BFA).copy(alpha = if (createEnabled) 0.18f else 0.08f),
        contentColor = Color.White.copy(alpha = if (createEnabled) 0.92f else 0.45f),
        border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = if (createEnabled) 0.38f else 0.14f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.singbox_profile_add_server), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      Surface(
        modifier = Modifier
          .weight(1f)
          .clickable(enabled = createEnabled) { onGenerateServer() },
        shape = RoundedCornerShape(100.dp),
        color = Color(0xFFEF4444).copy(alpha = if (createEnabled) 0.16f else 0.07f),
        contentColor = Color.White.copy(alpha = if (createEnabled) 0.92f else 0.45f),
        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = if (createEnabled) 0.34f else 0.13f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Text(stringResource(R.string.singbox_import_action), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }

    if (loading) {
      LinearProgressIndicator(Modifier.fillMaxWidth())
    } else if (servers.isEmpty()) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.16f)),
      ) {
        Text(
          stringResource(R.string.singbox_profile_no_servers),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
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
            showPort = true,
          )
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

  val accent = if (enabled) Color(0xFF22C55E) else Color(0xFFA78BFA)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(accent.copy(alpha = 0.10f), Color.Transparent)
          ),
          RoundedCornerShape(20.dp),
        )
        .padding(12.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
          Surface(
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            color = accent.copy(alpha = 0.15f),
            contentColor = accent,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
          ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(19.dp))
            }
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
              if (saving) stringResource(R.string.common_loading) else stringResource(R.string.singbox_server_autosave_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
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
          Surface(
            modifier = Modifier.weight(1f).clickable { onEditConfig() },
            shape = RoundedCornerShape(100.dp),
            color = Color(0xFF38BDF8).copy(alpha = 0.13f),
            contentColor = Color(0xFF7DD3FC),
            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.28f)),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
              Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.action_edit), fontWeight = FontWeight.Bold, maxLines = 1)
            }
          }
          Surface(
            modifier = Modifier.weight(1f).clickable { askDelete = true },
            shape = RoundedCornerShape(100.dp),
            color = Color(0xFFEF4444).copy(alpha = 0.13f),
            contentColor = Color(0xFFFCA5A5),
            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.30f)),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
              Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.action_delete), fontWeight = FontWeight.Bold, maxLines = 1)
            }
          }
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

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.92f),
      shape = RoundedCornerShape(28.dp),
      color = Color(0xFF17131E).copy(alpha = 0.98f),
      contentColor = MaterialTheme.colorScheme.onSurface,
      border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.34f)),
    ) {
      Box(
        modifier = Modifier
          .background(
            Brush.linearGradient(
              listOf(Color(0xFFA78BFA).copy(alpha = 0.18f), Color(0xFFEF4444).copy(alpha = 0.08f), Color.Transparent)
            ),
            RoundedCornerShape(28.dp),
          )
          .padding(18.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
              modifier = Modifier.size(44.dp),
              shape = CircleShape,
              color = Color(0xFFA78BFA).copy(alpha = 0.18f),
              contentColor = Color(0xFFC4B5FD),
              border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.36f)),
            ) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(23.dp))
              }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(
                stringResource(R.string.singbox_create_server_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                stringResource(R.string.singbox_create_server_rules),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          OutlinedTextField(
            value = name,
            onValueChange = {
              raw = it
              error = null
            },
            modifier = Modifier.fillMaxWidth(),
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
          error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          }

          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.action_cancel))
            }
            Button(
              modifier = Modifier.weight(1f),
              enabled = name.isNotBlank(),
              onClick = {
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
              },
            ) {
              Text(stringResource(R.string.action_create))
            }
          }
        }
      }
    }
  }
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

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.94f),
      shape = RoundedCornerShape(28.dp),
      color = Color(0xFF17131E).copy(alpha = 0.98f),
      contentColor = MaterialTheme.colorScheme.onSurface,
      border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.34f)),
    ) {
      Box(
        modifier = Modifier
          .background(
            Brush.linearGradient(
              listOf(Color(0xFFEF4444).copy(alpha = 0.16f), Color(0xFFA78BFA).copy(alpha = 0.08f), Color.Transparent)
            ),
            RoundedCornerShape(28.dp),
          )
          .padding(18.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
              modifier = Modifier.size(44.dp),
              shape = CircleShape,
              color = Color(0xFFEF4444).copy(alpha = 0.18f),
              contentColor = Color(0xFFFCA5A5),
              border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.36f)),
            ) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(23.dp))
              }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(
                stringResource(R.string.singbox_import_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                stringResource(R.string.singbox_import_dialog_beta_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.14f)),
          ) {
            Text(
              stringResource(R.string.singbox_import_dialog_desc),
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            )
          }

          OutlinedTextField(
            value = name,
            onValueChange = {
              if (lockedServerName == null) {
                raw = it
                error = null
              }
            },
            enabled = lockedServerName == null,
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            singleLine = false,
            supportingText = { Text(stringResource(R.string.singbox_import_source_support_hint)) },
          )

          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.action_cancel))
            }
            Button(
              modifier = Modifier.weight(1f),
              onClick = {
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
              },
            ) {
              Text(stringResource(R.string.singbox_import_action))
            }
          }
        }
      }
    }
  }
}
