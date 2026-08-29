package com.android.zdtd.service.vps

import android.app.Application
import android.media.MediaScannerConnection
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class VpsViewModel(application: Application) : AndroidViewModel(application) {
  private val store = VpsSecureStore(application)
  private val ssh = VpsSshClient()
  private val controller = VpsRemoteController(application, ssh)
  private val root = RootConfigManager(application)

  private val _servers = MutableStateFlow(store.loadServers())
  val servers: StateFlow<List<VpsServer>> = _servers.asStateFlow()

  private val _metrics = MutableStateFlow<Map<String, VpsMetrics>>(emptyMap())
  val metrics: StateFlow<Map<String, VpsMetrics>> = _metrics.asStateFlow()

  private val _serviceStates = MutableStateFlow<Map<String, List<VpsServiceState>>>(emptyMap())
  val serviceStates: StateFlow<Map<String, List<VpsServiceState>>> = _serviceStates.asStateFlow()

  private val _profiles = MutableStateFlow<Map<String, List<VpsServiceProfile>>>(emptyMap())
  val profiles: StateFlow<Map<String, List<VpsServiceProfile>>> = _profiles.asStateFlow()

  private val _clients = MutableStateFlow<Map<String, List<VpsClientConfig>>>(emptyMap())
  val clients: StateFlow<Map<String, List<VpsClientConfig>>> = _clients.asStateFlow()

  private val _serviceLoads = MutableStateFlow<Map<String, VpsLoadState>>(emptyMap())
  val serviceLoads: StateFlow<Map<String, VpsLoadState>> = _serviceLoads.asStateFlow()

  private val _profileLoads = MutableStateFlow<Map<String, VpsLoadState>>(emptyMap())
  val profileLoads: StateFlow<Map<String, VpsLoadState>> = _profileLoads.asStateFlow()

  private val _clientLoads = MutableStateFlow<Map<String, VpsLoadState>>(emptyMap())
  val clientLoads: StateFlow<Map<String, VpsLoadState>> = _clientLoads.asStateFlow()

  private val _pendingProbe = MutableStateFlow<PendingServerProbe?>(null)
  val pendingProbe: StateFlow<PendingServerProbe?> = _pendingProbe.asStateFlow()

  private val _operation = MutableStateFlow(VpsOperationState())
  val operation: StateFlow<VpsOperationState> = _operation.asStateFlow()

  private val _configResult = MutableStateFlow<VpsConfigResult?>(null)
  val configResult: StateFlow<VpsConfigResult?> = _configResult.asStateFlow()

  private val monitorJobs = ConcurrentHashMap<String, Job>()
  private var lastServerStatePersistAt = 0L
  private val monitorSemaphore = Semaphore(3)
  private val activeMetricRefreshes = ConcurrentHashMap.newKeySet<String>()
  private val activeServiceRefreshes = ConcurrentHashMap.newKeySet<String>()
  private val activeProfileRefreshes = ConcurrentHashMap.newKeySet<String>()
  private val activeClientRefreshes = ConcurrentHashMap.newKeySet<String>()

  data class PendingServerProbe(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: VpsSshAuth,
    val result: VpsProbeResult,
  )

  fun server(id: String): VpsServer? = _servers.value.firstOrNull { it.id == id }

  fun probeNewServer(name: String, host: String, port: Int, username: String, auth: VpsSshAuth) {
    if (_operation.value.running) return
    viewModelScope.launch {
      startOperation("Checking SSH connection")
      runCatching { ssh.probe(host.trim(), port, username.trim(), auth) }
        .onSuccess { probe ->
          appendLine("SSH connection established")
          appendLine("Host key: ${probe.fingerprint}")
          _pendingProbe.value = PendingServerProbe(name.trim().ifBlank { host.trim() }, host.trim(), port, username.trim(), auth, probe)
          _operation.value = VpsOperationState()
        }
        .onFailure { failOperation(it.message ?: "SSH connection failed") }
    }
  }

  fun dismissPendingProbe() { _pendingProbe.value = null }

  fun confirmPendingServer() {
    val pending = _pendingProbe.value ?: return
    val server = VpsServer(
      name = pending.name,
      host = pending.host,
      port = pending.port,
      username = pending.username,
      password = pending.auth.password,
      authType = pending.auth.type,
      privateKey = pending.auth.privateKey,
      privateKeyName = pending.auth.privateKeyName,
      privateKeyPassphrase = pending.auth.privateKeyPassphrase,
      pinnedHostKey = pending.result.hostKeyBase64,
      fingerprint = pending.result.fingerprint,
      lastSuccessfulCheck = System.currentTimeMillis(),
    )
    _servers.value = _servers.value + server
    _metrics.value = _metrics.value + (server.id to pending.result.metrics)
    store.saveServers(_servers.value)
    lastServerStatePersistAt = System.currentTimeMillis()
    _pendingProbe.value = null
  }

  fun deleteServer(serverId: String) {
    monitorJobs.keys.filter { it.contains(serverId) }.forEach(::stopMonitor)
    _servers.value = _servers.value.filterNot { it.id == serverId }
    _metrics.value = _metrics.value - serverId
    _serviceStates.value = _serviceStates.value - serverId
    _serviceLoads.value = _serviceLoads.value - serverId
    _profiles.value = _profiles.value.filterKeys { !it.startsWith("$serverId:") }
    _profileLoads.value = _profileLoads.value.filterKeys { !it.startsWith("$serverId:") }
    _clients.value = _clients.value.filterKeys { !it.startsWith("$serverId:") }
    _clientLoads.value = _clientLoads.value.filterKeys { !it.startsWith("$serverId:") }
    store.saveServers(_servers.value)
    lastServerStatePersistAt = System.currentTimeMillis()
  }

  fun startListMonitoring() = startMonitor(MONITOR_LIST_KEY) {
    refreshAllServers()
  }

  fun stopListMonitoring() = stopMonitor(MONITOR_LIST_KEY)

  fun startServerDetailsMonitoring(serverId: String) = startMonitor("details:$serverId") {
    refreshServer(serverId)
    loadServices(serverId, silent = true)
  }

  fun stopServerDetailsMonitoring(serverId: String) = stopMonitor("details:$serverId")

  fun startServiceMonitoring(serverId: String, kind: VpsServiceKind) = startMonitor("service:$serverId:${kind.wireId}") {
    refreshServer(serverId)
    loadServices(serverId, silent = true)
    if (kind != VpsServiceKind.DNSCRYPT) loadProfiles(serverId, kind, silent = true)
    if (kind == VpsServiceKind.HYSTERIA2) loadProfiles(serverId, VpsServiceKind.XRAY, silent = true)
  }

  fun stopServiceMonitoring(serverId: String, kind: VpsServiceKind) = stopMonitor("service:$serverId:${kind.wireId}")

  fun startProfileMonitoring(serverId: String, kind: VpsServiceKind, profileId: String) =
    startMonitor("profile:$serverId:${kind.wireId}:$profileId") {
      refreshServer(serverId)
      loadProfiles(serverId, kind, silent = true)
      loadClients(serverId, kind, profileId, silent = true)
    }

  fun stopProfileMonitoring(serverId: String, kind: VpsServiceKind, profileId: String) =
    stopMonitor("profile:$serverId:${kind.wireId}:$profileId")

  private fun startMonitor(key: String, refresh: () -> Unit) {
    if (monitorJobs[key]?.isActive == true) return
    monitorJobs[key] = viewModelScope.launch {
      while (isActive) {
        if (!_operation.value.running) refresh()
        delay(MONITOR_INTERVAL_MS)
      }
    }
  }

  private fun stopMonitor(key: String) {
    monitorJobs.remove(key)?.cancel()
  }

  fun refreshAllServers() {
    for (server in _servers.value) refreshServer(server.id)
  }

  fun refreshServer(serverId: String) {
    val server = server(serverId) ?: return
    if (!activeMetricRefreshes.add(serverId)) return
    _metrics.update { current ->
      val previous = current[serverId] ?: VpsMetrics()
      current + (serverId to previous.copy(
        reachability = if (previous.reachability == VpsReachability.UNKNOWN) VpsReachability.CHECKING else previous.reachability,
        refreshing = true,
        error = if (previous.reachability == VpsReachability.OFFLINE) previous.error else null,
      ))
    }
    viewModelScope.launch {
      try {
        monitorSemaphore.withPermit {
          runCatching { ssh.collectMetrics(server) }
            .onSuccess { value ->
              val now = System.currentTimeMillis()
              _metrics.update { it + (serverId to value.copy(refreshing = false, error = null)) }
              _servers.update { current ->
                current.map { item -> if (item.id == serverId) item.copy(lastSuccessfulCheck = now) else item }
              }
              if (now - lastServerStatePersistAt >= SERVER_STATE_PERSIST_INTERVAL_MS) {
                store.saveServers(_servers.value)
                lastServerStatePersistAt = now
              }
            }
            .onFailure { error ->
              _metrics.update { current ->
                val previous = current[serverId] ?: VpsMetrics()
                current + (serverId to previous.copy(
                  reachability = VpsReachability.OFFLINE,
                  refreshing = false,
                  lastUpdatedAt = System.currentTimeMillis(),
                  error = error.message ?: "Connection failed",
                ))
              }
            }
        }
      } finally {
        activeMetricRefreshes.remove(serverId)
      }
    }
  }

  fun loadServices(serverId: String, silent: Boolean = false) {
    val server = server(serverId) ?: return
    if (!activeServiceRefreshes.add(serverId)) return
    markLoadStarted(_serviceLoads, serverId)
    viewModelScope.launch {
      try {
        runCatching { controller.inventory(server) }
          .onSuccess { states ->
            _serviceStates.value = _serviceStates.value + (serverId to states)
            markLoadSucceeded(_serviceLoads, serverId)
          }
          .onFailure { error ->
            val message = error.message ?: "Unable to read services"
            markLoadFailed(_serviceLoads, serverId, message)
            if (!silent) failOperation(message)
          }
      } finally {
        activeServiceRefreshes.remove(serverId)
      }
    }
  }

  fun installService(serverId: String, kind: VpsServiceKind) {
    val server = server(serverId) ?: return
    runRemoteOperation(
      title = "Installing ${kind.wireId}",
      block = { onLine -> controller.install(server, kind, onLine) },
      onSuccess = { loadServices(serverId) },
    )
  }

  fun removeService(serverId: String, kind: VpsServiceKind) {
    val server = server(serverId) ?: return
    runRemoteOperation(
      title = "Removing ${kind.wireId}",
      block = { onLine -> controller.remove(server, kind, onLine) },
      onSuccess = {
        loadServices(serverId)
        val key = profileKey(serverId, kind)
        _profiles.value = _profiles.value - key
        _profileLoads.value = _profileLoads.value - key
        _clients.value = _clients.value.filterKeys { !it.startsWith("$key:") }
        _clientLoads.value = _clientLoads.value.filterKeys { !it.startsWith("$key:") }
      },
    )
  }

  fun loadProfiles(serverId: String, kind: VpsServiceKind, silent: Boolean = false) {
    val server = server(serverId) ?: return
    val key = profileKey(serverId, kind)
    if (!activeProfileRefreshes.add(key)) return
    markLoadStarted(_profileLoads, key)
    viewModelScope.launch {
      try {
        runCatching { controller.listProfiles(server, kind) }
          .onSuccess { profiles ->
            _profiles.value = _profiles.value + (key to profiles)
            markLoadSucceeded(_profileLoads, key)
          }
          .onFailure { error ->
            val message = error.message ?: "Unable to read profiles"
            markLoadFailed(_profileLoads, key, message)
            if (!silent) failOperation(message)
          }
      } finally {
        activeProfileRefreshes.remove(key)
      }
    }
  }

  fun createProfile(
    serverId: String,
    kind: VpsServiceKind,
    displayName: String,
    port: Int,
    mode: String,
    domain: String,
    email: String,
    snis: List<String>,
  ) {
    val server = server(serverId) ?: return
    val id = VpsRemoteController.normalizeId(displayName)
    runRemoteOperation(
      title = "Creating $displayName",
      block = { onLine ->
        controller.createProfile(
          server, kind, id, displayName.trim(), port, mode, domain.trim(), email.trim(),
          snis.map(String::trim).filter(String::isNotBlank).distinct(), onLine,
        )
      },
      onSuccess = { loadProfiles(serverId, kind) },
    )
  }

  fun deleteProfile(serverId: String, kind: VpsServiceKind, profileId: String) {
    val server = server(serverId) ?: return
    runRemoteOperation(
      title = "Deleting profile",
      block = { controller.deleteProfile(server, kind, profileId) },
      onSuccess = {
        loadProfiles(serverId, kind)
        val key = clientKey(serverId, kind, profileId)
        _clients.value = _clients.value - key
        _clientLoads.value = _clientLoads.value - key
      },
    )
  }

  fun loadClients(serverId: String, kind: VpsServiceKind, profileId: String, silent: Boolean = false) {
    val server = server(serverId) ?: return
    val key = clientKey(serverId, kind, profileId)
    if (!activeClientRefreshes.add(key)) return
    markLoadStarted(_clientLoads, key)
    viewModelScope.launch {
      try {
        runCatching { controller.listClients(server, kind, profileId) }
          .onSuccess { clients ->
            _clients.value = _clients.value + (key to clients)
            markLoadSucceeded(_clientLoads, key)
          }
          .onFailure { error ->
            val message = error.message ?: "Unable to read client configurations"
            markLoadFailed(_clientLoads, key, message)
            if (!silent) failOperation(message)
          }
      } finally {
        activeClientRefreshes.remove(key)
      }
    }
  }

  fun createClient(serverId: String, kind: VpsServiceKind, profileId: String, clientName: String) {
    val server = server(serverId) ?: return
    val id = VpsRemoteController.normalizeId(clientName)
    runRemoteOperation(
      title = "Creating client $clientName",
      block = { onLine -> controller.createClient(server, kind, profileId, id, clientName.trim(), onLine) },
      onSuccess = {
        loadClients(serverId, kind, profileId)
        loadProfiles(serverId, kind)
      },
    )
  }

  fun deleteClient(serverId: String, kind: VpsServiceKind, profileId: String, clientId: String) {
    val server = server(serverId) ?: return
    runRemoteOperation(
      title = "Revoking client access",
      block = { controller.deleteClient(server, kind, profileId, clientId) },
      onSuccess = {
        loadClients(serverId, kind, profileId)
        loadProfiles(serverId, kind)
      },
    )
  }

  fun requestConfig(serverId: String, kind: VpsServiceKind, profileId: String, clientId: String) {
    val server = server(serverId) ?: return
    viewModelScope.launch {
      startOperation("Generating client configuration")
      runCatching { controller.getConfig(server, kind, profileId, clientId) }
        .onSuccess { result -> _configResult.value = result; finishOperation() }
        .onFailure { failOperation(it.message ?: "Configuration generation failed") }
    }
  }

  fun clearConfigResult() { _configResult.value = null }

  fun rebootServer(serverId: String) {
    if (_operation.value.running) return
    val server = server(serverId) ?: return
    val app = getApplication<Application>()
    viewModelScope.launch {
      startOperation(app.getString(R.string.vps_rebooting))
      updateStage(app.getString(R.string.vps_rebooting))
      val scheduled = runCatching { controller.reboot(server) }.getOrElse { error ->
        failOperation(error.message ?: app.getString(R.string.vps_reboot_failed))
        return@launch
      }
      if (!scheduled.successful) {
        failOperation(scheduled.output.ifBlank { app.getString(R.string.vps_reboot_failed) })
        return@launch
      }

      appendLine(app.getString(R.string.vps_reboot_scheduled))
      updateStage(app.getString(R.string.vps_waiting_for_server))
      val startedAt = System.currentTimeMillis()
      val deadline = startedAt + REBOOT_WAIT_TIMEOUT_MS
      var observedOffline = false
      delay(REBOOT_INITIAL_DELAY_MS)

      while (isActive && System.currentTimeMillis() < deadline) {
        val probe = runCatching { ssh.collectMetrics(server) }
        val value = probe.getOrNull()
        if (value != null && (observedOffline || System.currentTimeMillis() - startedAt >= REBOOT_MIN_RETURN_MS)) {
          val now = System.currentTimeMillis()
          _metrics.update { it + (serverId to value.copy(refreshing = false, error = null)) }
          _servers.update { current ->
            current.map { item -> if (item.id == serverId) item.copy(lastSuccessfulCheck = now) else item }
          }
          store.saveServers(_servers.value)
          lastServerStatePersistAt = now
          appendLine(app.getString(R.string.vps_server_online_again))
          finishOperation()
          loadServices(serverId, silent = true)
          return@launch
        }

        if (value == null) {
          observedOffline = true
          _metrics.update { current ->
            val previous = current[serverId] ?: VpsMetrics()
            current + (serverId to previous.copy(
              reachability = VpsReachability.OFFLINE,
              refreshing = false,
              lastUpdatedAt = System.currentTimeMillis(),
              error = null,
            ))
          }
        }
        delay(REBOOT_POLL_INTERVAL_MS)
      }
      failOperation(app.getString(R.string.vps_reboot_timeout))
    }
  }

  fun restartService(serverId: String, kind: VpsServiceKind, profileId: String? = null) {
    val server = server(serverId) ?: return
    runRemoteOperation(
      title = "Restarting ${kind.wireId}",
      block = { controller.restart(server, kind, profileId) },
      onSuccess = {
        loadServices(serverId)
        if (profileId != null) loadProfiles(serverId, kind)
      },
    )
  }

  fun loadLogs(serverId: String, kind: VpsServiceKind, profileId: String?, onDone: (String) -> Unit) {
    val server = server(serverId) ?: return
    viewModelScope.launch {
      onDone(runCatching { controller.logs(server, kind, profileId) }.getOrElse { it.message ?: "Unable to read logs" })
    }
  }

  fun saveConfigToSharedStorage(serverName: String, result: VpsConfigResult, onDone: (Result<String>) -> Unit) {
    viewModelScope.launch {
      val outcome = withContext(Dispatchers.IO) {
        runCatching {
          val safeServer = sanitizePath(serverName)
          val safeKind = sanitizePath(result.kind.wireId)
          val safeFile = sanitizeFileName(result.fileName)
          val dir = "/storage/emulated/0/ZDT-D_Files/VPS/$safeServer/$safeKind"
          val path = "$dir/$safeFile"
          val encoded = Base64.encodeToString(result.content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
          val script = "mkdir -p '${escapeSingle(dir)}'; printf '%s' '$encoded' | base64 -d > '${escapeSingle(path)}'; chmod 0664 '${escapeSingle(path)}'; chown 1023:1023 '${escapeSingle(path)}' 2>/dev/null || true"
          val shellResult = root.execRootSh(script)
          check(shellResult.isSuccess) { shellResult.err.joinToString("\n").ifBlank { "Unable to save file" } }
          path
        }
      }
      outcome.getOrNull()?.let { path ->
        MediaScannerConnection.scanFile(getApplication(), arrayOf(path), null, null)
      }
      onDone(outcome)
    }
  }

  private fun runRemoteOperation(
    title: String,
    block: suspend ((String) -> Unit) -> VpsCommandResult,
    onSuccess: () -> Unit = {},
  ) {
    if (_operation.value.running) return
    viewModelScope.launch {
      startOperation(title)
      val result = runCatching {
        block { line ->
          when {
            line.startsWith("ZDT_STAGE=") -> updateStage(line.substringAfter('='))
            line.startsWith("ZDT_ROLLBACK=") -> {
              appendLine(line.substringAfter('='))
              _operation.value = _operation.value.copy(rolledBack = true)
            }
            else -> appendLine(line.removePrefix("ZDT_INFO=").removePrefix("ZDT_WARNING="))
          }
        }
      }.getOrElse {
        failOperation(it.message ?: "Operation failed")
        return@launch
      }
      if (result.successful) {
        finishOperation()
        onSuccess()
      } else {
        val failedStage = result.output.lineSequence().firstOrNull { it.startsWith("ZDT_FAILED_STAGE=") }?.substringAfter('=')
        failOperation(result.output.lineSequence().firstOrNull { it.startsWith("ZDT_ERROR=") }?.substringAfter('=') ?: result.output.ifBlank { "Operation failed" }, failedStage)
      }
    }
  }

  private fun markLoadStarted(flow: MutableStateFlow<Map<String, VpsLoadState>>, key: String) {
    flow.update { current ->
      val previous = current[key] ?: VpsLoadState()
      val next = if (!previous.loaded || previous.error != null) {
        VpsLoadState(loaded = false, loading = true)
      } else {
        previous
      }
      current + (key to next)
    }
  }

  private fun markLoadSucceeded(flow: MutableStateFlow<Map<String, VpsLoadState>>, key: String) {
    flow.update { it + (key to VpsLoadState(loaded = true, loading = false)) }
  }

  private fun markLoadFailed(flow: MutableStateFlow<Map<String, VpsLoadState>>, key: String, message: String) {
    flow.update { current ->
      val previous = current[key] ?: VpsLoadState()
      val next = if (previous.loaded && previous.error == null) {
        previous.copy(loading = false)
      } else {
        VpsLoadState(loaded = true, loading = false, error = message)
      }
      current + (key to next)
    }
  }

  private fun startOperation(title: String) { _operation.value = VpsOperationState(running = true, title = title, stage = title) }
  private fun updateStage(stage: String) { _operation.value = _operation.value.copy(stage = stage) }
  private fun appendLine(line: String) {
    if (line.isBlank()) return
    _operation.value = _operation.value.copy(log = (_operation.value.log + line).takeLast(250))
  }
  private fun finishOperation() { _operation.value = _operation.value.copy(running = false) }
  private fun failOperation(error: String, stage: String? = null) {
    _operation.value = _operation.value.copy(running = false, error = error, stage = stage ?: _operation.value.stage)
  }
  fun clearOperation() { if (!_operation.value.running) _operation.value = VpsOperationState() }

  companion object {
    private const val MONITOR_LIST_KEY = "list"
    private const val MONITOR_INTERVAL_MS = 15_000L
    private const val SERVER_STATE_PERSIST_INTERVAL_MS = 5 * 60_000L
    private const val REBOOT_INITIAL_DELAY_MS = 4_000L
    private const val REBOOT_POLL_INTERVAL_MS = 3_000L
    private const val REBOOT_MIN_RETURN_MS = 12_000L
    private const val REBOOT_WAIT_TIMEOUT_MS = 3 * 60_000L
    fun profileKey(serverId: String, kind: VpsServiceKind) = "$serverId:${kind.wireId}"
    fun clientKey(serverId: String, kind: VpsServiceKind, profileId: String) = "$serverId:${kind.wireId}:$profileId"
    private fun sanitizePath(value: String) = value.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(60).ifBlank { "server" }
    private fun sanitizeFileName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "config.txt" }
    private fun escapeSingle(value: String) = value.replace("'", "'\\''")
  }
}

class VpsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(VpsViewModel::class.java)) return VpsViewModel(application) as T
    error("Unknown ViewModel class: ${modelClass.name}")
  }
}
