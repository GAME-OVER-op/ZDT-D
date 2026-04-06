package com.android.zdtd.service.ui

import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class InstalledApp(
  val packageName: String,
  val label: String,
  val isSystem: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListPickerCard(
  title: String,
  desc: String,
  path: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  // Snackbar messages must be resolved in a composable context.
  val msgSaved = stringResource(R.string.app_picker_saved_apply)
  val msgSaveFailed = stringResource(R.string.app_picker_save_failed)

  var selected by remember(path) { mutableStateOf<Set<String>>(emptySet()) }
  var loading by remember(path) { mutableStateOf(true) }
  var saving by remember(path) { mutableStateOf(false) }
  var showPicker by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(path) {
    loading = true
    actions.loadText(path) { content ->
      selected = parsePkgList(content)
      loading = false
    }
  }

  if (showPicker) {
    AppPickerSheet(
      title = title,
      path = path,
      actions = actions,
      initialSelected = selected,
      onDismiss = { showPicker = false },
      onSave = { newSel ->
        showPicker = false
        selected = newSel
        val payload = newSel.sorted().joinToString("\n")
        saving = true
        actions.saveText(path, payload) { ok ->
          saving = false
          scope.launch {
            snackHost.showSnackbar(if (ok) msgSaved else msgSaveFailed)
          }
        }
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
      } else {
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
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    AppListPickerCard(
      title = stringResource(R.string.app_picker_apps_common_title),
      desc = stringResource(R.string.app_picker_apps_common_desc),
      path = "$pfx/apps/user",
      actions = actions,
      snackHost = snackHost,
    )
    AppListPickerCard(
      title = stringResource(R.string.app_picker_apps_mobile_title),
      desc = stringResource(R.string.app_picker_apps_mobile_desc),
      path = "$pfx/apps/mobile",
      actions = actions,
      snackHost = snackHost,
    )
    AppListPickerCard(
      title = stringResource(R.string.app_picker_apps_wifi_title),
      desc = stringResource(R.string.app_picker_apps_wifi_desc),
      path = "$pfx/apps/wifi",
      actions = actions,
      snackHost = snackHost,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
  title: String,
  path: String,
  actions: ZdtdActions,
  initialSelected: Set<String>,
  onDismiss: () -> Unit,
  onSave: (Set<String>) -> Unit,
) {
  val ctx = LocalContext.current

  val apps by produceState<List<InstalledApp>>(initialValue = emptyList(), key1 = Unit) {
    value = withContext(Dispatchers.IO) {
      runCatching { loadInstalledApps(ctx.packageManager) }.getOrDefault(emptyList())
    }
  }
  var assignments by remember(path) { mutableStateOf<ApiModels.AppAssignmentsState?>(null) }
  LaunchedEffect(path) {
    actions.loadAppAssignments { assignments = it ?: ApiModels.AppAssignmentsState() }
  }

  var query by remember { mutableStateOf("") }
  var selected by remember { mutableStateOf(initialSelected) }

  // Cache app icons across list item disposals (important for smooth scrolling).
  val iconCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

  val q = query.trim().lowercase(Locale.ROOT)
  val filtered = remember(apps, q) {
    if (q.isBlank()) apps
    else apps.filter { it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q) }
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
    else -> programId
  }

  fun programGroup(programId: String): String? = when (programId) {
    "operaproxy", "sing-box", "dpitunnel", "byedpi" -> "tunnel"
    "nfqws", "nfqws2" -> "zapret"
    else -> null
  }

    fun entryLabel(entry: ApiModels.AppAssignmentEntry): String {
    val base = programLabel(entry.programId)
    val slot = slotLabel(entry.slot)
    return if (entry.profile.isNullOrBlank()) "$base / $slot" else "$base / ${entry.profile} / $slot"
  }

  val hiddenPackages = remember(assignments, selected) {
    ((assignments?.proxyInfoPackages ?: emptySet()) - selected)
  }

  val disabledReasons = remember(assignments, currentEntry, selected) {
    val out = linkedMapOf<String, MutableList<ApiModels.AppAssignmentEntry>>()
    val entry = currentEntry
    val data = assignments
    if (entry != null && data != null) {
      val group = programGroup(entry.programId)
      if (group != null) {
        for (other in data.lists) {
          if (other.path == entry.path) continue
          if (other.slot != entry.slot) continue
          if (programGroup(other.programId) != group) continue
          for (pkg in other.packages) {
            if (pkg in selected) continue
            out.getOrPut(pkg) { mutableListOf() }.add(other)
          }
        }
      }
    }
    out
  }

  val selectedApps = remember(apps, selected) {
    val byPkg = apps.associateBy { it.packageName }
    selected.map { pkg -> byPkg[pkg] ?: InstalledApp(pkg, pkg, false) }
      .sortedBy { it.label.lowercase(Locale.ROOT) }
  }
  val notSelectedApps = remember(filtered, selected, hiddenPackages) {
    filtered.filter { it.packageName !in selected && it.packageName !in hiddenPackages }
  }

  val isCompactWidth = rememberIsCompactWidth()
  val isNarrowWidth = rememberIsNarrowWidth()
  val isShortHeight = rememberIsShortHeight()
  val useCompactHeader = isShortHeight || isNarrowWidth

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
              IconButton(onClick = { onSave(selected) }, modifier = Modifier.size(40.dp)) {
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
            Button(onClick = { onSave(selected) }) { Text(stringResource(R.string.app_picker_save)) }
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

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = if (isShortHeight) 220.dp else 280.dp, max = if (isShortHeight) 420.dp else 620.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        item {
          Text(
            stringResource(R.string.app_picker_selected_header, selected.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
          Spacer(Modifier.height(4.dp))
        }

        if (selectedApps.isEmpty()) {
          item {
            Text(
              stringResource(R.string.app_picker_none),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
            )
          }
        } else {
          items(selectedApps, key = { "sel:" + it.packageName }) { app ->
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
        }

        item {
          Spacer(Modifier.height(10.dp))
          Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
          Spacer(Modifier.height(10.dp))
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

        items(notSelectedApps, key = { "all:" + it.packageName }) { app ->
          val reasons = disabledReasons[app.packageName].orEmpty()
          val disabledReason = if (reasons.isEmpty()) null else stringResource(
            R.string.app_picker_conflict_used_in,
            reasons.joinToString(", ") { entryLabel(it) },
          )
          AppPickerRow(
            app = app,
            selected = false,
            compactWidth = isCompactWidth,
            iconCache = iconCache,
            enabled = disabledReason == null,
            reason = disabledReason,
            onToggle = { selected = selected + app.packageName },
          )
        }

        item { Spacer(Modifier.height(30.dp)) }
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
  onToggle: () -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = if (selected) {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    } else {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.32f)
    },
    tonalElevation = 0.dp,
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
        if (!reason.isNullOrBlank()) {
          Spacer(Modifier.height(2.dp))
          Text(
            reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
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
    .sortedWith(compareBy<InstalledApp>({ it.isSystem }, { it.label.lowercase(Locale.ROOT) }))
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
