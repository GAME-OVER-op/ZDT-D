package com.android.zdtd.service.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.NonRootAppRoutingMode
import com.android.zdtd.service.NonRootBackendServer
import com.android.zdtd.service.NonRootCascadeBackendMode
import com.android.zdtd.service.NonRootCascadeProfile
import com.android.zdtd.service.NonRootCascadeRouteItem
import com.android.zdtd.service.NonRootCascadeRouteItemType
import com.android.zdtd.service.NonRootCascadeState
import com.android.zdtd.service.NonRootDirectOperaConfig
import com.android.zdtd.service.NonRootPortRegistry
import com.android.zdtd.service.NonRootRuntimeLogEntry
import com.android.zdtd.service.NonRootRuntimeStore
import com.android.zdtd.service.NonRootT2sConfig
import com.android.zdtd.service.NonRootTgWsConfig
import com.android.zdtd.service.NonRootTgWsRuntimeState
import com.android.zdtd.service.tgwsplugin.TgWsPluginState
import com.android.zdtd.service.NonRootWorkMode
import com.android.zdtd.service.NonRootVpnState
import com.android.zdtd.service.R
import com.android.zdtd.service.ui.settings.SettingsScreen
import com.android.zdtd.service.ui.vps.VpsProfileScreen
import com.android.zdtd.service.ui.vps.VpsServerDetailsScreen
import com.android.zdtd.service.ui.vps.VpsServersScreen
import com.android.zdtd.service.ui.vps.VpsServiceScreen
import com.android.zdtd.service.vps.VpsServiceKind
import com.android.zdtd.service.vps.VpsViewModel
import com.android.zdtd.service.vps.VpsConfigResult
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Dedicated shell for the app-owned non-root path. */
@Composable
fun NonRootApp(
  languageMode: String,
  themeMode: String,
  workMode: NonRootWorkMode,
  appRoutingMode: NonRootAppRoutingMode,
  appRoutingPackages: Set<String>,
  cascadeState: NonRootCascadeState,
  t2sApiPort: Int,
  vpnState: NonRootVpnState,
  vpnLastError: String?,
  vpnLogs: List<NonRootRuntimeLogEntry>,
  tgWsConfig: NonRootTgWsConfig,
  tgWsPluginState: TgWsPluginState,
  tgWsRuntimeState: NonRootTgWsRuntimeState,
  tgWsRuntimeLastError: String?,
  vpsViewModel: VpsViewModel,
  onVpnStart: () -> Unit,
  onVpnStop: () -> Unit,
  onRequestRootMode: () -> Unit,
  onLanguageModeChange: (String) -> Unit,
  onThemeModeChange: (String) -> Unit,
  onWorkModeChange: (NonRootWorkMode) -> Unit,
  onAppRoutingModeChange: (NonRootAppRoutingMode) -> Unit,
  onAppRoutingPackagesChange: (Set<String>) -> Unit,
  onRestartVpn: () -> Unit,
  onDirectSelectedProfileChange: (String?) -> Unit,
  onCreateCascadeProfile: (String, String) -> Unit,
  onUpdateCascadeProfile: (NonRootCascadeProfile) -> Unit,
  onAddCascadeServer: (String, String) -> Unit,
  onUpdateCascadeServer: (String, NonRootBackendServer) -> Unit,
  onMoveCascadeServer: (String, Int, Int) -> Unit,
  onDeleteCascadeServer: (String, String) -> Unit,
  onCascadeServerPortChange: (String, String, Int) -> Boolean,
  onCascadeServerAuxPortChange: (String, String, Int) -> Boolean,
  onCascadeProfilePortChange: (String, Int) -> Boolean,
  onCascadeProfileByeDpiPortChange: (String, Int) -> Boolean,
  onDeleteCascadeProfile: (String) -> Unit,
  onCascadeBackendModeChange: (NonRootCascadeBackendMode) -> Unit,
  onT2sConfigChange: (NonRootT2sConfig) -> Unit,
  onCascadeRouteChange: (List<NonRootCascadeRouteItem>) -> Unit,
  onTgWsConfigChange: (NonRootTgWsConfig) -> Unit,
  onTgWsPortChange: (Int) -> Boolean,
  onInstallOrUpdateTgWsPlugin: () -> Unit,
  onRefreshTgWsPlugin: () -> Unit,
  onImportVpsConfig: (VpsConfigResult, String, String?) -> Unit,
) {
  val context = LocalContext.current
  val nonRootLogsDir = remember(context) { NonRootRuntimeStore(context.applicationContext).logsDir.absolutePath }
  var tab by remember { mutableStateOf(Tab.HOME) }
  var showSettings by remember { mutableStateOf(false) }
  var programLogTarget by remember { mutableStateOf<ProgramLogTarget?>(null) }
  var cascadeProfileId by remember { mutableStateOf<String?>(null) }
  var showT2sSettings by remember { mutableStateOf(false) }
  var showTgWsSettings by remember { mutableStateOf(false) }
  var showVps by remember { mutableStateOf(false) }
  var vpsServerId by remember { mutableStateOf<String?>(null) }
  var vpsServiceKind by remember { mutableStateOf<VpsServiceKind?>(null) }
  var vpsProfileId by remember { mutableStateOf<String?>(null) }
  var pendingVpsImport by remember { mutableStateOf<Pair<VpsConfigResult, String>?>(null) }
  val compactBottomBar = rememberUseScrollableTabs() || rememberIsShortHeight()
  val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val topContentPadding = topInset + 78.dp
  val bottomContentPadding = bottomInset + if (compactBottomBar) 78.dp else 88.dp

  BackHandler(enabled = showSettings || cascadeProfileId != null || showT2sSettings || showTgWsSettings || showVps || tab != Tab.HOME) {
    when {
      showSettings -> showSettings = false
      cascadeProfileId != null -> cascadeProfileId = null
      showT2sSettings -> showT2sSettings = false
      showTgWsSettings -> showTgWsSettings = false
      vpsProfileId != null -> vpsProfileId = null
      vpsServiceKind != null -> vpsServiceKind = null
      vpsServerId != null -> vpsServerId = null
      showVps -> showVps = false
      else -> tab = Tab.HOME
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    val editedProfile = cascadeProfileId?.let { id -> cascadeState.profiles.firstOrNull { it.id == id } }
    val pageKey = when {
      editedProfile != null -> "profile:${editedProfile.id}"
      showT2sSettings -> "t2s"
      showTgWsSettings -> "tgws"
      showVps && vpsProfileId != null && vpsServerId != null && vpsServiceKind != null -> "vps-profile:${vpsServerId}:${vpsServiceKind!!.wireId}:${vpsProfileId}"
      showVps && vpsServiceKind != null && vpsServerId != null -> "vps-service:${vpsServerId}:${vpsServiceKind!!.wireId}"
      showVps && vpsServerId != null -> "vps-server:${vpsServerId}"
      showVps -> "vps"
      else -> "tab:${tab.name}"
    }
    AnimatedContent(
      targetState = pageKey,
      transitionSpec = {
        val forward = nonRootPageOrder(targetState) > nonRootPageOrder(initialState)
        val enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { width ->
          if (forward) width / 6 else -width / 6
        }
        val exit = fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { width ->
          if (forward) -width / 6 else width / 6
        }
        (enter togetherWith exit).using(SizeTransform(clip = false))
      },
      label = "nonRootPageTransition",
    ) { page ->
      when {
        page.startsWith("profile:") -> {
          val id = page.substringAfter("profile:")
          cascadeState.profiles.firstOrNull { it.id == id }?.let { profile ->
            NonRootCascadeProfileEditorScreen(
              topContentPadding = topContentPadding,
              bottomContentPadding = bottomInset + 16.dp,
              profile = profile,
              onUpdateProfile = onUpdateCascadeProfile,
              onAddServer = onAddCascadeServer,
              onUpdateServer = onUpdateCascadeServer,
              onMoveServer = onMoveCascadeServer,
              onDeleteServer = onDeleteCascadeServer,
              onServerPortChange = onCascadeServerPortChange,
              onServerAuxPortChange = onCascadeServerAuxPortChange,
              onPortChange = onCascadeProfilePortChange,
              onByeDpiPortChange = onCascadeProfileByeDpiPortChange,
            )
          }
        }
        page == "t2s" -> NonRootT2sSettingsScreen(
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomInset + 16.dp,
          state = cascadeState,
          onT2sConfigChange = onT2sConfigChange,
          onRouteChange = onCascadeRouteChange,
          onUpdateProfile = onUpdateCascadeProfile,
        )
        page == "tgws" -> NonRootTgWsSettingsScreen(
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomInset + 16.dp,
          config = tgWsConfig,
          pluginState = tgWsPluginState,
          runtimeState = tgWsRuntimeState,
          runtimeLastError = tgWsRuntimeLastError,
          onConfigChange = onTgWsConfigChange,
          onPortChange = onTgWsPortChange,
          onInstallOrUpdatePlugin = onInstallOrUpdateTgWsPlugin,
          onRefreshPlugin = onRefreshTgWsPlugin,
        )
        page == "vps" -> VpsServersScreen(
          viewModel = vpsViewModel,
          onOpenServer = { vpsServerId = it },
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomInset + 16.dp,
        )
        page.startsWith("vps-server:") -> VpsServerDetailsScreen(
          serverId = vpsServerId.orEmpty(),
          viewModel = vpsViewModel,
          onOpenService = { vpsServiceKind = it },
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomInset + 16.dp,
        )
        page.startsWith("vps-service:") -> VpsServiceScreen(
          serverId = vpsServerId.orEmpty(),
          kind = vpsServiceKind ?: VpsServiceKind.HYSTERIA2,
          viewModel = vpsViewModel,
          onOpenProfile = { vpsProfileId = it },
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomInset + 16.dp,
        )
        page.startsWith("vps-profile:") -> VpsProfileScreen(
          serverId = vpsServerId.orEmpty(),
          kind = vpsServiceKind ?: VpsServiceKind.HYSTERIA2,
          profileId = vpsProfileId.orEmpty(),
          viewModel = vpsViewModel,
          onNonRootImport = { server, profile, result ->
            pendingVpsImport = result to listOfNotNull(server?.name, profile?.name)
              .joinToString(" · ").ifBlank { result.fileName }
          },
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomInset + 16.dp,
        )
        page == "tab:HOME" -> NonRootHomeScreen(
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
          vpnState = vpnState,
          vpnLastError = vpnLastError,
          vpnLogs = vpnLogs,
          onVpnStart = onVpnStart,
          onVpnStop = onVpnStop,
          onRequestRootMode = onRequestRootMode,
        )
        page == "tab:STATS" -> NonRootStatsScreen(
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
          workMode = workMode,
          t2sApiPort = t2sApiPort,
          vpnState = vpnState,
        )
        page == "tab:APPS" -> NonRootToolsScreen(
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
          workMode = workMode,
          cascadeState = cascadeState,
          tgWsConfig = tgWsConfig,
          tgWsPluginState = tgWsPluginState,
          tgWsRuntimeState = tgWsRuntimeState,
          configurationEnabled = vpnState == NonRootVpnState.STOPPED || vpnState == NonRootVpnState.ERROR,
          onWorkModeChange = onWorkModeChange,
          onDirectSelectedProfileChange = onDirectSelectedProfileChange,
          onCreateCascadeProfile = onCreateCascadeProfile,
          onUpdateCascadeProfile = onUpdateCascadeProfile,
          onDeleteCascadeProfile = onDeleteCascadeProfile,
          onCascadeBackendModeChange = onCascadeBackendModeChange,
          onCascadeRouteChange = onCascadeRouteChange,
          onOpenCascadeProfile = { cascadeProfileId = it },
          onOpenT2sSettings = { showT2sSettings = true },
          onOpenTgWs = { showTgWsSettings = true },
          onInstallOrUpdateTgWsPlugin = onInstallOrUpdateTgWsPlugin,
          onOpenVps = { showVps = true },
          onTgWsEnabledChange = { onTgWsConfigChange(tgWsConfig.copy(enabled = it)) },
        )
        else -> Box(Modifier.fillMaxSize().padding(bottom = bottomContentPadding)) {
          SupportScreen(topContentPadding = topContentPadding)
        }
      }
    }

    NonRootTopBarCard(
      modifier = Modifier.align(Alignment.TopCenter),
      title = when {
        editedProfile != null -> stringResource(R.string.non_root_profile_settings)
        showT2sSettings -> stringResource(R.string.non_root_t2s_settings)
        showTgWsSettings -> stringResource(R.string.tgws_basic_title)
        showVps -> stringResource(R.string.vps_servers_title)
        else -> when (tab) {
          Tab.HOME -> stringResource(R.string.app_name)
          Tab.STATS -> stringResource(R.string.nav_stats)
          Tab.APPS -> stringResource(R.string.nav_programs)
          Tab.SUPPORT -> stringResource(R.string.nav_support)
        }
      },
      onBack = when {
        editedProfile != null -> ({ cascadeProfileId = null })
        showT2sSettings -> ({ showT2sSettings = false })
        showTgWsSettings -> ({ showTgWsSettings = false })
        vpsProfileId != null -> ({ vpsProfileId = null })
        vpsServiceKind != null -> ({ vpsServiceKind = null })
        vpsServerId != null -> ({ vpsServerId = null })
        showVps -> ({ showVps = false })
        else -> null
      },
      onOpenLogs = editedProfile?.let { profile ->
        {
          programLogTarget = ProgramLogTarget(
            programId = profile.toolId,
            profile = profile.id,
            title = "${nonRootToolTitle(profile.toolId)} — ${profile.name}",
            source = ProgramLogSource.Local(
              directoryPath = nonRootLogsDir,
              fileNameToken = profile.id,
            ),
          )
        }
      },
      onOpenSettings = { showSettings = true },
    )

    if (editedProfile == null && !showT2sSettings && !showTgWsSettings && !showVps) {
      NonRootBottomNavigationCard(
        modifier = Modifier.align(Alignment.BottomCenter),
        compact = compactBottomBar,
        tab = tab,
        onTabChange = { tab = it },
      )
    }
  }

  pendingVpsImport?.let { (result, sourceName) ->
    val toolId = when (result.kind) {
      VpsServiceKind.HYSTERIA2 -> NonRootCascadeProfile.TOOL_HYSTERIA2
      VpsServiceKind.WIREPROXY -> NonRootCascadeProfile.TOOL_WIREPROXY
      VpsServiceKind.XRAY -> NonRootCascadeProfile.TOOL_SING_BOX
      else -> null
    }
    val candidates = toolId?.let { wanted -> cascadeState.profiles.filter { it.toolId == wanted } }.orEmpty()
    AlertDialog(
      onDismissRequest = { pendingVpsImport = null },
      title = { Text(stringResource(R.string.non_root_vps_import_title)) },
      text = {
        if (toolId == null) {
          Text(stringResource(R.string.non_root_vps_import_unsupported))
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
              OutlinedButton(
                onClick = {
                  onImportVpsConfig(result, sourceName, null)
                  vpsViewModel.clearConfigResult()
                  pendingVpsImport = null
                },
                modifier = Modifier.fillMaxWidth(),
              ) { Text(stringResource(R.string.non_root_vps_import_new_profile)) }
            }
            if (candidates.isNotEmpty()) {
              item {
                Text(
                  stringResource(R.string.non_root_vps_import_existing),
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                )
              }
              itemsIndexed(candidates, key = { _, profile -> profile.id }) { _, profile ->
                OutlinedButton(
                  onClick = {
                    onImportVpsConfig(result, sourceName, profile.id)
                    vpsViewModel.clearConfigResult()
                    pendingVpsImport = null
                  },
                  modifier = Modifier.fillMaxWidth(),
                ) { Text("${nonRootToolTitle(profile.toolId)} — ${profile.name}") }
              }
            }
          }
        }
      },
      confirmButton = {},
      dismissButton = { TextButton(onClick = { pendingVpsImport = null }) { Text(stringResource(R.string.common_cancel)) } },
    )
  }

  programLogTarget?.let { target ->
    ProgramLogsBrowserSheet(
      target = target,
      onDismiss = { programLogTarget = null },
    )
  }

  if (showSettings) {
    SettingsScreen(
      onDismiss = { showSettings = false },
      loading = false,
    ) {
      NonRootSettingsContent(
        languageMode = languageMode,
        onLanguageModeChange = onLanguageModeChange,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        appRoutingMode = appRoutingMode,
        appRoutingPackages = appRoutingPackages,
        vpnState = vpnState,
        onAppRoutingModeChange = onAppRoutingModeChange,
        onAppRoutingPackagesChange = onAppRoutingPackagesChange,
        onRestartVpn = onRestartVpn,
      )
    }
  }
}

