package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.coroutines.resume

private data class WireProxyProfileSettingUi(
  val t2sPort: Int?,
  val t2sWebPort: Int?,
)

private data class WireProxyServerUi(
  val name: String,
  val enabled: Boolean,
  val bindHost: String?,
  val bindPort: Int?,
)

private data class WireProxyPortRegistry(
  val labelsByPort: Map<Int, List<String>> = emptyMap(),
)

private fun parseWireProxyProfileSettingUi(obj: JSONObject?): WireProxyProfileSettingUi {
  val data = obj?.optJSONObject("data") ?: obj
  return WireProxyProfileSettingUi(
    t2sPort = data?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 },
    t2sWebPort = data?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 },
  )
}

private fun parseWireProxyServersUi(obj: JSONObject?): List<WireProxyServerUi> {
  val arr = obj?.optJSONArray("servers") ?: JSONArray()
  return buildList {
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val name = item.optString("name", "").trim()
      if (name.isEmpty()) continue
      val data = item.optJSONObject("data")
      val bind = item.optJSONObject("bind")
      add(
        WireProxyServerUi(
          name = name,
          enabled = data?.optBoolean("enabled", false) ?: false,
          bindHost = bind?.optString("host", "")?.trim()?.takeIf { it.isNotEmpty() },
          bindPort = bind?.optInt("port", 0)?.takeIf { it in 1..65535 },
        )
      )
    }
  }.sortedBy { it.name.lowercase() }
}

