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
