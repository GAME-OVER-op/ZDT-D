package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
  installedVersion: String,
  actions: ZdtdActions,
  onDismiss: () -> Unit,
) {
  val uriHandler = LocalUriHandler.current
  var releases by remember(componentId) { mutableStateOf<List<ToolReleaseUi>>(emptyList()) }
  var loading by remember(componentId) { mutableStateOf(true) }
  var error by remember(componentId) { mutableStateOf<String?>(null) }
  var expanded by remember(componentId) { mutableStateOf<String?>(null) }
  var busy by remember(componentId) { mutableStateOf<String?>(null) }
  var progress by remember(componentId) { mutableStateOf(0) }
  var progressText by remember(componentId) { mutableStateOf("") }
  var justInstalled by remember(componentId) { mutableStateOf<String?>(null) }

  fun load(silent: Boolean) {
    if (!silent) {
      loading = true
      error = null
    }
    actions.loadToolReleases(componentId) { items, message ->
      loading = false
      if (items.isNotEmpty() || !silent) releases = items
      error = message
    }
  }

  LaunchedEffect(componentId) { load(false) }

  fun normalize(version: String): String = version.trim().removePrefix("v").removePrefix("V")
  val currentVersion = normalize(installedVersion)

  AlertDialog(
    onDismissRequest = { if (busy == null) onDismiss() },
    title = { Text("$title — ${stringResource(R.string.tool_releases_title)}") },
    text = {
      Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        when {
          loading -> {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          releases.isEmpty() -> {
            Text(error ?: stringResource(R.string.tool_releases_empty))
          }
          else -> {
            Column(
              modifier = Modifier.fillMaxWidth().animateContentSize(),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              // Installation stage: kept separate from the list so the screen
              // never "reloads" and the user always sees what is happening.
              AnimatedVisibility(
                visible = busy != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
              ) {
                Surface(
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                ) {
                  Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                      Text(
                        stringResource(R.string.optional_tools_status_installing),
                        style = MaterialTheme.typography.titleSmall,
                      )
                      Text(busy.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                    LinearProgressIndicator(
                      progress = { (progress / 100f).coerceIn(0f, 1f) },
                      modifier = Modifier.fillMaxWidth(),
                    )
                    if (progressText.isNotBlank()) {
                      Text(progressText, style = MaterialTheme.typography.bodySmall)
                    }
                  }
                }
              }

              AnimatedVisibility(
                visible = justInstalled != null && busy == null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
              ) {
                Surface(
                  modifier = Modifier.fillMaxWidth(),
                  shape = RoundedCornerShape(16.dp),
                  color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                  Text(
                    text = "${stringResource(R.string.tool_releases_installed)}: ${justInstalled.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                  )
                }
              }

              AnimatedVisibility(
                visible = error != null && busy == null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
              ) {
                Text(
                  text = error.orEmpty(),
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodySmall,
                )
              }

              LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(releases, key = { it.version }) { release ->
                  val isBusy = busy == release.version
                  val installed = justInstalled == release.version ||
                    (currentVersion.isNotBlank() && normalize(release.version) == currentVersion)
                  Card(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clickable(enabled = !isBusy) {
                        expanded = if (expanded == release.version) null else release.version
                      },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                  ) {
                    Column(
                      Modifier.fillMaxWidth().padding(12.dp).animateContentSize(),
                      verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                          Text(release.version, style = MaterialTheme.typography.titleSmall)
                          if (release.publishedAt.isNotBlank()) {
                            Text(
                              release.publishedAt.take(10),
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                          }
                        }
                        when {
                          isBusy -> CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                          installed -> Surface(
                            shape = RoundedCornerShape(100.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                          ) {
                            Text(
                              stringResource(R.string.tool_releases_installed),
                              style = MaterialTheme.typography.labelMedium,
                              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                          }
                          else -> Button(onClick = {
                            busy = release.version
                            progress = 0
                            progressText = ""
                            justInstalled = null
                            error = null
                            actions.installToolRelease(
                              componentId = componentId,
                              release = release,
                              onProgress = { pct, text ->
                                progress = pct
                                progressText = text
                              },
                              onDone = { ok, message ->
                                busy = null
                                if (ok) {
                                  justInstalled = release.version
                                  load(silent = true)
                                } else {
                                  error = message
                                }
                              },
                            )
                          }) { Text(stringResource(R.string.tool_releases_install)) }
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
                          maxLines = if (expanded == release.version) Int.MAX_VALUE else 2,
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
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { justInstalled = null; load(false) }) {
        Text(stringResource(R.string.tool_releases_refresh))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss, enabled = busy == null) {
        Text(stringResource(R.string.backup_close))
      }
    },
  )
}
