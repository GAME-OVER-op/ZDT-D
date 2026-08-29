package com.android.zdtd.service.ui.vps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.singbox.importer.SingBoxOneLineImporter
import com.android.zdtd.service.vps.VpsAuthType
import com.android.zdtd.service.vps.VpsClientConfig
import com.android.zdtd.service.vps.VpsConfigResult
import com.android.zdtd.service.vps.VpsLoadState
import com.android.zdtd.service.vps.VpsMetrics
import com.android.zdtd.service.vps.VpsOperationState
import com.android.zdtd.service.vps.VpsReachability
import com.android.zdtd.service.vps.VpsServer
import com.android.zdtd.service.vps.VpsServiceKind
import com.android.zdtd.service.vps.VpsServiceProfile
import com.android.zdtd.service.vps.VpsServiceState
import com.android.zdtd.service.vps.VpsSshAuth
import com.android.zdtd.service.vps.VpsViewModel
import java.io.File
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun VpsServersScreen(
  viewModel: VpsViewModel,
  onOpenServer: (String) -> Unit,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val servers by viewModel.servers.collectAsState()
  val metrics by viewModel.metrics.collectAsState()
  val pending by viewModel.pendingProbe.collectAsState()
  val operation by viewModel.operation.collectAsState()
  var showAdd by remember { mutableStateOf(false) }
  var deleteTarget by remember { mutableStateOf<VpsServer?>(null) }

  LifecycleStartEffect(Unit) {
    viewModel.startListMonitoring()
    onStopOrDispose { viewModel.stopListMonitoring() }
  }

  if (showAdd && pending == null) {
    AddVpsServerDialog(
      busy = operation.running,
      onDismiss = { if (!operation.running) showAdd = false },
      onSubmit = viewModel::probeNewServer,
    )
  }

  pending?.let { probe ->
    AlertDialog(
      onDismissRequest = viewModel::dismissPendingProbe,
      icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
      title = { Text(stringResource(R.string.vps_confirm_fingerprint_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.vps_confirm_fingerprint_desc, probe.host, probe.port))
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          ) {
            Text(
              probe.result.fingerprint,
              modifier = Modifier.padding(12.dp),
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
            )
          }
          Text(stringResource(R.string.vps_confirm_fingerprint_warning), style = MaterialTheme.typography.bodySmall)
        }
      },
      confirmButton = {
        Button(onClick = {
          viewModel.confirmPendingServer()
          showAdd = false
        }) { Text(stringResource(R.string.action_confirm)) }
      },
      dismissButton = { OutlinedButton(onClick = viewModel::dismissPendingProbe) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  deleteTarget?.let { server ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text(stringResource(R.string.vps_delete_server_title)) },
      text = { Text(stringResource(R.string.vps_delete_server_message, server.name)) },
      confirmButton = {
        Button(onClick = { viewModel.deleteServer(server.id); deleteTarget = null }) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  VpsOperationDialog(operation = operation, onDismiss = viewModel::clearOperation)

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = topContentPadding + 8.dp, bottom = bottomContentPadding + 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      VpsHeaderCard(
        title = stringResource(R.string.vps_servers_title),
        description = stringResource(R.string.vps_servers_desc),
        actionText = stringResource(R.string.vps_add_server),
        onAction = { showAdd = true },
      )
    }
    if (servers.isEmpty()) {
      item { VpsEmptyState(stringResource(R.string.vps_no_servers), stringResource(R.string.vps_no_servers_hint)) }
    } else {
      items(servers, key = { it.id }) { server ->
        VpsServerCard(
          server = server,
          metrics = metrics[server.id] ?: VpsMetrics(),
          onClick = { onOpenServer(server.id) },
          onRefresh = { viewModel.refreshServer(server.id) },
          onDelete = { deleteTarget = server },
        )
      }
    }
  }
}

