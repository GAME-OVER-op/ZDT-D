package com.android.zdtd.service

import java.net.URI
import java.util.Locale

const val OPERA_UPSTREAM_DIRECT = "direct"
const val OPERA_UPSTREAM_BYEDPI = "byedpi"
const val OPERA_UPSTREAM_CUSTOM = "custom"

private val OPERA_UPSTREAM_SCHEMES = setOf("http", "https", "socks5", "socks5h")

fun normalizeOperaUpstreamMode(raw: String?, legacyUseByedpi: Boolean): String =
  when (raw?.trim()?.lowercase(Locale.ROOT)) {
    OPERA_UPSTREAM_DIRECT -> OPERA_UPSTREAM_DIRECT
    OPERA_UPSTREAM_BYEDPI -> OPERA_UPSTREAM_BYEDPI
    OPERA_UPSTREAM_CUSTOM -> OPERA_UPSTREAM_CUSTOM
    else -> if (legacyUseByedpi) OPERA_UPSTREAM_BYEDPI else OPERA_UPSTREAM_DIRECT
  }

fun isValidOperaCustomProxy(raw: String): Boolean {
  val value = raw.trim()
  if (value.isEmpty() || value.any(Char::isWhitespace)) return false
  val uri = runCatching { URI(value) }.getOrNull() ?: return false
  val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
  if (scheme !in OPERA_UPSTREAM_SCHEMES) return false
  if (uri.host.isNullOrBlank()) return false
  if (uri.port !in 1..65535) return false
  if (!uri.path.isNullOrEmpty() && uri.path != "/") return false
  if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return false
  return true
}

fun maskOperaProxyForLog(raw: String): String {
  val value = raw.trim()
  val separator = value.indexOf("://")
  if (separator <= 0) return value
  val prefix = value.substring(0, separator + 3)
  val rest = value.substring(separator + 3)
  val at = rest.lastIndexOf('@')
  return if (at >= 0) prefix + "***@" + rest.substring(at + 1) else value
}
