package com.android.zdtd.service.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
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
import org.json.JSONObject

private data class TorProgramStateUi(
  val enabled: Boolean = false,
  val active: Boolean = false,
  val apps: String = "",
  val t2sPort: Int? = null,
  val t2sWebPort: Int? = null,
  val torrc: String = "",
)

private fun parseTorProgramStateUi(obj: JSONObject?): TorProgramStateUi {
  val setting = obj?.optJSONObject("setting")
  return TorProgramStateUi(
    enabled = when {
      obj?.has("enabled") == true -> obj.optInt("enabled", if (obj.optBoolean("enabled", false)) 1 else 0) != 0
      else -> false
    },
    active = obj?.optBoolean("active", false) == true,
    apps = obj?.optString("apps", "").orEmpty(),
    t2sPort = setting?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 },
    t2sWebPort = setting?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 },
    torrc = obj?.optString("torrc", "").orEmpty(),
  )
}

private data class TorSocksPort(val host: String, val port: Int)

private fun parseTorSocksPort(torrc: String): TorSocksPort? {
  for (raw in torrc.lines()) {
    val line = raw.trim()
    if (!line.startsWith("SocksPort", ignoreCase = true)) continue
    val value = line.substringAfter(' ', "").trim().ifEmpty { line.substringAfter('=', "").trim() }
    val colon = value.lastIndexOf(':')
    if (colon <= 0 || colon >= value.lastIndex) continue
    val host = value.substring(0, colon).trim()
    val port = value.substring(colon + 1).trim().toIntOrNull() ?: continue
    if (host.isEmpty() || port !in 1..65535) continue
    return TorSocksPort(host = host, port = port)
  }
  return null
}

private fun upsertTorSocksPort(torrc: String, port: Int): String {
  val newLine = "SocksPort 127.0.0.1:$port"
  val lines = torrc.lines().toMutableList()
  for (i in lines.indices) {
    if (lines[i].trim().startsWith("SocksPort", ignoreCase = true)) {
      lines[i] = newLine
      return lines.joinToString("\n").trimEnd() + "\n"
    }
  }
  val prefix = torrc.trimEnd()
  return buildString {
    if (prefix.isNotEmpty()) {
      append(prefix)
      append("\n")
    }
    append(newLine)
    append("\n")
  }
}

private fun normalizeTorBridgeLine(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.isEmpty()) return ""
  return when {
    trimmed.startsWith("Bridge ", ignoreCase = true) -> "Bridge " + trimmed.substringAfter(' ').trim()
    trimmed.startsWith("obfs4 ", ignoreCase = true) -> "Bridge $trimmed"
    else -> trimmed
  }
}

private fun isTorBridgeLine(raw: String): Boolean {
  val trimmed = raw.trim()
  return trimmed.startsWith("Bridge ", ignoreCase = true) || trimmed.startsWith("obfs4 ", ignoreCase = true)
}

private fun extractTorBridgeLines(torrc: String): List<String> =
  torrc.lines()
    .mapNotNull { line ->
      val normalized = normalizeTorBridgeLine(line)
      normalized.takeIf { it.isNotBlank() && isTorBridgeLine(normalized) }
    }

private fun hasUseBridgesEnabled(torrc: String): Boolean = torrc.lines().any {
  val trimmed = it.trim()
  trimmed.startsWith("UseBridges", ignoreCase = true) && trimmed.substringAfter(' ', "").trim().ifEmpty { trimmed.substringAfter('=', "").trim() } == "1"
}

private fun ensureTorBridgeSupport(content: String, fallbackPort: Int): String {
  var out = content
  if (parseTorSocksPort(out) == null) {
    out = upsertTorSocksPort(out, fallbackPort)
  }
  val lines = out.lines().toMutableList()
  var hasUseBridges = false
  var hasPlugin = false
  for (i in lines.indices) {
    val trimmed = lines[i].trim()
    if (trimmed.startsWith("UseBridges", ignoreCase = true)) {
      lines[i] = "UseBridges 1"
      hasUseBridges = true
    }
    if (trimmed.startsWith("ClientTransportPlugin", ignoreCase = true) && trimmed.contains("obfs4", ignoreCase = true)) {
      hasPlugin = true
    }
  }
  if (!hasUseBridges) {
    lines.add("")
    lines.add("UseBridges 1")
  }
  if (!hasPlugin) {
    lines.add("ClientTransportPlugin obfs4 exec /data/adb/modules/ZDT-D/bin/obfs4proxy")
  }
  return lines.joinToString("\n").trimEnd() + "\n"
}

