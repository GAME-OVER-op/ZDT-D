package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
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
import com.android.zdtd.service.ProgramReleaseUi
import com.android.zdtd.service.ProgramUpdateItemUi
import com.android.zdtd.service.ProgramUpdatesUiState
import com.android.zdtd.service.R
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
  var picking by remember { mutableStateOf<String?>(null) }

  fun enabledFor(item: ProgramUpdateItemUi): Boolean {
    return !serviceRunning && !item.updating && !item.checking && !state.stoppingService
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnClickOutside = true,
      usePlatformDefaultWidth = false,
    ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing),
      contentAlignment = if (landscape) Alignment.CenterStart else Alignment.Center,
    ) {
      val accent = Color(0xFF38BDF8)
      val accent2 = Color(0xFFA78BFA)
      Surface(
        modifier = if (landscape) {
          Modifier
            .fillMaxHeight(0.94f)
            .fillMaxWidth(0.76f)
            .widthIn(max = 920.dp)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp)
        } else {
          Modifier
            .fillMaxWidth()
            .fillMaxHeight(if (compact) 0.88f else 0.82f)
            .widthIn(max = 660.dp)
            .padding(if (compact) 12.dp else 16.dp)
        },
        shape = RoundedCornerShape(if (compact) 26.dp else 32.dp),
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
                accent.copy(alpha = 0.14f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
                accent2.copy(alpha = 0.12f),
              )
            )
          )
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            ProgramUpdatesHeader(onRefresh = actions::resetProgramUpdatesUi)

            LazyColumn(
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              if (serviceRunning) {
                item(key = "service_running") {
                  ServiceRunningUpdateCard(
                    stopping = state.stoppingService,
                    onStopAndCheck = actions::stopServiceForProgramUpdatesAndCheck,
                  )
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
  }

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

@Composable
private fun ProgramUpdatesHeader(onRefresh: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      modifier = Modifier.size(42.dp),
      shape = CircleShape,
      color = Color(0xFF38BDF8).copy(alpha = 0.18f),
      border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.34f)),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null, tint = Color(0xFF7DD3FC))
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(
        stringResource(R.string.program_updates_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        stringResource(R.string.program_updates_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconButton(onClick = onRefresh) {
      Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.program_updates_reset_cd))
    }
  }
}

@Composable
private fun ServiceRunningUpdateCard(
  stopping: Boolean,
  onStopAndCheck: () -> Unit,
) {
  val accent = Color(0xFFF59E0B)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = accent.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        stringResource(R.string.program_updates_service_running_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        stringResource(R.string.program_updates_service_running_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
      )
      Button(
        onClick = onStopAndCheck,
        enabled = !stopping,
        shape = RoundedCornerShape(18.dp),
      ) {
        Text(if (stopping) stringResource(R.string.program_updates_stopping) else stringResource(R.string.program_updates_stop_and_check))
      }
    }
  }
}