private fun nonRootPageOrder(page: String): Int = when {
  page == "tab:HOME" -> 0
  page == "tab:STATS" -> 10
  page == "tab:APPS" -> 20
  page == "t2s" -> 30
  page == "tgws" -> 30
  page == "vps" -> 30
  page.startsWith("vps-server:") -> 31
  page.startsWith("vps-service:") -> 32
  page.startsWith("vps-profile:") -> 33
  page.startsWith("profile:") -> 30
  page == "tab:SUPPORT" -> 40
  else -> 20
}

@Composable
private fun NonRootHomeScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  vpnState: NonRootVpnState,
  vpnLastError: String?,
  vpnLogs: List<NonRootRuntimeLogEntry>,
  onVpnStart: () -> Unit,
  onVpnStop: () -> Unit,
  onRequestRootMode: () -> Unit,
) {
  val screenPadding = rememberAdaptiveScreenPadding()
  val compact = rememberIsShortHeight()
  val busy = vpnState == NonRootVpnState.STARTING || vpnState == NonRootVpnState.STOPPING
  var showRootSwitchConfirm by remember { mutableStateOf(false) }
  val visualState = when (vpnState) {
    NonRootVpnState.RUNNING -> HomeServiceVisualState.RUNNING
    NonRootVpnState.STARTING -> HomeServiceVisualState.STARTING
    NonRootVpnState.STOPPING -> HomeServiceVisualState.STOPPING
    NonRootVpnState.STOPPED -> HomeServiceVisualState.STOPPED
    NonRootVpnState.ERROR -> HomeServiceVisualState.UNAVAILABLE
  }
  val accent = when (vpnState) {
    NonRootVpnState.RUNNING -> Color(0xFF20C96B)
    NonRootVpnState.STARTING -> MaterialTheme.colorScheme.secondary
    NonRootVpnState.STOPPING -> MaterialTheme.colorScheme.tertiary
    NonRootVpnState.ERROR -> MaterialTheme.colorScheme.error
    NonRootVpnState.STOPPED -> MaterialTheme.colorScheme.primary
  }
  val actionText = stringResource(
    when (vpnState) {
      NonRootVpnState.RUNNING -> R.string.home_action_stop_service
      NonRootVpnState.STARTING -> R.string.home_power_starting
      NonRootVpnState.STOPPING -> R.string.home_power_stopping
      NonRootVpnState.ERROR,
      NonRootVpnState.STOPPED -> R.string.home_action_start_service
    }
  )
  val logTail = remember(vpnLogs) {
    vpnLogs.joinToString("\n") { entry ->
      val time = DateFormat.format("HH:mm:ss", entry.timestampMillis)
      "[${entry.level.name}] $time · ${entry.message}"
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = screenPadding)
      .padding(
        top = topContentPadding + 8.dp,
        bottom = bottomContentPadding + 8.dp,
      ),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .animateContentSize(animationSpec = tween(220))
        .clickable(onClick = { showRootSwitchConfirm = true }),
      shape = RoundedCornerShape(if (compact) 22.dp else 28.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ) {
      Column(
        modifier = Modifier.padding(if (compact) 14.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(14.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Surface(
            modifier = Modifier.size(if (compact) 44.dp else 48.dp),
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.primary,
          ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(25.dp))
            }
          }
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
          ) {
            Text(
              text = stringResource(R.string.non_root_home_title),
              style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = stringResource(R.string.non_root_home_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
            )
            Text(
              text = stringResource(R.string.non_root_home_restrictions),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        AnimatedVisibility(visible = vpnState == NonRootVpnState.ERROR && !vpnLastError.isNullOrBlank()) {
          Text(
            text = vpnLastError.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }

    Spacer(Modifier.height(if (compact) 12.dp else 16.dp))

    AnimatedPowerDial(
      visualState = visualState,
      busy = busy,
      accent = accent,
      size = if (compact) 184.dp else 214.dp,
      enabled = !busy,
      contentDescription = actionText,
      onClick = {
        if (vpnState == NonRootVpnState.RUNNING) onVpnStop() else onVpnStart()
      },
    )

    Spacer(Modifier.height(if (compact) 10.dp else 12.dp))

    NonRootServiceStateCard(
      vpnState = vpnState,
      accent = accent,
      compact = compact,
    )

    Spacer(Modifier.height(if (compact) 12.dp else 16.dp))

    HomeLogsCard(
      logTail = logTail,
      detailedLogTail = "",
      compact = compact,
      shortHeight = compact,
      fillHeight = false,
      titleText = stringResource(R.string.logs_title),
      showSourceSelector = false,
    )
  }

  if (showRootSwitchConfirm) {
    AlertDialog(
      onDismissRequest = { showRootSwitchConfirm = false },
      title = { Text(stringResource(R.string.non_root_switch_root_title)) },
      text = { Text(stringResource(R.string.non_root_switch_root_body)) },
      confirmButton = {
        TextButton(
          onClick = {
            showRootSwitchConfirm = false
            onRequestRootMode()
          },
        ) { Text(stringResource(R.string.common_yes)) }
      },
      dismissButton = {
        TextButton(onClick = { showRootSwitchConfirm = false }) {
          Text(stringResource(R.string.common_cancel))
        }
      },
    )
  }
}

@Composable
private fun NonRootServiceStateCard(
  vpnState: NonRootVpnState,
  accent: Color,
  compact: Boolean,
) {
  val fullStateTexts = listOf(
    stringResource(R.string.non_root_service_state_running),
    stringResource(R.string.non_root_service_state_starting),
    stringResource(R.string.non_root_service_state_stopping),
    stringResource(R.string.non_root_service_state_stopped),
    stringResource(R.string.non_root_service_state_error),
  )
  val parsedStateTexts = fullStateTexts.map(::splitNonRootServiceStateText)
  val serviceLabel = parsedStateTexts.firstOrNull()?.first.orEmpty()
  val statusTexts = parsedStateTexts.map { it.second }
  val targetStatusText = when (vpnState) {
    NonRootVpnState.RUNNING -> statusTexts[0]
    NonRootVpnState.STARTING -> statusTexts[1]
    NonRootVpnState.STOPPING -> statusTexts[2]
    NonRootVpnState.STOPPED -> statusTexts[3]
    NonRootVpnState.ERROR -> statusTexts[4]
  }

  var displayedStatusText by remember(statusTexts) { mutableStateOf(targetStatusText) }
  var animationTargetText by remember(statusTexts) { mutableStateOf(targetStatusText) }

  LaunchedEffect(targetStatusText, statusTexts) {
    if (targetStatusText == animationTargetText) {
      displayedStatusText = targetStatusText
      return@LaunchedEffect
    }

    val previousText = displayedStatusText
    animationTargetText = targetStatusText
    val revealLength = targetStatusText.length.coerceAtLeast(1)
    displayedStatusText = previousText
    delay(52)
    for (revealedChars in 1..revealLength) {
      displayedStatusText = blendNonRootServiceStateText(
        previous = previousText,
        target = targetStatusText,
        revealedChars = revealedChars,
      )
      if (revealedChars < revealLength) delay(52)
    }
    displayedStatusText = targetStatusText
  }

  Surface(
    modifier = Modifier.fillMaxWidth(if (compact) 0.70f else 0.58f),
    shape = RoundedCornerShape(999.dp),
    color = accent.copy(alpha = 0.09f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = if (compact) 7.dp else 8.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (serviceLabel.isNotBlank()) {
        Text(
          text = serviceLabel,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = accent,
          maxLines = 1,
        )
        Spacer(Modifier.size(4.dp))
      }

      // Every localized status is measured here, but only the active one is visible.
      // This keeps both the label and the status origin fixed when the word changes.
      Box(contentAlignment = Alignment.CenterStart) {
        statusTexts.distinct().forEach { candidate ->
          Text(
            text = candidate,
            modifier = Modifier
              .alpha(0f)
              .clearAndSetSemantics { },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
          )
        }
        Text(
          text = displayedStatusText,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = accent,
          maxLines = 1,
        )
      }
    }
  }
}

private fun blendNonRootServiceStateText(
  previous: String,
  target: String,
  revealedChars: Int,
): String {
  if (target.isEmpty()) return ""
  val revealed = revealedChars.coerceIn(0, target.length)
  if (revealed == target.length) return target

  val progress = revealed.toFloat() / target.length.toFloat()
  val easedProgress = progress * progress * (3f - 2f * progress)
  val previousCut = (previous.length * easedProgress)
    .roundToInt()
    .coerceIn(0, previous.length)
  return target.take(revealed) + previous.drop(previousCut)
}

private fun splitNonRootServiceStateText(text: String): Pair<String, String> {
  val separatorIndex = text.indexOfFirst { it == ':' || it == '：' }
  if (separatorIndex < 0) return "" to text.trim()
  val label = text.substring(0, separatorIndex + 1).trim()
  val state = text.substring(separatorIndex + 1).trim()
  return label to state
}

@Composable
private fun NonRootToolsScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  workMode: NonRootWorkMode,
  cascadeState: NonRootCascadeState,
  tgWsConfig: NonRootTgWsConfig,
  tgWsPluginState: TgWsPluginState,
  tgWsRuntimeState: NonRootTgWsRuntimeState,
  configurationEnabled: Boolean,
  onWorkModeChange: (NonRootWorkMode) -> Unit,
  onDirectSelectedProfileChange: (String?) -> Unit,
  onCreateCascadeProfile: (String, String) -> Unit,
  onUpdateCascadeProfile: (NonRootCascadeProfile) -> Unit,
  onDeleteCascadeProfile: (String) -> Unit,
  onCascadeBackendModeChange: (NonRootCascadeBackendMode) -> Unit,
  onCascadeRouteChange: (List<NonRootCascadeRouteItem>) -> Unit,
  onOpenCascadeProfile: (String) -> Unit,
  onOpenT2sSettings: () -> Unit,
  onOpenTgWs: () -> Unit,
  onInstallOrUpdateTgWsPlugin: () -> Unit,
  onOpenVps: () -> Unit,
  onTgWsEnabledChange: (Boolean) -> Unit,
) {
  val screenPadding = rememberAdaptiveScreenPadding()
  val byId = remember(cascadeState.profiles) { cascadeState.profiles.associateBy { it.id } }
  var showCreateDialog by remember { mutableStateOf(false) }
  var deleteProfileId by remember { mutableStateOf<String?>(null) }
  var cascadeModeCardVisible by remember { mutableStateOf(workMode == NonRootWorkMode.CASCADE) }
  var t2sSettingsButtonVisible by remember { mutableStateOf(workMode == NonRootWorkMode.CASCADE) }
  var lastAnimatedWorkMode by remember { mutableStateOf(workMode) }

  LaunchedEffect(workMode) {
    if (workMode != lastAnimatedWorkMode) {
      lastAnimatedWorkMode = workMode
      if (workMode == NonRootWorkMode.CASCADE) {
        t2sSettingsButtonVisible = false
        cascadeModeCardVisible = true
        delay(250)
        t2sSettingsButtonVisible = true
      } else {
        t2sSettingsButtonVisible = false
        delay(150)
        cascadeModeCardVisible = false
      }
    }
  }

  val visibleRoute = remember(cascadeState.route, workMode) {
    cascadeState.route.withIndex().filter { indexed ->
      when (indexed.value.type) {
        NonRootCascadeRouteItemType.PROFILE,
        NonRootCascadeRouteItemType.GROUP -> true
        NonRootCascadeRouteItemType.DIRECT_START,
        NonRootCascadeRouteItemType.DIRECT_BLOCK -> workMode == NonRootWorkMode.CASCADE
      }
    }
  }

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
      NonRootModeCard(
        workMode = workMode,
        enabled = configurationEnabled,
        onWorkModeChange = onWorkModeChange,
      )
    }

    item {
      AnimatedVisibility(
        visible = cascadeModeCardVisible,
        enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(140)),
      ) {
        NonRootT2sModeCard(
          mode = cascadeState.backendMode,
          enabled = configurationEnabled,
          onModeChange = onCascadeBackendModeChange,
        )
      }
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = { showCreateDialog = true },
          modifier = Modifier.weight(1f),
          enabled = configurationEnabled,
        ) {
          Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.size(6.dp))
          Text(stringResource(R.string.non_root_create_profile))
        }
        AnimatedVisibility(
          visible = t2sSettingsButtonVisible,
          enter = expandHorizontally(expandFrom = Alignment.End, animationSpec = tween(220)) + fadeIn(tween(160)),
          exit = shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(180)) + fadeOut(tween(120)),
        ) {
          Button(
            onClick = onOpenT2sSettings,
            enabled = configurationEnabled,
          ) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.non_root_t2s_advanced_settings))
          }
        }
      }
    }

    if (cascadeState.profiles.isEmpty()) {
      item {
        Surface(
          modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(180)),
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        ) {
          Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.non_root_profiles_empty_title), fontWeight = FontWeight.Bold)
            Text(
              stringResource(R.string.non_root_profiles_empty_body),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    } else {
      itemsIndexed(
        items = visibleRoute,
        key = { _, indexed -> nonRootToolsRouteKey(indexed.value, indexed.index) },
      ) { _, indexed ->
        val routeIndex = indexed.index
        val item = indexed.value
        when (item.type) {
          NonRootCascadeRouteItemType.PROFILE -> byId[item.profileId]?.let { profile ->
            val checked = if (workMode == NonRootWorkMode.DIRECT) {
              cascadeState.directSelectedProfileId == profile.id
            } else {
              profile.enabled
            }
            NonRootToolsProfileCard(
              modifier = Modifier.animateItem(),
              profile = profile,
              checked = checked,
              enabled = configurationEnabled,
              switchEnabled = configurationEnabled && (workMode != NonRootWorkMode.DIRECT || profile.directEligible),
              directBlocked = workMode == NonRootWorkMode.DIRECT && !profile.directEligible,
              onCheckedChange = { selected ->
                if (workMode == NonRootWorkMode.DIRECT) {
                  if (profile.directEligible) onDirectSelectedProfileChange(profile.id.takeIf { selected })
                } else {
                  onUpdateCascadeProfile(profile.copy(enabled = selected))
                }
              },
              onOpen = { onOpenCascadeProfile(profile.id) },
              onDelete = { deleteProfileId = profile.id },
              onMove = { current, direction ->
                val moved = moveToolsRouteItem(cascadeState.route, current, direction)
                if (moved == null) null else {
                  onCascadeRouteChange(moved.first)
                  moved.second
                }
              },
              routeIndex = routeIndex,
            )
          }
          NonRootCascadeRouteItemType.GROUP -> NonRootToolsGroupRow(
            modifier = Modifier.animateItem(),
            item = item,
            routeIndex = routeIndex,
            enabled = configurationEnabled,
            onMove = { current, direction ->
              val moved = moveToolsRouteItem(cascadeState.route, current, direction)
              if (moved == null) null else {
                onCascadeRouteChange(moved.first)
                moved.second
              }
            },
          )
          NonRootCascadeRouteItemType.DIRECT_START -> NonRootToolsServiceRow(
            modifier = Modifier.animateItem(),
            title = stringResource(R.string.non_root_t2s_direct_short),
          )
          NonRootCascadeRouteItemType.DIRECT_BLOCK -> NonRootToolsServiceRow(
            modifier = Modifier.animateItem(),
            title = stringResource(R.string.non_root_t2s_block_direct_short),
          )
        }
      }
    }

    item {
      Spacer(Modifier.height(2.dp))
      Text(
        text = stringResource(R.string.non_root_additional_tools),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )
    }
    item {
      val pluginActionLabel = when {
        tgWsPluginState.downloading -> stringResource(
          R.string.prog_update_status_downloading_pct_fmt,
          tgWsPluginState.progressPercent.coerceIn(0, 100),
        )
        tgWsPluginState.installing -> stringResource(R.string.common_installing)
        !tgWsPluginState.installed && !tgWsPluginState.signatureMismatch -> stringResource(R.string.common_install)
        tgWsPluginState.updateAvailable -> stringResource(R.string.common_update)
        else -> null
      }
      NonRootStandaloneToolCard(
        icon = { Icon(Icons.Filled.Send, contentDescription = null) },
        title = stringResource(R.string.non_root_tgws_title),
        subtitle = if (tgWsPluginState.installed) {
          val runtimeText = when (tgWsRuntimeState) {
            NonRootTgWsRuntimeState.STOPPED -> stringResource(R.string.non_root_service_state_stopped)
            NonRootTgWsRuntimeState.STARTING -> stringResource(R.string.non_root_service_state_starting)
            NonRootTgWsRuntimeState.RUNNING -> stringResource(R.string.non_root_service_state_running)
            NonRootTgWsRuntimeState.ERROR -> stringResource(R.string.non_root_service_state_error)
          }
          "${NonRootPortRegistry.LOOPBACK}:${tgWsConfig.port} · ${tgWsPluginState.installedVersionName.ifBlank { "?" }} · $runtimeText"
        } else {
          stringResource(R.string.non_root_tgws_plugin_not_installed)
        },
        checked = tgWsConfig.enabled && tgWsPluginState.installed,
        openEnabled = true,
        switchEnabled = configurationEnabled && tgWsPluginState.installed && !tgWsPluginState.busy,
        onCheckedChange = onTgWsEnabledChange,
        onOpen = onOpenTgWs,
        actionLabel = pluginActionLabel,
        actionEnabled = !tgWsPluginState.busy && configurationEnabled && !tgWsConfig.enabled && !tgWsPluginState.signatureMismatch,
        onAction = onInstallOrUpdateTgWsPlugin,
        progress = if (tgWsPluginState.downloading) {
          tgWsPluginState.progressPercent.coerceIn(0, 100) / 100f
        } else {
          null
        },
      )
    }
    item {
      NonRootStandaloneToolCard(
        icon = { Icon(Icons.Filled.Cloud, contentDescription = null) },
        title = stringResource(R.string.vps_servers_title),
        subtitle = stringResource(R.string.non_root_vps_desc),
        checked = null,
        openEnabled = true,
        switchEnabled = false,
        onCheckedChange = {},
        onOpen = onOpenVps,
      )
    }
  }

  if (showCreateDialog) {
    NonRootCreateProfileDialog(
      onDismiss = { showCreateDialog = false },
      onCreate = { name, toolId ->
        onCreateCascadeProfile(name, toolId)
        showCreateDialog = false
      },
    )
  }

  deleteProfileId?.let { id ->
    val profile = cascadeState.profiles.firstOrNull { it.id == id }
    if (profile != null) {
      AlertDialog(
        onDismissRequest = { deleteProfileId = null },
        title = { Text(stringResource(R.string.non_root_delete_profile_title)) },
        text = { Text(stringResource(R.string.non_root_delete_profile_body, profile.name)) },
        confirmButton = {
          TextButton(onClick = {
            onDeleteCascadeProfile(profile.id)
            deleteProfileId = null
          }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
          TextButton(onClick = { deleteProfileId = null }) { Text(stringResource(R.string.common_cancel)) }
        },
      )
    }
  }
}

