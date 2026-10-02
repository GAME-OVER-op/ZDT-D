package com.android.zdtd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.zdtd.service.plugin.com.ipc.ITgWsPlugin
import com.android.zdtd.service.plugin.com.ipc.ITgWsPluginCallback
import com.android.zdtd.service.tgwsplugin.TgWsPluginContract
import com.android.zdtd.service.tgwsplugin.TgWsPluginManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Foreground controller for the optional headless TGWS plugin in non-root mode. */
class NonRootTgWsService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val runtimeStore by lazy { NonRootRuntimeStore(applicationContext) }
  private var runtimeJob: Job? = null
  @Volatile private var plugin: ITgWsPlugin? = null
  @Volatile private var pluginBound = false
  @Volatile private var connectDeferred: CompletableDeferred<ITgWsPlugin>? = null
  private val logLock = Any()

  private val pluginCallback = object : ITgWsPluginCallback.Stub() {
    override fun onLog(line: String?) {
      val text = line.orEmpty()
      if (text.isBlank()) return
      runCatching {
        synchronized(logLock) {
          File(runtimeStore.logsDir, "tgwsproxy.log").appendText("$text\n")
        }
      }
    }

    override fun onStateChanged(state: Int, message: String?) {
      runCatching {
        File(runtimeStore.logsDir, "tgwsproxy-service.log").appendText(
          "${System.currentTimeMillis()} plugin_state=$state ${message.orEmpty()}\n"
        )
      }
    }
  }

  private val pluginConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      val service = ITgWsPlugin.Stub.asInterface(binder)
      plugin = service
      if (service != null) connectDeferred?.complete(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      plugin = null
    }

    override fun onBindingDied(name: ComponentName?) {
      plugin = null
      connectDeferred?.completeExceptionally(IllegalStateException("TGWS plugin binding died"))
    }

    override fun onNullBinding(name: ComponentName?) {
      plugin = null
      connectDeferred?.completeExceptionally(IllegalStateException("TGWS plugin returned a null binder"))
    }
  }

  override fun onCreate() {
    super.onCreate()
    runtimeStore.ensureLayout()
    ensureNotificationChannel()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action ?: ACTION_START) {
      ACTION_STOP -> {
        if (intent?.getBooleanExtra(EXTRA_PERSIST_DISABLED, false) == true) {
          val store = NonRootTgWsStore(applicationContext)
          store.save(store.load().copy(enabled = false))
        }
        stopRuntimeAndSelf()
        return START_NOT_STICKY
      }
      ACTION_RESTART -> requestStart(restart = true)
      else -> requestStart(restart = false)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    runtimeJob?.cancel()
    disconnectPlugin(stop = true)
    serviceScope.cancel()
    stopForegroundCompat()
    super.onDestroy()
  }

  private fun requestStart(restart: Boolean) {
    val config = NonRootTgWsStore(applicationContext).load()
    if (!config.enabled) {
      stopRuntimeAndSelf()
      return
    }
    startForegroundCompat(buildNotification())
    runtimeJob?.cancel()
    runtimeJob = serviceScope.launch {
      try {
        if (!TgWsPluginManager(applicationContext).isInstalled()) {
          error("TGWS plugin is not installed")
        }
        val remote = connectPlugin()
        if (!restart && runCatching { remote.isRunning }.getOrDefault(false)) return@launch
        runCatching { remote.stop() }
        startPlugin(config, remote)
      } catch (_: CancellationException) {
        throw CancellationException()
      } catch (t: Throwable) {
        File(runtimeStore.logsDir, "tgwsproxy-service.log").appendText(
          "${System.currentTimeMillis()} ERROR ${t.message ?: t.javaClass.simpleName}\n"
        )
        disconnectPlugin(stop = true)
        stopForegroundCompat()
        stopSelf()
      }
    }
  }

  private suspend fun startPlugin(config: NonRootTgWsConfig, remote: ITgWsPlugin) {
    check(NonRootTgWsStore.isValidSecret(config.secret)) { "Telegram WS Proxy secret is invalid" }
    check(remote.apiVersion == TgWsPluginContract.API_VERSION) {
      "TGWS plugin API mismatch: ${remote.apiVersion}"
    }
    val args = mutableListOf(
      "--port", config.port.toString(),
      "--host", NonRootPortRegistry.LOOPBACK,
      "--secret", NonRootTgWsStore.normalizeSecret(config.secret),
    )
    if (config.fakeTlsEnabled) {
      check(config.fakeTlsDomain.isNotBlank()) { "Telegram WS Proxy FakeTLS domain is required" }
      args += listOf("--listen-faketls-domain", config.fakeTlsDomain.trim())
    }
    config.dcIp.forEach { args += listOf("--dc-ip", it) }
    if (config.bufKb != 256) args += listOf("--buf-kb", config.bufKb.toString())
    if (config.poolSize != 4) args += listOf("--pool-size", config.poolSize.toString())
    if (config.maxConnections > 0) args += listOf("--max-connections", config.maxConnections.toString())
    if (config.verbose && !config.quiet) args += "--verbose"
    if (config.quiet) args += "--quiet"
    if (config.skipTlsVerify) args += "--danger-accept-invalid-certs"
    config.mtprotoProxies.forEach { args += listOf("--mtproto-proxy", it) }
    config.cfDomains.forEach { args += listOf("--cf-domain", it) }
    config.cfWorkerDomains.forEach { args += listOf("--cf-worker-domain", it) }
    if (config.cfPriority) args += "--cf-priority"
    if (config.cfBalance) args += "--cf-balance"
    if (config.defaultDomains) args += "--default-domains"
    if (config.frontingDomain.isNotBlank()) args += listOf("--fronting-domain", config.frontingDomain.trim())
    if (config.frontingCooldown != 1800L) args += listOf("--fronting-cooldown", config.frontingCooldown.toString())
    if (config.outboundProxy.isNotBlank()) args += listOf("--outbound-proxy", config.outboundProxy.trim())
    if (config.noOutboundProxy) args += "--no-outbound-proxy"
    if (config.noProxy.isNotBlank()) args += listOf("--no-proxy", config.noProxy.trim())

    File(runtimeStore.logsDir, "tgwsproxy.log").apply {
      parentFile?.mkdirs()
      appendText("${System.currentTimeMillis()} plugin=${runCatching { remote.pluginVersion }.getOrDefault("unknown")} start\n")
    }
    remote.start(args.toTypedArray(), pluginCallback)

    repeat(100) {
      if (!runCatching { remote.isRunning }.getOrDefault(false)) {
        error("Telegram WS Proxy plugin process exited before becoming ready")
      }
      if (canConnect(config.port)) return
      delay(100)
    }
    error("Telegram WS Proxy did not open ${NonRootPortRegistry.LOOPBACK}:${config.port}")
  }

  private suspend fun connectPlugin(): ITgWsPlugin {
    plugin?.let { return it }
    val deferred = CompletableDeferred<ITgWsPlugin>()
    connectDeferred = deferred
    val intent = Intent(TgWsPluginContract.BIND_ACTION).apply {
      component = ComponentName(TgWsPluginContract.PACKAGE_NAME, TgWsPluginContract.SERVICE_CLASS)
    }
    pluginBound = bindService(intent, pluginConnection, Context.BIND_AUTO_CREATE)
    check(pluginBound) { "Unable to bind TGWS plugin service" }
    return try {
      withTimeout(10_000L) { deferred.await() }
    } finally {
      connectDeferred = null
    }
  }

  private fun stopRuntimeAndSelf() {
    runtimeJob?.cancel()
    runtimeJob = null
    disconnectPlugin(stop = true)
    stopForegroundCompat()
    stopSelf()
  }

  @Synchronized
  private fun disconnectPlugin(stop: Boolean) {
    val remote = plugin
    plugin = null
    if (stop && remote != null) runCatching { remote.stop() }
    if (pluginBound) {
      pluginBound = false
      runCatching { unbindService(pluginConnection) }
    }
  }

  private fun canConnect(port: Int): Boolean = runCatching {
    Socket().use { socket ->
      socket.connect(InetSocketAddress(NonRootPortRegistry.LOOPBACK, port), 200)
    }
    true
  }.getOrDefault(false)

  private fun buildNotification(): Notification {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, NonRootActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      flags,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle(getString(R.string.non_root_tgws_title))
      .setContentText("${NonRootPortRegistry.LOOPBACK}:${NonRootTgWsStore(applicationContext).load().port}")
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(CHANNEL_ID, getString(R.string.non_root_tgws_title), NotificationManager.IMPORTANCE_LOW)
    )
  }

  private fun startForegroundCompat(notification: Notification) {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else 0
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private fun stopForegroundCompat() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
  }

  companion object {
    private const val ACTION_START = "com.android.zdtd.service.action.NON_ROOT_TGWS_START"
    private const val ACTION_RESTART = "com.android.zdtd.service.action.NON_ROOT_TGWS_RESTART"
    private const val ACTION_STOP = "com.android.zdtd.service.action.NON_ROOT_TGWS_STOP"
    private const val EXTRA_PERSIST_DISABLED = "persist_disabled"
    private const val CHANNEL_ID = "zdt_nonroot_tgws"
    private const val NOTIFICATION_ID = 92042

    fun start(context: Context) {
      context.startForegroundService(Intent(context, NonRootTgWsService::class.java).setAction(ACTION_START))
    }

    fun restart(context: Context) {
      context.startForegroundService(Intent(context, NonRootTgWsService::class.java).setAction(ACTION_RESTART))
    }

    fun stop(context: Context, persistDisabled: Boolean = true) {
      context.startService(
        Intent(context, NonRootTgWsService::class.java)
          .setAction(ACTION_STOP)
          .putExtra(EXTRA_PERSIST_DISABLED, persistDisabled),
      )
    }
  }
}
