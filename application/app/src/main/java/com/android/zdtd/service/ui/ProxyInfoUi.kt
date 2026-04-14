package com.android.zdtd.service.ui

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.R
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ProxyInfoSectionCard(
  enabled: Boolean,
  busy: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onConfigure: () -> Unit,
) {
  var showWarning by remember { mutableStateOf(false) }
  val compactWidth = rememberIsCompactWidth()

  Column(
    modifier = Modifier.fillMaxWidth(),
  ) {
    if (compactWidth) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.settings_proxyinfo_title), style = MaterialTheme.typography.bodyLarge)
          Text(
            stringResource(R.string.settings_proxyinfo_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Switch(
          checked = enabled,
          onCheckedChange = { checked ->
            if (busy) return@Switch
            if (checked && !enabled) showWarning = true else onEnabledChange(checked)
          },
          enabled = !busy,
        )
      }
    } else {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
          Text(stringResource(R.string.settings_proxyinfo_title), style = MaterialTheme.typography.bodyLarge)
          Text(
            stringResource(R.string.settings_proxyinfo_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Switch(
          checked = enabled,
          onCheckedChange = { checked ->
            if (busy) return@Switch
            if (checked && !enabled) showWarning = true else onEnabledChange(checked)
          },
          enabled = !busy,
        )
      }
    }

    AnimatedVisibility(visible = enabled) {
      Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        OutlinedButton(
          onClick = onConfigure,
          modifier = Modifier.fillMaxWidth(),
          enabled = !busy,
        ) {
          Text(stringResource(R.string.settings_proxyinfo_configure))
        }
      }
    }
  }

  if (showWarning) {
    AlertDialog(
      onDismissRequest = { showWarning = false },
      title = { Text(stringResource(R.string.settings_proxyinfo_warning_title)) },
      text = { Text(stringResource(R.string.settings_proxyinfo_warning_body)) },
      dismissButton = {
        OutlinedButton(onClick = { showWarning = false }) {
          Text(stringResource(R.string.common_cancel))
        }
      },
      confirmButton = {
        Button(onClick = {
          showWarning = false
          onEnabledChange(true)
        }) {
          Text(stringResource(R.string.settings_proxyinfo_warning_accept))
        }
      },
    )
  }
}

