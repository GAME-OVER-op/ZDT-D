package com.android.zdtd.service.worldmap.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.R
import com.android.zdtd.service.worldmap.model.ClosingSide
import com.android.zdtd.service.worldmap.model.PeerVisual
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDashboardScreen(
    viewModel: NetworkDashboardViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lastError = state.lastError
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(onBack = onBack)

    DisposableEffect(lifecycleOwner, viewModel) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.startMonitoring()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startMonitoring()
                Lifecycle.Event.ON_STOP -> viewModel.stopMonitoring()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopMonitoring()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                title = {
                    Text(text = stringResource(R.string.world_map_title))
                },
            )
        },
        containerColor = Color(0xFF070707),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070707))
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                DashboardHeader(
                    connectionsCount = state.peers.count { it.isActive },
                    isRootReady = state.isRootReady,
                    sessionDurationMs = state.sessionDurationMs,
                    sessionTrafficBytes = state.sessionTrafficBytes,
                )
            }

            item {
                NetworkMapCard(
                    peers = state.peers,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            item {
                if (lastError != null) {
                    Text(
                        text = lastError,
                        color = Color(0xFFB79A9A),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            items(state.peers, key = { it.id }) { peer ->
                PeerCard(peer = peer)
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    connectionsCount: Int,
    isRootReady: Boolean,
    sessionDurationMs: Long,
    sessionTrafficBytes: Long,
) {
    val displayedTrafficBytesState = remember { mutableLongStateOf(sessionTrafficBytes) }
    val displayedTrafficBytes = displayedTrafficBytesState.longValue

    LaunchedEffect(sessionTrafficBytes) {
        if (sessionTrafficBytes <= displayedTrafficBytesState.longValue) {
            displayedTrafficBytesState.longValue = sessionTrafficBytes
            return@LaunchedEffect
        }
        while (displayedTrafficBytesState.longValue < sessionTrafficBytes) {
            val current = displayedTrafficBytesState.longValue
            val delta = sessionTrafficBytes - current
            val step = maxOf(1L, delta / 10L)
            displayedTrafficBytesState.longValue = minOf(sessionTrafficBytes, current + step)
            delay(40L)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Router,
                contentDescription = null,
                tint = Color(0xFFFF5A52),
            )
            Text(
                text = pluralStringResource(R.plurals.world_map_connections_count, connectionsCount, connectionsCount),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = formatSessionDuration(sessionDurationMs),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formatTraffic(displayedTrafficBytes),
                color = Color(0xFFD2B5B5),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}


private fun formatSessionDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun formatTraffic(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0

    return when {
        value >= gb -> stringResource(R.string.world_map_traffic_gb, value / gb)
        value >= mb -> stringResource(R.string.world_map_traffic_mb, value / mb)
        value >= kb -> stringResource(R.string.world_map_traffic_kb, value / kb)
        else -> stringResource(R.string.world_map_traffic_b, bytes.coerceAtLeast(0L))
    }
}

@Composable
private fun NetworkMapCard(
    peers: List<PeerVisual>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF13090B)),
        shape = RoundedCornerShape(24.dp),
    ) {
        val infinite = rememberInfiniteTransition(label = "network-pulse")
        val pulse by infinite.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        val nowMs = rememberAnimationClockMillis()
        val startupSequenceStartMsState = remember { mutableLongStateOf(0L) }
        val startupSequenceStartMs = startupSequenceStartMsState.longValue

        LaunchedEffect(peers.isNotEmpty(), nowMs) {
            when {
                peers.isEmpty() && startupSequenceStartMs != 0L -> startupSequenceStartMsState.longValue = 0L
                peers.isNotEmpty() && startupSequenceStartMs == 0L && nowMs > 0L -> startupSequenceStartMsState.longValue = nowMs
            }
        }

        val elapsedMs = if (startupSequenceStartMs == 0L) 0L else (nowMs - startupSequenceStartMs).coerceAtLeast(0L)
        val waveMainDurationMs = 1180L
        val waveFadeDurationMs = 360L
        val bridgeBaseDelayMs = waveMainDurationMs + waveFadeDurationMs
        val bridgeRevealDurationMs = 1080L

        val waveAlpha = when {
            startupSequenceStartMs == 0L || peers.isEmpty() -> 0f
            elapsedMs <= waveMainDurationMs -> 1f
            else -> {
                val fade = ((elapsedMs - waveMainDurationMs).toFloat() / waveFadeDurationMs.toFloat()).coerceIn(0f, 1f)
                1f - smoothStep(fade)
            }
        }

        val displayPeers = remember(peers, nowMs, startupSequenceStartMs) {
            peers.map { peer ->
                if (!peer.isActive) {
                    peer
                } else {
                    val staggerMs = 120L + (peer.seed % 13) * 70L
                    val revealProgress = if (startupSequenceStartMs == 0L) {
                        0f
                    } else {
                        (((elapsedMs - bridgeBaseDelayMs - staggerMs).toFloat()) / bridgeRevealDurationMs.toFloat()).coerceIn(0f, 1f)
                    }
                    val easedReveal = smoothStep(revealProgress)
                    val activityGate = smoothStep(((easedReveal - 0.18f) / 0.82f).coerceIn(0f, 1f))
                    peer.copy(
                        visibility = min(peer.visibility, easedReveal),
                        txActivity = peer.txActivity * activityGate,
                        rxActivity = peer.rxActivity * activityGate,
                    )
                }
            }
        }

        val packetEngine = remember { PacketEngine() }
        val packetsByPeer = remember(displayPeers, nowMs) { packetEngine.update(displayPeers, nowMs) }
        val activePeerCount = displayPeers.count { it.isActive }
        val lineDensityScale = when {
            activePeerCount > 18 -> 0.36f
            activePeerCount > 12 -> 0.46f
            activePeerCount > 8 -> 0.60f
            else -> 0.92f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(392.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF150809), Color(0xFF050304)),
                    ),
                ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val user = Offset(size.width * 0.50f, size.height * 0.86f)
                val world = WorldFrame.from(size)

                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x18FF4D4D), Color.Transparent),
                    ),
                    size = size,
                )

                drawOceanBands(size = size)
                drawWorldGrid(world = world)
                drawWorldLand(world = world, pulse = pulse)

                displayPeers.forEach { peer ->
                    val peerOffset = Offset(size.width * peer.x, size.height * peer.y)
                    val route = buildPeerRoute(user = user, peer = peerOffset, size = size, seed = peer.seed)
                    val visibleRange = visibleRangeForPeer(peer)
                    val easedVisibility = easeOutCubic(peer.visibility)
                    val hotness = (0.70f + peer.activityScore * 0.85f).coerceIn(0.70f, 1.55f)
                    val lineAlpha = (((0.10f + 0.90f * easedVisibility) * lineDensityScale) * hotness).coerceIn(0.08f, 1f)

                    drawRoutePath(
                        points = route.points,
                        range = visibleRange,
                        color = Color(0x24C53F3F).copy(alpha = 0.24f * lineAlpha),
                        width = 0.72f,
                    )
                    drawRoutePath(
                        points = route.points,
                        range = visibleRange,
                        color = Color(0xFFFF6B63).copy(alpha = 0.64f * lineAlpha),
                        width = 0.22f,
                    )

                    drawPacketStreams(
                        route = route,
                        peer = peer,
                        visibleRange = visibleRange,
                        packets = packetsByPeer[peer.id].orEmpty(),
                    )
                    val markerAlpha = if (peer.isActive) {
                        ((0.05f + 0.95f * smoothStep(peer.visibility.coerceIn(0f, 1f))) * (0.82f + peer.activityScore * 0.42f)).coerceIn(0f, 1f)
                    } else {
                        (0.04f + 0.96f * easeOutCubic(peer.visibility)).coerceIn(0f, 1f)
                    }
                    drawPeerCluster(
                        center = peerOffset,
                        seed = peer.seed,
                        pulse = pulse,
                        alpha = markerAlpha,
                        activityScore = peer.activityScore,
                    )
                }

                if (waveAlpha > 0.01f) {
                    drawLoadingWaves(center = user, pulse = pulse, progressMs = elapsedMs, alpha = waveAlpha)
                }

                drawUserMarker(center = user, pulse = pulse)
            }

            Text(
                text = stringResource(R.string.world_map_you),
                color = Color(0xFFE53935),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
            )
        }
    }
}

