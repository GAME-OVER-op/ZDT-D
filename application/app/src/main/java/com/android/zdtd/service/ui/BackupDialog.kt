package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.BackupItem
import com.android.zdtd.service.BackupUiState
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions

@Composable
fun BackupDialog(
  state: BackupUiState,
  onDismiss: () -> Unit,
  actions: ZdtdActions,
) {
  val landscape = rememberUseLandscapeControlLayout()
  val compact = !landscape && (rememberIsCompactWidth() || rememberIsShortHeight())
  var confirmRestore by remember { mutableStateOf<BackupItem?>(null) }
  var confirmDelete by remember { mutableStateOf<BackupItem?>(null) }
  var requireReopenAfterRestore by remember { mutableStateOf(false) }
  var confirmForceRestore by remember { mutableStateOf<String?>(null) }
  var confirmForceRestoreFinal by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(state.progressVisible, state.progressFinished, state.progressError) {
    if (state.progressVisible && state.progressFinished && state.progressError == null) {
      requireReopenAfterRestore = true
    }
  }

  Dialog(
    onDismissRequest = {
      if (!state.progressVisible || state.progressFinished) {
        requireReopenAfterRestore = false
        onDismiss()
      }
    },
    properties = DialogProperties(
      dismissOnClickOutside = !state.progressVisible || state.progressFinished,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing),
      contentAlignment = if (landscape) Alignment.CenterStart else Alignment.Center,
    ) {
      val accent = Color(0xFFA78BFA)
      val accent2 = Color(0xFF38BDF8)
      val shape = RoundedCornerShape(if (compact) 26.dp else 32.dp)
      Surface(
        modifier = if (landscape) {
          Modifier
            .fillMaxWidth(0.76f)
            .fillMaxHeight(0.92f)
            .widthIn(max = 920.dp)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp)
        } else {
          Modifier
            .fillMaxWidth()
            .fillMaxHeight(if (compact) 0.88f else 0.82f)
            .widthIn(max = 660.dp)
            .padding(if (compact) 12.dp else 16.dp)
        },
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
      ) {
        Box(
          modifier = Modifier.background(
            Brush.linearGradient(
              colors = listOf(
                accent.copy(alpha = 0.16f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                accent2.copy(alpha = 0.10f),
              )
            )
          )
        ) {
          if (landscape) {
            BackupLandscapeContent(
              state = state,
              enabled = (!state.progressVisible || state.progressFinished) && !requireReopenAfterRestore,
              requireReopenAfterRestore = requireReopenAfterRestore,
              onRefresh = { requireReopenAfterRestore = false; actions.refreshBackups() },
              onCreate = actions::createBackup,
              onImport = actions::requestBackupImport,
              onRestore = { confirmRestore = it },
              onShare = { actions.shareBackup(it.name) },
              onDelete = { confirmDelete = it },
              onClose = { requireReopenAfterRestore = false; onDismiss() },
            )
          } else {
            BackupPortraitContent(
              state = state,
              compact = compact,
              enabled = (!state.progressVisible || state.progressFinished) && !requireReopenAfterRestore,
              requireReopenAfterRestore = requireReopenAfterRestore,
              onRefresh = { requireReopenAfterRestore = false; actions.refreshBackups() },
              onCreate = actions::createBackup,
              onImport = actions::requestBackupImport,
              onRestore = { confirmRestore = it },
              onShare = { actions.shareBackup(it.name) },
              onDelete = { confirmDelete = it },
              onClose = { requireReopenAfterRestore = false; onDismiss() },
            )
          }
        }
      }
    }
  }

  confirmRestore?.let { item ->
    BackupConfirmDialog(
      title = stringResource(R.string.backup_restore_confirm_title),
      text = stringResource(R.string.backup_restore_confirm_text),
      confirmText = stringResource(R.string.backup_restore),
      destructive = false,
      onDismiss = { confirmRestore = null },
      onConfirm = {
        confirmRestore = null
        actions.restoreBackup(item.name)
      },
    )
  }

  confirmDelete?.let { item ->
    BackupConfirmDialog(
      title = stringResource(R.string.backup_delete_confirm_title),
      text = stringResource(R.string.backup_delete_confirm_text),
      confirmText = stringResource(R.string.backup_delete),
      destructive = true,
      onDismiss = { confirmDelete = null },
      onConfirm = {
        confirmDelete = null
        actions.deleteBackup(item.name)
      },
    )
  }

  confirmForceRestore?.let { name ->
    BackupConfirmDialog(
      title = stringResource(R.string.backup_force_restore_title),
      text = stringResource(R.string.backup_force_restore_text),
      confirmText = stringResource(R.string.backup_force_restore_continue),
      destructive = true,
      dismissText = stringResource(R.string.backup_back),
      onDismiss = { confirmForceRestore = null },
      onConfirm = {
        confirmForceRestore = null
        confirmForceRestoreFinal = name
      },
    )
  }

  confirmForceRestoreFinal?.let { name ->
    BackupConfirmDialog(
      title = stringResource(R.string.backup_force_restore_confirm_title),
      text = stringResource(R.string.backup_force_restore_confirm_text),
      confirmText = stringResource(R.string.backup_force_restore_confirm_btn),
      destructive = true,
      dismissText = stringResource(R.string.backup_back),
      onDismiss = { confirmForceRestoreFinal = null },
      onConfirm = {
        confirmForceRestoreFinal = null
        actions.restoreBackup(name, ignoreVersionCode = true)
      },
    )
  }

  if (state.progressVisible) {
    BackupProgressDialog(
      state = state,
      onClose = actions::closeBackupProgress,
      onForceRestore = {
        actions.closeBackupProgress()
        confirmForceRestore = state.forceRestoreName
      },
    )
  }
}

