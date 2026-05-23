package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Intent
import com.android.zdtd.service.LocalWebPanelActivity
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.android.zdtd.service.R
import androidx.compose.material.icons.filled.Edit
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramScreen(
  programs: List<ApiModels.Program>,
  programId: String,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val useScrollableTabs = rememberUseScrollableTabs()
  val narrow = rememberIsNarrowWidth()
  val program = programs.firstOrNull { it.id == programId }
  if (program == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.program_not_found)) }
    return
  }

  val context = LocalContext.current

  val scope = rememberCoroutineScope()

  val hasStrategicFiles = program.id == "nfqws" || program.id == "nfqws2"
  var programTab by remember(program.id) { mutableStateOf(0) }

  var showCreateProfile by remember { mutableStateOf(false) }
  var operaWebPanelChecking by remember(program.id) { mutableStateOf(false) }
  var operaAppSelections by remember(program.id) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

  val operaAppPaths = remember(program.id) {
    if (program.id == "operaproxy") {
      listOf(
        "/api/programs/operaproxy/apps/user",
        "/api/programs/operaproxy/apps/mobile",
        "/api/programs/operaproxy/apps/wifi",
      )
    } else {
      emptyList()
    }
  }
  val operaSelectedApps = remember(operaAppSelections) { operaAppSelections.values.flatMap { it }.toSet() }
  val operaWebPanelVisible = program.enabled && !isOnlyZdtdAppSelected(operaSelectedApps)

  fun refreshOperaAppSelections() {
    if (operaAppPaths.isEmpty()) {
      operaAppSelections = emptyMap()
      return
    }
    val next = linkedMapOf<String, Set<String>>()
    var remaining = operaAppPaths.size
    operaAppPaths.forEach { path ->
      actions.loadText(path) { content ->
        next[path] = parsePkgList(content)
        remaining -= 1
        if (remaining == 0) {
          operaAppSelections = next
        }
      }
    }
  }

  LaunchedEffect(program.id) {
    refreshOperaAppSelections()
  }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val isProfileProgram = isProfileProgramType(program.type)
  val effectiveTopContentPadding = if (isProfileProgram && topContentPadding > 10.dp) topContentPadding - 10.dp else topContentPadding
  val sortedProfiles = remember(program.profiles) { sortProfilesDesc(program.profiles) }
  // Delete is allowed only in decreasing order: if there are profile1 and profile2,
  // the UI should only allow deleting profile2 first.
  val maxNumericProfileIdx = remember(program.profiles) {
    program.profiles.map { profileIndex(it.name) }.filter { it != Int.MIN_VALUE }.maxOrNull() ?: Int.MIN_VALUE
  }

  // Dialogs must be invoked from a @Composable context (not inside LazyColumn/LazyListScope).
  if (isProfileProgramType(program.type) && showCreateProfile && (!hasStrategicFiles || programTab == 0)) {
    CreateProfileDialog(
      existing = program.profiles.map { it.name },
      onDismiss = { showCreateProfile = false },
      onCreate = { name ->
        showCreateProfile = false
        actions.createNamedProfile(program.id, name) { created ->
          if (created != null) {
            showSnack(context.getString(R.string.profile_created_fmt, created))
            onOpenProfile(program.id, created)
          } else {
            showSnack(context.getString(R.string.create_failed))
          }
        }
      },
      snackHost = snackHost,
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(if (compact) 8.dp else 10.dp),
    contentPadding = PaddingValues(top = effectiveTopContentPadding),
    verticalArrangement = Arrangement.spacedBy(if (isProfileProgram) 10.dp else 8.dp),
  ) {
    item {
      ProgramHeroHeader(program = program)
    }

    // Global enabled toggle:
    // - MUST exist for dnscrypt + operaproxy (per Danil)
    // - sing-box has a custom enabled inside its own setting.json
    // - MUST NOT exist for zapret/dpitunnel/byedpi (it's useless there)
    if (program.id == "dnscrypt") {
      item {
        EnabledCard(
          title = stringResource(R.string.enabled_card_program_title),
          checked = program.enabled,
          onCheckedChange = { v -> actions.setProgramEnabled(program.id, v) },
        )
      }
    } else if (program.id == "operaproxy") {
      item(key = "operaproxy_global_controls", contentType = "operaproxy_global_controls") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OperaProxyStatusCard(
            checked = program.enabled,
            onCheckedChange = { v -> actions.setProgramEnabled(program.id, v) },
          )
          AnimatedVisibility(
            visible = operaWebPanelVisible,
            enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(animationSpec = tween(180)),
          ) {
            OperaWebPanelCard(
              checking = operaWebPanelChecking,
              onOpen = {
                if (!operaWebPanelChecking) {
                  scope.launch {
                    operaWebPanelChecking = true
                    val available = isLocalWebPanelPortOpen()
                    operaWebPanelChecking = false
                    if (available) {
                      context.startActivity(
                        Intent(context, LocalWebPanelActivity::class.java)
                          .putExtra(LocalWebPanelActivity.EXTRA_SCOPE_KEY, "program/operaproxy")
                          .putExtra(LocalWebPanelActivity.EXTRA_DEFAULT_URL, OPERAPROXY_WEB_PANEL_URL)
                      )
                    } else {
                      showSnack(context.getString(R.string.web_panel_unavailable))
                    }
                  }
                }
              },
            )
          }
        }
      }
    }

    when {

isProfileProgramType(program.type) -> {
        if (hasStrategicFiles) {
          item {
            if (useScrollableTabs) {
              ScrollableTabRow(selectedTabIndex = programTab, edgePadding = 12.dp) {
                Tab(selected = programTab == 0, onClick = { programTab = 0 }, text = { Text(stringResource(R.string.tab_profiles), maxLines = 2) })
                Tab(selected = programTab == 1, onClick = { programTab = 1 }, text = { Text(stringResource(R.string.tab_files), maxLines = 2) })
              }
            } else {
              TabRow(selectedTabIndex = programTab) {
                Tab(selected = programTab == 0, onClick = { programTab = 0 }, text = { Text(stringResource(R.string.tab_profiles), maxLines = 2) })
                Tab(selected = programTab == 1, onClick = { programTab = 1 }, text = { Text(stringResource(R.string.tab_files), maxLines = 2) })
              }
            }
          }
        }

        if (!hasStrategicFiles || programTab == 0) {
          item {
            CreateProfileCard(onAdd = { showCreateProfile = true })
          }
          item {
            ProfilesSectionTitle()
          }
          items(sortedProfiles, key = { it.name }, contentType = { "profile_card" }) { prof ->
            ProfileRow(
              programId = program.id,
              profile = prof,
              onOpen = { onOpenProfile(program.id, prof.name) },
              onToggle = { v -> actions.setProfileEnabled(program.id, prof.name, v) },
              onDelete = {
                actions.deleteProfile(program.id, prof.name) { ok ->
                  showSnack(
                    if (ok) context.getString(R.string.deleted)
                    else context.getString(R.string.delete_failed)
                  )
                }
              },
              deletable = run {
                val idx = profileIndex(prof.name)
                idx == Int.MIN_VALUE || idx == maxNumericProfileIdx
              },
            )
          }
        } else {
          item {
            ZapretStrategicFiles(programId = program.id, actions = actions, snackHost = snackHost)
          }
        }
      }

      program.id == "dnscrypt" -> {
        item {
          TextEditorCard(
            title = "dnscrypt-proxy.toml",
            desc = stringResource(R.string.dnscrypt_main_config_desc),
            path = "/api/programs/dnscrypt/config",
            actions = actions,
            snackHost = snackHost,
          )
        }
        item {
          Spacer(Modifier.height(10.dp))
          DnscryptSettingFilesSection(actions = actions, snackHost = snackHost)
        }
      }

      program.id == "operaproxy" -> {
        item {
          OperaProxySection(
            programs = programs,
            actions = actions,
            snackHost = snackHost,
            onAppsSelectionSaved = { path, selected ->
              operaAppSelections = operaAppSelections + (path to selected)
            },
          )
        }
      }

      program.id == "tor" -> {
        item {
          TorSection(program = program, programs = programs, actions = actions, snackHost = snackHost)
        }
      }

      program.id == "sing-box" -> {
        item {
          SingBoxSection(program = program, programs = programs, actions = actions, snackHost = snackHost)
        }
      }

      else -> {
        item {
          NotImplementedProgramCard()
        }
      }
    }

    item { Spacer(Modifier.height(64.dp)) }
  }
}

@Composable
private fun ProgramHeroHeader(program: ApiModels.Program) {
  ProgramDescriptionHeader(
    programId = program.id,
    description = toolDescription(program.id),
    isProfiles = isProfileProgramType(program.type),
    enabled = program.enabled,
  )
}

