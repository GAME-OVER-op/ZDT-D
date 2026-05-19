package com.android.zdtd.service

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URI

class LocalWebPanelActivity : Activity() {
  companion object {
    const val EXTRA_SCOPE_KEY = "com.android.zdtd.service.extra.WEB_PANEL_SCOPE_KEY"
    const val EXTRA_DEFAULT_URL = "com.android.zdtd.service.extra.WEB_PANEL_DEFAULT_URL"

    private const val DEFAULT_URL = "http://127.0.0.1:8000/"
  }

  private lateinit var webView: WebView
  private lateinit var loadingOverlay: View
  private lateinit var loadingTitle: TextView
  private lateinit var loadingBody: TextView
  private lateinit var defaultUrl: String
  private var allowedPort: Int = 8000
  private var loadGeneration: Long = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK

    defaultUrl = intent.getStringExtra(EXTRA_DEFAULT_URL)
      ?.takeIf { isLocalHttpUrl(it) }
      ?: DEFAULT_URL
    allowedPort = localHttpPort(defaultUrl) ?: 8000

    title = getString(R.string.web_panel_open)

    webView = WebView(this).apply {
      alpha = 0f
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.databaseEnabled = true
      settings.cacheMode = WebSettings.LOAD_NO_CACHE
      settings.builtInZoomControls = true
      settings.displayZoomControls = false
      settings.loadWithOverviewMode = true
      settings.useWideViewPort = true

      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
          val url = request.url?.toString().orEmpty()
          return !isAllowedLocalUrl(url)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
          return !isAllowedLocalUrl(url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
          super.onPageStarted(view, url, favicon)
          showLoadingOverlay(animate = true)
        }

        override fun onPageFinished(view: WebView, url: String) {
          super.onPageFinished(view, url)
          hideLoadingOverlay()
        }

        override fun onReceivedError(
          view: WebView,
          request: WebResourceRequest,
          error: WebResourceError,
        ) {
          super.onReceivedError(view, request, error)
          if (request.isForMainFrame) hideLoadingOverlay()
        }
      }
    }

    val contentFrame = FrameLayout(this).apply {
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f,
      )
      setBackgroundColor(Color.BLACK)
      addView(
        webView,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
      loadingOverlay = createLoadingOverlay()
      addView(
        loadingOverlay,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
    }

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.BLACK)
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
      addView(createToolbar())
      addView(contentFrame)
    }
    applySystemBarInsets(root)
    setContentView(root)
    ViewCompat.requestApplyInsets(root)

    showLoadingOverlay(animate = false)
    loadDefaultPage(clearData = false, fresh = true)
  }

  private fun applySystemBarInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(0, bars.top, 0, bars.bottom)
      insets
    }
  }

  override fun onDestroy() {
    if (::webView.isInitialized) {
      (webView.parent as? ViewGroup)?.removeView(webView)
      webView.stopLoading()
      webView.destroy()
    }
    super.onDestroy()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    finish()
  }

  private fun createToolbar(): View {
    val compact = useCompactToolbar()
    val toolbarHeight = if (compact) dp(62) else dp(64)
    val cardHeight = if (compact) dp(46) else dp(48)

    return FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      setPadding(dp(10), dp(7), dp(10), dp(7))
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        toolbarHeight,
      )

      val actions = LinearLayout(this@LocalWebPanelActivity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(5), dp(6), dp(5))

        addView(toolbarAction("←", getString(R.string.web_panel_action_back), compact = true) { finish() })
        addView(toolbarAction(if (compact) "↻" else getString(R.string.web_panel_action_refresh), getString(R.string.web_panel_action_refresh), compact = compact) {
          refreshPanelPage()
        })
        addView(toolbarAction(if (compact) "⟲" else getString(R.string.web_panel_action_reset), getString(R.string.web_panel_action_reset), compact = compact) {
          resetPanelPage()
        })
      }

      val card = FrameLayout(this@LocalWebPanelActivity).apply {
        background = roundedBackground(
          color = Color.rgb(12, 12, 16),
          radiusDp = 20,
          strokeColor = Color.rgb(64, 64, 78),
          strokeWidthDp = 1,
        )
        elevation = dp(5).toFloat()
        clipToOutline = true

        val scroll = HorizontalScrollView(this@LocalWebPanelActivity).apply {
          isHorizontalScrollBarEnabled = false
          overScrollMode = View.OVER_SCROLL_NEVER
          addView(
            actions,
            FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
              Gravity.START or Gravity.CENTER_VERTICAL,
            ),
          )
        }
        addView(
          scroll,
          FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL,
          ),
        )
      }

      addView(
        card,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          cardHeight,
          Gravity.START or Gravity.CENTER_VERTICAL,
        ),
      )
    }
  }

  private fun toolbarAction(
    label: String,
    description: String,
    compact: Boolean,
    onClick: () -> Unit,
  ): TextView {
    val width = if (compact) dp(44) else ViewGroup.LayoutParams.WRAP_CONTENT
    val horizontalPadding = if (compact) 0 else dp(16)
    return TextView(this).apply {
      text = label
      contentDescription = description
      gravity = Gravity.CENTER
      isClickable = true
      isFocusable = true
      includeFontPadding = false
      setTextColor(Color.WHITE)
      textSize = if (compact) 20f else 14f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      minWidth = dp(44)
      minHeight = dp(36)
      setPadding(horizontalPadding, 0, horizontalPadding, 0)
      background = roundedBackground(
        color = Color.rgb(30, 30, 38),
        radiusDp = 16,
        strokeColor = Color.rgb(84, 84, 100),
        strokeWidthDp = 1,
      )
      elevation = dp(1).toFloat()
      setOnTouchListener { view, event ->
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> view.alpha = 0.72f
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.alpha = 1f
        }
        false
      }
      setOnClickListener { onClick() }
      layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
      }
    }
  }

  private fun createLoadingOverlay(): View {
    val titleText = getString(R.string.delete_module_prepare_title)
    val bodyText = getString(R.string.web_panel_loading_page)

    val card = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(22), dp(20), dp(22), dp(20))
      background = roundedBackground(
        color = Color.rgb(14, 14, 19),
        radiusDp = 24,
        strokeColor = Color.rgb(72, 72, 86),
        strokeWidthDp = 1,
      )
      elevation = dp(8).toFloat()

      val progress = ProgressBar(this@LocalWebPanelActivity).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
          bottomMargin = dp(14)
        }
      }

      loadingTitle = TextView(this@LocalWebPanelActivity).apply {
        text = titleText
        setTextColor(Color.WHITE)
        textSize = 17f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        includeFontPadding = false
      }

      loadingBody = TextView(this@LocalWebPanelActivity).apply {
        text = bodyText
        setTextColor(Color.rgb(210, 210, 220))
        textSize = 13f
        gravity = Gravity.CENTER
        includeFontPadding = true
        setPadding(0, dp(8), 0, 0)
      }

      addView(progress)
      addView(loadingTitle)
      addView(loadingBody)
    }

    return FrameLayout(this).apply {
      visibility = View.VISIBLE
      alpha = 1f
      setBackgroundColor(Color.rgb(0, 0, 0))
      addView(
        card,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
          Gravity.CENTER,
        ).apply {
          marginStart = dp(24)
          marginEnd = dp(24)
        },
      )
    }
  }

  private fun useCompactToolbar(): Boolean {
    val cfg = resources.configuration
    return cfg.screenWidthDp < 420 || cfg.screenHeightDp < 760
  }

  private fun roundedBackground(
    color: Int,
    radiusDp: Int,
    strokeColor: Int? = null,
    strokeWidthDp: Int = 0,
  ): GradientDrawable {
    return GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      setColor(color)
      cornerRadius = dp(radiusDp).toFloat()
      if (strokeColor != null && strokeWidthDp > 0) {
        setStroke(dp(strokeWidthDp), strokeColor)
      }
    }
  }

  private fun refreshPanelPage() {
    showLoadingOverlay(animate = true)
    loadDefaultPage(clearData = false, fresh = true)
  }

  private fun resetPanelPage() {
    showLoadingOverlay(animate = true)
    loadDefaultPage(clearData = true, fresh = true)
  }

  private fun loadDefaultPage(clearData: Boolean, fresh: Boolean) {
    val generation = ++loadGeneration
    webView.stopLoading()
    if (clearData) {
      clearAllWebPanelData()
    } else if (fresh) {
      webView.clearCache(true)
    }
    webView.clearHistory()
    webView.clearFormData()
    webView.post {
      if (generation != loadGeneration || isFinishing) return@post
      val target = if (fresh) cacheBustedDefaultUrl() else defaultUrl
      webView.loadUrl(target, noCacheHeaders())
    }
  }

  private fun clearAllWebPanelData() {
    runCatching { WebStorage.getInstance().deleteAllData() }
    runCatching {
      CookieManager.getInstance().removeAllCookies(null)
      CookieManager.getInstance().flush()
    }
    runCatching {
      val db = WebViewDatabase.getInstance(this)
      db.clearFormData()
      db.clearHttpAuthUsernamePassword()
    }
    runCatching { webView.clearCache(true) }
    runCatching { webView.clearHistory() }
    runCatching { webView.clearFormData() }
  }

  private fun showLoadingOverlay(animate: Boolean) {
    if (!::loadingOverlay.isInitialized) return
    loadingTitle.text = getString(R.string.delete_module_prepare_title)
    loadingBody.text = getString(R.string.web_panel_loading_page)
    loadingOverlay.visibility = View.VISIBLE
    loadingOverlay.animate().cancel()
    webView.animate().cancel()
    if (animate) {
      loadingOverlay.animate().alpha(1f).setDuration(160L).start()
      webView.animate().alpha(0f).setDuration(140L).start()
    } else {
      loadingOverlay.alpha = 1f
      webView.alpha = 0f
    }
  }

  private fun hideLoadingOverlay() {
    if (!::loadingOverlay.isInitialized) return
    webView.animate().cancel()
    loadingOverlay.animate().cancel()
    webView.animate().alpha(1f).setDuration(180L).start()
    loadingOverlay.animate()
      .alpha(0f)
      .setDuration(180L)
      .withEndAction {
        loadingOverlay.visibility = View.GONE
      }
      .start()
  }

  private fun noCacheHeaders(): Map<String, String> = mapOf(
    "Cache-Control" to "no-cache, no-store, must-revalidate",
    "Pragma" to "no-cache",
    "Expires" to "0",
  )

  private fun cacheBustedDefaultUrl(): String {
    return runCatching {
      Uri.parse(defaultUrl)
        .buildUpon()
        .appendQueryParameter("zdt_refresh", System.currentTimeMillis().toString())
        .build()
        .toString()
    }.getOrDefault(defaultUrl)
  }

  private fun isAllowedLocalUrl(raw: String): Boolean {
    val uri = parseLocalHttpUri(raw) ?: return false
    return localHttpPort(uri) == allowedPort
  }

  private fun isLocalHttpUrl(raw: String): Boolean = parseLocalHttpUri(raw) != null

  private fun parseLocalHttpUri(raw: String): URI? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (scheme != "http") return null
    if (host != "127.0.0.1" && host != "localhost") return null
    val port = localHttpPort(uri) ?: return null
    return uri.takeIf { port in 1..65535 }
  }

  private fun localHttpPort(raw: String): Int? = parseLocalHttpUri(raw)?.let { localHttpPort(it) }

  private fun localHttpPort(uri: URI): Int? = if (uri.port == -1) 80 else uri.port

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
