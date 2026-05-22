package com.android.zdtd.service.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

private data class AmneziaWgProfileInfo(
  val name: String,
  val enabled: Boolean,
)

private data class AmneziaWgSettingUi(
  val tun: String = "awg1",
  val address: List<String> = listOf("172.16.0.2/32"),
  val dns: List<String> = listOf("1.1.1.1", "1.0.0.1"),
  val mtu: Int = 1280,
  val endpointResolve: Boolean = false,
  val stripFwmark: Boolean = false,
)

private val amneziaWgProfileNameRegex = Regex("^[A-Za-z0-9_-]{1,10}$")
private val amneziaWgTunRegex = Regex("^[A-Za-z0-9_.-]{1,15}$")
private const val AMNEZIAWG_AUTOSAVE_DELAY_MS = 1500L

private suspend fun awaitLoadJsonAmneziaWg(actions: ZdtdActions, path: String): JSONObject? =
  suspendCancellableCoroutine { cont -> actions.loadJsonData(path) { cont.resume(it) } }

private suspend fun awaitLoadTextAmneziaWg(actions: ZdtdActions, path: String): String? =
  suspendCancellableCoroutine { cont -> actions.loadText(path) { cont.resume(it) } }

private suspend fun awaitSaveJsonAmneziaWg(actions: ZdtdActions, path: String, obj: JSONObject): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveJsonData(path, obj) { cont.resume(it) } }

private suspend fun awaitSaveTextAmneziaWg(actions: ZdtdActions, path: String, content: String): Boolean =
  suspendCancellableCoroutine { cont -> actions.saveText(path, content) { cont.resume(it) } }

private suspend fun awaitUploadAmneziaWgConfig(actions: ZdtdActions, profile: String, filename: String, file: File): Boolean =
  suspendCancellableCoroutine { cont -> actions.uploadAmneziaWgConfig(profile, filename, file) { cont.resume(it) } }

private fun amneziaWgProfilePath(profile: String): String =
  "/api/programs/amneziawg/profiles/${URLEncoder.encode(profile, "UTF-8")}"

private fun parseAmneziaWgSetting(obj: JSONObject?): AmneziaWgSettingUi {
  val data = obj?.optJSONObject("data") ?: obj?.optJSONObject("setting") ?: obj
  return AmneziaWgSettingUi(
    tun = data?.optString("tun", "awg1")?.trim().orEmpty().ifBlank { "awg1" },
    address = readStringArray(data, "address").takeIf { it.isNotEmpty() } ?: listOf("172.16.0.2/32"),
    dns = readStringArray(data, "dns").takeIf { it.isNotEmpty() } ?: listOf("1.1.1.1", "1.0.0.1"),
    mtu = data?.optInt("mtu", 1280)?.takeIf { it in 576..9000 } ?: 1280,
    endpointResolve = data?.optBoolean("endpoint_resolve", false) ?: false,
    stripFwmark = data?.optBoolean("strip_fwmark", false) ?: false,
  )
}

private fun readStringArray(obj: JSONObject?, key: String): List<String> {
  val arr = obj?.optJSONArray(key) ?: return emptyList()
  return buildList {
    for (i in 0 until arr.length()) {
      val value = arr.optString(i, "").trim()
      if (value.isNotEmpty()) add(value)
    }
  }
}

private fun buildAmneziaWgSettingJson(setting: AmneziaWgSettingUi): JSONObject {
  val address = JSONArray()
  setting.address.forEach { address.put(it) }
  val dns = JSONArray()
  setting.dns.forEach { dns.put(it) }
  return JSONObject()
    .put("tun", setting.tun.trim())
    .put("address", address)
    .put("dns", dns)
    .put("mtu", setting.mtu)
    .put("endpoint_resolve", setting.endpointResolve)
    .put("strip_fwmark", setting.stripFwmark)
}

private fun parseAmneziaWgAddressInput(raw: String): List<String>? {
  val parts = splitAmneziaWgListInput(raw)
  if (parts.isEmpty() || parts.size > 4) return null
  if (parts.distinct().size != parts.size) return null
  return parts.takeIf { it.all(::isValidIpv4CidrLiteral) }
}

