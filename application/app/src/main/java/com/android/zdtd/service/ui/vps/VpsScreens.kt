package com.android.zdtd.service.ui.vps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.singbox.importer.SingBoxOneLineImporter
import com.android.zdtd.service.vps.VpsClientConfig
import com.android.zdtd.service.vps.VpsConfigResult
import com.android.zdtd.service.vps.VpsMetrics
import com.android.zdtd.service.vps.VpsOperationState
import com.android.zdtd.service.vps.VpsReachability
import com.android.zdtd.service.vps.VpsServer
import com.android.zdtd.service.vps.VpsServiceKind
import com.android.zdtd.service.vps.VpsServiceProfile
import com.android.zdtd.service.vps.VpsServiceState
import com.android.zdtd.service.vps.VpsViewModel
import java.io.File
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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

  DisposableEffect(Unit) {
    viewModel.startListMonitoring()
    onDispose { viewModel.stopListMonitoring() }
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
  val operation by viewModel.operation.collectAsState()
  var removeTarget by remember { mutableStateOf<VpsServiceKind?>(null) }
  var logs by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(serverId) {
    viewModel.refreshServer(serverId)
    viewModel.loadServices(serverId)
  }

  VpsOperationDialog(operation = operation, onDismiss = viewModel::clearOperation)
  logs?.let { text -> VpsTextDialog(stringResource(R.string.vps_logs_title), text, onDismiss = { logs = null }) }
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

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = topContentPadding + 8.dp, bottom = bottomContentPadding + 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item { VpsServerSummaryCard(server, currentMetrics, onRefresh = { viewModel.refreshServer(serverId); viewModel.loadServices(serverId) }) }
    item {
      Text(
        text = stringResource(R.string.vps_services_title),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
    }
    items(VpsServiceKind.entries, key = { it.wireId }) { kind ->
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
  val operation by viewModel.operation.collectAsState()
  val profiles = profilesMap[VpsViewModel.profileKey(serverId, kind)].orEmpty()
  val state = services[serverId].orEmpty().firstOrNull { it.kind == kind }
  var showCreate by remember { mutableStateOf(false) }
  var deleteTarget by remember { mutableStateOf<VpsServiceProfile?>(null) }
  var logs by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(serverId, kind) {
    viewModel.loadServices(serverId)
    if (kind != VpsServiceKind.DNSCRYPT) viewModel.loadProfiles(serverId, kind)
    if (kind == VpsServiceKind.HYSTERIA2) viewModel.loadProfiles(serverId, VpsServiceKind.XRAY)
  }

  VpsOperationDialog(operation = operation, onDismiss = viewModel::clearOperation)
  logs?.let { VpsTextDialog(stringResource(R.string.vps_logs_title), it, onDismiss = { logs = null }) }

  if (showCreate) {
    CreateVpsProfileDialog(
      kind = kind,
      suggestedHysteriaPort = profilesMap[VpsViewModel.profileKey(serverId, VpsServiceKind.XRAY)]?.firstOrNull()?.port,
      busy = operation.running,
      onDismiss = { showCreate = false },
      onCreate = { name, port, mode, domain, email, sni ->
        showCreate = false
        viewModel.createProfile(serverId, kind, name, port, mode, domain, email, sni)
      },
    )
  }

  deleteTarget?.let { profile ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text(stringResource(R.string.vps_delete_profile_title)) },
      text = { Text(stringResource(R.string.vps_delete_profile_message, profile.name)) },
      confirmButton = {
        Button(onClick = { viewModel.deleteProfile(serverId, kind, profile.id); deleteTarget = null }) { Text(stringResource(R.string.action_delete)) }
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
        onAction = { showCreate = true },
      )
    }
    if (kind == VpsServiceKind.DNSCRYPT) {
      item {
        ManagedDnscryptCard(
          state = state,
          onRestart = { viewModel.restartService(serverId, kind) },
          onLogs = { viewModel.loadLogs(serverId, kind, null) { logs = it } },
        )
      }
    } else if (profiles.isEmpty()) {
      item { VpsEmptyState(stringResource(R.string.vps_no_profiles), stringResource(R.string.vps_no_profiles_hint)) }
    } else {
      items(profiles, key = { it.id }) { profile ->
        VpsProfileCard(
          profile = profile,
          onClick = { onOpenProfile(profile.id) },
          onRestart = { viewModel.restartService(serverId, kind, profile.id) },
          onLogs = { viewModel.loadLogs(serverId, kind, profile.id) { logs = it } },
          onDelete = { deleteTarget = profile },
        )
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
  val operation by viewModel.operation.collectAsState()
  val configResult by viewModel.configResult.collectAsState()
  val profile = profilesMap[VpsViewModel.profileKey(serverId, kind)].orEmpty().firstOrNull { it.id == profileId }
  val clients = clientsMap[VpsViewModel.clientKey(serverId, kind, profileId)].orEmpty()
  var showCreateClient by remember { mutableStateOf(false) }
  var deleteTarget by remember { mutableStateOf<VpsClientConfig?>(null) }
  var snack by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(serverId, kind, profileId) {
    viewModel.clearConfigResult()
    viewModel.loadProfiles(serverId, kind)
    viewModel.loadClients(serverId, kind, profileId)
  }

  VpsOperationDialog(operation = operation, onDismiss = viewModel::clearOperation)

  if (showCreateClient) {
    CreateClientDialog(
      existing = clients.map { it.name }.toSet(),
      busy = operation.running,
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
        Button(onClick = { viewModel.deleteClient(serverId, kind, profileId, client.id); deleteTarget = null }) { Text(stringResource(R.string.vps_revoke_access)) }
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
        snack = context.getString(R.string.copied)
      },
      onSave = {
        val serverName = server?.name ?: "server"
        viewModel.saveConfigToSharedStorage(serverName, result) { saved ->
          snack = saved.fold(
            onSuccess = { context.getString(R.string.vps_saved_to_path, it) },
            onFailure = { it.message ?: context.getString(R.string.save_failed) },
          )
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
      VpsHeaderCard(
        title = profile?.name ?: profileId,
        description = profile?.let { "${it.mode.uppercase(Locale.ROOT)} · ${it.protocol.uppercase(Locale.ROOT)} ${it.port}" }.orEmpty(),
        actionText = stringResource(R.string.vps_create_client),
        onAction = { showCreateClient = true },
      )
    }
    if (clients.isEmpty()) {
      item { VpsEmptyState(stringResource(R.string.vps_no_clients), stringResource(R.string.vps_no_clients_hint)) }
    } else {
      items(clients, key = { it.id }) { client ->
        VpsClientCard(
          client = client,
          onGenerate = { viewModel.requestConfig(serverId, kind, profileId, client.id) },
          onDelete = { deleteTarget = client },
        )
      }
    }
  }
}

@Composable
private fun AddVpsServerDialog(
  busy: Boolean,
  onDismiss: () -> Unit,
  onSubmit: (String, String, Int, String, String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("22") }
  var username by remember { mutableStateOf("root") }
  var password by remember { mutableStateOf("") }
  val valid = host.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && port.toIntOrNull() in 1..65535
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
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.vps_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.vps_password_storage_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
      }
    },
    confirmButton = {
      Button(enabled = valid && !busy, onClick = { onSubmit(name, host, port.toInt(), username, password) }) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.vps_check_and_add))
      }
    },
    dismissButton = { OutlinedButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun CreateVpsProfileDialog(
  kind: VpsServiceKind,
  suggestedHysteriaPort: Int?,
  busy: Boolean,
  onDismiss: () -> Unit,
  onCreate: (String, Int, String, String, String, String) -> Unit,
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
  var sni by remember { mutableStateOf("www.microsoft.com") }
  var menu by remember { mutableStateOf(false) }
  val requiresTls = kind == VpsServiceKind.HYSTERIA2 || (kind == VpsServiceKind.XRAY && mode == "ws")
  val valid = name.isNotBlank() && port.toIntOrNull() in 1..65535 && (!requiresTls || (domain.isNotBlank() && email.isNotBlank())) && !(kind == VpsServiceKind.XRAY && mode == "reality" && sni.isBlank())
  val modes = when (kind) {
    VpsServiceKind.OPENVPN -> listOf("udp", "tcp")
    VpsServiceKind.XRAY -> listOf("reality", "ws")
    else -> listOf(mode)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.vps_create_profile)) },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
          OutlinedTextField(sni, { sni = it.trim() }, label = { Text(stringResource(R.string.vps_sni)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (requiresTls) {
          OutlinedTextField(domain, { domain = it.trim() }, label = { Text(stringResource(R.string.vps_domain)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(email, { email = it.trim() }, label = { Text(stringResource(R.string.vps_letsencrypt_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          Text(stringResource(R.string.vps_automatic_tls_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (kind == VpsServiceKind.HYSTERIA2 && suggestedHysteriaPort != null) {
          Text(stringResource(R.string.vps_shared_port_hint, suggestedHysteriaPort), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
      }
    },
    confirmButton = {
      Button(enabled = valid && !busy, onClick = { onCreate(name, port.toInt(), mode, domain, email, sni) }) { Text(stringResource(R.string.action_create)) }
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
private fun VpsHeaderCard(title: String, description: String, actionText: String?, onAction: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
        FilledTonalButton(onClick = onAction) { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(it) }
      }
    }
  }
}

@Composable
private fun VpsServerCard(server: VpsServer, metrics: VpsMetrics, onClick: () -> Unit, onRefresh: () -> Unit, onDelete: () -> Unit) {
  val accent = when (metrics.reachability) {
    VpsReachability.ONLINE -> Color(0xFF22C55E)
    VpsReachability.OFFLINE -> MaterialTheme.colorScheme.error
    VpsReachability.CHECKING -> Color(0xFFF59E0B)
    VpsReachability.UNKNOWN -> MaterialTheme.colorScheme.outline
  }
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
  ) {
    Column(modifier = Modifier.background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.14f), MaterialTheme.colorScheme.surface.copy(alpha = 0.66f)))).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(52.dp), shape = CircleShape, color = accent.copy(alpha = 0.15f), contentColor = accent, border = BorderStroke(1.dp, accent.copy(alpha = 0.38f))) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(27.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
          Text(serverStatusText(metrics), style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
      }
      if (metrics.reachability == VpsReachability.ONLINE) {
        Text("${metrics.osName} · ${metrics.architecture}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricPill(Icons.Outlined.Speed, "CPU ${formatPercent(metrics.cpuPercent)}", Modifier.weight(1f))
          MetricPill(Icons.Outlined.Memory, "RAM ${formatBytes(metrics.ramUsedBytes)} / ${formatBytes(metrics.ramTotalBytes)}", Modifier.weight(1f))
        }
      } else if (!metrics.error.isNullOrBlank()) {
        Text(metrics.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun VpsServerSummaryCard(server: VpsServer, metrics: VpsMetrics, onRefresh: () -> Unit) {
  val online = metrics.reachability == VpsReachability.ONLINE
  val accent = if (online) Color(0xFF22C55E) else MaterialTheme.colorScheme.error
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Router, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text("${server.username}@${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh)) }
      }
      Text(serverStatusText(metrics), color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
      if (online) {
        Text("${metrics.osName} · ${metrics.architecture} · ${metrics.cpuCores} CPU", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricPill(Icons.Outlined.Speed, "CPU ${formatPercent(metrics.cpuPercent)}", Modifier.weight(1f))
          MetricPill(Icons.Outlined.Memory, "RAM ${formatBytes(metrics.ramUsedBytes)}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MetricPill(Icons.Outlined.Storage, "Disk ${formatBytes(metrics.diskUsedBytes)} / ${formatBytes(metrics.diskTotalBytes)}", Modifier.weight(1f))
          MetricPill(Icons.Outlined.Refresh, formatUptime(metrics.uptimeSeconds), Modifier.weight(1f))
        }
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
private fun VpsProfileCard(profile: VpsServiceProfile, onClick: () -> Unit, onRestart: () -> Unit, onLogs: () -> Unit, onDelete: () -> Unit) {
  var menu by remember { mutableStateOf(false) }
  val accent = if (profile.active) Color(0xFF22C55E) else Color(0xFFF59E0B)
  Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, accent.copy(alpha = 0.36f)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
      Surface(Modifier.size(46.dp), shape = CircleShape, color = accent.copy(alpha = 0.14f), contentColor = accent) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Security, contentDescription = null) } }
      Column(Modifier.weight(1f)) {
        Text(profile.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${profile.mode.uppercase(Locale.ROOT)} · ${profile.protocol.uppercase(Locale.ROOT)} ${profile.port}", style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.vps_clients_count, profile.clientCount), style = MaterialTheme.typography.labelMedium, color = accent)
      }
      Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
          DropdownMenuItem(text = { Text(stringResource(R.string.vps_restart)) }, onClick = { menu = false; onRestart() })
          DropdownMenuItem(text = { Text(stringResource(R.string.vps_logs_title)) }, onClick = { menu = false; onLogs() })
          DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
        }
      }
    }
  }
}

@Composable
private fun VpsClientCard(client: VpsClientConfig, onGenerate: () -> Unit, onDelete: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))) {
    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Surface(Modifier.size(43.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), contentColor = MaterialTheme.colorScheme.primary) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Key, contentDescription = null) } }
      Column(Modifier.weight(1f)) {
        Text(client.name, fontWeight = FontWeight.Bold)
        if (client.createdAt > 0) Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(client.createdAt * 1000)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      }
      FilledTonalButton(onClick = onGenerate) { Text(stringResource(R.string.vps_get_config)) }
      IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
    }
  }
}

@Composable
private fun ManagedDnscryptCard(state: VpsServiceState?, onRestart: () -> Unit, onLogs: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))) {
    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(9.dp)); Text(stringResource(R.string.vps_dnscrypt_summary), fontWeight = FontWeight.Bold) }
      Text(stringResource(R.string.vps_dnscrypt_behavior), style = MaterialTheme.typography.bodySmall)
      Text(if (state?.active == true) stringResource(R.string.vps_running) else stringResource(R.string.vps_stopped), color = if (state?.active == true) Color(0xFF22C55E) else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRestart, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.vps_restart)) }
        OutlinedButton(onClick = onLogs, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.vps_logs_title)) }
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
            Text(operation.log.takeLast(12).joinToString("\n"), modifier = Modifier.padding(10.dp).heightIn(max = 190.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun VpsEmptyState(title: String, hint: String) {
  Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))) {
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
      if (drawable != null) androidx.compose.foundation.Image(painterResource(drawable), contentDescription = null, modifier = Modifier.size(36.dp))
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
          val path = "/api/programs/sing-box/profiles/${url(created)}/servers/${url(serverName)}/config"
          actions.saveText(path, imported.configJson) { ok -> onDone(context.getString(if (ok) R.string.vps_import_success else R.string.vps_import_failed)) }
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
