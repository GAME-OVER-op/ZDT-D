package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.NonRootAppRoutingMode
import com.android.zdtd.service.NonRootVpnState
import com.android.zdtd.service.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NonRootAppRoutingSection(
  mode: NonRootAppRoutingMode,
  selectedPackages: Set<String>,
  vpnState: NonRootVpnState,
  onModeChange: (NonRootAppRoutingMode) -> Unit,
  onPackagesChange: (Set<String>) -> Unit,
  onRestartVpn: () -> Unit,
) {
  var showPicker by remember { mutableStateOf(false) }
  var showRestartPrompt by remember { mutableStateOf(false) }
  val vpnActive = vpnState == NonRootVpnState.RUNNING ||
    vpnState == NonRootVpnState.STARTING || vpnState == NonRootVpnState.STOPPING

  fun routingChanged(nextMode: NonRootAppRoutingMode, packages: Set<String> = selectedPackages) {
    if (vpnActive && (nextMode != NonRootAppRoutingMode.ONLY_SELECTED || packages.isNotEmpty())) {
      showRestartPrompt = true
    }
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        stringResource(R.string.non_root_app_routing_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        stringResource(R.string.non_root_app_routing_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
      )

      RoutingModeChip(
        selected = mode == NonRootAppRoutingMode.ALL,
        title = stringResource(R.string.non_root_app_routing_all),
        body = stringResource(R.string.non_root_app_routing_all_desc),
        onClick = {
          if (mode != NonRootAppRoutingMode.ALL) {
            onModeChange(NonRootAppRoutingMode.ALL)
            routingChanged(NonRootAppRoutingMode.ALL)
          }
        },
      )
      RoutingModeChip(
        selected = mode == NonRootAppRoutingMode.ONLY_SELECTED,
        title = stringResource(R.string.non_root_app_routing_only_selected),
        body = stringResource(R.string.non_root_app_routing_only_selected_desc),
        onClick = {
          if (mode != NonRootAppRoutingMode.ONLY_SELECTED) {
            onModeChange(NonRootAppRoutingMode.ONLY_SELECTED)
            routingChanged(NonRootAppRoutingMode.ONLY_SELECTED)
          }
        },
      )
      RoutingModeChip(
        selected = mode == NonRootAppRoutingMode.EXCLUDE_SELECTED,
        title = stringResource(R.string.non_root_app_routing_exclude_selected),
        body = stringResource(R.string.non_root_app_routing_exclude_selected_desc),
        onClick = {
          if (mode != NonRootAppRoutingMode.EXCLUDE_SELECTED) {
            onModeChange(NonRootAppRoutingMode.EXCLUDE_SELECTED)
            routingChanged(NonRootAppRoutingMode.EXCLUDE_SELECTED)
          }
        },
      )

      if (mode != NonRootAppRoutingMode.ALL) {
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true },
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                stringResource(R.string.non_root_app_routing_select_apps),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                stringResource(R.string.non_root_app_routing_selected_count, selectedPackages.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
              )
            }
            Text(
              stringResource(R.string.app_picker_select),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }

      if (mode == NonRootAppRoutingMode.ONLY_SELECTED && selectedPackages.isEmpty()) {
        Text(
          stringResource(R.string.non_root_app_routing_only_selected_empty_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Text(
        stringResource(R.string.non_root_app_routing_self_excluded),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
      )
    }
  }

  if (showPicker) {
    NonRootRoutingAppPicker(
      initialSelected = selectedPackages,
      onDismiss = { showPicker = false },
      onSave = { packages ->
        showPicker = false
        onPackagesChange(packages)
        routingChanged(mode, packages)
      },
    )
  }

  if (showRestartPrompt) {
    AlertDialog(
      onDismissRequest = { showRestartPrompt = false },
      title = { Text(stringResource(R.string.non_root_app_routing_restart_title)) },
      text = { Text(stringResource(R.string.non_root_app_routing_restart_body)) },
      dismissButton = {
        TextButton(onClick = { showRestartPrompt = false }) {
          Text(stringResource(R.string.non_root_app_routing_restart_later))
        }
      },
      confirmButton = {
        Button(onClick = {
          showRestartPrompt = false
          onRestartVpn()
        }) {
          Text(stringResource(R.string.non_root_app_routing_restart_now))
        }
      },
    )
  }
}

@Composable
private fun RoutingModeChip(
  selected: Boolean,
  title: String,
  body: String,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    label = {
      Column(Modifier.padding(vertical = 3.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
          body,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
        )
      }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NonRootRoutingAppPicker(
  initialSelected: Set<String>,
  onDismiss: () -> Unit,
  onSave: (Set<String>) -> Unit,
) {
  val context = LocalContext.current
  var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var query by remember { mutableStateOf("") }
  var selected by remember(initialSelected) {
    mutableStateOf(initialSelected.filterNot { it == ZDTD_APP_PACKAGE_NAME }.toSet())
  }
  val iconCache = remember { AppIconMemoryCache.map }
  val listState = rememberLazyListState()

  LaunchedEffect(Unit) {
    loading = true
    apps = withContext(Dispatchers.IO) {
      runCatching { loadInstalledAppsCached(context.packageManager) }.getOrDefault(emptyList())
    }.filterNot { it.packageName == ZDTD_APP_PACKAGE_NAME }
    loading = false
  }

  val displayedApps = remember(apps, selected, query) {
    val known = apps.associateBy { it.packageName }
    val stale = selected
      .filterNot { known.containsKey(it) }
      .map { InstalledApp(packageName = it, label = it, isSystem = false) }
    val normalized = query.trim().lowercase(Locale.ROOT)
    (stale + apps)
      .distinctBy { it.packageName }
      .filter {
        normalized.isBlank() ||
          it.label.lowercase(Locale.ROOT).contains(normalized) ||
          it.packageName.lowercase(Locale.ROOT).contains(normalized)
      }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          stringResource(R.string.non_root_app_routing_picker_title),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.Close, contentDescription = stringResource(R.string.app_picker_cancel))
        }
        IconButton(onClick = { onSave(selected) }) {
          Icon(Icons.Default.Check, contentDescription = stringResource(R.string.app_picker_save))
        }
      }

      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.app_picker_search)) },
      )

      Text(
        stringResource(R.string.non_root_app_routing_selected_count, selected.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
      )

      if (loading) {
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp),
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(stringResource(R.string.app_picker_loading_apps))
          }
        }
      } else {
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp, max = 620.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(displayedApps, key = { it.packageName }) { app ->
            NonRootRoutingAppRow(
              app = app,
              selected = app.packageName in selected,
              iconCache = iconCache,
              onToggle = {
                selected = if (app.packageName in selected) selected - app.packageName
                else selected + app.packageName
              },
            )
          }
          item { Spacer(Modifier.height(28.dp)) }
        }
      }
    }
  }
}

@Composable
private fun NonRootRoutingAppRow(
  app: InstalledApp,
  selected: Boolean,
  iconCache: MutableMap<String, ImageBitmap?>,
  onToggle: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle),
    shape = MaterialTheme.shapes.large,
    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(
        modifier = Modifier.size(38.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
      ) {
        AppIcon(app.packageName, iconCache)
      }
      Column(Modifier.weight(1f)) {
        Text(
          app.label,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          app.packageName,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
  }
}
