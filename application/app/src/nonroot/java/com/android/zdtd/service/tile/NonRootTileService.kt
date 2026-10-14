package com.android.zdtd.service.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.android.zdtd.service.NonRootVpnPermissionActivity
import com.android.zdtd.service.NonRootVpnRuntime
import com.android.zdtd.service.NonRootVpnService
import com.android.zdtd.service.NonRootVpnState
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Non-root Quick Settings tile.
 *
 * Unlike the root tile this does not use the daemon HTTP API. It drives the
 * app-owned VpnService directly and observes the process-local runtime state.
 */
class NonRootTileService : TileService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var stateJob: Job? = null

  override fun onStartListening() {
    super.onStartListening()
    stateJob?.cancel()
    stateJob = scope.launch {
      NonRootVpnRuntime.state.collectLatest(::updateTile)
    }
  }

  override fun onStopListening() {
    stateJob?.cancel()
    stateJob = null
    super.onStopListening()
  }

  override fun onClick() {
    super.onClick()

    when (NonRootVpnRuntime.state.value) {
      NonRootVpnState.STARTING,
      NonRootVpnState.RUNNING -> requestStop()

      NonRootVpnState.STOPPING,
      NonRootVpnState.STOPPED,
      NonRootVpnState.ERROR -> requestStart()
    }
  }

  private fun requestStart() {
    // Do not allow the tile to bypass the first-run warning/setup flow.
    if (!RootConfigManager(applicationContext).isWelcomeAccepted()) {
      Toast.makeText(this, getString(R.string.tile_start_failed), Toast.LENGTH_SHORT).show()
      updateTile(NonRootVpnState.STOPPED)
      return
    }

    val prepareIntent = runCatching { VpnService.prepare(this) }.getOrElse {
      Toast.makeText(this, getString(R.string.tile_start_failed), Toast.LENGTH_SHORT).show()
      updateTile(NonRootVpnState.ERROR)
      return
    }

    if (prepareIntent == null) {
      updateTile(NonRootVpnState.STARTING)
      runCatching {
        ContextCompat.startForegroundService(this, NonRootVpnService.startIntent(this))
      }.onFailure {
        Toast.makeText(this, getString(R.string.tile_start_failed), Toast.LENGTH_SHORT).show()
        updateTile(NonRootVpnRuntime.state.value)
      }
      return
    }

    // Android requires user consent once. A tiny transparent helper activity
    // owns the Activity Result and starts the VPN immediately after consent.
    launchPermissionFlow()
  }

  private fun requestStop() {
    updateTile(NonRootVpnState.STOPPING)
    runCatching {
      NonRootVpnService.stop(this)
    }.onFailure {
      Toast.makeText(this, getString(R.string.tile_stop_failed), Toast.LENGTH_SHORT).show()
      updateTile(NonRootVpnRuntime.state.value)
    }
  }

  private fun launchPermissionFlow() {
    val intent = Intent(this, NonRootVpnPermissionActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    runCatching {
      if (Build.VERSION.SDK_INT >= 34) {
        val pendingIntent = PendingIntent.getActivity(
          this,
          0,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pendingIntent)
      } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
      }
    }.onFailure {
      Toast.makeText(this, getString(R.string.tile_start_failed), Toast.LENGTH_SHORT).show()
      updateTile(NonRootVpnRuntime.state.value)
    }
  }

  private fun updateTile(state: NonRootVpnState) {
    val tileState = when (state) {
      NonRootVpnState.RUNNING -> Tile.STATE_ACTIVE
      NonRootVpnState.STOPPED,
      NonRootVpnState.ERROR -> Tile.STATE_INACTIVE
      NonRootVpnState.STARTING,
      NonRootVpnState.STOPPING -> Tile.STATE_UNAVAILABLE
    }

    qsTile?.apply {
      label = getString(R.string.qs_tile_label)
      this.state = tileState
      updateTile()
    }
  }

  override fun onDestroy() {
    stateJob?.cancel()
    scope.cancel()
    super.onDestroy()
  }
}
