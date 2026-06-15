package com.android.zdtd.service.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateOffsetAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
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
import java.util.Locale
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.api.T2sApiClient
import com.android.zdtd.service.api.T2sPollResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private enum class StudioEdgeKind { NORMAL, DIRECT_FALLBACK }

private data class StudioEdge(
  val fromId: String,
  val toId: String,
  val accent: Color,
  val active: Boolean,
  val kind: StudioEdgeKind = StudioEdgeKind.NORMAL,
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
  var constructionEndpoints by remember { mutableStateOf<List<ApiModels.ConstructionProxyEndpointCandidate>>(emptyList()) }
  var t2sPolls by remember { mutableStateOf<Map<Int, T2sPollResult>>(emptyMap()) }
  var selectedGroup by remember { mutableStateOf<StudioAppGroup?>(null) }
  var selectedVpn by remember { mutableStateOf<ApiModels.VpnTraffic?>(null) }
  var showWarnings by remember { mutableStateOf(false) }

  // Canvas transform state: the entire map pans and zooms as one surface.
  // Animatable so button zoom, reset and "center on card" glide smoothly,
  // while finger gestures stay instant via snapTo.
  val scope = rememberCoroutineScope()
  val scaleAnim = remember { Animatable(0.82f) }
  val panX = remember { Animatable(0f) }
  val panY = remember { Animatable(0f) }
  val transformTween = tween<Float>(320, easing = FastOutSlowInEasing)

  // Per-card position overrides (user can drag cards); persisted so the layout
  // is restored on next open instead of being rebuilt from scratch.
  val density = LocalDensity.current
  val context = LocalContext.current
  val rootManager = remember(context) { RootConfigManager(context) }
  val layoutPrefs = remember { context.getSharedPreferences("construction_studio_layout", android.content.Context.MODE_PRIVATE) }
  val nodeOffsets = remember { mutableStateMapOf<String, Offset>() }
  var showSearch by remember { mutableStateOf(false) }
  // Id of the card whose output port was tapped; drives the "direct connection" picker.
  var connectFromId by remember { mutableStateOf<String?>(null) }
  var startingEndpointKey by remember { mutableStateOf<String?>(null) }
  var pendingStartCandidate by remember { mutableStateOf<ApiModels.ConstructionProxyEndpointCandidate?>(null) }
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

  fun requestConstructionEndpoints() {
    actions.loadConstructionProxyEndpoints { loaded ->
      if (loaded != null) constructionEndpoints = loaded
    }
  }

  LaunchedEffect(Unit) {
    while (isActive) {
      requestTraffic()
      requestConstructionEndpoints()
      delay(5_000)
    }
  }

  LaunchedEffect(connectFromId) {
    if (connectFromId != null) requestConstructionEndpoints()
  }

  LaunchedEffect(report.t2sInstances) {
    val ports = report.t2sInstances.map { it.webPort }.filter { it > 0 }.distinct()
    if (ports.isEmpty()) {
      t2sPolls = emptyMap()
      return@LaunchedEffect
    }
    while (isActive) {
      val next = withContext(Dispatchers.IO) {
        ports.mapNotNull { port ->
          runCatching { port to T2sApiClient(rootManager, port).poll() }.getOrNull()
        }.toMap()
      }
      t2sPolls = next
      delay(1_500)
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
    proxyEndpoints = report.proxyEndpoints,
    t2sInstances = report.t2sInstances,
    t2sPolls = t2sPolls,
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

  // One-time "draw-in" of routes when the map first builds (cards then lines).
  val buildProgress = remember { Animatable(0f) }
  val hasRealNodes = graph.nodes.any { it.kind != StudioNodeKind.INTERNET }
  LaunchedEffect(hasRealNodes) {
    if (hasRealNodes && buildProgress.value < 1f) {
      buildProgress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
  }

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
        detectTransformGestures { centroid, panChange, zoomChange, _ ->
          // Finger gestures are instant (snapTo); zoom keeps the pinch point fixed.
          scope.launch {
            val old = scaleAnim.value
            val newScale = (old * zoomChange).coerceIn(0.4f, 2.6f)
            val factor = newScale / old
            scaleAnim.snapTo(newScale)
            panX.snapTo(centroid.x - (centroid.x - panX.value) * factor + panChange.x)
            panY.snapTo(centroid.y - (centroid.y - panY.value) * factor + panChange.y)
          }
        }
      },
  ) {
    val viewportW = constraints.maxWidth.toFloat()
    val viewportH = constraints.maxHeight.toFloat()
    // The map: one transformed surface holding every node and edge.
    Box(
      modifier = Modifier
        .graphicsLayer {
          scaleX = scaleAnim.value
          scaleY = scaleAnim.value
          translationX = panX.value
          translationY = panY.value
          transformOrigin = TransformOrigin(0f, 0f)
        }
        .size(graph.width.dp, graph.height.dp),
    ) {
      // Edges follow animated card positions (1-frame lag is invisible). Until a
      // card reports its animated position, fall back to its raw layout + drag.
      val renderPos = remember { mutableStateMapOf<String, Offset>() }
      val edgeNodeMap = graph.nodes.associate { n ->
        val drag = nodeOffsets[n.id] ?: Offset.Zero
        n.id to n.copy(
          x = renderPos[n.id]?.x ?: (n.x + drag.x),
          y = renderPos[n.id]?.y ?: (n.y + drag.y),
        )
      }
      StudioEdgeCanvas(graph = graph, nodeMap = edgeNodeMap, phase = phase, buildProgress = buildProgress.value)

      graph.nodes.forEach { node ->
        key(node.id) {
          val drag = nodeOffsets[node.id] ?: Offset.Zero
          // Animate only the layout base; the live drag delta stays instant so
          // the card never lags behind the finger.
          val animatedBase by animateOffsetAsState(
            targetValue = Offset(node.x, node.y),
            animationSpec = tween(380, easing = FastOutSlowInEasing),
            label = "nodePos",
          )
          val finalPos = Offset(animatedBase.x + drag.x, animatedBase.y + drag.y)
          LaunchedEffect(finalPos) { renderPos[node.id] = finalPos }

          // Build/appear: fade + scale-up, staggered left-to-right by column.
          val appear = remember { Animatable(0f) }
          LaunchedEffect(node.id) {
            val col = (node.x / COL_STEP).toInt().coerceAtLeast(0)
            delay(col * 70L)
            appear.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
          }

          Box(
            modifier = Modifier
              .offset { IntOffset(finalPos.x.dp.roundToPx(), finalPos.y.dp.roundToPx()) }
              .graphicsLayer {
                alpha = appear.value
                val s = 0.8f + 0.2f * appear.value
                scaleX = s
                scaleY = s
              }
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
      onZoomIn = {
        val old = scaleAnim.value
        val ns = (old * 1.2f).coerceAtMost(2.6f)
        val f = ns / old
        val cx = viewportW / 2f
        val cy = viewportH / 2f
        scope.launch { scaleAnim.animateTo(ns, transformTween) }
        scope.launch { panX.animateTo(cx - (cx - panX.value) * f, transformTween) }
        scope.launch { panY.animateTo(cy - (cy - panY.value) * f, transformTween) }
      },
      onZoomOut = {
        val old = scaleAnim.value
        val ns = (old / 1.2f).coerceAtLeast(0.4f)
        val f = ns / old
        val cx = viewportW / 2f
        val cy = viewportH / 2f
        scope.launch { scaleAnim.animateTo(ns, transformTween) }
        scope.launch { panX.animateTo(cx - (cx - panX.value) * f, transformTween) }
        scope.launch { panY.animateTo(cy - (cy - panY.value) * f, transformTween) }
      },
      onReset = {
        scope.launch { scaleAnim.animateTo(0.82f, transformTween) }
        scope.launch { panX.animateTo(0f, transformTween) }
        scope.launch { panY.animateTo(0f, transformTween) }
      },
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
        val targetX = viewportW / 2f - cx * scaleAnim.value
        val targetY = viewportH / 2f - cy * scaleAnim.value
        val centerTween = tween<Float>(420, easing = FastOutSlowInEasing)
        scope.launch { panX.animateTo(targetX, centerTween) }
        scope.launch { panY.animateTo(targetY, centerTween) }
        showSearch = false
      },
    )

    if (graph.nodes.all { it.kind == StudioNodeKind.INTERNET } && !loading && !report.busy && !report.preparing) {
      StudioEmptyOverlay(modifier = Modifier.align(Alignment.Center))
    }

    val connectFrom = connectFromId?.let { id -> graph.nodes.find { it.id == id } }
    if (connectFrom != null) {
      val connectPort = connectFrom.id.substringAfter("t2s:", "").substringBefore(":").toIntOrNull()?.takeIf { it > 0 }
      val connectPoll = connectPort?.let { t2sPolls[it] }
      val connectCandidates = remember(report.proxyEndpoints, constructionEndpoints) {
        mergeConstructionCandidates(report.proxyEndpoints, constructionEndpoints)
      }

      fun finishT2sBackend(endpoint: ApiModels.ConstructionProxyEndpointCandidate, disconnect: Boolean) {
        val port = connectPort
        if (port == null) {
          connectFromId = null
          return
        }
        scope.launch {
          val (latestPolls, shouldRelease) = withContext(Dispatchers.IO) {
            val client = T2sApiClient(rootManager, port)
            val host = endpoint.host.ifBlank { "127.0.0.1" }
            val addr = "$host:${endpoint.port}"
            runCatching { if (disconnect) client.removeBackend(addr) else client.addBackend(host, endpoint.port) }
            runCatching { client.recheckBackends() }
            if (!disconnect) {
              emptyMap<Int, T2sPollResult>() to false
            } else {
              val latest = report.t2sInstances
                .map { it.webPort }
                .filter { it > 0 }
                .distinct()
                .mapNotNull { wp -> runCatching { wp to T2sApiClient(rootManager, wp).poll() }.getOrNull() }
                .toMap()
              latest to latest.values.none { poll ->
                poll.state.backends.any { backend -> backendAddrMatches(backend.addr, host, endpoint.port) }
              }
            }
          }
          if (latestPolls.isNotEmpty()) t2sPolls = latestPolls
          if (disconnect && shouldRelease) {
            actions.releaseConstructionProxyEndpoint(endpoint) {
              requestConstructionEndpoints()
              requestTraffic()
            }
          }
          startingEndpointKey = null
          connectFromId = null
          requestTraffic()
          requestConstructionEndpoints()
        }
      }

      StudioConnectSheet(
        from = connectFrom,
        candidates = connectCandidates,
        connectedAddrs = connectPoll?.state?.backends?.map { it.addr }?.toSet().orEmpty(),
        startingEndpointKey = startingEndpointKey,
        onDismiss = {
          pendingStartCandidate = null
          startingEndpointKey = null
          connectFromId = null
        },
        onPick = { candidate ->
          val host = candidate.host.ifBlank { "127.0.0.1" }
          val connected = connectPoll?.state?.backends?.any { it.addr == "$host:${candidate.port}" || it.addr.endsWith(":${candidate.port}") } == true
          when {
            startingEndpointKey != null && startingEndpointKey != candidate.key -> Unit
            connected -> finishT2sBackend(candidate, disconnect = true)
            candidate.running -> finishT2sBackend(candidate, disconnect = false)
            candidate.canStart -> {
              pendingStartCandidate = candidate
            }
            else -> connectFromId = null
          }
        },
      )

      pendingStartCandidate?.let { candidate ->
        ConfirmConstructionStartDialog(
          candidate = candidate,
          onDismiss = { pendingStartCandidate = null },
          onConfirm = {
            pendingStartCandidate = null
            startingEndpointKey = candidate.key
            actions.startConstructionProxyEndpoint(candidate) { result ->
              val endpoint = result?.endpoint ?: candidate
              if (result?.ok == true && result.started) {
                finishT2sBackend(endpoint, disconnect = false)
              } else {
                startingEndpointKey = null
                requestConstructionEndpoints()
              }
            }
          },
        )
      }
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
      actions = actions,
      programs = programs,
      onSaved = { requestTraffic() },
      onDismiss = { selectedGroup = null },
    )
  }
  selectedVpn?.let { vpn ->
    VpnAppsBottomSheet(
      vpn = vpn,
      programName = displayProgramName(programs, vpn.ownerProgram),
      actions = actions,
      programs = programs,
      onSaved = { requestTraffic() },
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

@Composable
private fun buildStudioGraph(
  groups: List<StudioAppGroup>,
  vpnItems: List<ApiModels.VpnTraffic>,
  proxyEndpoints: List<ApiModels.TrafficBackendPort>,
  t2sInstances: List<ApiModels.TrafficT2sInstance>,
  t2sPolls: Map<Int, T2sPollResult>,
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
  val internetCol = (if (hasAnyBackend || hasT2s) backendCol else programCol) + 1
  val internetX = colX(internetCol)

  var cursorY = MARGIN_TOP
  var totalBytes = 0L
  var anyActive = false

  val addedBackendNodes = mutableSetOf<String>()

  groups.forEach { group ->
    val showT2s = usesT2s(group.programId)
    val routePorts = group.rules.mapNotNull { it.redirectPort }.distinct()
    val t2sInstance = if (showT2s) findT2sInstanceForGroup(group, routePorts, t2sInstances) else null
    val t2sPoll = t2sInstance?.webPort?.let { t2sPolls[it] }
    val t2sBackendAddrs = t2sPoll?.state?.backends.orEmpty()
    val t2sBackendPorts = t2sBackendAddrs.mapNotNull { backendPortFromAddr(it.addr) }.distinct()
    val ruleBackends = group.rules.flatMap { it.backendPorts }
    val backends = mergeBackendCards(ruleBackends, proxyEndpoints, t2sBackendPorts).take(6)
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
    val t2sId = "t2s:${t2sInstance?.webPort ?: 0}:${group.key}"
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
      val t2sPorts = routePorts.joinToString(",")
      nodes += StudioNode(
        id = t2sId,
        x = colX(1), y = rowTopY, w = NODE_W, h = NODE_H,
        title = "t2s",
        subtitle = t2sSubtitle(t2sPorts, t2sPoll, t2sInstance),
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
      if (shouldShowDirectFallback(t2sPoll)) {
        edges += StudioEdge(t2sId, "internet", Color(0xFFF97316), hasDirectConnections(t2sPoll), StudioEdgeKind.DIRECT_FALLBACK)
      }
    } else {
      edges += StudioEdge(appId, progId, accent, active)
    }

    if (backends.isEmpty()) {
      if (!showT2s) edges += StudioEdge(progId, "internet", tertiary, active)
    } else {
      var by = centerY - backendsHeight / 2f
      backends.forEach { backend ->
        val bId = backendNodeId(backend)
        if (addedBackendNodes.add(bId)) {
          nodes += StudioNode(
            id = bId,
            x = colX(backendCol), y = by, w = BACKEND_W, h = BACKEND_H,
            title = backend.label.ifBlank { "127.0.0.1:${backend.port}" },
            subtitle = listOfNotNull(backend.programId, backend.profile, backend.server)
              .joinToString(" / ").ifBlank { "backend ${backend.port}" },
            icon = Icons.Filled.CallSplit, accent = backendColor, active = backendActive(t2sPoll, backend.port, active),
            kind = StudioNodeKind.BACKEND, onClick = null,
            iconRes = programIconRes(normalizeRouteProgramId(backend.programId.orEmpty())),
          )
          by += BACKEND_H + BACKEND_GAP
        }
        val beActive = backendActive(t2sPoll, backend.port, active)
        edges += StudioEdge(if (showT2s) t2sId else progId, bId, backendColor, beActive)
        edges += StudioEdge(bId, "internet", tertiary, beActive)
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

private fun findT2sInstanceForGroup(
  group: StudioAppGroup,
  routePorts: List<Int>,
  instances: List<ApiModels.TrafficT2sInstance>,
): ApiModels.TrafficT2sInstance? {
  return instances.firstOrNull { it.listenPort in routePorts }
    ?: instances.firstOrNull { normalizeRouteProgramId(it.program) == normalizeRouteProgramId(group.programId) && (group.profile.isNullOrBlank() || it.profile == group.profile) }
}

private fun backendPortFromAddr(addr: String): Int? = addr.substringAfterLast(':', "").toIntOrNull()

private fun mergeBackendCards(
  ruleBackends: List<ApiModels.TrafficBackendPort>,
  proxyEndpoints: List<ApiModels.TrafficBackendPort>,
  t2sBackendPorts: List<Int>,
): List<ApiModels.TrafficBackendPort> {
  val byPort = LinkedHashMap<Int, ApiModels.TrafficBackendPort>()
  (ruleBackends + t2sBackendPorts.mapNotNull { port -> proxyEndpoints.firstOrNull { it.port == port } ?: ApiModels.TrafficBackendPort(port = port, label = "127.0.0.1:$port") }).forEach { backend ->
    if (backend.port > 0) byPort.putIfAbsent(backend.port, backend)
  }
  return byPort.values.toList()
}

private fun backendNodeId(backend: ApiModels.TrafficBackendPort): String {
  val p = normalizeRouteProgramId(backend.programId.orEmpty()).ifBlank { "port" }
  val profile = backend.profile.orEmpty().ifBlank { "default" }
  val server = backend.server.orEmpty().ifBlank { backend.port.toString() }
  return "backend:$p:$profile:$server:${backend.port}"
}

private fun backendActive(poll: T2sPollResult?, port: Int, fallback: Boolean): Boolean {
  if (poll == null) return fallback
  return poll.state.backends.any { backendPortFromAddr(it.addr) == port && it.healthy }
}

private fun hasDirectConnections(poll: T2sPollResult?): Boolean = poll?.state?.connections?.any { it.mode.equals("direct", ignoreCase = true) } == true

private fun shouldShowDirectFallback(poll: T2sPollResult?): Boolean {
  val state = poll?.state ?: return false
  val hasGreen = state.backends.any { it.healthy }
  return !hasGreen || hasDirectConnections(poll)
}

@Composable
private fun t2sSubtitle(ports: String, poll: T2sPollResult?, meta: ApiModels.TrafficT2sInstance?): String {
  val mode = poll?.state?.runtime?.backendMode?.ifBlank { meta?.backendMode.orEmpty() }.orEmpty().ifBlank { "balance" }
  val backends = poll?.state?.backends.orEmpty()
  val green = backends.count { it.healthy }
  val direct = hasDirectConnections(poll)
  val base = if (ports.isNotBlank()) {
    stringResource(R.string.construction_studio_t2s_port, ports)
  } else {
    stringResource(R.string.construction_studio_t2s_advanced_node)
  }
  return when {
    poll == null -> stringResource(R.string.construction_studio_t2s_mode_only, base, mode)
    backends.isEmpty() -> stringResource(R.string.construction_studio_t2s_no_backend, base)
    green == 0 -> stringResource(R.string.construction_studio_t2s_all_backend_red, base)
    direct -> stringResource(R.string.construction_studio_t2s_direct_active, base, green, backends.size)
    else -> stringResource(R.string.construction_studio_t2s_mode_green, base, mode, green, backends.size)
  }
}

private fun programColor(programId: String): Color = when (normalizeRouteProgramId(programId)) {
  "mihomo" -> Color(0xFF8B5CF6)
  "mieru" -> Color(0xFF14B8A6)
  "sing-box" -> Color(0xFF3B82F6)
  "wireproxy" -> Color(0xFF22C55E)
  "tor" -> Color(0xFF7C3AED)
  "operaproxy" -> Color(0xFFEF4444)
  "myprogram" -> Color(0xFFF97316)
  else -> Color(0xFFF59E0B)
}

@Composable
private fun StudioEdgeCanvas(
  graph: StudioGraph,
  nodeMap: Map<String, StudioNode>,
  phase: Float,
  buildProgress: Float,
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
      val inactiveAlpha = if (edge.kind == StudioEdgeKind.DIRECT_FALLBACK) 0.74f else 0.5f
      val color = if (edge.active) edge.accent.copy(alpha = 0.92f) else if (edge.kind == StudioEdgeKind.DIRECT_FALLBACK) edge.accent.copy(alpha = inactiveAlpha) else grey.copy(alpha = inactiveAlpha)
      val progress = buildProgress.coerceIn(0f, 1f)
      // While the map builds, the route is "drawn in" from source to target.
      val drawn = if (progress >= 1f) {
        path
      } else {
        val measure = android.graphics.PathMeasure(path.asAndroidPath(), false)
        val segment = android.graphics.Path()
        measure.getSegment(0f, measure.length * progress, segment, true)
        segment.asComposePath()
      }
      // Dash flow only after the line has finished drawing in.
      val effect = if (edge.active && progress >= 1f) PathEffect.dashPathEffect(floatArrayOf(14f, 14f), -phase) else null
      drawPath(
        path = drawn,
        color = color,
        style = Stroke(
          width = if (edge.active) 4.5f else if (edge.kind == StudioEdgeKind.DIRECT_FALLBACK) 3.4f else 2.5f,
          cap = StrokeCap.Round,
          pathEffect = effect,
        ),
      )
      // Connection ports appear once the line is fully drawn.
      if (progress >= 1f) {
        val portColor = if (edge.active || edge.kind == StudioEdgeKind.DIRECT_FALLBACK) edge.accent else grey
        val portRadius = if (edge.active) 7f else 6f
        drawCircle(color = portRing, radius = portRadius + 2.5f, center = Offset(sx, sy))
        drawCircle(color = portColor, radius = portRadius, center = Offset(sx, sy))
        drawCircle(color = portRing, radius = portRadius + 2.5f, center = Offset(ex, ey))
        drawCircle(color = portColor, radius = portRadius, center = Offset(ex, ey))
      }
    }
  }
}

@Composable
private fun StudioGraphNode(node: StudioNode) {
  val shape = RoundedCornerShape(20.dp)
  // Active cards gently "breathe" via a pulsing border alpha.
  val pulseT = rememberInfiniteTransition(label = "pulse")
  val pulseAlpha by pulseT.animateFloat(
    initialValue = 0.42f,
    targetValue = 0.85f,
    animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    label = "pulseAlpha",
  )
  val border = BorderStroke(
    if (node.active) 1.5.dp else 1.dp,
    node.accent.copy(alpha = if (node.active) pulseAlpha else 0.26f),
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
  candidates: List<ApiModels.ConstructionProxyEndpointCandidate>,
  connectedAddrs: Set<String>,
  startingEndpointKey: String?,
  onDismiss: () -> Unit,
  onPick: (ApiModels.ConstructionProxyEndpointCandidate) -> Unit,
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
      Text(stringResource(R.string.construction_studio_connect_picker_title, from.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(
        stringResource(R.string.construction_studio_connect_picker_desc),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
      )
      if (candidates.isEmpty()) {
        Text(stringResource(R.string.construction_studio_connect_picker_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
      }
      candidates.forEach { endpoint ->
        val host = endpoint.host.ifBlank { "127.0.0.1" }
        val connected = connectedAddrs.any { it == "$host:${endpoint.port}" || it.endsWith(":${endpoint.port}") }
        val busy = startingEndpointKey == endpoint.key
        val accent = programColor(endpoint.programId)
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          color = if (connected) accent.copy(alpha = 0.14f) else if (!endpoint.running) Color(0xFFF97316).copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          border = if (connected) BorderStroke(1.dp, accent.copy(alpha = 0.55f)) else if (!endpoint.running) BorderStroke(1.dp, Color(0xFFF97316).copy(alpha = 0.45f)) else null,
          onClick = { if (!busy) onPick(endpoint) },
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.16f), contentColor = accent) {
              val iconRes = programIconRes(normalizeRouteProgramId(endpoint.programId))
              if (iconRes != null) {
                Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.padding(7.dp).size(18.dp), tint = accent)
              } else {
                Icon(Icons.Filled.CallSplit, contentDescription = null, modifier = Modifier.padding(7.dp).size(18.dp))
              }
            }
            Column(Modifier.weight(1f)) {
              Text(endpoint.label.ifBlank { "$host:${endpoint.port}" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(constructionEndpointSubtitle(endpoint, connected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when {
              busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
              connected -> {
                Box(Modifier.size(22.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                  Text("✓", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
              }
              !endpoint.running && endpoint.canStart -> Text(stringResource(R.string.construction_studio_start_badge), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF97316))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ConfirmConstructionStartDialog(
  candidate: ApiModels.ConstructionProxyEndpointCandidate,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  val title = listOfNotNull(candidate.programId, candidate.profile, candidate.server)
    .joinToString(" / ")
    .ifBlank { candidate.label.ifBlank { stringResource(R.string.construction_studio_proxy_endpoint_fallback) } }
  val body = buildString {
    append(stringResource(R.string.construction_studio_tool_start_body, title))
    if (candidate.appListEmpty) {
      append("\n\n")
      append(stringResource(R.string.construction_studio_tool_start_trigger_note))
    }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.construction_studio_tool_start_title)) },
    text = { Text(body) },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text(stringResource(R.string.construction_studio_start_badge)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
    },
  )
}

private fun mergeConstructionCandidates(
  running: List<ApiModels.TrafficBackendPort>,
  candidates: List<ApiModels.ConstructionProxyEndpointCandidate>,
): List<ApiModels.ConstructionProxyEndpointCandidate> {
  val byAddr = LinkedHashMap<String, ApiModels.ConstructionProxyEndpointCandidate>()
  fun putBest(candidate: ApiModels.ConstructionProxyEndpointCandidate) {
    val host = candidate.host.ifBlank { "127.0.0.1" }
    val key = "$host:${candidate.port}"
    val prev = byAddr[key]
    if (prev == null || constructionCandidatePriority(candidate) < constructionCandidatePriority(prev)) {
      byAddr[key] = candidate
    }
  }
  candidates.forEach(::putBest)
  running.forEach { endpoint ->
    val programId = endpoint.programId.orEmpty().ifBlank { stringResource(R.string.construction_studio_local_endpoint) }
    val key = "$programId:${endpoint.profile.orEmpty()}:${endpoint.server.orEmpty()}:${endpoint.port}"
    putBest(
      ApiModels.ConstructionProxyEndpointCandidate(
        key = key,
        programId = programId,
        profile = endpoint.profile,
        server = endpoint.server,
        host = "127.0.0.1",
        port = endpoint.port,
        label = endpoint.label.ifBlank { "127.0.0.1:${endpoint.port}" },
        kind = "socks5",
        enabled = true,
        running = true,
        canStart = false,
      )
    )
  }
  return byAddr.values.sortedWith(compareBy<ApiModels.ConstructionProxyEndpointCandidate> { !it.running }.thenBy { !it.canStart && !it.running }.thenBy { it.programId }.thenBy { it.profile.orEmpty() }.thenBy { it.server.orEmpty() }.thenBy { it.port })
}

private fun constructionCandidatePriority(candidate: ApiModels.ConstructionProxyEndpointCandidate): Int {
  val program = normalizeRouteProgramId(candidate.programId)
  return when {
    candidate.running && program != "myproxy" -> 0
    candidate.running -> 1
    candidate.canStart && candidate.enabled -> 2
    candidate.canStart -> 3
    else -> 8
  }
}

@Composable
private fun constructionEndpointSubtitle(endpoint: ApiModels.ConstructionProxyEndpointCandidate, connected: Boolean): String {
  val parts = mutableListOf<String>()
  parts += listOfNotNull(endpoint.programId, endpoint.profile, endpoint.server).joinToString(" / ").ifBlank { endpoint.kind }
  parts += "${endpoint.host.ifBlank { "127.0.0.1" }}:${endpoint.port}"
  parts += when {
    connected -> stringResource(R.string.construction_studio_endpoint_status_connected)
    endpoint.running -> stringResource(R.string.construction_studio_endpoint_status_running)
    endpoint.canStart && endpoint.appListEmpty -> stringResource(R.string.construction_studio_endpoint_status_empty_start)
    endpoint.canStart -> stringResource(R.string.construction_studio_endpoint_status_disabled_start)
    else -> stringResource(R.string.construction_studio_endpoint_status_unavailable)
  }
  return parts.joinToString(" • ")
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
        StudioMetricPill(stringResource(R.string.construction_studio_metric_vpn), vpnCount.toString())
      }
      val status = when {
        busy -> report.message.ifBlank { stringResource(R.string.construction_studio_preparing) }
        report.error.isNotBlank() -> report.error
        else -> stringResource(R.string.construction_studio_auto_refresh_hint)
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
              stringResource(R.string.construction_studio_no_cards),
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
  actions: ZdtdActions,
  programs: List<ApiModels.Program>,
  onSaved: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val byUid = remember(group) { group.rules.filter { it.uid != null }.groupBy { it.uid ?: 0 } }
  val iconCache = remember { mutableMapOf<String, ImageBitmap?>() }
  val listState = rememberLazyListState()
  var assignments by remember(group.key) { mutableStateOf<ApiModels.AppAssignmentsState?>(null) }
  var showPicker by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  var selectedPackages by remember(group.key) { mutableStateOf(groupPackages(group)) }
  val compact = rememberIsCompactWidth() || rememberIsShortHeight()

  LaunchedEffect(group.key) {
    actions.loadAppAssignments { data ->
      assignments = data ?: ApiModels.AppAssignmentsState()
      selectedPackages = findAssignmentForGroup(group, data)?.packages ?: groupPackages(group)
    }
  }

  val editableEntry = remember(assignments, group) { findAssignmentForGroup(group, assignments) }
  val editablePath = remember(assignments, group) { resolveEditableAppPath(group, assignments) }
  val canEdit = !editablePath.isNullOrBlank()

  if (showPicker && canEdit) {
    val targetPath = editablePath!!
    AppPickerSheet(
      title = stringResource(R.string.construction_studio_edit_list_title, appListTitle(group)),
      path = targetPath,
      actions = actions,
      programs = programs,
      initialSelected = selectedPackages,
      onDismiss = { showPicker = false },
      onSave = { newSel, removalsByPath ->
        showPicker = false
        saving = true
        selectedPackages = newSel
        val payload = if (newSel.isEmpty()) "" else newSel.sorted().joinToString("\n", postfix = "\n")
        val done: (Boolean) -> Unit = { ok ->
          saving = false
          if (ok) {
            selectedPackages = newSel
            onSaved()
          }
        }
        if (removalsByPath.isEmpty()) {
          actions.saveText(targetPath, payload, done)
        } else {
          actions.saveAppListResolvingConflicts(targetPath, payload, removalsByPath, done)
        }
      },
    )
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = if (rememberIsShortHeight()) 520.dp else 720.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
      item(key = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(appListTitle(group), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(programNodeTitle(programName, group.profile), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StudioEditAppsButton(
              compact = compact,
              enabled = canEdit && !saving,
              saving = saving,
              onClick = { showPicker = true },
            )
          }
          if (!canEdit) {
            Text(
              stringResource(R.string.construction_studio_list_path_missing),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            )
          }
        }
      }
      if (byUid.isEmpty()) {
        item(key = "empty") {
          Text(stringResource(R.string.construction_studio_no_apps), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
        }
      } else {
        items(byUid.toSortedMap().entries.toList(), key = { it.key }) { (uid, rules) ->
          AppTrafficRow(uid = uid, rules = rules, iconCache = iconCache)
        }
      }
    }
  }
}

@Composable
private fun StudioEditAppsButton(
  compact: Boolean,
  enabled: Boolean,
  saving: Boolean,
  onClick: () -> Unit,
) {
  val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.36f)
  Surface(
    shape = if (compact) CircleShape else RoundedCornerShape(14.dp),
    color = color,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    onClick = { if (enabled) onClick() },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
      if (!compact) Text(if (saving) "..." else stringResource(R.string.construction_studio_edit), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnAppsBottomSheet(
  vpn: ApiModels.VpnTraffic,
  programName: String,
  actions: ZdtdActions,
  programs: List<ApiModels.Program>,
  onSaved: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val iconCache = remember { mutableMapOf<String, ImageBitmap?>() }
  var showPicker by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  var selectedPackages by remember(vpn.ownerProgram, vpn.profile, vpn.tun) { mutableStateOf(vpnPackages(vpn)) }
  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
  val editablePath = remember(vpn.ownerProgram, vpn.profile) { deriveEditableVpnAppPath(vpn) }
  val canEdit = !editablePath.isNullOrBlank()

  if (showPicker && canEdit) {
    val targetPath = editablePath!!
    AppPickerSheet(
      title = stringResource(R.string.construction_studio_edit_list_title, programNodeTitle(programName, vpn.profile.takeIf { it.isNotBlank() })),
      path = targetPath,
      actions = actions,
      programs = programs,
      initialSelected = selectedPackages,
      onDismiss = { showPicker = false },
      onSave = { newSel, removalsByPath ->
        showPicker = false
        saving = true
        selectedPackages = newSel
        val payload = if (newSel.isEmpty()) "" else newSel.sorted().joinToString("\n", postfix = "\n")
        val done: (Boolean) -> Unit = { ok ->
          saving = false
          if (ok) {
            selectedPackages = newSel
            onSaved()
          }
        }
        if (removalsByPath.isEmpty()) {
          actions.saveText(targetPath, payload, done)
        } else {
          actions.saveAppListResolvingConflicts(targetPath, payload, removalsByPath, done)
        }
      },
    )
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = if (rememberIsShortHeight()) 520.dp else 720.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
      item(key = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              programNodeTitle(programName, vpn.profile.takeIf { it.isNotBlank() }),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.weight(1f),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            StudioEditAppsButton(
              compact = compact,
              enabled = canEdit && !saving,
              saving = saving,
              onClick = { showPicker = true },
            )
          }
          if (!canEdit) {
            Text(
              stringResource(R.string.construction_studio_vpn_list_path_missing),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            )
          }
        }
      }
      if (vpn.apps.isEmpty()) {
        item(key = "empty") {
          Text(stringResource(R.string.construction_studio_no_apps), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
        }
      } else {
        items(vpn.apps.sortedBy { it.uid }, key = { it.uid }) { app ->
          VpnAppRow(app = app, iconCache = iconCache)
        }
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

private fun groupPackages(group: StudioAppGroup): Set<String> =
  group.rules.flatMap { it.packages.ifEmpty { listOfNotNull(it.packageName) } }
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()

private fun resolveEditableAppPath(group: StudioAppGroup, data: ApiModels.AppAssignmentsState?): String? {
  return findAssignmentForGroup(group, data)?.path
    ?: deriveEditableAppPath(group)
    ?: group.uidFile?.let(::uidFileToEditableAppPath)
}

private fun findAssignmentForGroup(group: StudioAppGroup, data: ApiModels.AppAssignmentsState?): ApiModels.AppAssignmentEntry? {
  val lists = data?.lists.orEmpty()
  if (lists.isEmpty()) return null
  val program = normalizeRouteProgramId(group.programId)
  val profile = group.profile.orEmpty()
  val slot = group.slot.orEmpty().lowercase(Locale.ROOT)
  val uidFile = group.uidFile.orEmpty()
  val sameProgram = lists.filter { entry ->
    normalizeRouteProgramId(entry.programId) == program &&
      (profile.isBlank() || entry.profile.orEmpty() == profile)
  }
  return sameProgram.firstOrNull { entry ->
    slot.isNotBlank() && entry.slot.lowercase(Locale.ROOT) == slot
  } ?: sameProgram.firstOrNull { entry ->
    entry.slot.lowercase(Locale.ROOT) == "user"
  } ?: sameProgram.firstOrNull() ?: lists.firstOrNull { entry ->
    uidFile.isNotBlank() && (uidFile.endsWith("/" + entry.path.substringAfterLast('/')) || uidFile.contains(entry.path.substringAfter("working_folder/", entry.path)))
  }
}

private fun deriveEditableAppPath(group: StudioAppGroup): String? {
  val program = normalizeRouteProgramId(group.programId)
  val profile = group.profile?.takeIf { it.isNotBlank() }
  return when (program) {
    "tor" -> "/api/programs/tor/apps"
    "operaproxy" -> "/api/programs/operaproxy/apps/user"
    "sing-box", "wireproxy", "myproxy", "myprogram", "mihomo", "mieru", "openvpn", "amneziawg", "tun2socks", "myvpn" ->
      profile?.let { "/api/programs/$program/profiles/$it/apps/user" }
    else -> null
  }
}

private fun deriveEditableVpnAppPath(vpn: ApiModels.VpnTraffic): String? {
  val program = normalizeRouteProgramId(vpn.ownerProgram)
  val profile = vpn.profile.takeIf { it.isNotBlank() }
  return when (program) {
    "mihomo", "mieru", "openvpn", "amneziawg", "tun2socks", "myvpn" ->
      profile?.let { "/api/programs/$program/profiles/$it/apps/user" }
    else -> null
  }
}

private fun vpnPackages(vpn: ApiModels.VpnTraffic): Set<String> =
  vpn.apps.flatMap { it.packages.ifEmpty { listOfNotNull(it.packageName) } }
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()

private fun uidFileToEditableAppPath(uidFile: String): String? {
  val marker = "working_folder/"
  val idx = uidFile.indexOf(marker)
  if (idx < 0) return null
  val rel = uidFile.substring(idx)
  return when {
    rel.endsWith("/apps_out/user") -> rel.removeSuffix("_out/user").plus("/user")
    rel.contains("/apps_out/") -> rel.replace("/apps_out/", "/apps/")
    rel.contains("/apps/") -> rel
    else -> null
  }
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

private fun backendAddrMatches(addr: String, host: String, port: Int): Boolean {
  return addr == "$host:$port" || addr.endsWith(":$port")
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
