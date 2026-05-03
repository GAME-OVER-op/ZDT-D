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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

private data class Tun2SocksProfileInfo(
  val name: String,
  val enabled: Boolean,
)

private data class Tun2SocksSettingUi(
  val tun: String = "tun9",
  val proxy: String = "socks5://127.0.0.1:1080",
  val loglevel: String = "info",
)

private val tun2SocksProfileNameRegex = Regex("^[A-Za-z0-9_-]{1,10}$")
private val tun2SocksTunRegex = Regex("^[A-Za-z0-9_.-]{1,15}$")
private val tun2SocksForbiddenTunNames = setOf("wlan0", "rmnet_data0", "eth0", "lo", "dummy0")
private val tun2SocksLogLevels = setOf("debug", "info", "warn", "error", "silent")
private const val TUN2SOCKS_AUTOSAVE_DELAY_MS = 1500L

private suspend fun awaitLoadJsonTun2Socks(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextTun2Socks(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonTun2Socks(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private fun tun2SocksProfilePath(profile: String): String =
  "/api/programs/tun2socks/profiles/${URLEncoder.encode(profile, "UTF-8")}"

private fun tun2SocksDataObject(obj: JSONObject?): JSONObject? =
  obj?.optJSONObject("data") ?: obj?.optJSONObject("setting") ?: obj

private fun parseTun2SocksSetting(obj: JSONObject?): Tun2SocksSettingUi {
  val data = tun2SocksDataObject(obj)
  val rawLevel = data?.optString("loglevel", "info")?.trim()?.lowercase(Locale.ROOT).orEmpty()
  return Tun2SocksSettingUi(
    tun = data?.optString("tun", "tun9")?.trim().orEmpty().ifBlank { "tun9" },
    proxy = data?.optString("proxy", "socks5://127.0.0.1:1080")?.trim().orEmpty().ifBlank { "socks5://127.0.0.1:1080" },
    loglevel = rawLevel.takeIf { it in tun2SocksLogLevels } ?: "info",
  )
}

private fun buildTun2SocksSettingJson(setting: Tun2SocksSettingUi, hidden: JSONObject? = null): JSONObject {
  fun hiddenOrNull(key: String): Any = when {
    hidden != null && hidden.has(key) -> hidden.opt(key) ?: JSONObject.NULL
    else -> JSONObject.NULL
  }
  return JSONObject()
    .put("tun", setting.tun.trim())
    .put("proxy", setting.proxy.trim())
    .put("loglevel", setting.loglevel.trim().lowercase(Locale.ROOT))
    .put("udp_timeout", hiddenOrNull("udp_timeout"))
    .put("fwmark", hiddenOrNull("fwmark"))
    .put("restapi", hiddenOrNull("restapi"))
}

private fun extractTun2SocksHidden(obj: JSONObject?): JSONObject {
  val data = tun2SocksDataObject(obj)
  val out = JSONObject()
  listOf("udp_timeout", "fwmark", "restapi").forEach { key ->
    if (data != null && data.has(key)) out.put(key, data.opt(key) ?: JSONObject.NULL)
    else out.put(key, JSONObject.NULL)
  }
  return out
}

private fun isValidTun2SocksTun(value: String): Boolean {
  val v = value.trim()
  return tun2SocksTunRegex.matches(v) && v.lowercase(Locale.ROOT) !in tun2SocksForbiddenTunNames
}

private fun isValidTun2SocksProxy(raw: String): Boolean {
  val value = raw.trim()
  if (value.isBlank() || value.any { it.isWhitespace() }) return false
  val uri = runCatching { URI(value) }.getOrNull() ?: return false
  val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
  if (scheme !in setOf("socks5", "http")) return false
  val host = uri.host?.trim().orEmpty()
  if (host.isEmpty()) return false
  val port = uri.port
  if (port !in 1..65535) return false
  return true
}

private fun isValidTun2SocksLogLevel(value: String): Boolean =
  value.trim().lowercase(Locale.ROOT) in tun2SocksLogLevels

private fun tun2SocksProfileIndex(name: String): Int {
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) {
    return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  }
  return Int.MIN_VALUE
}

@Composable
fun Tun2SocksProgramScreen(
  programs: List<ApiModels.Program>,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "tun2socks" }
  var showCreate by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val profiles = remember(program?.profiles) {
    program?.profiles.orEmpty()
      .map { Tun2SocksProfileInfo(name = it.name, enabled = it.enabled) }
      .sortedWith(compareByDescending<Tun2SocksProfileInfo> { tun2SocksProfileIndex(it.name) }.thenBy { it.name.lowercase(Locale.ROOT) })
  }

  if (showCreate) {
    Tun2SocksCreateProfileDialog(
      existing = program?.profiles.orEmpty().map { it.name },
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.createNamedProfile("tun2socks", name) { created ->
          if (created != null) {
            scope.launch {
              val tun = nextFreeVpnTunName(actions, programs, excludeProgramId = "tun2socks", excludeProfile = created)
              val ok = awaitSaveJsonVpnTunGuard(
                actions,
                "${vpnProfileApiPath("tun2socks", created)}/setting",
                defaultTun2SocksSettingJson(tun),
              )
              showSnack(
                if (ok) context.getString(R.string.tun2socks_profile_created, created)
                else context.getString(R.string.save_failed)
              )
              actions.refreshPrograms()
              onOpenProfile("tun2socks", created)
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
        Text("tun2socks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        val enabledCount = program?.profiles.orEmpty().count { it.enabled }
        Text(
          stringResource(R.string.tun2socks_status_profiles, enabledCount, program?.profiles.orEmpty().size),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )
        Text(
          stringResource(R.string.tun2socks_program_hint),
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
          Text(stringResource(R.string.tun2socks_no_profiles_title), fontWeight = FontWeight.SemiBold)
          Text(stringResource(R.string.tun2socks_no_profiles_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
      }
    }

    profiles.forEach { info ->
      Tun2SocksProfileCard(
        info = info,
        onOpen = { onOpenProfile("tun2socks", info.name) },
        onToggle = { checked ->
          actions.setProfileEnabled("tun2socks", info.name, checked) { ok ->
            showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
            if (ok) actions.refreshPrograms()
          }
        },
        onDelete = {
          actions.deleteProfile("tun2socks", info.name) { ok ->
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
private fun Tun2SocksProfileCard(
  info: Tun2SocksProfileInfo,
  onOpen: () -> Unit,
  onToggle: (Boolean) -> Unit,
  onDelete: () -> Unit,
) {
  ProfileStatusCard(
    programId = "tun2socks",
    profileName = info.name,
    checked = info.enabled,
    onOpen = onOpen,
    onCheckedChange = onToggle,
    onDelete = onDelete,
  )
}

@Composable
private fun Tun2SocksCreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val existingSet = remember(existing) { existing.toSet() }
  val invalidText = stringResource(R.string.tun2socks_profile_name_invalid)
  val existsText = stringResource(R.string.profile_already_exists)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.tun2socks_create_profile_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.tun2socks_profile_name_rules),
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
            !tun2SocksProfileNameRegex.matches(n) -> error = invalidText
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
fun Tun2SocksProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "tun2socks" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val basePath = remember(profile) { tun2SocksProfilePath(profile) }

  var loading by remember(profile) { mutableStateOf(true) }
  var tunText by remember(profile) { mutableStateOf("tun9") }
  var proxyText by remember(profile) { mutableStateOf("socks5://127.0.0.1:1080") }
  var loglevelText by remember(profile) { mutableStateOf("info") }
  var syncedSetting by remember(profile) { mutableStateOf(Tun2SocksSettingUi()) }
  var hiddenSetting by remember(profile) { mutableStateOf(JSONObject().put("udp_timeout", JSONObject.NULL).put("fwmark", JSONObject.NULL).put("restapi", JSONObject.NULL)) }
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
      val usedTuns = loadUsedVpnTunNames(actions, programs, excludeProgramId = "tun2socks", excludeProfile = profile)
      val raw = awaitLoadJsonTun2Socks(actions, "$basePath/setting")
      val loaded = parseTun2SocksSetting(raw)
      val setting = if (isVpnTunNameUsed(loaded.tun, usedTuns)) loaded.copy(tun = nextFreeVpnTunName(usedTuns)) else loaded
      val apps = parsePkgList(awaitLoadTextTun2Socks(actions, "$basePath/apps/user").orEmpty()).size

      usedVpnTuns = usedTuns
      syncedSetting = loaded
      tunText = setting.tun
      proxyText = setting.proxy
      loglevelText = setting.loglevel
      hiddenSetting = extractTun2SocksHidden(raw)
      settingInitialized = true

      appCount = apps
      loading = false
    }
  }

  LaunchedEffect(profile) { reload() }

  val tunNameConflict = remember(tunText, usedVpnTuns) { isVpnTunNameUsed(tunText, usedVpnTuns) }
  val tunValid = remember(tunText, tunNameConflict) { isValidTun2SocksTun(tunText) && !tunNameConflict }
  val proxyValid = remember(proxyText) { isValidTun2SocksProxy(proxyText) }
  val loglevelValid = remember(loglevelText) { isValidTun2SocksLogLevel(loglevelText) }

  LaunchedEffect(tunText, proxyText, loglevelText, settingInitialized) {
    if (!settingInitialized || loading) return@LaunchedEffect
    delay(TUN2SOCKS_AUTOSAVE_DELAY_MS)
    if (!isValidTun2SocksTun(tunText) || isVpnTunNameUsed(tunText, usedVpnTuns)) return@LaunchedEffect
    if (!isValidTun2SocksProxy(proxyText)) return@LaunchedEffect
    if (!isValidTun2SocksLogLevel(loglevelText)) return@LaunchedEffect
    val current = Tun2SocksSettingUi(
      tun = tunText.trim(),
      proxy = proxyText.trim(),
      loglevel = loglevelText.trim().lowercase(Locale.ROOT),
    )
    if (current == syncedSetting) return@LaunchedEffect
    val ok = awaitSaveJsonTun2Socks(actions, "$basePath/setting", buildTun2SocksSettingJson(current, hiddenSetting))
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
    Text("tun2socks / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Text(
      stringResource(R.string.tun2socks_program_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = stringResource(R.string.enabled_card_profile_title),
      checked = prof?.enabled ?: false,
      onCheckedChange = { checked ->
        actions.setProfileEnabled("tun2socks", profile, checked) { ok ->
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
        Text(stringResource(R.string.tun2socks_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.tun2socks_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = profile,
          onValueChange = {},
          modifier = Modifier.fillMaxWidth(),
          readOnly = true,
          label = { Text(stringResource(R.string.profile_name_label)) },
          supportingText = { Text(stringResource(R.string.tun2socks_profile_name_readonly_hint)) },
        )
        OutlinedTextField(
          value = tunText,
          onValueChange = { tunText = it.trim().take(15) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.tun2socks_tun_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = tunText.isNotBlank() && !tunValid,
          supportingText = { Text(stringResource(R.string.tun2socks_tun_hint)) },
        )
        if (tunText.isNotBlank() && !isValidTun2SocksTun(tunText)) {
          Text(stringResource(R.string.tun2socks_tun_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (tunNameConflict) {
          Text(stringResource(R.string.vpn_tun_name_in_use), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = proxyText,
          onValueChange = { proxyText = it.trim() },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.tun2socks_proxy_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = proxyText.isNotBlank() && !proxyValid,
          supportingText = { Text(stringResource(R.string.tun2socks_proxy_hint)) },
        )
        if (proxyText.isBlank() || !proxyValid) {
          Text(stringResource(R.string.tun2socks_proxy_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = loglevelText,
          onValueChange = { loglevelText = it.trim().lowercase(Locale.ROOT).take(12) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.tun2socks_loglevel_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = loglevelText.isNotBlank() && !loglevelValid,
          supportingText = { Text(stringResource(R.string.tun2socks_loglevel_hint)) },
        )
        if (loglevelText.isNotBlank() && !loglevelValid) {
          Text(stringResource(R.string.tun2socks_loglevel_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    }

    AppListPickerCard(
      title = stringResource(R.string.tun2socks_apps_title),
      desc = stringResource(R.string.tun2socks_apps_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
      saveFailedMessage = stringResource(R.string.tun2socks_app_conflict_error),
      onSavedSelection = { appCount = it.size },
    )

    if ((prof?.enabled == true) && appCount == 0) {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
      ) {
        Text(
          stringResource(R.string.tun2socks_enabled_empty_apps_warning),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
    Spacer(Modifier.height(80.dp))
  }
}
