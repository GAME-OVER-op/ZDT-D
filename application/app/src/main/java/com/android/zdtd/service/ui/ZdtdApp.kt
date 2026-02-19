package com.android.zdtd.service.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.LogLine
import com.android.zdtd.service.AppUpdateUiState
import com.android.zdtd.service.BackupUiState
import com.android.zdtd.service.ProgramUpdatesUiState
import com.android.zdtd.service.RootState
import com.android.zdtd.service.SetupStep
import com.android.zdtd.service.SetupUiState
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.android.zdtd.service.ui.AppUpdateBanner
import com.android.zdtd.service.ui.AppUpdateSettings
import com.android.zdtd.service.ui.UnknownSourcesPermissionDialog

@Composable
fun ZdtdApp(
  rootState: RootState,
  setupFlow: StateFlow<SetupUiState>,
  uiStateFlow: StateFlow<UiState>,
  logsFlow: StateFlow<List<LogLine>>,
  appUpdateFlow: StateFlow<AppUpdateUiState>,
  backupFlow: StateFlow<BackupUiState>,
  programUpdatesFlow: StateFlow<ProgramUpdatesUiState>,
  actions: ZdtdActions,
) {
  val setup by setupFlow.collectAsStateWithLifecycle()

  when (setup.step) {
    SetupStep.WELCOME -> WelcomeScreen(onAccept = actions::acceptWelcome)
    SetupStep.ROOT -> RootInfoScreen(rootState = rootState, onRequest = actions::retryRoot)
    SetupStep.INSTALL -> InstallModuleScreen(
      rootState = rootState,
      setup = setup,
      onInstall = actions::beginModuleInstall,
      onManualConfirm = actions::confirmManualInstall,
      onManualDismiss = actions::dismissManualInstallDialog,
      onContinue = actions::continueAfterInstall,
      onReboot = actions::rebootNow,
      onRequestMigration = actions::requestSettingsMigration,
      onDismissMigrationDialog = actions::dismissSettingsMigrationDialog,
      onConfirmMigrationDialog = actions::confirmSettingsMigrationDialog,
      onCloseMigrationProgress = actions::closeSettingsMigrationProgress,
    )
    SetupStep.REBOOT -> {
      when (rootState) {
        RootState.CHECKING -> SplashScreen()
        RootState.DENIED -> RootInfoScreen(rootState = rootState, onRequest = actions::retryRoot)
        RootState.GRANTED -> RebootRequiredScreen(
          setup = setup,
          text = setup.rebootRequiredText,
          onReboot = actions::rebootNow,
          onRequestMigration = actions::requestSettingsMigration,
          onDismissMigrationDialog = actions::dismissSettingsMigrationDialog,
          onConfirmMigrationDialog = actions::confirmSettingsMigrationDialog,
          onCloseMigrationProgress = actions::closeSettingsMigrationProgress,
        )
      }
    }
    SetupStep.DONE -> {
      when (rootState) {
        RootState.CHECKING -> SplashScreen()
        RootState.DENIED -> RootInfoScreen(rootState = rootState, onRequest = actions::retryRoot)
        RootState.GRANTED -> {
          UpdatePromptDialog(setup = setup, onUpdate = actions::openModuleInstaller, onSkip = actions::dismissUpdatePrompt)
          MainShell(
            uiStateFlow = uiStateFlow,
            logsFlow = logsFlow,
            appUpdateFlow = appUpdateFlow,
            backupFlow = backupFlow,
            programUpdatesFlow = programUpdatesFlow,
            actions = actions,
          )
        }
      }
    }
  }
}

@Composable
private fun SplashScreen() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}