private fun parseAmneziaWgDnsInput(raw: String): List<String>? {
  val parts = splitAmneziaWgListInput(raw)
  if (parts.isEmpty() || parts.size > 8) return null
  if (parts.distinct().size != parts.size) return null
  return parts.takeIf { it.all(::isValidIpv4LiteralAmneziaWg) }
}

private fun splitAmneziaWgListInput(raw: String): List<String> = raw
  .split(Regex("[\\s,]+"))
  .map { it.trim() }
  .filter { it.isNotEmpty() }

private fun isValidIpv4CidrLiteral(value: String): Boolean {
  if (value.contains(":")) return false
  val parts = value.split('/')
  if (parts.size != 2) return false
  val prefix = parts[1].toIntOrNull() ?: return false
  return prefix in 0..32 && isValidIpv4LiteralAmneziaWg(parts[0])
}

private fun isValidIpv4LiteralAmneziaWg(value: String): Boolean {
  if (value.contains(":") || value.contains("/") || value.contains("://")) return false
  val parts = value.split('.')
  if (parts.size != 4) return false
  return parts.all { part ->
    part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
  }
}

private fun isValidAmneziaWgTun(value: String): Boolean {
  val v = value.trim()
  val lower = v.lowercase(Locale.ROOT)
  if (!amneziaWgTunRegex.matches(v)) return false
  if (lower == "lo" || lower == "dummy0") return false
  return !(lower.startsWith("wlan") || lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("eth") || lower.startsWith("ap") || lower.startsWith("rndis"))
}

private fun amneziaWgConfigWarnings(config: String): List<String> {
  var inInterface = false
  var inPeer = false
  var hasPrivateKey = false
  var hasPeerPublicKey = false
  config.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
    .forEach { line ->
      when {
        line.equals("[Interface]", ignoreCase = true) -> {
          inInterface = true
          inPeer = false
        }
        line.equals("[Peer]", ignoreCase = true) -> {
          inInterface = false
          inPeer = true
        }
        line.startsWith("[") && line.endsWith("]") -> {
          inInterface = false
          inPeer = false
        }
        inInterface && line.substringBefore('=').trim().equals("PrivateKey", ignoreCase = true) -> hasPrivateKey = true
        inPeer && line.substringBefore('=').trim().equals("PublicKey", ignoreCase = true) -> hasPeerPublicKey = true
      }
    }
  return buildList {
    if (!hasPrivateKey) add("client.conf: [Interface] PrivateKey not found")
    if (!hasPeerPublicKey) add("client.conf: [Peer] PublicKey not found")
  }
}

private fun amneziaWgUriDisplayName(context: Context, uri: Uri): String? = runCatching {
  context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
    if (c.moveToFirst()) c.getString(0) else null
  }
}.getOrNull()

private fun readAmneziaWgTextFromUri(context: Context, uri: Uri): String? = runCatching {
  context.contentResolver.openInputStream(uri)?.use { input ->
    input.bufferedReader(Charsets.UTF_8).use { it.readText() }
  }
}.getOrNull()

private fun copyAmneziaWgUriToTempFile(context: Context, uri: Uri, displayName: String): File? {
  val suffix = displayName.substringAfterLast('.', "conf").let { ".${it.take(16).ifBlank { "conf" }}" }
  val tmp = runCatching { File.createTempFile("amneziawg_config_", suffix, context.cacheDir) }.getOrNull() ?: return null
  return try {
    context.contentResolver.openInputStream(uri)?.use { input ->
      tmp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
    } ?: return null
    tmp
  } catch (_: Throwable) {
    runCatching { tmp.delete() }
    null
  }
}

private fun amneziaWgProfileIndex(name: String): Int {
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  return Int.MIN_VALUE
}


