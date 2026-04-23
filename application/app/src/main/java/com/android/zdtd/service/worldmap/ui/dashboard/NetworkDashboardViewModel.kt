package com.android.zdtd.service.worldmap.ui.dashboard

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.zdtd.service.worldmap.WorldMapStrings
import com.android.zdtd.service.worldmap.model.ClosingSide
import com.android.zdtd.service.worldmap.model.ConnectionSample
import com.android.zdtd.service.worldmap.model.PeerVisual
import com.android.zdtd.service.worldmap.root.GeoLocationRepository
import com.android.zdtd.service.worldmap.root.PeerVisualMapper
import com.android.zdtd.service.worldmap.root.RootConnectionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

data class DashboardUiState(
    val isRootReady: Boolean = false,
    val isConnected: Boolean = false,
    val peers: List<PeerVisual> = emptyList(),
    val lastError: String? = null,
    val sessionDurationMs: Long = 0L,
    val sessionTrafficBytes: Long = 0L,
)

private const val POLL_INTERVAL_MS = 2_000L
private const val FRAME_INTERVAL_MS = 42L
private const val VISIBILITY_STEP = 0.016f
private const val ACTIVITY_STEP = 0.014f
private const val PACKET_DRAIN_MS = 1_650L

class NetworkDashboardViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val strings = WorldMapStrings(application.applicationContext)
    private val repository = RootConnectionRepository(strings)
    private val geoRepository = GeoLocationRepository(strings)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var animatorJob: Job? = null
    private val renderPeers = LinkedHashMap<String, RenderPeer>()
    private var rootReady = false
    private var lastError: String? = null
    private var lastSamples: List<ConnectionSample> = emptyList()
    private var previousQueueSnapshots = LinkedHashMap<String, QueueSnapshot>()
    private var sessionStartedAtMs = 0L
    private var lastTrafficSnapshotBytes: Long? = null
    private var sessionTrafficBytes = 0L


    private data class QueueSnapshot(
        val sendQueue: Long,
        val recvQueue: Long,
        val txCounterBytes: Long,
        val rxCounterBytes: Long,
        val protocol: String,
    )

    private fun enhancePeerActivity(mappedPeers: List<PeerVisual>): List<PeerVisual> {
        val nextSnapshots = LinkedHashMap<String, QueueSnapshot>(mappedPeers.size)
        val tuned = mappedPeers.map { peer ->
            val current = QueueSnapshot(
                sendQueue = peer.sendQueue,
                recvQueue = peer.recvQueue,
                txCounterBytes = peer.txCounterBytes,
                rxCounterBytes = peer.rxCounterBytes,
                protocol = peer.protocol,
            )
            val previous = previousQueueSnapshots[peer.id]
            nextSnapshots[peer.id] = current

            val sendDelta = if (previous == null) 0L else kotlin.math.abs(peer.sendQueue - previous.sendQueue)
            val recvDelta = if (previous == null) 0L else kotlin.math.abs(peer.recvQueue - previous.recvQueue)
            val txByteDelta = if (previous == null || peer.txCounterBytes < previous.txCounterBytes) 0L else (peer.txCounterBytes - previous.txCounterBytes)
            val rxByteDelta = if (previous == null || peer.rxCounterBytes < previous.rxCounterBytes) 0L else (peer.rxCounterBytes - previous.rxCounterBytes)

            val queueSignal = max(peer.txActivity, peer.rxActivity)
            val throughputSignal = throughputSignal(txByteDelta, rxByteDelta, peer.protocol)
            val combinedScore = when {
                txByteDelta > 0L || rxByteDelta > 0L -> (queueSignal * 0.18f + throughputSignal * 1.02f).coerceIn(0f, 1f)
                else -> (queueSignal * 0.48f + queueDeltaSignal(sendDelta, recvDelta) * 0.72f).coerceIn(0f, 0.82f)
            }

            val directionFromBytes = txByteDelta.toFloat() / (txByteDelta + rxByteDelta).coerceAtLeast(1L).toFloat()
            val directionFromQueue = peer.sendQueue.toFloat() / (peer.sendQueue + peer.recvQueue).coerceAtLeast(1L).toFloat()
            val txBias = when {
                txByteDelta > 0L || rxByteDelta > 0L -> directionFromBytes
                else -> directionFromQueue
            }.coerceIn(0.08f, 0.92f)
            val rxBias = (1f - txBias).coerceIn(0.08f, 0.92f)

            val tunedTx = max(peer.txActivity * 0.22f, combinedScore * txBias * 1.28f).coerceIn(0f, 1f)
            val tunedRx = max(peer.rxActivity * 0.22f, combinedScore * rxBias * 1.28f).coerceIn(0f, 1f)
            val label = strings.enhancedActivityLabel(combinedScore)

            peer.copy(
                txActivity = tunedTx,
                rxActivity = tunedRx,
                activityScore = combinedScore,
                activityLabel = label,
            )
        }
        previousQueueSnapshots = nextSnapshots
        return tuned
    }

    private fun queueDeltaSignal(sendDelta: Long, recvDelta: Long): Float {
        val delta = max(sendDelta, recvDelta).toFloat()
        if (delta <= 0f) return 0f
        val normalized = (ln(1f + delta) / ln(1f + 131_072f)).coerceIn(0f, 1f)
        return (0.08f + normalized * 0.92f).coerceIn(0f, 1f)
    }

    private fun throughputSignal(txByteDelta: Long, rxByteDelta: Long, protocol: String): Float {
        val dominant = max(txByteDelta, rxByteDelta).toFloat()
        if (dominant <= 0f) return 0f
        val windowRef = if (protocol.startsWith("tcp")) 524_288f else 131_072f
        val normalized = (ln(1f + dominant) / ln(1f + windowRef)).coerceIn(0f, 1f)
        return (0.12f + normalized * 0.88f).coerceIn(0f, 1f)
    }

    fun startMonitoring() {
        if (sessionStartedAtMs == 0L) {
            sessionStartedAtMs = SystemClock.elapsedRealtime()
        }
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshSnapshot()
                delay(POLL_INTERVAL_MS)
            }
        }
        if (animatorJob?.isActive != true) {
            animatorJob = viewModelScope.launch {
                while (isActive) {
                    val changed = animateFrame()
                    if (changed) emitUiState()
                    delay(FRAME_INTERVAL_MS)
                }
            }
        }
    }

    fun stopMonitoring() {
        pollingJob?.cancel()
        pollingJob = null
        animatorJob?.cancel()
        animatorJob = null
    }

    override fun onCleared() {
        stopMonitoring()
        super.onCleared()
    }

    private suspend fun refreshSnapshot() {
        rootReady = repository.checkRoot()
        if (!rootReady) {
            lastError = strings.rootUnavailable()
            previousQueueSnapshots.clear()
            markAllInactive(ClosingSide.UNKNOWN)
            emitUiState()
            return
        }

        val result = repository.loadConnections()
        result.onSuccess { samples ->
            lastError = null
            lastSamples = samples
            repository.loadTotalTrafficBytes()?.let { totalBytes ->
                val previous = lastTrafficSnapshotBytes
                if (previous != null && totalBytes >= previous) {
                    sessionTrafficBytes += (totalBytes - previous)
                }
                lastTrafficSnapshotBytes = totalBytes
            }
            val mappedPeers = enhancePeerActivity(
                PeerVisualMapper.map(
                    connections = samples,
                    locationForIp = { ip -> geoRepository.cached(ip) },
                    stateForIp = { ip -> geoRepository.stateFor(ip) },
                    strings = strings,
                )
            )
            syncPeers(mappedPeers)
            emitUiState()
            viewModelScope.launch {
                val geoCandidates = samples
                    .asSequence()
                    .filter { it.protocol in geoProtocols }
                    .map { it.remoteIp }
                    .distinct()
                    .take(MAX_GEO_REQUESTS_PER_CYCLE)
                    .toList()

                val changed = geoRepository.resolveMissing(geoCandidates)
                if (changed && lastSamples.isNotEmpty()) {
                    val remappedPeers = enhancePeerActivity(
                        PeerVisualMapper.map(
                            connections = lastSamples,
                            locationForIp = { ip -> geoRepository.cached(ip) },
                            stateForIp = { ip -> geoRepository.stateFor(ip) },
                            strings = strings,
                        )
                    )
                    syncPeers(remappedPeers)
                    emitUiState()
                }
            }
        }.onFailure { error ->
            lastError = error.message ?: strings.readConnectionsFailed()
            markAllInactive(ClosingSide.UNKNOWN)
            emitUiState()
        }
    }


    private fun syncPeers(freshPeers: List<PeerVisual>) {
        val activeIds = freshPeers.mapTo(mutableSetOf()) { it.id }

        freshPeers.forEach { peer ->
            val current = renderPeers[peer.id]
            if (current == null) {
                renderPeers[peer.id] = RenderPeer(
                    peer = peer.copy(
                        visibility = 0f,
                        txActivity = 0f,
                        rxActivity = 0f,
                    ),
                    visibility = 0f,
                    targetVisibility = 0f,
                    targetTxActivity = peer.txActivity,
                    targetRxActivity = peer.rxActivity,
                    appearStartedAtMs = SystemClock.uptimeMillis() + (peer.seed % 7) * 32L,
                )
            } else {
                renderPeers[peer.id] = current.copy(
                    peer = peer.copy(
                        visibility = current.visibility,
                        txActivity = current.peer.txActivity,
                        rxActivity = current.peer.rxActivity,
                        closingSide = ClosingSide.NONE,
                        closeStartedAtMs = null,
                    ),
                    targetVisibility = 1f,
                    targetTxActivity = peer.txActivity,
                    targetRxActivity = peer.rxActivity,
                    pendingFadeOut = false,
                    appearStartedAtMs = current.appearStartedAtMs,
                )
            }
        }

        renderPeers.keys.toList().forEach { id ->
            if (id !in activeIds) {
                val current = renderPeers[id] ?: return@forEach
                val closingSide = inferClosingSide(current.peer)
                val closeStartedAt = current.peer.closeStartedAtMs ?: SystemClock.uptimeMillis()
                renderPeers[id] = current.copy(
                    peer = current.peer.copy(
                        isActive = false,
                        visibility = current.visibility,
                        closingSide = closingSide,
                        closeStartedAtMs = closeStartedAt,
                    ),
                    targetVisibility = 1f,
                    targetTxActivity = current.targetTxActivity * 0.82f,
                    targetRxActivity = current.targetRxActivity * 0.82f,
                    pendingFadeOut = true,
                )
            }
        }
    }

    private fun markAllInactive(side: ClosingSide) {
        val now = SystemClock.uptimeMillis()
        renderPeers.keys.toList().forEach { id ->
            val current = renderPeers[id] ?: return@forEach
            renderPeers[id] = current.copy(
                peer = current.peer.copy(
                    isActive = false,
                    visibility = current.visibility,
                    closingSide = if (current.peer.closingSide == ClosingSide.NONE) side else current.peer.closingSide,
                    closeStartedAtMs = current.peer.closeStartedAtMs ?: now,
                ),
                targetVisibility = 1f,
                targetTxActivity = current.targetTxActivity * 0.82f,
                targetRxActivity = current.targetRxActivity * 0.82f,
                pendingFadeOut = true,
            )
        }
    }

    private fun animateFrame(): Boolean {
        var changed = false
        val now = SystemClock.uptimeMillis()
        val iterator = renderPeers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            var item = entry.value

            if (item.peer.isActive && now >= item.appearStartedAtMs && item.targetVisibility < 1f) {
                item = item.copy(targetVisibility = 1f)
            }

            if (!item.peer.isActive && item.pendingFadeOut) {
                val closeStarted = item.peer.closeStartedAtMs ?: now
                if (now - closeStarted >= PACKET_DRAIN_MS) {
                    item = item.copy(targetVisibility = 0f, pendingFadeOut = false)
                } else {
                    item = item.copy(targetVisibility = 1f)
                }
            }

            val decayFactor = if (item.peer.isActive) 1f else 0.972f
            val targetTx = if (item.peer.isActive) item.targetTxActivity else item.targetTxActivity * decayFactor
            val targetRx = if (item.peer.isActive) item.targetRxActivity else item.targetRxActivity * decayFactor

            val newVisibility = stepTowards(item.visibility, item.targetVisibility, VISIBILITY_STEP)
            val newTx = stepTowards(item.peer.txActivity, targetTx, ACTIVITY_STEP)
            val newRx = stepTowards(item.peer.rxActivity, targetRx, ACTIVITY_STEP)

            val peerChanged =
                abs(newVisibility - item.visibility) > 0.0001f ||
                    abs(newTx - item.peer.txActivity) > 0.0001f ||
                    abs(newRx - item.peer.rxActivity) > 0.0001f ||
                    item !== entry.value

            if (peerChanged) {
                changed = true
                entry.setValue(
                    item.copy(
                        peer = item.peer.copy(
                            visibility = newVisibility,
                            txActivity = newTx,
                            rxActivity = newRx,
                        ),
                        visibility = newVisibility,
                        targetTxActivity = targetTx,
                        targetRxActivity = targetRx,
                    ),
                )
            }

            val currentValue = entry.value
            if (!currentValue.peer.isActive && currentValue.visibility <= 0.02f && currentValue.peer.txActivity <= 0.02f && currentValue.peer.rxActivity <= 0.02f) {
                iterator.remove()
                changed = true
            }
        }
        return changed
    }

    private fun emitUiState() {
        val peers = renderPeers.values
            .map { it.peer.copy(visibility = it.visibility) }
            .sortedWith(compareByDescending<PeerVisual> { it.isActive }.thenBy { it.latencyMs })

        _uiState.value = DashboardUiState(
            isRootReady = rootReady,
            isConnected = peers.any { it.isActive },
            peers = peers,
            lastError = lastError,
            sessionDurationMs = if (sessionStartedAtMs == 0L) 0L else (SystemClock.elapsedRealtime() - sessionStartedAtMs).coerceAtLeast(0L),
            sessionTrafficBytes = sessionTrafficBytes,
        )
    }

    private fun stepTowards(current: Float, target: Float, amount: Float): Float {
        val delta = target - current
        if (abs(delta) <= amount) return target
        return current + amount * delta.sign()
    }

    private fun Float.sign(): Float = when {
        this > 0f -> 1f
        this < 0f -> -1f
        else -> 0f
    }

    private fun inferClosingSide(peer: PeerVisual): ClosingSide {
        return when (peer.connectionState.uppercase()) {
            "FIN-WAIT-1", "FIN-WAIT-2", "TIME-WAIT" -> ClosingSide.LOCAL
            "CLOSE-WAIT" -> ClosingSide.REMOTE
            "LAST-ACK", "CLOSING", "CLOSED" -> ClosingSide.UNKNOWN
            else -> when {
                peer.sendQueue > peer.recvQueue && peer.sendQueue > 0 -> ClosingSide.LOCAL
                peer.recvQueue > peer.sendQueue && peer.recvQueue > 0 -> ClosingSide.REMOTE
                else -> ClosingSide.UNKNOWN
            }
        }
    }

    private data class RenderPeer(
        val peer: PeerVisual,
        val visibility: Float,
        val targetVisibility: Float,
        val targetTxActivity: Float,
        val targetRxActivity: Float,
        val pendingFadeOut: Boolean = false,
        val appearStartedAtMs: Long = 0L,
    )

    private companion object {
        private val geoProtocols = setOf("tcp", "tcp6", "udp", "udp6")
        private const val MAX_GEO_REQUESTS_PER_CYCLE = 8
    }
}
