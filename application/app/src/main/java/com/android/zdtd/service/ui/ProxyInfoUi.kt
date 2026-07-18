package com.android.zdtd.service.ui

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Info
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ProxyInfoSectionCard(
  enabled: Boolean,
  busy: Boolean,
  hidingStatus: ApiModels.HidingStatus,
  onEnabledChange: (Boolean) -> Unit,
  onConfigure: () -> Unit,
) {
  var showWarning by remember { mutableStateOf(false) }
  var infoLayer by remember { mutableStateOf<String?>(null) }
  val compactWidth = rememberIsCompactWidth()
  val shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
  val accent = MaterialTheme.colorScheme.primary
  val activeStatusText = stringResource(R.string.hiding_layer_status_active)
  val installedStatusText = stringResource(R.string.hiding_layer_status_installed)
  val requestedStatusText = stringResource(R.string.hiding_layer_status_requested)
  val enabledStatusText = stringResource(R.string.hiding_layer_status_enabled)
  val staleStatusText = stringResource(R.string.hiding_layer_status_stale)
  val inactiveStatusText = stringResource(R.string.hiding_layer_status_inactive)

  fun statusText(layer: ApiModels.HidingLayerStatus, fallbackActive: Boolean = false): String {
    return when {
      layer.active || fallbackActive -> activeStatusText
      layer.installed -> installedStatusText
      layer.requested -> requestedStatusText
      layer.enabled -> enabledStatusText
      layer.status == "stale" -> staleStatusText
      else -> inactiveStatusText
    }
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 13.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = accent.copy(alpha = if (enabled) 0.22f else 0.12f),
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.Check,
              contentDescription = null,
              tint = accent.copy(alpha = if (enabled) 1f else 0.62f),
            )
          }
        }

        Column(Modifier.weight(1f)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              stringResource(R.string.settings_proxyinfo_title),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.weight(1f),
            )
            Surface(
              shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
              color = if (enabled) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            ) {
              Text(
                text = if (enabled) stringResource(R.string.enabled_state_on) else stringResource(R.string.enabled_state_off),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
              )
            }
          }
          Spacer(Modifier.height(3.dp))
          Text(
            stringResource(R.string.settings_proxyinfo_body, hidingStatus.selectedApps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
          )
        }

        if (!compactWidth) {
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

      if (compactWidth) {
        Switch(
          checked = enabled,
          onCheckedChange = { checked ->
            if (busy) return@Switch
            if (checked && !enabled) showWarning = true else onEnabledChange(checked)
          },
          enabled = !busy,
        )
      }

      HidingLayerRow(
        title = stringResource(R.string.hiding_layer_zygisk_title),
        subtitle = stringResource(R.string.hiding_layer_zygisk_short),
        status = statusText(hidingStatus.zygisk),
        active = hidingStatus.zygisk.installed,
        onInfo = { infoLayer = "zygisk" },
      )
      val lsposedConfigured = enabled && hidingStatus.selectedApps > 0
      HidingLayerRow(
        title = stringResource(R.string.hiding_layer_lsposed_title),
        subtitle = stringResource(R.string.hiding_layer_lsposed_short),
        status = statusText(
          hidingStatus.lsposed,
          fallbackActive = hidingStatus.lsposed.active || hidingStatus.lsposed.enabled || lsposedConfigured,
        ),
        active = hidingStatus.lsposed.active || hidingStatus.lsposed.enabled || lsposedConfigured,
        onInfo = { infoLayer = "lsposed" },
      )
      HidingLayerRow(
        title = stringResource(R.string.hiding_layer_proxyinfo_title),
        subtitle = stringResource(R.string.hiding_layer_proxyinfo_short),
        status = statusText(hidingStatus.proxyInfo, fallbackActive = hidingStatus.proxyInfo.active),
        active = hidingStatus.proxyInfo.active,
        onInfo = { infoLayer = "proxyinfo" },
      )

      AnimatedVisibility(visible = enabled) {
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onConfigure),
          shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
          color = accent.copy(alpha = 0.12f),
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
          ) {
            Text(
              stringResource(R.string.settings_proxyinfo_configure),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = accent.copy(alpha = if (busy) 0.5f else 1f),
            )
            if (busy) {
              Spacer(Modifier.width(8.dp))
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
          }
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

  infoLayer?.let { layer ->
    val title = when (layer) {
      "zygisk" -> R.string.hiding_layer_zygisk_title
      "lsposed" -> R.string.hiding_layer_lsposed_title
      else -> R.string.hiding_layer_proxyinfo_title
    }
    val body = when (layer) {
      "zygisk" -> R.string.hiding_layer_zygisk_desc
      "lsposed" -> R.string.hiding_layer_lsposed_desc
      else -> R.string.hiding_layer_proxyinfo_desc
    }
    AlertDialog(
      onDismissRequest = { infoLayer = null },
      title = { Text(stringResource(title)) },
      text = { Text(stringResource(body)) },
      confirmButton = {
        Button(onClick = { infoLayer = null }) { Text(stringResource(R.string.common_ok)) }
      },
    )
  }
}

