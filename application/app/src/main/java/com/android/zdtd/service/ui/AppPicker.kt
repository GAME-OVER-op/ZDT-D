package com.android.zdtd.service.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.util.Collections
import java.util.LinkedHashMap
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal const val ZDTD_APP_PACKAGE_NAME = "com.android.zdtd.service"

data class InstalledApp(
  val packageName: String,
  val label: String,
  val isSystem: Boolean,
  val sortKey: String = label.lowercase(Locale.ROOT),
)

object AppIconMemoryCache {
  private const val MaxEntries = 384

  val map: MutableMap<String, ImageBitmap?> = Collections.synchronizedMap(
    object : LinkedHashMap<String, ImageBitmap?>(MaxEntries, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?): Boolean {
        return size > MaxEntries
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListPickerCard(
  title: String,
  desc: String,
  path: String,
  initialContent: String? = null,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  saveFailedMessage: String? = null,
  programs: List<ApiModels.Program> = emptyList(),
  onSavedSelection: ((Set<String>) -> Unit)? = null,
) {
  // Snackbar messages must be resolved in a composable context.
  val msgSaved = stringResource(R.string.app_picker_saved_apply)
  val msgSaveFailed = stringResource(R.string.app_picker_save_failed)
  var selected by remember(path, initialContent) { mutableStateOf(parsePkgList(initialContent)) }
  var loading by remember(path) { mutableStateOf(true) }
  var saving by remember(path) { mutableStateOf(false) }
  var showPicker by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  fun saveSelection(newSel: Set<String>, removalsByPath: Map<String, Set<String>> = emptyMap()) {
    selected = newSel
    val payload = if (newSel.isEmpty()) "" else newSel.sorted().joinToString("\n", postfix = "\n")
    saving = true
    val done: (Boolean) -> Unit = { ok ->
      saving = false
      if (ok) onSavedSelection?.invoke(newSel)
      scope.launch {
        snackHost.showSnackbar(if (ok) msgSaved else (saveFailedMessage ?: msgSaveFailed))
      }
    }
    if (removalsByPath.isEmpty()) {
      actions.saveText(path, payload, done)
    } else {
      actions.saveAppListResolvingConflicts(path, payload, removalsByPath, done)
    }
  }

  LaunchedEffect(path) {
    loading = true
    actions.loadText(path) { content ->
      if (content != null) {
        selected = parsePkgList(content)
      }
      loading = false
    }
  }

  if (showPicker) {
    AppPickerSheet(
      title = title,
      path = path,
      actions = actions,
      programs = programs,
      initialSelected = selected,
      onDismiss = { showPicker = false },
      onSave = { newSel, removalsByPath ->
        showPicker = false
        saveSelection(newSel, removalsByPath)
      },
    )
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.height(2.dp))
          Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        Button(
          onClick = { showPicker = true },
          enabled = !loading && !saving,
        ) {
          Text(if (saving) "..." else stringResource(R.string.app_picker_select))
        }
      }

      if (loading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
      if (!loading || selected.isNotEmpty()) {
        val preview = selected.sorted().take(6).joinToString("\n").ifBlank { stringResource(R.string.app_picker_empty) }
        Text(
          stringResource(R.string.app_picker_selected_count, selected.size),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
        Surface(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
          shape = MaterialTheme.shapes.medium,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        ) {
          Text(
            preview,
            modifier = Modifier
              .fillMaxWidth()
              .padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
          )
        }
      }
    }
  }
}

@Composable
fun NfqwsAppListsSection(
  pfx: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  programs: List<ApiModels.Program> = emptyList(),
  onSavedSelection: ((String, Set<String>) -> Unit)? = null,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    val userPath = "$pfx/apps/user"
    val mobilePath = "$pfx/apps/mobile"
    val wifiPath = "$pfx/apps/wifi"
    AppListPickerCard(
      title = stringResource(R.string.app_picker_apps_common_title),
      desc = stringResource(R.string.app_picker_apps_common_desc),
      path = userPath,
      actions = actions,
      snackHost = snackHost,
      programs = programs,
      onSavedSelection = { onSavedSelection?.invoke(userPath, it) },
    )
    AppListPickerCard(
      title = stringResource(R.string.app_picker_apps_mobile_title),
      desc = stringResource(R.string.app_picker_apps_mobile_desc),
      path = mobilePath,
      actions = actions,
      snackHost = snackHost,
      programs = programs,
      onSavedSelection = { onSavedSelection?.invoke(mobilePath, it) },
    )
    AppListPickerCard(
      title = stringResource(R.string.app_picker_apps_wifi_title),
      desc = stringResource(R.string.app_picker_apps_wifi_desc),
      path = wifiPath,
      actions = actions,
      snackHost = snackHost,
      programs = programs,
      onSavedSelection = { onSavedSelection?.invoke(wifiPath, it) },
    )
  }
}

private data class AppPickerMoveConfirm(
  val removalsByPath: Map<String, Set<String>>,
  val entries: List<ApiModels.AppAssignmentEntry>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
  title: String,
  path: String,
  actions: ZdtdActions,
  programs: List<ApiModels.Program>,
  initialSelected: Set<String>,
  onDismiss: () -> Unit,
  onSave: (Set<String>, Map<String, Set<String>>) -> Unit,
) {
  val ctx = LocalContext.current

  var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
  var appsLoading by remember { mutableStateOf(true) }
  LaunchedEffect(Unit) {
    appsLoading = true
    apps = withContext(Dispatchers.IO) {
      runCatching { loadInstalledAppsCached(ctx.packageManager) }.getOrDefault(emptyList())
    }
    appsLoading = false
  }
  var assignments by remember(path) { mutableStateOf<ApiModels.AppAssignmentsState?>(null) }
  LaunchedEffect(path) {
    actions.loadAppAssignments { assignments = it ?: ApiModels.AppAssignmentsState() }
  }

  var query by remember { mutableStateOf("") }
  var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
  val hasChanges = selected != initialSelected

  // Cache app icons across sheet opens and list item disposals (important for smooth scrolling).
  val iconCache = remember { AppIconMemoryCache.map }
  val listState = rememberLazyListState()

  var debouncedQuery by remember { mutableStateOf("") }
  LaunchedEffect(query) {
    delay(180L)
    debouncedQuery = query.trim().lowercase(Locale.ROOT)
  }

  fun matchesSearch(app: InstalledApp, normalizedQuery: String): Boolean {
    if (normalizedQuery.isBlank()) return true
    return app.label.lowercase(Locale.ROOT).contains(normalizedQuery) ||
      app.packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
  }

  val filtered = remember(apps, debouncedQuery) {
    if (debouncedQuery.isBlank()) apps else apps.filter { matchesSearch(it, debouncedQuery) }
  }

  val currentEntry = remember(assignments, path) {
    assignments?.lists?.firstOrNull { it.path == path }
  }

  val slotCommonLabel = stringResource(R.string.apps_conflict_slot_common)
  val slotWifiLabel = stringResource(R.string.apps_conflict_slot_wifi)
  val slotMobileLabel = stringResource(R.string.apps_conflict_slot_mobile)
  val programOperaLabel = stringResource(R.string.apps_conflict_program_operaproxy)
  val programSingboxLabel = stringResource(R.string.apps_conflict_program_singbox)
  val programDpitunnelLabel = stringResource(R.string.apps_conflict_program_dpitunnel)
  val programByedpiLabel = stringResource(R.string.apps_conflict_program_byedpi)
  val programZapretLabel = stringResource(R.string.apps_conflict_program_zapret)
  val programZapret2Label = stringResource(R.string.apps_conflict_program_zapret2)
  val programWireproxyLabel = stringResource(R.string.apps_conflict_program_wireproxy)
  val programTorLabel = stringResource(R.string.apps_conflict_program_tor)
  val programMyproxyLabel = stringResource(R.string.apps_conflict_program_myproxy)
  val programMyprogramLabel = stringResource(R.string.apps_conflict_program_myprogram)
  val programOpenVpnLabel = stringResource(R.string.apps_conflict_program_openvpn)
  val programTun2SocksLabel = stringResource(R.string.apps_conflict_program_tun2socks)
  val programMyVpnLabel = stringResource(R.string.apps_conflict_program_myvpn)
  val programMihomoLabel = stringResource(R.string.apps_conflict_program_mihomo)
  val programMieruLabel = stringResource(R.string.apps_conflict_program_mieru)
  val programAmneziaWgLabel = stringResource(R.string.apps_conflict_program_amneziawg)

  fun slotLabel(slot: String): String = when (slot.lowercase(Locale.ROOT)) {
    "common" -> slotCommonLabel
    "wifi" -> slotWifiLabel
    "mobile" -> slotMobileLabel
    else -> slot
  }

  fun programLabel(programId: String): String = when (programId) {
    "operaproxy" -> programOperaLabel
    "sing-box" -> programSingboxLabel
    "dpitunnel" -> programDpitunnelLabel
    "byedpi" -> programByedpiLabel
    "nfqws" -> programZapretLabel
    "nfqws2" -> programZapret2Label
    "wireproxy" -> programWireproxyLabel
    "tor" -> programTorLabel
    "myproxy" -> programMyproxyLabel
    "myprogram" -> programMyprogramLabel
    "openvpn" -> programOpenVpnLabel
    "tun2socks" -> programTun2SocksLabel
    "myvpn" -> programMyVpnLabel
    "mihomo" -> programMihomoLabel
    "mieru" -> programMieruLabel
    "amneziawg" -> programAmneziaWgLabel
    else -> programId
  }

  fun programGroup(programId: String): String? = when (programId) {
    "operaproxy", "sing-box", "dpitunnel", "byedpi", "wireproxy", "tor", "myproxy", "myprogram", "openvpn", "tun2socks", "myvpn", "mihomo", "mieru", "amneziawg" -> "tunnel"
    "nfqws", "nfqws2" -> "zapret"
    else -> null
  }

  fun appListsConflict(leftProgramId: String, rightProgramId: String): Boolean {
    val left = programGroup(leftProgramId) ?: return false
    val right = programGroup(rightProgramId) ?: return false
    if (leftProgramId == "openvpn" || rightProgramId == "openvpn") return true
    if (leftProgramId == "tun2socks" || rightProgramId == "tun2socks") return true
    if (leftProgramId == "myvpn" || rightProgramId == "myvpn") return true
    if (leftProgramId == "mihomo" || rightProgramId == "mihomo") return true
    if (leftProgramId == "mieru" || rightProgramId == "mieru") return true
    if (leftProgramId == "amneziawg" || rightProgramId == "amneziawg") return true
    return left == right
  }

  fun entryLabel(entry: ApiModels.AppAssignmentEntry): String {
    val base = programLabel(entry.programId)
    val slot = slotLabel(entry.slot)
    return if (entry.profile.isNullOrBlank()) "$base / $slot" else "$base / ${entry.profile} / $slot"
  }

  fun sameProgramId(left: String, right: String): Boolean {
    fun normalize(id: String): String = id.trim().lowercase(Locale.ROOT).replace("_", "-")
    val l = normalize(left)
    val r = normalize(right)
    return l == r || (l == "singbox" && r == "sing-box") || (l == "sing-box" && r == "singbox")
  }

  fun isAssignmentEnabled(entry: ApiModels.AppAssignmentEntry): Boolean {
    if (programs.isEmpty()) return true
    val program = programs.firstOrNull { sameProgramId(it.id, entry.programId) } ?: return true
    val profile = entry.profile?.trim().orEmpty()
    if (profile.isNotEmpty()) {
      return program.profiles.firstOrNull { it.name == profile }?.enabled ?: true
    }
    return program.enabled
  }

  val hiddenPackages = remember(assignments, selected) {
    ((assignments?.proxyInfoPackages ?: emptySet()) - selected - ZDTD_APP_PACKAGE_NAME)
  }

  fun buildConflictReasons(active: Boolean): Map<String, List<ApiModels.AppAssignmentEntry>> {
    val out = linkedMapOf<String, MutableList<ApiModels.AppAssignmentEntry>>()
    val entry = currentEntry
    val data = assignments
    if (entry != null && data != null) {
      if (programGroup(entry.programId) != null) {
        for (other in data.lists) {
          if (other.path == entry.path) continue
          val requiresSameSlot = entry.programId != "openvpn" && other.programId != "openvpn" && entry.programId != "tun2socks" && other.programId != "tun2socks" && entry.programId != "myvpn" && other.programId != "myvpn" && entry.programId != "mihomo" && other.programId != "mihomo" && entry.programId != "mieru" && other.programId != "mieru" && entry.programId != "amneziawg" && other.programId != "amneziawg"
          if (requiresSameSlot && other.slot != entry.slot) continue
          if (!appListsConflict(entry.programId, other.programId)) continue
          if (isAssignmentEnabled(other) != active) continue
          for (pkg in other.packages) {
            if (pkg == ZDTD_APP_PACKAGE_NAME) continue
            out.getOrPut(pkg) { mutableListOf() }.add(other)
          }
        }
      }
    }
    return out
  }

  val activeConflictReasons = remember(assignments, currentEntry, programs) { buildConflictReasons(active = true) }
  val inactiveConflictReasons = remember(assignments, currentEntry, programs) { buildConflictReasons(active = false) }

  var pendingMoveConfirm by remember { mutableStateOf<AppPickerMoveConfirm?>(null) }

  fun conflictRemovalsForSave(): Map<String, Set<String>> {
    val added = selected - initialSelected - ZDTD_APP_PACKAGE_NAME
    if (added.isEmpty()) return emptyMap()
    val out = linkedMapOf<String, MutableSet<String>>()
    for (pkg in added) {
      val reasons = inactiveConflictReasons[pkg].orEmpty()
      for (entry in reasons) {
        if (entry.path == path) continue
        out.getOrPut(entry.path) { linkedSetOf() }.add(pkg)
      }
    }
    return out
  }

  fun requestSave() {
    val removals = conflictRemovalsForSave()
    if (removals.isEmpty()) {
      onSave(selected, emptyMap())
    } else {
      val entries = assignments?.lists.orEmpty().filter { entry ->
        val pkgs = removals[entry.path].orEmpty()
        pkgs.isNotEmpty()
      }
      pendingMoveConfirm = AppPickerMoveConfirm(removals, entries)
    }
  }

  val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }
  val selectedAppsAll = remember(appsByPackage, selected) {
    selected.map { pkg -> appsByPackage[pkg] ?: InstalledApp(pkg, pkg, false) }
      .sortedBy { it.sortKey }
  }
  val selectedApps = remember(selectedAppsAll, debouncedQuery) {
    if (debouncedQuery.isBlank()) selectedAppsAll else selectedAppsAll.filter { matchesSearch(it, debouncedQuery) }
  }
  val notSelectedApps = remember(filtered, selected, hiddenPackages) {
    filtered.filter { it.packageName !in selected && it.packageName !in hiddenPackages }
  }
  val showSelectedSection = debouncedQuery.isBlank() || selectedApps.isNotEmpty()

