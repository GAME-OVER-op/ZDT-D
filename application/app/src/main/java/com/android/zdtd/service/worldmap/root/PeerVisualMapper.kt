package com.android.zdtd.service.worldmap.root

import com.android.zdtd.service.worldmap.WorldMapStrings
import com.android.zdtd.service.worldmap.model.ConnectionSample
import com.android.zdtd.service.worldmap.model.PeerVisual
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

object PeerVisualMapper {

    fun map(
        connections: List<ConnectionSample>,
        locationForIp: (String) -> GeoLocation?,
        stateForIp: (String) -> GeoResolveState,
        strings: WorldMapStrings,
    ): List<PeerVisual> {
        return connections.map { sample ->
            val hash = abs(sample.remoteIp.hashCode())
            val portBias = abs(sample.remotePort * 31 + sample.protocol.hashCode())
            val geoLocation = locationForIp(sample.remoteIp)
            val geoState = stateForIp(sample.remoteIp)
            val coords = geoLocation?.let { it.x to it.y } ?: pseudoGeoPosition(hash, portBias)
            val queueHint = max(sample.sendQueue, sample.recvQueue)
            val bytesHint = 36_000L + ((hash + portBias) % 420_000) + queueHint * 4L
            val seed = abs((sample.remoteIp + ':' + sample.remotePort.toString() + '/' + sample.protocol).hashCode())
            val baseActivityScore = (max(queueActivity(sample.sendQueue, active = true), queueActivity(sample.recvQueue, active = true)) * 0.55f).coerceIn(0f, 1f)
            val geoLabel = when {
                geoLocation != null -> geoLocation.label
                sample.protocol !in geoProtocols -> strings.geoNoGeo(sample.protocol)
                geoState == GeoResolveState.FAILED -> strings.geoNoGeo(sample.protocol)
                geoState == GeoResolveState.SKIPPED -> strings.geoSkipped(sample.protocol)
                else -> strings.geoResolving()
            }

            PeerVisual(
                id = "${sample.protocol}:${sample.remoteIp}:${sample.remotePort}",
                ip = sample.remoteIp,
                x = coords.first,
                y = coords.second,
                latencyMs = sample.latencyMs,
                bytesHint = bytesHint,
                seed = seed,
                protocol = sample.protocol,
                remotePort = sample.remotePort,
                connectionState = sample.state.ifBlank { strings.unknownState() },
                sendQueue = sample.sendQueue,
                recvQueue = sample.recvQueue,
                txCounterBytes = sample.txCounterBytes,
                rxCounterBytes = sample.rxCounterBytes,
                txActivity = queueActivity(sample.sendQueue, active = true),
                rxActivity = queueActivity(sample.recvQueue, active = true),
                activityScore = baseActivityScore,
                activityLabel = strings.activityLabel(baseActivityScore),
                geoLabel = geoLabel,
            )
        }
    }

    private fun queueActivity(queue: Long, active: Boolean): Float {
        if (queue <= 0L) return if (active) 0.14f else 0f
        val scaled = (ln(1f + queue.toFloat()) / ln(1f + 65_536f)).coerceIn(0f, 1f)
        return (0.22f + scaled * 0.78f).coerceIn(0f, 1f)
    }

    private fun pseudoGeoPosition(hash: Int, portBias: Int): Pair<Float, Float> {
        val seed = abs(hash * 31 + portBias * 17)
        val samples = LandSampleBank.canvasSamples
        return samples[seed % samples.size]
    }

    private val geoProtocols = setOf("tcp", "tcp6", "udp", "udp6")
}
