package com.android.zdtd.service.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class MieruSettingUi(
  val tun: String = "mrtun0",
  val socks5Port: Int = 2085,
  val rpcPort: Int = 8964,
  val mtu: Int? = null,
  val tun2proxyLogLevel: String = "info",
  val mieruLogLevel: String = "INFO",
)

private data class MieruConfigUi(
  val profileName: String = "",
  val username: String = "",
  val password: String = "",
  val ipAddress: String = "",
  val domainName: String = "",
  val serverPort: Int? = null,
  val protocol: String = "TCP",
  val mieruMtu: Int? = 1400,
  val multiplexingLevel: String = "MULTIPLEXING_HIGH",
  val handshakeMode: String = "HANDSHAKE_STANDARD",
)

private fun parseMieruSetting(obj: JSONObject?): MieruSettingUi {
  val data = obj?.optJSONObject("data") ?: obj
  return MieruSettingUi(
    tun = data?.optString("tun", "mrtun0")?.trim().orEmpty().ifBlank { "mrtun0" },
    socks5Port = data?.optInt("socks5_port", 2085)?.takeIf { it in 1025..65535 } ?: 2085,
    rpcPort = data?.optInt("rpc_port", 8964)?.takeIf { it in 1025..65535 } ?: 8964,
    mtu = data?.optInt("mtu", 0)?.takeIf { it in 1280..9000 },
    tun2proxyLogLevel = data?.optString("tun2proxy_loglevel", "info")?.trim().orEmpty().ifBlank { "info" },
    mieruLogLevel = data?.optString("mieru_loglevel", "INFO")?.trim().orEmpty().ifBlank { "INFO" },
  )
}

private fun MieruSettingUi.toJson(): JSONObject = JSONObject()
  .put("tun", tun.trim().ifBlank { "mrtun0" })
  .put("socks5_port", socks5Port)
  .put("rpc_port", rpcPort)
  .put("mtu", mtu ?: JSONObject.NULL)
  .put("tun2proxy_loglevel", tun2proxyLogLevel.trim().ifBlank { "info" })
  .put("mieru_loglevel", mieruLogLevel.trim().ifBlank { "INFO" })

private fun parseMieruConfig(raw: String?, fallbackProfile: String): MieruConfigUi {
  if (raw.isNullOrBlank()) return MieruConfigUi(profileName = fallbackProfile)
  return runCatching {
    val root = JSONObject(raw)
    val active = root.optString("activeProfile", "").trim()
    val profiles = root.optJSONArray("profiles") ?: JSONArray()
    var profileObj: JSONObject? = null
    for (i in 0 until profiles.length()) {
      val item = profiles.optJSONObject(i) ?: continue
      if (profileObj == null) profileObj = item
      val name = item.optString("profileName", "").trim()
      if (active.isNotEmpty() && name == active) {
        profileObj = item
        break
      }
    }
    val p = profileObj ?: JSONObject()
    val user = p.optJSONObject("user") ?: JSONObject()
    val server = p.optJSONArray("servers")?.optJSONObject(0) ?: JSONObject()
    val binding = server.optJSONArray("portBindings")?.optJSONObject(0) ?: JSONObject()
    val multiplexing = p.optJSONObject("multiplexing") ?: JSONObject()
    MieruConfigUi(
      profileName = p.optString("profileName", fallbackProfile).trim().ifBlank { fallbackProfile },
      username = user.optString("name", ""),
      password = user.optString("password", ""),
      ipAddress = server.optString("ipAddress", "").trim(),
      domainName = server.optString("domainName", "").trim(),
      serverPort = binding.optInt("port", 0).takeIf { it in 1..65535 },
      protocol = binding.optString("protocol", "TCP").trim().uppercase().let { if (it == "UDP") "UDP" else "TCP" },
      mieruMtu = p.optInt("mtu", 1400).takeIf { it in 1..9000 },
      multiplexingLevel = multiplexing.optString("level", "MULTIPLEXING_HIGH").trim().ifBlank { "MULTIPLEXING_HIGH" },
      handshakeMode = p.optString("handshakeMode", "HANDSHAKE_STANDARD").trim().ifBlank { "HANDSHAKE_STANDARD" },
    )
  }.getOrElse { MieruConfigUi(profileName = fallbackProfile) }
}