@Composable
private fun ProgramUpdateCard(
  modifier: Modifier = Modifier,
  item: ProgramUpdateItemUi,
  enabled: Boolean,
  onCheck: () -> Unit,
  onUpdate: () -> Unit,
  onPickVersion: () -> Unit,
) {
  val compact = rememberIsCompactWidth()
  val accent = if (item.updateAvailable) Color(0xFF22C55E) else Color(0xFF38BDF8)
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
    border = BorderStroke(1.dp, accent.copy(alpha = if (item.updateAvailable) 0.38f else 0.18f)),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
          modifier = Modifier.size(36.dp),
          shape = CircleShape,
          color = accent.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (item.checking || item.updating) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            }
          }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(
            item.titleRes?.let { stringResource(it) } ?: item.title,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            stringResource(
              R.string.program_updates_installed_latest_fmt,
              item.installedVersion ?: "—",
              item.latestVersion ?: "—",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
            maxLines = 2,
          )
        }
        IconButton(onClick = onPickVersion, enabled = enabled) {
          Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.program_updates_select_version_cd))
        }
      }

      val target = item.selectedVersion ?: item.latestVersion
      if (target != null) {
        val suffix = if (item.selectedVersion != null) {
          stringResource(R.string.program_updates_target_selected_suffix)
        } else {
          stringResource(R.string.program_updates_target_latest_suffix)
        }
        ProgramUpdateInfoPill(
          text = stringResource(R.string.program_updates_target_fmt, target, suffix),
          accent = accent,
        )
      }

      if (item.warningText != null) {
        ProgramUpdateMessage(text = item.warningText, accent = Color(0xFFF59E0B))
      }
      if (item.statusText.isNotBlank()) {
        ProgramUpdateMessage(text = item.statusText, accent = Color(0xFF38BDF8))
      }
      if (item.errorText != null) {
        ProgramUpdateMessage(text = item.errorText, accent = MaterialTheme.colorScheme.error)
      }

      if (item.checking || item.updating) {
        LinearProgressIndicator(
          progress = item.progressPercent.coerceIn(0, 100) / 100f,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      if (compact) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(onClick = onCheck, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text(if (item.checking) stringResource(R.string.program_updates_checking) else stringResource(R.string.program_updates_check))
          }
          Button(
            onClick = onUpdate,
            enabled = enabled && item.updateAvailable,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
          ) {
            Text(if (item.updating) stringResource(R.string.program_updates_updating) else stringResource(R.string.program_updates_update))
          }
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(onClick = onCheck, enabled = enabled, shape = RoundedCornerShape(16.dp)) {
            Text(if (item.checking) stringResource(R.string.program_updates_checking) else stringResource(R.string.program_updates_check))
          }
          Button(
            onClick = onUpdate,
            enabled = enabled && item.updateAvailable,
            shape = RoundedCornerShape(16.dp),
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
    ProgramUpdateWarningDialog(
      title = stringResource(R.string.program_updates_warning_title),
      text = stringResource(R.string.program_updates_warning_text_fmt, pending!!.version, minVersion),
      onDismiss = { showWarn = false; pending = null },
      onConfirm = {
        val p = pending
        showWarn = false
        pending = null
        if (p != null) onSelectRelease(p.version, p.downloadUrl)
      },
    )
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false),
  ) {
    StyledUpdateDialogSurface(compact = compact, accent = Color(0xFFA78BFA)) {
      Column(
        modifier = Modifier.padding(if (compact) 14.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = Color(0xFFA78BFA).copy(alpha = 0.16f),
            border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.30f)),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Color(0xFFD8B4FE))
            }
          }
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
              stringResource(R.string.program_updates_choose_version_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
              maxLines = 2,
            )
          }
          IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.program_updates_refresh_cd))
          }
        }

        if (stateItem.releasesLoading) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (stateItem.releasesError != null) {
          ProgramUpdateMessage(text = stateItem.releasesError, accent = MaterialTheme.colorScheme.error)
        }

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = if (compact) 330.dp else 430.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item {
            ReleaseCard(
              title = stringResource(R.string.program_updates_latest_auto_title),
              subtitle = stringResource(R.string.program_updates_latest_auto_desc),
              selected = stateItem.selectedVersion == null,
              onSelect = onSelectLatest,
            )
          }

          items(stateItem.releases, key = { it.version }, contentType = { "program_release" }) { release ->
            val isSelected = stateItem.selectedVersion == release.version
            val date = release.publishedAt.take(10)
            ReleaseCard(
              title = release.version,
              subtitle = date,
              selected = isSelected,
              onSelect = {
                if (isBelowMin(release.version, minVersion)) {
                  pending = release
                  showWarn = true
                } else {
                  onSelectRelease(release.version, release.downloadUrl)
                }
              },
            )
          }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_close)) }
        }
      }
    }
  }
}

@Composable
private fun ReleaseCard(
  title: String,
  subtitle: String,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  val compact = rememberIsCompactWidth()
  val accent = if (selected) Color(0xFF22C55E) else Color(0xFFA78BFA)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
    border = BorderStroke(1.dp, accent.copy(alpha = if (selected) 0.36f else 0.16f)),
  ) {
    if (compact) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column {
          Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
        }
        Button(onClick = onSelect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
          Text(if (selected) stringResource(R.string.program_updates_selected) else stringResource(R.string.program_updates_select))
        }
      }
    } else {
      Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
        }
        Button(onClick = onSelect, shape = RoundedCornerShape(16.dp)) {
          Text(if (selected) stringResource(R.string.program_updates_selected) else stringResource(R.string.program_updates_select))
        }
      }
    }
  }
}

@Composable
private fun ProgramUpdateWarningDialog(
  title: String,
  text: String,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
  val accent = Color(0xFFF59E0B)
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    StyledUpdateDialogSurface(compact = compact, accent = accent) {
      Column(
        modifier = Modifier.padding(if (compact) 16.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ProgramUpdateMessage(text = text, accent = accent)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.backup_cancel)) }
          Spacer(Modifier.width(8.dp))
          Button(onClick = onConfirm, shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.program_updates_continue)) }
        }
      }
    }
  }
}

@Composable
private fun StyledUpdateDialogSurface(
  compact: Boolean,
  accent: Color,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .widthIn(max = 640.dp)
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
          colors = listOf(
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
private fun ProgramUpdateInfoPill(
  text: String,
  accent: Color,
) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = accent.copy(alpha = 0.12f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
  ) {
    Text(
      text,
      modifier = Modifier.padding(start = 10.dp, top = 5.dp, end = 10.dp, bottom = 5.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
      maxLines = 2,
    )
  }
}

@Composable
private fun ProgramUpdateMessage(
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
