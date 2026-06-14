package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

private data class StudioAppGroup(
  val key: String,
  val programId: String,
  val profile: String?,
  val slot: String?,
  val uidFile: String?,
  val rules: List<ApiModels.TrafficRuleCounter>,
)

@Composable
fun ConstructionStudioScreen(
  programs: List<ApiModels.Program>,
  actions: ZdtdActions,
  onOpenProgram: (String) -> Unit,
  onOpenProfile: (String, String) -> Unit,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  var loading by remember { mutableStateOf(false) }
  var requestInFlight by remember { mutableStateOf(false) }
  var report by remember { mutableStateOf(ApiModels.TrafficReport()) }
  var selectedGroup by remember { mutableStateOf<StudioAppGroup?>(null) }
  var selectedVpn by remember { mutableStateOf<ApiModels.VpnTraffic?>(null) }
  val compact = rememberIsCompactWidth()

  fun requestTraffic() {
    if (requestInFlight) return
    requestInFlight = true
    loading = true
    actions.loadTrafficRules { loaded ->
      if (loaded != null) {
        // Busy/preparing keeps the last good graph visible and only updates the header state.
        report = if ((loaded.busy || loaded.preparing) && report.updatedAtUnix > 0L) {
          report.copy(ok = loaded.ok, busy = true, preparing = true, message = loaded.message, error = loaded.error)
        } else {
          loaded
        }
      } else {
        report = report.copy(ok = false, error = "/api/traffic/rules: load failed")
      }
      requestInFlight = false
      loading = false
    }
  }

  LaunchedEffect(Unit) {
    while (isActive) {
      requestTraffic()
      delay(5_000)
    }
  }

  val visibleRules = remember(report) { report.rules.filter(::shouldShowStudioRule) }
  val activeRules = remember(visibleRules) { visibleRules.count { it.active } }
  val groups = remember(visibleRules) { buildStudioGroups(visibleRules) }
  val vpnItems = remember(report) { report.vpn.filter { it.tun.isNotBlank() } }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = if (compact) 10.dp else 12.dp,
      end = if (compact) 10.dp else 12.dp,
      top = topContentPadding + 10.dp,
      bottom = bottomContentPadding + 16.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      StudioHeaderCard(
        loading = loading,
        report = report,
        activeRules = activeRules,
        actionRules = visibleRules.size,
      )
    }

    if (groups.isEmpty() && vpnItems.isEmpty() && !loading && !report.busy && !report.preparing) {
      item { EmptyStudioCard() }
    }

    items(groups, key = { it.key }) { group ->
      StudioRouteGraphCard(
        group = group,
        programName = displayProgramName(programs, group.programId),
        onOpenApps = { selectedGroup = group },
        onOpenSettings = {
          val routeProgramId = normalizeRouteProgramId(group.programId)
          val profile = group.profile
          if (!profile.isNullOrBlank()) onOpenProfile(routeProgramId, profile) else onOpenProgram(routeProgramId)
        },
      )
    }

    items(vpnItems, key = { "vpn:${it.ownerProgram}:${it.profile}:${it.tun}" }) { vpn ->
      VpnStudioGraphCard(
        vpn = vpn,
        programName = displayProgramName(programs, vpn.ownerProgram),
        onOpenApps = { selectedVpn = vpn },
        onOpenSettings = {
          val routeProgramId = normalizeRouteProgramId(vpn.ownerProgram)
          if (vpn.profile.isNotBlank()) onOpenProfile(routeProgramId, vpn.profile) else onOpenProgram(routeProgramId)
        },
      )
    }

    if (report.warnings.isNotEmpty()) {
      item { WarningsCard(report.warnings) }
    }
  }

  selectedGroup?.let { group ->
    StudioAppsBottomSheet(
      group = group,
      programName = displayProgramName(programs, group.programId),
      onDismiss = { selectedGroup = null },
    )
  }
  selectedVpn?.let { vpn ->
    VpnAppsBottomSheet(
      vpn = vpn,
      programName = displayProgramName(programs, vpn.ownerProgram),
      onDismiss = { selectedVpn = null },
    )
  }
}

