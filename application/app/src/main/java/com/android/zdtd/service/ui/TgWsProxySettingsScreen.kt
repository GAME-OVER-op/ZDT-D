package com.android.zdtd.service.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

private data class TgWsSettingUi(
  val port: String = "1443",
  val hostMode: String = "local",
  val secret: String = "",
  val fakeTlsEnabled: Boolean = false,
  val fakeTlsDomain: String = "",
  val defaultDomains: Boolean = false,
  val cfDomains: String = "",
  val cfWorkerDomains: String = "",
  val cfPriority: Boolean = false,
  val cfBalance: Boolean = false,
  val mtprotoProxies: String = "",
  val dcIp: String = "",
  val frontingDomain: String = "",
  val frontingCooldown: String = "1800",
  val bufKb: String = "256",
  val poolSize: String = "4",
  val maxConnections: String = "",
  val verbose: Boolean = false,
  val quiet: Boolean = false,
  val outboundProxy: String = "",
  val noOutboundProxy: Boolean = false,
  val noProxy: String = "",
  val skipTlsVerify: Boolean = false,
)

private data class TgWsPreviewUi(
  val commandLine: String = "",
  val localLink: String = "",
  val lanIp: String = "",
  val lanLink: String = "",
  val protectedByProxyInfo: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TgWsProxySettingsScreen(
  programs: List<ApiModels.Program>,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  val program = programs.firstOrNull { it.id == "tgwsproxy" } ?: ApiModels.Program("tgwsproxy", "Telegram WS Proxy", false, "single")

  var setting by remember { mutableStateOf(TgWsSettingUi()) }
  var enabled by remember(program.enabled) { mutableStateOf(program.enabled) }
  var loading by remember { mutableStateOf(true) }
  var saving by remember { mutableStateOf(false) }
  var preview by remember { mutableStateOf(TgWsPreviewUi()) }
  var advancedExpanded by remember { mutableStateOf(false) }

  fun showSnack(text: String) {
    scope.launch { snackHost.showSnackbar(text) }
  }

  fun copyText(text: String, label: String) {
    if (text.isBlank()) return
    clipboard.setText(AnnotatedString(text))
    showSnack(label)
  }

  fun openTelegramLink(link: String) {
    if (link.isBlank()) return
    runCatching {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
      showSnack(context.getString(R.string.tgws_open_link_failed))
    }
  }

  fun refreshPreview() {
    actions.loadJsonData("/api/programs/tgwsproxy/command") { obj ->
      preview = parseTgWsPreview(obj)
    }
  }

  fun loadAll() {
    loading = true
    actions.loadJsonData("/api/programs/tgwsproxy/setting") { obj ->
      setting = parseTgWsSetting(obj)
      loading = false
      refreshPreview()
    }
  }

  LaunchedEffect(Unit) { loadAll() }
  LaunchedEffect(program.enabled) { enabled = program.enabled }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = topContentPadding + 8.dp, bottom = bottomContentPadding + 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      TgWsHeaderCard(enabled = enabled)
    }

    item {
      TgWsSectionCard(
        title = stringResource(R.string.tgws_enable_title),
        desc = stringResource(R.string.tgws_enable_desc),
        accent = if (enabled) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary,
        icon = { Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(21.dp)) },
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(if (enabled) R.string.enabled else R.string.tgws_disabled), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.apply_after_restart_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
          }
          Switch(
            checked = enabled,
            onCheckedChange = { next ->
              enabled = next
              actions.setProgramEnabled("tgwsproxy", next) { ok ->
                if (!ok) {
                  enabled = !next
                  showSnack(context.getString(R.string.save_failed))
                }
              }
            },
          )
        }
      }
    }

    item {
      TgWsSectionCard(
        title = stringResource(R.string.tgws_basic_title),
        desc = stringResource(R.string.tgws_basic_desc),
        icon = { Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(21.dp)) },
      ) {
        OutlinedTextField(
          value = setting.port,
          onValueChange = { setting = setting.copy(port = it.filter(Char::isDigit).take(5)) },
          label = { Text(stringResource(R.string.tgws_port)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = setting.hostMode != "lan",
            onClick = { setting = setting.copy(hostMode = "local") },
            label = { Text(stringResource(R.string.tgws_host_local)) },
          )
          FilterChip(
            selected = setting.hostMode == "lan",
            onClick = { setting = setting.copy(hostMode = "lan") },
            label = { Text(stringResource(R.string.tgws_host_lan)) },
          )
        }
        Text(
          text = stringResource(if (setting.hostMode == "lan") R.string.tgws_host_lan_desc else R.string.tgws_host_local_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        OutlinedTextField(
          value = setting.secret,
          onValueChange = { setting = setting.copy(secret = it.trim().take(96)) },
          label = { Text(stringResource(R.string.tgws_secret)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { setting = setting.copy(secret = generateHexSecret()) }) { Text(stringResource(R.string.tgws_generate_secret)) }
          OutlinedButton(onClick = { copyText(setting.secret, context.getString(R.string.tgws_secret_copied)) }, enabled = setting.secret.isNotBlank()) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(7.dp))
            Text(stringResource(R.string.tgws_copy_secret))
          }
        }
      }
    }

    item {
      TgWsSectionCard(
        title = stringResource(R.string.tgws_link_title),
        desc = stringResource(R.string.tgws_link_desc),
      ) {
        val activeLink = if (setting.hostMode == "lan") preview.lanLink else preview.localLink
        val lanReady = setting.hostMode == "lan" && preview.lanLink.isNotBlank()
        Text(
          text = if (activeLink.isBlank()) stringResource(R.string.tgws_preview_empty) else activeLink,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )
        if (setting.hostMode == "lan") {
          Text(
            text = if (preview.lanIp.isBlank()) stringResource(R.string.tgws_lan_ip_missing) else stringResource(R.string.tgws_lan_ip_fmt, preview.lanIp),
            style = MaterialTheme.typography.bodySmall,
            color = if (preview.lanIp.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { copyText(activeLink, context.getString(R.string.tgws_link_copied)) }, enabled = activeLink.isNotBlank() && (setting.hostMode != "lan" || lanReady), modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(7.dp))
            Text(stringResource(if (setting.hostMode == "lan") R.string.tgws_copy_lan_link else R.string.tgws_copy_link))
          }
          OutlinedButton(onClick = { openTelegramLink(activeLink) }, enabled = activeLink.isNotBlank() && (setting.hostMode != "lan" || lanReady), modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.size(7.dp))
            Text(stringResource(R.string.tgws_open_link))
          }
        }
      }
    }

    item {
      TgWsSectionCard(
        title = stringResource(R.string.tgws_faketls_title),
        desc = stringResource(R.string.tgws_faketls_desc),
      ) {
        TgWsSwitchRow(
          title = stringResource(R.string.tgws_faketls_switch),
          desc = stringResource(R.string.tgws_faketls_switch_desc),
          checked = setting.fakeTlsEnabled,
          onCheckedChange = { setting = setting.copy(fakeTlsEnabled = it) },
        )
        if (setting.fakeTlsEnabled) {
          OutlinedTextField(
            value = setting.fakeTlsDomain,
            onValueChange = { setting = setting.copy(fakeTlsDomain = it.trim().take(180)) },
            label = { Text(stringResource(R.string.tgws_faketls_domain)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }

    item {
      TgWsSectionCard(title = stringResource(R.string.tgws_cloudflare_title), desc = stringResource(R.string.tgws_cloudflare_desc)) {
        TgWsSwitchRow(stringResource(R.string.tgws_default_domains), stringResource(R.string.tgws_default_domains_desc), setting.defaultDomains) { setting = setting.copy(defaultDomains = it) }
        OutlinedTextField(setting.cfDomains, { setting = setting.copy(cfDomains = it.take(800)) }, label = { Text(stringResource(R.string.tgws_cf_domains)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(setting.cfWorkerDomains, { setting = setting.copy(cfWorkerDomains = it.take(800)) }, label = { Text(stringResource(R.string.tgws_cf_worker_domains)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        TgWsSwitchRow(stringResource(R.string.tgws_cf_priority), stringResource(R.string.tgws_cf_priority_desc), setting.cfPriority) { setting = setting.copy(cfPriority = it) }
        TgWsSwitchRow(stringResource(R.string.tgws_cf_balance), stringResource(R.string.tgws_cf_balance_desc), setting.cfBalance) { setting = setting.copy(cfBalance = it) }
      }
    }

    item {
      TgWsSectionCard(title = stringResource(R.string.tgws_fallback_title), desc = stringResource(R.string.tgws_fallback_desc)) {
        OutlinedTextField(setting.mtprotoProxies, { setting = setting.copy(mtprotoProxies = it.take(1200)) }, label = { Text(stringResource(R.string.tgws_mtproto_proxies)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(setting.dcIp, { setting = setting.copy(dcIp = it.take(800)) }, label = { Text(stringResource(R.string.tgws_dc_ip)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(setting.frontingDomain, { setting = setting.copy(frontingDomain = it.trim().take(180)) }, label = { Text(stringResource(R.string.tgws_fronting_domain)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(setting.frontingCooldown, { setting = setting.copy(frontingCooldown = it.filter(Char::isDigit).take(8)) }, label = { Text(stringResource(R.string.tgws_fronting_cooldown)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
      }
    }

    item {
      TgWsSectionCard(title = stringResource(R.string.tgws_logs_title), desc = stringResource(R.string.tgws_logs_desc)) {
        TgWsSwitchRow(stringResource(R.string.tgws_verbose), stringResource(R.string.tgws_verbose_desc), setting.verbose) { setting = setting.copy(verbose = it, quiet = if (it) false else setting.quiet) }
        TgWsSwitchRow(stringResource(R.string.tgws_quiet), stringResource(R.string.tgws_quiet_desc), setting.quiet) { setting = setting.copy(quiet = it, verbose = if (it) false else setting.verbose) }
        Text("/data/adb/modules/ZDT-D/working_folder/tgwsproxy/log/tg-ws-proxy.log", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f))
      }
    }

    item {
      TgWsSectionCard(title = stringResource(R.string.tgws_advanced_title), desc = stringResource(R.string.tgws_advanced_desc)) {
        OutlinedButton(onClick = { advancedExpanded = !advancedExpanded }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(if (advancedExpanded) R.string.tgws_hide_advanced else R.string.tgws_show_advanced))
        }
        if (advancedExpanded) {
          OutlinedTextField(setting.bufKb, { setting = setting.copy(bufKb = it.filter(Char::isDigit).take(8)) }, label = { Text(stringResource(R.string.tgws_buf_kb)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(setting.poolSize, { setting = setting.copy(poolSize = it.filter(Char::isDigit).take(6)) }, label = { Text(stringResource(R.string.tgws_pool_size)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(setting.maxConnections, { setting = setting.copy(maxConnections = it.filter(Char::isDigit).take(8)) }, label = { Text(stringResource(R.string.tgws_max_connections)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(setting.outboundProxy, { setting = setting.copy(outboundProxy = it.trim().take(240)) }, label = { Text(stringResource(R.string.tgws_outbound_proxy)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          TgWsSwitchRow(stringResource(R.string.tgws_no_outbound_proxy), stringResource(R.string.tgws_no_outbound_proxy_desc), setting.noOutboundProxy) { setting = setting.copy(noOutboundProxy = it) }
          OutlinedTextField(setting.noProxy, { setting = setting.copy(noProxy = it.take(400)) }, label = { Text(stringResource(R.string.tgws_no_proxy)) }, modifier = Modifier.fillMaxWidth())
        }
      }
    }

    item {
      TgWsSectionCard(
        title = stringResource(R.string.tgws_danger_title),
        desc = stringResource(R.string.tgws_danger_desc),
        accent = MaterialTheme.colorScheme.error,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(21.dp)) },
      ) {
        TgWsSwitchRow(stringResource(R.string.tgws_skip_tls_verify), stringResource(R.string.tgws_skip_tls_verify_desc), setting.skipTlsVerify) { setting = setting.copy(skipTlsVerify = it) }
      }
    }

    item {
      TgWsSectionCard(title = stringResource(R.string.tgws_command_title), desc = stringResource(R.string.tgws_command_desc), icon = { Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(21.dp)) }) {
        Text(
          text = preview.commandLine.ifBlank { stringResource(R.string.tgws_preview_empty) },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
        )
        OutlinedButton(onClick = { copyText(preview.commandLine, context.getString(R.string.tgws_command_copied)) }, enabled = preview.commandLine.isNotBlank()) {
          Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
          Spacer(Modifier.size(7.dp))
          Text(stringResource(R.string.tgws_copy_command))
        }
      }
    }

    item {
      Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            val obj = setting.toJson()
            val err = validateTgWsSetting(obj)
            if (err != null) {
              showSnack(err)
              return@Button
            }
            saving = true
            actions.saveJsonData("/api/programs/tgwsproxy/setting", obj) { ok ->
              saving = false
              showSnack(context.getString(if (ok) R.string.saved else R.string.save_failed))
              if (ok) refreshPreview()
            }
          },
          enabled = !loading && !saving,
          modifier = Modifier.weight(1f),
        ) { Text(stringResource(if (saving) R.string.common_in_progress else R.string.action_save)) }
        OutlinedButton(onClick = { loadAll() }, enabled = !saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.retry)) }
      }
    }

    item { Spacer(Modifier.height(40.dp)) }
  }
}

@Composable
private fun TgWsHeaderCard(enabled: Boolean) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
  ) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Surface(modifier = Modifier.size(50.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.tg_ws_proxy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tgws_screen_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
      }
      Text(stringResource(if (enabled) R.string.enabled else R.string.tgws_disabled), style = MaterialTheme.typography.labelMedium, color = if (enabled) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun TgWsSectionCard(
  title: String,
  desc: String,
  modifier: Modifier = Modifier,
  accent: Color = MaterialTheme.colorScheme.primary,
  icon: @Composable (() -> Unit)? = null,
  content: @Composable Column.() -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(modifier = Modifier.size(42.dp), shape = CircleShape, color = accent.copy(alpha = 0.14f), border = BorderStroke(1.dp, accent.copy(alpha = 0.32f))) {
          Box(contentAlignment = Alignment.Center) { if (icon != null) icon() else Icon(Icons.Outlined.Settings, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
        }
      }
      content()
    }
  }
}

@Composable
private fun TgWsSwitchRow(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

private fun parseTgWsSetting(obj: JSONObject?): TgWsSettingUi {
  val secrets = obj?.optJSONArray("secrets")
  val secret = secrets?.optString(0, "").orEmpty()
  return TgWsSettingUi(
    port = obj?.optInt("port", 1443)?.toString() ?: "1443",
    hostMode = obj?.optString("host_mode", "local")?.takeIf { it == "lan" } ?: "local",
    secret = secret,
    fakeTlsEnabled = obj?.optBoolean("listen_faketls_enabled", false) == true,
    fakeTlsDomain = obj?.optString("listen_faketls_domain", "").orEmpty(),
    defaultDomains = obj?.optBoolean("default_domains", false) == true,
    cfDomains = joinJsonArray(obj?.optJSONArray("cf_domains")),
    cfWorkerDomains = joinJsonArray(obj?.optJSONArray("cf_worker_domains")),
    cfPriority = obj?.optBoolean("cf_priority", false) == true,
    cfBalance = obj?.optBoolean("cf_balance", false) == true,
    mtprotoProxies = joinJsonArray(obj?.optJSONArray("mtproto_proxies")),
    dcIp = joinJsonArray(obj?.optJSONArray("dc_ip")),
    frontingDomain = obj?.optString("fronting_domain", "").orEmpty(),
    frontingCooldown = obj?.optLong("fronting_cooldown", 1800L)?.toString() ?: "1800",
    bufKb = obj?.optInt("buf_kb", 256)?.toString() ?: "256",
    poolSize = obj?.optInt("pool_size", 4)?.toString() ?: "4",
    maxConnections = obj?.optInt("max_connections", 0)?.takeIf { it > 0 }?.toString().orEmpty(),
    verbose = obj?.optBoolean("verbose", false) == true,
    quiet = obj?.optBoolean("quiet", false) == true,
    outboundProxy = obj?.optString("outbound_proxy", "").orEmpty(),
    noOutboundProxy = obj?.optBoolean("no_outbound_proxy", false) == true,
    noProxy = obj?.optString("no_proxy", "").orEmpty(),
    skipTlsVerify = obj?.optBoolean("skip_tls_verify", false) == true,
  )
}

private fun parseTgWsPreview(obj: JSONObject?): TgWsPreviewUi = TgWsPreviewUi(
  commandLine = obj?.optString("command_line", "").orEmpty(),
  localLink = obj?.optString("local_link", "").orEmpty(),
  lanIp = obj?.optString("lan_ip", "").orEmpty(),
  lanLink = obj?.optString("lan_link", "").orEmpty(),
  protectedByProxyInfo = obj?.optBoolean("protected_by_proxyinfo", true) != false,
)

private fun TgWsSettingUi.toJson(): JSONObject {
  val host = if (hostMode == "lan") "0.0.0.0" else "127.0.0.1"
  return JSONObject()
    .put("port", port.toIntOrNull() ?: 1443)
    .put("host_mode", if (hostMode == "lan") "lan" else "local")
    .put("host", host)
    .put("link_ip", "")
    .put("secrets", JSONArray().also { arr -> if (secret.isNotBlank()) arr.put(secret.trim()) })
    .put("listen_faketls_enabled", fakeTlsEnabled)
    .put("listen_faketls_domain", fakeTlsDomain.trim())
    .put("dc_ip", linesToJsonArray(dcIp))
    .put("buf_kb", bufKb.toIntOrNull() ?: 256)
    .put("pool_size", poolSize.toIntOrNull() ?: 4)
    .put("max_connections", maxConnections.toIntOrNull() ?: 0)
    .put("verbose", verbose && !quiet)
    .put("quiet", quiet)
    .put("log_file", "")
    .put("mtproto_proxies", linesToJsonArray(mtprotoProxies))
    .put("cf_domains", linesToJsonArray(cfDomains))
    .put("cf_worker_domains", linesToJsonArray(cfWorkerDomains))
    .put("cf_priority", cfPriority)
    .put("cf_balance", cfBalance)
    .put("default_domains", defaultDomains)
    .put("fronting_domain", frontingDomain.trim())
    .put("fronting_cooldown", frontingCooldown.toLongOrNull() ?: 1800L)
    .put("ws_connect_timeout", 10)
    .put("ws_fail_probe_timeout", 2)
    .put("ws_fail_cooldown", 30)
    .put("ws_redirect_cooldown", 300)
    .put("handshake_timeout", 10)
    .put("tcp_fallback_timeout", 10)
    .put("upstream_connect_timeout", 5)
    .put("upstream_fail_cooldown", 60)
    .put("cf_connect_timeout", 10)
    .put("cf_fail_cooldown", 60)
    .put("pool_max_age", 55)
    .put("outbound_proxy", outboundProxy.trim())
    .put("no_outbound_proxy", noOutboundProxy)
    .put("no_proxy", noProxy.trim())
    .put("skip_tls_verify", skipTlsVerify)
}

private fun validateTgWsSetting(obj: JSONObject): String? {
  val port = obj.optInt("port", 0)
  if (port !in 1..65535) return "Port must be 1..65535"
  val secrets = obj.optJSONArray("secrets")
  val secret = secrets?.optString(0, "").orEmpty()
  if (secret.isBlank()) return "Secret is required"
  if (!Regex("^[0-9a-fA-F]{32}$").matches(secret)) return "Secret must be 32 hex chars"
  if (obj.optBoolean("listen_faketls_enabled", false) && obj.optString("listen_faketls_domain", "").isBlank()) return "FakeTLS domain is required"
  return null
}

private fun joinJsonArray(arr: JSONArray?): String {
  if (arr == null) return ""
  return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }.joinToString("\n")
}

private fun linesToJsonArray(text: String): JSONArray {
  val arr = JSONArray()
  text.replace(',', '\n').lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { arr.put(it) }
  return arr
}

private fun generateHexSecret(): String {
  val chars = "0123456789abcdef"
  return buildString(32) { repeat(32) { append(chars[Random.nextInt(chars.length)]) } }
}
