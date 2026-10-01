package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.NonRootRuntimeStore
import com.android.zdtd.service.NonRootWorkMode
import com.android.zdtd.service.NonRootVpnState
import com.android.zdtd.service.R
import com.android.zdtd.service.api.T2sApiClient
import com.android.zdtd.service.ui.t2s.T2sPanelScreen

@Composable
internal fun NonRootStatsScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  workMode: NonRootWorkMode,
  t2sApiPort: Int,
  vpnState: NonRootVpnState,
) {
  if (workMode == NonRootWorkMode.CASCADE && vpnState == NonRootVpnState.RUNNING) {
    val context = LocalContext.current
    val runtimeStore = remember(context) { NonRootRuntimeStore(context.applicationContext) }
    val client = remember(t2sApiPort, runtimeStore) {
      T2sApiClient(t2sApiPort) {
        runCatching { runtimeStore.ensureApiToken().readText().trim() }.getOrDefault("")
      }
    }
    T2sPanelScreen(
      title = stringResource(R.string.non_root_t2s_stats_title),
      scope = "127.0.0.1:$t2sApiPort",
      port = t2sApiPort,
      client = client,
      onClose = {},
      showTopBar = false,
      outerPadding = PaddingValues(top = topContentPadding, bottom = bottomContentPadding),
    )
    return
  }

  val screenPadding = rememberAdaptiveScreenPadding()
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = screenPadding,
      top = topContentPadding + 8.dp,
      end = screenPadding,
      bottom = bottomContentPadding + 8.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
      ) {
        androidx.compose.foundation.layout.Column(
          modifier = Modifier.padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = stringResource(R.string.nav_stats),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(
              if (workMode == NonRootWorkMode.CASCADE) R.string.non_root_t2s_stopped
              else R.string.non_root_direct_stats_empty,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