@Composable
fun VpsServerDetailsScreen(
  serverId: String,
  viewModel: VpsViewModel,
  onOpenService: (VpsServiceKind) -> Unit,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val server = viewModel.server(serverId)
  val metrics by viewModel.metrics.collectAsState()
  val services by viewModel.serviceStates.collectAsState()
  val serviceLoads by viewModel.serviceLoads.collectAsState()
  val operation by viewModel.operation.collectAsState()
  var removeTarget by remember { mutableStateOf<VpsServiceKind?>(null) }
  var logs by remember { mutableStateOf<String?>(null) }
  var showRebootConfirm by remember { mutableStateOf(false) }

  LifecycleStartEffect(serverId) {
    viewModel.startServerDetailsMonitoring(serverId)
    onStopOrDispose { viewModel.stopServerDetailsMonitoring(serverId) }
  }

  VpsOperationDialog(operation = operation, onDismiss = viewModel::clearOperation)
  logs?.let { text -> VpsTextDialog(stringResource(R.string.vps_logs_title), text, onDismiss = { logs = null }) }
  if (showRebootConfirm && server != null) {
    AlertDialog(
      onDismissRequest = { if (!operation.running) showRebootConfirm = false },
      title = { Text(stringResource(R.string.vps_reboot_confirm_title)) },
      text = { Text(stringResource(R.string.vps_reboot_confirm_message, server.name)) },
      confirmButton = {
        Button(
          enabled = !operation.running,
          onClick = {
            showRebootConfirm = false
            viewModel.rebootServer(serverId)
          },
        ) { Text(stringResource(R.string.vps_reboot_server)) }
      },
      dismissButton = {
        OutlinedButton(enabled = !operation.running, onClick = { showRebootConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
  removeTarget?.let { kind ->
    AlertDialog(
      onDismissRequest = { removeTarget = null },
      title = { Text(stringResource(R.string.vps_remove_service_title)) },
      text = { Text(stringResource(R.string.vps_remove_service_message, serviceTitle(kind))) },
      confirmButton = {
        Button(onClick = { viewModel.removeService(serverId, kind); removeTarget = null }) { Text(stringResource(R.string.action_delete)) }
      },
      dismissButton = { OutlinedButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  if (server == null) {
    VpsEmptyState(stringResource(R.string.vps_server_not_found), "")
    return
  }

  val currentMetrics = metrics[serverId] ?: VpsMetrics()
  val states = services[serverId].orEmpty().associateBy { it.kind }
  val serviceLoad = serviceLoads[serverId] ?: VpsLoadState()

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = topContentPadding + 8.dp, bottom = bottomContentPadding + 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      VpsServerSummaryCard(
        server = server,
        metrics = currentMetrics,
        rebootEnabled = currentMetrics.reachability == VpsReachability.ONLINE && !operation.running,
        onRefresh = { viewModel.refreshServer(serverId); viewModel.loadServices(serverId) },
        onReboot = { showRebootConfirm = true },
      )
    }
    item {
      Text(
        text = stringResource(R.string.vps_services_title),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }
    item {
      VpsLoadTransition(
        state = serviceLoad,
        loadingText = stringResource(R.string.vps_loading_services),
        onRetry = { viewModel.loadServices(serverId, silent = true) },
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          VpsServiceKind.entries.forEach { kind ->
            val state = states[kind] ?: VpsServiceState(kind, installed = false, active = false)
            VpsServiceCard(
              kind = kind,
              state = state,
              enabled = currentMetrics.reachability == VpsReachability.ONLINE && currentMetrics.supportedPlatform,
              onOpen = { if (state.installed) onOpenService(kind) },
              onInstall = { viewModel.installService(serverId, kind) },
              onRestart = { viewModel.restartService(serverId, kind) },
              onLogs = { viewModel.loadLogs(serverId, kind, null) { logs = it } },
              onRemove = { removeTarget = kind },
            )
          }
        }
      }
    }
    if (currentMetrics.reachability == VpsReachability.ONLINE && !currentMetrics.supportedPlatform) {
      item {
        WarningCard(stringResource(R.string.vps_unsupported_platform, currentMetrics.osName, currentMetrics.architecture))
      }
    }
  }
}

@Composable
fun VpsServiceScreen(
  serverId: String,
  kind: VpsServiceKind,
  viewModel: VpsViewModel,
  onOpenProfile: (String) -> Unit,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val profilesMap by viewModel.profiles.collectAsState()
  val services by viewModel.serviceStates.collectAsState()
  val serviceLoads by viewModel.serviceLoads.collectAsState()
  val profileLoads by viewModel.profileLoads.collectAsState()
  val metricsMap by viewModel.metrics.collectAsState()
  val operation by viewModel.operation.collectAsState()
  val profileKey = VpsViewModel.profileKey(serverId, kind)
  val profiles = profilesMap[profileKey].orEmpty()
  val state = services[serverId].orEmpty().firstOrNull { it.kind == kind }
  val contentLoad = if (kind == VpsServiceKind.DNSCRYPT) {
    serviceLoads[serverId] ?: VpsLoadState()
  } else {
    profileLoads[profileKey] ?: VpsLoadState()
  }
  val serverOnline = metricsMap[serverId]?.reachability == VpsReachability.ONLINE
  var showCreate by remember { mutableStateOf(false) }
  var deleteTarget by remember { mutableStateOf<VpsServiceProfile?>(null) }
  var logs by remember { mutableStateOf<String?>(null) }

  LifecycleStartEffect("$serverId:${kind.wireId}") {
    viewModel.startServiceMonitoring(serverId, kind)
    onStopOrDispose { viewModel.stopServiceMonitoring(serverId, kind) }
  }

  VpsOperationDialog(operation = operation, onDismiss = viewModel::clearOperation)
  logs?.let { VpsTextDialog(stringResource(R.string.vps_logs_title), it, onDismiss = { logs = null }) }

  if (showCreate) {
    CreateVpsProfileDialog(
      kind = kind,
      suggestedHysteriaPort = profilesMap[VpsViewModel.profileKey(serverId, VpsServiceKind.XRAY)]?.firstOrNull()?.port,
      busy = operation.running || !serverOnline,
      onDismiss = { showCreate = false },
      onCreate = { name, port, mode, domain, email, snis ->
        showCreate = false
        viewModel.createProfile(serverId, kind, name, port, mode, domain, email, snis)
      },
    )
  }

  deleteTarget?.let { profile ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text(stringResource(R.string.vps_delete_profile_title)) },
      text = { Text(stringResource(R.string.vps_delete_profile_message, profile.name)) },
      confirmButton = {
        Button(enabled = serverOnline, onClick = { viewModel.deleteProfile(serverId, kind, profile.id); deleteTarget = null }) { Text(stringResource(R.string.action_delete)) }
      },
      dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = topContentPadding + 8.dp, bottom = bottomContentPadding + 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      VpsHeaderCard(
        title = serviceTitle(kind),
        description = serviceDescription(kind),
        actionText = if (kind == VpsServiceKind.DNSCRYPT) null else stringResource(R.string.vps_create_profile),
        actionEnabled = serverOnline,
        onAction = { showCreate = true },
      )
    }
    item {
      VpsLoadTransition(
        state = contentLoad,
        loadingText = stringResource(if (kind == VpsServiceKind.DNSCRYPT) R.string.vps_loading_service else R.string.vps_loading_profiles),
        onRetry = {
          if (kind == VpsServiceKind.DNSCRYPT) viewModel.loadServices(serverId, silent = true)
          else viewModel.loadProfiles(serverId, kind, silent = true)
        },
      ) {
        if (kind == VpsServiceKind.DNSCRYPT) {
          ManagedDnscryptCard(
            state = state,
            enabled = serverOnline,
            onRestart = { viewModel.restartService(serverId, kind) },
            onLogs = { viewModel.loadLogs(serverId, kind, null) { logs = it } },
            horizontalPadding = 0.dp,
          )
        } else if (profiles.isEmpty()) {
          VpsEmptyState(
            stringResource(R.string.vps_no_profiles),
            stringResource(R.string.vps_no_profiles_hint),
            horizontalPadding = 0.dp,
          )
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            profiles.forEach { profile ->
              VpsProfileCard(
                profile = profile,
                enabled = serverOnline,
                onClick = { onOpenProfile(profile.id) },
                onRestart = { viewModel.restartService(serverId, kind, profile.id) },
                onLogs = { viewModel.loadLogs(serverId, kind, profile.id) { logs = it } },
                onDelete = { deleteTarget = profile },
                horizontalPadding = 0.dp,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun VpsProfileScreen(
  serverId: String,
  kind: VpsServiceKind,
  profileId: String,
  viewModel: VpsViewModel,
  actions: ZdtdActions,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val context = LocalContext.current
  val server = viewModel.server(serverId)
  val profilesMap by viewModel.profiles.collectAsState()
  val clientsMap by viewModel.clients.collectAsState()
  val profileLoads by viewModel.profileLoads.collectAsState()
  val clientLoads by viewModel.clientLoads.collectAsState()
  val metricsMap by viewModel.metrics.collectAsState()
  val operation by viewModel.operation.collectAsState()
  val configResult by viewModel.configResult.collectAsState()
  val profileKey = VpsViewModel.profileKey(serverId, kind)
  val clientKey = VpsViewModel.clientKey(serverId, kind, profileId)
  val profile = profilesMap[profileKey].orEmpty().firstOrNull { it.id == profileId }
  val clients = clientsMap[clientKey].orEmpty()
  val profileLoad = profileLoads[profileKey] ?: VpsLoadState()
  val clientLoad = clientLoads[clientKey] ?: VpsLoadState()
  val contentLoad = combineLoadStates(profileLoad, clientLoad)
  val serverOnline = metricsMap[serverId]?.reachability == VpsReachability.ONLINE
  var showCreateClient by remember { mutableStateOf(false) }
  var deleteTarget by remember { mutableStateOf<VpsClientConfig?>(null) }
  var snack by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(serverId, kind, profileId) { viewModel.clearConfigResult() }
  LifecycleStartEffect("$serverId:${kind.wireId}:$profileId") {
    viewModel.startProfileMonitoring(serverId, kind, profileId)
    onStopOrDispose { viewModel.stopProfileMonitoring(serverId, kind, profileId) }
  }

  VpsOperationDialog(operation = operation, onDismiss = viewModel::clearOperation)

  if (showCreateClient) {
    CreateClientDialog(
      existing = clients.map { it.name }.toSet(),
      busy = operation.running || !serverOnline,
      onDismiss = { showCreateClient = false },
      onCreate = { name -> showCreateClient = false; viewModel.createClient(serverId, kind, profileId, name) },
    )
  }

  deleteTarget?.let { client ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text(stringResource(R.string.vps_revoke_client_title)) },
      text = { Text(stringResource(R.string.vps_revoke_client_message, client.name)) },
      confirmButton = {
        Button(enabled = serverOnline, onClick = { viewModel.deleteClient(serverId, kind, profileId, client.id); deleteTarget = null }) { Text(stringResource(R.string.vps_revoke_access)) }
      },
      dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  configResult?.let { result ->
    VpsConfigResultDialog(
      result = result,
      onDismiss = viewModel::clearConfigResult,
      onCopy = {
        val value = result.shareLink ?: result.content
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(result.fileName, value))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
      },
      onSave = {
        val serverName = server?.name ?: "server"
        viewModel.saveConfigToSharedStorage(serverName, result) { saved ->
          val message = saved.fold(
            onSuccess = { context.getString(R.string.vps_saved_to_path, it) },
            onFailure = { it.message ?: context.getString(R.string.save_failed) },
          )
          Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
      },
      onShare = { shareConfig(context, result) },
      onOpenExternal = { openConfigExternally(context, result) },
      onImport = {
        importConfigIntoZdtd(context, actions, server, profile, result) { message -> snack = message }
      },
    )
  }

  snack?.let { message ->
    AlertDialog(
      onDismissRequest = { snack = null },
      title = { Text(stringResource(R.string.vps_result_title)) },
      text = { Text(message) },
      confirmButton = { TextButton(onClick = { snack = null }) { Text(stringResource(R.string.action_ok)) } },
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = topContentPadding + 8.dp, bottom = bottomContentPadding + 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      VpsLoadTransition(
        state = contentLoad,
        loadingText = stringResource(R.string.vps_loading_profile),
        onRetry = {
          viewModel.loadProfiles(serverId, kind, silent = true)
          viewModel.loadClients(serverId, kind, profileId, silent = true)
        },
      ) {
        if (profile == null) {
          VpsEmptyState(
            stringResource(R.string.vps_profile_not_found),
            "",
            horizontalPadding = 0.dp,
          )
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VpsHeaderCard(
              title = profile.name,
              description = "${profile.mode.uppercase(Locale.ROOT)} · ${profile.protocol.uppercase(Locale.ROOT)} ${profile.port}",
              actionText = stringResource(R.string.vps_create_client),
              actionEnabled = serverOnline,
              onAction = { showCreateClient = true },
              horizontalPadding = 0.dp,
            )
            if (clients.isEmpty()) {
              VpsEmptyState(
                stringResource(R.string.vps_no_clients),
                stringResource(R.string.vps_no_clients_hint),
                horizontalPadding = 0.dp,
              )
            } else {
              clients.forEach { client ->
                VpsClientCard(
                  client = client,
                  enabled = serverOnline,
                  onGenerate = { viewModel.requestConfig(serverId, kind, profileId, client.id) },
                  onDelete = { deleteTarget = client },
                  horizontalPadding = 0.dp,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AddVpsServerDialog(
  busy: Boolean,
  onDismiss: () -> Unit,
  onSubmit: (String, String, Int, String, VpsSshAuth) -> Unit,
) {
  val context = LocalContext.current
  var name by remember { mutableStateOf("") }
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("22") }
  var username by remember { mutableStateOf("root") }
  var authType by remember { mutableStateOf(VpsAuthType.PASSWORD) }
  var password by remember { mutableStateOf("") }
  var privateKey by remember { mutableStateOf("") }
  var privateKeyName by remember { mutableStateOf("") }
  var privateKeyPassphrase by remember { mutableStateOf("") }
  var keyError by remember { mutableStateOf<String?>(null) }

  val keyReadError = stringResource(R.string.vps_private_key_read_error)
  val keyTooLarge = stringResource(R.string.vps_private_key_too_large)
  val publicKeyError = stringResource(R.string.vps_public_key_error)

  val keyPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      runCatching { readVpsPrivateKeyFromUri(context, uri) }
        .onSuccess { imported ->
          privateKey = imported.text
          privateKeyName = imported.name
          keyError = if (looksLikeVpsPublicKey(imported.text)) publicKeyError else null
        }
        .onFailure { error ->
          privateKey = ""
          privateKeyName = ""
          keyError = if (error is VpsPrivateKeyTooLargeException) keyTooLarge else keyReadError
        }
    },
  )

  val portValid = port.toIntOrNull() in 1..65535
  val authValid = when (authType) {
    VpsAuthType.PASSWORD -> password.isNotEmpty()
    VpsAuthType.PRIVATE_KEY -> privateKey.isNotBlank() && keyError == null && !looksLikeVpsPublicKey(privateKey)
  }
  val valid = host.isNotBlank() && username.isNotBlank() && portValid && authValid

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
    title = { Text(stringResource(R.string.vps_add_server)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.vps_server_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.vps_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.vps_ssh_port)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, { username = it.trim() }, label = { Text(stringResource(R.string.vps_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text(
          stringResource(R.string.vps_auth_method),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (authType == VpsAuthType.PASSWORD) {
            FilledTonalButton(onClick = { authType = VpsAuthType.PASSWORD }, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.vps_auth_password))
            }
          } else {
            OutlinedButton(onClick = { authType = VpsAuthType.PASSWORD; keyError = null }, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.vps_auth_password))
            }
          }
          if (authType == VpsAuthType.PRIVATE_KEY) {
            FilledTonalButton(onClick = { authType = VpsAuthType.PRIVATE_KEY }, modifier = Modifier.weight(1f)) {
              Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.vps_auth_private_key))
            }
          } else {
            OutlinedButton(onClick = { authType = VpsAuthType.PRIVATE_KEY }, modifier = Modifier.weight(1f)) {
              Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.vps_auth_private_key))
            }
          }
        }

        if (authType == VpsAuthType.PASSWORD) {
          OutlinedTextField(
            password,
            { password = it },
            label = { Text(stringResource(R.string.vps_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            stringResource(R.string.vps_password_storage_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
          )
        } else {
          OutlinedButton(
            enabled = !busy,
            onClick = { keyPicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(
              if (privateKeyName.isBlank()) stringResource(R.string.vps_choose_private_key_file)
              else stringResource(R.string.vps_private_key_file_selected, privateKeyName),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          OutlinedTextField(
            value = privateKey,
            onValueChange = { value ->
              privateKey = value
              if (privateKeyName.isNotBlank()) privateKeyName = ""
              keyError = when {
                value.isBlank() -> null
                looksLikeVpsPublicKey(value) -> publicKeyError
                else -> null
              }
            },
            label = { Text(stringResource(R.string.vps_private_key)) },
            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
            minLines = 4,
            maxLines = 8,
            isError = keyError != null,
            supportingText = keyError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = privateKeyPassphrase,
            onValueChange = { privateKeyPassphrase = it },
            label = { Text(stringResource(R.string.vps_private_key_passphrase)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            stringResource(R.string.vps_private_key_storage_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
          )
        }
      }
    },
    confirmButton = {
      Button(
        enabled = valid && !busy,
        onClick = {
          onSubmit(
            name,
            host,
            port.toInt(),
            username,
            VpsSshAuth(
              type = authType,
              password = if (authType == VpsAuthType.PASSWORD) password else "",
              privateKey = if (authType == VpsAuthType.PRIVATE_KEY) privateKey else "",
              privateKeyName = if (authType == VpsAuthType.PRIVATE_KEY) privateKeyName else "",
              privateKeyPassphrase = if (authType == VpsAuthType.PRIVATE_KEY) privateKeyPassphrase else "",
            ),
          )
        },
      ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(stringResource(R.string.vps_check_and_add))
      }
    },
    dismissButton = { OutlinedButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

private data class ImportedVpsPrivateKey(val name: String, val text: String)
private class VpsPrivateKeyTooLargeException : IllegalArgumentException()

private fun readVpsPrivateKeyFromUri(context: Context, uri: Uri): ImportedVpsPrivateKey {
  val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else null
  }.orEmpty().ifBlank { "ssh_private_key" }
  val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open selected key")
  val bytes = input.use { stream ->
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
      val count = stream.read(buffer)
      if (count < 0) break
      total += count
      if (total > VPS_PRIVATE_KEY_MAX_BYTES) throw VpsPrivateKeyTooLargeException()
      output.write(buffer, 0, count)
    }
    output.toByteArray()
  }
  require(bytes.isNotEmpty()) { "Selected key is empty" }
  return ImportedVpsPrivateKey(name, bytes.toString(Charsets.UTF_8).trim())
}

private fun looksLikeVpsPublicKey(value: String): Boolean {
  val first = value.lineSequence().firstOrNull()?.trim().orEmpty()
  return first.startsWith("ssh-") || first.startsWith("ecdsa-") || first.startsWith("sk-")
}

private const val VPS_PRIVATE_KEY_MAX_BYTES = 512 * 1024

@Composable
private fun CreateVpsProfileDialog(
  kind: VpsServiceKind,
  suggestedHysteriaPort: Int?,
  busy: Boolean,
  onDismiss: () -> Unit,
  onCreate: (String, Int, String, String, String, List<String>) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  val defaultPort = when (kind) {
    VpsServiceKind.OPENVPN -> 1194
    VpsServiceKind.XRAY -> 443
    VpsServiceKind.HYSTERIA2 -> suggestedHysteriaPort ?: 443
    VpsServiceKind.WIREPROXY -> 51820
    else -> 0
  }
  var port by remember { mutableStateOf(defaultPort.toString()) }
  var mode by remember { mutableStateOf(if (kind == VpsServiceKind.XRAY) "reality" else if (kind == VpsServiceKind.OPENVPN) "udp" else "default") }
  var domain by remember { mutableStateOf("") }
  var email by remember { mutableStateOf("") }
  var hysteriaSni by remember(kind) { mutableStateOf("zdt-hysteria.local") }
  var xraySnis by remember(kind) { mutableStateOf(listOf("www.microsoft.com")) }
  var menu by remember { mutableStateOf(false) }
  val configuration = LocalConfiguration.current
  val requiresPublicTls = kind == VpsServiceKind.XRAY && mode == "ws"
  val normalizedXraySnis = xraySnis.map(String::trim).filter(String::isNotBlank).distinct()
  val requiresSni = (kind == VpsServiceKind.XRAY && mode == "reality") || kind == VpsServiceKind.HYSTERIA2
  val hasRequiredSni = when {
    kind == VpsServiceKind.XRAY && mode == "reality" -> normalizedXraySnis.isNotEmpty()
    kind == VpsServiceKind.HYSTERIA2 -> hysteriaSni.isNotBlank()
    else -> true
  }
  val valid = name.isNotBlank() && port.toIntOrNull() in 1..65535 && (!requiresPublicTls || domain.isNotBlank()) && (!requiresSni || hasRequiredSni)
  val modes = when (kind) {
    VpsServiceKind.OPENVPN -> listOf("udp", "tcp")
    VpsServiceKind.XRAY -> listOf("reality", "ws")
    else -> listOf(mode)
  }

  fun updateXraySni(index: Int, raw: String) {
    val hasSeparator = raw.any { it == ',' || it == '\n' || it == ';' }
    if (!hasSeparator) {
      xraySnis = xraySnis.toMutableList().also { list -> list[index] = raw.trim() }
      return
    }
    val parts = raw.split(',', '\n', ';').map(String::trim).filter(String::isNotBlank)
    val next = xraySnis.toMutableList()
    next.removeAt(index)
    next.addAll(index, parts.ifEmpty { listOf("") })
    xraySnis = next.distinct()
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.vps_create_profile)) },
    text = {
      Column(
        modifier = Modifier
          .heightIn(max = configuration.screenHeightDp.dp * 0.62f)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.vps_profile_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.vps_port)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        if (modes.size > 1) {
          Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vps_mode_value, mode)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              modes.forEach { item -> DropdownMenuItem(text = { Text(item.uppercase(Locale.ROOT)) }, onClick = { mode = item; menu = false }) }
            }
          }
        }
        if (kind == VpsServiceKind.XRAY && mode == "reality") {
          xraySnis.forEachIndexed { index, value ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              OutlinedTextField(
                value = value,
                onValueChange = { updateXraySni(index, it) },
                label = { Text(stringResource(R.string.vps_sni)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
              if (xraySnis.size > 1) {
                IconButton(
                  enabled = !busy,
                  onClick = { xraySnis = xraySnis.toMutableList().also { it.removeAt(index) } },
                ) {
                  Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                }
              }
            }
          }
          TextButton(
            enabled = !busy,
            onClick = { xraySnis = xraySnis + "" },
          ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.vps_add_sni))
          }
        } else if (kind == VpsServiceKind.HYSTERIA2) {
          OutlinedTextField(hysteriaSni, { hysteriaSni = it.trim() }, label = { Text(stringResource(R.string.vps_sni)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (requiresPublicTls) {
          OutlinedTextField(domain, { domain = it.trim() }, label = { Text(stringResource(R.string.vps_domain)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(email, { email = it.trim() }, label = { Text(stringResource(R.string.vps_letsencrypt_email_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          Text(stringResource(R.string.vps_automatic_tls_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (kind == VpsServiceKind.HYSTERIA2 && suggestedHysteriaPort != null) {
          Text(stringResource(R.string.vps_shared_port_hint, suggestedHysteriaPort), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
      }
    },
    confirmButton = {
      Button(
        enabled = valid && !busy,
        onClick = {
          val snis = when {
            kind == VpsServiceKind.XRAY && mode == "reality" -> normalizedXraySnis
            kind == VpsServiceKind.HYSTERIA2 -> listOf(hysteriaSni.trim())
            else -> emptyList()
          }
          onCreate(name, port.toInt(), mode, domain, email, snis)
        },
      ) { Text(stringResource(R.string.action_create)) }
    },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun CreateClientDialog(existing: Set<String>, busy: Boolean, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
  var name by remember { mutableStateOf("") }
  val normalized = name.trim().lowercase(Locale.ROOT)
  val valid = name.isNotBlank() && existing.none { it.lowercase(Locale.ROOT) == normalized }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.vps_create_client)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.vps_client_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (name.isNotBlank() && !valid) Text(stringResource(R.string.vps_client_name_exists), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
    },
    confirmButton = { Button(enabled = valid && !busy, onClick = { onCreate(name.trim()) }) { Text(stringResource(R.string.action_create)) } },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun VpsHeaderCard(
  title: String,
  description: String,
  actionText: String?,
  actionEnabled: Boolean = true,
  onAction: () -> Unit,
  horizontalPadding: Dp = 12.dp,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
      }
      actionText?.let {
        FilledTonalButton(enabled = actionEnabled, onClick = onAction) { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(it) }
      }
    }
  }
}

@Composable
private fun VpsServerCard(server: VpsServer, metrics: VpsMetrics, onClick: () -> Unit, onRefresh: () -> Unit, onDelete: () -> Unit) {
  val targetAccent = when (metrics.reachability) {
    VpsReachability.ONLINE -> Color(0xFF22C55E)
    VpsReachability.OFFLINE -> MaterialTheme.colorScheme.error
    VpsReachability.CHECKING -> Color(0xFFF59E0B)
    VpsReachability.UNKNOWN -> MaterialTheme.colorScheme.outline
  }
  val accent by animateColorAsState(targetAccent, animationSpec = tween(350), label = "vpsServerAccent")
  val online = metrics.reachability == VpsReachability.ONLINE
  val hasSnapshot = metrics.osName.isNotBlank() || metrics.ramTotalBytes > 0L
  val metricAlpha = if (metrics.reachability == VpsReachability.OFFLINE && hasSnapshot) 0.62f else 1f

  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 194.dp),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.14f), MaterialTheme.colorScheme.surface.copy(alpha = 0.66f))))
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
          modifier = Modifier.weight(1f).clickable(enabled = online, onClick = onClick),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(modifier = Modifier.size(52.dp), shape = CircleShape, color = accent.copy(alpha = 0.15f), contentColor = accent, border = BorderStroke(1.dp, accent.copy(alpha = 0.38f))) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(27.dp)) }
          }
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
            Text(serverStatusText(metrics), style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.SemiBold)
          }
        }
        VpsRefreshIcon(refreshing = metrics.refreshing, onClick = onRefresh)
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
      }

      Column(modifier = Modifier.graphicsLayer(alpha = metricAlpha), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = if (hasSnapshot) "${metrics.osName.ifBlank { "—" }} · ${metrics.architecture.ifBlank { "—" }}" else "— · —",
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricPill(Icons.Outlined.Speed, "CPU ${if (hasSnapshot) formatPercent(metrics.cpuPercent) else "—"}", Modifier.weight(1f))
          MetricPill(Icons.Outlined.Memory, "RAM ${formatBytes(metrics.ramUsedBytes)} / ${formatBytes(metrics.ramTotalBytes)}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricPill(Icons.Outlined.Download, "↓ ${formatBytes(metrics.networkRxBytes)} · ↑ ${formatBytes(metrics.networkTxBytes)}", Modifier.weight(1f))
          MetricPill(Icons.Outlined.Refresh, formatUptime(metrics.uptimeSeconds), Modifier.weight(1f))
        }
      }

      Text(
        text = when {
          metrics.reachability == VpsReachability.OFFLINE -> metrics.error ?: stringResource(R.string.vps_offline)
          metrics.networkInterface.isNotBlank() -> stringResource(R.string.vps_traffic_since_boot, metrics.networkInterface, formatBytes(metrics.networkRxBytes + metrics.networkTxBytes))
          else -> stringResource(R.string.vps_traffic_since_boot_unknown, formatBytes(metrics.networkRxBytes + metrics.networkTxBytes))
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (metrics.reachability == VpsReachability.OFFLINE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun VpsServerSummaryCard(
  server: VpsServer,
  metrics: VpsMetrics,
  rebootEnabled: Boolean,
  onRefresh: () -> Unit,
  onReboot: () -> Unit,
) {
  val targetAccent = when (metrics.reachability) {
    VpsReachability.ONLINE -> Color(0xFF22C55E)
    VpsReachability.OFFLINE -> MaterialTheme.colorScheme.error
    VpsReachability.CHECKING -> Color(0xFFF59E0B)
    VpsReachability.UNKNOWN -> MaterialTheme.colorScheme.outline
  }
  val accent by animateColorAsState(targetAccent, animationSpec = tween(350), label = "vpsSummaryAccent")
  val hasSnapshot = metrics.osName.isNotBlank() || metrics.ramTotalBytes > 0L
  val metricAlpha = if (metrics.reachability == VpsReachability.OFFLINE && hasSnapshot) 0.62f else 1f

  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 216.dp), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Router, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text("${server.username}@${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall)
        }
        VpsRefreshIcon(refreshing = metrics.refreshing, onClick = onRefresh)
      }
      Text(serverStatusText(metrics), color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
      Column(modifier = Modifier.graphicsLayer(alpha = metricAlpha), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          if (hasSnapshot) "${metrics.osName.ifBlank { "—" }} · ${metrics.architecture.ifBlank { "—" }} · ${metrics.cpuCores.takeIf { it > 0 } ?: "—"} CPU" else "— · — · — CPU",
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricPill(Icons.Outlined.Speed, "CPU ${if (hasSnapshot) formatPercent(metrics.cpuPercent) else "—"}", Modifier.weight(1f))
          MetricPill(Icons.Outlined.Memory, "RAM ${formatBytes(metrics.ramUsedBytes)} / ${formatBytes(metrics.ramTotalBytes)}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricPill(Icons.Outlined.Storage, "Disk ${formatBytes(metrics.diskUsedBytes)} / ${formatBytes(metrics.diskTotalBytes)}", Modifier.weight(1f))
          MetricPill(Icons.Outlined.Refresh, formatUptime(metrics.uptimeSeconds), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricPill(Icons.Outlined.Download, "↓ ${formatBytes(metrics.networkRxBytes)}", Modifier.weight(1f))
          MetricPill(Icons.Outlined.Share, "↑ ${formatBytes(metrics.networkTxBytes)}", Modifier.weight(1f))
        }
      }
      Text(
        if (metrics.networkInterface.isNotBlank()) stringResource(R.string.vps_traffic_since_boot, metrics.networkInterface, formatBytes(metrics.networkRxBytes + metrics.networkTxBytes)) else stringResource(R.string.vps_traffic_since_boot_unknown, formatBytes(metrics.networkRxBytes + metrics.networkTxBytes)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
      )
      OutlinedButton(
        enabled = rebootEnabled,
        onClick = onReboot,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(stringResource(R.string.vps_reboot_server))
      }
    }
  }
}

@Composable
private fun VpsServiceCard(kind: VpsServiceKind, state: VpsServiceState, enabled: Boolean, onOpen: () -> Unit, onInstall: () -> Unit, onRestart: () -> Unit, onLogs: () -> Unit, onRemove: () -> Unit) {
  val accent = if (state.installed && state.active) Color(0xFF22C55E) else if (state.installed) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary
  var menu by remember { mutableStateOf(false) }
  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(enabled = state.installed && enabled, onClick = onOpen),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
  ) {
    Row(modifier = Modifier.background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.12f), MaterialTheme.colorScheme.surface.copy(alpha = 0.68f)))).padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ServiceIcon(kind, accent)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(serviceTitle(kind), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(serviceDescription(kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (!state.installed) stringResource(R.string.vps_not_installed) else if (state.active) stringResource(R.string.vps_running) else stringResource(R.string.vps_stopped), color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
      }
      if (!state.installed) {
        Button(enabled = enabled, onClick = onInstall) { Text(stringResource(R.string.vps_install)) }
      } else {
        Box {
          IconButton(enabled = enabled, onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(enabled = enabled, text = { Text(stringResource(R.string.vps_open)) }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(enabled = enabled, text = { Text(stringResource(R.string.vps_restart)) }, onClick = { menu = false; onRestart() })
            DropdownMenuItem(enabled = enabled, text = { Text(stringResource(R.string.vps_logs_title)) }, onClick = { menu = false; onLogs() })
            DropdownMenuItem(enabled = enabled, text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onRemove() })
          }
        }
      }
    }
  }
}

@Composable
private fun VpsProfileCard(
  profile: VpsServiceProfile,
  enabled: Boolean,
  onClick: () -> Unit,
  onRestart: () -> Unit,
  onLogs: () -> Unit,
  onDelete: () -> Unit,
  horizontalPadding: Dp = 12.dp,
) {
  var menu by remember { mutableStateOf(false) }
  val targetAccent = if (profile.active) Color(0xFF22C55E) else Color(0xFFF59E0B)
  val accent by animateColorAsState(targetAccent, animationSpec = tween(300), label = "vpsProfileAccent")
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, accent.copy(alpha = 0.36f)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Row(Modifier.clickable(enabled = enabled, onClick = onClick).padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      Surface(Modifier.size(46.dp), shape = CircleShape, color = accent.copy(alpha = 0.14f), contentColor = accent) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Security, contentDescription = null) } }
      Column(Modifier.weight(1f)) {
        Text(profile.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${profile.mode.uppercase(Locale.ROOT)} · ${profile.protocol.uppercase(Locale.ROOT)} ${profile.port}", style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.vps_clients_count, profile.clientCount), style = MaterialTheme.typography.labelMedium, color = accent)
        Text(
          stringResource(R.string.vps_traffic_values, formatTrafficBytes(profile.uploadBytes), formatTrafficBytes(profile.downloadBytes)),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
        )
      }
      Box {
        IconButton(enabled = enabled, onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
          DropdownMenuItem(enabled = enabled, text = { Text(stringResource(R.string.vps_restart)) }, onClick = { menu = false; onRestart() })
          DropdownMenuItem(enabled = enabled, text = { Text(stringResource(R.string.vps_logs_title)) }, onClick = { menu = false; onLogs() })
          DropdownMenuItem(enabled = enabled, text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
        }
      }
    }
  }
}

@Composable
private fun VpsClientCard(
  client: VpsClientConfig,
  enabled: Boolean,
  onGenerate: () -> Unit,
  onDelete: () -> Unit,
  horizontalPadding: Dp = 12.dp,
) {
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))) {
    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Surface(Modifier.size(43.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), contentColor = MaterialTheme.colorScheme.primary) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Key, contentDescription = null) } }
      Column(Modifier.weight(1f)) {
        Text(client.name, fontWeight = FontWeight.Bold)
        if (client.createdAt > 0) Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(client.createdAt * 1000)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Text(
          stringResource(R.string.vps_traffic_values, formatTrafficBytes(client.uploadBytes), formatTrafficBytes(client.downloadBytes)),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
      }
      FilledTonalButton(enabled = enabled, onClick = onGenerate) { Text(stringResource(R.string.vps_get_config)) }
      IconButton(enabled = enabled, onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
    }
  }
}

@Composable
private fun ManagedDnscryptCard(
  state: VpsServiceState?,
  enabled: Boolean,
  onRestart: () -> Unit,
  onLogs: () -> Unit,
  horizontalPadding: Dp = 12.dp,
) {
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(9.dp)); Text(stringResource(R.string.vps_dnscrypt_summary), fontWeight = FontWeight.Bold) }
      Text(stringResource(R.string.vps_dnscrypt_behavior), style = MaterialTheme.typography.bodySmall)
      Text(if (state?.active == true) stringResource(R.string.vps_running) else stringResource(R.string.vps_stopped), color = if (state?.active == true) Color(0xFF22C55E) else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = enabled, onClick = onRestart, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.vps_restart)) }
        OutlinedButton(enabled = enabled, onClick = onLogs, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.vps_logs_title)) }
      }
    }
  }
}

@Composable
private fun VpsConfigResultDialog(result: VpsConfigResult, onDismiss: () -> Unit, onCopy: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onOpenExternal: () -> Unit, onImport: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.vps_config_ready)) },
    text = {
      Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(result.fileName, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
          Text(result.shareLink ?: result.content, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Download, contentDescription = null); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.vps_import_zdtd)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Save, contentDescription = null); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.action_save)) }
          OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ContentCopy, contentDescription = null); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.action_copy)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = onOpenExternal, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.OpenInNew, contentDescription = null); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.vps_open_external)) }
          OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Share, contentDescription = null); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.action_share)) }
        }
        Text(stringResource(R.string.vps_reuse_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
  )
}

