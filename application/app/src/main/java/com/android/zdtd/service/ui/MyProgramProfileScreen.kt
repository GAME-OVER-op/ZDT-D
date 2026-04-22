package com.android.zdtd.service.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import java.io.File
import kotlin.coroutines.resume

private data class MyProgramSettingUi(
  val appsMode: Boolean = true,
  val routeMode: String = "t2s",
  val protoMode: String = "tcp",
  val transparentPort: Int? = null,
  val t2sPort: Int? = null,
  val t2sWebPort: Int? = null,
  val socksUser: String = "",
  val socksPass: String = "",
)

private data class MyProgramBinFileUi(
  val name: String,
  val size: Long,
)

private suspend fun awaitLoadJsonMyProgram(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextMyProgram(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonMyProgram(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private suspend fun awaitSaveTextMyProgram(actions: ZdtdActions, path: String, content: String): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveText(path, content) { cont.resume(it) } }

private suspend fun awaitUploadMyProgram(actions: ZdtdActions, profile: String, filename: String, file: File): Boolean =
  suspendCancellableCoroutine { cont -> actions.uploadMyProgramBin(profile, filename, file) { cont.resume(it) } }

private suspend fun awaitDeleteMyProgram(actions: ZdtdActions, profile: String, filename: String): Boolean =
  suspendCancellableCoroutine { cont -> actions.deleteMyProgramBin(profile, filename) { cont.resume(it) } }

private suspend fun awaitApplyMyProgram(actions: ZdtdActions, profile: String): Boolean =
  suspendCancellableCoroutine { cont -> actions.applyMyProgramProfile(profile) { cont.resume(it) } }


private fun copyUriToTempFile(context: Context, uri: Uri, displayName: String): File? {
  val safeName = displayName.ifBlank { "file.bin" }
  val suffix = safeName.substringAfterLast('.', "bin").let { if (it.isBlank()) ".bin" else ".${it.take(16)}" }
  val tmp = kotlin.runCatching { File.createTempFile("myprogram_upload_", suffix, context.cacheDir) }.getOrNull() ?: return null
  return try {
    context.contentResolver.openInputStream(uri)?.use { input ->
      tmp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
    } ?: return null
    tmp
  } catch (_: Throwable) {
    kotlin.runCatching { tmp.delete() }
    null
  }
}

private fun parseMyProgramSettingUi(obj: JSONObject?): MyProgramSettingUi {
  val data = obj?.optJSONObject("data") ?: obj
  val routeMode = (data?.optString("route_mode", "t2s") ?: "t2s").ifBlank { "t2s" }
  val protoMode = (data?.optString("proto_mode", "tcp") ?: "tcp").ifBlank { "tcp" }
  return MyProgramSettingUi(
    appsMode = when (val raw = data?.opt("apps_mode")) {
      is Boolean -> raw
      is Number -> raw.toInt() != 0
      else -> true
    },
    routeMode = if (routeMode == "transparent") "transparent" else "t2s",
    protoMode = if (protoMode == "tcp_udp") "tcp_udp" else "tcp",
    transparentPort = data?.optInt("transparent_port", 0)?.takeIf { it in 1..65535 },
    t2sPort = data?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 },
    t2sWebPort = data?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 },
    socksUser = data?.optString("socks_user", "") ?: "",
    socksPass = data?.optString("socks_pass", "") ?: "",
  )
}

private fun parseMyProgramBinFiles(obj: JSONObject?): List<MyProgramBinFileUi> {
  val arr = obj?.optJSONArray("files") ?: JSONArray()
  val out = ArrayList<MyProgramBinFileUi>(arr.length())
  for (i in 0 until arr.length()) {
    val item = arr.optJSONObject(i) ?: continue
    val name = item.optString("name", "").trim()
    if (name.isEmpty()) continue
    out += MyProgramBinFileUi(name = name, size = item.optLong("size", 0L))
  }
  return out.sortedBy { it.name.lowercase() }
}

private fun normalizePortsContent(raw: String): String? {
  val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
  for (line in lines) {
    val port = line.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
  }
  return if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
}

private fun normalizeCommandForSave(raw: String): String =
  raw.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .joinToString(" ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun formatFileSize(size: Long): String = when {
  size >= 1024L * 1024L -> String.format("%.1f MB", size / 1024.0 / 1024.0)
  size >= 1024L -> String.format("%.1f KB", size / 1024.0)
  else -> "$size B"
}

