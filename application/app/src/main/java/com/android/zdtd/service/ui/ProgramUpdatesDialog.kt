package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.R
import com.android.zdtd.service.ProgramUpdateItemUi
import com.android.zdtd.service.ProgramReleaseUi
import com.android.zdtd.service.ProgramUpdatesUiState
import com.android.zdtd.service.ZdtdActions


@Composable
fun ProgramUpdatesDialog(
  state: ProgramUpdatesUiState,
  serviceRunning: Boolean,
  onDismiss: () -> Unit,
  actions: ZdtdActions,
) {
  var picking by remember { mutableStateOf<String?>(null) } // "zapret" | "zapret2"

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(dismissOnClickOutside = true),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      tonalElevation = 8.dp,
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.program_updates_title), style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.weight(1f))
          IconButton(onClick = actions::resetProgramUpdatesUi) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.program_updates_reset_cd))
          }
        }

        Spacer(Modifier.height(6.dp))
        Text(
          stringResource(R.string.program_updates_desc),
          style = MaterialTheme.typography.bodySmall,
        )

        if (serviceRunning) {
          Spacer(Modifier.height(12.dp))
          Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
              Text(stringResource(R.string.program_updates_service_running_title), fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Text(
                stringResource(R.string.program_updates_service_running_desc),
                style = MaterialTheme.typography.bodySmall,
              )
              Spacer(Modifier.height(10.dp))
              Button(
                onClick = actions::stopServiceForProgramUpdatesAndCheck,
                enabled = !state.stoppingService,
              ) {
                Text(if (state.stoppingService) stringResource(R.string.program_updates_stopping) else stringResource(R.string.program_updates_stop_and_check))
              }
            }
          }
        }

        Spacer(Modifier.height(12.dp))
        ProgramUpdateCard(
          item = state.zapret,
          enabled = !serviceRunning && !state.zapret.updating && !state.zapret.checking && !state.stoppingService,
          onCheck = actions::checkZapretNow,
          onUpdate = actions::updateZapretNow,
          onPickVersion = {
            picking = "zapret"
            if (state.zapret.releases.isEmpty() && !state.zapret.releasesLoading) actions.loadZapretReleases()
          },
        )

        Spacer(Modifier.height(12.dp))
        ProgramUpdateCard(
          item = state.zapret2,
          enabled = !serviceRunning && !state.zapret2.updating && !state.zapret2.checking && !state.stoppingService,
          onCheck = actions::checkZapret2Now,
          onUpdate = actions::updateZapret2Now,
          onPickVersion = {
            picking = "zapret2"
            if (state.zapret2.releases.isEmpty() && !state.zapret2.releasesLoading) actions.loadZapret2Releases()
          },
        )

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_close)) }
        }
      }
    }
  }

  // Version picker dialog (separate from the main dialog)
  val pick = picking
  if (pick != null) {
    val item = if (pick == "zapret") state.zapret else state.zapret2
    ReleasePickerDialog(
      title = if (pick == "zapret") stringResource(R.string.program_updates_pick_zapret_title) else stringResource(R.string.program_updates_pick_zapret2_title),
      stateItem = item,
      minVersion = if (pick == "zapret") "v71.4" else "v0.8.6",
      onRefresh = {
        if (pick == "zapret") actions.loadZapretReleases() else actions.loadZapret2Releases()
      },
      onSelectLatest = {
        if (pick == "zapret") actions.selectZapretRelease(null, null) else actions.selectZapret2Release(null, null)
        picking = null
      },
      onSelectRelease = { v, url ->
        if (pick == "zapret") actions.selectZapretRelease(v, url) else actions.selectZapret2Release(v, url)
        picking = null
      },
      onDismiss = { picking = null },
    )
  }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramUpdateCard(
  item: ProgramUpdateItemUi,
  enabled: Boolean,
  onCheck: () -> Unit,
  onUpdate: () -> Unit,
  onPickVersion: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(item.titleRes?.let { stringResource(it) } ?: item.title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPickVersion, enabled = enabled) {
          Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.program_updates_select_version_cd))
        }
        if (item.updateAvailable) {
          Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null)
        }
      }

      Spacer(Modifier.height(6.dp))
      Text(
        stringResource(
          R.string.program_updates_installed_latest_fmt,
          item.installedVersion ?: "—",
          item.latestVersion ?: "—",
        ),
        style = MaterialTheme.typography.bodySmall,
      )

      val target = item.selectedVersion ?: item.latestVersion
      if (target != null) {
        Spacer(Modifier.height(4.dp))
        val suffix = if (item.selectedVersion != null) {
          stringResource(R.string.program_updates_target_selected_suffix)
        } else {
          stringResource(R.string.program_updates_target_latest_suffix)
        }
        Text(
          stringResource(R.string.program_updates_target_fmt, target, suffix),
          style = MaterialTheme.typography.bodySmall,
        )
      }

      if (item.warningText != null) {
        Spacer(Modifier.height(6.dp))
        Text(item.warningText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }

      if (item.statusText.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(item.statusText, style = MaterialTheme.typography.bodySmall)
      }

      if (item.errorText != null) {
        Spacer(Modifier.height(6.dp))
        Text(item.errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }

      if (item.checking || item.updating) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
          progress = (item.progressPercent.coerceIn(0, 100)) / 100f,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      Spacer(Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onCheck, enabled = enabled) {
          Text(if (item.checking) stringResource(R.string.program_updates_checking) else stringResource(R.string.program_updates_check))
        }
        Button(
          onClick = onUpdate,
          enabled = enabled && item.updateAvailable,
        ) {
          Text(if (item.updating) stringResource(R.string.program_updates_updating) else stringResource(R.string.program_updates_update))
        }
      }
    }
  }
}


