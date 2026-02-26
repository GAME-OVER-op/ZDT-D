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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.LogLine
import com.android.zdtd.service.AppUpdateUiState
import com.android.zdtd.service.BackupUiState
import com.android.zdtd.service.ProgramUpdatesUiState
import com.android.zdtd.service.R
import com.android.zdtd.service.RootState
import com.android.zdtd.service.SetupStep
import com.android.zdtd.service.SetupUiState
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
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
    title = {
      Text(
        if (setup.updatePromptTitle.isBlank()) stringResource(R.string.update_prompt_title_default)
        else setup.updatePromptTitle
      )
    },
    text = { Text(setup.updatePromptText) },
    confirmButton = {
      TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_prompt_update)) }
    },
    dismissButton = {
      if (!mandatory) {
        TextButton(onClick = onSkip) { Text(stringResource(R.string.update_prompt_skip)) }
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
  var showDeleteModule by remember { mutableStateOf(false) }
  var showDeleteModuleNext by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }

  // System Back behavior:
  // - From Stats/Programs -> go to Home
  // - From Support -> go to Home
  // - Inside Programs (Program/Profile) -> go back within the Programs stack
  // - From Home -> default system behavior (finish activity)
  // - If logs sheet is open -> close it
  val ctx = LocalContext.current
  val activity = ctx as? Activity
  val handleBack = remember(tab, appsRoute, showLogs, showBackup, showProgramUpdates, showSettings, showDeleteModule, showDeleteModuleNext) {
    tab != Tab.HOME || showLogs || showBackup || showProgramUpdates || showSettings || showDeleteModule || showDeleteModuleNext || (tab == Tab.APPS && appsRoute != AppsRoute.List)
  }
  BackHandler(enabled = handleBack) {
    if (showDeleteModuleNext) {
      showDeleteModuleNext = false
      return@BackHandler
    }
    if (showDeleteModule) {
      showDeleteModule = false
      return@BackHandler
    }
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
  val uiState by uiStateFlow.collectAsStateWithLifecycle()

  var deleteModulePreparing by remember { mutableStateOf(false) }
  var deleteModulePrepareError by remember { mutableStateOf<String?>(null) }
  var deleteModulePrepareRequested by remember { mutableStateOf(false) }

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
        languageMode = appUpdate.languageMode,
        onLanguageModeChange = actions::setAppLanguageMode,
        onDeleteModule = {
          showSettings = false
          showDeleteModule = true
        },
      )
      Spacer(Modifier.height(16.dp))
    }
  }

  DeleteModuleConfirmDialog(
    visible = showDeleteModule,
    onConfirm = {
      showDeleteModule = false
      val serviceRunning = ApiModels.isServiceOn(uiState.status)
      if (serviceRunning) {
        deleteModulePrepareError = null
        deleteModulePreparing = true
        deleteModulePrepareRequested = true
      } else {
        actions.beginModuleRemoval()
        showDeleteModuleNext = true
      }
    },
    onDismiss = { showDeleteModule = false },
  )

  if (deleteModulePrepareRequested) {
    LaunchedEffect(deleteModulePrepareRequested) {
      deleteModulePrepareError = null
      actions.toggleService()
      var stopped = false
      var tries = 0
      while (tries < 20) {
        delay(500)
        actions.refreshStatus()
        delay(500)
        stopped = !ApiModels.isServiceOn(uiState.status)
        if (stopped) break
        tries++
      }
      deleteModulePreparing = false
      deleteModulePrepareRequested = false
      if (stopped) {
        actions.beginModuleRemoval()
        showDeleteModuleNext = true
      } else {
        deleteModulePrepareError = ctx.getString(R.string.mv_auto_054)
      }
    }
  }

  DeleteModulePreparingDialog(
    visible = deleteModulePreparing,
    errorText = deleteModulePrepareError,
    onDismiss = {
      deleteModulePreparing = false
      deleteModulePrepareRequested = false
      deleteModulePrepareError = null
    },
  )

  DeleteModuleNextStepDialog(
    visible = showDeleteModuleNext,
    onDismiss = { showDeleteModuleNext = false },
  )

  UnknownSourcesPermissionDialog(
    visible = appUpdate.needsUnknownSourcesPermission,
    onAllow = actions::requestUnknownSourcesPermission,
    onDecline = actions::declineUnknownSourcesPermission,
  )

  val canGoBack = tab == Tab.APPS && appsRoute != AppsRoute.List
  val title = when {
    tab == Tab.HOME -> stringResource(R.string.app_name)
    tab == Tab.STATS -> stringResource(R.string.nav_stats)
    tab == Tab.SUPPORT -> stringResource(R.string.nav_support)
    tab == Tab.APPS && appsRoute == AppsRoute.List -> stringResource(R.string.nav_programs)
    tab == Tab.APPS && appsRoute is AppsRoute.Program -> stringResource(R.string.title_program)
    tab == Tab.APPS && appsRoute is AppsRoute.Profile -> stringResource(R.string.title_profile)
    else -> stringResource(R.string.app_name)
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
            }) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) }
          }
        },
        actions = {
            IconButton(onClick = { showLogs = true }) { Icon(Icons.Filled.BugReport, contentDescription = stringResource(R.string.cd_logs)) }
            IconButton(onClick = { showBackup = true }) { Icon(Icons.Filled.CloudDownload, contentDescription = stringResource(R.string.cd_backup)) }
            IconButton(onClick = {
              showProgramUpdates = true
              actions.resetProgramUpdatesUi()
            }) { Icon(Icons.Filled.SystemUpdateAlt, contentDescription = stringResource(R.string.cd_program_updates)) }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings)) }
        }
      )
    },
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = tab == Tab.HOME,
          onClick = { tab = Tab.HOME },
          icon = { Icon(Icons.Filled.Power, contentDescription = null) },
          label = { Text(stringResource(R.string.nav_home)) },
        )
        NavigationBarItem(
          selected = tab == Tab.STATS,
          onClick = { tab = Tab.STATS },
          icon = { Icon(Icons.Filled.Equalizer, contentDescription = null) },
          label = { Text(stringResource(R.string.nav_stats)) },
        )
        NavigationBarItem(
          selected = tab == Tab.APPS,
          onClick = { tab = Tab.APPS },
          icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
          label = { Text(stringResource(R.string.nav_programs)) },
        )

        NavigationBarItem(
          selected = tab == Tab.SUPPORT,
          onClick = { tab = Tab.SUPPORT },
          icon = { Icon(Icons.Filled.Info, contentDescription = null) },
          label = { Text(stringResource(R.string.nav_support)) },
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
