package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

private data class MyVpnProfileInfo(
  val name: String,
  val enabled: Boolean,
)

private data class MyVpnSettingUi(
  val tun: String = "tun9",
  val dns: List<String> = listOf("1.1.1.1", "1.0.0.1"),
  val cidrMode: String = "auto",
  val cidr: String = "",
)

private val myVpnProfileNameRegex = Regex("^[A-Za-z0-9_-]{1,10}$")
private val myVpnTunRegex = Regex("^[A-Za-z0-9_.-]{1,15}$")
private val myVpnForbiddenTunNames = setOf("wlan0", "rmnet_data0", "eth0", "lo", "dummy0")
private const val MYVPN_AUTOSAVE_DELAY_MS = 1500L

private suspend fun awaitLoadJsonMyVpn(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextMyVpn(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonMyVpn(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private fun myVpnProfilePath(profile: String): String =
  "/api/programs/myvpn/profiles/${URLEncoder.encode(profile, "UTF-8")}"

private fun myVpnDataObject(obj: JSONObject?): JSONObject? =
  obj?.optJSONObject("data") ?: obj?.optJSONObject("setting") ?: obj

private fun parseMyVpnSetting(obj: JSONObject?): MyVpnSettingUi {
  val data = myVpnDataObject(obj)
  val dnsArr = data?.optJSONArray("dns") ?: JSONArray()
  val dns = buildList {
    for (i in 0 until dnsArr.length()) {
      val value = dnsArr.optString(i, "").trim()
      if (value.isNotEmpty()) add(value)
    }
  }
  val rawMode = data?.optString("cidr_mode", "auto")?.trim()?.lowercase(Locale.ROOT).orEmpty()
  val mode = rawMode.takeIf { it == "auto" || it == "manual" } ?: "auto"
  return MyVpnSettingUi(
    tun = data?.optString("tun", "tun9")?.trim().orEmpty().ifBlank { "tun9" },
    dns = dns.takeIf { it.isNotEmpty() } ?: listOf("1.1.1.1", "1.0.0.1"),
    cidrMode = mode,
    cidr = data?.optString("cidr", "")?.trim().orEmpty().takeIf { mode == "manual" } ?: "",
  )
}

private fun buildMyVpnSettingJson(setting: MyVpnSettingUi): JSONObject {
  val arr = JSONArray()
  setting.dns.forEach { arr.put(it) }
  val safeMode = setting.cidrMode.trim().lowercase(Locale.ROOT).takeIf { it == "auto" || it == "manual" } ?: "auto"
  return JSONObject()
    .put("tun", setting.tun.trim())
    .put("dns", arr)
    .put("cidr_mode", safeMode)
    .put("cidr", if (safeMode == "manual") setting.cidr.trim() else "")
}

private fun parseMyVpnDnsInput(raw: String): List<String>? {
  val parts = raw
    .split(Regex("[\\s,]+"))
    .map { it.trim() }
    .filter { it.isNotEmpty() }
  if (parts.isEmpty() || parts.size > 8) return null
  if (parts.distinct().size != parts.size) return null
  return parts.takeIf { it.all(::isValidMyVpnIpv4Literal) }
}

private fun isValidMyVpnIpv4Literal(value: String): Boolean {
  if (value.contains(":") || value.contains("/") || value.contains("://")) return false
  val parts = value.split('.')
  if (parts.size != 4) return false
  return parts.all { part ->
    part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
  }
}

private fun isValidMyVpnTun(value: String): Boolean {
  val v = value.trim()
  return myVpnTunRegex.matches(v) && v.lowercase(Locale.ROOT) !in myVpnForbiddenTunNames
}

private fun isValidMyVpnCidr(value: String): Boolean {
  val v = value.trim()
  if (v.isBlank() || v.contains(":") || v.contains("://")) return false
  val parts = v.split('/')
  if (parts.size != 2) return false
  val ip = parts[0].trim()
  val prefix = parts[1].trim().toIntOrNull() ?: return false
  return isValidMyVpnIpv4Literal(ip) && prefix in 0..32
}

private fun myVpnProfileIndex(name: String): Int {
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) {
    return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  }
  return Int.MIN_VALUE
}

@Composable
fun MyVpnProgramScreen(
  programs: List<ApiModels.Program>,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "myvpn" }
  var showCreate by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val profiles = remember(program?.profiles) {
    program?.profiles.orEmpty()
      .map { MyVpnProfileInfo(name = it.name, enabled = it.enabled) }
      .sortedWith(compareByDescending<MyVpnProfileInfo> { myVpnProfileIndex(it.name) }.thenBy { it.name.lowercase(Locale.ROOT) })
  }

  if (showCreate) {
    MyVpnCreateProfileDialog(
      existing = program?.profiles.orEmpty().map { it.name },
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.createNamedProfile("myvpn", name) { created ->
          if (created != null) {
            scope.launch {
              val tun = nextFreeVpnTunName(actions, programs, excludeProgramId = "myvpn", excludeProfile = created)
              val ok = awaitSaveJsonVpnTunGuard(
                actions,
                "${vpnProfileApiPath("myvpn", created)}/setting",
                defaultMyVpnSettingJson(tun),
              )
              showSnack(
                if (ok) context.getString(R.string.myvpn_profile_created, created)
                else context.getString(R.string.save_failed)
              )
              actions.refreshPrograms()
              onOpenProfile("myvpn", created)
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
        Text("myvpn", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        val enabledCount = program?.profiles.orEmpty().count { it.enabled }
        Text(
          stringResource(R.string.myvpn_status_profiles, enabledCount, program?.profiles.orEmpty().size),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )
        Text(
          stringResource(R.string.myvpn_program_hint),
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
          Text(stringResource(R.string.myvpn_no_profiles_title), fontWeight = FontWeight.SemiBold)
          Text(stringResource(R.string.myvpn_no_profiles_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
      }
    }

    profiles.forEach { info ->
      ProfileStatusCard(
        programId = "myvpn",
        profileName = info.name,
        checked = info.enabled,
        onOpen = { onOpenProfile("myvpn", info.name) },
        onCheckedChange = { checked ->
          actions.setProfileEnabled("myvpn", info.name, checked) { ok ->
            showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
            if (ok) actions.refreshPrograms()
          }
        },
        onDelete = {
          actions.deleteProfile("myvpn", info.name) { ok ->
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
private fun MyVpnCreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val existingSet = remember(existing) { existing.toSet() }
  val invalidText = stringResource(R.string.myvpn_profile_name_invalid)
  val existsText = stringResource(R.string.profile_already_exists)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.myvpn_create_profile_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.myvpn_profile_name_rules),
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
            !myVpnProfileNameRegex.matches(n) -> error = invalidText
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

@Composable
fun MyVpnProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "myvpn" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val basePath = remember(profile) { myVpnProfilePath(profile) }

  var loading by remember(profile) { mutableStateOf(true) }
  var tunText by remember(profile) { mutableStateOf("tun9") }
  var dnsText by remember(profile) { mutableStateOf("1.1.1.1 1.0.0.1") }
  var cidrMode by remember(profile) { mutableStateOf("auto") }
  var cidrText by remember(profile) { mutableStateOf("") }
  var syncedSetting by remember(profile) { mutableStateOf(MyVpnSettingUi()) }
  var settingInitialized by remember(profile) { mutableStateOf(false) }
  var appCount by remember(profile) { mutableStateOf(0) }
  var usedVpnTuns by remember(profile) { mutableStateOf(emptySet<String>()) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun reload() {
    loading = true
    settingInitialized = false
    scope.launch {
      val usedTuns = loadUsedVpnTunNames(actions, programs, excludeProgramId = "myvpn", excludeProfile = profile)
      val loaded = parseMyVpnSetting(awaitLoadJsonMyVpn(actions, "$basePath/setting"))
      val setting = if (isVpnTunNameUsed(loaded.tun, usedTuns)) loaded.copy(tun = nextFreeVpnTunName(usedTuns)) else loaded
      val apps = parsePkgList(awaitLoadTextMyVpn(actions, "$basePath/apps/user").orEmpty()).size

      usedVpnTuns = usedTuns
      syncedSetting = loaded
      tunText = setting.tun
      dnsText = setting.dns.joinToString(" ")
      cidrMode = setting.cidrMode
      cidrText = setting.cidr
      settingInitialized = true

      appCount = apps
      loading = false
    }
  }

  LaunchedEffect(profile) { reload() }

  val tunNameConflict = remember(tunText, usedVpnTuns) { isVpnTunNameUsed(tunText, usedVpnTuns) }
  val tunValid = remember(tunText, tunNameConflict) { isValidMyVpnTun(tunText) && !tunNameConflict }
  val dnsParsed = remember(dnsText) { parseMyVpnDnsInput(dnsText) }
  val cidrValid = remember(cidrMode, cidrText) { cidrMode == "auto" || isValidMyVpnCidr(cidrText) }

  LaunchedEffect(tunText, dnsText, cidrMode, cidrText, settingInitialized) {
    if (!settingInitialized || loading) return@LaunchedEffect
    delay(MYVPN_AUTOSAVE_DELAY_MS)
    if (!isValidMyVpnTun(tunText) || isVpnTunNameUsed(tunText, usedVpnTuns)) return@LaunchedEffect
    val dns = parseMyVpnDnsInput(dnsText) ?: return@LaunchedEffect
    val safeMode = cidrMode.takeIf { it == "auto" || it == "manual" } ?: "auto"
    if (safeMode == "manual" && !isValidMyVpnCidr(cidrText)) return@LaunchedEffect
    val current = MyVpnSettingUi(
      tun = tunText.trim(),
      dns = dns,
      cidrMode = safeMode,
      cidr = if (safeMode == "manual") cidrText.trim() else "",
    )
    if (current == syncedSetting) return@LaunchedEffect
    val ok = awaitSaveJsonMyVpn(actions, "$basePath/setting", buildMyVpnSettingJson(current))
    if (ok) {
      syncedSetting = current
    } else {
      showSnack(context.getString(R.string.save_failed))
    }
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("myvpn / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Text(
      stringResource(R.string.myvpn_program_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = stringResource(R.string.enabled_card_profile_title),
      checked = prof?.enabled ?: false,
      onCheckedChange = { checked ->
        actions.setProfileEnabled("myvpn", profile, checked) { ok ->
          showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
          if (ok) actions.refreshPrograms()
        }
      },
    )

    if (loading) {
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

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.myvpn_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.myvpn_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = profile,
          onValueChange = {},
          modifier = Modifier.fillMaxWidth(),
          readOnly = true,
          label = { Text(stringResource(R.string.profile_name_label)) },
          supportingText = { Text(stringResource(R.string.myvpn_profile_name_readonly_hint)) },
        )
        OutlinedTextField(
          value = tunText,
          onValueChange = { tunText = it.trim().take(15) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.myvpn_tun_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = tunText.isNotBlank() && !tunValid,
          supportingText = { Text(stringResource(R.string.myvpn_tun_hint)) },
        )
        if (tunText.isNotBlank() && !isValidMyVpnTun(tunText)) {
          Text(stringResource(R.string.myvpn_tun_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (tunNameConflict) {
          Text(stringResource(R.string.vpn_tun_name_in_use), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = dnsText,
          onValueChange = { dnsText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.myvpn_dns_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = false,
          minLines = 1,
          isError = dnsText.isNotBlank() && dnsParsed == null,
          supportingText = { Text(stringResource(R.string.myvpn_dns_hint)) },
        )
        if (dnsText.isBlank() || dnsParsed == null) {
          Text(stringResource(R.string.myvpn_dns_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Text(stringResource(R.string.myvpn_cidr_mode_label), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (cidrMode == "auto") {
            Button(onClick = { cidrMode = "auto" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myvpn_cidr_mode_auto)) }
            OutlinedButton(onClick = { cidrMode = "manual" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myvpn_cidr_mode_manual)) }
          } else {
            OutlinedButton(onClick = { cidrMode = "auto" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myvpn_cidr_mode_auto)) }
            Button(onClick = { cidrMode = "manual" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myvpn_cidr_mode_manual)) }
          }
        }
        Text(
          if (cidrMode == "auto") stringResource(R.string.myvpn_cidr_auto_hint) else stringResource(R.string.myvpn_cidr_manual_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        if (cidrMode == "manual") {
          OutlinedTextField(
            value = cidrText,
            onValueChange = { cidrText = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.myvpn_cidr_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            singleLine = true,
            isError = cidrText.isNotBlank() && !cidrValid,
            supportingText = { Text(stringResource(R.string.myvpn_cidr_hint)) },
          )
          if (cidrText.isBlank() || !cidrValid) {
            Text(stringResource(R.string.myvpn_cidr_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }

    AppListPickerCard(
      title = stringResource(R.string.myvpn_apps_title),
      desc = stringResource(R.string.myvpn_apps_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
      saveFailedMessage = stringResource(R.string.myvpn_app_conflict_error),
      onSavedSelection = { appCount = it.size },
    )

    if ((prof?.enabled == true) && appCount == 0) {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
      ) {
        Text(
          stringResource(R.string.myvpn_enabled_empty_apps_warning),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
    Spacer(Modifier.height(80.dp))
  }
}
