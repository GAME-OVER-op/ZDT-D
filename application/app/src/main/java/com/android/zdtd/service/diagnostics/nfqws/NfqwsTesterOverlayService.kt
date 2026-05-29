package com.android.zdtd.service.diagnostics.nfqws

import android.animation.LayoutTransition
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
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
import kotlin.math.max
import kotlin.math.min

class NfqwsTesterOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runner: NfqwsTesterRunner
    private lateinit var rootConfig: RootConfigManager
    private var pollJob: Job? = null
    private var windowManager: WindowManager? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bodyContainer: LinearLayout? = null
    private var metaView: TextView? = null
    private var strategyView: TextView? = null
    private var progressView: ProgressBar? = null
    private var cpuChipView: TextView? = null
    private var ramChipView: TextView? = null
    private var stateChipView: TextView? = null
    private var worksButton: Button? = null
    private var failedButton: Button? = null
    private var skipButton: Button? = null
    private var stopButton: Button? = null
    private var toggleButton: TextView? = null

    private var currentProgram: String = "nfqws"
    private var strategies: List<String> = emptyList()
    private var currentIndex: Int = -1
    private val working = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    private val skipped = mutableListOf<String>()
    private var overlayExpanded: Boolean = true

    override fun onCreate() {
        super.onCreate()
        runner = NfqwsTesterRunner(applicationContext)
        rootConfig = RootConfigManager(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayExpanded = overlayPrefs.getBoolean(KEY_EXPANDED, true)
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
            refreshOverlay(animated = true)
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
            refreshOverlay(animated = true)
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
        refreshOverlay(animated = true)
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
                refreshOverlay(animated = true)
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
            refreshOverlay(animated = false)
            return
        }
        val density = resources.displayMetrics.density
        val overlayWidth = min((resources.displayMetrics.widthPixels * 0.88f).toInt(), dp(360))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(180)
            }
            background = roundedDrawable(0xEE0F172A.toInt(), 0x335E748F.toInt(), 24f * density)
            elevation = 20f * density
            setPadding(dp(16), dp(14), dp(16), dp(14))
            clipToPadding = false
            minimumWidth = overlayWidth
            alpha = 0f
            scaleX = 0.96f
            scaleY = 0.96f
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dragArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnTouchListener(createDragTouchListener())
        }
        val gripView = TextView(this).apply {
            text = "⋮⋮"
            setTextColor(0x88E2E8F0.toInt())
            textSize = 15f
            setPadding(0, 0, dp(10), 0)
            typeface = Typeface.DEFAULT_BOLD
        }
        val headerTextColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        metaView = TextView(this).apply {
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(2, 12f)
            typeface = Typeface.DEFAULT_BOLD
            alpha = 0.94f
        }
        strategyView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(2, 18f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        headerTextColumn.addView(metaView)
        headerTextColumn.addView(strategyView)
        dragArea.addView(gripView)
        dragArea.addView(headerTextColumn)

        toggleButton = TextView(this).apply {
            text = if (overlayExpanded) "−" else "+"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            minWidth = dp(36)
            minHeight = dp(36)
            background = roundedDrawable(0x223B82F6.toInt(), 0x335E748F.toInt(), 18f * density)
            setOnClickListener {
                overlayExpanded = !overlayExpanded
                overlayPrefs.edit().putBoolean(KEY_EXPANDED, overlayExpanded).apply()
                updateExpandedState(animated = true)
            }
        }
        headerRow.addView(dragArea)
        headerRow.addView(toggleButton)

        stateChipView = TextView(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextSize(2, 13f)
            setTextColor(0xFFE5F3FF.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            alpha = 0.92f
        }

        bodyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(180)
            }
        }

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cpuChipView = chipView(0x1F10B981, "CPU")
        ramChipView = chipView(0x1F8B5CF6, "RAM")
        statsRow.addView(cpuChipView)
        statsRow.addView(Space(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        statsRow.addView(ramChipView)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        worksButton = actionButton(getString(R.string.nfqws_tester_decision_yes), 0xFF10B981.toInt()).apply {
            setOnClickListener { decide("works") }
        }
        failedButton = actionButton(getString(R.string.nfqws_tester_decision_no), 0xFFEF4444.toInt()).apply {
            setOnClickListener { decide("failed") }
        }
        skipButton = actionButton(getString(R.string.nfqws_tester_decision_skip), 0xFF64748B.toInt()).apply {
            setOnClickListener { decide("skip") }
        }
        actionsRow.addView(worksButton, weightedActionParams())
        actionsRow.addView(failedButton, weightedActionParams(dp(8)))
        actionsRow.addView(skipButton, weightedActionParams(dp(8)))

        stopButton = actionButton(getString(R.string.nfqws_tester_stop), 0xFF334155.toInt()).apply {
            setOnClickListener {
                serviceScope.launch {
                    stopTesterSession(getString(R.string.nfqws_tester_status_stopped))
                }
            }
        }
        val stopParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }

        bodyContainer?.addView(statsRow)
        bodyContainer?.addView(actionsRow)
        bodyContainer?.addView(stopButton, stopParams)

        root.addView(headerRow)
        root.addView(spaceView(dp(10)))
        root.addView(stateChipView)
        root.addView(spaceView(dp(8)))
        root.addView(progressView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        root.addView(bodyContainer)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            overlayWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayPrefs.getInt(KEY_POS_X, dp(8))
            y = overlayPrefs.getInt(KEY_POS_Y, dp(56))
        }
        overlayParams = params
        overlayView = root
        windowManager?.addView(root, params)
        root.post {
            clampAndApplyOverlayPosition(snapToEdge = false)
            updateExpandedState(animated = false)
            refreshOverlay(animated = false)
            root.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setDuration(180)
                .start()
        }
    }

    private fun isZdtdServiceRunning(): Boolean {
        val report = runCatching {
            ApiModels.parseStatusFile(rootConfig.readTextFile("/data/adb/modules/ZDT-D/api/status.json"))
        }.getOrNull()
        val merged = ApiModels.applyStatusFile(null, report)
        return ApiModels.isServiceOn(merged)
    }

    private fun refreshOverlay(animated: Boolean) {
        val state = NfqwsTesterStore.state.value
        val safeIndex = if (state.currentIndex < 0) 0 else min(state.currentIndex + 1, max(state.strategies.size, 1))
        val meta = "${state.program.uppercase()}  •  ${safeIndex}/${max(state.strategies.size, 1)}"
        val strategy = if (state.currentStrategy.isBlank()) getString(R.string.nfqws_tester_none) else state.currentStrategy
        val status = state.errorText ?: state.statusText

        setAnimatedText(metaView, meta, animated)
        setAnimatedText(strategyView, strategy, animated)
        setAnimatedText(stateChipView, status, animated)

        cpuChipView?.text = getString(R.string.nfqws_tester_chip_cpu_fmt, state.cpuPercent)
        ramChipView?.text = getString(R.string.nfqws_tester_chip_ram_fmt, state.rssMb)

        progressView?.max = max(state.strategies.size, 1)
        progressView?.progress = when (state.phase) {
            NfqwsTesterPhase.FINISHED -> max(state.strategies.size, 1)
            else -> safeIndex.coerceAtMost(max(state.strategies.size, 1))
        }

        toggleButton?.text = if (overlayExpanded) "−" else "+"
        updateStateChipStyle(state)
        updateActionState(state)
    }

    private fun updateActionState(state: NfqwsTesterSessionState) {
        val decisionsEnabled = state.phase == NfqwsTesterPhase.RUNNING || state.phase == NfqwsTesterPhase.WAITING_DECISION
        worksButton?.isEnabled = decisionsEnabled
        failedButton?.isEnabled = decisionsEnabled
        skipButton?.isEnabled = decisionsEnabled
        val alpha = if (decisionsEnabled) 1f else 0.55f
        worksButton?.alpha = alpha
        failedButton?.alpha = alpha
        skipButton?.alpha = alpha
        stopButton?.alpha = 1f
    }

    private fun updateExpandedState(animated: Boolean) {
        val body = bodyContainer ?: return
        val root = overlayView ?: return
        if (overlayExpanded) {
            body.visibility = View.VISIBLE
            if (animated) {
                body.alpha = 0f
                body.translationY = -dp(6).toFloat()
                body.animate().alpha(1f).translationY(0f).setDuration(170).start()
            }
        } else if (animated) {
            body.animate()
                .alpha(0f)
                .translationY(-dp(6).toFloat())
                .setDuration(140)
                .withEndAction {
                    body.visibility = View.GONE
                    body.alpha = 1f
                    body.translationY = 0f
                    clampAndApplyOverlayPosition(snapToEdge = false)
                }
                .start()
        } else {
            body.visibility = View.GONE
        }
        if (!animated) {
            body.alpha = 1f
            body.translationY = 0f
        }
        root.requestLayout()
        toggleButton?.text = if (overlayExpanded) "−" else "+"
    }

    private fun updateStateChipStyle(state: NfqwsTesterSessionState) {
        val (fill, stroke, text) = when {
            state.errorText != null || state.phase == NfqwsTesterPhase.ERROR -> Triple(0x33EF4444.toInt(), 0x55EF4444.toInt(), 0xFFFFE4E6.toInt())
            state.phase == NfqwsTesterPhase.WAITING_DECISION -> Triple(0x333B82F6.toInt(), 0x553B82F6.toInt(), 0xFFE0F2FE.toInt())
            state.phase == NfqwsTesterPhase.RUNNING -> Triple(0x3310B981.toInt(), 0x5510B981.toInt(), 0xFFE7FFF6.toInt())
            state.phase == NfqwsTesterPhase.PREPARING -> Triple(0x33F59E0B.toInt(), 0x55F59E0B.toInt(), 0xFFFFF7E8.toInt())
            state.phase == NfqwsTesterPhase.FINISHED -> Triple(0x3322C55E.toInt(), 0x5522C55E.toInt(), 0xFFF0FFF4.toInt())
            else -> Triple(0x335E748F.toInt(), 0x335E748F.toInt(), 0xFFE2E8F0.toInt())
        }
        stateChipView?.setTextColor(text)
        stateChipView?.background = roundedDrawable(fill, stroke, 16f * resources.displayMetrics.density)
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        overlayParams = null
        bodyContainer = null
        metaView = null
        strategyView = null
        progressView = null
        cpuChipView = null
        ramChipView = null
        stateChipView = null
        worksButton = null
        failedButton = null
        skipButton = null
        stopButton = null
        toggleButton = null
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

    private fun chipView(fillColor: Int, label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(0xFFE5EDF7.toInt())
            setTextSize(2, 12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedDrawable(fillColor, 0x335E748F.toInt(), 14f * resources.displayMetrics.density)
        }
    }

    private fun actionButton(text: String, fillColor: Int): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(2, 15f)
            minHeight = dp(44)
            background = roundedDrawable(fillColor, 0x22000000, 18f * resources.displayMetrics.density)
        }
    }

    private fun weightedActionParams(startMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (startMargin > 0) marginStart = startMargin
        }
    }

    private fun spaceView(height: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun roundedDrawable(fillColor: Int, strokeColor: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun setAnimatedText(view: TextView?, next: String, animated: Boolean) {
        view ?: return
        if (view.text?.toString() == next) return
        if (!animated || !view.isAttachedToWindow) {
            view.text = next
            return
        }
        view.animate().cancel()
        view.animate().alpha(0.2f).setDuration(90).withEndAction {
            view.text = next
            view.animate().alpha(1f).setDuration(140).start()
        }.start()
    }

    private fun createDragTouchListener(): View.OnTouchListener {
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        return View.OnTouchListener { _, event ->
            val params = overlayParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4))) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        clampAndApplyOverlayPosition(snapToEdge = false)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        clampAndApplyOverlayPosition(snapToEdge = true)
                        overlayPrefs.edit().putInt(KEY_POS_X, params.x).putInt(KEY_POS_Y, params.y).apply()
                    }
                    dragging
                }

                else -> false
            }
        }
    }

    private fun clampAndApplyOverlayPosition(snapToEdge: Boolean) {
        val params = overlayParams ?: return
        val view = overlayView ?: return
        val width = if (view.width > 0) view.width else params.width.coerceAtLeast(dp(280))
        val height = if (view.height > 0) view.height else dp(if (overlayExpanded) 240 else 112)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val sideMargin = dp(8)
        val topMargin = dp(24)
        val maxX = max(sideMargin, screenWidth - width - sideMargin)
        val maxY = max(topMargin, screenHeight - height - sideMargin)
        params.x = params.x.coerceIn(sideMargin, maxX)
        params.y = params.y.coerceIn(topMargin, maxY)
        if (snapToEdge) {
            val center = params.x + width / 2
            params.x = if (center >= screenWidth / 2) maxX else sideMargin
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val overlayPrefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val CHANNEL_ID = "nfqws_tester"
        private const val NOTIFICATION_ID = 9024
        private const val EXTRA_PROGRAM = "program"
        private const val PREFS_NAME = "nfqws_tester_overlay"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"
        private const val KEY_EXPANDED = "expanded"

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
