package com.android.zdtd.service.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.R
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private data class ProcRow(
  val name: String,
  val agg: ApiModels.ProcAgg,
  val order: Int,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(uiStateFlow: StateFlow<UiState>, actions: ZdtdActions) {
  val listState = rememberLazyListState()

  // Collect ONLY what Stats needs, and only while Stats is visible.
  val rep by remember(uiStateFlow) {
    uiStateFlow.map { it.status }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = null)

  val daemonOnline by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonOnline }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val device by remember(uiStateFlow) {
    uiStateFlow.map { it.device }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = UiState().device)

  // Optional: freeze updates while the user is actively scrolling to avoid visual jitter.
  var stableRep by remember { mutableStateOf<ApiModels.StatusReport?>(null) }
  LaunchedEffect(rep, listState.isScrollInProgress) {
    if (!listState.isScrollInProgress) stableRep = rep
  }
  val showRep = if (listState.isScrollInProgress) (stableRep ?: rep) else rep

  val totals by remember(showRep) { derivedStateOf { ApiModels.computeTotals(showRep) } }

  val cpuTotalRaw = totals.cpuPercent.coerceAtLeast(0.0)
  val cpuTotalShown = cpuTotalRaw.coerceIn(0.0, 100.0)
  val cpuProgress = (cpuTotalShown / 100.0).toFloat().coerceIn(0f, 1f)

  val totalRamMb = device.totalRamMb?.toDouble()?.takeIf { it > 0 }
  val usedMb = totals.rssMb.coerceAtLeast(0.0)
  val usedFrac = if (totalRamMb != null) (usedMb / totalRamMb).toFloat().coerceIn(0f, 1f) else null
  val freeMb = if (totalRamMb != null) (totalRamMb - usedMb).coerceAtLeast(0.0) else null

  val rows by remember(showRep) {
    derivedStateOf {
      listOf(
        ProcRow("zdt-d", showRep?.zdtd ?: ApiModels.ProcAgg(), 0),
        ProcRow("zapret", showRep?.zapret ?: ApiModels.ProcAgg(), 1),
        ProcRow("zapret2", showRep?.zapret2 ?: ApiModels.ProcAgg(), 2),
        ProcRow("byedpi", showRep?.byedpi ?: ApiModels.ProcAgg(), 3),
        ProcRow("dpitunnel", showRep?.dpitunnel ?: ApiModels.ProcAgg(), 4),
        ProcRow("dnscrypt", showRep?.dnscrypt ?: ApiModels.ProcAgg(), 5),
        ProcRow("sing-box", showRep?.singBox ?: ApiModels.ProcAgg(), 6),
        ProcRow("WireProxy", showRep?.wireProxy ?: ApiModels.ProcAgg(), 7),
        ProcRow("Tor", showRep?.tor ?: ApiModels.ProcAgg(), 8),
        ProcRow("OpenVPN", showRep?.openVpn ?: ApiModels.ProcAgg(), 9),
        ProcRow("tun2socks", showRep?.tun2Socks ?: ApiModels.ProcAgg(), 10),
        ProcRow("opera-proxy", showRep?.opera?.opera ?: ApiModels.ProcAgg(), 11),
        ProcRow("t2s", showRep?.t2s ?: ApiModels.ProcAgg(), 12),
        ProcRow("opera-byedpi", showRep?.opera?.byedpi ?: ApiModels.ProcAgg(), 13),
      ).sortedWith(
        compareByDescending<ProcRow> { it.agg.count > 0 }
          .thenByDescending { it.agg.cpuPercent }
          .thenByDescending { it.agg.rssMb }
          .thenBy { it.order }
      )
    }
  }

  val runningCount by remember(rows) { derivedStateOf { rows.count { it.agg.count > 0 } } }

  val cpuTitle = stringResource(R.string.stats_cpu_title)
  val isNarrowWidth = rememberIsNarrowWidth()
  val isShortHeight = rememberIsShortHeight()
  val compactScreen = isNarrowWidth || isShortHeight
  val listPadding = if (compactScreen) 12.dp else 16.dp
  val sectionGap = if (compactScreen) 10.dp else 12.dp
  val cpuUnknown = stringResource(R.string.stats_unknown_cpu)
  val memTitle = stringResource(R.string.stats_memory_title)
  val cpuLabel = stringResource(R.string.stats_cpu_label)
  val ramLabel = stringResource(R.string.stats_ram_label)
  val runningLower = stringResource(R.string.stats_running_lower)
  val stoppedLower = stringResource(R.string.stats_stopped_lower)

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = listState,
    contentPadding = PaddingValues(horizontal = listPadding, vertical = if (compactScreen) 12.dp else 16.dp),
    verticalArrangement = Arrangement.spacedBy(sectionGap),
  ) {
    item {
      StatusCard(
        daemonOnline = daemonOnline,
        runningServices = runningCount,
        compact = compactScreen,
      )
    }

    item {
      if (compactScreen) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
          MetricCard(
            modifier = Modifier.fillMaxWidth(),
            compact = compactScreen,
            icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
            title = cpuTitle,
            subtitle = device.cpuName?.takeIf { it.isNotBlank() } ?: cpuUnknown,
            value = "${fmtPct(cpuTotalShown)}%",
            progress = cpuProgress,
            footnote = if (cpuTotalRaw > 100.0) {
              stringResource(R.string.stats_clamped_from, fmtPct(cpuTotalRaw))
            } else null,
          )

          MetricCard(
            modifier = Modifier.fillMaxWidth(),
            compact = compactScreen,
            icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
            title = memTitle,
            subtitle = if (totalRamMb != null) {
              stringResource(R.string.stats_total_fmt, mbToHuman(totalRamMb))
            } else {
              stringResource(R.string.stats_total_unknown)
            },
            value = mbToHuman(usedMb),
            progress = usedFrac,
            footnote = if (totalRamMb != null) {
              stringResource(R.string.stats_free_fmt, mbToHuman(freeMb ?: 0.0))
            } else null,
          )
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
          MetricCard(
            modifier = Modifier.weight(1f),
            compact = compactScreen,
            icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
            title = cpuTitle,
            subtitle = device.cpuName?.takeIf { it.isNotBlank() } ?: cpuUnknown,
            value = "${fmtPct(cpuTotalShown)}%",
            progress = cpuProgress,
            footnote = if (cpuTotalRaw > 100.0) {
              stringResource(R.string.stats_clamped_from, fmtPct(cpuTotalRaw))
            } else null,
          )

          MetricCard(
            modifier = Modifier.weight(1f),
            compact = compactScreen,
            icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
            title = memTitle,
            subtitle = if (totalRamMb != null) {
              stringResource(R.string.stats_total_fmt, mbToHuman(totalRamMb))
            } else {
              stringResource(R.string.stats_total_unknown)
            },
            value = mbToHuman(usedMb),
            progress = usedFrac,
            footnote = if (totalRamMb != null) {
              stringResource(R.string.stats_free_fmt, mbToHuman(freeMb ?: 0.0))
            } else null,
          )
        }
      }
    }

    item {
      SectionHeader(
        title = stringResource(R.string.stats_processes_title),
        trailing = if (daemonOnline) {
          stringResource(R.string.stats_running_count, runningCount)
        } else {
          stringResource(R.string.stats_offline)
        },
        compact = compactScreen,
      )
    }

    val enablePlacementAnimations = !listState.isScrollInProgress

    items(
      items = rows,
      key = { it.name },
      contentType = { "proc" },
    ) { row ->
      ProcCard(
        modifier = if (enablePlacementAnimations) Modifier.animateItemPlacement() else Modifier,
        name = row.name,
        agg = row.agg,
        totalRamMb = totalRamMb,
        cpuLabel = cpuLabel,
        ramLabel = ramLabel,
        runningLower = runningLower,
        stoppedLower = stoppedLower,
        compact = compactScreen,
      )
    }

    item { Spacer(Modifier.height(12.dp)) }
  }
}

