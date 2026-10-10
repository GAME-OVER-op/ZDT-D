package com.android.zdtd.service

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NonRootVpnState {
  STOPPED,
  STARTING,
  RUNNING,
  STOPPING,
  ERROR,
}

enum class NonRootRuntimeLogLevel {
  INFO,
  ERROR,
}

data class NonRootRuntimeLogEntry(
  val id: Long,
  val timestampMillis: Long,
  val level: NonRootRuntimeLogLevel,
  val message: String,
)

data class NonRootProfileFailure(
  val profileId: String,
  val toolId: String,
  val profileName: String,
  val serverId: String? = null,
  val serverName: String? = null,
  val message: String,
  val timestampMillis: Long = System.currentTimeMillis(),
)

/** Process-local status and lifecycle progress shared by the non-root service and Compose UI. */
object NonRootVpnRuntime {
  private const val MAX_LOG_ENTRIES = 100

  private val _state = MutableStateFlow(NonRootVpnState.STOPPED)
  val state: StateFlow<NonRootVpnState> = _state.asStateFlow()

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

  private val nextLogId = AtomicLong(0L)
  private val _logs = MutableStateFlow<List<NonRootRuntimeLogEntry>>(emptyList())
  val logs: StateFlow<List<NonRootRuntimeLogEntry>> = _logs.asStateFlow()

  private val _profileFailures = MutableStateFlow<List<NonRootProfileFailure>>(emptyList())
  val profileFailures: StateFlow<List<NonRootProfileFailure>> = _profileFailures.asStateFlow()

  internal fun update(state: NonRootVpnState, error: String? = null) {
    _state.value = state
    _lastError.value = error
  }

  @Synchronized
  internal fun clearLogs() {
    _logs.value = emptyList()
  }

  @Synchronized
  internal fun clearProfileFailures() {
    _profileFailures.value = emptyList()
  }

  @Synchronized
  internal fun reportProfileFailure(failure: NonRootProfileFailure) {
    val withoutSameTarget = _profileFailures.value.filterNot { existing ->
      existing.profileId == failure.profileId && existing.serverId == failure.serverId
    }
    _profileFailures.value = withoutSameTarget + failure
  }

  @Synchronized
  internal fun log(message: String, level: NonRootRuntimeLogLevel = NonRootRuntimeLogLevel.INFO) {
    val entry = NonRootRuntimeLogEntry(
      id = nextLogId.incrementAndGet(),
      timestampMillis = System.currentTimeMillis(),
      level = level,
      message = message,
    )
    _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)
  }
}
