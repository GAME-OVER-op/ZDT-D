package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Close
import com.android.zdtd.service.AppUpdateUiState
import com.android.zdtd.service.R
import kotlin.math.roundToInt

@Composable
fun AppUpdateBanner(
  state: AppUpdateUiState,
  onDismiss: () -> Unit,
  onUpdate: () -> Unit,
) {
  val compactWidth = rememberIsCompactWidth()
  AnimatedVisibility(
    visible = state.bannerVisible,
    enter = slideInVertically(
      initialOffsetY = { -it },
      animationSpec = tween(220),
    ) + expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
    exit = slideOutVertically(
      targetOffsetY = { -it },
      animationSpec = tween(180),
    ) + shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(120)),
  ) {
    ElevatedCard(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(Modifier.weight(1f)) {
            Text(
              text = if (state.urgent) stringResource(R.string.app_update_urgent_title) else stringResource(R.string.app_update_available_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            val ver = state.remoteVersionName
            val code = state.remoteVersionCode
            if (ver != null || code != null) {
              Text(
                text = buildString {
                  append(stringResource(R.string.app_update_available_version_prefix))
                  if (ver != null) append(ver)
                  if (code != null) append(stringResource(R.string.app_update_available_version_code_fmt, code))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = if (compactWidth) 2 else 1,
              )
            }
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
          }
        }

        if (state.urgent) {
          Spacer(Modifier.height(6.dp))
          Text(
            text = stringResource(R.string.app_update_urgent_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }

        if (state.errorText != null) {
          Spacer(Modifier.height(8.dp))
          Text(
            text = state.errorText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        Spacer(Modifier.height(10.dp))

        if (state.downloading) {
          LinearProgressIndicator(
            progress = (state.downloadPercent.coerceIn(0, 100) / 100f),
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(Modifier.height(8.dp))
          if (compactWidth) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(text = "${state.downloadPercent.coerceIn(0,100)}%", style = MaterialTheme.typography.bodySmall)
              Text(text = formatSpeed(state.downloadSpeedBytesPerSec), style = MaterialTheme.typography.bodySmall)
            }
          } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(text = "${state.downloadPercent.coerceIn(0,100)}%", style = MaterialTheme.typography.bodySmall)
              Text(text = formatSpeed(state.downloadSpeedBytesPerSec), style = MaterialTheme.typography.bodySmall)
            }
          }
          Spacer(Modifier.height(8.dp))
          OutlinedButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_cancel))
          }
        } else {
          Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_update))
          }
        }
      }
    }
  }
}

