package com.android.zdtd.service.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.zdtd.service.RootConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

data class RemoteSetupUiState(
  val canHost: Boolean = false,
  val host: RemoteHostState = RemoteHostState(),
  val discovered: List<RemoteDeviceInfo> = emptyList(),
  val connecting: Boolean = false,
  val message: String = "",
  val error: String = "",
)

class RemoteSetupViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx = app.applicationContext
  private val root = RootConfigManager(ctx)
  private val hostServer = RemoteHostManager.get(ctx)
  private val discovery = RemoteDiscovery(ctx)
  private val client = RemoteClient()
  private var discoveryJob: Job? = null

  private val _state = MutableStateFlow(RemoteSetupUiState())
  val state: StateFlow<RemoteSetupUiState> = _state.asStateFlow()

  init {
    refreshCapabilities()
    viewModelScope.launch { hostServer.state.collect { h -> _state.update { it.copy(host = h) } } }
    startDiscovery()
  }

  fun refreshCapabilities() {
    viewModelScope.launch(Dispatchers.IO) {
      val canHost = runCatching { root.testRoot() && root.isModuleInstalled() }.getOrDefault(false)
      _state.update { it.copy(canHost = canHost) }
    }
  }

  fun setError(message: String) {
    _state.update { it.copy(error = message, message = "") }
  }

  fun startHost() {
    viewModelScope.launch(Dispatchers.IO) {
      val canHost = runCatching { root.testRoot() && root.isModuleInstalled() }.getOrDefault(false)
      if (!canHost) {
        _state.update { it.copy(error = "Запуск удалённого управления доступен только на root-устройстве с ZDT-D", message = "") }
        return@launch
      }
      val st = hostServer.start()
      _state.update { it.copy(host = st, error = st.error, message = if (st.running) "Удалённое управление запущено" else "") }
    }
  }

  fun stopHost() {
    hostServer.stop()
    _state.update { it.copy(message = "Удалённое управление остановлено", error = "") }
  }

  fun startDiscovery() {
    discoveryJob?.cancel()
    discoveryJob = viewModelScope.launch(Dispatchers.IO) {
      _state.update { it.copy(message = "Поиск устройств ZDT-D в сети…", error = "") }
      val found = linkedMapOf<String, RemoteDeviceInfo>()
      fun putFound(d: RemoteDeviceInfo) {
        val key = d.deviceId.ifBlank { "${d.host}:${d.port}" }
        if (d.appVersionCode == 0) {
          launch { client.ping(d)?.let { fresh -> putFound(fresh) } }
          return
        }
        val current = found[key]
        val merged = when {
          current == null -> d
          current.appVersionCode == 0 && d.appVersionCode != 0 -> d.copy(sessionToken = current.sessionToken.ifBlank { d.sessionToken })
          current.appVersionCode != 0 && d.appVersionCode == 0 -> current.copy(online = true, lastSeenMs = maxOf(current.lastSeenMs, d.lastSeenMs))
          else -> d
        }
        found[key] = merged
        _state.update { it.copy(discovered = found.values.toList(), message = "") }
      }
      val nsdJob = launch { runCatching { discovery.discoverNsd().collect { d -> putFound(d) } } }
      runCatching { discovery.discoverUdp().collect { d -> putFound(d) } }
      nsdJob.cancel()
      val message = if (found.isEmpty()) "Устройства не найдены. Проверьте одну Wi‑Fi/локальную сеть или введите IP, порт и код вручную." else ""
      _state.update { it.copy(message = message) }
    }
  }

  fun connectManual(host: String, portText: String, code: String) {
    connect(host.trim(), portText.trim().toIntOrNull() ?: 0, code)
  }

  private fun connect(host: String, port: Int, code: String) {
    if (host.isBlank() || port !in 1..65535) {
      _state.update { it.copy(error = "Введите IP и порт устройства", message = "") }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      _state.update { it.copy(connecting = true, error = "", message = "Подключение…") }
      runCatching { client.pair(host, port, code) }
        .onSuccess { target ->
          RemoteControlCenter.enter(target)
          _state.update { it.copy(connecting = false, message = "Подключено: ${target.device.displayTitle()}") }
        }
        .onFailure { e ->
          val msg = if ((e.message ?: "").startsWith("version_mismatch")) {
            val parts = e.message!!.split(':')
            "Версии приложений отличаются. Телефон: ${parts.getOrNull(1)}, устройство: ${parts.getOrNull(2)}"
          } else e.message ?: "Не удалось подключиться"
          _state.update { it.copy(connecting = false, error = msg, message = "") }
        }
    }
  }

  override fun onCleared() {
    discoveryJob?.cancel()
    super.onCleared()
  }
}
