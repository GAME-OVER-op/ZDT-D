package com.android.zdtd.service.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

internal data class MihomoSubscriptionStatusUi(
  val lastUpdatedAt: Long = 0L,
  val nextUpdateAt: Long = 0L,
  val serverCount: Int = 0,
  val contentBytes: Long = 0L,
  val lastError: String = "",
  val remoteTitle: String = "",
  val remoteIntervalMinutes: Long? = null,
  val upload: Long? = null,
  val download: Long? = null,
  val total: Long? = null,
  val expire: Long? = null,
  val webPageUrl: String = "",
  val supportUrl: String = "",
)

internal data class MihomoSubscriptionItemUi(
  val id: String,
  val name: String,
  val url: String,
  val enabled: Boolean,
  val basicEnabled: Boolean,
  val hwidEnabled: Boolean,
  val hwidMode: String,
  val userAgent: String,
  val updateIntervalMinutes: Long,
  val useRemoteInterval: Boolean,
  val refreshing: Boolean,
  val profiles: List<String>,
  val status: MihomoSubscriptionStatusUi,
)

private data class SubscriptionNodeUi(
  val id: String,
  val name: String,
  val protocol: String,
  val server: String,
  val port: Int,
  val targets: List<String>,
)

private data class SubscriptionLinkUi(
  val id: String,
  val nodeId: String,
  val target: String,
  val profile: String,
  val serverName: String,
  val missing: Boolean,
)

private data class SubscriptionNodesUi(
  val nodes: List<SubscriptionNodeUi> = emptyList(),
  val links: List<SubscriptionLinkUi> = emptyList(),
)

private data class SubscriptionImportDraft(
  val subscriptionId: String,
  val node: SubscriptionNodeUi,
  val target: String,
  val profiles: List<String> = emptyList(),
  val selectedProfile: String = "",
  val serverName: String = "",
  val loadingProfiles: Boolean = true,
)

private data class MihomoSubscriptionDraft(
  val id: String? = null,
  val name: String = "",
  val url: String = "",
  val enabled: Boolean = true,
  val basicEnabled: Boolean = false,
  val basicUsername: String = "",
  val basicPassword: String = "",
  val hwidEnabled: Boolean = false,
  val hwid: String = "",
  val hwidMode: String = "header",
  val userAgent: String = "",
  val sendDeviceHeaders: Boolean = true,
  val deviceLocale: String = Locale.getDefault().language,
  val deviceOs: String = "Android",
  val osVersion: String = Build.VERSION.RELEASE.orEmpty(),
  val deviceModel: String = Build.MODEL.orEmpty(),
  val customHeadersText: String = "",
  val useRemoteInterval: Boolean = true,
  val updateIntervalMinutes: String = "60",
)

private fun parseStatus(obj: JSONObject?): MihomoSubscriptionStatusUi = MihomoSubscriptionStatusUi(
  lastUpdatedAt = obj?.optLong("last_updated_at", 0L) ?: 0L,
  nextUpdateAt = obj?.optLong("next_update_at", 0L) ?: 0L,
  serverCount = obj?.optInt("server_count", 0) ?: 0,
  contentBytes = obj?.optLong("content_bytes", 0L) ?: 0L,
  lastError = obj?.optString("last_error", "").orEmpty(),
  remoteTitle = obj?.optString("remote_title", "").orEmpty(),
  remoteIntervalMinutes = obj?.takeIf { it.has("remote_interval_minutes") && !it.isNull("remote_interval_minutes") }?.optLong("remote_interval_minutes"),
  upload = obj?.takeIf { it.has("upload") && !it.isNull("upload") }?.optLong("upload"),
  download = obj?.takeIf { it.has("download") && !it.isNull("download") }?.optLong("download"),
  total = obj?.takeIf { it.has("total") && !it.isNull("total") }?.optLong("total"),
  expire = obj?.takeIf { it.has("expire") && !it.isNull("expire") }?.optLong("expire"),
  webPageUrl = obj?.optString("web_page_url", "").orEmpty(),
  supportUrl = obj?.optString("support_url", "").orEmpty(),
)