  val isCompactWidth = rememberIsCompactWidth()
  val isNarrowWidth = rememberIsNarrowWidth()
  val isShortHeight = rememberIsShortHeight()
  val useCompactHeader = isShortHeight || isNarrowWidth

  LaunchedEffect(debouncedQuery, appsLoading) {
    if (!appsLoading) listState.animateScrollToItem(0)
  }


  pendingMoveConfirm?.let { confirm ->
    val detailLines = confirm.entries.mapNotNull { entry ->
      val pkgs = confirm.removalsByPath[entry.path].orEmpty()
      if (pkgs.isEmpty()) null else "${entryLabel(entry)}: ${pkgs.sorted().joinToString(", ")}"
    }.take(8)
    AlertDialog(
      onDismissRequest = { pendingMoveConfirm = null },
      title = { Text(stringResource(R.string.app_picker_move_conflict_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.app_picker_move_conflict_body))
          if (detailLines.isNotEmpty()) {
            Surface(
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
              shape = MaterialTheme.shapes.medium,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            ) {
              Text(
                detailLines.joinToString("\n"),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
              )
            }
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingMoveConfirm = null }) { Text(stringResource(R.string.common_no)) }
      },
      confirmButton = {
        Button(onClick = {
          val removals = confirm.removalsByPath
          pendingMoveConfirm = null
          onSave(selected, removals)
        }) { Text(stringResource(R.string.common_yes)) }
      },
    )
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize()) {
      if (useCompactHeader) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
              IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.app_picker_cancel))
              }
            }
            Surface(
              shape = CircleShape,
              color = if (hasChanges) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            ) {
              IconButton(onClick = { requestSave() }, enabled = hasChanges, modifier = Modifier.size(40.dp)) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = stringResource(R.string.app_picker_save),
                  tint = MaterialTheme.colorScheme.onPrimary,
                )
              }
            }
          }
        }
      } else {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.width(12.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_picker_cancel)) }
            Button(onClick = { requestSave() }, enabled = hasChanges) { Text(stringResource(R.string.app_picker_save)) }
          }
        }
      }

      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.app_picker_search)) },
      )

      Spacer(Modifier.height(10.dp))

      Crossfade(targetState = appsLoading) { isLoading ->
        if (isLoading) {
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = if (isShortHeight) 220.dp else 280.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.32f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              Text(
                stringResource(R.string.app_picker_loading_apps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
              )
            }
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = if (isShortHeight) 220.dp else 280.dp, max = if (isShortHeight) 420.dp else 620.dp)
              .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            item(key = "selected_section") {
              AnimatedVisibility(
                visible = showSelectedSection,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
              ) {
                Column(Modifier.fillMaxWidth()) {
                  Text(
                    stringResource(R.string.app_picker_selected_header, selectedApps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                  )
                  Spacer(Modifier.height(4.dp))
                  if (selectedApps.isEmpty()) {
                    Text(
                      stringResource(R.string.app_picker_none),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                    )
                  }
                }
              }
            }

            items(selectedApps, key = { "sel:" + it.packageName }, contentType = { "app_selected" }) { app ->
              AppPickerRow(
                app = app,
                selected = true,
                compactWidth = isCompactWidth,
                iconCache = iconCache,
                enabled = true,
                reason = null,
                onToggle = { selected = selected - app.packageName },
              )
            }

            item(key = "all_apps_header") {
              Column(Modifier.fillMaxWidth().animateContentSize()) {
                if (showSelectedSection || selectedApps.isNotEmpty()) {
                  Spacer(Modifier.height(10.dp))
                  Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                  Spacer(Modifier.height(10.dp))
                }
                Text(
                  stringResource(R.string.app_picker_all_apps_title),
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                  stringResource(R.string.app_picker_all_apps_hint),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
                Spacer(Modifier.height(6.dp))
              }
            }

            if (notSelectedApps.isEmpty()) {
              item(key = "available_empty") {
                Text(
                  stringResource(if (debouncedQuery.isBlank()) R.string.app_picker_none else R.string.app_picker_no_matches),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
              }
            } else {
              items(notSelectedApps, key = { "all:" + it.packageName }, contentType = { "app_available" }) { app ->
                val activeReasons = activeConflictReasons[app.packageName].orEmpty()
                val inactiveReasons = inactiveConflictReasons[app.packageName].orEmpty()
                val activeReason = if (activeReasons.isEmpty()) null else stringResource(
                  R.string.app_picker_conflict_used_in,
                  activeReasons.joinToString(", ") { entryLabel(it) },
                )
                val inactiveReason = if (activeReason == null && inactiveReasons.isNotEmpty()) stringResource(
                  R.string.app_picker_conflict_will_move,
                  inactiveReasons.joinToString(", ") { entryLabel(it) },
                ) else null
                AppPickerRow(
                  app = app,
                  selected = false,
                  compactWidth = isCompactWidth,
                  iconCache = iconCache,
                  enabled = activeReason == null,
                  reason = activeReason ?: inactiveReason,
                  reasonIsWarning = inactiveReason != null,
                  onToggle = { selected = selected + app.packageName },
                )
              }
            }

            item { Spacer(Modifier.height(30.dp)) }
          }
        }
      }
    }
  }
}