@Composable
fun AppUpdateSettings(
  enabled: Boolean,
  onToggle: (Boolean) -> Unit,
  onCheckNow: () -> Unit,
  daemonNotificationEnabled: Boolean,
  onToggleDaemonNotification: (Boolean) -> Unit,
  languageMode: String,
  onLanguageModeChange: (String) -> Unit,
  protectorMode: String,
  onProtectorModeChange: (String) -> Unit,
  hotspotT2sEnabled: Boolean,
  hotspotT2sTarget: String,
  hotspotT2sSingboxProfile: String,
  hotspotSingboxProfiles: List<com.android.zdtd.service.api.ApiModels.SingBoxProfileChoice>,
  onHotspotT2sEnabledChange: (Boolean) -> Unit,
  onHotspotT2sTargetChange: (String) -> Unit,
  onHotspotT2sSingboxProfileChange: (String) -> Unit,
  proxyInfoEnabled: Boolean,
  proxyInfoBusy: Boolean,
  proxyInfoAppsContent: String,
  onProxyInfoEnabledChange: (Boolean) -> Unit,
  onLoadAppAssignments: (((com.android.zdtd.service.api.ApiModels.AppAssignmentsState?) -> Unit) -> Unit),
  onProxyInfoAppsSave: (String, (Boolean) -> Unit) -> Unit,
  onProxyInfoAppsSaveRemovingConflicts: (String, (Boolean) -> Unit) -> Unit,
  resettingModuleIdentifier: Boolean,
  onResetModuleIdentifier: () -> Unit,
  onDeleteModule: () -> Unit,
) {
  val compactWidth = rememberIsCompactWidth()
  var showHotspotWarning by remember { mutableStateOf(false) }
  var showResetIdentifierConfirm by remember { mutableStateOf(false) }
  var showProxyInfoConfigure by remember { mutableStateOf(false) }
  // BottomSheet content may not have enough height on small screens.
  // Make it scrollable so the Language section is always reachable.
  Column(
    Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(16.dp)
  ) {
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    if (compactWidth) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.app_update_check_title), style = MaterialTheme.typography.bodyLarge)
          Text(
            stringResource(R.string.app_update_check_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
      }
    } else {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
          Text(stringResource(R.string.app_update_check_title), style = MaterialTheme.typography.bodyLarge)
          Text(
            stringResource(R.string.app_update_check_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
      }
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onCheckNow, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.app_update_check_now))
    }

    Spacer(Modifier.height(18.dp))

    if (compactWidth) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.settings_notifications_title), style = MaterialTheme.typography.bodyLarge)
          Text(
            stringResource(R.string.settings_notifications_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Switch(checked = daemonNotificationEnabled, onCheckedChange = onToggleDaemonNotification)
      }
    } else {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
          Text(stringResource(R.string.settings_notifications_title), style = MaterialTheme.typography.bodyLarge)
          Text(
            stringResource(R.string.settings_notifications_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Switch(checked = daemonNotificationEnabled, onCheckedChange = onToggleDaemonNotification)
      }
    }

    Spacer(Modifier.height(18.dp))

    ProtectorModeSection(
      selectedMode = protectorMode,
      onModeSelected = onProtectorModeChange,
    )

    Spacer(Modifier.height(18.dp))

    HotspotT2sSection(
      enabled = hotspotT2sEnabled,
      target = hotspotT2sTarget,
      singboxProfile = hotspotT2sSingboxProfile,
      singboxProfiles = hotspotSingboxProfiles,
      compactWidth = compactWidth,
      onEnabledChange = { checked ->
        if (checked && !hotspotT2sEnabled) {
          showHotspotWarning = true
        } else {
          onHotspotT2sEnabledChange(checked)
        }
      },
      onTargetChange = onHotspotT2sTargetChange,
      onSingboxProfileChange = onHotspotT2sSingboxProfileChange,
    )

    if (showHotspotWarning) {
      AlertDialog(
        onDismissRequest = { showHotspotWarning = false },
        title = { Text(stringResource(R.string.settings_hotspot_warning_title)) },
        text = { Text(stringResource(R.string.settings_hotspot_warning_body)) },
        dismissButton = {
          OutlinedButton(onClick = { showHotspotWarning = false }) {
            Text(stringResource(R.string.common_cancel))
          }
        },
        confirmButton = {
          Button(onClick = {
            showHotspotWarning = false
            onHotspotT2sEnabledChange(true)
          }) {
            Text(stringResource(R.string.settings_hotspot_warning_accept))
          }
        },
      )
    }

    Spacer(Modifier.height(18.dp))

    ProxyInfoSectionCard(
      enabled = proxyInfoEnabled,
      busy = proxyInfoBusy,
      onEnabledChange = onProxyInfoEnabledChange,
      onConfigure = { showProxyInfoConfigure = true },
    )

    if (showProxyInfoConfigure) {
      ProxyInfoAppsDialog(
        initialContent = proxyInfoAppsContent,
        saving = proxyInfoBusy,
        onDismiss = { if (!proxyInfoBusy) showProxyInfoConfigure = false },
        onLoadAssignments = onLoadAppAssignments,
        onSave = onProxyInfoAppsSave,
        onSaveRemovingConflicts = onProxyInfoAppsSaveRemovingConflicts,
      )
    }

    Spacer(Modifier.height(18.dp))

    Column(Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.bodyLarge)
      Text(
        stringResource(R.string.settings_language_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      )

      Spacer(Modifier.height(10.dp))

      val selected = languageMode.lowercase().ifBlank { "auto" }
      val isAuto = selected == "auto"
      val isRu = selected == "ru"
      val isEn = selected == "en"

      if (compactWidth) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          if (isAuto) {
            Button(onClick = { onLanguageModeChange("auto") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.language_auto)) }
          } else {
            OutlinedButton(onClick = { onLanguageModeChange("auto") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.language_auto)) }
          }
          if (isRu) {
            Button(onClick = { onLanguageModeChange("ru") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.language_ru)) }
          } else {
            OutlinedButton(onClick = { onLanguageModeChange("ru") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.language_ru)) }
          }
          if (isEn) {
            Button(onClick = { onLanguageModeChange("en") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.language_en)) }
          } else {
            OutlinedButton(onClick = { onLanguageModeChange("en") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.language_en)) }
          }
        }
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          if (isAuto) {
            Button(onClick = { onLanguageModeChange("auto") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.language_auto)) }
          } else {
            OutlinedButton(onClick = { onLanguageModeChange("auto") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.language_auto)) }
          }

          if (isRu) {
            Button(onClick = { onLanguageModeChange("ru") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.language_ru)) }
          } else {
            OutlinedButton(onClick = { onLanguageModeChange("ru") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.language_ru)) }
          }

          if (isEn) {
            Button(onClick = { onLanguageModeChange("en") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.language_en)) }
          } else {
            OutlinedButton(onClick = { onLanguageModeChange("en") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.language_en)) }
          }
        }
      }
    }


    Spacer(Modifier.height(18.dp))

    Column(Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.settings_reset_identifier_title), style = MaterialTheme.typography.bodyLarge)
      Text(
        stringResource(R.string.settings_reset_identifier_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      )
      Spacer(Modifier.height(10.dp))
      OutlinedButton(
        onClick = { showResetIdentifierConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        enabled = !resettingModuleIdentifier,
      ) {
        Text(stringResource(R.string.settings_reset_identifier_action))
      }
    }

    if (showResetIdentifierConfirm) {
      AlertDialog(
        onDismissRequest = { showResetIdentifierConfirm = false },
        title = { Text(stringResource(R.string.settings_reset_identifier_title)) },
        text = { Text(stringResource(R.string.settings_reset_identifier_confirm_body)) },
        dismissButton = {
          OutlinedButton(onClick = { showResetIdentifierConfirm = false }) {
            Text(stringResource(R.string.common_cancel))
          }
        },
        confirmButton = {
          Button(onClick = {
            showResetIdentifierConfirm = false
            onResetModuleIdentifier()
          }) {
            Text(stringResource(R.string.settings_reset_identifier_confirm_action))
          }
        },
      )
    }

    ModuleIdentifierResetProgressDialog(visible = resettingModuleIdentifier)


    Spacer(Modifier.height(18.dp))

    Column(Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.settings_delete_module_title), style = MaterialTheme.typography.bodyLarge)
      Text(
        stringResource(R.string.settings_delete_module_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      )
      Spacer(Modifier.height(10.dp))
      OutlinedButton(onClick = onDeleteModule, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_delete_module_action))
      }
    }

  }
}