private fun parseSubscriptionItem(obj: JSONObject): MihomoSubscriptionItemUi {
  val profilesArray = obj.optJSONArray("profiles") ?: JSONArray()
  val profiles = (0 until profilesArray.length()).mapNotNull { index -> profilesArray.optString(index).takeIf { it.isNotBlank() } }
  return MihomoSubscriptionItemUi(
    id = obj.optString("id"),
    name = obj.optString("name"),
    url = obj.optString("url"),
    enabled = obj.optBoolean("enabled", true),
    basicEnabled = obj.optBoolean("basic_enabled", false),
    hwidEnabled = obj.optBoolean("hwid_enabled", false),
    hwidMode = obj.optString("hwid_mode", "header"),
    userAgent = obj.optString("user_agent", ""),
    updateIntervalMinutes = obj.optLong("update_interval_minutes", 60L),
    useRemoteInterval = obj.optBoolean("use_remote_interval", true),
    refreshing = obj.optBoolean("refreshing", false),
    profiles = profiles,
    status = parseStatus(obj.optJSONObject("status")),
  )
}

private fun parseStringArray(array: JSONArray?): List<String> = if (array == null) emptyList() else
  (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }

private suspend fun loadSubscriptionNodes(actions: ZdtdActions, id: String): SubscriptionNodesUi? =
  suspendCancellableCoroutine { cont ->
    actions.loadJsonData("/api/subscriptions/${URLEncoder.encode(id, "UTF-8")}/nodes") { obj ->
      val nodesArray = obj?.optJSONArray("nodes")
      val importsArray = obj?.optJSONArray("imports")
      val result = if (nodesArray == null) null else SubscriptionNodesUi(
        nodes = (0 until nodesArray.length()).mapNotNull { index -> nodesArray.optJSONObject(index) }.map { node ->
          SubscriptionNodeUi(
            id = node.optString("id"),
            name = node.optString("name"),
            protocol = node.optString("protocol"),
            server = node.optString("server"),
            port = node.optInt("port"),
            targets = parseStringArray(node.optJSONArray("targets")),
          )
        },
        links = if (importsArray == null) emptyList() else (0 until importsArray.length()).mapNotNull { index -> importsArray.optJSONObject(index) }.map { link ->
          SubscriptionLinkUi(
            id = link.optString("id"),
            nodeId = link.optString("node_id"),
            target = link.optString("target"),
            profile = link.optString("profile"),
            serverName = link.optString("server_name"),
            missing = link.optBoolean("missing"),
          )
        },
      )
      if (cont.isActive) cont.resume(result)
    }
  }

private suspend fun loadTargetProfiles(actions: ZdtdActions, target: String): List<String> =
  suspendCancellableCoroutine { cont ->
    actions.loadJsonData("/api/programs/${URLEncoder.encode(target, "UTF-8")}/profiles") { obj ->
      val array = obj?.optJSONArray("profiles")
      val profiles = if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
      }
      if (cont.isActive) cont.resume(profiles)
    }
  }

internal suspend fun loadMihomoSubscriptionItems(actions: ZdtdActions): List<MihomoSubscriptionItemUi>? =
  suspendCancellableCoroutine { cont ->
    actions.loadJsonData("/api/subscriptions") { obj ->
      val arr = obj?.optJSONArray("items")
      val result = if (arr == null) null else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseSubscriptionItem) }
      if (cont.isActive) cont.resume(result)
    }
  }

private suspend fun loadMihomoSubscriptionDraft(actions: ZdtdActions, id: String): MihomoSubscriptionDraft? =
  suspendCancellableCoroutine { cont ->
    actions.loadJsonData("/api/subscriptions/${URLEncoder.encode(id, "UTF-8")}") { obj ->
      val s = obj?.optJSONObject("subscription")
      val headers = s?.optJSONObject("custom_headers")
      val headerText = if (headers == null) "" else headers.keys().asSequence().map { key -> "$key: ${headers.optString(key)}" }.joinToString("\n")
      val draft = s?.let {
        MihomoSubscriptionDraft(
          id = it.optString("id"),
          name = it.optString("name"),
          url = it.optString("url"),
          enabled = it.optBoolean("enabled", true),
          basicEnabled = it.optBoolean("basic_enabled", false),
          basicUsername = it.optString("basic_username", ""),
          basicPassword = it.optString("basic_password", ""),
          hwidEnabled = it.optBoolean("hwid_enabled", false),
          hwid = it.optString("hwid", ""),
          hwidMode = it.optString("hwid_mode", "header"),
          userAgent = it.optString("user_agent", ""),
          sendDeviceHeaders = it.optBoolean("send_device_headers", true),
          deviceLocale = it.optString("device_locale", Locale.getDefault().language),
          deviceOs = it.optString("device_os", "Android"),
          osVersion = it.optString("os_version", Build.VERSION.RELEASE.orEmpty()),
          deviceModel = it.optString("device_model", Build.MODEL.orEmpty()),
          customHeadersText = headerText,
          useRemoteInterval = it.optBoolean("use_remote_interval", true),
          updateIntervalMinutes = it.optLong("update_interval_minutes", 60L).toString(),
        )
      }
      if (cont.isActive) cont.resume(draft)
    }
  }

