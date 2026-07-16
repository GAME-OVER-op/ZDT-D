package com.android.zdtd.service.remote

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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

data class RemoteSetupUiState(
  val canHost: Boolean = false,
  val host: RemoteHostState = RemoteHostState(),
  val discovered: List<RemoteDeviceInfo> = emptyList(),
  val history: List<RemoteDeviceInfo> = emptyList(),
  val connecting: Boolean = false,
  val cameraPermissionGranted: Boolean = false,
  val message: String = "",
  val error: String = "",
)

class RemoteSetupViewModel(app: Application) : AndroidViewModel(app) {
  private val ctx = app.applicationContext
  private val root = RootConfigManager(ctx)
  private val hostServer = RemoteHostServer(ctx, root)
  private val store = RemoteDeviceStore(ctx)
  private val discovery = RemoteDiscovery(ctx)
  private val client = RemoteClient()
  private var discoveryJob: Job? = null

  private val _state = MutableStateFlow(RemoteSetupUiState())
  val state: StateFlow<RemoteSetupUiState> = _state.asStateFlow()

  init {
    refreshCapabilities()
    refreshHistory()
    viewModelScope.launch { hostServer.state.collect { h -> _state.update { it.copy(host = h) } } }
    startDiscovery()
    updateCameraPermission()
  }

  fun refreshCapabilities() {
    viewModelScope.launch(Dispatchers.IO) {
      val canHost = runCatching { root.testRoot() && root.isModuleInstalled() }.getOrDefault(false)
      _state.update { it.copy(canHost = canHost) }
    }
  }

  fun updateCameraPermission() {
    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    _state.update { it.copy(cameraPermissionGranted = granted) }
  }

  fun refreshHistory() {
    _state.update { it.copy(history = store.load()) }
  }

  fun startHost() {
    viewModelScope.launch(Dispatchers.IO) {
      val canHost = runCatching { root.testRoot() && root.isModuleInstalled() }.getOrDefault(false)
      if (!canHost) {
        _state.update { it.copy(error = "Запуск удалённого управления доступен только на root-устройстве с ZDT-D") }
        return@launch
      }
      val st = hostServer.start()
      _state.update { it.copy(host = st, error = st.error) }
    }
  }

  fun stopHost() {
    hostServer.stop()
  }

  fun startDiscovery() {
    discoveryJob?.cancel()
    discoveryJob = viewModelScope.launch(Dispatchers.IO) {
      _state.update { it.copy(message = "Поиск устройств ZDT-D в сети…") }
      val found = linkedMapOf<String, RemoteDeviceInfo>()
      fun putFound(d: RemoteDeviceInfo) {
        found[d.deviceId.ifBlank { "${d.host}:${d.port}" }] = d
        _state.update { it.copy(discovered = found.values.toList(), message = "") }
      }
      val nsdJob = launch { discovery.discoverNsd().collect { d -> putFound(d) } }
      discovery.discoverUdp().collect { d -> putFound(d) }
      nsdJob.cancel()
      val history = store.load()
      val pinged = history.map { d -> client.ping(d) ?: d.copy(online = false) }
      _state.update { it.copy(history = pinged, message = "") }
    }
  }

  fun connectManual(host: String, portText: String, code: String) {
    connect(host.trim(), portText.trim().toIntOrNull() ?: 0, code)
  }

  fun connectQr(raw: String) {
    val map = RemoteProtocol.parseQrPayload(raw)
    if (map.isEmpty()) {
      _state.update { it.copy(error = "QR-код не похож на ZDT-D remote pair") }
      return
    }
    connect(map["host"].orEmpty(), map["port"]?.toIntOrNull() ?: 0, map["code"].orEmpty())
  }

  fun connectKnown(device: RemoteDeviceInfo, code: String = "") {
    if (device.sessionToken.isNotBlank() && code.isBlank()) {
      val baseUrl = "http://" + device.host + ":" + device.port
      val target = RemoteControlTarget(device, baseUrl, device.sessionToken)
      RemoteControlCenter.enter(target)
      return
    }
    connect(device.host, device.port, code)
  }

  private fun connect(host: String, port: Int, code: String) {
    if (host.isBlank() || port !in 1..65535) {
      _state.update { it.copy(error = "Введите IP и порт устройства") }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      _state.update { it.copy(connecting = true, error = "", message = "Подключение…") }
      val result = runCatching { client.pair(host, port, code) }
      result.onSuccess { target ->
        store.save(target.device)
        RemoteControlCenter.enter(target)
        _state.update { it.copy(connecting = false, message = "Подключено: ${target.device.displayTitle()}", history = store.load()) }
      }.onFailure { e ->
        val msg = if ((e.message ?: "").startsWith("version_mismatch")) {
          val parts = e.message!!.split(':')
          "Версии приложений отличаются. Телефон: ${parts.getOrNull(1)}, устройство: ${parts.getOrNull(2)}"
        } else e.message ?: "Не удалось подключиться"
        _state.update { it.copy(connecting = false, error = msg, message = "") }
      }
    }
  }

  override fun onCleared() {
    hostServer.stop()
    discoveryJob?.cancel()
    super.onCleared()
  }
}
