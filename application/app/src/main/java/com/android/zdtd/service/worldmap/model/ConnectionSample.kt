package com.android.zdtd.service.worldmap.model

enum class ClosingSide {
    NONE,
    LOCAL,
    REMOTE,
    UNKNOWN,
}

data class ConnectionSample(
    val protocol: String,
    val state: String,
    val remoteIp: String,
    val remotePort: Int,
    val latencyMs: Int = 0,
    val recvQueue: Long = 0,
    val sendQueue: Long = 0,
    val txCounterBytes: Long = 0,
    val rxCounterBytes: Long = 0,
)

data class PeerVisual(
    val id: String,
    val ip: String,
    val x: Float,
    val y: Float,
    val latencyMs: Int,
    val bytesHint: Long,
    val seed: Int,
    val protocol: String,
    val remotePort: Int,
    val connectionState: String,
    val sendQueue: Long,
    val recvQueue: Long,
    val txCounterBytes: Long = 0,
    val rxCounterBytes: Long = 0,
    val txActivity: Float,
    val rxActivity: Float,
    val activityScore: Float = 0f,
    val activityLabel: String = "",
    val geoLabel: String = "",
    val isActive: Boolean = true,
    val visibility: Float = 1f,
    val closingSide: ClosingSide = ClosingSide.NONE,
    val closeStartedAtMs: Long? = null,
)
