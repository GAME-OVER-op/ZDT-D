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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.zdtd.service.R
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.components.RootBinaryComponentCatalog
import com.android.zdtd.service.ui.vps.VpsProfileScreen
import com.android.zdtd.service.ui.vps.VpsServerDetailsScreen
import com.android.zdtd.service.ui.vps.VpsServersScreen
import com.android.zdtd.service.ui.vps.VpsServiceScreen
import com.android.zdtd.service.ui.vps.VpsTerminalScreen
import com.android.zdtd.service.vps.VpsServiceKind
import com.android.zdtd.service.vps.VpsViewModel
import com.android.zdtd.service.vps.VpsViewModelFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun AppsHost(
  uiStateFlow: StateFlow<UiState>,
  route: AppsRoute,
  onOpenAppsList: () -> Unit,
  onOpenProgram: (String) -> Unit,
  onOpenProfile: (String, String) -> Unit,
  onOpenAnalysisTools: () -> Unit,
  onOpenOptionalTools: () -> Unit,
  onOpenVpsServers: () -> Unit,
  onOpenVpsServer: (String) -> Unit,
  onOpenVpsTerminal: (String) -> Unit,
  onOpenVpsService: (String, String) -> Unit,
  onOpenVpsProfile: (String, String, String) -> Unit,
  onOpenConstructionStudio: () -> Unit,
  onOpenDpiDetector: () -> Unit,
  onOpenNfqwsTester: () -> Unit,
  onOpenSubscriptions: () -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  tproxyEnabled: Boolean = false,
  landscapeControl: Boolean = false,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
  appsEntryFocusRequester: FocusRequester? = null,
  appsNavFocusRequester: FocusRequester? = null,
) {
  val context = LocalContext.current
  val application = context.applicationContext as android.app.Application
  val vpsViewModel: VpsViewModel = viewModel(factory = remember(application) { VpsViewModelFactory(application) })

  val programs by remember(uiStateFlow) {
    uiStateFlow.map { it.programs }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = emptyList())

  val daemonOnline by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonOnline }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val tgWsProxy by remember(uiStateFlow) {
    uiStateFlow.map { it.tgWsProxy }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = com.android.zdtd.service.tgwsproxy.TgWsProxyComponentState())

  val singBoxLx by remember(uiStateFlow) {
    uiStateFlow.map { it.singBoxLx }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = com.android.zdtd.service.singboxlx.SingBoxLxComponentState())

  val components by remember(uiStateFlow) {
    uiStateFlow.map { it.components }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = emptyList())

  val componentsResolved by remember(uiStateFlow) {
    uiStateFlow.map { it.componentsResolved }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val rootComponents by remember(uiStateFlow) {
    uiStateFlow.map { it.rootComponents }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = emptyMap())

  val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

  LaunchedEffect(Unit) {
    actions.refreshOptionalTools()
  }

  fun AppsRoute.depth(): Int = when (this) {
    AppsRoute.List -> 0
    AppsRoute.AnalysisTools -> 1
    AppsRoute.OptionalTools -> 1
    AppsRoute.VpsServers -> 1
    is AppsRoute.VpsServer -> 2
    is AppsRoute.VpsTerminal -> 3
    is AppsRoute.VpsService -> 3
    is AppsRoute.VpsProfile -> 4
    AppsRoute.ConstructionStudio -> 2
    AppsRoute.DpiDetector -> 2
    AppsRoute.NfqwsTester -> 2
    AppsRoute.Subscriptions -> 1
    is AppsRoute.Program -> 1
    is AppsRoute.Profile -> 2
  }

  val routeProgramId = when (route) {
    is AppsRoute.Program -> route.programId
    is AppsRoute.Profile -> route.programId
    else -> null
  }
  val knownComponentProgramIds = remember {
    RootBinaryComponentCatalog.moduleComponents.flatMap { it.programIds }.toSet() + setOf("sing-box-lx", "tgwsproxy")
  }
  val componentBoundRoute = routeProgramId != null && routeProgramId in knownComponentProgramIds
  val routeComponent = routeProgramId?.let { programId ->
    components.firstOrNull { component -> programId in component.programIds }
  }

  if (componentBoundRoute && daemonOnline && !componentsResolved) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  if (componentBoundRoute && componentsResolved && routeComponent?.installed == false) {
    val componentName = RootBinaryComponentCatalog.moduleComponents
      .firstOrNull { spec -> routeProgramId in spec.programIds }
      ?.displayName
      ?: when (routeProgramId) {
        "sing-box-lx" -> "sing-box-lx"
        "tgwsproxy" -> "Telegram WS Proxy"
        else -> routeProgramId.orEmpty()
      }
    ComponentInstallRequiredDialog(
      componentName = componentName,
      onDismiss = onOpenAppsList,
      onOpenOptionalTools = onOpenOptionalTools,
    )
    return
  }

  AnimatedContent(
    targetState = route,
    transitionSpec = {
      // Match the non-root navigation motion: a short directional slide with
      // a soft cross-fade. It stays readable on phones and wide/TV layouts
      // without the heavier card-stack scaling that used to make root feel
      // visually busier than non-root.
      val forward = targetState.depth() > initialState.depth()
      val enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { width ->
        if (forward) width / 6 else -width / 6
      }
      val exit = fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { width ->
        if (forward) -width / 6 else width / 6
      }
      (enter togetherWith exit).using(SizeTransform(clip = false))
    },
    label = "appsRouteTransition",
  ) { r ->
    when (r) {
      AppsRoute.List -> AppsListScreen(
        programs = programs,
        daemonOnline = daemonOnline,
        components = components,
        componentsResolved = componentsResolved,
        onOpenProgram = onOpenProgram,
        onOpenAnalysisTools = onOpenAnalysisTools,
        onOpenOptionalTools = onOpenOptionalTools,
        onOpenVpsServers = onOpenVpsServers,
        onOpenSubscriptions = onOpenSubscriptions,
        listState = listState,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
        entryFocusRequester = if (landscapeControl) appsEntryFocusRequester else null,
        navFocusRequester = if (landscapeControl) appsNavFocusRequester else null,
      )
      AppsRoute.VpsServers -> VpsServersScreen(
        viewModel = vpsViewModel,
        onOpenServer = onOpenVpsServer,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      is AppsRoute.VpsServer -> VpsServerDetailsScreen(
        serverId = r.serverId,
        viewModel = vpsViewModel,
        onOpenService = { onOpenVpsService(r.serverId, it.wireId) },
        onOpenTerminal = { onOpenVpsTerminal(r.serverId) },
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      is AppsRoute.VpsTerminal -> VpsTerminalScreen(
        serverId = r.serverId,
        viewModel = vpsViewModel,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      is AppsRoute.VpsService -> {
        val kind = VpsServiceKind.fromWireId(r.serviceId)
        if (kind == null) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.vps_unknown_service)) }
        } else {
          VpsServiceScreen(
            serverId = r.serverId,
            kind = kind,
            viewModel = vpsViewModel,
            onOpenProfile = { onOpenVpsProfile(r.serverId, r.serviceId, it) },
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
          )
        }
      }
      is AppsRoute.VpsProfile -> {
        val kind = VpsServiceKind.fromWireId(r.serviceId)
        if (kind == null) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.vps_unknown_service)) }
        } else {
          VpsProfileScreen(
            serverId = r.serverId,
            kind = kind,
            profileId = r.profileId,
            viewModel = vpsViewModel,
            actions = actions,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
          )
        }
      }
      AppsRoute.OptionalTools -> OptionalToolsScreen(
        tgWsProxyState = tgWsProxy,
        singBoxLxState = singBoxLx,
        components = components,
        rootComponentStates = rootComponents,
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
      AppsRoute.Subscriptions -> SubscriptionsScreen(
        actions = actions,
        snackHost = snackHost,
        singBoxLxInstalled = singBoxLx.installed,
        onOpenOptionalTools = onOpenOptionalTools,
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
          tproxyEnabled = tproxyEnabled,
          singBoxLxInstalled = singBoxLx.installed,
          singBoxLxStatusResolved = singBoxLx.statusResolved,
          onOpenProgram = onOpenProgram,
          onOpenOptionalTools = onOpenOptionalTools,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "sing-box-lx" -> SingBoxProfileScreen(
          programs = programs,
          profile = r.profile,
          programId = "sing-box-lx",
          actions = actions,
          snackHost = snackHost,
          tproxyEnabled = tproxyEnabled,
          singBoxLxInstalled = singBoxLx.installed,
          singBoxLxStatusResolved = singBoxLx.statusResolved,
          onOpenProgram = onOpenProgram,
          onOpenOptionalTools = onOpenOptionalTools,
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
        )
        "hysteria2" -> Hysteria2ProfileScreen(
          programs = programs,
          profile = r.profile,
          actions = actions,
          snackHost = snackHost,
          tproxyEnabled = tproxyEnabled,
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