private data class WorldFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun point(x: Float, y: Float): Offset = Offset(left + width * x, top + height * y)

    companion object {
        fun from(size: Size): WorldFrame {
            return WorldFrame(
                left = size.width * 0.035f,
                top = size.height * 0.09f,
                width = size.width * 0.93f,
                height = size.height * 0.68f,
            )
        }
    }
}

private data class RouteGeometry(
    val points: List<Offset>,
)

private data class RoutePosition(
    val point: Offset,
    val tangent: Offset,
)

private data class PacketParticle(
    val progress: Float,
    val alpha: Float,
    val scale: Float,
    val outgoing: Boolean,
    val seed: Int,
)

private class PacketEngine {
    private data class MutablePacket(
        var progress: Float,
        val speed: Float,
        val alpha: Float,
        val scale: Float,
        val outgoing: Boolean,
        val seed: Int,
    )

    private data class LaneState(
        val particles: MutableList<MutablePacket> = mutableListOf(),
        var accumulator: Float = 0f,
        var serial: Int = 0,
    )

    private data class PeerState(
        val outbound: LaneState = LaneState(),
        val inbound: LaneState = LaneState(),
    )

    private val peerStates = LinkedHashMap<String, PeerState>()
    private var lastUpdateMs: Long = 0L

    fun update(peers: List<PeerVisual>, nowMs: Long): Map<String, List<PacketParticle>> {
        val dt = if (lastUpdateMs == 0L) 0.016f else ((nowMs - lastUpdateMs).coerceIn(8L, 56L) / 1000f)
        lastUpdateMs = nowMs

        val activeIds = HashSet<String>(peers.size)
        val result = LinkedHashMap<String, List<PacketParticle>>(peers.size)
        val activePeerCount = peers.count { it.isActive }
        val densityScale = when {
            activePeerCount > 20 -> 0.56f
            activePeerCount > 14 -> 0.68f
            activePeerCount > 10 -> 0.82f
            else -> 1f
        }

        peers.forEach { peer ->
            activeIds += peer.id
            val state = peerStates.getOrPut(peer.id) { PeerState() }
            updateLane(state.outbound, peer = peer, activity = peer.txActivity, outgoing = true, dt = dt, densityScale = densityScale)
            updateLane(state.inbound, peer = peer, activity = peer.rxActivity, outgoing = false, dt = dt, densityScale = densityScale)
            result[peer.id] = buildList {
                state.outbound.particles.forEach { add(it.snapshot()) }
                state.inbound.particles.forEach { add(it.snapshot()) }
            }
        }

        val iterator = peerStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in activeIds) {
                if (entry.value.outbound.particles.isEmpty() && entry.value.inbound.particles.isEmpty()) {
                    iterator.remove()
                }
            }
        }
        return result
    }

    private fun updateLane(
        lane: LaneState,
        peer: PeerVisual,
        activity: Float,
        outgoing: Boolean,
        dt: Float,
        densityScale: Float,
    ) {
        val clampedActivity = activity.coerceIn(0f, 1f)
        val appearFactor = (peer.visibility.coerceIn(0f, 1f) * 1.15f).coerceIn(0f, 1f)
        val spawnRateBase = when {
            !peer.isActive -> 0f
            clampedActivity < 0.08f -> 0.34f
            else -> 0.38f + clampedActivity * (if (outgoing) 1.9f else 1.65f)
        }
        val spawnRate = spawnRateBase * densityScale * (0.35f + 0.65f * appearFactor)
        val maxParticlesBase = when {
            clampedActivity >= 0.82f -> 4
            clampedActivity >= 0.44f -> 3
            clampedActivity >= 0.16f -> 2
            else -> 1
        }
        val maxParticles = when {
            densityScale < 0.60f -> minOf(2, maxParticlesBase)
            densityScale < 0.75f -> minOf(3, maxParticlesBase)
            else -> maxParticlesBase
        }

        lane.accumulator += spawnRate * dt
        if (peer.isActive && lane.particles.isEmpty() && lane.accumulator >= 0.42f) {
            lane.accumulator -= 0.42f
            lane.particles += spawnParticle(peer = peer, outgoing = outgoing, activity = clampedActivity, serial = lane.serial++)
        }
        while (lane.accumulator >= 1f && lane.particles.size < maxParticles) {
            lane.accumulator -= 1f
            lane.particles += spawnParticle(peer = peer, outgoing = outgoing, activity = clampedActivity, serial = lane.serial++)
        }
        lane.accumulator = lane.accumulator.coerceIn(0f, 1.25f)

        val iterator = lane.particles.iterator()
        while (iterator.hasNext()) {
            val packet = iterator.next()
            packet.progress += packet.speed * dt
            if (packet.progress >= 1f) {
                iterator.remove()
            }
        }
    }

    private fun spawnParticle(
        peer: PeerVisual,
        outgoing: Boolean,
        activity: Float,
        serial: Int,
    ): MutablePacket {
        val serialSeed = peer.seed + serial * 17 + if (outgoing) 11 else 23
        val jitter = (((serialSeed % 9) - 4) * 0.0045f)
        val protocolBoost = if (peer.protocol.startsWith("udp")) 0.015f else 0f
        val speed = ((if (outgoing) 0.15f else 0.13f) + activity * (if (outgoing) 0.095f else 0.082f) + protocolBoost + jitter)
            .coerceIn(0.11f, 0.31f)
        val alpha = (0.42f + activity * 0.42f + if (outgoing) 0.06f else 0f).coerceIn(0.36f, 0.9f)
        val scale = (0.84f + activity * 0.20f + ((serialSeed % 5) - 2) * 0.015f).coerceIn(0.76f, 1.08f)
        return MutablePacket(
            progress = 0f,
            speed = speed,
            alpha = alpha,
            scale = scale,
            outgoing = outgoing,
            seed = serialSeed,
        )
    }

    private fun MutablePacket.snapshot(): PacketParticle = PacketParticle(
        progress = progress,
        alpha = alpha,
        scale = scale,
        outgoing = outgoing,
        seed = seed,
    )
}

