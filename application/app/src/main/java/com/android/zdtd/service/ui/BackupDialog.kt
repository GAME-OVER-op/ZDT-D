package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.android.zdtd.service.BackupItem
import com.android.zdtd.service.BackupUiState
import com.android.zdtd.service.ZdtdActions


@Composable
fun BackupDialog(
  state: BackupUiState,
  onDismiss: () -> Unit,
  actions: ZdtdActions,
) {
  var confirmRestore by remember { mutableStateOf<BackupItem?>(null) }
  var confirmDelete by remember { mutableStateOf<BackupItem?>(null) }
  var requireReopenAfterRestore by remember { mutableStateOf(false) }
  var confirmForceRestore by remember { mutableStateOf<String?>(null) }
  var confirmForceRestoreFinal by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(state.progressVisible, state.progressFinished, state.progressError) {
    if (state.progressVisible && state.progressFinished && state.progressError == null) {
      // Avoid immediate repeated restore in the same open dialog session: some devices keep
      // a stale restore state until the dialog is reopened.
      requireReopenAfterRestore = true
    }
  }

  Dialog(
    onDismissRequest = {
      // While an operation is running, keep the user inside the dialog.
      if (!state.progressVisible || state.progressFinished) {
        requireReopenAfterRestore = false
        onDismiss()
      }
    },
    properties = DialogProperties(dismissOnClickOutside = !state.progressVisible || state.progressFinished)
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
          Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.weight(1f))
          IconButton(onClick = { requireReopenAfterRestore = false; actions.refreshBackups() }) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.backup_refresh_cd))
          }
        }

        Spacer(Modifier.height(6.dp))
        Text(
          stringResource(R.string.backup_desc),
          style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(
            onClick = { actions.createBackup() },
            enabled = (!state.progressVisible || state.progressFinished) && !requireReopenAfterRestore,
          ) { Text(stringResource(R.string.backup_create)) }

          OutlinedButton(
            onClick = { actions.requestBackupImport() },
            enabled = (!state.progressVisible || state.progressFinished) && !requireReopenAfterRestore,
          ) { Text(stringResource(R.string.backup_import)) }
        }

        if (state.error != null) {
          Spacer(Modifier.height(12.dp))
          Text(state.error, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))

        when {
          state.loading -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp))
              Text(stringResource(R.string.backup_loading_list))
            }
          }
          state.items.isEmpty() -> {
            Text(stringResource(R.string.backup_none_found))
          }
          else -> {
            Text(stringResource(R.string.backup_yours), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              items(state.items, key = { it.name }) { item ->
                BackupItemCard(
                  item = item,
                  enabled = (!state.progressVisible || state.progressFinished) && !requireReopenAfterRestore,
                  onRestore = { confirmRestore = item },
                  onShare = { actions.shareBackup(item.name) },
                  onDelete = { confirmDelete = item },
                )
              }
            }
          }
        }

        Spacer(Modifier.height(12.dp))
        if (requireReopenAfterRestore && !state.progressVisible) {
          Text(
            stringResource(R.string.mv_backup_restore_reopen_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(
            onClick = { requireReopenAfterRestore = false; onDismiss() },
            enabled = !state.progressVisible || state.progressFinished,
          ) { Text(stringResource(R.string.backup_close)) }
        }
      }
    }
  }

  if (confirmRestore != null) {
    val item = confirmRestore!!
    AlertDialog(
      onDismissRequest = { confirmRestore = null },
      title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
      text = {
        Text(
          stringResource(R.string.backup_restore_confirm_text)
        )
      },
      confirmButton = {
        TextButton(onClick = {
          confirmRestore = null
          actions.restoreBackup(item.name)
        }) { Text(stringResource(R.string.backup_restore)) }
      },
      dismissButton = {
        TextButton(onClick = { confirmRestore = null }) { Text(stringResource(R.string.backup_cancel)) }
      },
    )
  }

  if (confirmDelete != null) {
    val item = confirmDelete!!
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text(stringResource(R.string.backup_delete_confirm_title)) },
      text = { Text(stringResource(R.string.backup_delete_confirm_text)) },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = null
          actions.deleteBackup(item.name)
        }) { Text(stringResource(R.string.backup_delete)) }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.backup_cancel)) }
      },
    )
  }


  if (confirmForceRestore != null) {
    val name = confirmForceRestore!!
    AlertDialog(
      onDismissRequest = { confirmForceRestore = null },
      title = { Text(stringResource(R.string.backup_force_restore_title)) },
      text = { Text(stringResource(R.string.backup_force_restore_text)) },
      confirmButton = {
        TextButton(onClick = {
          confirmForceRestore = null
          confirmForceRestoreFinal = name
        }) { Text(stringResource(R.string.backup_force_restore_continue)) }
      },
      dismissButton = {
        TextButton(onClick = { confirmForceRestore = null }) { Text(stringResource(R.string.backup_back)) }
      },
    )
  }

  if (confirmForceRestoreFinal != null) {
    val name = confirmForceRestoreFinal!!
    AlertDialog(
      onDismissRequest = { confirmForceRestoreFinal = null },
      title = { Text(stringResource(R.string.backup_force_restore_confirm_title)) },
      text = { Text(stringResource(R.string.backup_force_restore_confirm_text)) },
      confirmButton = {
        TextButton(onClick = {
          confirmForceRestoreFinal = null
          actions.restoreBackup(name, ignoreVersionCode = true)
        }) { Text(stringResource(R.string.backup_force_restore_confirm_btn)) }
      },
      dismissButton = {
        TextButton(onClick = { confirmForceRestoreFinal = null }) { Text(stringResource(R.string.backup_back)) }
      },
    )
  }

  if (state.progressVisible) {
    BackupProgressDialog(
      state = state,
      onClose = actions::closeBackupProgress,
      onForceRestore = {
        // Close current error dialog first, then show force-restore warning.
        actions.closeBackupProgress()
        confirmForceRestore = state.forceRestoreName
      },
    )
  }
}


