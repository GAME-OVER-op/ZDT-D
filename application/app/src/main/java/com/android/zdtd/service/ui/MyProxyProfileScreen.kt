package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.coroutines.resume

private data class MyProxySettingUi(
  val t2sPort: Int?,
  val t2sWebPort: Int?,
)

private data class MyProxyUpstreamUi(
  val host: String,
  val port: Int?,
  val user: String,
  val pass: String,
)

private suspend fun awaitLoadJsonMyProxy(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonMyProxy(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private fun parseMyProxySettingUi(obj: JSONObject?): MyProxySettingUi {
  val data = obj?.optJSONObject("data") ?: obj
  return MyProxySettingUi(
    t2sPort = data?.optInt("t2s_port", 0)?.takeIf { it in 1..65535 },
    t2sWebPort = data?.optInt("t2s_web_port", 0)?.takeIf { it in 1..65535 },
  )
}

private fun parseMyProxyUpstreamUi(obj: JSONObject?): MyProxyUpstreamUi {
  val data = obj?.optJSONObject("data") ?: obj
  return MyProxyUpstreamUi(
    host = data?.optString("host", "")?.trim().orEmpty(),
    port = data?.optInt("port", 0)?.takeIf { it in 1..65535 },
    user = data?.optString("user", "")?.trim().orEmpty(),
    pass = data?.optString("pass", "") ?: "",
  )
}


@Composable
private fun FieldHint(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
  )
}

@Composable
fun MyProxyProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "myproxy" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val encodedProfile = remember(profile) { URLEncoder.encode(profile, "UTF-8") }
  val basePath = remember(encodedProfile) { "/api/programs/myproxy/profiles/$encodedProfile" }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  var loading by remember(profile) { mutableStateOf(true) }
  var settingSaving by remember(profile) { mutableStateOf(false) }
  var proxySaving by remember(profile) { mutableStateOf(false) }

  var syncedSetting by remember(profile) { mutableStateOf(MyProxySettingUi(null, null)) }
  var syncedProxy by remember(profile) { mutableStateOf(MyProxyUpstreamUi("", null, "", "")) }

  var t2sPortText by remember(profile) { mutableStateOf("") }
  var t2sWebPortText by remember(profile) { mutableStateOf("") }
  var hostText by remember(profile) { mutableStateOf("") }
  var proxyPortText by remember(profile) { mutableStateOf("") }
  var userText by remember(profile) { mutableStateOf("") }
  var passText by remember(profile) { mutableStateOf("") }

  var settingInitialized by remember(profile) { mutableStateOf(false) }
  var proxyInitialized by remember(profile) { mutableStateOf(false) }

  fun loadAll() {
    loading = true
    scope.launch {
      val settingObj = awaitLoadJsonMyProxy(actions, "$basePath/setting")
      val proxyObj = awaitLoadJsonMyProxy(actions, "$basePath/proxy")
      val parsedSetting = parseMyProxySettingUi(settingObj)
      val parsedProxy = parseMyProxyUpstreamUi(proxyObj)
      syncedSetting = parsedSetting
      syncedProxy = parsedProxy
      t2sPortText = parsedSetting.t2sPort?.toString().orEmpty()
      t2sWebPortText = parsedSetting.t2sWebPort?.toString().orEmpty()
      hostText = parsedProxy.host
      proxyPortText = parsedProxy.port?.toString().orEmpty()
      userText = parsedProxy.user
      passText = parsedProxy.pass
      settingInitialized = true
      proxyInitialized = true
      loading = false
    }
  }

  LaunchedEffect(profile) { loadAll() }

  LaunchedEffect(t2sPortText, t2sWebPortText, settingInitialized) {
    if (!settingInitialized) return@LaunchedEffect
    delay(700)
    val t2sPort = t2sPortText.trim().toIntOrNull()
    val t2sWebPort = t2sWebPortText.trim().toIntOrNull()
    val current = MyProxySettingUi(t2sPort, t2sWebPort)
    if (current == syncedSetting) return@LaunchedEffect
    if (t2sPort !in 1..65535 || t2sWebPort !in 1..65535) return@LaunchedEffect
    if (t2sPort == t2sWebPort) return@LaunchedEffect
    settingSaving = true
    val ok = awaitSaveJsonMyProxy(
      actions,
      "$basePath/setting",
      JSONObject().put("t2s_port", t2sPort).put("t2s_web_port", t2sWebPort)
    )
    settingSaving = false
    if (ok) syncedSetting = current else showSnack(context.getString(R.string.myproxy_auto_save_failed))
  }

  LaunchedEffect(hostText, proxyPortText, userText, passText, proxyInitialized) {
    if (!proxyInitialized) return@LaunchedEffect
    delay(700)
    val host = hostText.trim()
    val port = proxyPortText.trim().toIntOrNull()
    val user = userText.trim()
    val pass = passText
    val current = MyProxyUpstreamUi(host = host, port = port, user = user, pass = pass)
    if (current == syncedProxy) return@LaunchedEffect
    if (host.isBlank()) return@LaunchedEffect
    if (port !in 1..65535) return@LaunchedEffect
    if ((user.isBlank()) xor pass.isBlank()) return@LaunchedEffect
    proxySaving = true
    val ok = awaitSaveJsonMyProxy(
      actions,
      "$basePath/proxy",
      JSONObject().put("host", host).put("port", port).put("user", user).put("pass", pass)
    )
    proxySaving = false
    if (ok) syncedProxy = current else showSnack(context.getString(R.string.myproxy_auto_save_failed))
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("${program?.name ?: "myproxy"} / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
      stringResource(R.string.changes_apply_after_restart),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = stringResource(R.string.enabled_card_profile_title),
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("myproxy", profile, v) },
    )

    AppListPickerCard(
      title = stringResource(R.string.myproxy_apps_title),
      desc = stringResource(R.string.myproxy_apps_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
    )

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.myproxy_ports_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.myproxy_ports_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        FieldHint(stringResource(R.string.myproxy_t2s_port_hint))
        OutlinedTextField(
          value = t2sPortText,
          onValueChange = { t2sPortText = it.filter(Char::isDigit).take(5) },
          label = { Text(stringResource(R.string.myproxy_t2s_port_label)) },
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = t2sPortText.isNotBlank() && t2sPortText.toIntOrNull()?.let { it !in 1..65535 } != false,
        )
        FieldHint(stringResource(R.string.myproxy_t2s_web_port_hint))
        OutlinedTextField(
          value = t2sWebPortText,
          onValueChange = { t2sWebPortText = it.filter(Char::isDigit).take(5) },
          label = { Text(stringResource(R.string.myproxy_t2s_web_port_label)) },
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = t2sWebPortText.isNotBlank() && t2sWebPortText.toIntOrNull()?.let { it !in 1..65535 } != false,
        )
        val samePorts = t2sPortText.isNotBlank() && t2sPortText == t2sWebPortText
        if (samePorts) {
          Text(
            stringResource(R.string.myproxy_ports_must_differ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Text(
          if (settingSaving) stringResource(R.string.myproxy_ports_saving) else stringResource(R.string.myproxy_ports_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
      Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.myproxy_upstream_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.myproxy_upstream_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        FieldHint(stringResource(R.string.myproxy_host_hint))
        OutlinedTextField(
          value = hostText,
          onValueChange = { hostText = it },
          label = { Text(stringResource(R.string.myproxy_host_label)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          isError = hostText.isBlank(),
        )
        FieldHint(stringResource(R.string.myproxy_proxy_port_hint))
        OutlinedTextField(
          value = proxyPortText,
          onValueChange = { proxyPortText = it.filter(Char::isDigit).take(5) },
          label = { Text(stringResource(R.string.myproxy_proxy_port_label)) },
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = proxyPortText.isNotBlank() && proxyPortText.toIntOrNull()?.let { it !in 1..65535 } != false,
        )
        FieldHint(stringResource(R.string.myproxy_user_hint))
        OutlinedTextField(
          value = userText,
          onValueChange = { userText = it },
          label = { Text(stringResource(R.string.myproxy_user_label)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        FieldHint(stringResource(R.string.myproxy_pass_hint))
        OutlinedTextField(
          value = passText,
          onValueChange = { passText = it },
          label = { Text(stringResource(R.string.myproxy_pass_label)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        if ((userText.isBlank()) xor passText.isBlank()) {
          Text(
            stringResource(R.string.myproxy_credentials_pair_required),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Text(
          if (proxySaving) stringResource(R.string.myproxy_ports_saving) else stringResource(R.string.myproxy_ports_autosave_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }

    if (loading) {
      Spacer(Modifier.height(4.dp))
      CircularProgressIndicator()
    }
  }
}