@Composable
private fun NonRootStandaloneToolCard(
  icon: @Composable () -> Unit,
  title: String,
  subtitle: String,
  checked: Boolean?,
  openEnabled: Boolean,
  switchEnabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  onOpen: () -> Unit,
  actionLabel: String? = null,
  actionEnabled: Boolean = false,
  onAction: (() -> Unit)? = null,
  progress: Float? = null,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(enabled = openEnabled, onClick = onOpen),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
      ) {
        Surface(
          modifier = Modifier.size(44.dp),
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
          contentColor = MaterialTheme.colorScheme.primary,
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        checked?.let { Switch(checked = it, enabled = switchEnabled, onCheckedChange = onCheckedChange) }
      }
      progress?.let { value ->
        LinearProgressIndicator(
          progress = { value.coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth(),
        )
      }
      if (actionLabel != null && onAction != null) {
        Button(
          onClick = onAction,
          enabled = actionEnabled,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(actionLabel)
        }
      }
    }
  }
}

@Composable
private fun NonRootModeCard(
  workMode: NonRootWorkMode,
  enabled: Boolean,
  onWorkModeChange: (NonRootWorkMode) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)),
    shape = RoundedCornerShape(22.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
    tonalElevation = 1.dp,
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
      ) {
        Surface(
          modifier = Modifier.size(42.dp),
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(23.dp))
          }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.non_root_work_mode_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          AnimatedContent(
            targetState = workMode,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "nonRootModeDescription",
          ) { mode ->
            Text(
              text = stringResource(
                if (mode == NonRootWorkMode.DIRECT) R.string.non_root_mode_direct_desc
                else R.string.non_root_mode_cascade_desc,
              ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
      ) {
        Row(
          modifier = Modifier.padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          NonRootModeChoice(
            modifier = Modifier.weight(1f),
            selected = workMode == NonRootWorkMode.DIRECT,
            enabled = enabled,
            title = stringResource(R.string.non_root_mode_direct),
            onClick = { onWorkModeChange(NonRootWorkMode.DIRECT) },
          )
          NonRootModeChoice(
            modifier = Modifier.weight(1f),
            selected = workMode == NonRootWorkMode.CASCADE,
            enabled = enabled,
            title = stringResource(R.string.non_root_mode_cascade),
            onClick = { onWorkModeChange(NonRootWorkMode.CASCADE) },
          )
        }
      }
    }
  }
}

