package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.NonRootBackendServer
import com.android.zdtd.service.NonRootCascadeProfile
import com.android.zdtd.service.NonRootPortRegistry
import com.android.zdtd.service.R
import com.android.zdtd.service.singbox.importer.SingBoxOneLineImporter
import org.json.JSONObject
import kotlin.math.abs

internal fun nonRootToolTitle(toolId: String): String = when (NonRootCascadeProfile.normalizeToolId(toolId)) {
  NonRootCascadeProfile.TOOL_HYSTERIA2 -> "Hysteria2"
  NonRootCascadeProfile.TOOL_SING_BOX -> "sing-box"
  NonRootCascadeProfile.TOOL_MIERU -> "Mieru"
  NonRootCascadeProfile.TOOL_WIREPROXY -> "WireProxy"
  else -> "Opera Proxy"
}

@Composable
internal fun NonRootMultiServerProfileEditorScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  profile: NonRootCascadeProfile,
  onUpdateProfile: (NonRootCascadeProfile) -> Unit,
  onAddServer: (String, String) -> Unit,
  onUpdateServer: (String, NonRootBackendServer) -> Unit,
  onMoveServer: (String, Int, Int) -> Unit,
  onDeleteServer: (String, String) -> Unit,
  onServerPortChange: (String, String, Int) -> Boolean,
  onServerAuxPortChange: (String, String, Int) -> Boolean,
) {
  val screenPadding = rememberAdaptiveScreenPadding()
  var nameText by remember(profile.id, profile.name) { mutableStateOf(profile.name) }
  var addDialog by remember { mutableStateOf(false) }
  var editServer by remember { mutableStateOf<NonRootBackendServer?>(null) }
  var deleteServer by remember { mutableStateOf<NonRootBackendServer?>(null) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = screenPadding,
      top = topContentPadding + 8.dp,
      end = screenPadding,
      bottom = bottomContentPadding + 8.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(200)),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
              modifier = Modifier.size(48.dp),
              shape = RoundedCornerShape(15.dp),
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
              contentColor = MaterialTheme.colorScheme.primary,
            ) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val icon = programIconRes(profile.toolId)
                if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(27.dp))
                else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(24.dp))
              }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text = "${nonRootToolTitle(profile.toolId)} — ${profile.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = stringResource(R.string.non_root_server_count_fmt, profile.servers.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          OutlinedTextField(
            value = nameText,
            onValueChange = { nameText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.non_root_profile_name)) },
            singleLine = true,
          )
          Button(
            onClick = { onUpdateProfile(profile.copy(name = nameText)) },
            enabled = nameText.trim().isNotEmpty() && nameText.trim() != profile.name,
            modifier = Modifier.fillMaxWidth(),
          ) { Text(stringResource(R.string.action_save)) }
        }
      }
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.non_root_servers_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            stringResource(R.string.non_root_servers_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        OutlinedButton(onClick = { addDialog = true }) {
          Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.size(6.dp))
          Text(stringResource(R.string.non_root_add_server))
        }
      }
    }

    if (profile.servers.isEmpty()) {
      item {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
          Text(
            stringResource(R.string.non_root_servers_empty),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      itemsIndexed(profile.servers, key = { _, server -> server.id }) { index, server ->
        NonRootBackendServerCard(
          modifier = Modifier.animateItem(),
          server = server,
          index = index,
          moveEnabled = profile.servers.size > 1,
          onMove = { current, direction ->
            val target = current + if (direction > 0) 1 else -1
            if (target !in profile.servers.indices) null else {
              onMoveServer(profile.id, current, target)
              target
            }
          },
          onToggle = { onUpdateServer(profile.id, server.copy(enabled = it)) },
          onEdit = { editServer = server },
          onDelete = { deleteServer = server },
        )
      }
    }
  }

  if (addDialog) {
    NonRootAddServerDialog(
      toolId = profile.toolId,
      index = profile.servers.size + 1,
      onDismiss = { addDialog = false },
      onCreate = { name ->
        onAddServer(profile.id, name)
        addDialog = false
      },
    )
  }

  editServer?.let { server ->
    NonRootBackendServerConfigDialog(
      toolId = profile.toolId,
      server = server,
      onDismiss = { editServer = null },
      onSave = { next, port, auxPort ->
        val portChanged = port != server.port
        val portOk = !portChanged || onServerPortChange(profile.id, server.id, port)
        if (!portOk) {
          false
        } else {
          val auxOk = auxPort == server.auxPort || auxPort <= 0 || onServerAuxPortChange(profile.id, server.id, auxPort)
          if (auxOk) {
            onUpdateServer(profile.id, next.copy(port = port, auxPort = if (auxPort > 0) auxPort else next.auxPort))
            editServer = null
            true
          } else {
            if (portChanged) onServerPortChange(profile.id, server.id, server.port)
            false
          }
        }
      },
    )
  }

  deleteServer?.let { server ->
    AlertDialog(
      onDismissRequest = { deleteServer = null },
      title = { Text(stringResource(R.string.non_root_delete_server_title)) },
      text = { Text(stringResource(R.string.non_root_delete_server_body, server.name)) },
      confirmButton = {
        TextButton(onClick = {
          onDeleteServer(profile.id, server.id)
          deleteServer = null
        }) { Text(stringResource(R.string.action_delete)) }
      },
      dismissButton = { TextButton(onClick = { deleteServer = null }) { Text(stringResource(R.string.common_cancel)) } },
    )
  }
}