@Composable
private fun BackupLandscapeContent(
  state: BackupUiState,
  enabled: Boolean,
  requireReopenAfterRestore: Boolean,
  onRefresh: () -> Unit,
  onCreate: () -> Unit,
  onImport: () -> Unit,
  onRestore: (BackupItem) -> Unit,
  onShare: (BackupItem) -> Unit,
  onDelete: (BackupItem) -> Unit,
  onClose: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    BackupDialogHeader(onRefresh = onRefresh)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      BackupActionPanel(
        modifier = Modifier
          .weight(0.42f)
          .fillMaxHeight(),
        state = state,
        compact = false,
        enabled = enabled,
        requireReopenAfterRestore = requireReopenAfterRestore,
        onCreate = onCreate,
        onImport = onImport,
      )
      BackupListPanel(
        modifier = Modifier
          .weight(0.58f)
          .fillMaxHeight(),
        state = state,
        enabled = enabled,
        onRestore = onRestore,
        onShare = onShare,
        onDelete = onDelete,
      )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      TextButton(onClick = onClose, enabled = !state.progressVisible || state.progressFinished) {
        Text(stringResource(R.string.backup_close))
      }
    }
  }
}

@Composable
private fun BackupPortraitContent(
  state: BackupUiState,
  compact: Boolean,
  enabled: Boolean,
  requireReopenAfterRestore: Boolean,
  onRefresh: () -> Unit,
  onCreate: () -> Unit,
  onImport: () -> Unit,
  onRestore: (BackupItem) -> Unit,
  onShare: (BackupItem) -> Unit,
  onDelete: (BackupItem) -> Unit,
  onClose: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    BackupDialogHeader(onRefresh = onRefresh)
    BackupActionPanel(
      modifier = Modifier.fillMaxWidth(),
      state = state,
      compact = compact,
      enabled = enabled,
      requireReopenAfterRestore = requireReopenAfterRestore,
      onCreate = onCreate,
      onImport = onImport,
    )
    BackupListPanel(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f),
      state = state,
      enabled = enabled,
      onRestore = onRestore,
      onShare = onShare,
      onDelete = onDelete,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      TextButton(onClick = onClose, enabled = !state.progressVisible || state.progressFinished) {
        Text(stringResource(R.string.backup_close))
      }
    }
  }
}

@Composable
private fun BackupDialogHeader(onRefresh: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      modifier = Modifier.size(42.dp),
      shape = CircleShape,
      color = Color(0xFFA78BFA).copy(alpha = 0.18f),
      border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.34f)),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Restore, contentDescription = null, tint = Color(0xFFD8B4FE))
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(
        stringResource(R.string.backup_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        stringResource(R.string.backup_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconButton(onClick = onRefresh) {
      Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.backup_refresh_cd))
    }
  }
}

