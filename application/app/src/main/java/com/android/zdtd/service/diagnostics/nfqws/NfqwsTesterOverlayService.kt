package com.android.zdtd.service.diagnostics.nfqws

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
    private var compactOnlyViews = mutableListOf<View>()
    private var fullOnlyViews = mutableListOf<View>()

    private var handleView: View? = null
    private var titleView: TextView? = null
    private var statusValueView: TextView? = null
    private var strategyValueView: TextView? = null
    private var progressCountView: TextView? = null
    private var progressView: ProgressBar? = null
    private var cpuValueView: TextView? = null
    private var ramValueView: TextView? = null
    private var worksButton: Button? = null
    private var failedButton: Button? = null
    private var skipButton: Button? = null
    private var stopButton: Button? = null
    private var toggleButton: TextView? = null

    private var expandedWidthPx: Int = 0
    private var compactWidthPx: Int = 0

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
        val prefs = overlayPrefs
        val layoutVersion = prefs.getInt(KEY_LAYOUT_VERSION, 0)
        if (layoutVersion < OVERLAY_LAYOUT_VERSION) {
            prefs.edit()
                .putBoolean(KEY_EXPANDED, true)
                .putInt(KEY_LAYOUT_VERSION, OVERLAY_LAYOUT_VERSION)
                .remove(KEY_POS_X)
                .remove(KEY_POS_Y)
                .apply()
        }
        overlayExpanded = prefs.getBoolean(KEY_EXPANDED, true)
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
        expandedWidthPx = min((resources.displayMetrics.widthPixels * 0.74f).toInt(), dp(328))
        compactWidthPx = min((resources.displayMetrics.widthPixels * 0.50f).toInt(), dp(202))
        compactOnlyViews = mutableListOf()
        fullOnlyViews = mutableListOf()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(180)
            }
            background = roundedDrawable(0xF012070B.toInt(), 0x88E33A60.toInt(), 22f * density)
            elevation = 26f * density
            setPadding(dp(12), dp(10), dp(12), dp(12))
            clipToPadding = false
            minimumWidth = compactWidthPx
            alpha = 0f
            scaleX = 0.965f
            scaleY = 0.965f
        }

        handleView = View(this).apply {
            background = roundedDrawable(0xFFBF3253.toInt(), 0x00FFFFFF, 100f * density)
            alpha = 0.88f
        }
        root.addView(handleView, LinearLayout.LayoutParams(dp(46), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })

        val headerCard = sectionCard(paddingHorizontal = dp(12), paddingVertical = dp(12), radiusDp = 18f)
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
        val logoBadge = TextView(this).apply {
            text = "N"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFEDF1.toInt())
            setTextSize(2, 22f)
            background = roundedDrawable(0xFF2B0911.toInt(), 0x88E33A60.toInt(), 20f * density)
        }
        dragArea.addView(logoBadge, LinearLayout.LayoutParams(dp(54), dp(54)))
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        titleView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(2, 20f)
            typeface = Typeface.DEFAULT_BOLD
            text = getString(R.string.nfqws_tester_overlay_family)
        }
        val subtitleView = TextView(this).apply {
            setTextColor(0xCCFF6C8B.toInt())
            setTextSize(2, 11f)
            text = if (currentProgram == "nfqws2") "NFQWS2" else "NFQWS"
            setAllCaps(true)
        }
        titleColumn.addView(titleView)
        titleColumn.addView(subtitleView)
        dragArea.addView(titleColumn)

        toggleButton = TextView(this).apply {
            text = if (overlayExpanded) "⌃" else "⌄"
            setTextColor(0xFFFFEDF1.toInt())
            setTextSize(2, 18f)
            gravity = Gravity.CENTER
            background = roundedDrawable(0x44250B11.toInt(), 0x88E33A60.toInt(), 18f * density)
            setOnClickListener {
                overlayExpanded = !overlayExpanded
                overlayPrefs.edit().putBoolean(KEY_EXPANDED, overlayExpanded).apply()
                updateExpandedState(animated = true)
            }
        }
        headerRow.addView(dragArea)
        headerRow.addView(toggleButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginStart = dp(8)
        })

        statusValueView = TextView(this).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setTextColor(0xFFFFEEF2.toInt())
            setTextSize(2, 13f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerCard.addView(headerRow)
        headerCard.addView(spaceView(dp(10)))
        headerCard.addView(statusValueView)
        root.addView(headerCard)

        val strategySection = sectionCard(radiusDp = 18f)
        strategySection.addView(sectionLabel(getString(R.string.nfqws_tester_overlay_strategy)))
        strategyValueView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(2, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        strategySection.addView(spaceView(dp(6)))
        strategySection.addView(strategyValueView)
        root.addView(strategySection, topMarginParams(dp(10)))

        val progressSection = sectionCard(radiusDp = 18f)
        val progressHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val progressLabel = sectionLabel(getString(R.string.nfqws_tester_overlay_progress)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        progressCountView = TextView(this).apply {
            setTextColor(0xFFFF6D8A.toInt())
            setTextSize(2, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        progressHeaderRow.addView(progressLabel)
        progressHeaderRow.addView(progressCountView)
        progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(0xFFE6325F.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0xFF311218.toInt())
            indeterminateTintList = ColorStateList.valueOf(0xFFE6325F.toInt())
            progressDrawable?.mutate()
        }
        progressSection.addView(progressHeaderRow)
        progressSection.addView(spaceView(dp(10)))
        progressSection.addView(progressView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        root.addView(progressSection, topMarginParams(dp(10)))
        compactOnlyViews += progressSection

        val metricsSection = sectionCard(radiusDp = 18f)
        metricsSection.addView(sectionLabel(getString(R.string.nfqws_tester_overlay_resources)))
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        val cpuCard = metricCard(getString(R.string.nfqws_tester_overlay_cpu)).also { card ->
            cpuValueView = card.second
            metricsRow.addView(card.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        metricsRow.addView(Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 1)
        })
        val ramCard = metricCard(getString(R.string.nfqws_tester_overlay_ram)).also { card ->
            ramValueView = card.second
            metricsRow.addView(card.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        metricsSection.addView(metricsRow)
        root.addView(metricsSection, topMarginParams(dp(10)))
        fullOnlyViews += metricsSection

        val actionsSection = sectionCard(radiusDp = 18f)
        actionsSection.addView(sectionLabel(getString(R.string.nfqws_tester_overlay_actions)))
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        worksButton = actionButton(getString(R.string.nfqws_tester_decision_yes), 0xFF57111F.toInt(), 0xFFFFE7EC.toInt()).apply {
            setOnClickListener { decide("works") }
        }
        failedButton = actionButton(getString(R.string.nfqws_tester_decision_no), 0xFF220C11.toInt(), 0xFFFFE7EC.toInt()).apply {
            setOnClickListener { decide("failed") }
        }
        skipButton = actionButton(getString(R.string.nfqws_tester_decision_skip), 0xFF220C11.toInt(), 0xFFFFE7EC.toInt()).apply {
            setOnClickListener { decide("skip") }
        }
        actionsRow.addView(worksButton, weightedActionParams())
        actionsRow.addView(failedButton, weightedActionParams(dp(8)))
        actionsRow.addView(skipButton, weightedActionParams(dp(8)))
        actionsSection.addView(actionsRow)
        root.addView(actionsSection, topMarginParams(dp(10)))
        fullOnlyViews += actionsSection

        stopButton = actionButton(getString(R.string.nfqws_tester_stop), 0xFF8E1631.toInt(), 0xFFFFFFFF.toInt()).apply {
            setOnClickListener {
                serviceScope.launch {
                    stopTesterSession(getString(R.string.nfqws_tester_status_stopped))
                }
            }
        }
        root.addView(stopButton, topMarginParams(dp(10)))
        fullOnlyViews += stopButton!!

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val defaultX = resources.displayMetrics.widthPixels - (if (overlayExpanded) expandedWidthPx else compactWidthPx) - dp(10)
        val params = WindowManager.LayoutParams(
            if (overlayExpanded) expandedWidthPx else compactWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayPrefs.getInt(KEY_POS_X, defaultX)
            y = overlayPrefs.getInt(KEY_POS_Y, dp(84))
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

    private fun isZdtdServiceRunning(): Boolean {
        val report = runCatching {
            ApiModels.parseStatusFile(rootConfig.readTextFile("/data/adb/modules/ZDT-D/api/status.json"))
        }.getOrNull()
        val merged = ApiModels.applyStatusFile(null, report)
        return ApiModels.isServiceOn(merged)
    }

    private fun refreshOverlay(animated: Boolean) {
        val state = NfqwsTesterStore.state.value
        val total = max(state.strategies.size, 1)
        val safeIndex = when (state.phase) {
            NfqwsTesterPhase.FINISHED -> total
            else -> if (state.currentIndex < 0) 1 else min(state.currentIndex + 1, total)
        }
        val strategy = if (state.currentStrategy.isBlank()) getString(R.string.nfqws_tester_none) else state.currentStrategy
        titleView?.text = getString(R.string.nfqws_tester_overlay_family)
        setAnimatedText(strategyValueView, strategy, animated)
        setAnimatedText(progressCountView, "$safeIndex / $total", animated)
        setAnimatedText(statusValueView, shortStatus(state), animated)
        setAnimatedText(cpuValueView, getString(R.string.nfqws_tester_overlay_cpu_value, state.cpuPercent), animated)
        setAnimatedText(ramValueView, getString(R.string.nfqws_tester_overlay_ram_value, state.rssMb), animated)

        progressView?.max = total * 100
        progressView?.progress = when (state.phase) {
            NfqwsTesterPhase.FINISHED -> total * 100
            else -> ((safeIndex.toFloat() / total.toFloat()) * (total * 100)).toInt().coerceAtLeast(5)
        }

        toggleButton?.text = if (overlayExpanded) "⌃" else "⌄"
        updateStateChipStyle(state)
        updateActionState(state)
    }

    private fun shortStatus(state: NfqwsTesterSessionState): String {
        return when {
            state.errorText != null || state.phase == NfqwsTesterPhase.ERROR -> getString(R.string.nfqws_tester_overlay_state_error)
            state.phase == NfqwsTesterPhase.WAITING_DECISION -> getString(R.string.nfqws_tester_overlay_state_check)
            state.phase == NfqwsTesterPhase.RUNNING -> getString(R.string.nfqws_tester_overlay_state_running)
            state.phase == NfqwsTesterPhase.PREPARING -> getString(R.string.nfqws_tester_overlay_state_preparing)
            state.phase == NfqwsTesterPhase.FINISHED -> getString(R.string.nfqws_tester_overlay_state_finished)
            else -> getString(R.string.nfqws_tester_overlay_state_idle)
        }
    }

    private fun updateActionState(state: NfqwsTesterSessionState) {
        val decisionsEnabled = state.phase == NfqwsTesterPhase.RUNNING || state.phase == NfqwsTesterPhase.WAITING_DECISION
        worksButton?.isEnabled = decisionsEnabled
        failedButton?.isEnabled = decisionsEnabled
        skipButton?.isEnabled = decisionsEnabled
        val activeAlpha = if (decisionsEnabled) 1f else 0.52f
        worksButton?.alpha = activeAlpha
        failedButton?.alpha = activeAlpha
        skipButton?.alpha = activeAlpha
        stopButton?.alpha = 1f
    }

    private fun updateExpandedState(animated: Boolean) {
        val targetWidth = if (overlayExpanded) expandedWidthPx else compactWidthPx
        strategyValueView?.maxLines = if (overlayExpanded) 2 else 1
        fullOnlyViews.forEach { view ->
            if (overlayExpanded) {
                view.visibility = View.VISIBLE
                if (animated) {
                    view.alpha = 0f
                    view.translationY = -dp(6).toFloat()
                    view.animate().alpha(1f).translationY(0f).setDuration(180).start()
                }
            } else if (animated) {
                view.animate()
                    .alpha(0f)
                    .translationY(-dp(6).toFloat())
                    .setDuration(120)
                    .withEndAction {
                        view.visibility = View.GONE
                        view.alpha = 1f
                        view.translationY = 0f
                        clampAndApplyOverlayPosition()
                    }
                    .start()
            } else {
                view.visibility = View.GONE
                view.alpha = 1f
                view.translationY = 0f
            }
        }
        animateOverlayWidth(targetWidth, animated)
        if (!animated) clampAndApplyOverlayPosition()
    }

    private fun animateOverlayWidth(targetWidth: Int, animated: Boolean) {
        val params = overlayParams ?: return
        val view = overlayView ?: return
        val startWidth = params.width.takeIf { it > 0 } ?: targetWidth
        if (!animated || startWidth == targetWidth || !view.isAttachedToWindow) {
            params.width = targetWidth
            runCatching { windowManager?.updateViewLayout(view, params) }
            return
        }
        ValueAnimator.ofInt(startWidth, targetWidth).apply {
            duration = 190L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                params.width = animator.animatedValue as Int
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
            start()
        }
    }

    private fun updateStateChipStyle(state: NfqwsTesterSessionState) {
        val (fill, stroke, text) = when {
            state.errorText != null || state.phase == NfqwsTesterPhase.ERROR -> Triple(0x66391A21.toInt(), 0x88FF7A92.toInt(), 0xFFFFE7EC.toInt())
            state.phase == NfqwsTesterPhase.WAITING_DECISION -> Triple(0x664A101E.toInt(), 0x88FF5B7D.toInt(), 0xFFFFE7EC.toInt())
            state.phase == NfqwsTesterPhase.RUNNING -> Triple(0x66411119.toInt(), 0x88F1456A.toInt(), 0xFFFFEEF2.toInt())
            state.phase == NfqwsTesterPhase.PREPARING -> Triple(0x66331218.toInt(), 0x88D13958.toInt(), 0xFFFFEEF2.toInt())
            state.phase == NfqwsTesterPhase.FINISHED -> Triple(0x66301218.toInt(), 0x88F67B93.toInt(), 0xFFFFEEF2.toInt())
            else -> Triple(0x66201014.toInt(), 0x88421A24.toInt(), 0xFFFFE7EC.toInt())
        }
        statusValueView?.setTextColor(text)
        statusValueView?.background = roundedDrawable(fill, stroke, 16f * resources.displayMetrics.density)
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        overlayParams = null
        compactOnlyViews = mutableListOf()
        fullOnlyViews = mutableListOf()
        handleView = null
        titleView = null
        statusValueView = null
        strategyValueView = null
        progressCountView = null
        progressView = null
        cpuValueView = null
        ramValueView = null
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

    private fun sectionCard(
        fillColor: Int = 0xCC18060A.toInt(),
        strokeColor: Int = 0x66E33A60.toInt(),
        paddingHorizontal: Int = dp(12),
        paddingVertical: Int = dp(11),
        radiusDp: Float = 18f,
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(fillColor, strokeColor, radiusDp * resources.displayMetrics.density)
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(0xCCFF5D7E.toInt())
            setTextSize(2, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setAllCaps(true)
        }
    }

    private fun metricCard(label: String): Pair<LinearLayout, TextView> {
        val valueView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(2, 17f)
            typeface = Typeface.DEFAULT_BOLD
            text = label
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val card = sectionCard(
            fillColor = 0xDD14070B.toInt(),
            strokeColor = 0x55D23A58.toInt(),
            paddingHorizontal = dp(10),
            paddingVertical = dp(10),
            radiusDp = 15f,
        ).apply {
            addView(sectionLabel(label))
            addView(spaceView(dp(6)))
            addView(valueView)
        }
        return card to valueView
    }

    private fun actionButton(text: String, fillColor: Int, textColor: Int): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(textColor)
            setTextSize(2, 16f)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(64)
            gravity = Gravity.CENTER
            background = roundedDrawable(fillColor, 0x77E33A60.toInt(), 16f * resources.displayMetrics.density)
        }
    }

    private fun weightedActionParams(startMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (startMargin > 0) marginStart = startMargin
        }
    }

    private fun topMarginParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
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
            if (strokeColor != 0) {
                setStroke(dp(1), strokeColor)
            }
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
        view.animate().alpha(0.18f).setDuration(90).withEndAction {
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
                        clampAndApplyOverlayPosition()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        clampAndApplyOverlayPosition()
                        overlayPrefs.edit().putInt(KEY_POS_X, params.x).putInt(KEY_POS_Y, params.y).apply()
                    }
                    dragging
                }

                else -> false
            }
        }
    }

    private fun clampAndApplyOverlayPosition() {
        val params = overlayParams ?: return
        val view = overlayView ?: return
        val width = if (view.width > 0) view.width else params.width.coerceAtLeast(dp(180))
        val height = if (view.height > 0) view.height else dp(if (overlayExpanded) 360 else 190)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val sideMargin = dp(8)
        val topMargin = dp(24)
        val maxX = max(sideMargin, screenWidth - width - sideMargin)
        val maxY = max(topMargin, screenHeight - height - sideMargin)
        params.x = params.x.coerceIn(sideMargin, maxX)
        params.y = params.y.coerceIn(topMargin, maxY)
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
        private const val KEY_LAYOUT_VERSION = "layout_version"
        private const val OVERLAY_LAYOUT_VERSION = 2

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