@Composable
private fun NonRootBackendServerCard(
  modifier: Modifier = Modifier,
  server: NonRootBackendServer,
  index: Int,
  moveEnabled: Boolean,
  onMove: (Int, Int) -> Int?,
  onToggle: (Boolean) -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth().animateContentSize(tween(180)),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(
      1.dp,
      if (server.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
      else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
    ),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      NonRootServerDragHandle(
        stableKey = server.id,
        index = index,
        enabled = moveEnabled,
        onMove = onMove,
      )
      Column(
        modifier = Modifier.weight(1f).clickable(onClick = onEdit),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
          "SOCKS5 · ${NonRootPortRegistry.LOOPBACK}:${server.port}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = server.enabled, onCheckedChange = onToggle)
      IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit)) }
      IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error) }
    }
  }
}

@Composable
private fun NonRootServerDragHandle(
  stableKey: String,
  index: Int,
  enabled: Boolean,
  onMove: (Int, Int) -> Int?,
) {
  val latestOnMove by rememberUpdatedState(onMove)
  var gestureIndex by remember(stableKey) { mutableIntStateOf(index) }
  var dragTotal by remember(stableKey) { mutableFloatStateOf(0f) }
  Icon(
    imageVector = Icons.Filled.DragHandle,
    contentDescription = stringResource(R.string.non_root_t2s_drag_handle),
    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
    modifier = Modifier
      .size(30.dp)
      .pointerInput(stableKey, enabled) {
        if (!enabled) return@pointerInput
        detectDragGesturesAfterLongPress(
          onDragStart = {
            gestureIndex = index
            dragTotal = 0f
          },
          onDragCancel = { dragTotal = 0f },
          onDragEnd = { dragTotal = 0f },
          onDrag = { change, amount ->
            change.consume()
            dragTotal += amount.y
            if (abs(dragTotal) >= 46.dp.toPx()) {
              val direction = if (dragTotal > 0f) 1 else -1
              latestOnMove(gestureIndex, direction)?.let { gestureIndex = it }
              dragTotal = 0f
            }
          },
        )
      },
  )
}

