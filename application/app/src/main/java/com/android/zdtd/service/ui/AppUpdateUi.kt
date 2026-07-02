package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import com.android.zdtd.service.AppReleaseBuildStatus
import com.android.zdtd.service.AppReleaseStageStatus
import com.android.zdtd.service.AppReleaseBuildStageUi
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
              text = if (state.releaseBuild.status == AppReleaseBuildStatus.PREPARING || state.releaseBuild.status == AppReleaseBuildStatus.FAILED) {
                stringResource(R.string.app_update_release_preparing_title)
              } else if (state.urgent) stringResource(R.string.app_update_urgent_title) else stringResource(R.string.app_update_available_title),
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
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            GitHubApiStatusPill(state.githubApiOnline)
            IconButton(onClick = onDismiss) {
              Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
            }
          }
        }

        if (state.releaseBuild.status == AppReleaseBuildStatus.PREPARING || state.releaseBuild.status == AppReleaseBuildStatus.FAILED) {
          Spacer(Modifier.height(8.dp))
          AppReleaseBuildProgressCard(state = state)
        } else if (state.urgent) {
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
        } else if (state.releaseBuild.status != AppReleaseBuildStatus.PREPARING && state.releaseBuild.status != AppReleaseBuildStatus.FAILED) {
          Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_update))
          }
        }
      }
    }
  }
}




@Composable
private fun GitHubApiStatusPill(online: Boolean?) {
  val isOnline = online == true
  val color = if (isOnline) Color(0xFF22C55E) else MaterialTheme.colorScheme.error
  val text = when (online) {
    true -> stringResource(R.string.app_update_github_api_online)
    false -> stringResource(R.string.app_update_github_api_offline)
    null -> stringResource(R.string.app_update_github_api_offline)
  }
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = color.copy(alpha = 0.12f),
    contentColor = color,
    border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .background(color, CircleShape),
      )
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun AppReleaseBuildProgressCard(state: AppUpdateUiState) {
  val messageRes = state.releaseBuild.messageRes ?: if (state.releaseBuild.status == AppReleaseBuildStatus.FAILED) {
    R.string.app_update_release_failed_body
  } else {
    R.string.app_update_release_preparing_body
  }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.releaseBuild.status == AppReleaseBuildStatus.FAILED) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
        },
      )
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val stages = state.releaseBuild.stages.ifEmpty {
          listOf(
            AppReleaseBuildStageUi("binaries", R.string.app_update_stage_binaries, AppReleaseStageStatus.RUNNING),
            AppReleaseBuildStageUi("archives", R.string.app_update_stage_archives, AppReleaseStageStatus.WAITING),
            AppReleaseBuildStageUi("apk", R.string.app_update_stage_apk, AppReleaseStageStatus.WAITING),
            AppReleaseBuildStageUi("release", R.string.app_update_stage_release, AppReleaseStageStatus.WAITING),
            AppReleaseBuildStageUi("ready", R.string.app_update_stage_ready, AppReleaseStageStatus.WAITING),
          )
        }
        stages.forEach { stage ->
          AppReleaseBuildStageRow(stage)
        }
      }
    }
  }
}

@Composable
private fun AppReleaseBuildStageRow(stage: AppReleaseBuildStageUi) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    AppReleaseBuildStageIcon(stage.status)
    Text(
      text = stringResource(stage.titleRes),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (stage.status == AppReleaseStageStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
      color = when (stage.status) {
        AppReleaseStageStatus.FAILED -> MaterialTheme.colorScheme.error
        AppReleaseStageStatus.DONE -> MaterialTheme.colorScheme.onSurface
        AppReleaseStageStatus.RUNNING -> MaterialTheme.colorScheme.primary
        AppReleaseStageStatus.WAITING -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
      },
    )
  }
}

@Composable
private fun AppReleaseBuildStageIcon(status: AppReleaseStageStatus) {
  val tint = when (status) {
    AppReleaseStageStatus.DONE -> MaterialTheme.colorScheme.primary
    AppReleaseStageStatus.RUNNING -> MaterialTheme.colorScheme.primary
    AppReleaseStageStatus.FAILED -> MaterialTheme.colorScheme.error
    AppReleaseStageStatus.WAITING -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f)
  }
  Surface(
    shape = CircleShape,
    color = tint.copy(alpha = 0.14f),
    contentColor = tint,
  ) {
    Box(
      modifier = Modifier.padding(4.dp).size(20.dp),
      contentAlignment = Alignment.Center,
    ) {
      when (status) {
        AppReleaseStageStatus.DONE -> Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
        AppReleaseStageStatus.RUNNING -> {
          CircularProgressIndicator(
            modifier = Modifier.matchParentSize(),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
          )
          Box(
            modifier = Modifier
              .size(5.dp)
              .background(MaterialTheme.colorScheme.primary, CircleShape),
          )
        }
        AppReleaseStageStatus.FAILED -> Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(17.dp))
        AppReleaseStageStatus.WAITING -> Box(
          modifier = Modifier
            .size(9.dp)
            .background(tint, CircleShape),
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
