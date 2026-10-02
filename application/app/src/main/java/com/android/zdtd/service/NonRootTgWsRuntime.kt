package com.android.zdtd.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NonRootTgWsRuntimeState {
  STOPPED,
  STARTING,
  RUNNING,
  ERROR,
}

/** Process-local TGWS status reported by the controller service to the non-root UI. */
object NonRootTgWsRuntime {
  private val _state = MutableStateFlow(NonRootTgWsRuntimeState.STOPPED)
  val state: StateFlow<NonRootTgWsRuntimeState> = _state.asStateFlow()

  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError.asStateFlow()

  internal fun update(state: NonRootTgWsRuntimeState, error: String? = null) {
    _state.value = state
    _lastError.value = error
  }
}
