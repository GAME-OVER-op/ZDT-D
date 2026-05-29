package com.android.zdtd.service.diagnostics.nfqws

import android.animation.LayoutTransition
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
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

    private var contentContainer: LinearLayout? = null
    private var headerRowView: LinearLayout? = null
    private var headerProgramView: TextView? = null
    private var headerProgramSubView: TextView? = null
    private var headerCollapseButton: TextView? = null
    private var statusPillView: TextView? = null
    private var strategyValueView: TextView? = null
    private var progressCountView: TextView? = null
    private var progressView: HudProgressView? = null
    private var cpuValueView: TextView? = null
    private var ramValueView: TextView? = null
    private var cpuGraphView: HudSparklineView? = null
    private var ramGraphView: HudSparklineView? = null
    private var worksButton: TextView? = null
    private var failedButton: TextView? = null
    private var skipButton: TextView? = null
    private var stopButton: TextView? = null
    private var compactStrip: LinearLayout? = null
    private var compactTitleView: TextView? = null
    private var compactMetaView: TextView? = null
    private var compactStateView: TextView? = null
    private var compactIconView: ImageView? = null

    private var currentProgram: String = "nfqws"
    private var strategies: List<String> = emptyList()
    private var currentIndex: Int = -1
    private val working = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    private val skipped = mutableListOf<String>()
    private var overlayExpanded: Boolean = true
    private val cpuHistory = ArrayDeque<Float>()
    private val ramHistory = ArrayDeque<Float>()

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
        if (isZdtdServiceRunning()) {
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
                it.copy(
                    phase = NfqwsTesterPhase.ERROR,
                    errorText = err.message ?: "list failed",
                    statusText = getString(R.string.nfqws_tester_status_error),
                )
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
        refreshOverlay(animated = true)

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
        val expandedWidth = expandedOverlayWidth()
        val collapsedWidth = dp(184)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = CyberPanelDrawable(density, glow = true)
            elevation = 28f * density
            setPadding(dp(10), dp(10), dp(10), dp(10))
            clipToPadding = false
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(180)
            }
            alpha = 0f
            scaleX = 0.985f
            scaleY = 0.985f
        }

        val topGrip = CyberAccentGripView(this)
        root.addView(topGrip, LinearLayout.LayoutParams(dp(128), dp(14)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(0)
        })

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = CyberHeaderDrawable(density)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnTouchListener(createDragTouchListener())
        }
        headerRowView = headerRow
        root.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val logoTile = CyberLogoView(this).apply {
            setOnTouchListener(createDragTouchListener())
        }
        headerRow.addView(logoTile, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            marginEnd = dp(12)
        })

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerProgramView = TextView(this).apply {
            text = "NFQWS / NFQWS2"
            setTextColor(0xFFFFF3F6.toInt())
            setTextSize(2, 20f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        headerProgramSubView = TextView(this).apply {
            text = getString(R.string.nfqws_tester_overlay_section_strategy).lowercase()
            setTextColor(0xFFFF335F.toInt())
            setTextSize(2, 14f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            includeFontPadding = false
            setPadding(0, dp(9), 0, dp(2))
        }
        strategyValueView = TextView(this).apply {
            setTextColor(0xFFD8D4D7.toInt())
            setTextSize(2, 16f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        titleColumn.addView(headerProgramView)
        titleColumn.addView(headerProgramSubView)
        titleColumn.addView(strategyValueView)
        headerRow.addView(titleColumn)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = CyberSectionDrawable(density, strong = false)
            setPadding(dp(14), dp(8), dp(12), dp(8))
        }
        val statusLabel = TextView(this).apply {
            text = "статус"
            setTextColor(0xFFFF385F.toInt())
            setTextSize(2, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            includeFontPadding = false
        }
        statusPillView = TextView(this).apply {
            setTextColor(0xFFECE3E7.toInt())
            setTextSize(2, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(8), 0, 0)
        }
        statusCard.addView(statusLabel)
        statusCard.addView(statusPillView)
        headerRow.addView(statusCard, LinearLayout.LayoutParams(dp(168), dp(58)).apply {
            marginStart = dp(10)
        })

        headerCollapseButton = TextView(this).apply {
            text = "✥"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD8E0.toInt())
            setTextSize(2, 20f)
            typeface = Typeface.DEFAULT_BOLD
            background = CyberIconButtonDrawable(density)
            setOnClickListener {
                overlayExpanded = !overlayExpanded
                overlayPrefs.edit().putBoolean(KEY_EXPANDED, overlayExpanded).apply()
                updateExpandedState(animated = true)
            }
        }
        headerRow.addView(headerCollapseButton, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginStart = dp(8)
        })

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(180)
            }
        }
        root.addView(contentContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = CyberSectionDrawable(density, strong = true)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val progressMeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val progressLabel = TextView(this).apply {
            text = getString(R.string.nfqws_tester_overlay_section_progress).lowercase()
            setTextColor(0xFFFF335F.toInt())
            setTextSize(2, 13f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        progressCountView = TextView(this).apply {
            setTextColor(0xFFFFE5EC.toInt())
            setTextSize(2, 20f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            includeFontPadding = false
        }
        progressMeta.addView(progressLabel)
        progressMeta.addView(progressCountView)
        progressCard.addView(progressMeta)
        progressView = HudProgressView(this)
        progressCard.addView(progressView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).apply {
            topMargin = dp(5)
        })
        contentContainer?.addView(progressCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })

        val resourcesHeader = sectionHeader(getString(R.string.nfqws_tester_overlay_section_resources))
        contentContainer?.addView(resourcesHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            leftMargin = dp(8)
        })

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cpuMetric = metricCard(getString(R.string.nfqws_tester_overlay_metric_cpu), isCpu = true)
        val ramMetric = metricCard(getString(R.string.nfqws_tester_overlay_metric_ram), isCpu = false)
        statsRow.addView(cpuMetric, LinearLayout.LayoutParams(0, dp(58), 1f))
        statsRow.addView(ramMetric, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(12) })
        contentContainer?.addView(statsRow)

        val actionsHeader = sectionHeader(getString(R.string.nfqws_tester_overlay_section_actions))
        contentContainer?.addView(actionsHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            leftMargin = dp(8)
        })

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        worksButton = actionButton("▶\n${getString(R.string.nfqws_tester_decision_yes)}", true).apply {
            setOnClickListener { decide("works") }
        }
        failedButton = actionButton("×\nНе работает", false).apply {
            setOnClickListener { decide("failed") }
        }
        skipButton = actionButton("»\n${getString(R.string.nfqws_tester_decision_skip)}", false).apply {
            setOnClickListener { decide("skip") }
        }
        actionsRow.addView(worksButton, LinearLayout.LayoutParams(0, dp(74), 1f))
        actionsRow.addView(failedButton, LinearLayout.LayoutParams(0, dp(74), 1f).apply { marginStart = dp(12) })
        actionsRow.addView(skipButton, LinearLayout.LayoutParams(0, dp(74), 1f).apply { marginStart = dp(12) })
        contentContainer?.addView(actionsRow)

        stopButton = actionButton("■   Остановить", true, wide = true).apply {
            setOnClickListener { serviceScope.launch { stopTesterSession(getString(R.string.nfqws_tester_status_stopped)) } }
        }
        contentContainer?.addView(stopButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            topMargin = dp(10)
        })

        compactStrip = buildCompactStrip()
        root.addView(compactStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            if (overlayExpanded) expandedWidth else collapsedWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = overlayPrefs.getInt(KEY_POS_X, Int.MIN_VALUE)
            val savedY = overlayPrefs.getInt(KEY_POS_Y, Int.MIN_VALUE)
            x = if (savedX == Int.MIN_VALUE) (resources.displayMetrics.widthPixels - width) / 2 else savedX
            y = if (savedY == Int.MIN_VALUE) (resources.displayMetrics.heightPixels * 0.23f).toInt() else savedY
        }
        overlayParams = params
        overlayView = root
        windowManager?.addView(root, params)
        root.post {
            clampAndApplyOverlayPosition()
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

    private fun refreshOverlay(animated: Boolean) {
        val state = NfqwsTesterStore.state.value
        val total = max(state.strategies.size, 1)
        val currentHuman = when {
            state.phase == NfqwsTesterPhase.FINISHED -> total
            state.currentIndex >= 0 -> min(state.currentIndex + 1, total)
            else -> 1
        }
        val strategy = state.currentStrategy.ifBlank { getString(R.string.nfqws_tester_none) }
        val status = shortHudStatus(state)

        headerProgramView?.text = "NFQWS / NFQWS2"
        headerProgramSubView?.text = getString(R.string.nfqws_tester_overlay_section_strategy).lowercase()
        strategyValueView?.let { setAnimatedText(it, strategy, animated) }
        statusPillView?.let { setAnimatedText(it, "●  $status", animated) }
        progressCountView?.let { setAnimatedText(it, "$currentHuman / $total", animated) }
        compactTitleView?.let { setAnimatedText(it, strategy, animated) }
        compactMetaView?.let { setAnimatedText(it, "${state.program.uppercase()} • $currentHuman/$total", animated) }
        compactStateView?.let { setAnimatedText(it, shortStateLabel(state), animated) }
        compactIconView?.setImageResource(programBadgeIcon(state.program))
        compactIconView?.setColorFilter(0xFFFFEEF4.toInt())

        rememberMetric(cpuHistory, state.cpuPercent.toFloat().coerceAtLeast(0f))
        rememberMetric(ramHistory, state.rssMb.toFloat().coerceAtLeast(0f))
        cpuValueView?.text = String.format(java.util.Locale.US, "%.1f%%", state.cpuPercent)
        ramValueView?.text = String.format(java.util.Locale.US, "%.1f MB", state.rssMb)
        cpuGraphView?.setValues(cpuHistory.toList(), maxHint = 12f)
        ramGraphView?.setValues(ramHistory.toList(), maxHint = max(8f, ramHistory.maxOrNull() ?: 8f))
        progressView?.setProgress(currentHuman.coerceIn(0, total), total)
        updateStatusPillStyle(state)
        updateActionState(state)
        headerCollapseButton?.text = if (overlayExpanded) "✥" else "✣"
    }

    private fun updateActionState(state: NfqwsTesterSessionState) {
        val decisionsEnabled = state.phase == NfqwsTesterPhase.RUNNING || state.phase == NfqwsTesterPhase.WAITING_DECISION
        listOf(worksButton, failedButton, skipButton).forEach {
            it?.isEnabled = decisionsEnabled
            it?.alpha = if (decisionsEnabled) 1f else 0.55f
        }
        stopButton?.alpha = 1f
    }

    private fun updateExpandedState(animated: Boolean) {
        val params = overlayParams ?: return
        val root = overlayView ?: return
        val content = contentContainer ?: return
        val compact = compactStrip ?: return
        val header = headerRowView
        val targetWidth = if (overlayExpanded) expandedOverlayWidth() else dp(184)
        params.width = targetWidth
        if (overlayExpanded) {
            header?.visibility = View.VISIBLE
            compact.visibility = View.GONE
            content.visibility = View.VISIBLE
            if (animated) {
                header?.alpha = 0f
                header?.translationY = -dp(6).toFloat()
                header?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(170)?.start()
                content.alpha = 0f
                content.translationY = -dp(6).toFloat()
                content.animate().alpha(1f).translationY(0f).setDuration(170).start()
            }
        } else {
            header?.visibility = View.GONE
            content.visibility = View.GONE
            compact.visibility = View.VISIBLE
            if (animated) {
                compact.alpha = 0f
                compact.translationY = -dp(4).toFloat()
                compact.animate().alpha(1f).translationY(0f).setDuration(150).start()
            }
        }
        runCatching { windowManager?.updateViewLayout(root, params) }
        clampAndApplyOverlayPosition()
        headerCollapseButton?.text = if (overlayExpanded) "✥" else "✣"
    }

    private fun shortHudStatus(state: NfqwsTesterSessionState): String {
        return when {
            state.errorText != null || state.phase == NfqwsTesterPhase.ERROR -> "Ошибка"
            state.phase == NfqwsTesterPhase.FINISHED -> "Готово"
            state.phase == NfqwsTesterPhase.PREPARING -> "Запуск"
            state.phase == NfqwsTesterPhase.IDLE -> "Ожидание"
            else -> "Тест идёт"
        }
    }

    private fun shortStateLabel(state: NfqwsTesterSessionState): String {
        return when {
            state.errorText != null || state.phase == NfqwsTesterPhase.ERROR -> getString(R.string.nfqws_tester_overlay_state_error)
            state.phase == NfqwsTesterPhase.WAITING_DECISION -> getString(R.string.nfqws_tester_overlay_state_waiting)
            state.phase == NfqwsTesterPhase.RUNNING -> getString(R.string.nfqws_tester_overlay_state_running)
            state.phase == NfqwsTesterPhase.PREPARING -> getString(R.string.nfqws_tester_overlay_state_preparing)
            state.phase == NfqwsTesterPhase.FINISHED -> getString(R.string.nfqws_tester_overlay_state_finished)
            else -> getString(R.string.nfqws_tester_overlay_state_idle)
        }
    }

    private fun updateStatusPillStyle(state: NfqwsTesterSessionState) {
        val text = when {
            state.errorText != null || state.phase == NfqwsTesterPhase.ERROR -> 0xFFFF9CB4.toInt()
            state.phase == NfqwsTesterPhase.FINISHED -> 0xFFB7FFD2.toInt()
            else -> 0xFFECE3E7.toInt()
        }
        statusPillView?.setTextColor(text)
    }

    private fun buildCompactStrip(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = CyberSectionDrawable(density, strong = true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnTouchListener(createDragTouchListener {
                overlayExpanded = true
                overlayPrefs.edit().putBoolean(KEY_EXPANDED, true).apply()
                updateExpandedState(animated = true)
            })
            val top = LinearLayout(this@NfqwsTesterOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            compactMetaView = TextView(this@NfqwsTesterOverlayService).apply {
                setTextColor(0xFFFF4D73.toInt())
                setTextSize(2, 11f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            compactIconView = ImageView(this@NfqwsTesterOverlayService).apply {
                setImageResource(programBadgeIcon(currentProgram))
                setColorFilter(0xFFFFEEF4.toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = CyberIconButtonDrawable(density)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            top.addView(compactIconView, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
            top.addView(compactMetaView)
            addView(top)
            addView(space(dp(8)))
            compactTitleView = TextView(this@NfqwsTesterOverlayService).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(2, 14f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            addView(compactTitleView)
            addView(space(dp(8)))
            compactStateView = TextView(this@NfqwsTesterOverlayService).apply {
                setTextColor(0xFFFFD3E0.toInt())
                setTextSize(2, 11f)
                background = CyberMiniPillDrawable(density)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                includeFontPadding = false
            }
            addView(compactStateView)
        }
    }

    private fun sectionHeader(text: String): TextView {
        return TextView(this).apply {
            setTextColor(0xFFFF335F.toInt())
            setTextSize(2, 13f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            this.text = text.lowercase()
            setPadding(dp(2), 0, dp(2), dp(7))
            includeFontPadding = false
        }
    }

    private fun simpleValueCard(view: TextView): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = CyberSectionDrawable(resources.displayMetrics.density, strong = false)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(view)
        }
    }

    private fun metricCard(label: String, isCpu: Boolean): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = CyberSectionDrawable(density, strong = false)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val labelValue = TextView(this@NfqwsTesterOverlayService).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(2, 13f)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setLineSpacing(0f, 1.2f)
            }
            val graph = HudSparklineView(this@NfqwsTesterOverlayService).apply {
                if (isCpu) cpuGraphView = this else ramGraphView = this
            }
            val value = TextView(this@NfqwsTesterOverlayService).apply {
                setTextColor(0xFFFF456B.toInt())
                setTextSize(2, 16f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 2
            }
            if (isCpu) cpuValueView = value else ramValueView = value
            addView(labelValue, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(graph, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(10)
            })
            addView(value, LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun actionButton(text: String, primary: Boolean, wide: Boolean = false): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            setAllCaps(false)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(2, if (wide) 20f else 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = if (wide) dp(58) else dp(74)
            gravity = Gravity.CENTER
            includeFontPadding = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = CyberButtonDrawable(density, primary = primary, wide = wide)
        }
    }

    private fun layeredRootDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            colors = intArrayOf(0xF90B0508.toInt(), 0xF7110408.toInt())
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(dp(1), 0xAAFF224F.toInt())
        }
    }

    private fun sectionDrawable(fillColor: Int): GradientDrawable {
        return roundedDrawable(fillColor, 0x55FF2D6B.toInt(), 20f * resources.displayMetrics.density)
    }

    private fun rememberMetric(history: ArrayDeque<Float>, value: Float) {
        history.addLast(value)
        while (history.size > 34) history.removeFirst()
    }

    private fun programBadgeIcon(program: String): Int {
        return if (program == "nfqws2") R.drawable.ic_tool_zapret2 else R.drawable.ic_tool_zapret
    }

    private fun expandedOverlayWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val safeMax = max(dp(280), screenWidth - dp(24))
        val preferred = (screenWidth * 0.84f).toInt().coerceAtLeast(dp(330))
        return min(safeMax, preferred)
    }

    private fun removeOverlay() {
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayView = null
        overlayParams = null
        contentContainer = null
        headerRowView = null
        headerProgramView = null
        headerProgramSubView = null
        headerCollapseButton = null
        statusPillView = null
        strategyValueView = null
        progressCountView = null
        progressView = null
        cpuValueView = null
        ramValueView = null
        cpuGraphView = null
        ramGraphView = null
        worksButton = null
        failedButton = null
        skipButton = null
        stopButton = null
        compactStrip = null
        compactTitleView = null
        compactMetaView = null
        compactStateView = null
        compactIconView = null
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

    private fun createDragTouchListener(onTap: (() -> Unit)? = null): View.OnTouchListener {
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
                        clampAndApplyOverlayPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        clampAndApplyOverlayPosition()
                        overlayPrefs.edit().putInt(KEY_POS_X, params.x).putInt(KEY_POS_Y, params.y).apply()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampAndApplyOverlayPosition() {
        val params = overlayParams ?: return
        val view = overlayView ?: return
        val width = if (view.width > 0) view.width else params.width.coerceAtLeast(dp(184))
        val height = if (view.height > 0) view.height else dp(if (overlayExpanded) 360 else 132)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val sideMargin = dp(10)
        val topMargin = dp(30)
        val maxX = max(sideMargin, screenWidth - width - sideMargin)
        val maxY = max(topMargin, screenHeight - height - sideMargin)
        params.x = params.x.coerceIn(sideMargin, maxX)
        params.y = params.y.coerceIn(topMargin, maxY)
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun isZdtdServiceRunning(): Boolean {
        val report = runCatching {
            ApiModels.parseStatusFile(rootConfig.readTextFile("/data/adb/modules/ZDT-D/api/status.json"))
        }.getOrNull()
        val merged = ApiModels.applyStatusFile(null, report)
        return ApiModels.isServiceOn(merged)
    }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
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

private class HudProgressView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x551E0A10
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF375F.toInt()
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FF375F.toInt()
        style = Paint.Style.FILL
        setShadowLayer(12f, 0f, 0f, 0xCCFF375F.toInt())
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FF6886.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private var progressFraction = 0f
    private val rect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setProgress(value: Int, maxValue: Int) {
        val next = if (maxValue <= 0) 0f else (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
        if (progressFraction == next) return
        progressFraction = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val barH = h * 0.42f
        val top = (h - barH) / 2f
        rect.set(0f, top, width.toFloat(), top + barH)
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, trackPaint)
        val fillW = width * progressFraction
        if (fillW > 0f) {
            rect.set(0f, top, fillW, top + barH)
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, glowPaint)
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, fillPaint)
        }
        canvas.drawLine(0f, top, width.toFloat(), top, linePaint)
    }
}

private class HudSparklineView(context: Context) : View(context) {
    private val values = mutableListOf<Float>()
    private var maxHint = 1f
    private val path = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF375F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFF375F.toInt()
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FF375F
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    fun setValues(next: List<Float>, maxHint: Float) {
        values.clear()
        values.addAll(next.takeLast(34))
        this.maxHint = maxHint.coerceAtLeast(1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (values.isEmpty()) return
        val maxValue = max(maxHint, values.maxOrNull() ?: maxHint).coerceAtLeast(1f)
        val step = if (values.size <= 1) w else w / (values.size - 1)
        path.reset()
        values.forEachIndexed { index, value ->
            val x = index * step
            val y = h - ((value / maxValue).coerceIn(0f, 1f) * h)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (index % 4 == 0) canvas.drawCircle(x, y, 1.5f, dotPaint)
        }
        canvas.drawPath(path, linePaint)
    }
}

private class CyberPanelDrawable(
    private val density: Float,
    private val glow: Boolean,
) : Drawable() {
    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2100408.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFF204D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }
    private val innerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FF6D86.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FF1F4A
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        setShadowLayer(12f * density, 0f, 0f, 0xAAFF204D.toInt())
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cut = 24f * density
        val inset = 2f * density
        buildPanelPath(path, b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, cut)
        if (glow) canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        buildPanelPath(path, b.left + 8f * density, b.top + 8f * density, b.right - 8f * density, b.bottom - 8f * density, cut * 0.72f)
        canvas.drawPath(path, innerStrokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        innerStrokePaint.alpha = alpha
        glowPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        innerStrokePaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private class CyberHeaderDrawable(private val density: Float) : Drawable() {
    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB412060A.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFF224F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FF244F
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        setShadowLayer(8f * density, 0f, 0f, 0x99FF244F.toInt())
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFF365B.toInt()
        style = Paint.Style.FILL
    }

    init {
        // shadow is applied by the owner view layer when possible; drawable stays lightweight.
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cut = 18f * density
        buildPanelPath(path, b.left + 1f, b.top + 1f, b.right - 1f, b.bottom - 1f, cut)
        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        val w = (b.right - b.left).toFloat()
        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()
        canvas.drawRect(b.left + w * 0.42f, top + 1f * density, b.left + w * 0.58f, top + 4f * density, accentPaint)
        canvas.drawRect(b.left + 18f * density, bottom - 4f * density, b.left + 92f * density, bottom - 1f * density, accentPaint)
        canvas.drawRect(b.right - 92f * density, bottom - 4f * density, b.right - 18f * density, bottom - 1f * density, accentPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        glowPaint.alpha = alpha
        accentPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
        accentPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private class CyberSectionDrawable(
    private val density: Float,
    private val strong: Boolean,
) : Drawable() {
    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (strong) 0xCC16070C.toInt() else 0x9913090D.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (strong) 0xCCFF224F.toInt() else 0x88FF244D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FF345C.toInt()
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cut = 13f * density
        buildPanelPath(path, b.left + 1f, b.top + 1f, b.right - 1f, b.bottom - 1f, cut)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        canvas.drawRect(b.left + 8f * density, b.top + 1f, b.left + 46f * density, b.top + 3f * density, capPaint)
        canvas.drawRect(b.right - 46f * density, b.bottom - 3f * density, b.right - 8f * density, b.bottom - 1f, capPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        capPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        capPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private class CyberButtonDrawable(
    private val density: Float,
    private val primary: Boolean,
    private val wide: Boolean,
) : Drawable() {
    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (primary) 0xCCB81232.toInt() else 0x8813090D.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (primary) 0xFFFF5F78.toInt() else 0xAAFF667C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FF365C
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        setShadowLayer(10f * density, 0f, 0f, 0xAAFF365C.toInt())
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFF6C7E.toInt()
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cut = if (wide) 20f * density else 12f * density
        buildPanelPath(path, b.left + 2f, b.top + 2f, b.right - 2f, b.bottom - 2f, cut)
        if (primary) canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        if (wide) {
            canvas.drawRect(b.left + 16f * density, b.bottom - 5f * density, b.left + 82f * density, b.bottom - 2f * density, accentPaint)
            canvas.drawRect(b.right - 82f * density, b.top + 2f * density, b.right - 16f * density, b.top + 5f * density, accentPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        glowPaint.alpha = alpha
        accentPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
        accentPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private class CyberIconButtonDrawable(private val density: Float) : Drawable() {
    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA1A070D.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFF4566.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left + 2f, b.top + 2f, b.right - 2f, b.bottom - 2f)
        canvas.drawOval(rect, fillPaint)
        canvas.drawOval(rect, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private class CyberMiniPillDrawable(private val density: Float) : Drawable() {
    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA240811.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FF4566.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left + 1f, b.top + 1f, b.right - 1f, b.bottom - 1f)
        val radius = 14f * density
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private class CyberAccentGripView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF2E57.toInt()
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        path.moveTo(w * 0.12f, h * 0.42f)
        path.lineTo(w * 0.38f, h * 0.42f)
        path.lineTo(w * 0.44f, h * 0.68f)
        path.lineTo(w * 0.56f, h * 0.68f)
        path.lineTo(w * 0.62f, h * 0.42f)
        path.lineTo(w * 0.88f, h * 0.42f)
        path.lineTo(w * 0.82f, h * 0.62f)
        path.lineTo(w * 0.18f, h * 0.62f)
        path.close()
        canvas.drawPath(path, paint)
    }
}

private class CyberLogoView(context: Context) : View(context) {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xBB3B0812.toInt()
        style = Paint.Style.FILL
        setShadowLayer(12f, 0f, 0f, 0xAAFF204D.toInt())
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFF375F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val rect = RectF()
    private val logoDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_nfqws_overlay_logo)?.mutate()?.also {
        DrawableCompat.setTint(it, 0xFFFFE8EE.toInt())
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        rect.set(cx - size * 0.42f, cy - size * 0.42f, cx + size * 0.42f, cy + size * 0.42f)
        canvas.drawOval(rect, circlePaint)
        canvas.drawOval(rect, strokePaint)

        val drawable = logoDrawable ?: return
        val inset = (size * 0.20f).toInt()
        val left = (cx - size * 0.42f).toInt() + inset
        val top = (cy - size * 0.42f).toInt() + inset
        val right = (cx + size * 0.42f).toInt() - inset
        val bottom = (cy + size * 0.42f).toInt() - inset
        drawable.setBounds(left, top, right, bottom)
        drawable.draw(canvas)
    }
}

private fun buildPanelPath(path: Path, left: Float, top: Float, right: Float, bottom: Float, cut: Float) {
    path.reset()
    path.moveTo(left + cut, top)
    path.lineTo(right - cut, top)
    path.lineTo(right, top + cut)
    path.lineTo(right, bottom - cut)
    path.lineTo(right - cut, bottom)
    path.lineTo(left + cut, bottom)
    path.lineTo(left, bottom - cut)
    path.lineTo(left, top + cut)
    path.close()
}