private fun customHeadersJson(text: String): JSONObject {
  val obj = JSONObject()
  text.lines().forEach { raw ->
    val line = raw.trim()
    if (line.isBlank() || line.startsWith("#")) return@forEach
    val idx = line.indexOf(':')
    if (idx <= 0) return@forEach
    val key = line.substring(0, idx).trim()
    val value = line.substring(idx + 1).trim()
    if (key.isNotBlank() && value.isNotBlank()) obj.put(key, value)
  }
  return obj
}

private fun draftToJson(draft: MihomoSubscriptionDraft): JSONObject = JSONObject()
  .put("name", draft.name.trim())
  .put("url", draft.url.trim())
  .put("enabled", draft.enabled)
  .put("basic_enabled", draft.basicEnabled)
  .put("basic_username", draft.basicUsername.trim())
  .put("basic_password", draft.basicPassword)
  .put("hwid_enabled", draft.hwidEnabled)
  .put("hwid", draft.hwid.trim())
  .put("hwid_mode", draft.hwidMode)
  .put("user_agent", draft.userAgent.trim())
  .put("send_device_headers", draft.sendDeviceHeaders)
  .put("device_locale", draft.deviceLocale.trim())
  .put("device_os", draft.deviceOs.trim())
  .put("os_version", draft.osVersion.trim())
  .put("device_model", draft.deviceModel.trim())
  .put("custom_headers", customHeadersJson(draft.customHeadersText))
  .put("use_remote_interval", draft.useRemoteInterval)
  .put("update_interval_minutes", draft.updateIntervalMinutes.toLongOrNull()?.coerceIn(15L, 10080L) ?: 60L)

private fun formatBytes(bytes: Long?): String? {
  val v = bytes ?: return null
  if (v < 0) return null
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  var value = v.toDouble()
  var idx = 0
  while (value >= 1024.0 && idx < units.lastIndex) { value /= 1024.0; idx++ }
  return if (idx == 0) "${value.toLong()} ${units[idx]}" else String.format(Locale.US, "%.1f %s", value, units[idx])
}

private fun formatEpoch(seconds: Long): String = if (seconds <= 0L) "—" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000L))

