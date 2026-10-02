package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.NonRootPortRegistry
import com.android.zdtd.service.NonRootTgWsConfig
import com.android.zdtd.service.NonRootTgWsStore
import com.android.zdtd.service.NonRootTgWsRuntimeState
import com.android.zdtd.service.tgwsplugin.TgWsPluginState
import com.android.zdtd.service.R

@Composable
internal fun NonRootTgWsSettingsScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  config: NonRootTgWsConfig,
  pluginState: TgWsPluginState,
  runtimeState: NonRootTgWsRuntimeState,
  runtimeLastError: String?,
  onConfigChange: (NonRootTgWsConfig) -> Unit,
  onPortChange: (Int) -> Boolean,
  onInstallOrUpdatePlugin: () -> Unit,
  onRefreshPlugin: () -> Unit,
) {
  val screenPadding = rememberAdaptiveScreenPadding()
  var draft by remember(config) { mutableStateOf(config) }
  var portText by remember(config.port) { mutableStateOf(config.port.toString()) }
  var advanced by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var frontingCooldownText by remember(config.frontingCooldown) { mutableStateOf(config.frontingCooldown.toString()) }
  var bufKbText by remember(config.bufKb) { mutableStateOf(config.bufKb.toString()) }
  var poolSizeText by remember(config.poolSize) { mutableStateOf(config.poolSize.toString()) }
  var maxConnectionsText by remember(config.maxConnections) { mutableStateOf(config.maxConnections.takeIf { it > 0 }?.toString().orEmpty()) }

  val portErrorText = stringResource(R.string.non_root_port_error)
  val secretErrorText = stringResource(R.string.non_root_tgws_secret_error)
  val fakeTlsErrorText = stringResource(R.string.non_root_tgws_faketls_error)

  val hasChanges =
    draft != config ||
      portText != config.port.toString() ||
      frontingCooldownText != config.frontingCooldown.toString() ||
      bufKbText != config.bufKb.toString() ||
      poolSizeText != config.poolSize.toString() ||
      maxConnectionsText != config.maxConnections.takeIf { it > 0 }?.toString().orEmpty()

  fun lines(value: List<String>) = value.joinToString("\n")
  fun parse(value: String) = value.replace(',', '\n').lines().map(String::trim).filter(String::isNotEmpty).distinct()

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = screenPadding,
      top = topContentPadding + 8.dp,
      end = screenPadding,
      bottom = bottomContentPadding + 12.dp,
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.non_root_tgws_plugin_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Text(
            text = if (pluginState.installed) {
              stringResource(R.string.non_root_tgws_plugin_installed_fmt, pluginState.installedVersionName.ifBlank { "?" })
            } else {
              stringResource(R.string.non_root_tgws_plugin_not_installed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (pluginState.latestVersionName.isNotBlank()) {
            Text(
              stringResource(R.string.non_root_tgws_plugin_latest_fmt, pluginState.latestVersionName),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (pluginState.installed) {
            val runtimeText = when (runtimeState) {
              NonRootTgWsRuntimeState.STOPPED -> stringResource(R.string.non_root_service_state_stopped)
              NonRootTgWsRuntimeState.STARTING -> stringResource(R.string.non_root_service_state_starting)
              NonRootTgWsRuntimeState.RUNNING -> stringResource(R.string.non_root_service_state_running)
              NonRootTgWsRuntimeState.ERROR -> stringResource(R.string.non_root_service_state_error)
            }
            Text(
              runtimeText,
              style = MaterialTheme.typography.bodySmall,
              color = if (runtimeState == NonRootTgWsRuntimeState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (runtimeState == NonRootTgWsRuntimeState.ERROR) {
              runtimeLastError?.takeIf { it.isNotBlank() }?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
              }
            }
          }
          if (pluginState.busy) {
            LinearProgressIndicator(
              progress = { pluginState.progressPercent.coerceIn(0, 100) / 100f },
              modifier = Modifier.fillMaxWidth(),
            )
          }
          pluginState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          }
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              if (!pluginState.signatureMismatch) {
                Button(
                  onClick = onInstallOrUpdatePlugin,
                  enabled = !pluginState.busy && !config.enabled,
                  modifier = Modifier.weight(1f),
                ) {
                  Text(stringResource(if (pluginState.installed) R.string.common_update else R.string.common_install))
                }
              }
              OutlinedButton(
                onClick = onRefreshPlugin,
                enabled = !pluginState.busy,
                modifier = Modifier.weight(1f),
              ) { Text(stringResource(R.string.action_refresh)) }
            }
          }
        }
      }
    }

    item {
      Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(200)),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.tgws_basic_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          Text(stringResource(R.string.tgws_basic_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.tgws_enable_title), fontWeight = FontWeight.SemiBold)
              Text(stringResource(R.string.tgws_host_local_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = draft.enabled && pluginState.installed, enabled = pluginState.installed, onCheckedChange = { draft = draft.copy(enabled = it) })
          }
          OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.tgws_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
          )
          OutlinedTextField(
            value = draft.secret,
            onValueChange = { draft = draft.copy(secret = it.filter { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' }.take(32)); error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.tgws_secret)) },
            singleLine = true,
          )
          OutlinedButton(
            onClick = { draft = draft.copy(secret = NonRootTgWsStore.generateSecret()) },
            modifier = Modifier.fillMaxWidth(),
          ) { Text(stringResource(R.string.tgws_generate_secret)) }
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.tgws_faketls_title), fontWeight = FontWeight.SemiBold)
              Text(stringResource(R.string.tgws_faketls_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = draft.fakeTlsEnabled, onCheckedChange = { draft = draft.copy(fakeTlsEnabled = it) })
          }
          if (draft.fakeTlsEnabled) {
            OutlinedTextField(
              value = draft.fakeTlsDomain,
              onValueChange = { draft = draft.copy(fakeTlsDomain = it) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.tgws_faketls_domain)) },
              singleLine = true,
            )
          }
          error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
          Button(
            onClick = {
              val port = portText.toIntOrNull()
              error = when {
                port == null || port !in NonRootPortRegistry.MIN_PORT..65535 -> portErrorText
                !NonRootTgWsStore.isValidSecret(draft.secret) -> secretErrorText
                draft.fakeTlsEnabled && draft.fakeTlsDomain.isBlank() -> fakeTlsErrorText
                port != config.port && !onPortChange(port) -> portErrorText
                else -> null
              }
              if (error == null) {
                onConfigChange(
                  draft.copy(
                    port = port!!,
                    frontingCooldown = frontingCooldownText.toLongOrNull()?.coerceAtLeast(0L) ?: 1800L,
                    bufKb = bufKbText.toIntOrNull()?.coerceIn(16, 65536) ?: 256,
                    poolSize = poolSizeText.toIntOrNull()?.coerceIn(1, 128) ?: 4,
                    maxConnections = maxConnectionsText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                  )
                )
              }
            },
            enabled = hasChanges,
            modifier = Modifier.fillMaxWidth(),
          ) { Text(stringResource(R.string.action_save)) }
        }
      }
    }

    item {
      OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.tgws_advanced_title))
      }
    }

    item {
      AnimatedVisibility(visible = advanced) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(22.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = lines(draft.dcIp), onValueChange = { draft = draft.copy(dcIp = parse(it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tgws_dc_ip)) }, minLines = 2)
            OutlinedTextField(value = lines(draft.mtprotoProxies), onValueChange = { draft = draft.copy(mtprotoProxies = parse(it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tgws_mtproto_proxies)) }, minLines = 2)
            OutlinedTextField(value = lines(draft.cfDomains), onValueChange = { draft = draft.copy(cfDomains = parse(it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tgws_cf_domains)) }, minLines = 2)
            OutlinedTextField(value = lines(draft.cfWorkerDomains), onValueChange = { draft = draft.copy(cfWorkerDomains = parse(it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tgws_cf_worker_domains)) }, minLines = 2)
            ToggleRow(stringResource(R.string.tgws_default_domains), draft.defaultDomains) { draft = draft.copy(defaultDomains = it) }
            ToggleRow(stringResource(R.string.tgws_cf_priority), draft.cfPriority) { draft = draft.copy(cfPriority = it) }
            ToggleRow(stringResource(R.string.tgws_cf_balance), draft.cfBalance) { draft = draft.copy(cfBalance = it) }
            OutlinedTextField(value = draft.frontingDomain, onValueChange = { draft = draft.copy(frontingDomain = it) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tgws_fronting_domain)) }, singleLine = true)
            OutlinedTextField(
              value = frontingCooldownText,
              onValueChange = { frontingCooldownText = it.filter(Char::isDigit).take(8) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.tgws_fronting_cooldown)) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
            )
            OutlinedTextField(
              value = bufKbText,
              onValueChange = { bufKbText = it.filter(Char::isDigit).take(8) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.tgws_buf_kb)) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
            )
            OutlinedTextField(
              value = poolSizeText,
              onValueChange = { poolSizeText = it.filter(Char::isDigit).take(6) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.tgws_pool_size)) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
            )
            OutlinedTextField(
              value = maxConnectionsText,
              onValueChange = { maxConnectionsText = it.filter(Char::isDigit).take(8) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.tgws_max_connections)) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
            )
            OutlinedTextField(value = draft.outboundProxy, onValueChange = { draft = draft.copy(outboundProxy = it) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tgws_outbound_proxy)) }, singleLine = true)
            OutlinedTextField(value = draft.noProxy, onValueChange = { draft = draft.copy(noProxy = it) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tgws_no_proxy)) }, singleLine = true)
            ToggleRow(stringResource(R.string.tgws_no_outbound_proxy), draft.noOutboundProxy) { draft = draft.copy(noOutboundProxy = it) }
            ToggleRow(stringResource(R.string.tgws_skip_tls_verify), draft.skipTlsVerify) { draft = draft.copy(skipTlsVerify = it) }
            ToggleRow(stringResource(R.string.tgws_verbose), draft.verbose) { draft = draft.copy(verbose = it, quiet = if (it) false else draft.quiet) }
            ToggleRow(stringResource(R.string.tgws_quiet), draft.quiet) { draft = draft.copy(quiet = it, verbose = if (it) false else draft.verbose) }
          }
        }
      }
    }
  }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}