@Composable
internal fun ProgramDescriptionHeader(
  programId: String,
  description: String,
  isProfiles: Boolean = true,
  enabled: Boolean = false,
) {
  val accentColor = when {
    isProfiles -> MaterialTheme.colorScheme.primary
    enabled -> Color(0xFF22C55E)
    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 0.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Surface(
      modifier = Modifier.size(38.dp),
      color = accentColor.copy(alpha = 0.14f),
      contentColor = accentColor,
      shape = CircleShape,
      border = BorderStroke(1.dp, accentColor.copy(alpha = 0.32f)),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val iconRes = programIconRes(programId)
        if (iconRes != null) {
          Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(23.dp),
          )
        } else {
          Icon(
            imageVector = programIcon(programId),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }

    Text(
      text = description,
      modifier = Modifier.weight(1f),
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}


private const val OPERAPROXY_WEB_PANEL_URL = "http://127.0.0.1:8000/"

@Composable
private fun OperaProxyStatusCard(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val accent = Color(0xFFFACC15)
  val shape = RoundedCornerShape(24.dp)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        brush = Brush.horizontalGradient(
          listOf(
            accent.copy(alpha = if (checked) 0.20f else 0.08f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
          )
        ),
        shape = shape,
      )
      .border(
        BorderStroke(1.dp, accent.copy(alpha = if (checked) 0.46f else 0.22f)),
        shape,
      )
      .padding(horizontal = 14.dp, vertical = 12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = accent.copy(alpha = if (checked) 0.18f else 0.10f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
      ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val iconRes = programIconRes("operaproxy")
          if (iconRes != null) {
            Icon(
              painter = painterResource(iconRes),
              contentDescription = null,
              modifier = Modifier.size(25.dp),
            )
          } else {
            Icon(
              imageVector = programIcon("operaproxy"),
              contentDescription = null,
              modifier = Modifier.size(22.dp),
            )
          }
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = stringResource(R.string.opera_proxy_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = stringResource(R.string.enabled_card_program_title),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Surface(
          shape = RoundedCornerShape(100.dp),
          color = if (checked) Color(0xFF22C55E).copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
          border = BorderStroke(1.dp, if (checked) Color(0xFF22C55E).copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        ) {
          Text(
            text = if (checked) stringResource(R.string.enabled_state_on) else stringResource(R.string.enabled_state_off),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (checked) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            fontWeight = FontWeight.SemiBold,
          )
        }
      }

      Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
  }
}

@Composable
private fun OperaWebPanelCard(
  checking: Boolean,
  onOpen: () -> Unit,
) {
  val accent = Color(0xFF60A5FA)
  val shape = RoundedCornerShape(22.dp)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        brush = Brush.horizontalGradient(
          listOf(
            accent.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
          )
        ),
        shape = shape,
      )
      .border(BorderStroke(1.dp, accent.copy(alpha = 0.30f)), shape)
      .clickable(enabled = !checking, onClick = onOpen)
      .padding(horizontal = 14.dp, vertical = 11.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = accent.copy(alpha = 0.16f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
      ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp))
        }
      }

      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = stringResource(R.string.web_panel_open),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = OPERAPROXY_WEB_PANEL_URL,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Surface(
        shape = RoundedCornerShape(100.dp),
        color = accent.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
      ) {
        Box(
          modifier = Modifier
            .height(38.dp)
            .widthIn(min = 48.dp)
            .padding(horizontal = 13.dp),
          contentAlignment = Alignment.Center,
        ) {
          if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          } else {
            Icon(Icons.Filled.Public, contentDescription = stringResource(R.string.web_panel_open), modifier = Modifier.size(20.dp))
          }
        }
      }
    }
  }
}

private suspend fun isLocalWebPanelPortOpen(): Boolean = withContext(Dispatchers.IO) {
  runCatching {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", 8000), 850)
    }
    true
  }.getOrDefault(false)
}


@Composable
private fun NotImplementedProgramCard() {
  val transition = rememberInfiniteTransition()
  val handOffset by transition.animateFloat(
    initialValue = -32f,
    targetValue = 32f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 950),
      repeatMode = RepeatMode.Reverse,
    )
  )
  val handRotation by transition.animateFloat(
    initialValue = -10f,
    targetValue = 10f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 950),
      repeatMode = RepeatMode.Reverse,
    )
  )

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = stringResource(R.string.not_implemented_yet),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(76.dp),
        contentAlignment = Alignment.Center,
      ) {
        Row(
          modifier = Modifier.width(180.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "‹",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            fontSize = 30.sp,
          )
          Text(
            text = "›",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            fontSize = 30.sp,
          )
        }
        Text(
          text = "☝",
          fontSize = 42.sp,
          modifier = Modifier
            .offset(x = handOffset.dp)
            .rotate(handRotation),
        )
      }
    }
  }
}

// ---------------- sing-box UI ----------------

private data class SingProfile(
  val name: String,
  val enabled: Boolean,
  val port: Int?,
  val capture: String?,
)

private data class SingSetting(
  val enabled: Boolean,
  val mode: String,
  val t2sPort: Int?,
  val t2sWebPort: Int?,
  val activeTransparentProfile: String,
  val profiles: List<SingProfile>,
)

private fun parseSingSetting(o: JSONObject?): SingSetting? {
  if (o == null) return null
  val enabled = o.optBoolean("enabled", false)
  val mode = o.optString("mode", "socks5")
  val t2sPort = o.optInt("t2s_port", 0).takeIf { it > 0 }
  val t2sWebPort = o.optInt("t2s_web_port", 8001).takeIf { it > 0 }
  val active = o.optString("active_transparent_profile", "")
  val arr = o.optJSONArray("profiles") ?: JSONArray()
  val profiles = buildList {
    for (i in 0 until arr.length()) {
      val p = arr.optJSONObject(i) ?: continue
      val name = p.optString("name", "").trim()
      if (name.isEmpty()) continue
      add(
        SingProfile(
          name = name,
          enabled = p.optBoolean("enabled", false),
          port = p.optInt("port", 0).takeIf { it > 0 },
          capture = p.optString("capture", "").takeIf { it.isNotBlank() },
        )
      )
    }
  }
  return SingSetting(
    enabled = enabled,
    mode = mode,
    t2sPort = t2sPort,
    t2sWebPort = t2sWebPort,
    activeTransparentProfile = active,
    profiles = profiles,
  )
}

private fun buildSingSettingJson(s: SingSetting): JSONObject {
  val out = JSONObject()
  out.put("enabled", s.enabled)
  out.put("mode", s.mode)
  out.put("t2s_port", s.t2sPort ?: 0)
  out.put("t2s_web_port", s.t2sWebPort ?: 8001)
  out.put("active_transparent_profile", s.activeTransparentProfile)
  val arr = JSONArray()
  for (p in s.profiles) {
    val o = JSONObject()
    o.put("name", p.name)
    o.put("enabled", p.enabled)
    o.put("port", p.port ?: 0)
    if (!p.capture.isNullOrBlank()) o.put("capture", p.capture)
    arr.put(o)
  }
  out.put("profiles", arr)
  return out
}

private fun isValidProfileName(name: String): Boolean {
  if (name.isBlank()) return false
  // English symbols, no spaces.
  return name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}

private fun isValidPort(v: Int?): Boolean {
  if (v == null) return false
  return v in 1..65535
}

private data class SingConfigPortPlan(
  val profileName: String,
  val originalConfig: String,
  val detectedPort: Int,
  val applyPortToProfile: Boolean,
  val conflictProfileName: String? = null,
  val replacementPort: Int? = null,
)

private fun findSingMixedInboundPort(configText: String): Int? {
  val root = runCatching { JSONObject(configText) }.getOrNull() ?: return null
  val inbounds = root.optJSONArray("inbounds") ?: return null

  fun extractPort(obj: JSONObject?): Int? = obj?.optInt("listen_port", 0)?.takeIf { it in 1..65535 }

  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (inbound.optString("type") == "mixed" && inbound.optString("tag") == "mixed-in") {
      return extractPort(inbound)
    }
  }
  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (inbound.optString("type") == "mixed") {
      return extractPort(inbound)
    }
  }
  return null
}

private fun replaceSingMixedInboundPort(configText: String, newPort: Int): String? {
  if (newPort !in 1..65535) return null
  val root = runCatching { JSONObject(configText) }.getOrNull() ?: return null
  val inbounds = root.optJSONArray("inbounds") ?: return null

  fun replaceAt(index: Int): String {
    val inbound = inbounds.optJSONObject(index) ?: JSONObject()
    inbound.put("listen_port", newPort)
    inbounds.put(index, inbound)
    root.put("inbounds", inbounds)
    return root.toString(2)
  }

  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (inbound.optString("type") == "mixed" && inbound.optString("tag") == "mixed-in") {
      return replaceAt(i)
    }
  }
  for (i in 0 until inbounds.length()) {
    val inbound = inbounds.optJSONObject(i) ?: continue
    if (inbound.optString("type") == "mixed") {
      return replaceAt(i)
    }
  }
  return null
}

private fun findNextAvailableSingPort(profiles: List<SingProfile>, preferredPort: Int): Int {
  val used = profiles.mapNotNull { it.port }.toMutableSet()
  if (preferredPort in 1..65535 && preferredPort !in used) return preferredPort
  var port = preferredPort.coerceIn(1, 65535)
  while (port in used && port < 65535) port += 1
  if (port !in used && port in 1..65535) return port
  port = 1
  while (port in used && port < 65535) port += 1
  return port.coerceIn(1, 65535)
}

