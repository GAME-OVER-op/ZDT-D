package com.android.zdtd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ContextCompat
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

/** Standalone app-owned lifecycle for Telegram WS Proxy in non-root mode. */
class NonRootTgWsService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val runtimeStore by lazy { NonRootRuntimeStore(applicationContext) }
  private var runtimeJob: Job? = null
  private var process: Process? = null

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
    stopProcess()
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
    if (!restart && process?.isAlive == true) return
    runtimeJob?.cancel()
    runtimeJob = serviceScope.launch {
      try {
        stopProcess()
        startProcess(config)
      } catch (_: CancellationException) {
        throw CancellationException()
      } catch (t: Throwable) {
        File(runtimeStore.logsDir, "tgwsproxy-service.log").appendText(
          "${System.currentTimeMillis()} ERROR ${t.message ?: t.javaClass.simpleName}\n"
        )
        stopProcess()
        stopForegroundCompat()
        stopSelf()
      }
    }
  }

  private suspend fun startProcess(config: NonRootTgWsConfig) {
    check(NonRootTgWsStore.isValidSecret(config.secret)) { "Telegram WS Proxy secret is invalid" }
    val args = mutableListOf(
      "--port", config.port.toString(),
      "--host", NonRootPortRegistry.LOOPBACK,
      "--secret", NonRootTgWsStore.normalizeSecret(config.secret),
      "--log-file", File(runtimeStore.logsDir, "tgwsproxy.log").absolutePath,
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

    val executable = nativeExecutable("libzdt_tgwsproxy.so")
    check(executable.isFile) { "Native executable missing: ${executable.absolutePath}" }
    val processLog = File(runtimeStore.logsDir, "tgwsproxy-process.log").apply { parentFile?.mkdirs() }
    process = ProcessBuilder(listOf(executable.absolutePath) + args)
      .directory(runtimeStore.runtimeDir)
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.appendTo(processLog))
      .start()

    repeat(100) {
      if (process?.isAlive != true) error("Telegram WS Proxy exited before becoming ready")
      if (canConnect(config.port)) return
      delay(100)
    }
    error("Telegram WS Proxy did not open ${NonRootPortRegistry.LOOPBACK}:${config.port}")
  }

  private fun stopRuntimeAndSelf() {
    runtimeJob?.cancel()
    runtimeJob = null
    stopProcess()
    stopForegroundCompat()
    stopSelf()
  }

  @Synchronized
  private fun stopProcess() {
    val child = process ?: return
    process = null
    runCatching {
      child.destroy()
      if (!child.waitFor(2, TimeUnit.SECONDS)) {
        child.destroyForcibly()
        child.waitFor(1, TimeUnit.SECONDS)
      }
    }
  }

  private fun canConnect(port: Int): Boolean = runCatching {
    Socket().use { socket ->
      socket.connect(InetSocketAddress(NonRootPortRegistry.LOOPBACK, port), 200)
    }
    true
  }.getOrDefault(false)

  private fun nativeExecutable(name: String): File = File(applicationInfo.nativeLibraryDir, name)

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
      ContextCompat.startForegroundService(
        context,
        Intent(context, NonRootTgWsService::class.java).setAction(ACTION_START),
      )
    }

    fun restart(context: Context) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, NonRootTgWsService::class.java).setAction(ACTION_RESTART),
      )
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