@Composable
private fun BackupItemCard(
  item: BackupItem,
  enabled: Boolean,
  onRestore: () -> Unit,
  onShare: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(12.dp)) {
      Text(item.name, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(4.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item.createdAtText.isNotBlank()) {
          Text(item.createdAtText, style = MaterialTheme.typography.bodySmall)
        }
        Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.bodySmall)
      }

      Spacer(Modifier.height(10.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(onClick = onRestore, enabled = enabled) {
          Icon(Icons.Filled.Restore, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.backup_item_restore))
        }

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onShare, enabled = enabled) {
          Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.backup_share_cd))
        }
        IconButton(onClick = onDelete, enabled = enabled) {
          Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.backup_delete_cd))
        }
      }
    }
  }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupProgressDialog(
  state: BackupUiState,
  onClose: () -> Unit,
  onForceRestore: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = {
      if (state.progressFinished) onClose()
    },
    title = { Text(if (state.progressTitle.isBlank()) stringResource(R.string.backup_progress_default_title) else state.progressTitle) },
    text = {
      Column(Modifier.fillMaxWidth()) {
        if (state.progressText.isNotBlank()) {
          Text(state.progressText)
          Spacer(Modifier.height(8.dp))
        }

        LinearProgressIndicator(
          progress = state.progressPercent.coerceIn(0, 100) / 100f,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text("${state.progressPercent.coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall)

        if (state.progressError != null) {
          Spacer(Modifier.height(10.dp))
          Text(state.progressError, color = MaterialTheme.colorScheme.error)
        }
      }
    },
    confirmButton = {
      if (state.progressFinished) {
        if (state.forceRestoreAvailable) {
          TextButton(onClick = onForceRestore) { Text(stringResource(R.string.backup_restore_anyway)) }
        } else {
          TextButton(onClick = onClose) { Text(stringResource(R.string.backup_close)) }
        }
      }
    },
    dismissButton = {
      if (state.progressFinished && state.forceRestoreAvailable) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.backup_back)) }
      }
    },
    properties = DialogProperties(
      dismissOnBackPress = state.progressFinished,
      dismissOnClickOutside = state.progressFinished,
    )
  )
}


private fun formatBytes(bytes: Long): String {
  if (bytes <= 0L) return "0 B"
  val kb = 1024.0
  val mb = kb * 1024.0
  val gb = mb * 1024.0
  return when {
    bytes >= gb -> String.format("%.2f GB", bytes / gb)
    bytes >= mb -> String.format("%.2f MB", bytes / mb)
    bytes >= kb -> String.format("%.2f KB", bytes / kb)
    else -> "$bytes B"
  }
}
