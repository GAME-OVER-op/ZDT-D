package com.android.zdtd.service.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.R
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale
import kotlin.math.roundToInt

private enum class StatsProcIcon {
  PROGRAM,
  DAEMON,
  D2S,
  T2S,
}

private val LocalStatsLoadingShift = compositionLocalOf { 0f }

private data class ProcRow(
  val key: String,
  val name: String,
  val agg: ApiModels.ProcAgg,
  val programId: String? = null,
  val iconProgramId: String? = programId,
  val configuredEnabled: Boolean = false,
  val icon: StatsProcIcon = StatsProcIcon.PROGRAM,
  val daemon: Boolean = false,
) {
  val running: Boolean get() = agg.count > 0
  val activeGroup: Boolean get() = running || configuredEnabled
}

@Composable
fun StatsScreen(
  uiStateFlow: StateFlow<UiState>,
  @Suppress("UNUSED_PARAMETER") actions: ZdtdActions,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val listState = rememberLazyListState()

  // Collect only the state this tab renders. MainViewModel starts the extra power sampler only
  // while STATS is the active main tab and stops it immediately when the user leaves the page.
  val rep by remember(uiStateFlow) {
    uiStateFlow.map { it.status }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = null)

  val daemonOnline by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonOnline }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val daemonUnavailableVisible by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonUnavailableVisible }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val device by remember(uiStateFlow) {
    uiStateFlow.map { it.device }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = UiState().device)

  val programs by remember(uiStateFlow) {
    uiStateFlow.map { it.programs }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = emptyList())

  val power by remember(uiStateFlow) {
    uiStateFlow.map { it.statsPower }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = UiState().statsPower)

  val initialLoading = rep == null && !daemonUnavailableVisible
  val totals by remember(rep) { derivedStateOf { ApiModels.computeTotals(rep) } }

  val cpuTotalRaw = totals.cpuPercent.coerceAtLeast(0.0)
  val cpuTotalShown = cpuTotalRaw.coerceIn(0.0, 100.0)
  val cpuProgress = (cpuTotalShown / 100.0).toFloat().coerceIn(0f, 1f)

  val totalRamMb = device.totalRamMb?.toDouble()?.takeIf { it > 0.0 }
  val usedMb = totals.rssMb.coerceAtLeast(0.0)
  val usedFrac = totalRamMb?.let { (usedMb / it).toFloat().coerceIn(0f, 1f) }
  val freeMb = totalRamMb?.let { (it - usedMb).coerceAtLeast(0.0) }

  val enabledByProgramId = remember(programs) {
    programs.associate { program ->
      program.id to if (isProfileProgramType(program.type)) {
        program.profiles.any { it.enabled }
      } else {
        program.enabled
      }
    }
  }
  val installedProgramIds = remember(programs) { programs.map { it.id }.toSet() }

  val rows by remember(rep, enabledByProgramId, installedProgramIds) {
    derivedStateOf {
      fun enabled(id: String): Boolean = enabledByProgramId[id] == true
      val r = rep
      buildList {
        add(
          ProcRow(
            key = "zdtd",
            name = "zdt-d",
            agg = r?.zdtd ?: ApiModels.ProcAgg(),
            icon = StatsProcIcon.DAEMON,
            daemon = true,
            configuredEnabled = true,
          )
        )
        add(ProcRow("amneziawg", "AmneziaWG", r?.amneziaWg ?: ApiModels.ProcAgg(), "amneziawg", configuredEnabled = enabled("amneziawg")))
        add(ProcRow("byedpi", "ByeDPI", r?.byedpi ?: ApiModels.ProcAgg(), "byedpi", configuredEnabled = enabled("byedpi")))
        add(
          ProcRow(
            key = "d2s",
            name = "D2S",
            agg = r?.d2s ?: ApiModels.ProcAgg(),
            programId = "dnscrypt",
            iconProgramId = "dnscrypt",
            configuredEnabled = false,
            icon = StatsProcIcon.D2S,
          )
        )
        add(ProcRow("dnscrypt", "DNSCrypt", r?.dnscrypt ?: ApiModels.ProcAgg(), "dnscrypt", configuredEnabled = enabled("dnscrypt")))
        add(ProcRow("dpitunnel", "DPITunnel", r?.dpitunnel ?: ApiModels.ProcAgg(), "dpitunnel", configuredEnabled = enabled("dpitunnel")))
        add(ProcRow("hysteria2", "hysteria2", r?.hysteria2 ?: ApiModels.ProcAgg(), "hysteria2", configuredEnabled = enabled("hysteria2")))
        add(ProcRow("mihomo", "Mihomo", r?.mihomo ?: ApiModels.ProcAgg(), "mihomo", configuredEnabled = enabled("mihomo")))
        add(ProcRow("mieru", "mieru", r?.mieru ?: ApiModels.ProcAgg(), "mieru", configuredEnabled = enabled("mieru")))
        add(ProcRow("openvpn", "OpenVPN", r?.openVpn ?: ApiModels.ProcAgg(), "openvpn", configuredEnabled = enabled("openvpn")))
        add(ProcRow("opera-proxy", "opera-proxy", r?.opera?.opera ?: ApiModels.ProcAgg(), "operaproxy", configuredEnabled = enabled("operaproxy")))
        add(ProcRow("opera-byedpi", "opera-ByeDPI", r?.opera?.byedpi ?: ApiModels.ProcAgg(), "operaproxy", "byedpi", configuredEnabled = enabled("operaproxy")))
        add(ProcRow("sing-box", "sing-box", r?.singBox ?: ApiModels.ProcAgg(), "sing-box", configuredEnabled = enabled("sing-box")))
        add(
          ProcRow(
            key = "t2s",
            name = "t2s",
            agg = r?.t2s ?: ApiModels.ProcAgg(),
            configuredEnabled = (r?.t2s?.count ?: 0) > 0,
            icon = StatsProcIcon.T2S,
          )
        )
        val tgwsAgg = r?.tgwsproxy ?: ApiModels.ProcAgg()
        if ("tgwsproxy" in installedProgramIds || tgwsAgg.count > 0) {
          add(ProcRow("tgwsproxy", "Telegram WS Proxy", tgwsAgg, "tgwsproxy", configuredEnabled = enabled("tgwsproxy")))
        }
        add(ProcRow("tor", "Tor", r?.tor ?: ApiModels.ProcAgg(), "tor", configuredEnabled = enabled("tor")))
        add(
          ProcRow(
            key = "tun2proxy",
            name = "tun2proxy",
            agg = r?.tun2Proxy ?: ApiModels.ProcAgg(),
            programId = "tun2socks",
            iconProgramId = "tun2socks",
            configuredEnabled = false,
          )
        )
        add(ProcRow("wireproxy", "WireProxy", r?.wireProxy ?: ApiModels.ProcAgg(), "wireproxy", configuredEnabled = enabled("wireproxy")))
        add(ProcRow("zapret", "Zapret", r?.zapret ?: ApiModels.ProcAgg(), "nfqws", configuredEnabled = enabled("nfqws")))
        add(ProcRow("zapret2", "Zapret 2", r?.zapret2 ?: ApiModels.ProcAgg(), "nfqws2", configuredEnabled = enabled("nfqws2")))
      }.sortedWith(
        compareBy<ProcRow> {
          when {
            it.daemon -> 0
            it.activeGroup -> 1
            else -> 2
          }
        }.thenBy { it.name.lowercase(Locale.ROOT) }
      )
    }
  }

  val runningTools by remember(rows) {
    derivedStateOf { rows.count { !it.daemon && it.running } }
  }
  val serviceRunning = daemonOnline && ApiModels.isServiceOn(rep)

  val isNarrowWidth = rememberIsNarrowWidth()
  val isShortHeight = rememberIsShortHeight()
  val landscapeControl = rememberUseLandscapeControlLayout()
  val compactScreen = isNarrowWidth || (isShortHeight && !landscapeControl)
  val sidePadding = if (compactScreen) 12.dp else 16.dp
  val sectionGap = if (compactScreen) 10.dp else 12.dp

  val cpuTitle = stringResource(R.string.stats_cpu_title)
  val cpuUnknown = stringResource(R.string.stats_unknown_cpu)
  val memoryTitle = stringResource(R.string.stats_memory_title)
  val cpuLabel = stringResource(R.string.stats_cpu_label)
  val ramLabel = stringResource(R.string.stats_ram_label)
  val runningLower = stringResource(R.string.stats_running_lower)
  val stoppedLower = stringResource(R.string.stats_stopped_lower)

  // One shared animation clock drives every skeleton placeholder on this screen.
  // It exists only while some Statistics data is actually loading, so dozens of
  // independent infinite animations are never left running in the process list.
  val needsLoadingAnimation = initialLoading || power.loading || !power.resolved
  val loadingShift = if (needsLoadingAnimation) {
    val transition = rememberInfiniteTransition(label = "statsLoading")
    val shift by transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 1_050, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
      ),
      label = "statsLoadingShift",
    )
    shift
  } else {
    0f
  }

  CompositionLocalProvider(LocalStatsLoadingShift provides loadingShift) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = listState,
    contentPadding = PaddingValues(
      start = sidePadding,
      top = topContentPadding + if (compactScreen) 12.dp else 16.dp,
      end = sidePadding,
      bottom = bottomContentPadding + 16.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(sectionGap),
  ) {
    item(key = "service") {
      ServiceStatusCard(
        connected = daemonOnline,
        running = serviceRunning,
        activeComponents = runningTools,
        loading = initialLoading,
        compact = compactScreen,
      )
    }

    item(key = "cpu_ram") {
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 340.dp
        if (twoColumns) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            DashboardMetricCard(
              modifier = Modifier.weight(1f),
              title = cpuTitle,
              subtitle = device.cpuName?.takeIf { it.isNotBlank() } ?: cpuUnknown,
              value = "${fmtPct(cpuTotalShown)}%",
              progress = cpuProgress,
              footnote = if (cpuTotalRaw > 100.0) stringResource(R.string.stats_clamped_from, fmtPct(cpuTotalRaw)) else null,
              icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
              loading = initialLoading,
              compact = compactScreen,
            )
            DashboardMetricCard(
              modifier = Modifier.weight(1f),
              title = memoryTitle,
              subtitle = totalRamMb?.let { stringResource(R.string.stats_total_fmt, mbToHuman(it)) }
                ?: stringResource(R.string.stats_total_unknown),
              value = mbToHuman(usedMb),
              progress = usedFrac,
              footnote = totalRamMb?.let { stringResource(R.string.stats_free_fmt, mbToHuman(freeMb ?: 0.0)) },
              icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
              loading = initialLoading,
              compact = compactScreen,
            )
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DashboardMetricCard(
              modifier = Modifier.fillMaxWidth(),
              title = cpuTitle,
              subtitle = device.cpuName?.takeIf { it.isNotBlank() } ?: cpuUnknown,
              value = "${fmtPct(cpuTotalShown)}%",
              progress = cpuProgress,
              footnote = if (cpuTotalRaw > 100.0) stringResource(R.string.stats_clamped_from, fmtPct(cpuTotalRaw)) else null,
              icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
              loading = initialLoading,
              compact = true,
            )
            DashboardMetricCard(
              modifier = Modifier.fillMaxWidth(),
              title = memoryTitle,
              subtitle = totalRamMb?.let { stringResource(R.string.stats_total_fmt, mbToHuman(it)) }
                ?: stringResource(R.string.stats_total_unknown),
              value = mbToHuman(usedMb),
              progress = usedFrac,
              footnote = totalRamMb?.let { stringResource(R.string.stats_free_fmt, mbToHuman(freeMb ?: 0.0)) },
              icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
              loading = initialLoading,
              compact = true,
            )
          }
        }
      }
    }

    item(key = "power") {
      PowerConsumptionCard(
        milliAmps = power.milliAmps,
        loading = power.loading || !power.resolved,
        compact = compactScreen,
      )
    }

    item(key = "process_header") {
      SectionHeader(
        title = stringResource(R.string.stats_processes_title),
        trailing = if (initialLoading) null else if (daemonOnline) {
          stringResource(R.string.stats_running_count, runningTools)
        } else {
          stringResource(R.string.stats_offline)
        },
        compact = compactScreen,
      )
    }

    items(
      items = rows,
      key = { it.key },
      contentType = { "stats_process" },
    ) { row ->
      ProcessStatusCard(
        row = row,
        totalRamMb = totalRamMb,
        cpuLabel = cpuLabel,
        ramLabel = ramLabel,
        runningLower = runningLower,
        stoppedLower = stoppedLower,
        loading = initialLoading,
        compact = compactScreen,
      )
    }

    item(key = "bottom_spacer") { Spacer(Modifier.height(6.dp)) }
  }
  }
}

