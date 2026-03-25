package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.android.zdtd.service.R
import androidx.compose.material.icons.filled.Edit
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramScreen(
  programs: List<ApiModels.Program>,
  programId: String,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val useScrollableTabs = rememberUseScrollableTabs()
  val narrow = rememberIsNarrowWidth()
  val program = programs.firstOrNull { it.id == programId }
  if (program == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.program_not_found)) }
    return
  }

  val context = LocalContext.current

  val scope = rememberCoroutineScope()

  val hasStrategicFiles = program.id == "nfqws" || program.id == "nfqws2"
  var programTab by remember(program.id) { mutableStateOf(0) }

  var showCreateProfile by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val sortedProfiles = remember(program.profiles) { sortProfilesDesc(program.profiles) }
  // Delete is allowed only in decreasing order: if there are profile1 and profile2,
  // the UI should only allow deleting profile2 first.
  val maxNumericProfileIdx = remember(program.profiles) {
    program.profiles.map { profileIndex(it.name) }.filter { it != Int.MIN_VALUE }.maxOrNull() ?: Int.MIN_VALUE
  }

  // Dialogs must be invoked from a @Composable context (not inside LazyColumn/LazyListScope).
  if (program.type == "profiles" && showCreateProfile && (!hasStrategicFiles || programTab == 0)) {
    CreateProfileDialog(
      existing = program.profiles.map { it.name },
      onDismiss = { showCreateProfile = false },
      onCreate = { name ->
        showCreateProfile = false
        actions.createNamedProfile(program.id, name) { created ->
          if (created != null) {
            showSnack(context.getString(R.string.profile_created_fmt, created))
            onOpenProfile(program.id, created)
          } else {
            showSnack(context.getString(R.string.create_failed))
          }
        }
      },
      snackHost = snackHost,
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(if (compact) 12.dp else 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Text(program.name ?: program.id, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
      Spacer(Modifier.height(4.dp))
      Text(
        stringResource(R.string.changes_apply_after_restart),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        style = MaterialTheme.typography.bodySmall,
      )
    }

    // Global enabled toggle:
    // - MUST exist for dnscrypt + operaproxy (per Danil)
    // - sing-box has a custom enabled inside its own setting.json
    // - MUST NOT exist for zapret/dpitunnel/byedpi (it's useless there)
    if (program.id == "dnscrypt" || program.id == "operaproxy") {
      item {
        EnabledCard(
          title = stringResource(R.string.enabled),
          checked = program.enabled,
          onCheckedChange = { v -> actions.setProgramEnabled(program.id, v) },
        )
      }
    }

    when {
      
program.type == "profiles" -> {
        item {
          if (hasStrategicFiles) {
            if (useScrollableTabs) {
              ScrollableTabRow(selectedTabIndex = programTab, edgePadding = 12.dp) {
                Tab(selected = programTab == 0, onClick = { programTab = 0 }, text = { Text(stringResource(R.string.tab_profiles), maxLines = 2) })
                Tab(selected = programTab == 1, onClick = { programTab = 1 }, text = { Text(stringResource(R.string.tab_files), maxLines = 2) })
              }
            } else {
              TabRow(selectedTabIndex = programTab) {
                Tab(selected = programTab == 0, onClick = { programTab = 0 }, text = { Text(stringResource(R.string.tab_profiles), maxLines = 2) })
                Tab(selected = programTab == 1, onClick = { programTab = 1 }, text = { Text(stringResource(R.string.tab_files), maxLines = 2) })
              }
            }
            Spacer(Modifier.height(10.dp))
          }

          if (!hasStrategicFiles || programTab == 0) {
            ProfilesHeader(onAdd = { showCreateProfile = true })
          }
        }

        if (!hasStrategicFiles || programTab == 0) {
          items(sortedProfiles, key = { it.name }) { prof ->
            ProfileRow(
              programId = program.id,
              profile = prof,
              onOpen = { onOpenProfile(program.id, prof.name) },
              onToggle = { v -> actions.setProfileEnabled(program.id, prof.name, v) },
              onDelete = {
                actions.deleteProfile(program.id, prof.name) { ok ->
                  showSnack(
                    if (ok) context.getString(R.string.deleted)
                    else context.getString(R.string.delete_failed)
                  )
                }
              },
              deletable = run {
                val idx = profileIndex(prof.name)
                idx == Int.MIN_VALUE || idx == maxNumericProfileIdx
              },
            )
          }
        } else {
          item {
            ZapretStrategicFiles(programId = program.id, actions = actions, snackHost = snackHost)
          }
        }
      }

      program.id == "dnscrypt" -> {
        item {
          TextEditorCard(
            title = "dnscrypt-proxy.toml",
            desc = stringResource(R.string.dnscrypt_main_config_desc),
            path = "/api/programs/dnscrypt/config",
            actions = actions,
            snackHost = snackHost,
          )
        }
        item {
          Spacer(Modifier.height(10.dp))
          DnscryptSettingFilesSection(actions = actions, snackHost = snackHost)
        }
      }

      program.id == "operaproxy" -> {
        item {
          OperaProxySection(actions = actions, snackHost = snackHost)
        }
      }

      program.id == "sing-box" -> {
        item {
          SingBoxSection(program = program, actions = actions, snackHost = snackHost)
        }
      }

      else -> {
        item {
          Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
            Column(Modifier.padding(12.dp)) {
              Text(stringResource(R.string.not_implemented_yet), fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Text(
                stringResource(R.string.not_implemented_yet_desc),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              )
            }
          }
        }
      }
    }

    item { Spacer(Modifier.height(80.dp)) }
  }
}

// ---------------- sing-box UI ----------------

private data class SingProfile(
  val name: String,
  val enabled: Boolean,
  val port: Int?,
  val capture: String?,
)

private data class SingSetting(
  val enabled: Boolean,
  val mode: String,
  val t2sPort: Int?,
  val t2sWebPort: Int?,
  val activeTransparentProfile: String,
  val profiles: List<SingProfile>,
)

private fun parseSingSetting(o: JSONObject?): SingSetting? {
  if (o == null) return null
  val enabled = o.optBoolean("enabled", false)
  val mode = o.optString("mode", "socks5")
  val t2sPort = o.optInt("t2s_port", 0).takeIf { it > 0 }
  val t2sWebPort = o.optInt("t2s_web_port", 8001).takeIf { it > 0 }
  val active = o.optString("active_transparent_profile", "")
  val arr = o.optJSONArray("profiles") ?: JSONArray()
  val profiles = buildList {
    for (i in 0 until arr.length()) {
      val p = arr.optJSONObject(i) ?: continue
      val name = p.optString("name", "").trim()
      if (name.isEmpty()) continue
      add(
        SingProfile(
          name = name,
          enabled = p.optBoolean("enabled", false),
          port = p.optInt("port", 0).takeIf { it > 0 },
          capture = p.optString("capture", "").takeIf { it.isNotBlank() },
        )
      )
    }
  }
  return SingSetting(
    enabled = enabled,
    mode = mode,
    t2sPort = t2sPort,
    t2sWebPort = t2sWebPort,
    activeTransparentProfile = active,
    profiles = profiles,
  )
}

private fun buildSingSettingJson(s: SingSetting): JSONObject {
  val out = JSONObject()
  out.put("enabled", s.enabled)
  out.put("mode", s.mode)
  out.put("t2s_port", s.t2sPort ?: 0)
  out.put("t2s_web_port", s.t2sWebPort ?: 8001)
  out.put("active_transparent_profile", s.activeTransparentProfile)
  val arr = JSONArray()
  for (p in s.profiles) {
    val o = JSONObject()
    o.put("name", p.name)
    o.put("enabled", p.enabled)
    o.put("port", p.port ?: 0)
    if (!p.capture.isNullOrBlank()) o.put("capture", p.capture)
    arr.put(o)
  }
  out.put("profiles", arr)
  return out
}

private fun isValidProfileName(name: String): Boolean {
  if (name.isBlank()) return false
  // English symbols, no spaces.
  return name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

private fun isValidPort(v: Int?): Boolean {
  if (v == null) return false
  return v in 1..65535
}

private data class SingConfigPortPlan(
  val profileName: String,
  val originalConfig: String,
  val detectedPort: Int,
  val applyPortToProfile: Boolean,
  val conflictProfileName: String? = null,
  val replacementPort: Int? = null,
)

private fun findSingMixedInboundPort(configText: String): Int? {
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

private fun replaceSingMixedInboundPort(configText: String, newPort: Int): String? {
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

private fun findNextAvailableSingPort(profiles: List<SingProfile>, preferredPort: Int): Int {
  val used = profiles.mapNotNull { it.port }.toMutableSet()
  if (preferredPort in 1..65535 && preferredPort !in used) return preferredPort
  var port = preferredPort.coerceIn(1, 65535)
  while (port in used && port < 65535) port += 1
  if (port !in used && port in 1..65535) return port
  port = 1
  while (port in used && port < 65535) port += 1
  return port.coerceIn(1, 65535)
}

private fun updateSingProfilePort(setting: SingSetting, profileName: String, newPort: Int): SingSetting =
  setting.copy(
    profiles = setting.profiles.map { profile ->
      if (profile.name == profileName) profile.copy(port = newPort) else profile
    }
  )


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingBoxSection(
  program: ApiModels.Program,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val narrow = rememberIsNarrowWidth()
  fun showSnack(msg: String) { scope.launch { snackHost.showSnackbar(msg) } }

  var rawJson by remember { mutableStateOf<JSONObject?>(null) }
  var setting by remember { mutableStateOf<SingSetting?>(null) }
  var loading by remember { mutableStateOf(false) }

  // Config editor dialog state.
  var editProfile by remember { mutableStateOf<String?>(null) }
  var editText by remember { mutableStateOf("") }
  var editLoading by remember { mutableStateOf(false) }

  // Create profile dialog
  var showCreate by remember { mutableStateOf(false) }
  if (showCreate) {
    CreateProfileDialog(
      existing = (setting?.profiles ?: emptyList()).map { it.name },
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.createNamedProfile("sing-box", name) { created ->
          if (created != null) {
            showSnack(context.getString(R.string.profile_created_fmt, created))
            // Refresh setting.
            loading = true
            actions.loadJsonData("/api/programs/sing-box/setting") { obj ->
              loading = false
              rawJson = obj
              setting = parseSingSetting(obj)
            }
          } else {
            showSnack(context.getString(R.string.create_failed))
          }
        }
      },
      snackHost = snackHost,
    )
  }

  // Initial load
  LaunchedEffect(Unit) {
    loading = true
    actions.loadJsonData("/api/programs/sing-box/setting") { obj ->
      loading = false
      rawJson = obj
      setting = parseSingSetting(obj)
    }
  }

  var pendingConfigPortPlan by remember { mutableStateOf<SingConfigPortPlan?>(null) }

  fun applyConfigSavePlan(plan: SingConfigPortPlan) {
    val updatedConfig = when {
      plan.replacementPort != null -> replaceSingMixedInboundPort(plan.originalConfig, plan.replacementPort)
      else -> plan.originalConfig
    }
    if (updatedConfig == null) {
      showSnack(context.getString(R.string.singbox_listen_port_update_failed))
      return
    }

    val profEnc = URLEncoder.encode(plan.profileName, "UTF-8")
    editLoading = true
    actions.saveText("/api/programs/sing-box/profiles/$profEnc/config", updatedConfig) { ok ->
      if (!ok) {
        editLoading = false
        showSnack(context.getString(R.string.save_failed))
        return@saveText
      }

      val currentSetting = setting
      val portToSave = when {
        plan.replacementPort != null -> plan.replacementPort
        plan.applyPortToProfile -> plan.detectedPort
        else -> null
      }
      if (currentSetting == null || portToSave == null) {
        editLoading = false
        editText = updatedConfig
        showSnack(context.getString(R.string.saved))
        editProfile = null
        return@saveText
      }

      val updatedSetting = updateSingProfilePort(currentSetting, plan.profileName, portToSave)
      val obj = buildSingSettingJson(updatedSetting)
      actions.saveJsonData("/api/programs/sing-box/setting", obj) { settingOk ->
        editLoading = false
        if (settingOk) {
          setting = updatedSetting
          rawJson = obj
          editText = updatedConfig
          showSnack(context.getString(R.string.saved))
          editProfile = null
        } else {
          showSnack(context.getString(R.string.singbox_config_saved_port_update_failed))
        }
      }
    }
  }

  // Editor dialog
  if (editProfile != null) {
    AlertDialog(
      onDismissRequest = { if (!editLoading) editProfile = null },
      title = { Text("config.json / ${editProfile}") },
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
            label = { Text("config.json") },
          )
        }
      },
      confirmButton = {
        Button(enabled = !editLoading, onClick = {
          val prof = editProfile ?: return@Button
          val parsed = runCatching { JSONObject(editText.trim()) }.getOrElse {
            showSnack(context.getString(R.string.singbox_invalid_json_fmt, it.message ?: context.getString(R.string.singbox_parse_error)))
            return@Button
          }
          val normalizedText = parsed.toString(2)
          val detectedPort = findSingMixedInboundPort(normalizedText)
          val currentSetting = setting
          val currentProfile = currentSetting?.profiles?.firstOrNull { it.name == prof }

          if (detectedPort == null || currentSetting == null || currentProfile == null) {
            applyConfigSavePlan(SingConfigPortPlan(prof, normalizedText, currentProfile?.port ?: 0, false))
            return@Button
          }

          if (currentProfile.port == detectedPort) {
            applyConfigSavePlan(SingConfigPortPlan(prof, normalizedText, detectedPort, false))
            return@Button
          }

          val conflict = currentSetting.profiles.firstOrNull { it.name != prof && it.port == detectedPort }
          pendingConfigPortPlan = if (conflict == null) {
            SingConfigPortPlan(
              profileName = prof,
              originalConfig = normalizedText,
              detectedPort = detectedPort,
              applyPortToProfile = true,
            )
          } else {
            SingConfigPortPlan(
              profileName = prof,
              originalConfig = normalizedText,
              detectedPort = detectedPort,
              applyPortToProfile = true,
              conflictProfileName = conflict.name,
              replacementPort = findNextAvailableSingPort(currentSetting.profiles, detectedPort),
            )
          }
        }) { Text(stringResource(R.string.action_save)) }
      },
      dismissButton = {
        TextButton(enabled = !editLoading, onClick = { editProfile = null }) { Text(stringResource(R.string.action_cancel)) }
      }
    )
  }

  pendingConfigPortPlan?.let { plan ->
    val message = if (plan.conflictProfileName == null) {
      "В config.json найден listen_port ${plan.detectedPort}. Сохранить этот порт в профиль ${plan.profileName}?"
    } else {
      "Порт ${plan.detectedPort} уже используется профилем ${plan.conflictProfileName}. Предлагаемый свободный порт: ${plan.replacementPort}. Заменить порт в config.json и сохранить его в профиль ${plan.profileName}?"
    }
    AlertDialog(
      onDismissRequest = { pendingConfigPortPlan = null },
      title = { Text(stringResource(R.string.singbox_port_sync_title)) },
      text = { Text(message) },
      confirmButton = {
        Button(onClick = {
          pendingConfigPortPlan = null
          applyConfigSavePlan(plan)
        }) { Text(if (plan.conflictProfileName == null) "Сохранить" else "Заменить и сохранить") }
      },
      dismissButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = {
            pendingConfigPortPlan = null
            applyConfigSavePlan(plan.copy(applyPortToProfile = false, replacementPort = null))
          }) { Text(if (plan.conflictProfileName == null) "Не менять профиль" else "Сохранить только config") }
          TextButton(onClick = { pendingConfigPortPlan = null }) { Text(stringResource(R.string.action_cancel)) }
        }
      }
    )
  }

  val s0 = setting
  if (s0 == null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
      Column(Modifier.padding(12.dp)) {
        Text("sing-box", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
          if (loading) "Loading..." else "Failed to load setting.json",
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }
    return
  }

  var enabled by remember(s0.enabled) { mutableStateOf(s0.enabled) }
  var mode by remember(s0.mode) { mutableStateOf(s0.mode) }
  var t2sPortTxt by remember(s0.t2sPort) { mutableStateOf((s0.t2sPort ?: 0).toString()) }
  var t2sWebPortTxt by remember(s0.t2sWebPort) { mutableStateOf((s0.t2sWebPort ?: 8001).toString()) }
  var activeTransparent by remember(s0.activeTransparentProfile) { mutableStateOf(s0.activeTransparentProfile) }
  var profiles by remember(s0.profiles) { mutableStateOf(s0.profiles) }
  var lastSavedSettingJson by remember(s0) { mutableStateOf(buildSingSettingJson(s0).toString()) }
  var showTransparentUnavailableDialog by remember { mutableStateOf(false) }
  var autoSaveInFlight by remember { mutableStateOf(false) }
  var pendingAutoSave by remember { mutableStateOf(false) }
  var autoSaveRetryTick by remember { mutableStateOf(0) }

  fun validateAndBuild(showErrors: Boolean = true): SingSetting? {
    val t2sPort = t2sPortTxt.trim().toIntOrNull()
    val t2sWebPort = t2sWebPortTxt.trim().toIntOrNull()

    // Profile validation
    for (p in profiles) {
      if (!isValidProfileName(p.name)) {
        if (showErrors) showSnack(context.getString(R.string.singbox_invalid_profile_name_fmt, p.name))
        return null
      }
      if (!isValidPort(p.port)) {
        if (showErrors) showSnack(context.getString(R.string.singbox_invalid_port_for_profile_fmt, p.name))
        return null
      }
      val cap = p.capture ?: "tcp"
      if (cap != "tcp" && cap != "tcp_udp") {
        if (showErrors) showSnack(context.getString(R.string.singbox_invalid_capture_for_profile_fmt, p.name))
        return null
      }
    }
    val ports = profiles.mapNotNull { it.port }
    if (ports.size != ports.distinct().size) {
      if (showErrors) showSnack(context.getString(R.string.singbox_profile_ports_must_be_unique))
      return null
    }

    if (mode != "socks5" && mode != "transparent") {
      if (showErrors) showSnack(context.getString(R.string.singbox_select_mode))
      return null
    }

    if (mode == "socks5") {
      if (!isValidPort(t2sPort)) {
        if (showErrors) showSnack(context.getString(R.string.singbox_fill_t2s_port))
        return null
      }
      if (!isValidPort(t2sWebPort)) {
        if (showErrors) showSnack(context.getString(R.string.singbox_fill_t2s_web_port))
        return null
      }
      // Must have at least one enabled profile in socks5
      if (profiles.none { it.enabled }) {
        if (showErrors) showSnack(context.getString(R.string.singbox_enable_at_least_one_profile))
        return null
      }
    }

    if (mode == "transparent") {
      if (activeTransparent.isBlank()) {
        if (showErrors) showSnack(context.getString(R.string.singbox_select_active_transparent_profile))
        return null
      }
      if (profiles.none { it.name == activeTransparent }) {
        if (showErrors) showSnack(context.getString(R.string.singbox_active_transparent_profile_not_found))
        return null
      }
    }

    return SingSetting(
      enabled = enabled,
      mode = mode,
      t2sPort = t2sPort,
      t2sWebPort = t2sWebPort,
      activeTransparentProfile = activeTransparent,
      profiles = profiles,
    )
  }

  if (showTransparentUnavailableDialog) {
    AlertDialog(
      onDismissRequest = { showTransparentUnavailableDialog = false },
      title = { Text(stringResource(R.string.singbox_mode_transparent)) },
      text = { Text(stringResource(R.string.singbox_transparent_unavailable_message)) },
      confirmButton = {
        Button(onClick = { showTransparentUnavailableDialog = false }) {
          Text(stringResource(R.string.common_ok))
        }
      },
    )
  }

  LaunchedEffect(mode, t2sPortTxt, t2sWebPortTxt, activeTransparent, profiles, autoSaveRetryTick) {
    delay(400)
    val candidate = validateAndBuild(showErrors = false) ?: return@LaunchedEffect
    val obj = buildSingSettingJson(candidate)
    val json = obj.toString()
    if (json == lastSavedSettingJson) return@LaunchedEffect
    if (autoSaveInFlight) {
      pendingAutoSave = true
      return@LaunchedEffect
    }
    autoSaveInFlight = true
    pendingAutoSave = false
    actions.saveJsonData("/api/programs/sing-box/setting", obj) { ok ->
      autoSaveInFlight = false
      if (ok) {
        setting = candidate
        rawJson = obj
        lastSavedSettingJson = json
      } else {
        showSnack(context.getString(R.string.singbox_auto_save_failed))
      }
      val latestCandidate = validateAndBuild(showErrors = false)
      val latestJson = latestCandidate?.let { buildSingSettingJson(it).toString() }
      if (pendingAutoSave || (latestJson != null && latestJson != lastSavedSettingJson)) {
        pendingAutoSave = false
        autoSaveRetryTick += 1
      }
    }
  }


  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    EnabledCard(
      title = stringResource(R.string.enabled),
      checked = enabled,
      onCheckedChange = { v ->
        // Spec: top Enabled switch must update setting.json immediately, without pressing Save.
        val prev = enabled
        enabled = v
        val obj = (rawJson ?: JSONObject()).also { it.put("enabled", v) }
        actions.saveJsonData("/api/programs/sing-box/setting", obj) { ok ->
          if (!ok) {
            enabled = prev
            showSnack(context.getString(R.string.save_failed))
          } else {
            rawJson = obj
            lastSavedSettingJson = obj.toString()
            // Refresh program list so the Programs screen reflects enabled state immediately.
            actions.refreshPrograms()
          }
        }
      },
    )

    Card {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.singbox_mode_title), fontWeight = FontWeight.SemiBold)
        if (narrow) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
            selected = mode == "socks5",
            onClick = { mode = "socks5" },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
              selectedLabelColor = MaterialTheme.colorScheme.error,
            ),
            label = { Text(stringResource(R.string.singbox_mode_socks5)) },
          )
          FilterChip(
            selected = mode == "transparent",
            onClick = { showTransparentUnavailableDialog = true },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
              selectedLabelColor = MaterialTheme.colorScheme.error,
            ),
            label = { Text(stringResource(R.string.singbox_mode_transparent)) },
          )
          }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
              selected = mode == "socks5",
              onClick = { mode = "socks5" },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.error,
              ),
              label = { Text(stringResource(R.string.singbox_mode_socks5)) },
            )
            FilterChip(
              selected = mode == "transparent",
              onClick = { showTransparentUnavailableDialog = true },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.error,
              ),
              label = { Text(stringResource(R.string.singbox_mode_transparent)) },
            )
          }
        }

        if (mode == "socks5") {
          OutlinedTextField(
            value = t2sPortTxt,
            onValueChange = { t2sPortTxt = it },
            label = { Text(stringResource(R.string.singbox_t2s_port_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = t2sWebPortTxt,
            onValueChange = { t2sWebPortTxt = it },
            label = { Text(stringResource(R.string.singbox_t2s_web_port_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            "Capture: TCP only (always)",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall,
          )
        }

        if (mode == "transparent") {
          // Active profile selector
          val names = profiles.map { it.name }
          var expanded by remember { mutableStateOf(false) }
          ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
              value = activeTransparent,
              onValueChange = {},
              readOnly = true,
              modifier = Modifier.menuAnchor().fillMaxWidth(),
              label = { Text(stringResource(R.string.singbox_active_transparent_profile_label)) },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
              names.forEach { n ->
                DropdownMenuItem(
                  text = { Text(n) },
                  onClick = {
                    activeTransparent = n
                    expanded = false
                  },
                )
              }
            }
          }
        }
      }
    }

    // Global apps list (common only)
    AppListPickerCard(
      title = stringResource(R.string.apps_common_title),
      desc = stringResource(R.string.apps_common_desc),
      path = "/api/programs/sing-box/apps/user",
      actions = actions,
      snackHost = snackHost,
    )

    // Profiles
    Card {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val compactProfiles = rememberIsCompactWidth()
        if (compactProfiles) {
          Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tab_profiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FilledTonalButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Filled.Add, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.action_add))
            }
          }
        } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_profiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FilledTonalButton(onClick = { showCreate = true }) {
              Icon(Icons.Filled.Add, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.action_add))
            }
          }
        }

        profiles.forEach { p ->
          Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              val compactRow = rememberIsCompactWidth()
              if (compactRow) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                      val prof = p.name
                      editProfile = prof
                      editLoading = true
                      val profEnc = URLEncoder.encode(prof, "UTF-8")
                      actions.loadText("/api/programs/sing-box/profiles/$profEnc/config") { txt ->
                        editLoading = false
                        editText = txt ?: ""
                      }
                    }) {
                      Icon(Icons.Filled.Edit, contentDescription = null)
                    }
                    IconButton(onClick = {
                      actions.deleteProfile("sing-box", p.name) { ok ->
                        if (ok) {
                          showSnack(context.getString(R.string.deleted))
                          profiles = profiles.filterNot { it.name == p.name }
                          if (activeTransparent == p.name) activeTransparent = ""
                          rawJson = (rawJson ?: JSONObject()).also { obj ->
                            val arr = obj.optJSONArray("profiles")
                            if (arr != null) {
                              val newArr = org.json.JSONArray()
                              for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                if (item.optString("name") != p.name) newArr.put(item)
                              }
                              obj.put("profiles", newArr)
                            }
                            if (obj.optString("active_transparent_profile") == p.name) {
                              obj.put("active_transparent_profile", "")
                            }
                          }
                          loading = true
                          actions.loadJsonData("/api/programs/sing-box/setting") { obj ->
                            loading = false
                            rawJson = obj
                            setting = parseSingSetting(obj)
                          }
                        } else {
                          showSnack(context.getString(R.string.delete_failed))
                        }
                      }
                    }) {
                      Icon(Icons.Filled.Delete, contentDescription = null)
                    }
                  }
                }
              } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                  Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                      val prof = p.name
                      editProfile = prof
                      editLoading = true
                      val profEnc = URLEncoder.encode(prof, "UTF-8")
                      actions.loadText("/api/programs/sing-box/profiles/$profEnc/config") { txt ->
                        editLoading = false
                        editText = txt ?: ""
                      }
                    }) {
                      Icon(Icons.Filled.Edit, contentDescription = null)
                    }
                    IconButton(onClick = {
                      actions.deleteProfile("sing-box", p.name) { ok ->
                        if (ok) {
                          showSnack(context.getString(R.string.deleted))
                          profiles = profiles.filterNot { it.name == p.name }
                          if (activeTransparent == p.name) activeTransparent = ""
                          rawJson = (rawJson ?: JSONObject()).also { obj ->
                            val arr = obj.optJSONArray("profiles")
                            if (arr != null) {
                              val newArr = org.json.JSONArray()
                              for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                if (item.optString("name") != p.name) newArr.put(item)
                              }
                              obj.put("profiles", newArr)
                            }
                            if (obj.optString("active_transparent_profile") == p.name) {
                              obj.put("active_transparent_profile", "")
                            }
                          }
                          loading = true
                          actions.loadJsonData("/api/programs/sing-box/setting") { obj ->
                            loading = false
                            rawJson = obj
                            setting = parseSingSetting(obj)
                          }
                        } else {
                          showSnack(context.getString(R.string.delete_failed))
                        }
                      }
                    }) {
                      Icon(Icons.Filled.Delete, contentDescription = null)
                    }
                  }
                }
              }

              if (mode == "socks5") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                  Text(stringResource(R.string.enabled))
                  Switch(
                    checked = p.enabled,
                    onCheckedChange = { v ->
                      profiles = profiles.map { if (it.name == p.name) it.copy(enabled = v) else it }
                    },
                  )
                }
              } else {
                Text(
                  stringResource(R.string.singbox_enabled_only_in_socks5_mode),
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                  style = MaterialTheme.typography.bodySmall,
                )
              }

              OutlinedTextField(
                value = (p.port ?: 0).toString(),
                onValueChange = { txt ->
                  val v = txt.trim().toIntOrNull()
                  profiles = profiles.map { if (it.name == p.name) it.copy(port = v) else it }
                },
                label = { Text(stringResource(R.string.common_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
              )

              if (mode == "transparent" && activeTransparent == p.name) {
                val cap = (p.capture ?: "tcp")
                if (compactRow) {
                  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                      selected = cap == "tcp",
                      onClick = { profiles = profiles.map { if (it.name == p.name) it.copy(capture = "tcp") else it } },
                      colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.error,
                      ),
                      label = { Text(stringResource(R.string.singbox_capture_tcp)) },
                    )
                    FilterChip(
                      selected = cap == "tcp_udp",
                      onClick = { profiles = profiles.map { if (it.name == p.name) it.copy(capture = "tcp_udp") else it } },
                      colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.error,
                      ),
                      label = { Text(stringResource(R.string.singbox_capture_tcp_udp)) },
                    )
                  }
                } else {
                  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                      selected = cap == "tcp",
                      onClick = { profiles = profiles.map { if (it.name == p.name) it.copy(capture = "tcp") else it } },
                      colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.error,
                      ),
                      label = { Text(stringResource(R.string.singbox_capture_tcp)) },
                    )
                    FilterChip(
                      selected = cap == "tcp_udp",
                      onClick = { profiles = profiles.map { if (it.name == p.name) it.copy(capture = "tcp_udp") else it } },
                      colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.error,
                      ),
                      label = { Text(stringResource(R.string.singbox_capture_tcp_udp)) },
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProfilesHeader(onAdd: () -> Unit) {
  val compact = rememberIsCompactWidth()
  if (compact) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(stringResource(R.string.tab_profiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.action_add))
      }
    }
  } else {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.tab_profiles), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      FilledTonalButton(onClick = onAdd) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.action_add))
      }
    }
  }
}