@Composable
fun SubscriptionsScreen(
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val compact = rememberIsCompactWidth()
  var loading by remember { mutableStateOf(true) }
  var items by remember { mutableStateOf(emptyList<MihomoSubscriptionItemUi>()) }
  var editor by remember { mutableStateOf<MihomoSubscriptionDraft?>(null) }
  var editorLoading by remember { mutableStateOf(false) }
  var deleting by remember { mutableStateOf<MihomoSubscriptionItemUi?>(null) }
  var busyId by remember { mutableStateOf<String?>(null) }
  var nodesFor by remember { mutableStateOf<MihomoSubscriptionItemUi?>(null) }
  var nodesData by remember { mutableStateOf(SubscriptionNodesUi()) }
  var nodesLoading by remember { mutableStateOf(false) }
  var importDraft by remember { mutableStateOf<SubscriptionImportDraft?>(null) }
  var importSaving by remember { mutableStateOf(false) }

  fun snack(text: String) { scope.launch { snackHost.showSnackbar(text) } }
  fun reload() {
    loading = true
    scope.launch {
      val loaded = loadMihomoSubscriptionItems(actions)
      if (loaded != null) items = loaded
      loading = false
    }
  }

  LaunchedEffect(Unit) { reload() }
  LaunchedEffect(items.any { it.refreshing }) {
    if (items.any { it.refreshing }) {
      while (true) {
        delay(1000)
        val loaded = loadMihomoSubscriptionItems(actions) ?: break
        items = loaded
        if (loaded.none { it.refreshing }) break
      }
    }
  }

  fun openNodes(item: MihomoSubscriptionItemUi) {
    nodesFor = item
    nodesLoading = true
    scope.launch {
      nodesData = loadSubscriptionNodes(actions, item.id) ?: SubscriptionNodesUi()
      nodesLoading = false
    }
  }

  fun beginImport(subscriptionId: String, node: SubscriptionNodeUi, target: String) {
    importDraft = SubscriptionImportDraft(
      subscriptionId = subscriptionId,
      node = node,
      target = target,
      serverName = node.name,
    )
    scope.launch {
      val profiles = loadTargetProfiles(actions, target)
      importDraft = importDraft?.takeIf { it.node.id == node.id && it.target == target }?.copy(
        profiles = profiles,
        selectedProfile = profiles.firstOrNull().orEmpty(),
        loadingProfiles = false,
      )
    }
  }

  nodesFor?.let { subscription ->
    SubscriptionNodesDialog(
      subscription = subscription,
      data = nodesData,
      loading = nodesLoading,
      onDismiss = { if (!importSaving) nodesFor = null },
      onImport = { node, target -> beginImport(subscription.id, node, target) },
      onDetach = { link ->
        actions.deleteJsonPath("/api/subscription-links/${URLEncoder.encode(link.id, "UTF-8")}") { ok ->
          if (ok) openNodes(subscription) else snack(context.getString(R.string.subscription_detach_failed))
        }
      },
    )
  }

  importDraft?.let { draft ->
    SubscriptionImportDialog(
      draft = draft,
      saving = importSaving,
      onChange = { importDraft = it },
      onDismiss = { if (!importSaving) importDraft = null },
      onSave = {
        importSaving = true
        val path = "/api/subscriptions/${URLEncoder.encode(it.subscriptionId, "UTF-8")}/nodes/${URLEncoder.encode(it.node.id, "UTF-8")}/import"
        val payload = JSONObject()
          .put("target", it.target)
          .put("profile", it.selectedProfile)
          .put("server_name", it.serverName)
        actions.postJsonData(path, payload) { ok ->
          importSaving = false
          if (ok) {
            importDraft = null
            nodesFor?.let(::openNodes)
            snack(context.getString(R.string.subscription_import_success))
          } else {
            snack(context.getString(R.string.subscription_import_failed))
          }
        }
      },
    )
  }

  if (editor != null) {
    MihomoSubscriptionEditorDialog(
      draft = editor!!,
      saving = editorLoading,
      onDismiss = { if (!editorLoading) editor = null },
      onSave = { draft ->
        editorLoading = true
        val body = draftToJson(draft)
        val id = draft.id
        if (id == null) {
          actions.postJsonData("/api/subscriptions", body) { ok ->
            editorLoading = false
            if (ok) { editor = null; reload(); snack(context.getString(R.string.mihomo_sub_saved)) }
            else snack(context.getString(R.string.save_failed))
          }
        } else {
          actions.saveJsonData("/api/subscriptions/${URLEncoder.encode(id, "UTF-8")}", body) { ok ->
            editorLoading = false
            if (ok) { editor = null; reload(); snack(context.getString(R.string.mihomo_sub_saved)) }
            else snack(context.getString(R.string.save_failed))
          }
        }
      },
    )
  }

  deleting?.let { item ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text(stringResource(R.string.mihomo_sub_delete_title)) },
      text = { Text(stringResource(R.string.mihomo_sub_delete_message, item.name)) },
      confirmButton = {
        Button(onClick = {
          busyId = item.id
          deleting = null
          actions.deleteJsonPath("/api/subscriptions/${URLEncoder.encode(item.id, "UTF-8")}") { ok ->
            busyId = null
            if (ok) reload() else snack(context.getString(R.string.delete_failed))
          }
        }) { Text(stringResource(R.string.action_delete)) }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(
      start = if (compact) 12.dp else 16.dp,
      end = if (compact) 12.dp else 16.dp,
      top = topContentPadding + 12.dp,
      bottom = bottomContentPadding + 18.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      SubscriptionsHeaderCard(
        items = items,
        loading = loading,
        onAdd = { editor = MihomoSubscriptionDraft() },
        onRefreshAll = {
          busyId = "__all__"
          actions.postJsonData("/api/subscriptions/refresh-all", JSONObject()) { _ ->
            busyId = null
            reload()
          }
        },
      )
    }
    if (loading && items.isEmpty()) {
      item {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f))) {
          Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.common_loading))
          }
        }
      }
    } else if (items.isEmpty()) {
      item {
        MihomoSectionCard(
          title = stringResource(R.string.mihomo_sub_empty_title),
          desc = stringResource(R.string.mihomo_sub_empty_desc),
          icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
        )
      }
    }
    items(items, key = { it.id }) { item ->
      MihomoSubscriptionCard(
        item = item,
        busy = busyId == item.id || item.refreshing,
        onToggle = { enabled ->
          busyId = item.id
          actions.saveJsonData(
            "/api/subscriptions/${URLEncoder.encode(item.id, "UTF-8")}/enabled",
            JSONObject().put("enabled", enabled),
          ) { ok ->
            busyId = null
            if (ok) reload() else snack(context.getString(R.string.save_failed))
          }
        },
        onRefresh = {
          busyId = item.id
          actions.postJsonData("/api/subscriptions/${URLEncoder.encode(item.id, "UTF-8")}/refresh", JSONObject()) { _ ->
            busyId = null
            reload()
          }
        },
        onEdit = {
          if (!editorLoading) {
            editorLoading = true
            scope.launch {
              val draft = loadMihomoSubscriptionDraft(actions, item.id)
              editorLoading = false
              if (draft != null) editor = draft else snack(context.getString(R.string.load_failed))
            }
          }
        },
        onDelete = { deleting = item },
        onOpenNodes = { openNodes(item) },
      )
    }
  }
}

