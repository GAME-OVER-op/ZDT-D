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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
  selinuxPermissiveEnabled: Boolean,
  ipForwardEnabled: Boolean,
  onAdvancedSettingChange: (String, Boolean) -> Unit,
  hotspotT2sEnabled: Boolean,
  hotspotT2sTarget: String,
  hotspotT2sSingboxProfile: String,
  hotspotT2sWireproxyProfile: String,
  hotspotSingboxProfiles: List<com.android.zdtd.service.api.ApiModels.SingBoxProfileChoice>,
  hotspotWireproxyProfiles: List<com.android.zdtd.service.api.ApiModels.SingBoxProfileChoice>,
  onHotspotT2sEnabledChange: (Boolean) -> Unit,
  onHotspotT2sTargetChange: (String) -> Unit,
  onHotspotT2sSingboxProfileChange: (String) -> Unit,
  onHotspotT2sWireproxyProfileChange: (String) -> Unit,
  proxyInfoEnabled: Boolean,
  proxyInfoBusy: Boolean,
  proxyInfoAppsContent: String,
  onProxyInfoEnabledChange: (Boolean) -> Unit,
  onLoadAppAssignments: (((com.android.zdtd.service.api.ApiModels.AppAssignmentsState?) -> Unit) -> Unit),
  onProxyInfoAppsSave: (String, (Boolean) -> Unit) -> Unit,
  onProxyInfoAppsSaveRemovingConflicts: (String, (Boolean) -> Unit) -> Unit,
  blockedQuicEnabled: Boolean,
  blockedQuicBusy: Boolean,
  blockedQuicAppsContent: String,
  onBlockedQuicEnabledChange: (Boolean) -> Unit,
  onBlockedQuicAppsSave: (String, (Boolean) -> Unit) -> Unit,
  resettingModuleIdentifier: Boolean,
  onResetModuleIdentifier: () -> Unit,
  onDeleteModule: () -> Unit,
  landscapeColumns: Boolean = false,
) {
  val compactWidth = rememberIsCompactWidth()
  var showHotspotWarning by remember { mutableStateOf(false) }
  var showResetIdentifierConfirm by remember { mutableStateOf(false) }
  var showProxyInfoConfigure by remember { mutableStateOf(false) }
  var showBlockedQuicConfigure by remember { mutableStateOf(false) }
  var showAdvancedSettings by remember { mutableStateOf(false) }
  if (landscapeColumns) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
    ) {
      Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(12.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          SettingsSwitchSection(
            title = stringResource(R.string.app_update_check_title),
            body = stringResource(R.string.app_update_check_body),
            checked = enabled,
            onCheckedChange = onToggle,
          )
          OutlinedButton(onClick = onCheckNow, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.app_update_check_now))
          }
          SettingsSwitchSection(
            title = stringResource(R.string.settings_notifications_title),
            body = stringResource(R.string.settings_notifications_body),
            checked = daemonNotificationEnabled,
            onCheckedChange = onToggleDaemonNotification,
          )
          ProtectorModeSection(
            selectedMode = protectorMode,
            onModeSelected = onProtectorModeChange,
          )
          OutlinedButton(onClick = { showAdvancedSettings = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_advanced_title))
          }
          HotspotT2sSection(
            enabled = hotspotT2sEnabled,
            target = hotspotT2sTarget,
            singboxProfile = hotspotT2sSingboxProfile,
            wireproxyProfile = hotspotT2sWireproxyProfile,
            singboxProfiles = hotspotSingboxProfiles,
            wireproxyProfiles = hotspotWireproxyProfiles,
            compactWidth = false,
            onEnabledChange = { checked ->
              if (checked && !hotspotT2sEnabled) {
                showHotspotWarning = true
              } else {
                onHotspotT2sEnabledChange(checked)
              }
            },
            onTargetChange = onHotspotT2sTargetChange,
            onSingboxProfileChange = onHotspotT2sSingboxProfileChange,
            onWireproxyProfileChange = onHotspotT2sWireproxyProfileChange,
          )
        }

        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          ProxyInfoSectionCard(
            enabled = proxyInfoEnabled,
            busy = proxyInfoBusy,
            onEnabledChange = onProxyInfoEnabledChange,
            onConfigure = { showProxyInfoConfigure = true },
          )
          BlockedQuicSectionCard(
            enabled = blockedQuicEnabled,
            busy = blockedQuicBusy,
            onEnabledChange = onBlockedQuicEnabledChange,
            onConfigure = { showBlockedQuicConfigure = true },
          )
          SettingsLanguageSection(
            languageMode = languageMode,
            compactWidth = false,
            onLanguageModeChange = onLanguageModeChange,
          )
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
    }

    SettingsDialogsHost(
      showHotspotWarning = showHotspotWarning,
      onDismissHotspotWarning = { showHotspotWarning = false },
      onAcceptHotspotWarning = {
        showHotspotWarning = false
        onHotspotT2sEnabledChange(true)
      },
      showResetIdentifierConfirm = showResetIdentifierConfirm,
      onDismissResetIdentifierConfirm = { showResetIdentifierConfirm = false },
      onConfirmResetIdentifier = {
        showResetIdentifierConfirm = false
        onResetModuleIdentifier()
      },
      resettingModuleIdentifier = resettingModuleIdentifier,
      showProxyInfoConfigure = showProxyInfoConfigure,
      proxyInfoAppsContent = proxyInfoAppsContent,
      proxyInfoBusy = proxyInfoBusy,
      onDismissProxyInfoConfigure = { if (!proxyInfoBusy) showProxyInfoConfigure = false },
      onLoadAppAssignments = onLoadAppAssignments,
      onProxyInfoAppsSave = onProxyInfoAppsSave,
      onProxyInfoAppsSaveRemovingConflicts = onProxyInfoAppsSaveRemovingConflicts,
      showBlockedQuicConfigure = showBlockedQuicConfigure,
      blockedQuicAppsContent = blockedQuicAppsContent,
      blockedQuicBusy = blockedQuicBusy,
      onDismissBlockedQuicConfigure = { if (!blockedQuicBusy) showBlockedQuicConfigure = false },
      onBlockedQuicAppsSave = onBlockedQuicAppsSave,
      showAdvancedSettings = showAdvancedSettings,
      selinuxPermissiveEnabled = selinuxPermissiveEnabled,
      ipForwardEnabled = ipForwardEnabled,
      onDismissAdvancedSettings = { showAdvancedSettings = false },
      onAdvancedSettingChange = onAdvancedSettingChange,
    )
    return
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp)
      .padding(bottom = 22.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SettingsHeaderCard()

    SettingsSwitchSection(
      title = stringResource(R.string.app_update_check_title),
      body = stringResource(R.string.app_update_check_body),
      checked = enabled,
      onCheckedChange = onToggle,
    )

    SettingsActionCard(
      title = stringResource(R.string.app_update_check_now),
      body = stringResource(R.string.app_update_check_body),
      actionText = stringResource(R.string.app_update_check_now),
      showActionChip = false,
      onClick = onCheckNow,
    )

    SettingsSwitchSection(
      title = stringResource(R.string.settings_notifications_title),
      body = stringResource(R.string.settings_notifications_body),
      checked = daemonNotificationEnabled,
      onCheckedChange = onToggleDaemonNotification,
    )

    SettingsSectionCard {
      ProtectorModeSection(
        selectedMode = protectorMode,
        onModeSelected = onProtectorModeChange,
      )
    }

    SettingsActionCard(
      title = stringResource(R.string.settings_advanced_title),
      body = stringResource(R.string.settings_advanced_body),
      actionText = stringResource(R.string.settings_advanced_open),
      onClick = { showAdvancedSettings = true },
    )

    SettingsSectionCard {
      HotspotT2sSection(
        enabled = hotspotT2sEnabled,
        target = hotspotT2sTarget,
        singboxProfile = hotspotT2sSingboxProfile,
        wireproxyProfile = hotspotT2sWireproxyProfile,
        singboxProfiles = hotspotSingboxProfiles,
        wireproxyProfiles = hotspotWireproxyProfiles,
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
        onWireproxyProfileChange = onHotspotT2sWireproxyProfileChange,
      )
    }

    ProxyInfoSectionCard(
      enabled = proxyInfoEnabled,
      busy = proxyInfoBusy,
      onEnabledChange = onProxyInfoEnabledChange,
      onConfigure = { showProxyInfoConfigure = true },
    )

    BlockedQuicSectionCard(
      enabled = blockedQuicEnabled,
      busy = blockedQuicBusy,
      onEnabledChange = onBlockedQuicEnabledChange,
      onConfigure = { showBlockedQuicConfigure = true },
    )

    SettingsLanguageSection(
      languageMode = languageMode,
      compactWidth = compactWidth,
      onLanguageModeChange = onLanguageModeChange,
    )

    SettingsActionCard(
      title = stringResource(R.string.settings_reset_identifier_title),
      body = stringResource(R.string.settings_reset_identifier_body),
      actionText = stringResource(R.string.settings_reset_identifier_action),
      showActionChip = false,
      enabled = !resettingModuleIdentifier,
      onClick = { showResetIdentifierConfirm = true },
    )

    SettingsActionCard(
      title = stringResource(R.string.settings_delete_module_title),
      body = stringResource(R.string.settings_delete_module_body),
      actionText = stringResource(R.string.settings_delete_module_action),
      showActionChip = false,
      danger = true,
      onClick = onDeleteModule,
    )
  }

  SettingsDialogsHost(
    showHotspotWarning = showHotspotWarning,
    onDismissHotspotWarning = { showHotspotWarning = false },
    onAcceptHotspotWarning = {
      showHotspotWarning = false
      onHotspotT2sEnabledChange(true)
    },
    showResetIdentifierConfirm = showResetIdentifierConfirm,
    onDismissResetIdentifierConfirm = { showResetIdentifierConfirm = false },
    onConfirmResetIdentifier = {
      showResetIdentifierConfirm = false
      onResetModuleIdentifier()
    },
    resettingModuleIdentifier = resettingModuleIdentifier,
    showProxyInfoConfigure = showProxyInfoConfigure,
    proxyInfoAppsContent = proxyInfoAppsContent,
    proxyInfoBusy = proxyInfoBusy,
    onDismissProxyInfoConfigure = { if (!proxyInfoBusy) showProxyInfoConfigure = false },
    onLoadAppAssignments = onLoadAppAssignments,
    onProxyInfoAppsSave = onProxyInfoAppsSave,
    onProxyInfoAppsSaveRemovingConflicts = onProxyInfoAppsSaveRemovingConflicts,
    showBlockedQuicConfigure = showBlockedQuicConfigure,
    blockedQuicAppsContent = blockedQuicAppsContent,
    blockedQuicBusy = blockedQuicBusy,
    onDismissBlockedQuicConfigure = { if (!blockedQuicBusy) showBlockedQuicConfigure = false },
    onBlockedQuicAppsSave = onBlockedQuicAppsSave,
    showAdvancedSettings = showAdvancedSettings,
    selinuxPermissiveEnabled = selinuxPermissiveEnabled,
    ipForwardEnabled = ipForwardEnabled,
    onDismissAdvancedSettings = { showAdvancedSettings = false },
    onAdvancedSettingChange = onAdvancedSettingChange,
  )
}



