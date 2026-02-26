package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
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
          verticalAlignment = Alignment.CenterVertically,
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
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "${state.downloadPercent.coerceIn(0,100)}%", style = MaterialTheme.typography.bodySmall)
            Text(text = formatSpeed(state.downloadSpeedBytesPerSec), style = MaterialTheme.typography.bodySmall)
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
  onDeleteModule: () -> Unit,
) {
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
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onCheckNow, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.app_update_check_now))
    }

    Spacer(Modifier.height(18.dp))

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
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val isAuto = selected == "auto"
        val isRu = selected == "ru"
        val isEn = selected == "en"

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
