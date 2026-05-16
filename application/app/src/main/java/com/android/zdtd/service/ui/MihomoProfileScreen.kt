package com.android.zdtd.service.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.LocalWebPanelActivity
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlin.coroutines.resume

private data class MihomoProfileInfo(
  val name: String,
  val enabled: Boolean,
)

private data class MihomoSettingUi(
  val tun: String = "tun20",
  val mixedPort: Int = 17890,
  val logLevel: String = "info",
  val tun2socksLogLevel: String = "info",
)

private data class MihomoPendingChange(
  val id: String,
  val title: String,
  val message: String,
  val detail: String,
  val yaml: String,
  val addProxyToDefaultGroup: String? = null,
)


private val mihomoProfileNameRegex = Regex("^[A-Za-z0-9_-]{1,10}$")
private val mihomoTunRegex = Regex("^[A-Za-z0-9_.-]{1,15}$")
private val mihomoForbiddenTunNames = setOf("wlan0", "rmnet_data0", "eth0", "lo", "dummy0")
private val mihomoLogLevels = listOf("debug", "info", "warning", "error", "silent")
private val mihomoTun2SocksLogLevels = listOf("debug", "info", "warn", "error", "silent")
private const val MIHOMO_AUTOSAVE_DELAY_MS = 1500L

private suspend fun awaitLoadJsonMihomo(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextMihomo(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonMihomo(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private suspend fun awaitSaveTextMihomo(actions: ZdtdActions, path: String, content: String): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveText(path, content) { cont.resume(it) } }

private fun mihomoProfilePath(profile: String): String =
  "/api/programs/mihomo/profiles/${URLEncoder.encode(profile, "UTF-8")}" 

private fun mihomoDataObject(obj: JSONObject?): JSONObject? =
  obj?.optJSONObject("data") ?: obj?.optJSONObject("setting") ?: obj

private fun parseMihomoSetting(obj: JSONObject?): MihomoSettingUi {
  val data = mihomoDataObject(obj)
  val log = data?.optString("log_level", "info")?.trim()?.lowercase(Locale.ROOT).orEmpty()
  val t2sLog = data?.optString("tun2socks_loglevel", "info")?.trim()?.lowercase(Locale.ROOT).orEmpty()
  return MihomoSettingUi(
    tun = data?.optString("tun", "tun20")?.trim().orEmpty().ifBlank { "tun20" },
    mixedPort = data?.optInt("mixed_port", 17890)?.takeIf { it in 1..65535 } ?: 17890,
    logLevel = log.takeIf { it in mihomoLogLevels } ?: "info",
    tun2socksLogLevel = t2sLog.takeIf { it in mihomoTun2SocksLogLevels } ?: "info",
  )
}

private fun buildMihomoSettingJson(setting: MihomoSettingUi): JSONObject = JSONObject()
  .put("tun", setting.tun.trim())
  .put("mixed_port", setting.mixedPort)
  .put("log_level", setting.logLevel.trim().lowercase(Locale.ROOT))
  .put("tun2socks_loglevel", setting.tun2socksLogLevel.trim().lowercase(Locale.ROOT))

private fun isValidMihomoTun(value: String): Boolean {
  val v = value.trim()
  return mihomoTunRegex.matches(v) && v.lowercase(Locale.ROOT) !in mihomoForbiddenTunNames
}

private fun isValidMihomoPort(value: String): Boolean = value.trim().toIntOrNull()?.let { it in 1..65535 } == true

private fun mihomoProfileIndex(name: String): Int {
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) {
    return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  }
  return Int.MIN_VALUE
}

private suspend fun loadUsedMihomoMixedPorts(
  actions: ZdtdActions,
  programs: List<ApiModels.Program>,
  excludeProfile: String? = null,
): Set<Int> {
  val program = programs.firstOrNull { it.id == "mihomo" } ?: return emptySet()
  val used = linkedSetOf<Int>()
  for (profile in program.profiles) {
    if (profile.name == excludeProfile) continue
    val raw = awaitLoadJsonMihomo(actions, "${vpnProfileApiPath("mihomo", profile.name)}/setting")
    val port = parseMihomoSetting(raw).mixedPort
    if (port in 1..65535) used += port
  }
  return used
}

private fun nextFreeMihomoMixedPort(used: Set<Int>, preferred: Int = 17890): Int {
  if (preferred !in used) return preferred
  for (port in preferred + 1..65535) if (port !in used) return port
  for (port in 1024 until preferred) if (port !in used) return port
  return preferred
}

private fun parseMihomoControllerPort(value: String): Int? {
  val v = value.trim().trim('"')
  if (v.isBlank()) return null
  val portText = when {
    v.count { it == ':' } == 1 -> v.substringAfterLast(':')
    v.startsWith("[") && v.contains("]:") -> v.substringAfterLast(':')
    else -> v.substringAfterLast(':', missingDelimiterValue = "")
  }
  return portText.toIntOrNull()?.takeIf { it in 1..65535 }
}

private fun extractMihomoControllerPort(yaml: String): Int? =
  parseMihomoControllerPort(extractTopLevelScalar(yaml, "external-controller"))

private suspend fun loadUsedMihomoControllerPorts(
  actions: ZdtdActions,
  programs: List<ApiModels.Program>,
  excludeProfile: String? = null,
): Set<Int> {
  val program = programs.firstOrNull { it.id == "mihomo" } ?: return emptySet()
  val used = linkedSetOf<Int>()
  for (profile in program.profiles) {
    if (profile.name == excludeProfile) continue
    val raw = awaitLoadTextMihomo(actions, "${vpnProfileApiPath("mihomo", profile.name)}/config").orEmpty()
    val port = extractMihomoControllerPort(raw)
    if (port != null) used += port
  }
  return used
}

private fun nextFreeMihomoControllerPort(used: Set<Int>, preferred: Int = 19090): Int {
  if (preferred !in used) return preferred
  for (port in preferred + 1..65535) if (port !in used) return port
  for (port in 1024 until preferred) if (port !in used) return port
  return preferred
}

private fun mihomoWebPanelUrl(port: Int): String = "http://127.0.0.1:$port/ui/"

private fun mihomoWebPanelScope(profile: String): String = "profile/mihomo/${profile.ifBlank { "main" }}"

private suspend fun isLocalWebPanelPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
  runCatching {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", port), 850)
    }
    true
  }.getOrDefault(false)
}