private fun replaceTorBridgeLines(content: String, bridges: List<String>, fallbackPort: Int): String {
  val normalized = bridges.map(::normalizeTorBridgeLine).filter { it.isNotBlank() }
  val baseLines = ensureTorBridgeSupport(content, fallbackPort)
    .lines()
    .filterNot { isTorBridgeLine(it) }
    .toMutableList()
  while (baseLines.isNotEmpty() && baseLines.last().isBlank()) baseLines.removeAt(baseLines.lastIndex)
  if (normalized.isNotEmpty()) {
    baseLines.add("")
    baseLines.addAll(normalized)
  }
  return baseLines.joinToString("\n").trimEnd() + "\n"
}

private fun buildTorValidationIssues(torrc: String): List<Int> {
  val issues = mutableListOf<Int>()
  val socks = parseTorSocksPort(torrc)
  if (socks == null || socks.host != "127.0.0.1") issues += R.string.tor_validation_need_socks
  if (!hasUseBridgesEnabled(torrc)) issues += R.string.tor_validation_need_use_bridges
  if (extractTorBridgeLines(torrc).isEmpty()) issues += R.string.tor_validation_need_bridge
  return issues
}

private fun chooseTorGeneratedPorts(existingSocksPort: Int?): Pair<Int, Int> {
  val reserved = mutableSetOf<Int>()
  if (existingSocksPort != null) reserved += existingSocksPort
  var t2s = 12347
  while (t2s in reserved) t2s += 1
  reserved += t2s
  var web = 8002
  while (web in reserved) web += 1
  return t2s to web
}

@Composable
private fun TorSectionCard(
  title: String,
  desc: String,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  ElevatedCard(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          desc,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
      }
      content()
    }
  }
}