@Composable
private fun StudioHeaderCard(
  loading: Boolean,
  report: ApiModels.TrafficReport,
  activeRules: Int,
  actionRules: Int,
) {
  val busy = report.busy || report.preparing
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(26.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
              Color.Transparent,
              MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
            ),
          ),
        )
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), contentColor = MaterialTheme.colorScheme.primary) {
          Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(stringResource(R.string.construction_studio_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          Text(stringResource(R.string.construction_studio_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
        }
        if (loading || busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StudioMetricChip(stringResource(R.string.construction_studio_rules), actionRules.toString())
        StudioMetricChip(stringResource(R.string.construction_studio_active), activeRules.toString())
        StudioMetricChip("VPN", report.vpn.size.toString())
      }

      Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (busy) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.70f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        contentColor = if (busy) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
      ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.size(18.dp))
          Text(
            when {
              busy -> report.message.ifBlank { "Готовлю данные, подожди…" }
              report.error.isNotBlank() -> report.error
              else -> "Автообновление каждые 5 сек, пока открыт экран. Карта двигается целиком."
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (busy) FontWeight.SemiBold else FontWeight.Normal,
          )
        }
      }
    }
  }
}

@Composable
private fun StudioMetricChip(label: String, value: String) {
  AssistChip(onClick = {}, label = { Text("$label: $value", fontWeight = FontWeight.SemiBold) })
}

@Composable
private fun StudioRouteGraphCard(
  group: StudioAppGroup,
  programName: String,
  onOpenApps: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val activeBytes = group.rules.sumOf { it.bytes }
  val activePackets = group.rules.sumOf { it.packets }
  val active = group.rules.any { it.active }
  val semantic = group.rules.firstOrNull()?.semantic.orEmpty().ifBlank { "rule" }
  val accent = semanticAccent(semantic)
  val backendPorts = group.rules.flatMap { it.backendPorts }.distinctBy { it.port }
  val horizontal = rememberScrollState()

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
    border = BorderStroke(1.dp, accent.copy(alpha = if (active) 0.52f else 0.22f)),
  ) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(active)
        Text(appListTitle(group), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${formatPackets(activePackets)} • ${formatBytes(activeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f))
      }

      Box(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(22.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
          .horizontalScroll(horizontal)
          .padding(14.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            StudioGraphNode(Icons.Filled.Apps, appListShortTitle(group), group.uidFile ?: stringResource(R.string.construction_studio_app_list), MaterialTheme.colorScheme.primary, onOpenApps)
            StudioGraphEdge(accent, active, "iptables")
            StudioGraphNode(Icons.Filled.CallSplit, programNodeTitle(programName, group.profile), ruleSummary(group.rules), accent, onOpenSettings)
            if (backendPorts.isNotEmpty()) {
              StudioGraphEdge(Color(0xFFF59E0B), active, "t2s split")
              StudioBackendColumn(backendPorts, active)
            }
            StudioGraphEdge(MaterialTheme.colorScheme.tertiary, active, "out")
            StudioGraphNode(Icons.Filled.Cloud, stringResource(R.string.construction_studio_internet), "${formatPackets(activePackets)} / ${formatBytes(activeBytes)}", MaterialTheme.colorScheme.tertiary, null)
          }
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpenApps, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.construction_studio_open_apps)) }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(17.dp))
          Spacer(Modifier.size(6.dp))
          Text(stringResource(R.string.construction_studio_open_settings))
        }
      }
    }
  }
}

@Composable
private fun StudioBackendColumn(ports: List<ApiModels.TrafficBackendPort>, active: Boolean) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    ports.take(6).forEach { backend ->
      val title = backend.label.ifBlank { "127.0.0.1:${backend.port}" }
      val subtitle = listOfNotNull(backend.programId, backend.profile, backend.server).joinToString(" / ").ifBlank { "backend ${backend.port}" }
      MiniBackendNode(title, subtitle, active)
    }
  }
}

@Composable
private fun MiniBackendNode(title: String, subtitle: String, active: Boolean) {
  Surface(
    modifier = Modifier.width(178.dp),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = if (active) 0.55f else 0.22f)),
  ) {
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun VpnStudioGraphCard(
  vpn: ApiModels.VpnTraffic,
  programName: String,
  onOpenApps: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val active = vpn.totalBytes > 0L
  val accent = Color(0xFF22C55E)
  val horizontal = rememberScrollState()
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
    border = BorderStroke(1.dp, accent.copy(alpha = if (active) 0.50f else 0.22f)),
  ) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(active)
        Text("$programName / ${vpn.profile}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatBytes(vpn.totalBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f))
      }
      Box(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(22.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
          .horizontalScroll(horizontal)
          .padding(14.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          StudioGraphNode(Icons.Filled.Apps, "${vpn.apps.size} ${stringResource(R.string.construction_studio_apps)}", vpn.uidRanges.joinToString(", "), MaterialTheme.colorScheme.primary, onOpenApps)
          StudioGraphEdge(accent, active, vpn.tun)
          StudioGraphNode(Icons.Filled.CallSplit, "$programName / ${vpn.profile}", "netId ${vpn.netid} • ↓ ${formatBytes(vpn.rxBytes)} / ↑ ${formatBytes(vpn.txBytes)}", accent, onOpenSettings)
          StudioGraphEdge(MaterialTheme.colorScheme.tertiary, active, "out")
          StudioGraphNode(Icons.Filled.Cloud, stringResource(R.string.construction_studio_internet), formatBytes(vpn.totalBytes), MaterialTheme.colorScheme.tertiary, null)
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpenApps, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.construction_studio_open_apps)) }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.construction_studio_open_settings)) }
      }
    }
  }
}