private fun DrawScope.drawOceanBands(size: Size) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x18D93636), Color.Transparent),
            center = Offset(size.width * 0.50f, size.height * 0.46f),
            radius = size.minDimension * 0.47f,
        ),
        radius = size.minDimension * 0.47f,
        center = Offset(size.width * 0.50f, size.height * 0.46f),
    )
}

private fun DrawScope.drawWorldGrid(world: WorldFrame) {
    repeat(4) { index ->
        val t = (index + 1) / 5f
        val y = world.top + world.height * (0.14f + t * 0.70f)
        drawLine(
            color = Color(0x16B84848),
            start = Offset(world.left + world.width * 0.03f, y),
            end = Offset(world.left + world.width * 0.97f, y),
            strokeWidth = 0.8f,
            cap = StrokeCap.Round,
        )
    }

    repeat(6) { index ->
        val t = (index + 1) / 7f
        val x = world.left + world.width * t
        drawLine(
            color = Color(0x12A43A3A),
            start = Offset(x, world.top + world.height * 0.10f),
            end = Offset(x, world.top + world.height * 0.86f),
            strokeWidth = 0.7f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawWorldLand(
    world: WorldFrame,
    pulse: Float,
) {
    WorldLandData.polygons.forEach { coords ->
        val path = polygonPath(world, coords)
        drawPath(
            path = path,
            color = Color(0x14A53131),
        )
        drawPath(
            path = path,
            color = Color(0xFF5C1414).copy(alpha = 0.28f),
            style = Stroke(width = 1.05f + pulse * 0.04f, cap = StrokeCap.Round),
        )
        drawPath(
            path = path,
            color = Color(0xFFFF847C).copy(alpha = 0.18f + pulse * 0.05f),
            style = Stroke(width = 0.52f + pulse * 0.06f, cap = StrokeCap.Round),
        )
    }
}

private fun polygonPath(world: WorldFrame, coords: IntArray): Path {
    val path = Path()
    if (coords.isEmpty()) return path
    path.moveTo(
        world.left + world.width * (coords[0] / 10000f),
        world.top + world.height * (coords[1] / 10000f),
    )
    var index = 2
    while (index < coords.size) {
        path.lineTo(
            world.left + world.width * (coords[index] / 10000f),
            world.top + world.height * (coords[index + 1] / 10000f),
        )
        index += 2
    }
    path.close()
    return path
}

private fun buildPeerRoute(
    user: Offset,
    peer: Offset,
    size: Size,
    seed: Int,
): RouteGeometry {
    val dx = peer.x - user.x
    val dy = peer.y - user.y
    val direction = sign(dx).takeIf { it != 0f } ?: 1f
    val lateral = smoothStep((abs(dx) / size.width).coerceIn(0f, 1f))
    val distanceFactor = (distance(user, peer) / size.minDimension).coerceIn(0f, 1f)
    val apex = Offset(
        x = (user.x + peer.x) * 0.5f + direction * size.width * (0.015f + 0.055f * lateral) + ((seed % 9) - 4) * 0.0012f * size.width,
        y = min(user.y, peer.y) - size.height * (0.045f + 0.11f * distanceFactor + 0.015f * (1f - lateral)),
    )
    val control1 = linearPoint(user, apex, 0.58f)
    val control2 = linearPoint(peer, apex, 0.58f)

    val sampled = buildList {
        val steps = (48 + (28f * distanceFactor)).toInt().coerceIn(48, 76)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            add(cubicPoint(user, control1, control2, peer, t))
        }
    }
    return RouteGeometry(points = sampled)
}

private fun cubicPoint(start: Offset, control1: Offset, control2: Offset, end: Offset, t: Float): Offset {
    val oneMinus = 1f - t
    val oneMinus2 = oneMinus * oneMinus
    val t2 = t * t
    return Offset(
        x = start.x * oneMinus2 * oneMinus + 3f * control1.x * oneMinus2 * t + 3f * control2.x * oneMinus * t2 + end.x * t2 * t,
        y = start.y * oneMinus2 * oneMinus + 3f * control1.y * oneMinus2 * t + 3f * control2.y * oneMinus * t2 + end.y * t2 * t,
    )
}

private fun visibleRangeForPeer(peer: PeerVisual): ClosedFloatingPointRange<Float> {
    val eased = easeInOut(peer.visibility)
    return if (peer.isActive) {
        0f..eased
    } else {
        when (peer.closingSide) {
            ClosingSide.LOCAL -> (1f - eased).coerceIn(0f, 1f)..1f
            ClosingSide.REMOTE -> 0f..eased.coerceIn(0f, 1f)
            ClosingSide.UNKNOWN, ClosingSide.NONE -> 0f..eased.coerceIn(0f, 1f)
        }
    }
}

private fun DrawScope.drawRoutePath(
    points: List<Offset>,
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    width: Float,
) {
    val trimmed = trimPoints(points, range.start, range.endInclusive)
    if (trimmed.size < 2) return
    drawPath(
        path = pathFromPoints(trimmed),
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

private fun trimPoints(points: List<Offset>, startFraction: Float, endFraction: Float): List<Offset> {
    if (points.size < 2) return emptyList()
    val start = startFraction.coerceIn(0f, 1f)
    val end = endFraction.coerceIn(0f, 1f)
    if (end <= start) return emptyList()

    val segments = FloatArray(points.size - 1)
    var total = 0f
    for (i in 0 until points.lastIndex) {
        val len = distance(points[i], points[i + 1])
        segments[i] = len
        total += len
    }
    if (total <= 0f) return emptyList()

    val fromDistance = total * start
    val toDistance = total * end
    var cursor = 0f
    val output = mutableListOf<Offset>()

    for (i in 0 until points.lastIndex) {
        val segStart = points[i]
        val segEnd = points[i + 1]
        val segLength = segments[i]
        val nextCursor = cursor + segLength
        if (nextCursor < fromDistance) {
            cursor = nextCursor
            continue
        }
        if (cursor > toDistance) break

        val localStart = ((fromDistance - cursor) / segLength).coerceIn(0f, 1f)
        val localEnd = ((toDistance - cursor) / segLength).coerceIn(0f, 1f)
        if (localEnd <= 0f || localStart >= 1f) {
            cursor = nextCursor
            continue
        }

        val startPoint = linearPoint(segStart, segEnd, localStart)
        val endPoint = linearPoint(segStart, segEnd, localEnd)
        if (output.isEmpty()) output += startPoint
        if (distance(output.last(), startPoint) > 0.15f) output += startPoint
        output += endPoint
        cursor = nextCursor
    }
    return output
}

private fun pathFromPoints(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { point ->
        path.lineTo(point.x, point.y)
    }
    return path
}

private fun DrawScope.drawPacketStreams(
    route: RouteGeometry,
    peer: PeerVisual,
    visibleRange: ClosedFloatingPointRange<Float>,
    packets: List<PacketParticle>,
) {
    val visibility = easeOutCubic(peer.visibility)
    if (visibility <= 0.20f) return
    val span = (visibleRange.endInclusive - visibleRange.start).coerceAtLeast(0f)
    if (span <= 0.01f || packets.isEmpty()) return

    packets.sortedBy { it.progress }.forEach { packet ->
        val routeFraction = if (packet.outgoing) {
            interpolateRange(visibleRange, packet.progress)
        } else {
            interpolateRange(visibleRange, 1f - packet.progress)
        }
        val position = pointAndTangentOnRoute(route.points, routeFraction)
        drawPacketBundle(
            position = position,
            alpha = packet.alpha * packetEnvelope(packet.progress) * visibility,
            seed = packet.seed,
            packetScale = packet.scale,
            outgoing = packet.outgoing,
        )
    }
}

private fun packetEnvelope(phase: Float): Float {
    val p = phase.coerceIn(0f, 1f)
    val enter = smoothStep((p / 0.16f).coerceIn(0f, 1f))
    val exit = smoothStep(((1f - p) / 0.20f).coerceIn(0f, 1f))
    return (enter * exit).coerceIn(0f, 1f)
}

private fun interpolateRange(range: ClosedFloatingPointRange<Float>, t: Float): Float {
    return (range.start + (range.endInclusive - range.start) * t).coerceIn(0f, 1f)
}

private fun pointAndTangentOnRoute(points: List<Offset>, fraction: Float): RoutePosition {
    if (points.isEmpty()) return RoutePosition(Offset.Zero, Offset(0f, -1f))
    if (points.size == 1) return RoutePosition(points.first(), Offset(0f, -1f))
    val clamped = fraction.coerceIn(0f, 1f)
    val scaled = clamped * points.lastIndex
    val index = scaled.toInt().coerceIn(0, points.lastIndex - 1)
    val localT = scaled - index
    val start = points[index]
    val end = points[index + 1]
    val tangent = normalize(end - start)
    return RoutePosition(
        point = linearPoint(start, end, localT),
        tangent = if (tangent == Offset.Zero) Offset(0f, -1f) else tangent,
    )
}

private fun DrawScope.drawPacketBundle(
    position: RoutePosition,
    alpha: Float,
    seed: Int,
    packetScale: Float,
    outgoing: Boolean,
) {
    if (alpha <= 0.01f) return

    val tangent = if (outgoing) position.tangent else position.tangent * -1f
    val normal = perpendicular(tangent)
    val glowColor = if (outgoing) Color(0xFFFF6D61) else Color(0xFFFFC1BA)
    val coreColor = if (outgoing) Color(0xFFFFF6F5) else Color(0xFFFFE7E3)
    val head = position.point
    val tailLength = (if (outgoing) 13.2f else 9.8f + (seed % 4) * 0.9f) * packetScale
    val tail = head - tangent * tailLength

    drawLine(
        color = glowColor.copy(alpha = alpha * 0.26f),
        start = tail,
        end = head,
        strokeWidth = 2.8f * packetScale,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = coreColor.copy(alpha = alpha),
        start = tail + tangent * (tailLength * 0.40f),
        end = head,
        strokeWidth = 0.88f * packetScale,
        cap = StrokeCap.Round,
    )

    repeat(2) { index ->
        val offset = 2.7f * index * packetScale
        val lateral = ((index - 0.5f) * (if (outgoing) 0.54f else 0.78f) + sin((seed * 0.27f + index).toDouble()).toFloat() * 0.12f) * packetScale
        val point = head - tangent * offset + normal * lateral
        drawCircle(
            color = glowColor.copy(alpha = alpha * (0.28f - index * 0.07f).coerceAtLeast(0.12f)),
            radius = (1.85f - index * 0.22f) * packetScale,
            center = point,
        )
        drawCircle(
            color = coreColor.copy(alpha = alpha * (1f - index * 0.18f).coerceAtLeast(0.52f)),
            radius = (0.88f - index * 0.10f).coerceAtLeast(0.44f) * packetScale,
            center = point,
        )
    }
}

private fun wrapped(value: Float): Float {
    val raw = value % 1f
    return if (raw < 0f) raw + 1f else raw
}

private fun DrawScope.drawPeerCluster(
    center: Offset,
    seed: Int,
    pulse: Float,
    alpha: Float,
    activityScore: Float = 0f,
) {
    val glowBoost = (0.82f + activityScore * 1.05f).coerceIn(0.82f, 1.95f)
    drawCircle(
        color = Color(0x22FF4A42).copy(alpha = (0.34f * alpha * glowBoost).coerceAtMost(1f)),
        radius = 2.9f + pulse * 0.55f + activityScore * 2.1f,
        center = center,
    )

    clusterOffsets(seed).forEachIndexed { index, offset ->
        val point = center + offset
        val radius = 0.50f + ((seed + index) % 3) * 0.10f + activityScore * 0.20f
        drawCircle(
            color = Color(0x44FF7066).copy(alpha = (0.26f * alpha * glowBoost).coerceAtMost(1f)),
            radius = radius * 1.42f,
            center = point,
        )
        drawCircle(
            color = Color(0xFFFFF0EE).copy(alpha = alpha),
            radius = radius,
            center = point,
        )
    }
}

private fun DrawScope.drawLoadingWaves(
    center: Offset,
    pulse: Float,
    progressMs: Long,
    alpha: Float,
) {
    repeat(4) { index ->
        val phase = (((progressMs % 2550L).toFloat() / 2550f) + index * 0.19f) % 1f
        val eased = easeOutCubic(phase)
        val ringAlpha = (0.24f * (1f - eased) * alpha).coerceIn(0f, 0.24f)
        drawCircle(
            color = Color(0xFFE53935).copy(alpha = ringAlpha),
            radius = 12f + eased * (78f + index * 14f) + pulse * 2.5f,
            center = center,
            style = Stroke(width = (1.2f + (1f - eased) * 0.95f) * alpha.coerceAtLeast(0.45f)),
        )
    }
}

private fun DrawScope.drawUserMarker(
    center: Offset,
    pulse: Float,
) {
    drawCircle(
        color = Color(0x22FF4A42),
        radius = 18f + 5f * pulse,
        center = center,
    )
    drawCircle(
        color = Color(0x66FF4A42),
        radius = 8f + 2f * pulse,
        center = center,
    )
    drawCircle(
        color = Color(0xFFE53935),
        radius = 4.3f,
        center = center,
    )
}

private fun clusterOffsets(seed: Int): List<Offset> {
    val count = 4 + (seed % 3)
    val radius = 1.7f + (seed % 4) * 0.22f
    return List(count) { index ->
        val angle = (index.toFloat() / count.toFloat()) * 2f * PI + (seed % 360) * 0.07f
        val offsetRadius = radius * (0.52f + (index % 3) * 0.16f)
        Offset(
            x = (cos(angle) * offsetRadius).toFloat(),
            y = (sin(angle) * offsetRadius).toFloat(),
        )
    }
}



@Composable
private fun PeerCard(peer: PeerVisual) {
    val closingLabel = when (peer.closingSide) {
        ClosingSide.LOCAL -> stringResource(R.string.world_map_closing_local)
        ClosingSide.REMOTE -> stringResource(R.string.world_map_closing_remote)
        ClosingSide.UNKNOWN -> stringResource(R.string.world_map_closing_unknown)
        ClosingSide.NONE -> ""
    }
    val headerMeta = buildString {
        append(peer.protocol.uppercase())
        append(':')
        append(peer.remotePort)
        append(' ')
        append(peer.connectionState)
        if (!peer.isActive && closingLabel.isNotEmpty()) {
            append(" • ")
            append(closingLabel)
        }
    }
    val trafficMeta = stringResource(
        R.string.world_map_peer_traffic_meta,
        (peer.txActivity * 100f).roundToInt(),
        (peer.rxActivity * 100f).roundToInt(),
        peer.latencyMs,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1012).copy(alpha = 0.60f + peer.visibility * 0.40f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    tint = Color(0xFFFF5A52).copy(alpha = 0.45f + 0.55f * peer.visibility),
                    modifier = Modifier.size(17.dp),
                )
                Column(
                    modifier = Modifier.padding(start = 8.dp, end = 10.dp)
                ) {
                    Text(
                        text = peer.ip,
                        color = Color.White.copy(alpha = 0.68f + 0.32f * peer.visibility),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = headerMeta,
                        color = Color(0xFFE7D7D7).copy(alpha = 0.65f + 0.35f * peer.visibility),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = peer.geoLabel,
                    color = Color(0xFFFF8A80).copy(alpha = 0.62f + 0.38f * peer.visibility),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = trafficMeta,
                    color = Color(0xFFB79A9A).copy(alpha = 0.65f + 0.35f * peer.visibility),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun linearPoint(start: Offset, end: Offset, t: Float): Offset {
    return Offset(
        x = start.x + (end.x - start.x) * t,
        y = start.y + (end.y - start.y) * t,
    )
}

private fun distance(start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    return sqrt(dx * dx + dy * dy)
}

private fun normalize(vector: Offset): Offset {
    val len = sqrt(vector.x * vector.x + vector.y * vector.y)
    if (len <= 0.0001f) return Offset.Zero
    return Offset(vector.x / len, vector.y / len)
}

private fun perpendicular(vector: Offset): Offset = Offset(-vector.y, vector.x)
private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)
private fun easeInOut(value: Float): Float = smoothStep(value.coerceIn(0f, 1f))
private fun easeOutCubic(value: Float): Float = 1f - (1f - value.coerceIn(0f, 1f)).pow(3)

private operator fun Offset.plus(other: Offset): Offset = Offset(x + other.x, y + other.y)
private operator fun Offset.minus(other: Offset): Offset = Offset(x - other.x, y - other.y)
private operator fun Offset.times(value: Float): Offset = Offset(x * value, y * value)

@Composable
private fun rememberAnimationClockMillis(): Long {
    val frameTime = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameTime.longValue = it }
        }
    }
    return frameTime.longValue
}
