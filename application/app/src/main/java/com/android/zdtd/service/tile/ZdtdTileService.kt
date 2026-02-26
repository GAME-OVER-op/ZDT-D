package com.android.zdtd.service.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile: start/stop the daemon without keeping the app running.
 *
 * Design goals (per Danil):
 * - no background polling
 * - on click: send ONLY /api/start OR /api/stop
 * - state shown is last-known (cached) state
 */
class ZdtdTileService : TileService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
  }

  override fun onClick() {
    super.onClick()

    // Decide action purely from cached state, so onClick sends exactly ONE command.
    val wasOn = root.getCachedServiceOn()

    scope.launch {
      val rootOk = runCatching { root.testRoot() }.getOrDefault(false)
      if (!rootOk) {
        withContext(Dispatchers.Main.immediate) {
          Toast.makeText(this@ZdtdTileService, getString(R.string.tile_root_required), Toast.LENGTH_SHORT).show()
          qsTile?.label = getString(R.string.qs_tile_label)
          qsTile?.state = Tile.STATE_UNAVAILABLE
          qsTile?.updateTile()
        }
        return@launch
      }

      val ok = runCatching {
        if (wasOn) api.stopService() else api.startService()
      }.getOrDefault(false)

      withContext(Dispatchers.Main.immediate) {
        if (ok) {
          val nowOn = !wasOn
          root.setCachedServiceOn(nowOn)
          qsTile?.label = getString(R.string.qs_tile_label)
          qsTile?.state = if (nowOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
          qsTile?.updateTile()
          Toast.makeText(
            this@ZdtdTileService,
            if (nowOn) getString(R.string.tile_started) else getString(R.string.tile_stopped),
            Toast.LENGTH_SHORT
          ).show()
        } else {
          // Keep state unchanged.
          Toast.makeText(
            this@ZdtdTileService,
            if (wasOn) getString(R.string.tile_stop_failed) else getString(R.string.tile_start_failed),
            Toast.LENGTH_SHORT
          ).show()
          updateTileFromCache()
        }
      }
    }
  }

  private fun updateTileFromCache() {
    val on = root.getCachedServiceOn()
    // Some OEM quick settings UIs may show a generic label (e.g. "Power") unless we explicitly set it.
    // Keep the label stable and recognizable.
    qsTile?.label = getString(R.string.qs_tile_label)
    qsTile?.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    qsTile?.updateTile()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }
}
