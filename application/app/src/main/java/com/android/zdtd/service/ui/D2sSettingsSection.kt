package com.android.zdtd.service.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val D2S_CONFIG_API = "/api/programs/dnscrypt/d2s-config"

private data class D2sSettingsUi(
  val listener: String = "",
  val backendPorts: List<String> = emptyList(),
  val directFallback: Boolean = true,
  val connectTimeoutMs: String = "500",
  val upstreamHandshakeTimeoutMs: String = "1000",
  val backendAttemptTimeoutMs: String = "1200",
  val directConnectTimeoutMs: String = "2000",
  val clientHandshakeTimeoutMs: String = "3000",
  val probeTimeoutMs: String = "1200",
  val healthyProbeIntervalSecs: String = "30",
  val recoveryProbeIntervalSecs: String = "5",
  val failureThreshold: String = "3",
  val runtimeCooldownMs: String = "2000",
  val probeTargets: List<String> = listOf("1.1.1.1:443", "8.8.8.8:443"),
  val maxConnections: String = "1024",
  val tcpNodelay: Boolean = true,
  val logLevel: String = "info",
  val shutdownGracePeriodMs: String = "5000",
)

@Composable
fun D2sSettingsSection(
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var settings by remember { mutableStateOf(D2sSettingsUi()) }
  var savedSettings by remember { mutableStateOf<D2sSettingsUi?>(null) }
  var loading by remember { mutableStateOf(true) }
  var saving by remember { mutableStateOf(false) }
  var loadFailed by remember { mutableStateOf(false) }
  var validationError by remember { mutableStateOf<String?>(null) }

  fun update(next: D2sSettingsUi) {
    settings = next
    validationError = null
  }

  fun load() {
    loading = true
    loadFailed = false
    actions.loadJsonData(D2S_CONFIG_API) { obj ->
      if (obj == null) {
        loadFailed = true
        loading = false
      } else {
        val parsed = parseD2sSettings(obj)
        val normalized = if (parsed.backendPorts.isEmpty() && !parsed.directFallback) {
          parsed.copy(directFallback = true)
        } else {
          parsed
        }
        settings = normalized
        savedSettings = parsed
        loading = false
      }
    }
  }

  LaunchedEffect(Unit) { load() }

  when {
    loading -> {
      D2sSectionCard(
        title = stringResource(R.string.d2s_loading_title),
        description = stringResource(R.string.d2s_loading_desc),
        icon = { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
      ) {}
    }
    loadFailed -> {
      D2sSectionCard(
        title = stringResource(R.string.d2s_load_failed_title),
        description = stringResource(R.string.d2s_load_failed_desc),
        icon = { Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(22.dp)) },
      ) {
        OutlinedButton(onClick = { load() }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.dnscrypt_setting_files_refresh))
        }
      }
    }
    else -> {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        D2sSectionCard(
          title = stringResource(R.string.d2s_overview_title),
          description = stringResource(R.string.d2s_overview_desc),
          icon = { Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(22.dp)) },
        ) {
          D2sReadOnlyValue(
            label = stringResource(R.string.d2s_listener_label),
            value = settings.listener.ifBlank { stringResource(R.string.d2s_listener_unavailable) },
            description = stringResource(R.string.d2s_listener_desc),
          )
        }

        D2sSectionCard(
          title = stringResource(R.string.d2s_backends_title),
          description = stringResource(R.string.d2s_backends_desc),
          icon = { Icon(Icons.Outlined.SettingsEthernet, contentDescription = null, modifier = Modifier.size(22.dp)) },
        ) {
          if (settings.backendPorts.isEmpty()) {
            Text(
              text = stringResource(R.string.d2s_backends_empty),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          settings.backendPorts.forEachIndexed { index, port ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedTextField(
                value = port,
                onValueChange = { value ->
                  val next = settings.backendPorts.toMutableList()
                  next[index] = value.filter(Char::isDigit).take(5)
                  update(settings.copy(backendPorts = next))
                },
                label = { Text(stringResource(R.string.d2s_backend_port_label)) },
                supportingText = { Text(stringResource(R.string.d2s_backend_port_desc)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
              IconButton(
                onClick = {
                  val next = settings.backendPorts.toMutableList().also { it.removeAt(index) }
                  update(settings.copy(backendPorts = next, directFallback = if (next.isEmpty()) true else settings.directFallback))
                },
              ) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.d2s_remove_backend))
              }
            }
          }
          OutlinedButton(
            onClick = { update(settings.copy(backendPorts = settings.backendPorts + "")) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.d2s_add_backend), modifier = Modifier.padding(start = 8.dp))
          }
          HorizontalDivider()
          D2sSwitchRow(
            title = stringResource(R.string.d2s_direct_title),
            description = stringResource(R.string.d2s_direct_desc),
            checked = settings.directFallback,
            enabled = settings.backendPorts.isNotEmpty(),
            onCheckedChange = { update(settings.copy(directFallback = it)) },
          )
        }

        D2sSectionCard(
          title = stringResource(R.string.d2s_health_title),
          description = stringResource(R.string.d2s_health_desc),
          icon = { Icon(Icons.Outlined.HealthAndSafety, contentDescription = null, modifier = Modifier.size(22.dp)) },
        ) {
          D2sNumberField(settings.healthyProbeIntervalSecs, { update(settings.copy(healthyProbeIntervalSecs = it)) }, R.string.d2s_healthy_interval, R.string.d2s_seconds_desc)
          D2sNumberField(settings.recoveryProbeIntervalSecs, { update(settings.copy(recoveryProbeIntervalSecs = it)) }, R.string.d2s_recovery_interval, R.string.d2s_seconds_desc)
          D2sNumberField(settings.failureThreshold, { update(settings.copy(failureThreshold = it)) }, R.string.d2s_failure_threshold, R.string.d2s_failure_threshold_desc)
          D2sNumberField(settings.runtimeCooldownMs, { update(settings.copy(runtimeCooldownMs = it)) }, R.string.d2s_runtime_cooldown, R.string.d2s_milliseconds_desc)
          HorizontalDivider()
          Text(stringResource(R.string.d2s_probe_targets_label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(stringResource(R.string.d2s_probe_targets_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          settings.probeTargets.forEachIndexed { index, target ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedTextField(
                value = target,
                onValueChange = { value ->
                  val next = settings.probeTargets.toMutableList()
                  next[index] = value.trim().take(255)
                  update(settings.copy(probeTargets = next))
                },
                label = { Text(stringResource(R.string.d2s_probe_target_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
              IconButton(onClick = {
                val next = settings.probeTargets.toMutableList().also { it.removeAt(index) }
                update(settings.copy(probeTargets = next))
              }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.d2s_remove_probe_target))
              }
            }
          }
          OutlinedButton(
            onClick = { update(settings.copy(probeTargets = settings.probeTargets + "")) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.d2s_add_probe_target), modifier = Modifier.padding(start = 8.dp))
          }
        }

        D2sSectionCard(
          title = stringResource(R.string.d2s_timeouts_title),
          description = stringResource(R.string.d2s_timeouts_desc),
          icon = { Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(22.dp)) },
        ) {
          D2sNumberField(settings.connectTimeoutMs, { update(settings.copy(connectTimeoutMs = it)) }, R.string.d2s_connect_timeout, R.string.d2s_milliseconds_desc)
          D2sNumberField(settings.upstreamHandshakeTimeoutMs, { update(settings.copy(upstreamHandshakeTimeoutMs = it)) }, R.string.d2s_upstream_handshake_timeout, R.string.d2s_milliseconds_desc)
          D2sNumberField(settings.backendAttemptTimeoutMs, { update(settings.copy(backendAttemptTimeoutMs = it)) }, R.string.d2s_backend_attempt_timeout, R.string.d2s_milliseconds_desc)
          D2sNumberField(settings.directConnectTimeoutMs, { update(settings.copy(directConnectTimeoutMs = it)) }, R.string.d2s_direct_connect_timeout, R.string.d2s_milliseconds_desc)
          D2sNumberField(settings.clientHandshakeTimeoutMs, { update(settings.copy(clientHandshakeTimeoutMs = it)) }, R.string.d2s_client_handshake_timeout, R.string.d2s_milliseconds_desc)
          D2sNumberField(settings.probeTimeoutMs, { update(settings.copy(probeTimeoutMs = it)) }, R.string.d2s_probe_timeout, R.string.d2s_milliseconds_desc)
        }

        D2sSectionCard(
          title = stringResource(R.string.d2s_performance_title),
          description = stringResource(R.string.d2s_performance_desc),
          icon = { Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(22.dp)) },
        ) {
          D2sNumberField(settings.maxConnections, { update(settings.copy(maxConnections = it)) }, R.string.d2s_max_connections, R.string.d2s_max_connections_desc)
          D2sSwitchRow(
            title = stringResource(R.string.d2s_tcp_nodelay_title),
            description = stringResource(R.string.d2s_tcp_nodelay_desc),
            checked = settings.tcpNodelay,
            onCheckedChange = { update(settings.copy(tcpNodelay = it)) },
          )
          D2sLogLevelField(settings.logLevel) { update(settings.copy(logLevel = it)) }
          D2sNumberField(settings.shutdownGracePeriodMs, { update(settings.copy(shutdownGracePeriodMs = it)) }, R.string.d2s_shutdown_grace, R.string.d2s_milliseconds_desc)
        }

        validationError?.let { error ->
          Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }

        Button(
          enabled = !saving && settings != savedSettings,
          onClick = {
            val error = validateD2sSettings(settings, context)
            if (error != null) {
              validationError = error
              return@Button
            }
            saving = true
            actions.saveJsonData(D2S_CONFIG_API, settings.toJson()) { ok ->
              saving = false
              if (ok) {
                savedSettings = settings
                validationError = null
                scope.launch { snackHost.showSnackbar(context.getString(R.string.d2s_saved)) }
              } else {
                scope.launch { snackHost.showSnackbar(context.getString(R.string.save_failed)) }
              }
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(if (saving) R.string.d2s_saving else R.string.d2s_save))
        }
      }
    }
  }
}

