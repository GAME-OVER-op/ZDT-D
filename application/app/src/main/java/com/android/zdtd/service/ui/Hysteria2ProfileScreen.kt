package com.android.zdtd.service.ui

import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.android.zdtd.service.R
import com.android.zdtd.service.T2sPanelActivity
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

private const val HYSTERIA2_MODE_T2S = "t2s"
private const val HYSTERIA2_MODE_VPN = "vpn"

private val HYSTERIA2_TUN2SOCKS_LOG_LEVELS = listOf("trace", "debug", "info", "warn", "error", "silent")

private fun normalizeHysteria2Mode(raw: String?): String {
  return when (raw?.trim()?.lowercase()) {
    HYSTERIA2_MODE_VPN, "tun2socks", "t2s-vpn", "t2s_vpn" -> HYSTERIA2_MODE_VPN
    else -> HYSTERIA2_MODE_T2S
  }
}

private fun isHysteria2Ipv4(value: String): Boolean {
  val parts = value.trim().split('.')
  return parts.size == 4 && parts.all { part ->
    part.isNotBlank() && part.length <= 3 && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255
  }
}

private fun normalizeHysteria2Dns(values: List<String>): List<String> = values
  .map { it.trim() }
  .filter { it.isNotBlank() && isHysteria2Ipv4(it) }
  .distinct()
  .ifEmpty { listOf("8.8.8.8") }

private fun normalizeHysteria2TunName(value: String?): String {
  val cleaned = value
    ?.trim()
    ?.filter { it.isLetterOrDigit() || it == '_' }
    ?.take(15)
    .orEmpty()
  if (cleaned.isBlank()) return "hytun0"
  val lower = cleaned.lowercase()
  return when (lower) {
    "wlan0", "rmnet0", "eth0", "lo", "tunl0" -> "hytun0"
    else -> cleaned
  }
}

private fun normalizeHysteria2Tun2socksLogLevel(value: String?): String {
  val normalized = value?.trim()?.lowercase().orEmpty()
  return if (normalized in HYSTERIA2_TUN2SOCKS_LOG_LEVELS) normalized else "info"
}

private fun normalizeHysteria2ProtoMode(raw: String?): String =
  if (raw?.trim()?.lowercase() == "tcp") "tcp" else "tcp_udp"

private data class Hysteria2ProfileSettingUi(
  val mode: String = HYSTERIA2_MODE_T2S,
  val t2sPort: Int? = 12590,
  val t2sWebPort: Int? = 8059,
  val tun: String = "hytun0",
  val dns: List<String> = listOf("8.8.8.8"),
  val tun2socksLogLevel: String = "info",
  val protoMode: String = "tcp_udp",
) {
  val isVpn: Boolean get() = mode == HYSTERIA2_MODE_VPN
  val isT2s: Boolean get() = mode != HYSTERIA2_MODE_VPN
}

private fun defaultHysteria2ProfileSettingUi(): Hysteria2ProfileSettingUi = Hysteria2ProfileSettingUi()

private data class Hysteria2ServerUi(
  val name: String,
  val enabled: Boolean,
  val port: Int?,
  val logLevel: String = "info",
)



private data class Hysteria2PortRegistry(
  val labelsByPort: Map<Int, List<String>> = emptyMap(),
)

private fun parseHysteria2ProfileSettingUi(obj: JSONObject?): Hysteria2ProfileSettingUi {
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

  return Hysteria2ProfileSettingUi(
    mode = normalizeHysteria2Mode(obj?.optString("mode", HYSTERIA2_MODE_T2S)),
    t2sPort = obj?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 } ?: 12590,
    t2sWebPort = obj?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 } ?: 8059,
    tun = normalizeHysteria2TunName(obj?.optString("tun", "hytun0")),
    dns = normalizeHysteria2Dns(rawDns),
    tun2socksLogLevel = normalizeHysteria2Tun2socksLogLevel(obj?.optString("tun2socks_loglevel", "info")),
    protoMode = normalizeHysteria2ProtoMode(obj?.optString("proto_mode", "tcp_udp")),
  )
}

private fun Hysteria2ProfileSettingUi.toJson(): JSONObject {
  return JSONObject()
    .put("mode", normalizeHysteria2Mode(mode))
    .put("t2s_port", t2sPort ?: 12590)
    .put("t2s_web_port", t2sWebPort ?: 8059)
    .put("tun", normalizeHysteria2TunName(tun))
    .put("dns", JSONArray().also { arr -> normalizeHysteria2Dns(dns).forEach { arr.put(it) } })
    .put("tun2socks_loglevel", normalizeHysteria2Tun2socksLogLevel(tun2socksLogLevel))
    .put("proto_mode", normalizeHysteria2ProtoMode(protoMode))
}

private fun hysteria2WebPanelUrl(port: Int): String = "http://127.0.0.1:$port/"

private fun hysteria2WebPanelScope(profile: String): String = "profile/hysteria2/${profile.ifBlank { "main" }}"

private suspend fun isHysteria2WebPanelPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
  runCatching {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", port), 850)
    }
    true
  }.getOrDefault(false)
}

private fun createSnackFunction(
  scope: kotlinx.coroutines.CoroutineScope,
  snackHost: SnackbarHostState,
): (String) -> Unit = { msg ->
  scope.launch { snackHost.showSnackbar(msg) }
}