@Composable
private fun VpsOperationDialog(operation: VpsOperationState, onDismiss: () -> Unit) {
  if (!operation.running && operation.error == null && operation.log.isEmpty()) return
  val context = LocalContext.current
  var showFullLog by remember(operation.title, operation.running) { mutableStateOf(false) }
  val visibleLog = if (showFullLog) operation.log else operation.log.takeLast(12)
  AlertDialog(
    onDismissRequest = { if (!operation.running) onDismiss() },
    icon = {
      if (operation.running) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
      else Icon(if (operation.error == null) Icons.Outlined.Security else Icons.Outlined.ErrorOutline, contentDescription = null)
    },
    title = { Text(operation.title.ifBlank { stringResource(R.string.vps_operation_title) }) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (operation.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(operation.stage, fontWeight = FontWeight.SemiBold)
        operation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (operation.rolledBack) Text(stringResource(R.string.vps_rollback_completed), color = Color(0xFF22C55E), fontWeight = FontWeight.SemiBold)
        if (operation.log.isNotEmpty()) {
          Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
            Text(
              visibleLog.joinToString("\n"),
              modifier = Modifier.padding(10.dp).heightIn(max = if (showFullLog) 380.dp else 190.dp).verticalScroll(rememberScrollState()),
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showFullLog = !showFullLog }) {
              Text(stringResource(if (showFullLog) R.string.vps_hide_full_log else R.string.vps_show_full_log))
            }
            TextButton(onClick = {
              (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("VPS log", operation.log.joinToString("\n")))
              Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            }) {
              Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
              Spacer(Modifier.width(5.dp))
              Text(stringResource(R.string.action_copy))
            }
          }
        }
      }
    },
    confirmButton = { if (!operation.running) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
  )
}