private fun updateSingProfilePort(setting: SingSetting, profileName: String, newPort: Int): SingSetting =
  setting.copy(
    profiles = setting.profiles.map { profile ->
      if (profile.name == profileName) profile.copy(port = newPort) else profile
    }
  )


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingBoxSection(
  program: ApiModels.Program,
  programs: List<ApiModels.Program>,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val narrow = rememberIsNarrowWidth()
  fun showSnack(msg: String) { scope.launch { snackHost.showSnackbar(msg) } }

  var rawJson by remember { mutableStateOf<JSONObject?>(null) }
  var setting by remember { mutableStateOf<SingSetting?>(null) }
  var loading by remember { mutableStateOf(false) }

  var editProfile by remember { mutableStateOf<String?>(null) }
  var editText by remember { mutableStateOf("") }
  var editLastLoadedText by remember { mutableStateOf("") }
  var editLoading by remember { mutableStateOf(false) }

  var showCreate by remember { mutableStateOf(false) }
  var showImport by remember { mutableStateOf(false) }

  fun refreshSetting(onDone: (() -> Unit)? = null) {
    loading = true
    actions.loadJsonData("/api/programs/sing-box/setting") { obj ->
      loading = false
      rawJson = obj
      setting = parseSingSetting(obj)
      onDone?.invoke()
    }
  }

  fun openEditor(profileName: String) {
    editProfile = profileName
    editLoading = true
    val profEnc = URLEncoder.encode(profileName, "UTF-8")
    actions.loadText("/api/programs/sing-box/profiles/$profEnc/config") { txt ->
      editLoading = false
      editText = txt ?: ""
      editLastLoadedText = editText
    }
  }

  fun deleteServer(profileName: String) {
    actions.deleteProfile("sing-box", profileName) { ok ->
      if (ok) {
        showSnack(context.getString(R.string.deleted))
        refreshSetting()
      } else {
        showSnack(context.getString(R.string.delete_failed))
      }
    }
  }

  LaunchedEffect(Unit) {
    refreshSetting()
  }

  var pendingConfigPortPlan by remember { mutableStateOf<SingConfigPortPlan?>(null) }

  fun applyConfigSavePlan(plan: SingConfigPortPlan) {
    val updatedConfig = when {
      plan.replacementPort != null -> replaceSingMixedInboundPort(plan.originalConfig, plan.replacementPort)
      else -> plan.originalConfig
    }
    if (updatedConfig == null) {
      showSnack(context.getString(R.string.singbox_listen_port_update_failed))
      return
    }

    val profEnc = URLEncoder.encode(plan.profileName, "UTF-8")
    editLoading = true
    actions.saveText("/api/programs/sing-box/profiles/$profEnc/config", updatedConfig) { ok ->
      if (!ok) {
        editLoading = false
        showSnack(context.getString(R.string.save_failed))
        return@saveText
      }

      val currentSetting = setting
      val portToSave = when {
        plan.replacementPort != null -> plan.replacementPort
        plan.applyPortToProfile -> plan.detectedPort
        else -> null
      }
      if (currentSetting == null || portToSave == null) {
        editLoading = false
        editText = updatedConfig
        editLastLoadedText = updatedConfig
        showSnack(context.getString(R.string.saved))
        editProfile = null
        return@saveText
      }

      val updatedSetting = updateSingProfilePort(currentSetting, plan.profileName, portToSave)
      val obj = buildSingSettingJson(updatedSetting)
      actions.saveJsonData("/api/programs/sing-box/setting", obj) { settingOk ->
        editLoading = false
        if (settingOk) {
          setting = updatedSetting
          rawJson = obj
          editText = updatedConfig
          editLastLoadedText = updatedConfig
          showSnack(context.getString(R.string.saved))
          editProfile = null
        } else {
          showSnack(context.getString(R.string.singbox_config_saved_port_update_failed))
        }
      }
    }
  }

  if (showCreate) {
    CreateProfileDialog(
      existing = (setting?.profiles ?: emptyList()).map { it.name },
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.createNamedProfile("sing-box", name) { created ->
          if (created != null) {
            showSnack(context.getString(R.string.singbox_server_created_fmt, created))
            refreshSetting()
          } else {
            showSnack(context.getString(R.string.create_failed))
          }
        }
      },
      snackHost = snackHost,
      titleRes = R.string.singbox_create_server_title,
      rulesRes = R.string.singbox_create_server_rules,
      nameLabelRes = R.string.singbox_server_name_label,
      existsErrorRes = R.string.singbox_server_already_exists,
      invalidNameRes = R.string.singbox_invalid_server_name,
    )
  }

  val currentSetting = setting
  if (showImport && currentSetting != null) {
    SingBoxImportServerDialog(
      existing = currentSetting.profiles.map { it.name },
      suggestedPort = findNextAvailableSingPort(currentSetting.profiles, 2080),
      onDismiss = { showImport = false },
      onGenerate = { serverName, sourceText ->
        val preferredPort = findNextAvailableSingPort(currentSetting.profiles, 2080)
        val imported = runCatching { com.android.zdtd.service.singbox.importer.SingBoxOneLineImporter.import(sourceText, preferredPort) }
          .getOrElse {
            showSnack(context.getString(R.string.singbox_import_failed_fmt, it.message ?: context.getString(R.string.singbox_parse_error)))
            return@SingBoxImportServerDialog
          }

        var configToSave = imported.configJson
        val detectedPort = findSingMixedInboundPort(configToSave)
        val resolvedPort = when {
          detectedPort == null -> preferredPort
          currentSetting.profiles.none { it.port == detectedPort } -> detectedPort
          else -> {
            val next = findNextAvailableSingPort(currentSetting.profiles, detectedPort)
            configToSave = replaceSingMixedInboundPort(configToSave, next) ?: configToSave
            next
          }
        }

        showImport = false
        actions.createNamedProfile("sing-box", serverName) { created ->
          if (created == null) {
            showSnack(context.getString(R.string.create_failed))
            return@createNamedProfile
          }
          val encoded = URLEncoder.encode(created, "UTF-8")
          actions.saveText("/api/programs/sing-box/profiles/$encoded/config", configToSave) { configOk ->
            if (!configOk) {
              showSnack(context.getString(R.string.save_failed))
              refreshSetting()
              return@saveText
            }

            actions.loadJsonData("/api/programs/sing-box/setting") { latestObj ->
              val latestSetting = parseSingSetting(latestObj) ?: currentSetting
              val nextProfiles = if (latestSetting.profiles.any { it.name == created }) {
                latestSetting.profiles.map {
                  if (it.name == created) it.copy(enabled = true, port = resolvedPort, capture = "tcp") else it
                }
              } else {
                latestSetting.profiles + SingProfile(
                  name = created,
                  enabled = true,
                  port = resolvedPort,
                  capture = "tcp",
                )
              }
              val updatedSetting = latestSetting.copy(
                activeTransparentProfile = if (latestSetting.activeTransparentProfile.isBlank()) created else latestSetting.activeTransparentProfile,
                profiles = nextProfiles,
              )
              val obj = buildSingSettingJson(updatedSetting)
              actions.saveJsonData("/api/programs/sing-box/setting", obj) { settingOk ->
                if (settingOk) {
                  setting = updatedSetting
                  rawJson = obj
                  showSnack(context.getString(R.string.singbox_import_created_fmt, created, resolvedPort))
                } else {
                  showSnack(context.getString(R.string.singbox_config_saved_port_update_failed))
                  refreshSetting()
                }
              }
            }
          }
        }
      },
      snackHost = snackHost,
    )
  }

  if (editProfile != null) {
    AlertDialog(
      onDismissRequest = { if (!editLoading) editProfile = null },
      title = { Text(stringResource(R.string.singbox_editor_title_fmt, editProfile ?: "")) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          if (editLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
          }
          OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            singleLine = false,
            label = { Text(stringResource(R.string.singbox_editor_label)) },
          )
        }
      },
      confirmButton = {
        Button(enabled = !editLoading && editText != editLastLoadedText, onClick = {
          val prof = editProfile ?: return@Button
          val parsed = runCatching { JSONObject(editText.trim()) }.getOrElse {
            showSnack(context.getString(R.string.singbox_invalid_json_fmt, it.message ?: context.getString(R.string.singbox_parse_error)))
            return@Button
          }
          val normalizedText = parsed.toString(2)
          val detectedPort = findSingMixedInboundPort(normalizedText)
          val localSetting = setting
          val currentProfile = localSetting?.profiles?.firstOrNull { it.name == prof }

          if (detectedPort == null || localSetting == null || currentProfile == null) {
            applyConfigSavePlan(SingConfigPortPlan(prof, normalizedText, currentProfile?.port ?: 0, false))
            return@Button
          }

          if (currentProfile.port == detectedPort) {
            applyConfigSavePlan(SingConfigPortPlan(prof, normalizedText, detectedPort, false))
            return@Button
          }

          val conflict = localSetting.profiles.firstOrNull { it.name != prof && it.port == detectedPort }
          pendingConfigPortPlan = if (conflict == null) {
            SingConfigPortPlan(
              profileName = prof,
              originalConfig = normalizedText,
              detectedPort = detectedPort,
              applyPortToProfile = true,
            )
          } else {
            SingConfigPortPlan(
              profileName = prof,
              originalConfig = normalizedText,
              detectedPort = detectedPort,
              applyPortToProfile = true,
              conflictProfileName = conflict.name,
              replacementPort = findNextAvailableSingPort(localSetting.profiles, detectedPort),
            )
          }
        }) { Text(stringResource(R.string.action_save)) }
      },
      dismissButton = {
        TextButton(enabled = !editLoading, onClick = { editProfile = null }) { Text(stringResource(R.string.action_cancel)) }
      }
    )
  }

  pendingConfigPortPlan?.let { plan ->
    val message = if (plan.conflictProfileName == null) {
      context.getString(R.string.singbox_port_sync_found_fmt, plan.detectedPort, plan.profileName)
    } else {
      context.getString(
        R.string.singbox_port_sync_conflict_fmt,
        plan.detectedPort,
        plan.conflictProfileName,
        plan.replacementPort ?: plan.detectedPort,
        plan.profileName,
      )
    }
    AlertDialog(
      onDismissRequest = { pendingConfigPortPlan = null },
      title = { Text(stringResource(R.string.singbox_port_sync_title)) },
      text = { Text(message) },
      confirmButton = {
        Button(onClick = {
          pendingConfigPortPlan = null
          applyConfigSavePlan(plan)
        }) {
          Text(
            if (plan.conflictProfileName == null) stringResource(R.string.singbox_port_sync_apply)
            else stringResource(R.string.singbox_port_sync_replace_and_apply)
          )
        }
      },
      dismissButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = {
            pendingConfigPortPlan = null
            applyConfigSavePlan(plan.copy(applyPortToProfile = false, replacementPort = null))
          }) {
            Text(
              if (plan.conflictProfileName == null) stringResource(R.string.singbox_port_sync_skip_profile)
              else stringResource(R.string.singbox_port_sync_save_config_only)
            )
          }
          TextButton(onClick = { pendingConfigPortPlan = null }) { Text(stringResource(R.string.action_cancel)) }
        }
      }
    )
  }

  val s0 = setting
  if (s0 == null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
      Column(Modifier.padding(12.dp)) {
        Text(program.name ?: "sing-box", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
          if (loading) stringResource(R.string.singbox_loading) else stringResource(R.string.singbox_failed_to_load_setting),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
    }
    return
  }

  var enabled by remember(s0.enabled) { mutableStateOf(s0.enabled) }
  var mode by remember(s0.mode) { mutableStateOf(s0.mode) }
  var t2sPortTxt by remember(s0.t2sPort) { mutableStateOf((s0.t2sPort ?: 0).toString()) }
  var t2sWebPortTxt by remember(s0.t2sWebPort) { mutableStateOf((s0.t2sWebPort ?: 8001).toString()) }
  var activeTransparent by remember(s0.activeTransparentProfile) { mutableStateOf(s0.activeTransparentProfile) }
  var profiles by remember(s0.profiles) { mutableStateOf(s0.profiles) }
  var lastSavedSettingJson by remember(s0) { mutableStateOf(buildSingSettingJson(s0).toString()) }
  var showTransparentUnavailableDialog by remember { mutableStateOf(false) }
  var autoSaveInFlight by remember { mutableStateOf(false) }
  var pendingAutoSave by remember { mutableStateOf(false) }
  var autoSaveRetryTick by remember { mutableStateOf(0) }

  fun validateAndBuild(showErrors: Boolean = true): SingSetting? {
    val t2sPort = t2sPortTxt.trim().toIntOrNull()
    val t2sWebPort = t2sWebPortTxt.trim().toIntOrNull()

    for (p in profiles) {
      if (!isValidProfileName(p.name)) {
        if (showErrors) showSnack(context.getString(R.string.singbox_invalid_profile_name_fmt, p.name))
        return null
      }
      if (!isValidPort(p.port)) {
        if (showErrors) showSnack(context.getString(R.string.singbox_invalid_port_for_profile_fmt, p.name))
        return null
      }
      val cap = p.capture ?: "tcp"
      if (cap != "tcp" && cap != "tcp_udp") {
        if (showErrors) showSnack(context.getString(R.string.singbox_invalid_capture_for_profile_fmt, p.name))
        return null
      }
    }
    val ports = profiles.mapNotNull { it.port }
    if (ports.size != ports.distinct().size) {
      if (showErrors) showSnack(context.getString(R.string.singbox_profile_ports_must_be_unique))
      return null
    }

    if (mode != "socks5" && mode != "transparent") {
      if (showErrors) showSnack(context.getString(R.string.singbox_select_mode))
      return null
    }

    if (mode == "socks5") {
      if (!isValidPort(t2sPort)) {
        if (showErrors) showSnack(context.getString(R.string.singbox_fill_t2s_port))
        return null
      }
      if (!isValidPort(t2sWebPort)) {
        if (showErrors) showSnack(context.getString(R.string.singbox_fill_t2s_web_port))
        return null
      }
      if (profiles.none { it.enabled }) {
        if (showErrors) showSnack(context.getString(R.string.singbox_enable_at_least_one_profile))
        return null
      }
    }

    if (mode == "transparent") {
      if (activeTransparent.isBlank()) {
        if (showErrors) showSnack(context.getString(R.string.singbox_select_active_transparent_profile))
        return null
      }
      if (profiles.none { it.name == activeTransparent }) {
        if (showErrors) showSnack(context.getString(R.string.singbox_active_transparent_profile_not_found))
        return null
      }
    }

    return SingSetting(
      enabled = enabled,
      mode = mode,
      t2sPort = t2sPort,
      t2sWebPort = t2sWebPort,
      activeTransparentProfile = activeTransparent,
      profiles = profiles,
    )
  }

  if (showTransparentUnavailableDialog) {
    AlertDialog(
      onDismissRequest = { showTransparentUnavailableDialog = false },
      title = { Text(stringResource(R.string.singbox_mode_transparent)) },
      text = { Text(stringResource(R.string.singbox_transparent_unavailable_message)) },
      confirmButton = {
        Button(onClick = { showTransparentUnavailableDialog = false }) {
          Text(stringResource(R.string.common_ok))
        }
      },
    )
  }

  LaunchedEffect(mode, t2sPortTxt, t2sWebPortTxt, activeTransparent, profiles, autoSaveRetryTick) {
    delay(400)
    val candidate = validateAndBuild(showErrors = false) ?: return@LaunchedEffect
    val obj = buildSingSettingJson(candidate)
    val json = obj.toString()
    if (json == lastSavedSettingJson) return@LaunchedEffect
    if (autoSaveInFlight) {
      pendingAutoSave = true
      return@LaunchedEffect
    }
    autoSaveInFlight = true
    pendingAutoSave = false
    actions.saveJsonData("/api/programs/sing-box/setting", obj) { ok ->
      autoSaveInFlight = false
      if (ok) {
        setting = candidate
        rawJson = obj
        lastSavedSettingJson = json
      } else {
        showSnack(context.getString(R.string.singbox_auto_save_failed))
      }
      val latestCandidate = validateAndBuild(showErrors = false)
      val latestJson = latestCandidate?.let { buildSingSettingJson(it).toString() }
      if (pendingAutoSave || (latestJson != null && latestJson != lastSavedSettingJson)) {
        pendingAutoSave = false
        autoSaveRetryTick += 1
      }
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    EnabledCard(
      title = stringResource(R.string.enabled_card_program_title),
      checked = enabled,
      onCheckedChange = { v ->
        val prev = enabled
        enabled = v
        val obj = (rawJson ?: JSONObject()).also { it.put("enabled", v) }
        actions.saveJsonData("/api/programs/sing-box/setting", obj) { ok ->
          if (!ok) {
            enabled = prev
            showSnack(context.getString(R.string.save_failed))
          } else {
            rawJson = obj
            lastSavedSettingJson = obj.toString()
            actions.refreshPrograms()
          }
        }
      },
    )

    Card {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.singbox_mode_title), fontWeight = FontWeight.SemiBold)
        Text(
          stringResource(R.string.singbox_settings_desc),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          style = MaterialTheme.typography.bodySmall,
        )
        if (narrow) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
              selected = mode == "socks5",
              onClick = { mode = "socks5" },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.error,
              ),
              label = { Text(stringResource(R.string.singbox_mode_socks5)) },
            )
            FilterChip(
              selected = mode == "transparent",
              onClick = { showTransparentUnavailableDialog = true },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.error,
              ),
              label = { Text(stringResource(R.string.singbox_mode_transparent)) },
            )
          }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
              selected = mode == "socks5",
              onClick = { mode = "socks5" },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.error,
              ),
              label = { Text(stringResource(R.string.singbox_mode_socks5)) },
            )
            FilterChip(
              selected = mode == "transparent",
              onClick = { showTransparentUnavailableDialog = true },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.error,
              ),
              label = { Text(stringResource(R.string.singbox_mode_transparent)) },
            )
          }
        }

        if (mode == "socks5") {
          OutlinedTextField(
            value = t2sPortTxt,
            onValueChange = { t2sPortTxt = it },
            label = { Text(stringResource(R.string.singbox_t2s_port_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = t2sWebPortTxt,
            onValueChange = { t2sWebPortTxt = it },
            label = { Text(stringResource(R.string.singbox_t2s_web_port_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            stringResource(R.string.singbox_capture_tcp_only),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall,
          )
        }

        if (mode == "transparent") {
          val names = profiles.map { it.name }
          var expanded by remember { mutableStateOf(false) }
          ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
              value = activeTransparent,
              onValueChange = {},
              readOnly = true,
              modifier = Modifier.menuAnchor().fillMaxWidth(),
              label = { Text(stringResource(R.string.singbox_active_transparent_profile_label)) },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
              names.forEach { n ->
                DropdownMenuItem(
                  text = { Text(n) },
                  onClick = {
                    activeTransparent = n
                    expanded = false
                  },
                )
              }
            }
          }
        }
      }
    }

    SingBoxImportCard(onClick = { showImport = true })

    AppListPickerCard(
      title = stringResource(R.string.apps_common_title),
      desc = stringResource(R.string.apps_common_desc),
      path = "/api/programs/sing-box/apps/user",
      actions = actions,
      snackHost = snackHost,
      programs = programs,
    )

    Card {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val compactHeader = rememberIsCompactWidth()
        if (compactHeader) {
          Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.singbox_servers_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
              stringResource(R.string.singbox_servers_desc),
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              style = MaterialTheme.typography.bodySmall,
            )
            FilledTonalButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
              Icon(Icons.Filled.Add, contentDescription = null)
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.action_add))
            }
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Text(stringResource(R.string.singbox_servers_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              FilledTonalButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_add))
              }
            }
            Text(
              stringResource(R.string.singbox_servers_desc),
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }

        profiles.forEach { profile ->
          SingBoxServerCard(
            profile = profile,
            mode = mode,
            isActiveTransparent = activeTransparent == profile.name,
            onEdit = { openEditor(profile.name) },
            onDelete = { deleteServer(profile.name) },
            onToggleEnabled = { value ->
              profiles = profiles.map { if (it.name == profile.name) it.copy(enabled = value) else it }
            },
            onPortChange = { value ->
              profiles = profiles.map { if (it.name == profile.name) it.copy(port = value) else it }
            },
            onCaptureChange = { value ->
              profiles = profiles.map { if (it.name == profile.name) it.copy(capture = value) else it }
            },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingBoxImportCard(onClick: () -> Unit) {
  Card(onClick = onClick) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.singbox_import_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        stringResource(R.string.singbox_import_card_desc),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      )
      Text(
        stringResource(R.string.singbox_import_card_warning),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
      )
      Button(onClick = onClick) {
        Text(stringResource(R.string.singbox_import_action))
      }
    }
  }
}