private fun sortProfilesDesc(list: List<ApiModels.Profile>): List<ApiModels.Profile> {
  // Danil: delete profiles in decreasing order; also show newest (highest) first.
  return list.sortedWith(compareByDescending<ApiModels.Profile> {
    profileIndex(it.name)
  }.thenByDescending { it.name.lowercase() })
}

private fun profileIndex(name: String): Int {
  // Supports both "1" and "profile1" formats.
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) {
    return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  }
  return Int.MIN_VALUE
}

@Composable
private fun ProfileRow(
  programId: String,
  profile: ApiModels.Profile,
  onOpen: () -> Unit,
  onToggle: (Boolean) -> Unit,
  onDelete: () -> Unit,
  deletable: Boolean,
) {
  var askDelete by remember { mutableStateOf(false) }
  if (askDelete) {
    AlertDialog(
      onDismissRequest = { askDelete = false },
      title = { Text(stringResource(R.string.delete_profile_title)) },
      text = { Text("$programId / ${profile.name}") },
      confirmButton = {
        Button(onClick = { askDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete)) }
      },
      dismissButton = { OutlinedButton(onClick = { askDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  val compact = rememberIsCompactWidth()
  Card(
    onClick = onOpen,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
  ) {
    if (compact) {
      Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column {
          Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
          Spacer(Modifier.height(2.dp))
          Text(stringResource(R.string.apply_after_restart_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Switch(checked = profile.enabled, onCheckedChange = onToggle)
          if (deletable) {
            IconButton(onClick = { askDelete = true }) {
              Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
            }
          } else {
            Spacer(Modifier.width(48.dp))
          }
        }
      }
    } else {
      Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
          Spacer(Modifier.height(2.dp))
          Text(stringResource(R.string.apply_after_restart_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        Switch(checked = profile.enabled, onCheckedChange = onToggle)
        if (deletable) {
          IconButton(onClick = { askDelete = true }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
          }
        }
      }
    }
  }
}

@Composable
private fun CreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
  snackHost: SnackbarHostState,
) {
  val scope = rememberCoroutineScope()
  val existingNorm = remember(existing) { existing.map { normalizeProfileName(it) }.toSet() }

  var raw by remember { mutableStateOf("") }
  val name = remember(raw) { normalizeProfileName(raw) }
  var error by remember { mutableStateOf<String?>(null) }

  fun snack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val enterNameErr = stringResource(R.string.enter_a_name)
  val invalidNameSnack = stringResource(R.string.invalid_profile_name)
  val profileExistsErr = stringResource(R.string.profile_already_exists)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.create_profile_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          stringResource(R.string.create_profile_rules),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )

        OutlinedTextField(
          value = name,
          onValueChange = { v ->
            // Keep 'raw' so we can normalize consistently; but show normalized in the field.
            raw = v
            error = null
          },
          label = { Text(stringResource(R.string.profile_name_label)) },
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
      Button(
        onClick = {
          val n = name.trim()
          when {
            n.isEmpty() -> {
              error = enterNameErr
              snack(invalidNameSnack)
            }
            existingNorm.contains(n) -> {
              error = profileExistsErr
              snack(profileExistsErr)
            }
            else -> onCreate(n)
          }
        },
        enabled = name.isNotBlank(),
      ) { Text(stringResource(R.string.action_create)) }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

private fun normalizeProfileName(input: String): String {
  // Requirements from Danil:
  // - English only
  // - no spaces (convert spaces to _)
  // - max length 10
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

@Composable
private fun OperaProxySection(actions: ZdtdActions, snackHost: SnackbarHostState) {
  var tab by remember { mutableStateOf(0) }
  val useScrollableTabs = rememberUseScrollableTabs()

  val scope = rememberCoroutineScope()
  fun snack(msg: String) { scope.launch { snackHost.showSnackbar(msg) } }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(R.string.opera_proxy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (useScrollableTabs) {
      ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_apps), maxLines = 2) })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_byedpi), maxLines = 2) })
        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_sni), maxLines = 2) })
        Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(stringResource(R.string.tab_servers), maxLines = 2) })
      }
    } else {
      TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_apps), maxLines = 2) })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_byedpi), maxLines = 2) })
        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_sni), maxLines = 2) })
        Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(stringResource(R.string.tab_servers), maxLines = 2) })
      }
    }

    when (tab) {
      0 -> {
        // Apps lists: common + mobile + Wi‑Fi (like nfqws).
        NfqwsAppListsSection(
          pfx = "/api/programs/operaproxy",
          actions = actions,
          snackHost = snackHost,
        )
      }
      1 -> {
        TextEditorCard(
          title = stringResource(R.string.byedpi_start_args_title),
          desc = stringResource(R.string.byedpi_start_args_desc),
          path = "/api/programs/operaproxy/byedpi/start_args",
          actions = actions,
          snackHost = snackHost,
        )
        Spacer(Modifier.height(10.dp))
        TextEditorCard(
          title = stringResource(R.string.byedpi_restart_args_title),
          desc = stringResource(R.string.byedpi_restart_args_desc),
          path = "/api/programs/operaproxy/byedpi/restart_args",
          actions = actions,
          snackHost = snackHost,
        )
        Spacer(Modifier.height(10.dp))
        JsonEditorCard(
          title = stringResource(R.string.opera_ports_title),
          desc = stringResource(R.string.opera_ports_desc),
          path = "/api/programs/operaproxy/ports",
          actions = actions,
          snackHost = snackHost,
        )
      }
      2 -> {
        OperaSniJsonSection(actions = actions, snack = ::snack)
      }
      3 -> {
        OperaServerSection(actions = actions, snack = ::snack)
      }
    }
  }
}


