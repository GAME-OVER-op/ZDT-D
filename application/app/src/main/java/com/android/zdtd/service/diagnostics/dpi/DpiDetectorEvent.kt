package com.android.zdtd.service.diagnostics.dpi

/**
 * Models for the NDJSON protocol emitted by the native Rust helper.
 *
 * Rust writes one JSON object per line. The UI consumes these events as a
 * stream and updates stages/probes immediately without waiting for the full
 * scan to finish.
 */
sealed interface DpiDetectorEvent {
    val type: String

    data class ProbeCheck(
        val name: String,
        val status: String,
        val detail: String,
        val value: String,
        val sizeLabel: String,
    )

    data class Meta(
        val test: String,
        val status: String,
        val detail: String,
        val data: String,
        val sequence: Long,
        val timestampMs: Long,
    ) : DpiDetectorEvent {
        override val type: String = "meta"
    }

    data class Started(
        val test: String,
        val title: String,
        val totalProbes: Int,
        val data: String,
        val sequence: Long,
        val timestampMs: Long,
    ) : DpiDetectorEvent {
        override val type: String = "started"
    }

    data class Probe(
        val test: String,
        val key: String,
        val name: String,
        val target: String,
        val sizeLabel: String,
        val technical: Map<String, String> = emptyMap(),
        val checks: List<ProbeCheck> = emptyList(),
        val diagnosis: String = "",
        val status: String,
        val detail: String,
        val data: String,
        val sequence: Long,
        val timestampMs: Long,
    ) : DpiDetectorEvent {
        override val type: String = "probe"
    }

    data class Progress(
        val test: String,
        val status: String,
        val detail: String,
        val data: String,
        val sequence: Long,
        val timestampMs: Long,
    ) : DpiDetectorEvent {
        override val type: String = "progress"
    }

    data class Result(
        val test: String,
        val status: String,
        val detail: String,
        val diagnosis: String,
        val data: String,
        val sequence: Long,
        val timestampMs: Long,
    ) : DpiDetectorEvent {
        override val type: String = "result"
    }

    data class Finished(
        val test: String,
        val status: String,
        val data: String,
        val sequence: Long,
        val timestampMs: Long,
    ) : DpiDetectorEvent {
        override val type: String = "finished"
    }

    data class Error(
        val message: String,
        val rawLine: String? = null,
    ) : DpiDetectorEvent {
        override val type: String = "error"
    }
}