private fun MieruConfigUi.toJson(setting: MieruSettingUi, fallbackProfile: String): JSONObject {
  val effectiveProfile = profileName.trim().ifBlank { fallbackProfile.ifBlank { "default" } }
  val server = JSONObject()
  if (ipAddress.trim().isNotEmpty()) server.put("ipAddress", ipAddress.trim())
  if (domainName.trim().isNotEmpty()) server.put("domainName", domainName.trim())
  server.put(
    "portBindings",
    JSONArray().put(
      JSONObject()
        .put("port", serverPort ?: 443)
        .put("protocol", protocol.trim().uppercase().let { if (it == "UDP") "UDP" else "TCP" })
    )
  )

  val profileObj = JSONObject()
    .put("profileName", effectiveProfile)
    .put("user", JSONObject().put("name", username).put("password", password))
    .put("servers", JSONArray().put(server))
    .put("multiplexing", JSONObject().put("level", multiplexingLevel.trim().ifBlank { "MULTIPLEXING_HIGH" }))
    .put("handshakeMode", handshakeMode.trim().ifBlank { "HANDSHAKE_STANDARD" })
  mieruMtu?.let { profileObj.put("mtu", it) }

  return JSONObject()
    .put("profiles", JSONArray().put(profileObj))
    .put("activeProfile", effectiveProfile)
    .put("rpcPort", setting.rpcPort)
    .put("socks5Port", setting.socks5Port)
    .put("loggingLevel", setting.mieruLogLevel.trim().ifBlank { "INFO" })
    .put("socks5ListenLAN", false)
}