@Composable
private fun NonRootT2sModeCard(
  mode: NonRootCascadeBackendMode,
  enabled: Boolean,
  onModeChange: (NonRootCascadeBackendMode) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(200)),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(Icons.Filled.Equalizer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
          stringResource(R.string.non_root_t2s_backend_mode),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NonRootModeChoice(
          modifier = Modifier.weight(1f),
          selected = mode == NonRootCascadeBackendMode.BALANCE,
          enabled = enabled,
          title = stringResource(R.string.myproxy_backend_mode_balance),
          onClick = { onModeChange(NonRootCascadeBackendMode.BALANCE) },
        )
        NonRootModeChoice(
          modifier = Modifier.weight(1f),
          selected = mode == NonRootCascadeBackendMode.PRIORITY,
          enabled = enabled,
          title = stringResource(R.string.myproxy_backend_mode_priority),
          onClick = { onModeChange(NonRootCascadeBackendMode.PRIORITY) },
        )
      }
      AnimatedContent(
        targetState = mode,
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
        label = "nonRootT2sModeDescription",
      ) { selected ->
        Text(
          text = stringResource(
            if (selected == NonRootCascadeBackendMode.PRIORITY) R.string.myproxy_backend_mode_priority_desc
            else R.string.myproxy_backend_mode_balance_desc,
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun NonRootModeChoice(
  modifier: Modifier,
  selected: Boolean,
  enabled: Boolean,
  title: String,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(13.dp)
  val background by animateColorAsState(
    targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    animationSpec = tween(180),
    label = "nonRootModeChoiceBackground",
  )
  val foreground by animateColorAsState(
    targetValue = (if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
      .copy(alpha = if (enabled) 1f else 0.55f),
    animationSpec = tween(180),
    label = "nonRootModeChoiceForeground",
  )
  val borderColor by animateColorAsState(
    targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f) else Color.Transparent,
    animationSpec = tween(180),
    label = "nonRootModeChoiceBorder",
  )
  Surface(
    modifier = modifier
      .clip(shape)
      .clickable(enabled = enabled, onClick = onClick),
    shape = shape,
    color = background,
    contentColor = foreground,
    border = BorderStroke(1.dp, borderColor),
  ) {
    Box(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun NonRootToolsProfileCard(
  modifier: Modifier = Modifier,
  profile: NonRootCascadeProfile,
  checked: Boolean,
  enabled: Boolean,
  switchEnabled: Boolean,
  directBlocked: Boolean,
  routeIndex: Int,
  onCheckedChange: (Boolean) -> Unit,
  onOpen: () -> Unit,
  onDelete: () -> Unit,
  onMove: (Int, Int) -> Int?,
) {
  val borderColor by animateColorAsState(
    targetValue = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
    animationSpec = tween(180),
    label = "nonRootProfileBorder",
  )
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, borderColor),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      NonRootToolsDragHandle(
        stableKey = profile.id,
        routeIndex = routeIndex,
        enabled = enabled,
        onMove = onMove,
      )
      Surface(
        modifier = Modifier.size(44.dp).clickable(enabled = enabled, onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.primary,
      ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val icon = programIconRes(profile.toolId)
          if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(25.dp))
          else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(23.dp))
        }
      }
      Column(
        modifier = Modifier.weight(1f).clickable(enabled = enabled, onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = stringResource(R.string.non_root_profile_title_fmt, nonRootToolTitle(profile.toolId), profile.name),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = when {
            directBlocked -> stringResource(R.string.non_root_direct_multi_server_disabled)
            profile.toolId == NonRootCascadeProfile.TOOL_OPERA_PROXY -> "${NonRootPortRegistry.LOOPBACK}:${profile.port}"
            profile.serverCount == 1 -> profile.servers.singleOrNull()?.let { "${NonRootPortRegistry.LOOPBACK}:${it.port}" }.orEmpty()
            else -> stringResource(R.string.non_root_server_count_fmt, profile.serverCount)
          },
          style = MaterialTheme.typography.bodySmall,
          color = if (directBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = checked,
        enabled = switchEnabled,
        onCheckedChange = onCheckedChange,
      )
      IconButton(onClick = onDelete, enabled = enabled) {
        Icon(
          Icons.Filled.Delete,
          contentDescription = stringResource(R.string.action_delete),
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun NonRootToolsGroupRow(
  modifier: Modifier = Modifier,
  item: NonRootCascadeRouteItem,
  routeIndex: Int,
  enabled: Boolean,
  onMove: (Int, Int) -> Int?,
) {
  val title = item.name.ifBlank { stringResource(R.string.non_root_t2s_group_unnamed) }
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(15.dp),
    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      NonRootToolsDragHandle(
        stableKey = item.markerId,
        routeIndex = routeIndex,
        enabled = enabled,
        onMove = onMove,
      )
      Box(Modifier.weight(1f).height(1.dp)) {
        Surface(Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)) {}
      }
      Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Box(Modifier.weight(1f).height(1.dp)) {
        Surface(Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)) {}
      }
    }
  }
}

@Composable
private fun NonRootToolsServiceRow(
  modifier: Modifier = Modifier,
  title: String,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f),
  ) {
    Text(
      text = title,
      modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun NonRootToolsDragHandle(
  stableKey: String,
  routeIndex: Int,
  enabled: Boolean,
  onMove: (Int, Int) -> Int?,
) {
  val latestOnMove by rememberUpdatedState(onMove)
  var gestureIndex by remember(stableKey) { mutableIntStateOf(routeIndex) }
  var dragTotal by remember(stableKey) { mutableFloatStateOf(0f) }
  Icon(
    imageVector = Icons.Filled.DragHandle,
    contentDescription = stringResource(R.string.non_root_t2s_drag_handle),
    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
    modifier = Modifier
      .size(32.dp)
      .pointerInput(stableKey, enabled) {
        if (!enabled) return@pointerInput
        detectDragGesturesAfterLongPress(
          onDragStart = {
            gestureIndex = routeIndex
            dragTotal = 0f
          },
          onDragCancel = { dragTotal = 0f },
          onDragEnd = { dragTotal = 0f },
          onDrag = { change, amount ->
            change.consume()
            dragTotal += amount.y
            if (abs(dragTotal) >= 46.dp.toPx()) {
              val direction = if (dragTotal > 0f) 1 else -1
              latestOnMove(gestureIndex, direction)?.let { gestureIndex = it }
              dragTotal = 0f
            }
          },
        )
      },
  )
}

private fun nonRootToolsRouteKey(item: NonRootCascadeRouteItem, index: Int): String = when (item.type) {
  NonRootCascadeRouteItemType.PROFILE -> "profile:${item.profileId}"
  NonRootCascadeRouteItemType.GROUP -> "group:${item.markerId.ifBlank { index.toString() }}"
  NonRootCascadeRouteItemType.DIRECT_START -> "direct-start:${item.markerId.ifBlank { "single" }}"
  NonRootCascadeRouteItemType.DIRECT_BLOCK -> "direct-block:${item.markerId.ifBlank { "single" }}"
}

private fun moveToolsRouteItem(
  route: List<NonRootCascadeRouteItem>,
  from: Int,
  direction: Int,
): Pair<List<NonRootCascadeRouteItem>, Int>? {
  if (from !in route.indices || direction == 0) return null
  val movable = route.indices.filter { index ->
    route[index].type == NonRootCascadeRouteItemType.PROFILE || route[index].type == NonRootCascadeRouteItemType.GROUP
  }
  val position = movable.indexOf(from)
  if (position < 0) return null
  val targetPosition = position + if (direction > 0) 1 else -1
  if (targetPosition !in movable.indices) return null
  val target = movable[targetPosition]
  val updated = route.toMutableList()
  val item = updated.removeAt(from)
  updated.add(target, item)
  if (!isValidToolsRoute(updated)) return null
  return updated to target
}

private fun isValidToolsRoute(route: List<NonRootCascadeRouteItem>): Boolean {
  route.forEachIndexed { index, item ->
    if (item.type == NonRootCascadeRouteItemType.GROUP) {
      if (index == 0 || index == route.lastIndex) return false
      if (route[index - 1].type != NonRootCascadeRouteItemType.PROFILE) return false
      if (route[index + 1].type != NonRootCascadeRouteItemType.PROFILE) return false
    }
  }
  return true
}

@Composable
internal fun NonRootByeDpiCard(
  port: Int,
  config: NonRootDirectOperaConfig,
  onPortChange: (Int) -> Boolean,
  onConfigChange: (NonRootDirectOperaConfig) -> Unit,
) {
  var portText by remember(port) { mutableStateOf(port.toString()) }
  var portError by remember(port) { mutableStateOf(false) }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
          modifier = Modifier.size(46.dp),
          shape = RoundedCornerShape(15.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val iconRes = programIconRes("byedpi")
            if (iconRes != null) Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(26.dp))
            else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(23.dp))
          }
        }
        Text(
          text = stringResource(R.string.tab_byedpi),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }

      OutlinedTextField(
        value = portText,
        onValueChange = { raw ->
          if (raw.length <= 5 && raw.all(Char::isDigit)) {
            portText = raw
            val parsed = raw.toIntOrNull()
            portError = when {
              parsed == null -> raw.isNotEmpty()
              parsed !in NonRootPortRegistry.MIN_PORT..NonRootPortRegistry.MAX_PORT -> true
              else -> !onPortChange(parsed)
            }
          }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.common_port)) },
        prefix = { Text("${NonRootPortRegistry.LOOPBACK}:") },
        supportingText = {
          Text(if (portError) stringResource(R.string.non_root_port_error) else stringResource(R.string.non_root_loopback_port_hint))
        },
        isError = portError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )

      OutlinedTextField(
        value = config.byedpiStartArgs,
        onValueChange = { onConfigChange(config.copy(byedpiStartArgs = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.byedpi_start_args_title)) },
        supportingText = { Text(stringResource(R.string.byedpi_start_args_desc)) },
        minLines = 2,
        maxLines = 5,
      )
      OutlinedTextField(
        value = config.byedpiRestartArgs,
        onValueChange = { onConfigChange(config.copy(byedpiRestartArgs = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.byedpi_restart_args_title)) },
        supportingText = { Text(stringResource(R.string.byedpi_restart_args_desc)) },
        minLines = 2,
        maxLines = 5,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.non_root_byedpi_restart_after_opera),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = stringResource(R.string.non_root_byedpi_restart_after_opera_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = config.restartByedpiAfterOpera,
          onCheckedChange = { onConfigChange(config.copy(restartByedpiAfterOpera = it)) },
        )
      }
    }
  }
}