@Composable
private fun SingBoxServerCard(
  profile: SingProfile,
  mode: String,
  isActiveTransparent: Boolean,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onToggleEnabled: (Boolean) -> Unit,
  onPortChange: (Int?) -> Unit,
  onCaptureChange: (String) -> Unit,
) {
  val compact = rememberIsCompactWidth()
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f))) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (compact) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = null) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = null) }
          }
        }
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
              stringResource(R.string.apply_after_restart_short),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = null) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = null) }
          }
        }
      }

      if (mode == "socks5") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.enabled))
          Switch(checked = profile.enabled, onCheckedChange = onToggleEnabled)
        }
      } else {
        Text(
          stringResource(R.string.singbox_enabled_only_in_socks5_mode),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
          style = MaterialTheme.typography.bodySmall,
        )
      }

      OutlinedTextField(
        value = (profile.port ?: 0).toString(),
        onValueChange = { txt -> onPortChange(txt.trim().toIntOrNull()) },
        label = { Text(stringResource(R.string.common_port)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
      )

      if (mode == "transparent" && isActiveTransparent) {
        val cap = profile.capture ?: "tcp"
        val chips: @Composable () -> Unit = {
          FilterChip(
            selected = cap == "tcp",
            onClick = { onCaptureChange("tcp") },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
              selectedLabelColor = MaterialTheme.colorScheme.error,
            ),
            label = { Text(stringResource(R.string.singbox_capture_tcp)) },
          )
          FilterChip(
            selected = cap == "tcp_udp",
            onClick = { onCaptureChange("tcp_udp") },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
              selectedLabelColor = MaterialTheme.colorScheme.error,
            ),
            label = { Text(stringResource(R.string.singbox_capture_tcp_udp)) },
          )
        }
        if (compact) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { chips() }
        }
      }
    }
  }
}

