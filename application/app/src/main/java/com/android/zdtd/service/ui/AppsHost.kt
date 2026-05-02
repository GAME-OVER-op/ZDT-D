package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.R
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun AppsHost(
  uiStateFlow: StateFlow<UiState>,
  route: AppsRoute,
  onOpenProgram: (String) -> Unit,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val programs by remember(uiStateFlow) {
    uiStateFlow.map { it.programs }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = emptyList())

  val daemonOnline by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonOnline }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

  fun AppsRoute.depth(): Int = when (this) {
    AppsRoute.List -> 0
    is AppsRoute.Program -> 1
    is AppsRoute.Profile -> 2
  }

  AnimatedContent(
    targetState = route,
    transitionSpec = {
      val forward = targetState.depth() > initialState.depth()

      val enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) {
        if (forward) it / 6 else -it / 6
      }
      val exit = fadeOut(tween(160)) + slideOutHorizontally(tween(220)) {
        if (forward) -it / 6 else it / 6
      }

      (enter togetherWith exit).using(SizeTransform(clip = false))
    },
    label = "appsRoute",
  ) { r ->
    when (r) {
      AppsRoute.List -> AppsListScreen(
        programs = programs,
        daemonOnline = daemonOnline,
        onOpenProgram = onOpenProgram,
        listState = listState,
      )
      is AppsRoute.Program -> when (r.programId) {
        "openvpn" -> OpenVpnProgramScreen(
          programs = programs,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
        )
        "tun2socks" -> Tun2SocksProgramScreen(
          programs = programs,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
        )
        "myvpn" -> MyVpnProgramScreen(
          programs = programs,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
        )
        else -> ProgramScreen(
          programs = programs,
          programId = r.programId,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
        )
      }
      is AppsRoute.Profile -> when (r.programId) {
        "sing-box" -> SingBoxProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
        )
        "wireproxy" -> WireProxyProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
        )
        "myproxy" -> MyProxyProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
        )
        "myprogram" -> MyProgramProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
        )
        "openvpn" -> OpenVpnProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
        )
        "tun2socks" -> Tun2SocksProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
        )
        "myvpn" -> MyVpnProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
        )
        else -> ProfileScreen(
          programs = programs,
          programId = r.programId,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
        )
      }
      else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.apps_host_unknown_route)) }
    }
  }
}
