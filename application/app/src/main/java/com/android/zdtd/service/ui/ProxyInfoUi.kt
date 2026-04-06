package com.android.zdtd.service.ui

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ProxyInfoSectionCard(
  enabled: Boolean,
  busy: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onConfigure: () -> Unit,
) {
  var showWarning by remember { mutableStateOf(false) }
  val compactWidth = rememberIsCompactWidth()

  Column(
    modifier = Modifier.fillMaxWidth(),
  ) {
    if (compactWidth) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.settings_proxyinfo_title), style = MaterialTheme.typography.bodyLarge)
          Text(
            stringResource(R.string.settings_proxyinfo_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Switch(
          checked = enabled,
          onCheckedChange = { checked ->
            if (busy) return@Switch
            if (checked && !enabled) showWarning = true else onEnabledChange(checked)
          },
          enabled = !busy,
        )
      }
    } else {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
          Text(stringResource(R.string.settings_proxyinfo_title), style = MaterialTheme.typography.bodyLarge)
          Text(
            stringResource(R.string.settings_proxyinfo_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
          )
        }
        Switch(
          checked = enabled,
          onCheckedChange = { checked ->
            if (busy) return@Switch
            if (checked && !enabled) showWarning = true else onEnabledChange(checked)
          },
          enabled = !busy,
        )
      }
    }

    AnimatedVisibility(visible = enabled) {
      Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        OutlinedButton(
          onClick = onConfigure,
          modifier = Modifier.fillMaxWidth(),
          enabled = !busy,
        ) {
          Text(stringResource(R.string.settings_proxyinfo_configure))
        }
      }
    }
  }

  if (showWarning) {
    AlertDialog(
      onDismissRequest = { showWarning = false },
      title = { Text(stringResource(R.string.settings_proxyinfo_warning_title)) },
      text = { Text(stringResource(R.string.settings_proxyinfo_warning_body)) },
      dismissButton = {
        OutlinedButton(onClick = { showWarning = false }) {
          Text(stringResource(R.string.common_cancel))
        }
      },
      confirmButton = {
        Button(onClick = {
          showWarning = false
          onEnabledChange(true)
        }) {
          Text(stringResource(R.string.settings_proxyinfo_warning_accept))
        }
      },
    )
  }
}

@Composable
fun ProxyInfoAppsDialog(
  initialContent: String,
  saving: Boolean,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val pm = ctx.packageManager
  var query by remember { mutableStateOf("") }
  var selected by remember(initialContent) { mutableStateOf(parsePkgList(initialContent)) }
  val iconCache = remember { mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>() }
  val apps by produceState<List<InstalledApp>>(initialValue = emptyList(), key1 = Unit) {
    value = withContext(Dispatchers.IO) {
      runCatching { loadInstalledApps(pm) }.getOrDefault(emptyList())
    }
  }

  val q = query.trim().lowercase(Locale.ROOT)
  val filtered = remember(apps, q) {
    if (q.isBlank()) apps
    else apps.filter {
      it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
    }
  }
  val selectedApps = remember(apps, selected) {
    val byPkg = apps.associateBy { it.packageName }
    selected.map { pkg -> byPkg[pkg] ?: InstalledApp(pkg, pkg, false) }
      .sortedBy { it.label.lowercase(Locale.ROOT) }
  }
  val notSelectedApps = remember(filtered, selected) { filtered.filter { it.packageName !in selected } }
  val compactWidth = rememberIsCompactWidth()
  val shortHeight = rememberIsShortHeight()

  Dialog(
    onDismissRequest = { if (!saving) onDismiss() },
    properties = DialogProperties(
      dismissOnBackPress = !saving,
      dismissOnClickOutside = !saving,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp, vertical = 20.dp),
      shape = MaterialTheme.shapes.extraLarge,
      tonalElevation = 6.dp,
      shadowElevation = 12.dp,
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 16.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.settings_proxyinfo_apps_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
              text = stringResource(R.string.settings_proxyinfo_apps_body),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }
          Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
          ) {
            IconButton(onClick = onDismiss, enabled = !saving) {
              Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
            }
          }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(stringResource(R.string.settings_proxyinfo_search_hint)) },
          enabled = !saving,
        )

        Spacer(Modifier.height(12.dp))

        if (apps.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(240.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator()
          }
        } else {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = if (shortHeight) 220.dp else 300.dp, max = if (shortHeight) 420.dp else 560.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            item {
              Text(
                text = stringResource(R.string.settings_proxyinfo_selected_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
              )
            }
            if (selectedApps.isEmpty()) {
              item {
                Text(
                  text = stringResource(R.string.settings_proxyinfo_empty),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                )
              }
            } else {
              items(selectedApps, key = { "sel:" + it.packageName }) { app ->
                ProxyInfoAppRow(
                  app = app,
                  selected = true,
                  compactWidth = compactWidth,
                  iconCache = iconCache,
                  enabled = !saving,
                  onToggle = { checked ->
                    selected = if (checked) selected + app.packageName else selected - app.packageName
                  },
                )
              }
            }

            item {
              Spacer(Modifier.height(8.dp))
              HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
              Spacer(Modifier.height(8.dp))
              Text(
                text = stringResource(R.string.settings_proxyinfo_all_apps_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
              )
            }

            items(notSelectedApps, key = { "all:" + it.packageName }) { app ->
              ProxyInfoAppRow(
                app = app,
                selected = false,
                compactWidth = compactWidth,
                iconCache = iconCache,
                enabled = !saving,
                onToggle = { checked ->
                  selected = if (checked) selected + app.packageName else selected - app.packageName
                },
              )
            }
          }
        }

        Spacer(Modifier.height(14.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            enabled = !saving,
          ) {
            Text(stringResource(R.string.common_cancel))
          }
          Button(
            onClick = { onSave(selected.sorted().joinToString("\n")) },
            modifier = Modifier.weight(1f),
            enabled = !saving,
          ) {
            if (saving) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
              )
            } else {
              Text(stringResource(R.string.common_save))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProxyInfoAppRow(
  app: InstalledApp,
  selected: Boolean,
  compactWidth: Boolean,
  iconCache: MutableMap<String, androidx.compose.ui.graphics.ImageBitmap?>,
  enabled: Boolean,
  onToggle: (Boolean) -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = if (selected) {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    } else {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)
    },
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled) { onToggle(!selected) }
        .padding(horizontal = 10.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      AppIcon(packageName = app.packageName, cache = iconCache)
      Spacer(Modifier.width(10.dp))
      Checkbox(
        checked = selected,
        onCheckedChange = { checked -> onToggle(checked) },
        enabled = enabled,
      )
      Spacer(Modifier.width(6.dp))
      Column(Modifier.weight(1f)) {
        Text(app.label, maxLines = if (compactWidth) 2 else 1, overflow = TextOverflow.Ellipsis)
        Text(
          app.packageName,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
          maxLines = if (compactWidth) 2 else 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (app.isSystem) {
        Spacer(Modifier.width(8.dp))
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
          Text(
            text = stringResource(R.string.app_picker_system_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}
