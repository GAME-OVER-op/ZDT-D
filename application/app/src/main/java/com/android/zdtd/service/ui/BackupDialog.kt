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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

  Dialog(
    onDismissRequest = {
      // While an operation is running, keep the user inside the dialog.
      if (!state.progressVisible || state.progressFinished) onDismiss()
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
          Text("Бэкап настроек", style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.weight(1f))
          IconButton(onClick = { actions.refreshBackups() }) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
          }
        }

        Spacer(Modifier.height(6.dp))
        Text(
          "Сохраните все папки из working_folder в один архив, а затем при необходимости восстановите их обратно. " +
            "Файл flag.sha256 не переносится.",
          style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(
            onClick = { actions.createBackup() },
            enabled = !state.progressVisible || state.progressFinished,
          ) { Text("Создать бэкап") }

          OutlinedButton(
            onClick = { actions.requestBackupImport() },
            enabled = !state.progressVisible || state.progressFinished,
          ) { Text("Импортировать") }
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
              Text("Загрузка списка…")
            }
          }
          state.items.isEmpty() -> {
            Text("Бэкапы не найдены в /storage/emulated/0/ZDT-D_Backups")
          }
          else -> {
            Text("Ваши бэкапы", fontWeight = FontWeight.SemiBold)
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
                  enabled = !state.progressVisible || state.progressFinished,
                  onRestore = { confirmRestore = item },
                  onShare = { actions.shareBackup(item.name) },
                  onDelete = { confirmDelete = item },
                )
              }
            }
          }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(
            onClick = onDismiss,
            enabled = !state.progressVisible || state.progressFinished,
          ) { Text("Закрыть") }
        }
      }
    }
  }

  if (confirmRestore != null) {
    val item = confirmRestore!!
    AlertDialog(
      onDismissRequest = { confirmRestore = null },
      title = { Text("Восстановить бэкап?") },
      text = {
        Text(
          "Текущие настройки будут полностью заменены содержимым архива. " +
            "Сервис будет остановлен на время операции."
        )
      },
      confirmButton = {
        TextButton(onClick = {
          confirmRestore = null
          actions.restoreBackup(item.name)
        }) { Text("Восстановить") }
      },
      dismissButton = {
        TextButton(onClick = { confirmRestore = null }) { Text("Отмена") }
      },
    )
  }

  if (confirmDelete != null) {
    val item = confirmDelete!!
    AlertDialog(
      onDismissRequest = { confirmDelete = null },
      title = { Text("Удалить бэкап?") },
      text = { Text("Файл будет удалён без возможности восстановления.") },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = null
          actions.deleteBackup(item.name)
        }) { Text("Удалить") }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = null }) { Text("Отмена") }
      },
    )
  }

  if (state.progressVisible) {
    BackupProgressDialog(state = state, onClose = actions::closeBackupProgress)
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
          Text("Восстановить")
        }

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onShare, enabled = enabled) {
          Icon(Icons.Filled.Share, contentDescription = "Share")
        }
        IconButton(onClick = onDelete, enabled = enabled) {
          Icon(Icons.Filled.Delete, contentDescription = "Delete")
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
) {
  AlertDialog(
    onDismissRequest = {
      if (state.progressFinished) onClose()
    },
    title = { Text(if (state.progressTitle.isBlank()) "Операция" else state.progressTitle) },
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
        TextButton(onClick = onClose) { Text("Закрыть") }
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
