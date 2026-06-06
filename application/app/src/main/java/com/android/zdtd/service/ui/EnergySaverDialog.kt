package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.api.ApiModels
import kotlin.math.max

private val delayOptions = listOf(30L, 60L, 180L, 300L, 600L, 1800L)

@Composable
fun EnergySaverDialog(
  visible: Boolean,
  state: ApiModels.EnergySaverState,
  saving: Boolean,
  onDismiss: () -> Unit,
  onSave: (ApiModels.EnergySaverConfig) -> Unit,
) {
  if (!visible) return

  val sourceConfig = state.settings
  var enabled by remember(state) { mutableStateOf(sourceConfig.enabled) }
  var delaySeconds by remember(state) { mutableStateOf(sourceConfig.freezeDelaySeconds.coerceAtLeast(10L)) }
  var programSettings by remember(state) { mutableStateOf(sourceConfig.programs.toMutableMap()) }
  val cpuCount = remember(state.onlineCpuCount) {
    val runtimeCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val daemonCount = state.onlineCpuCount.takeIf { it > 0 } ?: runtimeCount
    max(1, daemonCount).coerceAtMost(16)
  }
  val scroll = rememberScrollState()

  Dialog(
    onDismissRequest = { if (!saving) onDismiss() },
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .heightIn(max = 720.dp),
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 8.dp,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .animateContentSize()
          .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          }
          Column(Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.settings_energy_saver_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = stringResource(R.string.settings_energy_saver_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }
          IconButton(onClick = onDismiss, enabled = !saving) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
          }
        }

        Column(
          modifier = Modifier
            .weight(1f, fill = false)
            .animateContentSize()
            .verticalScroll(scroll),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          EnergySaverSwitchCard(
            title = stringResource(R.string.settings_energy_saver_global_title),
            body = stringResource(R.string.settings_energy_saver_global_body),
            checked = enabled,
            onCheckedChange = { enabled = it },
          )

          EnergySaverDelayCard(
            selected = delaySeconds,
            onSelected = { delaySeconds = it },
          )

          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
          ) {
            Text(
              text = stringResource(R.string.settings_energy_saver_warning),
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            )
          }

          if (state.programs.isEmpty()) {
            Text(
              text = stringResource(R.string.settings_energy_saver_no_programs),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          } else {
            state.programs.forEach { program ->
              val current = programSettings[program.id] ?: ApiModels.EnergySaverProgramSetting()
              EnergySaverProgramCard(
                program = program,
                setting = current,
                cpuCount = cpuCount,
                onSettingChange = { next ->
                  programSettings = programSettings.toMutableMap().also { it[program.id] = next }
                },
              )
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.common_cancel))
          }
          Button(
            onClick = {
              val cleanPrograms = programSettings
                .filterKeys { id -> state.programs.any { it.id == id } }
              onSave(
                ApiModels.EnergySaverConfig(
                  enabled = enabled,
                  freezeDelaySeconds = delaySeconds,
                  programs = cleanPrograms,
                )
              )
              onDismiss()
            },
            enabled = !saving,
            modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.common_save))
          }
        }
      }
    }
  }
}

@Composable
private fun EnergySaverSwitchCard(
  title: String,
  body: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.weight(1f).padding(end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
      }
      Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
  }
}

@Composable
private fun EnergySaverDelayCard(
  selected: Long,
  onSelected: (Long) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(stringResource(R.string.settings_energy_saver_delay_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(stringResource(R.string.settings_energy_saver_delay_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
      delayOptions.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          row.forEach { seconds ->
            FilterChip(
              selected = selected == seconds,
              onClick = { onSelected(seconds) },
              label = { Text(delayLabel(seconds)) },
              modifier = Modifier.weight(1f),
            )
          }
          repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun EnergySaverProgramCard(
  program: ApiModels.EnergySaverProgram,
  setting: ApiModels.EnergySaverProgramSetting,
  cpuCount: Int,
  onSettingChange: (ApiModels.EnergySaverProgramSetting) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().animateContentSize(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProgramIconBox(program.id)
        Column(Modifier.weight(1f)) {
          Text(
            text = toolDisplayName(program.id, program.displayName),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = stringResource(R.string.settings_energy_saver_running_pids, program.runningPids.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
          )
        }
      }

      EnergySaverOptionRow(
        title = stringResource(R.string.settings_energy_saver_freeze_title),
        body = stringResource(R.string.settings_energy_saver_freeze_body),
        checked = setting.freezeOnScreenOff,
        enabled = program.allowFreeze,
        onCheckedChange = { onSettingChange(setting.copy(freezeOnScreenOff = it)) },
      )
      EnergySaverOptionRow(
        title = stringResource(R.string.settings_energy_saver_affinity_title),
        body = stringResource(R.string.settings_energy_saver_affinity_body),
        checked = setting.cpuAffinityEnabled,
        enabled = program.allowAffinity,
        onCheckedChange = { onSettingChange(setting.copy(cpuAffinityEnabled = it)) },
      )
      AnimatedVisibility(
        visible = setting.cpuAffinityEnabled,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
      ) {
        CpuCoreSelector(
          selectedCores = setting.cpuCores,
          cpuCount = cpuCount,
          onSelectedCoresChange = { cores -> onSettingChange(setting.copy(cpuCores = cores)) },
        )
      }
    }
  }
}

@Composable
private fun CpuCoreSelector(
  selectedCores: List<Int>,
  cpuCount: Int,
  onSelectedCoresChange: (List<Int>) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().animateContentSize(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.Filled.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
        }
        Column(Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.settings_energy_saver_cores_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = stringResource(R.string.settings_energy_saver_cores_selected, selectedCores.size, cpuCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
          )
        }
      }

      (0 until cpuCount).chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          row.forEach { core ->
            val selected = selectedCores.contains(core)
            FilterChip(
              selected = selected,
              onClick = {
                val next = if (selected) {
                  selectedCores.filterNot { it == core }
                } else {
                  (selectedCores + core).distinct().sorted()
                }.ifEmpty { listOf(core) }
                onSelectedCoresChange(next)
              },
              label = { Text(stringResource(R.string.settings_energy_saver_core_chip, core)) },
              modifier = Modifier.weight(1f),
            )
          }
          repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun EnergySaverOptionRow(
  title: String,
  body: String,
  checked: Boolean,
  enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}

@Composable
private fun ProgramIconBox(programId: String) {
  Box(
    modifier = Modifier
      .size(42.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
    contentAlignment = Alignment.Center,
  ) {
    val iconRes = programIconRes(programId)
    if (iconRes != null) {
      Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(26.dp),
      )
    } else {
      Icon(programIcon(programId), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
  }
}

private fun delayLabel(seconds: Long): String {
  return when {
    seconds < 60 -> "${seconds}s"
    seconds % 60L == 0L -> "${seconds / 60L}m"
    else -> "${seconds}s"
  }
}