@Composable
private fun VpsTextDialog(title: String, text: String, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text, modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
  )
}

private fun combineLoadStates(first: VpsLoadState, second: VpsLoadState): VpsLoadState {
  return VpsLoadState(
    loaded = first.loaded && second.loaded,
    loading = first.loading || second.loading || !first.loaded || !second.loaded,
    error = first.error ?: second.error,
  )
}

@Composable
private fun VpsLoadTransition(
  state: VpsLoadState,
  loadingText: String,
  onRetry: () -> Unit,
  content: @Composable () -> Unit,
) {
  val phase = when {
    state.error != null -> "error"
    !state.loaded -> "loading"
    else -> "content"
  }
  Crossfade(
    targetState = phase,
    animationSpec = tween(durationMillis = 260),
    label = "vpsContentLoad",
  ) { target ->
    when (target) {
      "loading" -> VpsInitialLoadingCard(loadingText)
      "error" -> VpsLoadErrorCard(state.error.orEmpty(), onRetry)
      else -> content()
    }
  }
}

@Composable
private fun VpsInitialLoadingCard(text: String) {
  val transition = rememberInfiniteTransition(label = "vpsInitialLoading")
  val pulse by transition.animateFloat(
    initialValue = 0.42f,
    targetValue = 0.92f,
    animationSpec = infiniteRepeatable(
      animation = tween(820, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "vpsLoadingPulse",
  )
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 172.dp),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
      CircularProgressIndicator(
        modifier = Modifier.size(34.dp),
        strokeWidth = 3.dp,
      )
      Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Column(
        modifier = Modifier.fillMaxWidth().graphicsLayer(alpha = pulse),
        verticalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        repeat(3) { index ->
          Surface(
            modifier = Modifier
              .fillMaxWidth(if (index == 1) 0.82f else 1f)
              .height(if (index == 0) 13.dp else 10.dp),
            shape = RoundedCornerShape(100.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
          ) {}
        }
      }
    }
  }
}

