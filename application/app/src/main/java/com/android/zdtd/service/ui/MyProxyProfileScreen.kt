package com.android.zdtd.service.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import com.android.zdtd.service.T2sPanelActivity
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import kotlin.coroutines.resume

private data class MyProxySettingUi(
  val t2sPort: Int?,
  val t2sWebPort: Int?,
)

private data class MyProxyUpstreamUi(
  val host: String,
  val ports: List<Int>,
  val backendMode: String,
  val backendPriority: String,
  val prioritySpeedAware: Boolean,
  val user: String,
  val pass: String,
  val wrappedHost: String,
  val wrappedPort: Int?,
  val wrappedUser: String,
  val wrappedPass: String,
  val protoMode: String = "tcp_udp",
)

private suspend fun awaitLoadJsonMyProxy(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextMyProxy(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonMyProxy(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private fun parseMyProxySettingUi(obj: JSONObject?): MyProxySettingUi {
  val data = obj?.optJSONObject("data") ?: obj
  return MyProxySettingUi(
    t2sPort = data?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 },
    t2sWebPort = data?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 },
  )
}

private fun parseMyProxyPortToken(token: String, allowZero: Boolean = false): Int? =
  token.trim()
    .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    ?.toIntOrNull()
    ?.takeIf { it in 1..65535 || (allowZero && it == 0) }

private fun normalizeMyProxyPortList(raw: String, allowZeroMarker: Boolean = false): List<Int>? {
  val zeroPositions = mutableListOf<Int>()
  val ports = raw
    .split(',')
    .mapIndexedNotNull { index, part ->
      val trimmed = part.trim()
      if (trimmed.isEmpty()) {
        null
      } else {
        val port = parseMyProxyPortToken(trimmed, allowZero = allowZeroMarker) ?: return null
        if (port == 0) zeroPositions += index
        port
      }
    }
  if (ports.isEmpty()) return null
  if (0 in ports) {
    if (!allowZeroMarker) return null
    if (zeroPositions.size != 1) return null
    val zeroIndex = zeroPositions.first()
    if (zeroIndex != 0 && zeroIndex != ports.lastIndex) return null
  }
  return ports.distinct().takeIf { it.size == ports.size }
}

private fun parseMyProxyPortValue(value: Any?): List<Int> = when (value) {
  null, JSONObject.NULL -> emptyList()
  is Number -> value.toInt().takeIf { it in 0..65535 }?.let { listOf(it) } ?: emptyList()
  is String -> normalizeMyProxyPortList(value, allowZeroMarker = true).orEmpty()
  is JSONArray -> {
    val out = mutableListOf<Int>()
    for (i in 0 until value.length()) {
      when (val item = value.opt(i)) {
        is Number -> item.toInt().takeIf { it in 0..65535 }?.let(out::add)
        is String -> parseMyProxyPortToken(item, allowZero = true)?.let(out::add)
      }
    }
    out.distinct()
  }
  else -> emptyList()
}

private fun normalizeMyProxyBackendMode(raw: String?): String =
  if (raw?.trim()?.lowercase() == "priority") "priority" else "balance"

private fun normalizeMyProxyProtoMode(raw: String?): String =
  if (raw?.trim()?.lowercase() == "tcp") "tcp" else "tcp_udp"

private fun sanitizeMyProxyBackendPriorityInput(raw: String): String = buildString {
  raw.forEach { ch ->
    when {
      ch.isDigit() -> append(ch)
      ch == ',' || ch == '，' || ch == '﹐' || ch == '､' || ch == '、' -> append(',')
      ch == ';' || ch == '；' || ch == '﹔' || ch == '︔' || ch == ';' || ch == '؛' -> append(';')
      ch.isWhitespace() -> append(ch)
    }
  }
}.take(192)

private fun normalizeMyProxyBackendPriority(raw: String, allowedPorts: List<Int>): String? {
  val text = sanitizeMyProxyBackendPriorityInput(raw).trim()
  if (text.isEmpty()) return ""
  val allowed = allowedPorts.toSet()
  if (allowed.isEmpty()) return null
  val used = linkedSetOf<Int>()
  val groups = mutableListOf<String>()
  for (groupRaw in text.split(';')) {
    val groupText = groupRaw.trim()
    if (groupText.isEmpty()) return null
    val groupPorts = mutableListOf<Int>()
    for (token in groupText.split(',')) {
      val port = parseMyProxyPortToken(token) ?: return null
      if (port !in allowed) return null
      if (!used.add(port)) return null
      groupPorts += port
    }
    if (groupPorts.isEmpty()) return null
    groups += groupPorts.joinToString(",")
  }
  return groups.joinToString(";")
}

private fun buildMyProxyUpstreamJson(
  host: String,
  ports: List<Int>,
  backendMode: String,
  backendPriority: String,
  prioritySpeedAware: Boolean,
  user: String,
  pass: String,
  wrappedHost: String,
  wrappedPort: Int?,
  wrappedUser: String,
  wrappedPass: String,
  protoMode: String,
): JSONObject =
  JSONObject()
    .put("host", host)
    .put("proto_mode", normalizeMyProxyProtoMode(protoMode))
    .put("backend_mode", normalizeMyProxyBackendMode(backendMode))
    .put("backend_priority", if (normalizeMyProxyBackendMode(backendMode) == "priority") backendPriority else "")
    .put("priority_speed_aware", normalizeMyProxyBackendMode(backendMode) == "priority" && prioritySpeedAware)
    .put("user", user)
    .put("pass", pass)
    .put(
      "wrapped_socks",
      JSONObject()
        .put("host", wrappedHost)
        .put("port", wrappedPort ?: 0)
        .put("user", wrappedUser)
        .put("pass", wrappedPass)
    )
    .also { obj ->
      if (ports.size == 1) {
        obj.put("port", ports.first())
      } else {
        obj.put("port", ports.joinToString(","))
      }
    }

private fun parseMyProxyUpstreamUi(obj: JSONObject?): MyProxyUpstreamUi {
  val data = obj?.optJSONObject("data") ?: obj
  val portsFromArray = parseMyProxyPortValue(data?.opt("ports"))
  val wrapped = data?.optJSONObject("wrapped_socks")
  return MyProxyUpstreamUi(
    host = data?.optString("host", "")?.trim().orEmpty(),
    ports = portsFromArray.takeIf { it.isNotEmpty() } ?: parseMyProxyPortValue(data?.opt("port")),
    backendMode = normalizeMyProxyBackendMode(data?.optString("backend_mode", "balance")),
    backendPriority = sanitizeMyProxyBackendPriorityInput(data?.optString("backend_priority", "").orEmpty()).trim(),
    prioritySpeedAware = data?.optBoolean("priority_speed_aware", false) == true,
    user = data?.optString("user", "")?.trim().orEmpty(),
    pass = data?.optString("pass", "") ?: "",
    wrappedHost = wrapped?.optString("host", "")?.trim().orEmpty(),
    wrappedPort = wrapped?.optInt("port", 0)?.takeIf { it in 1..65535 },
    wrappedUser = wrapped?.optString("user", "")?.trim().orEmpty(),
    wrappedPass = wrapped?.optString("pass", "") ?: "",
    protoMode = normalizeMyProxyProtoMode(data?.optString("proto_mode", "tcp_udp")),
  )
}

private fun myProxyWebPanelUrl(port: Int): String = "http://127.0.0.1:$port/"

private fun myProxyWebPanelScope(profile: String): String = "profile/myproxy/${profile.ifBlank { "main" }}"

private suspend fun isMyProxyWebPanelPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
  runCatching {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", port), 850)
    }
    true
  }.getOrDefault(false)
}

