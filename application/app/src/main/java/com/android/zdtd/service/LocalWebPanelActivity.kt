package com.android.zdtd.service

import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI

class LocalWebPanelActivity : Activity() {
  companion object {
    const val EXTRA_SCOPE_KEY = "com.android.zdtd.service.extra.WEB_PANEL_SCOPE_KEY"
    const val EXTRA_DEFAULT_URL = "com.android.zdtd.service.extra.WEB_PANEL_DEFAULT_URL"

    private const val PREFS = "web_panel_memory"
    private const val DEFAULT_SCOPE = "program/operaproxy"
    private const val DEFAULT_URL = "http://127.0.0.1:8000/"
  }

  private lateinit var webView: WebView
  private lateinit var scopeKey: String
  private lateinit var defaultUrl: String
  private var allowedPort: Int = 8000
  private var restoredScroll = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    scopeKey = intent.getStringExtra(EXTRA_SCOPE_KEY)?.takeIf { it.isNotBlank() } ?: DEFAULT_SCOPE
    defaultUrl = intent.getStringExtra(EXTRA_DEFAULT_URL)
      ?.takeIf { isLocalHttpUrl(it) }
      ?: DEFAULT_URL
    allowedPort = localHttpPort(defaultUrl) ?: 8000

    title = getString(R.string.web_panel_open)

    webView = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.databaseEnabled = true
      settings.cacheMode = WebSettings.LOAD_DEFAULT
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

        override fun onPageFinished(view: WebView, url: String) {
          super.onPageFinished(view, url)
          restoreScrollOnce(view)
        }
      }
    }

    setContentView(webView)

    if (savedInstanceState != null) {
      restoredScroll = true
      webView.restoreState(savedInstanceState)
    } else {
      webView.loadUrl(loadRememberedUrl())
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    savePanelMemory()
    webView.saveState(outState)
  }

  override fun onPause() {
    savePanelMemory()
    super.onPause()
  }

  override fun onDestroy() {
    savePanelMemory()
    webView.destroy()
    super.onDestroy()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (::webView.isInitialized && webView.canGoBack()) {
      webView.goBack()
    } else {
      super.onBackPressed()
    }
  }

  private fun loadRememberedUrl(): String {
    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    val remembered = prefs.getString(key("lastUrl"), null)
    return remembered?.takeIf { isAllowedLocalUrl(it) } ?: defaultUrl
  }

  private fun restoreScrollOnce(view: WebView) {
    if (restoredScroll) return
    restoredScroll = true
    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    val sx = prefs.getInt(key("scrollX"), 0)
    val sy = prefs.getInt(key("scrollY"), 0)
    if (sx == 0 && sy == 0) return
    view.postDelayed({ view.scrollTo(sx, sy) }, 120)
  }

  private fun savePanelMemory() {
    if (!::webView.isInitialized) return
    val currentUrl = webView.url?.takeIf { isAllowedLocalUrl(it) } ?: defaultUrl
    getSharedPreferences(PREFS, MODE_PRIVATE)
      .edit()
      .putString(key("lastUrl"), currentUrl)
      .putInt(key("scrollX"), webView.scrollX)
      .putInt(key("scrollY"), webView.scrollY)
      .putLong(key("updatedAt"), System.currentTimeMillis())
      .apply()
  }

  private fun key(name: String): String = "$scopeKey.$name"

  private fun isAllowedLocalUrl(raw: String): Boolean {
    if (raw == "about:blank") return true
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
}