@Composable
fun MieruProfileScreen(
  programs: List<ApiModels.Program>,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val effectiveTopContentPadding = topContentPadding + 12.dp
  val effectiveBottomContentPadding = bottomContentPadding + if (compact) 12.dp else 16.dp
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val program = programs.firstOrNull { it.id == "mieru" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val basePath = "/api/programs/mieru/profiles/$profile"
  val scroll = rememberScrollState()

  var setting by remember(profile) { mutableStateOf(MieruSettingUi()) }
  var loaded by remember(profile) { mutableStateOf(false) }
  var configLoaded by remember(profile) { mutableStateOf(false) }
  var configSaving by remember(profile) { mutableStateOf(false) }
  var settingSaving by remember(profile) { mutableStateOf(false) }
  var syncedConfig by remember(profile) { mutableStateOf(MieruConfigUi(profileName = profile)) }
  var tab by remember(profile) { mutableStateOf(0) }
  var tunText by remember(profile) { mutableStateOf("mrtun0") }
  var socksText by remember(profile) { mutableStateOf("2085") }
  var rpcText by remember(profile) { mutableStateOf("8964") }
  var mtuText by remember(profile) { mutableStateOf("") }
  var t2pLogText by remember(profile) { mutableStateOf("info") }
  var mieruLogText by remember(profile) { mutableStateOf("INFO") }

  var mieruProfileText by remember(profile) { mutableStateOf(profile) }
  var usernameText by remember(profile) { mutableStateOf("") }
  var passwordText by remember(profile) { mutableStateOf("") }
  var ipText by remember(profile) { mutableStateOf("") }
  var domainText by remember(profile) { mutableStateOf("") }
  var serverPortText by remember(profile) { mutableStateOf("") }
  var protocolText by remember(profile) { mutableStateOf("TCP") }
  var mieruMtuText by remember(profile) { mutableStateOf("1400") }
  var multiplexingText by remember(profile) { mutableStateOf("MULTIPLEXING_HIGH") }
  var handshakeText by remember(profile) { mutableStateOf("HANDSHAKE_STANDARD") }

  fun showSnack(msg: String) { scope.launch { snackHost.showSnackbar(msg) } }

  fun applyConfigToFields(c: MieruConfigUi) {
    mieruProfileText = c.profileName.ifBlank { profile }
    usernameText = c.username
    passwordText = c.password
    ipText = c.ipAddress
    domainText = c.domainName
    serverPortText = c.serverPort?.toString().orEmpty()
    protocolText = if (c.protocol.uppercase() == "UDP") "UDP" else "TCP"
    mieruMtuText = c.mieruMtu?.toString().orEmpty()
    multiplexingText = c.multiplexingLevel
    handshakeText = c.handshakeMode
  }

  fun draftSettingOrNull(): MieruSettingUi? {
    val socks = socksText.trim().toIntOrNull() ?: return null
    val rpc = rpcText.trim().toIntOrNull() ?: return null
    val mtu = mtuText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    return MieruSettingUi(
      tun = tunText.trim(),
      socks5Port = socks,
      rpcPort = rpc,
      mtu = mtu,
      tun2proxyLogLevel = t2pLogText.trim().ifBlank { "info" },
      mieruLogLevel = mieruLogText.trim().ifBlank { "INFO" },
    )
  }

  fun draftConfigOrNull(): MieruConfigUi? {
    val serverPort = serverPortText.trim().toIntOrNull() ?: return null
    val proxyMtu = mieruMtuText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    return MieruConfigUi(
      profileName = mieruProfileText.trim().ifBlank { profile },
      username = usernameText.trim(),
      password = passwordText,
      ipAddress = ipText.trim(),
      domainName = domainText.trim(),
      serverPort = serverPort,
      protocol = protocolText.trim().uppercase().let { if (it == "UDP") "UDP" else "TCP" },
      mieruMtu = proxyMtu,
      multiplexingLevel = multiplexingText.trim().ifBlank { "MULTIPLEXING_HIGH" },
      handshakeMode = handshakeText.trim().ifBlank { "HANDSHAKE_STANDARD" },
    )
  }

  LaunchedEffect(profile) {
    actions.loadJsonData("$basePath/setting") { obj ->
      val s = parseMieruSetting(obj)
      setting = s
      tunText = s.tun
      socksText = s.socks5Port.toString()
      rpcText = s.rpcPort.toString()
      mtuText = s.mtu?.toString().orEmpty()
      t2pLogText = s.tun2proxyLogLevel
      mieruLogText = s.mieruLogLevel
      loaded = true
    }
    actions.loadText("$basePath/config") { raw ->
      val parsed = parseMieruConfig(raw, profile)
      syncedConfig = parsed
      applyConfigToFields(parsed)
      configLoaded = true
    }
  }

  fun saveSettings() {
    val socks = socksText.trim().toIntOrNull()
    val rpc = rpcText.trim().toIntOrNull()
    val mtu = mtuText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    when {
      tunText.trim().isEmpty() -> showSnack("TUN interface is required")
      socks == null || socks !in 1025..65535 -> showSnack("SOCKS5 port must be 1025..65535")
      rpc == null || rpc !in 1025..65535 -> showSnack("RPC port must be 1025..65535")
      socks == rpc -> showSnack("SOCKS5 port must not equal RPC port")
      mtuText.trim().isNotEmpty() && (mtu == null || mtu !in 1280..9000) -> showSnack("MTU must be empty or 1280..9000")
      else -> {
        val next = MieruSettingUi(
          tun = tunText.trim(),
          socks5Port = socks,
          rpcPort = rpc,
          mtu = mtu,
          tun2proxyLogLevel = t2pLogText.trim().ifBlank { "info" },
          mieruLogLevel = mieruLogText.trim().ifBlank { "INFO" },
        )
        settingSaving = true
        actions.saveJsonData("$basePath/setting", next.toJson()) { ok ->
          settingSaving = false
          if (ok) {
            setting = next
            showSnack(context.getString(R.string.saved))
          } else {
            showSnack(context.getString(R.string.save_failed))
          }
        }
      }
    }
  }

  fun saveMieruConfig() {
    val serverPort = serverPortText.trim().toIntOrNull()
    val proxyMtu = mieruMtuText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    when {
      usernameText.trim().isEmpty() -> showSnack("Username is required")
      passwordText.isEmpty() -> showSnack("Password is required")
      ipText.trim().isEmpty() && domainText.trim().isEmpty() -> showSnack("Server IP or domain is required")
      serverPort == null || serverPort !in 1..65535 -> showSnack("Server port must be 1..65535")
      mieruMtuText.trim().isNotEmpty() && (proxyMtu == null || proxyMtu !in 1..9000) -> showSnack("mieru MTU must be empty or 1..9000")
      else -> {
        val cfg = MieruConfigUi(
          profileName = mieruProfileText.trim().ifBlank { profile },
          username = usernameText.trim(),
          password = passwordText,
          ipAddress = ipText.trim(),
          domainName = domainText.trim(),
          serverPort = serverPort,
          protocol = protocolText,
          mieruMtu = proxyMtu,
          multiplexingLevel = multiplexingText.trim().ifBlank { "MULTIPLEXING_HIGH" },
          handshakeMode = handshakeText.trim().ifBlank { "HANDSHAKE_STANDARD" },
        )
        configSaving = true
        actions.saveText("$basePath/config", cfg.toJson(setting, profile).toString(2)) { ok ->
          configSaving = false
          if (ok) syncedConfig = cfg
          showSnack(if (ok) context.getString(R.string.saved) else context.getString(R.string.save_failed))
        }
      }
    }
  }

  val settingsDraft = draftSettingOrNull()
  val settingsDirty = loaded && settingsDraft != null && settingsDraft != setting
  val configDraft = draftConfigOrNull()
  val configDirty = configLoaded && configDraft != null && configDraft != syncedConfig

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(scroll)
      .padding(horizontal = if (compact) 12.dp else 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    MieruProfileEnabledCard(
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled("mieru", profile, v) },
    )

    MieruProfileTabs(
      selected = tab,
      onSelect = { tab = it },
    )

    when (tab) {
      0 -> AppListPickerCard(
        title = stringResource(R.string.apps_common_title),
        desc = stringResource(R.string.apps_common_desc),
        path = "$basePath/apps/user",
        actions = actions,
        snackHost = snackHost,
      programs = programs,
      )
      1 -> MieruSectionCard(
        title = "Настройки tun2proxy",
        desc = "SOCKS5 port — общий локальный порт между mieru и tun2proxy. CIDR назначается автоматически. MTU можно оставить пустым.",
        accent = Color(0xFF38BDF8),
        icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(21.dp)) },
      ) {
          OutlinedTextField(value = tunText, onValueChange = { tunText = it }, label = { Text("TUN interface") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = socksText, onValueChange = { socksText = it.filter { ch -> ch.isDigit() }.take(5) }, label = { Text("SOCKS5 backend port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = rpcText, onValueChange = { rpcText = it.filter { ch -> ch.isDigit() }.take(5) }, label = { Text("RPC port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = mtuText, onValueChange = { mtuText = it.filter { ch -> ch.isDigit() }.take(4) }, label = { Text("MTU, optional 1280–9000") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = t2pLogText, onValueChange = { t2pLogText = it }, label = { Text("tun2proxy log level") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(value = mieruLogText, onValueChange = { mieruLogText = it }, label = { Text("mieru log level") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          MieruSaveButton(
            dirty = settingsDirty,
            enabled = loaded && !settingSaving && settingsDirty,
            saving = settingSaving,
            onClick = { saveSettings() },
          )
      }
      2 -> MieruConfigFieldsCard(
        loaded = configLoaded,
        saving = configSaving,
        profileName = mieruProfileText,
        onProfileName = { mieruProfileText = it },
        username = usernameText,
        onUsername = { usernameText = it },
        password = passwordText,
        onPassword = { passwordText = it },
        ipAddress = ipText,
        onIpAddress = { ipText = it },
        domainName = domainText,
        onDomainName = { domainText = it },
        serverPort = serverPortText,
        onServerPort = { serverPortText = it.filter { ch -> ch.isDigit() }.take(5) },
        protocol = protocolText,
        onProtocol = { protocolText = it },
        mtu = mieruMtuText,
        onMtu = { mieruMtuText = it.filter { ch -> ch.isDigit() }.take(4) },
        multiplexing = multiplexingText,
        onMultiplexing = { multiplexingText = it },
        handshake = handshakeText,
        onHandshake = { handshakeText = it },
        dirty = configDirty,
        onSave = { saveMieruConfig() },
      )
    }

    Spacer(Modifier.height(effectiveBottomContentPadding))
  }
}

@Composable
private fun MieruSectionCard(
  title: String,
  desc: String? = null,
  accent: Color = Color(0xFF38BDF8),
  icon: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  content: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val compact = rememberIsCompactWidth()
  val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              accent.copy(alpha = 0.13f),
              MaterialTheme.colorScheme.surface.copy(alpha = 0.05f),
              Color.Transparent,
            )
          ),
          shape = shape,
        )
        .padding(if (compact) 12.dp else 14.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
        ) {
          if (icon != null) {
            Surface(
              modifier = Modifier.size(if (compact) 42.dp else 46.dp),
              shape = CircleShape,
              color = accent.copy(alpha = 0.16f),
              contentColor = accent,
              border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
            ) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon() }
            }
          }
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
              title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.93f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (desc != null) {
              Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          if (trailing != null) trailing()
        }
        if (content != null) content()
      }
    }
  }
}