@Composable
private fun BackupActionPanel(
  modifier: Modifier,
  state: BackupUiState,
  compact: Boolean,
  enabled: Boolean,
  requireReopenAfterRestore: Boolean,
  onCreate: () -> Unit,
  onImport: () -> Unit,
) {
  StyledDialogSection(
    modifier = modifier,
    accent = Color(0xFFA78BFA),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .then(if (compact) Modifier else Modifier.verticalScroll(rememberScrollState())),
      verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
      if (compact) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            stringResource(R.string.backup_create),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          IconButton(
            onClick = onCreate,
            enabled = enabled,
          ) {
            Icon(
              Icons.Filled.Add,
              contentDescription = stringResource(R.string.backup_create),
            )
          }
          IconButton(
            onClick = onImport,
            enabled = enabled,
          ) {
            Icon(
              Icons.Filled.FileUpload,
              contentDescription = stringResource(R.string.backup_import),
            )
          }
        }
      } else {
        Text(
          stringResource(R.string.backup_create),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          stringResource(R.string.backup_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
        Button(
          onClick = onCreate,
          enabled = enabled,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
        ) {
          Text(stringResource(R.string.backup_create))
        }
        OutlinedButton(
          onClick = onImport,
          enabled = enabled,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
        ) {
          Text(stringResource(R.string.backup_import))
        }
      }
      if (state.error != null) {
        StyledMessageCard(
          text = state.error,
          accent = MaterialTheme.colorScheme.error,
        )
      }
      if (requireReopenAfterRestore && !state.progressVisible) {
        StyledMessageCard(
          text = stringResource(R.string.mv_backup_restore_reopen_hint),
          accent = Color(0xFF38BDF8),
        )
      }
    }
  }
}

@Composable
private fun BackupListPanel(
  modifier: Modifier,
  state: BackupUiState,
  enabled: Boolean,
  onRestore: (BackupItem) -> Unit,
  onShare: (BackupItem) -> Unit,
  onDelete: (BackupItem) -> Unit,
) {
  StyledDialogSection(
    modifier = modifier,
    accent = Color(0xFF38BDF8),
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          stringResource(R.string.backup_yours),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = Color(0xFF38BDF8).copy(alpha = 0.14f),
          border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.24f)),
        ) {
          Text(
            state.items.size.toString(),
            modifier = Modifier.padding(start = 10.dp, top = 4.dp, end = 10.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF7DD3FC),
          )
        }
      }
      BackupListContent(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        state = state,
        enabled = enabled,
        onRestore = onRestore,
        onShare = onShare,
        onDelete = onDelete,
      )
    }
  }
}

@Composable
private fun BackupListContent(
  modifier: Modifier,
  state: BackupUiState,
  enabled: Boolean,
  onRestore: (BackupItem) -> Unit,
  onShare: (BackupItem) -> Unit,
  onDelete: (BackupItem) -> Unit,
) {
  when {
    state.loading -> {
      StyledDialogSection(modifier = modifier, accent = Color(0xFF38BDF8)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp))
          Text(stringResource(R.string.backup_loading_list))
        }
      }
    }
    state.items.isEmpty() -> {
      StyledDialogSection(modifier = modifier, accent = Color(0xFF38BDF8)) {
        Text(
          stringResource(R.string.backup_none_found),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
      }
    }
    state.items.size <= 2 -> {
      Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        state.items.forEach { item ->
          BackupItemCard(
            item = item,
            enabled = enabled,
            onRestore = { onRestore(item) },
            onShare = { onShare(item) },
            onDelete = { onDelete(item) },
          )
        }
      }
    }
    else -> {
      LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(state.items, key = { it.name }, contentType = { "backup_item" }) { item ->
          BackupItemCard(
            item = item,
            enabled = enabled,
            onRestore = { onRestore(item) },
            onShare = { onShare(item) },
            onDelete = { onDelete(item) },
          )
        }
      }
    }
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
  val compact = rememberIsCompactWidth()
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
          modifier = Modifier.size(32.dp),
          shape = CircleShape,
          color = Color(0xFFA78BFA).copy(alpha = 0.14f),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              Icons.Filled.Restore,
              contentDescription = null,
              tint = Color(0xFFD8B4FE),
              modifier = Modifier.size(18.dp),
            )
          }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (item.createdAtText.isNotBlank()) {
              Text(item.createdAtText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f), maxLines = 1)
            }
            Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f), maxLines = 1)
          }
        }
      }
      if (compact) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(onClick = onRestore, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Restore, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.backup_item_restore))
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            IconButton(onClick = onShare, enabled = enabled) {
              Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.backup_share_cd))
            }
            IconButton(onClick = onDelete, enabled = enabled) {
              Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.backup_delete_cd), tint = MaterialTheme.colorScheme.error)
            }
          }
        }
      } else {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(onClick = onRestore, enabled = enabled, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Restore, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.backup_item_restore))
          }
          Spacer(Modifier.weight(1f))
          IconButton(onClick = onShare, enabled = enabled) {
            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.backup_share_cd))
          }
          IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.backup_delete_cd), tint = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }
}