@Composable
private fun SingBoxImportServerDialog(
  existing: List<String>,
  suggestedPort: Int,
  onDismiss: () -> Unit,
  onGenerate: (String, String) -> Unit,
  snackHost: SnackbarHostState,
) {
  val scope = rememberCoroutineScope()
  val existingNorm = remember(existing) { existing.map { normalizeProfileName(it) }.toSet() }
  var rawName by remember { mutableStateOf("") }
  val name = remember(rawName) { normalizeProfileName(rawName) }
  var source by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  fun snack(msg: String) { scope.launch { snackHost.showSnackbar(msg) } }

  val enterNameErr = stringResource(R.string.enter_a_name)
  val invalidServerErr = stringResource(R.string.singbox_invalid_server_name)
  val serverExistsErr = stringResource(R.string.singbox_server_already_exists)
  val sourceRequiredErr = stringResource(R.string.singbox_import_source_required)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.singbox_import_dialog_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.singbox_import_dialog_beta_warning),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          stringResource(R.string.singbox_import_dialog_desc),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = name,
          onValueChange = {
            rawName = it
            error = null
          },
          label = { Text(stringResource(R.string.singbox_server_name_label)) },
          singleLine = false,
          maxLines = 2,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          supportingText = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(stringResource(R.string.singbox_create_server_rules))
              Text(stringResource(R.string.singbox_import_auto_port_hint, suggestedPort))
            }
          },
          isError = error != null,
        )
        OutlinedTextField(
          value = source,
          onValueChange = {
            source = it
            error = null
          },
          label = { Text(stringResource(R.string.singbox_import_source_label)) },
          modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
          singleLine = false,
          supportingText = { Text(stringResource(R.string.singbox_import_source_support_hint)) },
          isError = error != null,
        )
        error?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(onClick = {
        when {
          name.isBlank() -> {
            error = enterNameErr
            snack(invalidServerErr)
          }
          existingNorm.contains(name) -> {
            error = serverExistsErr
            snack(serverExistsErr)
          }
          source.trim().isBlank() -> {
            error = sourceRequiredErr
            snack(sourceRequiredErr)
          }
          else -> onGenerate(name, source)
        }
      }) {
        Text(stringResource(R.string.singbox_import_action))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
internal fun ProfilesSectionTitle() {
  val accentSoft = Color(0xFFFF4D7D)

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 5.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier
        .width(4.dp)
        .height(24.dp)
        .background(accentSoft.copy(alpha = 0.90f), RoundedCornerShape(100.dp))
    )
    Text(
      stringResource(R.string.tab_profiles),
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
internal fun CreateProfileCard(onAdd: () -> Unit) {
  val compact = rememberIsCompactWidth()
  val accent = Color(0xFFA78BFA)
  val accentSoft = Color(0xFFFF4D7D)
  val shape = RoundedCornerShape(if (compact) 20.dp else 24.dp)

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onAdd() },
    shape = shape,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          brush = Brush.linearGradient(
            listOf(
              accent.copy(alpha = 0.18f),
              accentSoft.copy(alpha = 0.08f),
              MaterialTheme.colorScheme.surface.copy(alpha = 0.08f),
            )
          ),
          shape = shape,
        )
        .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 10.dp else 12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
      ) {
        Surface(
          modifier = Modifier.size(if (compact) 40.dp else 44.dp),
          color = Color.Transparent,
          contentColor = Color.White,
          shape = CircleShape,
          border = BorderStroke(1.dp, accent.copy(alpha = 0.58f)),
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                brush = Brush.linearGradient(
                  listOf(accent.copy(alpha = 0.34f), accentSoft.copy(alpha = 0.16f))
                ),
                shape = CircleShape,
              ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Filled.Add,
              contentDescription = null,
              modifier = Modifier.size(if (compact) 21.dp else 23.dp),
            )
          }
        }

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = stringResource(R.string.create_profile_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = stringResource(R.string.create_profile_rules),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            maxLines = if (compact) 2 else 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Surface(
          shape = RoundedCornerShape(100.dp),
          color = accent.copy(alpha = 0.20f),
          contentColor = Color.White,
          border = BorderStroke(1.dp, accent.copy(alpha = 0.36f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
              stringResource(R.string.action_create),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
            )
          }
        }
      }
    }
  }
}

private fun sortProfilesDesc(list: List<ApiModels.Profile>): List<ApiModels.Profile> {
  // Danil: delete profiles in decreasing order; also show newest (highest) first.
  return list.sortedWith(compareByDescending<ApiModels.Profile> {
    profileIndex(it.name)
  }.thenByDescending { it.name.lowercase() })
}

private fun profileIndex(name: String): Int {
  // Supports both "1" and "profile1" formats.
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) {
    return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  }
  return Int.MIN_VALUE
}

@Composable
private fun ProfileRow(
  programId: String,
  profile: ApiModels.Profile,
  onOpen: () -> Unit,
  onToggle: (Boolean) -> Unit,
  onDelete: () -> Unit,
  deletable: Boolean,
) {
  ProfileStatusCard(
    programId = programId,
    profileName = profile.name,
    checked = profile.enabled,
    onOpen = onOpen,
    onCheckedChange = onToggle,
    onDelete = onDelete,
    deletable = deletable,
  )
}

@Composable
private fun CreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
  snackHost: SnackbarHostState,
  titleRes: Int = R.string.create_profile_title,
  rulesRes: Int = R.string.create_profile_rules,
  nameLabelRes: Int = R.string.profile_name_label,
  existsErrorRes: Int = R.string.profile_already_exists,
  invalidNameRes: Int = R.string.invalid_profile_name,
) {
  val scope = rememberCoroutineScope()
  val existingNorm = remember(existing) { existing.map { normalizeProfileName(it) }.toSet() }

  var raw by remember { mutableStateOf("") }
  val name = remember(raw) { normalizeProfileName(raw) }
  var error by remember { mutableStateOf<String?>(null) }

  fun snack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val enterNameErr = stringResource(R.string.enter_a_name)
  val invalidNameSnack = stringResource(invalidNameRes)
  val profileExistsErr = stringResource(existsErrorRes)
  val accent = Color(0xFFA78BFA)
  val accentSoft = Color(0xFFFF4D7D)
  val dialogShape = RoundedCornerShape(28.dp)

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp),
      shape = dialogShape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      contentColor = MaterialTheme.colorScheme.onSurface,
      border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
      tonalElevation = 0.dp,
      shadowElevation = 12.dp,
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            brush = Brush.linearGradient(
              listOf(
                accent.copy(alpha = 0.16f),
                accentSoft.copy(alpha = 0.06f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.04f),
              )
            ),
            shape = dialogShape,
          )
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Surface(
              modifier = Modifier.size(46.dp),
              shape = CircleShape,
              color = accent.copy(alpha = 0.20f),
              contentColor = Color.White,
              border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
            ) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp))
              }
            }
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = stringResource(R.string.tab_profiles),
                style = MaterialTheme.typography.labelMedium,
                color = accent.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold,
              )
            }
          }

          Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
          ) {
            Text(
              text = stringResource(rulesRes),
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            )
          }

          OutlinedTextField(
            value = name,
            onValueChange = { v ->
              raw = v
              error = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(nameLabelRes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            supportingText = {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.allowed_chars_hint))
                Text(stringResource(R.string.profile_name_len_fmt, name.length))
              }
            },
            isError = error != null,
            shape = RoundedCornerShape(16.dp),
          )

          if (error != null) {
            Text(
              error!!,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.SemiBold,
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedButton(
              onClick = onDismiss,
              shape = RoundedCornerShape(100.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
              contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) { Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold) }

            Button(
              onClick = {
                val n = name.trim()
                when {
                  n.isEmpty() -> {
                    error = enterNameErr
                    snack(invalidNameSnack)
                  }
                  existingNorm.contains(n) -> {
                    error = profileExistsErr
                    snack(profileExistsErr)
                  }
                  else -> onCreate(n)
                }
              },
              enabled = name.isNotBlank(),
              shape = RoundedCornerShape(100.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.White,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f),
              ),
              contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) { Text(stringResource(R.string.action_create), fontWeight = FontWeight.Bold) }
          }
        }
      }
    }
  }
}