private data class OperaSniItemUi(
  val sni: String = "",
  val useByedpi: Boolean = false,
)

@Composable
private fun OperaSniJsonSection(
  actions: ZdtdActions,
  snack: (String) -> Unit,
) {
  val apiPath = "/api/programs/operaproxy/sni"
  val savedMsg = stringResource(R.string.saved)
  val saveFailedMsg = stringResource(R.string.save_failed)

  var items by remember { mutableStateOf(listOf<OperaSniItemUi>()) }
  var loaded by remember { mutableStateOf(false) }

  fun parseItems(raw: String?): List<OperaSniItemUi> {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(text)
      buildList {
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val sni = o.optString("sni", "").trim()
          if (sni.isEmpty()) continue
          add(OperaSniItemUi(sni = sni, useByedpi = o.optBoolean("use_byedpi", false)))
        }
      }
    }.getOrDefault(emptyList())
  }

  fun toJson(items: List<OperaSniItemUi>): String {
    val arr = JSONArray()
    items.forEach { item ->
      val sni = item.sni.trim()
      if (sni.isEmpty()) return@forEach
      arr.put(JSONObject().apply {
        put("sni", sni)
        put("use_byedpi", item.useByedpi)
      })
    }
    return arr.toString(2)
  }

  LaunchedEffect(Unit) {
    actions.loadText(apiPath) { raw ->
      items = parseItems(raw)
      loaded = true
    }
  }

  Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.tab_sni), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.operaproxy_sni_section_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )

      Button(
        onClick = { items = items + OperaSniItemUi() },
      ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.operaproxy_sni_create_new))
      }

      if (!loaded) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      if (loaded && items.isEmpty()) {
        Text(
          stringResource(R.string.operaproxy_sni_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }

      items.forEachIndexed { index, item ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        ) {
          Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(stringResource(R.string.operaproxy_sni_entry_title_fmt, index + 1), fontWeight = FontWeight.SemiBold)
              IconButton(onClick = { items = items.toMutableList().also { it.removeAt(index) } }) {
                Icon(Icons.Default.Delete, contentDescription = null)
              }
            }

            OutlinedTextField(
              value = item.sni,
              onValueChange = { v ->
                items = items.toMutableList().also { it[index] = item.copy(sni = v) }
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = false,
              maxLines = 2,
              label = { Text("SNI") },
              placeholder = { Text(stringResource(R.string.operaproxy_sni_placeholder)) },
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.operaproxy_sni_use_byedpi), fontWeight = FontWeight.Medium)
                Text(
                  stringResource(R.string.operaproxy_sni_use_byedpi_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
              }
              Switch(
                checked = item.useByedpi,
                onCheckedChange = { checked ->
                  items = items.toMutableList().also { it[index] = item.copy(useByedpi = checked) }
                }
              )
            }
          }
        }
      }

      Button(
        onClick = {
          val payload = toJson(items)
          actions.saveText(apiPath, payload) { ok ->
            if (ok) snack(savedMsg) else snack(saveFailedMsg)
          }
        },
        enabled = loaded,
      ) { Text(stringResource(R.string.common_save)) }
    }
  }
}