@Composable
private fun HidingLayerRow(
  title: String,
  subtitle: String,
  status: String,
  active: Boolean,
  onInfo: () -> Unit,
) {
  val accent = MaterialTheme.colorScheme.primary
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        Modifier.size(9.dp).background(if (active) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f), CircleShape)
      )
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
      Text(status, style = MaterialTheme.typography.labelSmall, color = if (active) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
      IconButton(onClick = onInfo, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
      }
    }
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
  val initialSelected = remember(initialContent) { parsePkgList(initialContent) - ZDTD_APP_PACKAGE_NAME }
  var selected by remember(initialSelected) { mutableStateOf(initialSelected) }
  val hasChanges = selected != initialSelected
  var assignments by remember { mutableStateOf<ApiModels.AppAssignmentsState?>(null) }
  var pendingConflicts by remember { mutableStateOf<Map<String, List<ApiModels.AppAssignmentEntry>>>(emptyMap()) }
  val iconCache = remember { AppIconMemoryCache.map }
  val apps by produceState<List<InstalledApp>>(initialValue = emptyList(), key1 = Unit) {
    value = withContext(Dispatchers.IO) {
      runCatching { loadInstalledAppsCached(pm) }.getOrDefault(emptyList())
    }
  }
  LaunchedEffect(Unit) {
    onLoadAssignments { assignments = it ?: ApiModels.AppAssignmentsState() }
  }

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

  val availableApps = remember(apps) { apps.filter { it.packageName != ZDTD_APP_PACKAGE_NAME } }
  val filtered = remember(availableApps, debouncedQuery) {
    if (debouncedQuery.isBlank()) availableApps else availableApps.filter { matchesSearch(it, debouncedQuery) }
  }
  val appsByPackage = remember(availableApps) { availableApps.associateBy { it.packageName } }
  val selectedAppsAll = remember(appsByPackage, selected) {
    selected.map { pkg -> appsByPackage[pkg] ?: InstalledApp(pkg, pkg, false) }
      .sortedBy { it.sortKey }
  }
  val selectedApps = remember(selectedAppsAll, debouncedQuery) {
    if (debouncedQuery.isBlank()) selectedAppsAll else selectedAppsAll.filter { matchesSearch(it, debouncedQuery) }
  }
  val notSelectedApps = remember(filtered, selected) { filtered.filter { it.packageName !in selected } }
  val showSelectedSection = debouncedQuery.isBlank() || selectedApps.isNotEmpty()
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
  val programHysteria2Label = "hysteria2"
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

  fun slotLabel(slot: String): String = when (slot.lowercase(Locale.ROOT)) {
    "common" -> slotCommonLabel
    "wifi" -> slotWifiLabel
    "mobile" -> slotMobileLabel
    else -> slot
  }

  fun programLabel(programId: String): String = when (programId) {
    "operaproxy" -> programOperaLabel
    "sing-box" -> programSingboxLabel
    "hysteria2" -> programHysteria2Label
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
    if (!hasChanges) return
    val payload = (selected - ZDTD_APP_PACKAGE_NAME).sorted().joinToString("\n")
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
                color = if (saving || !hasChanges) {
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                } else {
                  MaterialTheme.colorScheme.primary
                },
              ) {
                IconButton(onClick = { attemptSave() }, enabled = !saving && hasChanges, modifier = Modifier.size(40.dp)) {
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
            if (showSelectedSection) {
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
                items(selectedApps, key = { "sel:" + it.packageName }, contentType = { "proxyinfo_selected_app" }) { app ->
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
            }

            item {
              if (showSelectedSection) {
                Spacer(Modifier.height(if (shortHeight) 6.dp else 8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                Spacer(Modifier.height(if (shortHeight) 6.dp else 8.dp))
              }
              Text(
                text = stringResource(R.string.settings_proxyinfo_all_apps_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
              )
            }

            if (notSelectedApps.isEmpty()) {
              item {
                Text(
                  text = stringResource(if (debouncedQuery.isBlank()) R.string.app_picker_none else R.string.app_picker_no_matches),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
              }
            } else {
              items(notSelectedApps, key = { "all:" + it.packageName }, contentType = { "proxyinfo_available_app" }) { app ->
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
              enabled = !saving && hasChanges,
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
            val payload = (selected - ZDTD_APP_PACKAGE_NAME).sorted().joinToString("\n")
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