private fun normalizeProfileName(input: String): String {
  // Requirements from Danil:
  // - English only
  // - no spaces (convert spaces to _)
  // - max length 10
  val sb = StringBuilder(10)
  for (ch in input.lowercase()) {
    if (sb.length >= 10) break
    val c = when {
      ch.isWhitespace() -> '_'
      ch in 'a'..'z' -> ch
      ch in '0'..'9' -> ch
      ch == '_' || ch == '-' -> ch
      else -> null
    }
    if (c != null) sb.append(c)
  }
  return sb.toString()
}

@Composable
private fun OperaProxySection(
  programs: List<ApiModels.Program>,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  onAppsSelectionSaved: ((String, Set<String>) -> Unit)? = null,
) {
  var tab by remember { mutableStateOf(0) }

  val scope = rememberCoroutineScope()
  fun snack(msg: String) { scope.launch { snackHost.showSnackbar(msg) } }

  val labels = listOf(
    stringResource(R.string.tab_apps),
    stringResource(R.string.tab_byedpi),
    stringResource(R.string.tab_sni),
    stringResource(R.string.tab_servers),
    stringResource(R.string.tab_opera_args),
  )

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    OperaProxyTabSelector(
      labels = labels,
      selected = tab,
      onSelected = { tab = it },
    )

    when (tab) {
      0 -> {
        OperaSectionIntroCard(
          title = stringResource(R.string.tab_apps),
          description = stringResource(R.string.app_picker_apps_common_desc),
          accent = Color(0xFFFACC15),
        )
        NfqwsAppListsSection(
          pfx = "/api/programs/operaproxy",
          actions = actions,
          snackHost = snackHost,
          programs = programs,
          onSavedSelection = onAppsSelectionSaved,
        )
      }
      1 -> {
        OperaSectionIntroCard(
          title = stringResource(R.string.tab_byedpi),
          description = stringResource(R.string.byedpi_start_args_desc),
          accent = Color(0xFF22C55E),
        )
        TextEditorCard(
          title = stringResource(R.string.byedpi_start_args_title),
          desc = stringResource(R.string.byedpi_start_args_desc),
          path = "/api/programs/operaproxy/byedpi/start_args",
          actions = actions,
          snackHost = snackHost,
        )
        TextEditorCard(
          title = stringResource(R.string.byedpi_restart_args_title),
          desc = stringResource(R.string.byedpi_restart_args_desc),
          path = "/api/programs/operaproxy/byedpi/restart_args",
          actions = actions,
          snackHost = snackHost,
        )
        JsonEditorCard(
          title = stringResource(R.string.opera_ports_title),
          desc = stringResource(R.string.opera_ports_desc),
          path = "/api/programs/operaproxy/ports",
          actions = actions,
          snackHost = snackHost,
        )
      }
      2 -> {
        OperaSniJsonSection(actions = actions, snack = ::snack)
      }
      3 -> {
        OperaServerSection(actions = actions, snack = ::snack)
      }
      4 -> {
        OperaArgsSection(actions = actions, snackHost = snackHost)
      }
    }
  }
}