@Composable
private fun SettingsHeaderCard() {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(46.dp)
          .clip(androidx.compose.foundation.shape.CircleShape)
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.AddCircle,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Column(Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.settings_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = stringResource(R.string.settings_subtitle),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
        )
      }
    }
  }
}

@Composable
private fun SettingsSwitchSection(
  title: String,
  body: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  SettingsSectionCard {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
          body,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
        )
      }
      Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
  }
}

@Composable
private fun SettingsActionCard(
  title: String,
  body: String,
  actionText: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
  danger: Boolean = false,
  showActionChip: Boolean = true,
) {
  val shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
  val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
  val clickModifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .then(clickModifier),
    shape = shape,
    color = if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(
          body,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
      }
      if (showActionChip) {
        Surface(
          shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
          color = accent.copy(alpha = if (enabled) 0.18f else 0.08f),
        ) {
          Text(
            text = actionText,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent.copy(alpha = if (enabled) 1f else 0.45f),
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}

@Composable
private fun SettingsLanguageSection(
  languageMode: String,
  compactWidth: Boolean,
  onLanguageModeChange: (String) -> Unit,
) {
  SettingsSectionCard {
    Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
      stringResource(R.string.settings_language_body),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
    )

    val selected = languageMode.lowercase().ifBlank { "auto" }
    val items = listOf(
      "auto" to stringResource(R.string.language_auto),
      "ru" to stringResource(R.string.language_ru),
      "en" to stringResource(R.string.language_en),
    )

    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      if (compactWidth) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(5.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          items.forEach { (value, label) ->
            SettingsSegmentButton(
              text = label,
              selected = selected == value,
              onClick = { onLanguageModeChange(value) },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth().padding(5.dp),
          horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          items.forEach { (value, label) ->
            SettingsSegmentButton(
              text = label,
              selected = selected == value,
              onClick = { onLanguageModeChange(value) },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsSegmentButton(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
  Box(
    modifier = modifier
      .clip(shape)
      .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else Color.Transparent)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
      textAlign = TextAlign.Center,
    )
  }
}


@Composable
private fun SettingsDialogsHost(
  showHotspotWarning: Boolean,
  onDismissHotspotWarning: () -> Unit,
  onAcceptHotspotWarning: () -> Unit,
  showResetIdentifierConfirm: Boolean,
  onDismissResetIdentifierConfirm: () -> Unit,
  onConfirmResetIdentifier: () -> Unit,
  resettingModuleIdentifier: Boolean,
  showProxyInfoConfigure: Boolean,
  proxyInfoAppsContent: String,
  proxyInfoBusy: Boolean,
  onDismissProxyInfoConfigure: () -> Unit,
  onLoadAppAssignments: (((com.android.zdtd.service.api.ApiModels.AppAssignmentsState?) -> Unit) -> Unit),
  onProxyInfoAppsSave: (String, (Boolean) -> Unit) -> Unit,
  onProxyInfoAppsSaveRemovingConflicts: (String, (Boolean) -> Unit) -> Unit,
  showBlockedQuicConfigure: Boolean,
  blockedQuicAppsContent: String,
  blockedQuicBusy: Boolean,
  onDismissBlockedQuicConfigure: () -> Unit,
  onBlockedQuicAppsSave: (String, (Boolean) -> Unit) -> Unit,
  showAdvancedSettings: Boolean,
  selinuxPermissiveEnabled: Boolean,
  ipForwardEnabled: Boolean,
  onDismissAdvancedSettings: () -> Unit,
  onAdvancedSettingChange: (String, Boolean) -> Unit,
) {
  SettingsConfirmDialog(
    visible = showHotspotWarning,
    title = stringResource(R.string.settings_hotspot_warning_title),
    body = stringResource(R.string.settings_hotspot_warning_body),
    confirmText = stringResource(R.string.settings_hotspot_warning_accept),
    dismissText = stringResource(R.string.common_cancel),
    danger = true,
    onDismiss = onDismissHotspotWarning,
    onConfirm = onAcceptHotspotWarning,
  )

  SettingsConfirmDialog(
    visible = showResetIdentifierConfirm,
    title = stringResource(R.string.settings_reset_identifier_title),
    body = stringResource(R.string.settings_reset_identifier_confirm_body),
    confirmText = stringResource(R.string.settings_reset_identifier_confirm_action),
    dismissText = stringResource(R.string.common_cancel),
    onDismiss = onDismissResetIdentifierConfirm,
    onConfirm = onConfirmResetIdentifier,
  )

  ModuleIdentifierResetProgressDialog(visible = resettingModuleIdentifier)

  if (showProxyInfoConfigure) {
    ProxyInfoAppsDialog(
      initialContent = proxyInfoAppsContent,
      saving = proxyInfoBusy,
      onDismiss = onDismissProxyInfoConfigure,
      onLoadAssignments = onLoadAppAssignments,
      onSave = onProxyInfoAppsSave,
      onSaveRemovingConflicts = onProxyInfoAppsSaveRemovingConflicts,
    )
  }

  if (showBlockedQuicConfigure) {
    BlockedQuicAppsDialog(
      initialContent = blockedQuicAppsContent,
      saving = blockedQuicBusy,
      onDismiss = onDismissBlockedQuicConfigure,
      onLoadAssignments = onLoadAppAssignments,
      onSave = onBlockedQuicAppsSave,
    )
  }

  AdvancedSettingsDialog(
    visible = showAdvancedSettings,
    selinuxPermissiveEnabled = selinuxPermissiveEnabled,
    ipForwardEnabled = ipForwardEnabled,
    onDismiss = onDismissAdvancedSettings,
    onAdvancedSettingChange = onAdvancedSettingChange,
  )
}

@Composable
private fun AdvancedSettingsDialog(
  visible: Boolean,
  selinuxPermissiveEnabled: Boolean,
  ipForwardEnabled: Boolean,
  onDismiss: () -> Unit,
  onAdvancedSettingChange: (String, Boolean) -> Unit,
) {
  if (!visible) return
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier = Modifier
              .size(42.dp)
              .clip(androidx.compose.foundation.shape.CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(Icons.Filled.AddCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          }
          Column(Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.settings_advanced_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = stringResource(R.string.settings_advanced_body),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
          }
        }

        AdvancedSwitchRow(
          title = stringResource(R.string.settings_advanced_selinux_title),
          body = stringResource(R.string.settings_advanced_selinux_body),
          checked = selinuxPermissiveEnabled,
          onCheckedChange = { onAdvancedSettingChange("selinux_permissive_enabled", it) },
        )
        AdvancedSwitchRow(
          title = stringResource(R.string.settings_advanced_ip_forward_title),
          body = stringResource(R.string.settings_advanced_ip_forward_body),
          checked = ipForwardEnabled,
          onCheckedChange = { onAdvancedSettingChange("ip_forward_enabled", it) },
        )
      }
    }
  }
}

@Composable
private fun AdvancedSwitchRow(
  title: String,
  body: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
          body,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
        )
      }
      Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
  }
}

@Composable
private fun SettingsConfirmDialog(
  visible: Boolean,
  title: String,
  body: String,
  confirmText: String,
  dismissText: String,
  danger: Boolean = false,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  if (!visible) return
  val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier = Modifier
              .size(42.dp)
              .clip(androidx.compose.foundation.shape.CircleShape)
              .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(Icons.Filled.AddCircle, contentDescription = null, tint = accent)
          }
          Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
            Text(dismissText)
          }
          Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
            Text(confirmText)
          }
        }
      }
    }
  }
}