@Composable
private fun StatusCard(daemonOnline: Boolean, runningServices: Int, compact: Boolean) {
  Surface(
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(Modifier.padding(if (compact) 12.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
      if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Text(
            stringResource(R.string.stats_daemon_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          StatusPill(
            text = if (daemonOnline) stringResource(R.string.stats_online_upper) else stringResource(R.string.stats_offline_upper),
            good = daemonOnline,
          )
        }
      } else {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            stringResource(R.string.stats_daemon_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          StatusPill(
            text = if (daemonOnline) stringResource(R.string.stats_online_upper) else stringResource(R.string.stats_offline_upper),
            good = daemonOnline,
          )
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
        Text(
          text = if (daemonOnline) {
            stringResource(R.string.stats_running_services, runningServices)
          } else {
            stringResource(R.string.stats_no_connection)
          },
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null, compact: Boolean = false) {
  if (compact) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      if (!trailing.isNullOrBlank()) {
        Text(trailing, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
      }
    }
  } else {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      if (!trailing.isNullOrBlank()) {
        Text(trailing, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
      }
    }
  }
}

@Composable
private fun MetricCard(
  modifier: Modifier,
  icon: @Composable () -> Unit,
  title: String,
  subtitle: String,
  value: String,
  progress: Float?,
  footnote: String? = null,
  compact: Boolean = false,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(Modifier.padding(if (compact) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon()
        Column(Modifier.weight(1f)) {
          Text(title, fontWeight = FontWeight.SemiBold)
          Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 2)
        }
      }

      Text(value, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

      SimpleBar(progress = progress)

      if (!footnote.isNullOrBlank()) {
        Text(footnote, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      }
    }
  }
}


@Composable
private fun MetricChip(
  modifier: Modifier,
  icon: @Composable () -> Unit,
  label: String,
  value: String,
  progress: Float?,
  muted: Boolean,
  compact: Boolean = false,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.06f else 0.08f),
  ) {
    Column(Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 8.dp else 9.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(
          label,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.55f else 0.78f),
          style = MaterialTheme.typography.labelLarge,
        )
      }
      Text(
        value,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.65f else 0.92f),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyLarge,
      )
      if (progress != null) {
        SimpleBar(progress = progress, modifier = Modifier.height(if (compact) 4.dp else 5.dp))
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProcCard(
  modifier: Modifier = Modifier,
  name: String,
  agg: ApiModels.ProcAgg,
  totalRamMb: Double?,
  cpuLabel: String,
  ramLabel: String,
  runningLower: String,
  stoppedLower: String,
  compact: Boolean = false,
) {
  val running = agg.count > 0
  val cpuP = (agg.cpuPercent / 100.0).toFloat().coerceIn(0f, 1f)
  val ramP = if (totalRamMb != null) (agg.rssMb / totalRamMb).toFloat().coerceIn(0f, 1f) else null

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (running) 0.78f else 0.46f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(Modifier.padding(if (compact) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
      if (compact) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .height(24.dp)
                .width(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                  if (running) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.95f)
                  else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                )
            )
            Text(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TinyPill(
              text = if (running) runningLower else stoppedLower,
              good = running,
            )
            TinyPill(text = "x${agg.count}", good = running, strong = false)
          }

          Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricChip(
              modifier = Modifier.fillMaxWidth(),
              icon = { Icon(Icons.Outlined.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.85f else 0.55f)) },
              label = cpuLabel,
              value = "${fmtPct(agg.cpuPercent)}%",
              progress = if (running) cpuP else null,
              muted = !running,
              compact = true,
            )
            MetricChip(
              modifier = Modifier.fillMaxWidth(),
              icon = { Icon(Icons.Outlined.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.85f else 0.55f)) },
              label = ramLabel,
              value = mbToHuman(agg.rssMb),
              progress = if (running) ramP else null,
              muted = !running,
              compact = true,
            )
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Box(
              modifier = Modifier
                .height(28.dp)
                .width(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                  if (running) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.95f)
                  else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                )
            )
            Text(name, fontWeight = FontWeight.SemiBold)
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TinyPill(
              text = if (running) runningLower else stoppedLower,
              good = running,
            )
            TinyPill(text = "x${agg.count}", good = running, strong = false)
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          MetricChip(
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Outlined.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.85f else 0.55f)) },
            label = cpuLabel,
            value = "${fmtPct(agg.cpuPercent)}%",
            progress = if (running) cpuP else null,
            muted = !running,
          )
          MetricChip(
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Outlined.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.85f else 0.55f)) },
            label = ramLabel,
            value = mbToHuman(agg.rssMb),
            progress = if (running) ramP else null,
            muted = !running,
          )
        }
      }
    }
  }
}