@Composable
private fun StudioGraphNode(
  icon: ImageVector,
  title: String,
  subtitle: String,
  tint: Color,
  onClick: (() -> Unit)?,
) {
  val content: @Composable () -> Unit = {
    Column(Modifier.width(190.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Surface(shape = RoundedCornerShape(15.dp), color = tint.copy(alpha = 0.16f), contentColor = tint) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(9.dp).size(22.dp))
      }
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
    onClick = onClick ?: {},
    enabled = onClick != null,
  ) { content() }
}

@Composable
private fun StudioGraphEdge(accent: Color, active: Boolean, label: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(56.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = if (active) 0.82f else 0.24f)))
      AnimatedVisibility(visible = active) { Text("›", color = accent, fontWeight = FontWeight.Bold) }
      Box(Modifier.width(56.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = if (active) 0.82f else 0.24f)))
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun StatusPill(active: Boolean) {
  val color = if (active) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
  Surface(shape = CircleShape, color = color.copy(alpha = 0.14f), contentColor = color) {
    Text(if (active) stringResource(R.string.construction_studio_active_now) else stringResource(R.string.construction_studio_idle), modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun EmptyStudioCard() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(stringResource(R.string.construction_studio_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(stringResource(R.string.construction_studio_empty_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
    }
  }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.50f)),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(stringResource(R.string.construction_studio_warnings), fontWeight = FontWeight.SemiBold)
      warnings.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioAppsBottomSheet(
  group: StudioAppGroup,
  programName: String,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val byUid = remember(group) { group.rules.filter { it.uid != null }.groupBy { it.uid ?: 0 } }
  val iconCache = remember { mutableMapOf<String, ImageBitmap?>() }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(appListTitle(group), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      Text(programNodeTitle(programName, group.profile), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
      if (byUid.isEmpty()) {
        Text(stringResource(R.string.construction_studio_no_apps), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
      } else {
        byUid.toSortedMap().forEach { (uid, rules) -> AppTrafficRow(uid = uid, rules = rules, iconCache = iconCache) }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnAppsBottomSheet(
  vpn: ApiModels.VpnTraffic,
  programName: String,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val iconCache = remember { mutableMapOf<String, ImageBitmap?>() }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("$programName / ${vpn.profile}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      Text("netId ${vpn.netid} • ${vpn.tun} • ${vpn.uidRanges.joinToString(", ")}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
      if (vpn.apps.isEmpty()) {
        Text(stringResource(R.string.construction_studio_no_apps), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
      } else {
        vpn.apps.sortedBy { it.uid }.forEach { app -> VpnAppRow(app = app, iconCache = iconCache) }
      }
    }
  }
}

@Composable
private fun VpnAppRow(app: ApiModels.VpnApp, iconCache: MutableMap<String, ImageBitmap?>) {
  val packages = app.packages.ifEmpty { listOfNotNull(app.packageName) }
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    val firstPackage = packages.firstOrNull()
    if (firstPackage != null) AppIcon(packageName = firstPackage, cache = iconCache) else Surface(shape = CircleShape, color = Color(0xFF22C55E).copy(alpha = 0.16f)) { Text(app.uid.toString().takeLast(2), modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp), fontWeight = FontWeight.Bold) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(firstPackage ?: "UID ${app.uid}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("UID ${app.uid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
    }
  }
}

@Composable
private fun AppTrafficRow(uid: Int, rules: List<ApiModels.TrafficRuleCounter>, iconCache: MutableMap<String, ImageBitmap?>) {
  val packages = rules.flatMap { it.packages.ifEmpty { listOfNotNull(it.packageName) } }.distinct()
  val packets = rules.sumOf { it.packets }
  val bytes = rules.sumOf { it.bytes }
  val active = packets > 0 || bytes > 0
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    val firstPackage = packages.firstOrNull()
    if (firstPackage != null) AppIcon(packageName = firstPackage, cache = iconCache) else Surface(shape = CircleShape, color = semanticAccent(rules.firstOrNull()?.semantic.orEmpty()).copy(alpha = 0.16f)) { Text(uid.toString().takeLast(2), modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp), fontWeight = FontWeight.Bold) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(firstPackage ?: "UID $uid", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("UID $uid • ${rules.joinToString { it.proto ?: it.semantic }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(formatPackets(packets), fontWeight = FontWeight.SemiBold, color = if (active) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
      Text(formatBytes(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
    }
  }
}

private fun shouldShowStudioRule(rule: ApiModels.TrafficRuleCounter): Boolean {
  if (!rule.actionCounter) return false
  val program = rule.programId.orEmpty().lowercase()
  val chain = rule.chain.uppercase()
  if (program == "blockedquic" || program == "proxyinfo") return false
  if (chain.contains("BLOCKEDQUIC") || chain.contains("PROXYINFO")) return false
  if (rule.semantic == "drop" || rule.semantic == "reject") return false
  return true
}

private fun buildStudioGroups(rules: List<ApiModels.TrafficRuleCounter>): List<StudioAppGroup> {
  return rules
    .filter { it.actionCounter && (it.programId != null || it.uidFile != null) }
    .groupBy { listOf(it.programId.orEmpty(), it.profile.orEmpty(), it.slot.orEmpty(), it.uidFile.orEmpty()).joinToString("|") }
    .map { (key, groupRules) ->
      val first = groupRules.first()
      StudioAppGroup(key = key, programId = first.programId ?: "unknown", profile = first.profile, slot = first.slot, uidFile = first.uidFile, rules = groupRules)
    }
    .sortedWith(compareBy<StudioAppGroup> { it.programId }.thenBy { it.profile ?: "" }.thenBy { it.slot ?: "" })
}

private fun displayProgramName(programs: List<ApiModels.Program>, id: String): String {
  val routeId = normalizeRouteProgramId(id)
  return programs.firstOrNull { it.id == routeId }?.name ?: toolDisplayName(routeId, id)
}

private fun normalizeRouteProgramId(id: String): String = when (id) {
  "singbox" -> "sing-box"
  else -> id
}

private fun appListTitle(group: StudioAppGroup): String = listOfNotNull(group.programId, group.profile, group.slot).joinToString(" / ").ifBlank { group.uidFile ?: "App list" }
private fun appListShortTitle(group: StudioAppGroup): String = group.slot?.takeIf { it.isNotBlank() } ?: stringResourceNameFallback(group.programId)
private fun stringResourceNameFallback(id: String): String = if (id.isBlank()) "App list" else id
private fun programNodeTitle(programName: String, profile: String?): String = if (profile.isNullOrBlank()) programName else "$programName / $profile"

private fun ruleSummary(rules: List<ApiModels.TrafficRuleCounter>): String {
  val ports = rules.mapNotNull { it.redirectPort }.distinct().joinToString(",")
  val base = rules.groupBy { it.semantic }.map { (semantic, rs) -> "$semantic ${formatPackets(rs.sumOf { it.packets })} / ${formatBytes(rs.sumOf { it.bytes })}" }.joinToString(" • ")
  return if (ports.isNotBlank()) "$base • local:$ports" else base
}

private fun semanticAccent(semantic: String): Color = when (semantic) {
  "nfqueue" -> Color(0xFF8B5CF6)
  "nat_redirect", "dns_redirect" -> Color(0xFF06B6D4)
  "drop", "reject" -> Color(0xFFEF4444)
  "masquerade", "accept" -> Color(0xFF22C55E)
  else -> Color(0xFF64748B)
}

private fun formatPackets(value: Long): String {
  val safe = max(0L, value)
  return when {
    safe >= 1_000_000 -> "%.1fM pkt".format(safe / 1_000_000.0)
    safe >= 1_000 -> "%.1fK pkt".format(safe / 1_000.0)
    else -> "$safe pkt"
  }
}

private fun formatBytes(bytes: Long): String {
  val safe = max(0L, bytes).toDouble()
  val kb = 1024.0
  val mb = kb * 1024.0
  val gb = mb * 1024.0
  return when {
    safe >= gb -> "%.2f GB".format(safe / gb)
    safe >= mb -> "%.1f MB".format(safe / mb)
    safe >= kb -> "%.1f KB".format(safe / kb)
    else -> "${bytes.coerceAtLeast(0L)} B"
  }
}
