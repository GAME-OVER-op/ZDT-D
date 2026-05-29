package com.android.zdtd.service.diagnostics.nfqws

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class NfqwsTesterOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runner: NfqwsTesterRunner
    private lateinit var rootConfig: RootConfigManager
    private var pollJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var titleView: TextView? = null
    private var statsView: TextView? = null
    private var stateView: TextView? = null
    private var worksButton: Button? = null
    private var failedButton: Button? = null
    private var skipButton: Button? = null
    private var stopButton: Button? = null

    private var currentProgram: String = "nfqws"
    private var strategies: List<String> = emptyList()
    private var currentIndex: Int = -1
    private val working = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    private val skipped = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        runner = NfqwsTesterRunner(applicationContext)
        rootConfig = RootConfigManager(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val program = intent.getStringExtra(EXTRA_PROGRAM)?.trim().orEmpty().ifBlank { "nfqws" }
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.nfqws_tester_notification_running)))
                serviceScope.launch { beginSession(program) }
            }
            ACTION_DECISION_WORKS -> decide("works")
            ACTION_DECISION_FAILED -> decide("failed")
            ACTION_DECISION_SKIP -> decide("skip")
            ACTION_STOP -> serviceScope.launch { stopTesterSession(getString(R.string.nfqws_tester_status_stopped)) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        runCatching {
            rootConfig.execRootSh(
                "if [ -x /data/user/0/${packageName}/no_backup/bin/nfqws_tester ]; then " +
                    "/data/user/0/${packageName}/no_backup/bin/nfqws_tester stop >/dev/null 2>&1; " +
                    "fi"
            )
        }
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun beginSession(program: String) {
        if (!Settings.canDrawOverlays(this)) {
            NfqwsTesterStore.update {
                it.copy(
                    phase = NfqwsTesterPhase.ERROR,
                    errorText = getString(R.string.nfqws_tester_overlay_required),
                    overlayPermissionGranted = false,
                )
            }
            stopSelfSafely()
            return
        }
        val serviceRunning = isZdtdServiceRunning()
        if (serviceRunning) {
            NfqwsTesterStore.update {
                it.copy(
                    phase = NfqwsTesterPhase.ERROR,
                    errorText = getString(R.string.nfqws_tester_service_must_be_stopped),
                    overlayPermissionGranted = true,
                )
            }
            stopSelfSafely()
            return
        }
        currentProgram = if (program == "nfqws2") "nfqws2" else "nfqws"
        working.clear(); failed.clear(); skipped.clear()
        NfqwsTesterStore.replace(
            NfqwsTesterSessionState(
                phase = NfqwsTesterPhase.PREPARING,
                program = currentProgram,
                statusText = getString(R.string.nfqws_tester_status_preparing),
                overlayPermissionGranted = true,
            )
        )
        showOverlayIfNeeded()
        strategies = runCatching { runner.listStrategies(currentProgram) }.getOrElse { err ->
            NfqwsTesterStore.update {
                it.copy(phase = NfqwsTesterPhase.ERROR, errorText = err.message ?: "list failed", statusText = getString(R.string.nfqws_tester_status_error))
            }
            stopSelfSafely()
            return
        }
        if (strategies.isEmpty()) {
            NfqwsTesterStore.update {
                it.copy(
                    phase = NfqwsTesterPhase.ERROR,
                    errorText = getString(R.string.nfqws_tester_no_strategies),
                    statusText = getString(R.string.nfqws_tester_status_error),
                    strategies = emptyList(),
                )
            }
            stopSelfSafely()
            return
        }
        currentIndex = 0
        startCurrentStrategy()
    }

    private fun decide(decision: String) {
        serviceScope.launch {
            val state = NfqwsTesterStore.state.value
            val strategy = state.currentStrategy
            if (strategy.isBlank()) return@launch
            when (decision) {
                "works" -> working += strategy
                "failed" -> failed += strategy
                else -> skipped += strategy
            }
            currentIndex += 1
            if (currentIndex >= strategies.size) {
                finishSession()
            } else {
                startCurrentStrategy()
            }
        }
    }

    private suspend fun startCurrentStrategy() {
        pollJob?.cancel()
        val strategy = strategies.getOrNull(currentIndex).orEmpty()
        if (strategy.isBlank()) {
            finishSession()
            return
        }
        NfqwsTesterStore.update {
            it.copy(
                phase = NfqwsTesterPhase.PREPARING,
                program = currentProgram,
                strategies = strategies,
                currentIndex = currentIndex,
                currentStrategy = strategy,
                pid = 0,
                cpuPercent = 0.0,
                rssMb = 0.0,
                statusText = getString(R.string.nfqws_tester_status_starting),
                errorText = null,
                working = working.toList(),
                failed = failed.toList(),
                skipped = skipped.toList(),
                overlayPermissionGranted = true,
            )
        }
        val configPath = "/data/adb/modules/ZDT-D/strategic/strategicvar/${currentProgram}/${strategy}"
        val result = runCatching { runner.startStrategy(currentProgram, configPath) }.getOrElse { err ->
            NfqwsTesterStore.update {
                it.copy(
                    phase = NfqwsTesterPhase.ERROR,
                    statusText = getString(R.string.nfqws_tester_status_error),
                    errorText = err.message ?: getString(R.string.nfqws_tester_start_failed),
                    working = working.toList(),
                    failed = failed.toList(),
                    skipped = skipped.toList(),
                )
            }
            return
        }
        val pid = result.optInt("pid", 0)
        if (pid <= 0) {
            NfqwsTesterStore.update {
                it.copy(
                    phase = NfqwsTesterPhase.ERROR,
                    statusText = getString(R.string.nfqws_tester_status_error),
                    errorText = getString(R.string.nfqws_tester_start_failed),
                    working = working.toList(),
                    failed = failed.toList(),
                    skipped = skipped.toList(),
                )
            }
            return
        }
        NfqwsTesterStore.update {
            it.copy(
                phase = NfqwsTesterPhase.RUNNING,
                pid = pid,
                statusText = getString(R.string.nfqws_tester_status_running),
                errorText = null,
            )
        }
        refreshOverlay()
        pollJob = serviceScope.launch {
            while (true) {
                val status = runCatching { runner.status() }.getOrDefault(JSONObject())
                val active = status.optBoolean("active", false)
                val state = NfqwsTesterStore.state.value
                val usage = if (state.pid > 0) runCatching { runner.usage(state.pid) }.getOrDefault(JSONObject()) else JSONObject()
                NfqwsTesterStore.update {
                    it.copy(
                        phase = if (active) NfqwsTesterPhase.WAITING_DECISION else NfqwsTesterPhase.RUNNING,
                        cpuPercent = usage.optDouble("cpu_percent", 0.0),
                        rssMb = usage.optDouble("rss_mb", 0.0),
                        statusText = if (active) getString(R.string.nfqws_tester_status_waiting_decision) else getString(R.string.nfqws_tester_status_process_exited),
                    )
                }
                refreshOverlay()
                delay(1000)
            }
        }
    }

    private suspend fun finishSession() {
        pollJob?.cancel()
        runCatching { runner.stopTester() }
        NfqwsTesterStore.update {
            it.copy(
                phase = NfqwsTesterPhase.FINISHED,
                currentIndex = strategies.size,
                currentStrategy = "",
                pid = 0,
                cpuPercent = 0.0,
                rssMb = 0.0,
                statusText = getString(R.string.nfqws_tester_status_finished),
                working = working.toList(),
                failed = failed.toList(),
                skipped = skipped.toList(),
                errorText = null,
            )
        }
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfSafely()
    }

    private suspend fun stopTesterSession(statusText: String) {
        pollJob?.cancel()
        runCatching { runner.stopTester() }
        NfqwsTesterStore.update {
            it.copy(
                phase = NfqwsTesterPhase.IDLE,
                pid = 0,
                cpuPercent = 0.0,
                rssMb = 0.0,
                statusText = statusText,
                currentStrategy = "",
                currentIndex = -1,
            )
        }
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfSafely()
    }

    private fun showOverlayIfNeeded() {
        if (overlayView != null) {
            refreshOverlay()
            return
        }
        val density = resources.displayMetrics.density
        val pad = (14f * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xEE101318.toInt())
        }
        titleView = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 16f }
        statsView = TextView(this).apply { setTextColor(0xFFD7DEE8.toInt()); textSize = 13f }
        stateView = TextView(this).apply { setTextColor(0xFF8BD5FF.toInt()); textSize = 13f }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        worksButton = Button(this).apply { text = getString(R.string.nfqws_tester_decision_yes); setOnClickListener { decide("works") } }
        failedButton = Button(this).apply { text = getString(R.string.nfqws_tester_decision_no); setOnClickListener { decide("failed") } }
        skipButton = Button(this).apply { text = getString(R.string.nfqws_tester_decision_skip); setOnClickListener { decide("skip") } }
        stopButton = Button(this).apply { text = getString(R.string.nfqws_tester_stop); setOnClickListener { serviceScope.launch { stopTesterSession(getString(R.string.nfqws_tester_status_stopped)) } } }
        row1.addView(worksButton)
        row1.addView(failedButton)
        row1.addView(skipButton)
        root.addView(titleView)
        root.addView(statsView)
        root.addView(stateView)
        root.addView(row1)
        root.addView(stopButton)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (56f * density).toInt()
        }
        overlayView = root
        windowManager?.addView(root, params)
        refreshOverlay()
    }

    private fun isZdtdServiceRunning(): Boolean {
        val report = runCatching {
            ApiModels.parseStatusFile(rootConfig.readTextFile("/data/adb/modules/ZDT-D/api/status.json"))
        }.getOrNull()
        val merged = ApiModels.applyStatusFile(null, report)
        return ApiModels.isServiceOn(merged)
    }

    private fun refreshOverlay() {
        val state = NfqwsTesterStore.state.value
        titleView?.text = getString(
            R.string.nfqws_tester_overlay_title_fmt,
            state.program.uppercase(),
            if (state.currentStrategy.isBlank()) getString(R.string.nfqws_tester_none) else state.currentStrategy,
            (state.currentIndex + 1).coerceAtLeast(0),
            state.strategies.size,
        )
        statsView?.text = getString(R.string.nfqws_tester_overlay_stats_fmt, state.cpuPercent, state.rssMb)
        stateView?.text = state.errorText ?: state.statusText
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        titleView = null
        statsView = null
        stateView = null
        worksButton = null
        failedButton = null
        skipButton = null
        stopButton = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.nfqws_tester_notification_channel), NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle(getString(R.string.nfqws_tester_title))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun stopSelfSafely() {
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "nfqws_tester"
        private const val NOTIFICATION_ID = 9024
        private const val EXTRA_PROGRAM = "program"
        const val ACTION_START = "com.android.zdtd.service.action.NFQWS_TESTER_START"
        const val ACTION_STOP = "com.android.zdtd.service.action.NFQWS_TESTER_STOP"
        const val ACTION_DECISION_WORKS = "com.android.zdtd.service.action.NFQWS_TESTER_WORKS"
        const val ACTION_DECISION_FAILED = "com.android.zdtd.service.action.NFQWS_TESTER_FAILED"
        const val ACTION_DECISION_SKIP = "com.android.zdtd.service.action.NFQWS_TESTER_SKIP"

        fun start(context: Context, program: String) {
            val intent = Intent(context, NfqwsTesterOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROGRAM, program)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NfqwsTesterOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun overlaySettingsIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }
}