@Composable
private fun VpsLoadErrorCard(error: String, onRetry: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.32f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(9.dp))
        Text(
          stringResource(R.string.vps_load_failed),
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
      Text(
        error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      OutlinedButton(onClick = onRetry) {
        Icon(Icons.Outlined.Refresh, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.common_retry))
      }
    }
  }
}

@Composable
private fun VpsEmptyState(title: String, hint: String, horizontalPadding: Dp = 12.dp) {
  Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))) {
    Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
      Text(title, fontWeight = FontWeight.Bold)
      if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
    }
  }
}

@Composable
private fun WarningCard(text: String) {
  Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.64f)) {
    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(9.dp)); Text(text, style = MaterialTheme.typography.bodySmall) }
  }
}

@Composable
private fun VpsRefreshIcon(refreshing: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
  val transition = rememberInfiniteTransition(label = "vpsRefresh")
  val rotation by transition.animateFloat(
    initialValue = 0f,
    targetValue = if (refreshing) 360f else 0f,
    animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Restart),
    label = "vpsRefreshRotation",
  )
  IconButton(enabled = enabled, onClick = onClick) {
    Icon(
      Icons.Outlined.Refresh,
      contentDescription = stringResource(R.string.action_refresh),
      modifier = Modifier.graphicsLayer(rotationZ = if (refreshing) rotation else 0f),
    )
  }
}