@Composable
private fun MieruProfileEnabledCard(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val accent = if (checked) Color(0xFF22C55E) else Color(0xFFEF4444)
  MieruSectionCard(
    title = stringResource(R.string.enabled_card_profile_title),
    desc = stringResource(R.string.enabled_card_apply_hint),
    accent = accent,
    icon = { Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(22.dp)) },
    trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
  ) {
    Surface(
      shape = RoundedCornerShape(100.dp),
      color = accent.copy(alpha = 0.16f),
      contentColor = accent,
      border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
    ) {
      Text(
        text = stringResource(if (checked) R.string.enabled_state_on else R.string.enabled_state_off),
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun MieruProfileTabs(
  selected: Int,
  onSelect: (Int) -> Unit,
) {
  val compact = rememberIsCompactWidth()
  val tabs = listOf(
    0 to "Приложения",
    1 to "tun2proxy",
    2 to "mieru",
  )
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(5.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      tabs.forEach { (index, label) ->
        val active = selected == index
        Surface(
          modifier = Modifier
            .weight(1f)
            .clickable { onSelect(index) },
          shape = RoundedCornerShape(16.dp),
          color = if (active) Color(0xFF38BDF8).copy(alpha = 0.18f) else Color.Transparent,
          contentColor = if (active) Color(0xFF7DD3FC) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          border = if (active) BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.28f)) else null,
        ) {
          Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = if (compact) 8.dp else 9.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              label,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MieruConfigFieldsCard(
  loaded: Boolean,
  saving: Boolean,
  profileName: String,
  onProfileName: (String) -> Unit,
  username: String,
  onUsername: (String) -> Unit,
  password: String,
  onPassword: (String) -> Unit,
  ipAddress: String,
  onIpAddress: (String) -> Unit,
  domainName: String,
  onDomainName: (String) -> Unit,
  serverPort: String,
  onServerPort: (String) -> Unit,
  protocol: String,
  onProtocol: (String) -> Unit,
  mtu: String,
  onMtu: (String) -> Unit,
  multiplexing: String,
  onMultiplexing: (String) -> Unit,
  handshake: String,
  onHandshake: (String) -> Unit,
  dirty: Boolean,
  onSave: () -> Unit,
) {
  MieruSectionCard(
    title = "Настройки mieru proxy",
    desc = "Профиль, пользователь и сервер. Если название профиля пустое, будет использовано имя профиля ZDT-D. socks5Port/rpcPort синхронизируются из блока tun2proxy.",
    accent = Color(0xFFA78BFA),
    icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(21.dp)) },
  ) {
      OutlinedTextField(value = profileName, onValueChange = onProfileName, label = { Text("Profile name, optional") }, singleLine = true, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(value = username, onValueChange = onUsername, label = { Text("Username") }, singleLine = true, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(value = password, onValueChange = onPassword, label = { Text("Password") }, singleLine = true, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(value = ipAddress, onValueChange = onIpAddress, label = { Text("Server IP, optional if domain is set") }, singleLine = true, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(value = domainName, onValueChange = onDomainName, label = { Text("Server domain, optional if IP is set") }, singleLine = true, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(value = serverPort, onValueChange = onServerPort, label = { Text("Server port") }, singleLine = true, enabled = loaded && !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

      Text("Protocol", style = MaterialTheme.typography.labelLarge)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MieruProtocolButton(
          text = "TCP",
          selected = protocol == "TCP",
          enabled = loaded && !saving,
          onClick = { onProtocol("TCP") },
          modifier = Modifier.weight(1f),
        )
        MieruProtocolButton(
          text = "UDP",
          selected = protocol == "UDP",
          enabled = loaded && !saving,
          onClick = { onProtocol("UDP") },
          modifier = Modifier.weight(1f),
        )
      }

      OutlinedTextField(value = mtu, onValueChange = onMtu, label = { Text("mieru MTU, optional") }, singleLine = true, enabled = loaded && !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
      OutlinedTextField(value = multiplexing, onValueChange = onMultiplexing, label = { Text("Multiplexing level") }, singleLine = true, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(value = handshake, onValueChange = onHandshake, label = { Text("Handshake mode") }, singleLine = true, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth())

      MieruSaveButton(
        dirty = dirty,
        enabled = loaded && !saving && dirty,
        saving = saving,
        onClick = onSave,
      )
  }
}

@Composable
private fun MieruSaveButton(
  dirty: Boolean,
  enabled: Boolean,
  saving: Boolean,
  onClick: () -> Unit,
) {
  val container by animateColorAsState(
    targetValue = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    label = "mieruSaveContainer",
  )
  val content by animateColorAsState(
    targetValue = if (dirty) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    label = "mieruSaveContent",
  )
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.buttonColors(
      containerColor = container,
      contentColor = content,
      disabledContainerColor = container,
      disabledContentColor = content.copy(alpha = 0.72f),
    ),
  ) {
    Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
  }
}

@Composable
private fun MieruProtocolButton(
  text: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val container by animateColorAsState(
    targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    label = "mieruProtocolContainer",
  )
  val content by animateColorAsState(
    targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    label = "mieruProtocolContent",
  )
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier,
    colors = ButtonDefaults.buttonColors(
      containerColor = container,
      contentColor = content,
      disabledContainerColor = container.copy(alpha = 0.62f),
      disabledContentColor = content.copy(alpha = 0.62f),
    ),
  ) {
    Text(text)
  }
}

