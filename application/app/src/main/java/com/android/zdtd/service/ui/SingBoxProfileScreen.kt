package com.android.zdtd.service.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private data class SingBoxProfileSettingUi(
  val t2sPort: Int?,
  val t2sWebPort: Int?,
)

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
  return SingBoxProfileSettingUi(
    t2sPort = obj?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 },
    t2sWebPort = obj?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 },
  )
}

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

private fun findSingBoxMixedInboundPort(configText: String): Int? {
  val root = runCatching { JSONObject(configText) }.getOrNull() ?: return null
  val inbounds = root.optJSONArray("inbounds") ?: return null

  fun extractPort(obj: JSONObject?): Int? = obj?.optInt("listen_port", 0)?.takeIf { it in 1..65535 }

  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (inbound.optString("type") == "mixed" && inbound.optString("tag") == "mixed-in") {
      return extractPort(inbound)
    }
  }
  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (inbound.optString("type") == "mixed") {
      return extractPort(inbound)
    }
  }
  return null
}

private fun replaceSingBoxMixedInboundPort(configText: String, newPort: Int): String? {
  if (newPort !in 1..65535) return null
  val root = runCatching { JSONObject(configText) }.getOrNull() ?: return null
  val inbounds = root.optJSONArray("inbounds") ?: return null

  fun replaceAt(index: Int): String {
    val inbound = inbounds.optJSONObject(index) ?: JSONObject()
    inbound.put("listen_port", newPort)
    inbounds.put(index, inbound)
    root.put("inbounds", inbounds)
    return root.toString(2)
  }

  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (inbound.optString("type") == "mixed" && inbound.optString("tag") == "mixed-in") {
      return replaceAt(i)
    }
  }
  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (inbound.optString("type") == "mixed") {
      return replaceAt(i)
    }
  }
  return null
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
    add(setting?.t2sPort, singBoxT2sPortLabel(profile))
    add(setting?.t2sWebPort, singBoxT2sWebPortLabel(profile))
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
  var editServer by remember(profile) { mutableStateOf<String?>(null) }
  var editText by remember(profile) { mutableStateOf("") }
  var editLoading by remember(profile) { mutableStateOf(false) }
  var pendingConfigPlan by remember(profile) { mutableStateOf<ServerConfigPortPlan?>(null) }

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
      editLoading = false
    }
  }

  fun saveProfileSetting(t2sPortText: String, t2sWebPortText: String) {
    val t2sPort = t2sPortText.trim().toIntOrNull()
    val t2sWebPort = t2sWebPortText.trim().toIntOrNull()
    when {
      t2sPort !in 1..65535 -> {
        showSnack(context.getString(R.string.singbox_fill_t2s_port))
        return
      }
      t2sWebPort !in 1..65535 -> {
        showSnack(context.getString(R.string.singbox_fill_t2s_web_port))
        return
      }
      t2sPort == t2sWebPort -> {
        showSnack(context.getString(R.string.singbox_profile_ports_must_differ))
        return
      }
    }
    settingSaving = true
    val obj = JSONObject()
      .put("t2s_port", t2sPort)
      .put("t2s_web_port", t2sWebPort)
    actions.saveJsonData("$basePath/setting", obj) { ok ->
      settingSaving = false
      if (ok) {
        setting = SingBoxProfileSettingUi(t2sPort = t2sPort, t2sWebPort = t2sWebPort)
        refreshGlobalPortRegistry()
      } else {
        showSnack(context.getString(R.string.singbox_auto_save_failed))
      }
    }
  }

  fun applyConfigPlan(plan: ServerConfigPortPlan) {
    val encodedServer = URLEncoder.encode(plan.serverName, "UTF-8")
    val updatedConfig = when {
      plan.replacementPort != null -> replaceSingBoxMixedInboundPort(plan.originalConfig, plan.replacementPort)
      else -> plan.originalConfig
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
    SingBoxImportToProfileDialog(
      existing = servers.map { it.name },
      suggestedPort = findNextAvailableSingBoxPort(globalPortRegistry, 2080),
      onDismiss = { showImportServer = false },
      onGenerate = { serverName, sourceText ->
        val preferredPort = findNextAvailableSingBoxPort(globalPortRegistry, 2080)
        val imported = runCatching { com.android.zdtd.service.singbox.importer.SingBoxOneLineImporter.import(sourceText, preferredPort) }
          .getOrElse {
            showSnack(context.getString(R.string.singbox_import_failed_fmt, it.message ?: context.getString(R.string.singbox_parse_error)))
            return@SingBoxImportToProfileDialog
          }
        var configToSave = imported.configJson
        val detectedPort = findSingBoxMixedInboundPort(configToSave)
        val resolvedPort = when {
          detectedPort == null -> preferredPort
          findSingBoxPortConflictLabel(globalPortRegistry, detectedPort) == null -> detectedPort
          else -> {
            val next = findNextAvailableSingBoxPort(globalPortRegistry, detectedPort)
            configToSave = replaceSingBoxMixedInboundPort(configToSave, next) ?: configToSave
            next
          }
        }
        showImportServer = false
        actions.createSingBoxServer(profile, serverName) { created ->
          if (created == null) {
            showSnack(context.getString(R.string.create_failed))
            return@createSingBoxServer
          }
          val encodedServer = URLEncoder.encode(created, "UTF-8")
          actions.saveText("$basePath/servers/$encodedServer/config", configToSave) { configOk ->
            if (!configOk) {
              showSnack(context.getString(R.string.save_failed))
              refreshServers()
              return@saveText
            }
            val payload = JSONObject().put("enabled", true).put("port", resolvedPort)
            actions.saveJsonData("$basePath/servers/$encodedServer/setting", payload) { settingOk ->
              if (settingOk) {
                rememberGeneratedServer(created, resolvedPort)
                showSnack(context.getString(R.string.singbox_import_created_fmt, created, resolvedPort))
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
        Button(enabled = !editLoading, onClick = {
          val serverName = editServer ?: return@Button
          val parsed = runCatching { JSONObject(editText.trim()) }.getOrElse {
            showSnack(context.getString(R.string.singbox_invalid_json_fmt, it.message ?: context.getString(R.string.singbox_parse_error)))
            return@Button
          }
          val normalizedText = parsed.toString(2)
          val detectedPort = findSingBoxMixedInboundPort(normalizedText)
          val currentServer = servers.firstOrNull { it.name == serverName }

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

  Column(
    Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("sing-box / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Text(
      stringResource(R.string.changes_apply_after_restart),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = stringResource(R.string.enabled_card_profile_title),
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("sing-box", profile, v) },
    )

    SingBoxProfileSettingCard(
      setting = setting,
      loading = settingLoading,
      saving = settingSaving,
      onSave = ::saveProfileSetting,
    )

    AppListPickerCard(
      title = stringResource(R.string.singbox_apps_title),
      desc = stringResource(R.string.apps_common_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
    )

    SingBoxServersSection(
      profile = profile,
      basePath = basePath,
      servers = servers,
      loading = serversLoading,
      actions = actions,
      snackHost = snackHost,
      onCreateServer = { showCreateServer = true },
      onGenerateServer = { showImportServer = true },
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
private fun SingBoxServersSection(
  profile: String,
  basePath: String,
  servers: List<SingBoxServerUi>,
  loading: Boolean,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  onCreateServer: () -> Unit,
  onGenerateServer: () -> Unit,
  onRefresh: () -> Unit,
  onEditConfig: (String) -> Unit,
) {
  ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.singbox_servers_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.singbox_servers_desc),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          style = MaterialTheme.typography.bodySmall,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilledTonalButton(onClick = onCreateServer, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.singbox_profile_add_server))
          }
          OutlinedButton(onClick = onGenerateServer, modifier = Modifier.weight(1f)) {
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
    val port = portText.trim().toIntOrNull()
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
  val validPort = portText.trim().toIntOrNull() in 1..65535

  LaunchedEffect(enabled, portText, server.enabled, currentPortText, saving) {
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

      OutlinedTextField(
        value = portText,
        onValueChange = { portText = it.filter(Char::isDigit) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !saving,
        singleLine = true,
        label = { Text(stringResource(R.string.singbox_server_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )

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
  onDismiss: () -> Unit,
  onGenerate: (String, String) -> Unit,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val existingNorm = remember(existing) { existing.map { normalizeSingBoxServerName(it) }.toSet() }
  var raw by remember { mutableStateOf("") }
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
            raw = it
            error = null
          },
          label = { Text(stringResource(R.string.singbox_server_name_label)) },
          singleLine = false,
          maxLines = 2,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          supportingText = {
            Column {
              Text(stringResource(R.string.singbox_create_server_rules))
              Text(stringResource(R.string.singbox_import_auto_port_hint, suggestedPort))
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
          existingNorm.contains(n) -> {
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

