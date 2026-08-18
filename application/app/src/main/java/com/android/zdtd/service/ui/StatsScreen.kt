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
import androidx.compose.material.icons.filled.BatteryFull
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
import androidx.compose.ui.platform.LocalConfiguration
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

  // Statistics uses the real available Android dp size instead of assuming one phone density.
  // Small phones and landscape stay dense; tablets get a little more breathing room, but sizes
  // are capped so cards never become oversized.
  val configuration = LocalConfiguration.current
  val screenWidthDp = configuration.screenWidthDp
  val screenHeightDp = configuration.screenHeightDp
  val compactScreen = screenWidthDp < 380 || screenHeightDp < 600
  val largeScreen = screenWidthDp >= 600 && screenHeightDp >= 480
  val sidePadding = when {
    compactScreen -> 10.dp
    largeScreen -> 18.dp
    else -> 12.dp
  }
  val sectionGap = when {
    compactScreen -> 8.dp
    largeScreen -> 12.dp
    else -> 10.dp
  }

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
      top = topContentPadding + if (compactScreen) 8.dp else if (largeScreen) 14.dp else 10.dp,
      end = sidePadding,
      bottom = bottomContentPadding + if (compactScreen) 10.dp else 14.dp,
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
        large = largeScreen,
      )
    }

    item(key = "cpu_ram") {
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 330.dp
        if (twoColumns) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compactScreen) 8.dp else if (largeScreen) 12.dp else 10.dp),
          ) {
            DashboardMetricCard(
              modifier = Modifier.weight(1f),
              title = cpuTitle,
              subtitle = device.cpuName?.takeIf { it.isNotBlank() } ?: cpuUnknown,
              value = "${fmtPct(cpuTotalShown)}%",
              progress = cpuProgress,
              icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
              loading = initialLoading,
              compact = compactScreen,
              large = largeScreen,
            )
            DashboardMetricCard(
              modifier = Modifier.weight(1f),
              title = memoryTitle,
              subtitle = totalRamMb?.let { stringResource(R.string.stats_total_fmt, mbToHuman(it)) }
                ?: stringResource(R.string.stats_total_unknown),
              value = mbToHuman(usedMb),
              progress = usedFrac,
              icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
              loading = initialLoading,
              compact = compactScreen,
              large = largeScreen,
            )
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardMetricCard(
              modifier = Modifier.fillMaxWidth(),
              title = cpuTitle,
              subtitle = device.cpuName?.takeIf { it.isNotBlank() } ?: cpuUnknown,
              value = "${fmtPct(cpuTotalShown)}%",
              progress = cpuProgress,
              icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
              loading = initialLoading,
              compact = true,
              large = false,
            )
            DashboardMetricCard(
              modifier = Modifier.fillMaxWidth(),
              title = memoryTitle,
              subtitle = totalRamMb?.let { stringResource(R.string.stats_total_fmt, mbToHuman(it)) }
                ?: stringResource(R.string.stats_total_unknown),
              value = mbToHuman(usedMb),
              progress = usedFrac,
              icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
              loading = initialLoading,
              compact = true,
              large = false,
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
        large = largeScreen,
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
        large = largeScreen,
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
  large: Boolean,
) {
  val shape = RoundedCornerShape(when { compact -> 20.dp; large -> 24.dp; else -> 22.dp })
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
      .padding(
        horizontal = when { compact -> 11.dp; large -> 15.dp; else -> 13.dp },
        vertical = when { compact -> 10.dp; large -> 13.dp; else -> 11.dp },
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(when { compact -> 9.dp; large -> 13.dp; else -> 11.dp }),
    ) {
      Box(
        modifier = Modifier
          .size(when { compact -> 44.dp; large -> 52.dp; else -> 48.dp })
          .clip(RoundedCornerShape(when { compact -> 14.dp; large -> 17.dp; else -> 15.dp }))
          .background(primary.copy(alpha = 0.18f))
          .border(1.dp, primary.copy(alpha = 0.30f), RoundedCornerShape(when { compact -> 14.dp; large -> 17.dp; else -> 15.dp })),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Outlined.Dns,
          contentDescription = null,
          modifier = Modifier.size(when { compact -> 23.dp; large -> 28.dp; else -> 25.dp }),
          tint = primary,
        )
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = stringResource(R.string.stats_daemon_title),
          style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
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
        AnimatedLoadingLine(width = if (compact) 78.dp else 88.dp, height = 28.dp, radius = 999.dp, phaseDelayMs = 220)
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
  icon: @Composable () -> Unit,
  loading: Boolean,
  compact: Boolean,
  large: Boolean,
) {
  val blue = MaterialTheme.colorScheme.secondary
  val shape = RoundedCornerShape(when { compact -> 16.dp; large -> 20.dp; else -> 18.dp })
  val gaugeSize = when { compact -> 48.dp; large -> 58.dp; else -> 52.dp }

  Box(
    modifier = modifier
      .height(when { compact -> 70.dp; large -> 84.dp; else -> 76.dp })
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            blue.copy(alpha = 0.14f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
          )
        )
      )
      .border(1.dp, blue.copy(alpha = 0.42f), shape)
      .padding(horizontal = when { compact -> 8.dp; large -> 12.dp; else -> 10.dp }, vertical = if (compact) 7.dp else 8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp),
    ) {
      Box(
        modifier = Modifier
          .size(when { compact -> 30.dp; large -> 36.dp; else -> 32.dp })
          .clip(RoundedCornerShape(if (compact) 10.dp else 11.dp))
          .background(blue.copy(alpha = 0.13f))
          .border(1.dp, blue.copy(alpha = 0.26f), RoundedCornerShape(if (compact) 10.dp else 11.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          Modifier.size(when { compact -> 17.dp; large -> 21.dp; else -> 19.dp }),
          contentAlignment = Alignment.Center,
        ) { icon() }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(1.dp),
      ) {
        Text(
          text = title,
          style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      CircularValueGauge(
        modifier = Modifier.size(gaugeSize),
        progress = if (loading) null else progress,
        accent = blue,
        loading = loading,
        value = value,
        phaseDelayMs = 50,
        compact = compact,
      )
    }
  }
}

@Composable
private fun PowerConsumptionCard(
  milliAmps: Double?,
  loading: Boolean,
  compact: Boolean,
  large: Boolean,
) {
  val blue = MaterialTheme.colorScheme.secondary
  val shape = RoundedCornerShape(when { compact -> 16.dp; large -> 20.dp; else -> 18.dp })

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(
        Brush.linearGradient(
          listOf(
            blue.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
          )
        )
      )
      .border(1.dp, blue.copy(alpha = 0.38f), shape)
      .padding(
        horizontal = when { compact -> 10.dp; large -> 15.dp; else -> 12.dp },
        vertical = when { compact -> 9.dp; large -> 12.dp; else -> 10.dp },
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(when { compact -> 30.dp; large -> 36.dp; else -> 32.dp })
          .clip(RoundedCornerShape(if (compact) 10.dp else 11.dp))
          .background(blue.copy(alpha = 0.13f))
          .border(1.dp, blue.copy(alpha = 0.26f), RoundedCornerShape(if (compact) 10.dp else 11.dp)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.BatteryFull,
          contentDescription = null,
          modifier = Modifier.size(when { compact -> 18.dp; large -> 22.dp; else -> 20.dp }),
          tint = blue,
        )
      }

      Text(
        text = stringResource(R.string.stats_power_title),
        modifier = Modifier.weight(1f),
        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )

      if (loading) {
        AnimatedLoadingLine(
          width = when { compact -> 72.dp; large -> 96.dp; else -> 84.dp },
          height = if (compact) 19.dp else 21.dp,
          phaseDelayMs = 130,
        )
      } else {
        Text(
          text = milliAmps?.let { "≈ ${fmtPowerMah(it)} mA-h" } ?: "—",
          style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
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
          .height(if (compact) 24.dp else 28.dp)
          .clip(RoundedCornerShape(999.dp))
          .background(MaterialTheme.colorScheme.primary)
      )
      Text(
        text = title,
        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
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
          modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelMedium,
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
  large: Boolean,
) {
  val running = row.running
  val visualActive = running || (loading && (row.configuredEnabled || row.daemon))
  val primary = MaterialTheme.colorScheme.primary
  val blue = MaterialTheme.colorScheme.secondary
  val inactive = MaterialTheme.colorScheme.onSurface
  val accent = if (visualActive) primary else inactive
  val shape = RoundedCornerShape(when { compact -> 14.dp; large -> 18.dp; else -> 16.dp })
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
        .padding(
          start = when { compact -> 9.dp; large -> 12.dp; else -> 10.dp },
          end = when { compact -> 7.dp; large -> 10.dp; else -> 8.dp },
          top = when { compact -> 7.dp; large -> 9.dp; else -> 8.dp },
          bottom = when { compact -> 7.dp; large -> 9.dp; else -> 8.dp },
        ),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(when { compact -> 7.dp; large -> 10.dp; else -> 8.dp }),
    ) {
      ProcessIcon(row = row, running = visualActive, compact = compact, large = large)

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = row.name,
          style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (loading) {
          Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AnimatedLoadingLine(width = 68.dp, height = 19.dp, radius = 999.dp, phaseDelayMs = 80)
            AnimatedLoadingLine(width = 34.dp, height = 19.dp, radius = 999.dp, phaseDelayMs = 190)
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
        large = large,
        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
        phaseDelayMs = 150,
      )

      Box(
        modifier = Modifier
          .width(1.dp)
          .height(when { compact -> 40.dp; large -> 48.dp; else -> 44.dp })
          .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
      )

      MiniProcessMetric(
        label = ramLabel,
        value = mbToHuman(row.agg.rssMb),
        progress = ramProgress,
        accent = blue,
        loading = loading,
        compact = compact,
        large = large,
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
  large: Boolean,
) {
  val primary = MaterialTheme.colorScheme.primary
  val blue = MaterialTheme.colorScheme.secondary
  val tint = when (row.icon) {
    StatsProcIcon.T2S, StatsProcIcon.D2S -> if (running) blue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    else -> if (running) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
  }
  val size = when { compact -> 40.dp; large -> 48.dp; else -> 44.dp }
  val shape = RoundedCornerShape(when { compact -> 12.dp; large -> 15.dp; else -> 13.dp })

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
        modifier = Modifier.size(when { compact -> 21.dp; large -> 26.dp; else -> 23.dp }),
        tint = tint,
      )
      StatsProcIcon.D2S -> Icon(
        Icons.Outlined.SyncAlt,
        contentDescription = null,
        modifier = Modifier.size(when { compact -> 21.dp; large -> 26.dp; else -> 23.dp }),
        tint = tint,
      )
      StatsProcIcon.T2S -> Icon(
        Icons.Filled.Hub,
        contentDescription = null,
        modifier = Modifier.size(when { compact -> 21.dp; large -> 26.dp; else -> 23.dp }),
        tint = tint,
      )
      StatsProcIcon.PROGRAM -> {
        val iconId = row.iconProgramId ?: row.programId.orEmpty()
        val res = programIconRes(iconId)
        if (res != null) {
          Icon(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(when { compact -> 23.dp; large -> 28.dp; else -> 25.dp }),
            tint = tint,
          )
        } else {
          Icon(
            imageVector = programIcon(iconId),
            contentDescription = null,
            modifier = Modifier.size(when { compact -> 21.dp; large -> 26.dp; else -> 23.dp }),
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
  large: Boolean,
  icon: @Composable () -> Unit,
  phaseDelayMs: Int,
) {
  Column(
    modifier = Modifier.width(when { compact -> 46.dp; large -> 60.dp; else -> 54.dp }),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Box(
        modifier = Modifier.size(if (compact) 12.dp else 13.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(Modifier.size(if (compact) 11.dp else 12.dp), contentAlignment = Alignment.Center) { icon() }
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
      size = when { compact -> 36.dp; large -> 46.dp; else -> 42.dp },
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
  compact: Boolean,
) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    GaugeCanvas(progress = if (loading) 0f else progress ?: 0f, accent = accent, strokeWidth = if (compact) 4.dp else 5.dp)
    if (loading) {
      AnimatedLoadingLine(width = if (compact) 27.dp else 31.dp, height = if (compact) 12.dp else 14.dp, phaseDelayMs = phaseDelayMs)
    } else {
      Text(
        text = value,
        fontSize = when {
          value.length >= 7 -> if (compact) 8.sp else 9.sp
          value.length >= 6 -> if (compact) 9.sp else 10.sp
          else -> if (compact) 10.sp else 11.sp
        },
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
        fontSize = if (value.length > 6) 9.sp else 10.sp,
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
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Box(Modifier.size(7.dp).clip(CircleShape).background(base.copy(alpha = if (good) 1f else 0.42f)))
      Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
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
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
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

private fun fmtPowerMah(v: Double): String {
  if (!v.isFinite()) return "0"
  // The live sampler stores the fractional value; Statistics presents it in thousandths
  // as a whole mA-h number (0.238 -> 238, 1.238 -> 1238) to avoid decimal noise.
  return (v.coerceAtLeast(0.0) * 1000.0).roundToInt().toString()
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