@Composable
private fun ServiceStatusCard(
  connected: Boolean,
  running: Boolean,
  activeComponents: Int,
  loading: Boolean,
  compact: Boolean,
) {
  val shape = RoundedCornerShape(if (compact) 26.dp else 30.dp)
  val primary = MaterialTheme.colorScheme.primary
  val inactive = MaterialTheme.colorScheme.onSurface
  val visualRunning = running || loading
  val accent = if (visualRunning) primary else inactive
  val statusText = if (running) stringResource(R.string.stats_online_upper) else stringResource(R.string.stats_offline_upper)
  val detailText = when {
    !connected -> stringResource(R.string.stats_no_connection)
    else -> stringResource(R.string.stats_running_services, activeComponents)
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            primary.copy(alpha = if (visualRunning) 0.30f else 0.12f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
          )
        )
      )
      .border(
        width = 1.dp,
        color = if (visualRunning) primary.copy(alpha = 0.72f) else inactive.copy(alpha = 0.16f),
        shape = shape,
      )
      .padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 16.dp else 20.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
    ) {
      Box(
        modifier = Modifier
          .size(if (compact) 54.dp else 62.dp)
          .clip(RoundedCornerShape(if (compact) 18.dp else 21.dp))
          .background(primary.copy(alpha = 0.18f))
          .border(1.dp, primary.copy(alpha = 0.30f), RoundedCornerShape(if (compact) 18.dp else 21.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Outlined.Dns,
          contentDescription = null,
          modifier = Modifier.size(if (compact) 28.dp else 32.dp),
          tint = primary,
        )
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = stringResource(R.string.stats_daemon_title),
          style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
        if (loading) {
          AnimatedLoadingLine(width = 150.dp, height = 15.dp, phaseDelayMs = 90)
        } else {
          Text(
            text = detailText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (loading) {
        AnimatedLoadingLine(width = if (compact) 92.dp else 108.dp, height = 32.dp, radius = 999.dp, phaseDelayMs = 220)
      } else {
        StatusPill(text = statusText, good = running, accent = accent)
      }
    }
  }
}

@Composable
private fun DashboardMetricCard(
  modifier: Modifier,
  title: String,
  subtitle: String,
  value: String,
  progress: Float?,
  footnote: String?,
  icon: @Composable () -> Unit,
  loading: Boolean,
  compact: Boolean,
) {
  val blue = MaterialTheme.colorScheme.secondary
  val shape = RoundedCornerShape(if (compact) 24.dp else 28.dp)

  Box(
    modifier = modifier
      .height(if (compact) 182.dp else 198.dp)
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            blue.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
          )
        )
      )
      .border(1.dp, blue.copy(alpha = 0.48f), shape)
      .padding(if (compact) 13.dp else 15.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        Box(
          modifier = Modifier
            .size(if (compact) 38.dp else 42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(blue.copy(alpha = 0.15f))
            .border(1.dp, blue.copy(alpha = 0.30f), RoundedCornerShape(13.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Box(Modifier.size(if (compact) 21.dp else 23.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Text(
            text = title,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Spacer(Modifier.height(1.dp))

      Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CircularValueGauge(
          modifier = Modifier.size(if (compact) 88.dp else 96.dp),
          progress = if (loading) null else progress,
          accent = blue,
          loading = loading,
          value = value,
          phaseDelayMs = 50,
        )

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.Center,
        ) {
          if (loading) {
            AnimatedLoadingLine(width = 72.dp, height = 13.dp, phaseDelayMs = 170)
            Spacer(Modifier.height(8.dp))
            AnimatedLoadingLine(width = 58.dp, height = 18.dp, phaseDelayMs = 260)
          } else if (!footnote.isNullOrBlank()) {
            Text(
              text = footnote,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PowerConsumptionCard(
  milliAmps: Double?,
  loading: Boolean,
  compact: Boolean,
) {
  val blue = MaterialTheme.colorScheme.secondary
  val shape = RoundedCornerShape(if (compact) 24.dp else 28.dp)

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            blue.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
          )
        )
      )
      .border(1.dp, blue.copy(alpha = 0.44f), shape)
      .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 13.dp else 16.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 13.dp else 16.dp),
    ) {
      Box(
        modifier = Modifier
          .size(if (compact) 52.dp else 58.dp)
          .clip(RoundedCornerShape(if (compact) 17.dp else 19.dp))
          .background(blue.copy(alpha = 0.14f))
          .border(1.dp, blue.copy(alpha = 0.32f), RoundedCornerShape(if (compact) 17.dp else 19.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.BatteryChargingFull,
          contentDescription = null,
          modifier = Modifier.size(if (compact) 29.dp else 32.dp),
          tint = blue,
        )
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = stringResource(R.string.stats_power_title),
          style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
        if (loading) {
          AnimatedLoadingLine(width = if (compact) 104.dp else 126.dp, height = if (compact) 25.dp else 30.dp, phaseDelayMs = 130)
        } else {
          Text(
            text = milliAmps?.let { "≈ ${fmtMa(it)} mA" } ?: "—",
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(
  title: String,
  trailing: String?,
  compact: Boolean,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
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
          .width(4.dp)
          .height(if (compact) 28.dp else 32.dp)
          .clip(RoundedCornerShape(999.dp))
          .background(MaterialTheme.colorScheme.primary)
      )
      Text(
        text = title,
        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }

    if (!trailing.isNullOrBlank()) {
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Text(
          text = trailing,
          modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun ProcessStatusCard(
  row: ProcRow,
  totalRamMb: Double?,
  cpuLabel: String,
  ramLabel: String,
  runningLower: String,
  stoppedLower: String,
  loading: Boolean,
  compact: Boolean,
) {
  val running = row.running
  val visualActive = running || (loading && (row.configuredEnabled || row.daemon))
  val primary = MaterialTheme.colorScheme.primary
  val blue = MaterialTheme.colorScheme.secondary
  val inactive = MaterialTheme.colorScheme.onSurface
  val accent = if (visualActive) primary else inactive
  val shape = RoundedCornerShape(if (compact) 20.dp else 23.dp)
  val cpuProgress = (row.agg.cpuPercent / 100.0).toFloat().coerceIn(0f, 1f)
  val ramProgress = totalRamMb?.let { (row.agg.rssMb / it).toFloat().coerceIn(0f, 1f) }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            if (visualActive) primary.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.66f),
          )
        )
      )
      .border(1.dp, accent.copy(alpha = if (visualActive) 0.30f else 0.10f), shape)
      .drawBehind {
        val strip = if (visualActive) primary else inactive.copy(alpha = 0.13f)
        val x = 2.dp.toPx()
        drawLine(
          color = strip,
          start = Offset(x, 0f),
          end = Offset(x, size.height),
          strokeWidth = 4.dp.toPx(),
        )
      },
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = if (compact) 12.dp else 14.dp, end = if (compact) 10.dp else 12.dp, top = if (compact) 11.dp else 12.dp, bottom = if (compact) 11.dp else 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 11.dp),
    ) {
      ProcessIcon(row = row, running = visualActive, compact = compact)

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = row.name,
          style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (loading) {
          Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AnimatedLoadingLine(width = 76.dp, height = 22.dp, radius = 999.dp, phaseDelayMs = 80)
            AnimatedLoadingLine(width = 38.dp, height = 22.dp, radius = 999.dp, phaseDelayMs = 190)
          }
        } else {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
          ) {
            TinyPill(
              text = if (running) runningLower else stoppedLower,
              good = running,
            )
            TinyPill(
              text = "x${row.agg.count}",
              good = running,
              strong = false,
            )
          }
        }
      }

      MiniProcessMetric(
        label = cpuLabel,
        value = "${fmtPct(row.agg.cpuPercent)}%",
        progress = cpuProgress,
        accent = blue,
        loading = loading,
        compact = compact,
        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
        phaseDelayMs = 150,
      )

      Box(
        modifier = Modifier
          .width(1.dp)
          .height(if (compact) 48.dp else 54.dp)
          .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
      )

      MiniProcessMetric(
        label = ramLabel,
        value = mbToHuman(row.agg.rssMb),
        progress = ramProgress,
        accent = blue,
        loading = loading,
        compact = compact,
        icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
        phaseDelayMs = 260,
      )
    }
  }
}

@Composable
private fun ProcessIcon(
  row: ProcRow,
  running: Boolean,
  compact: Boolean,
) {
  val primary = MaterialTheme.colorScheme.primary
  val blue = MaterialTheme.colorScheme.secondary
  val tint = when (row.icon) {
    StatsProcIcon.T2S, StatsProcIcon.D2S -> if (running) blue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    else -> if (running) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
  }
  val size = if (compact) 48.dp else 54.dp
  val shape = RoundedCornerShape(if (compact) 15.dp else 17.dp)

  Box(
    modifier = Modifier
      .size(size)
      .clip(shape)
      .background(tint.copy(alpha = if (running) 0.12f else 0.06f))
      .border(1.dp, tint.copy(alpha = if (running) 0.30f else 0.10f), shape),
    contentAlignment = Alignment.Center,
  ) {
    when (row.icon) {
      StatsProcIcon.DAEMON -> Icon(
        Icons.Outlined.Terminal,
        contentDescription = null,
        modifier = Modifier.size(if (compact) 26.dp else 29.dp),
        tint = tint,
      )
      StatsProcIcon.D2S -> Icon(
        Icons.Outlined.SyncAlt,
        contentDescription = null,
        modifier = Modifier.size(if (compact) 26.dp else 29.dp),
        tint = tint,
      )
      StatsProcIcon.T2S -> Icon(
        Icons.Filled.Hub,
        contentDescription = null,
        modifier = Modifier.size(if (compact) 26.dp else 29.dp),
        tint = tint,
      )
      StatsProcIcon.PROGRAM -> {
        val iconId = row.iconProgramId ?: row.programId.orEmpty()
        val res = programIconRes(iconId)
        if (res != null) {
          Icon(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 28.dp else 31.dp),
            tint = tint,
          )
        } else {
          Icon(
            imageVector = programIcon(iconId),
            contentDescription = null,
            modifier = Modifier.size(if (compact) 26.dp else 29.dp),
            tint = tint,
          )
        }
      }
    }
  }
}

@Composable
private fun MiniProcessMetric(
  label: String,
  value: String,
  progress: Float?,
  accent: Color,
  loading: Boolean,
  compact: Boolean,
  icon: @Composable () -> Unit,
  phaseDelayMs: Int,
) {
  Column(
    modifier = Modifier.width(if (compact) 60.dp else 66.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Box(
        modifier = Modifier.size(14.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) { icon() }
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        maxLines = 1,
      )
    }

    MiniCircularGauge(
      value = value,
      progress = if (loading) null else progress,
      accent = accent,
      loading = loading,
      size = if (compact) 47.dp else 51.dp,
      phaseDelayMs = phaseDelayMs,
    )
  }
}

@Composable
private fun CircularValueGauge(
  modifier: Modifier,
  progress: Float?,
  accent: Color,
  loading: Boolean,
  value: String,
  phaseDelayMs: Int,
) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    GaugeCanvas(progress = if (loading) 0f else progress ?: 0f, accent = accent, strokeWidth = 8.dp)
    if (loading) {
      AnimatedLoadingLine(width = 58.dp, height = 22.dp, phaseDelayMs = phaseDelayMs)
    } else {
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun MiniCircularGauge(
  value: String,
  progress: Float?,
  accent: Color,
  loading: Boolean,
  size: Dp,
  phaseDelayMs: Int,
) {
  Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
    GaugeCanvas(progress = if (loading) 0f else progress ?: 0f, accent = accent, strokeWidth = 5.dp)
    if (loading) {
      AnimatedLoadingLine(width = size * 0.58f, height = 11.dp, phaseDelayMs = phaseDelayMs)
    } else {
      Text(
        text = value,
        fontSize = if (value.length > 6) 10.sp else 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun GaugeCanvas(
  progress: Float,
  accent: Color,
  strokeWidth: Dp,
) {
  val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
  Canvas(modifier = Modifier.fillMaxSize()) {
    val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
    val inset = stroke.width / 2f
    val arcSize = androidx.compose.ui.geometry.Size(
      width = size.width - stroke.width,
      height = size.height - stroke.width,
    )
    drawArc(
      color = track,
      startAngle = -90f,
      sweepAngle = 360f,
      useCenter = false,
      topLeft = Offset(inset, inset),
      size = arcSize,
      style = stroke,
    )
    val p = progress.coerceIn(0f, 1f)
    if (p > 0f) {
      drawArc(
        color = accent,
        startAngle = -90f,
        sweepAngle = 360f * p,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = arcSize,
        style = stroke,
      )
    }
  }
}

@Composable
private fun StatusPill(
  text: String,
  good: Boolean,
  accent: Color,
) {
  val base = if (good) accent else MaterialTheme.colorScheme.onSurface
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = base.copy(alpha = if (good) 0.16f else 0.08f),
    border = androidx.compose.foundation.BorderStroke(1.dp, base.copy(alpha = if (good) 0.22f else 0.10f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Box(Modifier.size(7.dp).clip(CircleShape).background(base.copy(alpha = if (good) 1f else 0.42f)))
      Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = base.copy(alpha = if (good) 1f else 0.68f),
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun TinyPill(
  text: String,
  good: Boolean,
  strong: Boolean = true,
) {
  val primary = MaterialTheme.colorScheme.primary
  val blue = MaterialTheme.colorScheme.secondary
  val neutral = MaterialTheme.colorScheme.onSurface
  val base = when {
    good && strong -> primary
    good -> blue
    else -> neutral
  }
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = base.copy(alpha = if (good) 0.13f else 0.06f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Box(
        Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(base.copy(alpha = if (good) 1f else 0.30f))
      )
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = base.copy(alpha = if (good) 1f else 0.62f),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun AnimatedLoadingLine(
  width: Dp,
  height: Dp,
  radius: Dp = 999.dp,
  phaseDelayMs: Int = 0,
) {
  val globalShift = LocalStatsLoadingShift.current
  val phaseOffset = (phaseDelayMs.coerceAtLeast(0) % 1_050) / 1_050f
  val shift = (globalShift + phaseOffset) % 1f
  val shape = RoundedCornerShape(radius)
  val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.105f)
  val shade = Color.Black.copy(alpha = 0.26f)

  BoxWithConstraints(
    modifier = Modifier
      .width(width)
      .height(height)
      .clip(shape)
      .background(base),
  ) {
    val bandWidth = maxWidth * 0.42f
    val travel = maxWidth + bandWidth
    Box(
      modifier = Modifier
        .offset(x = -bandWidth + travel * shift)
        .width(bandWidth)
        .fillMaxHeight()
        .background(
          Brush.horizontalGradient(
            colors = listOf(Color.Transparent, shade, Color.Transparent),
          )
        )
    )
  }
}

private fun fmtPct(v: Double): String {
  if (!v.isFinite()) return "0.0"
  return String.format(Locale.getDefault(), "%.1f", v.coerceAtLeast(0.0))
}

private fun fmtMa(v: Double): String {
  if (!v.isFinite()) return "0.0"
  val safe = v.coerceAtLeast(0.0)
  return when {
    safe >= 100.0 -> String.format(Locale.getDefault(), "%.0f", safe)
    safe >= 10.0 -> String.format(Locale.getDefault(), "%.1f", safe)
    else -> String.format(Locale.getDefault(), "%.1f", safe)
  }
}

private fun mbToHuman(mb: Double): String {
  if (!mb.isFinite() || mb <= 0.0) return "0 MB"
  val gb = mb / 1024.0
  return when {
    gb >= 10.0 -> String.format(Locale.getDefault(), "%.1f GB", gb)
    gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
    else -> "${mb.roundToInt()} MB"
  }
}
