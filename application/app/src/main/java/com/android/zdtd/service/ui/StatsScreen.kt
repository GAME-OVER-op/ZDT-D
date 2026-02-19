package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
)

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

  val totals = ApiModels.computeTotals(showRep)

  val cpuTotalRaw = totals.cpuPercent.coerceAtLeast(0.0)
  val cpuTotalShown = cpuTotalRaw.coerceIn(0.0, 100.0)
  val cpuProgress = (cpuTotalShown / 100.0).toFloat().coerceIn(0f, 1f)

  val totalRamMb = device.totalRamMb?.toDouble()?.takeIf { it > 0 }
  val usedMb = totals.rssMb.coerceAtLeast(0.0)
  val usedFrac = if (totalRamMb != null) (usedMb / totalRamMb).toFloat().coerceIn(0f, 1f) else null
  val freeMb = if (totalRamMb != null) (totalRamMb - usedMb).coerceAtLeast(0.0) else null

  val rows = remember(showRep) {
    listOf(
    ProcRow("zdt-d", showRep?.zdtd ?: ApiModels.ProcAgg()),
    ProcRow("zapret", showRep?.zapret ?: ApiModels.ProcAgg()),
    ProcRow("zapret2", showRep?.zapret2 ?: ApiModels.ProcAgg()),
    ProcRow("byedpi", showRep?.byedpi ?: ApiModels.ProcAgg()),
    ProcRow("dpitunnel", showRep?.dpitunnel ?: ApiModels.ProcAgg()),
    ProcRow("dnscrypt", showRep?.dnscrypt ?: ApiModels.ProcAgg()),
    ProcRow("opera-proxy", showRep?.opera?.opera ?: ApiModels.ProcAgg()),
    ProcRow("t2s", showRep?.opera?.t2s ?: ApiModels.ProcAgg()),
      ProcRow("opera-byedpi", showRep?.opera?.byedpi ?: ApiModels.ProcAgg()),
    )
  }

  val runningCount = rows.count { it.agg.count > 0 }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = listState,
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      StatusCard(
        daemonOnline = daemonOnline,
        runningServices = runningCount,
      )
    }

    item {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard(
          modifier = Modifier.weight(1f),
          icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
          title = "CPU",
          subtitle = device.cpuName.ifBlank { "Unknown CPU" },
          value = "${fmtPct(cpuTotalShown)}%",
          progress = cpuProgress,
          footnote = if (cpuTotalRaw > 100.0) "Clamped from ${fmtPct(cpuTotalRaw)}%" else null,
        )

        MetricCard(
          modifier = Modifier.weight(1f),
          icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
          title = "Memory",
          subtitle = if (totalRamMb != null) "Total: ${mbToHuman(totalRamMb)}" else "Total: —",
          value = mbToHuman(usedMb),
          progress = usedFrac,
          footnote = if (totalRamMb != null) "Free: ${mbToHuman(freeMb ?: 0.0)}" else null,
        )
      }
    }

    item {
      SectionHeader(
        title = "Processes",
        trailing = if (daemonOnline) "$runningCount running" else "offline",
      )
    }

    items(rows, key = { it.name }) { row ->
      ProcCard(
        name = row.name,
        agg = row.agg,
        totalRamMb = totalRamMb,
      )
    }

    item { Spacer(Modifier.height(12.dp)) }
  }
}

@Composable
private fun StatusCard(daemonOnline: Boolean, runningServices: Int) {
  ElevatedCard(
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Daemon", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        StatusPill(text = if (daemonOnline) "ONLINE" else "OFFLINE", good = daemonOnline)
      }

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
        Text(
          text = if (daemonOnline) "$runningServices running service(s)" else "No connection to API",
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
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

@Composable
private fun MetricCard(
  modifier: Modifier,
  icon: @Composable () -> Unit,
  title: String,
  subtitle: String,
  value: String,
  progress: Float?,
  footnote: String? = null,
) {
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon()
        Column(Modifier.weight(1f)) {
          Text(title, fontWeight = FontWeight.SemiBold)
          Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1)
        }
      }

      Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

      SimpleBar(progress = progress)

      if (!footnote.isNullOrBlank()) {
        Text(footnote, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      }
    }
  }
}

@Composable
private fun ProcCard(name: String, agg: ApiModels.ProcAgg, totalRamMb: Double?) {
  val running = agg.count > 0
  val cpuP = (agg.cpuPercent / 100.0).toFloat().coerceIn(0f, 1f)
  val ramP = if (totalRamMb != null) (agg.rssMb / totalRamMb).toFloat().coerceIn(0f, 1f) else null

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (running) 0.70f else 0.55f)),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(name, fontWeight = FontWeight.SemiBold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          TinyPill(text = if (running) "running" else "stopped", good = running)
          TinyPill(text = "x${agg.count}", good = true, strong = false)
        }
      }

      // CPU
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Outlined.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
          Text("CPU", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
        Text("${fmtPct(agg.cpuPercent)}%", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
      }
      SimpleBar(progress = cpuP)

      // RAM
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Outlined.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
          Text("RAM", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
        Text(mbToHuman(agg.rssMb), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
      }
      if (ramP != null) {
        SimpleBar(progress = ramP)
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