@Composable
private fun MetricPill(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
  Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)) {
    Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
  }
}

@Composable
private fun ServiceIcon(kind: VpsServiceKind, accent: Color) {
  val drawable = when (kind) {
    VpsServiceKind.OPENVPN -> R.drawable.ic_tool_openvpn
    VpsServiceKind.XRAY -> R.drawable.ic_tool_sing_box
    VpsServiceKind.HYSTERIA2 -> R.drawable.ic_tool_hysteria2
    VpsServiceKind.WIREPROXY -> R.drawable.ic_tool_wireproxy
    VpsServiceKind.DNSCRYPT -> null
  }
  Surface(modifier = Modifier.size(54.dp), shape = CircleShape, color = accent.copy(alpha = 0.13f), contentColor = accent, border = BorderStroke(1.dp, accent.copy(alpha = 0.32f))) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      if (drawable != null) Icon(painter = painterResource(drawable), contentDescription = null, modifier = Modifier.size(31.dp))
      else Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(28.dp))
    }
  }
}

@Composable
private fun serviceTitle(kind: VpsServiceKind): String = when (kind) {
  VpsServiceKind.DNSCRYPT -> stringResource(R.string.vps_service_dnscrypt)
  VpsServiceKind.OPENVPN -> stringResource(R.string.vps_service_openvpn)
  VpsServiceKind.XRAY -> stringResource(R.string.vps_service_xray)
  VpsServiceKind.HYSTERIA2 -> stringResource(R.string.vps_service_hysteria2)
  VpsServiceKind.WIREPROXY -> stringResource(R.string.vps_service_wireproxy)
}

