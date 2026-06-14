package com.android.zdtd.service.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.max

// ---------------------------------------------------------------------------
// Construction Studio is a single living canvas (pan + zoom), not a list of
// cards.  Traffic routes are laid out as a left-to-right dependency graph:
//   app-list -> program/rule -> [t2s backend split] -> internet
// The whole map moves and scales as one surface, similar to the GitHub Actions
// workflow visualization.
// ---------------------------------------------------------------------------

private data class StudioAppGroup(
  val key: String,
  val programId: String,
  val profile: String?,
  val slot: String?,
  val uidFile: String?,
  val rules: List<ApiModels.TrafficRuleCounter>,
)

private enum class StudioNodeKind { APP, PROGRAM, BACKEND, VPN, INTERNET }

private data class StudioNode(
  val id: String,
  val x: Float,
  val y: Float,
  val w: Float,
  val h: Float,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val accent: Color,
  val active: Boolean,
  val kind: StudioNodeKind,
  val onClick: (() -> Unit)?,
  val iconRes: Int? = null,
  val warn: Boolean = false,
  // When set, the card shows a tappable output port. Tapping it (or, for hub
  // cards like t2s, the whole card) opens the "direct connection" picker
  // instead of requiring a drag gesture.
  val onPort: (() -> Unit)? = null,
)

private data class StudioEdge(
  val fromId: String,
  val toId: String,
  val accent: Color,
  val active: Boolean,
)