private fun normalizeWireProxyServerName(input: String): String {
  val sb = StringBuilder(24)
  for (ch in input.lowercase()) {
    if (sb.length >= 24) break
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

private fun wireProxyServerPortLabel(profile: String, server: String): String = "$profile / $server"
private fun wireProxyT2sPortLabel(profile: String): String = "$profile / t2s"
private fun wireProxyT2sWebPortLabel(profile: String): String = "$profile / t2s web"

private fun buildWireProxyPortRegistry(
  settingsByProfile: Map<String, WireProxyProfileSettingUi?>,
  serversByProfile: Map<String, List<WireProxyServerUi>>,
): WireProxyPortRegistry {
  val labels = linkedMapOf<Int, MutableList<String>>()

  fun add(port: Int?, label: String) {
    val safePort = port ?: return
    if (safePort !in 1..65535) return
    labels.getOrPut(safePort) { mutableListOf() }.add(label)
  }

  settingsByProfile.forEach { (profile, setting) ->
    add(setting?.t2sPort, wireProxyT2sPortLabel(profile))
    add(setting?.t2sWebPort, wireProxyT2sWebPortLabel(profile))
  }
  serversByProfile.forEach { (profile, profileServers) ->
    profileServers.forEach { server ->
      add(server.bindPort, wireProxyServerPortLabel(profile, server.name))
    }
  }

  return WireProxyPortRegistry(labelsByPort = labels.mapValues { it.value.toList() })
}

private fun findWireProxyPortConflictLabel(
  registry: WireProxyPortRegistry,
  port: Int,
  ignoredLabel: String? = null,
): String? {
  if (port !in 1..65535) return null
  return registry.labelsByPort[port].orEmpty().firstOrNull { it != ignoredLabel }
}

private fun findNextAvailableWireProxyPort(
  registry: WireProxyPortRegistry,
  preferredPort: Int,
  ignoredLabel: String? = null,
): Int {
  fun isFree(port: Int): Boolean {
    if (port !in 1..65535) return false
    return registry.labelsByPort[port].orEmpty().none { it != ignoredLabel }
  }
  var port = preferredPort.coerceIn(1, 65535)
  if (isFree(port)) return port
  while (port < 65535) {
    port += 1
    if (isFree(port)) return port
  }
  port = 1
  while (port < 65535) {
    if (isFree(port)) return port
    port += 1
  }
  return preferredPort.coerceIn(1, 65535)
}

private data class Socks5Bind(val host: String, val port: Int)

private fun parseWireProxySocks5Bind(configText: String): Socks5Bind? {
  var inSection = false
  for (rawLine in configText.lines()) {
    val line = rawLine.trim()
    if (line.startsWith("[") && line.endsWith("]")) {
      inSection = line.equals("[Socks5]", ignoreCase = true)
      continue
    }
    if (!inSection) continue
    if (!line.startsWith("BindAddress", ignoreCase = true)) continue
    val idx = line.indexOf('=')
    if (idx < 0) continue
    val value = line.substring(idx + 1).trim()
    val colon = value.lastIndexOf(':')
    if (colon <= 0 || colon == value.lastIndex) continue
    val host = value.substring(0, colon).trim()
    val port = value.substring(colon + 1).trim().toIntOrNull() ?: continue
    if (host.isEmpty() || port !in 1..65535) continue
    return Socks5Bind(host = host, port = port)
  }
  return null
}

private fun upsertWireProxySocks5Bind(configText: String, port: Int): String {
  val newLine = "BindAddress = 127.0.0.1:$port"
  val lines = configText.lines().toMutableList()
  var sectionStart = -1
  var sectionEnd = lines.size
  for (i in lines.indices) {
    val trimmed = lines[i].trim()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      if (trimmed.equals("[Socks5]", ignoreCase = true)) {
        sectionStart = i
        var j = i + 1
        while (j < lines.size) {
          val next = lines[j].trim()
          if (next.startsWith("[") && next.endsWith("]")) break
          j += 1
        }
        sectionEnd = j
        break
      }
    }
  }
  if (sectionStart >= 0) {
    for (i in sectionStart + 1 until sectionEnd) {
      if (lines[i].trim().startsWith("BindAddress", ignoreCase = true)) {
        lines[i] = newLine
        return lines.joinToString("\n")
      }
    }
    lines.add(sectionEnd, newLine)
    return lines.joinToString("\n")
  }
  val prefix = configText.trimEnd()
  return buildString {
    if (prefix.isNotEmpty()) {
      append(prefix)
      append("\n\n")
    }
    append("[Socks5]\n")
    append(newLine)
    append("\n")
  }
}

private fun buildWireProxyStarterConfig(port: Int): String = """
  [Interface]

  [Peer]

  [Socks5]
  BindAddress = 127.0.0.1:$port
""".trimIndent() + "\n"

private suspend fun awaitLoadJson(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont ->
    actions.loadJsonData(path) { cont.resume(it) }
  }

private suspend fun awaitLoadText(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont ->
    actions.loadText(path) { cont.resume(it) }
  }

private suspend fun awaitSaveJson(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont ->
    actions.saveJsonData(path, obj) { cont.resume(it) }
  }

private suspend fun awaitSaveText(actions: ZdtdActions, path: String, content: String): Boolean =
  suspendCancellableCoroutine { cont ->
    actions.saveText(path, content) { cont.resume(it) }
  }

private suspend fun awaitCreateWireProxyServer(actions: ZdtdActions, profile: String, server: String): String? =
  suspendCancellableCoroutine { cont ->
    actions.createWireProxyServer(profile, server) { cont.resume(it) }
  }

private suspend fun awaitDeleteWireProxyServer(actions: ZdtdActions, profile: String, server: String): Boolean =
  suspendCancellableCoroutine { cont ->
    actions.deleteWireProxyServer(profile, server) { cont.resume(it) }
  }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireProxyProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val program = programs.firstOrNull { it.id == "wireproxy" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val encodedProfile = remember(profile) { URLEncoder.encode(profile, "UTF-8") }
  val basePath = remember(encodedProfile) { "/api/programs/wireproxy/profiles/$encodedProfile" }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  var setting by remember(profile) { mutableStateOf<WireProxyProfileSettingUi?>(null) }
  var settingLoading by remember(profile) { mutableStateOf(false) }
  var settingSaving by remember(profile) { mutableStateOf(false) }
  var servers by remember(profile) { mutableStateOf<List<WireProxyServerUi>>(emptyList()) }
  var serversLoading by remember(profile) { mutableStateOf(false) }
  var globalPortRegistry by remember(profile, program?.profiles) { mutableStateOf(WireProxyPortRegistry()) }
  var globalPortRefreshSeq by remember(profile, program?.profiles) { mutableStateOf(0) }

  var showCreateServer by remember(profile) { mutableStateOf(false) }
  var askDeleteServer by remember(profile) { mutableStateOf<WireProxyServerUi?>(null) }
  var editServer by remember(profile) { mutableStateOf<WireProxyServerUi?>(null) }
  var editorText by remember(profile) { mutableStateOf("") }
  var editorLoading by remember(profile) { mutableStateOf(false) }
  var editorSaving by remember(profile) { mutableStateOf(false) }

  fun refreshSetting() {
    settingLoading = true
    actions.loadJsonData("$basePath/setting") { obj ->
      setting = parseWireProxyProfileSettingUi(obj)
      settingLoading = false
      globalPortRefreshSeq += 1
    }
  }

  fun refreshServers() {
    serversLoading = true
    actions.loadJsonData("$basePath/servers") { obj ->
      servers = parseWireProxyServersUi(obj)
      serversLoading = false
      globalPortRefreshSeq += 1
    }
  }

  LaunchedEffect(profile) {
    refreshSetting()
    refreshServers()
  }

  LaunchedEffect(program?.profiles, globalPortRefreshSeq) {
    val profiles = program?.profiles.orEmpty()
    if (profiles.isEmpty()) {
      globalPortRegistry = WireProxyPortRegistry()
      return@LaunchedEffect
    }
    val settingsByProfile = linkedMapOf<String, WireProxyProfileSettingUi?>()
    val serversByProfile = linkedMapOf<String, List<WireProxyServerUi>>()
    profiles.forEach { item ->
      val enc = URLEncoder.encode(item.name, "UTF-8")
      val profileBase = "/api/programs/wireproxy/profiles/$enc"
      val settingObj = awaitLoadJson(actions, "$profileBase/setting")
      settingsByProfile[item.name] = parseWireProxyProfileSettingUi(settingObj)
      val serversObj = awaitLoadJson(actions, "$profileBase/servers")
      serversByProfile[item.name] = parseWireProxyServersUi(serversObj)
    }
    globalPortRegistry = buildWireProxyPortRegistry(settingsByProfile, serversByProfile)
  }

  if (askDeleteServer != null) {
    val server = askDeleteServer!!
    AlertDialog(
      onDismissRequest = { askDeleteServer = null },
      title = { Text(stringResource(R.string.wireproxy_delete_server_title)) },
      text = { Text(stringResource(R.string.wireproxy_delete_server_message_fmt, profile, server.name)) },
      confirmButton = {
        Button(onClick = {
          val deleting = server
          askDeleteServer = null
          scope.launch {
            val ok = awaitDeleteWireProxyServer(actions, profile, deleting.name)
            if (ok) {
              showSnack(context.getString(R.string.deleted))
              refreshServers()
            } else {
              showSnack(context.getString(R.string.delete_failed))
            }
          }
        }) { Text(stringResource(R.string.action_delete)) }
      },
      dismissButton = {
        OutlinedButton(onClick = { askDeleteServer = null }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }

  if (showCreateServer) {
    WireProxyCreateServerDialog(
      existing = servers.map { it.name },
      onDismiss = { showCreateServer = false },
      onCreate = { requestedName ->
        showCreateServer = false
        scope.launch {
          val created = awaitCreateWireProxyServer(actions, profile, requestedName)
          if (created == null) {
            showSnack(context.getString(R.string.create_failed))
            return@launch
          }
          val serverLabel = wireProxyServerPortLabel(profile, created)
          val preferredPort = findNextAvailableWireProxyPort(globalPortRegistry, 1167, ignoredLabel = serverLabel)
          val configPath = "$basePath/servers/${URLEncoder.encode(created, "UTF-8")}/config"
          val current = awaitLoadText(actions, configPath).orEmpty()
          val bind = parseWireProxySocks5Bind(current)
          if (bind == null || bind.host != "127.0.0.1") {
            val generated = if (current.isBlank()) buildWireProxyStarterConfig(preferredPort) else upsertWireProxySocks5Bind(current, preferredPort)
            awaitSaveText(actions, configPath, generated)
          }
          showSnack(context.getString(R.string.wireproxy_server_created_fmt, created))
          refreshServers()
        }
      },
      snackHost = snackHost,
    )
  }

  if (editServer != null) {
    WireProxyConfigDialog(
      profile = profile,
      server = editServer!!.name,
      initialText = editorText,
      loading = editorLoading,
      saving = editorSaving,
      registry = globalPortRegistry,
      onDismiss = { if (!editorSaving) editServer = null },
      onSave = { newText ->
        val server = editServer ?: return@WireProxyConfigDialog
        val ignoredLabel = wireProxyServerPortLabel(profile, server.name)
        val fallbackPort = findNextAvailableWireProxyPort(globalPortRegistry, server.bindPort ?: 1167, ignoredLabel)
        var finalText = newText
        val existingBind = parseWireProxySocks5Bind(finalText)
        if (existingBind == null) {
          finalText = if (finalText.isBlank()) buildWireProxyStarterConfig(fallbackPort) else upsertWireProxySocks5Bind(finalText, fallbackPort)
        }
        val bind = parseWireProxySocks5Bind(finalText)
        if (bind == null) {
          showSnack(context.getString(R.string.wireproxy_socks5_bind_missing))
          return@WireProxyConfigDialog
        }
        if (bind.host != "127.0.0.1") {
          showSnack(context.getString(R.string.wireproxy_socks5_bind_host_invalid))
          return@WireProxyConfigDialog
        }
        val conflict = findWireProxyPortConflictLabel(globalPortRegistry, bind.port, ignoredLabel)
        if (conflict != null) {
          showSnack(context.getString(R.string.wireproxy_port_conflict_fmt, bind.port, conflict))
          return@WireProxyConfigDialog
        }
        editorSaving = true
        actions.saveText("$basePath/servers/${URLEncoder.encode(server.name, "UTF-8")}/config", finalText) { ok ->
          editorSaving = false
          if (ok) {
            editServer = null
            refreshServers()
          } else {
            showSnack(context.getString(R.string.save_failed))
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
    Text("${program?.name ?: "WireGuard"} / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Text(
      stringResource(R.string.changes_apply_after_restart),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = stringResource(R.string.enabled_card_profile_title),
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("wireproxy", profile, v) },
    )

    WireProxyProfileSettingsCard(
      profile = profile,
      setting = setting,
      loading = settingLoading,
      saving = settingSaving,
      registry = globalPortRegistry,
      onSaveStateChange = { settingSaving = it },
      onSave = { t2sPort, t2sWebPort, onDone ->
        val payload = JSONObject().put("t2s_port", t2sPort).put("t2s_web_port", t2sWebPort)
        actions.saveJsonData("$basePath/setting", payload) { ok ->
          if (ok) {
            setting = WireProxyProfileSettingUi(t2sPort = t2sPort, t2sWebPort = t2sWebPort)
            globalPortRefreshSeq += 1
          }
          onDone(ok)
        }
      },
      snackHost = snackHost,
    )

    AppListPickerCard(
      title = stringResource(R.string.wireproxy_apps_title),
      desc = stringResource(R.string.wireproxy_apps_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
    )

    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
      Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.wireproxy_servers_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            stringResource(R.string.wireproxy_servers_desc),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall,
          )
          FilledTonalButton(onClick = { showCreateServer = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.wireproxy_add_server))
          }
        }

        if (serversLoading) {
          LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (servers.isEmpty()) {
          Text(
            stringResource(R.string.wireproxy_no_servers),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          )
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            servers.forEach { server ->
              WireProxyServerCard(
                profile = profile,
                basePath = basePath,
                server = server,
                onEdit = {
                  editServer = server
                  editorLoading = true
                  editorSaving = false
                  actions.loadText("$basePath/servers/${URLEncoder.encode(server.name, "UTF-8")}/config") { txt ->
                    val current = txt.orEmpty()
                    val bind = parseWireProxySocks5Bind(current)
                    val preferred = findNextAvailableWireProxyPort(
                      globalPortRegistry,
                      server.bindPort ?: 1167,
                      ignoredLabel = wireProxyServerPortLabel(profile, server.name),
                    )
                    editorText = if (bind == null) {
                      if (current.isBlank()) buildWireProxyStarterConfig(preferred) else upsertWireProxySocks5Bind(current, preferred)
                    } else {
                      current
                    }
                    editorLoading = false
                  }
                },
                onDelete = { askDeleteServer = server },
                onRefresh = { refreshServers() },
                actions = actions,
                snackHost = snackHost,
              )
            }
          }
        }
      }
    }

    Spacer(Modifier.height(80.dp))
  }
}

@Composable
private fun WireProxyProfileSettingsCard(
  profile: String,
  setting: WireProxyProfileSettingUi?,
  loading: Boolean,
  saving: Boolean,
  registry: WireProxyPortRegistry,
  onSaveStateChange: (Boolean) -> Unit,
  onSave: (Int, Int, (Boolean) -> Unit) -> Unit,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var t2sPortText by remember(setting?.t2sPort) { mutableStateOf(setting?.t2sPort?.toString().orEmpty()) }
  var t2sWebPortText by remember(setting?.t2sWebPort) { mutableStateOf(setting?.t2sWebPort?.toString().orEmpty()) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val parsedT2sPort = t2sPortText.trim().toIntOrNull()
  val parsedT2sWebPort = t2sWebPortText.trim().toIntOrNull()
  val t2sConflict = parsedT2sPort?.let { findWireProxyPortConflictLabel(registry, it, wireProxyT2sPortLabel(profile)) }
  val t2sWebConflict = parsedT2sWebPort?.let { findWireProxyPortConflictLabel(registry, it, wireProxyT2sWebPortLabel(profile)) }
  val changed = parsedT2sPort != setting?.t2sPort || parsedT2sWebPort != setting?.t2sWebPort
  val valid = parsedT2sPort in 1..65535 && parsedT2sWebPort in 1..65535 && parsedT2sPort != parsedT2sWebPort && t2sConflict == null && t2sWebConflict == null

  LaunchedEffect(parsedT2sPort, parsedT2sWebPort, setting?.t2sPort, setting?.t2sWebPort, loading, saving, t2sConflict, t2sWebConflict) {
    if (loading || saving || !changed || !valid) return@LaunchedEffect
    delay(700)
    if (parsedT2sPort != setting?.t2sPort || parsedT2sWebPort != setting?.t2sWebPort) {
      onSaveStateChange(true)
      onSave(parsedT2sPort!!, parsedT2sWebPort!!) { ok ->
        onSaveStateChange(false)
        if (!ok) showSnack(context.getString(R.string.wireproxy_auto_save_failed))
      }
    }
  }

  ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.wireproxy_profile_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.wireproxy_profile_settings_desc),
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
        label = { Text(stringResource(R.string.wireproxy_t2s_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
      if (t2sConflict != null && parsedT2sPort != null) {
        Text(
          stringResource(R.string.wireproxy_port_conflict_fmt, parsedT2sPort, t2sConflict),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      OutlinedTextField(
        value = t2sWebPortText,
        onValueChange = { t2sWebPortText = it.filter(Char::isDigit) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading,
        label = { Text(stringResource(R.string.wireproxy_t2s_web_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
      if (t2sWebConflict != null && parsedT2sWebPort != null) {
        Text(
          stringResource(R.string.wireproxy_port_conflict_fmt, parsedT2sWebPort, t2sWebConflict),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      val footerText = when {
        parsedT2sPort == null || parsedT2sWebPort == null -> stringResource(R.string.wireproxy_profile_autosave_hint)
        parsedT2sPort == parsedT2sWebPort -> stringResource(R.string.wireproxy_profile_ports_must_differ)
        parsedT2sPort !in 1..65535 || parsedT2sWebPort !in 1..65535 -> stringResource(R.string.wireproxy_profile_autosave_hint)
        t2sConflict != null || t2sWebConflict != null -> stringResource(R.string.wireproxy_profile_fix_conflicts)
        saving -> stringResource(R.string.common_loading)
        else -> stringResource(R.string.wireproxy_profile_autosave_hint)
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
private fun WireProxyServerCard(
  profile: String,
  basePath: String,
  server: WireProxyServerUi,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onRefresh: () -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var enabled by remember(server.name, server.enabled) { mutableStateOf(server.enabled) }
  var saving by remember(server.name) { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  LaunchedEffect(enabled, server.enabled, saving) {
    if (saving || enabled == server.enabled) return@LaunchedEffect
    delay(450)
    if (enabled == server.enabled) return@LaunchedEffect
    saving = true
    val encodedServer = URLEncoder.encode(server.name, "UTF-8")
    val payload = JSONObject().put("enabled", enabled)
    actions.saveJsonData("$basePath/servers/$encodedServer/setting", payload) { ok ->
      saving = false
      if (ok) {
        onRefresh()
      } else {
        enabled = server.enabled
        showSnack(context.getString(R.string.wireproxy_auto_save_failed))
      }
    }
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            when {
              saving -> stringResource(R.string.common_loading)
              server.bindPort != null -> stringResource(R.string.wireproxy_server_bind_fmt, server.bindHost ?: "127.0.0.1", server.bindPort)
              else -> stringResource(R.string.wireproxy_server_bind_missing)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
        Switch(checked = enabled, onCheckedChange = { enabled = it })
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Edit, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_edit))
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Delete, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_delete))
        }
      }
    }
  }
}

@Composable
private fun WireProxyCreateServerDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val existingNorm = remember(existing) { existing.map { normalizeWireProxyServerName(it) }.toSet() }
  var raw by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val name = remember(raw) { normalizeWireProxyServerName(raw) }

  fun snack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.wireproxy_create_server_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          stringResource(R.string.wireproxy_create_server_rules),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = name,
          onValueChange = {
            raw = it
            error = null
          },
          label = { Text(stringResource(R.string.wireproxy_server_name_label)) },
          singleLine = true,
          supportingText = {
            Text(stringResource(R.string.wireproxy_server_name_optional))
          },
          modifier = Modifier.fillMaxWidth(),
        )
        if (!error.isNullOrBlank()) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(onClick = {
        when {
          name.isNotBlank() && name in existingNorm -> {
            val msg = context.getString(R.string.wireproxy_server_exists)
            error = msg
            snack(msg)
          }
          name.isNotBlank() && name.length < 2 -> {
            val msg = context.getString(R.string.wireproxy_server_name_too_short)
            error = msg
            snack(msg)
          }
          else -> onCreate(name)
        }
      }) {
        Text(stringResource(R.string.action_create))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
private fun WireProxyConfigDialog(
  profile: String,
  server: String,
  initialText: String,
  loading: Boolean,
  saving: Boolean,
  registry: WireProxyPortRegistry,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  val compactWidth = rememberIsCompactWidth()
  val narrowWidth = rememberIsNarrowWidth()
  val shortHeight = rememberIsShortHeight()
  val useCompactHeader = shortHeight || narrowWidth
  var text by remember(profile, server, initialText) { mutableStateOf(initialText) }
  val bind = remember(text) { parseWireProxySocks5Bind(text) }
  val ignoredLabel = remember(profile, server) { wireProxyServerPortLabel(profile, server) }
  val conflict = bind?.port?.let { findWireProxyPortConflictLabel(registry, it, ignoredLabel) }

  Dialog(
    onDismissRequest = { if (!saving) onDismiss() },
    properties = DialogProperties(
      dismissOnBackPress = !saving,
      dismissOnClickOutside = !saving,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(if (compactWidth) 0.96f else 0.90f)
        .fillMaxHeight(if (shortHeight) 0.94f else 0.86f)
        .navigationBarsPadding(),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      shadowElevation = 10.dp,
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = if (compactWidth) 12.dp else 18.dp, vertical = if (shortHeight) 10.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (useCompactHeader) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                stringResource(R.string.wireproxy_config_editor_title_fmt, server),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                if (bind != null) stringResource(R.string.wireproxy_server_bind_fmt, bind.host, bind.port) else stringResource(R.string.wireproxy_socks5_bind_auto_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
              IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp), enabled = !saving) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
              }
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
              IconButton(onClick = { onSave(text) }, modifier = Modifier.size(40.dp), enabled = !saving && !loading) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_save), tint = MaterialTheme.colorScheme.onPrimary)
              }
            }
          }
        } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.wireproxy_config_editor_title_fmt, server), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              Text(
                if (bind != null) stringResource(R.string.wireproxy_server_bind_fmt, bind.host, bind.port) else stringResource(R.string.wireproxy_socks5_bind_auto_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.common_cancel)) }
              Button(onClick = { onSave(text) }, enabled = !saving && !loading) { Text(stringResource(R.string.common_save)) }
            }
          }
        }

        if (loading || saving) {
          LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Text(
          stringResource(R.string.wireproxy_config_editor_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        if (conflict != null && bind != null) {
          Text(
            stringResource(R.string.wireproxy_port_conflict_fmt, bind.port, conflict),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = true),
          enabled = !saving,
          label = { Text(stringResource(R.string.wireproxy_config_label)) },
          singleLine = false,
          minLines = if (shortHeight) 10 else 14,
        )
      }
    }
  }
}