@Composable
private fun ReleasePickerDialog(
  title: String,
  stateItem: ProgramUpdateItemUi,
  minVersion: String,
  onRefresh: () -> Unit,
  onSelectLatest: () -> Unit,
  onSelectRelease: (String, String) -> Unit,
  onDismiss: () -> Unit,
) {
  var pending by remember { mutableStateOf<ProgramReleaseUi?>(null) }
  var showWarn by remember { mutableStateOf(false) }

  if (showWarn && pending != null) {
    AlertDialog(
      onDismissRequest = { showWarn = false; pending = null },
      title = { Text(stringResource(R.string.program_updates_warning_title)) },
      text = {
        Text(stringResource(R.string.program_updates_warning_text_fmt, pending!!.version, minVersion))
      },
      confirmButton = {
        TextButton(onClick = {
          val p = pending
          showWarn = false
          pending = null
          if (p != null) onSelectRelease(p.version, p.downloadUrl)
        }) { Text(stringResource(R.string.program_updates_continue)) }
      },
      dismissButton = {
        TextButton(onClick = { showWarn = false; pending = null }) { Text(stringResource(R.string.backup_cancel)) }
      }
    )
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      tonalElevation = 8.dp,
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(title, style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.weight(1f))
          IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.program_updates_refresh_cd)) }
        }

        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.program_updates_choose_version_hint), style = MaterialTheme.typography.bodySmall)

        if (stateItem.releasesLoading) {
          Spacer(Modifier.height(10.dp))
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (stateItem.releasesError != null) {
          Spacer(Modifier.height(10.dp))
          Text(stateItem.releasesError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item {
            Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(Modifier.weight(1f)) {
                  Text(stringResource(R.string.program_updates_latest_auto_title), fontWeight = FontWeight.SemiBold)
                  Text(stringResource(R.string.program_updates_latest_auto_desc), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onSelectLatest) { Text(stringResource(R.string.program_updates_select)) }
              }
            }
          }

          items(stateItem.releases) { r ->
            Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(Modifier.weight(1f)) {
                  Text(r.version, fontWeight = FontWeight.SemiBold)
                  val date = r.publishedAt.take(10)
                  if (date.isNotBlank()) Text(date, style = MaterialTheme.typography.bodySmall)
                }
                val isSelected = stateItem.selectedVersion == r.version
                val label = if (isSelected) stringResource(R.string.program_updates_selected) else stringResource(R.string.program_updates_select)
                Button(onClick = {
                  if (isBelowMin(r.version, minVersion)) {
                    pending = r
                    showWarn = true
                  } else {
                    onSelectRelease(r.version, r.downloadUrl)
                  }
                }) { Text(label) }
              }
            }
          }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_close)) }
        }
      }
    }
  }
}

private fun parseVersionParts(v: String): List<Int>? {
  val s = v.trim().removePrefix("v").removePrefix("V")
  if (s.isBlank()) return null
  val parts = s.split('.')
  val nums = parts.mapNotNull { it.toIntOrNull() }
  if (nums.isEmpty() || nums.size != parts.size) return null
  return (nums + listOf(0, 0, 0, 0)).take(4)
}

private fun isBelowMin(v: String, min: String): Boolean {
  val a = parseVersionParts(v) ?: return false
  val b = parseVersionParts(min) ?: return false
  for (i in 0 until 4) {
    if (a[i] != b[i]) return a[i] < b[i]
  }
  return false
}