@Composable
fun TorSection(
  program: ApiModels.Program,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val compact = rememberIsCompactWidth()

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  var loading by remember { mutableStateOf(true) }
  var enabled by remember(program.enabled) { mutableStateOf(program.enabled) }
  var active by remember { mutableStateOf(false) }
  var toggleSaving by remember { mutableStateOf(false) }

  var t2sPortText by remember { mutableStateOf("") }
  var t2sWebPortText by remember { mutableStateOf("") }
  var savedT2sPortText by remember { mutableStateOf("") }
  var savedT2sWebPortText by remember { mutableStateOf("") }
  var failedT2sSignature by remember { mutableStateOf<Pair<String, String>?>(null) }
  var settingsSaving by remember { mutableStateOf(false) }
  var t2sDirty by remember { mutableStateOf(false) }

  var torrcText by remember { mutableStateOf("") }
  var torrcSaved by remember { mutableStateOf("") }
  var torrcSaving by remember { mutableStateOf(false) }
  var portText by remember { mutableStateOf("") }
  var savedPortText by remember { mutableStateOf("") }
  var failedPortText by remember { mutableStateOf<String?>(null) }
  var portSaving by remember { mutableStateOf(false) }
  var portDirty by remember { mutableStateOf(false) }

  var editingBridgeIndex by remember { mutableStateOf<Int?>(null) }
  var showBridgeDialog by remember { mutableStateOf(false) }
  var bridgeDialogInitialText by remember { mutableStateOf("") }
  var askDeleteBridgeIndex by remember { mutableStateOf<Int?>(null) }
  var appsInitialContent by remember { mutableStateOf("") }

  fun syncFromState(state: TorProgramStateUi) {
    enabled = state.enabled
    active = state.active
    appsInitialContent = state.apps.ifBlank { appsInitialContent }
    torrcText = state.torrc
    torrcSaved = state.torrc
    t2sDirty = false
    portDirty = false

    val socksPort = parseTorSocksPort(state.torrc)?.port
    val generated = chooseTorGeneratedPorts(socksPort)
    val effectiveT2sPort = state.t2sPort ?: generated.first
    val effectiveT2sWebPort = state.t2sWebPort ?: generated.second
    t2sPortText = effectiveT2sPort.toString()
    t2sWebPortText = effectiveT2sWebPort.toString()
    savedT2sPortText = state.t2sPort?.toString().orEmpty()
    savedT2sWebPortText = state.t2sWebPort?.toString().orEmpty()
    failedT2sSignature = null

    val effectiveSocksPort = socksPort ?: 9050
    portText = effectiveSocksPort.toString()
    savedPortText = socksPort?.toString().orEmpty()
    failedPortText = null
  }

  fun refresh() {
    loading = true
    actions.loadJsonData("/api/programs/tor") { obj ->
      if (obj != null) {
        syncFromState(parseTorProgramStateUi(obj))
      } else {
        showSnack(context.getString(R.string.load_failed))
      }
      loading = false
    }
  }

  LaunchedEffect(Unit) { refresh() }

  val bridges = remember(torrcText) { extractTorBridgeLines(torrcText) }
  val validationIssues = remember(torrcText) { buildTorValidationIssues(torrcText) }
  val canEnable = bridges.isNotEmpty()

  val parsedT2sPort = t2sPortText.trim().toIntOrNull()
  val parsedT2sWebPort = t2sWebPortText.trim().toIntOrNull()
  val parsedSocksPort = portText.trim().toIntOrNull()
  val t2sPortsValid = parsedT2sPort in 1..65535 && parsedT2sWebPort in 1..65535
  val internalPortConflict = listOfNotNull(parsedT2sPort, parsedT2sWebPort, parsedSocksPort).let { ports -> ports.size != ports.toSet().size }

  LaunchedEffect(loading, t2sDirty, t2sPortText, t2sWebPortText, portText) {
    if (loading || !t2sDirty || settingsSaving || portSaving || torrcSaving) return@LaunchedEffect
    val currentSig = t2sPortText.trim() to t2sWebPortText.trim()
    val savedSig = savedT2sPortText.trim() to savedT2sWebPortText.trim()
    if (currentSig == savedSig || currentSig == failedT2sSignature) return@LaunchedEffect
    if (!t2sPortsValid || internalPortConflict) return@LaunchedEffect
    delay(700)
    if (!t2sDirty || settingsSaving || portSaving || torrcSaving) return@LaunchedEffect
    val submittedSig = t2sPortText.trim() to t2sWebPortText.trim()
    settingsSaving = true
    val payload = JSONObject().put("t2s_port", parsedT2sPort).put("t2s_web_port", parsedT2sWebPort)
    actions.saveJsonData("/api/programs/tor/setting", payload) { ok ->
      settingsSaving = false
      if (ok) {
        savedT2sPortText = submittedSig.first
        savedT2sWebPortText = submittedSig.second
        failedT2sSignature = null
      } else {
        failedT2sSignature = submittedSig
        if (t2sPortText.trim() == submittedSig.first) t2sPortText = savedT2sPortText
        if (t2sWebPortText.trim() == submittedSig.second) t2sWebPortText = savedT2sWebPortText
        showSnack(context.getString(R.string.singbox_auto_save_failed))
      }
      t2sDirty = (t2sPortText.trim() to t2sWebPortText.trim()) != (savedT2sPortText.trim() to savedT2sWebPortText.trim())
    }
  }

  LaunchedEffect(loading, portDirty, portText, t2sPortText, t2sWebPortText, torrcSaved) {
    if (loading || !portDirty || settingsSaving || portSaving || torrcSaving) return@LaunchedEffect
    val trimmed = portText.trim()
    if (trimmed == savedPortText.trim() || trimmed == failedPortText) return@LaunchedEffect
    if (parsedSocksPort !in 1..65535 || internalPortConflict) return@LaunchedEffect
    delay(700)
    if (!portDirty || settingsSaving || portSaving || torrcSaving) return@LaunchedEffect
    val submittedPort = portText.trim()
    val updated = upsertTorSocksPort(torrcSaved.ifBlank { torrcText }, parsedSocksPort!!)
    portSaving = true
    torrcSaving = true
    actions.saveText("/api/programs/tor/torrc", updated) { ok ->
      portSaving = false
      torrcSaving = false
      if (ok) {
        torrcText = updated
        torrcSaved = updated
        savedPortText = submittedPort
        failedPortText = null
      } else {
        failedPortText = submittedPort
        if (portText.trim() == submittedPort) portText = savedPortText
        showSnack(context.getString(R.string.singbox_auto_save_failed))
      }
      portDirty = portText.trim() != savedPortText.trim()
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    TorSectionCard(
      title = stringResource(R.string.tor_enabled_title),
      desc = if (active) stringResource(R.string.tor_enabled_active) else stringResource(R.string.tor_enabled_desc),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = stringResource(R.string.tor_bridges_title) + ": " + bridges.size,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
          )
          Text(
            text = stringResource(R.string.tor_port_label) + ": 127.0.0.1:${parsedSocksPort ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          )
        }
        Switch(
          checked = enabled,
          enabled = !loading && !toggleSaving,
          onCheckedChange = { desired ->
            if (desired && !canEnable) {
              showSnack(context.getString(R.string.tor_validation_need_bridge))
              return@Switch
            }
            toggleSaving = true
            actions.setProgramEnabled("tor", desired) { ok ->
              toggleSaving = false
              if (ok) enabled = desired else showSnack(context.getString(R.string.save_failed))
            }
          },
        )
      }
      if (loading || toggleSaving) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      if (validationIssues.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          validationIssues.forEach { msgRes ->
            Text(
              text = stringResource(msgRes),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }
    }

    AppListPickerCard(
      title = stringResource(R.string.tor_apps_title),
      desc = stringResource(R.string.tor_apps_desc),
      path = "/api/programs/tor/apps",
      initialContent = appsInitialContent,
      actions = actions,
      snackHost = snackHost,
    )

    TorSectionCard(
      title = stringResource(R.string.tor_ports_title),
      desc = stringResource(R.string.tor_ports_desc),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = t2sPortText,
          onValueChange = { t2sPortText = it.filter(Char::isDigit); t2sDirty = true },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          label = { Text(stringResource(R.string.tor_t2s_port_label)) },
        )
        OutlinedTextField(
          value = t2sWebPortText,
          onValueChange = { t2sWebPortText = it.filter(Char::isDigit); t2sDirty = true },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          label = { Text(stringResource(R.string.tor_t2s_web_port_label)) },
        )
        OutlinedTextField(
          value = portText,
          onValueChange = { portText = it.filter(Char::isDigit); portDirty = true },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          label = { Text(stringResource(R.string.tor_port_label)) },
        )
        when {
          !t2sPortsValid -> Text(
            stringResource(R.string.tor_t2s_fill_ports),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          parsedSocksPort !in 1..65535 -> Text(
            stringResource(R.string.tor_port_invalid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          internalPortConflict -> Text(
            stringResource(R.string.tor_ports_must_differ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          settingsSaving || portSaving || torrcSaving -> Text(
            stringResource(R.string.tor_ports_saving),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
          )
          else -> Text(
            stringResource(R.string.tor_ports_autosave_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
          )
        }
      }
    }

    TorSectionCard(
      title = stringResource(R.string.tor_bridges_title),
      desc = stringResource(R.string.tor_bridges_desc),
    ) {
      FilledTonalButton(
        onClick = {
          editingBridgeIndex = null
          bridgeDialogInitialText = ""
          showBridgeDialog = true
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.tor_bridge_create))
      }
      if (bridges.isEmpty()) {
        Text(
          stringResource(R.string.tor_bridges_empty),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          bridges.forEachIndexed { index, bridge ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))) {
              Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  stringResource(R.string.tor_bridge_title_fmt, index + 1),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  bridge,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  OutlinedButton(
                    onClick = {
                      editingBridgeIndex = index
                      bridgeDialogInitialText = bridge
                      showBridgeDialog = true
                    },
                    modifier = Modifier.weight(1f),
                  ) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                  }
                  OutlinedButton(onClick = { askDeleteBridgeIndex = index }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_delete))
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  if (showBridgeDialog) {
    TorBridgeDialog(
      initialText = bridgeDialogInitialText,
      isEditing = editingBridgeIndex != null,
      onDismiss = { showBridgeDialog = false },
      onOpenBot = {
        runCatching {
          val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/GetBridgesBot"))
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(intent)
        }.onFailure {
          showSnack(context.getString(R.string.tor_bridge_open_bot_failed))
        }
      },
      onSave = { bridgeText ->
        val current = bridges.toMutableList()
        val normalized = normalizeTorBridgeLine(bridgeText)
        if (editingBridgeIndex != null && editingBridgeIndex in current.indices) current[editingBridgeIndex!!] = normalized else current.add(normalized)
        val fallbackPort = parseTorSocksPort(torrcText)?.port ?: parsedSocksPort ?: 9050
        val updated = replaceTorBridgeLines(torrcText.ifBlank { torrcSaved }, current, fallbackPort)
        torrcSaving = true
        actions.saveText("/api/programs/tor/torrc", updated) { ok ->
          torrcSaving = false
          if (ok) {
            torrcText = updated
            torrcSaved = updated
            savedPortText = parseTorSocksPort(updated)?.port?.toString().orEmpty()
            portText = savedPortText.ifBlank { portText }
            showBridgeDialog = false
          } else {
            showSnack(context.getString(R.string.save_failed))
          }
        }
      },
    )
  }

  if (askDeleteBridgeIndex != null) {
    val idx = askDeleteBridgeIndex!!
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { askDeleteBridgeIndex = null },
      title = { Text(stringResource(R.string.tor_bridge_delete_title)) },
      text = { Text(stringResource(R.string.tor_bridge_delete_message_fmt, idx + 1)) },
      confirmButton = {
        Button(onClick = {
          val current = bridges.toMutableList()
          if (idx in current.indices) current.removeAt(idx)
          val fallbackPort = parseTorSocksPort(torrcText)?.port ?: parsedSocksPort ?: 9050
          val updated = replaceTorBridgeLines(torrcText.ifBlank { torrcSaved }, current, fallbackPort)
          torrcSaving = true
          actions.saveText("/api/programs/tor/torrc", updated) { ok ->
            torrcSaving = false
            askDeleteBridgeIndex = null
            if (ok) {
              torrcText = updated
              torrcSaved = updated
              showSnack(context.getString(R.string.deleted))
            } else {
              showSnack(context.getString(R.string.delete_failed))
            }
          }
        }) { Text(stringResource(R.string.action_delete)) }
      },
      dismissButton = {
        OutlinedButton(onClick = { askDeleteBridgeIndex = null }) {
          Text(stringResource(R.string.common_cancel))
        }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorBridgeDialog(
  initialText: String,
  isEditing: Boolean,
  onDismiss: () -> Unit,
  onOpenBot: () -> Unit,
  onSave: (String) -> Unit,
) {
  val compactMode = rememberIsNarrowWidth() || rememberIsShortHeight()
  val dialogHorizontalPadding = if (compactMode) 10.dp else 28.dp
  val dialogVerticalPadding = if (compactMode) 12.dp else 40.dp
  val contentPadding = if (compactMode) 14.dp else 20.dp
  val scrollState = rememberScrollState()

  var text by remember(initialText) { mutableStateOf(initialText) }
  var attemptedSave by remember { mutableStateOf(false) }
  val normalized = normalizeTorBridgeLine(text)
  val error = attemptedSave && normalized.isBlank()

  fun attemptSave() {
    attemptedSave = true
    if (normalized.isBlank()) return
    onSave(normalized)
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
    ),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = dialogHorizontalPadding, vertical = dialogVerticalPadding),
      shape = MaterialTheme.shapes.extraLarge,
      tonalElevation = 6.dp,
      shadowElevation = 12.dp,
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .imePadding()
          .padding(horizontal = contentPadding, vertical = contentPadding)
          .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (compactMode) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                text = stringResource(if (isEditing) R.string.tor_bridge_edit_title else R.string.tor_bridge_new_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                  Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                }
              }
              Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                IconButton(onClick = { attemptSave() }, modifier = Modifier.size(40.dp)) {
                  Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_save), tint = MaterialTheme.colorScheme.onPrimary)
                }
              }
            }
          }
        } else {
          Text(
            text = stringResource(if (isEditing) R.string.tor_bridge_edit_title else R.string.tor_bridge_new_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
          Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tor_bridge_info_title), fontWeight = FontWeight.SemiBold)
            Text(
              stringResource(R.string.tor_bridge_info_body),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            FilledTonalButton(onClick = onOpenBot, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Filled.OpenInNew, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.tor_bridge_open_bot))
            }
            Text(
              stringResource(R.string.tor_bridge_info_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }
        }

        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
          label = { Text(stringResource(R.string.tor_bridge_input_label)) },
          supportingText = {
            Text(
              text = if (error) stringResource(R.string.tor_bridge_input_required) else stringResource(R.string.tor_bridge_input_hint),
              color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          },
          isError = error,
          maxLines = 6,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )

        if (!compactMode) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onDismiss) {
              Text(stringResource(R.string.common_cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { attemptSave() }) {
              Text(stringResource(R.string.common_save))
            }
          }
        }
      }
    }
  }
}