@Composable
private fun MyProxySectionCard(
  title: String,
  desc: String? = null,
  accent: Color = Color(0xFFFACC15),
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
private fun MyProxyProfileEnabledCard(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val accent = if (checked) Color(0xFF22C55E) else Color(0xFFEF4444)
  MyProxySectionCard(
    title = stringResource(R.string.enabled_card_profile_title),
    desc = stringResource(R.string.enabled_card_apply_hint),
    accent = accent,
    icon = {
      Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(22.dp))
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
private fun MyProxyWebPanelCard(
  checking: Boolean,
  onOpen: () -> Unit,
) {
  MyProxySectionCard(
    title = "t2s",
    desc = "Нативная панель состояния t2s",
    accent = Color(0xFF38BDF8),
    icon = {
      if (checking) {
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp,
        )
      } else {
        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(22.dp))
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
          "Панель",
          modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
    },
  )
}

@Composable
private fun FieldHint(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
  )
}

@Composable
private fun MyProxyProtoModeCard(
  protoMode: String,
  onSelect: (String) -> Unit,
) {
  val accent = Color(0xFFA855F7)
  MyProxySectionCard(
    title = stringResource(R.string.myproxy_proto_mode_title),
    desc = stringResource(R.string.myproxy_proto_mode_desc),
    accent = accent,
    icon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp)) },
  ) {
    val options = listOf(
      "tcp" to stringResource(R.string.myproxy_proto_mode_tcp),
      "tcp_udp" to stringResource(R.string.myproxy_proto_mode_tcp_udp),
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
          val selected = protoMode == value
          val bg by animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.22f) else Color.Transparent,
            animationSpec = tween(220),
            label = "myProxyProtoBg",
          )
          val borderColor by animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.55f) else Color.Transparent,
            animationSpec = tween(220),
            label = "myProxyProtoBorder",
          )
          val textColor by animateColorAsState(
            targetValue = if (selected) Color(0xFFC084FC) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            animationSpec = tween(220),
            label = "myProxyProtoText",
          )
          Surface(
            modifier = Modifier.weight(1f).clickable { onSelect(value) },
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
fun MyProxyProfileScreen(
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
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "myproxy" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val encodedProfile = remember(profile) { URLEncoder.encode(profile, "UTF-8") }
  val basePath = remember(encodedProfile) { "/api/programs/myproxy/profiles/$encodedProfile" }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  var loading by remember(profile) { mutableStateOf(true) }
  var settingSaving by remember(profile) { mutableStateOf(false) }
  var proxySaving by remember(profile) { mutableStateOf(false) }

  var syncedSetting by remember(profile) { mutableStateOf(MyProxySettingUi(null, null)) }
  var syncedProxy by remember(profile) { mutableStateOf(MyProxyUpstreamUi("", emptyList(), "balance", "", false, "", "", "", null, "", "")) }

  var t2sPortText by remember(profile) { mutableStateOf("") }
  var t2sWebPortText by remember(profile) { mutableStateOf("") }
  var hostText by remember(profile) { mutableStateOf("") }
  var proxyPortText by remember(profile) { mutableStateOf("") }
  var backendMode by remember(profile) { mutableStateOf("balance") }
  var protoMode by remember(profile) { mutableStateOf("tcp_udp") }
  var backendPriorityText by remember(profile) { mutableStateOf("") }
  var prioritySpeedAware by remember(profile) { mutableStateOf(false) }
  var userText by remember(profile) { mutableStateOf("") }
  var passText by remember(profile) { mutableStateOf("") }
  var wrappedHostText by remember(profile) { mutableStateOf("") }
  var wrappedPortText by remember(profile) { mutableStateOf("") }
  var wrappedUserText by remember(profile) { mutableStateOf("") }
  var wrappedPassText by remember(profile) { mutableStateOf("") }

  var settingInitialized by remember(profile) { mutableStateOf(false) }
  var proxyInitialized by remember(profile) { mutableStateOf(false) }
  var myProxyWebPanelChecking by remember(profile) { mutableStateOf(false) }
  var selectedApps by remember(profile) { mutableStateOf(emptySet<String>()) }

  fun loadAll() {
    loading = true
    scope.launch {
      val settingObj = awaitLoadJsonMyProxy(actions, "$basePath/setting")
      val proxyObj = awaitLoadJsonMyProxy(actions, "$basePath/proxy")
      val apps = parsePkgList(awaitLoadTextMyProxy(actions, "$basePath/apps/user").orEmpty())
      val parsedSetting = parseMyProxySettingUi(settingObj)
      val parsedProxy = parseMyProxyUpstreamUi(proxyObj)
      syncedSetting = parsedSetting
      syncedProxy = parsedProxy
      selectedApps = apps
      t2sPortText = parsedSetting.t2sPort?.toString().orEmpty()
      t2sWebPortText = parsedSetting.t2sWebPort?.toString().orEmpty()
      hostText = parsedProxy.host
      proxyPortText = parsedProxy.ports.joinToString(",")
      backendMode = parsedProxy.backendMode
      protoMode = parsedProxy.protoMode
      backendPriorityText = parsedProxy.backendPriority
      prioritySpeedAware = parsedProxy.prioritySpeedAware
      userText = parsedProxy.user
      passText = parsedProxy.pass
      wrappedHostText = parsedProxy.wrappedHost
      wrappedPortText = parsedProxy.wrappedPort?.toString().orEmpty()
      wrappedUserText = parsedProxy.wrappedUser
      wrappedPassText = parsedProxy.wrappedPass
      settingInitialized = true
      proxyInitialized = true
      loading = false
    }
  }

  LaunchedEffect(profile) { loadAll() }

  LaunchedEffect(t2sPortText, t2sWebPortText, settingInitialized) {
    if (!settingInitialized) return@LaunchedEffect
    delay(700)
    val t2sPort = t2sPortText.trim().toIntOrNull()
    val t2sWebPort = t2sWebPortText.trim().toIntOrNull()
    val current = MyProxySettingUi(t2sPort, t2sWebPort)
    if (current == syncedSetting) return@LaunchedEffect
    if (t2sPort !in 1..65535 || t2sWebPort !in 1..65535) return@LaunchedEffect
    if (t2sPort == t2sWebPort) return@LaunchedEffect
    settingSaving = true
    val ok = awaitSaveJsonMyProxy(
      actions,
      "$basePath/setting",
      JSONObject().put("t2s_port", t2sPort).put("t2s_web_port", t2sWebPort)
    )
    settingSaving = false
    if (ok) syncedSetting = current else showSnack(context.getString(R.string.myproxy_auto_save_failed))
  }

  LaunchedEffect(hostText, proxyPortText, backendMode, backendPriorityText, prioritySpeedAware, userText, passText, wrappedHostText, wrappedPortText, wrappedUserText, wrappedPassText, protoMode, proxyInitialized) {
    if (!proxyInitialized) return@LaunchedEffect
    delay(700)
    val host = hostText.trim()
    val mode = normalizeMyProxyBackendMode(backendMode)
    val ports = normalizeMyProxyPortList(proxyPortText.trim(), allowZeroMarker = mode == "priority")
    val realBackendPorts = ports?.filter { it != 0 }.orEmpty()
    val directOnly = mode == "priority" && ports == listOf(0)
    val priority = if (mode == "priority" && ports != null) {
      normalizeMyProxyBackendPriority(backendPriorityText, realBackendPorts)
    } else {
      ""
    }
    val effectivePrioritySpeedAware = mode == "priority" && prioritySpeedAware && !directOnly
    val user = userText.trim()
    val pass = passText
    val wrappedHost = wrappedHostText.trim()
    val wrappedPort = wrappedPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val wrappedUser = wrappedUserText.trim()
    val wrappedPass = wrappedPassText
    val wrappedConfigured = wrappedHost.isNotBlank() || wrappedPortText.isNotBlank()
    if (host.isBlank() && !directOnly) return@LaunchedEffect
    val portsForSave = ports?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
    val priorityForSave = priority ?: return@LaunchedEffect
    if ((user.isBlank()) xor pass.isBlank()) return@LaunchedEffect
    if (wrappedConfigured && (wrappedHost.isBlank() || wrappedPort == null)) return@LaunchedEffect
    if ((wrappedUser.isBlank()) xor wrappedPass.isBlank()) return@LaunchedEffect
    val current = MyProxyUpstreamUi(
      host = host,
      ports = portsForSave,
      backendMode = mode,
      backendPriority = priorityForSave,
      prioritySpeedAware = effectivePrioritySpeedAware,
      user = user,
      pass = pass,
      wrappedHost = wrappedHost,
      wrappedPort = if (wrappedConfigured) wrappedPort else null,
      wrappedUser = wrappedUser,
      wrappedPass = wrappedPass,
      protoMode = normalizeMyProxyProtoMode(protoMode),
    )
    if (current == syncedProxy) return@LaunchedEffect
    proxySaving = true
    val ok = awaitSaveJsonMyProxy(
      actions,
      "$basePath/proxy",
      buildMyProxyUpstreamJson(host, portsForSave, mode, priorityForSave, effectivePrioritySpeedAware, user, pass, wrappedHost, if (wrappedConfigured) wrappedPort else null, wrappedUser, wrappedPass, normalizeMyProxyProtoMode(protoMode))
    )
    proxySaving = false
    if (ok) {
      syncedProxy = current
      if (mode == "priority" && backendPriorityText.trim() != priorityForSave) backendPriorityText = priorityForSave
    } else {
      showSnack(context.getString(R.string.myproxy_auto_save_failed))
    }
  }

  val t2sWebPanelPort = remember(t2sWebPortText) { t2sWebPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 } }
  val myProxyPanelUrl = remember(t2sWebPanelPort) { t2sWebPanelPort?.let { myProxyWebPanelUrl(it) } }
  val myProxyWebPanelVisible = prof?.enabled == true && t2sWebPanelPort != null && !isOnlyZdtdAppSelected(selectedApps)

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(scroll)
      .padding(horizontal = if (compact) 12.dp else 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    MyProxyProfileEnabledCard(
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("myproxy", profile, v) },
    )

    AnimatedVisibility(
      visible = myProxyWebPanelVisible,
      enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
      exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(180)),
    ) {
      MyProxyWebPanelCard(
        checking = myProxyWebPanelChecking,
        onOpen = {
          val port = t2sWebPanelPort
          val url = myProxyPanelUrl
          if (port == null || url == null) {
            showSnack(context.getString(R.string.web_panel_unavailable))
          } else if (!myProxyWebPanelChecking) {
            scope.launch {
              myProxyWebPanelChecking = true
              val available = isMyProxyWebPanelPortOpen(port)
              myProxyWebPanelChecking = false
              if (available) {
                context.startActivity(
                  Intent(context, T2sPanelActivity::class.java)
                    .putExtra(T2sPanelActivity.EXTRA_SCOPE, myProxyWebPanelScope(profile))
                    .putExtra(T2sPanelActivity.EXTRA_PORT, port)
                    .putExtra(T2sPanelActivity.EXTRA_TITLE, "myproxy / $profile")
                )
              } else {
                showSnack(context.getString(R.string.web_panel_unavailable))
              }
            }
          }
        },
      )
    }

    AppListPickerCard(
      title = stringResource(R.string.myproxy_apps_title),
      desc = stringResource(R.string.myproxy_apps_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
      programs = programs,
      onSavedSelection = { selectedApps = it },
    )

    MyProxySectionCard(
      title = stringResource(R.string.myproxy_ports_title),
      desc = stringResource(R.string.myproxy_ports_desc),
      accent = Color(0xFF38BDF8),
      icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(21.dp)) },
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = t2sPortText,
          onValueChange = { t2sPortText = it.filter(Char::isDigit).take(5) },
          label = { Text(stringResource(R.string.myproxy_t2s_port_label)) },
          modifier = Modifier.weight(1f),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = t2sPortText.isNotBlank() && t2sPortText.toIntOrNull()?.let { it !in 1..65535 } != false,
        )
        OutlinedTextField(
          value = t2sWebPortText,
          onValueChange = { t2sWebPortText = it.filter(Char::isDigit).take(5) },
          label = { Text(stringResource(R.string.myproxy_t2s_web_port_label)) },
          modifier = Modifier.weight(1f),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = t2sWebPortText.isNotBlank() && t2sWebPortText.toIntOrNull()?.let { it !in 1..65535 } != false,
        )
      }
      val samePorts = t2sPortText.isNotBlank() && t2sPortText == t2sWebPortText
      if (samePorts) {
        Text(
          stringResource(R.string.myproxy_ports_must_differ),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.12f)),
      ) {
        Text(
          if (settingSaving) stringResource(R.string.myproxy_ports_saving) else stringResource(R.string.myproxy_ports_autosave_hint),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }

    MyProxySectionCard(
      title = stringResource(R.string.myproxy_upstream_title),
      desc = stringResource(R.string.myproxy_upstream_desc),
      accent = Color(0xFFFACC15),
      icon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp)) },
    ) {
      val currentMode = normalizeMyProxyBackendMode(backendMode)
      val currentPorts = normalizeMyProxyPortList(proxyPortText, allowZeroMarker = currentMode == "priority")
      val directOnlyPorts = currentMode == "priority" && currentPorts == listOf(0)
      OutlinedTextField(
        value = hostText,
        onValueChange = { hostText = it },
        label = { Text(stringResource(R.string.myproxy_host_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = hostText.isBlank() && !directOnlyPorts,
      )
      FieldHint(stringResource(R.string.myproxy_host_hint))

      val proxyPortsValid = proxyPortText.isNotBlank() && currentPorts != null
      OutlinedTextField(
        value = proxyPortText,
        onValueChange = { proxyPortText = it.filter { ch -> ch.isDigit() || ch == ',' || ch.isWhitespace() }.take(128) },
        label = { Text(stringResource(R.string.myproxy_proxy_port_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        singleLine = true,
        isError = proxyPortText.isNotBlank() && !proxyPortsValid,
      )
      FieldHint(stringResource(R.string.myproxy_proxy_port_hint))
      if (proxyPortText.isNotBlank() && !proxyPortsValid) {
        Text(
          stringResource(R.string.myproxy_proxy_ports_invalid),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, Color(0xFFFACC15).copy(alpha = 0.18f)),
      ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.myproxy_backend_mode_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            FilterChip(
              selected = backendMode == "balance",
              onClick = { backendMode = "balance" },
              label = { Text(stringResource(R.string.myproxy_backend_mode_balance)) },
            )
            FilterChip(
              selected = backendMode == "priority",
              onClick = { backendMode = "priority" },
              label = { Text(stringResource(R.string.myproxy_backend_mode_priority)) },
            )
          }
          Text(
            if (backendMode == "priority") {
              stringResource(R.string.myproxy_backend_mode_priority_desc)
            } else {
              stringResource(R.string.myproxy_backend_mode_balance_desc)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
        }
      }

      AnimatedVisibility(
        visible = backendMode == "priority",
        enter = fadeIn(tween(160)) + expandVertically(animationSpec = tween(180)),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = tween(150)),
      ) {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
          border = BorderStroke(1.dp, Color(0xFFFACC15).copy(alpha = 0.14f)),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                stringResource(R.string.myproxy_priority_speed_aware_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
              )
              Text(
                stringResource(R.string.myproxy_priority_speed_aware_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
              )
            }
            Switch(
              checked = prioritySpeedAware,
              onCheckedChange = { prioritySpeedAware = it },
            )
          }
        }
      }

      AnimatedVisibility(
        visible = backendMode == "priority",
        enter = fadeIn(tween(160)) + expandVertically(animationSpec = tween(180)),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = tween(150)),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          val priorityAllowedPorts = normalizeMyProxyPortList(proxyPortText, allowZeroMarker = true).orEmpty().filter { it != 0 }
          val backendPriorityValid = backendPriorityText.isBlank() ||
            normalizeMyProxyBackendPriority(backendPriorityText, priorityAllowedPorts) != null
          OutlinedTextField(
            value = backendPriorityText,
            onValueChange = {
              backendPriorityText = sanitizeMyProxyBackendPriorityInput(it)
            },
            label = { Text(stringResource(R.string.myproxy_backend_priority_label)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            singleLine = true,
            isError = backendPriorityText.isNotBlank() && !backendPriorityValid,
          )
          FieldHint(stringResource(R.string.myproxy_backend_priority_hint))
          if (backendPriorityText.isNotBlank() && !backendPriorityValid) {
            Text(
              stringResource(R.string.myproxy_backend_priority_invalid),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, Color(0xFFFACC15).copy(alpha = 0.14f)),
      ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.myproxy_credentials_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          OutlinedTextField(
            value = userText,
            onValueChange = { userText = it },
            label = { Text(stringResource(R.string.myproxy_user_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
          FieldHint(stringResource(R.string.myproxy_user_hint))
          OutlinedTextField(
            value = passText,
            onValueChange = { passText = it },
            label = { Text(stringResource(R.string.myproxy_pass_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
          FieldHint(stringResource(R.string.myproxy_pass_hint))
          if ((userText.isBlank()) xor passText.isBlank()) {
            Text(
              stringResource(R.string.myproxy_credentials_pair_required),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.18f)),
      ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.myproxy_wrapped_socks_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          Text(
            stringResource(R.string.myproxy_wrapped_socks_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
          OutlinedTextField(
            value = wrappedHostText,
            onValueChange = { wrappedHostText = it },
            label = { Text(stringResource(R.string.myproxy_wrapped_host_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = wrappedPortText,
              onValueChange = { wrappedPortText = it.filter(Char::isDigit).take(5) },
              label = { Text(stringResource(R.string.myproxy_wrapped_port_label)) },
              modifier = Modifier.weight(1f),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              isError = wrappedPortText.isNotBlank() && wrappedPortText.toIntOrNull()?.let { it !in 1..65535 } != false,
            )
          }
          FieldHint(stringResource(R.string.myproxy_wrapped_hint))
          OutlinedTextField(
            value = wrappedUserText,
            onValueChange = { wrappedUserText = it },
            label = { Text(stringResource(R.string.myproxy_wrapped_user_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
          OutlinedTextField(
            value = wrappedPassText,
            onValueChange = { wrappedPassText = it },
            label = { Text(stringResource(R.string.myproxy_wrapped_pass_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
          if ((wrappedUserText.isBlank()) xor wrappedPassText.isBlank()) {
            Text(
              stringResource(R.string.myproxy_credentials_pair_required),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }

      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
        border = BorderStroke(1.dp, Color(0xFFFACC15).copy(alpha = 0.12f)),
      ) {
        Text(
          if (proxySaving) stringResource(R.string.myproxy_ports_saving) else stringResource(R.string.myproxy_ports_autosave_hint),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }

    AnimatedVisibility(
      visible = tproxyEnabled,
      enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
      exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(180)),
    ) {
      MyProxyProtoModeCard(
        protoMode = protoMode,
        onSelect = { protoMode = it },
      )
    }

    if (loading) {
      Spacer(Modifier.height(4.dp))
      CircularProgressIndicator()
    }

    Spacer(Modifier.height(effectiveBottomContentPadding))
  }
}