@Composable
private fun SubscriptionsHeaderCard(
  items: List<MihomoSubscriptionItemUi>,
  loading: Boolean,
  onAdd: () -> Unit,
  onRefreshAll: () -> Unit,
) {
  val active = items.count { it.enabled }
  val servers = items.filter { it.enabled }.sumOf { it.status.serverCount }
  MihomoSectionCard(
    title = stringResource(R.string.subscriptions_title),
    desc = stringResource(R.string.mihomo_subscriptions_summary, items.size, active, servers),
    accent = MaterialTheme.colorScheme.primary,
    icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.mihomo_sub_add))
      }
      OutlinedButton(onClick = onRefreshAll, enabled = !loading, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.mihomo_sub_refresh_all))
      }
    }
  }
}

@Composable
private fun MihomoSubscriptionCard(
  item: MihomoSubscriptionItemUi,
  busy: Boolean,
  onToggle: (Boolean) -> Unit,
  onRefresh: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onOpenNodes: () -> Unit,
) {
  val status = item.status
  val ok = status.lastError.isBlank() && status.lastUpdatedAt > 0L
  val accent = when {
    !item.enabled -> MaterialTheme.colorScheme.outline
    status.lastError.isNotBlank() -> MaterialTheme.colorScheme.error
    ok -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.tertiary
  }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
    tonalElevation = 1.dp,
  ) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = accent.copy(alpha = 0.14f), contentColor = accent) {
          Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.CloudDownload, contentDescription = null)
          }
        }
        Column(Modifier.weight(1f)) {
          Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(
            when {
              !item.enabled -> stringResource(R.string.mihomo_sub_state_disabled)
              status.lastError.isNotBlank() -> stringResource(R.string.mihomo_sub_state_error)
              status.lastUpdatedAt > 0 -> stringResource(R.string.mihomo_sub_state_ready)
              else -> stringResource(R.string.mihomo_sub_state_waiting)
            },
            style = MaterialTheme.typography.bodySmall,
            color = accent,
          )
        }
        Switch(checked = item.enabled, onCheckedChange = onToggle, enabled = !busy)
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubscriptionMetric(stringResource(R.string.mihomo_sub_servers), status.serverCount.toString(), Modifier.weight(1f))
        SubscriptionMetric(stringResource(R.string.mihomo_sub_profiles), item.profiles.size.toString(), Modifier.weight(1f))
        SubscriptionMetric(stringResource(R.string.mihomo_sub_interval), "${status.remoteIntervalMinutes ?: item.updateIntervalMinutes}m", Modifier.weight(1f))
      }

      val used = listOfNotNull(status.upload, status.download).sum()
      val traffic = if (status.total != null && status.total > 0L) {
        "${formatBytes(used)} / ${formatBytes(status.total)}"
      } else formatBytes(used.takeIf { it > 0 })
      if (traffic != null || status.expire != null) {
        Text(
          listOfNotNull(
            traffic?.let { stringResource(R.string.mihomo_sub_traffic_value, it) },
            status.expire?.takeIf { it > 0 }?.let { stringResource(R.string.mihomo_sub_expire_value, formatEpoch(it)) },
          ).joinToString("  •  "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
      }
      if (item.profiles.isNotEmpty()) {
        Text(
          stringResource(R.string.mihomo_sub_used_by, item.profiles.joinToString(", ")),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
          maxLines = 2,
        )
      }
      if (status.lastError.isNotBlank()) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.44f),
          shape = MaterialTheme.shapes.medium,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.20f)),
        ) {
          Text(status.lastError, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
      } else if (status.lastUpdatedAt > 0) {
        Text(stringResource(R.string.mihomo_sub_updated_value, formatEpoch(status.lastUpdatedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpenNodes, enabled = !busy && status.serverCount > 0) {
          Text(stringResource(R.string.subscription_view_servers))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRefresh, enabled = item.enabled && !busy) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.mihomo_sub_refresh)) }
        IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit)) }
        IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error) }
      }
    }
  }
}

