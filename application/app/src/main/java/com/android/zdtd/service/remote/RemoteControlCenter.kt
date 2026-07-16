package com.android.zdtd.service.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RemoteControlCenter {
  private val _target = MutableStateFlow<RemoteControlTarget?>(null)
  val target: StateFlow<RemoteControlTarget?> = _target.asStateFlow()

  fun enter(target: RemoteControlTarget) {
    _target.value = target
  }

  fun exit() {
    _target.value = null
  }
}
