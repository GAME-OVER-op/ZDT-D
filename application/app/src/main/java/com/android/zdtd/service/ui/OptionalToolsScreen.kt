package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.tgwsproxy.TgWsProxyComponentStage
import com.android.zdtd.service.tgwsproxy.TgWsProxyComponentState

@Composable
fun OptionalToolsScreen(
  state: TgWsProxyComponentState,
  actions: ZdtdActions,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  LaunchedEffect(Unit) {
    actions.refreshOptionalTools()
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = topContentPadding + 8.dp, bottom = bottomContentPadding + 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      OptionalToolsHeader()
    }
    item {
      RemoteSetupUtilityCard(
        onOpen = actions::openRemoteSetup,
      )
    }
    item {
      TgWsProxyUtilityCard(
        state = state,
        onInstall = actions::installTgWsProxy,
        onRemove = actions::removeTgWsProxy,
      )
    }
    item { Spacer(Modifier.height(4.dp)) }
  }
}

@Composable
private fun OptionalToolsHeader() {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = stringResource(R.string.optional_tools_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.optional_tools_screen_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
      )
    }
  }
}

@Composable
private fun RemoteSetupUtilityCard(
  onOpen: () -> Unit,
) {
  val accentColor = MaterialTheme.colorScheme.secondary
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.40f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Brush.horizontalGradient(listOf(accentColor.copy(alpha = 0.15f), MaterialTheme.colorScheme.surface.copy(alpha = 0.68f))))
        .padding(horizontal = 14.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          modifier = Modifier.size(58.dp),
          color = accentColor.copy(alpha = 0.14f),
          contentColor = accentColor,
          shape = CircleShape,
          border = BorderStroke(1.dp, accentColor.copy(alpha = 0.36f)),
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SettingsRemote, contentDescription = null, modifier = Modifier.size(29.dp))
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Удалённая настройка",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "Запустить управление на root-устройстве, подключиться по QR/IP-коду или открыть историю устройств ZDT-D в сети.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          )
        }
      }
      Button(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Outlined.CastConnected, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Открыть удалённую настройку")
      }
    }
  }
}

@Composable
private fun TgWsProxyUtilityCard(
  state: TgWsProxyComponentState,
  onInstall: () -> Unit,
  onRemove: () -> Unit,
) {
  val accentColor = if (state.installed) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.40f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Brush.horizontalGradient(listOf(accentColor.copy(alpha = 0.15f), MaterialTheme.colorScheme.surface.copy(alpha = 0.68f))))
        .padding(horizontal = 14.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          modifier = Modifier.size(58.dp),
          color = accentColor.copy(alpha = 0.14f),
          contentColor = accentColor,
          shape = CircleShape,
          border = BorderStroke(1.dp, accentColor.copy(alpha = 0.36f)),
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(29.dp))
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = stringResource(R.string.tg_ws_proxy_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = stringResource(R.string.tg_ws_proxy_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          )
        }
      }

      OptionalToolStatusPill(state = state, accentColor = accentColor)

      if (state.isWorking) {
        OptionalToolProgress(state = state)
      }

      state.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
        Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (state.installed) {
          Button(
            onClick = onInstall,
            enabled = !state.isWorking,
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.optional_tools_download_again))
          }
          OutlinedButton(
            onClick = onRemove,
            enabled = !state.isWorking,
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_delete))
          }
        } else {
          Button(
            onClick = onInstall,
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.optional_tools_download))
          }
        }
      }
    }
  }
}

@Composable
private fun OptionalToolStatusPill(state: TgWsProxyComponentState, accentColor: Color) {
  val text = when {
    state.installed -> stringResource(R.string.optional_tools_status_installed)
    state.stage == TgWsProxyComponentStage.DOWNLOADING -> stringResource(R.string.optional_tools_status_downloading)
    state.stage == TgWsProxyComponentStage.UNPACKING -> stringResource(R.string.optional_tools_status_unpacking)
    state.stage == TgWsProxyComponentStage.VERIFYING -> stringResource(R.string.optional_tools_status_verifying)
    state.stage == TgWsProxyComponentStage.INSTALLING -> stringResource(R.string.optional_tools_status_installing)
    state.stage == TgWsProxyComponentStage.FAILED -> stringResource(R.string.optional_tools_status_failed)
    else -> stringResource(R.string.optional_tools_status_not_installed)
  }
  Surface(
    color = accentColor.copy(alpha = 0.14f),
    contentColor = accentColor,
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
    shape = RoundedCornerShape(100.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      if (state.isWorking) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = accentColor)
      } else {
        Icon(
          imageVector = if (state.installed) Icons.Outlined.DownloadDone else Icons.Outlined.CloudDownload,
          contentDescription = null,
          modifier = Modifier.size(15.dp),
        )
      }
      Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
      if (state.selectedAbi.isNotBlank()) {
        Text("• ${state.selectedAbi}", style = MaterialTheme.typography.labelMedium)
      }
      if (state.installedVersion.isNotBlank()) {
        Text("• ${state.installedVersion}", style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

@Composable
private fun OptionalToolProgress(state: TgWsProxyComponentState) {
  val progress = (state.progressPercent / 100f).coerceIn(0f, 1f)
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    androidx.compose.material3.LinearProgressIndicator(
      progress = { progress },
      modifier = Modifier.fillMaxWidth(),
    )
    val progressText = if (state.totalBytes > 0L && state.stage == TgWsProxyComponentStage.DOWNLOADING) {
      stringResource(R.string.optional_tools_download_progress, state.progressPercent, formatBytes(state.downloadedBytes), formatBytes(state.totalBytes))
    } else {
      stringResource(R.string.optional_tools_progress_percent, state.progressPercent)
    }
    Text(
      text = progressText,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
    )
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 0L) return "—"
  val kb = 1024.0
  val mb = kb * 1024.0
  return when {
    bytes >= mb -> String.format(java.util.Locale.US, "%.1f MB", bytes / mb)
    bytes >= kb -> String.format(java.util.Locale.US, "%.0f KB", bytes / kb)
    else -> "$bytes B"
  }
}
