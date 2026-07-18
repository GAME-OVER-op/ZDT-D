package com.android.zdtd.service.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
fun StatsScreen(
  uiStateFlow: StateFlow<UiState>,
  actions: ZdtdActions,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
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

  val installedProgramIds by remember(uiStateFlow) {
    uiStateFlow.map { it.programs.map { p -> p.id }.toSet() }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = emptySet())

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

  val rows by remember(showRep, installedProgramIds) {
    derivedStateOf {
      buildList {
        add(ProcRow("zdt-d", showRep?.zdtd ?: ApiModels.ProcAgg(), 0))
        add(ProcRow("Zapret", showRep?.zapret ?: ApiModels.ProcAgg(), 1))
        add(ProcRow("Zapret 2", showRep?.zapret2 ?: ApiModels.ProcAgg(), 2))
        add(ProcRow("ByeDPI", showRep?.byedpi ?: ApiModels.ProcAgg(), 3))
        add(ProcRow("DPITunnel", showRep?.dpitunnel ?: ApiModels.ProcAgg(), 4))
        add(ProcRow("DNSCrypt", showRep?.dnscrypt ?: ApiModels.ProcAgg(), 5))
        add(ProcRow("sing-box", showRep?.singBox ?: ApiModels.ProcAgg(), 6))
        add(ProcRow("hysteria2", showRep?.hysteria2 ?: ApiModels.ProcAgg(), 7))
        add(ProcRow("WireProxy", showRep?.wireProxy ?: ApiModels.ProcAgg(), 8))
        add(ProcRow("Tor", showRep?.tor ?: ApiModels.ProcAgg(), 8))
        add(ProcRow("OpenVPN", showRep?.openVpn ?: ApiModels.ProcAgg(), 9))
        add(ProcRow("Mihomo", showRep?.mihomo ?: ApiModels.ProcAgg(), 10))
        add(ProcRow("mieru", showRep?.mieru ?: ApiModels.ProcAgg(), 11))
        val tgwsAgg = showRep?.tgwsproxy ?: ApiModels.ProcAgg()
        if ("tgwsproxy" in installedProgramIds || tgwsAgg.count > 0) {
          add(ProcRow("Telegram WS Proxy", tgwsAgg, 12))
        }
        add(ProcRow("tun2proxy", showRep?.tun2Proxy ?: ApiModels.ProcAgg(), 13))
        add(ProcRow("AmneziaWG", showRep?.amneziaWg ?: ApiModels.ProcAgg(), 14))
        add(ProcRow("opera-proxy", showRep?.opera?.opera ?: ApiModels.ProcAgg(), 15))
        add(ProcRow("t2s", showRep?.t2s ?: ApiModels.ProcAgg(), 16))
        add(ProcRow("opera-ByeDPI", showRep?.opera?.byedpi ?: ApiModels.ProcAgg(), 17))
      }.sortedWith(
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
  val landscapeControl = rememberUseLandscapeControlLayout()
  val compactScreen = isNarrowWidth || (isShortHeight && !landscapeControl)
  val listPadding = if (compactScreen) 12.dp else if (landscapeControl) 14.dp else 16.dp
  val sectionGap = if (compactScreen) 10.dp else 12.dp
  val cpuUnknown = stringResource(R.string.stats_unknown_cpu)
  val memTitle = stringResource(R.string.stats_memory_title)
  val cpuLabel = stringResource(R.string.stats_cpu_label)
  val ramLabel = stringResource(R.string.stats_ram_label)
  val runningLower = stringResource(R.string.stats_running_lower)
  val stoppedLower = stringResource(R.string.stats_stopped_lower)

  if (landscapeControl) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = listState,
      contentPadding = PaddingValues(
        start = listPadding,
        top = topContentPadding + 12.dp,
        end = listPadding,
        bottom = bottomContentPadding + 12.dp,
      ),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(key = "dashboard") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          StatusCard(
            modifier = Modifier.weight(1f),
            daemonOnline = daemonOnline,
            runningServices = runningCount,
            compact = false,
          )
          MetricCard(
            modifier = Modifier.weight(1f),
            compact = false,
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
            compact = false,
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

      item(key = "process_header") {
        SectionHeader(
          title = stringResource(R.string.stats_processes_title),
          trailing = if (daemonOnline) {
            stringResource(R.string.stats_running_count, runningCount)
          } else {
            stringResource(R.string.stats_offline)
          },
          compact = false,
        )
      }

      items(
        items = rows.chunked(2),
        key = { row -> row.joinToString("|") { it.name } },
        contentType = { "proc_row" },
      ) { row ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          row.forEach { proc ->
            ProcCard(
              modifier = Modifier.weight(1f),
              name = proc.name,
              agg = proc.agg,
              totalRamMb = totalRamMb,
              cpuLabel = cpuLabel,
              ramLabel = ramLabel,
              runningLower = runningLower,
              stoppedLower = stoppedLower,
              compact = false,
            )
          }
          if (row.size == 1) Spacer(Modifier.weight(1f))
        }
      }

      item { Spacer(Modifier.height(12.dp)) }
    }
  } else {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = listState,
      contentPadding = PaddingValues(
        start = listPadding,
        top = topContentPadding + if (compactScreen) 12.dp else 16.dp,
        end = listPadding,
        bottom = bottomContentPadding + if (compactScreen) 12.dp else 16.dp,
      ),
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
          modifier = if (enablePlacementAnimations) Modifier.animateItem() else Modifier,
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
}

@Composable
private fun StatusCard(
  modifier: Modifier = Modifier,
  daemonOnline: Boolean,
  runningServices: Int,
  compact: Boolean,
) {
  val shape = RoundedCornerShape(if (compact) 24.dp else 28.dp)
  val accent = if (daemonOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
  val title = stringResource(R.string.stats_daemon_title)
  val status = if (daemonOnline) stringResource(R.string.stats_online_upper) else stringResource(R.string.stats_offline_upper)
  val details = if (daemonOnline) {
    stringResource(R.string.stats_running_services, runningServices)
  } else {
    stringResource(R.string.stats_no_connection)
  }

  Box(
    modifier = modifier
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            accent.copy(alpha = if (daemonOnline) 0.24f else 0.18f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
          )
        )
      )
      .border(1.dp, accent.copy(alpha = if (daemonOnline) 0.46f else 0.34f), shape)
      .padding(if (compact) 14.dp else 16.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(
          modifier = Modifier
            .size(if (compact) 44.dp else 48.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.Outlined.Storage,
            contentDescription = null,
            tint = accent,
          )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            title,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            details,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        StatusPill(text = status, good = daemonOnline)
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null, compact: Boolean = false) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = if (compact) 2.dp else 4.dp),
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
          .height(if (compact) 26.dp else 30.dp)
          .width(4.dp)
          .clip(RoundedCornerShape(999.dp))
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.95f))
      )
      Text(
        title,
        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (!trailing.isNullOrBlank()) {
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Text(
          trailing,
          modifier = Modifier.padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 5.dp else 6.dp),
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
        )
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
  val shape = RoundedCornerShape(if (compact) 22.dp else 26.dp)
  Box(
    modifier = modifier
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
          )
        )
      )
      .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.32f), shape)
      .padding(if (compact) 12.dp else 14.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier = Modifier
            .size(if (compact) 38.dp else 42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center,
        ) {
          icon()
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            title,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      Text(
        value,
        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )

      SimpleBar(progress = progress, modifier = Modifier.height(if (compact) 5.dp else 6.dp))

      if (!footnote.isNullOrBlank()) {
        Text(
          footnote,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
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
  val shape = RoundedCornerShape(if (compact) 16.dp else 18.dp)
  val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (muted) 0.38f else 0.84f)
  Box(
    modifier = modifier
      .clip(shape)
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.045f else 0.07f))
      .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.06f else 0.09f), shape)
      .padding(horizontal = if (compact) 9.dp else 10.dp, vertical = if (compact) 8.dp else 9.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
          modifier = Modifier
            .size(if (compact) 22.dp else 24.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.10f)),
          contentAlignment = Alignment.Center,
        ) {
          icon()
        }
        Text(
          label,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.52f else 0.74f),
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        value,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (muted) 0.62f else 0.92f),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
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
  val accent = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
  val shape = RoundedCornerShape(if (compact) 22.dp else 26.dp)

  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            accent.copy(alpha = if (running) 0.17f else 0.08f),
            MaterialTheme.colorScheme.surface.copy(alpha = if (running) 0.68f else 0.48f),
            MaterialTheme.colorScheme.surface.copy(alpha = if (running) 0.42f else 0.34f),
          )
        )
      )
      .border(1.dp, accent.copy(alpha = if (running) 0.34f else 0.13f), shape)
      .padding(if (compact) 11.dp else 13.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 11.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(if (compact) 38.dp else 42.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (running) 0.17f else 0.08f)),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier
              .size(if (running) 12.dp else 10.dp)
              .clip(CircleShape)
              .background(accent.copy(alpha = if (running) 0.98f else 0.35f))
          )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
          Text(
            name,
            fontWeight = FontWeight.SemiBold,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            TinyPill(
              text = if (running) runningLower else stoppedLower,
              good = running,
            )
            TinyPill(text = "x${agg.count}", good = running, strong = false)
          }
        }
      }

      if (compact) {
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
      } else {
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
  val base = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = base.copy(alpha = if (good) 0.18f else 0.14f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(base)
      )
      Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = base,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun TinyPill(text: String, good: Boolean, strong: Boolean = true) {
  val base = when {
    good && strong -> MaterialTheme.colorScheme.primary
    good -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurface
  }
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = base.copy(alpha = if (good) 0.15f else 0.08f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(base.copy(alpha = if (good) 1f else 0.38f))
      )
      Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = base.copy(alpha = if (good) 1f else 0.72f),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
      )
    }
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