@Composable
private fun AmneziaWgSectionCard(
  title: String,
  desc: String? = null,
  accent: Color = Color(0xFF22C55E),
  icon: @Composable (() -> Unit)? = null,
  trailing: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  content: @Composable (() -> Unit)? = null,
) {
  val compact = rememberIsCompactWidth()
  val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)
  Surface(
    modifier = modifier.fillMaxWidth(),
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
private fun AmneziaWgProfileEnabledCard(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val accent = if (checked) Color(0xFF22C55E) else Color(0xFFEF4444)
  AmneziaWgSectionCard(
    title = stringResource(R.string.enabled_card_profile_title),
    desc = stringResource(R.string.enabled_card_apply_hint),
    accent = accent,
    icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(22.dp)) },
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
fun AmneziaWgProgramScreen(
  programs: List<ApiModels.Program>,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "amneziawg" }
  val effectiveTopContentPadding = topContentPadding + 6.dp
  val effectiveBottomContentPadding = bottomContentPadding + 80.dp
  var showCreate by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val details = remember(program?.profiles) {
    program?.profiles.orEmpty()
      .map { AmneziaWgProfileInfo(name = it.name, enabled = it.enabled) }
      .sortedWith(compareByDescending<AmneziaWgProfileInfo> { amneziaWgProfileIndex(it.name) }.thenByDescending { it.name.lowercase(Locale.ROOT) })
  }

  if (showCreate) {
    AmneziaWgCreateProfileDialog(
      existing = program?.profiles.orEmpty().map { it.name },
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.createNamedProfile("amneziawg", name) { created ->
          if (created != null) {
            scope.launch {
              val usedTuns = loadUsedVpnTunNames(actions, programs, excludeProgramId = "amneziawg", excludeProfile = created)
              val tun = nextFreeVpnTunName(usedTuns, prefix = "awg")
              val usedIpv4 = loadUsedVpnIpv4Cidrs(actions, programs, excludeProgramId = "amneziawg", excludeProfile = created)
              val address = nextFreeVpnIpv4Cidr(usedIpv4)
              val ok = awaitSaveJsonVpnTunGuard(
                actions,
                "${vpnProfileApiPath("amneziawg", created)}/setting",
                defaultAmneziaWgSettingJson(tun, address),
              )
              showSnack(
                if (ok) context.getString(R.string.amneziawg_profile_created, created)
                else context.getString(R.string.save_failed)
              )
              actions.refreshPrograms()
              onOpenProfile("amneziawg", created)
            }
          } else {
            showSnack(context.getString(R.string.create_failed))
          }
        }
      },
    )
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(top = effectiveTopContentPadding)
      .padding(horizontal = if (compact) 12.dp else 16.dp)
      .verticalScroll(scroll)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    ProgramDescriptionHeader(
      programId = "amneziawg",
      description = stringResource(R.string.amneziawg_program_hint),
      isProfiles = true,
    )

    CreateProfileCard(onAdd = { showCreate = true })

    ProfilesSectionTitle()

    if (details.isEmpty()) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.amneziawg_no_profiles_title), fontWeight = FontWeight.SemiBold)
          Text(stringResource(R.string.amneziawg_no_profiles_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
      }
    }

    details.forEach { info ->
      ProfileStatusCard(
        programId = "amneziawg",
        profileName = info.name,
        checked = info.enabled,
        onOpen = { onOpenProfile("amneziawg", info.name) },
        onCheckedChange = { checked ->
          actions.setProfileEnabled("amneziawg", info.name, checked) { ok ->
            showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
            if (ok) actions.refreshPrograms()
          }
        },
        onDelete = {
          actions.deleteProfile("amneziawg", info.name) { ok ->
            showSnack(if (ok) context.getString(R.string.deleted) else context.getString(R.string.delete_failed))
            if (ok) actions.refreshPrograms()
          }
        },
      )
    }

    Spacer(Modifier.height(effectiveBottomContentPadding))
  }
}

@Composable
private fun AmneziaWgCreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  StyledCreateProfileDialog(
    existing = existing,
    onDismiss = onDismiss,
    onCreate = onCreate,
    titleRes = R.string.amneziawg_create_profile_title,
    rulesRes = R.string.amneziawg_profile_name_rules,
    invalidNameRes = R.string.amneziawg_profile_name_invalid,
    validator = { name -> amneziaWgProfileNameRegex.matches(name) },
  )
}

