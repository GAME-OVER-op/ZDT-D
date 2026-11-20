package com.android.zdtd.service.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.components.ToolReleaseUi

/** GitHub release picker for a single tool card. Only shown for tools with official Android binaries. */
@Composable
internal fun ToolReleasesDialog(
  componentId: String,
  title: String,
  actions: ZdtdActions,
  onDismiss: () -> Unit,
) {
  val uriHandler = LocalUriHandler.current
  val releases = remember(componentId) { mutableStateOf<List<ToolReleaseUi>>(emptyList()) }
  val loading = remember(componentId) { mutableStateOf(true) }
  val error = remember(componentId) { mutableStateOf<String?>(null) }
  val expanded = remember(componentId) { mutableStateOf<String?>(null) }
  val busy = remember(componentId) { mutableStateOf<String?>(null) }
  val progress = remember(componentId) { mutableStateOf(0) }
  val progressText = remember(componentId) { mutableStateOf("") }

  fun reload() {
    loading.value = true
    error.value = null
    actions.loadToolReleases(componentId) { items, message ->
      loading.value = false
      releases.value = items
      error.value = message
    }
  }

  LaunchedEffect(componentId) { reload() }

  AlertDialog(
    onDismissRequest = { if (busy.value == null) onDismiss() },
    title = { Text("$title — ${stringResource(R.string.tool_releases_title)}") },
    text = {
      Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        when {
          loading.value -> {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          releases.value.isEmpty() -> {
            Text(error.value ?: stringResource(R.string.tool_releases_empty))
          }
          else -> {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              items(releases.value, key = { it.version }) { release ->
                val isBusy = busy.value == release.version
                Card(
                  modifier = Modifier.fillMaxWidth().clickable(enabled = !isBusy) {
                    expanded.value = if (expanded.value == release.version) null else release.version
                  },
                  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                  Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Column(Modifier.weight(1f)) {
                        Text(release.version, style = MaterialTheme.typography.titleSmall)
                        if (release.publishedAt.isNotBlank()) {
                          Text(release.publishedAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                      }
                      if (isBusy) {
                        CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                      } else {
                        Button(onClick = {
                          busy.value = release.version
                          progress.value = 0
                          actions.installToolRelease(
                            componentId = componentId,
                            release = release,
                            onProgress = { pct, text -> progress.value = pct; progressText.value = text },
                            onDone = { ok, message ->
                              busy.value = null
                              if (!ok && message != null) error.value = message
                              if (ok) reload()
                            },
                          )
                        }) { Text(stringResource(R.string.tool_releases_install)) }
                      }
                    }
                    if (isBusy) {
                      LinearProgressIndicator(progress = progress.value / 100f, modifier = Modifier.fillMaxWidth())
                      if (progressText.value.isNotBlank()) {
                        Text(progressText.value, style = MaterialTheme.typography.bodySmall)
                      }
                    }
                    if (release.releaseNotes.isNotBlank()) {
                      Text(
                        stringResource(R.string.tool_releases_notes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                      )
                      Text(
                        release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded.value == release.version) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                    if (release.htmlUrl.isNotBlank()) {
                      TextButton(onClick = { runCatching { uriHandler.openUri(release.htmlUrl) } }) {
                        Text(stringResource(R.string.tool_releases_open_page))
                      }
                    }
                  }
                }
              }
            }
          }
        }
        error.value?.takeIf { releases.value.isEmpty() }?.let {
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { reload() }) { Text(stringResource(R.string.tool_releases_refresh)) }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss, enabled = busy.value == null) {
        Text(stringResource(R.string.backup_close))
      }
    },
  )
}