@Composable
private fun serviceDescription(kind: VpsServiceKind): String = when (kind) {
  VpsServiceKind.DNSCRYPT -> stringResource(R.string.vps_service_dnscrypt_desc)
  VpsServiceKind.OPENVPN -> stringResource(R.string.vps_service_openvpn_desc)
  VpsServiceKind.XRAY -> stringResource(R.string.vps_service_xray_desc)
  VpsServiceKind.HYSTERIA2 -> stringResource(R.string.vps_service_hysteria2_desc)
  VpsServiceKind.WIREPROXY -> stringResource(R.string.vps_service_wireproxy_desc)
}

@Composable
private fun serverStatusText(metrics: VpsMetrics): String = when (metrics.reachability) {
  VpsReachability.ONLINE -> stringResource(R.string.vps_online)
  VpsReachability.OFFLINE -> stringResource(R.string.vps_offline)
  VpsReachability.CHECKING -> stringResource(R.string.vps_checking)
  VpsReachability.UNKNOWN -> stringResource(R.string.vps_not_checked)
}

private fun formatBytes(value: Long): String {
  if (value <= 0L) return "—"
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  var v = value.toDouble(); var i = 0
  while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
  return if (i <= 1) String.format(Locale.US, "%.0f %s", v, units[i]) else String.format(Locale.US, "%.1f %s", v, units[i])
}
private fun formatTrafficBytes(value: Long): String = if (value <= 0L) "0 B" else formatBytes(value)
private fun formatPercent(value: Double) = String.format(Locale.US, "%.1f%%", value.coerceIn(0.0, 100.0))
private fun formatUptime(seconds: Long): String {
  if (seconds <= 0) return "—"
  val days = seconds / 86400; val hours = (seconds % 86400) / 3600
  return if (days > 0) "${days}d ${hours}h" else "${hours}h"
}

