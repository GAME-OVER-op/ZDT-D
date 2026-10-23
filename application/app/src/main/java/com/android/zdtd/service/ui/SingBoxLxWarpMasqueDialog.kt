package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.singboxlx.warp.WarpMasqueAccount
import com.android.zdtd.service.singboxlx.warp.WarpMasqueConfigFactory
import com.android.zdtd.service.singboxlx.warp.WarpMasqueException
import com.android.zdtd.service.singboxlx.warp.WarpMasqueFailure
import com.android.zdtd.service.singboxlx.warp.WarpMasqueNodeOptions
import com.android.zdtd.service.singboxlx.warp.WarpMasqueRegistrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MASQUE_ACCOUNT_PATH = "/api/programs/sing-box-lx/warp/masque/account"

@Composable
internal fun SingBoxLxWarpMasqueDialog(
  actions: ZdtdActions,
  existingServerNames: List<String>,
  preferredMixedPort: Int,
  onDismiss: () -> Unit,
  onCreate: (serverName: String, configText: String, localPort: Int) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.82f

  var cacheLoading by remember { mutableStateOf(true) }
  var cachedAccount by remember { mutableStateOf<WarpMasqueAccount?>(null) }
  var cacheError by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var errorText by remember { mutableStateOf<String?>(null) }
  var forceNew by remember { mutableStateOf(false) }
  var vhttp by remember { mutableStateOf("auto") }
  var vhttpMenu by remember { mutableStateOf(false) }
  var name by remember(existingServerNames) { mutableStateOf(nextWarpMasqueName(existingServerNames)) }
  var endpoint by remember { mutableStateOf("") }
  var endpointPort by remember { mutableStateOf("") }
  var sni by remember { mutableStateOf("www.cloudflare.com") }
  var mtu by remember { mutableStateOf("1280") }
  var idleMinutes by remember { mutableStateOf("") }
  var keepAliveSeconds by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    actions.loadJsonData(MASQUE_ACCOUNT_PATH) { obj ->
      cachedAccount = WarpMasqueAccount.fromJson(obj?.optJSONObject("account"))
      cacheError = obj == null
      cacheLoading = false
    }
  }

  fun createWith(account: WarpMasqueAccount) {
    val safeName = name.trim()
    if (safeName.isBlank()) {
      errorText = context.getString(R.string.warp_masque_error_name)
      return
    }
    if (existingServerNames.any { it.equals(safeName, ignoreCase = true) }) {
      errorText = context.getString(R.string.warp_masque_error_name_exists)
      return
    }
    val portOverride = endpointPort.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    if (endpointPort.isNotBlank() && (portOverride == null || portOverride !in 1..65535)) {
      errorText = context.getString(R.string.warp_masque_error_port)
      return
    }
    val mtuValue = mtu.trim().toIntOrNull()
    if (mtuValue == null || mtuValue !in 576..16000) {
      errorText = context.getString(R.string.warp_masque_error_mtu)
      return
    }
    val idle = idleMinutes.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    if (idleMinutes.isNotBlank() && (idle == null || idle !in 1..1440)) {
      errorText = context.getString(R.string.warp_masque_error_idle)
      return
    }
    val keepAlive = keepAliveSeconds.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    if (keepAliveSeconds.isNotBlank() && (keepAlive == null || keepAlive !in 1..3600)) {
      errorText = context.getString(R.string.warp_masque_error_keepalive)
      return
    }

    val config = runCatching {
      WarpMasqueConfigFactory.build(
        account = account,
        options = WarpMasqueNodeOptions(
          name = safeName,
          vhttp = vhttp,
          server = endpoint.trim(),
          port = portOverride,
          sni = sni.trim(),
          mtu = mtuValue,
          idleTimeoutMinutes = idle,
          keepAliveSeconds = keepAlive,
        ),
        mixedPort = preferredMixedPort,
      )
    }.getOrElse {
      errorText = context.getString(R.string.warp_masque_error_config)
      return
    }
    onCreate(safeName, config, preferredMixedPort)
  }

  fun registerAndCreate() {
    if (busy) return
    errorText = null
    val cached = cachedAccount
    if (cached != null && !forceNew) {
      createWith(cached)
      return
    }

    busy = true
    scope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { WarpMasqueRegistrar().register() } }
      val account = result.getOrNull()
      if (account == null) {
        busy = false
        errorText = warpMasqueRegistrationError(context, result.exceptionOrNull())
        return@launch
      }
      actions.saveJsonData(MASQUE_ACCOUNT_PATH, account.toJson()) { saved ->
        busy = false
        if (!saved) {
          errorText = context.getString(R.string.warp_masque_error_cache_save)
          return@saveJsonData
        }
        cachedAccount = account
        cacheError = false
        forceNew = false
        createWith(account)
      }
    }
  }

  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(stringResource(R.string.warp_masque_title)) },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = maxHeight)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.warp_masque_desc),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
          cacheLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.warp_masque_cache_loading))
          }
          cachedAccount != null -> {
            val account = cachedAccount!!
            Text(stringResource(R.string.warp_masque_cache_ready), style = MaterialTheme.typography.labelLarge)
            Text(
              stringResource(
                R.string.warp_masque_cache_summary,
                account.clientV4.ifBlank { "—" },
                account.clientV6.ifBlank { "—" },
                account.server,
                account.port,
              ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          cacheError -> Text(
            stringResource(R.string.warp_masque_cache_unavailable),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
          else -> Text(
            stringResource(R.string.warp_masque_cache_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          enabled = !busy,
          label = { Text(stringResource(R.string.warp_masque_server_name)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Column {
          OutlinedButton(
            onClick = { vhttpMenu = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().tvFocusHighlight(RoundedCornerShape(18.dp), !busy),
          ) {
            Text(stringResource(R.string.warp_masque_transport_fmt, warpVhttpLabel(vhttp)))
          }
          DropdownMenu(expanded = vhttpMenu, onDismissRequest = { vhttpMenu = false }) {
            listOf("auto", "h3", "h2").forEach { mode ->
              DropdownMenuItem(
                text = { Text(warpVhttpLabel(mode)) },
                onClick = { vhttp = mode; vhttpMenu = false },
                modifier = Modifier.tvFocusHighlight(RoundedCornerShape(12.dp), true),
              )
            }
          }
        }

        OutlinedTextField(
          value = endpoint,
          onValueChange = { endpoint = it },
          enabled = !busy,
          label = { Text(stringResource(R.string.warp_masque_endpoint)) },
          supportingText = {
            Text(stringResource(R.string.warp_masque_endpoint_hint, cachedAccount?.server ?: "162.159.198.1"))
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = endpointPort,
          onValueChange = { endpointPort = it.filter(Char::isDigit).take(5) },
          enabled = !busy,
          label = { Text(stringResource(R.string.warp_masque_endpoint_port)) },
          supportingText = { Text(stringResource(R.string.warp_masque_endpoint_port_hint, cachedAccount?.port ?: 443)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = sni,
          onValueChange = { sni = it },
          enabled = !busy,
          label = { Text(stringResource(R.string.warp_masque_sni)) },
          supportingText = { Text(stringResource(R.string.warp_masque_sni_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = mtu,
          onValueChange = { mtu = it.filter(Char::isDigit).take(5) },
          enabled = !busy,
          label = { Text(stringResource(R.string.warp_masque_mtu)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = idleMinutes,
          onValueChange = { idleMinutes = it.filter(Char::isDigit).take(4) },
          enabled = !busy,
          label = { Text(stringResource(R.string.warp_masque_idle_minutes)) },
          supportingText = { Text(stringResource(R.string.warp_masque_optional_default)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = keepAliveSeconds,
          onValueChange = { keepAliveSeconds = it.filter(Char::isDigit).take(4) },
          enabled = !busy && vhttp != "h2",
          label = { Text(stringResource(R.string.warp_masque_keepalive_seconds)) },
          supportingText = {
            Text(stringResource(if (vhttp == "h2") R.string.warp_masque_keepalive_h2 else R.string.warp_masque_optional_default))
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.warp_masque_reregister), style = MaterialTheme.typography.labelLarge)
            Text(
              stringResource(R.string.warp_masque_reregister_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = forceNew,
            onCheckedChange = { forceNew = it },
            enabled = !busy,
            modifier = Modifier.tvFocusHighlight(RoundedCornerShape(18.dp), !busy),
          )
        }

        if (cachedAccount != null) {
          OutlinedButton(
            onClick = {
              if (!busy) {
                busy = true
                actions.deleteJsonPath(MASQUE_ACCOUNT_PATH) { ok ->
                  busy = false
                  if (ok) {
                    cachedAccount = null
                    cacheError = false
                    forceNew = false
                    errorText = null
                  } else {
                    errorText = context.getString(R.string.warp_masque_error_cache_clear)
                  }
                }
              }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().tvFocusHighlight(RoundedCornerShape(18.dp), !busy),
          ) {
            Text(stringResource(R.string.warp_masque_clear_cache))
          }
        }

        errorText?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = ::registerAndCreate,
        enabled = !busy && !cacheLoading,
        modifier = Modifier.tvFocusHighlight(RoundedCornerShape(18.dp), !busy && !cacheLoading),
      ) {
        if (busy) {
          CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
        }
        Text(
          stringResource(
            if (cachedAccount != null && !forceNew) R.string.warp_masque_create_cached
            else R.string.warp_masque_register_create
          )
        )
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = onDismiss,
        enabled = !busy,
        modifier = Modifier.tvFocusHighlight(RoundedCornerShape(18.dp), !busy),
      ) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}


private fun warpMasqueRegistrationError(context: android.content.Context, error: Throwable?): String {
  val warpError = error as? WarpMasqueException
  return when (warpError?.failure) {
    WarpMasqueFailure.NETWORK -> context.getString(R.string.warp_masque_error_network)
    WarpMasqueFailure.API -> {
      val code = warpError.httpCode
      if (code != null) context.getString(R.string.warp_masque_error_api_http, code)
      else context.getString(R.string.warp_masque_error_registration)
    }
    WarpMasqueFailure.RESPONSE -> context.getString(R.string.warp_masque_error_response)
    WarpMasqueFailure.CRYPTO -> context.getString(R.string.warp_masque_error_crypto)
    null -> context.getString(R.string.warp_masque_error_registration)
  }
}

@Composable
private fun warpVhttpLabel(mode: String): String = when (mode) {
  "h3" -> stringResource(R.string.warp_masque_transport_h3)
  "h2" -> stringResource(R.string.warp_masque_transport_h2)
  else -> stringResource(R.string.warp_masque_transport_auto)
}

private fun nextWarpMasqueName(existing: List<String>): String {
  val base = "WARP MASQUE"
  if (existing.none { it.equals(base, ignoreCase = true) }) return base
  var index = 2
  while (existing.any { it.equals("$base $index", ignoreCase = true) }) index += 1
  return "$base $index"
}