@Composable
private fun AppPickerRow(
  app: InstalledApp,
  selected: Boolean,
  compactWidth: Boolean,
  iconCache: MutableMap<String, ImageBitmap?>,
  enabled: Boolean,
  reason: String?,
  reasonIsWarning: Boolean = false,
  onToggle: () -> Unit,
) {
  val isOwnApp = app.packageName == ZDTD_APP_PACKAGE_NAME
  Surface(
    modifier = Modifier.animateContentSize(),
    shape = MaterialTheme.shapes.medium,
    color = when {
      isOwnApp -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (selected) 0.56f else 0.44f)
      selected -> MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
      else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.32f)
    },
    border = if (isOwnApp) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)) else null,
    tonalElevation = if (isOwnApp) 1.dp else 0.dp,
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled) { onToggle() }
        .padding(horizontal = 10.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      AppIcon(packageName = app.packageName, cache = iconCache)
      Checkbox(
        checked = selected,
        onCheckedChange = { if (enabled) onToggle() },
        enabled = enabled,
      )
      Column(Modifier.weight(1f)) {
        Text(
          app.label,
          maxLines = if (compactWidth) 2 else 1,
          overflow = TextOverflow.Ellipsis,
          color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
        )
        Text(
          app.packageName,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.60f else 0.42f),
          maxLines = if (compactWidth) 2 else 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (isOwnApp) {
          Spacer(Modifier.height(3.dp))
          Text(
            stringResource(R.string.app_picker_own_app_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (!reason.isNullOrBlank()) {
          Spacer(Modifier.height(2.dp))
          Text(
            reason,
            style = MaterialTheme.typography.labelSmall,
            color = if (reasonIsWarning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (app.isSystem) {
        Spacer(Modifier.width(10.dp))
        AssistChip(
          onClick = {},
          enabled = false,
          label = { Text(stringResource(R.string.app_picker_system_label)) },
        )
      }
    }
  }
}

fun parsePkgList(content: String?): Set<String> {
  if (content.isNullOrBlank()) return emptySet()
  return content
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .filterNot { it.startsWith("#") || it.startsWith("//") }
    .toSet()
}

fun isOnlyZdtdAppSelected(packages: Set<String>): Boolean =
  packages.size == 1 && packages.contains(ZDTD_APP_PACKAGE_NAME)

private object InstalledAppMemoryCache {
  private const val TtlMs = 120_000L
  private var cachedAtMs: Long = 0L
  private var cachedApps: List<InstalledApp>? = null

  @Synchronized
  fun getOrLoad(pm: PackageManager): List<InstalledApp> {
    val now = System.currentTimeMillis()
    val cached = cachedApps
    if (cached != null && now - cachedAtMs < TtlMs) return cached
    val loaded = loadInstalledApps(pm)
    cachedApps = loaded
    cachedAtMs = now
    return loaded
  }
}

fun loadInstalledAppsCached(pm: PackageManager): List<InstalledApp> = InstalledAppMemoryCache.getOrLoad(pm)

fun loadInstalledApps(pm: PackageManager): List<InstalledApp> {
  val apps = runCatching {
    if (Build.VERSION.SDK_INT >= 33) {
      pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      pm.getInstalledApplications(0)
    }
  }.getOrDefault(emptyList())

  return apps
    .asSequence()
    .filter { it.packageName.isNotBlank() }
    .map { ai ->
      val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
      val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
      InstalledApp(ai.packageName, label, isSystem)
    }
    .distinctBy { it.packageName }
    // User apps first, then system apps, then alphabetical.
    .sortedWith(compareBy<InstalledApp>({ it.isSystem }, { it.sortKey }))
    .toList()
}

@Composable
fun AppIcon(packageName: String, cache: MutableMap<String, ImageBitmap?>) {
  val ctx = LocalContext.current
  val pm = ctx.packageManager
  val density = LocalDensity.current
  val px = with(density) { 32.dp.roundToPx() }

  // Fast path: already cached.
  var icon by remember(packageName) { mutableStateOf(cache[packageName]) }
  LaunchedEffect(packageName) {
    if (cache.containsKey(packageName)) {
      icon = cache[packageName]
      return@LaunchedEffect
    }
    val bmp = withContext(Dispatchers.IO) {
      runCatching {
        val d = pm.getApplicationIcon(packageName)
        d.toBitmap(width = px, height = px).asImageBitmap()
      }.getOrNull()
    }
    cache[packageName] = bmp
    icon = bmp
  }

  Surface(
    modifier = Modifier.size(32.dp),
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
  ) {
    if (icon != null) {
      Image(
        bitmap = icon!!,
        contentDescription = null,
        modifier = Modifier
          .fillMaxSize()
          .padding(4.dp),
      )
    }
  }
}