private fun uriDisplayName(context: Context, uri: Uri): String? = runCatching {
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
    if (c.moveToFirst()) c.getString(0) else null
  }
}.getOrNull()

@Composable
fun MyProgramProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "myprogram" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val encodedProfile = remember(profile) { URLEncoder.encode(profile, "UTF-8") }
  val basePath = remember(encodedProfile) { "/api/programs/myprogram/profiles/$encodedProfile" }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  var loading by remember(profile) { mutableStateOf(true) }
  var settingSaving by remember(profile) { mutableStateOf(false) }
  var commandSaving by remember(profile) { mutableStateOf(false) }
  var t2sPortsSaving by remember(profile) { mutableStateOf(false) }
  var protectPortsSaving by remember(profile) { mutableStateOf(false) }
  var binLoading by remember(profile) { mutableStateOf(false) }
  var applying by remember(profile) { mutableStateOf(false) }

  var syncedSetting by remember(profile) { mutableStateOf(MyProgramSettingUi()) }
  var syncedCommand by remember(profile) { mutableStateOf("") }
  var syncedT2sPorts by remember(profile) { mutableStateOf("") }
  var syncedProtectPorts by remember(profile) { mutableStateOf("") }
  var binFiles by remember(profile) { mutableStateOf<List<MyProgramBinFileUi>>(emptyList()) }

  var appsMode by remember(profile) { mutableStateOf(true) }
  var routeMode by remember(profile) { mutableStateOf("t2s") }
  var protoMode by remember(profile) { mutableStateOf("tcp") }
  var transparentPortText by remember(profile) { mutableStateOf("") }
  var t2sPortText by remember(profile) { mutableStateOf("") }
  var t2sWebPortText by remember(profile) { mutableStateOf("") }
  var socksUserText by remember(profile) { mutableStateOf("") }
  var socksPassText by remember(profile) { mutableStateOf("") }
  var commandText by remember(profile) { mutableStateOf("") }
  var t2sPortsText by remember(profile) { mutableStateOf("") }
  var protectPortsText by remember(profile) { mutableStateOf("") }

  var settingInitialized by remember(profile) { mutableStateOf(false) }
  var commandInitialized by remember(profile) { mutableStateOf(false) }
  var t2sPortsInitialized by remember(profile) { mutableStateOf(false) }
  var protectPortsInitialized by remember(profile) { mutableStateOf(false) }

  fun refreshBin() {
    binLoading = true
    scope.launch {
      val obj = awaitLoadJsonMyProgram(actions, "$basePath/bin")
      if (obj != null) {
        binFiles = parseMyProgramBinFiles(obj)
      } else {
        showSnack(context.getString(R.string.load_failed))
      }
      binLoading = false
    }
  }

  fun loadAll() {
    loading = true
    scope.launch {
      val settingObj = awaitLoadJsonMyProgram(actions, "$basePath/setting")
      val command = awaitLoadTextMyProgram(actions, "$basePath/command")
      val t2sPorts = awaitLoadTextMyProgram(actions, "$basePath/t2s_ports")
      val protectPorts = awaitLoadTextMyProgram(actions, "$basePath/protect_ports")
      val binObj = awaitLoadJsonMyProgram(actions, "$basePath/bin")

      val parsedSetting = parseMyProgramSettingUi(settingObj)
      syncedSetting = parsedSetting
      appsMode = parsedSetting.appsMode
      routeMode = parsedSetting.routeMode
      protoMode = parsedSetting.protoMode
      transparentPortText = parsedSetting.transparentPort?.toString().orEmpty()
      t2sPortText = parsedSetting.t2sPort?.toString().orEmpty()
      t2sWebPortText = parsedSetting.t2sWebPort?.toString().orEmpty()
      socksUserText = parsedSetting.socksUser
      socksPassText = parsedSetting.socksPass
      settingInitialized = true

      syncedCommand = command ?: ""
      commandText = command ?: ""
      commandInitialized = true

      syncedT2sPorts = t2sPorts ?: ""
      t2sPortsText = t2sPorts ?: ""
      t2sPortsInitialized = true

      syncedProtectPorts = protectPorts ?: ""
      protectPortsText = protectPorts ?: ""
      protectPortsInitialized = true

      binFiles = parseMyProgramBinFiles(binObj)
      loading = false
    }
  }

  LaunchedEffect(profile) { loadAll() }

  LaunchedEffect(appsMode, routeMode, protoMode, transparentPortText, t2sPortText, t2sWebPortText, socksUserText, socksPassText, settingInitialized) {
    if (!settingInitialized) return@LaunchedEffect
    delay(700)
    val transparentPort = transparentPortText.trim().toIntOrNull()
    val t2sPort = t2sPortText.trim().toIntOrNull()
    val t2sWebPort = t2sWebPortText.trim().toIntOrNull()
    val current = MyProgramSettingUi(
      appsMode = appsMode,
      routeMode = routeMode,
      protoMode = protoMode,
      transparentPort = transparentPort,
      t2sPort = t2sPort,
      t2sWebPort = t2sWebPort,
      socksUser = socksUserText,
      socksPass = socksPassText,
    )
    if (current == syncedSetting) return@LaunchedEffect
    if (appsMode) {
      if (routeMode == "transparent") {
        if (transparentPort !in 1..65535) return@LaunchedEffect
      } else {
        if (t2sPort !in 1..65535 || t2sWebPort !in 1..65535) return@LaunchedEffect
        if (t2sPort == t2sWebPort) return@LaunchedEffect
      }
    }
    settingSaving = true
    val payload = JSONObject()
      .put("apps_mode", appsMode)
      .put("route_mode", routeMode)
      .put("proto_mode", protoMode)
      .put("transparent_port", transparentPort ?: 0)
      .put("t2s_port", t2sPort ?: 0)
      .put("t2s_web_port", t2sWebPort ?: 0)
      .put("socks_user", socksUserText)
      .put("socks_pass", socksPassText)
    val ok = awaitSaveJsonMyProgram(actions, "$basePath/setting", payload)
    settingSaving = false
    if (ok) syncedSetting = current else showSnack(context.getString(R.string.myprogram_auto_save_failed))
  }

  LaunchedEffect(commandText, commandInitialized) {
    if (!commandInitialized) return@LaunchedEffect
    delay(700)
    val normalized = normalizeCommandForSave(commandText)
    if (normalized == syncedCommand) return@LaunchedEffect
    commandSaving = true
    val ok = awaitSaveTextMyProgram(actions, "$basePath/command", normalized)
    commandSaving = false
    if (ok) syncedCommand = normalized else showSnack(context.getString(R.string.myprogram_auto_save_failed))
  }

  LaunchedEffect(t2sPortsText, t2sPortsInitialized) {
    if (!t2sPortsInitialized) return@LaunchedEffect
    delay(700)
    val normalized = normalizePortsContent(t2sPortsText) ?: return@LaunchedEffect
    if (normalized == syncedT2sPorts) return@LaunchedEffect
    t2sPortsSaving = true
    val ok = awaitSaveTextMyProgram(actions, "$basePath/t2s_ports", normalized)
    t2sPortsSaving = false
    if (ok) syncedT2sPorts = normalized else showSnack(context.getString(R.string.myprogram_auto_save_failed))
  }

  LaunchedEffect(protectPortsText, protectPortsInitialized) {
    if (!protectPortsInitialized) return@LaunchedEffect
    delay(700)
    val normalized = normalizePortsContent(protectPortsText) ?: return@LaunchedEffect
    if (normalized == syncedProtectPorts) return@LaunchedEffect
    protectPortsSaving = true
    val ok = awaitSaveTextMyProgram(actions, "$basePath/protect_ports", normalized)
    protectPortsSaving = false
    if (ok) syncedProtectPorts = normalized else showSnack(context.getString(R.string.myprogram_auto_save_failed))
  }

  val uploadLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      val name = uriDisplayName(context, uri) ?: "file.bin"
      val tmpFile = copyUriToTempFile(context, uri, name)
      if (tmpFile == null) {
        showSnack(context.getString(R.string.common_upload_failed))
        return@rememberLauncherForActivityResult
      }
      scope.launch {
        val ok = try {
          awaitUploadMyProgram(actions, profile, name, tmpFile)
        } finally {
          kotlin.runCatching { tmpFile.delete() }
        }
        showSnack(if (ok) context.getString(R.string.common_uploaded) else context.getString(R.string.common_upload_failed))
        if (ok) refreshBin()
      }
    }
  )

  val t2sPortsError = remember(t2sPortsText) { normalizePortsContent(t2sPortsText) == null }
  val protectPortsError = remember(protectPortsText) { normalizePortsContent(protectPortsText) == null }
  val routeModeT2s = routeMode == "t2s"
  val routeModeTransparent = routeMode == "transparent"
  val settingsPortsSame = appsMode && routeModeT2s && t2sPortText.isNotBlank() && t2sPortText == t2sWebPortText
  val transparentPortError = appsMode && routeModeTransparent && transparentPortText.isNotBlank() && transparentPortText.toIntOrNull()?.let { it !in 1..65535 } != false

  Column(
    Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("${program?.name ?: "myprogram"} / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
      stringResource(R.string.changes_apply_after_restart),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    if (loading) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
          Text(stringResource(R.string.myprogram_loading))
        }
      }
    }

    EnabledCard(
      title = stringResource(R.string.profile_enabled),
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("myprogram", profile, v) },
    )

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.myprogram_apps_mode_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
              stringResource(R.string.myprogram_apps_mode_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
          }
          Spacer(Modifier.width(12.dp))
          Switch(checked = appsMode, onCheckedChange = { appsMode = it })
        }
        if (appsMode) {
          Text(
            stringResource(R.string.myprogram_apps_mode_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
          )
          Spacer(Modifier.height(4.dp))
          Text(stringResource(R.string.myprogram_route_mode_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            stringResource(R.string.myprogram_route_mode_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (routeModeT2s) {
              Button(onClick = { routeMode = "t2s" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myprogram_route_mode_t2s)) }
            } else {
              OutlinedButton(onClick = { routeMode = "t2s" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myprogram_route_mode_t2s)) }
            }
            if (routeModeTransparent) {
              Button(onClick = { routeMode = "transparent" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myprogram_route_mode_transparent)) }
            } else {
              OutlinedButton(onClick = { routeMode = "transparent" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myprogram_route_mode_transparent)) }
            }
          }
          Text(
            if (routeModeT2s) stringResource(R.string.myprogram_route_mode_t2s_hint) else stringResource(R.string.myprogram_route_mode_transparent_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
        } else {
          Text(
            stringResource(R.string.myprogram_launcher_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
        }
        Text(
          if (settingSaving) stringResource(R.string.myprogram_saving) else stringResource(R.string.myprogram_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }

    if (appsMode) {
      AppListPickerCard(
        title = stringResource(R.string.myprogram_apps_title),
        desc = stringResource(R.string.myprogram_apps_desc),
        path = "$basePath/apps/user",
        actions = actions,
        snackHost = snackHost,
      )

      if (routeModeT2s) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
          Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.myprogram_ports_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
              stringResource(R.string.myprogram_ports_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
            OutlinedTextField(
              value = t2sPortText,
              onValueChange = { t2sPortText = it.filter(Char::isDigit).take(5) },
              label = { Text(stringResource(R.string.myprogram_t2s_port_label)) },
              modifier = Modifier.fillMaxWidth(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              isError = t2sPortText.isNotBlank() && t2sPortText.toIntOrNull()?.let { it !in 1..65535 } != false,
            )
            OutlinedTextField(
              value = t2sWebPortText,
              onValueChange = { t2sWebPortText = it.filter(Char::isDigit).take(5) },
              label = { Text(stringResource(R.string.myprogram_t2s_web_port_label)) },
              modifier = Modifier.fillMaxWidth(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              isError = t2sWebPortText.isNotBlank() && t2sWebPortText.toIntOrNull()?.let { it !in 1..65535 } != false,
            )
            if (settingsPortsSame) {
              Text(
                stringResource(R.string.myprogram_ports_must_differ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
              )
            }
            OutlinedTextField(
              value = socksUserText,
              onValueChange = { socksUserText = it },
              label = { Text(stringResource(R.string.myprogram_socks_user_label)) },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
            )
            OutlinedTextField(
              value = socksPassText,
              onValueChange = { socksPassText = it },
              label = { Text(stringResource(R.string.myprogram_socks_pass_label)) },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
            )
            Text(
              if (settingSaving) stringResource(R.string.myprogram_saving) else stringResource(R.string.myprogram_autosave_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
        }
      } else {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
          Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.myprogram_transparent_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
              stringResource(R.string.myprogram_transparent_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
            OutlinedTextField(
              value = transparentPortText,
              onValueChange = { transparentPortText = it.filter(Char::isDigit).take(5) },
              label = { Text(stringResource(R.string.myprogram_transparent_port_label)) },
              modifier = Modifier.fillMaxWidth(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              isError = transparentPortError,
            )
            Text(stringResource(R.string.myprogram_proto_mode_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              stringResource(R.string.myprogram_proto_mode_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              if (protoMode == "tcp") {
                Button(onClick = { protoMode = "tcp" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myprogram_proto_mode_tcp)) }
              } else {
                OutlinedButton(onClick = { protoMode = "tcp" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myprogram_proto_mode_tcp)) }
              }
              if (protoMode == "tcp_udp") {
                Button(onClick = { protoMode = "tcp_udp" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myprogram_proto_mode_tcp_udp)) }
              } else {
                OutlinedButton(onClick = { protoMode = "tcp_udp" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.myprogram_proto_mode_tcp_udp)) }
              }
            }
            Text(
              if (settingSaving) stringResource(R.string.myprogram_saving) else stringResource(R.string.myprogram_autosave_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.myprogram_command_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.myprogram_command_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        OutlinedTextField(
          value = commandText,
          onValueChange = { commandText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.myprogram_command_label)) },
          supportingText = { Text(stringResource(R.string.myprogram_command_hint)) },
          minLines = 3,
          maxLines = 6,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        Text(
          if (commandSaving) stringResource(R.string.myprogram_saving) else stringResource(R.string.myprogram_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }

    if (appsMode && routeModeT2s) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.myprogram_t2s_ports_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            stringResource(R.string.myprogram_t2s_ports_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
          OutlinedTextField(
            value = t2sPortsText,
            onValueChange = { t2sPortsText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.myprogram_t2s_ports_label)) },
            minLines = 3,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = t2sPortsError,
          )
          if (t2sPortsError) {
            Text(
              stringResource(R.string.myprogram_ports_per_line_error),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Text(
            if (t2sPortsSaving) stringResource(R.string.myprogram_saving) else stringResource(R.string.myprogram_autosave_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.myprogram_protect_ports_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.myprogram_protect_ports_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        OutlinedTextField(
          value = protectPortsText,
          onValueChange = { protectPortsText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.myprogram_protect_ports_label)) },
          minLines = 3,
          maxLines = 6,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          isError = protectPortsError,
        )
        if (protectPortsError) {
          Text(
            stringResource(R.string.myprogram_ports_per_line_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Text(
          if (protectPortsSaving) stringResource(R.string.myprogram_saving) else stringResource(R.string.myprogram_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.myprogram_bin_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
              stringResource(R.string.myprogram_bin_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { refreshBin() }) {
              Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh_cd))
            }
            IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
              Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.common_upload_cd))
            }
          }
        }
        if (binLoading) {
          CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        } else if (binFiles.isEmpty()) {
          Text(
            stringResource(R.string.common_no_files),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        } else {
          binFiles.forEach { file ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
              Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(Modifier.weight(1f)) {
                  Text(file.name, fontWeight = FontWeight.SemiBold)
                  Text(
                    formatFileSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                  )
                }
                IconButton(onClick = {
                  scope.launch {
                    val ok = awaitDeleteMyProgram(actions, profile, file.name)
                    showSnack(if (ok) context.getString(R.string.common_deleted) else context.getString(R.string.common_delete_failed))
                    if (ok) refreshBin()
                  }
                }) {
                  Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete_cd))
                }
              }
            }
          }
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.myprogram_apply_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.myprogram_apply_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        Button(
          onClick = {
            scope.launch {
              applying = true
              val ok = awaitApplyMyProgram(actions, profile)
              applying = false
              showSnack(if (ok) context.getString(R.string.common_applied_with_value, profile) else context.getString(R.string.common_apply_failed))
            }
          },
          enabled = !applying,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(if (applying) stringResource(R.string.myprogram_applying) else stringResource(R.string.myprogram_apply_action))
        }
      }
    }

    Spacer(Modifier.height(6.dp))
  }
}