@Composable
fun AmneziaWgProfileScreen(
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
  val scroll = rememberScrollState()
  val program = programs.firstOrNull { it.id == "amneziawg" }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val basePath = remember(profile) { amneziaWgProfilePath(profile) }

  var loading by remember(profile) { mutableStateOf(true) }
  var uploading by remember(profile) { mutableStateOf(false) }
  var tunText by remember(profile) { mutableStateOf("awg1") }
  var addressText by remember(profile) { mutableStateOf("172.16.0.2/32") }
  var dnsText by remember(profile) { mutableStateOf("1.1.1.1 1.0.0.1") }
  var mtuText by remember(profile) { mutableStateOf("1280") }
  var endpointResolve by remember(profile) { mutableStateOf(false) }
  var stripFwmark by remember(profile) { mutableStateOf(false) }
  var configText by remember(profile) { mutableStateOf("") }
  var syncedSetting by remember(profile) { mutableStateOf(AmneziaWgSettingUi()) }
  var syncedConfig by remember(profile) { mutableStateOf("") }
  var settingInitialized by remember(profile) { mutableStateOf(false) }
  var configInitialized by remember(profile) { mutableStateOf(false) }
  var appCount by remember(profile) { mutableStateOf(0) }
  var usedVpnTuns by remember(profile) { mutableStateOf(emptySet<String>()) }
  var usedVpnIpv4Cidrs by remember(profile) { mutableStateOf(emptyList<VpnIpv4Use>()) }
  var showConfigEditor by remember(profile) { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun reload() {
    loading = true
    settingInitialized = false
    configInitialized = false
    scope.launch {
      val usedTuns = loadUsedVpnTunNames(actions, programs, excludeProgramId = "amneziawg", excludeProfile = profile)
      val usedIpv4 = loadUsedVpnIpv4Cidrs(actions, programs, excludeProgramId = "amneziawg", excludeProfile = profile)
      val rawSetting = awaitLoadJsonAmneziaWg(actions, "$basePath/setting")
      val loaded = parseAmneziaWgSetting(rawSetting)
      val settingWithTun = if (isVpnTunNameUsed(loaded.tun, usedTuns)) loaded.copy(tun = nextFreeVpnTunName(usedTuns, prefix = "awg")) else loaded
      val setting = if (vpnIpv4CidrsOverlapEachOther(settingWithTun.address) || vpnIpv4CidrsConflict(settingWithTun.address, usedIpv4)) {
        settingWithTun.copy(address = listOf(nextFreeVpnIpv4Cidr(usedIpv4)))
      } else {
        settingWithTun
      }
      val loadedConfig = awaitLoadTextAmneziaWg(actions, "$basePath/config").orEmpty()
      val apps = parsePkgList(awaitLoadTextAmneziaWg(actions, "$basePath/apps/user").orEmpty()).size

      usedVpnTuns = usedTuns
      usedVpnIpv4Cidrs = usedIpv4
      syncedSetting = loaded
      tunText = setting.tun
      addressText = setting.address.joinToString(" ")
      dnsText = setting.dns.joinToString(" ")
      mtuText = setting.mtu.toString()
      endpointResolve = setting.endpointResolve
      stripFwmark = setting.stripFwmark
      settingInitialized = true

      syncedConfig = loadedConfig
      configText = loadedConfig
      configInitialized = true

      appCount = apps
      loading = false
    }
  }

  LaunchedEffect(profile) { reload() }

  val fileLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      val fileName = amneziaWgUriDisplayName(context, uri) ?: "client.conf"
      if (!fileName.lowercase(Locale.ROOT).endsWith(".conf")) {
        showSnack(context.getString(R.string.amneziawg_upload_ext_error))
        return@rememberLauncherForActivityResult
      }
      val localText = readAmneziaWgTextFromUri(context, uri)
      val tmp = copyAmneziaWgUriToTempFile(context, uri, fileName)
      if (localText == null || tmp == null) {
        showSnack(context.getString(R.string.common_upload_failed))
        return@rememberLauncherForActivityResult
      }
      configText = localText
      uploading = true
      scope.launch {
        val ok = try {
          awaitUploadAmneziaWgConfig(actions, profile, fileName, tmp)
        } finally {
          runCatching { tmp.delete() }
        }
        uploading = false
        if (ok) {
          showSnack(context.getString(R.string.saved_apply_after_restart))
          reload()
        } else {
          showSnack(context.getString(R.string.common_upload_failed))
        }
      }
    },
  )

  val addressParsed = remember(addressText) { parseAmneziaWgAddressInput(addressText) }
  val addressSelfOverlap = remember(addressParsed) { addressParsed?.let { vpnIpv4CidrsOverlapEachOther(it) } == true }
  val addressConflict = remember(addressParsed, usedVpnIpv4Cidrs) { addressParsed?.let { vpnIpv4CidrsConflict(it, usedVpnIpv4Cidrs) } == true }
  val addressValid = remember(addressParsed, addressSelfOverlap, addressConflict) { addressParsed != null && !addressSelfOverlap && !addressConflict }
  val dnsParsed = remember(dnsText) { parseAmneziaWgDnsInput(dnsText) }
  val mtuParsed = remember(mtuText) { mtuText.toIntOrNull()?.takeIf { it in 576..9000 } }
  val tunNameConflict = remember(tunText, usedVpnTuns) { isVpnTunNameUsed(tunText, usedVpnTuns) }
  val tunValid = remember(tunText, tunNameConflict) { isValidAmneziaWgTun(tunText) && !tunNameConflict }
  val configBlank = configText.trim().isBlank()
  val configWarnings = remember(configText) { if (configBlank) emptyList() else amneziaWgConfigWarnings(configText) }
  val configLineCount = remember(configText) { configText.lines().count { it.isNotBlank() } }

  LaunchedEffect(tunText, addressText, dnsText, mtuText, endpointResolve, stripFwmark, settingInitialized) {
    if (!settingInitialized || loading) return@LaunchedEffect
    delay(AMNEZIAWG_AUTOSAVE_DELAY_MS)
    val address = parseAmneziaWgAddressInput(addressText) ?: return@LaunchedEffect
    if (vpnIpv4CidrsOverlapEachOther(address) || vpnIpv4CidrsConflict(address, usedVpnIpv4Cidrs)) return@LaunchedEffect
    val dns = parseAmneziaWgDnsInput(dnsText) ?: return@LaunchedEffect
    val mtu = mtuText.toIntOrNull()?.takeIf { it in 576..9000 } ?: return@LaunchedEffect
    if (!isValidAmneziaWgTun(tunText) || isVpnTunNameUsed(tunText, usedVpnTuns)) return@LaunchedEffect
    val current = AmneziaWgSettingUi(
      tun = tunText.trim(),
      address = address,
      dns = dns,
      mtu = mtu,
      endpointResolve = endpointResolve,
      stripFwmark = stripFwmark,
    )
    if (current == syncedSetting) return@LaunchedEffect
    val ok = awaitSaveJsonAmneziaWg(actions, "$basePath/setting", buildAmneziaWgSettingJson(current))
    if (ok) {
      syncedSetting = current
    } else {
      showSnack(context.getString(R.string.save_failed))
    }
  }

  LaunchedEffect(configText, configInitialized) {
    if (!configInitialized || loading || uploading) return@LaunchedEffect
    delay(AMNEZIAWG_AUTOSAVE_DELAY_MS)
    if (configText == syncedConfig) return@LaunchedEffect
    if (configText.trim().isBlank()) return@LaunchedEffect
    val ok = awaitSaveTextAmneziaWg(actions, "$basePath/config", configText)
    if (ok) {
      showSnack(context.getString(R.string.amneziawg_config_normalized))
      reload()
    } else {
      showSnack(context.getString(R.string.save_failed))
    }
  }

  if (showConfigEditor) {
    AmneziaWgConfigEditorDialog(
      profile = profile,
      text = configText,
      loading = loading,
      saving = uploading,
      warnings = configWarnings,
      isEmpty = configBlank,
      onTextChange = { configText = it },
      onUpload = { fileLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
      onDismiss = { showConfigEditor = false },
    )
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(scroll)
      .padding(horizontal = if (compact) 12.dp else 16.dp)
      .animateContentSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    AmneziaWgProfileEnabledCard(
      checked = prof?.enabled ?: false,
      onCheckedChange = { checked ->
        val canEnable = !checked || (!configBlank && configWarnings.isEmpty() && addressValid && dnsParsed != null && mtuParsed != null && appCount > 0)
        if (!canEnable) {
          showSnack(context.getString(R.string.amneziawg_enable_requirements_error))
        } else {
          actions.setProfileEnabled("amneziawg", profile, checked) { ok ->
            showSnack(if (ok) context.getString(R.string.saved_apply_after_restart) else context.getString(R.string.save_failed))
            if (ok) actions.refreshPrograms()
          }
        }
      },
    )

    if (loading) {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Row(
          Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
          Text(stringResource(R.string.common_loading))
        }
      }
    }

    AmneziaWgSectionCard(
      title = stringResource(R.string.amneziawg_settings_title),
      desc = stringResource(R.string.amneziawg_autosave_hint),
      accent = Color(0xFF22C55E),
      icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(22.dp)) },
    ) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = profile,
          onValueChange = {},
          modifier = Modifier.fillMaxWidth(),
          readOnly = true,
          label = { Text(stringResource(R.string.profile_name_label)) },
          supportingText = { Text(stringResource(R.string.amneziawg_profile_name_readonly_hint)) },
        )
        OutlinedTextField(
          value = tunText,
          onValueChange = { tunText = it.trim().take(15) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.amneziawg_tun_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
          isError = tunText.isNotBlank() && !tunValid,
          supportingText = { Text(stringResource(R.string.amneziawg_tun_hint)) },
        )
        if (tunText.isNotBlank() && !isValidAmneziaWgTun(tunText)) {
          Text(stringResource(R.string.amneziawg_tun_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (tunNameConflict) {
          Text(stringResource(R.string.vpn_tun_name_in_use), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = addressText,
          onValueChange = { addressText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.amneziawg_address_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = false,
          minLines = 1,
          maxLines = 3,
          isError = addressText.isNotBlank() && !addressValid,
          supportingText = { Text(stringResource(R.string.amneziawg_address_hint)) },
        )
        if (addressText.isNotBlank() && addressParsed == null) {
          Text(stringResource(R.string.amneziawg_address_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (addressSelfOverlap) {
          Text(stringResource(R.string.vpn_ipv4_address_self_overlap), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (addressConflict) {
          Text(stringResource(R.string.vpn_ipv4_address_in_use), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = dnsText,
          onValueChange = { dnsText = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.amneziawg_dns_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = false,
          minLines = 1,
          maxLines = 3,
          isError = dnsText.isNotBlank() && dnsParsed == null,
          supportingText = { Text(stringResource(R.string.amneziawg_dns_hint)) },
        )
        if (dnsText.isNotBlank() && dnsParsed == null) {
          Text(stringResource(R.string.amneziawg_dns_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = mtuText,
          onValueChange = { mtuText = it.filter(Char::isDigit).take(4) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.amneziawg_mtu_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          isError = mtuText.isNotBlank() && mtuParsed == null,
          supportingText = { Text(stringResource(R.string.amneziawg_mtu_hint)) },
        )
        if (mtuText.isNotBlank() && mtuParsed == null) {
          Text(stringResource(R.string.amneziawg_mtu_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.amneziawg_endpoint_resolve_label), fontWeight = FontWeight.SemiBold)
            Text(
              stringResource(R.string.amneziawg_endpoint_resolve_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
          Switch(checked = endpointResolve, onCheckedChange = { endpointResolve = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.amneziawg_strip_fwmark_label), fontWeight = FontWeight.SemiBold)
            Text(
              stringResource(R.string.amneziawg_strip_fwmark_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
          Switch(checked = stripFwmark, onCheckedChange = { stripFwmark = it })
        }
      }
    }

    AmneziaWgConfigSummaryCard(
      hasConfig = !configBlank,
      lineCount = configLineCount,
      warnings = configWarnings,
      saving = uploading,
      onEdit = { showConfigEditor = true },
      onUpload = { fileLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
    )

    AppListPickerCard(
      title = stringResource(R.string.amneziawg_apps_title),
      desc = stringResource(R.string.amneziawg_apps_desc),
      path = "$basePath/apps/user",
      actions = actions,
      snackHost = snackHost,
      programs = programs,
      saveFailedMessage = stringResource(R.string.amneziawg_app_conflict_error),
      onSavedSelection = { appCount = it.size },
    )

    if ((prof?.enabled == true) && appCount == 0) {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
      ) {
        Text(
          stringResource(R.string.amneziawg_enabled_empty_apps_warning),
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    Spacer(Modifier.height(effectiveBottomContentPadding))
  }
}

@Composable
private fun AmneziaWgConfigSummaryCard(
  hasConfig: Boolean,
  lineCount: Int,
  warnings: List<String>,
  saving: Boolean,
  onEdit: () -> Unit,
  onUpload: () -> Unit,
) {
  AmneziaWgSectionCard(
    title = stringResource(R.string.amneziawg_config_title),
    desc = stringResource(R.string.amneziawg_config_summary_desc),
    accent = Color(0xFF38BDF8),
    icon = { Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(22.dp)) },
    trailing = {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(enabled = !saving, onClick = onUpload) {
          Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.common_upload_cd))
        }
        FilledTonalButton(onClick = onEdit) {
          Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_edit))
        }
      }
    },
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        if (hasConfig) stringResource(R.string.amneziawg_config_summary_present_fmt, lineCount) else stringResource(R.string.amneziawg_config_missing_warning),
        style = MaterialTheme.typography.bodySmall,
        color = if (hasConfig) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else MaterialTheme.colorScheme.error,
      )
      Text(
        stringResource(R.string.amneziawg_config_normalize_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
      )
      warnings.forEach { warning ->
        Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun AmneziaWgConfigEditorDialog(
  profile: String,
  text: String,
  loading: Boolean,
  saving: Boolean,
  warnings: List<String>,
  isEmpty: Boolean,
  onTextChange: (String) -> Unit,
  onUpload: () -> Unit,
  onDismiss: () -> Unit,
) {
  val compactWidth = rememberIsCompactWidth()
  val narrowWidth = rememberIsNarrowWidth()
  val shortHeight = rememberIsShortHeight()
  val useCompactHeader = shortHeight || narrowWidth

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(if (compactWidth) 0.96f else 0.90f)
        .fillMaxHeight(if (shortHeight) 0.94f else 0.86f)
        .navigationBarsPadding(),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
      shadowElevation = 10.dp,
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = if (compactWidth) 12.dp else 18.dp, vertical = if (shortHeight) 10.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (useCompactHeader) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                stringResource(R.string.amneziawg_config_editor_title_fmt, profile),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                stringResource(R.string.amneziawg_config_autosave_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
              IconButton(onClick = onUpload, modifier = Modifier.size(40.dp), enabled = !saving) {
                Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.common_upload_cd))
              }
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
              IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
              }
            }
          }
        } else {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.amneziawg_config_editor_title_fmt, profile), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              Text(
                stringResource(R.string.amneziawg_config_autosave_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = onUpload, enabled = !saving) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.common_upload_cd))
              }
              FilledTonalButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.common_close))
              }
            }
          }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        Text(
          stringResource(R.string.amneziawg_config_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        if (isEmpty) {
          Text(stringResource(R.string.amneziawg_config_empty), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        warnings.forEach { warning ->
          Text(warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
          value = text,
          onValueChange = onTextChange,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = true),
          enabled = !loading,
          label = { Text("client.conf") },
          singleLine = false,
          minLines = if (shortHeight) 10 else 14,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
          isError = isEmpty,
        )
      }
    }
  }
}