@Composable
private fun SettingsSectionCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  ElevatedCard(
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
    )
  }
}

@Composable
private fun HotspotT2sSection(
  enabled: Boolean,
  target: String,
  singboxProfile: String,
  singboxProfiles: List<com.android.zdtd.service.api.ApiModels.SingBoxProfileChoice>,
  compactWidth: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onTargetChange: (String) -> Unit,
  onSingboxProfileChange: (String) -> Unit,
) {
  val safeTarget = when (target.trim().lowercase()) {
    "operaproxy" -> "operaproxy"
    "singbox" -> "singbox"
    else -> ""
  }
  val enabledProfiles = remember(singboxProfiles) { singboxProfiles.filter { it.enabled } }

  if (compactWidth) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_hotspot_title), style = MaterialTheme.typography.bodyLarge)
        Text(
          stringResource(R.string.settings_hotspot_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }
      Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
  } else {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text(stringResource(R.string.settings_hotspot_title), style = MaterialTheme.typography.bodyLarge)
        Text(
          stringResource(R.string.settings_hotspot_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }
      Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
  }

  AnimatedVisibility(visible = enabled) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
      Text(stringResource(R.string.settings_hotspot_program_title), style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(10.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(
          selected = safeTarget == "operaproxy",
          onClick = {
            if (safeTarget != "operaproxy") onTargetChange("operaproxy")
          },
          label = {
            Text(
              stringResource(R.string.settings_hotspot_program_operaproxy),
              modifier = Modifier.fillMaxWidth(),
              textAlign = TextAlign.Center,
            )
          },
          modifier = Modifier.weight(1f).heightIn(min = 46.dp),
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
          ),
        )
        FilterChip(
          selected = safeTarget == "singbox",
          onClick = {
            if (safeTarget != "singbox") onTargetChange("singbox")
          },
          label = {
            Text(
              stringResource(R.string.settings_hotspot_program_singbox),
              modifier = Modifier.fillMaxWidth(),
              textAlign = TextAlign.Center,
            )
          },
          modifier = Modifier.weight(1f).heightIn(min = 46.dp),
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
          ),
        )
      }

      AnimatedVisibility(
        visible = safeTarget == "singbox",
        enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(animationSpec = tween(140)),
      ) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Text(
                stringResource(R.string.settings_hotspot_singbox_hint_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                stringResource(R.string.settings_hotspot_singbox_hint_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
              )

              if (enabledProfiles.isEmpty()) {
                Text(
                  stringResource(R.string.settings_hotspot_singbox_profiles_empty),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
              } else {
                Text(
                  stringResource(R.string.settings_hotspot_singbox_profiles_title),
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  enabledProfiles.forEach { profile ->
                    val selected = profile.name == singboxProfile
                    if (selected) {
                      Button(
                        onClick = { onSingboxProfileChange(profile.name) },
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Text(profile.name)
                      }
                    } else {
                      OutlinedButton(
                        onClick = { onSingboxProfileChange(profile.name) },
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Text(profile.name)
                      }
                    }
                  }
                }
              }
            }
          }

          Spacer(Modifier.height(10.dp))
          if (singboxProfile.isBlank()) {
            Text(
              stringResource(R.string.settings_hotspot_singbox_profile_missing),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Medium,
            )
          } else {
            Text(
              stringResource(R.string.settings_hotspot_singbox_profile_selected_fmt, singboxProfile),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
          }
        }
      }
    }
  }
}