@Composable
private fun D2sSectionCard(
  title: String,
  description: String,
  icon: @Composable () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon()
        Column(Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      content()
    }
  }
}

@Composable
private fun D2sReadOnlyValue(label: String, value: String, description: String) {
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun D2sNumberField(
  value: String,
  onValueChange: (String) -> Unit,
  labelRes: Int,
  descriptionRes: Int,
) {
  OutlinedTextField(
    value = value,
    onValueChange = { onValueChange(it.filter(Char::isDigit).take(9)) },
    label = { Text(stringResource(labelRes)) },
    supportingText = { Text(stringResource(descriptionRes)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun D2sSwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun D2sLogLevelField(value: String, onValueChange: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val levels = listOf(
    "error" to R.string.d2s_log_level_error,
    "warn" to R.string.d2s_log_level_warn,
    "info" to R.string.d2s_log_level_info,
    "debug" to R.string.d2s_log_level_debug,
    "trace" to R.string.d2s_log_level_trace,
  )
  val selectedLabel = levels.firstOrNull { it.first == value }?.second
    ?.let { stringResource(it) }
    ?: value
  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    OutlinedTextField(
      value = selectedLabel,
      onValueChange = {},
      readOnly = true,
      label = { Text(stringResource(R.string.d2s_log_level)) },
      supportingText = { Text(stringResource(R.string.d2s_log_level_desc)) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor().fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      levels.forEach { (level, labelRes) ->
        DropdownMenuItem(
          text = { Text(stringResource(labelRes)) },
          onClick = {
            onValueChange(level)
            expanded = false
          },
        )
      }
    }
  }
}

private fun parseD2sSettings(obj: JSONObject): D2sSettingsUi {
  val backends = obj.optJSONArray("backends")?.toStringList().orEmpty().mapNotNull(::extractPort)
  val listener = obj.opt("listener")
    ?.takeUnless { it == JSONObject.NULL }
    ?.toString()
    .orEmpty()
  return D2sSettingsUi(
    listener = listener,
    backendPorts = backends,
    directFallback = obj.optBoolean("direct_fallback", true),
    connectTimeoutMs = obj.optLong("connect_timeout_ms", 500L).toString(),
    upstreamHandshakeTimeoutMs = obj.optLong("upstream_handshake_timeout_ms", 1000L).toString(),
    backendAttemptTimeoutMs = obj.optLong("backend_attempt_timeout_ms", 1200L).toString(),
    directConnectTimeoutMs = obj.optLong("direct_connect_timeout_ms", 2000L).toString(),
    clientHandshakeTimeoutMs = obj.optLong("client_handshake_timeout_ms", 3000L).toString(),
    probeTimeoutMs = obj.optLong("probe_timeout_ms", 1200L).toString(),
    healthyProbeIntervalSecs = obj.optLong("healthy_probe_interval_secs", 30L).toString(),
    recoveryProbeIntervalSecs = obj.optLong("recovery_probe_interval_secs", 5L).toString(),
    failureThreshold = obj.optInt("failure_threshold", 3).toString(),
    runtimeCooldownMs = obj.optLong("runtime_cooldown_ms", 2000L).toString(),
    probeTargets = obj.optJSONArray("probe_targets")?.toStringList()
      ?: listOf("1.1.1.1:443", "8.8.8.8:443"),
    maxConnections = obj.optLong("max_connections", 1024L).toString(),
    tcpNodelay = obj.optBoolean("tcp_nodelay", true),
    logLevel = obj.optString("log_level", "info").lowercase(),
    shutdownGracePeriodMs = obj.optLong("shutdown_grace_period_ms", 5000L).toString(),
  )
}

private fun D2sSettingsUi.toJson(): JSONObject = JSONObject()
  .put("backends", JSONArray(backendPorts.map { "127.0.0.1:${it.trim()}" }))
  .put("direct_fallback", directFallback)
  .put("connect_timeout_ms", connectTimeoutMs.toLong())
  .put("upstream_handshake_timeout_ms", upstreamHandshakeTimeoutMs.toLong())
  .put("backend_attempt_timeout_ms", backendAttemptTimeoutMs.toLong())
  .put("direct_connect_timeout_ms", directConnectTimeoutMs.toLong())
  .put("client_handshake_timeout_ms", clientHandshakeTimeoutMs.toLong())
  .put("probe_timeout_ms", probeTimeoutMs.toLong())
  .put("healthy_probe_interval_secs", healthyProbeIntervalSecs.toLong())
  .put("recovery_probe_interval_secs", recoveryProbeIntervalSecs.toLong())
  .put("failure_threshold", failureThreshold.toLong())
  .put("runtime_cooldown_ms", runtimeCooldownMs.toLong())
  .put("probe_targets", JSONArray(probeTargets.map { it.trim() }))
  .put("max_connections", maxConnections.toLong())
  .put("tcp_nodelay", tcpNodelay)
  .put("log_level", logLevel)
  .put("shutdown_grace_period_ms", shutdownGracePeriodMs.toLong())

private fun validateD2sSettings(settings: D2sSettingsUi, context: Context): String? {
  val listenerPort = extractPort(settings.listener)?.toIntOrNull()
  val ports = mutableSetOf<Int>()
  for (raw in settings.backendPorts) {
    val port = raw.toIntOrNull() ?: return context.getString(R.string.d2s_error_port_required)
    if (port !in 1..65535) return context.getString(R.string.d2s_error_port_range)
    if (!ports.add(port)) return context.getString(R.string.d2s_error_duplicate_port)
    if (listenerPort == port) return context.getString(R.string.d2s_error_listener_port)
  }
  if (ports.isEmpty() && !settings.directFallback) return context.getString(R.string.d2s_error_direct_required)

  val positive = listOf(
    settings.connectTimeoutMs,
    settings.upstreamHandshakeTimeoutMs,
    settings.backendAttemptTimeoutMs,
    settings.directConnectTimeoutMs,
    settings.clientHandshakeTimeoutMs,
    settings.probeTimeoutMs,
    settings.healthyProbeIntervalSecs,
    settings.recoveryProbeIntervalSecs,
    settings.failureThreshold,
    settings.runtimeCooldownMs,
    settings.maxConnections,
    settings.shutdownGracePeriodMs,
  )
  if (positive.any { it.toLongOrNull()?.let { value -> value <= 0 } != false }) {
    return context.getString(R.string.d2s_error_positive_number)
  }
  if (ports.isNotEmpty() && settings.probeTargets.isEmpty()) return context.getString(R.string.d2s_error_probe_required)
  if (settings.probeTargets.any { !isHostPort(it) }) return context.getString(R.string.d2s_error_probe_target)
  return null
}

private fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { index ->
  opt(index)
    ?.takeUnless { it == JSONObject.NULL }
    ?.toString()
    ?.trim()
    ?.takeIf(String::isNotEmpty)
}

private fun extractPort(value: String): String? {
  val trimmed = value.trim()
  val port = trimmed.substringAfterLast(':', "").removeSuffix("]")
  return port.toIntOrNull()?.takeIf { it in 1..65535 }?.toString()
}

private fun isHostPort(value: String): Boolean {
  val trimmed = value.trim()
  if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return false
  val separator = trimmed.lastIndexOf(':')
  if (separator <= 0 || separator == trimmed.lastIndex) return false
  val host = trimmed.substring(0, separator)
  val port = trimmed.substring(separator + 1).toIntOrNull() ?: return false
  return host.isNotBlank() && port in 1..65535
}