private fun shareConfig(context: Context, result: VpsConfigResult) {
  if (result.shareLink != null) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, result.shareLink) }, context.getString(R.string.action_share)))
  } else {
    val file = writeTempConfig(context, result)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = result.mimeType; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, context.getString(R.string.action_share)))
  }
}

private fun openConfigExternally(context: Context, result: VpsConfigResult) {
  val intent = if (result.shareLink != null) Intent(Intent.ACTION_VIEW, Uri.parse(result.shareLink)) else {
    val file = writeTempConfig(context, result)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    Intent(Intent.ACTION_VIEW).setDataAndType(uri, result.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.vps_open_external))) }
    .onFailure { shareConfig(context, result) }
}

private fun writeTempConfig(context: Context, result: VpsConfigResult): File {
  val dir = File(context.cacheDir, "vps-share").apply { mkdirs() }
  return File(dir, result.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeText(result.content) }
}

private fun importConfigIntoZdtd(
  context: Context,
  actions: ZdtdActions,
  server: VpsServer?,
  profile: VpsServiceProfile?,
  result: VpsConfigResult,
  onDone: (String) -> Unit,
) {
  val base = listOfNotNull(server?.name, profile?.name, result.clientName).joinToString("-")
  val profileName = normalizeLocalProfileName(base)
  when (result.kind) {
    VpsServiceKind.OPENVPN -> {
      val temp = writeTempConfig(context, result)
      actions.createNamedProfile("openvpn", profileName) { created ->
        if (created == null) return@createNamedProfile onDone(context.getString(R.string.create_failed))
        actions.uploadOpenVpnConfig(created, "client.ovpn", temp) { ok -> onDone(context.getString(if (ok) R.string.vps_import_success else R.string.vps_import_failed)) }
      }
    }
    VpsServiceKind.WIREPROXY -> {
      actions.createNamedProfile("wireproxy", profileName) { created ->
        if (created == null) return@createNamedProfile onDone(context.getString(R.string.create_failed))
        val serverName = "server"
        actions.createWireProxyServer(created, serverName) { createdServer ->
          if (createdServer == null) return@createWireProxyServer onDone(context.getString(R.string.vps_import_failed))
          val path = "/api/programs/wireproxy/profiles/${url(created)}/servers/${url(createdServer)}/config"
          actions.saveText(path, result.content) { ok -> onDone(context.getString(if (ok) R.string.vps_import_success else R.string.vps_import_failed)) }
        }
      }
    }
    VpsServiceKind.XRAY -> {
      val link = result.shareLink ?: return onDone(context.getString(R.string.vps_import_failed))
      val imported = runCatching { SingBoxOneLineImporter.import(link, 12345) }.getOrElse { return onDone(it.message ?: context.getString(R.string.vps_import_failed)) }
      actions.createNamedProfile("sing-box", profileName) { created ->
        if (created == null) return@createNamedProfile onDone(context.getString(R.string.create_failed))
        actions.createSingBoxServer(created, "server") { serverName ->
          if (serverName == null) return@createSingBoxServer onDone(context.getString(R.string.vps_import_failed))
          val serverBase = "/api/programs/sing-box/profiles/${url(created)}/servers/${url(serverName)}"
          actions.saveText("$serverBase/config", imported.configJson) { configOk ->
            if (!configOk || result.sniOptions.isEmpty()) {
              onDone(context.getString(if (configOk) R.string.vps_import_success else R.string.vps_import_failed))
              return@saveText
            }
            actions.loadJsonData("$serverBase/setting") { current ->
              val setting = current ?: JSONObject()
              setting.put("sni", result.sniOptions.first())
              setting.put("sni_options", JSONArray().also { array -> result.sniOptions.forEach { option -> array.put(option) } })
              actions.saveJsonData("$serverBase/setting", setting) { settingOk ->
                onDone(context.getString(if (settingOk) R.string.vps_import_success else R.string.vps_import_failed))
              }
            }
          }
        }
      }
    }
    VpsServiceKind.HYSTERIA2 -> {
      actions.createNamedProfile("hysteria2", profileName) { created ->
        if (created == null) return@createNamedProfile onDone(context.getString(R.string.create_failed))
        actions.createHysteria2Server(created, "server") { serverName ->
          if (serverName == null) return@createHysteria2Server onDone(context.getString(R.string.vps_import_failed))
          val path = "/api/programs/hysteria2/profiles/${url(created)}/servers/${url(serverName)}/config"
          actions.saveText(path, result.content) { ok -> onDone(context.getString(if (ok) R.string.vps_import_success else R.string.vps_import_failed)) }
        }
      }
    }
    VpsServiceKind.DNSCRYPT -> onDone(context.getString(R.string.vps_import_failed))
  }
}

private fun normalizeLocalProfileName(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]+"), "_").trim('_', '.', '-').take(24).ifBlank { "vps_profile" }
private fun url(value: String) = URLEncoder.encode(value, "UTF-8")
