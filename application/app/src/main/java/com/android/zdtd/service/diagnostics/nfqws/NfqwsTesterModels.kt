package com.android.zdtd.service.diagnostics.nfqws

enum class NfqwsTesterPhase {
    IDLE,
    PREPARING,
    RUNNING,
    WAITING_DECISION,
    FINISHED,
    ERROR,
}

data class NfqwsTesterSessionState(
    val phase: NfqwsTesterPhase = NfqwsTesterPhase.IDLE,
    val program: String = "nfqws",
    val strategies: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val currentStrategy: String = "",
    val pid: Int = 0,
    val cpuPercent: Double = 0.0,
    val rssMb: Double = 0.0,
    val statusText: String = "",
    val errorText: String? = null,
    val working: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val overlayPermissionGranted: Boolean = false,
)

object NfqwsTesterStore {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(NfqwsTesterSessionState())
    val state: kotlinx.coroutines.flow.StateFlow<NfqwsTesterSessionState> = mutableState

    fun update(transform: (NfqwsTesterSessionState) -> NfqwsTesterSessionState) {
        mutableState.value = transform(mutableState.value)
    }

    fun replace(next: NfqwsTesterSessionState) {
        mutableState.value = next
    }
}
