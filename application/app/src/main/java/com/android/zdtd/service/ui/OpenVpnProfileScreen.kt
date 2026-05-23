package com.android.zdtd.service.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

private data class OpenVpnProfileInfo(
  val name: String,
  val enabled: Boolean,
)

private data class OpenVpnSettingUi(
  val tun: String = "tun1",
  val dns: List<String> = listOf("94.140.14.14", "94.140.15.15"),
)

private val openVpnProfileNameRegex = Regex("^[A-Za-z0-9_-]{1,10}$")
private val openVpnTunRegex = Regex("^[A-Za-z0-9_.-]{1,15}$")
private val forbiddenTunNames = setOf("wlan0", "rmnet_data0", "eth0", "lo", "dummy0")
private const val OPENVPN_AUTOSAVE_DELAY_MS = 1500L

private suspend fun awaitLoadJsonOpenVpn(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextOpenVpn(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonOpenVpn(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private suspend fun awaitSaveTextOpenVpn(actions: ZdtdActions, path: String, content: String): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveText(path, content) { cont.resume(it) } }

private suspend fun awaitUploadOpenVpnConfig(actions: ZdtdActions, profile: String, filename: String, file: File): Boolean =
  suspendCancellableCoroutine { cont -> actions.uploadOpenVpnConfig(profile, filename, file) { cont.resume(it) } }

private fun openVpnProfilePath(profile: String): String =
  "/api/programs/openvpn/profiles/${URLEncoder.encode(profile, "UTF-8")}"

private fun parseOpenVpnSetting(obj: JSONObject?): OpenVpnSettingUi {
  val data = obj?.optJSONObject("data") ?: obj?.optJSONObject("setting") ?: obj
  val dnsArr = data?.optJSONArray("dns") ?: JSONArray()
  val dns = buildList {
    for (i in 0 until dnsArr.length()) {
      val value = dnsArr.optString(i, "").trim()
      if (value.isNotEmpty()) add(value)
    }
  }
  return OpenVpnSettingUi(
    tun = data?.optString("tun", "tun1")?.trim().orEmpty().ifBlank { "tun1" },
    dns = dns.takeIf { it.isNotEmpty() } ?: listOf("94.140.14.14", "94.140.15.15"),
  )
}

private fun buildOpenVpnSettingJson(tun: String, dns: List<String>): JSONObject {
  val arr = JSONArray()
  dns.forEach { arr.put(it) }
  return JSONObject()
    .put("tun", tun.trim())
    .put("dns", arr)
}

private fun parseOpenVpnDnsInput(raw: String): List<String>? {
  val parts = raw
    .split(Regex("[\\s,]+"))
    .map { it.trim() }
    .filter { it.isNotEmpty() }
  if (parts.isEmpty() || parts.size > 8) return null
  if (parts.distinct().size != parts.size) return null
  return parts.takeIf { it.all(::isValidIpv4Literal) }
}

private fun isValidIpv4Literal(value: String): Boolean {
  if (value.contains(":") || value.contains("/") || value.contains("://")) return false
  val parts = value.split('.')
  if (parts.size != 4) return false
  return parts.all { part ->
    part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
  }
}

private fun isValidOpenVpnTun(value: String): Boolean {
  val v = value.trim()
  return openVpnTunRegex.matches(v) && v.lowercase(Locale.ROOT) !in forbiddenTunNames
}

private fun openVpnConfigWarnings(config: String): List<String> {
  val lines = config.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }.toList()
  fun hasDirective(name: String): Boolean = lines.any { it == name || it.startsWith("$name ") || it.startsWith("$name\t") }
  val missing = buildList {
    if (!hasDirective("client")) add("client")
    if (!hasDirective("dev")) add("dev")
    if (!hasDirective("remote")) add("remote")
  }
  return if (missing.isEmpty()) emptyList() else listOf("client.ovpn: ${missing.joinToString(", ")} not found")
}

private fun openVpnUriDisplayName(context: Context, uri: Uri): String? = runCatching {
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
    if (c.moveToFirst()) c.getString(0) else null
  }
}.getOrNull()