@Composable
private fun SettingsSectionCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
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
  wireproxyProfile: String,
  singboxProfiles: List<com.android.zdtd.service.api.ApiModels.SingBoxProfileChoice>,
  wireproxyProfiles: List<com.android.zdtd.service.api.ApiModels.SingBoxProfileChoice>,
  compactWidth: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onTargetChange: (String) -> Unit,
  onSingboxProfileChange: (String) -> Unit,
  onWireproxyProfileChange: (String) -> Unit,
) {
  val safeTarget = when (target.trim().lowercase()) {
    "operaproxy" -> "operaproxy"
    "singbox" -> "singbox"
    "wireproxy" -> "wireproxy"
    else -> ""
  }
  val enabledProfiles = remember(singboxProfiles) { singboxProfiles.filter { it.enabled } }
  val enabledWireproxyProfiles = remember(wireproxyProfiles) { wireproxyProfiles.filter { it.enabled } }

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
        FilterChip(
          selected = safeTarget == "wireproxy",
          onClick = {
            if (safeTarget != "wireproxy") onTargetChange("wireproxy")
          },
          label = {
            Text(
              stringResource(R.string.settings_hotspot_program_wireproxy),
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
        visible = safeTarget == "singbox" || safeTarget == "wireproxy",
        enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(animationSpec = tween(140)),
      ) {
        val useWireproxy = safeTarget == "wireproxy"
        val profiles = if (useWireproxy) enabledWireproxyProfiles else enabledProfiles
        val selectedProfile = if (useWireproxy) wireproxyProfile else singboxProfile
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
                stringResource(if (useWireproxy) R.string.settings_hotspot_wireproxy_hint_title else R.string.settings_hotspot_singbox_hint_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                stringResource(if (useWireproxy) R.string.settings_hotspot_wireproxy_hint_body else R.string.settings_hotspot_singbox_hint_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
              )

              if (profiles.isEmpty()) {
                Text(
                  stringResource(if (useWireproxy) R.string.settings_hotspot_wireproxy_profiles_empty else R.string.settings_hotspot_singbox_profiles_empty),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
              } else {
                Text(
                  stringResource(if (useWireproxy) R.string.settings_hotspot_wireproxy_profiles_title else R.string.settings_hotspot_singbox_profiles_title),
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  profiles.forEach { profile ->
                    val selected = profile.name == selectedProfile
                    if (selected) {
                      Button(
                        onClick = {
                          if (useWireproxy) onWireproxyProfileChange(profile.name) else onSingboxProfileChange(profile.name)
                        },
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Text(profile.name)
                      }
                    } else {
                      OutlinedButton(
                        onClick = {
                          if (useWireproxy) onWireproxyProfileChange(profile.name) else onSingboxProfileChange(profile.name)
                        },
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
          if (selectedProfile.isBlank()) {
            Text(
              stringResource(if (useWireproxy) R.string.settings_hotspot_wireproxy_profile_missing else R.string.settings_hotspot_singbox_profile_missing),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Medium,
            )
          } else {
            Text(
              stringResource(
                if (useWireproxy) R.string.settings_hotspot_wireproxy_profile_selected_fmt else R.string.settings_hotspot_singbox_profile_selected_fmt,
                selectedProfile,
              ),
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
      modifier = Modifier.fillMaxWidth(),
      shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(
          modifier = Modifier
            .size(56.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(30.dp))
        }
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