private data class StudioGraph(
  val nodes: List<StudioNode>,
  val edges: List<StudioEdge>,
  val width: Float,
  val height: Float,
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
  var showWarnings by remember { mutableStateOf(false) }

  // Canvas transform state: the entire map pans and zooms as one surface.
  var scale by remember { mutableStateOf(0.82f) }
  var pan by remember { mutableStateOf(Offset.Zero) }

  // Per-card position overrides (user can drag cards); persisted so the layout
  // is restored on next open instead of being rebuilt from scratch.
  val density = LocalDensity.current
  val context = LocalContext.current
  val layoutPrefs = remember { context.getSharedPreferences("construction_studio_layout", android.content.Context.MODE_PRIVATE) }
  val nodeOffsets = remember { mutableStateMapOf<String, Offset>() }
  var showSearch by remember { mutableStateOf(false) }
  // Id of the card whose output port was tapped; drives the "direct connection" picker.
  var connectFromId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    layoutPrefs.all.forEach { (key, value) ->
      val parts = (value as? String)?.split(",")
      val px = parts?.getOrNull(0)?.toFloatOrNull()
      val py = parts?.getOrNull(1)?.toFloatOrNull()
      if (px != null && py != null) nodeOffsets[key] = Offset(px, py)
    }
  }

  fun requestTraffic() {
    if (requestInFlight) return
    requestInFlight = true
    loading = true
    actions.loadTrafficRules { loaded ->
      if (loaded != null) {
        // Busy/preparing keeps the last good graph visible and only updates header state.
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

  val primary = MaterialTheme.colorScheme.primary
  val tertiary = MaterialTheme.colorScheme.tertiary
  val vpnColor = Color(0xFF22C55E)
  val appListLabel = stringResource(R.string.construction_studio_app_list)
  val internetLabel = stringResource(R.string.construction_studio_internet)
  val appsWord = stringResource(R.string.construction_studio_apps)

  val graph = buildStudioGraph(
    groups = groups,
    vpnItems = vpnItems,
    appListLabel = appListLabel,
    internetLabel = internetLabel,
    appsWord = appsWord,
    primary = primary,
    tertiary = tertiary,
    vpnColor = vpnColor,
    programName = { id -> displayProgramName(programs, id) },
    onOpenGroupApps = { selectedGroup = it },
    onOpenGroupSettings = { group ->
      val routeProgramId = normalizeRouteProgramId(group.programId)
      val profile = group.profile
      if (!profile.isNullOrBlank()) onOpenProfile(routeProgramId, profile) else onOpenProgram(routeProgramId)
    },
    onOpenVpnApps = { selectedVpn = it },
    onOpenVpnSettings = { vpn ->
      val routeProgramId = normalizeRouteProgramId(vpn.ownerProgram)
      if (vpn.profile.isNotBlank()) onOpenProfile(routeProgramId, vpn.profile) else onOpenProgram(routeProgramId)
    },
    onConnectFrom = { connectFromId = it },
  )

  val anyActive = graph.edges.any { it.active }

  // Animated dash phase makes active routes look like flowing traffic.
  val flow = rememberInfiniteTransition(label = "flow")
  val phase by flow.animateFloat(
    initialValue = 0f,
    targetValue = 28f,
    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
    label = "phase",
  )

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .clipToBounds()
      .pointerInput(Unit) {
        detectTransformGestures { _, panChange, zoomChange, _ ->
          scale = (scale * zoomChange).coerceIn(0.4f, 2.6f)
          pan += panChange
        }
      },
  ) {
    val viewportW = constraints.maxWidth.toFloat()
    val viewportH = constraints.maxHeight.toFloat()
    // The map: one transformed surface holding every node and edge.
    Box(
      modifier = Modifier
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
          translationX = pan.x
          translationY = pan.y
          transformOrigin = TransformOrigin(0f, 0f)
        }
        .size(graph.width.dp, graph.height.dp),
    ) {
      // Apply user drag overrides to node positions so edges follow the cards.
      val positionedNodes = graph.nodes.map { n ->
        val o = nodeOffsets[n.id]
        if (o != null) n.copy(x = n.x + o.x, y = n.y + o.y) else n
      }
      val positionedMap = positionedNodes.associateBy { it.id }
      StudioEdgeCanvas(graph = graph, nodeMap = positionedMap, phase = phase)
      positionedNodes.forEach { node ->
        Box(
          modifier = Modifier
            .offset { IntOffset(node.x.dp.roundToPx(), node.y.dp.roundToPx()) }
            .pointerInput(node.id) {
              // Dragging a card moves only that card (not the whole canvas);
              // consuming the gesture stops the parent pan/zoom from firing.
              detectDragGestures(
                onDragEnd = {
                  val o = nodeOffsets[node.id]
                  if (o != null) layoutPrefs.edit().putString(node.id, "${o.x},${o.y}").apply()
                },
              ) { change, dragAmount ->
                change.consume()
                val current = nodeOffsets[node.id] ?: Offset.Zero
                nodeOffsets[node.id] = current + Offset(dragAmount.x.toDp().value, dragAmount.y.toDp().value)
              }
            },
        ) {
          StudioGraphNode(node)
        }
      }
    }

    // Floating status bar (overlay, not part of the map).
    StudioTopBar(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .padding(top = topContentPadding + 10.dp, start = 12.dp, end = 12.dp)
        .fillMaxWidth(),
      report = report,
      loading = loading,
      activeRules = activeRules,
      totalRules = visibleRules.size,
      vpnCount = report.vpn.size,
      warningCount = report.warnings.size,
      onToggleWarnings = { showWarnings = !showWarnings },
    )

    // Zoom / reset controls (overlay).
    StudioZoomControls(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 14.dp, bottom = bottomContentPadding + 18.dp),
      onZoomIn = { scale = (scale * 1.2f).coerceAtMost(2.6f) },
      onZoomOut = { scale = (scale / 1.2f).coerceAtLeast(0.4f) },
      onReset = { scale = 0.82f; pan = Offset.Zero },
    )

    // Search: open a list of cards and center the map on the chosen one.
    StudioSearchControl(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = 14.dp, bottom = bottomContentPadding + 18.dp),
      expanded = showSearch,
      nodes = graph.nodes.filter { it.kind != StudioNodeKind.INTERNET },
      onToggle = { showSearch = !showSearch },
      onPick = { node ->
        val o = nodeOffsets[node.id] ?: Offset.Zero
        val cx = with(density) { (node.x + o.x + node.w / 2f).dp.toPx() }
        val cy = with(density) { (node.y + o.y + node.h / 2f).dp.toPx() }
        pan = Offset(viewportW / 2f - cx * scale, viewportH / 2f - cy * scale)
        showSearch = false
      },
    )

    if (graph.nodes.all { it.kind == StudioNodeKind.INTERNET } && !loading && !report.busy && !report.preparing) {
      StudioEmptyOverlay(modifier = Modifier.align(Alignment.Center))
    }

    val connectFrom = connectFromId?.let { id -> graph.nodes.find { it.id == id } }
    if (connectFrom != null) {
      StudioConnectSheet(
        from = connectFrom,
        targets = graph.nodes.filter { it.id != connectFrom.id && it.kind != StudioNodeKind.APP },
        onDismiss = { connectFromId = null },
        onPick = { connectFromId = null },
      )
    }

    if (showWarnings && report.warnings.isNotEmpty()) {
      StudioWarningsOverlay(
        warnings = report.warnings,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(start = 12.dp, end = 12.dp, bottom = bottomContentPadding + 18.dp),
        onClose = { showWarnings = false },
      )
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

// ---------------------------------------------------------------------------
// Layout: turn traffic routes into absolute node/edge coordinates (in dp).
// ---------------------------------------------------------------------------

private const val NODE_W = 204f
private const val NODE_H = 96f
private const val BACKEND_W = 188f
private const val BACKEND_H = 70f
private const val BACKEND_GAP = 12f
private const val COL_STEP = 312f
private const val BAND_GAP = 34f
private const val MARGIN_X = 28f
private const val MARGIN_TOP = 118f
private const val MARGIN_BOTTOM = 48f

private fun colX(index: Int): Float = MARGIN_X + index * COL_STEP

private fun buildStudioGraph(
  groups: List<StudioAppGroup>,
  vpnItems: List<ApiModels.VpnTraffic>,
  appListLabel: String,
  internetLabel: String,
  appsWord: String,
  primary: Color,
  tertiary: Color,
  vpnColor: Color,
  programName: (String) -> String,
  onOpenGroupApps: (StudioAppGroup) -> Unit,
  onOpenGroupSettings: (StudioAppGroup) -> Unit,
  onOpenVpnApps: (ApiModels.VpnTraffic) -> Unit,
  onOpenVpnSettings: (ApiModels.VpnTraffic) -> Unit,
  onConnectFrom: (String) -> Unit,
): StudioGraph {
  val nodes = mutableListOf<StudioNode>()
  val edges = mutableListOf<StudioEdge>()
  val backendColor = Color(0xFFF59E0B)

  // Programs that actually route through t2s get a dedicated t2s card placed
  // right after the app list (apps -> t2s -> program -> backends -> internet).
  val t2sFronted = setOf("wireproxy", "myproxy", "operaproxy", "tor", "myprogram", "sing-box")
  fun usesT2s(programId: String): Boolean = normalizeRouteProgramId(programId) in t2sFronted

  val hasAnyBackend = groups.any { g -> g.rules.any { it.backendPorts.isNotEmpty() } }
  val hasT2s = groups.any { usesT2s(it.programId) }
  val programCol = if (hasT2s) 2 else 1
  val backendCol = programCol + 1
  val internetCol = (if (hasAnyBackend) backendCol else programCol) + 1
  val internetX = colX(internetCol)

  var cursorY = MARGIN_TOP
  var totalBytes = 0L
  var anyActive = false

  groups.forEach { group ->
    val backends = group.rules.flatMap { it.backendPorts }.distinctBy { it.port }.take(6)
    val active = group.rules.any { it.active }
    if (active) anyActive = true
    val bytes = group.rules.sumOf { it.bytes }
    val packets = group.rules.sumOf { it.packets }
    totalBytes += bytes
    val appCount = group.rules.mapNotNull { it.uid }.distinct().size
    val accent = semanticAccent(group.rules.firstOrNull()?.semantic.orEmpty())

    val backendsHeight = if (backends.isEmpty()) 0f else backends.size * BACKEND_H + (backends.size - 1) * BACKEND_GAP
    val bandH = max(NODE_H, backendsHeight)
    val centerY = cursorY + bandH / 2f
    val rowTopY = centerY - NODE_H / 2f

    val appId = "app:${group.key}"
    val progId = "prog:${group.key}"
    val showT2s = usesT2s(group.programId)
    val t2sId = "t2s:${group.key}"
    val t2sColor = Color(0xFF06B6D4)
    nodes += StudioNode(
      id = appId,
      x = colX(0), y = rowTopY, w = NODE_W, h = NODE_H,
      title = appListShortTitle(group),
      subtitle = if (appCount > 0) "$appCount $appsWord" else (group.uidFile ?: appListLabel),
      icon = Icons.Filled.Apps, accent = primary, active = active,
      kind = StudioNodeKind.APP, onClick = { onOpenGroupApps(group) },
      warn = appCount == 0,
    )
    if (showT2s) {
      // t2s is the advanced hub: rules are pulled from here toward the program/backends.
      val t2sPorts = group.rules.mapNotNull { it.redirectPort }.distinct().joinToString(",")
      nodes += StudioNode(
        id = t2sId,
        x = colX(1), y = rowTopY, w = NODE_W, h = NODE_H,
        title = "t2s",
        subtitle = if (t2sPorts.isNotBlank()) "порт $t2sPorts • продвинутый узел" else "продвинутый узел",
        icon = Icons.Filled.Hub, accent = t2sColor, active = active,
        // t2s is a routing hub, not a program: tapping it (or its port) opens the
        // "where to direct the connection" picker. It must NOT open program
        // settings — only the program card does that.
        kind = StudioNodeKind.PROGRAM, onClick = { onConnectFrom(t2sId) },
        onPort = { onConnectFrom(t2sId) },
      )
    }
    nodes += StudioNode(
      id = progId,
      x = colX(programCol), y = rowTopY, w = NODE_W, h = NODE_H,
      title = programNodeTitle(programName(group.programId), group.profile),
      subtitle = ruleSummary(group.rules),
      icon = Icons.Filled.CallSplit, accent = accent, active = active,
      kind = StudioNodeKind.PROGRAM, onClick = { onOpenGroupSettings(group) },
      iconRes = programIconRes(normalizeRouteProgramId(group.programId)),
    )
    if (showT2s) {
      edges += StudioEdge(appId, t2sId, t2sColor, active)
      edges += StudioEdge(t2sId, progId, accent, active)
    } else {
      edges += StudioEdge(appId, progId, accent, active)
    }

    if (backends.isEmpty()) {
      edges += StudioEdge(progId, "internet", tertiary, active)
    } else {
      var by = centerY - backendsHeight / 2f
      backends.forEach { backend ->
        val bId = "backend:${group.key}:${backend.port}"
        nodes += StudioNode(
          id = bId,
          x = colX(backendCol), y = by, w = BACKEND_W, h = BACKEND_H,
          title = backend.label.ifBlank { "127.0.0.1:${backend.port}" },
          subtitle = listOfNotNull(backend.programId, backend.profile, backend.server)
            .joinToString(" / ").ifBlank { "backend ${backend.port}" },
          icon = Icons.Filled.CallSplit, accent = backendColor, active = active,
          kind = StudioNodeKind.BACKEND, onClick = null,
          iconRes = programIconRes(normalizeRouteProgramId(backend.programId.orEmpty())),
        )
        edges += StudioEdge(progId, bId, backendColor, active)
        edges += StudioEdge(bId, "internet", tertiary, active)
        by += BACKEND_H + BACKEND_GAP
      }
    }

    // Ignore unused locals warning for packets: surfaced via node subtitles.
    if (packets < 0L) anyActive = anyActive
    cursorY += bandH + BAND_GAP
  }

  vpnItems.forEach { vpn ->
    val active = vpn.totalBytes > 0L
    if (active) anyActive = true
    totalBytes += vpn.totalBytes
    val centerY = cursorY + NODE_H / 2f
    val rowTopY = centerY - NODE_H / 2f
    val key = "${vpn.ownerProgram}:${vpn.profile}:${vpn.tun}"
    val appId = "vpnapp:$key"
    val progId = "vpn:$key"
    nodes += StudioNode(
      id = appId,
      x = colX(0), y = rowTopY, w = NODE_W, h = NODE_H,
      title = "${vpn.apps.size} $appsWord",
      subtitle = vpn.uidRanges.joinToString(", ").ifBlank { vpn.tun },
      icon = Icons.Filled.Apps, accent = primary, active = active,
      kind = StudioNodeKind.APP, onClick = { onOpenVpnApps(vpn) },
    )
    nodes += StudioNode(
      id = progId,
      x = colX(programCol), y = rowTopY, w = NODE_W, h = NODE_H,
      title = "${programName(vpn.ownerProgram)} / ${vpn.profile}",
      subtitle = "netId ${vpn.netid} • ${vpn.tun} • ↓${formatBytes(vpn.rxBytes)} ↑${formatBytes(vpn.txBytes)}",
      icon = Icons.Filled.VpnKey, accent = vpnColor, active = active,
      kind = StudioNodeKind.VPN, onClick = { onOpenVpnSettings(vpn) },
      iconRes = programIconRes(normalizeRouteProgramId(vpn.ownerProgram)),
    )
    edges += StudioEdge(appId, progId, vpnColor, active)
    edges += StudioEdge(progId, "internet", tertiary, active)
    cursorY += NODE_H + BAND_GAP
  }

  val contentBottom = (cursorY - BAND_GAP).coerceAtLeast(MARGIN_TOP + NODE_H)
  val internetY = MARGIN_TOP + (contentBottom - MARGIN_TOP) / 2f - NODE_H / 2f
  nodes += StudioNode(
    id = "internet",
    x = internetX, y = internetY, w = NODE_W, h = NODE_H,
    title = internetLabel,
    subtitle = formatBytes(totalBytes),
    icon = Icons.Filled.Cloud, accent = tertiary, active = anyActive,
    kind = StudioNodeKind.INTERNET, onClick = null,
  )

  val width = internetX + NODE_W + MARGIN_X
  val height = contentBottom + MARGIN_BOTTOM
  return StudioGraph(nodes = nodes, edges = edges, width = width, height = height)
}

@Composable
private fun StudioEdgeCanvas(
  graph: StudioGraph,
  nodeMap: Map<String, StudioNode>,
  phase: Float,
) {
  val portRing = MaterialTheme.colorScheme.background
  Canvas(modifier = Modifier.size(graph.width.dp, graph.height.dp)) {
    graph.edges.forEach { edge ->
      val from = nodeMap[edge.fromId] ?: return@forEach
      val to = nodeMap[edge.toId] ?: return@forEach
      val sx = (from.x + from.w).dp.toPx()
      val sy = (from.y + from.h / 2f).dp.toPx()
      val ex = to.x.dp.toPx()
      val ey = (to.y + to.h / 2f).dp.toPx()
      // Always bow out horizontally so lines stay curved and route around cards
      // instead of collapsing into a straight line under them when cards are moved.
      val dx = max(abs(ex - sx) * 0.5f, 90f)
      val path = Path().apply {
        moveTo(sx, sy)
        cubicTo(sx + dx, sy, ex - dx, ey, ex, ey)
      }
      // Stopped (inactive) lines are grey and static; active lines flow in the route color.
      val grey = Color(0xFF64748B)
      val color = if (edge.active) edge.accent.copy(alpha = 0.92f) else grey.copy(alpha = 0.5f)
      val effect = if (edge.active) PathEffect.dashPathEffect(floatArrayOf(14f, 14f), -phase) else null
      drawPath(
        path = path,
        color = color,
        style = Stroke(
          width = if (edge.active) 4.5f else 2.5f,
          cap = StrokeCap.Round,
          pathEffect = effect,
        ),
      )
      // Connection ports: a small round bump where a line meets a card.
      val portColor = if (edge.active) edge.accent else grey
      val portRadius = if (edge.active) 7f else 6f
      drawCircle(color = portRing, radius = portRadius + 2.5f, center = Offset(sx, sy))
      drawCircle(color = portColor, radius = portRadius, center = Offset(sx, sy))
      drawCircle(color = portRing, radius = portRadius + 2.5f, center = Offset(ex, ey))
      drawCircle(color = portColor, radius = portRadius, center = Offset(ex, ey))
    }
  }
}

@Composable
private fun StudioGraphNode(node: StudioNode) {
  val shape = RoundedCornerShape(20.dp)
  val border = BorderStroke(
    if (node.active) 1.5.dp else 1.dp,
    node.accent.copy(alpha = if (node.active) 0.62f else 0.26f),
  )
  val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
  val body: @Composable () -> Unit = {
    Row(
      modifier = Modifier.fillMaxSize().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(shape = RoundedCornerShape(14.dp), color = node.accent.copy(alpha = 0.16f), contentColor = node.accent) {
        if (node.iconRes != null) {
          Icon(painterResource(node.iconRes), contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp), tint = node.accent)
        } else {
          Icon(node.icon, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp))
        }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(node.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(node.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
      if (node.warn) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFF59E0B)), contentAlignment = Alignment.Center) {
          Text("!", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.Black)
        }
      } else if (node.active) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(node.accent))
      }
    }
  }
  Box(modifier = Modifier.size(node.w.dp, node.h.dp)) {
    if (node.onClick != null) {
      Surface(modifier = Modifier.fillMaxSize(), shape = shape, color = container, border = border, onClick = node.onClick) { body() }
    } else {
      Surface(modifier = Modifier.fillMaxSize(), shape = shape, color = container, border = border) { body() }
    }
    if (node.onPort != null) {
      // Tappable output port (replaces drag-to-connect); sits on the right edge.
      Box(
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .offset(x = 11.dp)
          .size(22.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surface)
          .clickable(onClick = node.onPort),
        contentAlignment = Alignment.Center,
      ) {
        Box(Modifier.size(15.dp).clip(CircleShape).background(node.accent))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioConnectSheet(
  from: StudioNode,
  targets: List<StudioNode>,
  onDismiss: () -> Unit,
  onPick: (StudioNode) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp)
        .padding(bottom = 24.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Куда направить от «${from.title}»", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(
        "Выбери карточку-получателя. В одну программу можно направить несколько соединений.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
      )
      if (targets.isEmpty()) {
        Text("Нет доступных целей", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
      }
      targets.forEach { target ->
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          onClick = { onPick(target) },
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Surface(shape = RoundedCornerShape(12.dp), color = target.accent.copy(alpha = 0.16f), contentColor = target.accent) {
              if (target.iconRes != null) {
                Icon(painterResource(target.iconRes), contentDescription = null, modifier = Modifier.padding(7.dp).size(18.dp), tint = target.accent)
              } else {
                Icon(target.icon, contentDescription = null, modifier = Modifier.padding(7.dp).size(18.dp))
              }
            }
            Column(Modifier.weight(1f)) {
              Text(target.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(target.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StudioTopBar(
  modifier: Modifier,
  report: ApiModels.TrafficReport,
  loading: Boolean,
  activeRules: Int,
  totalRules: Int,
  vpnCount: Int,
  warningCount: Int,
  onToggleWarnings: () -> Unit,
) {
  val busy = report.busy || report.preparing
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
  ) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), contentColor = MaterialTheme.colorScheme.primary) {
          Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp))
        }
        Text(
          stringResource(R.string.construction_studio_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (loading || busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
        if (warningCount > 0) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onToggleWarnings,
          ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(15.dp))
              Text(warningCount.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StudioMetricPill(stringResource(R.string.construction_studio_rules), totalRules.toString())
        StudioMetricPill(stringResource(R.string.construction_studio_active), activeRules.toString())
        StudioMetricPill("VPN", vpnCount.toString())
      }
      val status = when {
        busy -> report.message.ifBlank { "Готовлю данные, подожди…" }
        report.error.isNotBlank() -> report.error
        else -> "Живая карта • автообновление 5 сек • тяни и масштабируй"
      }
      Text(
        status,
        style = MaterialTheme.typography.labelSmall,
        color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
        fontWeight = if (busy) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun StudioMetricPill(label: String, value: String) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
  ) {
    Text(
      "$label: $value",
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun StudioZoomControls(
  modifier: Modifier,
  onZoomIn: () -> Unit,
  onZoomOut: () -> Unit,
  onReset: () -> Unit,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    StudioZoomButton("+", onZoomIn)
    StudioZoomButton("−", onZoomOut)
    StudioZoomButton("⤢", onReset)
  }
}

@Composable
private fun StudioZoomButton(symbol: String, onClick: () -> Unit) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
    onClick = onClick,
  ) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
      Text(symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun StudioSearchControl(
  modifier: Modifier,
  expanded: Boolean,
  nodes: List<StudioNode>,
  onToggle: () -> Unit,
  onPick: (StudioNode) -> Unit,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (expanded) {
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier.widthIn(max = 280.dp),
      ) {
        Column(
          Modifier.padding(8.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (nodes.isEmpty()) {
            Text(
              "Нет карточек",
              modifier = Modifier.padding(8.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
          } else {
            nodes.forEach { node ->
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                onClick = { onPick(node) },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Box(Modifier.size(8.dp).clip(CircleShape).background(node.accent))
                  Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(node.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(node.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                  }
                }
              }
            }
          }
        }
      }
    }
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
      onClick = onToggle,
    ) {
      Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Icon(if (expanded) Icons.Filled.Close else Icons.Filled.Search, contentDescription = null)
      }
    }
  }
}

@Composable
private fun StudioEmptyOverlay(modifier: Modifier) {
  Surface(
    modifier = modifier.widthIn(max = 320.dp),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(stringResource(R.string.construction_studio_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(stringResource(R.string.construction_studio_empty_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
    }
  }
}

@Composable
private fun StudioWarningsOverlay(warnings: List<String>, modifier: Modifier, onClose: () -> Unit) {
  Surface(
    modifier = modifier.widthIn(max = 420.dp),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.construction_studio_warnings), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Surface(shape = CircleShape, color = Color.Transparent, onClick = onClose) {
          Text("✕", modifier = Modifier.padding(6.dp), fontWeight = FontWeight.Bold)
        }
      }
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