@Composable
private fun NonRootAddServerDialog(
  toolId: String,
  index: Int,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("Server $index") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.non_root_add_server)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(nonRootToolTitle(toolId), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.non_root_server_name)) },
          singleLine = true,
        )
      }
    },
    confirmButton = { TextButton(onClick = { onCreate(name.trim().ifBlank { "Server $index" }) }) { Text(stringResource(R.string.action_create)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
  )
}

@Composable
private fun NonRootBackendServerConfigDialog(
  toolId: String,
  server: NonRootBackendServer,
  onDismiss: () -> Unit,
  onSave: (NonRootBackendServer, Int, Int) -> Boolean,
) {
  var name by remember(server.id) { mutableStateOf(server.name) }
  var portText by remember(server.id) { mutableStateOf(server.port.toString()) }
  var auxPortText by remember(server.id) { mutableStateOf(server.auxPort.takeIf { it > 0 }?.toString().orEmpty()) }
  var logLevel by remember(server.id) { mutableStateOf(server.logLevel) }
  var config by remember(server.id) { mutableStateOf(server.configText) }
  var importSource by remember(server.id) { mutableStateOf("") }
  var importExpanded by remember(server.id) { mutableStateOf(false) }
  var error by remember(server.id) { mutableStateOf<String?>(null) }
  val hysteriaServerRequired = stringResource(R.string.non_root_error_hysteria_server_required)
  val wireProxyEmpty = stringResource(R.string.non_root_error_wireproxy_empty)
  val importFailed = stringResource(R.string.non_root_error_import_failed)
  val invalidSocksPort = stringResource(R.string.non_root_error_invalid_socks_port)
  val invalidRpcPort = stringResource(R.string.non_root_error_invalid_rpc_port)
  val rpcPortConflict = stringResource(R.string.non_root_error_rpc_port_conflict)
  val portUnavailable = stringResource(R.string.non_root_port_error)

  fun validateConfig(): Boolean {
    error = when (toolId) {
      NonRootCascadeProfile.TOOL_HYSTERIA2 -> runCatching {
        val obj = JSONObject(config)
        require(obj.optString("server").isNotBlank()) { hysteriaServerRequired }
      }.exceptionOrNull()?.message
      NonRootCascadeProfile.TOOL_SING_BOX,
      NonRootCascadeProfile.TOOL_MIERU -> runCatching { JSONObject(config) }.exceptionOrNull()?.message
      NonRootCascadeProfile.TOOL_WIREPROXY -> if (config.isBlank()) wireProxyEmpty else null
      else -> null
    }
    return error == null
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("${nonRootToolTitle(toolId)} · ${server.name}") },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.non_root_server_name)) },
            singleLine = true,
          )
        }
        item {
          OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.non_root_local_socks_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
          )
        }
        if (toolId == NonRootCascadeProfile.TOOL_MIERU) {
          item {
            OutlinedTextField(
              value = auxPortText,
              onValueChange = { auxPortText = it.filter(Char::isDigit).take(5) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.non_root_mieru_rpc_port)) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
            )
          }
        }
        if (toolId == NonRootCascadeProfile.TOOL_HYSTERIA2) {
          item {
            OutlinedTextField(
              value = logLevel,
              onValueChange = { logLevel = it.trim().take(12) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.non_root_log_level)) },
              singleLine = true,
            )
          }
        }
        if (toolId == NonRootCascadeProfile.TOOL_SING_BOX) {
          item {
            OutlinedButton(onClick = { importExpanded = !importExpanded }, modifier = Modifier.fillMaxWidth()) {
              Text(stringResource(R.string.non_root_singbox_import))
            }
          }
          item {
            AnimatedVisibility(
              visible = importExpanded,
              enter = expandVertically(tween(180)) + fadeIn(tween(140)),
              exit = shrinkVertically(tween(160)) + fadeOut(tween(110)),
            ) {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = importSource,
                  onValueChange = { importSource = it },
                  modifier = Modifier.fillMaxWidth(),
                  label = { Text(stringResource(R.string.non_root_singbox_import_source)) },
                  minLines = 2,
                  maxLines = 5,
                )
                Button(
                  onClick = {
                    runCatching { SingBoxOneLineImporter.import(importSource, server.port) }
                      .onSuccess { result -> config = result.configJson; error = null }
                      .onFailure { error = it.message ?: importFailed }
                  },
                  enabled = importSource.isNotBlank(),
                  modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.non_root_import_config)) }
              }
            }
          }
        }
        item {
          OutlinedTextField(
            value = config,
            onValueChange = { config = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (toolId == NonRootCascadeProfile.TOOL_WIREPROXY) "config.conf" else "config.json") },
            minLines = 10,
            maxLines = 20,
          )
        }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        val port = portText.toIntOrNull()
        val aux = auxPortText.toIntOrNull() ?: server.auxPort
        when {
          port == null || port !in NonRootPortRegistry.MIN_PORT..65535 -> error = invalidSocksPort
          toolId == NonRootCascadeProfile.TOOL_MIERU && aux !in NonRootPortRegistry.MIN_PORT..65535 -> error = invalidRpcPort
          toolId == NonRootCascadeProfile.TOOL_MIERU && aux == port -> error = rpcPortConflict
          !validateConfig() -> Unit
          else -> {
            val saved = onSave(server.copy(
              name = name.trim().ifBlank { server.name },
              configText = config,
              logLevel = logLevel.trim().ifBlank { "info" },
            ), port, aux)
            if (!saved) error = portUnavailable
          }
        }
      }) { Text(stringResource(R.string.action_save)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
  )
}