@Composable
private fun StatusPill(text: String, good: Boolean) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = (if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface)
      .copy(alpha = if (good) 0.22f else 0.92f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun TinyPill(text: String, good: Boolean, strong: Boolean = true) {
  val base = when {
    good && strong -> MaterialTheme.colorScheme.tertiary
    good -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.surface
  }
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = base.copy(alpha = if (good) 0.18f else 0.92f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelMedium,
    )
  }
}

@Composable
private fun SimpleBar(progress: Float?, modifier: Modifier = Modifier) {
  // A lightweight, non-animated progress bar (Material3 LinearProgressIndicator can be animation-heavy).
  val p = (progress ?: 1f).coerceIn(0f, 1f)
  val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
  val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(6.dp)
      .clip(RoundedCornerShape(999.dp))
      .background(track)
  ) {
    Box(
      Modifier
        .fillMaxHeight()
        .fillMaxWidth(p)
        .clip(RoundedCornerShape(999.dp))
        .background(fill)
    )
  }
}

private fun fmtPct(v: Double): String = ((v * 10.0).roundToInt() / 10.0).toString()

private fun mbToHuman(mb: Double): String {
  if (!mb.isFinite() || mb <= 0) return "0 MB"
  val gb = mb / 1024.0
  return when {
    gb >= 10 -> String.format("%.1f GB", gb)
    gb >= 1 -> String.format("%.2f GB", gb)
    else -> "${mb.roundToInt()} MB"
  }
}
