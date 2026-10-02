package com.android.zdtd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground VpnService for the app-owned non-root path.
 *
 * Native helpers are executed directly from nativeLibraryDir. Runtime files are
 * kept under files/nonroot and every local listener is explicitly loopback-only.
 */
class NonRootVpnService : VpnService() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val runtimeStore by lazy { NonRootRuntimeStore(applicationContext) }
  private val portRegistry by lazy { NonRootPortRegistry(applicationContext) }
  private var runtimeJob: Job? = null
  private var tun: ParcelFileDescriptor? = null
  private val processes = mutableListOf<ManagedProcess>()
  @Volatile private var hevStarted = false
  @Volatile private var stopRequested = false

  override fun onCreate() {
    super.onCreate()
    runtimeStore.ensureLayout()
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action ?: ACTION_START) {
      ACTION_STOP -> requestStop()
      else -> requestStart()
    }
    return START_NOT_STICKY
  }

  override fun onRevoke() {
    requestStop()
    super.onRevoke()
  }

  override fun onDestroy() {
    runtimeJob?.cancel()
    stopNativeRuntime()
    serviceScope.cancel()
    if (NonRootVpnRuntime.state.value != NonRootVpnState.ERROR) {
      NonRootVpnRuntime.update(NonRootVpnState.STOPPED)
    }
    super.onDestroy()
  }

  private fun requestStart() {
    val current = NonRootVpnRuntime.state.value
    if (current == NonRootVpnState.STARTING || current == NonRootVpnState.RUNNING) return
    stopRequested = false
    startForegroundCompat(buildNotification())
    runtimeJob?.cancel()
    runtimeJob = serviceScope.launch {
      NonRootVpnRuntime.clearLogs()
      NonRootVpnRuntime.update(NonRootVpnState.STARTING)
      NonRootVpnRuntime.log(getString(R.string.non_root_log_preparing_runtime))
      try {
        startNativeRuntime()
        if (!stopRequested) {
          NonRootVpnRuntime.update(NonRootVpnState.RUNNING)
          NonRootVpnRuntime.log(getString(R.string.non_root_log_vpn_running))
        }
      } catch (_: CancellationException) {
        if (!stopRequested) throw CancellationException()
      } catch (t: Throwable) {
        stopNativeRuntime()
        val message = t.message ?: t.javaClass.simpleName
        NonRootVpnRuntime.log(
          getString(R.string.non_root_log_error, message),
          NonRootRuntimeLogLevel.ERROR,
        )
        NonRootVpnRuntime.update(NonRootVpnState.ERROR, message)
        stopForegroundCompat()
        stopSelf()
      }
    }
  }

  private fun requestStop() {
    if (stopRequested) return
    stopRequested = true
    NonRootVpnRuntime.update(NonRootVpnState.STOPPING)
    NonRootVpnRuntime.log(getString(R.string.non_root_log_stop_requested))
    runtimeJob?.cancel()
    runtimeJob = serviceScope.launch {
      stopNativeRuntime(reportProgress = true)
      NonRootVpnRuntime.update(NonRootVpnState.STOPPED)
      NonRootVpnRuntime.log(getString(R.string.non_root_log_vpn_stopped))
      stopForegroundCompat()
      stopSelf()
    }
  }

  private suspend fun startNativeRuntime() {
    runtimeStore.ensureLayout()
    stopNativeRuntime()

    val settings = NonRootSettingsStore(applicationContext)
    val mode = settings.getWorkMode()
    val appRoutingMode = settings.getAppRoutingMode()
    val appRoutingPackages = installedRoutingPackages(settings.getAppRoutingPackages())
    if (appRoutingMode == NonRootAppRoutingMode.ONLY_SELECTED && appRoutingPackages.isEmpty()) {
      error(getString(R.string.non_root_app_routing_only_selected_empty_error))
    }
    NonRootVpnRuntime.log(
      getString(
        R.string.non_root_log_start_mode,
        getString(
          if (mode == NonRootWorkMode.DIRECT) R.string.non_root_mode_direct
          else R.string.non_root_mode_cascade,
        ),
      )
    )
    val socksTarget = when (mode) {
      NonRootWorkMode.DIRECT -> startDirect()
      NonRootWorkMode.CASCADE -> startCascade()
    }

    NonRootVpnRuntime.log(getString(R.string.non_root_log_create_vpn_interface))
    val established = establishVpnInterface(appRoutingMode, appRoutingPackages)
      ?: error("VpnService.Builder.establish() returned null")
    tun = established
    val hevConfig = writeHevConfig(socksTarget)
    NonRootVpnRuntime.log(getString(R.string.non_root_log_start_component, "HEV"))
    startHev(hevConfig, established.fd)
    waitForHevRunning()
    NonRootVpnRuntime.log(getString(R.string.non_root_log_component_ready, "HEV"))
  }


  private suspend fun startDirect(): SocksTarget {
    val store = NonRootCascadeStore(applicationContext)
    val state = store.load()
    val profile = state.profiles.firstOrNull { it.id == state.directSelectedProfileId }
      ?: error("Direct mode has no selected proxy profile")
    check(profile.directEligible) { "Profiles with multiple servers are available only in Cascade mode" }
    return when (profile.toolId) {
      NonRootCascadeProfile.TOOL_OPERA_PROXY -> {
        startOperaProfile(
          label = "direct-${safeFileName(profile.id)}",
          displayName = profile.name,
          port = profile.port,
          byedpiPort = profile.byedpiPort,
          config = profile.operaConfig,
        )
        SocksTarget(port = profile.port)
      }
      else -> {
        val server = profile.servers.singleOrNull() ?: error("Direct profile must contain exactly one server")
        startBackendServer(profile, server, "direct-${safeFileName(profile.id)}-${safeFileName(server.id)}")
        SocksTarget(port = server.port, udpMode = "udp")
      }
    }
  }

  private suspend fun startCascade(): SocksTarget {
    val state = NonRootCascadeStore(applicationContext).load()
    val enabled = state.profiles.filter { it.enabled }.associateBy { it.id }
    val orderedProfiles = state.route.mapNotNull { item ->
      if (item.type == NonRootCascadeRouteItemType.PROFILE) enabled[item.profileId] else null
    }
    val hasBackend = orderedProfiles.any { cascadePorts(it).isNotEmpty() }
    if (!hasBackend && state.route.none { it.type == NonRootCascadeRouteItemType.DIRECT_START }) {
      error("Cascade has no enabled proxy server")
    }

    for (profile in orderedProfiles) {
      when (profile.toolId) {
        NonRootCascadeProfile.TOOL_OPERA_PROXY -> startOperaProfile(
          label = "cascade-${safeFileName(profile.id)}",
          displayName = profile.name,
          port = profile.port,
          byedpiPort = profile.byedpiPort,
          config = profile.operaConfig,
        )
        else -> profile.servers.filter { it.enabled }.forEachIndexed { index, server ->
          startBackendServer(
            profile = profile,
            server = server,
            label = "cascade-${safeFileName(profile.id)}-${index + 1}-${safeFileName(server.id)}",
          )
        }
      }
    }

    val listenPort = portRegistry.getOrAllocate(NonRootPortRegistry.T2S_LISTEN_KEY)
    val apiPort = portRegistry.getOrAllocate(NonRootPortRegistry.T2S_API_KEY)
    val args = buildT2sArgs(state, enabled, listenPort, apiPort)
    NonRootVpnRuntime.log(getString(R.string.non_root_log_start_component, "T2S"))
    startProcess(
      name = "t2s",
      executable = nativeExecutable("libzdt_t2s.so"),
      args = args,
      logFile = File(runtimeStore.logsDir, "t2s.log"),
    )
    waitForLoopbackPort(listenPort, "T2S", "t2s")
    NonRootVpnRuntime.log(getString(R.string.non_root_log_component_ready, "T2S"))

    val token = runtimeStore.ensureApiToken().readText().trim()
    check(token.isNotEmpty()) { "Non-root API token is empty" }
    return SocksTarget(port = listenPort, username = "zdtd", password = token, udpMode = "udp")
  }

  private suspend fun startBackendServer(
    profile: NonRootCascadeProfile,
    server: NonRootBackendServer,
    label: String,
  ) {
    val display = "${nonRootBackendName(profile.toolId)} · ${profile.name} / ${server.name}"
    NonRootVpnRuntime.log(getString(R.string.non_root_log_start_component, display))
    when (profile.toolId) {
      NonRootCascadeProfile.TOOL_HYSTERIA2 -> startHysteria2Server(server, label)
      NonRootCascadeProfile.TOOL_SING_BOX -> startSingBoxServer(server, label)
      NonRootCascadeProfile.TOOL_MIERU -> startMieruServer(server, label)
      NonRootCascadeProfile.TOOL_WIREPROXY -> startWireProxyServer(server, label)
      else -> error("Unsupported non-root backend: ${profile.toolId}")
    }
    waitForLoopbackPort(server.port, nonRootBackendName(profile.toolId), label)
    NonRootVpnRuntime.log(getString(R.string.non_root_log_component_ready, display))
  }

  private fun startHysteria2Server(server: NonRootBackendServer, label: String) {
    val obj = JSONObject(server.configText.ifBlank { "{}" })
    check(obj.optString("server").isNotBlank()) { "Hysteria2 config requires server" }
    listOf("http", "tcpForwarding", "udpForwarding", "tcpTProxy", "udpTProxy", "tcpRedirect", "tun", "inbounds", "outbounds", "route", "dns").forEach(obj::remove)
    val socks = obj.optJSONObject("socks5") ?: JSONObject()
    socks.put("listen", "${NonRootPortRegistry.LOOPBACK}:${server.port}")
    socks.put("disableUDP", false)
    obj.put("socks5", socks)
    val config = runtimeConfigFile("hysteria2-$label.json", obj.toString(2))
    startProcess(
      name = label,
      executable = nativeExecutable("libzdt_hysteria2.so"),
      args = listOf("--disable-update-check", "-f", "console", "-l", server.logLevel.ifBlank { "info" }, "-c", config.absolutePath, "client"),
      logFile = File(runtimeStore.logsDir, "$label.log"),
    )
  }

  private fun startSingBoxServer(server: NonRootBackendServer, label: String) {
    val obj = JSONObject(server.configText.ifBlank { "{}" })
    val inbound = JSONObject()
      .put("type", "mixed")
      .put("tag", "mixed-in")
      .put("listen", NonRootPortRegistry.LOOPBACK)
      .put("listen_port", server.port)
    obj.put("inbounds", JSONArray().put(inbound))
    val config = runtimeConfigFile("sing-box-$label.json", obj.toString(2))
    startProcess(
      name = label,
      executable = nativeExecutable("libzdt_singbox.so"),
      args = listOf("run", "-c", config.absolutePath),
      logFile = File(runtimeStore.logsDir, "$label.log"),
    )
  }

  private fun startMieruServer(server: NonRootBackendServer, label: String) {
    val obj = JSONObject(server.configText.ifBlank { "{}" })
    val rpcPort = server.auxPort.takeIf { it in NonRootPortRegistry.MIN_PORT..65535 }
      ?: error("Mieru RPC port is invalid")
    val profiles = obj.optJSONArray("profiles")
    if (obj.optString("activeProfile").isBlank() && profiles != null) {
      for (index in 0 until profiles.length()) {
        val profileName = profiles.optJSONObject(index)?.optString("profileName").orEmpty().trim()
        if (profileName.isNotEmpty()) {
          obj.put("activeProfile", profileName)
          break
        }
      }
    }
    obj.put("socks5Port", server.port)
    obj.put("rpcPort", rpcPort)
    obj.put("loggingLevel", server.logLevel.ifBlank { "info" }.uppercase())
    obj.put("socks5ListenLAN", false)
    obj.remove("httpProxyPort")
    obj.remove("httpProxyListenLAN")
    val config = runtimeConfigFile("mieru-$label.json", obj.toString(2))
    startProcess(
      name = label,
      executable = nativeExecutable("libzdt_mieru.so"),
      args = listOf("run"),
      logFile = File(runtimeStore.logsDir, "$label.log"),
      environment = mapOf("MIERU_CONFIG_JSON_FILE" to config.absolutePath),
    )
  }

  private fun startWireProxyServer(server: NonRootBackendServer, label: String) {
    val config = runtimeConfigFile("wireproxy-$label.conf", upsertWireProxySocks5Bind(server.configText, server.port))
    startProcess(
      name = label,
      executable = nativeExecutable("libzdt_wireproxy.so"),
      args = listOf("-c", config.absolutePath),
      logFile = File(runtimeStore.logsDir, "$label.log"),
    )
  }

  private fun runtimeConfigFile(name: String, content: String): File = File(runtimeStore.configsDir, name).apply {
    parentFile?.mkdirs()
    writeText(content)
  }

  private fun nonRootBackendName(toolId: String): String = when (toolId) {
    NonRootCascadeProfile.TOOL_HYSTERIA2 -> "Hysteria2"
    NonRootCascadeProfile.TOOL_SING_BOX -> "sing-box"
    NonRootCascadeProfile.TOOL_MIERU -> "Mieru"
    NonRootCascadeProfile.TOOL_WIREPROXY -> "WireProxy"
    else -> "Opera Proxy"
  }

  private fun cascadePorts(profile: NonRootCascadeProfile): List<Int> = when (profile.toolId) {
    NonRootCascadeProfile.TOOL_OPERA_PROXY -> listOf(profile.port)
    else -> profile.servers.filter { it.enabled }.map { it.port }
  }

  private suspend fun startOperaProfile(
    label: String,
    displayName: String,
    port: Int,
    byedpiPort: Int,
    config: NonRootDirectOperaConfig,
  ) {
    val sni = config.serverSni.trim().takeIf { it.isNotEmpty() }
      ?: error("Opera Proxy requires an SNI")
    val useByeDpi = config.useByedpi

    if (useByeDpi) {
      NonRootVpnRuntime.log(getString(R.string.non_root_log_start_component, "ByeDPI · $displayName"))
      startByeDpi(label, byedpiPort, config.byedpiStartArgs)
      waitForLoopbackPort(byedpiPort, "ByeDPI", "byedpi-$label")
      NonRootVpnRuntime.log(getString(R.string.non_root_log_component_ready, "ByeDPI · $displayName"))
    }

    val caFile = ensureOperaCaBundle()
    val defaults = NonRootDirectOperaConfig()
    val bootstrapDns = config.bootstrapDns.map(String::trim).filter(String::isNotEmpty)
      .ifEmpty { defaults.bootstrapDns }
      .joinToString(",")
    val operaArgs = mutableListOf(
      "-bind-address", "${NonRootPortRegistry.LOOPBACK}:$port",
      "-socks-mode",
      "-cafile", caFile.absolutePath,
      "-fake-SNI", sni,
      "-bootstrap-dns", bootstrapDns,
      "-api-user-agent", config.apiUserAgent.ifBlank { defaults.apiUserAgent },
      "-country", config.serverRegion.ifBlank { defaults.serverRegion },
      "-init-retry-interval", config.initRetryInterval.ifBlank { defaults.initRetryInterval },
      "-server-selection", config.serverSelection.ifBlank { defaults.serverSelection },
      "-server-selection-dl-limit", config.serverSelectionDlLimit.ifBlank { defaults.serverSelectionDlLimit },
      "-verbosity", config.verbosity.ifBlank { defaults.verbosity },
      "-server-selection-test-url", config.serverSelectionTestUrl.ifBlank { defaults.serverSelectionTestUrl },
    )
    NonRootVpnRuntime.log(getString(R.string.non_root_log_start_component, "Opera Proxy · $displayName"))
    val selectedApiProxy = NonRootOperaApiProxyResolver.resolve(
      raw = config.apiProxy,
      cacheFile = File(runtimeStore.configsDir, "api_proxy_list.txt"),
      logFile = File(runtimeStore.logsDir, "api_proxy_check-$label.log"),
    )
    if (!selectedApiProxy.isNullOrBlank()) operaArgs += listOf("-api-proxy", selectedApiProxy)
    if (useByeDpi) operaArgs += listOf("-proxy", "socks5://${NonRootPortRegistry.LOOPBACK}:$byedpiPort")
    if (config.overrideProxyAddress.isNotBlank()) {
      operaArgs += listOf("-override-proxy-address", config.overrideProxyAddress.trim())
    }

    startProcess(
      name = "opera-$label",
      executable = nativeExecutable("libzdt_operaproxy.so"),
      args = operaArgs,
      logFile = File(runtimeStore.logsDir, "opera-$label.log"),
    )
    waitForLoopbackPort(port, "Opera Proxy", "opera-$label")
    NonRootVpnRuntime.log(getString(R.string.non_root_log_component_ready, "Opera Proxy · $displayName"))

    if (useByeDpi && config.restartByedpiAfterOpera) {
      NonRootVpnRuntime.log(getString(R.string.non_root_log_restart_component, "ByeDPI · $displayName"))
      stopProcess("byedpi-$label")
      startByeDpi(label, byedpiPort, config.byedpiRestartArgs)
      waitForLoopbackPort(byedpiPort, "ByeDPI", "byedpi-$label")
      NonRootVpnRuntime.log(getString(R.string.non_root_log_component_ready, "ByeDPI · $displayName"))
    }
  }

  private fun startByeDpi(label: String, port: Int, extraArgs: String) {
    val args = mutableListOf("-i", NonRootPortRegistry.LOOPBACK, "-p", port.toString(), "-x", "0")
    args += splitCommandLine(extraArgs)
    startProcess(
      name = "byedpi-$label",
      executable = nativeExecutable("libzdt_byedpi.so"),
      args = args,
      logFile = File(runtimeStore.logsDir, "byedpi-$label.log"),
    )
  }

  private fun buildT2sArgs(
    state: NonRootCascadeState,
    enabledProfiles: Map<String, NonRootCascadeProfile>,
    listenPort: Int,
    apiPort: Int,
  ): List<String> {
    val actualPorts = state.route.flatMap { item ->
      if (item.type == NonRootCascadeRouteItemType.PROFILE) {
        enabledProfiles[item.profileId]?.let(::cascadePorts).orEmpty()
      } else emptyList()
    }
    val directFirst = state.backendMode == NonRootCascadeBackendMode.PRIORITY &&
      state.route.firstOrNull()?.type == NonRootCascadeRouteItemType.DIRECT_START
    val blockDirect = state.backendMode == NonRootCascadeBackendMode.PRIORITY &&
      state.route.lastOrNull()?.type == NonRootCascadeRouteItemType.DIRECT_BLOCK

    val socksPorts = buildList {
      if (directFirst) add(0)
      addAll(actualPorts)
      if (blockDirect) add(0)
    }
    check(socksPorts.isNotEmpty()) { "T2S route is empty" }

    val args = mutableListOf(
      "--non-root",
      "--api-dir", runtimeStore.apiDir.absolutePath,
      "--token-file", runtimeStore.tokenFile.absolutePath,
      "--listen-addr", NonRootPortRegistry.LOOPBACK,
      "--listen-port", listenPort.toString(),
      "--socks-host", NonRootPortRegistry.LOOPBACK,
      "--socks-port", socksPorts.joinToString(","),
      "--backend-mode", state.backendMode.name.lowercase(),
      "--buffer-size", state.t2s.bufferSize.toString(),
      "--idle-timeout", state.t2s.idleTimeoutSeconds.toString(),
      "--connect-timeout", state.t2s.connectTimeoutSeconds.toString(),
      "--max-conns", state.t2s.maxConnections.toString(),
      "--download-limit-mbit", state.t2s.downloadLimitMbit,
      "--connect-stagger-ms", state.t2s.connectStaggerMs.toString(),
      "--web-socket",
      "--web-addr", NonRootPortRegistry.LOOPBACK,
      "--web-port", apiPort.toString(),
      "--program", "nonroot-cascade",
      "--profile", "Cascade",
      "--scope", "nonroot/cascade",
    )
    if (state.backendMode == NonRootCascadeBackendMode.PRIORITY) {
      priorityGroups(state.route, enabledProfiles).takeIf { it.isNotBlank() }?.let {
        args += listOf("--backend-priority", it)
      }
      if (state.t2s.prioritySpeedAware) args += "--priority-speed-aware"
    }
    if (!state.t2s.peerCoordination) args += "--no-peer-coordination"
    if (!state.t2s.serializeBackendConnects) args += "--no-serialize-backend-connects"
    return args
  }

  private fun priorityGroups(
    route: List<NonRootCascadeRouteItem>,
    profiles: Map<String, NonRootCascadeProfile>,
  ): String {
    val groups = mutableListOf<MutableList<Int>>(mutableListOf())
    route.forEach { item ->
      when (item.type) {
        NonRootCascadeRouteItemType.PROFILE -> profiles[item.profileId]?.let { groups.last() += cascadePorts(it) }
        NonRootCascadeRouteItemType.GROUP -> if (groups.last().isNotEmpty()) groups.add(mutableListOf())
        NonRootCascadeRouteItemType.DIRECT_START,
        NonRootCascadeRouteItemType.DIRECT_BLOCK -> Unit
      }
    }
    return groups.filter { it.isNotEmpty() }.joinToString(";") { it.joinToString(",") }
  }

  private fun establishVpnInterface(
    appRoutingMode: NonRootAppRoutingMode,
    appRoutingPackages: Set<String>,
  ): ParcelFileDescriptor? {
    val builder = Builder()
      .setSession(getString(R.string.app_name))
      .setMtu(VPN_MTU)
      .addAddress("198.18.0.1", 32)
      .addAddress("fc00::1", 128)
      .addRoute("0.0.0.0", 0)
      .addRoute("::", 0)
      .addDnsServer("198.18.0.2")

    when (appRoutingMode) {
      NonRootAppRoutingMode.ALL -> {
        runCatching { builder.addDisallowedApplication(packageName) }
      }
      NonRootAppRoutingMode.ONLY_SELECTED -> {
        // Android forbids mixing allowed and disallowed application lists.
        // ZDT-D is never added to this allowlist, which keeps its own backend
        // sockets outside the VPN and prevents routing loops.
        var added = 0
        appRoutingPackages.forEach { candidate ->
          if (runCatching { builder.addAllowedApplication(candidate) }.isSuccess) added += 1
        }
        check(added > 0) { getString(R.string.non_root_app_routing_only_selected_empty_error) }
      }
      NonRootAppRoutingMode.EXCLUDE_SELECTED -> {
        runCatching { builder.addDisallowedApplication(packageName) }
        appRoutingPackages.forEach { candidate ->
          runCatching { builder.addDisallowedApplication(candidate) }
        }
      }
    }
    return builder.establish()
  }

  @Suppress("DEPRECATION")
  private fun installedRoutingPackages(packages: Set<String>): Set<String> =
    packages.filterTo(linkedSetOf()) { candidate ->
      candidate != packageName && runCatching { packageManager.getApplicationInfo(candidate, 0) }.isSuccess
    }

  private fun writeHevConfig(target: SocksTarget): File {
    val file = File(runtimeStore.configsDir, "hev.yml")
    val lines = mutableListOf(
      "tunnel:",
      "  name: tun0",
      "  mtu: $VPN_MTU",
      "  multi-queue: false",
      "  ipv4: 198.18.0.1",
      "  ipv6: 'fc00::1'",
      "  icmp: 'off'",
      "socks5:",
      "  address: ${NonRootPortRegistry.LOOPBACK}",
      "  port: ${target.port}",
      "  udp: '${target.udpMode}'",
    )
    target.username?.let { lines += "  username: '${yamlQuote(it)}'" }
    target.password?.let { lines += "  password: '${yamlQuote(it)}'" }
    lines += listOf(
      "mapdns:",
      "  address: 198.18.0.2",
      "  port: 53",
      "  network: 100.64.0.0",
      "  netmask: 255.192.0.0",
      "  cache-size: 10000",
      "misc:",
      "  task-stack-size: 86016",
      "  tcp-buffer-size: 65536",
      "  connect-timeout: 10000",
      "  tcp-read-write-timeout: 300000",
      "  log-file: '${yamlQuote(File(runtimeStore.logsDir, "hev.log").absolutePath)}'",
      "  log-level: warn",
      "  limit-nofile: 65535",
    )
    file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    return file
  }

  private suspend fun waitForHevRunning(timeoutMs: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (runCatching { HevBridge.isRunning() }.getOrDefault(false)) return
      if (!hevStarted) error("HEV failed to start")
      delay(50)
    }
    error("HEV did not enter running state")
  }

  private fun startHev(config: File, fd: Int) {
    check(!hevStarted) { "HEV is already running" }
    hevStarted = true
    serviceScope.launch(Dispatchers.IO) {
      var failureMessage: String? = null
      runCatching { HevBridge.start(config.absolutePath, fd) }
        .onFailure { failureMessage = it.message ?: "HEV failed" }
      hevStarted = false
      if (!stopRequested && NonRootVpnRuntime.state.value == NonRootVpnState.RUNNING) {
        val message = failureMessage ?: "HEV stopped unexpectedly"
        NonRootVpnRuntime.log(
          getString(R.string.non_root_log_error, message),
          NonRootRuntimeLogLevel.ERROR,
        )
        NonRootVpnRuntime.update(NonRootVpnState.ERROR, message)
        stopNativeRuntime()
        stopForegroundCompat()
        stopSelf()
      }
    }
  }

  @Synchronized
  private fun startProcess(
    name: String,
    executable: File,
    args: List<String>,
    logFile: File,
    environment: Map<String, String> = emptyMap(),
  ): Process {
    check(processes.none { it.name == name && it.process.isAlive }) { "$name is already running" }
    check(executable.isFile) { "Native executable missing: ${executable.absolutePath}" }
    check(executable.canExecute()) { "Native executable is not executable: ${executable.absolutePath}" }
    logFile.parentFile?.mkdirs()
    val builder = ProcessBuilder(listOf(executable.absolutePath) + args)
      .directory(runtimeStore.runtimeDir)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.to(logFile))
    builder.environment().putAll(environment)
    val process = builder.start()
    processes += ManagedProcess(name, process)
    return process
  }

  @Synchronized
  private fun stopProcess(name: String) {
    val matches = processes.filter { it.name == name }
    matches.forEach { terminateProcess(it.process) }
    processes.removeAll(matches.toSet())
  }

  @Synchronized
  private fun stopNativeRuntime(reportProgress: Boolean = false) {
    if (hevStarted || runCatching { HevBridge.isRunning() }.getOrDefault(false)) {
      if (reportProgress) NonRootVpnRuntime.log(getString(R.string.non_root_log_stop_component, "HEV"))
      runCatching { HevBridge.stop() }
    }
    hevStarted = false
    if (tun != null) {
      if (reportProgress) NonRootVpnRuntime.log(getString(R.string.non_root_log_close_vpn_interface))
      runCatching { tun?.close() }
      tun = null
    }
    if (processes.isNotEmpty()) {
      if (reportProgress) NonRootVpnRuntime.log(getString(R.string.non_root_log_stop_proxy_processes))
      processes.asReversed().forEach { terminateProcess(it.process) }
      processes.clear()
    }
  }

  private fun terminateProcess(process: Process) {
    if (!process.isAlive) return
    process.destroy()
    runCatching {
      if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        process.waitFor(800, TimeUnit.MILLISECONDS)
      }
    }
  }

  private suspend fun waitForLoopbackPort(
    port: Int,
    label: String,
    processName: String,
    timeoutMs: Long = 20_000,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val ready = withContext(Dispatchers.IO) {
        runCatching {
          Socket().use { socket -> socket.connect(InetSocketAddress(NonRootPortRegistry.LOOPBACK, port), 350) }
          true
        }.getOrDefault(false)
      }
      if (ready) return
      val dead = synchronized(this) {
        processes.firstOrNull { it.name == processName && !it.process.isAlive }
      }
      if (dead != null) error("$label exited before becoming ready")
      delay(250)
    }
    error("$label did not open ${NonRootPortRegistry.LOOPBACK}:$port")
  }

  private fun nativeExecutable(name: String): File = File(applicationInfo.nativeLibraryDir, name)

  private fun ensureOperaCaBundle(): File {
    val target = File(runtimeStore.configsDir, "opera-ca.bundle")
    if (!target.isFile || target.length() == 0L) {
      assets.open("nonroot/ca.bundle").use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
      }
    }
    return target
  }

  private fun buildNotification(): Notification {
    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, NonRootActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val stopIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, NonRootVpnService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(getString(R.string.non_root_home_title))
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(0, getString(R.string.widget_action_stop), stopIntent)
      .build()
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, getString(R.string.non_root_home_title), NotificationManager.IMPORTANCE_LOW)
    )
  }

  private fun startForegroundCompat(notification: Notification) {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
      0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private fun stopForegroundCompat() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
  }

  private data class SocksTarget(
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val udpMode: String = "tcp",
  )

  private data class ManagedProcess(val name: String, val process: Process)

  private object HevBridge {
    private val clazz by lazy { Class.forName("hev.htproxy.TProxyService") }
    private val startMethod by lazy { clazz.getMethod("TProxyStartService", String::class.java, java.lang.Integer.TYPE) }
    private val stopMethod by lazy { clazz.getMethod("TProxyStopService") }
    private val runningMethod by lazy { clazz.getMethod("TProxyIsRunning") }

    fun start(configPath: String, fd: Int): Boolean = startMethod.invoke(null, configPath, fd) as? Boolean ?: false
    fun stop(): Boolean = stopMethod.invoke(null) as? Boolean ?: false
    fun isRunning(): Boolean = runningMethod.invoke(null) as? Boolean ?: false
  }

  private fun upsertWireProxySocks5Bind(configText: String, port: Int): String {
    val line = "BindAddress = ${NonRootPortRegistry.LOOPBACK}:$port"
    val lines = configText.lines().toMutableList()
    var sectionStart = -1
    var sectionEnd = lines.size
    for (i in lines.indices) {
      val trimmed = lines[i].trim()
      if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.equals("[Socks5]", ignoreCase = true)) {
        sectionStart = i
        var j = i + 1
        while (j < lines.size && !(lines[j].trim().startsWith("[") && lines[j].trim().endsWith("]"))) j++
        sectionEnd = j
        break
      }
    }
    if (sectionStart >= 0) {
      for (i in sectionStart + 1 until sectionEnd) {
        if (lines[i].trim().startsWith("BindAddress", ignoreCase = true)) {
          lines[i] = line
          return lines.joinToString("\n")
        }
      }
      lines.add(sectionEnd, line)
      return lines.joinToString("\n")
    }
    return buildString {
      append(configText.trimEnd())
      if (isNotEmpty()) append("\n\n")
      append("[Socks5]\n")
      append(line)
      append('\n')
    }
  }

  companion object {
    const val ACTION_START = "com.android.zdtd.service.action.NON_ROOT_VPN_START"
    const val ACTION_STOP = "com.android.zdtd.service.action.NON_ROOT_VPN_STOP"
    private const val CHANNEL_ID = "zdt_nonroot_vpn"
    private const val NOTIFICATION_ID = 92041
    private const val VPN_MTU = 1500

    fun startIntent(context: android.content.Context): Intent =
      Intent(context, NonRootVpnService::class.java).setAction(ACTION_START)

    fun stop(context: android.content.Context) {
      context.startService(Intent(context, NonRootVpnService::class.java).setAction(ACTION_STOP))
    }

    private fun safeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun yamlQuote(value: String): String = value.replace("'", "''")

    private fun splitCommandLine(raw: String): List<String> {
      val out = mutableListOf<String>()
      val current = StringBuilder()
      var quote: Char? = null
      var escape = false
      raw.forEach { ch ->
        when {
          escape -> { current.append(ch); escape = false }
          ch == '\\' -> escape = true
          quote != null && ch == quote -> quote = null
          quote == null && (ch == '\'' || ch == '"') -> quote = ch
          quote == null && ch.isWhitespace() -> if (current.isNotEmpty()) {
            out += current.toString()
            current.clear()
          }
          else -> current.append(ch)
        }
      }
      if (escape) current.append('\\')
      if (current.isNotEmpty()) out += current.toString()
      return out
    }
  }
}
