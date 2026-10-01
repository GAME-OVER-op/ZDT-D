package com.android.zdtd.service

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

/** Process-local status shared by the non-root foreground service and Compose UI. */
object NonRootVpnRuntime {
  private val _state = MutableStateFlow(NonRootVpnState.STOPPED)
  val state: StateFlow<NonRootVpnState> = _state.asStateFlow()

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

  internal fun update(state: NonRootVpnState, error: String? = null) {
    _state.value = state
    _lastError.value = error
  }
}
