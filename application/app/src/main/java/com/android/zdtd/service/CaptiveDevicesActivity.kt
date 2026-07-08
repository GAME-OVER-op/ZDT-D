package com.android.zdtd.service

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.api.ApiClient
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.ui.theme.ZdtdTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Device-settings screen for the captive portal: lists devices asking for
 * internet access (pending / allowed / denied), lets the user allow or deny
 * each one, and exposes the captive-portal toggle. All data flows over the
 * token-authenticated local API. EN + RU strings.
 */
class CaptiveDevicesActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    AppLanguageSupport.applyPersistedAppLocale(applicationContext)
    super.onCreate(savedInstanceState)

    val root = RootConfigManager(applicationContext)
    val api = ApiClient(
      rootManager = root,
      baseUrlProvider = { "http://127.0.0.1:1006" },
      tokenProvider = { root.readApiToken() },
    )

    setContent {
      ZdtdTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          CaptiveDevicesScreen(api = api, onClose = { finish() })
        }
      }
    }
  }
}

@Composable
private fun CaptiveDevicesScreen(api: ApiClient, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  var loading by remember { mutableStateOf(true) }
  var portalEnabled by remember { mutableStateOf(false) }
  var devices by remember { mutableStateOf<List<ApiModels.CaptiveDevice>>(emptyList()) }

  suspend fun reload() {
    val status = withContext(Dispatchers.IO) { runCatching { api.getCaptivePortalStatus() }.getOrNull() }
    val list = withContext(Dispatchers.IO) { runCatching { api.getCaptiveDevices() }.getOrDefault(emptyList()) }
    if (status != null) portalEnabled = status.enabled
    devices = list.sortedByDescending { it.lastSeen }
    loading = false
  }

  LaunchedEffect(Unit) {
    reload()
    // Light polling so decisions made on the notification reflect here too.
    while (true) {
      delay(4000)
      reload()
    }
  }

  val doAllow: (String) -> Unit = { id ->
    scope.launch {
      withContext(Dispatchers.IO) { runCatching { api.allowCaptiveDevice(id) } }
      reload()
    }
  }
  val doDeny: (String) -> Unit = { id ->
    scope.launch {
      withContext(Dispatchers.IO) { runCatching { api.denyCaptiveDevice(id) } }
      reload()
    }
  }

  val pending = devices.filter { it.status == "pending" || (!it.allowed && it.status != "denied") }
  val allowed = devices.filter { it.status == "allowed" || it.allowed }
  val denied = devices.filter { it.status == "denied" && !it.allowed }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(
      text = stringResource(R.string.captive_devices_title),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )

    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text(stringResource(R.string.captive_devices_toggle_title), style = MaterialTheme.typography.bodyLarge)
        Text(
          stringResource(R.string.captive_devices_toggle_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }
      Switch(
        checked = portalEnabled,
        onCheckedChange = { checked ->
          portalEnabled = checked
          scope.launch {
            withContext(Dispatchers.IO) { runCatching { api.setCaptivePortalEnabled(checked) } }
            reload()
          }
        },
      )
    }

    if (loading) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
      }
    } else {
      DeviceSection(
        title = stringResource(R.string.captive_devices_section_pending),
        devices = pending,
        canAllow = true,
        canDeny = true,
        onAllow = doAllow,
        onDeny = doDeny,
      )
      DeviceSection(
        title = stringResource(R.string.captive_devices_section_allowed),
        devices = allowed,
        canAllow = false,
        canDeny = true,
        onAllow = doAllow,
        onDeny = doDeny,
      )
      DeviceSection(
        title = stringResource(R.string.captive_devices_section_denied),
        devices = denied,
        canAllow = true,
        canDeny = false,
        onAllow = doAllow,
        onDeny = doDeny,
      )
    }

    Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
      Text(stringResource(R.string.captive_devices_close))
    }
  }
}

@Composable
private fun DeviceSection(
  title: String,
  devices: List<ApiModels.CaptiveDevice>,
  canAllow: Boolean,
  canDeny: Boolean,
  onAllow: (String) -> Unit,
  onDeny: (String) -> Unit,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = "$title (${devices.size})",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.primary,
    )
    if (devices.isEmpty()) {
      Text(
        stringResource(R.string.captive_devices_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
    } else {
      devices.forEach { d ->
        DeviceCard(device = d, canAllow = canAllow, canDeny = canDeny, onAllow = onAllow, onDeny = onDeny)
      }
    }
  }
}

@Composable
private fun DeviceCard(
  device: ApiModels.CaptiveDevice,
  canAllow: Boolean,
  canDeny: Boolean,
  onAllow: (String) -> Unit,
  onDeny: (String) -> Unit,
) {
  val unknown = stringResource(R.string.captive_devices_unknown)
  val idForAction = device.shortId.ifBlank { device.id }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = device.model.ifBlank { device.shortId.ifBlank { unknown } },
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
      )
      FieldRow(stringResource(R.string.captive_devices_field_id), device.shortId.ifBlank { unknown })
      FieldRow(stringResource(R.string.captive_devices_field_ip), device.ip.ifBlank { unknown })
      FieldRow(stringResource(R.string.captive_devices_field_mac), device.mac.ifBlank { unknown })
      FieldRow(stringResource(R.string.captive_devices_field_status), device.status.ifBlank { unknown })
      if (device.userAgent.isNotBlank()) {
        FieldRow(stringResource(R.string.captive_devices_field_user_agent), device.userAgent)
      }
      if (canAllow || canDeny) {
        Row(
          Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (canAllow) {
            Button(onClick = { onAllow(idForAction) }, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.captive_action_allow))
            }
          }
          if (canDeny) {
            OutlinedButton(onClick = { onDeny(idForAction) }, modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.captive_action_deny))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun FieldRow(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(text = value, style = MaterialTheme.typography.bodySmall)
  }
}