@Composable
private fun OperaProxyTabSelector(
  labels: List<String>,
  selected: Int,
  onSelected: (Int) -> Unit,
) {
  val accent = Color(0xFFFACC15)
  val shape = RoundedCornerShape(24.dp)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f), shape)
      .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)), shape)
      .padding(horizontal = 8.dp, vertical = 8.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      labels.forEachIndexed { index, label ->
        val isSelected = selected == index
        Surface(
          modifier = Modifier.clickable { onSelected(index) },
          shape = RoundedCornerShape(100.dp),
          color = if (isSelected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
          contentColor = if (isSelected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
          border = BorderStroke(1.dp, if (isSelected) accent.copy(alpha = 0.38f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        ) {
          Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun OperaSectionIntroCard(
  title: String,
  description: String,
  accent: Color,
) {
  val shape = RoundedCornerShape(22.dp)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        brush = Brush.horizontalGradient(
          listOf(
            accent.copy(alpha = 0.13f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
          )
        ),
        shape = shape,
      )
      .border(BorderStroke(1.dp, accent.copy(alpha = 0.24f)), shape)
      .padding(horizontal = 14.dp, vertical = 12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        color = accent.copy(alpha = 0.15f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
      ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val iconRes = programIconRes("operaproxy")
          if (iconRes != null) {
            Icon(
              painter = painterResource(iconRes),
              contentDescription = null,
              modifier = Modifier.size(22.dp),
            )
          } else {
            Icon(
              imageVector = programIcon("operaproxy"),
              contentDescription = null,
              modifier = Modifier.size(19.dp),
            )
          }
        }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun OperaMiniBadge(
  text: String,
  accent: Color,
) {
  Surface(
    shape = RoundedCornerShape(100.dp),
    color = accent.copy(alpha = 0.13f),
    contentColor = accent,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

private data class OperaSniItemUi(
  val sni: String = "",
  val useByedpi: Boolean = false,
  val overrideProxyAddress: String = "",
)

private fun isValidOverrideProxyAddress(value: String): Boolean {
  val text = value.trim()
  if (text.isEmpty()) return true
  val sep = text.lastIndexOf(':')
  if (sep <= 0 || sep >= text.lastIndex) return false
  val host = text.substring(0, sep).trim()
  val portText = text.substring(sep + 1).trim()
  val port = portText.toIntOrNull() ?: return false
  return host.isNotEmpty() && port in 1..65535
}

@Composable
private fun OperaSniJsonSection(
  actions: ZdtdActions,
  snack: (String) -> Unit,
) {
  val apiPath = "/api/programs/operaproxy/sni"
  val saveFailedMsg = stringResource(R.string.save_failed)
  val compact = rememberIsCompactWidth()
  val narrow = rememberIsNarrowWidth()
  val shortHeight = rememberIsShortHeight()

  var items by remember { mutableStateOf(listOf<OperaSniItemUi>()) }
  var loaded by remember { mutableStateOf(false) }
  var editingIndex by remember { mutableStateOf<Int?>(null) }
  var dialogItem by remember { mutableStateOf<OperaSniItemUi?>(null) }

  fun parseItems(raw: String?): List<OperaSniItemUi> {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(text)
      buildList {
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val sni = o.optString("sni", "").trim()
          if (sni.isEmpty()) continue
          add(
            OperaSniItemUi(
              sni = sni,
              useByedpi = o.optBoolean("use_byedpi", false),
              overrideProxyAddress = o.optString("override_proxy_address", "").trim(),
            )
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  fun toJson(items: List<OperaSniItemUi>): String {
    val arr = JSONArray()
    items.forEach { item ->
      val sni = item.sni.trim()
      if (sni.isEmpty()) return@forEach
      arr.put(JSONObject().apply {
        put("sni", sni)
        put("use_byedpi", item.useByedpi)
        val overrideAddress = item.overrideProxyAddress.trim()
        if (overrideAddress.isNotEmpty()) {
          put("override_proxy_address", overrideAddress)
        }
      })
    }
    return arr.toString(2)
  }

  fun persistItems(previous: List<OperaSniItemUi>, updated: List<OperaSniItemUi>) {
    items = updated
    actions.saveText(apiPath, toJson(updated)) { ok ->
      if (!ok) {
        items = previous
        snack(saveFailedMsg)
      }
    }
  }

  LaunchedEffect(Unit) {
    actions.loadText(apiPath) { raw ->
      items = parseItems(raw)
      loaded = true
    }
  }

  dialogItem?.let { current ->
    OperaSniServerDialog(
      initial = current,
      isEditing = editingIndex != null,
      compactMode = narrow || shortHeight,
      onDismiss = {
        dialogItem = null
        editingIndex = null
      },
      onSave = { saved ->
        val previous = items
        val list = items.toMutableList()
        val idx = editingIndex
        if (idx == null) {
          list += saved
        } else if (idx in list.indices) {
          list[idx] = saved
        }
        persistItems(previous = previous, updated = list)
        dialogItem = null
        editingIndex = null
      },
    )
  }

  val accent = MaterialTheme.colorScheme.primary
  val shape = RoundedCornerShape(24.dp)
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          brush = Brush.horizontalGradient(
            listOf(
              accent.copy(alpha = 0.16f),
              MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
            )
          ),
          shape = shape,
        )
        .border(BorderStroke(1.dp, accent.copy(alpha = 0.30f)), shape)
        .clickable {
          editingIndex = null
          dialogItem = OperaSniItemUi()
        }
        .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          modifier = Modifier.size(46.dp),
          shape = CircleShape,
          color = accent.copy(alpha = 0.16f),
          contentColor = accent,
          border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
          }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            text = stringResource(R.string.operaproxy_sni_create_new),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = stringResource(R.string.operaproxy_sni_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Surface(
          shape = RoundedCornerShape(100.dp),
          color = accent.copy(alpha = 0.18f),
          contentColor = accent,
          border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
              text = stringResource(R.string.action_create),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
            )
          }
        }
      }
    }

    if (!loaded) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    if (loaded && items.isEmpty()) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
      ) {
        Text(
          stringResource(R.string.operaproxy_sni_empty),
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
      }
    }

    items.forEachIndexed { index, item ->
      val itemShape = RoundedCornerShape(22.dp)
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f), itemShape)
          .border(BorderStroke(1.dp, accent.copy(alpha = 0.22f)), itemShape)
          .padding(horizontal = 13.dp, vertical = 12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = accent.copy(alpha = 0.13f),
            contentColor = accent,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
          ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = item.sni,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              OperaMiniBadge(
                text = if (item.useByedpi) stringResource(R.string.tab_byedpi) else stringResource(R.string.opera_proxy_title),
                accent = if (item.useByedpi) Color(0xFF22C55E) else accent,
              )
              Text(
                text = item.overrideProxyAddress.ifBlank { stringResource(R.string.operaproxy_sni_address_not_set) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
              onClick = {
                editingIndex = index
                dialogItem = item
              }
            ) {
              Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.operaproxy_sni_edit_server))
            }
            IconButton(
              onClick = {
                val previous = items
                val updated = items.toMutableList().also { it.removeAt(index) }
                persistItems(previous = previous, updated = updated)
              }
            ) {
              Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete_cd))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OperaSniServerDialog(
  initial: OperaSniItemUi,
  isEditing: Boolean,
  compactMode: Boolean,
  onDismiss: () -> Unit,
  onSave: (OperaSniItemUi) -> Unit,
) {
  var sni by remember(initial) { mutableStateOf(initial.sni) }
  var useByedpi by remember(initial) { mutableStateOf(initial.useByedpi) }
  var address by remember(initial) { mutableStateOf(initial.overrideProxyAddress) }
  var attemptedSave by remember { mutableStateOf(false) }
  val shortHeight = rememberIsShortHeight()
  val dialogHorizontalPadding = if (compactMode) 12.dp else 20.dp
  val dialogVerticalPadding = if (shortHeight) 12.dp else 24.dp
  val contentPadding = if (compactMode) 14.dp else 20.dp
  val scrollState = rememberScrollState()

  val currentItem = OperaSniItemUi(
    sni = sni.trim(),
    useByedpi = useByedpi,
    overrideProxyAddress = address.trim(),
  )
  val inputValid = currentItem.sni.isNotEmpty() && isValidOverrideProxyAddress(address)
  val hasChanges = !isEditing || currentItem != initial
  val canSave = inputValid && hasChanges
  val sniError = attemptedSave && currentItem.sni.isEmpty()
  val addressError = attemptedSave && !isValidOverrideProxyAddress(address)

  fun attemptSave() {
    attemptedSave = true
    if (!canSave) return
    onSave(currentItem)
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      dismissOnBackPress = true,
      dismissOnClickOutside = true,
    ),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = dialogHorizontalPadding, vertical = dialogVerticalPadding),
      shape = MaterialTheme.shapes.extraLarge,
      tonalElevation = 6.dp,
      shadowElevation = 12.dp,
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .imePadding()
          .padding(horizontal = contentPadding, vertical = contentPadding)
          .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (compactMode) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                text = stringResource(if (isEditing) R.string.operaproxy_sni_edit_server_title else R.string.operaproxy_sni_new_server_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = stringResource(R.string.operaproxy_sni_dialog_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                  Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                }
              }
              Surface(shape = CircleShape, color = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)) {
                IconButton(onClick = { attemptSave() }, enabled = canSave, modifier = Modifier.size(40.dp)) {
                  Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_save), tint = MaterialTheme.colorScheme.onPrimary)
                }
              }
            }
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
              text = stringResource(if (isEditing) R.string.operaproxy_sni_edit_server_title else R.string.operaproxy_sni_new_server_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = stringResource(R.string.operaproxy_sni_dialog_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }
        }

        OutlinedTextField(
          value = sni,
          onValueChange = { sni = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("SNI") },
          placeholder = { Text(stringResource(R.string.operaproxy_sni_placeholder)) },
          isError = sniError,
          supportingText = {
            if (sniError) {
              Text(stringResource(R.string.operaproxy_sni_required))
            }
          },
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.operaproxy_sni_use_byedpi), fontWeight = FontWeight.Medium)
            Text(
              stringResource(R.string.operaproxy_sni_use_byedpi_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
          Switch(checked = useByedpi, onCheckedChange = { useByedpi = it })
        }

        OutlinedTextField(
          value = address,
          onValueChange = { address = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(stringResource(R.string.operaproxy_sni_server_address)) },
          placeholder = { Text(stringResource(R.string.operaproxy_sni_server_address_placeholder)) },
          isError = addressError,
          supportingText = {
            Text(
              text = if (addressError) stringResource(R.string.operaproxy_sni_server_address_error)
              else stringResource(R.string.operaproxy_sni_server_address_hint),
            )
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        if (!compactMode) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            TextButton(onClick = onDismiss) {
              Text(stringResource(R.string.common_cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { attemptSave() }, enabled = canSave) {
              Text(stringResource(R.string.common_save))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OperaServerSection(
  actions: ZdtdActions,
  snack: (String) -> Unit,
) {
  val compact = rememberIsCompactWidth()
  val apiPath = "/api/programs/operaproxy/server"
  val savedMsg = stringResource(R.string.saved)
  val saveFailedMsg = stringResource(R.string.save_failed)

  fun normalize(raw: String?): String {
    val v = raw.orEmpty().trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().uppercase()
    return if (v == "EU" || v == "AS" || v == "AM") v else "EU"
  }

  var value by remember { mutableStateOf("EU") }
  var loaded by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    actions.loadText(apiPath) { raw ->
      value = normalize(raw)
      loaded = true
    }
  }

  fun saveRegion(code: String) {
    actions.saveText(apiPath, "$code\n") { ok ->
      if (ok) {
        value = code
        snack(savedMsg)
      } else {
        snack(saveFailedMsg)
      }
    }
  }

  val accent = Color(0xFFFACC15)
  val shape = RoundedCornerShape(24.dp)
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        brush = Brush.horizontalGradient(
          listOf(
            accent.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
          )
        ),
        shape = shape,
      )
      .border(BorderStroke(1.dp, accent.copy(alpha = 0.28f)), shape)
      .padding(horizontal = 14.dp, vertical = 13.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          modifier = Modifier.size(42.dp),
          shape = CircleShape,
          color = accent.copy(alpha = 0.16f),
          contentColor = accent,
          border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(21.dp))
          }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.tab_servers),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          Text(
            stringResource(R.string.opera_server_region_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (loaded) {
          OperaMiniBadge(text = value, accent = accent)
        }
      }

      if (!loaded) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      if (compact) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          RegionButton(label = stringResource(R.string.region_europe), code = "EU", selected = value == "EU", modifier = Modifier.fillMaxWidth()) { saveRegion("EU") }
          RegionButton(label = stringResource(R.string.region_asia), code = "AS", selected = value == "AS", modifier = Modifier.fillMaxWidth()) { saveRegion("AS") }
          RegionButton(label = stringResource(R.string.region_america), code = "AM", selected = value == "AM", modifier = Modifier.fillMaxWidth()) { saveRegion("AM") }
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          RegionButton(label = stringResource(R.string.region_europe), code = "EU", selected = value == "EU") { saveRegion("EU") }
          RegionButton(label = stringResource(R.string.region_asia), code = "AS", selected = value == "AS") { saveRegion("AS") }
          RegionButton(label = stringResource(R.string.region_america), code = "AM", selected = value == "AM") { saveRegion("AM") }
        }
      }
    }
  }
}


@Composable
private fun OperaArgsSection(actions: ZdtdActions, snackHost: SnackbarHostState) {
  val apiPath = "/api/programs/operaproxy/args"
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  fun snack(msg: String) { scope.launch { snackHost.showSnackbar(msg) } }

  val dnsApiPath = "/api/programs/operaproxy/bootstrap_dns"

  // State for each OperaArgs field (mirrors the Rust struct)
  var apiProxy        by remember { mutableStateOf("") }
  var apiUserAgent    by remember { mutableStateOf("") }
  var initRetry       by remember { mutableStateOf("") }
  var serverSelection by remember { mutableStateOf("fastest") }
  var dlLimit         by remember { mutableStateOf("") }
  var testUrl         by remember { mutableStateOf("") }
  var verbosity       by remember { mutableStateOf("") }
  var loaded          by remember { mutableStateOf(false) }
  var saving          by remember { mutableStateOf(false) }
  var lastSavedArgsJson by remember { mutableStateOf<String?>(null) }

  // Bootstrap DNS: list of resolver URL strings
  var dnsResolvers    by remember { mutableStateOf(listOf<String>()) }
  var lastSavedDnsResolvers by remember { mutableStateOf<List<String>?>(null) }
  var dnsLoaded       by remember { mutableStateOf(false) }
  var dnsSaving       by remember { mutableStateOf(false) }
  // New resolver input field
  var dnsNewEntry     by remember { mutableStateOf("") }

  fun buildArgsJson(): JSONObject = JSONObject().apply {
    put("api_proxy",                    apiProxy.trim())
    put("api_user_agent",               apiUserAgent.trim())
    put("init_retry_interval",          initRetry.trim())
    put("server_selection",             serverSelection.trim())
    put("server_selection_dl_limit",    dlLimit.trim().toLongOrNull() ?: 204800L)
    put("server_selection_test_url",    testUrl.trim())
    put("verbosity",                    verbosity.trim().toIntOrNull() ?: 50)
  }

  fun normalizeDnsResolvers(list: List<String>): List<String> =
    list.map { it.trim() }.filter { it.isNotEmpty() }

  // Load current args from daemon on first compose
  LaunchedEffect(Unit) {
    actions.loadJsonData(apiPath) { obj ->
      if (obj != null) {
        apiProxy        = obj.optString("api_proxy",                 "socks5://193.221.203.192:1080")
        apiUserAgent    = obj.optString("api_user_agent",            "")
        initRetry       = obj.optString("init_retry_interval",       "3s")
        serverSelection = obj.optString("server_selection",          "fastest")
        dlLimit         = obj.optLong("server_selection_dl_limit",   204800L).toString()
        testUrl         = obj.optString("server_selection_test_url", "https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js")
        verbosity       = obj.optInt("verbosity",                    50).toString()
      }
      lastSavedArgsJson = buildArgsJson().toString()
      loaded = true
    }
    // Load bootstrap DNS resolvers list
    actions.loadJsonData(dnsApiPath) { obj ->
      if (obj != null) {
        val arr = obj.optJSONArray("data") ?: obj.optJSONArray("resolvers")
        if (arr != null) {
          dnsResolvers = (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).trim().takeIf { it.isNotEmpty() }
          }
        }
      }
      // If still empty after load, show defaults hint via empty list
      lastSavedDnsResolvers = normalizeDnsResolvers(dnsResolvers)
      dnsLoaded = true
    }
  }

  fun apiProxyHasInvalidEntries(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    val allowed = listOf("http://", "https://", "socks5://", "socks5h://")
    return trimmed.split(',')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .any { token -> allowed.none { prefix -> token.startsWith(prefix, ignoreCase = true) } }
  }

  fun save() {
    if (apiProxyHasInvalidEntries(apiProxy)) {
      snack(context.getString(R.string.opera_args_api_proxy_invalid))
      return
    }
    val obj = buildArgsJson()
    saving = true
    actions.saveJsonData(apiPath, obj) { ok ->
      saving = false
      if (ok) lastSavedArgsJson = obj.toString()
      snack(if (ok) context.getString(R.string.saved) else context.getString(R.string.save_failed))
    }
  }

  fun saveDns() {
    val list = dnsResolvers.map { it.trim() }.filter { it.isNotEmpty() }
    if (list.isEmpty()) {
      snack(context.getString(R.string.opera_args_dns_empty_error))
      return
    }
    val arr = JSONArray().also { a -> list.forEach { a.put(it) } }
    val obj = JSONObject().put("data", arr)
    // The API expects a plain JSON array, not a wrapped object — send array directly
    dnsSaving = true
    // saveJsonData wraps in JSONObject, so we use saveText with raw JSON
    actions.saveText(dnsApiPath, arr.toString()) { ok ->
      dnsSaving = false
      if (ok) lastSavedDnsResolvers = list
      snack(if (ok) context.getString(R.string.saved) else context.getString(R.string.save_failed))
    }
  }

  if (!loaded) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    return
  }

  val argsDirty = lastSavedArgsJson?.let { buildArgsJson().toString() != it } == true
  val dnsDirty = lastSavedDnsResolvers?.let { normalizeDnsResolvers(dnsResolvers) != it } == true

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

    // ── Card: -api-proxy ── (highlighted as most important)
    val apiProxyInvalid = apiProxyHasInvalidEntries(apiProxy)
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(
        containerColor = Color(0xFFFACC15).copy(alpha = 0.12f)
      ),
      border = BorderStroke(1.dp, Color(0xFFFACC15).copy(alpha = 0.26f)),
    ) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          stringResource(R.string.opera_args_api_proxy_title),
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          stringResource(R.string.opera_args_api_proxy_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = apiProxy,
          onValueChange = { apiProxy = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("-api-proxy") },
          placeholder = { Text("socks5://host:port") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          isError = apiProxyInvalid,
          supportingText = {
            Text(
              if (apiProxyInvalid) {
                stringResource(R.string.opera_args_api_proxy_invalid)
              } else {
                stringResource(R.string.opera_args_api_proxy_hint)
              }
            )
          },
        )
      }
    }

    // ── Card: server selection ──
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.opera_args_server_selection_title),
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.titleSmall,
        )

        // -server-selection chip picker
        Text(
          "-server-selection",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("fastest", "random").forEach { opt ->
            FilterChip(
              selected = serverSelection == opt,
              onClick = { serverSelection = opt },
              label = { Text(opt) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.error,
              ),
            )
          }
        }

        // -server-selection-dl-limit
        OutlinedTextField(
          value = dlLimit,
          onValueChange = { dlLimit = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("-server-selection-dl-limit") },
          placeholder = { Text("204800") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          supportingText = { Text(stringResource(R.string.opera_args_dl_limit_hint)) },
          isError = dlLimit.trim().toLongOrNull() == null,
        )

        // -server-selection-test-url
        OutlinedTextField(
          value = testUrl,
          onValueChange = { testUrl = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("-server-selection-test-url") },
          placeholder = { Text("https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
      }
    }

    // ── Card: timing & verbosity ──
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.opera_args_misc_title),
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.titleSmall,
        )

        // -init-retry-interval
        OutlinedTextField(
          value = initRetry,
          onValueChange = { initRetry = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("-init-retry-interval") },
          placeholder = { Text("3s") },
          supportingText = { Text(stringResource(R.string.opera_args_init_retry_hint)) },
          isError = initRetry.trim().isEmpty(),
        )

        // -verbosity
        OutlinedTextField(
          value = verbosity,
          onValueChange = { verbosity = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("-verbosity") },
          placeholder = { Text("50") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          supportingText = { Text(stringResource(R.string.opera_args_verbosity_hint)) },
          isError = verbosity.trim().toIntOrNull() == null,
        )
      }
    }

    // ── Card: User-Agent (advanced, collapsed visually at bottom) ──
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          stringResource(R.string.opera_args_ua_title),
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          stringResource(R.string.opera_args_ua_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        OutlinedTextField(
          value = apiUserAgent,
          onValueChange = { apiUserAgent = it },
          modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
          singleLine = false,
          maxLines = 3,
          label = { Text("-api-user-agent") },
        )
      }
    }

    // ── Card: Bootstrap DNS ──
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    ) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.opera_args_bootstrap_dns_title),
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          stringResource(R.string.opera_args_bootstrap_dns_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )

        if (!dnsLoaded) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
          // Existing resolver rows
          dnsResolvers.forEachIndexed { index, resolver ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              OutlinedTextField(
                value = resolver,
                onValueChange = { v ->
                  dnsResolvers = dnsResolvers.toMutableList().also { it[index] = v }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.opera_args_dns_resolver_label, index + 1)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
              )
              IconButton(onClick = {
                dnsResolvers = dnsResolvers.toMutableList().also { it.removeAt(index) }
              }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
              }
            }
          }

          // New resolver input row
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            OutlinedTextField(
              value = dnsNewEntry,
              onValueChange = { dnsNewEntry = it },
              modifier = Modifier.weight(1f),
              singleLine = true,
              label = { Text(stringResource(R.string.opera_args_dns_add_label)) },
              placeholder = { Text("https://1.1.1.1/dns-query") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            IconButton(
              onClick = {
                val trimmed = dnsNewEntry.trim()
                if (trimmed.isNotEmpty()) {
                  dnsResolvers = dnsResolvers + trimmed
                  dnsNewEntry = ""
                }
              },
              enabled = dnsNewEntry.trim().isNotEmpty(),
            ) {
              Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.opera_args_dns_add_label))
            }
          }

          // Save DNS button (separate from main args save)
          OutlinedButton(
            onClick = { saveDns() },
            enabled = !dnsSaving && dnsLoaded && dnsDirty,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(100.dp),
            border = BorderStroke(1.dp, Color(0xFFFACC15).copy(alpha = 0.36f)),
            contentPadding = PaddingValues(vertical = 11.dp),
          ) {
            if (dnsSaving) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
              Spacer(Modifier.width(8.dp))
            } else {
              Text(stringResource(R.string.opera_args_dns_save))
            }
          }
        }
      }
    }

    // ── Save button ──
    Button(
      onClick = { save() },
      enabled = !saving && loaded && argsDirty,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(100.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFFACC15),
        contentColor = Color.Black,
      ),
      contentPadding = PaddingValues(vertical = 12.dp),
    ) {
      if (saving) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          strokeWidth = 2.dp,
        )
      } else {
        Text(stringResource(R.string.action_save))
      }
    }

    Text(
      toolDescription("operaproxy"),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    )
  }
}

@Composable
private fun RegionButton(
  label: String,
  code: String,
  selected: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  val accent = Color(0xFFFACC15)
  Surface(
    modifier = modifier.clickable(onClick = onClick),
    shape = RoundedCornerShape(100.dp),
    color = if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
    contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
    border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.38f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = code,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}