@Composable
private fun SubscriptionMetric(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)) {
    Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
      Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
      Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), maxLines = 1)
    }
  }
}

@Composable
private fun subscriptionTargetLabel(target: String): String = when (target) {
  "sing-box" -> "sing-box"
  "hysteria2" -> "Hysteria2"
  "wireproxy" -> "WireProxy"
  else -> target
}

@Composable
private fun SubscriptionNodesDialog(
  subscription: MihomoSubscriptionItemUi,
  data: SubscriptionNodesUi,
  loading: Boolean,
  onDismiss: () -> Unit,
  onImport: (SubscriptionNodeUi, String) -> Unit,
  onDetach: (SubscriptionLinkUi) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Column {
        Text(subscription.name)
        Text(
          stringResource(R.string.subscription_servers_count, data.nodes.size),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
      }
    },
    text = {
      if (loading) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val missing = data.links.filter { it.missing }
          if (missing.isNotEmpty()) {
            item(key = "missing") {
              Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.46f),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.22f)),
              ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text(stringResource(R.string.subscription_missing_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                  missing.forEach { link ->
                    Text(
                      "${subscriptionTargetLabel(link.target)} · ${link.profile} / ${link.serverName}",
                      style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.subscription_local_copy_saved), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDetach(link) }) { Text(stringResource(R.string.subscription_detach)) }
                  }
                }
              }
            }
          }
          if (data.nodes.isEmpty()) {
            item { Text(stringResource(R.string.subscription_nodes_empty)) }
          }
          items(data.nodes, key = { it.id }) { node ->
            val links = data.links.filter { it.nodeId == node.id && !it.missing }
            Surface(
              shape = MaterialTheme.shapes.large,
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
            ) {
              Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(node.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(node.protocol.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (node.server.isNotBlank()) {
                  Text(
                    if (node.port > 0) "${node.server}:${node.port}" else node.server,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                  )
                }
                links.forEach { link ->
                  Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)) {
                    Row(
                      Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Text(
                        stringResource(R.string.subscription_linked_to, subscriptionTargetLabel(link.target), link.profile, link.serverName),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                      )
                      TextButton(onClick = { onDetach(link) }) { Text(stringResource(R.string.subscription_detach)) }
                    }
                  }
                }
                node.targets.forEach { target ->
                  OutlinedButton(onClick = { onImport(node, target) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.subscription_add_to_target, subscriptionTargetLabel(target)))
                  }
                }
                if (node.targets.isEmpty()) {
                  Text(
                    stringResource(R.string.subscription_unsupported_node),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
  )
}

@Composable
private fun SubscriptionImportDialog(
  draft: SubscriptionImportDraft,
  saving: Boolean,
  onChange: (SubscriptionImportDraft) -> Unit,
  onDismiss: () -> Unit,
  onSave: (SubscriptionImportDraft) -> Unit,
) {
  val valid = !draft.loadingProfiles && draft.selectedProfile.isNotBlank() && draft.serverName.isNotBlank()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.subscription_import_title, subscriptionTargetLabel(draft.target))) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(draft.node.name, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.subscription_choose_profile), style = MaterialTheme.typography.labelLarge)
        if (draft.loadingProfiles) {
          CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else if (draft.profiles.isEmpty()) {
          Text(stringResource(R.string.subscription_no_target_profiles), color = MaterialTheme.colorScheme.error)
        } else {
          LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(draft.profiles, key = { it }) { profile ->
              FilterChip(
                selected = profile == draft.selectedProfile,
                onClick = { onChange(draft.copy(selectedProfile = profile)) },
                label = { Text(profile) },
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }
        }
        OutlinedTextField(
          value = draft.serverName,
          onValueChange = { onChange(draft.copy(serverName = it)) },
          label = { Text(stringResource(R.string.subscription_local_server_name)) },
          supportingText = { Text(stringResource(R.string.subscription_server_name_hint)) },
          singleLine = true,
          enabled = !saving,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(onClick = { onSave(draft) }, enabled = valid && !saving) {
        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.subscription_import_action))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun MihomoSubscriptionEditorDialog(
  draft: MihomoSubscriptionDraft,
  saving: Boolean,
  onDismiss: () -> Unit,
  onSave: (MihomoSubscriptionDraft) -> Unit,
) {
  val context = LocalContext.current
  var state by remember(draft) { mutableStateOf(draft) }
  val valid = state.name.trim().isNotBlank() &&
    (state.url.startsWith("http://") || state.url.startsWith("https://")) &&
    (!state.hwidEnabled || state.hwid.trim().isNotBlank()) &&
    (!state.basicEnabled || state.basicUsername.trim().isNotBlank()) &&
    (state.updateIntervalMinutes.toLongOrNull() ?: 0L) in 15L..10080L

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(if (draft.id == null) R.string.mihomo_sub_add_title else R.string.mihomo_sub_edit_title)) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 590.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          OutlinedTextField(state.name, { state = state.copy(name = it.take(80)) }, label = { Text(stringResource(R.string.mihomo_sub_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
          OutlinedTextField(state.url, { state = state.copy(url = it.trim().take(2048)) }, label = { Text(stringResource(R.string.mihomo_sub_url)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
          SubscriptionToggleRow(stringResource(R.string.mihomo_sub_basic_auth), stringResource(R.string.mihomo_sub_basic_auth_desc), state.basicEnabled) { state = state.copy(basicEnabled = it) }
        }
        if (state.basicEnabled) {
          item { OutlinedTextField(state.basicUsername, { state = state.copy(basicUsername = it.take(160)) }, label = { Text(stringResource(R.string.mihomo_sub_username)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
          item { OutlinedTextField(state.basicPassword, { state = state.copy(basicPassword = it.take(512)) }, label = { Text(stringResource(R.string.mihomo_sub_password)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        }
        item {
          SubscriptionToggleRow(stringResource(R.string.mihomo_sub_hwid), stringResource(R.string.mihomo_sub_hwid_desc), state.hwidEnabled) { state = state.copy(hwidEnabled = it) }
        }
        if (state.hwidEnabled) {
          item {
            OutlinedTextField(state.hwid, { state = state.copy(hwid = it.trim().take(512)) }, label = { Text(stringResource(R.string.mihomo_sub_hwid_value)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
          }
          item {
            OutlinedButton(
              onClick = {
                val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
                if (androidId.isNotBlank()) state = state.copy(hwid = androidId)
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.mihomo_sub_use_device_hwid))
            }
          }
          item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(selected = state.hwidMode == "header", onClick = { state = state.copy(hwidMode = "header") }, label = { Text("X-HWID") })
              FilterChip(selected = state.hwidMode == "cookie", onClick = { state = state.copy(hwidMode = "cookie") }, label = { Text("Cookie") })
            }
          }
        }
        item {
          SubscriptionToggleRow(stringResource(R.string.mihomo_sub_device_headers), stringResource(R.string.mihomo_sub_device_headers_desc), state.sendDeviceHeaders) { state = state.copy(sendDeviceHeaders = it) }
        }
        item {
          OutlinedTextField(
            state.userAgent,
            { state = state.copy(userAgent = it.take(300)) },
            label = { Text(stringResource(R.string.mihomo_sub_user_agent)) },
            supportingText = { Text(stringResource(R.string.mihomo_sub_user_agent_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
        }
        if (state.sendDeviceHeaders) {
          item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(state.deviceLocale, { state = state.copy(deviceLocale = it.take(32)) }, label = { Text(stringResource(R.string.mihomo_sub_locale)) }, modifier = Modifier.weight(1f), singleLine = true)
              OutlinedTextField(state.osVersion, { state = state.copy(osVersion = it.take(64)) }, label = { Text(stringResource(R.string.mihomo_sub_os_version)) }, modifier = Modifier.weight(1f), singleLine = true)
            }
          }
          item { OutlinedTextField(state.deviceModel, { state = state.copy(deviceModel = it.take(120)) }, label = { Text(stringResource(R.string.mihomo_sub_device_model)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        }
        item {
          OutlinedTextField(
            state.customHeadersText,
            { state = state.copy(customHeadersText = it.take(6000)) },
            label = { Text(stringResource(R.string.mihomo_sub_custom_headers)) },
            supportingText = { Text(stringResource(R.string.mihomo_sub_custom_headers_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 7,
          )
        }
        item {
          SubscriptionToggleRow(stringResource(R.string.mihomo_sub_remote_interval), stringResource(R.string.mihomo_sub_remote_interval_desc), state.useRemoteInterval) { state = state.copy(useRemoteInterval = it) }
        }
        item {
          OutlinedTextField(
            state.updateIntervalMinutes,
            { state = state.copy(updateIntervalMinutes = it.filter(Char::isDigit).take(5)) },
            label = { Text(stringResource(R.string.mihomo_sub_fallback_interval)) },
            supportingText = { Text(stringResource(R.string.mihomo_sub_interval_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = { onSave(state) }, enabled = valid && !saving) {
        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun SubscriptionToggleRow(title: String, desc: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
  Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
      }
      Switch(checked = checked, onCheckedChange = onChecked)
    }
  }
}


@Composable
internal fun MihomoProfileSubscriptionsTab(
  selectedIds: Set<String>,
  actions: ZdtdActions,
  onSelectionChange: (Set<String>) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var loading by remember { mutableStateOf(true) }
  var items by remember { mutableStateOf(emptyList<MihomoSubscriptionItemUi>()) }

  fun reload() {
    loading = true
    scope.launch {
      loadMihomoSubscriptionItems(actions)?.let { items = it }
      loading = false
    }
  }
  LaunchedEffect(Unit) { reload() }
  LaunchedEffect(items.any { it.refreshing }) {
    if (items.any { it.refreshing }) {
      while (true) {
        delay(1000)
        val loaded = loadMihomoSubscriptionItems(actions) ?: break
        items = loaded
        if (loaded.none { it.refreshing }) break
      }
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    MihomoSectionCard(
      title = stringResource(R.string.mihomo_profile_subscriptions_title),
      desc = stringResource(R.string.mihomo_profile_subscriptions_desc),
      accent = MaterialTheme.colorScheme.tertiary,
      icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
    ) {
      val selectedAvailable = items.count { it.id in selectedIds && it.enabled }
      Text(
        stringResource(R.string.mihomo_profile_subscriptions_selected, selectedAvailable, items.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      )
      Text(
        stringResource(R.string.mihomo_profile_subscriptions_runtime_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
      )
    }

    if (loading && items.isEmpty()) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
          Text(stringResource(R.string.common_loading))
        }
      }
    } else if (items.isEmpty()) {
      MihomoSectionCard(
        title = stringResource(R.string.mihomo_sub_empty_title),
        desc = stringResource(R.string.subscriptions_entry_desc),
        icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
      )
    } else {
      items.forEach { item ->
        val selected = item.id in selectedIds
        val statusAccent = when {
          !item.enabled -> MaterialTheme.colorScheme.outline
          item.status.lastError.isNotBlank() -> MaterialTheme.colorScheme.error
          else -> MaterialTheme.colorScheme.primary
        }
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
          border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.18f)),
        ) {
          Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                  stringResource(R.string.mihomo_profile_subscription_servers, item.status.serverCount),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
              }
              Switch(
                checked = selected,
                onCheckedChange = { checked ->
                  val next = selectedIds.toMutableSet()
                  if (checked) next += item.id else next -= item.id
                  onSelectionChange(next)
                },
                enabled = item.enabled || selected,
              )
            }
            if (!item.enabled) {
              Text(stringResource(R.string.mihomo_profile_subscription_disabled_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
            }
            if (item.status.lastError.isNotBlank()) {
              Text(item.status.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}
