package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.android.zdtd.service.AppUpdateUiState
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
              text = if (state.urgent) "Срочное обновление" else "Доступно обновление",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            val ver = state.remoteVersionName
            val code = state.remoteVersionCode
            if (ver != null || code != null) {
              Text(
                text = buildString {
                  append("Доступно: ")
                  if (ver != null) append(ver)
                  if (code != null) append(" (code=").append(code).append(")")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
              )
            }
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
          }
        }

        if (state.urgent) {
          Spacer(Modifier.height(6.dp))
          Text(
            text = "Исправление ошибок предыдущего обновления.",
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
            Text("Отмена")
          }
        } else {
          Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
            Text("Обновить")
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
) {
  Column(Modifier.fillMaxWidth().padding(16.dp)) {
    Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text("Проверять обновления", style = MaterialTheme.typography.bodyLarge)
        Text(
          "Проверка выполняется в фоне при открытии приложения (не чаще 1 раза в 12 часов).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }
      Switch(checked = enabled, onCheckedChange = onToggle)
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = onCheckNow, modifier = Modifier.fillMaxWidth()) {
      Text("Проверить сейчас")
    }

    Spacer(Modifier.height(18.dp))

    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text("Уведомления", style = MaterialTheme.typography.bodyLarge)
        Text(
          "Показывать уведомление о состоянии службы (запущена / остановлена).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }
      Switch(checked = daemonNotificationEnabled, onCheckedChange = onToggleDaemonNotification)
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
    title = { Text("Разрешение требуется") },
    text = {
      Text(
        "Для онлайн-обновления приложения требуется разрешить установку обновлений из этого приложения. " +
          "Это нужно только для установки APK после загрузки."
      )
    },
    confirmButton = {
      Button(onClick = onAllow) { Text("Разрешить") }
    },
    dismissButton = {
      OutlinedButton(onClick = onDecline) { Text("Нет") }
    }
  )
}

private fun formatSpeed(bps: Long): String {
  if (bps <= 0) return "0 KB/s"
  val kb = bps.toDouble() / 1024.0
  if (kb < 1024.0) return "${kb.roundToInt()} KB/s"
  val mb = kb / 1024.0
  return String.format("%.1f MB/s", mb)
}