@Composable
private fun Hysteria2SectionCard(
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
private fun Hysteria2ProfileEnabledCard(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val accent = if (checked) Color(0xFF22C55E) else Color(0xFFEF4444)
  Hysteria2SectionCard(
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
private fun Hysteria2WebPanelCard(
  checking: Boolean,
  onOpen: () -> Unit,
) {
  Hysteria2SectionCard(
    title = "t2s",
    desc = stringResource(R.string.hysteria2_t2s_panel_desc),
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
          stringResource(R.string.hysteria2_panel),
          modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
    },
  )
}


private fun parseDnsText(text: String): List<String> = normalizeHysteria2Dns(
  text.split(',', '\n', ';', ' ').map { it.trim() }
)

private fun parseHysteria2ServersUi(obj: JSONObject?): List<Hysteria2ServerUi> {
  val arr = obj?.optJSONArray("servers") ?: JSONArray()
  return buildList {
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val name = item.optString("name", "").trim()
      if (name.isBlank()) continue
      val setting = item.optJSONObject("setting")
      add(
        Hysteria2ServerUi(
          name = name,
          enabled = setting?.optBoolean("enabled", false) ?: false,
          port = setting?.optInt("socks5_port", 0)?.takeIf { it in 1..65535 },
          logLevel = setting?.optString("log_level", "info")?.takeIf { it.isNotBlank() } ?: "info",
        )
      )
    }
  }.sortedBy { it.name.lowercase() }
}

private fun normalizeHysteria2ServerName(input: String): String {
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


private fun hysteria2ServerPortLabel(profile: String, server: String): String = "$profile / $server"

private fun hysteria2T2sPortLabel(profile: String): String = "$profile / t2s"

private fun hysteria2T2sWebPortLabel(profile: String): String = "$profile / t2s web"

private fun buildHysteria2PortRegistry(
  settingsByProfile: Map<String, Hysteria2ProfileSettingUi?>,
  serversByProfile: Map<String, List<Hysteria2ServerUi>>,
): Hysteria2PortRegistry {
  val labels = linkedMapOf<Int, MutableList<String>>()

  fun add(port: Int?, label: String) {
    val safePort = port ?: return
    if (safePort !in 1..65535) return
    labels.getOrPut(safePort) { mutableListOf() }.add(label)
  }

  settingsByProfile.forEach { (profile, setting) ->
    if (setting?.isVpn != true) {
      add(setting?.t2sPort, hysteria2T2sPortLabel(profile))
      add(setting?.t2sWebPort, hysteria2T2sWebPortLabel(profile))
    }
  }
  serversByProfile.forEach { (profile, profileServers) ->
    profileServers.forEach { server ->
      add(server.port, hysteria2ServerPortLabel(profile, server.name))
    }
  }

  return Hysteria2PortRegistry(labelsByPort = labels.mapValues { it.value.toList() })
}

private fun findHysteria2PortConflictLabel(
  registry: Hysteria2PortRegistry,
  port: Int,
  ignoredLabel: String? = null,
): String? {
  if (port !in 1..65535) return null
  return registry.labelsByPort[port]
    .orEmpty()
    .firstOrNull { it != ignoredLabel }
}

private fun findNextAvailableHysteria2Port(
  registry: Hysteria2PortRegistry,
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
fun Hysteria2ProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  tproxyEnabled: Boolean = false,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val effectiveTopContentPadding = topContentPadding + 12.dp
  val effectiveBottomContentPadding = bottomContentPadding + if (compact) 12.dp else 16.dp
  val program = programs.firstOrNull { it.id == "hysteria2" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val encodedProfile = remember(profile) { URLEncoder.encode(profile, "UTF-8") }
  val basePath = remember(encodedProfile) { "/api/programs/hysteria2/profiles/$encodedProfile" }
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val scope = rememberCoroutineScope()
  val dialogScrollState = rememberScrollState()
  val maxDialogHeight = configuration.screenHeightDp.dp * 0.92f
  val scroll = rememberScrollState()

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  var setting by remember(profile) { mutableStateOf<Hysteria2ProfileSettingUi?>(null) }
  var settingLoading by remember(profile) { mutableStateOf(false) }
  var settingSaving by remember(profile) { mutableStateOf(false) }
  var servers by remember(profile) { mutableStateOf<List<Hysteria2ServerUi>>(emptyList()) }
  var serversLoading by remember(profile) { mutableStateOf(false) }
  var globalPortRegistry by remember(profile, program?.profiles) { mutableStateOf(Hysteria2PortRegistry()) }
  var globalPortRefreshSeq by remember(profile, program?.profiles) { mutableStateOf(0) }

  var showCreateServer by remember(profile) { mutableStateOf(false) }
  var editServer by remember(profile) { mutableStateOf<String?>(null) }
  var editText by remember(profile) { mutableStateOf("") }
  var editLastLoadedText by remember(profile) { mutableStateOf("") }
  var editSettingsServer by remember(profile) { mutableStateOf<String?>(null) }
  var editSettingsConfig by remember(profile) { mutableStateOf("") }
  var editSettingsLoading by remember(profile) { mutableStateOf(false) }
  var editLoading by remember(profile) { mutableStateOf(false) }
  var hysteria2WebPanelChecking by remember(profile) { mutableStateOf(false) }
  var selectedApps by remember(profile) { mutableStateOf(emptySet<String>()) }

  fun refreshApps() {
    actions.loadText("$basePath/apps/user") { content ->
      selectedApps = parsePkgList(content)
    }
  }

  fun refreshSetting() {
    settingLoading = true
    actions.loadJsonData("$basePath/setting") { obj ->
      setting = parseHysteria2ProfileSettingUi(obj)
      settingLoading = false
    }
  }

  fun refreshServers() {
    serversLoading = true
    actions.loadJsonData("$basePath/servers") { obj ->
      servers = parseHysteria2ServersUi(obj)
      serversLoading = false
    }
  }

  fun refreshGlobalPortRegistry() {
    val profileNames = program?.profiles?.map { it.name }?.distinct().orEmpty()
    val requestSeq = globalPortRefreshSeq + 1
    globalPortRefreshSeq = requestSeq
    if (profileNames.isEmpty()) {
      globalPortRegistry = Hysteria2PortRegistry()
      return
    }

    val settingsByProfile = linkedMapOf<String, Hysteria2ProfileSettingUi?>()
    val serversByProfile = linkedMapOf<String, List<Hysteria2ServerUi>>()
    var remaining = profileNames.size * 2

    fun finishOne() {
      remaining -= 1
      if (remaining == 0 && globalPortRefreshSeq == requestSeq) {
        globalPortRegistry = buildHysteria2PortRegistry(settingsByProfile, serversByProfile)
      }
    }

    profileNames.forEach { profileName ->
      val encoded = URLEncoder.encode(profileName, "UTF-8")
      val profileBasePath = "/api/programs/hysteria2/profiles/$encoded"
      actions.loadJsonData("$profileBasePath/setting") { obj ->
        settingsByProfile[profileName] = parseHysteria2ProfileSettingUi(obj)
        finishOne()
      }
      actions.loadJsonData("$profileBasePath/servers") { obj ->
        serversByProfile[profileName] = parseHysteria2ServersUi(obj)
        finishOne()
      }
    }
  }

  fun rememberGeneratedServer(serverName: String, port: Int) {
    servers = (servers.filterNot { it.name == serverName } + Hysteria2ServerUi(name = serverName, enabled = true, port = port))
      .sortedBy { it.name.lowercase() }

    val label = hysteria2ServerPortLabel(profile, serverName)
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
    globalPortRegistry = Hysteria2PortRegistry(labelsByPort = nextLabels)
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

  fun openSettingsEditor(serverName: String) {
    editSettingsServer = serverName
    editSettingsLoading = true
    val encodedServer = URLEncoder.encode(serverName, "UTF-8")
    actions.loadText("$basePath/servers/$encodedServer/config") { txt ->
      editSettingsConfig = txt ?: ""
      editSettingsLoading = false
    }
  }

  fun currentProfileSetting(): Hysteria2ProfileSettingUi = setting ?: defaultHysteria2ProfileSettingUi()

  fun saveProfileSetting(next: Hysteria2ProfileSettingUi, onDone: ((Boolean) -> Unit)? = null) {
    val normalized = next.copy(
      mode = normalizeHysteria2Mode(next.mode),
      t2sPort = next.t2sPort ?: 12590,
      t2sWebPort = next.t2sWebPort ?: 8059,
      tun = normalizeHysteria2TunName(next.tun),
      dns = normalizeHysteria2Dns(next.dns),
      tun2socksLogLevel = normalizeHysteria2Tun2socksLogLevel(next.tun2socksLogLevel),
      protoMode = normalizeHysteria2ProtoMode(next.protoMode),
    )
    when {
      normalized.t2sPort !in 1..65535 -> {
        showSnack(context.getString(R.string.hysteria2_fill_t2s_port))
        onDone?.invoke(false)
        return
      }
      normalized.t2sWebPort !in 1..65535 -> {
        showSnack(context.getString(R.string.hysteria2_fill_t2s_web_port))
        onDone?.invoke(false)
        return
      }
      normalized.t2sPort == normalized.t2sWebPort -> {
        showSnack(context.getString(R.string.hysteria2_profile_ports_must_differ))
        onDone?.invoke(false)
        return
      }
      normalized.isVpn && normalized.tun.isBlank() -> {
        showSnack(context.getString(R.string.hysteria2_enter_tun_interface))
        onDone?.invoke(false)
        return
      }
      normalized.isVpn && normalized.dns.isEmpty() -> {
        showSnack(context.getString(R.string.hysteria2_enter_dns))
        onDone?.invoke(false)
        return
      }
      normalized.isVpn && (servers.size != 1 || servers.count { it.enabled } != 1) -> {
        showSnack(context.getString(R.string.hysteria2_vpn_requires_one_enabled_server))
        onDone?.invoke(false)
        return
      }
      normalized.isT2s -> {
        val t2sConflict = findHysteria2PortConflictLabel(globalPortRegistry, normalized.t2sPort ?: 0, hysteria2T2sPortLabel(profile))
        val webConflict = findHysteria2PortConflictLabel(globalPortRegistry, normalized.t2sWebPort ?: 0, hysteria2T2sWebPortLabel(profile))
        when {
          t2sConflict != null -> {
            showSnack(context.getString(R.string.hysteria2_t2s_port_busy_fmt, t2sConflict))
            onDone?.invoke(false)
            return
          }
          webConflict != null -> {
            showSnack(context.getString(R.string.hysteria2_t2s_web_port_busy_fmt, webConflict))
            onDone?.invoke(false)
            return
          }
        }
      }
    }

    settingSaving = true
    actions.saveJsonData("$basePath/setting", normalized.toJson()) { ok ->
      settingSaving = false
      if (ok) {
        setting = normalized
        refreshGlobalPortRegistry()
      } else {
        showSnack(context.getString(R.string.hysteria2_autosave_failed))
      }
      onDone?.invoke(ok)
    }
  }

  fun saveProfileSetting(t2sPortText: String, t2sWebPortText: String) {
    val current = currentProfileSetting()
    saveProfileSetting(
      current.copy(
        mode = HYSTERIA2_MODE_T2S,
        t2sPort = t2sPortText.trim().toIntOrNull(),
        t2sWebPort = t2sWebPortText.trim().toIntOrNull(),
      )
    )
  }


  fun switchHysteria2Mode(nextMode: String) {
    val current = currentProfileSetting()
    if (nextMode == HYSTERIA2_MODE_VPN && (servers.size != 1 || servers.count { it.enabled } != 1)) {
      showSnack(context.getString(R.string.hysteria2_vpn_requires_one_enabled_server))
      return
    }
    val next = current.copy(mode = if (nextMode == HYSTERIA2_MODE_VPN) HYSTERIA2_MODE_VPN else HYSTERIA2_MODE_T2S)
    saveProfileSetting(next)
  }



  if (showCreateServer) {
    Hysteria2CreateServerDialog(
      existing = servers.map { it.name },
      onDismiss = { showCreateServer = false },
      onCreate = { name ->
        showCreateServer = false
        actions.createHysteria2Server(profile, name) { created ->
          if (created != null) {
            showSnack(context.getString(R.string.hysteria2_server_created_fmt, created))
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

  if (editSettingsServer != null) {
    val serverName = editSettingsServer ?: ""
    val server = servers.firstOrNull { it.name == serverName }
    Hysteria2ServerSettingsDialog(
      profile = profile,
      serverName = serverName,
      server = server,
      configText = editSettingsConfig,
      portRegistry = globalPortRegistry,
      loading = editSettingsLoading,
      onDismiss = { if (!editSettingsLoading) editSettingsServer = null },
      onSave = { nextServer, nextConfig ->
        val encodedServer = URLEncoder.encode(serverName, "UTF-8")
        editSettingsLoading = true
        actions.saveText("$basePath/servers/$encodedServer/config", nextConfig) { configOk ->
          if (!configOk) {
            editSettingsLoading = false
            showSnack(context.getString(R.string.save_failed))
            return@saveText
          }
          val payload = JSONObject()
            .put("enabled", nextServer.enabled)
            .put("socks5_port", nextServer.port ?: 11590)
            .put("log_level", nextServer.logLevel.ifBlank { "info" })
          actions.saveJsonData("$basePath/servers/$encodedServer/setting", payload) { settingOk ->
            editSettingsLoading = false
            if (settingOk) {
              editSettingsServer = null
              showSnack(context.getString(R.string.common_saved))
              refreshServers()
              refreshGlobalPortRegistry()
            } else {
              showSnack(context.getString(R.string.hysteria2_json_saved_settings_failed))
            }
          }
        }
      },
    )
  }

  if (editServer != null) {
    AlertDialog(
      onDismissRequest = { if (!editLoading) editServer = null },
      title = { Text(stringResource(R.string.hysteria2_json_title_fmt, editServer ?: "")) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          if (editLoading) {
            StableLinearProgressIndicator(visible = true)
          }
          OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            singleLine = false,
            label = { Text(stringResource(R.string.hysteria2_client_config_json)) },
          )
        }
      },
      confirmButton = {
        Button(enabled = !editLoading && editText != editLastLoadedText, onClick = {
          val serverName = editServer ?: return@Button
          val parsed = runCatching { JSONObject(editText.trim()) }.getOrElse {
            showSnack(context.getString(R.string.hysteria2_invalid_json_fmt, it.message ?: context.getString(R.string.hysteria2_parse_error)))
            return@Button
          }
          val normalizedText = parsed.toString(2)
          val encodedServer = URLEncoder.encode(serverName, "UTF-8")
          editLoading = true
          actions.saveText("$basePath/servers/$encodedServer/config", normalizedText) { ok ->
            editLoading = false
            if (ok) {
              editText = normalizedText
              editLastLoadedText = normalizedText
              editServer = null
              showSnack(context.getString(R.string.common_saved))
              refreshServers()
              refreshGlobalPortRegistry()
            } else {
              showSnack(context.getString(R.string.save_failed))
            }
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



  if (program == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(stringResource(R.string.program_not_found))
    }
    return
  }

  val activeSetting = currentProfileSetting()
  val enabledServerCount = servers.count { it.enabled }
  val vpnStructureInvalid = activeSetting.isVpn && (servers.size != 1 || enabledServerCount != 1)
  val activeWebPanelPort = activeSetting.t2sWebPort?.takeIf { it in 1..65535 }
  val hysteria2PanelUrl = remember(activeWebPanelPort) { activeWebPanelPort?.let { hysteria2WebPanelUrl(it) } }
  val hysteria2WebPanelVisible = prof?.enabled == true && activeSetting.isT2s && activeWebPanelPort != null && !isOnlyZdtdAppSelected(selectedApps)

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(scroll)
      .padding(horizontal = if (compact) 12.dp else 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    Hysteria2ProfileEnabledCard(
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("hysteria2", profile, v) },
    )

    AnimatedVisibility(
      visible = hysteria2WebPanelVisible,
      enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
      exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(180)),
    ) {
      Hysteria2WebPanelCard(
        checking = hysteria2WebPanelChecking,
        onOpen = {
          val port = activeWebPanelPort
          val url = hysteria2PanelUrl
          if (port == null || url == null) {
            showSnack(context.getString(R.string.web_panel_unavailable))
          } else if (!hysteria2WebPanelChecking) {
            scope.launch {
              hysteria2WebPanelChecking = true
              val available = isHysteria2WebPanelPortOpen(port)
              hysteria2WebPanelChecking = false
              if (available) {
                context.startActivity(
                  Intent(context, T2sPanelActivity::class.java)
                    .putExtra(T2sPanelActivity.EXTRA_SCOPE, hysteria2WebPanelScope(profile))
                    .putExtra(T2sPanelActivity.EXTRA_PORT, port)
                    .putExtra(T2sPanelActivity.EXTRA_TITLE, "hysteria2 / $profile")
                )
              } else {
                showSnack(context.getString(R.string.web_panel_unavailable))
              }
            }
          }
        },
      )
    }

    Hysteria2ModeSwitchCard(
      setting = activeSetting,
      loading = settingLoading || serversLoading,
      saving = settingSaving,
      serverCount = servers.size,
      enabledServerCount = enabledServerCount,
      onSwitchMode = ::switchHysteria2Mode,
    )

    AnimatedVisibility(
      visible = activeSetting.isT2s,
      enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
      exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(180)),
    ) {
      Hysteria2ProfileSettingCard(
        setting = activeSetting,
        loading = settingLoading,
        saving = settingSaving,
        onSave = ::saveProfileSetting,
      )
    }

    AnimatedVisibility(
      visible = tproxyEnabled && activeSetting.isT2s,
      enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
      exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(180)),
    ) {
      Hysteria2ProtoModeCard(
        protoMode = activeSetting.protoMode,
        loading = settingLoading,
        saving = settingSaving,
        onSelect = { value -> saveProfileSetting(activeSetting.copy(protoMode = value)) },
      )
    }

    AnimatedVisibility(
      visible = activeSetting.isVpn,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      Hysteria2VpnProfileCard(
        setting = activeSetting,
        loading = settingLoading || serversLoading,
        saving = settingSaving,
        onSave = { next -> saveProfileSetting(next) },
      )
    }

    AnimatedVisibility(
      visible = vpnStructureInvalid,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      Hysteria2VpnStructureWarningCard(onSwitchToT2s = { switchHysteria2Mode(HYSTERIA2_MODE_T2S) })
    }

    AppListPickerCard(
      title = stringResource(R.string.hysteria2_apps_title),
      desc = stringResource(R.string.apps_common_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
      programs = programs,
      onSavedSelection = { selectedApps = it },
    )

    Hysteria2ServersSection(
      profile = profile,
      basePath = basePath,
      setting = activeSetting,
      servers = servers,
      portRegistry = globalPortRegistry,
      loading = serversLoading,
      actions = actions,
      snackHost = snackHost,
      onCreateServer = {
        if (activeSetting.isVpn && servers.isNotEmpty()) {
          showSnack(context.getString(R.string.hysteria2_vpn_only_one_server_allowed))
        } else {
          showCreateServer = true
        }
      },
      onRefresh = { refreshServers() },
      onServerSaved = { updated ->
        servers = servers.map { if (it.name == updated.name) updated else it }
        refreshGlobalPortRegistry()
      },
      onEditConfig = ::openEditor,
      onEditSettings = ::openSettingsEditor,
    )

    Spacer(Modifier.height(effectiveBottomContentPadding))
  }
}

@Composable
private fun Hysteria2ProfileSettingCard(
  setting: Hysteria2ProfileSettingUi?,
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

  Hysteria2SectionCard(
    title = stringResource(R.string.hysteria2_t2s_settings_title),
    desc = stringResource(R.string.hysteria2_t2s_settings_desc),
    accent = Color(0xFF38BDF8),
    icon = {
      Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(21.dp))
    },
  ) {
    StableLinearProgressIndicator(visible = loading || saving)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlinedTextField(
        value = t2sPortText,
        onValueChange = { t2sPortText = it.filter(Char::isDigit) },
        modifier = Modifier.weight(1f),
        enabled = !loading,
        label = { Text(stringResource(R.string.hysteria2_t2s_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
      OutlinedTextField(
        value = t2sWebPortText,
        onValueChange = { t2sWebPortText = it.filter(Char::isDigit) },
        modifier = Modifier.weight(1f),
        enabled = !loading,
        label = { Text(stringResource(R.string.hysteria2_t2s_web_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
    }

    val footerText = when {
      parsedT2sPort == null || parsedT2sWebPort == null -> stringResource(R.string.hysteria2_apply_after_restart)
      parsedT2sPort == parsedT2sWebPort -> stringResource(R.string.hysteria2_profile_ports_must_differ)
      parsedT2sPort !in 1..65535 || parsedT2sWebPort !in 1..65535 -> stringResource(R.string.hysteria2_apply_after_restart)
      saving -> stringResource(R.string.common_loading)
      else -> stringResource(R.string.hysteria2_apply_after_restart)
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
private fun Hysteria2ProtoModeCard(
  protoMode: String,
  loading: Boolean,
  saving: Boolean,
  onSelect: (String) -> Unit,
) {
  val accent = Color(0xFFA855F7)
  Hysteria2SectionCard(
    title = stringResource(R.string.hysteria2_route_mode_title),
    desc = stringResource(R.string.hysteria2_route_mode_desc),
    accent = accent,
    icon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp)) },
  ) {
    StableLinearProgressIndicator(visible = loading || saving)
    val options = listOf(
      "tcp" to "TCP",
      "tcp_udp" to "TCP + UDP",
    )
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
      border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        options.forEach { (value, label) ->
          val selected = normalizeHysteria2ProtoMode(protoMode) == value
          val enabled = !loading && !saving
          val bg by animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.22f) else Color.Transparent,
            animationSpec = tween(220),
            label = "hysteria2ProtoBg",
          )
          val borderColor by animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.55f) else Color.Transparent,
            animationSpec = tween(220),
            label = "hysteria2ProtoBorder",
          )
          val textColor by animateColorAsState(
            targetValue = if (selected) Color(0xFFC084FC) else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.70f else 0.42f),
            animationSpec = tween(220),
            label = "hysteria2ProtoText",
          )
          Surface(
            modifier = Modifier.weight(1f).clickable(enabled = enabled && !selected) { onSelect(value) },
            shape = RoundedCornerShape(11.dp),
            color = bg,
            contentColor = textColor,
            border = BorderStroke(1.dp, borderColor),
          ) {
            Text(
              text = label,
              modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
              color = textColor,
              maxLines = 1,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun Hysteria2ModeSwitchCard(
  setting: Hysteria2ProfileSettingUi,
  loading: Boolean,
  saving: Boolean,
  serverCount: Int,
  enabledServerCount: Int,
  onSwitchMode: (String) -> Unit,
) {
  val accent = if (setting.isVpn) Color(0xFF22C55E) else Color(0xFF38BDF8)
  Hysteria2SectionCard(
    title = stringResource(R.string.hysteria2_mode_title),
    desc = stringResource(R.string.hysteria2_mode_desc),
    accent = accent,
    icon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp)) },
  ) {
    StableLinearProgressIndicator(visible = loading || saving)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Hysteria2ModeOption(
        modifier = Modifier.weight(1f),
        selected = setting.isT2s,
        enabled = !loading && !saving,
        accent = Color(0xFF38BDF8),
        title = "T2S",
        desc = stringResource(R.string.hysteria2_mode_t2s_desc),
        onClick = { onSwitchMode(HYSTERIA2_MODE_T2S) },
      )
      Hysteria2ModeOption(
        modifier = Modifier.weight(1f),
        selected = setting.isVpn,
        enabled = !loading && !saving,
        accent = Color(0xFF22C55E),
        title = "VPN / tun2proxy",
        desc = stringResource(R.string.hysteria2_mode_vpn_desc),
        onClick = { onSwitchMode(HYSTERIA2_MODE_VPN) },
      )
    }

    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
      border = BorderStroke(1.dp, accent.copy(alpha = 0.14f)),
    ) {
      Text(
        text = if (setting.isVpn && (serverCount != 1 || enabledServerCount != 1)) {
          stringResource(R.string.hysteria2_vpn_requires_one_enabled_server)
        } else if (setting.isVpn) {
          "apps → tun2socks → Hysteria2 SOCKS5 → server"
        } else {
          stringResource(R.string.hysteria2_apply_after_restart)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      )
    }
  }
}

@Composable
private fun Hysteria2ModeOption(
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
private fun Hysteria2VpnStructureWarningCard(onSwitchToT2s: () -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.70f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.hysteria2_vpn_structure_invalid_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.hysteria2_vpn_structure_invalid_desc),
        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedButton(onClick = onSwitchToT2s, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.hysteria2_switch_to_t2s))
      }
    }
  }
}

@Composable
private fun Hysteria2VpnProfileCard(
  setting: Hysteria2ProfileSettingUi,
  loading: Boolean,
  saving: Boolean,
  onSave: (Hysteria2ProfileSettingUi) -> Unit,
) {
  var tunText by remember(setting.tun) { mutableStateOf(setting.tun) }
  var dnsText by remember(setting.dns) { mutableStateOf(setting.dns.joinToString("\n")) }
  var logLevel by remember(setting.tun2socksLogLevel) { mutableStateOf(setting.tun2socksLogLevel) }

  val normalizedTun = remember(tunText) { normalizeHysteria2TunName(tunText) }
  val normalizedDns = remember(dnsText) { parseDnsText(dnsText) }
  val normalizedLogLevel = remember(logLevel) { normalizeHysteria2Tun2socksLogLevel(logLevel) }
  val changed = normalizedTun != setting.tun || normalizedDns != setting.dns || normalizedLogLevel != setting.tun2socksLogLevel
  val valid = normalizedTun.isNotBlank() && normalizedDns.isNotEmpty()

  LaunchedEffect(normalizedTun, normalizedDns, normalizedLogLevel, loading, saving, changed, valid) {
    if (loading || saving || !changed || !valid) return@LaunchedEffect
    delay(700)
    onSave(
      setting.copy(
        mode = HYSTERIA2_MODE_VPN,
        tun = normalizedTun,
        dns = normalizedDns,
        tun2socksLogLevel = normalizedLogLevel,
      )
    )
  }

  Hysteria2SectionCard(
    title = stringResource(R.string.hysteria2_vpn_profile_title),
    desc = stringResource(R.string.hysteria2_vpn_profile_desc),
    accent = Color(0xFF22C55E),
    icon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp)) },
  ) {
    StableLinearProgressIndicator(visible = loading || saving)

    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color(0xFF22C55E).copy(alpha = 0.11f),
      border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.22f)),
    ) {
      Text(
        "apps → tun2socks → Hysteria2 SOCKS5 → server",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
      )
    }

    OutlinedTextField(
      value = tunText,
      onValueChange = { tunText = normalizeHysteria2TunName(it) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !loading,
      singleLine = true,
      label = { Text("TUN interface") },
      supportingText = { Text(stringResource(R.string.hysteria2_tun_example)) },
    )
    OutlinedTextField(
      value = dnsText,
      onValueChange = { dnsText = it },
      modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
      enabled = !loading,
      singleLine = false,
      label = { Text("DNS") },
      supportingText = { Text(stringResource(R.string.hysteria2_dns_supporting)) },
    )

    Text(
      "tun2socks log level",
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      HYSTERIA2_TUN2SOCKS_LOG_LEVELS.chunked(3).forEach { rowLevels ->
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
      if (saving) stringResource(R.string.common_loading) else stringResource(R.string.hysteria2_auto_save_restart_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
    )
  }
}

@Composable
private fun Hysteria2ServersSection(
  profile: String,
  basePath: String,
  setting: Hysteria2ProfileSettingUi,
  servers: List<Hysteria2ServerUi>,
  portRegistry: Hysteria2PortRegistry,
  loading: Boolean,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  onCreateServer: () -> Unit,
  onRefresh: () -> Unit,
  onServerSaved: (Hysteria2ServerUi) -> Unit,
  onEditConfig: (String) -> Unit,
  onEditSettings: (String) -> Unit,
) {
  val createEnabled = setting.isT2s || servers.isEmpty()

  Hysteria2SectionCard(
    title = stringResource(R.string.hysteria2_servers_title),
    desc = if (setting.isVpn) stringResource(R.string.hysteria2_servers_vpn_desc) else stringResource(R.string.hysteria2_servers_t2s_desc),
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
    Surface(
      modifier = Modifier
        .fillMaxWidth()
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
        Text(stringResource(R.string.hysteria2_add_server), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }

    StableLinearProgressIndicator(visible = loading)
    if (!loading && servers.isEmpty()) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.16f)),
      ) {
        Text(
          stringResource(R.string.hysteria2_no_servers),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        servers.forEach { server ->
          Hysteria2ServerCard(
            profile = profile,
            basePath = basePath,
            server = server,
            portRegistry = portRegistry,
            actions = actions,
            snackHost = snackHost,
            onRefresh = onRefresh,
            onServerSaved = onServerSaved,
            onEditConfig = { onEditConfig(server.name) },
            onEditSettings = { onEditSettings(server.name) },
            showPort = true,
          )
        }
      }
    }
  }
}

@Composable
private fun Hysteria2ServerCard(
  profile: String,
  basePath: String,
  server: Hysteria2ServerUi,
  portRegistry: Hysteria2PortRegistry,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  onRefresh: () -> Unit,
  onServerSaved: (Hysteria2ServerUi) -> Unit,
  onEditConfig: () -> Unit,
  onEditSettings: () -> Unit,
  showPort: Boolean = true,
) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val scope = rememberCoroutineScope()
  val dialogScrollState = rememberScrollState()
  val maxDialogHeight = configuration.screenHeightDp.dp * 0.92f
  var enabled by remember(server.name, server.enabled) { mutableStateOf(server.enabled) }
  var portText by remember(server.name, server.port) { mutableStateOf((server.port ?: 0).toString()) }
  var saving by remember(server.name) { mutableStateOf(false) }
  var askDelete by remember(server.name) { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun autoSave() {
    val port = if (showPort) portText.trim().toIntOrNull() else (server.port ?: 11590)
    if (port == null || port !in 1..65535) return
    val conflict = findHysteria2PortConflictLabel(portRegistry, port, hysteria2ServerPortLabel(profile, server.name))
    if (conflict != null) {
      showSnack(context.getString(R.string.hysteria2_socks5_port_busy_fmt, conflict))
      return
    }
    saving = true
    val encodedServer = URLEncoder.encode(server.name, "UTF-8")
    val payload = JSONObject().put("enabled", enabled).put("socks5_port", port).put("log_level", server.logLevel.ifBlank { "info" })
    actions.saveJsonData("$basePath/servers/$encodedServer/setting", payload) { ok ->
      saving = false
      if (ok) {
        onServerSaved(server.copy(enabled = enabled, port = port))
      } else {
        showSnack(context.getString(R.string.hysteria2_autosave_failed))
      }
    }
  }

  if (askDelete) {
    AlertDialog(
      onDismissRequest = { askDelete = false },
      title = { Text(stringResource(R.string.hysteria2_delete_server_title)) },
      text = { Text(stringResource(R.string.hysteria2_delete_server_message_fmt, profile, server.name)) },
      confirmButton = {
        Button(onClick = {
          askDelete = false
          actions.deleteHysteria2Server(profile, server.name) { ok ->
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
  val currentServerLabel = hysteria2ServerPortLabel(profile, server.name)
  val parsedPort = portText.trim().toIntOrNull()
  val portConflict = parsedPort?.let { findHysteria2PortConflictLabel(portRegistry, it, currentServerLabel) }
  val changed = enabled != server.enabled || portText.trim() != currentPortText
  val validPort = !showPort || (parsedPort != null && parsedPort in 1..65535 && portConflict == null)

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
              if (saving) stringResource(R.string.common_loading) else stringResource(R.string.hysteria2_socks5_autosave_hint),
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
            label = { Text(stringResource(R.string.hysteria2_socks5_backend_port)) },
            isError = portConflict != null,
            supportingText = { portConflict?.let { Text(stringResource(R.string.hysteria2_port_busy_fmt, it)) } },
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
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
              Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(5.dp))
              Text("JSON", fontWeight = FontWeight.Bold, maxLines = 1)
            }
          }
          Surface(
            modifier = Modifier.weight(1f).clickable { onEditSettings() },
            shape = RoundedCornerShape(100.dp),
            color = Color(0xFFFFBC00).copy(alpha = 0.13f),
            contentColor = Color(0xFFFFD166),
            border = BorderStroke(1.dp, Color(0xFFFFBC00).copy(alpha = 0.30f)),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
              Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(5.dp))
              Text("UI", fontWeight = FontWeight.Bold, maxLines = 1)
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
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center,
            ) {
              Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(5.dp))
              Text(stringResource(R.string.action_delete), fontWeight = FontWeight.Bold, maxLines = 1)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Hysteria2CreateServerDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val scope = rememberCoroutineScope()
  val dialogScrollState = rememberScrollState()
  val maxDialogHeight = configuration.screenHeightDp.dp * 0.92f
  val existingNorm = remember(existing) { existing.map { normalizeHysteria2ServerName(it) }.toSet() }
  var raw by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val name = remember(raw) { normalizeHysteria2ServerName(raw) }

  val snack = createSnackFunction(scope, snackHost)

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
                stringResource(R.string.hysteria2_new_server_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                stringResource(R.string.hysteria2_server_name_rules),
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
            label = { Text(stringResource(R.string.hysteria2_server_name_label)) },
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
                    val msg = context.getString(R.string.hysteria2_server_exists)
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


private fun hysteria2ConfigString(obj: JSONObject, key: String): String = obj.optString(key, "")

private fun hysteria2NestedObject(root: JSONObject, key: String): JSONObject = root.optJSONObject(key) ?: JSONObject()


private fun normalizeHysteria2ClientConfigForUi(root: JSONObject, port: Int) {
  listOf("http", "tcpForwarding", "udpForwarding", "tcpTProxy", "udpTProxy", "tcpRedirect", "tun", "inbounds", "outbounds", "route", "dns").forEach { root.remove(it) }
  val socks5 = root.optJSONObject("socks5") ?: JSONObject()
  socks5.put("listen", "127.0.0.1:$port")
  if (!socks5.has("disableUDP")) socks5.put("disableUDP", false)
  root.put("socks5", socks5)
}

@Composable
private fun Hysteria2ServerSettingsDialog(
  profile: String,
  serverName: String,
  server: Hysteria2ServerUi?,
  configText: String,
  portRegistry: Hysteria2PortRegistry,
  loading: Boolean,
  onDismiss: () -> Unit,
  onSave: (Hysteria2ServerUi, String) -> Unit,
) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val maxDialogHeight = configuration.screenHeightDp.dp * 0.92f
  val dialogScrollState = rememberScrollState()
  val parsed = remember(configText) { runCatching { JSONObject(configText.takeIf { it.trim().isNotBlank() } ?: "{}") }.getOrElse { JSONObject() } }
  val tls = remember(configText) { hysteria2NestedObject(parsed, "tls") }
  val socks5 = remember(configText) { hysteria2NestedObject(parsed, "socks5") }
  val obfs = remember(configText) { hysteria2NestedObject(parsed, "obfs") }
  val salamander = remember(configText) { hysteria2NestedObject(obfs, "salamander") }
  val bandwidth = remember(configText) { hysteria2NestedObject(parsed, "bandwidth") }
  val congestion = remember(configText) { hysteria2NestedObject(parsed, "congestion") }
  val quic = remember(configText) { hysteria2NestedObject(parsed, "quic") }

  var enabled by remember(serverName, server?.enabled) { mutableStateOf(server?.enabled ?: false) }
  var portText by remember(serverName, server?.port) { mutableStateOf((server?.port ?: 11590).toString()) }
  var logLevel by remember(serverName, server?.logLevel) { mutableStateOf(server?.logLevel?.takeIf { it.isNotBlank() } ?: "info") }
  var remoteServer by remember(configText) { mutableStateOf(hysteria2ConfigString(parsed, "server")) }
  var auth by remember(configText) { mutableStateOf(hysteria2ConfigString(parsed, "auth")) }
  var sni by remember(configText) { mutableStateOf(tls.optString("sni", "")) }
  var tlsInsecure by remember(configText) { mutableStateOf(tls.optBoolean("insecure", false)) }
  var fastOpen by remember(configText) { mutableStateOf(parsed.optBoolean("fastOpen", false)) }
  var lazy by remember(configText) { mutableStateOf(parsed.optBoolean("lazy", false)) }
  var obfsType by remember(configText) { mutableStateOf(obfs.optString("type", "")) }
  var obfsPassword by remember(configText) { mutableStateOf(salamander.optString("password", "")) }
  var bandwidthUp by remember(configText) { mutableStateOf(bandwidth.optString("up", "")) }
  var bandwidthDown by remember(configText) { mutableStateOf(bandwidth.optString("down", "")) }
  var bandwidthDisableLossCompensation by remember(configText) { mutableStateOf(bandwidth.optBoolean("disableLossCompensation", false)) }
  var tlsPinSha256 by remember(configText) { mutableStateOf(tls.optString("pinSHA256", "")) }
  var tlsCa by remember(configText) { mutableStateOf(tls.optString("ca", "")) }
  var tlsClientCertificate by remember(configText) { mutableStateOf(tls.optString("clientCertificate", "")) }
  var tlsClientKey by remember(configText) { mutableStateOf(tls.optString("clientKey", "")) }
  var congestionType by remember(configText) { mutableStateOf(congestion.optString("type", "")) }
  var congestionBbrProfile by remember(configText) { mutableStateOf(congestion.optString("bbrProfile", "")) }
  var quicMaxIdleTimeout by remember(configText) { mutableStateOf(quic.optString("maxIdleTimeout", "")) }
  var quicKeepAlivePeriod by remember(configText) { mutableStateOf(quic.optString("keepAlivePeriod", "")) }
  var quicDisablePathMtuDiscovery by remember(configText) { mutableStateOf(quic.optBoolean("disablePathMTUDiscovery", false)) }
  var socksUsername by remember(configText) { mutableStateOf(socks5.optString("username", "")) }
  var socksPassword by remember(configText) { mutableStateOf(socks5.optString("password", "")) }
  var error by remember(serverName) { mutableStateOf<String?>(null) }
  val parsedSocksPort = portText.toIntOrNull()
  val settingsPortConflict = parsedSocksPort?.let { findHysteria2PortConflictLabel(portRegistry, it, hysteria2ServerPortLabel(profile, serverName)) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .heightIn(max = maxDialogHeight)
        .navigationBarsPadding(),
      shape = RoundedCornerShape(28.dp),
      color = Color(0xFF17131E).copy(alpha = 0.98f),
      contentColor = MaterialTheme.colorScheme.onSurface,
      border = BorderStroke(1.dp, Color(0xFFFFBC00).copy(alpha = 0.34f)),
    ) {
      Column(
        modifier = Modifier
          .verticalScroll(dialogScrollState)
          .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = Color(0xFFFFBC00).copy(alpha = 0.18f),
            contentColor = Color(0xFFFFD166),
            border = BorderStroke(1.dp, Color(0xFFFFBC00).copy(alpha = 0.36f)),
          ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(23.dp)) } }
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.hysteria2_server_ui_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(serverName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
          }
        }

        if (loading) StableLinearProgressIndicator(visible = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.hysteria2_enabled), fontWeight = FontWeight.Bold)
          Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        OutlinedTextField(value = remoteServer, onValueChange = { remoteServer = it.trim(); error = null }, label = { Text(stringResource(R.string.hysteria2_server_field_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = auth, onValueChange = { auth = it }, label = { Text("auth") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
          value = portText,
          onValueChange = { portText = it.filter(Char::isDigit).take(5); error = null },
          label = { Text(stringResource(R.string.hysteria2_local_socks5_port)) },
          singleLine = true,
          isError = settingsPortConflict != null,
          supportingText = { settingsPortConflict?.let { Text(stringResource(R.string.hysteria2_port_busy_fmt, it)) } },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = logLevel, onValueChange = { logLevel = it.trim().lowercase().take(12) }, label = { Text("Hysteria log level") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = sni, onValueChange = { sni = it.trim() }, label = { Text("TLS SNI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("TLS insecure")
          Switch(checked = tlsInsecure, onCheckedChange = { tlsInsecure = it })
        }
        OutlinedTextField(value = tlsPinSha256, onValueChange = { tlsPinSha256 = it.trim() }, label = { Text("TLS pinSHA256 optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tlsCa, onValueChange = { tlsCa = it.trim() }, label = { Text("TLS CA path optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tlsClientCertificate, onValueChange = { tlsClientCertificate = it.trim() }, label = { Text("TLS clientCertificate optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tlsClientKey, onValueChange = { tlsClientKey = it.trim() }, label = { Text("TLS clientKey optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("fastOpen")
          Switch(checked = fastOpen, onCheckedChange = { fastOpen = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("lazy")
          Switch(checked = lazy, onCheckedChange = { lazy = it })
        }
        OutlinedTextField(value = obfsType, onValueChange = { obfsType = it.trim().lowercase() }, label = { Text(stringResource(R.string.hysteria2_obfs_type_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = obfsPassword, onValueChange = { obfsPassword = it }, label = { Text("obfs salamander password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = bandwidthUp, onValueChange = { bandwidthUp = it.trim() }, label = { Text(stringResource(R.string.hysteria2_bandwidth_up_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = bandwidthDown, onValueChange = { bandwidthDown = it.trim() }, label = { Text(stringResource(R.string.hysteria2_bandwidth_down_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("bandwidth.disableLossCompensation")
          Switch(checked = bandwidthDisableLossCompensation, onCheckedChange = { bandwidthDisableLossCompensation = it })
        }
        OutlinedTextField(value = congestionType, onValueChange = { congestionType = it.trim().lowercase() }, label = { Text(stringResource(R.string.hysteria2_congestion_type_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = congestionBbrProfile, onValueChange = { congestionBbrProfile = it.trim().lowercase() }, label = { Text("congestion.bbrProfile") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = quicMaxIdleTimeout, onValueChange = { quicMaxIdleTimeout = it.trim() }, label = { Text("quic.maxIdleTimeout optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = quicKeepAlivePeriod, onValueChange = { quicKeepAlivePeriod = it.trim() }, label = { Text("quic.keepAlivePeriod optional") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("quic.disablePathMTUDiscovery")
          Switch(checked = quicDisablePathMtuDiscovery, onCheckedChange = { quicDisablePathMtuDiscovery = it })
        }
        OutlinedTextField(value = socksUsername, onValueChange = { socksUsername = it }, label = { Text(stringResource(R.string.hysteria2_socks5_username_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = socksPassword, onValueChange = { socksPassword = it }, label = { Text(stringResource(R.string.hysteria2_socks5_password_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(onClick = onDismiss, enabled = !loading, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
          Button(enabled = !loading, modifier = Modifier.weight(1f), onClick = {
            val port = portText.toIntOrNull()
            if (remoteServer.isBlank()) {
              error = context.getString(R.string.hysteria2_server_required)
              return@Button
            }
            if (port == null || port !in 1..65535) {
              error = context.getString(R.string.hysteria2_invalid_socks5_port)
              return@Button
            }
            settingsPortConflict?.let {
              error = context.getString(R.string.hysteria2_socks5_port_busy_fmt, it)
              return@Button
            }
            val root = runCatching { JSONObject(configText.takeIf { it.trim().isNotBlank() } ?: "{}") }.getOrElse { JSONObject() }
            root.put("server", remoteServer.trim())
            if (auth.isBlank()) root.remove("auth") else root.put("auth", auth)
            val nextTls = root.optJSONObject("tls") ?: JSONObject()
            if (sni.isBlank()) nextTls.remove("sni") else nextTls.put("sni", sni)
            if (tlsPinSha256.isBlank()) nextTls.remove("pinSHA256") else nextTls.put("pinSHA256", tlsPinSha256)
            if (tlsCa.isBlank()) nextTls.remove("ca") else nextTls.put("ca", tlsCa)
            if (tlsClientCertificate.isBlank()) nextTls.remove("clientCertificate") else nextTls.put("clientCertificate", tlsClientCertificate)
            if (tlsClientKey.isBlank()) nextTls.remove("clientKey") else nextTls.put("clientKey", tlsClientKey)
            nextTls.put("insecure", tlsInsecure)
            root.put("tls", nextTls)
            root.put("fastOpen", fastOpen)
            root.put("lazy", lazy)
            if (obfsType.isBlank() && obfsPassword.isBlank()) {
              root.remove("obfs")
            } else {
              val nextObfs = JSONObject().put("type", obfsType.ifBlank { "salamander" })
              if (obfsPassword.isNotBlank()) nextObfs.put("salamander", JSONObject().put("password", obfsPassword))
              root.put("obfs", nextObfs)
            }
            if (bandwidthUp.isBlank() && bandwidthDown.isBlank() && !bandwidthDisableLossCompensation) {
              root.remove("bandwidth")
            } else {
              val bw = JSONObject()
              if (bandwidthUp.isNotBlank()) bw.put("up", bandwidthUp)
              if (bandwidthDown.isNotBlank()) bw.put("down", bandwidthDown)
              if (bandwidthDisableLossCompensation) bw.put("disableLossCompensation", true)
              root.put("bandwidth", bw)
            }
            if (congestionType.isBlank() && congestionBbrProfile.isBlank()) {
              root.remove("congestion")
            } else {
              val nextCongestion = JSONObject()
              if (congestionType.isNotBlank()) nextCongestion.put("type", congestionType)
              if (congestionBbrProfile.isNotBlank()) nextCongestion.put("bbrProfile", congestionBbrProfile)
              root.put("congestion", nextCongestion)
            }
            if (quicMaxIdleTimeout.isBlank() && quicKeepAlivePeriod.isBlank() && !quicDisablePathMtuDiscovery) {
              root.remove("quic")
            } else {
              val nextQuic = JSONObject()
              if (quicMaxIdleTimeout.isNotBlank()) nextQuic.put("maxIdleTimeout", quicMaxIdleTimeout)
              if (quicKeepAlivePeriod.isNotBlank()) nextQuic.put("keepAlivePeriod", quicKeepAlivePeriod)
              if (quicDisablePathMtuDiscovery) nextQuic.put("disablePathMTUDiscovery", true)
              root.put("quic", nextQuic)
            }
            val nextSocks = root.optJSONObject("socks5") ?: JSONObject()
            if (socksUsername.isBlank()) nextSocks.remove("username") else nextSocks.put("username", socksUsername)
            if (socksPassword.isBlank()) nextSocks.remove("password") else nextSocks.put("password", socksPassword)
            root.put("socks5", nextSocks)
            normalizeHysteria2ClientConfigForUi(root, port)
            onSave(Hysteria2ServerUi(serverName, enabled, port, logLevel.ifBlank { "info" }), root.toString(2))
          }) { Text(stringResource(R.string.action_save)) }
        }
      }
    }
  }
}