@Composable
internal fun NonRootOperaProxyCard(
  port: Int,
  config: NonRootDirectOperaConfig,
  onPortChange: (Int) -> Boolean,
  onConfigChange: (NonRootDirectOperaConfig) -> Unit,
  descriptionRes: Int = R.string.non_root_direct_opera_desc,
) {
  var portText by remember(port) { mutableStateOf(port.toString()) }
  var portError by remember(port) { mutableStateOf(false) }
  var advancedExpanded by remember { mutableStateOf(false) }
  var apiProxyImportFailed by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val apiProxyFilePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    apiProxyImportFailed = false
    val importDir = File(context.filesDir, "nonroot/configs/api_proxy_imports").apply { mkdirs() }
    val target = File(importDir, "api_proxy_${System.currentTimeMillis()}.txt")
    runCatching {
      val input = context.contentResolver.openInputStream(uri)
        ?: error("Cannot open selected API proxy list")
      input.use { source ->
        target.outputStream().use { output ->
          val buffer = ByteArray(8192)
          var total = 0
          while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > 1024 * 1024) error("API proxy list is too large")
            output.write(buffer, 0, read)
          }
        }
      }
      if (target.length() <= 0L) error("API proxy list is empty")
      val previous = config.apiProxy.trim()
      if (previous.startsWith(importDir.absolutePath + File.separator)) {
        runCatching { File(previous).delete() }
      }
      onConfigChange(config.copy(apiProxy = target.absolutePath))
    }.onFailure {
      target.delete()
      apiProxyImportFailed = true
    }
  }

  Surface(
    modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
          modifier = Modifier.size(48.dp),
          shape = RoundedCornerShape(15.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
          contentColor = MaterialTheme.colorScheme.primary,
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val iconRes = programIconRes("operaproxy")
            if (iconRes != null) Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(27.dp))
            else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(24.dp))
          }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            text = stringResource(R.string.opera_proxy_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
      ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = stringResource(R.string.non_root_opera_server_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(R.string.non_root_opera_server_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("EU" to R.string.region_europe, "AS" to R.string.region_asia, "AM" to R.string.region_america).forEach { (code, label) ->
              NonRootSmallChoice(
                modifier = Modifier.weight(1f),
                selected = config.serverRegion == code,
                label = stringResource(label),
                onClick = { onConfigChange(config.copy(serverRegion = code)) },
              )
            }
          }
          OutlinedTextField(
            value = config.serverSni,
            onValueChange = { onConfigChange(config.copy(serverSni = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("SNI") },
            placeholder = { Text(stringResource(R.string.operaproxy_sni_placeholder)) },
            singleLine = true,
          )
          OutlinedTextField(
            value = config.overrideProxyAddress,
            onValueChange = { onConfigChange(config.copy(overrideProxyAddress = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.operaproxy_sni_server_address)) },
            placeholder = { Text(stringResource(R.string.operaproxy_sni_server_address_placeholder)) },
            supportingText = { Text(stringResource(R.string.operaproxy_sni_server_address_hint)) },
            singleLine = true,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(stringResource(R.string.operaproxy_sni_use_byedpi), fontWeight = FontWeight.SemiBold)
              Text(
                stringResource(R.string.operaproxy_sni_use_byedpi_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = config.useByedpi,
              onCheckedChange = { onConfigChange(config.copy(useByedpi = it)) },
            )
          }
        }
      }

      OutlinedTextField(
        value = portText,
        onValueChange = { raw ->
          if (raw.length <= 5 && raw.all(Char::isDigit)) {
            portText = raw
            val parsed = raw.toIntOrNull()
            portError = when {
              parsed == null -> raw.isNotEmpty()
              parsed !in NonRootPortRegistry.MIN_PORT..NonRootPortRegistry.MAX_PORT -> true
              else -> !onPortChange(parsed)
            }
          }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.common_port)) },
        prefix = { Text("${NonRootPortRegistry.LOOPBACK}:") },
        supportingText = {
          Text(if (portError) stringResource(R.string.non_root_port_error) else stringResource(R.string.non_root_loopback_port_hint))
        },
        isError = portError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )

      TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.settings_advanced_title))
      }
      AnimatedVisibility(
        visible = advancedExpanded,
        enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(160)),
        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
      ) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
        ) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = config.apiProxy,
              onValueChange = {
                apiProxyImportFailed = false
                onConfigChange(config.copy(apiProxy = it))
              },
              modifier = Modifier.fillMaxWidth(),
              label = { Text("-api-proxy") },
              supportingText = {
                Text(
                  if (apiProxyImportFailed) {
                    stringResource(R.string.non_root_opera_api_proxy_import_failed)
                  } else {
                    stringResource(R.string.opera_args_api_proxy_hint)
                  }
                )
              },
              isError = apiProxyImportFailed,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedButton(
              onClick = { apiProxyFilePicker.launch(arrayOf("text/*", "application/octet-stream")) },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(stringResource(R.string.non_root_opera_api_proxy_choose_file))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              NonRootSmallChoice(
                modifier = Modifier.weight(1f),
                selected = config.serverSelection == "fastest",
                label = "fastest",
                onClick = { onConfigChange(config.copy(serverSelection = "fastest")) },
              )
              NonRootSmallChoice(
                modifier = Modifier.weight(1f),
                selected = config.serverSelection == "random",
                label = "random",
                onClick = { onConfigChange(config.copy(serverSelection = "random")) },
              )
            }
            OutlinedTextField(
              value = config.serverSelectionDlLimit,
              onValueChange = { onConfigChange(config.copy(serverSelectionDlLimit = it)) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text("-server-selection-dl-limit") },
              supportingText = { Text(stringResource(R.string.opera_args_dl_limit_hint)) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
              value = config.serverSelectionTestUrl,
              onValueChange = { onConfigChange(config.copy(serverSelectionTestUrl = it)) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text("-server-selection-test-url") },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
              value = config.initRetryInterval,
              onValueChange = { onConfigChange(config.copy(initRetryInterval = it)) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text("-init-retry-interval") },
              supportingText = { Text(stringResource(R.string.opera_args_init_retry_hint)) },
              singleLine = true,
            )
            OutlinedTextField(
              value = config.verbosity,
              onValueChange = { onConfigChange(config.copy(verbosity = it)) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text("-verbosity") },
              supportingText = { Text(stringResource(R.string.opera_args_verbosity_hint)) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
              value = config.apiUserAgent,
              onValueChange = { onConfigChange(config.copy(apiUserAgent = it)) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.opera_args_ua_title)) },
              supportingText = { Text(stringResource(R.string.opera_args_ua_desc)) },
              singleLine = true,
            )
            OutlinedTextField(
              value = config.bootstrapDns.joinToString("\n"),
              onValueChange = { raw -> onConfigChange(config.copy(bootstrapDns = raw.lines().map { it.trim() })) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.opera_args_bootstrap_dns_title)) },
              supportingText = { Text(stringResource(R.string.opera_args_bootstrap_dns_desc)) },
              minLines = 2,
              maxLines = 5,
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun NonRootSmallChoice(
  modifier: Modifier,
  selected: Boolean,
  label: String,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(14.dp)
  Surface(
    modifier = modifier.clip(shape).clickable(onClick = onClick),
    shape = shape,
    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(
      1.dp,
      if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
      else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
    ),
  ) {
    Text(
      text = label,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun NonRootCascadeIntroCard() {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Text(
        text = stringResource(R.string.non_root_mode_cascade),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.non_root_cascade_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun NonRootTopBarCard(
  modifier: Modifier = Modifier,
  title: String,
  onBack: (() -> Unit)? = null,
  onOpenLogs: (() -> Unit)? = null,
  onOpenSettings: () -> Unit,
) {
  val shape = RoundedCornerShape(24.dp)
  val startPadding by animateDpAsState(
    targetValue = if (onBack == null) 16.dp else 6.dp,
    animationSpec = tween(180),
    label = "nonRootTopBarStartPadding",
  )
  Box(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(horizontal = 12.dp)
      .padding(top = 8.dp),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .height(58.dp)
        .clip(shape),
      shape = shape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(start = startPadding, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AnimatedVisibility(
          visible = onBack != null,
          enter = expandHorizontally(expandFrom = Alignment.Start, animationSpec = tween(180)) + fadeIn(tween(140)),
          exit = shrinkHorizontally(shrinkTowards = Alignment.Start, animationSpec = tween(160)) + fadeOut(tween(100)),
        ) {
          IconButton(onClick = { onBack?.invoke() }, modifier = Modifier.size(46.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
          }
        }
        AnimatedContent(
          targetState = title,
          modifier = Modifier.weight(1f),
          transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
          label = "nonRootTopBarTitle",
        ) { value ->
          Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        AnimatedVisibility(
          visible = onOpenLogs != null,
          enter = expandHorizontally(expandFrom = Alignment.End, animationSpec = tween(210)) + fadeIn(tween(150)),
          exit = shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(180)) + fadeOut(tween(120)),
        ) {
          IconButton(onClick = { onOpenLogs?.invoke() }, modifier = Modifier.size(46.dp)) {
            Icon(
              imageVector = Icons.Filled.BugReport,
              contentDescription = stringResource(R.string.cd_logs),
              tint = MaterialTheme.colorScheme.error,
            )
          }
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(46.dp)) {
          Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.settings_title),
          )
        }
      }
    }
  }
}

@Composable
private fun NonRootBottomNavigationCard(
  modifier: Modifier = Modifier,
  compact: Boolean,
  tab: Tab,
  onTabChange: (Tab) -> Unit,
) {
  val shape = RoundedCornerShape(24.dp)
  val homeLabel = stringResource(R.string.nav_home)
  val statsLabel = stringResource(R.string.nav_stats)
  val toolsLabel = stringResource(R.string.nav_programs)
  val supportLabel = stringResource(R.string.nav_support)

  Box(
    modifier = modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(horizontal = 12.dp)
      .padding(bottom = 8.dp),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .height(if (compact) 58.dp else 68.dp)
        .clip(shape),
      shape = shape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        NonRootBottomNavItem(tab == Tab.HOME, { onTabChange(Tab.HOME) }, compact, homeLabel) {
          Icon(Icons.Filled.Power, contentDescription = homeLabel, modifier = Modifier.size(22.dp))
        }
        NonRootBottomNavItem(tab == Tab.STATS, { onTabChange(Tab.STATS) }, compact, statsLabel) {
          Icon(Icons.Filled.Equalizer, contentDescription = statsLabel, modifier = Modifier.size(22.dp))
        }
        NonRootBottomNavItem(tab == Tab.APPS, { onTabChange(Tab.APPS) }, compact, toolsLabel) {
          Icon(Icons.Filled.Apps, contentDescription = toolsLabel, modifier = Modifier.size(22.dp))
        }
        NonRootBottomNavItem(tab == Tab.SUPPORT, { onTabChange(Tab.SUPPORT) }, compact, supportLabel) {
          Icon(Icons.Filled.Info, contentDescription = supportLabel, modifier = Modifier.size(22.dp))
        }
      }
    }
  }
}

@Composable
private fun RowScope.NonRootBottomNavItem(
  selected: Boolean,
  onClick: () -> Unit,
  compact: Boolean,
  label: String,
  icon: @Composable () -> Unit,
) {
  val itemColor by animateColorAsState(
    targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
    animationSpec = tween(180),
    label = "nonRootBottomItemColor",
  )
  val indicatorColor by animateColorAsState(
    targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    animationSpec = tween(180),
    label = "nonRootBottomIndicatorColor",
  )
  val indicatorHorizontalPadding by animateDpAsState(
    targetValue = if (selected) 16.dp else 6.dp,
    animationSpec = tween(180),
    label = "nonRootBottomIndicatorPadding",
  )
  val indicatorVerticalPadding by animateDpAsState(
    targetValue = if (selected) 5.dp else 3.dp,
    animationSpec = tween(180),
    label = "nonRootBottomIndicatorVerticalPadding",
  )
  val itemShape = RoundedCornerShape(20.dp)

  Column(
    modifier = Modifier
      .weight(1f)
      .fillMaxHeight()
      .clip(itemShape)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
      ) { onClick() }
      .padding(horizontal = 2.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(18.dp))
        .background(indicatorColor)
        .padding(
          horizontal = indicatorHorizontalPadding,
          vertical = indicatorVerticalPadding,
        ),
      contentAlignment = Alignment.Center,
    ) {
      CompositionLocalProvider(LocalContentColor provides itemColor) { icon() }
    }
    if (!compact) {
      Spacer(Modifier.height(3.dp))
      Text(
        text = label,
        color = itemColor,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
      )
    }
  }
}
