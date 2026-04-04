package com.android.zdtd.service.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.api.ApiClient
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quick Settings tile: start/stop the daemon without keeping the app running.
 *
 * Current behavior:
 * - on listen: show cached state immediately, then refresh from real /api/status
 * - on click: prefer real /api/status to choose action, fall back to cached state only if needed
 * - after /api/start or /api/stop: wait until the expected state is actually observed
 */
class ZdtdTileService : TileService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val clickInFlight = AtomicBoolean(false)

  private lateinit var root: RootConfigManager
  private lateinit var api: ApiClient

  override fun onCreate() {
    super.onCreate()
    root = RootConfigManager(applicationContext)
    api = ApiClient(
      rootManager = root,
      baseUrlProvider = { "http://127.0.0.1:1006" },
      tokenProvider = { root.readApiToken() },
    )
  }

  override fun onStartListening() {
    super.onStartListening()
    updateTileFromCache()
    scope.launch {
      refreshTileFromRealStatus()
    }
  }

  override fun onClick() {
    super.onClick()
    if (!clickInFlight.compareAndSet(false, true)) return

    scope.launch {
      try {
        val rootOk = runCatching { root.testRoot() }.getOrDefault(false)
        if (!rootOk) {
          withContext(Dispatchers.Main.immediate) {
            Toast.makeText(this@ZdtdTileService, getString(R.string.tile_root_required), Toast.LENGTH_SHORT).show()
            setTileState(Tile.STATE_UNAVAILABLE)
          }
          return@launch
        }

        // Prefer real state; only fall back to cached value if status cannot be read.
        val currentReport = runCatching { api.getStatus() }.getOrNull()
        val wasOn = currentReport?.let { ApiModels.isServiceOn(it) } ?: root.getCachedServiceOn()
        root.setCachedServiceOn(wasOn)

        withContext(Dispatchers.Main.immediate) {
          setTileState(Tile.STATE_UNAVAILABLE)
        }

        val commandOk = runCatching {
          if (wasOn) api.stopService() else api.startService()
        }.getOrDefault(false)

        if (!commandOk) {
          withContext(Dispatchers.Main.immediate) {
            Toast.makeText(
              this@ZdtdTileService,
              if (wasOn) getString(R.string.tile_stop_failed) else getString(R.string.tile_start_failed),
              Toast.LENGTH_SHORT
            ).show()
          }
          refreshTileFromRealStatus(fallbackToCache = true)
          return@launch
        }

        val expectedOn = !wasOn
        val resolvedOn = waitForExpectedServiceState(expectedOn)
        if (resolvedOn != null) {
          root.setCachedServiceOn(resolvedOn)
          withContext(Dispatchers.Main.immediate) {
            setTileState(if (resolvedOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
            Toast.makeText(
              this@ZdtdTileService,
              if (resolvedOn) getString(R.string.tile_started) else getString(R.string.tile_stopped),
              Toast.LENGTH_SHORT
            ).show()
          }
        } else {
          // Final best-effort refresh. If state is still unknown, keep the last known cache.
          val refreshed = refreshTileFromRealStatus(fallbackToCache = true)
          withContext(Dispatchers.Main.immediate) {
            Toast.makeText(
              this@ZdtdTileService,
              if (expectedOn) getString(R.string.tile_start_failed) else getString(R.string.tile_stop_failed),
              Toast.LENGTH_SHORT
            ).show()
            if (!refreshed) {
              updateTileFromCache()
            }
          }
        }
      } finally {
        clickInFlight.set(false)
      }
    }
  }

  private suspend fun refreshTileFromRealStatus(fallbackToCache: Boolean = false): Boolean {
    val report = runCatching { api.getStatus() }.getOrNull()
    return if (report != null) {
      val on = ApiModels.isServiceOn(report)
      root.setCachedServiceOn(on)
      withContext(Dispatchers.Main.immediate) {
        setTileState(if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
      }
      true
    } else {
      withContext(Dispatchers.Main.immediate) {
        if (fallbackToCache) {
          updateTileFromCache()
        } else {
          setTileState(Tile.STATE_UNAVAILABLE)
        }
      }
      false
    }
  }

  private suspend fun waitForExpectedServiceState(expectedOn: Boolean): Boolean? {
    val timeoutMs = 20_000L
    val pollMs = 750L
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < timeoutMs) {
      val report = runCatching { api.getStatus() }.getOrNull()
      if (report != null) {
        val on = ApiModels.isServiceOn(report)
        if (on == expectedOn) return on
      }
      delay(pollMs)
    }
    return null
  }

  private fun updateTileFromCache() {
    val on = root.getCachedServiceOn()
    setTileState(if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
  }

  private fun setTileState(state: Int) {
    qsTile?.label = getString(R.string.qs_tile_label)
    qsTile?.state = state
    qsTile?.updateTile()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }
}
