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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
  onOpenAnalysisTools: () -> Unit,
  onOpenOptionalTools: () -> Unit,
  onOpenConstructionStudio: () -> Unit,
  onOpenDpiDetector: () -> Unit,
  onOpenNfqwsTester: () -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val programs by remember(uiStateFlow) {
    uiStateFlow.map { it.programs }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = emptyList())

  val daemonOnline by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonOnline }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val tgWsProxy by remember(uiStateFlow) {
    uiStateFlow.map { it.tgWsProxy }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = com.android.zdtd.service.tgwsproxy.TgWsProxyComponentState())

  val tproxyEnabled by remember(uiStateFlow) {
    uiStateFlow.map { it.tproxyEnabled }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

  LaunchedEffect(Unit) {
    actions.refreshOptionalTools()
  }

  fun AppsRoute.depth(): Int = when (this) {
    AppsRoute.List -> 0
    AppsRoute.AnalysisTools -> 1
    AppsRoute.OptionalTools -> 1
    AppsRoute.ConstructionStudio -> 2
    AppsRoute.DpiDetector -> 2
    AppsRoute.NfqwsTester -> 2
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
        tgWsProxy = tgWsProxy,
        onOpenProgram = onOpenProgram,
        onOpenAnalysisTools = onOpenAnalysisTools,
        onOpenOptionalTools = onOpenOptionalTools,
        listState = listState,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      AppsRoute.OptionalTools -> OptionalToolsScreen(
        state = tgWsProxy,
        actions = actions,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      AppsRoute.AnalysisTools -> AnalysisToolsScreen(
        onOpenConstructionStudio = onOpenConstructionStudio,
        onOpenDpiDetector = onOpenDpiDetector,
        onOpenNfqwsTester = onOpenNfqwsTester,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      AppsRoute.ConstructionStudio -> ConstructionStudioScreen(
        programs = programs,
        actions = actions,
        onOpenProgram = onOpenProgram,
        onOpenProfile = onOpenProfile,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      AppsRoute.DpiDetector -> DpiDetectorScreen(
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      AppsRoute.NfqwsTester -> NfqwsTesterScreen(
        uiStateFlow = uiStateFlow,
        actions = actions,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      is AppsRoute.Program -> when (r.programId) {
        "tgwsproxy" -> TgWsProxySettingsScreen(
          programs = programs,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "openvpn" -> OpenVpnProgramScreen(
          programs = programs,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
        )
        "amneziawg" -> AmneziaWgProgramScreen(
          programs = programs,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "tun2socks" -> Tun2SocksProgramScreen(
          programs = programs,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "myvpn" -> MyVpnProgramScreen(
          programs = programs,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "mihomo" -> MihomoProgramScreen(
          programs = programs,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        else -> ProgramScreen(
          programs = programs,
          programId = r.programId,
          onOpenProfile = onOpenProfile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
        )
      }
      is AppsRoute.Profile -> when (r.programId) {
        "sing-box" -> SingBoxProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "wireproxy" -> WireProxyProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "myproxy" -> MyProxyProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          tproxyEnabled = tproxyEnabled,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "myprogram" -> MyProgramProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "openvpn" -> OpenVpnProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "amneziawg" -> AmneziaWgProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "tun2socks" -> Tun2SocksProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "myvpn" -> MyVpnProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "mihomo" -> MihomoProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "mieru" -> MieruProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        else -> ProfileScreen(
          programs = programs,
          programId = r.programId,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
      }
      else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.apps_host_unknown_route)) }
    }
  }
}