@Composable
private fun BackupProgressDialog(
  state: BackupUiState,
  onClose: () -> Unit,
  onForceRestore: () -> Unit,
) {
  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
  Dialog(
    onDismissRequest = { if (state.progressFinished) onClose() },
    properties = DialogProperties(
      dismissOnBackPress = state.progressFinished,
      dismissOnClickOutside = state.progressFinished,
      usePlatformDefaultWidth = false,
    ),
  ) {
    StyledDialogSurface(compact = compact, accent = if (state.progressError == null) Color(0xFF38BDF8) else MaterialTheme.colorScheme.error) {
      Column(
        modifier = Modifier.padding(if (compact) 16.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          if (state.progressTitle.isBlank()) stringResource(R.string.backup_progress_default_title) else state.progressTitle,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        if (state.progressText.isNotBlank()) {
          Text(state.progressText, style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
          progress = state.progressPercent.coerceIn(0, 100) / 100f,
          modifier = Modifier.fillMaxWidth(),
        )
        Text(
          "${state.progressPercent.coerceIn(0, 100)}%",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
        if (state.progressError != null) {
          StyledMessageCard(text = state.progressError, accent = MaterialTheme.colorScheme.error)
        }
        if (state.progressFinished) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (state.forceRestoreAvailable) {
              OutlinedButton(onClick = onClose, shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.backup_back)) }
              Spacer(Modifier.width(8.dp))
              Button(
                onClick = onForceRestore,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
              ) { Text(stringResource(R.string.backup_restore_anyway)) }
            } else {
              TextButton(onClick = onClose) { Text(stringResource(R.string.backup_close)) }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BackupConfirmDialog(
  title: String,
  text: String,
  confirmText: String,
  destructive: Boolean,
  dismissText: String? = null,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
  val accent = if (destructive) MaterialTheme.colorScheme.error else Color(0xFF38BDF8)
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    StyledDialogSurface(compact = compact, accent = accent) {
      Column(
        modifier = Modifier.padding(if (compact) 16.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        StyledMessageCard(text = text, accent = accent)
        val cancelText = dismissText ?: stringResource(R.string.backup_cancel)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(16.dp)) { Text(cancelText) }
          Spacer(Modifier.width(8.dp))
          Button(
            onClick = onConfirm,
            shape = RoundedCornerShape(16.dp),
            colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
          ) { Text(confirmText) }
        }
      }
    }
  }
}

@Composable
private fun StyledDialogSurface(
  compact: Boolean,
  accent: Color,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .widthIn(max = 560.dp)
      .padding(if (compact) 18.dp else 24.dp),
    shape = RoundedCornerShape(if (compact) 26.dp else 30.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
    tonalElevation = 0.dp,
    shadowElevation = 16.dp,
  ) {
    Box(
      modifier = Modifier.background(
        Brush.linearGradient(
          listOf(
            accent.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
          )
        )
      )
    ) {
      content()
    }
  }
}

@Composable
private fun StyledDialogSection(
  modifier: Modifier = Modifier,
  accent: Color,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
  ) {
    Box(modifier = Modifier.padding(14.dp)) {
      content()
    }
  }
}

@Composable
private fun StyledMessageCard(
  text: String,
  accent: Color,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = accent.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
  ) {
    Text(
      text,
      modifier = Modifier.padding(12.dp),
      style = MaterialTheme.typography.bodySmall,
      color = if (accent == MaterialTheme.colorScheme.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
    )
  }
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