@Composable
fun ProxyInfoAppsDialog(
  initialContent: String,
  saving: Boolean,
  onDismiss: () -> Unit,
  onLoadAssignments: (((ApiModels.AppAssignmentsState?) -> Unit) -> Unit),
  onSave: (String, (Boolean) -> Unit) -> Unit,
  onSaveRemovingConflicts: (String, (Boolean) -> Unit) -> Unit,
) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val pm = ctx.packageManager
  var query by remember { mutableStateOf("") }
  var selected by remember(initialContent) { mutableStateOf(parsePkgList(initialContent)) }
  var assignments by remember { mutableStateOf<ApiModels.AppAssignmentsState?>(null) }
  var pendingConflicts by remember { mutableStateOf<Map<String, List<ApiModels.AppAssignmentEntry>>>(emptyMap()) }
  val iconCache = remember { mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>() }
  val apps by produceState<List<InstalledApp>>(initialValue = emptyList(), key1 = Unit) {
    value = withContext(Dispatchers.IO) {
      runCatching { loadInstalledApps(pm) }.getOrDefault(emptyList())
    }
  }
  LaunchedEffect(Unit) {
    onLoadAssignments { assignments = it ?: ApiModels.AppAssignmentsState() }
  }

  val q = query.trim().lowercase(Locale.ROOT)
  val filtered = remember(apps, q) {
    if (q.isBlank()) apps
    else apps.filter {
      it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
    }
  }
  val selectedApps = remember(apps, selected) {
    val byPkg = apps.associateBy { it.packageName }
    selected.map { pkg -> byPkg[pkg] ?: InstalledApp(pkg, pkg, false) }
      .sortedBy { it.label.lowercase(Locale.ROOT) }
  }
  val notSelectedApps = remember(filtered, selected) { filtered.filter { it.packageName !in selected } }
  val compactWidth = rememberIsCompactWidth()
  val narrowWidth = rememberIsNarrowWidth()
  val shortHeight = rememberIsShortHeight()
  val useCompactHeader = shortHeight || narrowWidth
  val dialogHorizontalPadding = if (compactWidth) 10.dp else 18.dp
  val dialogVerticalPadding = if (shortHeight) 8.dp else 20.dp
  val contentPadding = if (shortHeight) 12.dp else 16.dp

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
    else -> programId
  }

    fun entryLabel(entry: ApiModels.AppAssignmentEntry): String {
    val base = programLabel(entry.programId)
    val slot = slotLabel(entry.slot)
    return if (entry.profile.isNullOrBlank()) "$base / $slot" else "$base / ${entry.profile} / $slot"
  }

  fun computeConflicts(pkgs: Set<String>): Map<String, List<ApiModels.AppAssignmentEntry>> {
    val data = assignments ?: return emptyMap()
    val out = linkedMapOf<String, MutableList<ApiModels.AppAssignmentEntry>>()
    for (entry in data.lists) {
      for (pkg in entry.packages) {
        if (pkg in pkgs) out.getOrPut(pkg) { mutableListOf() }.add(entry)
      }
    }
    return out
  }

  val selectedConflicts = remember(selected, assignments) { computeConflicts(selected) }

  fun attemptSave() {
    val payload = selected.sorted().joinToString("\n")
    if (selectedConflicts.isEmpty()) {
      onSave(payload) { ok -> if (ok) onDismiss() }
    } else {
      pendingConflicts = selectedConflicts
    }
  }

  Dialog(
    onDismissRequest = { if (!saving) onDismiss() },
    properties = DialogProperties(
      dismissOnBackPress = !saving,
      dismissOnClickOutside = !saving,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(if (shortHeight) 0.96f else 0.90f)
        .padding(horizontal = dialogHorizontalPadding, vertical = dialogVerticalPadding),
      shape = MaterialTheme.shapes.extraLarge,
      tonalElevation = 6.dp,
      shadowElevation = 12.dp,
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .navigationBarsPadding()
          .imePadding()
          .padding(horizontal = contentPadding, vertical = contentPadding),
      ) {
        if (useCompactHeader) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.settings_proxyinfo_apps_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = stringResource(R.string.settings_proxyinfo_apps_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
              ) {
                IconButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.size(40.dp)) {
                  Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                }
              }
              Surface(
                shape = CircleShape,
                color = if (saving) {
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
                } else {
                  MaterialTheme.colorScheme.primary
                },
              ) {
                IconButton(onClick = { attemptSave() }, enabled = !saving, modifier = Modifier.size(40.dp)) {
                  Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.common_save),
                    tint = MaterialTheme.colorScheme.onPrimary,
                  )
                }
              }
            }
          }
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.settings_proxyinfo_apps_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = stringResource(R.string.settings_proxyinfo_apps_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
              )
            }
            Surface(
              shape = MaterialTheme.shapes.large,
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
            ) {
              IconButton(onClick = onDismiss, enabled = !saving) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
              }
            }
          }
        }

        Spacer(Modifier.height(if (shortHeight) 8.dp else 12.dp))

        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(stringResource(R.string.settings_proxyinfo_search_hint)) },
          enabled = !saving,
        )

        Spacer(Modifier.height(if (shortHeight) 8.dp else 12.dp))

        if (apps.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f, fill = true),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator()
          }
        } else {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(if (shortHeight) 6.dp else 8.dp),
          ) {
            item {
              Text(
                text = stringResource(R.string.settings_proxyinfo_selected_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
              )
            }
            if (selectedApps.isEmpty()) {
              item {
                Text(
                  text = stringResource(R.string.settings_proxyinfo_empty),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
              }
            } else {
              items(selectedApps, key = { "sel:" + it.packageName }) { app ->
                ProxyInfoAppRow(
                  app = app,
                  selected = true,
                  compactWidth = compactWidth,
                  iconCache = iconCache,
                  enabled = !saving,
                  onToggle = { checked ->
                    selected = if (checked) selected + app.packageName else selected - app.packageName
                  },
                )
              }
            }

            item {
              Spacer(Modifier.height(if (shortHeight) 6.dp else 8.dp))
              HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
              Spacer(Modifier.height(if (shortHeight) 6.dp else 8.dp))
              Text(
                text = stringResource(R.string.settings_proxyinfo_all_apps_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
              )
            }

            items(notSelectedApps, key = { "all:" + it.packageName }) { app ->
              ProxyInfoAppRow(
                app = app,
                selected = false,
                compactWidth = compactWidth,
                iconCache = iconCache,
                enabled = !saving,
                onToggle = { checked ->
                  selected = if (checked) selected + app.packageName else selected - app.packageName
                },
              )
            }
          }
        }

        if (!useCompactHeader) {
          Spacer(Modifier.height(if (shortHeight) 10.dp else 14.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = onDismiss,
              modifier = Modifier.weight(1f),
              enabled = !saving,
            ) {
              Text(stringResource(R.string.common_cancel))
            }
            Button(
              onClick = { attemptSave() },
              modifier = Modifier.weight(1f),
              enabled = !saving,
            ) {
              if (saving) {
                CircularProgressIndicator(
                  modifier = Modifier.size(18.dp),
                  strokeWidth = 2.dp,
                )
              } else {
                Text(stringResource(R.string.common_save))
              }
            }
          }
        }
      }
    }
  }

  if (pendingConflicts.isNotEmpty()) {
    val conflictPackages = pendingConflicts.keys.toSet()
    val conflictText = pendingConflicts.entries.joinToString("\n") { (pkg, entries) ->
      val whereUsed = entries.joinToString(", ") { entry -> entryLabel(entry) }
      "• $pkg — $whereUsed"
    }
    AlertDialog(
      onDismissRequest = { if (!saving) pendingConflicts = emptyMap() },
      title = { Text(stringResource(R.string.settings_proxyinfo_conflict_title)) },
      text = {
        Text(
          stringResource(R.string.settings_proxyinfo_conflict_body) + "\n\n" + conflictText
        )
      },
      dismissButton = {
        OutlinedButton(
          onClick = {
            selected = selected - conflictPackages
            pendingConflicts = emptyMap()
          },
          enabled = !saving,
        ) {
          Text(stringResource(R.string.settings_proxyinfo_conflict_no))
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val payload = selected.sorted().joinToString("\n")
            onSaveRemovingConflicts(payload) { ok ->
              if (ok) {
                pendingConflicts = emptyMap()
                onDismiss()
              }
            }
          },
          enabled = !saving,
        ) {
          Text(stringResource(R.string.settings_proxyinfo_conflict_yes))
        }
      },
    )
  }
}

@Composable
private fun ProxyInfoAppRow(
  app: InstalledApp,
  selected: Boolean,
  compactWidth: Boolean,
  iconCache: MutableMap<String, androidx.compose.ui.graphics.ImageBitmap?>,
  enabled: Boolean,
  onToggle: (Boolean) -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = if (selected) {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    } else {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)
    },
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled) { onToggle(!selected) }
        .padding(horizontal = 10.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AppIcon(packageName = app.packageName, cache = iconCache)
      Spacer(Modifier.width(10.dp))
      Checkbox(
        checked = selected,
        onCheckedChange = { checked -> onToggle(checked) },
        enabled = enabled,
      )
      Spacer(Modifier.width(6.dp))
      Column(Modifier.weight(1f)) {
        Text(app.label, maxLines = if (compactWidth) 2 else 1, overflow = TextOverflow.Ellipsis)
        Text(
          app.packageName,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
          maxLines = if (compactWidth) 2 else 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (app.isSystem) {
        Spacer(Modifier.width(8.dp))
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
          Text(
            text = stringResource(R.string.app_picker_system_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}
