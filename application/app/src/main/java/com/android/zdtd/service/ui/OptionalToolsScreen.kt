package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.components.RootBinaryComponentCatalog
import com.android.zdtd.service.components.RootBinaryComponentStage
import com.android.zdtd.service.components.RootBinaryComponentState
import com.android.zdtd.service.singboxlx.SingBoxLxComponentError
import com.android.zdtd.service.singboxlx.SingBoxLxComponentStage
import com.android.zdtd.service.singboxlx.SingBoxLxComponentState
import com.android.zdtd.service.tgwsproxy.TgWsProxyComponentStage
import com.android.zdtd.service.tgwsproxy.TgWsProxyComponentState

@Composable
fun OptionalToolsScreen(
  tgWsProxyState: TgWsProxyComponentState,
  singBoxLxState: SingBoxLxComponentState,
  components: List<ApiModels.Component>,
  rootComponentStates: Map<String, RootBinaryComponentState>,
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
    item(key = "tg-ws-proxy") {
      TgWsProxyUtilityCard(
        state = tgWsProxyState,
        onInstall = actions::installTgWsProxy,
        onRemove = actions::removeTgWsProxy,
      )
    }
    item(key = "sing-box-lx") {
      SingBoxLxUtilityCard(
        state = singBoxLxState,
        onInstall = actions::installSingBoxLx,
        onRemove = actions::removeSingBoxLx,
      )
    }
    RootBinaryComponentCatalog.moduleComponents.forEach { spec ->
      item(key = "component-${spec.id}") {
        val daemonState = components.firstOrNull { it.id == spec.id }
        val localState = rootComponentStates[spec.id] ?: RootBinaryComponentState(id = spec.id)
        val installed = if (localState.isWorking) localState.installed else daemonState?.installed ?: localState.installed
        GenericRootComponentCard(
          title = spec.displayName,
          state = localState,
          installed = installed,
          onInstall = { actions.installRootComponent(spec.id) },
          onRemove = { actions.removeRootComponent(spec.id) },
        )
      }
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
private fun TgWsProxyUtilityCard(
  state: TgWsProxyComponentState,
  onInstall: () -> Unit,
  onRemove: () -> Unit,
) {
  OptionalBinaryUtilityCard(
    title = stringResource(R.string.tg_ws_proxy_title),
    description = stringResource(R.string.tg_ws_proxy_desc),
    icon = Icons.Outlined.Send,
    installed = state.installed,
    isWorking = state.isWorking,
    statusText = tgWsProxyStatusText(state),
    progressPercent = state.progressPercent,
    downloadedBytes = state.downloadedBytes,
    totalBytes = state.totalBytes,
    isDownloading = state.stage == TgWsProxyComponentStage.DOWNLOADING,
    selectedAbi = state.selectedAbi,
    installedVersion = state.installedVersion,
    errorText = state.errorMessage,
    onInstall = onInstall,
    onRemove = onRemove,
  )
}

@Composable
private fun SingBoxLxUtilityCard(
  state: SingBoxLxComponentState,
  onInstall: () -> Unit,
  onRemove: () -> Unit,
) {
  OptionalBinaryUtilityCard(
    title = stringResource(R.string.sing_box_lx_title),
    description = stringResource(R.string.sing_box_lx_desc),
    icon = Icons.Outlined.Dns,
    installed = state.installed,
    isWorking = state.isWorking,
    statusText = singBoxLxStatusText(state),
    progressPercent = state.progressPercent,
    downloadedBytes = state.downloadedBytes,
    totalBytes = state.totalBytes,
    isDownloading = state.stage == SingBoxLxComponentStage.DOWNLOADING,
    selectedAbi = state.selectedAbi,
    installedVersion = state.installedVersion,
    errorText = singBoxLxErrorText(state),
    onInstall = onInstall,
    onRemove = onRemove,
  )
}

@Composable
private fun GenericRootComponentCard(
  title: String,
  state: RootBinaryComponentState,
  installed: Boolean,
  onInstall: () -> Unit,
  onRemove: () -> Unit,
) {
  val statusText = when {
    installed && state.stage != RootBinaryComponentStage.FAILED -> stringResource(R.string.optional_tools_status_installed)
    state.stage == RootBinaryComponentStage.DOWNLOADING -> stringResource(R.string.optional_tools_status_downloading)
    state.stage == RootBinaryComponentStage.UNPACKING -> stringResource(R.string.optional_tools_status_unpacking)
    state.stage == RootBinaryComponentStage.VERIFYING -> stringResource(R.string.optional_tools_status_verifying)
    state.stage == RootBinaryComponentStage.INSTALLING -> stringResource(R.string.optional_tools_status_installing)
    state.stage == RootBinaryComponentStage.FAILED -> stringResource(R.string.optional_tools_status_failed)
    else -> stringResource(R.string.optional_tools_status_not_installed)
  }
  OptionalBinaryUtilityCard(
    title = title,
    description = stringResource(R.string.optional_component_generic_desc),
    icon = Icons.Outlined.Extension,
    installed = installed,
    isWorking = state.isWorking,
    statusText = statusText,
    progressPercent = state.progressPercent,
    downloadedBytes = state.downloadedBytes,
    totalBytes = state.totalBytes,
    isDownloading = state.stage == RootBinaryComponentStage.DOWNLOADING,
    selectedAbi = state.selectedAbi,
    installedVersion = state.installedVersion,
    errorText = state.errorMessage,
    onInstall = onInstall,
    onRemove = onRemove,
  )
}

@Composable
private fun OptionalBinaryUtilityCard(
  title: String,
  description: String,
  icon: ImageVector,
  installed: Boolean,
  isWorking: Boolean,
  statusText: String,
  progressPercent: Int,
  downloadedBytes: Long,
  totalBytes: Long,
  isDownloading: Boolean,
  selectedAbi: String,
  installedVersion: String,
  errorText: String?,
  onInstall: () -> Unit,
  onRemove: () -> Unit,
) {
  val accentColor = if (installed) Color(0xFF22C55E) else MaterialTheme.colorScheme.primary
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.40f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
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
            Icon(icon, contentDescription = null, modifier = Modifier.size(29.dp))
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          )
        }
      }

      OptionalToolStatusPill(
        installed = installed,
        isWorking = isWorking,
        statusText = statusText,
        selectedAbi = selectedAbi,
        installedVersion = installedVersion,
        accentColor = accentColor,
      )

      if (isWorking) {
        OptionalToolProgress(
          progressPercent = progressPercent,
          downloadedBytes = downloadedBytes,
          totalBytes = totalBytes,
          isDownloading = isDownloading,
        )
      }

      errorText?.takeIf { it.isNotBlank() }?.let { error ->
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
        if (installed) {
          Button(
            onClick = onInstall,
            enabled = !isWorking,
            modifier = Modifier
              .weight(1f)
              .tvFocusHighlight(RoundedCornerShape(18.dp), !isWorking),
          ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.optional_tools_download_again))
          }
          OutlinedButton(
            onClick = onRemove,
            enabled = !isWorking,
            modifier = Modifier
              .weight(1f)
              .tvFocusHighlight(RoundedCornerShape(18.dp), !isWorking),
          ) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_delete))
          }
        } else {
          Button(
            onClick = onInstall,
            enabled = !isWorking,
            modifier = Modifier
              .fillMaxWidth()
              .tvFocusHighlight(RoundedCornerShape(18.dp), !isWorking),
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
private fun tgWsProxyStatusText(state: TgWsProxyComponentState): String = when {
  state.installed -> stringResource(R.string.optional_tools_status_installed)
  state.stage == TgWsProxyComponentStage.DOWNLOADING -> stringResource(R.string.optional_tools_status_downloading)
  state.stage == TgWsProxyComponentStage.UNPACKING -> stringResource(R.string.optional_tools_status_unpacking)
  state.stage == TgWsProxyComponentStage.VERIFYING -> stringResource(R.string.optional_tools_status_verifying)
  state.stage == TgWsProxyComponentStage.INSTALLING -> stringResource(R.string.optional_tools_status_installing)
  state.stage == TgWsProxyComponentStage.FAILED -> stringResource(R.string.optional_tools_status_failed)
  else -> stringResource(R.string.optional_tools_status_not_installed)
}

@Composable
private fun singBoxLxStatusText(state: SingBoxLxComponentState): String = when {
  state.installed && state.stage != SingBoxLxComponentStage.FAILED -> stringResource(R.string.optional_tools_status_installed)
  state.stage == SingBoxLxComponentStage.DOWNLOADING -> stringResource(R.string.optional_tools_status_downloading)
  state.stage == SingBoxLxComponentStage.UNPACKING -> stringResource(R.string.optional_tools_status_unpacking)
  state.stage == SingBoxLxComponentStage.VERIFYING -> stringResource(R.string.optional_tools_status_verifying)
  state.stage == SingBoxLxComponentStage.INSTALLING -> stringResource(R.string.optional_tools_status_installing)
  state.stage == SingBoxLxComponentStage.FAILED -> stringResource(R.string.optional_tools_status_failed)
  else -> stringResource(R.string.optional_tools_status_not_installed)
}

@Composable
private fun singBoxLxErrorText(state: SingBoxLxComponentState): String? {
  val error = state.error ?: return null
  val detail = state.errorDetail.orEmpty().ifBlank { "—" }
  return when (error) {
    SingBoxLxComponentError.STATUS -> stringResource(R.string.sing_box_lx_error_status)
    SingBoxLxComponentError.UNSUPPORTED_ABI -> stringResource(R.string.sing_box_lx_error_unsupported_abi, detail)
    SingBoxLxComponentError.DOWNLOAD -> stringResource(R.string.sing_box_lx_error_download, detail)
    SingBoxLxComponentError.PACKAGE -> stringResource(R.string.sing_box_lx_error_package, detail)
    SingBoxLxComponentError.INSTALL -> stringResource(R.string.sing_box_lx_error_install, detail)
    SingBoxLxComponentError.REMOVE -> stringResource(R.string.sing_box_lx_error_remove, detail)
  }
}

@Composable
private fun OptionalToolStatusPill(
  installed: Boolean,
  isWorking: Boolean,
  statusText: String,
  selectedAbi: String,
  installedVersion: String,
  accentColor: Color,
) {
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
      if (isWorking) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = accentColor)
      } else {
        Icon(
          imageVector = if (installed) Icons.Outlined.DownloadDone else Icons.Outlined.CloudDownload,
          contentDescription = null,
          modifier = Modifier.size(15.dp),
        )
      }
      Text(statusText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
      if (selectedAbi.isNotBlank()) {
        Text("• $selectedAbi", style = MaterialTheme.typography.labelMedium)
      }
      if (installedVersion.isNotBlank()) {
        Text("• $installedVersion", style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

@Composable
private fun OptionalToolProgress(
  progressPercent: Int,
  downloadedBytes: Long,
  totalBytes: Long,
  isDownloading: Boolean,
) {
  val progress = (progressPercent / 100f).coerceIn(0f, 1f)
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    androidx.compose.material3.LinearProgressIndicator(
      progress = { progress },
      modifier = Modifier.fillMaxWidth(),
    )
    val progressText = if (totalBytes > 0L && isDownloading) {
      stringResource(
        R.string.optional_tools_download_progress,
        progressPercent,
        formatBytes(downloadedBytes),
        formatBytes(totalBytes),
      )
    } else {
      stringResource(R.string.optional_tools_progress_percent, progressPercent)
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
