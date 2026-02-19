package com.android.zdtd.service

import android.webkit.JavascriptInterface

class ConfigBridge(private val manager: RootConfigManager) {

    @JavascriptInterface
    fun testRoot(): Boolean = manager.testRoot()

    // ----- ZDT-D API helpers -----

    @JavascriptInterface
    fun getApiBaseUrl(): String = "http://127.0.0.1:1006"

    @JavascriptInterface
    fun getApiToken(): String = manager.readApiToken()

    // ----- Root-proxy HTTP (fallback for devices where WebView/network stack can't reach localhost) -----

    @JavascriptInterface
    fun proxyGet(path: String): String = manager.proxyGet(path)

    @JavascriptInterface
    fun proxyPost(path: String, body: String): String = manager.proxyPost(path, body)

    @JavascriptInterface
    fun proxyPut(path: String, body: String): String = manager.proxyPut(path, body)

    @JavascriptInterface
    fun proxyDelete(path: String, body: String): String = manager.proxyDelete(path, body)
}
