package com.android.zdtd.service

import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * App-owned local port registry for the non-root runtime.
 *
 * Ports are always bound to 127.0.0.1 by native components. This registry only
 * allocates and persists numbers; it never exposes listeners to LAN interfaces.
 */
class NonRootPortRegistry(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  @Synchronized
  fun getOrAllocate(key: String): Int {
    val persisted = prefs.getInt(key, 0)
    if (persisted in MIN_PORT..MAX_PORT && !isReservedByAnotherKey(key, persisted)) {
      return persisted
    }

    val port = (MIN_PORT..MAX_PORT).firstOrNull { candidate ->
      !isReserved(candidate) && canBindLoopback(candidate)
    } ?: error("No free non-root port available")

    prefs.edit().putInt(key, port).apply()
    return port
  }

  @Synchronized
  fun set(key: String, port: Int): Boolean {
    if (port !in MIN_PORT..MAX_PORT) return false
    if (prefs.getInt(key, 0) == port) return true
    if (isReservedByAnotherKey(key, port)) return false
    if (!canBindLoopback(port)) return false
    prefs.edit().putInt(key, port).apply()
    return true
  }

  @Synchronized
  fun clear(key: String) {
    prefs.edit().remove(key).apply()
  }

  private fun isReserved(port: Int): Boolean = prefs.all.values.any { it == port }

  private fun isReservedByAnotherKey(key: String, port: Int): Boolean =
    prefs.all.any { (storedKey, value) -> storedKey != key && value == port }

  private fun canBindLoopback(port: Int): Boolean = runCatching {
    ServerSocket().use { socket ->
      socket.reuseAddress = false
      socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port))
    }
    true
  }.getOrDefault(false)

  companion object {
    const val LOOPBACK = "127.0.0.1"
    const val MIN_PORT = 10_000
    const val MAX_PORT = 65_535
    const val DIRECT_OPERA_KEY = "direct.operaproxy"
    const val DIRECT_BYEDPI_KEY = "direct.byedpi"
    const val T2S_LISTEN_KEY = "cascade.t2s.listen"
    const val T2S_API_KEY = "cascade.t2s.api"

    private const val PREFS_NAME = "non_root_ports"
  }
}