@Composable
private fun MihomoWebPanelCard(
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

private fun mihomoDefaultController(port: Int = 19090): String = "127.0.0.1:$port"

private fun mihomoSampleConfig(profile: String, controllerPort: Int = 19090): String {
  val safeProfile = profile.ifBlank { "main" }
  return """
# =========================
# External controller / UI
# =========================
external-controller: ${mihomoDefaultController(controllerPort)}
secret: ""

external-ui: "/data/adb/modules/ZDT-D/working_folder/mihomo/profile/$safeProfile/work/ui"
external-ui-url: "https://github.com/MetaCubeX/metacubexd/archive/refs/heads/gh-pages.zip"

# =========================
# General
# =========================
mode: rule
ipv6: false

# =========================
# DNS
# =========================
dns:
  enable: true
  listen: 127.0.0.1:1053
  enhanced-mode: fake-ip
  nameserver:
    - 1.1.1.1
    - 8.8.8.8

# =========================
# Proxies
# =========================
proxies:
  - name: "DIRECT-OUT"
    type: direct
    udp: true
    ip-version: ipv4

# =========================
# Proxy Groups
# =========================
proxy-groups:
  - name: "Proxy"
    type: select
    proxies:
      - DIRECT-OUT

# =========================
# Rules
# =========================
rules:
  - MATCH,Proxy
""".trimIndent() + "\n"
}

private fun extractTopLevelYamlSection(yaml: String, section: String): String {
  val lines = yaml.replace("\r\n", "\n").split("\n")
  val start = lines.indexOfFirst { it.trim() == "$section:" || it.trim().startsWith("$section: ") }
  if (start < 0) return "$section:\n"
  var end = lines.size
  for (i in start + 1 until lines.size) {
    val line = lines[i]
    if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t") || line.trimStart().startsWith("#")) continue
    if (Regex("^[A-Za-z0-9_-]+:").containsMatchIn(line)) {
      end = i
      break
    }
  }
  return lines.subList(start, end).joinToString("\n").trimEnd() + "\n"
}


private val mihomoManagedTopLevelKeys = listOf(
  "tun",
  "iptables",
  "redir-port",
  "tproxy-port",
  "port",
  "socks-port",
  "mixed-port",
  "allow-lan",
  "bind-address",
  "log-level",
)

private fun findMihomoManagedTopLevelKeys(yaml: String): List<String> {
  val lines = yaml.replace("\r\n", "\n").lines()
  return mihomoManagedTopLevelKeys.filter { key ->
    lines.any { line -> line.startsWith("$key:") }
  }
}

private fun removeMihomoTopLevelKeys(yaml: String, keys: Collection<String>): String {
  val keySet = keys.toSet()
  val lines = yaml.replace("\r\n", "\n").lines()
  val out = mutableListOf<String>()
  var i = 0
  while (i < lines.size) {
    val line = lines[i]
    val key = keySet.firstOrNull { line.startsWith("$it:") }
    if (key == null) {
      out += line
      i++
      continue
    }
    i++
    while (i < lines.size) {
      val next = lines[i]
      if (next.isBlank() || next.startsWith(" ") || next.startsWith("\t") || next.trimStart().startsWith("#")) {
        i++
      } else {
        break
      }
    }
  }
  return out.joinToString("\n").trimEnd() + "\n"
}

private fun normalizeMihomoYamlText(yaml: String): String = yaml
  .replace("\r\n", "\n")
  .replace("\r", "\n")
  .lines()
  .joinToString("\n") { it.trimEnd() }
  .trimEnd() + "\n"

private val mihomoUserConfigTopLevelKeys = setOf(
  "external-controller",
  "secret",
  "external-ui",
  "external-ui-url",
  "mode",
  "ipv6",
  "dns",
  "proxies",
  "proxy-groups",
  "rules",
  "proxy-providers",
  "rule-providers",
  "sniffer",
  "sub-rules",
  "tunnels",
  "listeners",
  "ntp",
  "profile",
  "experimental",
)

private fun removeDuplicateMihomoTopLevelEntries(yaml: String): String {
  val lines = normalizeMihomoYamlText(yaml).lines()
  val out = mutableListOf<String>()
  val seen = mutableSetOf<String>()
  var i = 0
  val keyRegex = Regex("""^([A-Za-z0-9_-]+):(?:\s.*)?$""")
  while (i < lines.size) {
    val line = lines[i]
    val key = keyRegex.find(line)?.groupValues?.getOrNull(1)
    if (key != null && key in mihomoUserConfigTopLevelKeys) {
      if (key in seen) {
        i++
        while (i < lines.size) {
          val next = lines[i]
          val nextKey = keyRegex.find(next)?.groupValues?.getOrNull(1)
          if (nextKey != null) break
          i++
        }
        continue
      }
      seen += key
    }
    out += line
    i++
  }
  return out.joinToString("\n").trimEnd() + "\n"
}


private fun removeEmptyMihomoProviderSections(yaml: String): String {
  var out = yaml
  listOf("proxy-providers", "rule-providers").forEach { section ->
    val sectionText = extractTopLevelYamlSection(out, section).trim()
    if (sectionText == "$section:" || sectionText == "$section: {}") {
      out = replaceTopLevelYamlSection(out, section, "")
    }
  }
  return out
}


private fun removeMihomoRuntimeManagedBlock(yaml: String): String {
  val lines = yaml.replace("\r\n", "\n").lines()
  val out = mutableListOf<String>()
  var i = 0
  while (i < lines.size) {
    val line = lines[i]
    if (line.contains("ZDT-D managed runtime config", ignoreCase = true)) {
      i++
      while (i < lines.size) {
        val next = lines[i]
        if (next.startsWith("mixed-port:") || next.startsWith("allow-lan:") || next.startsWith("bind-address:") || next.startsWith("log-level:") || next.isBlank()) {
          i++
        } else {
          break
        }
      }
      continue
    }
    out += line
    i++
  }
  return out.joinToString("\n").trimEnd() + "\n"
}

private fun sanitizeMihomoUserConfigYaml(yaml: String): String {
  val withoutRuntimeBlock = removeMihomoRuntimeManagedBlock(yaml)
  val withoutDuplicate = removeDuplicateMihomoTopLevelEntries(withoutRuntimeBlock)
  return normalizeMihomoYamlText(removeEmptyMihomoProviderSections(withoutDuplicate))
}

private fun replaceTopLevelYamlSection(yaml: String, section: String, newSection: String): String {
  val normalizedYaml = yaml.replace("\r\n", "\n")
  val cleanedSection = newSection.trimEnd()
  val lines = normalizedYaml.split("\n")
  val start = lines.indexOfFirst { it.trim() == "$section:" || it.trim().startsWith("$section: ") }
  if (start < 0) {
    if (cleanedSection.isBlank()) return normalizedYaml.trimEnd() + "\n"
    return normalizedYaml.trimEnd() + "\n\n" + cleanedSection + "\n"
  }
  var end = lines.size
  for (i in start + 1 until lines.size) {
    val line = lines[i]
    if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t") || line.trimStart().startsWith("#")) continue
    if (Regex("^[A-Za-z0-9_-]+:").containsMatchIn(line)) {
      end = i
      break
    }
  }
  val before = lines.subList(0, start).joinToString("\n").trimEnd()
  val after = lines.subList(end, lines.size).joinToString("\n").trimStart('\n')
  return buildString {
    if (before.isNotBlank()) append(before).append("\n\n")
    if (cleanedSection.isNotBlank()) append(cleanedSection).append("\n")
    if (after.isNotBlank()) {
      if (isNotEmpty() && !endsWith("\n\n")) append("\n")
      append(after.trimEnd()).append("\n")
    }
  }.ifBlank { "\n" }
}

private fun appendToYamlSection(sectionText: String, snippet: String): String {
  val base = sectionText.trimEnd().ifBlank { "proxies:" }
  return base + "\n" + snippet.trimEnd() + "\n"
}


private data class MihomoYamlListBlock(
  val raw: String,
  val name: String,
  val type: String,
  val summary: String,
)

private data class MihomoProviderBlock(
  val raw: String,
  val name: String,
  val type: String,
  val url: String,
  val path: String,
  val interval: String,
  val behavior: String,
  val healthCheck: Boolean,
  val format: String,
  val proxy: String,
  val sizeLimit: String,
  val filter: String,
  val excludeFilter: String,
)

private data class MihomoRuleParts(
  val type: String,
  val value: String,
  val policy: String,
  val options: List<String>,
)

private data class MihomoDashboardPreset(
  val title: String,
  val url: String,
)

private data class MihomoProxyPreset(
  val title: String,
  val type: String,
  val category: String,
  val description: String,
  val yaml: String,
)

private data class MihomoGroupDraft(
  val name: String,
  val type: String,
  val proxies: List<String>,
  val use: List<String> = emptyList(),
  val includeAll: Boolean = false,
  val includeAllProxies: Boolean = false,
  val includeAllProviders: Boolean = false,
  val filter: String = "",
  val excludeFilter: String = "",
  val excludeType: String = "",
  val url: String = "",
  val interval: String = "",
  val lazy: Boolean = false,
  val timeout: String = "",
  val maxFailedTimes: String = "",
  val expectedStatus: String = "",
  val disableUdp: Boolean = false,
  val hidden: Boolean = false,
  val icon: String = "",
  val strategy: String = "",
)

private val mihomoDashboardPresets = listOf(
  MihomoDashboardPreset("MetaCubeXD", "https://github.com/MetaCubeX/metacubexd/archive/refs/heads/gh-pages.zip"),
  MihomoDashboardPreset("Zashboard", "https://github.com/Zephyruso/zashboard/archive/refs/heads/gh-pages.zip"),
  MihomoDashboardPreset("Zashboard no fonts", "https://github.com/Zephyruso/zashboard/archive/refs/heads/gh-pages-no-fonts.zip"),
  MihomoDashboardPreset("Yacd-meta", "https://github.com/MetaCubeX/Yacd-meta/archive/refs/heads/gh-pages.zip"),
  MihomoDashboardPreset("Yacd original", "https://github.com/haishanh/yacd/archive/refs/heads/gh-pages.zip"),
  MihomoDashboardPreset("Clash Dashboard / Razord", "https://github.com/Dreamacro/clash-dashboard/archive/refs/heads/gh-pages.zip"),
  MihomoDashboardPreset("Yacd-Meta-Classic", "https://github.com/hmjz100/Yacd-Meta-Classic/archive/refs/heads/gh-pages.zip"),
)

private fun defaultMihomoExternalUiPath(profile: String): String =
  "/data/adb/modules/ZDT-D/working_folder/mihomo/profile/${profile.ifBlank { "main" }}/work/ui"

private fun extractTopLevelScalar(yaml: String, key: String): String {
  val prefix = "$key:"
  return yaml.replace("\r\n", "\n")
    .lineSequence()
    .firstOrNull { it.startsWith(prefix) }
    ?.substringAfter(':')
    ?.trim()
    ?.trim('"')
    .orEmpty()
}

private fun yamlQuote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun replaceTopLevelScalars(yaml: String, replacements: Map<String, String>): String {
  val keys = replacements.keys
  val cleaned = yaml.replace("\r\n", "\n")
    .lineSequence()
    .filterNot { line -> keys.any { key -> line.startsWith("$key:") } }
    .joinToString("\n")
    .trimEnd()
  val head = replacements.entries.joinToString("\n") { (key, value) -> "$key: $value" }
  return head.trimEnd() + "\n\n" + cleaned.trimStart('\n') + "\n"
}

private fun parseYamlListBlocks(sectionText: String): List<MihomoYamlListBlock> {
  val lines = sectionText.replace("\r\n", "\n").split("\n")
  if (lines.isEmpty()) return emptyList()
  val result = mutableListOf<String>()
  var current = mutableListOf<String>()
  var pendingComments = mutableListOf<String>()
  fun flush() {
    if (current.isNotEmpty()) {
      result += current.joinToString("\n").trimEnd()
      current = mutableListOf()
    }
  }
  for (line in lines.drop(1)) {
    when {
      line.startsWith("  - ") -> {
        flush()
        if (pendingComments.isNotEmpty()) {
          current.addAll(pendingComments)
          pendingComments = mutableListOf()
        }
        current.add(line)
      }
      current.isEmpty() && (line.trimStart().startsWith("#") || line.isBlank()) -> pendingComments.add(line)
      current.isNotEmpty() -> current.add(line)
    }
  }
  flush()
  return result.filter { it.isNotBlank() }.map { raw ->
    val name = Regex("""(?m)^\s*(?:-\s*)?name:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val type = Regex("""(?m)^\s*type:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val server = Regex("""(?m)^\s*server:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val network = Regex("""(?m)^\s*network:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val tls = Regex("""(?m)^\s*tls:\s*true\s*$""").containsMatchIn(raw)
    val reality = raw.contains("reality-opts:")
    val summary = listOf(type, server, network, if (tls) "tls" else "", if (reality) "reality" else "")
      .filter { it.isNotBlank() }
      .joinToString(" · ")
      .ifBlank { "raw YAML block" }
    MihomoYamlListBlock(raw = raw, name = name.ifBlank { "Unnamed" }, type = type.ifBlank { "unknown" }, summary = summary)
  }
}

private fun buildYamlListSection(section: String, blocks: List<String>): String = buildString {
  append(section).append(":\n")
  blocks.filter { it.isNotBlank() }.forEach { block ->
    append(block.trimEnd()).append("\n")
  }
}

private fun parseProviderBlocks(sectionText: String): List<MihomoProviderBlock> {
  val lines = sectionText.replace("\r\n", "\n").split("\n")
  if (lines.isEmpty()) return emptyList()
  val rawBlocks = mutableListOf<String>()
  var current = mutableListOf<String>()
  fun flush() {
    if (current.isNotEmpty()) {
      rawBlocks += current.joinToString("\n").trimEnd()
      current = mutableListOf()
    }
  }
  for (line in lines.drop(1)) {
    if (Regex("^  [A-Za-z0-9_.-]+:\\s*$").matches(line)) {
      flush()
      current.add(line)
    } else if (current.isNotEmpty()) {
      current.add(line)
    }
  }
  flush()
  return rawBlocks.map { raw ->
    val name = Regex("^  ([A-Za-z0-9_.-]+):", RegexOption.MULTILINE).find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val type = Regex("""(?m)^\s{4}type:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val url = Regex("""(?m)^\s{4}url:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val path = Regex("""(?m)^\s{4}path:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val interval = Regex("""(?m)^\s{4}interval:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val behavior = Regex("""(?m)^\s{4}behavior:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val format = Regex("""(?m)^\s{4}format:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val proxy = Regex("""(?m)^\s{4}proxy:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val sizeLimit = Regex("""(?m)^\s{4}size-limit:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val filter = Regex("""(?m)^\s{4}filter:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val excludeFilter = Regex("""(?m)^\s{4}exclude-filter:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    val healthCheck = Regex("""(?m)^\s{4}health-check:\s*$""").containsMatchIn(raw)
    MihomoProviderBlock(
      raw = raw,
      name = name.ifBlank { "provider" },
      type = type.ifBlank { "unknown" },
      url = url,
      path = path,
      interval = interval,
      behavior = behavior,
      healthCheck = healthCheck,
      format = format,
      proxy = proxy,
      sizeLimit = sizeLimit,
      filter = filter,
      excludeFilter = excludeFilter,
    )
  }
}

private fun buildProviderSection(section: String, blocks: List<String>): String {
  val nonEmptyBlocks = blocks.map { it.trimEnd() }.filter { it.isNotBlank() }
  if (nonEmptyBlocks.isEmpty()) return ""
  return buildString {
    append(section).append(":\n")
    nonEmptyBlocks.forEach { append(it).append("\n") }
  }
}

private fun proxyNamesFromYaml(yamlText: String): List<String> =
  parseYamlListBlocks(extractTopLevelYamlSection(yamlText, "proxies")).map { it.name }.filter { it != "Unnamed" }

private fun proxyGroupNamesFromYaml(yamlText: String): List<String> =
  parseYamlListBlocks(extractTopLevelYamlSection(yamlText, "proxy-groups")).map { it.name }.filter { it != "Unnamed" }

private fun proxyProviderNamesFromYaml(yamlText: String): List<String> =
  parseProviderBlocks(extractTopLevelYamlSection(yamlText, "proxy-providers")).map { it.name }.filter { it.isNotBlank() }

private val mihomoBuiltInPolicies = listOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "GLOBAL", "COMPATIBLE")

private fun mihomoGroupPolicyNamesFromYaml(yamlText: String, currentGroupName: String? = null): List<String> =
  (proxyNamesFromYaml(yamlText) + proxyGroupNamesFromYaml(yamlText).filter { it != currentGroupName } + mihomoBuiltInPolicies)
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()

private fun parseMihomoRuleParts(rule: String): MihomoRuleParts {
  val parts = rule.split(',').map { it.trim() }.filter { it.isNotBlank() }
  val type = parts.getOrNull(0).orEmpty().ifBlank { "RAW" }
  return if (type == "MATCH") {
    MihomoRuleParts(
      type = type,
      value = "",
      policy = parts.getOrNull(1).orEmpty().ifBlank { "Proxy" },
      options = parts.drop(2),
    )
  } else {
    MihomoRuleParts(
      type = type,
      value = parts.getOrNull(1).orEmpty(),
      policy = parts.getOrNull(2).orEmpty(),
      options = parts.drop(3),
    )
  }
}


private fun mihomoProxyIsInDefaultGroup(yamlText: String, proxyName: String, groupName: String = "Proxy"): Boolean {
  val safeProxy = proxyName.trim()
  if (safeProxy.isBlank()) return true
  val groupSection = extractTopLevelYamlSection(yamlText, "proxy-groups")
  return parseYamlListBlocks(groupSection)
    .map { readMihomoGroupDraft(it.raw) }
    .firstOrNull { it.name == groupName }
    ?.proxies
    ?.any { it == safeProxy } == true
}

private fun addMihomoProxyToDefaultGroup(yamlText: String, proxyName: String, groupName: String = "Proxy"): String {
  val safeProxy = proxyName.trim()
  if (safeProxy.isBlank()) return yamlText
  val groupSection = extractTopLevelYamlSection(yamlText, "proxy-groups")
  val blocks = parseYamlListBlocks(groupSection).map { it.raw }.toMutableList()
  val targetIndex = blocks.indexOfFirst { readMihomoGroupDraft(it).name == groupName }
  if (targetIndex >= 0) {
    val draft = readMihomoGroupDraft(blocks[targetIndex])
    val updatedProxies = (listOf(safeProxy) + draft.proxies).filter { it != draft.name }.distinct()
    blocks[targetIndex] = buildMihomoGroupFromDraft(blocks[targetIndex], draft.copy(proxies = updatedProxies))
  } else {
    blocks.add(0, mihomoGroupTemplate(groupName, "select", listOf(safeProxy)))
  }
  return replaceTopLevelYamlSection(yamlText, "proxy-groups", buildYamlListSection("proxy-groups", blocks))
}


private data class MihomoProviderDraft(
  val name: String,
  val type: String,
  val url: String,
  val path: String,
  val interval: String,
  val behavior: String,
  val healthCheck: Boolean,
  val format: String = "",
  val proxy: String = "",
  val sizeLimit: String = "",
  val headerUserAgent: String = "",
  val headerAuthorization: String = "",
  val healthUrl: String = "",
  val healthInterval: String = "",
  val healthTimeout: String = "",
  val healthLazy: Boolean = false,
  val healthExpectedStatus: String = "",
  val overridePrefix: String = "",
  val overrideSuffix: String = "",
  val overrideTfo: Boolean = false,
  val overrideMptcp: Boolean = false,
  val overrideUdp: Boolean = false,
  val overrideUdpOverTcp: Boolean = false,
  val overrideDown: String = "",
  val overrideUp: String = "",
  val overrideSkipCertVerify: Boolean = false,
  val overrideDialerProxy: String = "",
  val overrideInterfaceName: String = "",
  val overrideRoutingMark: String = "",
  val overrideIpVersion: String = "",
  val overrideProxyName: String = "",
  val filter: String = "",
  val excludeFilter: String = "",
  val excludeType: String = "",
)

private fun readMihomoYamlList(raw: String, key: String, baseIndent: Int = 4): List<String> {
  val lines = raw.replace("\r\n", "\n").lines()
  val parentIndent = " ".repeat(baseIndent)
  val itemIndent = " ".repeat(baseIndent + 2)
  val start = lines.indexOfFirst { it == "$parentIndent$key:" }
  if (start < 0) return emptyList()
  val out = mutableListOf<String>()
  for (i in start + 1 until lines.size) {
    val line = lines[i]
    if (line.startsWith(parentIndent) && !line.startsWith(itemIndent)) break
    if (line.startsWith("$itemIndent- ")) out += stripYamlQuotes(line.removePrefix("$itemIndent- ").trim())
  }
  return out
}

private fun replaceMihomoYamlList(raw: String, key: String, values: List<String>, baseIndent: Int = 4): String {
  val lines = raw.replace("\r\n", "\n").trimEnd().lines().toMutableList()
  val parentIndent = " ".repeat(baseIndent)
  val itemIndent = " ".repeat(baseIndent + 2)
  val start = lines.indexOfFirst { it == "$parentIndent$key:" }
  val newLines = mutableListOf("$parentIndent$key:").also { list ->
    values.filter { it.isNotBlank() }.forEach { list += "$itemIndent- $it" }
  }
  if (start < 0) {
    if (values.isEmpty()) return lines.joinToString("\n").trimEnd()
    lines.addAll(newLines)
    return lines.joinToString("\n").trimEnd()
  }
  var end = lines.size
  for (i in start + 1 until lines.size) {
    if (lines[i].startsWith(parentIndent) && !lines[i].startsWith(itemIndent)) {
      end = i
      break
    }
  }
  repeat(end - start) { lines.removeAt(start) }
  if (values.isNotEmpty()) lines.addAll(start, newLines)
  return lines.joinToString("\n").trimEnd()
}

private fun readMihomoGroupDraft(raw: String): MihomoGroupDraft = MihomoGroupDraft(
  name = readMihomoBlockScalar(raw, "name").ifBlank { "Proxy" },
  type = readMihomoBlockScalar(raw, "type").ifBlank { "select" },
  proxies = readMihomoYamlList(raw, "proxies"),
  use = readMihomoYamlList(raw, "use"),
  includeAll = readMihomoBlockBool(raw, "include-all"),
  includeAllProxies = readMihomoBlockBool(raw, "include-all-proxies"),
  includeAllProviders = readMihomoBlockBool(raw, "include-all-providers"),
  filter = readMihomoBlockScalar(raw, "filter"),
  excludeFilter = readMihomoBlockScalar(raw, "exclude-filter"),
  excludeType = readMihomoBlockScalar(raw, "exclude-type"),
  url = readMihomoBlockScalar(raw, "url"),
  interval = readMihomoBlockScalar(raw, "interval"),
  lazy = readMihomoBlockBool(raw, "lazy"),
  timeout = readMihomoBlockScalar(raw, "timeout"),
  maxFailedTimes = readMihomoBlockScalar(raw, "max-failed-times"),
  expectedStatus = readMihomoBlockScalar(raw, "expected-status"),
  disableUdp = readMihomoBlockBool(raw, "disable-udp"),
  hidden = readMihomoBlockBool(raw, "hidden"),
  icon = readMihomoBlockScalar(raw, "icon"),
  strategy = readMihomoBlockScalar(raw, "strategy"),
)

private fun sanitizeMihomoGroupRaw(raw: String): String {
  val draft = readMihomoGroupDraft(raw)
  val safeProxies = draft.proxies.map { it.trim() }.filter { it.isNotBlank() && it != draft.name }.distinct()
  val safeUse = draft.use.map { it.trim() }.filter { it.isNotBlank() }.distinct()
  return buildMihomoGroupFromDraft(raw, draft.copy(proxies = safeProxies, use = safeUse))
}

private fun buildMihomoGroupFromDraft(raw: String, draft: MihomoGroupDraft): String {
  var out = raw.trimEnd().ifBlank { mihomoGroupTemplate(draft.name, draft.type, draft.proxies) }
  out = upsertMihomoBlockScalar(out, "name", draft.name.ifBlank { "Proxy" }, quote = true, removeIfBlank = false)
  out = upsertMihomoBlockScalar(out, "type", draft.type.ifBlank { "select" }, removeIfBlank = false)
  out = replaceMihomoYamlList(out, "proxies", draft.proxies.filter { it != draft.name }.distinct())
  out = replaceMihomoYamlList(out, "use", draft.use.distinct())
  out = upsertMihomoBlockScalar(out, "include-all", if (draft.includeAll) "true" else "")
  out = upsertMihomoBlockScalar(out, "include-all-proxies", if (draft.includeAllProxies) "true" else "")
  out = upsertMihomoBlockScalar(out, "include-all-providers", if (draft.includeAllProviders) "true" else "")
  out = upsertMihomoBlockScalar(out, "filter", draft.filter, quote = true)
  out = upsertMihomoBlockScalar(out, "exclude-filter", draft.excludeFilter, quote = true)
  out = upsertMihomoBlockScalar(out, "exclude-type", draft.excludeType, quote = true)
  out = upsertMihomoBlockScalar(out, "url", draft.url.ifBlank { if (draft.type in setOf("url-test", "fallback", "load-balance")) "http://www.gstatic.com/generate_204" else "" })
  out = upsertMihomoBlockScalar(out, "interval", draft.interval.ifBlank { if (draft.type in setOf("url-test", "fallback", "load-balance")) "300" else "" })
  out = upsertMihomoBlockScalar(out, "lazy", if (draft.lazy) "true" else "")
  out = upsertMihomoBlockScalar(out, "timeout", draft.timeout.filter(Char::isDigit))
  out = upsertMihomoBlockScalar(out, "max-failed-times", draft.maxFailedTimes.filter(Char::isDigit))
  out = upsertMihomoBlockScalar(out, "expected-status", draft.expectedStatus.filter(Char::isDigit))
  out = upsertMihomoBlockScalar(out, "disable-udp", if (draft.disableUdp) "true" else "")
  out = upsertMihomoBlockScalar(out, "hidden", if (draft.hidden) "true" else "")
  out = upsertMihomoBlockScalar(out, "icon", draft.icon, quote = draft.icon.contains(':') || draft.icon.contains('/'))
  out = upsertMihomoBlockScalar(out, "strategy", draft.strategy.ifBlank { if (draft.type == "load-balance") "consistent-hashing" else "" })
  return out.trimEnd()
}

private fun readMihomoProviderDraft(raw: String, ruleProvider: Boolean): MihomoProviderDraft {
  val name = Regex("^  ([A-Za-z0-9_.-]+):", RegexOption.MULTILINE).find(raw)?.groupValues?.getOrNull(1).orEmpty()
  return MihomoProviderDraft(
    name = name.ifBlank { if (ruleProvider) "reject" else "provider1" },
    type = Regex("""(?m)^\s{4}type:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "http" },
    url = Regex("""(?m)^\s{4}url:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
    path = Regex("""(?m)^\s{4}path:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
    interval = Regex("""(?m)^\s{4}interval:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
    behavior = Regex("""(?m)^\s{4}behavior:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "domain" },
    healthCheck = raw.contains(Regex("""(?m)^\s{4}health-check:\s*$""")),
    format = Regex("""(?m)^\s{4}format:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
    proxy = Regex("""(?m)^\s{4}proxy:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
    sizeLimit = Regex("""(?m)^\s{4}size-limit:\s*([^\s#]+)""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
    headerUserAgent = readMihomoProviderHeaderUserAgent(raw),
    headerAuthorization = readMihomoProviderHeaderAuthorization(raw),
    healthUrl = readMihomoNestedScalar(raw, "health-check", "url"),
    healthInterval = readMihomoNestedScalar(raw, "health-check", "interval"),
    healthTimeout = readMihomoNestedScalar(raw, "health-check", "timeout"),
    healthLazy = readMihomoNestedScalar(raw, "health-check", "lazy").equals("true", ignoreCase = true),
    healthExpectedStatus = readMihomoNestedScalar(raw, "health-check", "expected-status"),
    overridePrefix = readMihomoNestedScalar(raw, "override", "additional-prefix"),
    overrideSuffix = readMihomoNestedScalar(raw, "override", "additional-suffix"),
    overrideTfo = readMihomoNestedScalar(raw, "override", "tfo").equals("true", ignoreCase = true),
    overrideMptcp = readMihomoNestedScalar(raw, "override", "mptcp").equals("true", ignoreCase = true),
    overrideUdp = readMihomoNestedScalar(raw, "override", "udp").equals("true", ignoreCase = true),
    overrideUdpOverTcp = readMihomoNestedScalar(raw, "override", "udp-over-tcp").equals("true", ignoreCase = true),
    overrideDown = readMihomoNestedScalar(raw, "override", "down"),
    overrideUp = readMihomoNestedScalar(raw, "override", "up"),
    overrideSkipCertVerify = readMihomoNestedScalar(raw, "override", "skip-cert-verify").equals("true", ignoreCase = true),
    overrideDialerProxy = readMihomoNestedScalar(raw, "override", "dialer-proxy"),
    overrideInterfaceName = readMihomoNestedScalar(raw, "override", "interface-name"),
    overrideRoutingMark = readMihomoNestedScalar(raw, "override", "routing-mark"),
    overrideIpVersion = readMihomoNestedScalar(raw, "override", "ip-version"),
    overrideProxyName = readMihomoNestedScalar(raw, "override", "proxy-name"),
    filter = Regex("""(?m)^\s{4}filter:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
    excludeFilter = Regex("""(?m)^\s{4}exclude-filter:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
    excludeType = Regex("""(?m)^\s{4}exclude-type:\s*[\"']?([^\"'\n]+)[\"']?\s*$""").find(raw)?.groupValues?.getOrNull(1).orEmpty(),
  )
}

private fun renameMihomoProviderBlock(raw: String, newName: String): String {
  val safeName = newName.ifBlank { "provider1" }.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
  val lines = raw.replace("\r\n", "\n").trimEnd().lines().toMutableList()
  val idx = lines.indexOfFirst { Regex("^  [A-Za-z0-9_.-]+:\\s*$").matches(it) }
  if (idx >= 0) lines[idx] = "  $safeName:" else lines.add(0, "  $safeName:")
  return lines.joinToString("\n").trimEnd()
}

private fun upsertMihomoProviderScalar(raw: String, key: String, value: String, quote: Boolean = false, removeIfBlank: Boolean = true): String {
  val lines = raw.replace("\r\n", "\n").trimEnd().lines().toMutableList()
  val trimmedValue = value.trim()
  val regex = Regex("""^\s{4}${Regex.escape(key)}:\s*.*$""")
  val index = lines.indexOfFirst { regex.matches(it) }
  if (trimmedValue.isBlank() && removeIfBlank) {
    if (index >= 0) lines.removeAt(index)
    return lines.joinToString("\n").trimEnd()
  }
  val newLine = "    $key: ${formatMihomoScalar(trimmedValue, quote)}"
  if (index >= 0) lines[index] = newLine else {
    val insertAfterType = lines.indexOfFirst { Regex("""^\s{4}type:\s*.*$""").matches(it) }
    lines.add(if (insertAfterType >= 0) insertAfterType + 1 else lines.size, newLine)
  }
  return lines.joinToString("\n").trimEnd()
}

private fun removeMihomoProviderNestedBlock(raw: String, key: String): String {
  val lines = raw.replace("\r\n", "\n").trimEnd().lines().toMutableList()
  val start = lines.indexOfFirst { it == "    $key:" }
  if (start < 0) return lines.joinToString("\n").trimEnd()
  var end = lines.size
  for (i in start + 1 until lines.size) {
    if (lines[i].startsWith("    ") && !lines[i].startsWith("      ")) { end = i; break }
  }
  repeat(end - start) { lines.removeAt(start) }
  return lines.joinToString("\n").trimEnd()
}

private fun appendMihomoProviderNestedBlock(raw: String, key: String, linesToAdd: List<String>): String {
  val base = removeMihomoProviderNestedBlock(raw, key).trimEnd()
  if (linesToAdd.isEmpty()) return base
  return buildString {
    if (base.isNotBlank()) append(base).append("\n")
    append("    ").append(key).append(":\n")
    linesToAdd.forEach { append("      ").append(it).append("\n") }
  }.trimEnd()
}

private fun readMihomoProviderHeaderUserAgent(raw: String): String {
  val lines = raw.replace("\r\n", "\n").lines()
  val headerIndex = lines.indexOfFirst { it == "    header:" || it == "    headers:" }
  if (headerIndex < 0) return ""
  val uaIndex = (headerIndex + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("User-Agent:") } ?: return ""
  for (i in uaIndex + 1 until lines.size) {
    val line = lines[i]
    if (line.startsWith("      ") && !line.startsWith("        ")) break
    if (line.trim().startsWith("- ")) return stripYamlQuotes(line.trim().removePrefix("- "))
  }
  return ""
}

private fun readMihomoProviderHeaderAuthorization(raw: String): String {
  val lines = raw.replace("\r\n", "\n").lines()
  val headerIndex = lines.indexOfFirst { it == "    header:" || it == "    headers:" }
  if (headerIndex < 0) return ""
  val authIndex = (headerIndex + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("Authorization:") } ?: return ""
  for (i in authIndex + 1 until lines.size) {
    val line = lines[i]
    if (line.startsWith("      ") && !line.startsWith("        ")) break
    if (line.trim().startsWith("- ")) return stripYamlQuotes(line.trim().removePrefix("- "))
  }
  return ""
}

private fun setMihomoProviderHeader(raw: String, userAgent: String, authorization: String): String {
  var out = removeMihomoProviderNestedBlock(raw, "header")
  out = removeMihomoProviderNestedBlock(out, "headers")
  val lines = mutableListOf<String>()
  if (userAgent.isNotBlank()) {
    lines += "User-Agent:"
    lines += "  - ${yamlQuote(userAgent.trim())}"
  }
  if (authorization.isNotBlank()) {
    lines += "Authorization:"
    lines += "  - ${yamlQuote(authorization.trim())}"
  }
  if (lines.isEmpty()) return out
  return appendMihomoProviderNestedBlock(out, "header", lines)
}

private fun setMihomoProviderOverride(raw: String, draft: MihomoProviderDraft): String {
  val lines = mutableListOf<String>()
  if (draft.overridePrefix.isNotBlank()) lines += "additional-prefix: ${yamlQuote(draft.overridePrefix.trim())}"
  if (draft.overrideSuffix.isNotBlank()) lines += "additional-suffix: ${yamlQuote(draft.overrideSuffix.trim())}"
  if (draft.overrideTfo) lines += "tfo: true"
  if (draft.overrideMptcp) lines += "mptcp: true"
  if (draft.overrideUdp) lines += "udp: true"
  if (draft.overrideUdpOverTcp) lines += "udp-over-tcp: true"
  if (draft.overrideDown.isNotBlank()) lines += "down: ${yamlQuote(draft.overrideDown.trim())}"
  if (draft.overrideUp.isNotBlank()) lines += "up: ${yamlQuote(draft.overrideUp.trim())}"
  if (draft.overrideSkipCertVerify) lines += "skip-cert-verify: true"
  if (draft.overrideDialerProxy.isNotBlank()) lines += "dialer-proxy: ${draft.overrideDialerProxy.trim()}"
  if (draft.overrideInterfaceName.isNotBlank()) lines += "interface-name: ${draft.overrideInterfaceName.trim()}"
  if (draft.overrideRoutingMark.isNotBlank()) lines += "routing-mark: ${draft.overrideRoutingMark.filter(Char::isDigit)}"
  if (draft.overrideIpVersion.isNotBlank()) lines += "ip-version: ${draft.overrideIpVersion.trim()}"
  if (draft.overrideProxyName.isNotBlank()) lines += "proxy-name: ${yamlQuote(draft.overrideProxyName.trim())}"
  return appendMihomoProviderNestedBlock(raw, "override", lines)
}

private fun setMihomoProviderHealthCheck(raw: String, draft: MihomoProviderDraft): String {
  if (!draft.healthCheck) return removeMihomoProviderNestedBlock(raw, "health-check")
  val lines = mutableListOf("enable: true")
  lines += "url: ${draft.healthUrl.ifBlank { "http://www.gstatic.com/generate_204" }}"
  lines += "interval: ${draft.healthInterval.filter(Char::isDigit).ifBlank { "300" }}"
  draft.healthTimeout.filter(Char::isDigit).takeIf { it.isNotBlank() }?.let { lines += "timeout: $it" }
  if (draft.healthLazy) lines += "lazy: true"
  draft.healthExpectedStatus.filter(Char::isDigit).takeIf { it.isNotBlank() }?.let { lines += "expected-status: $it" }
  return appendMihomoProviderNestedBlock(raw, "health-check", lines)
}

private fun buildMihomoProviderFromDraft(raw: String, draft: MihomoProviderDraft, ruleProvider: Boolean): String {
  var out = raw.trimEnd().ifBlank {
    if (ruleProvider) mihomoRuleProviderTemplate(draft.name, draft.type) else mihomoProxyProviderTemplate(draft.name, draft.type)
  }
  out = renameMihomoProviderBlock(out, draft.name)
  out = upsertMihomoProviderScalar(out, "type", draft.type.ifBlank { "http" }, removeIfBlank = false)
  if (ruleProvider) {
    out = upsertMihomoProviderScalar(out, "behavior", draft.behavior.ifBlank { "domain" }, removeIfBlank = false)
    out = upsertMihomoProviderScalar(out, "format", draft.format)
  }
  out = upsertMihomoProviderScalar(out, "url", draft.url, quote = draft.url.contains(':') || draft.url.contains('/'))
  out = upsertMihomoProviderScalar(out, "path", draft.path.ifBlank { if (ruleProvider) "./rule_providers/${draft.name}.yaml" else "./proxy_providers/${draft.name}.yaml" })
  out = upsertMihomoProviderScalar(out, "interval", draft.interval.filter(Char::isDigit))
  out = upsertMihomoProviderScalar(out, "proxy", draft.proxy)
  out = upsertMihomoProviderScalar(out, "size-limit", draft.sizeLimit.filter(Char::isDigit))
  out = upsertMihomoProviderScalar(out, "filter", draft.filter, quote = true)
  out = upsertMihomoProviderScalar(out, "exclude-filter", draft.excludeFilter, quote = true)
  out = upsertMihomoProviderScalar(out, "exclude-type", draft.excludeType, quote = true)
  out = setMihomoProviderHeader(out, draft.headerUserAgent, draft.headerAuthorization)
  out = setMihomoProviderOverride(out, draft)
  out = setMihomoProviderHealthCheck(out, draft)
  return out.trimEnd()
}


private fun stripYamlQuotes(value: String): String {
  val trimmed = value.trim().substringBefore(" #").trim()
  return if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
    trimmed.substring(1, trimmed.length - 1)
  } else trimmed
}

private fun readMihomoBlockScalar(raw: String, key: String): String {
  val pattern = Regex("""(?m)^\s*(?:-\s*)?${Regex.escape(key)}:\s*(.*?)\s*$""")
  return stripYamlQuotes(pattern.find(raw)?.groupValues?.getOrNull(1).orEmpty())
}

private fun readMihomoBlockBool(raw: String, key: String): Boolean =
  readMihomoBlockScalar(raw, key).equals("true", ignoreCase = true)

private fun formatMihomoScalar(value: String, quote: Boolean): String =
  if (quote) yamlQuote(value) else value.ifBlank { "\"\"" }

private fun upsertMihomoBlockScalar(
  raw: String,
  key: String,
  value: String,
  quote: Boolean = false,
  removeIfBlank: Boolean = true,
): String {
  val normalized = raw.replace("\r\n", "\n").trimEnd()
  val lines = normalized.lines().toMutableList()
  val trimmedValue = value.trim()
  val lineRegex = Regex("""^\s*(?:-\s*)?${Regex.escape(key)}:\s*.*$""")
  val index = lines.indexOfFirst { lineRegex.matches(it) }
  if (trimmedValue.isBlank() && removeIfBlank) {
    if (index >= 0) lines.removeAt(index)
    return lines.joinToString("\n").trimEnd()
  }
  val newLine = if (key == "name") "  - name: ${formatMihomoScalar(trimmedValue, quote = true)}" else "    $key: ${formatMihomoScalar(trimmedValue, quote)}"
  if (index >= 0) {
    lines[index] = newLine
  } else {
    val insertAfterType = lines.indexOfFirst { Regex("""^\s*type:\s*.*$""").matches(it) }
    val insertAfterName = lines.indexOfFirst { Regex("""^\s*-\s*name:\s*.*$""").matches(it) }
    val insertAt = when {
      key == "name" -> 0
      insertAfterType >= 0 -> insertAfterType + 1
      insertAfterName >= 0 -> insertAfterName + 1
      else -> lines.size
    }
    lines.add(insertAt.coerceIn(0, lines.size), newLine)
  }
  return lines.joinToString("\n").trimEnd()
}

private fun readMihomoNestedScalar(raw: String, parent: String, key: String): String {
  val lines = raw.replace("\r\n", "\n").lines()
  val parentIndex = lines.indexOfFirst { Regex("""^\s{4}${Regex.escape(parent)}:\s*$""").matches(it) }
  if (parentIndex < 0) return ""
  for (i in parentIndex + 1 until lines.size) {
    val line = lines[i]
    if (line.startsWith("    ") && !line.startsWith("      ")) break
    val pattern = Regex("""^\s{6}${Regex.escape(key)}:\s*(.*?)\s*$""")
    val match = pattern.find(line)
    if (match != null) return stripYamlQuotes(match.groupValues.getOrNull(1).orEmpty())
  }
  return ""
}

private fun upsertMihomoNestedScalar(
  raw: String,
  parent: String,
  key: String,
  value: String,
  quote: Boolean = false,
  removeIfBlank: Boolean = true,
): String {
  val lines = raw.replace("\r\n", "\n").trimEnd().lines().toMutableList()
  val trimmedValue = value.trim()
  var parentIndex = lines.indexOfFirst { Regex("""^\s{4}${Regex.escape(parent)}:\s*$""").matches(it) }
  if (parentIndex < 0) {
    if (trimmedValue.isBlank() && removeIfBlank) return lines.joinToString("\n").trimEnd()
    lines.add("    $parent:")
    parentIndex = lines.lastIndex
  }
  var end = lines.size
  for (i in parentIndex + 1 until lines.size) {
    if (lines[i].startsWith("    ") && !lines[i].startsWith("      ")) {
      end = i
      break
    }
  }
  val fieldRegex = Regex("""^\s{6}${Regex.escape(key)}:\s*.*$""")
  val fieldIndex = (parentIndex + 1 until end).firstOrNull { fieldRegex.matches(lines[it]) } ?: -1
  if (trimmedValue.isBlank() && removeIfBlank) {
    if (fieldIndex >= 0) lines.removeAt(fieldIndex)
    val newEnd = lines.size
    val hasChildren = (parentIndex + 1 until newEnd).any { it < lines.size && lines[it].startsWith("      ") }
    if (!hasChildren && parentIndex in lines.indices) lines.removeAt(parentIndex)
    return lines.joinToString("\n").trimEnd()
  }
  val newLine = "      $key: ${formatMihomoScalar(trimmedValue, quote)}"
  if (fieldIndex >= 0) lines[fieldIndex] = newLine else lines.add((parentIndex + 1).coerceIn(0, lines.size), newLine)
  return lines.joinToString("\n").trimEnd()
}


private fun readMihomoWsHost(raw: String): String {
  val lines = raw.replace("\r\n", "\n").lines()
  val wsIndex = lines.indexOfFirst { Regex("""^\s{4}ws-opts:\s*$""").matches(it) }
  if (wsIndex < 0) return ""
  val headersIndex = (wsIndex + 1 until lines.size).firstOrNull { i ->
    val line = lines[i]
    if (line.startsWith("    ") && !line.startsWith("      ")) return ""
    Regex("""^\s{6}headers:\s*$""").matches(line)
  } ?: return ""
  for (i in headersIndex + 1 until lines.size) {
    val line = lines[i]
    if (!line.startsWith("        ")) break
    val match = Regex("""^\s{8}Host:\s*(.*?)\s*$""").find(line)
    if (match != null) return stripYamlQuotes(match.groupValues.getOrNull(1).orEmpty())
  }
  return ""
}

private fun upsertMihomoWsHost(raw: String, value: String): String {
  val lines = raw.replace("\r\n", "\n").trimEnd().lines().toMutableList()
  val trimmedValue = value.trim()
  var wsIndex = lines.indexOfFirst { Regex("""^\s{4}ws-opts:\s*$""").matches(it) }
  if (wsIndex < 0) {
    if (trimmedValue.isBlank()) return lines.joinToString("\n").trimEnd()
    lines.add("    ws-opts:")
    wsIndex = lines.lastIndex
  }
  var wsEnd = lines.size
  for (i in wsIndex + 1 until lines.size) {
    if (lines[i].startsWith("    ") && !lines[i].startsWith("      ")) {
      wsEnd = i
      break
    }
  }
  var headersIndex = (wsIndex + 1 until wsEnd).firstOrNull { Regex("""^\s{6}headers:\s*$""").matches(lines[it]) } ?: -1
  if (headersIndex < 0) {
    if (trimmedValue.isBlank()) return lines.joinToString("\n").trimEnd()
    lines.add((wsIndex + 1).coerceIn(0, lines.size), "      headers:")
    headersIndex = wsIndex + 1
    wsEnd += 1
  }
  var headersEnd = wsEnd
  for (i in headersIndex + 1 until lines.size) {
    if (!lines[i].startsWith("        ")) {
      headersEnd = i
      break
    }
  }
  val hostIndex = (headersIndex + 1 until headersEnd).firstOrNull { Regex("""^\s{8}Host:\s*.*$""").matches(lines[it]) } ?: -1
  if (trimmedValue.isBlank()) {
    if (hostIndex >= 0) lines.removeAt(hostIndex)
    return lines.joinToString("\n").trimEnd()
  }
  val newLine = "        Host: ${formatMihomoScalar(trimmedValue, quote = false)}"
  if (hostIndex >= 0) lines[hostIndex] = newLine else lines.add((headersIndex + 1).coerceIn(0, lines.size), newLine)
  return lines.joinToString("\n").trimEnd()
}

private fun mihomoProxyNeedsServer(type: String): Boolean = type.lowercase(Locale.ROOT) !in setOf("direct", "dns")

private fun mihomoProxySupportsTransport(type: String): Boolean = type.lowercase(Locale.ROOT) in setOf("vmess", "vless", "trojan")

private fun mihomoProxyAuthKey(type: String): String = when (type.lowercase(Locale.ROOT)) {
  "hysteria" -> "auth-str"
  "snell" -> "psk"
  "sudoku" -> "key"
  else -> "password"
}

private fun mihomoProxyUsesUuid(type: String): Boolean = type.lowercase(Locale.ROOT) in setOf("vmess", "vless", "tuic")

private fun mihomoProxyUsesCipher(type: String): Boolean = type.lowercase(Locale.ROOT) in setOf("ss", "ssr", "vmess")

private fun mihomoProxyServerNameKey(type: String): String = when (type.lowercase(Locale.ROOT)) {
  "vmess", "vless" -> "servername"
  else -> "sni"
}

private fun mihomoProxySupportsTls(type: String): Boolean = type.lowercase(Locale.ROOT) in setOf(
  "http", "socks5", "vmess", "vless", "trojan", "anytls", "hysteria", "hysteria2", "tuic", "masque", "trusttunnel"
)

private fun buildMihomoProxyBlockFromFields(
  baseRaw: String,
  name: String,
  type: String,
  server: String,
  port: String,
  username: String,
  secret: String,
  uuid: String,
  cipher: String,
  udp: Boolean,
  tls: Boolean,
  skipCert: Boolean,
  serverName: String,
  network: String,
  flow: String,
  packetEncoding: String,
  wsPath: String,
  wsHost: String,
  grpcService: String,
  realityPublicKey: String,
  realityShortId: String,
): String {
  val safeType = type.ifBlank { "direct" }.lowercase(Locale.ROOT)
  val authKey = mihomoProxyAuthKey(safeType)
  val serverNameKey = mihomoProxyServerNameKey(safeType)
  var updated = baseRaw.ifBlank { "  - name: ${yamlQuote(name.ifBlank { "NEW-PROXY" })}\n    type: $safeType" }
  updated = upsertMihomoBlockScalar(updated, "name", name.ifBlank { "NEW-PROXY" }, quote = true, removeIfBlank = false)
  updated = upsertMihomoBlockScalar(updated, "type", safeType, quote = false, removeIfBlank = false)
  if (mihomoProxyNeedsServer(safeType)) {
    updated = upsertMihomoBlockScalar(updated, "server", server, quote = false)
    updated = upsertMihomoBlockScalar(updated, "port", port, quote = false)
  } else {
    updated = upsertMihomoBlockScalar(updated, "server", "")
    updated = upsertMihomoBlockScalar(updated, "port", "")
  }
  updated = upsertMihomoBlockScalar(updated, "username", username, quote = true)
  updated = upsertMihomoBlockScalar(updated, authKey, secret, quote = true)
  updated = upsertMihomoBlockScalar(updated, "uuid", uuid, quote = true)
  updated = upsertMihomoBlockScalar(updated, "cipher", cipher, quote = false)
  if (safeType != "dns") updated = upsertMihomoBlockScalar(updated, "udp", udp.toString(), quote = false, removeIfBlank = false)
  if (mihomoProxySupportsTls(safeType)) {
    updated = upsertMihomoBlockScalar(updated, "tls", if (tls) "true" else "", quote = false)
    updated = upsertMihomoBlockScalar(updated, "skip-cert-verify", if (skipCert) "true" else "", quote = false)
    updated = upsertMihomoBlockScalar(updated, serverNameKey, serverName, quote = false)
  } else {
    updated = upsertMihomoBlockScalar(updated, "tls", "")
    updated = upsertMihomoBlockScalar(updated, "skip-cert-verify", "")
    updated = upsertMihomoBlockScalar(updated, serverNameKey, "")
  }
  if (mihomoProxySupportsTransport(safeType)) {
    updated = upsertMihomoBlockScalar(updated, "network", network.ifBlank { "tcp" }, quote = false, removeIfBlank = false)
    if (network == "ws") {
      updated = upsertMihomoNestedScalar(updated, "ws-opts", "path", wsPath, quote = true)
      updated = upsertMihomoWsHost(updated, wsHost)
    }
    if (network == "grpc") updated = upsertMihomoNestedScalar(updated, "grpc-opts", "grpc-service-name", grpcService, quote = true)
  } else {
    updated = upsertMihomoBlockScalar(updated, "network", "")
  }
  if (safeType in setOf("vless", "trojan")) {
    updated = upsertMihomoBlockScalar(updated, "flow", flow, quote = false)
    updated = upsertMihomoBlockScalar(updated, "packet-encoding", packetEncoding, quote = false)
    updated = upsertMihomoNestedScalar(updated, "reality-opts", "public-key", realityPublicKey, quote = true)
    updated = upsertMihomoNestedScalar(updated, "reality-opts", "short-id", realityShortId, quote = true)
  }
  return updated.trimEnd()
}

private val mihomoProxyPresets = listOf(
  MihomoProxyPreset("DIRECT", "direct", "Basic", "Direct outbound", """
  - name: "DIRECT-OUT"
    type: direct
    udp: true
    ip-version: ipv4
""".trimEnd()),
  MihomoProxyPreset("DNS", "dns", "Basic", "Internal DNS outbound", """
  - name: "DNS-OUT"
    type: dns
""".trimEnd()),
  MihomoProxyPreset("HTTP / HTTPS", "http", "Basic", "HTTP proxy; set tls: true for HTTPS proxy", """
  - name: "HTTP-PROXY"
    type: http
    server: proxy.example.com
    port: 8080
    username: "user"
    password: "pass"
    # tls: true
    # sni: proxy.example.com
    # skip-cert-verify: false
    headers:
      User-Agent: "mihomo"
""".trimEnd()),
  MihomoProxyPreset("SOCKS5", "socks5", "Basic", "SOCKS5 proxy with optional auth", """
  - name: "SOCKS5-PROXY"
    type: socks5
    server: socks.example.com
    port: 1080
    username: "user"
    password: "pass"
    udp: true
    # tls: true
""".trimEnd()),
  MihomoProxyPreset("Shadowsocks", "ss", "Shadowsocks", "SS/SS2022 proxy", """
  - name: "SS-AES-GCM"
    type: ss
    server: ss.example.com
    port: 443
    cipher: aes-128-gcm
    password: "password"
    udp: true
""".trimEnd()),
  MihomoProxyPreset("Shadowsocks + simple-obfs", "ss", "Shadowsocks", "SS with obfs plugin", """
  - name: "SS-OBFS"
    type: ss
    server: ss-obfs.example.com
    port: 443
    cipher: aes-256-gcm
    password: "password"
    udp: true
    plugin: obfs
    plugin-opts:
      mode: tls
      host: bing.com
""".trimEnd()),
  MihomoProxyPreset("Shadowsocks + v2ray-plugin WS", "ss", "Shadowsocks", "SS over WebSocket/WSS", """
  - name: "SS-V2RAY-PLUGIN-WS"
    type: ss
    server: ss-ws.example.com
    port: 443
    cipher: chacha20-ietf-poly1305
    password: "password"
    udp: true
    plugin: v2ray-plugin
    plugin-opts:
      mode: websocket
      tls: true
      host: cdn.example.com
      path: "/ws"
""".trimEnd()),
  MihomoProxyPreset("ShadowsocksR", "ssr", "Shadowsocks", "Legacy SSR format", """
  - name: "SSR"
    type: ssr
    server: ssr.example.com
    port: 443
    cipher: chacha20-ietf
    password: "password"
    obfs: tls1.2_ticket_auth
    protocol: auth_sha1_v4
    # obfs-param: domain.tld
    # protocol-param: "#"
""".trimEnd()),
  MihomoProxyPreset("Snell v3", "snell", "Shadowsocks", "Snell v1/v2/v3; UDP for v3", """
  - name: "SNELL-V3"
    type: snell
    server: snell.example.com
    port: 44046
    psk: "yourpsk"
    version: 3
    udp: true
    obfs-opts:
      mode: http
      host: bing.com
""".trimEnd()),
  MihomoProxyPreset("VMess TCP TLS", "vmess", "Xray-like", "VMess over TCP/TLS", """
  - name: "VMESS-TCP-TLS"
    type: vmess
    server: vmess.example.com
    port: 443
    uuid: "00000000-0000-0000-0000-000000000000"
    alterId: 0
    cipher: auto
    udp: true
    tls: true
    servername: vmess.example.com
    network: tcp
""".trimEnd()),
  MihomoProxyPreset("VMess WS TLS", "vmess", "Xray-like", "VMess over WebSocket/WSS", """
  - name: "VMESS-WS-TLS"
    type: vmess
    server: vmess-ws.example.com
    port: 443
    uuid: "00000000-0000-0000-0000-000000000000"
    alterId: 0
    cipher: auto
    udp: true
    tls: true
    servername: cdn.example.com
    network: ws
    ws-opts:
      path: "/ws"
      headers:
        Host: cdn.example.com
""".trimEnd()),
  MihomoProxyPreset("VMess gRPC TLS", "vmess", "Xray-like", "VMess over gRPC", """
  - name: "VMESS-GRPC-TLS"
    type: vmess
    server: vmess-grpc.example.com
    port: 443
    uuid: "00000000-0000-0000-0000-000000000000"
    alterId: 0
    cipher: auto
    udp: true
    tls: true
    servername: vmess-grpc.example.com
    network: grpc
    grpc-opts:
      grpc-service-name: "grpc-service"
""".trimEnd()),
  MihomoProxyPreset("VLESS TCP TLS", "vless", "Xray-like", "VLESS over TCP/TLS", """
  - name: "VLESS-TCP-TLS"
    type: vless
    server: vless.example.com
    port: 443
    uuid: "00000000-0000-0000-0000-000000000000"
    udp: true
    tls: true
    servername: vless.example.com
    network: tcp
""".trimEnd()),
  MihomoProxyPreset("VLESS Reality Vision", "vless", "Xray-like", "VLESS Reality Vision", """
  - name: "VLESS-REALITY-VISION"
    type: vless
    server: reality.example.com
    port: 443
    uuid: "00000000-0000-0000-0000-000000000000"
    udp: true
    tls: true
    flow: xtls-rprx-vision
    servername: www.microsoft.com
    client-fingerprint: chrome
    reality-opts:
      public-key: "PUBLIC_KEY"
      short-id: "SHORT_ID"
    packet-encoding: xudp
    network: tcp
""".trimEnd()),
  MihomoProxyPreset("VLESS WS TLS", "vless", "Xray-like", "VLESS over WS/WSS", """
  - name: "VLESS-WS-TLS"
    type: vless
    server: vless-ws.example.com
    port: 443
    uuid: "00000000-0000-0000-0000-000000000000"
    udp: true
    tls: true
    servername: cdn.example.com
    network: ws
    ws-opts:
      path: "/vless"
      headers:
        Host: cdn.example.com
""".trimEnd()),
  MihomoProxyPreset("VLESS gRPC TLS", "vless", "Xray-like", "VLESS over gRPC", """
  - name: "VLESS-GRPC-TLS"
    type: vless
    server: vless-grpc.example.com
    port: 443
    uuid: "00000000-0000-0000-0000-000000000000"
    udp: true
    tls: true
    servername: vless-grpc.example.com
    network: grpc
    grpc-opts:
      grpc-service-name: "grpc-service"
""".trimEnd()),
  MihomoProxyPreset("Trojan TCP TLS", "trojan", "Xray-like", "Classic Trojan", """
  - name: "TROJAN-TCP-TLS"
    type: trojan
    server: trojan.example.com
    port: 443
    password: "password"
    udp: true
    sni: trojan.example.com
    alpn:
      - h2
      - http/1.1
    client-fingerprint: chrome
    skip-cert-verify: false
    network: tcp
""".trimEnd()),
  MihomoProxyPreset("Trojan WS TLS", "trojan", "Xray-like", "Trojan-Go style WS/WSS", """
  - name: "TROJAN-WS-TLS"
    type: trojan
    server: trojan-ws.example.com
    port: 443
    password: "password"
    udp: true
    sni: cdn.example.com
    network: ws
    ws-opts:
      path: "/trojan"
      headers:
        Host: cdn.example.com
""".trimEnd()),
  MihomoProxyPreset("Trojan gRPC TLS", "trojan", "Xray-like", "Trojan over gRPC", """
  - name: "TROJAN-GRPC-TLS"
    type: trojan
    server: trojan-grpc.example.com
    port: 443
    password: "password"
    udp: true
    sni: trojan-grpc.example.com
    network: grpc
    grpc-opts:
      grpc-service-name: "trojan-grpc"
""".trimEnd()),
  MihomoProxyPreset("Trojan Reality", "trojan", "Xray-like", "Trojan with reality-opts", """
  - name: "TROJAN-REALITY"
    type: trojan
    server: trojan-reality.example.com
    port: 443
    password: "password"
    udp: true
    sni: www.microsoft.com
    client-fingerprint: chrome
    reality-opts:
      public-key: "PUBLIC_KEY"
      short-id: "SHORT_ID"
    network: tcp
""".trimEnd()),
  MihomoProxyPreset("AnyTLS", "anytls", "Modern", "AnyTLS outbound", """
  - name: "ANYTLS"
    type: anytls
    server: anytls.example.com
    port: 443
    password: "password"
    client-fingerprint: chrome
    udp: true
    sni: anytls.example.com
    alpn:
      - h2
      - http/1.1
    skip-cert-verify: false
    idle-session-check-interval: 30
    idle-session-timeout: 30
    min-idle-session: 0
""".trimEnd()),
  MihomoProxyPreset("Mieru TCP", "mieru", "Modern", "Mieru over TCP", """
  - name: "MIERU-TCP"
    type: mieru
    server: mieru.example.com
    port: 2999
    transport: TCP
    username: "user"
    password: "password"
    multiplexing: MULTIPLEXING_LOW
""".trimEnd()),
  MihomoProxyPreset("Mieru UDP", "mieru", "Modern", "Mieru over UDP", """
  - name: "MIERU-UDP"
    type: mieru
    server: mieru.example.com
    port: 2999
    transport: UDP
    username: "user"
    password: "password"
    multiplexing: MULTIPLEXING_LOW
""".trimEnd()),
  MihomoProxyPreset("Sudoku", "sudoku", "Modern", "Obfuscated Sudoku proxy", """
  - name: "SUDOKU"
    type: sudoku
    server: sudoku.example.com
    port: 443
    key: "<client_key_or_uuid>"
    aead-method: chacha20-poly1305
    padding-min: 2
    padding-max: 7
    table-type: prefer_ascii
    httpmask:
      disable: false
      mode: legacy
      tls: true
      mask-host: ""
      path-root: ""
      multiplex: off
    enable-pure-downlink: false
""".trimEnd()),
  MihomoProxyPreset("Hysteria v1 UDP", "hysteria", "QUIC / UDP", "Hysteria v1 over UDP", """
  - name: "HYSTERIA1-UDP"
    type: hysteria
    server: hysteria.example.com
    port: 443
    auth-str: "password"
    protocol: udp
    up: "30 Mbps"
    down: "200 Mbps"
    sni: hysteria.example.com
    alpn:
      - h3
    skip-cert-verify: false
""".trimEnd()),
  MihomoProxyPreset("Hysteria v1 faketcp", "hysteria", "QUIC / UDP", "Hysteria v1 with faketcp", """
  - name: "HYSTERIA1-FAKETCP"
    type: hysteria
    server: hysteria.example.com
    port: 443
    auth-str: "password"
    protocol: faketcp
    up: "30 Mbps"
    down: "200 Mbps"
    sni: hysteria.example.com
    skip-cert-verify: false
""".trimEnd()),
  MihomoProxyPreset("Hysteria2", "hysteria2", "QUIC / UDP", "Modern Hysteria2", """
  - name: "HYSTERIA2"
    type: hysteria2
    server: hy2.example.com
    port: 443
    password: "password"
    up: "30 Mbps"
    down: "200 Mbps"
    sni: hy2.example.com
    alpn:
      - h3
    skip-cert-verify: false
""".trimEnd()),
  MihomoProxyPreset("Hysteria2 port hopping", "hysteria2", "QUIC / UDP", "HY2 with port hopping", """
  - name: "HYSTERIA2-PORT-HOPPING"
    type: hysteria2
    server: hy2-hop.example.com
    port: 443
    ports: 443-8443
    hop-interval: 30
    password: "password"
    up: "30 Mbps"
    down: "200 Mbps"
    obfs: salamander
    obfs-password: "obfs-password"
    sni: hy2-hop.example.com
    alpn:
      - h3
    skip-cert-verify: false
""".trimEnd()),
  MihomoProxyPreset("TUIC v5", "tuic", "QUIC / UDP", "TUIC v5 uuid/password", """
  - name: "TUIC-V5"
    type: tuic
    server: tuic.example.com
    port: 10443
    uuid: "00000000-0000-0000-0000-000000000001"
    password: "password"
    udp-relay-mode: native
    congestion-controller: bbr
    sni: tuic.example.com
    alpn:
      - h3
    reduce-rtt: true
    request-timeout: 8000
    skip-cert-verify: false
""".trimEnd()),
  MihomoProxyPreset("TUIC v4", "tuic", "QUIC / UDP", "TUIC v4 token", """
  - name: "TUIC-V4"
    type: tuic
    server: tuic-v4.example.com
    port: 10443
    token: "TOKEN"
    udp-relay-mode: native
    sni: tuic-v4.example.com
    alpn:
      - h3
    reduce-rtt: true
    request-timeout: 8000
    skip-cert-verify: false
""".trimEnd()),
  MihomoProxyPreset("WireGuard outbound", "wireguard", "Other", "WireGuard outbound inside Mihomo", """
  - name: "WIREGUARD"
    type: wireguard
    server: wg.example.com
    port: 51820
    private-key: "CLIENT_PRIVATE_KEY"
    public-key: "SERVER_PUBLIC_KEY"
    ip: 172.16.0.2
    ipv6: fd01:5ca1:ab1e:80fa::2
    allowed-ips:
      - 0.0.0.0/0
      - ::/0
    udp: true
    mtu: 1420
    # pre-shared-key: "PRESHARED_KEY"
    # persistent-keepalive: 25
""".trimEnd()),
  MihomoProxyPreset("WireGuard via proxy", "wireguard", "Other", "WireGuard dialer-proxy", """
  - name: "WIREGUARD-VIA-PROXY"
    type: wireguard
    server: wg.example.com
    port: 51820
    private-key: "CLIENT_PRIVATE_KEY"
    public-key: "SERVER_PUBLIC_KEY"
    ip: 172.16.0.2
    allowed-ips:
      - 0.0.0.0/0
    udp: true
    dialer-proxy: "SS-AES-GCM"
""".trimEnd()),
  MihomoProxyPreset("SSH", "ssh", "Other", "SSH outbound", """
  - name: "SSH-PROXY"
    type: ssh
    server: ssh.example.com
    port: 22
    username: "root"
    password: "password"
    # private-key: "/path/to/id_ed25519"
    # private-key-passphrase: "key_password"
    # host-key:
    #   - "ssh-rsa AAAAB3NzaC1yc2EAA..."
    # host-key-algorithms:
    #   - rsa
""".trimEnd()),
  MihomoProxyPreset("MASQUE QUIC", "masque", "Other", "MASQUE over QUIC", """
  - name: "MASQUE-QUIC"
    type: masque
    server: masque.example.com
    port: 443
    private-key: "BASE64_ENCODED_PRIVATE_KEY"
    public-key: "BASE64_ENCODED_SERVER_PUBLIC_KEY"
    ip: 172.16.0.2/32
    ipv6: fd00::2/128
    mtu: 1280
    udp: true
    # network: quic
""".trimEnd()),
  MihomoProxyPreset("MASQUE H2", "masque", "Other", "MASQUE over HTTP/2", """
  - name: "MASQUE-H2"
    type: masque
    server: masque-h2.example.com
    port: 443
    private-key: "BASE64_ENCODED_PRIVATE_KEY"
    public-key: "BASE64_ENCODED_SERVER_PUBLIC_KEY"
    ip: 172.16.0.2/32
    ipv6: fd00::2/128
    mtu: 1280
    udp: true
    network: h2
""".trimEnd()),
  MihomoProxyPreset("TrustTunnel TCP/TLS", "trusttunnel", "Other", "TrustTunnel over TCP/TLS", """
  - name: "TRUSTTUNNEL"
    type: trusttunnel
    server: trust.example.com
    port: 443
    username: "username"
    password: "password"
    health-check: true
    udp: true
    sni: trust.example.com
    alpn:
      - h2
    skip-cert-verify: false
""".trimEnd()),
  MihomoProxyPreset("TrustTunnel QUIC", "trusttunnel", "Other", "TrustTunnel over QUIC", """
  - name: "TRUSTTUNNEL-QUIC"
    type: trusttunnel
    server: trust-quic.example.com
    port: 443
    username: "username"
    password: "password"
    health-check: true
    udp: true
    quic: true
    congestion-controller: bbr
    sni: trust-quic.example.com
    alpn:
      - h3
    skip-cert-verify: false
""".trimEnd()),
)

private fun mihomoGroupTemplate(name: String, type: String, proxies: List<String>): String = buildString {
  append("  - name: ").append(yamlQuote(name)).append("\n")
  append("    type: ").append(type).append("\n")
  if (proxies.isNotEmpty()) {
    append("    proxies:\n")
    proxies.distinct().forEach { append("      - ").append(it).append("\n") }
  }
  when (type) {
    "url-test", "fallback" -> append("    url: http://www.gstatic.com/generate_204\n    interval: 300\n")
    "load-balance" -> append("    url: http://www.gstatic.com/generate_204\n    interval: 300\n    strategy: consistent-hashing\n")
    "relay" -> Unit
  }
}.trimEnd()

private fun mihomoProxyProviderTemplate(name: String, type: String): String = when (type) {
  "file" -> """
  $name:
    type: file
    path: ./proxy_providers/$name.yaml
    health-check:
      enable: true
      url: http://www.gstatic.com/generate_204
      interval: 300
""".trimEnd()
  "inline" -> """
  $name:
    type: inline
    payload:
      - name: "DIRECT-OUT"
        type: direct
        udp: true
""".trimEnd()
  else -> """
  $name:
    type: http
    url: "https://example.com/sub.yaml"
    path: ./proxy_providers/$name.yaml
    interval: 3600
    health-check:
      enable: true
      url: http://www.gstatic.com/generate_204
      interval: 300
""".trimEnd()
}

private fun mihomoRuleProviderTemplate(name: String, type: String): String = when (type) {
  "file" -> """
  $name:
    type: file
    behavior: domain
    path: ./rule_providers/$name.yaml
""".trimEnd()
  "inline" -> """
  $name:
    type: inline
    behavior: domain
    payload:
      - '+.example.com'
""".trimEnd()
  else -> """
  $name:
    type: http
    behavior: domain
    url: "https://example.com/$name.yaml"
    path: ./rule_providers/$name.yaml
    interval: 86400
""".trimEnd()
}

private fun mihomoProxyTemplate(type: String): String = when (type) {
  "direct" -> """
  # DIRECT — прямое подключение без прокси
  - name: "DIRECT-OUT"
    type: direct
    udp: true
    ip-version: ipv4
""".trimEnd()
  "socks5" -> """
  # SOCKS5 — обычный SOCKS5 proxy
  - name: "SOCKS5-PROXY"
    type: socks5
    server: socks.example.com
    port: 1080
    username: "user"
    password: "pass"
    udp: true
""".trimEnd()
  "http" -> """
  # HTTP — обычный HTTP/HTTPS proxy
  - name: "HTTP-PROXY"
    type: http
    server: proxy.example.com
    port: 8080
    username: "user"
    password: "pass"
""".trimEnd()
  "vless" -> """
  # VLESS Reality Vision
  - name: "VLESS-REALITY-VISION"
    type: vless
    server: reality.example.com
    port: 443
    uuid: "00000000-0000-0000-0000-000000000000"
    udp: true
    tls: true
    flow: xtls-rprx-vision
    servername: www.microsoft.com
    client-fingerprint: chrome
    reality-opts:
      public-key: "PUBLIC_KEY"
      short-id: "SHORT_ID"
    packet-encoding: xudp
    network: tcp
""".trimEnd()
  "hysteria2" -> """
  # Hysteria2
  - name: "HYSTERIA2"
    type: hysteria2
    server: hy2.example.com
    port: 443
    password: "password"
    up: "30 Mbps"
    down: "200 Mbps"
    sni: hy2.example.com
    alpn:
      - h3
    skip-cert-verify: false
""".trimEnd()
  "tuic" -> """
  # TUIC v5
  - name: "TUIC-V5"
    type: tuic
    server: tuic.example.com
    port: 10443
    uuid: "00000000-0000-0000-0000-000000000001"
    password: "password"
    udp-relay-mode: native
    congestion-controller: bbr
    sni: tuic.example.com
    alpn:
      - h3
    reduce-rtt: true
    request-timeout: 8000
    skip-cert-verify: false
""".trimEnd()
  else -> """
  - name: "NEW-PROXY"
    type: $type
    server: example.com
    port: 443
""".trimEnd()
}

@Composable
fun MihomoProgramScreen(
  programs: List<ApiModels.Program>,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "mihomo" }
  var showCreate by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val profiles = remember(program?.profiles) {
    program?.profiles.orEmpty()
      .map { MihomoProfileInfo(name = it.name, enabled = it.enabled) }
      .sortedWith(compareByDescending<MihomoProfileInfo> { mihomoProfileIndex(it.name) }.thenBy { it.name.lowercase(Locale.ROOT) })
  }

  if (showCreate) {
    MihomoCreateProfileDialog(
      existing = program?.profiles.orEmpty().map { it.name },
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.createNamedProfile("mihomo", name) { created ->
          if (created != null) {
            scope.launch {
              val usedTuns = loadUsedVpnTunNames(actions, programs, excludeProgramId = "mihomo", excludeProfile = created)
              val tun = nextFreeVpnTunName(usedTuns, startAt = 20)
              val usedPorts = loadUsedMihomoMixedPorts(actions, programs, excludeProfile = created)
              val port = nextFreeMihomoMixedPort(usedPorts)
              val usedControllerPorts = loadUsedMihomoControllerPorts(actions, programs, excludeProfile = created)
              val controllerPort = nextFreeMihomoControllerPort(usedControllerPorts)
              val usedIpv4 = loadUsedVpnIpv4Cidrs(actions, programs, excludeProgramId = "mihomo", excludeProfile = created)
              val settingOk = awaitSaveJsonVpnTunGuard(
                actions,
                "${vpnProfileApiPath("mihomo", created)}/setting",
                defaultMihomoSettingJson(tun, port),
              )
              val configOk = awaitSaveTextMihomo(
                actions,
                "${vpnProfileApiPath("mihomo", created)}/config",
                rewriteMihomoExplicitIpv4Conflicts(mihomoSampleConfig(created, controllerPort), usedIpv4),
              )
              showSnack(
                if (settingOk && configOk) context.getString(R.string.mihomo_profile_created, created)
                else context.getString(R.string.save_failed)
              )
              actions.refreshPrograms()
              onOpenProfile("mihomo", created)
            }
          } else {
            showSnack(context.getString(R.string.create_failed))
          }
        }
      },
    )
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mihomo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.mihomo_program_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.tab_profiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      FilledTonalButton(onClick = { showCreate = true }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.action_add))
      }
    }

    if (profiles.isEmpty()) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.mihomo_no_profiles_title), fontWeight = FontWeight.SemiBold)
          Text(stringResource(R.string.mihomo_no_profiles_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
      }
    }

    profiles.forEach { info ->
      ProfileStatusCard(
        programId = "mihomo",
        profileName = info.name,
        checked = info.enabled,
        onOpen = { onOpenProfile("mihomo", info.name) },
        onCheckedChange = { checked ->
          actions.setProfileEnabled("mihomo", info.name, checked) { ok ->
            showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
            if (ok) actions.refreshPrograms()
          }
        },
        onDelete = {
          actions.deleteProfile("mihomo", info.name) { ok ->
            showSnack(if (ok) context.getString(R.string.deleted) else context.getString(R.string.delete_failed))
            if (ok) actions.refreshPrograms()
          }
        },
      )
    }

    Spacer(Modifier.height(80.dp))
  }
}

@Composable
private fun MihomoCreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val existingSet = remember(existing) { existing.toSet() }
  val invalidText = stringResource(R.string.mihomo_profile_name_invalid)
  val existsText = stringResource(R.string.profile_already_exists)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.mihomo_create_profile_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.mihomo_profile_name_rules),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = name,
          onValueChange = { value ->
            name = value.take(10)
            error = null
          },
          label = { Text(stringResource(R.string.profile_name_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          supportingText = { Text(stringResource(R.string.profile_name_len_fmt, name.length)) },
          isError = error != null,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val n = name.trim()
          when {
            !mihomoProfileNameRegex.matches(n) -> error = invalidText
            n in existingSet -> error = existsText
            else -> onCreate(n)
          }
        },
        enabled = name.isNotBlank(),
      ) { Text(stringResource(R.string.action_create)) }
    },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MihomoProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "mihomo" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val basePath = remember(profile) { mihomoProfilePath(profile) }

  var selectedTab by remember(profile) { mutableIntStateOf(0) }
  var loading by remember(profile) { mutableStateOf(true) }
  var tunText by remember(profile) { mutableStateOf("tun20") }
  var mixedPortText by remember(profile) { mutableStateOf("17890") }
  var logLevel by remember(profile) { mutableStateOf("info") }
  var tun2socksLogLevel by remember(profile) { mutableStateOf("info") }
  var syncedSetting by remember(profile) { mutableStateOf(MihomoSettingUi()) }
  var settingInitialized by remember(profile) { mutableStateOf(false) }
  var yamlText by remember(profile) { mutableStateOf("") }
  var lastSavedYamlText by remember(profile) { mutableStateOf("") }
  var yamlLoading by remember(profile) { mutableStateOf(true) }
  var yamlSaving by remember(profile) { mutableStateOf(false) }
  var selectedApps by remember(profile) { mutableStateOf(emptySet<String>()) }
  var appCount by remember(profile) { mutableStateOf(0) }
  var usedVpnTuns by remember(profile) { mutableStateOf(emptySet<String>()) }
  var usedMihomoPorts by remember(profile) { mutableStateOf(emptySet<Int>()) }
  var usedMihomoControllerPorts by remember(profile) { mutableStateOf(emptySet<Int>()) }
  var usedVpnIpv4Cidrs by remember(profile) { mutableStateOf(emptyList<VpnIpv4Use>()) }
  var pendingChanges by remember(profile) { mutableStateOf(emptyList<MihomoPendingChange>()) }
  var pendingExpanded by remember(profile) { mutableStateOf(false) }
  var mihomoWebPanelChecking by remember(profile) { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun reload() {
    loading = true
    yamlLoading = true
    settingInitialized = false
    scope.launch {
      val usedTuns = loadUsedVpnTunNames(actions, programs, excludeProgramId = "mihomo", excludeProfile = profile)
      val loaded = parseMihomoSetting(awaitLoadJsonMihomo(actions, "$basePath/setting"))
      val setting = if (isVpnTunNameUsed(loaded.tun, usedTuns)) loaded.copy(tun = nextFreeVpnTunName(usedTuns, startAt = 20)) else loaded
      val ports = loadUsedMihomoMixedPorts(actions, programs, excludeProfile = profile)
      val controllerPorts = loadUsedMihomoControllerPorts(actions, programs, excludeProfile = profile)
      val usedIpv4 = loadUsedVpnIpv4Cidrs(actions, programs, excludeProgramId = "mihomo", excludeProfile = profile)
      val apps = parsePkgList(awaitLoadTextMihomo(actions, "$basePath/apps/user").orEmpty())
      val cfg = awaitLoadTextMihomo(actions, "$basePath/config").orEmpty().ifBlank {
        rewriteMihomoExplicitIpv4Conflicts(mihomoSampleConfig(profile, nextFreeMihomoControllerPort(controllerPorts)), usedIpv4)
      }

      usedVpnTuns = usedTuns
      usedMihomoPorts = ports
      usedMihomoControllerPorts = controllerPorts
      usedVpnIpv4Cidrs = usedIpv4
      syncedSetting = loaded
      tunText = setting.tun
      mixedPortText = setting.mixedPort.toString()
      logLevel = setting.logLevel
      tun2socksLogLevel = setting.tun2socksLogLevel
      settingInitialized = true
      selectedApps = apps
      appCount = apps.size
      yamlText = cfg
      lastSavedYamlText = cfg
      loading = false
      yamlLoading = false
    }
  }

  LaunchedEffect(profile) { reload() }

  val tunNameConflict = remember(tunText, usedVpnTuns) { isVpnTunNameUsed(tunText, usedVpnTuns) }
  val tunValid = remember(tunText, tunNameConflict) { isValidMihomoTun(tunText) && !tunNameConflict }
  val mixedPort = remember(mixedPortText) { mixedPortText.trim().toIntOrNull() }
  val portConflict = remember(mixedPort, usedMihomoPorts) { mixedPort != null && mixedPort in usedMihomoPorts }
  val portValid = remember(mixedPortText, portConflict) { isValidMihomoPort(mixedPortText) && !portConflict }
  val controllerPort = remember(yamlText) { extractMihomoControllerPort(yamlText) }
  val mihomoIpv4Cidrs = remember(yamlText) { extractMihomoExplicitIpv4Cidrs(yamlText).map { it.value } }
  val mihomoIpv4SelfOverlap = remember(mihomoIpv4Cidrs) { vpnIpv4CidrsOverlapEachOther(mihomoIpv4Cidrs) }
  val mihomoIpv4Conflict = remember(mihomoIpv4Cidrs, usedVpnIpv4Cidrs) { vpnIpv4CidrsConflict(mihomoIpv4Cidrs, usedVpnIpv4Cidrs) }
  val webPanelUrl = remember(controllerPort) { controllerPort?.let { mihomoWebPanelUrl(it) } }
  val webPanelVisible = prof?.enabled == true && controllerPort != null && !isOnlyZdtdAppSelected(selectedApps)

  LaunchedEffect(tunText, mixedPortText, logLevel, tun2socksLogLevel, settingInitialized) {
    if (!settingInitialized || loading) return@LaunchedEffect
    delay(MIHOMO_AUTOSAVE_DELAY_MS)
    if (!isValidMihomoTun(tunText) || isVpnTunNameUsed(tunText, usedVpnTuns)) return@LaunchedEffect
    val port = mixedPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return@LaunchedEffect
    if (port in usedMihomoPorts) return@LaunchedEffect
    val safeLog = logLevel.takeIf { it in mihomoLogLevels } ?: "info"
    val safeT2sLog = tun2socksLogLevel.takeIf { it in mihomoTun2SocksLogLevels } ?: "info"
    val current = MihomoSettingUi(
      tun = tunText.trim(),
      mixedPort = port,
      logLevel = safeLog,
      tun2socksLogLevel = safeT2sLog,
    )
    if (current == syncedSetting) return@LaunchedEffect
    val ok = awaitSaveJsonMihomo(actions, "$basePath/setting", buildMihomoSettingJson(current))
    if (ok) {
      syncedSetting = current
    } else {
      showSnack(context.getString(R.string.mihomo_port_conflict_error))
    }
  }

  fun queuePendingChange(change: MihomoPendingChange) {
    pendingChanges = (pendingChanges.filter { it.id != change.id } + change).takeLast(6)
    pendingExpanded = false
  }

  fun removePendingChange(id: String) {
    pendingChanges = pendingChanges.filter { it.id != id }
    if (pendingChanges.isEmpty()) pendingExpanded = false
  }

  fun savePendingChange(change: MihomoPendingChange, notify: Boolean = true) {
    val sourceYaml = change.addProxyToDefaultGroup?.let { proxyName ->
      addMihomoProxyToDefaultGroup(yamlText, proxyName)
    } ?: change.yaml
    val normalizedText = sanitizeMihomoUserConfigYaml(rewriteMihomoExplicitIpv4Conflicts(sourceYaml, usedVpnIpv4Cidrs))
    yamlSaving = true
    actions.saveText("$basePath/config", normalizedText) { ok ->
      yamlSaving = false
      if (ok) {
        yamlText = normalizedText
        lastSavedYamlText = normalizedText
        removePendingChange(change.id)
        if (notify) showSnack(context.getString(R.string.saved_apply_after_restart))
      } else {
        showSnack(context.getString(R.string.save_failed))
      }
    }
  }

  fun saveAllPendingChanges() {
    if (pendingChanges.isEmpty()) return
    var mergedYaml = yamlText
    pendingChanges.forEach { change ->
      mergedYaml = change.addProxyToDefaultGroup?.let { proxyName ->
        addMihomoProxyToDefaultGroup(mergedYaml, proxyName)
      } ?: change.yaml
    }
    val normalizedText = sanitizeMihomoUserConfigYaml(rewriteMihomoExplicitIpv4Conflicts(mergedYaml, usedVpnIpv4Cidrs))
    yamlSaving = true
    actions.saveText("$basePath/config", normalizedText) { ok ->
      yamlSaving = false
      if (ok) {
        yamlText = normalizedText
        lastSavedYamlText = normalizedText
        pendingChanges = emptyList()
        pendingExpanded = false
        showSnack(context.getString(R.string.saved_apply_after_restart))
      } else {
        showSnack(context.getString(R.string.save_failed))
      }
    }
  }

  fun saveYaml(newText: String = yamlText, notify: Boolean = true, afterSuccess: (() -> Unit)? = null) {
    val normalizedText = sanitizeMihomoUserConfigYaml(rewriteMihomoExplicitIpv4Conflicts(newText, usedVpnIpv4Cidrs))
    yamlSaving = true
    actions.saveText("$basePath/config", normalizedText) { ok ->
      yamlSaving = false
      if (ok) {
        yamlText = normalizedText
        lastSavedYamlText = normalizedText
        pendingChanges = pendingChanges.filter { it.yaml.trimEnd() != normalizedText.trimEnd() }
        afterSuccess?.invoke()
        if (notify) showSnack(context.getString(R.string.saved_apply_after_restart))
      } else {
        queuePendingChange(
          MihomoPendingChange(
            id = "save-failed-${normalizedText.hashCode()}",
            title = context.getString(R.string.mihomo_pending_save_failed_title),
            message = context.getString(R.string.mihomo_pending_save_failed_desc),
            detail = context.getString(R.string.save_failed),
            yaml = normalizedText,
          )
        )
        showSnack(context.getString(R.string.save_failed))
      }
    }
  }

  Box(Modifier.fillMaxSize()) {
  Column(
    Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Mihomo / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Text(
      stringResource(R.string.mihomo_program_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = stringResource(R.string.enabled_card_profile_title),
      checked = prof?.enabled ?: false,
      onCheckedChange = { checked ->
        when {
          checked && mihomoIpv4SelfOverlap -> showSnack(context.getString(R.string.vpn_ipv4_address_self_overlap))
          checked && mihomoIpv4Conflict -> showSnack(context.getString(R.string.vpn_ipv4_address_in_use))
          else -> {
            actions.setProfileEnabled("mihomo", profile, checked) { ok ->
              showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
              if (ok) actions.refreshPrograms()
            }
          }
        }
      },
    )

    AnimatedVisibility(
      visible = webPanelVisible,
      enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
      exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(180)),
    ) {
      MihomoWebPanelCard(
        checking = mihomoWebPanelChecking,
        onOpen = {
          val port = controllerPort
          val url = webPanelUrl
          if (port == null || url == null) {
            showSnack(context.getString(R.string.web_panel_unavailable))
          } else if (!mihomoWebPanelChecking) {
            scope.launch {
              mihomoWebPanelChecking = true
              val available = isLocalWebPanelPortOpen(port)
              mihomoWebPanelChecking = false
              if (available) {
                context.startActivity(
                  Intent(context, LocalWebPanelActivity::class.java)
                    .putExtra(LocalWebPanelActivity.EXTRA_SCOPE_KEY, mihomoWebPanelScope(profile))
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

    val tabLabels = listOf(
      stringResource(R.string.mihomo_tab_general),
      stringResource(R.string.mihomo_tab_dashboard),
      stringResource(R.string.mihomo_tab_yaml),
      stringResource(R.string.mihomo_tab_dns),
      stringResource(R.string.mihomo_tab_proxies),
      stringResource(R.string.mihomo_tab_groups),
      stringResource(R.string.mihomo_tab_rules),
      stringResource(R.string.mihomo_tab_providers),
      stringResource(R.string.mihomo_tab_apps),
      stringResource(R.string.mihomo_tab_advanced),
    )
    val tabDescriptions = listOf(
      stringResource(R.string.mihomo_tab_desc_general),
      stringResource(R.string.mihomo_tab_desc_dashboard),
      stringResource(R.string.mihomo_tab_desc_yaml),
      stringResource(R.string.mihomo_tab_desc_dns),
      stringResource(R.string.mihomo_tab_desc_proxies),
      stringResource(R.string.mihomo_tab_desc_groups),
      stringResource(R.string.mihomo_tab_desc_rules),
      stringResource(R.string.mihomo_tab_desc_providers),
      stringResource(R.string.mihomo_tab_desc_apps),
      stringResource(R.string.mihomo_tab_desc_advanced),
    )
    MihomoProfileTabSelector(
      labels = tabLabels,
      descriptions = tabDescriptions,
      selectedIndex = selectedTab,
      onSelect = { selectedTab = it },
    )

    if (loading || yamlLoading) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
          Text(stringResource(R.string.common_loading))
        }
      }
    }

    if (mihomoIpv4SelfOverlap || mihomoIpv4Conflict) {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
      ) {
        Text(
          stringResource(if (mihomoIpv4SelfOverlap) R.string.vpn_ipv4_address_self_overlap else R.string.vpn_ipv4_address_in_use),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    when (selectedTab) {
      0 -> MihomoGeneralTab(
        profile = profile,
        tunText = tunText,
        onTunChange = { tunText = it.trim().take(15) },
        mixedPortText = mixedPortText,
        onMixedPortChange = { mixedPortText = it.filter(Char::isDigit).take(5) },
        logLevel = logLevel,
        onLogLevelChange = { logLevel = it },
        tun2socksLogLevel = tun2socksLogLevel,
        onTun2SocksLogLevelChange = { tun2socksLogLevel = it },
        tunValid = tunValid,
        tunConflict = tunNameConflict,
        portValid = portValid,
        portConflict = portConflict,
      )
      1 -> MihomoDashboardTab(
        profile = profile,
        yamlText = yamlText,
        usedControllerPorts = usedMihomoControllerPorts,
        onSaveYaml = { updated ->
          saveYaml(updated, afterSuccess = {
            actions.clearMihomoProfileUi(profile)
          })
        },
      )
      2 -> MihomoYamlTab(
        profile = profile,
        yamlText = yamlText,
        onYamlChange = { yamlText = it },
        saving = yamlSaving,
        hasChanges = yamlText != lastSavedYamlText,
        onSave = { saveYaml() },
        onRestoreSample = { yamlText = mihomoSampleConfig(profile, nextFreeMihomoControllerPort(usedMihomoControllerPorts)) },
      )
      3 -> MihomoRawSectionEditorTab(
        title = stringResource(R.string.mihomo_dns_title),
        description = stringResource(R.string.mihomo_dns_desc),
        section = "dns",
        yamlText = yamlText,
        defaultText = mihomoDefaultDnsSection(),
        onSaveYaml = { updated -> saveYaml(updated, notify = false) },
      )
      4 -> MihomoProxiesBuilderTab(
        yamlText = yamlText,
        onSaveYaml = { updated -> saveYaml(updated, notify = false) },
        onQueuePendingChange = { proxyName, pendingYaml ->
          queuePendingChange(
            MihomoPendingChange(
              id = "mihomo-proxy-group-$proxyName",
              title = context.getString(R.string.mihomo_pending_add_proxy_group_title),
              message = context.getString(R.string.mihomo_pending_add_proxy_group_desc, proxyName),
              detail = "Proxy",
              yaml = pendingYaml,
              addProxyToDefaultGroup = proxyName,
            )
          )
        },
      )
      5 -> MihomoGroupsBuilderTab(
        yamlText = yamlText,
        onSaveYaml = { updated -> saveYaml(updated, notify = false) },
      )
      6 -> MihomoRulesBuilderTab(
        yamlText = yamlText,
        onSaveYaml = { updated -> saveYaml(updated, notify = false) },
      )
      7 -> MihomoProvidersBuilderTab(
        yamlText = yamlText,
        onSaveYaml = { updated -> saveYaml(updated, notify = false) },
      )
      8 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppListPickerCard(
          title = stringResource(R.string.mihomo_apps_title),
          desc = stringResource(R.string.mihomo_apps_desc),
          path = "$basePath/apps/user",
          actions = actions,
          snackHost = snackHost,
          saveFailedMessage = stringResource(R.string.mihomo_app_conflict_error),
          onSavedSelection = {
            selectedApps = it
            appCount = it.size
          },
        )
        if ((prof?.enabled == true) && appCount == 0) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
          ) {
            Text(
              stringResource(R.string.mihomo_enabled_empty_apps_warning),
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }
      else -> MihomoAdvancedCompatibilityTab(
        yamlText = yamlText,
        onSaveYaml = { updated -> saveYaml(updated, notify = false) },
      )
    }

    Spacer(Modifier.height(80.dp))
  }

  AnimatedVisibility(
    visible = pendingChanges.isNotEmpty(),
    modifier = Modifier
      .align(Alignment.BottomEnd)
      .padding(end = 16.dp, bottom = 64.dp)
      .navigationBarsPadding(),
  ) {
    MihomoPendingChangesPanel(
      pendingChanges = pendingChanges,
      expanded = pendingExpanded,
      compact = compact,
      saving = yamlSaving,
      onToggle = { pendingExpanded = !pendingExpanded },
      onApply = { change -> savePendingChange(change) },
      onDiscard = { change -> removePendingChange(change.id) },
      onApplyAll = { saveAllPendingChanges() },
      onDiscardAll = { pendingChanges = emptyList(); pendingExpanded = false },
    )
  }
  }
}


@Composable
private fun MihomoPendingChangesPanel(
  pendingChanges: List<MihomoPendingChange>,
  expanded: Boolean,
  compact: Boolean,
  saving: Boolean,
  onToggle: () -> Unit,
  onApply: (MihomoPendingChange) -> Unit,
  onDiscard: (MihomoPendingChange) -> Unit,
  onApplyAll: () -> Unit,
  onDiscardAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (pendingChanges.isEmpty()) return
  Column(
    modifier = modifier.width(if (compact) 286.dp else 360.dp),
    horizontalAlignment = Alignment.End,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    AnimatedVisibility(visible = expanded) {
      Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
      ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            stringResource(R.string.mihomo_pending_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
          )
          Text(
            stringResource(R.string.mihomo_pending_count, pendingChanges.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f),
          )
          pendingChanges.forEach { change ->
            Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.18f)),
            ) {
              Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(change.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(change.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                if (change.detail.isNotBlank()) {
                  Text(
                    "${stringResource(R.string.mihomo_pending_details)}: ${change.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    maxLines = 3,
                  )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                  TextButton(onClick = { onDiscard(change) }, enabled = !saving) {
                    Text(if (compact) "✕" else stringResource(R.string.mihomo_pending_discard))
                  }
                  Spacer(Modifier.width(6.dp))
                  Button(onClick = { onApply(change) }, enabled = !saving) {
                    Text(if (compact) "✓" else stringResource(R.string.mihomo_pending_apply))
                  }
                }
              }
            }
          }
          if (pendingChanges.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
              TextButton(onClick = onDiscardAll, enabled = !saving) {
                Text(if (compact) "✕ ✕" else stringResource(R.string.mihomo_pending_discard_all))
              }
              Spacer(Modifier.width(6.dp))
              Button(onClick = onApplyAll, enabled = !saving) {
                Text(if (compact) "✓ ✓" else stringResource(R.string.mihomo_pending_apply_all))
              }
            }
          }
        }
      }
    }
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.errorContainer,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
      shadowElevation = 8.dp,
      modifier = Modifier
        .width(52.dp)
        .height(52.dp),
    ) {
      TextButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxSize(),
      ) {
        Text(
          "⚠",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.error,
          maxLines = 1,
        )
      }
    }
  }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MihomoProfileTabSelector(
  labels: List<String>,
  descriptions: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  var recentTabs by remember(labels) { mutableStateOf(emptyList<Int>()) }
  val safeSelected = selectedIndex.coerceIn(0, labels.lastIndex.coerceAtLeast(0))

  LaunchedEffect(safeSelected, labels.size) {
    recentTabs = (listOf(safeSelected) + recentTabs.filter { it != safeSelected && it in labels.indices })
      .distinct()
      .take(labels.size)
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val reservedForMenu = 70f
        val availableWidth = (maxWidth.value - reservedForMenu).coerceAtLeast(96f)
        val basePriority = listOf(0, 1, 4, 5, 6, 8, 3, 2, 7, 9).filter { it in labels.indices }
        val ordered = (listOf(safeSelected) + recentTabs + basePriority + labels.indices.toList()).distinct()
        val visible = mutableListOf<Int>()
        var usedWidth = 0f
        ordered.forEach { index ->
          val labelWidth = (labels[index].length * 8.6f + 34f).coerceIn(76f, 168f)
          if (visible.isEmpty() || usedWidth + labelWidth <= availableWidth) {
            visible += index
            usedWidth += labelWidth + 8f
          }
        }
        if (safeSelected !in visible && visible.isNotEmpty()) visible[visible.lastIndex] = safeSelected
        val visibleIndices = visible.distinct()
        val hiddenIndices = labels.indices.filter { it !in visibleIndices }

        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          visibleIndices.forEach { index ->
            val isActive = safeSelected == index
            FilterChip(
              selected = isActive,
              onClick = { onSelect(index) },
              label = {
                Text(
                  labels[index],
                  maxLines = 1,
                  fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.98f),
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
            )
          }
          Spacer(Modifier.weight(1f))
          if (hiddenIndices.isNotEmpty()) {
            Box {
              FilledTonalButton(onClick = { menuExpanded = true }) {
                Text("⋯")
              }
              DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
              ) {
                labels.indices.forEach { index ->
                  val isActive = safeSelected == index
                  val label = labels[index]
                  DropdownMenuItem(
                    text = {
                      Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = if (isActive) {
                          MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                        } else {
                          MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                        },
                      ) {
                        Column(
                          Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                          verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                          Text(
                            text = if (isActive) "● $label" else label,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                          )
                          Text(
                            descriptions.getOrNull(index).orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) {
                              MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                            } else {
                              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                            },
                            maxLines = 2,
                          )
                        }
                      }
                    },
                    onClick = {
                      onSelect(index)
                      menuExpanded = false
                    },
                  )
                }
              }
            }
          }
        }
      }
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
      ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(labels.getOrNull(safeSelected).orEmpty(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
          Text(
            descriptions.getOrNull(safeSelected).orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
      }
    }
  }
}

@Composable
private fun MihomoDropdownSelectorCard(
  label: String,
  value: String,
  options: List<String>,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  descriptionFor: (String) -> String = { "" },
  displayFor: (String) -> String = { it },
) {
  var expanded by remember { mutableStateOf(false) }
  val safeOptions = options.distinct().ifEmpty { listOf(value) }
  val shownValue = value.ifBlank { safeOptions.firstOrNull().orEmpty() }
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
  ) {
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
      Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
      Box {
        FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
          Text(displayFor(shownValue), modifier = Modifier.weight(1f), maxLines = 1)
          Text("▼")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          safeOptions.forEach { option ->
            val active = option == shownValue
            DropdownMenuItem(
              text = {
                Surface(
                  modifier = Modifier.fillMaxWidth(),
                  shape = MaterialTheme.shapes.medium,
                  color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                ) {
                  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                      if (active) "● ${displayFor(option)}" else displayFor(option),
                      fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                      color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    descriptionFor(option).takeIf { it.isNotBlank() }?.let { desc ->
                      Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        maxLines = 2,
                      )
                    }
                  }
                }
              },
              onClick = {
                onValueChange(option)
                expanded = false
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MihomoGeneralTab(
  profile: String,
  tunText: String,
  onTunChange: (String) -> Unit,
  mixedPortText: String,
  onMixedPortChange: (String) -> Unit,
  logLevel: String,
  onLogLevelChange: (String) -> Unit,
  tun2socksLogLevel: String,
  onTun2SocksLogLevelChange: (String) -> Unit,
  tunValid: Boolean,
  tunConflict: Boolean,
  portValid: Boolean,
  portConflict: Boolean,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.mihomo_general_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.mihomo_autosave_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )
      OutlinedTextField(
        value = profile,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        readOnly = true,
        label = { Text(stringResource(R.string.profile_name_label)) },
        supportingText = { Text(stringResource(R.string.mihomo_profile_name_readonly_hint)) },
      )
      OutlinedTextField(
        value = tunText,
        onValueChange = onTunChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.mihomo_tun_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        singleLine = true,
        isError = tunText.isNotBlank() && !tunValid,
        supportingText = { Text(stringResource(R.string.mihomo_tun_hint)) },
      )
      if (tunText.isNotBlank() && !isValidMihomoTun(tunText)) {
        Text(stringResource(R.string.mihomo_tun_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
      if (tunConflict) {
        Text(stringResource(R.string.vpn_tun_name_in_use), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
      OutlinedTextField(
        value = mixedPortText,
        onValueChange = onMixedPortChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.mihomo_mixed_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        isError = mixedPortText.isNotBlank() && !portValid,
        supportingText = { Text(stringResource(R.string.mihomo_mixed_port_hint)) },
      )
      if (mixedPortText.isBlank() || !isValidMihomoPort(mixedPortText)) {
        Text(stringResource(R.string.mihomo_mixed_port_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
      if (portConflict) {
        Text(stringResource(R.string.mihomo_port_conflict_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }

      Text(stringResource(R.string.mihomo_log_level_label), style = MaterialTheme.typography.labelLarge)
      ChipRow(options = mihomoLogLevels, selected = logLevel, onSelected = onLogLevelChange)
      Text(stringResource(R.string.mihomo_tun2socks_loglevel_label), style = MaterialTheme.typography.labelLarge)
      ChipRow(options = mihomoTun2SocksLogLevels, selected = tun2socksLogLevel, onSelected = onTun2SocksLogLevelChange)
      Text(
        stringResource(R.string.mihomo_port_protection_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRow(options: List<String>, selected: String, onSelected: (String) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    options.forEach { value ->
      FilterChip(
        selected = selected == value,
        onClick = { onSelected(value) },
        label = { Text(value) },
      )
    }
  }
}

@Composable
private fun MihomoYamlTab(
  profile: String,
  yamlText: String,
  onYamlChange: (String) -> Unit,
  saving: Boolean,
  hasChanges: Boolean,
  onSave: () -> Unit,
  onRestoreSample: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.mihomo_yaml_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.mihomo_yaml_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )
      OutlinedTextField(
        value = yamlText,
        onValueChange = onYamlChange,
        modifier = Modifier.fillMaxWidth().height(420.dp),
        label = { Text("config.yaml") },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        minLines = 16,
      )
      Text(
        stringResource(R.string.mihomo_managed_fields_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )
      val managedFields = remember(yamlText) { findMihomoManagedTopLevelKeys(yamlText) }
      if (managedFields.isNotEmpty()) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.38f),
          shape = MaterialTheme.shapes.medium,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.24f)),
        ) {
          Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              stringResource(R.string.mihomo_managed_fields_found, managedFields.joinToString(", ")),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { onYamlChange(removeMihomoTopLevelKeys(yamlText, managedFields)) }, modifier = Modifier.fillMaxWidth()) {
              Text(stringResource(R.string.mihomo_remove_managed_fields))
            }
          }
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = !saving && hasChanges, modifier = Modifier.weight(1f)) {
          Text(if (saving) "..." else stringResource(R.string.action_save))
        }
        OutlinedButton(onClick = onRestoreSample, enabled = !saving, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Restore, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.mihomo_restore_sample))
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MihomoSectionTab(
  title: String,
  desc: String,
  yamlText: String,
  section: String,
  templates: List<String>,
  templateProvider: (String) -> String,
  onSaveSection: (String) -> Unit,
) {
  val initialSectionText = remember(yamlText, section) { extractTopLevelYamlSection(yamlText, section) }
  var sectionText by remember(initialSectionText, section) { mutableStateOf(initialSectionText) }
  val hasChanges = sectionText != initialSectionText
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        templates.take(4).forEach { type ->
          FilterChip(
            selected = false,
            onClick = { sectionText = appendToYamlSection(sectionText, templateProvider(type)) },
            label = { Text(type) },
          )
        }
      }
      if (templates.size > 4) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          templates.drop(4).forEach { type ->
            FilterChip(
              selected = false,
              onClick = { sectionText = appendToYamlSection(sectionText, templateProvider(type)) },
              label = { Text(type) },
            )
          }
        }
      }
      OutlinedTextField(
        value = sectionText,
        onValueChange = { sectionText = it },
        modifier = Modifier.fillMaxWidth().height(360.dp),
        label = { Text(section) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        minLines = 12,
      )
      Button(onClick = { onSaveSection(sectionText) }, enabled = hasChanges, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_save))
      }
    }
  }
}

@Composable
private fun MihomoDashboardTab(
  profile: String,
  yamlText: String,
  usedControllerPorts: Set<Int>,
  onSaveYaml: (String) -> Unit,
) {
  val currentUrl = remember(yamlText, profile) { extractTopLevelScalar(yamlText, "external-ui-url") }
  val initialPreset = remember(currentUrl) { mihomoDashboardPresets.firstOrNull { it.url == currentUrl } }
  val fallbackController = remember(usedControllerPorts) { mihomoDefaultController(nextFreeMihomoControllerPort(usedControllerPorts)) }
  val initialController = remember(yamlText, profile, fallbackController) { extractTopLevelScalar(yamlText, "external-controller").ifBlank { fallbackController } }
  val initialSecret = remember(yamlText, profile) { extractTopLevelScalar(yamlText, "secret") }
  val initialDashboardUrl = remember(currentUrl) { currentUrl.ifBlank { mihomoDashboardPresets.first().url } }
  var controller by remember(yamlText, profile, fallbackController) { mutableStateOf(initialController) }
  var secret by remember(yamlText, profile) { mutableStateOf(initialSecret) }
  var selectedPreset by remember(yamlText, profile) { mutableStateOf(initialPreset) }
  var customUrl by remember(yamlText, profile) { mutableStateOf(if (initialPreset == null) currentUrl else "") }
  var presetMenuExpanded by remember { mutableStateOf(false) }
  val customTitle = stringResource(R.string.mihomo_dashboard_custom)
  val selectedTitle = selectedPreset?.title ?: customTitle
  val finalDashboardUrl = selectedPreset?.url ?: customUrl.trim()
  val finalController = controller.ifBlank { fallbackController }
  val controllerPort = remember(finalController) { parseMihomoControllerPort(finalController) }
  val controllerPortConflict = controllerPort != null && controllerPort in usedControllerPorts
  val controllerValid = controllerPort != null && !controllerPortConflict
  val dashboardYaml = remember(yamlText, finalController, secret, finalDashboardUrl, profile) {
    replaceTopLevelScalars(
      yamlText,
      linkedMapOf(
        "external-controller" to finalController,
        "secret" to yamlQuote(secret),
        "external-ui" to yamlQuote(defaultMihomoExternalUiPath(profile)),
        "external-ui-url" to yamlQuote(finalDashboardUrl.ifBlank { mihomoDashboardPresets.first().url }),
      ),
    )
  }
  val hasChanges = listOf(
    finalController,
    secret,
    finalDashboardUrl.ifBlank { mihomoDashboardPresets.first().url },
    defaultMihomoExternalUiPath(profile),
  ) != listOf(
    initialController,
    initialSecret,
    initialDashboardUrl,
    defaultMihomoExternalUiPath(profile),
  )

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.mihomo_dashboard_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.mihomo_dashboard_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )
      OutlinedTextField(
        value = controller,
        onValueChange = { controller = it.trim().take(64) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("external-controller") },
        singleLine = true,
        isError = controller.isNotBlank() && !controllerValid,
        supportingText = {
          Text(
            when {
              controllerPortConflict -> stringResource(R.string.mihomo_dashboard_controller_port_conflict)
              controllerPort == null -> stringResource(R.string.mihomo_dashboard_controller_invalid)
              else -> stringResource(R.string.mihomo_dashboard_controller_hint)
            }
          )
        },
      )
      OutlinedTextField(
        value = secret,
        onValueChange = { secret = it.take(120) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("secret") },
        singleLine = true,
      )

      Text(stringResource(R.string.mihomo_dashboard_presets), style = MaterialTheme.typography.labelLarge)
      Box {
        FilledTonalButton(onClick = { presetMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
          Text(selectedTitle, maxLines = 1)
          Spacer(Modifier.weight(1f))
          Text("⌄")
        }
        DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
          mihomoDashboardPresets.forEach { preset ->
            DropdownMenuItem(
              text = { Text(preset.title) },
              onClick = {
                selectedPreset = preset
                presetMenuExpanded = false
              },
            )
          }
          DropdownMenuItem(
            text = { Text(customTitle) },
            onClick = {
              selectedPreset = null
              presetMenuExpanded = false
            },
          )
        }
      }

      if (selectedPreset == null) {
        OutlinedTextField(
          value = customUrl,
          onValueChange = { customUrl = it.trim().take(260) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.mihomo_dashboard_custom_url)) },
          singleLine = false,
        )
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
      ) {
        Text(
          stringResource(R.string.mihomo_dashboard_path_fixed_hint, defaultMihomoExternalUiPath(profile)),
          modifier = Modifier.fillMaxWidth().padding(10.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }

      Button(
        onClick = {
          onSaveYaml(dashboardYaml)
        },
        enabled = controllerValid && hasChanges,
        modifier = Modifier.fillMaxWidth(),
      ) { Text(stringResource(R.string.action_save)) }
      Text(
        stringResource(R.string.mihomo_dashboard_local_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )
    }
  }
}

@Composable
private fun MihomoProxiesBuilderTab(
  yamlText: String,
  onSaveYaml: (String) -> Unit,
  onQueuePendingChange: (String, String) -> Unit,
) {
  val sectionText = remember(yamlText) { extractTopLevelYamlSection(yamlText, "proxies") }
  var blocks by remember(sectionText) { mutableStateOf(parseYamlListBlocks(sectionText).map { it.raw }) }
  var editIndex by remember { mutableStateOf<Int?>(null) }
  var showAdd by remember { mutableStateOf(false) }
  var lastSavedSection by remember(sectionText) { mutableStateOf(sectionText.trimEnd()) }
  val parsed = remember(blocks) { parseYamlListBlocks(buildYamlListSection("proxies", blocks)) }
  val serializedSection = remember(blocks) { buildYamlListSection("proxies", blocks).trimEnd() }
  val currentYamlWithProxiesSnapshot = remember(yamlText, blocks) {
    replaceTopLevelYamlSection(yamlText, "proxies", buildYamlListSection("proxies", blocks))
  }
  val defaultGroupProxyNames = remember(currentYamlWithProxiesSnapshot) {
    parseYamlListBlocks(extractTopLevelYamlSection(currentYamlWithProxiesSnapshot, "proxy-groups"))
      .map { readMihomoGroupDraft(it.raw) }
      .firstOrNull { it.name == "Proxy" }
      ?.proxies
      ?.toSet()
      .orEmpty()
  }

  fun currentYamlWithProxies(): String = currentYamlWithProxiesSnapshot

  LaunchedEffect(serializedSection) {
    if (serializedSection == lastSavedSection.trimEnd()) return@LaunchedEffect
    delay(MIHOMO_AUTOSAVE_DELAY_MS)
    onSaveYaml(currentYamlWithProxies())
    lastSavedSection = serializedSection
  }

  if (showAdd) {
    MihomoAddProxyDialog(
      onDismiss = { showAdd = false },
      onAdd = { presetYaml ->
        val newBlocks = blocks + presetYaml
        val proxyName = readMihomoBlockScalar(presetYaml, "name")
          .ifBlank { parseYamlListBlocks("proxies:\n$presetYaml").firstOrNull()?.name.orEmpty() }
        val withProxy = replaceTopLevelYamlSection(yamlText, "proxies", buildYamlListSection("proxies", newBlocks))
        if (proxyName.isNotBlank()) {
          onQueuePendingChange(proxyName, addMihomoProxyToDefaultGroup(withProxy, proxyName))
        }
        blocks = newBlocks
        showAdd = false
      },
    )
  }
  editIndex?.let { index ->
    MihomoProxySmartEditorDialog(
      initialText = blocks.getOrNull(index).orEmpty(),
      onDismiss = { editIndex = null },
      onSave = { updated ->
        blocks = blocks.toMutableList().also { if (index in it.indices) it[index] = updated.trimEnd() }
        editIndex = null
      },
    )
  }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.mihomo_proxies_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.mihomo_proxies_builder_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
          }
          FilledTonalButton(onClick = { showAdd = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_add))
          }
        }
        Text(
          stringResource(R.string.mihomo_builder_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
        )
      }
    }
    if (parsed.isEmpty()) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f))) {
        Text(stringResource(R.string.mihomo_no_proxy_blocks), modifier = Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      }
    }
    if (parsed.isNotEmpty()) {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        itemsIndexed(
          items = parsed,
          key = { index, item -> "proxy:${item.name}:${item.type}:$index" },
          contentType = { _, _ -> "mihomo_proxy_card" },
        ) { i, item ->
          MihomoBuilderItemCard(
            title = item.name,
            subtitle = item.summary,
            raw = item.raw,
            type = item.type,
            canMoveUp = i > 0,
            canMoveDown = i < parsed.lastIndex,
            onEdit = { editIndex = i },
            onDuplicate = { blocks = blocks.toMutableList().also { it.add(i + 1, item.raw) } },
            onDelete = { blocks = blocks.toMutableList().also { it.removeAt(i) } },
            onMoveUp = { blocks = blocks.toMutableList().also { java.util.Collections.swap(it, i, i - 1) } },
            onMoveDown = { blocks = blocks.toMutableList().also { java.util.Collections.swap(it, i, i + 1) } },
            extraActionLabel = if (item.name.isNotBlank() && item.name != "Unnamed" && item.name !in defaultGroupProxyNames) stringResource(R.string.mihomo_add_to_proxy_group) else null,
            onExtraAction = if (item.name.isNotBlank() && item.name != "Unnamed" && item.name !in defaultGroupProxyNames) {
              {
                onSaveYaml(addMihomoProxyToDefaultGroup(currentYamlWithProxies(), item.name))
                lastSavedSection = serializedSection
              }
            } else null,
          )
        }
      }
    }
  }
}

