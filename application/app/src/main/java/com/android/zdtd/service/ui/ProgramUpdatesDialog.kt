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
  val landscape = rememberUseLandscapeControlLayout()
  val compact = !landscape && (rememberIsCompactWidth() || rememberIsShortHeight())
  val contentPadding = if (compact) 12.dp else 16.dp
  var picking by remember { mutableStateOf<String?>(null) } // "zapret" | "zapret2" | "mihomo" | "mieru" | "operaproxy"

  fun enabledFor(item: ProgramUpdateItemUi): Boolean {
    return !serviceRunning && !item.updating && !item.checking && !state.stoppingService
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnClickOutside = true,
      usePlatformDefaultWidth = !landscape,
    ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing),
      contentAlignment = if (landscape) Alignment.CenterStart else Alignment.Center,
    ) {
      Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 8.dp,
        modifier = if (landscape) {
          Modifier
            .fillMaxHeight(0.94f)
            .fillMaxWidth(0.74f)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp)
        } else {
          Modifier
            .fillMaxWidth()
            .fillMaxHeight(if (compact) 0.88f else 0.82f)
            .widthIn(max = 640.dp)
            .padding(if (compact) 12.dp else 16.dp)
        },
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              stringResource(R.string.program_updates_title),
              style = MaterialTheme.typography.titleLarge,
              maxLines = 2,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = actions::resetProgramUpdatesUi) {
              Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.program_updates_reset_cd))
            }
          }

          Text(
            stringResource(R.string.program_updates_desc),
            style = MaterialTheme.typography.bodySmall,
          )

          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (serviceRunning) {
              item(key = "service_running") {
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
                      modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
                    ) {
                      Text(if (state.stoppingService) stringResource(R.string.program_updates_stopping) else stringResource(R.string.program_updates_stop_and_check))
                    }
                  }
                }
              }
            }

            if (landscape) {
              item(key = "updates_row_1") {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  ProgramUpdateCard(
                    modifier = Modifier.weight(1f),
                    item = state.zapret,
                    enabled = enabledFor(state.zapret),
                    onCheck = actions::checkZapretNow,
                    onUpdate = actions::updateZapretNow,
                    onPickVersion = {
                      picking = "zapret"
                      if (state.zapret.releases.isEmpty() && !state.zapret.releasesLoading) actions.loadZapretReleases()
                    },
                  )
                  ProgramUpdateCard(
                    modifier = Modifier.weight(1f),
                    item = state.zapret2,
                    enabled = enabledFor(state.zapret2),
                    onCheck = actions::checkZapret2Now,
                    onUpdate = actions::updateZapret2Now,
                    onPickVersion = {
                      picking = "zapret2"
                      if (state.zapret2.releases.isEmpty() && !state.zapret2.releasesLoading) actions.loadZapret2Releases()
                    },
                  )
                }
              }

              item(key = "updates_row_2") {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  ProgramUpdateCard(
                    modifier = Modifier.weight(1f),
                    item = state.mihomo,
                    enabled = enabledFor(state.mihomo),
                    onCheck = actions::checkMihomoNow,
                    onUpdate = actions::updateMihomoNow,
                    onPickVersion = {
                      picking = "mihomo"
                      if (state.mihomo.releases.isEmpty() && !state.mihomo.releasesLoading) actions.loadMihomoReleases()
                    },
                  )
                  ProgramUpdateCard(
                    modifier = Modifier.weight(1f),
                    item = state.mieru,
                    enabled = enabledFor(state.mieru),
                    onCheck = actions::checkMieruNow,
                    onUpdate = actions::updateMieruNow,
                    onPickVersion = {
                      picking = "mieru"
                      if (state.mieru.releases.isEmpty() && !state.mieru.releasesLoading) actions.loadMieruReleases()
                    },
                  )
                }
              }

              item(key = "updates_row_3") {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  ProgramUpdateCard(
                    modifier = Modifier.weight(1f),
                    item = state.operaProxy,
                    enabled = enabledFor(state.operaProxy),
                    onCheck = actions::checkOperaProxyNow,
                    onUpdate = actions::updateOperaProxyNow,
                    onPickVersion = {
                      picking = "operaproxy"
                      if (state.operaProxy.releases.isEmpty() && !state.operaProxy.releasesLoading) actions.loadOperaProxyReleases()
                    },
                  )
                  Spacer(modifier = Modifier.weight(1f))
                }
              }
            } else {
              item(key = "zapret") {
                ProgramUpdateCard(
                  item = state.zapret,
                  enabled = enabledFor(state.zapret),
                  onCheck = actions::checkZapretNow,
                  onUpdate = actions::updateZapretNow,
                  onPickVersion = {
                    picking = "zapret"
                    if (state.zapret.releases.isEmpty() && !state.zapret.releasesLoading) actions.loadZapretReleases()
                  },
                )
              }
              item(key = "zapret2") {
                ProgramUpdateCard(
                  item = state.zapret2,
                  enabled = enabledFor(state.zapret2),
                  onCheck = actions::checkZapret2Now,
                  onUpdate = actions::updateZapret2Now,
                  onPickVersion = {
                    picking = "zapret2"
                    if (state.zapret2.releases.isEmpty() && !state.zapret2.releasesLoading) actions.loadZapret2Releases()
                  },
                )
              }
              item(key = "mihomo") {
                ProgramUpdateCard(
                  item = state.mihomo,
                  enabled = enabledFor(state.mihomo),
                  onCheck = actions::checkMihomoNow,
                  onUpdate = actions::updateMihomoNow,
                  onPickVersion = {
                    picking = "mihomo"
                    if (state.mihomo.releases.isEmpty() && !state.mihomo.releasesLoading) actions.loadMihomoReleases()
                  },
                )
              }
              item(key = "mieru") {
                ProgramUpdateCard(
                  item = state.mieru,
                  enabled = enabledFor(state.mieru),
                  onCheck = actions::checkMieruNow,
                  onUpdate = actions::updateMieruNow,
                  onPickVersion = {
                    picking = "mieru"
                    if (state.mieru.releases.isEmpty() && !state.mieru.releasesLoading) actions.loadMieruReleases()
                  },
                )
              }
              item(key = "operaproxy") {
                ProgramUpdateCard(
                  item = state.operaProxy,
                  enabled = enabledFor(state.operaProxy),
                  onCheck = actions::checkOperaProxyNow,
                  onUpdate = actions::updateOperaProxyNow,
                  onPickVersion = {
                    picking = "operaproxy"
                    if (state.operaProxy.releases.isEmpty() && !state.operaProxy.releasesLoading) actions.loadOperaProxyReleases()
                  },
                )
              }
            }
          }

          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_close)) }
          }
        }
      }
    }
  }

  // Version picker dialog (separate from the main dialog)
  val pick = picking
  if (pick != null) {
    val item = when (pick) {
      "zapret" -> state.zapret
      "zapret2" -> state.zapret2
      "mihomo" -> state.mihomo
      "mieru" -> state.mieru
      "operaproxy" -> state.operaProxy
      else -> state.zapret
    }
    ReleasePickerDialog(
      title = when (pick) {
        "zapret" -> stringResource(R.string.program_updates_pick_zapret_title)
        "zapret2" -> stringResource(R.string.program_updates_pick_zapret2_title)
        "mihomo" -> stringResource(R.string.program_updates_pick_mihomo_title)
        "mieru" -> stringResource(R.string.program_updates_pick_mieru_title)
        "operaproxy" -> stringResource(R.string.program_updates_pick_operaproxy_title)
        else -> stringResource(R.string.program_updates_pick_zapret_title)
      },
      stateItem = item,
      minVersion = when (pick) {
        "zapret" -> "v71.4"
        "zapret2" -> "v0.8.6"
        else -> "v0.0.0"
      },
      onRefresh = {
        when (pick) {
          "zapret" -> actions.loadZapretReleases()
          "zapret2" -> actions.loadZapret2Releases()
          "mihomo" -> actions.loadMihomoReleases()
          "mieru" -> actions.loadMieruReleases()
          "operaproxy" -> actions.loadOperaProxyReleases()
        }
      },
      onSelectLatest = {
        when (pick) {
          "zapret" -> actions.selectZapretRelease(null, null)
          "zapret2" -> actions.selectZapret2Release(null, null)
          "mihomo" -> actions.selectMihomoRelease(null, null)
          "mieru" -> actions.selectMieruRelease(null, null)
          "operaproxy" -> actions.selectOperaProxyRelease(null, null)
        }
        picking = null
      },
      onSelectRelease = { v, url ->
        when (pick) {
          "zapret" -> actions.selectZapretRelease(v, url)
          "zapret2" -> actions.selectZapret2Release(v, url)
          "mihomo" -> actions.selectMihomoRelease(v, url)
          "mieru" -> actions.selectMieruRelease(v, url)
          "operaproxy" -> actions.selectOperaProxyRelease(v, url)
        }
        picking = null
      },
      onDismiss = { picking = null },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgramUpdateCard(
  modifier: Modifier = Modifier,
  item: ProgramUpdateItemUi,
  enabled: Boolean,
  onCheck: () -> Unit,
  onUpdate: () -> Unit,
  onPickVersion: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(), modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(item.titleRes?.let { stringResource(it) } ?: item.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
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
      if (rememberIsCompactWidth()) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(onClick = onCheck, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(if (item.checking) stringResource(R.string.program_updates_checking) else stringResource(R.string.program_updates_check))
          }
          Button(
            onClick = onUpdate,
            enabled = enabled && item.updateAvailable,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (item.updating) stringResource(R.string.program_updates_updating) else stringResource(R.string.program_updates_update))
          }
        }
      } else {
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
  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
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
        .widthIn(max = 640.dp)
        .padding(if (compact) 12.dp else 16.dp)
    ) {
      Column(Modifier.padding(if (compact) 12.dp else 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, modifier = Modifier.weight(1f))
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
            .heightIn(max = if (compact) 320.dp else 420.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item {
            Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
              if (compact) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Column {
                    Text(stringResource(R.string.program_updates_latest_auto_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.program_updates_latest_auto_desc), style = MaterialTheme.typography.bodySmall)
                  }
                  Button(onClick = onSelectLatest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.program_updates_select)) }
                }
              } else {
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
          }

          items(stateItem.releases, key = { it.version }, contentType = { "program_release" }) { r ->
            Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
              val isSelected = stateItem.selectedVersion == r.version
              val label = if (isSelected) stringResource(R.string.program_updates_selected) else stringResource(R.string.program_updates_select)
              if (compact) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Column {
                    Text(r.version, fontWeight = FontWeight.SemiBold)
                    val date = r.publishedAt.take(10)
                    if (date.isNotBlank()) Text(date, style = MaterialTheme.typography.bodySmall)
                  }
                  Button(onClick = {
                    if (isBelowMin(r.version, minVersion)) {
                      pending = r
                      showWarn = true
                    } else {
                      onSelectRelease(r.version, r.downloadUrl)
                    }
                  }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
              } else {
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