@Composable
private fun UpdatePromptDialog(setup: SetupUiState, onUpdate: () -> Unit, onSkip: () -> Unit) {
  if (!setup.showUpdatePrompt) return
  val mandatory = setup.updatePromptMandatory
  AlertDialog(
    onDismissRequest = { if (!mandatory) onSkip() },
    title = { Text(if (setup.updatePromptTitle.isBlank()) "Обновление" else setup.updatePromptTitle) },
    text = { Text(setup.updatePromptText) },
    confirmButton = {
      TextButton(onClick = onUpdate) { Text("Обновить") }
    },
    dismissButton = {
      if (!mandatory) {
        TextButton(onClick = onSkip) { Text("Пропустить") }
      }
    },
    properties = androidx.compose.ui.window.DialogProperties(
      dismissOnBackPress = !mandatory,
      dismissOnClickOutside = !mandatory,
    )
  )
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
  uiStateFlow: StateFlow<UiState>,
  logsFlow: StateFlow<List<LogLine>>,
  appUpdateFlow: StateFlow<AppUpdateUiState>,
  backupFlow: StateFlow<BackupUiState>,
  programUpdatesFlow: StateFlow<ProgramUpdatesUiState>,
  actions: ZdtdActions,
) {
  var tab by remember { mutableStateOf(Tab.HOME) }
  var appsRoute by remember { mutableStateOf<AppsRoute>(AppsRoute.List) }
  var showLogs by remember { mutableStateOf(false) }
  var showBackup by remember { mutableStateOf(false) }
  var showProgramUpdates by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }

  // System Back behavior:
  // - From Stats/Programs -> go to Home
  // - From Support -> go to Home
  // - Inside Programs (Program/Profile) -> go back within the Programs stack
  // - From Home -> default system behavior (finish activity)
  // - If logs sheet is open -> close it
  val activity = LocalContext.current as? Activity
  val handleBack = remember(tab, appsRoute, showLogs, showBackup, showProgramUpdates, showSettings) {
    tab != Tab.HOME || showLogs || showBackup || showProgramUpdates || showSettings || (tab == Tab.APPS && appsRoute != AppsRoute.List)
  }
  BackHandler(enabled = handleBack) {
    if (showSettings) {
      showSettings = false
      return@BackHandler
    }
    if (showProgramUpdates) {
      showProgramUpdates = false
      return@BackHandler
    }
    if (showBackup) {
      showBackup = false
      return@BackHandler
    }
    if (showLogs) {
      showLogs = false
      return@BackHandler
    }

    when (tab) {
      Tab.APPS -> {
        if (appsRoute != AppsRoute.List) {
          appsRoute = when (val r = appsRoute) {
            is AppsRoute.Profile -> AppsRoute.Program(r.programId)
            is AppsRoute.Program -> AppsRoute.List
            AppsRoute.List -> AppsRoute.List
          }
        } else {
          tab = Tab.HOME
        }
      }
      Tab.STATS -> {
        tab = Tab.HOME
      }
      Tab.SUPPORT -> {
        tab = Tab.HOME
      }
      Tab.HOME -> {
        // Should normally be handled by the system (finish) when BackHandler is disabled.
        activity?.finish()
      }
    }
  }

  val snackHost = remember { SnackbarHostState() }

  // When user opens the Programs tab, refresh programs once (no manual refresh buttons in UI).
  LaunchedEffect(tab) {
    if (tab == Tab.APPS) actions.refreshPrograms()
  }

  if (showLogs) {
    // Collect logs ONLY when the sheet is open.
    val logs by logsFlow.collectAsStateWithLifecycle()
    LogsBottomSheet(
      logs = logs,
      onClear = actions::clearLogs,
      onDismiss = { showLogs = false },
    )
  }

  if (showBackup) {
    val backup by backupFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
      actions.refreshBackups()
    }
    BackupDialog(
      state = backup,
      onDismiss = { showBackup = false },
      actions = actions,
    )
  }

  if (showProgramUpdates) {
    val uiState by uiStateFlow.collectAsStateWithLifecycle()
    val pu by programUpdatesFlow.collectAsStateWithLifecycle()
    ProgramUpdatesDialog(
      state = pu,
      serviceRunning = ApiModels.isServiceOn(uiState.status),
      onDismiss = { showProgramUpdates = false },
      actions = actions,
    )
  }

  val appUpdate by appUpdateFlow.collectAsStateWithLifecycle()

  if (showSettings) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { showSettings = false },
      sheetState = sheetState,
    ) {
      AppUpdateSettings(
        enabled = appUpdate.enabled,
        onToggle = actions::setAppUpdateChecksEnabled,
        onCheckNow = actions::checkAppUpdateNow,
        daemonNotificationEnabled = appUpdate.daemonStatusNotificationEnabled,
        onToggleDaemonNotification = actions::setDaemonStatusNotificationsEnabled,
      )
      Spacer(Modifier.height(16.dp))
    }
  }

  UnknownSourcesPermissionDialog(
    visible = appUpdate.needsUnknownSourcesPermission,
    onAllow = actions::requestUnknownSourcesPermission,
    onDecline = actions::declineUnknownSourcesPermission,
  )

  val canGoBack = tab == Tab.APPS && appsRoute != AppsRoute.List
  val title = when {
    tab == Tab.HOME -> "ZDT-D"
    tab == Tab.STATS -> "Stats"
    tab == Tab.SUPPORT -> "Support"
    tab == Tab.APPS && appsRoute == AppsRoute.List -> "Programs"
    tab == Tab.APPS && appsRoute is AppsRoute.Program -> "Program"
    tab == Tab.APPS && appsRoute is AppsRoute.Profile -> "Profile"
    else -> "ZDT-D"
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title, letterSpacing = 2.sp) },
        navigationIcon = {
          if (canGoBack) {
            IconButton(onClick = {
              appsRoute = when (val r = appsRoute) {
                is AppsRoute.Profile -> AppsRoute.Program(r.programId)
                is AppsRoute.Program -> AppsRoute.List
                AppsRoute.List -> AppsRoute.List
              }
            }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
          }
        },
        actions = {
            IconButton(onClick = { showLogs = true }) { Icon(Icons.Filled.BugReport, contentDescription = "Logs") }
            IconButton(onClick = { showBackup = true }) { Icon(Icons.Filled.CloudDownload, contentDescription = "Backup") }
            IconButton(onClick = {
              showProgramUpdates = true
              actions.resetProgramUpdatesUi()
            }) { Icon(Icons.Filled.SystemUpdateAlt, contentDescription = "Program updates") }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
      )
    },
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = tab == Tab.HOME,
          onClick = { tab = Tab.HOME },
          icon = { Icon(Icons.Filled.Power, contentDescription = null) },
          label = { Text("Home") },
        )
        NavigationBarItem(
          selected = tab == Tab.STATS,
          onClick = { tab = Tab.STATS },
          icon = { Icon(Icons.Filled.Equalizer, contentDescription = null) },
          label = { Text("Stats") },
        )
        NavigationBarItem(
          selected = tab == Tab.APPS,
          onClick = { tab = Tab.APPS },
          icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
          label = { Text("Programs") },
        )

        NavigationBarItem(
          selected = tab == Tab.SUPPORT,
          onClick = { tab = Tab.SUPPORT },
          icon = { Icon(Icons.Filled.Info, contentDescription = null) },
          label = { Text("Support") },
        )
      }
    },
    snackbarHost = { SnackbarHost(snackHost) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      AppUpdateBanner(
        state = appUpdate,
        onDismiss = actions::dismissAppUpdateBanner,
        onUpdate = {
          if (appUpdate.downloading) actions.cancelAppUpdateDownload() else actions.startAppUpdateDownload()
        },
      )
      Box(Modifier.fillMaxSize()) {
        TabBody(
          tab = tab,
          uiStateFlow = uiStateFlow,
          appsRoute = appsRoute,
          onOpenProgram = { appsRoute = AppsRoute.Program(it) },
          onOpenProfile = { pid, pr -> appsRoute = AppsRoute.Profile(pid, pr) },
          actions = actions,
          snackHost = snackHost,
        )
      }
    }
  }
}

@Composable
private fun TabBody(
  tab: Tab,
  uiStateFlow: StateFlow<UiState>,
  appsRoute: AppsRoute,
  onOpenProgram: (String) -> Unit,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  AnimatedContent(
    targetState = tab,
    transitionSpec = {
      val forward = targetState.ordinal > initialState.ordinal

      val enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) {
        if (forward) it / 8 else -it / 8
      }
      val exit = fadeOut(tween(160)) + slideOutHorizontally(tween(220)) {
        if (forward) -it / 8 else it / 8
      }

      (enter togetherWith exit).using(SizeTransform(clip = false))
    },
    label = "tab",
  ) { t ->
    when (t) {
      Tab.HOME -> HomeScreen(uiStateFlow = uiStateFlow, actions = actions)
      Tab.STATS -> StatsScreen(uiStateFlow = uiStateFlow, actions = actions)
      Tab.APPS -> AppsHost(
        uiStateFlow = uiStateFlow,
        route = appsRoute,
        onOpenProgram = onOpenProgram,
        onOpenProfile = onOpenProfile,
        actions = actions,
        snackHost = snackHost,
      )
      Tab.SUPPORT -> SupportScreen()
    }
  }
}