@Composable
private fun MihomoAddProxyDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var category by remember { mutableStateOf(mihomoProxyPresets.first().category) }
  val categories = remember { mihomoProxyPresets.map { it.category }.distinct() }
  val presetsForCategory = remember(category) { mihomoProxyPresets.filter { it.category == category } }
  var selectedTitle by remember { mutableStateOf(presetsForCategory.first().title) }
  val selected = presetsForCategory.firstOrNull { it.title == selectedTitle } ?: presetsForCategory.first()

  var rawText by remember { mutableStateOf(selected.yaml) }
  var name by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "name").ifBlank { selected.title.uppercase(Locale.ROOT).replace(" ", "-").replace("/", "-") }) }
  var type by remember { mutableStateOf(selected.type) }
  var server by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "server")) }
  var port by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "port")) }
  var username by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "username")) }
  var secret by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, mihomoProxyAuthKey(selected.type))) }
  var uuid by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "uuid")) }
  var cipher by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "cipher")) }
  var udp by remember { mutableStateOf(readMihomoBlockBool(selected.yaml, "udp")) }
  var tls by remember { mutableStateOf(readMihomoBlockBool(selected.yaml, "tls")) }
  var skipCert by remember { mutableStateOf(readMihomoBlockBool(selected.yaml, "skip-cert-verify")) }
  var serverName by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, mihomoProxyServerNameKey(selected.type))) }
  var network by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "network").ifBlank { "tcp" }) }
  var flow by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "flow")) }
  var packetEncoding by remember { mutableStateOf(readMihomoBlockScalar(selected.yaml, "packet-encoding")) }
  var wsPath by remember { mutableStateOf(readMihomoNestedScalar(selected.yaml, "ws-opts", "path")) }
  var wsHost by remember { mutableStateOf(readMihomoWsHost(selected.yaml)) }
  var grpcService by remember { mutableStateOf(readMihomoNestedScalar(selected.yaml, "grpc-opts", "grpc-service-name")) }
  var realityPublicKey by remember { mutableStateOf(readMihomoNestedScalar(selected.yaml, "reality-opts", "public-key")) }
  var realityShortId by remember { mutableStateOf(readMihomoNestedScalar(selected.yaml, "reality-opts", "short-id")) }

  LaunchedEffect(category) {
    val first = mihomoProxyPresets.first { it.category == category }
    selectedTitle = first.title
  }
  LaunchedEffect(selectedTitle, category) {
    val preset = mihomoProxyPresets.firstOrNull { it.category == category && it.title == selectedTitle } ?: return@LaunchedEffect
    rawText = preset.yaml
    type = preset.type
    name = readMihomoBlockScalar(preset.yaml, "name").ifBlank { preset.title.uppercase(Locale.ROOT).replace(" ", "-").replace("/", "-") }
    server = readMihomoBlockScalar(preset.yaml, "server")
    port = readMihomoBlockScalar(preset.yaml, "port")
    username = readMihomoBlockScalar(preset.yaml, "username")
    secret = readMihomoBlockScalar(preset.yaml, mihomoProxyAuthKey(preset.type))
    uuid = readMihomoBlockScalar(preset.yaml, "uuid")
    cipher = readMihomoBlockScalar(preset.yaml, "cipher")
    udp = readMihomoBlockBool(preset.yaml, "udp")
    tls = readMihomoBlockBool(preset.yaml, "tls")
    skipCert = readMihomoBlockBool(preset.yaml, "skip-cert-verify")
    serverName = readMihomoBlockScalar(preset.yaml, mihomoProxyServerNameKey(preset.type))
    network = readMihomoBlockScalar(preset.yaml, "network").ifBlank { "tcp" }
    flow = readMihomoBlockScalar(preset.yaml, "flow")
    packetEncoding = readMihomoBlockScalar(preset.yaml, "packet-encoding")
    wsPath = readMihomoNestedScalar(preset.yaml, "ws-opts", "path")
    wsHost = readMihomoWsHost(preset.yaml)
    grpcService = readMihomoNestedScalar(preset.yaml, "grpc-opts", "grpc-service-name")
    realityPublicKey = readMihomoNestedScalar(preset.yaml, "reality-opts", "public-key")
    realityShortId = readMihomoNestedScalar(preset.yaml, "reality-opts", "short-id")
  }

  val authKey = remember(type) { mihomoProxyAuthKey(type) }
  val serverNameKey = remember(type) { mihomoProxyServerNameKey(type) }
  val portInvalid = port.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } != true)
  val generatedRaw = remember(rawText, name, type, server, port, username, secret, uuid, cipher, udp, tls, skipCert, serverName, network, flow, packetEncoding, wsPath, wsHost, grpcService, realityPublicKey, realityShortId) {
    buildMihomoProxyBlockFromFields(rawText, name, type, server, port, username, secret, uuid, cipher, udp, tls, skipCert, serverName, network, flow, packetEncoding, wsPath, wsHost, grpcService, realityPublicKey, realityShortId)
  }
  var previewRaw by remember { mutableStateOf(generatedRaw) }
  LaunchedEffect(generatedRaw) {
    delay(220)
    previewRaw = generatedRaw
  }
  val categoryDisplay = mapOf(
    "Basic" to stringResource(R.string.mihomo_proxy_category_basic),
    "Shadowsocks" to stringResource(R.string.mihomo_proxy_category_shadowsocks),
    "Xray-like" to stringResource(R.string.mihomo_proxy_category_xray),
    "QUIC / modern" to stringResource(R.string.mihomo_proxy_category_quic),
    "Other" to stringResource(R.string.mihomo_proxy_category_other),
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.mihomo_add_proxy_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        MihomoDropdownSelectorCard(
          label = stringResource(R.string.mihomo_proxy_category_label),
          value = category,
          options = categories,
          onValueChange = { category = it },
          displayFor = { categoryDisplay[it] ?: it },
        )
        MihomoDropdownSelectorCard(
          label = stringResource(R.string.mihomo_proxy_type_label),
          value = selectedTitle,
          options = presetsForCategory.map { it.title },
          onValueChange = { selectedTitle = it },
          descriptionFor = { title -> presetsForCategory.firstOrNull { it.title == title }?.description.orEmpty() },
        )
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        ) {
          Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.mihomo_section_basic), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, label = { Text(stringResource(R.string.mihomo_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (mihomoProxyNeedsServer(type)) {
              OutlinedTextField(value = server, onValueChange = { server = it.trim().take(180) }, label = { Text(stringResource(R.string.mihomo_field_server)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
              OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.mihomo_field_port)) },
                singleLine = true,
                isError = portInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
              )
              if (portInvalid) Text(stringResource(R.string.mihomo_port_range_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (type in setOf("http", "socks5", "mieru", "ssh", "trusttunnel")) {
              Text(stringResource(R.string.mihomo_section_auth), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
              OutlinedTextField(value = username, onValueChange = { username = it.take(120) }, label = { Text(stringResource(R.string.mihomo_field_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (type !in setOf("direct", "dns", "wireguard", "masque")) {
              OutlinedTextField(value = secret, onValueChange = { secret = it.take(220) }, label = { Text(mihomoAuthFieldLabel(authKey)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (mihomoProxyUsesUuid(type)) OutlinedTextField(value = uuid, onValueChange = { uuid = it.trim().take(64) }, label = { Text(stringResource(R.string.mihomo_field_uuid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (mihomoProxyUsesCipher(type)) OutlinedTextField(value = cipher, onValueChange = { cipher = it.trim().take(48) }, label = { Text(stringResource(R.string.mihomo_field_cipher)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text(stringResource(R.string.mihomo_section_options), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(selected = udp, onClick = { udp = !udp }, label = { Text("udp") })
              if (mihomoProxySupportsTls(type)) FilterChip(selected = tls, onClick = { tls = !tls }, label = { Text("tls") })
              if (mihomoProxySupportsTls(type)) FilterChip(selected = skipCert, onClick = { skipCert = !skipCert }, label = { Text("skip-cert-verify") })
            }
            if (mihomoProxySupportsTls(type)) {
              OutlinedTextField(value = serverName, onValueChange = { serverName = it.trim().take(180) }, label = { Text(mihomoServerNameFieldLabel(serverNameKey)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (mihomoProxySupportsTransport(type)) {
              MihomoDropdownSelectorCard(
                label = stringResource(R.string.mihomo_field_network),
                value = network,
                options = listOf("tcp", "ws", "grpc"),
                onValueChange = { network = it },
              )
              if (network == "ws") {
                OutlinedTextField(value = wsPath, onValueChange = { wsPath = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_ws_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = wsHost, onValueChange = { wsHost = it.trim().take(180) }, label = { Text(stringResource(R.string.mihomo_field_ws_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
              }
              if (network == "grpc") {
                OutlinedTextField(value = grpcService, onValueChange = { grpcService = it.take(120) }, label = { Text(stringResource(R.string.mihomo_field_grpc_service)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
              }
            }
            if (type in setOf("vless", "trojan")) {
              Text(stringResource(R.string.mihomo_section_reality_vision), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
              OutlinedTextField(value = flow, onValueChange = { flow = it.trim().take(64) }, label = { Text(stringResource(R.string.mihomo_field_flow)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
              OutlinedTextField(value = packetEncoding, onValueChange = { packetEncoding = it.trim().take(32) }, label = { Text(stringResource(R.string.mihomo_field_packet_encoding)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
              OutlinedTextField(value = realityPublicKey, onValueChange = { realityPublicKey = it.trim().take(120) }, label = { Text(stringResource(R.string.mihomo_field_reality_public_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
              OutlinedTextField(value = realityShortId, onValueChange = { realityShortId = it.trim().take(32) }, label = { Text(stringResource(R.string.mihomo_field_reality_short_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        Text(stringResource(R.string.mihomo_live_yaml_preview_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
              value = previewRaw,
              onValueChange = { updated ->
                previewRaw = updated
                rawText = updated
              },
              modifier = Modifier.fillMaxWidth().height(180.dp),
              textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        enabled = name.isNotBlank() && type.isNotBlank() && !portInvalid,
        onClick = {
          onAdd(generatedRaw.trimEnd())
        },
      ) { Text(stringResource(R.string.action_add)) }
    },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun MihomoGroupsBuilderTab(yamlText: String, onSaveYaml: (String) -> Unit) {
  val sectionText = remember(yamlText) { extractTopLevelYamlSection(yamlText, "proxy-groups") }
  val policyNames = remember(yamlText) { mihomoGroupPolicyNamesFromYaml(yamlText) }
  val providerNames = remember(yamlText) { proxyProviderNamesFromYaml(yamlText) }
  var blocks by remember(sectionText) { mutableStateOf(parseYamlListBlocks(sectionText).map { sanitizeMihomoGroupRaw(it.raw) }) }
  var editIndex by remember { mutableStateOf<Int?>(null) }
  var showAdd by remember { mutableStateOf(false) }
  var lastSavedSection by remember(sectionText) { mutableStateOf(sectionText.trimEnd()) }
  val parsed = remember(blocks) { parseYamlListBlocks(buildYamlListSection("proxy-groups", blocks)) }
  val serializedSection = remember(blocks) { buildYamlListSection("proxy-groups", blocks).trimEnd() }

  LaunchedEffect(serializedSection) {
    if (serializedSection == lastSavedSection.trimEnd()) return@LaunchedEffect
    delay(MIHOMO_AUTOSAVE_DELAY_MS)
    onSaveYaml(replaceTopLevelYamlSection(yamlText, "proxy-groups", buildYamlListSection("proxy-groups", blocks)))
    lastSavedSection = serializedSection
  }

  if (showAdd) {
    MihomoAddGroupDialog(policyNames = policyNames, providerNames = providerNames, onDismiss = { showAdd = false }, onAdd = { raw -> blocks = blocks + raw; showAdd = false })
  }
  editIndex?.let { index ->
    MihomoEditGroupDialog(
      initialRaw = blocks.getOrNull(index).orEmpty(),
      policyNames = policyNames,
      providerNames = providerNames,
      onDismiss = { editIndex = null },
      onSave = { updated -> blocks = blocks.toMutableList().also { if (index in it.indices) it[index] = updated.trimEnd() }; editIndex = null },
    )
  }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(stringResource(R.string.mihomo_groups_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(stringResource(R.string.mihomo_groups_builder_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
          Text(stringResource(R.string.mihomo_builder_autosave_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
        }
        FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_add)) }
      }
    }
    if (parsed.isNotEmpty()) {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        itemsIndexed(
          items = parsed,
          key = { index, item -> "group:${item.name}:${item.type}:$index" },
          contentType = { _, _ -> "mihomo_group_card" },
        ) { i, item ->
          MihomoBuilderItemCard(
            title = item.name,
            subtitle = item.summary,
            raw = item.raw,
            type = item.type,
            canMoveUp = i > 0,
            canMoveDown = i < parsed.lastIndex,
            onEdit = { editIndex = i },
            onDuplicate = { blocks = blocks.toMutableList().also { it.add(i + 1, item.raw) } },
            onDelete = { blocks = blocks.toMutableList().also { it.removeAt(i) } },
            onMoveUp = { blocks = blocks.toMutableList().also { java.util.Collections.swap(it, i, i - 1) } },
            onMoveDown = { blocks = blocks.toMutableList().also { java.util.Collections.swap(it, i, i + 1) } },
          )
        }
      }
    }
  }
}

@Composable
private fun MihomoAddGroupDialog(policyNames: List<String>, providerNames: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var name by remember { mutableStateOf("Proxy") }
  var type by remember { mutableStateOf("select") }
  var selectedProxies by remember(policyNames) { mutableStateOf(emptyList<String>()) }
  var selectedUse by remember(providerNames) { mutableStateOf(emptyList<String>()) }
  var includeAll by remember { mutableStateOf(false) }
  var includeAllProxies by remember { mutableStateOf(false) }
  var includeAllProviders by remember { mutableStateOf(false) }
  var filter by remember { mutableStateOf("") }
  var excludeFilter by remember { mutableStateOf("") }
  var excludeType by remember { mutableStateOf("") }
  var url by remember { mutableStateOf("") }
  var interval by remember { mutableStateOf("") }
  var lazy by remember { mutableStateOf(false) }
  var timeout by remember { mutableStateOf("") }
  var maxFailedTimes by remember { mutableStateOf("") }
  var expectedStatus by remember { mutableStateOf("") }
  var disableUdp by remember { mutableStateOf(false) }
  var hidden by remember { mutableStateOf(false) }
  var icon by remember { mutableStateOf("") }
  var strategy by remember { mutableStateOf("") }
  val types = listOf("select", "url-test", "fallback", "load-balance", "relay")
  val visiblePolicies = remember(policyNames, selectedProxies, name) { (selectedProxies + policyNames).map { it.trim() }.filter { it.isNotBlank() && it != name }.distinct() }
  val visibleProviders = remember(providerNames, selectedUse) { (selectedUse + providerNames).map { it.trim() }.filter { it.isNotBlank() }.distinct() }
  val generatedRaw = remember(name, type, selectedProxies, selectedUse, includeAll, includeAllProxies, includeAllProviders, filter, excludeFilter, excludeType, url, interval, lazy, timeout, maxFailedTimes, expectedStatus, disableUdp, hidden, icon, strategy) {
    buildMihomoGroupFromDraft(
      "",
      MihomoGroupDraft(
        name = name.ifBlank { "Proxy" },
        type = type,
        proxies = selectedProxies.filter { it != name },
        use = selectedUse,
        includeAll = includeAll,
        includeAllProxies = includeAllProxies,
        includeAllProviders = includeAllProviders,
        filter = filter,
        excludeFilter = excludeFilter,
        excludeType = excludeType,
        url = url,
        interval = interval,
        lazy = lazy,
        timeout = timeout,
        maxFailedTimes = maxFailedTimes,
        expectedStatus = expectedStatus,
        disableUdp = disableUdp,
        hidden = hidden,
        icon = icon,
        strategy = strategy,
      )
    )
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.mihomo_add_group_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(48) }, label = { Text(stringResource(R.string.mihomo_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MihomoDropdownSelectorCard(
          label = stringResource(R.string.mihomo_field_type),
          value = type,
          options = types,
          onValueChange = { type = it },
        )
        Text(stringResource(R.string.mihomo_group_policies_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (visiblePolicies.isEmpty()) {
          Text(
            stringResource(R.string.mihomo_no_available_group_policies),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
          )
        } else {
          visiblePolicies.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              row.forEach { p ->
                FilterChip(
                  selected = p in selectedProxies,
                  onClick = { selectedProxies = if (p in selectedProxies) selectedProxies.filter { it != p } else (listOf(p) + selectedProxies) },
                  label = { Text(p, maxLines = 1) },
                )
              }
            }
          }
        }
        Text(stringResource(R.string.mihomo_group_use_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (visibleProviders.isEmpty()) {
          Text(
            stringResource(R.string.mihomo_no_available_providers_for_use),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
          )
        } else {
          visibleProviders.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              row.forEach { provider ->
                FilterChip(
                  selected = provider in selectedUse,
                  onClick = { selectedUse = if (provider in selectedUse) selectedUse.filter { it != provider } else (listOf(provider) + selectedUse) },
                  label = { Text(provider, maxLines = 1) },
                )
              }
            }
          }
        }
        Text(stringResource(R.string.mihomo_group_include_filter_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = includeAll, onClick = { includeAll = !includeAll }, label = { Text("include-all") })
          FilterChip(selected = includeAllProxies, onClick = { includeAllProxies = !includeAllProxies }, label = { Text("include-all-proxies") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = includeAllProviders, onClick = { includeAllProviders = !includeAllProviders }, label = { Text("include-all-providers") })
          FilterChip(selected = disableUdp, onClick = { disableUdp = !disableUdp }, label = { Text("disable-udp") })
        }
        OutlinedTextField(value = filter, onValueChange = { filter = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = excludeFilter, onValueChange = { excludeFilter = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_exclude_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = excludeType, onValueChange = { excludeType = it.take(120) }, label = { Text(stringResource(R.string.mihomo_field_exclude_type)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.mihomo_group_health_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = url, onValueChange = { url = it.take(220) }, label = { Text(stringResource(R.string.mihomo_field_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = interval, onValueChange = { interval = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_interval)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = lazy, onClick = { lazy = !lazy }, label = { Text("lazy") })
          FilterChip(selected = hidden, onClick = { hidden = !hidden }, label = { Text("hidden") })
        }
        OutlinedTextField(value = timeout, onValueChange = { timeout = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_timeout)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = maxFailedTimes, onValueChange = { maxFailedTimes = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.mihomo_field_max_failed_times)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = expectedStatus, onValueChange = { expectedStatus = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_expected_status)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        if (type == "load-balance") {
          OutlinedTextField(value = strategy, onValueChange = { strategy = it.take(64) }, label = { Text(stringResource(R.string.mihomo_field_strategy)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(value = icon, onValueChange = { icon = it.take(220) }, label = { Text(stringResource(R.string.mihomo_field_icon)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.mihomo_live_yaml_preview_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
          value = generatedRaw,
          onValueChange = {},
          readOnly = true,
          modifier = Modifier.fillMaxWidth().height(180.dp),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
      }
    },
    confirmButton = { Button(onClick = { onAdd(generatedRaw) }) { Text(stringResource(R.string.action_add)) } },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}


@Composable
private fun MihomoEditGroupDialog(initialRaw: String, policyNames: List<String>, providerNames: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  val initial = remember(initialRaw) { readMihomoGroupDraft(initialRaw) }
  var name by remember(initialRaw) { mutableStateOf(initial.name) }
  var type by remember(initialRaw) { mutableStateOf(initial.type) }
  var selectedProxies by remember(initialRaw) { mutableStateOf(initial.proxies.distinct()) }
  var selectedUse by remember(initialRaw) { mutableStateOf(initial.use.distinct()) }
  var includeAll by remember(initialRaw) { mutableStateOf(initial.includeAll) }
  var includeAllProxies by remember(initialRaw) { mutableStateOf(initial.includeAllProxies) }
  var includeAllProviders by remember(initialRaw) { mutableStateOf(initial.includeAllProviders) }
  var filter by remember(initialRaw) { mutableStateOf(initial.filter) }
  var excludeFilter by remember(initialRaw) { mutableStateOf(initial.excludeFilter) }
  var excludeType by remember(initialRaw) { mutableStateOf(initial.excludeType) }
  var url by remember(initialRaw) { mutableStateOf(initial.url) }
  var interval by remember(initialRaw) { mutableStateOf(initial.interval) }
  var lazy by remember(initialRaw) { mutableStateOf(initial.lazy) }
  var timeout by remember(initialRaw) { mutableStateOf(initial.timeout) }
  var maxFailedTimes by remember(initialRaw) { mutableStateOf(initial.maxFailedTimes) }
  var expectedStatus by remember(initialRaw) { mutableStateOf(initial.expectedStatus) }
  var disableUdp by remember(initialRaw) { mutableStateOf(initial.disableUdp) }
  var hidden by remember(initialRaw) { mutableStateOf(initial.hidden) }
  var icon by remember(initialRaw) { mutableStateOf(initial.icon) }
  var strategy by remember(initialRaw) { mutableStateOf(initial.strategy) }
  var raw by remember(initialRaw) { mutableStateOf(initialRaw) }
  val types = listOf("select", "url-test", "fallback", "load-balance", "relay")
  val visiblePolicies = remember(policyNames, selectedProxies, name) { (selectedProxies + policyNames).map { it.trim() }.filter { it.isNotBlank() && it != name }.distinct() }
  val visibleProviders = remember(providerNames, selectedUse) { (selectedUse + providerNames).map { it.trim() }.filter { it.isNotBlank() }.distinct() }
  val currentDraft = MihomoGroupDraft(
    name = name.ifBlank { "Proxy" },
    type = type.ifBlank { "select" },
    proxies = selectedProxies.filter { it != name }.distinct(),
    use = selectedUse.distinct(),
    includeAll = includeAll,
    includeAllProxies = includeAllProxies,
    includeAllProviders = includeAllProviders,
    filter = filter,
    excludeFilter = excludeFilter,
    excludeType = excludeType,
    url = url,
    interval = interval,
    lazy = lazy,
    timeout = timeout,
    maxFailedTimes = maxFailedTimes,
    expectedStatus = expectedStatus,
    disableUdp = disableUdp,
    hidden = hidden,
    icon = icon,
    strategy = strategy,
  )
  val initialDraft = remember(initialRaw) {
    initial.copy(
      proxies = initial.proxies.filter { it != initial.name }.distinct(),
      use = initial.use.distinct(),
    )
  }
  val generatedRaw = remember(raw, currentDraft) {
    buildMihomoGroupFromDraft(raw, currentDraft)
  }
  val hasChanges = currentDraft != initialDraft
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.mihomo_edit_group_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(48) }, label = { Text(stringResource(R.string.mihomo_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MihomoDropdownSelectorCard(label = stringResource(R.string.mihomo_field_type), value = type, options = types, onValueChange = { type = it })
        Text(stringResource(R.string.mihomo_group_policies_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (visiblePolicies.isEmpty()) {
          Text(
            stringResource(R.string.mihomo_no_available_group_policies),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
          )
        } else {
          visiblePolicies.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              row.forEach { p ->
                FilterChip(
                  selected = p in selectedProxies,
                  onClick = { selectedProxies = if (p in selectedProxies) selectedProxies.filter { it != p } else (listOf(p) + selectedProxies) },
                  label = { Text(p, maxLines = 1) },
                )
              }
            }
          }
        }
        Text(stringResource(R.string.mihomo_group_use_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (visibleProviders.isEmpty()) {
          Text(
            stringResource(R.string.mihomo_no_available_providers_for_use),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
          )
        } else {
          visibleProviders.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              row.forEach { provider ->
                FilterChip(
                  selected = provider in selectedUse,
                  onClick = { selectedUse = if (provider in selectedUse) selectedUse.filter { it != provider } else (listOf(provider) + selectedUse) },
                  label = { Text(provider, maxLines = 1) },
                )
              }
            }
          }
        }
        Text(stringResource(R.string.mihomo_group_include_filter_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = includeAll, onClick = { includeAll = !includeAll }, label = { Text("include-all") })
          FilterChip(selected = includeAllProxies, onClick = { includeAllProxies = !includeAllProxies }, label = { Text("include-all-proxies") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = includeAllProviders, onClick = { includeAllProviders = !includeAllProviders }, label = { Text("include-all-providers") })
          FilterChip(selected = disableUdp, onClick = { disableUdp = !disableUdp }, label = { Text("disable-udp") })
        }
        OutlinedTextField(value = filter, onValueChange = { filter = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = excludeFilter, onValueChange = { excludeFilter = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_exclude_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = excludeType, onValueChange = { excludeType = it.take(120) }, label = { Text(stringResource(R.string.mihomo_field_exclude_type)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.mihomo_group_health_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = url, onValueChange = { url = it.take(220) }, label = { Text(stringResource(R.string.mihomo_field_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = interval, onValueChange = { interval = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_interval)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = lazy, onClick = { lazy = !lazy }, label = { Text("lazy") })
          FilterChip(selected = hidden, onClick = { hidden = !hidden }, label = { Text("hidden") })
        }
        OutlinedTextField(value = timeout, onValueChange = { timeout = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_timeout)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = maxFailedTimes, onValueChange = { maxFailedTimes = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.mihomo_field_max_failed_times)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = expectedStatus, onValueChange = { expectedStatus = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_expected_status)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        if (type == "load-balance") {
          OutlinedTextField(value = strategy, onValueChange = { strategy = it.take(64) }, label = { Text(stringResource(R.string.mihomo_field_strategy)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(value = icon, onValueChange = { icon = it.take(220) }, label = { Text(stringResource(R.string.mihomo_field_icon)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.mihomo_raw_extra_title), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
          value = generatedRaw,
          onValueChange = { updatedRaw ->
            raw = updatedRaw
            val parsedRaw = readMihomoGroupDraft(updatedRaw)
            name = parsedRaw.name
            type = parsedRaw.type
            selectedProxies = parsedRaw.proxies.distinct()
            selectedUse = parsedRaw.use.distinct()
            includeAll = parsedRaw.includeAll
            includeAllProxies = parsedRaw.includeAllProxies
            includeAllProviders = parsedRaw.includeAllProviders
            filter = parsedRaw.filter
            excludeFilter = parsedRaw.excludeFilter
            excludeType = parsedRaw.excludeType
            url = parsedRaw.url
            interval = parsedRaw.interval
            lazy = parsedRaw.lazy
            timeout = parsedRaw.timeout
            maxFailedTimes = parsedRaw.maxFailedTimes
            expectedStatus = parsedRaw.expectedStatus
            disableUdp = parsedRaw.disableUdp
            hidden = parsedRaw.hidden
            icon = parsedRaw.icon
            strategy = parsedRaw.strategy
          },
          modifier = Modifier.fillMaxWidth().height(240.dp),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
      }
    },
    confirmButton = {
      Button(onClick = { onSave(generatedRaw) }, enabled = hasChanges) {
        Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}


@Composable
private fun MihomoRulesBuilderTab(yamlText: String, onSaveYaml: (String) -> Unit) {
  val sectionText = remember(yamlText) { extractTopLevelYamlSection(yamlText, "rules") }
  var rules by remember(sectionText) {
    mutableStateOf(sectionText.lines().drop(1).map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() })
  }
  var editIndex by remember { mutableStateOf<Int?>(null) }
  var showAdd by remember { mutableStateOf(false) }
  var lastSavedSection by remember(sectionText) { mutableStateOf(sectionText.trimEnd()) }
  val serializedSection = remember(rules) { "rules:\n" + rules.joinToString("\n") { "  - $it" } + "\n" }

  LaunchedEffect(serializedSection) {
    if (serializedSection.trimEnd() == lastSavedSection.trimEnd()) return@LaunchedEffect
    delay(MIHOMO_AUTOSAVE_DELAY_MS)
    onSaveYaml(replaceTopLevelYamlSection(yamlText, "rules", serializedSection))
    lastSavedSection = serializedSection.trimEnd()
  }

  if (showAdd) MihomoAddRuleDialog(onDismiss = { showAdd = false }, onAdd = { rule -> rules = rules + rule; showAdd = false })
  editIndex?.let { idx ->
    MihomoSingleLineDialog(
      title = stringResource(R.string.mihomo_edit_rule_title),
      initialText = rules.getOrNull(idx).orEmpty(),
      onDismiss = { editIndex = null },
      onSave = { updated -> rules = rules.toMutableList().also { if (idx in it.indices) it[idx] = updated.trim() }; editIndex = null },
    )
  }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(stringResource(R.string.mihomo_rules_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(stringResource(R.string.mihomo_rules_builder_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
          Text(stringResource(R.string.mihomo_builder_autosave_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
        }
        FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_add)) }
      }
    }
    if (rules.isNotEmpty()) {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        itemsIndexed(
          items = rules,
          key = { index, rule -> "rule:${rule.hashCode()}:$index" },
          contentType = { _, _ -> "mihomo_rule_card" },
        ) { i, rule ->
          MihomoRuleCard(
            rule = rule,
            canMoveUp = i > 0,
            canMoveDown = i < rules.lastIndex,
            onEdit = { editIndex = i },
            onDelete = { rules = rules.toMutableList().also { it.removeAt(i) } },
            onMoveUp = { rules = rules.toMutableList().also { java.util.Collections.swap(it, i, i - 1) } },
            onMoveDown = { rules = rules.toMutableList().also { java.util.Collections.swap(it, i, i + 1) } },
          )
        }
      }
    }
  }
}

@Composable
private fun MihomoAddRuleDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var type by remember { mutableStateOf("MATCH") }
  var value by remember { mutableStateOf("") }
  var target by remember { mutableStateOf("Proxy") }
  var noResolve by remember { mutableStateOf(false) }
  var src by remember { mutableStateOf(false) }
  val types = listOf(
    "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-WILDCARD", "DOMAIN-REGEX", "GEOSITE",
    "IP-CIDR", "IP-CIDR6", "IP-SUFFIX", "IP-ASN", "GEOIP",
    "SRC-GEOIP", "SRC-IP-ASN", "SRC-IP-CIDR", "SRC-IP-SUFFIX",
    "DST-PORT", "SRC-PORT", "IN-PORT", "IN-TYPE", "IN-USER", "IN-NAME",
    "PROCESS-PATH", "PROCESS-PATH-WILDCARD", "PROCESS-PATH-REGEX",
    "PROCESS-NAME", "PROCESS-NAME-WILDCARD", "PROCESS-NAME-REGEX",
    "UID", "NETWORK", "DSCP", "RULE-SET", "SUB-RULE", "AND", "OR", "NOT", "MATCH",
  )
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.mihomo_add_rule_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        MihomoDropdownSelectorCard(label = stringResource(R.string.mihomo_rule_type_label), value = type, options = types, onValueChange = { type = it })
        if (type != "MATCH") OutlinedTextField(value = value, onValueChange = { value = it.take(260) }, label = { Text(stringResource(R.string.mihomo_field_value)) }, modifier = Modifier.fillMaxWidth())
        if (type != "SUB-RULE") OutlinedTextField(value = target, onValueChange = { target = it.take(64) }, label = { Text(stringResource(R.string.mihomo_field_policy)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (type in setOf("IP-CIDR", "IP-CIDR6", "IP-SUFFIX", "IP-ASN", "GEOIP", "SRC-GEOIP", "SRC-IP-ASN", "SRC-IP-CIDR", "SRC-IP-SUFFIX")) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = noResolve, onClick = { noResolve = !noResolve }, label = { Text("no-resolve") })
            FilterChip(selected = src, onClick = { src = !src }, label = { Text("src") })
          }
        }
        if (type in setOf("AND", "OR", "NOT", "SUB-RULE")) {
          Text(stringResource(R.string.mihomo_complex_rule_raw_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        }
      }
    },
    confirmButton = {
      Button(onClick = {
        val options = listOf(if (noResolve) "no-resolve" else "", if (src) "src" else "").filter { it.isNotBlank() }
        val rule = when (type) {
          "MATCH" -> "MATCH,${target.ifBlank { "Proxy" }}"
          "SUB-RULE" -> "SUB-RULE,${value.ifBlank { "example" }}"
          else -> (listOf(type, value.ifBlank { "example.com" }, target.ifBlank { "Proxy" }) + options).joinToString(",")
        }
        onAdd(rule)
      }) { Text(stringResource(R.string.action_add)) }
    },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun MihomoProvidersBuilderTab(yamlText: String, onSaveYaml: (String) -> Unit) {
  val initialProxySection = remember(yamlText) { extractTopLevelYamlSection(yamlText, "proxy-providers") }
  val initialRuleSection = remember(yamlText) { extractTopLevelYamlSection(yamlText, "rule-providers") }
  var proxyBlocks by remember(initialProxySection) { mutableStateOf(parseProviderBlocks(initialProxySection).map { it.raw }) }
  var ruleBlocks by remember(initialRuleSection) { mutableStateOf(parseProviderBlocks(initialRuleSection).map { it.raw }) }
  var editProxyIndex by remember { mutableStateOf<Int?>(null) }
  var editRuleIndex by remember { mutableStateOf<Int?>(null) }
  var addProxy by remember { mutableStateOf(false) }
  var addRule by remember { mutableStateOf(false) }
  var lastSavedProxySection by remember(initialProxySection) { mutableStateOf(initialProxySection.trimEnd()) }
  var lastSavedRuleSection by remember(initialRuleSection) { mutableStateOf(initialRuleSection.trimEnd()) }
  val proxyProviders = remember(proxyBlocks) { parseProviderBlocks(buildProviderSection("proxy-providers", proxyBlocks)) }
  val ruleProviders = remember(ruleBlocks) { parseProviderBlocks(buildProviderSection("rule-providers", ruleBlocks)) }
  val serializedProxySection = remember(proxyBlocks) { buildProviderSection("proxy-providers", proxyBlocks).trimEnd() }
  val serializedRuleSection = remember(ruleBlocks) { buildProviderSection("rule-providers", ruleBlocks).trimEnd() }

  LaunchedEffect(serializedProxySection, serializedRuleSection) {
    if (serializedProxySection == lastSavedProxySection.trimEnd() && serializedRuleSection == lastSavedRuleSection.trimEnd()) return@LaunchedEffect
    delay(MIHOMO_AUTOSAVE_DELAY_MS)
    val withProxy = replaceTopLevelYamlSection(yamlText, "proxy-providers", buildProviderSection("proxy-providers", proxyBlocks))
    onSaveYaml(replaceTopLevelYamlSection(withProxy, "rule-providers", buildProviderSection("rule-providers", ruleBlocks)))
    lastSavedProxySection = serializedProxySection
    lastSavedRuleSection = serializedRuleSection
  }

  if (addProxy) MihomoAddProviderDialog(ruleProvider = false, onDismiss = { addProxy = false }, onAdd = { raw -> proxyBlocks = proxyBlocks + raw; addProxy = false })
  if (addRule) MihomoAddProviderDialog(ruleProvider = true, onDismiss = { addRule = false }, onAdd = { raw -> ruleBlocks = ruleBlocks + raw; addRule = false })
  editProxyIndex?.let { idx -> MihomoEditProviderDialog(ruleProvider = false, initialRaw = proxyBlocks.getOrNull(idx).orEmpty(), onDismiss = { editProxyIndex = null }, onSave = { updated -> proxyBlocks = proxyBlocks.toMutableList().also { if (idx in it.indices) it[idx] = updated.trimEnd() }; editProxyIndex = null }) }
  editRuleIndex?.let { idx -> MihomoEditProviderDialog(ruleProvider = true, initialRaw = ruleBlocks.getOrNull(idx).orEmpty(), onDismiss = { editRuleIndex = null }, onSave = { updated -> ruleBlocks = ruleBlocks.toMutableList().also { if (idx in it.indices) it[idx] = updated.trimEnd() }; editRuleIndex = null }) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    MihomoProviderListCard(
      title = stringResource(R.string.mihomo_proxy_providers_title),
      providers = proxyProviders,
      onAdd = { addProxy = true },
      onEdit = { editProxyIndex = it },
      onDelete = { idx -> proxyBlocks = proxyBlocks.toMutableList().also { it.removeAt(idx) } },
    )
    MihomoProviderListCard(
      title = stringResource(R.string.mihomo_rule_providers_title),
      providers = ruleProviders,
      onAdd = { addRule = true },
      onEdit = { editRuleIndex = it },
      onDelete = { idx -> ruleBlocks = ruleBlocks.toMutableList().also { it.removeAt(idx) } },
    )
    Text(
      stringResource(R.string.mihomo_builder_autosave_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
      modifier = Modifier.padding(horizontal = 4.dp),
    )
  }
}

@Composable
private fun MihomoProviderListCard(
  title: String,
  providers: List<MihomoProviderBlock>,
  onAdd: () -> Unit,
  onEdit: (Int) -> Unit,
  onDelete: (Int) -> Unit,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FilledTonalButton(onClick = onAdd) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_add)) }
      }
      if (providers.isNotEmpty()) {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          itemsIndexed(
            items = providers,
            key = { index, provider -> "provider:${provider.name}:${provider.type}:$index" },
            contentType = { _, _ -> "mihomo_provider_card" },
          ) { idx, provider ->
            Surface(
              shape = MaterialTheme.shapes.large,
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
            ) {
              Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                  Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                      MihomoMiniBadge(provider.type)
                      if (provider.behavior.isNotBlank()) MihomoInfoChip(provider.behavior)
                      if (provider.format.isNotBlank()) MihomoInfoChip(provider.format)
                      if (provider.proxy.isNotBlank()) MihomoInfoChip("${stringResource(R.string.mihomo_field_proxy)}: ${provider.proxy}")
                      if (provider.healthCheck) MihomoInfoChip(stringResource(R.string.mihomo_field_health_check))
                    }
                    val mainLine = listOf(provider.url, provider.path).filter { it.isNotBlank() }.joinToString(" · ")
                    if (mainLine.isNotBlank()) {
                      Text(
                        mainLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                        maxLines = 2,
                      )
                    } else {
                      Text(
                        stringResource(R.string.mihomo_raw_provider),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                        maxLines = 1,
                      )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                      if (provider.interval.isNotBlank()) MihomoInfoChip("${stringResource(R.string.mihomo_field_interval)}: ${provider.interval}")
                      if (provider.sizeLimit.isNotBlank()) MihomoInfoChip("${stringResource(R.string.mihomo_field_size_limit)}: ${provider.sizeLimit}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                      if (provider.filter.isNotBlank()) MihomoInfoChip("${stringResource(R.string.mihomo_field_filter)}")
                      if (provider.excludeFilter.isNotBlank()) MihomoInfoChip("${stringResource(R.string.mihomo_field_exclude_filter)}")
                    }
                  }
                }
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.End,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  TextButton(onClick = { onEdit(idx) }) { Text(stringResource(R.string.action_edit)) }
                  TextButton(onClick = { onDelete(idx) }) { Text(stringResource(R.string.action_delete)) }
                }
              }
            }
          }
        }
      }    }
  }
}


@Composable
private fun MihomoAddProviderDialog(ruleProvider: Boolean, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var name by remember { mutableStateOf(if (ruleProvider) "reject" else "provider1") }
  var type by remember { mutableStateOf("http") }
  val types = listOf("http", "file", "inline")
  val generatedRaw = remember(name, type, ruleProvider) {
    if (ruleProvider) mihomoRuleProviderTemplate(name.ifBlank { "reject" }, type) else mihomoProxyProviderTemplate(name.ifBlank { "provider1" }, type)
  }
  var customRaw by remember(generatedRaw) { mutableStateOf(generatedRaw) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (ruleProvider) stringResource(R.string.mihomo_add_rule_provider_title) else stringResource(R.string.mihomo_add_proxy_provider_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }.take(32) }, label = { Text(stringResource(R.string.mihomo_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MihomoDropdownSelectorCard(label = stringResource(R.string.mihomo_field_type), value = type, options = types, onValueChange = { type = it })
        Text(stringResource(R.string.mihomo_live_yaml_preview_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
          value = customRaw,
          onValueChange = { customRaw = it },
          modifier = Modifier.fillMaxWidth().height(200.dp),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
      }
    },
    confirmButton = { Button(onClick = { onAdd(customRaw.trimEnd()) }) { Text(stringResource(R.string.action_add)) } },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}


@Composable
private fun MihomoEditProviderDialog(ruleProvider: Boolean, initialRaw: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  val initial = remember(initialRaw, ruleProvider) { readMihomoProviderDraft(initialRaw, ruleProvider) }
  var name by remember(initialRaw) { mutableStateOf(initial.name) }
  var type by remember(initialRaw) { mutableStateOf(initial.type) }
  var url by remember(initialRaw) { mutableStateOf(initial.url) }
  var path by remember(initialRaw) { mutableStateOf(initial.path) }
  var interval by remember(initialRaw) { mutableStateOf(initial.interval) }
  var behavior by remember(initialRaw) { mutableStateOf(initial.behavior) }
  var healthCheck by remember(initialRaw) { mutableStateOf(initial.healthCheck) }
  var format by remember(initialRaw) { mutableStateOf(initial.format) }
  var proxy by remember(initialRaw) { mutableStateOf(initial.proxy) }
  var sizeLimit by remember(initialRaw) { mutableStateOf(initial.sizeLimit) }
  var headerUserAgent by remember(initialRaw) { mutableStateOf(initial.headerUserAgent) }
  var headerAuthorization by remember(initialRaw) { mutableStateOf(initial.headerAuthorization) }
  var healthUrl by remember(initialRaw) { mutableStateOf(initial.healthUrl) }
  var healthInterval by remember(initialRaw) { mutableStateOf(initial.healthInterval) }
  var healthTimeout by remember(initialRaw) { mutableStateOf(initial.healthTimeout) }
  var healthLazy by remember(initialRaw) { mutableStateOf(initial.healthLazy) }
  var healthExpectedStatus by remember(initialRaw) { mutableStateOf(initial.healthExpectedStatus) }
  var overridePrefix by remember(initialRaw) { mutableStateOf(initial.overridePrefix) }
  var overrideSuffix by remember(initialRaw) { mutableStateOf(initial.overrideSuffix) }
  var overrideTfo by remember(initialRaw) { mutableStateOf(initial.overrideTfo) }
  var overrideMptcp by remember(initialRaw) { mutableStateOf(initial.overrideMptcp) }
  var overrideUdp by remember(initialRaw) { mutableStateOf(initial.overrideUdp) }
  var overrideUdpOverTcp by remember(initialRaw) { mutableStateOf(initial.overrideUdpOverTcp) }
  var overrideDown by remember(initialRaw) { mutableStateOf(initial.overrideDown) }
  var overrideUp by remember(initialRaw) { mutableStateOf(initial.overrideUp) }
  var overrideSkipCertVerify by remember(initialRaw) { mutableStateOf(initial.overrideSkipCertVerify) }
  var overrideDialerProxy by remember(initialRaw) { mutableStateOf(initial.overrideDialerProxy) }
  var overrideInterfaceName by remember(initialRaw) { mutableStateOf(initial.overrideInterfaceName) }
  var overrideRoutingMark by remember(initialRaw) { mutableStateOf(initial.overrideRoutingMark) }
  var overrideIpVersion by remember(initialRaw) { mutableStateOf(initial.overrideIpVersion) }
  var overrideProxyName by remember(initialRaw) { mutableStateOf(initial.overrideProxyName) }
  var filter by remember(initialRaw) { mutableStateOf(initial.filter) }
  var excludeFilter by remember(initialRaw) { mutableStateOf(initial.excludeFilter) }
  var excludeType by remember(initialRaw) { mutableStateOf(initial.excludeType) }
  var raw by remember(initialRaw) { mutableStateOf(initialRaw) }
  val types = listOf("http", "file", "inline")
  val behaviors = listOf("domain", "ipcidr", "classical")
  val formats = listOf("", "yaml", "text", "mrs")
  val defaultFormatLabel = stringResource(R.string.mihomo_value_default)
  val currentDraft = MihomoProviderDraft(
    name = name,
    type = type,
    url = url,
    path = path,
    interval = interval,
    behavior = behavior,
    healthCheck = healthCheck,
    format = format,
    proxy = proxy,
    sizeLimit = sizeLimit,
    headerUserAgent = headerUserAgent,
    headerAuthorization = headerAuthorization,
    healthUrl = healthUrl,
    healthInterval = healthInterval,
    healthTimeout = healthTimeout,
    healthLazy = healthLazy,
    healthExpectedStatus = healthExpectedStatus,
    overridePrefix = overridePrefix,
    overrideSuffix = overrideSuffix,
    overrideTfo = overrideTfo,
    overrideMptcp = overrideMptcp,
    overrideUdp = overrideUdp,
    overrideUdpOverTcp = overrideUdpOverTcp,
    overrideDown = overrideDown,
    overrideUp = overrideUp,
    overrideSkipCertVerify = overrideSkipCertVerify,
    overrideDialerProxy = overrideDialerProxy,
    overrideInterfaceName = overrideInterfaceName,
    overrideRoutingMark = overrideRoutingMark,
    overrideIpVersion = overrideIpVersion,
    overrideProxyName = overrideProxyName,
    filter = filter,
    excludeFilter = excludeFilter,
    excludeType = excludeType,
  )
  val generatedRaw = remember(raw, currentDraft, ruleProvider) {
    buildMihomoProviderFromDraft(raw, currentDraft, ruleProvider)
  }
  val hasChanges = currentDraft != initial || raw.trimEnd() != initialRaw.trimEnd()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.mihomo_edit_provider_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(value = name, onValueChange = { name = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' }.take(48) }, label = { Text(stringResource(R.string.mihomo_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MihomoDropdownSelectorCard(label = stringResource(R.string.mihomo_field_type), value = type, options = types, onValueChange = { type = it })
        if (ruleProvider) {
          MihomoDropdownSelectorCard(label = stringResource(R.string.mihomo_field_behavior), value = behavior, options = behaviors, onValueChange = { behavior = it })
          MihomoDropdownSelectorCard(label = stringResource(R.string.mihomo_field_format), value = format, options = formats, onValueChange = { format = it }, displayFor = { it.ifBlank { defaultFormatLabel } })
        }
        if (type == "http") {
          OutlinedTextField(value = url, onValueChange = { url = it.take(260) }, label = { Text(stringResource(R.string.mihomo_field_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = interval, onValueChange = { interval = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_interval)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        if (type != "inline") {
          OutlinedTextField(value = path, onValueChange = { path = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.mihomo_provider_options_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = proxy, onValueChange = { proxy = it.take(96) }, label = { Text(stringResource(R.string.mihomo_field_proxy)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = sizeLimit, onValueChange = { sizeLimit = it.filter(Char::isDigit).take(12) }, label = { Text(stringResource(R.string.mihomo_field_size_limit)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = headerUserAgent, onValueChange = { headerUserAgent = it.take(160) }, label = { Text(stringResource(R.string.mihomo_field_user_agent)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = headerAuthorization, onValueChange = { headerAuthorization = it.take(220) }, label = { Text(stringResource(R.string.mihomo_field_authorization)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = filter, onValueChange = { filter = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = excludeFilter, onValueChange = { excludeFilter = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_exclude_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = excludeType, onValueChange = { excludeType = it.take(120) }, label = { Text(stringResource(R.string.mihomo_field_exclude_type)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = overridePrefix, onValueChange = { overridePrefix = it.take(80) }, label = { Text(stringResource(R.string.mihomo_field_override_prefix)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = overrideSuffix, onValueChange = { overrideSuffix = it.take(80) }, label = { Text(stringResource(R.string.mihomo_field_override_suffix)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.mihomo_provider_override_advanced_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = overrideTfo, onClick = { overrideTfo = !overrideTfo }, label = { Text("tfo") })
            FilterChip(selected = overrideMptcp, onClick = { overrideMptcp = !overrideMptcp }, label = { Text("mptcp") })
            FilterChip(selected = overrideUdp, onClick = { overrideUdp = !overrideUdp }, label = { Text("udp") })
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = overrideUdpOverTcp, onClick = { overrideUdpOverTcp = !overrideUdpOverTcp }, label = { Text("udp-over-tcp") })
            FilterChip(selected = overrideSkipCertVerify, onClick = { overrideSkipCertVerify = !overrideSkipCertVerify }, label = { Text("skip-cert") })
          }
        }
        OutlinedTextField(value = overrideDown, onValueChange = { overrideDown = it.take(48) }, label = { Text(stringResource(R.string.mihomo_field_down)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = overrideUp, onValueChange = { overrideUp = it.take(48) }, label = { Text(stringResource(R.string.mihomo_field_up)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = overrideDialerProxy, onValueChange = { overrideDialerProxy = it.take(96) }, label = { Text(stringResource(R.string.mihomo_field_dialer_proxy)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = overrideInterfaceName, onValueChange = { overrideInterfaceName = it.take(32) }, label = { Text(stringResource(R.string.mihomo_field_interface_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = overrideRoutingMark, onValueChange = { overrideRoutingMark = it.filter(Char::isDigit).take(10) }, label = { Text(stringResource(R.string.mihomo_field_routing_mark)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        MihomoDropdownSelectorCard(label = stringResource(R.string.mihomo_field_ip_version), value = overrideIpVersion, options = listOf("", "ipv4", "ipv6", "dual"), onValueChange = { overrideIpVersion = it }, displayFor = { it.ifBlank { defaultFormatLabel } })
        OutlinedTextField(value = overrideProxyName, onValueChange = { overrideProxyName = it.take(96) }, label = { Text(stringResource(R.string.mihomo_field_proxy_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.mihomo_field_health_check), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FilterChip(selected = healthCheck, onClick = { healthCheck = !healthCheck }, label = { Text(stringResource(R.string.mihomo_field_health_check)) })
        if (healthCheck) {
          OutlinedTextField(value = healthUrl, onValueChange = { healthUrl = it.take(220) }, label = { Text(stringResource(R.string.mihomo_field_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = healthInterval, onValueChange = { healthInterval = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_interval)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = healthTimeout, onValueChange = { healthTimeout = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_timeout)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = healthLazy, onClick = { healthLazy = !healthLazy }, label = { Text("lazy") })
          }
          OutlinedTextField(value = healthExpectedStatus, onValueChange = { healthExpectedStatus = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.mihomo_field_expected_status)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.mihomo_raw_extra_title), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
          value = generatedRaw,
          onValueChange = { raw = it },
          modifier = Modifier.fillMaxWidth().height(240.dp),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
      }
    },
    confirmButton = {
      Button(onClick = { onSave(generatedRaw) }, enabled = hasChanges) {
        Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}



@Composable
private fun mihomoAuthFieldLabel(key: String): String = when (key) {
  "password" -> stringResource(R.string.mihomo_field_password)
  "auth-str" -> stringResource(R.string.mihomo_field_auth_str)
  "psk" -> stringResource(R.string.mihomo_field_psk)
  "key" -> stringResource(R.string.mihomo_field_key)
  else -> key
}

@Composable
private fun mihomoServerNameFieldLabel(key: String): String = when (key) {
  "servername" -> stringResource(R.string.mihomo_field_servername)
  "sni" -> stringResource(R.string.mihomo_field_sni)
  else -> key
}

@Composable
private fun mihomoDetailLabel(label: String): String = when (label) {
  "server" -> stringResource(R.string.mihomo_detail_server)
  "auth" -> stringResource(R.string.mihomo_detail_auth)
  "sni" -> stringResource(R.string.mihomo_field_sni)
  "servername" -> stringResource(R.string.mihomo_field_servername)
  "cipher" -> stringResource(R.string.mihomo_field_cipher)
  "uuid" -> stringResource(R.string.mihomo_field_uuid)
  "proxies" -> stringResource(R.string.mihomo_detail_proxies)
  "use" -> stringResource(R.string.mihomo_detail_use)
  "url" -> stringResource(R.string.mihomo_field_url)
  "interval" -> stringResource(R.string.mihomo_field_interval)
  "filter" -> stringResource(R.string.mihomo_field_filter)
  "exclude-filter" -> stringResource(R.string.mihomo_field_exclude_filter)
  "exclude-type" -> stringResource(R.string.mihomo_field_exclude_type)
  "expected-status" -> stringResource(R.string.mihomo_field_expected_status)
  "path" -> stringResource(R.string.mihomo_field_path)
  "mode" -> stringResource(R.string.mihomo_detail_mode)
  else -> label
}
private fun mihomoBuilderCardChips(raw: String, type: String): List<String> {
  val t = type.lowercase(Locale.ROOT).ifBlank { readMihomoBlockScalar(raw, "type").lowercase(Locale.ROOT) }
  val chips = mutableListOf<String>()
  when {
    readMihomoYamlList(raw, "proxies").isNotEmpty() -> chips += "proxies: ${readMihomoYamlList(raw, "proxies").size}"
    readMihomoYamlList(raw, "use").isNotEmpty() -> chips += "use: ${readMihomoYamlList(raw, "use").size}"
  }
  if (readMihomoBlockBool(raw, "udp")) chips += "udp"
  if (readMihomoBlockBool(raw, "tls")) chips += "tls"
  if (readMihomoBlockBool(raw, "skip-cert-verify")) chips += "skip-cert"
  readMihomoBlockScalar(raw, "network").takeIf { it.isNotBlank() }?.let { chips += it }
  if (raw.contains("ws-opts:")) chips += "ws"
  if (raw.contains("grpc-opts:")) chips += "grpc"
  if (raw.contains("reality-opts:")) chips += "reality"
  if (raw.contains("health-check:")) chips += "health-check"
  if (readMihomoBlockBool(raw, "include-all")) chips += "include-all"
  if (readMihomoBlockBool(raw, "include-all-proxies")) chips += "include-all-proxies"
  if (readMihomoBlockBool(raw, "include-all-providers")) chips += "include-all-providers"
  if (readMihomoBlockBool(raw, "disable-udp")) chips += "disable-udp"
  if (readMihomoBlockBool(raw, "hidden")) chips += "hidden"
  readMihomoBlockScalar(raw, "strategy").takeIf { it.isNotBlank() }?.let { chips += it }
  return (listOf(t).filter { it.isNotBlank() && it != "unknown" } + chips).distinct().take(8)
}

private fun mihomoBuilderCardDetails(raw: String, type: String): List<Pair<String, String>> {
  val t = type.lowercase(Locale.ROOT).ifBlank { readMihomoBlockScalar(raw, "type").lowercase(Locale.ROOT) }
  val details = mutableListOf<Pair<String, String>>()
  val server = readMihomoBlockScalar(raw, "server")
  val port = readMihomoBlockScalar(raw, "port")
  if (server.isNotBlank()) details += "server" to (if (port.isNotBlank()) "$server:$port" else server)
  readMihomoBlockScalar(raw, "username").takeIf { it.isNotBlank() }?.let { details += "auth" to it }
  readMihomoBlockScalar(raw, "sni").takeIf { it.isNotBlank() }?.let { details += "sni" to it }
  readMihomoBlockScalar(raw, "servername").takeIf { it.isNotBlank() }?.let { details += "servername" to it }
  readMihomoBlockScalar(raw, "cipher").takeIf { it.isNotBlank() }?.let { details += "cipher" to it }
  readMihomoBlockScalar(raw, "uuid").takeIf { it.isNotBlank() }?.let { details += "uuid" to it.take(18) + if (it.length > 18) "…" else "" }
  val wsPath = readMihomoNestedScalar(raw, "ws-opts", "path")
  val wsHost = readMihomoWsHost(raw)
  if (wsPath.isNotBlank() || wsHost.isNotBlank()) details += "ws" to listOf(wsPath, wsHost).filter { it.isNotBlank() }.joinToString(" · ")
  readMihomoNestedScalar(raw, "grpc-opts", "grpc-service-name").takeIf { it.isNotBlank() }?.let { details += "grpc" to it }
  val realityKey = readMihomoNestedScalar(raw, "reality-opts", "public-key")
  val realityShort = readMihomoNestedScalar(raw, "reality-opts", "short-id")
  if (realityKey.isNotBlank() || realityShort.isNotBlank()) details += "reality" to listOf(realityKey.take(12), realityShort).filter { it.isNotBlank() }.joinToString(" · ")
  val proxies = readMihomoYamlList(raw, "proxies")
  if (proxies.isNotEmpty()) details += "proxies" to proxies.take(4).joinToString(", ") + if (proxies.size > 4) " +${proxies.size - 4}" else ""
  val use = readMihomoYamlList(raw, "use")
  if (use.isNotEmpty()) details += "use" to use.take(4).joinToString(", ") + if (use.size > 4) " +${use.size - 4}" else ""
  readMihomoBlockScalar(raw, "url").takeIf { it.isNotBlank() }?.let { details += "url" to it }
  readMihomoBlockScalar(raw, "interval").takeIf { it.isNotBlank() }?.let { details += "interval" to "${it}s" }
  readMihomoBlockScalar(raw, "filter").takeIf { it.isNotBlank() }?.let { details += "filter" to it }
  readMihomoBlockScalar(raw, "exclude-filter").takeIf { it.isNotBlank() }?.let { details += "exclude-filter" to it }
  readMihomoBlockScalar(raw, "exclude-type").takeIf { it.isNotBlank() }?.let { details += "exclude-type" to it }
  readMihomoBlockScalar(raw, "expected-status").takeIf { it.isNotBlank() }?.let { details += "expected-status" to it }
  readMihomoBlockScalar(raw, "path").takeIf { it.isNotBlank() }?.let { details += "path" to it }
  if (details.isEmpty()) {
    details += "mode" to when (t) {
      "direct" -> "direct outbound"
      "dns" -> "internal DNS outbound"
      "select" -> "manual group selection"
      "url-test" -> "latency based auto selection"
      "fallback" -> "first available proxy"
      "load-balance" -> "load balancing group"
      "relay" -> "proxy chain"
      else -> "raw YAML block"
    }
  }
  return details
}

@Composable
private fun MihomoBuilderItemCard(
  title: String,
  subtitle: String,
  raw: String,
  type: String,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onEdit: () -> Unit,
  onDuplicate: () -> Unit,
  onDelete: () -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  extraActionLabel: String? = null,
  onExtraAction: (() -> Unit)? = null,
) {
  val chips = remember(raw, type) { mihomoBuilderCardChips(raw, type) }
  val details = remember(raw, type) { mihomoBuilderCardDetails(raw, type) }
  val safeTitle = title.ifBlank { readMihomoBlockScalar(raw, "name") }.ifBlank { stringResource(R.string.mihomo_unnamed_item) }
  val safeType = type.ifBlank { readMihomoBlockScalar(raw, "type") }.ifBlank { stringResource(R.string.mihomo_raw_type) }
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
  ) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(safeTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            MihomoMiniBadge(safeType)
          }
          if (subtitle.isNotBlank()) {
            Text(
              subtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
              maxLines = 2,
            )
          }
        }
      }
      if (chips.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          chips.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              row.forEach { chip -> MihomoInfoChip(chip) }
            }
          }
        }
      }
      if (details.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          details.take(5).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(mihomoDetailLabel(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f), modifier = Modifier.width(78.dp))
              Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f), maxLines = 2, modifier = Modifier.weight(1f))
            }
          }
        }
      }
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
      ) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (extraActionLabel != null && onExtraAction != null) {
            Button(onClick = onExtraAction, modifier = Modifier.fillMaxWidth()) {
              Text(extraActionLabel, maxLines = 1)
            }
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_edit), maxLines = 1) }
            OutlinedButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_duplicate), maxLines = 1) }
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.weight(1f)) { Text("↑") }
            OutlinedButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.weight(1f)) { Text("↓") }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_delete), maxLines = 1) }
          }
        }
      }
    }
  }
}

@Composable
private fun MihomoMiniBadge(text: String) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      maxLines = 1,
    )
  }
}

@Composable
private fun MihomoInfoChip(text: String) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      maxLines = 1,
    )
  }
}


@Composable
private fun MihomoRuleCard(
  rule: String,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
) {
  val parts = remember(rule) { parseMihomoRuleParts(rule) }
  val isMatch = parts.type.equals("MATCH", ignoreCase = true)
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MihomoMiniBadge(parts.type)
            if (parts.policy.isNotBlank()) MihomoInfoChip(parts.policy)
          }
          Text(
            text = if (parts.value.isNotBlank()) parts.value else stringResource(R.string.mihomo_rule_match_fallback),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
          )
          if (isMatch) {
            Text(
              text = stringResource(R.string.mihomo_rule_match_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
              maxLines = 2,
            )
          }
          if (parts.options.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              parts.options.take(3).forEach { MihomoInfoChip(it) }
            }
          }
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.44f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
          ) {
            Text(
              text = rule,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
              maxLines = 2,
            )
          }
        }
      }
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.50f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
      ) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.action_edit), maxLines = 1)
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.action_delete), maxLines = 1)
            }
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.action_move_up), maxLines = 1)
            }
            OutlinedButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.action_move_down), maxLines = 1)
            }
          }
        }
      }
    }
  }
}



@Composable
private fun MihomoProxySmartEditorDialog(initialText: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  val initialType = remember(initialText) { readMihomoBlockScalar(initialText, "type").ifBlank { "direct" }.lowercase(Locale.ROOT) }
  var rawText by remember(initialText) { mutableStateOf(initialText) }
  var name by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "name").ifBlank { "NEW-PROXY" }) }
  var type by remember(initialText) { mutableStateOf(initialType) }
  var server by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "server")) }
  var port by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "port")) }
  var username by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "username")) }
  var secret by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, mihomoProxyAuthKey(initialType))) }
  var uuid by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "uuid")) }
  var cipher by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "cipher")) }
  var udp by remember(initialText) { mutableStateOf(readMihomoBlockBool(initialText, "udp")) }
  var tls by remember(initialText) { mutableStateOf(readMihomoBlockBool(initialText, "tls")) }
  var skipCert by remember(initialText) { mutableStateOf(readMihomoBlockBool(initialText, "skip-cert-verify")) }
  var network by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "network").ifBlank { "tcp" }) }
  var serverName by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, mihomoProxyServerNameKey(initialType))) }
  var flow by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "flow")) }
  var packetEncoding by remember(initialText) { mutableStateOf(readMihomoBlockScalar(initialText, "packet-encoding")) }
  var wsPath by remember(initialText) { mutableStateOf(readMihomoNestedScalar(initialText, "ws-opts", "path")) }
  var wsHost by remember(initialText) { mutableStateOf(readMihomoWsHost(initialText)) }
  var grpcService by remember(initialText) { mutableStateOf(readMihomoNestedScalar(initialText, "grpc-opts", "grpc-service-name")) }
  var realityPublicKey by remember(initialText) { mutableStateOf(readMihomoNestedScalar(initialText, "reality-opts", "public-key")) }
  var realityShortId by remember(initialText) { mutableStateOf(readMihomoNestedScalar(initialText, "reality-opts", "short-id")) }

  val authKey = remember(type) { mihomoProxyAuthKey(type) }
  val serverNameKey = remember(type) { mihomoProxyServerNameKey(type) }
  val portInvalid = port.isNotBlank() && (port.toIntOrNull()?.let { it in 1..65535 } != true)
  val generatedRaw = remember(rawText, name, type, server, port, username, secret, uuid, cipher, udp, tls, skipCert, serverName, network, flow, packetEncoding, wsPath, wsHost, grpcService, realityPublicKey, realityShortId) {
    buildMihomoProxyBlockFromFields(rawText, name, type, server, port, username, secret, uuid, cipher, udp, tls, skipCert, serverName, network, flow, packetEncoding, wsPath, wsHost, grpcService, realityPublicKey, realityShortId)
  }
  val isEditing = initialText.trim().isNotEmpty()
  val initialSignature = remember(initialText) {
    listOf(
      readMihomoBlockScalar(initialText, "name").ifBlank { "NEW-PROXY" },
      initialType,
      readMihomoBlockScalar(initialText, "server"),
      readMihomoBlockScalar(initialText, "port"),
      readMihomoBlockScalar(initialText, "username"),
      readMihomoBlockScalar(initialText, mihomoProxyAuthKey(initialType)),
      readMihomoBlockScalar(initialText, "uuid"),
      readMihomoBlockScalar(initialText, "cipher"),
      readMihomoBlockBool(initialText, "udp"),
      readMihomoBlockBool(initialText, "tls"),
      readMihomoBlockBool(initialText, "skip-cert-verify"),
      readMihomoBlockScalar(initialText, "network").ifBlank { "tcp" },
      readMihomoBlockScalar(initialText, mihomoProxyServerNameKey(initialType)),
      readMihomoBlockScalar(initialText, "flow"),
      readMihomoBlockScalar(initialText, "packet-encoding"),
      readMihomoNestedScalar(initialText, "ws-opts", "path"),
      readMihomoWsHost(initialText),
      readMihomoNestedScalar(initialText, "grpc-opts", "grpc-service-name"),
      readMihomoNestedScalar(initialText, "reality-opts", "public-key"),
      readMihomoNestedScalar(initialText, "reality-opts", "short-id"),
    )
  }
  val currentSignature = listOf(
    name,
    type,
    server,
    port,
    username,
    secret,
    uuid,
    cipher,
    udp,
    tls,
    skipCert,
    network,
    serverName,
    flow,
    packetEncoding,
    wsPath,
    wsHost,
    grpcService,
    realityPublicKey,
    realityShortId,
  )
  val hasChanges = !isEditing || currentSignature != initialSignature || rawText.trimEnd() != initialText.trimEnd()
  var previewRaw by remember(initialText) { mutableStateOf(generatedRaw) }
  LaunchedEffect(generatedRaw) {
    delay(220)
    previewRaw = generatedRaw
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.mihomo_edit_proxy_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(stringResource(R.string.mihomo_section_basic), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, label = { Text(stringResource(R.string.mihomo_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MihomoDropdownSelectorCard(
          label = stringResource(R.string.mihomo_field_type),
          value = type,
          options = (mihomoProxyPresets.map { it.type } + type).distinct(),
          onValueChange = { type = it },
        )
        if (mihomoProxyNeedsServer(type)) {
          OutlinedTextField(value = server, onValueChange = { server = it.trim().take(180) }, label = { Text(stringResource(R.string.mihomo_field_server)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.mihomo_field_port)) },
            singleLine = true,
            isError = portInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
          )
          if (portInvalid) Text(stringResource(R.string.mihomo_port_range_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (type in setOf("http", "socks5", "mieru", "ssh", "trusttunnel")) {
          Text(stringResource(R.string.mihomo_section_auth), style = MaterialTheme.typography.labelLarge)
          OutlinedTextField(value = username, onValueChange = { username = it.take(120) }, label = { Text(stringResource(R.string.mihomo_field_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (type !in setOf("direct", "dns", "wireguard", "masque")) {
          OutlinedTextField(value = secret, onValueChange = { secret = it.take(220) }, label = { Text(mihomoAuthFieldLabel(authKey)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (mihomoProxyUsesUuid(type)) {
          OutlinedTextField(value = uuid, onValueChange = { uuid = it.trim().take(64) }, label = { Text(stringResource(R.string.mihomo_field_uuid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (mihomoProxyUsesCipher(type)) {
          OutlinedTextField(value = cipher, onValueChange = { cipher = it.trim().take(48) }, label = { Text(stringResource(R.string.mihomo_field_cipher)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Text(stringResource(R.string.mihomo_section_options), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = udp, onClick = { udp = !udp }, label = { Text("udp") })
          if (mihomoProxySupportsTls(type)) FilterChip(selected = tls, onClick = { tls = !tls }, label = { Text("tls") })
          if (mihomoProxySupportsTls(type)) FilterChip(selected = skipCert, onClick = { skipCert = !skipCert }, label = { Text("skip-cert-verify") })
        }
        if (mihomoProxySupportsTls(type)) {
          OutlinedTextField(value = serverName, onValueChange = { serverName = it.trim().take(180) }, label = { Text(mihomoServerNameFieldLabel(serverNameKey)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        if (mihomoProxySupportsTransport(type)) {
          Text(stringResource(R.string.mihomo_section_transport), style = MaterialTheme.typography.labelLarge)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("tcp", "ws", "grpc").forEach { item ->
              FilterChip(selected = network == item, onClick = { network = item }, label = { Text(item) })
            }
          }
          if (network == "ws") {
            OutlinedTextField(value = wsPath, onValueChange = { wsPath = it.take(180) }, label = { Text(stringResource(R.string.mihomo_field_ws_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = wsHost, onValueChange = { wsHost = it.trim().take(180) }, label = { Text(stringResource(R.string.mihomo_field_ws_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          }
          if (network == "grpc") {
            OutlinedTextField(value = grpcService, onValueChange = { grpcService = it.take(120) }, label = { Text(stringResource(R.string.mihomo_field_grpc_service)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          }
        }

        if (type in setOf("vless", "trojan")) {
          Text(stringResource(R.string.mihomo_section_reality_vision), style = MaterialTheme.typography.labelLarge)
          OutlinedTextField(value = flow, onValueChange = { flow = it.trim().take(64) }, label = { Text(stringResource(R.string.mihomo_field_flow)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = packetEncoding, onValueChange = { packetEncoding = it.trim().take(32) }, label = { Text(stringResource(R.string.mihomo_field_packet_encoding)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = realityPublicKey, onValueChange = { realityPublicKey = it.trim().take(120) }, label = { Text(stringResource(R.string.mihomo_field_reality_public_key)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = realityShortId, onValueChange = { realityShortId = it.trim().take(32) }, label = { Text(stringResource(R.string.mihomo_field_reality_short_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Text(stringResource(R.string.mihomo_raw_block_title), style = MaterialTheme.typography.labelLarge)
        Text(
          stringResource(R.string.mihomo_raw_live_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = previewRaw,
          onValueChange = { updated ->
            previewRaw = updated
            rawText = updated
          },
          modifier = Modifier.fillMaxWidth().height(220.dp),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
      }
    },
    confirmButton = {
      Button(
        enabled = name.isNotBlank() && type.isNotBlank() && !portInvalid && hasChanges,
        onClick = {
          onSave(generatedRaw.trimEnd())
        },
      ) { Text(stringResource(R.string.action_save)) }
    },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun MihomoRawBlockDialog(title: String, initialText: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var text by remember(initialText) { mutableStateOf(initialText) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().height(360.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
    },
    confirmButton = { Button(onClick = { onSave(text) }, enabled = text != initialText) { Text(stringResource(R.string.action_save)) } },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun MihomoSingleLineDialog(title: String, initialText: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var text by remember(initialText) { mutableStateOf(initialText) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = false) },
    confirmButton = { Button(onClick = { onSave(text) }, enabled = text != initialText) { Text(stringResource(R.string.action_save)) } },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun MihomoProvidersTab(yamlText: String, onSaveYaml: (String) -> Unit) {
  val initialProxyProviders = remember(yamlText) { extractTopLevelYamlSection(yamlText, "proxy-providers") }
  val initialRuleProviders = remember(yamlText) { extractTopLevelYamlSection(yamlText, "rule-providers") }
  var proxyProviders by remember(initialProxyProviders) { mutableStateOf(initialProxyProviders) }
  var ruleProviders by remember(initialRuleProviders) { mutableStateOf(initialRuleProviders) }
  val hasChanges = proxyProviders != initialProxyProviders || ruleProviders != initialRuleProviders
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    MihomoProviderCard(
      title = stringResource(R.string.mihomo_proxy_providers_title),
      text = proxyProviders,
      onTextChange = { proxyProviders = it },
      onAddTemplate = {
        proxyProviders = proxyProviders.trimEnd().ifBlank { "proxy-providers:" } + "\n" + """
  provider1:
    type: http
    url: "https://example.com/sub.yaml"
    path: ./proxy_providers/provider1.yaml
    interval: 3600
    health-check:
      enable: true
      url: http://www.gstatic.com/generate_204
      interval: 300
""".trimEnd() + "\n"
      },
    )
    MihomoProviderCard(
      title = stringResource(R.string.mihomo_rule_providers_title),
      text = ruleProviders,
      onTextChange = { ruleProviders = it },
      onAddTemplate = {
        ruleProviders = ruleProviders.trimEnd().ifBlank { "rule-providers:" } + "\n" + """
  reject:
    type: http
    behavior: domain
    url: "https://example.com/reject.yaml"
    path: ./rule_providers/reject.yaml
    interval: 86400
""".trimEnd() + "\n"
      },
    )
    Button(
      onClick = {
        val withProxy = replaceTopLevelYamlSection(yamlText, "proxy-providers", proxyProviders)
        onSaveYaml(replaceTopLevelYamlSection(withProxy, "rule-providers", ruleProviders))
      },
      enabled = hasChanges,
      modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.action_save)) }
  }
}

@Composable
private fun MihomoProviderCard(
  title: String,
  text: String,
  onTextChange: (String) -> Unit,
  onAddTemplate: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onAddTemplate) {
          Icon(Icons.Filled.Add, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_add))
        }
      }
      OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth().height(260.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
    }
  }
}


private fun mihomoDefaultDnsSection(): String = """
dns:
  enable: true
  listen: 127.0.0.1:1053
  enhanced-mode: fake-ip
  nameserver:
    - 1.1.1.1
    - 8.8.8.8
""".trimIndent()

private fun mihomoDefaultAdvancedSection(section: String): String = when (section) {
  "sniffer" -> """
sniffer:
  enable: false
  force-dns-mapping: true
  parse-pure-ip: true
  override-destination: false
""".trimIndent()
  "sub-rules" -> """
sub-rules:
  example:
    - DOMAIN-SUFFIX,example.com,DIRECT
    - MATCH,Proxy
""".trimIndent()
  "tunnels" -> """
tunnels:
  # - tcp/udp,127.0.0.1:12345,example.com:443,Proxy
""".trimIndent()
  "listeners" -> """
listeners:
  # Keep this empty unless you intentionally add Mihomo inbound listeners.
""".trimIndent()
  "ntp" -> """
ntp:
  enable: false
  server: time.apple.com
  port: 123
  interval: 30
""".trimIndent()
  "experimental" -> """
experimental:
  quic-go-disable-gso: false
  quic-go-disable-ecn: false
""".trimIndent()
  "profile" -> """
profile:
  store-selected: true
  store-fake-ip: true
""".trimIndent()
  else -> "$section:\n"
}

@Composable
private fun MihomoRawSectionEditorTab(
  title: String,
  description: String,
  section: String,
  yamlText: String,
  defaultText: String,
  onSaveYaml: (String) -> Unit,
) {
  var text by remember(yamlText, section) {
    val extracted = extractTopLevelYamlSection(yamlText, section).trimEnd()
    mutableStateOf(if (extracted == "$section:") "" else extracted)
  }
  var lastSaved by remember(yamlText, section) {
    val extracted = extractTopLevelYamlSection(yamlText, section).trimEnd()
    mutableStateOf(if (extracted == "$section:") "" else extracted)
  }
  LaunchedEffect(text) {
    if (text.trimEnd() == lastSaved.trimEnd()) return@LaunchedEffect
    delay(MIHOMO_AUTOSAVE_DELAY_MS)
    val cleaned = text.trimEnd()
    val updated = if (cleaned.isBlank() || cleaned == "$section:") {
      replaceTopLevelYamlSection(yamlText, section, "")
    } else {
      replaceTopLevelYamlSection(yamlText, section, cleaned + "\n")
    }
    onSaveYaml(updated)
    lastSaved = cleaned
  }
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      Text(stringResource(R.string.mihomo_raw_safe_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().height(360.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { text = defaultText.trimEnd() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.mihomo_restore_sample)) }
        OutlinedButton(onClick = { text = "" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_delete)) }
      }
    }
  }
}

@Composable
private fun MihomoAdvancedCompatibilityTab(yamlText: String, onSaveYaml: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mihomo_advanced_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.mihomo_advanced_compat_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Text(stringResource(R.string.mihomo_inbound_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    }
    listOf(
      "sniffer" to stringResource(R.string.mihomo_advanced_sniffer_desc),
      "sub-rules" to stringResource(R.string.mihomo_advanced_subrules_desc),
      "tunnels" to stringResource(R.string.mihomo_advanced_tunnels_desc),
      "listeners" to stringResource(R.string.mihomo_advanced_listeners_desc),
      "ntp" to stringResource(R.string.mihomo_advanced_ntp_desc),
      "experimental" to stringResource(R.string.mihomo_advanced_experimental_desc),
      "profile" to stringResource(R.string.mihomo_advanced_profile_desc),
    ).forEach { (section, desc) ->
      MihomoRawSectionEditorTab(
        title = section,
        description = desc,
        section = section,
        yamlText = yamlText,
        defaultText = mihomoDefaultAdvancedSection(section),
        onSaveYaml = onSaveYaml,
      )
    }
  }
}

@Composable
private fun MihomoAdvancedTab(yamlText: String, onSaveYaml: (String) -> Unit) {
  val externalKeys = listOf("external-controller", "external-ui", "external-ui-url", "secret")
  val initialExternal = remember(yamlText) {
    externalKeys
      .mapNotNull { key -> yamlText.lineSequence().firstOrNull { it.startsWith("$key:") } }
      .joinToString("\n")
      .ifBlank {
        """
external-controller: 127.0.0.1:19090
secret: ""
external-ui: "/data/adb/modules/ZDT-D/working_folder/mihomo/profile/main/work/ui"
external-ui-url: "https://github.com/MetaCubeX/metacubexd/archive/refs/heads/gh-pages.zip"
""".trimIndent()
      }
  }
  var external by remember(yamlText) { mutableStateOf(initialExternal) }
  val cleanedYaml = remember(yamlText) {
    yamlText.lineSequence()
      .filterNot { line -> externalKeys.any { key -> line.startsWith("$key:") } }
      .joinToString("\n")
      .trimEnd()
  }
  val advancedYaml = (external.trimEnd() + "\n\n" + cleanedYaml + "\n")
  val hasChanges = advancedYaml.trimEnd() != yamlText.trimEnd()
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.mihomo_advanced_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.mihomo_advanced_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )
      OutlinedTextField(
        value = external,
        onValueChange = { external = it },
        modifier = Modifier.fillMaxWidth().height(220.dp),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { onSaveYaml(advancedYaml) },
          enabled = hasChanges,
          modifier = Modifier.weight(1f),
        ) {
          Icon(Icons.Filled.ContentCopy, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_save))
        }
      }
      Text(
        stringResource(R.string.mihomo_no_managed_route_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )
    }
  }
}