private fun readOpenVpnTextFromUri(context: Context, uri: Uri): String? = runCatching {
  context.contentResolver.openInputStream(uri)?.use { input ->
    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
  }
}.getOrNull()

private fun openVpnProfileIndex(name: String): Int {
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) {
    return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  }
  return Int.MIN_VALUE
}

private fun copyOpenVpnUriToTempFile(context: Context, uri: Uri, displayName: String): File? {
  val suffix = displayName.substringAfterLast('.', "ovpn").let { ".${it.take(16).ifBlank { "ovpn" }}" }
  val tmp = runCatching { File.createTempFile("openvpn_config_", suffix, context.cacheDir) }.getOrNull() ?: return null
  return try {
    context.contentResolver.openInputStream(uri)?.use { input ->
      tmp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
    } ?: return null
    tmp
  } catch (_: Throwable) {
    runCatching { tmp.delete() }
    null
  }
}

@Composable
fun OpenVpnProgramScreen(
  programs: List<ApiModels.Program>,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "openvpn" }
  val effectiveTopContentPadding = topContentPadding + 6.dp
  var showCreate by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val details = remember(program?.profiles) {
    program?.profiles.orEmpty()
      .map { OpenVpnProfileInfo(name = it.name, enabled = it.enabled) }
      .sortedWith(compareByDescending<OpenVpnProfileInfo> { openVpnProfileIndex(it.name) }.thenByDescending { it.name.lowercase(Locale.ROOT) })
  }

  if (showCreate) {
    OpenVpnCreateProfileDialog(
      existing = program?.profiles.orEmpty().map { it.name },
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.createNamedProfile("openvpn", name) { created ->
          if (created != null) {
            scope.launch {
              val tun = nextFreeVpnTunName(actions, programs, excludeProgramId = "openvpn", excludeProfile = created)
              val ok = awaitSaveJsonVpnTunGuard(
                actions,
                "${vpnProfileApiPath("openvpn", created)}/setting",
                defaultOpenVpnSettingJson(tun),
              )
              showSnack(
                if (ok) context.getString(R.string.openvpn_profile_created, created)
                else context.getString(R.string.save_failed)
              )
              actions.refreshPrograms()
              onOpenProfile("openvpn", created)
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
      .padding(top = effectiveTopContentPadding)
      .padding(horizontal = if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    ProgramDescriptionHeader(
      programId = "openvpn",
      description = stringResource(R.string.openvpn_program_hint),
      isProfiles = true,
    )

    CreateProfileCard(onAdd = { showCreate = true })

    ProfilesSectionTitle()

    if (details.isEmpty()) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.openvpn_no_profiles_title), fontWeight = FontWeight.SemiBold)
          Text(stringResource(R.string.openvpn_no_profiles_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
      }
    }

    details.forEach { info ->
      OpenVpnProfileCard(
        info = info,
        onOpen = { onOpenProfile("openvpn", info.name) },
        onToggle = { checked ->
          actions.setProfileEnabled("openvpn", info.name, checked) { ok ->
            showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
            if (ok) actions.refreshPrograms()
          }
        },
        onDelete = {
          actions.deleteProfile("openvpn", info.name) { ok ->
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
private fun OpenVpnProfileCard(
  info: OpenVpnProfileInfo,
  onOpen: () -> Unit,
  onToggle: (Boolean) -> Unit,
  onDelete: () -> Unit,
) {
  ProfileStatusCard(
    programId = "openvpn",
    profileName = info.name,
    checked = info.enabled,
    onOpen = onOpen,
    onCheckedChange = onToggle,
    onDelete = onDelete,
  )
}

@Composable
private fun OpenVpnCreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  StyledCreateProfileDialog(
    existing = existing,
    onDismiss = onDismiss,
    onCreate = onCreate,
    titleRes = R.string.openvpn_create_profile_title,
    rulesRes = R.string.openvpn_profile_name_rules,
    invalidNameRes = R.string.openvpn_profile_name_invalid,
    validator = { name -> openVpnProfileNameRegex.matches(name) },
  )
}

@Composable
fun OpenVpnProfileScreen(
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
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "openvpn" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val basePath = remember(profile) { openVpnProfilePath(profile) }

  var loading by remember(profile) { mutableStateOf(true) }
  var uploading by remember(profile) { mutableStateOf(false) }
  var tunText by remember(profile) { mutableStateOf("tun1") }
  var dnsText by remember(profile) { mutableStateOf("94.140.14.14 94.140.15.15") }
  var configText by remember(profile) { mutableStateOf("") }
  var syncedSetting by remember(profile) { mutableStateOf(OpenVpnSettingUi()) }
  var syncedConfig by remember(profile) { mutableStateOf("") }
  var settingInitialized by remember(profile) { mutableStateOf(false) }
  var configInitialized by remember(profile) { mutableStateOf(false) }
  var appCount by remember(profile) { mutableStateOf(0) }
  var usedVpnTuns by remember(profile) { mutableStateOf(emptySet<String>()) }
  var showConfigEditor by remember(profile) { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun reload() {
    loading = true
    settingInitialized = false
    configInitialized = false
    scope.launch {
      val usedTuns = loadUsedVpnTunNames(actions, programs, excludeProgramId = "openvpn", excludeProfile = profile)
      val rawSetting = awaitLoadJsonOpenVpn(actions, "$basePath/setting")
      val loaded = parseOpenVpnSetting(rawSetting)
      val setting = if (isVpnTunNameUsed(loaded.tun, usedTuns)) loaded.copy(tun = nextFreeVpnTunName(usedTuns)) else loaded
      val loadedConfig = awaitLoadTextOpenVpn(actions, "$basePath/config").orEmpty()
      val apps = parsePkgList(awaitLoadTextOpenVpn(actions, "$basePath/apps/user").orEmpty()).size

      usedVpnTuns = usedTuns
      syncedSetting = loaded
      tunText = setting.tun
      dnsText = setting.dns.joinToString(" ")
      settingInitialized = true

      syncedConfig = loadedConfig
      configText = loadedConfig
      configInitialized = true

      appCount = apps
      loading = false
    }
  }

  LaunchedEffect(profile) { reload() }

  val fileLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      val fileName = openVpnUriDisplayName(context, uri) ?: "client.ovpn"
      val localText = readOpenVpnTextFromUri(context, uri)
      val tmp = copyOpenVpnUriToTempFile(context, uri, fileName)
      if (localText == null || tmp == null) {
        showSnack(context.getString(R.string.common_upload_failed))
        return@rememberLauncherForActivityResult
      }
      configText = localText
      uploading = true
      scope.launch {
        val ok = try {
          awaitUploadOpenVpnConfig(actions, profile, fileName, tmp)
        } finally {
          runCatching { tmp.delete() }
        }
        uploading = false
        if (ok) {
          syncedConfig = localText
          showSnack(context.getString(R.string.saved_apply_after_restart))
        } else {
          showSnack(context.getString(R.string.common_upload_failed))
        }
      }
    },
  )

  val dnsParsed = remember(dnsText) { parseOpenVpnDnsInput(dnsText) }
  val tunNameConflict = remember(tunText, usedVpnTuns) { isVpnTunNameUsed(tunText, usedVpnTuns) }
  val tunValid = remember(tunText, tunNameConflict) { isValidOpenVpnTun(tunText) && !tunNameConflict }
  val configBlank = configText.trim().isBlank()
  val configWarnings = remember(configText) { if (configBlank) emptyList() else openVpnConfigWarnings(configText) }
  val configLineCount = remember(configText) { configText.lines().count { it.isNotBlank() } }

  LaunchedEffect(tunText, dnsText, settingInitialized) {
    if (!settingInitialized || loading) return@LaunchedEffect
    delay(OPENVPN_AUTOSAVE_DELAY_MS)
    val dns = parseOpenVpnDnsInput(dnsText) ?: return@LaunchedEffect
    if (!isValidOpenVpnTun(tunText) || isVpnTunNameUsed(tunText, usedVpnTuns)) return@LaunchedEffect
    val current = OpenVpnSettingUi(tun = tunText.trim(), dns = dns)
    if (current == syncedSetting) return@LaunchedEffect
    val ok = awaitSaveJsonOpenVpn(actions, "$basePath/setting", buildOpenVpnSettingJson(current.tun, current.dns))
    if (ok) {
      syncedSetting = current
    } else {
      showSnack(context.getString(R.string.save_failed))
    }
  }

  LaunchedEffect(configText, configInitialized) {
    if (!configInitialized || loading || uploading) return@LaunchedEffect
    delay(OPENVPN_AUTOSAVE_DELAY_MS)
    if (configText == syncedConfig) return@LaunchedEffect
    if (configText.trim().isBlank()) return@LaunchedEffect
    val ok = awaitSaveTextOpenVpn(actions, "$basePath/config", configText)
    if (ok) {
      syncedConfig = configText
    } else {
      showSnack(context.getString(R.string.save_failed))
    }
  }

  if (showConfigEditor) {
    OpenVpnConfigEditorDialog(
      profile = profile,
      text = configText,
      loading = loading,
      saving = uploading,
      warnings = configWarnings,
      isEmpty = configBlank,
      onTextChange = { configText = it },
      onUpload = { fileLauncher.launch(arrayOf("application/x-openvpn-profile", "text/*", "*/*")) },
      onDismiss = { showConfigEditor = false },
    )
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(scroll)
      .padding(horizontal = if (compact) 12.dp else 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    OpenVpnProfileEnabledCard(
      checked = prof?.enabled ?: false,
      onCheckedChange = { checked ->
        actions.setProfileEnabled("openvpn", profile, checked) { ok ->
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

    OpenVpnSectionCard(
      title = stringResource(R.string.openvpn_settings_title),
      desc = stringResource(R.string.openvpn_autosave_hint),
      accent = Color(0xFF38BDF8),
      icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(21.dp)) },
    ) {
        OutlinedTextField(
          value = profile,
          onValueChange = {},
          modifier = Modifier.fillMaxWidth(),
          readOnly = true,
          label = { Text(stringResource(R.string.profile_name_label)) },
          supportingText = { Text(stringResource(R.string.openvpn_profile_name_readonly_hint)) },
        )
        OutlinedTextField(
          value = tunText,
          onValueChange = { tunText = it.trim().take(15) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.openvpn_tun_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = tunText.isNotBlank() && !tunValid,
          supportingText = { Text(stringResource(R.string.openvpn_tun_hint)) },
        )
        if (tunText.isNotBlank() && !isValidOpenVpnTun(tunText)) {
          Text(stringResource(R.string.openvpn_tun_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (tunNameConflict) {
          Text(stringResource(R.string.vpn_tun_name_in_use), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = dnsText,
          onValueChange = { dnsText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.openvpn_dns_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = false,
          minLines = 1,
          maxLines = 3,
          isError = dnsText.isNotBlank() && dnsParsed == null,
          supportingText = { Text(stringResource(R.string.openvpn_dns_hint)) },
        )
        if (dnsText.isNotBlank() && dnsParsed == null) {
          Text(stringResource(R.string.openvpn_dns_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    OpenVpnConfigSummaryCard(
      hasConfig = !configBlank,
      lineCount = configLineCount,
      warnings = configWarnings,
      saving = uploading,
      onEdit = { showConfigEditor = true },
      onUpload = { fileLauncher.launch(arrayOf("application/x-openvpn-profile", "text/*", "*/*")) },
    )

    AppListPickerCard(
      title = stringResource(R.string.openvpn_apps_title),
      desc = stringResource(R.string.openvpn_apps_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
      programs = programs,
      saveFailedMessage = stringResource(R.string.openvpn_app_conflict_error),
      onSavedSelection = { appCount = it.size },
    )

    if ((prof?.enabled == true) && appCount == 0) {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
      ) {
        Text(
          stringResource(R.string.openvpn_enabled_empty_apps_warning),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    Spacer(Modifier.height(effectiveBottomContentPadding))
  }
}

@Composable
private fun OpenVpnSectionCard(
  title: String,
  desc: String? = null,
  accent: Color = Color(0xFF38BDF8),
  icon: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  content: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val compact = rememberIsCompactWidth()
  val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
  Surface(
    modifier = Modifier.fillMaxWidth(),
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
private fun OpenVpnProfileEnabledCard(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val accent = if (checked) Color(0xFF22C55E) else Color(0xFFEF4444)
  OpenVpnSectionCard(
    title = stringResource(R.string.enabled_card_profile_title),
    desc = stringResource(R.string.enabled_card_apply_hint),
    accent = accent,
    icon = { Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(22.dp)) },
    trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
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
private fun OpenVpnConfigSummaryCard(
  hasConfig: Boolean,
  lineCount: Int,
  warnings: List<String>,
  saving: Boolean,
  onEdit: () -> Unit,
  onUpload: () -> Unit,
) {
  OpenVpnSectionCard(
    title = stringResource(R.string.openvpn_config_title),
    desc = stringResource(R.string.openvpn_config_summary_desc),
    accent = Color(0xFFA78BFA),
    icon = { Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(21.dp)) },
    trailing = {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(enabled = !saving, onClick = onUpload) {
          Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.common_upload_cd))
        }
        FilledTonalIconButton(onClick = onEdit) {
          Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
        }
      }
    },
  ) {
      Text(
        if (hasConfig) stringResource(R.string.openvpn_config_summary_present_fmt, lineCount) else stringResource(R.string.openvpn_config_missing_warning),
        style = MaterialTheme.typography.bodySmall,
        color = if (hasConfig) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else MaterialTheme.colorScheme.error,
      )
      warnings.forEach { warning ->
        Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
      }
  }
}

@Composable
private fun OpenVpnConfigEditorDialog(
  profile: String,
  text: String,
  loading: Boolean,
  saving: Boolean,
  warnings: List<String>,
  isEmpty: Boolean,
  onTextChange: (String) -> Unit,
  onUpload: () -> Unit,
  onDismiss: () -> Unit,
) {
  val compactWidth = rememberIsCompactWidth()
  val narrowWidth = rememberIsNarrowWidth()
  val shortHeight = rememberIsShortHeight()
  val useCompactHeader = shortHeight || narrowWidth

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
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
                stringResource(R.string.openvpn_config_editor_title_fmt, profile),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                stringResource(R.string.openvpn_config_autosave_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
              IconButton(onClick = onUpload, modifier = Modifier.size(40.dp), enabled = !saving) {
                Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.common_upload_cd))
              }
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
              IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
              }
            }
          }
        } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.openvpn_config_editor_title_fmt, profile), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              Text(
                stringResource(R.string.openvpn_config_autosave_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = onUpload, enabled = !saving) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.common_upload_cd))
              }
              FilledTonalButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.common_close))
              }
            }
          }
        }

        StableLinearProgressIndicator(visible = loading)

        Text(
          stringResource(R.string.openvpn_config_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        if (isEmpty) {
          Text(stringResource(R.string.openvpn_config_empty), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        warnings.forEach { warning ->
          Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = text,
          onValueChange = onTextChange,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = true),
          enabled = !loading,
          label = { Text("client.ovpn") },
          singleLine = false,
          minLines = if (shortHeight) 10 else 14,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
          isError = isEmpty,
        )
      }
    }
  }
}