private data class ProtectorModeOption(
  val value: String,
  val titleRes: Int,
  val descRes: Int,
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
  val color: Color,
)

@Composable
private fun ProtectorModeSection(
  selectedMode: String,
  onModeSelected: (String) -> Unit,
) {
  val safeMode = selectedMode.lowercase().ifBlank { "off" }.let { mode ->
    when (mode) {
      "on", "off", "auto" -> mode
      else -> "off"
    }
  }

  val options = listOf(
    ProtectorModeOption(
      value = "on",
      titleRes = R.string.settings_protector_on,
      descRes = R.string.settings_protector_on_desc,
      icon = Icons.Filled.BatteryChargingFull,
      color = Color(0xFFD84B4B),
    ),
    ProtectorModeOption(
      value = "off",
      titleRes = R.string.settings_protector_off,
      descRes = R.string.settings_protector_off_desc,
      icon = Icons.Filled.BatteryFull,
      color = Color(0xFF34A853),
    ),
    ProtectorModeOption(
      value = "auto",
      titleRes = R.string.settings_protector_auto,
      descRes = R.string.settings_protector_auto_desc,
      icon = Icons.Filled.AddCircle,
      color = Color(0xFFF4B400),
    ),
  )

  val active = options.firstOrNull { it.value == safeMode } ?: options[1]

  Column(Modifier.fillMaxWidth()) {
    Text(stringResource(R.string.settings_protector_title), style = MaterialTheme.typography.bodyLarge)
    Text(
      stringResource(R.string.settings_protector_body),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
    )

    Spacer(Modifier.height(10.dp))

    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        options.forEach { option ->
          ProtectorModeChip(
            option = option,
            selected = option.value == active.value,
            onClick = { onModeSelected(option.value) },
            modifier = Modifier.weight(1f),
          )
        }
      }
    }

    Spacer(Modifier.height(10.dp))

    Text(
      text = stringResource(active.descRes),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
    )
  }
}

@Composable
private fun ProtectorModeChip(
  option: ProtectorModeOption,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val bg = if (selected) option.color.copy(alpha = 0.18f) else Color.Transparent
  val iconBg = if (selected) option.color else MaterialTheme.colorScheme.surface
  val iconTint = if (selected) Color.White else option.color

  Column(
    modifier = modifier
      .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
      .background(bg)
      .clickable(onClick = onClick)
      .padding(vertical = 10.dp, horizontal = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(androidx.compose.foundation.shape.CircleShape)
        .background(iconBg),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = option.icon,
        contentDescription = stringResource(option.titleRes),
        tint = iconTint,
      )
    }
    Text(
      text = stringResource(option.titleRes),
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun ModuleIdentifierResetProgressDialog(visible: Boolean) {
  if (!visible) return

  Dialog(
    onDismissRequest = {},
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
  ) {
    Surface(
      shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
      tonalElevation = 6.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        CircularProgressIndicator()
        Text(
          text = stringResource(R.string.settings_reset_identifier_wait_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center,
        )
        Text(
          text = stringResource(R.string.settings_reset_identifier_wait_body),
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )
      }
    }
  }
}


@Composable
fun UnknownSourcesPermissionDialog(
  visible: Boolean,
  onAllow: () -> Unit,
  onDecline: () -> Unit,
) {
  if (!visible) return
  AlertDialog(
    onDismissRequest = onDecline,
    title = { Text(stringResource(R.string.permission_required_title)) },
    text = {
      Text(
        stringResource(R.string.permission_required_body)
      )
    },
    confirmButton = {
      Button(onClick = onAllow) { Text(stringResource(R.string.common_allow)) }
    },
    dismissButton = {
      OutlinedButton(onClick = onDecline) { Text(stringResource(R.string.common_no)) }
    }
  )
}

@Composable
private fun formatSpeed(bps: Long): String {
  if (bps <= 0) return stringResource(R.string.app_speed_zero)
  val kb = bps.toDouble() / 1024.0
  if (kb < 1024.0) return stringResource(R.string.app_speed_kbps_fmt, kb.roundToInt())
  val mb = kb / 1024.0
  return stringResource(R.string.app_speed_mbps_fmt, mb)
}
