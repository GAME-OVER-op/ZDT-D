package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay
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
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)),
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

      AnimatedVisibility(
        visible = mode != NonRootAppRoutingMode.ALL,
        enter = fadeIn(tween(160)) + expandVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)),
      ) {
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

      AnimatedVisibility(
        visible = mode == NonRootAppRoutingMode.ONLY_SELECTED && selectedPackages.isEmpty(),
        enter = fadeIn(tween(140)) + expandVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(100)) + shrinkVertically(animationSpec = tween(160, easing = FastOutSlowInEasing)),
      ) {
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
  var debouncedQuery by remember { mutableStateOf("") }
  var selected by remember(initialSelected) {
    mutableStateOf(initialSelected.filterNot { it == ZDTD_APP_PACKAGE_NAME }.toSet())
  }
  val initialClean = remember(initialSelected) { initialSelected - ZDTD_APP_PACKAGE_NAME }
  val hasChanges = selected != initialClean
  val iconCache = remember { AppIconMemoryCache.map }
  val listState = rememberLazyListState()
  val isCompactWidth = rememberIsCompactWidth()
  val isNarrowWidth = rememberIsNarrowWidth()
  val isShortHeight = rememberIsShortHeight()
  val useCompactHeader = isShortHeight || isNarrowWidth

  LaunchedEffect(Unit) {
    loading = true
    apps = withContext(Dispatchers.IO) {
      runCatching { loadInstalledAppsCached(context.packageManager) }.getOrDefault(emptyList())
    }.filterNot { it.packageName == ZDTD_APP_PACKAGE_NAME }
    loading = false
  }

  LaunchedEffect(query) {
    delay(180)
    debouncedQuery = query.trim()
  }

  val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }
  val selectedAppsAll = remember(appsByPackage, selected) {
    selected.map { pkg -> appsByPackage[pkg] ?: InstalledApp(pkg, pkg, false) }
      .sortedBy { it.sortKey }
  }
  val selectedApps = remember(selectedAppsAll, debouncedQuery) {
    if (debouncedQuery.isBlank()) selectedAppsAll else selectedAppsAll.filter { app -> matchesRoutingSearch(app, debouncedQuery) }
  }
  val notSelectedApps = remember(apps, selected, debouncedQuery) {
    apps.asSequence()
      .filter { it.packageName !in selected }
      .filter { debouncedQuery.isBlank() || matchesRoutingSearch(it, debouncedQuery) }
      .toList()
  }
  val showSelectedSection = debouncedQuery.isBlank() || selectedApps.isNotEmpty()

  LaunchedEffect(debouncedQuery, loading) {
    if (!loading) runCatching { listState.animateScrollToItem(0) }
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
      if (useCompactHeader) {
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
          Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
              Icon(Icons.Default.Close, contentDescription = stringResource(R.string.app_picker_cancel))
            }
          }
          Surface(
            shape = CircleShape,
            color = if (hasChanges) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
          ) {
            IconButton(onClick = { onSave(selected) }, enabled = hasChanges, modifier = Modifier.size(40.dp)) {
              Icon(Icons.Default.Check, contentDescription = stringResource(R.string.app_picker_save), tint = MaterialTheme.colorScheme.onPrimary)
            }
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            stringResource(R.string.non_root_app_routing_picker_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.width(12.dp))
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_picker_cancel)) }
          Button(onClick = { onSave(selected) }, enabled = hasChanges) { Text(stringResource(R.string.app_picker_save)) }
        }
      }

      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.app_picker_search)) },
      )

      Crossfade(targetState = loading) { isLoading ->
        if (isLoading) {
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = if (isShortHeight) 220.dp else 280.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.32f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              StableLinearProgressIndicator(visible = true)
              Text(
                stringResource(R.string.app_picker_loading_apps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
              )
            }
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = if (isShortHeight) 220.dp else 280.dp, max = if (isShortHeight) 420.dp else 620.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            item(key = "selected_section") {
              AnimatedVisibility(
                visible = showSelectedSection,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
              ) {
                Column(Modifier.fillMaxWidth()) {
                  Text(
                    stringResource(R.string.app_picker_selected_header, selectedApps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                  )
                  Spacer(Modifier.height(4.dp))
                  if (selectedApps.isEmpty()) {
                    Text(
                      stringResource(R.string.app_picker_none),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                    )
                  }
                }
              }
            }

            items(selectedApps, key = { "sel:" + it.packageName }, contentType = { "routing_app_selected" }) { app ->
              AppPickerRow(
                app = app,
                selected = true,
                compactWidth = isCompactWidth,
                iconCache = iconCache,
                enabled = true,
                reason = null,
                onToggle = { selected = selected - app.packageName },
              )
            }

            item(key = "all_apps_header") {
              Column(Modifier.fillMaxWidth()) {
                if (showSelectedSection || selectedApps.isNotEmpty()) {
                  Spacer(Modifier.height(10.dp))
                  Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                  Spacer(Modifier.height(10.dp))
                }
                Text(
                  stringResource(R.string.app_picker_all_apps_title),
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                  stringResource(R.string.app_picker_all_apps_hint),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
                Spacer(Modifier.height(6.dp))
              }
            }

            if (notSelectedApps.isEmpty()) {
              item(key = "available_empty") {
                Text(
                  stringResource(if (debouncedQuery.isBlank()) R.string.app_picker_none else R.string.app_picker_no_matches),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
              }
            } else {
              items(notSelectedApps, key = { "all:" + it.packageName }, contentType = { "routing_app_available" }) { app ->
                AppPickerRow(
                  app = app,
                  selected = false,
                  compactWidth = isCompactWidth,
                  iconCache = iconCache,
                  enabled = true,
                  reason = null,
                  onToggle = { selected = selected + app.packageName },
                )
              }
            }

            item { Spacer(Modifier.height(30.dp)) }
          }
        }
      }
    }
  }
}

private fun matchesRoutingSearch(app: InstalledApp, query: String): Boolean {
  val normalized = query.trim().lowercase(Locale.ROOT)
  if (normalized.isBlank()) return true
  return app.label.lowercase(Locale.ROOT).contains(normalized) ||
    app.packageName.lowercase(Locale.ROOT).contains(normalized)
}