@Composable
private fun OperaServerSection(
  actions: ZdtdActions,
  snack: (String) -> Unit,
) {
  val compact = rememberIsCompactWidth()
  val apiPath = "/api/programs/operaproxy/server"
  val savedMsg = stringResource(R.string.saved)
  val saveFailedMsg = stringResource(R.string.save_failed)

  fun normalize(raw: String?): String {
    val v = raw.orEmpty().trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().uppercase()
    return if (v == "EU" || v == "AS" || v == "AM") v else "EU"
  }

  var value by remember { mutableStateOf("EU") }
  var loaded by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    actions.loadText(apiPath) { raw ->
      value = normalize(raw)
      loaded = true
    }
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.tab_servers), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.opera_server_region_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )

      if (compact) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          RegionButton(label = stringResource(R.string.region_europe), code = "EU", selected = value == "EU", modifier = Modifier.fillMaxWidth()) {
            actions.saveText(apiPath, "EU\n") { ok ->
              if (ok) {
                value = "EU"
                snack(savedMsg)
              } else {
                snack(saveFailedMsg)
              }
            }
          }
          RegionButton(label = stringResource(R.string.region_asia), code = "AS", selected = value == "AS", modifier = Modifier.fillMaxWidth()) {
            actions.saveText(apiPath, "AS\n") { ok ->
              if (ok) {
                value = "AS"
                snack(savedMsg)
              } else {
                snack(saveFailedMsg)
              }
            }
          }
          RegionButton(label = stringResource(R.string.region_america), code = "AM", selected = value == "AM", modifier = Modifier.fillMaxWidth()) {
            actions.saveText(apiPath, "AM\n") { ok ->
              if (ok) {
                value = "AM"
                snack(savedMsg)
              } else {
                snack(saveFailedMsg)
              }
            }
          }
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          RegionButton(label = stringResource(R.string.region_europe), code = "EU", selected = value == "EU") {
            actions.saveText(apiPath, "EU\n") { ok ->
              if (ok) {
                value = "EU"
                snack(savedMsg)
              } else {
                snack(saveFailedMsg)
              }
            }
          }
          RegionButton(label = stringResource(R.string.region_asia), code = "AS", selected = value == "AS") {
            actions.saveText(apiPath, "AS\n") { ok ->
              if (ok) {
                value = "AS"
                snack(savedMsg)
              } else {
                snack(saveFailedMsg)
              }
            }
          }
          RegionButton(label = stringResource(R.string.region_america), code = "AM", selected = value == "AM") {
            actions.saveText(apiPath, "AM\n") { ok ->
              if (ok) {
                value = "AM"
                snack(savedMsg)
              } else {
                snack(saveFailedMsg)
              }
            }
          }
        }
      }
      if (!loaded) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      } else {
        Text(
          stringResource(R.string.current_value_fmt, value),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }
    }
  }
}

@Composable
private fun RegionButton(
  label: String,
  code: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  if (selected) {
    Button(onClick = onClick, modifier = modifier) { Text(label) }
  } else {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
  }
}