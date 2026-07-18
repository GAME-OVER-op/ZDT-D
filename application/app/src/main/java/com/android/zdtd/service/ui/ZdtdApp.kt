package com.android.zdtd.service.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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
import com.android.zdtd.service.WorldMapActivity
import com.android.zdtd.service.StartupStage
import com.android.zdtd.service.StartupUiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.ui.AppUpdateBanner
import com.android.zdtd.service.ui.AppUpdateSettings
import com.android.zdtd.service.ui.settings.SettingsScreen
import com.android.zdtd.service.ui.UnknownSourcesPermissionDialog

private const val REMOTE_CONTROL_FEATURE_ENABLED = false
// Remote control is intentionally hidden/disabled for now: the feature is not stable yet.
// Keep the implementation in the tree so it can be restored and continued later.

private fun supportsArm64ToolUpdates(): Boolean {
  return Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
}

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

  Crossfade(
    targetState = setup.step,
    animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
    label = "setup_step_crossfade",
  ) { setupStep ->
    when (setupStep) {
      SetupStep.WELCOME -> WelcomeScreen(onAccept = actions::acceptWelcome)
      SetupStep.ROOT -> RootInfoScreen(rootState = rootState, onRequest = actions::retryRoot, onRemoteSetup = actions::openRemoteSetup)
      SetupStep.INSTALL -> InstallModuleScreen(
        rootState = rootState,
        setup = setup,
        onInstall = actions::beginModuleInstall,
        onManualConfirm = actions::confirmManualInstall,
        onManualDismiss = actions::dismissManualInstallDialog,
        onContinue = actions::continueAfterInstall,
        onReboot = actions::rebootNow,
        onRefreshConflicts = actions::refreshInstallConflicts,
        onToggleConflictRemove = actions::setInstallConflictMarked,
        onRefreshZygiskInstallMarker = actions::refreshZygiskInstallMarker,
        onToggleZygiskInstall = actions::requestSetInstallZygisk,
        onConfirmZygiskInstall = actions::confirmInstallZygisk,
        onDismissZygiskInstallConfirm = actions::dismissInstallZygiskConfirm,
        onDismissZygiskInstallRecovery = actions::dismissZygiskInstallRecoveryDialog,
        onDismissMetamoduleInstallBlocked = actions::dismissMetamoduleInstallBlockedDialog,
        onRetryInstallWithoutZygisk = actions::retryInstallWithoutZygisk,
      )
      SetupStep.REBOOT -> {
        when (rootState) {
          RootState.CHECKING -> SplashScreen()
          RootState.DENIED -> RootInfoScreen(rootState = rootState, onRequest = actions::retryRoot, onRemoteSetup = actions::openRemoteSetup)
          RootState.GRANTED -> RebootRequiredScreen(
            setup = setup,
            text = setup.rebootRequiredText,
            onReboot = actions::rebootNow,
          )
        }
      }
      SetupStep.DONE -> {
        when (rootState) {
          RootState.CHECKING -> SplashScreen()
          RootState.DENIED -> RootInfoScreen(rootState = rootState, onRequest = actions::retryRoot, onRemoteSetup = actions::openRemoteSetup)
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
}

@Composable
private fun SplashScreen() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}




@Composable
private fun StartupDialogHost(
  uiState: UiState,
  onRetry: () -> Unit,
  onReinstall: () -> Unit,
  onFullyHidden: () -> Unit,
) {
  val startup = uiState.startup
  var renderedStartup by remember { mutableStateOf(startup) }

  LaunchedEffect(startup) {
    if (startup.visible) {
      renderedStartup = startup
    }
  }

  val exiting = !startup.visible && renderedStartup.visible
  val cardAlpha by animateFloatAsState(
    targetValue = if (exiting) 0f else 1f,
    animationSpec = tween(durationMillis = 260),
    label = "startup_card_alpha",
  )

  LaunchedEffect(exiting) {
    if (exiting) {
      delay(260)
      renderedStartup = StartupUiState.hidden()
      onFullyHidden()
    }
  }

  if (!renderedStartup.visible) return

  StartupFullscreenContent(
    startup = renderedStartup,
    onRetry = onRetry,
    onReinstall = onReinstall,
    contentAlpha = cardAlpha,
  )
}

@Composable
private fun StartupFullscreenContent(
  startup: com.android.zdtd.service.StartupUiState,
  onRetry: () -> Unit,
  onReinstall: () -> Unit,
  contentAlpha: Float,
) {
  val pulseAlpha by rememberInfiniteTransition(label = "startup_stage_pulse").animateFloat(
    initialValue = 0.58f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1150),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "startup_stage_alpha",
  )

  val progress = remember { Animatable(0f) }

  LaunchedEffect(startup.stage) {
    when (startup.stage) {
      StartupStage.CONNECTING_DAEMON -> {
        if (progress.value > 0.52f) progress.snapTo(0f)
        progress.animateTo(0.52f, animationSpec = tween(durationMillis = startup.connectingDurationMs))
      }
      StartupStage.LOADING_STATUS -> {
        progress.animateTo(0.94f, animationSpec = tween(durationMillis = startup.loadingDurationMs))
      }
      StartupStage.COMPLETE -> {
        progress.animateTo(1f, animationSpec = tween(durationMillis = startup.completeDurationMs))
      }
      StartupStage.FAILED -> Unit
      StartupStage.IDLE -> progress.snapTo(0f)
    }
  }

  val stageText = when (startup.stage) {
    StartupStage.CONNECTING_DAEMON -> stringResource(R.string.startup_stage_connecting_short)
    StartupStage.LOADING_STATUS -> stringResource(R.string.startup_stage_initializing)
    StartupStage.COMPLETE -> stringResource(R.string.startup_stage_opening)
    StartupStage.FAILED -> stringResource(R.string.startup_dialog_error_title)
    StartupStage.IDLE -> ""
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .zIndex(20f)
      .pointerInput(startup.stage) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
          }
        }
      }
      .background(
        Brush.verticalGradient(
          colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
          )
        )
      ),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 20.dp),
      contentAlignment = Alignment.Center,
    ) {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 440.dp)
          .alpha(contentAlpha),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        ),
        elevation = CardDefaults.cardElevation(
          defaultElevation = 18.dp
        ),
      ) {
        val showErrorState = startup.stage == StartupStage.FAILED
        Crossfade(
          targetState = showErrorState,
          animationSpec = tween(durationMillis = 180),
          label = "startup_fullscreen_state",
        ) { isErrorState ->
          if (isErrorState) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              Box(
                modifier = Modifier
                  .size(84.dp)
                  .clip(MaterialTheme.shapes.extraLarge)
                  .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Filled.ErrorOutline,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(34.dp),
                )
              }
              Text(
                text = stringResource(R.string.startup_dialog_error_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
              )
              Text(
                text = startup.errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
              Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Button(
                  onClick = onRetry,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(stringResource(R.string.common_retry))
                }
                OutlinedButton(
                  onClick = onReinstall,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(stringResource(R.string.startup_reinstall_module))
                }
              }
            }
          } else {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
              Box(
                modifier = Modifier
                  .size(84.dp)
                  .clip(MaterialTheme.shapes.extraLarge)
                  .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Filled.Power,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(36.dp),
                )
              }

              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  text = stringResource(R.string.startup_loading_title),
                  style = MaterialTheme.typography.headlineSmall,
                  fontWeight = FontWeight.SemiBold,
                  textAlign = TextAlign.Center,
                )
                Text(
                  text = stringResource(R.string.startup_loading_subtitle),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }

              Text(
                text = stageText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alpha(pulseAlpha),
                textAlign = TextAlign.Center,
              )

              StartupProgressBar(progress = progress.value)

              Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                StartupStagePill(
                  text = stringResource(R.string.startup_stage_connecting_short),
                  active = startup.stage == StartupStage.CONNECTING_DAEMON,
                  done = startup.stage == StartupStage.LOADING_STATUS || startup.stage == StartupStage.COMPLETE,
                )
                StartupStagePill(
                  text = stringResource(R.string.startup_stage_initializing),
                  active = startup.stage == StartupStage.LOADING_STATUS,
                  done = startup.stage == StartupStage.COMPLETE,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StartupProgressBar(progress: Float) {
  val clamped = progress.coerceIn(0f, 1f)
  val percentText = "${(clamped * 100).roundToInt()}%"
  val labelColor = if (clamped >= 0.48f) {
    MaterialTheme.colorScheme.onPrimary
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .height(30.dp)
      .clip(MaterialTheme.shapes.extraLarge)
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxHeight()
        .fillMaxWidth(clamped)
        .clip(MaterialTheme.shapes.extraLarge)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.94f))
    )
    Text(
      text = percentText,
      modifier = Modifier.align(Alignment.Center),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = labelColor,
    )
  }
}

@Composable
private fun StartupStagePill(text: String, active: Boolean, done: Boolean) {
  val containerColor = when {
    done -> MaterialTheme.colorScheme.primaryContainer
    active -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f)
  }
  val contentColor = when {
    done -> MaterialTheme.colorScheme.onPrimaryContainer
    active -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = containerColor,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Crossfade(
        targetState = when {
          done -> "done"
          active -> "active"
          else -> "idle"
        },
        animationSpec = tween(220),
        label = "startup_pill_icon",
      ) { state ->
        when (state) {
          "done" -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
          )
          "active" -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
          )
          else -> Icon(
            imageVector = Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
          )
        }
      }
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
      )
    }
  }
}

@Composable
private fun DaemonUnavailableDialogHost(uiState: UiState) {
  if (!uiState.daemonUnavailableVisible) return

  Dialog(
    onDismissRequest = {},
    properties = DialogProperties(
      dismissOnBackPress = false,
      dismissOnClickOutside = false,
    )
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp),
      shape = MaterialTheme.shapes.extraLarge,
      tonalElevation = 6.dp,
      shadowElevation = 12.dp,
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.ErrorOutline,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(28.dp),
        )
        Text(
          text = stringResource(R.string.daemon_runtime_unavailable_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = stringResource(R.string.daemon_runtime_unavailable_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CircularProgressIndicator()
      }
    }
  }
}

@Composable
private fun UpdatePromptDialog(setup: SetupUiState, onUpdate: () -> Unit, onSkip: () -> Unit) {
  var renderDialog by rememberSaveable { mutableStateOf(false) }
  var contentVisible by remember { mutableStateOf(false) }
  var dismissRequested by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(setup.showUpdatePrompt) {
    dismissRequested = false
    if (setup.showUpdatePrompt) {
      contentVisible = false
      renderDialog = false
      delay(760)
      renderDialog = true
      withFrameNanos { }
      contentVisible = true
    } else {
      contentVisible = false
      delay(240)
      renderDialog = false
    }
  }

  if (!renderDialog) return

  val mandatory = setup.updatePromptMandatory
  fun requestSkip() {
    if (mandatory || dismissRequested) return
    dismissRequested = true
    contentVisible = false
    scope.launch {
      delay(240)
      onSkip()
    }
  }

  Dialog(
    onDismissRequest = { requestSkip() },
    properties = DialogProperties(
      dismissOnBackPress = !mandatory,
      dismissOnClickOutside = !mandatory,
    )
  ) {
    AnimatedVisibility(
      visible = contentVisible,
      enter = fadeIn(
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
      ) + scaleIn(
        initialScale = 0.94f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
      ) + slideInVertically(
        initialOffsetY = { it / 7 },
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
      ),
      exit = fadeOut(
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
      ) + scaleOut(
        targetScale = 0.98f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
      ) + slideOutVertically(
        targetOffsetY = { it / 12 },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
      ),
    ) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface,
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 22.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text(
            text = if (setup.updatePromptTitle.isBlank()) {
              stringResource(R.string.update_prompt_title_default)
            } else {
              setup.updatePromptTitle
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = setup.updatePromptText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (!mandatory) {
              TextButton(onClick = { requestSkip() }) {
                Text(stringResource(R.string.update_prompt_skip))
              }
              Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onUpdate) {
              Text(stringResource(R.string.update_prompt_update))
            }
          }
        }
      }
    }
  }
}



private fun parentAppsRoute(route: AppsRoute): AppsRoute = when (route) {
  AppsRoute.List -> AppsRoute.List
  AppsRoute.AnalysisTools -> AppsRoute.List
  AppsRoute.OptionalTools -> AppsRoute.List
  AppsRoute.ConstructionStudio -> AppsRoute.AnalysisTools
  AppsRoute.DpiDetector -> AppsRoute.AnalysisTools
  AppsRoute.NfqwsTester -> AppsRoute.AnalysisTools
  is AppsRoute.Program -> AppsRoute.List
  is AppsRoute.Profile -> AppsRoute.Program(route.programId)
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
  var settingsCloseRequested by remember { mutableStateOf(false) }
  var programLogsTarget by remember { mutableStateOf<ProgramLogTarget?>(null) }
  var showWorldMapPrompt by remember { mutableStateOf(false) }

  // System Back behavior:
  // - From Stats/Programs -> go to Home
  // - From Support -> go to Home
  // - Inside Programs (Program/Profile) -> go back within the Programs stack
  // - From Home -> default system behavior (finish activity)
  // - If logs sheet is open -> close it
  val ctx = LocalContext.current
  val activity = ctx as? Activity
  val handleBack = remember(tab, appsRoute, showLogs, showBackup, showProgramUpdates, showSettings, showDeleteModule, showDeleteModuleNext, showWorldMapPrompt, programLogsTarget) {
    tab != Tab.HOME || showLogs || showBackup || showProgramUpdates || showSettings || showDeleteModule || showDeleteModuleNext || showWorldMapPrompt || programLogsTarget != null || (tab == Tab.APPS && appsRoute != AppsRoute.List)
  }
  BackHandler(enabled = handleBack) {
    if (showDeleteModuleNext) {
      showDeleteModuleNext = false
      return@BackHandler
    }
    if (showWorldMapPrompt) {
      showWorldMapPrompt = false
      return@BackHandler
    }
    if (showDeleteModule) {
      showDeleteModule = false
      return@BackHandler
    }
    if (programLogsTarget != null) {
      programLogsTarget = null
      return@BackHandler
    }
    if (showSettings) {
      settingsCloseRequested = true
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
          appsRoute = parentAppsRoute(appsRoute)
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
  val backup by backupFlow.collectAsStateWithLifecycle()
  val landscapeControl = rememberUseLandscapeControlLayout()

  DaemonUnavailableDialogHost(uiState = uiState)

  if (backup.externalRestorePromptVisible) {
    AlertDialog(
      onDismissRequest = actions::dismissExternalBackupRestore,
      title = { Text(stringResource(R.string.backup_external_restore_title)) },
      text = {
        val fileName = backup.externalRestoreDisplayName.ifBlank {
          backup.externalRestoreName ?: stringResource(R.string.backup_external_restore_default_file)
        }
        Text(stringResource(R.string.backup_external_restore_text, fileName))
      },
      dismissButton = {
        TextButton(onClick = actions::dismissExternalBackupRestore) {
          Text(stringResource(R.string.backup_cancel))
        }
      },
      confirmButton = {
        TextButton(onClick = {
          showBackup = true
          actions.confirmExternalBackupRestore()
        }) {
          Text(stringResource(R.string.backup_external_restore_apply))
        }
      },
    )
  }

  if (showWorldMapPrompt) {
    AlertDialog(
      onDismissRequest = { showWorldMapPrompt = false },
      title = { Text(stringResource(R.string.world_map_prompt_title)) },
      text = { Text(stringResource(R.string.world_map_prompt_body)) },
      dismissButton = {
        TextButton(onClick = { showWorldMapPrompt = false }) {
          Text(stringResource(R.string.common_no))
        }
      },
      confirmButton = {
        TextButton(onClick = {
          showWorldMapPrompt = false
          ctx.startActivity(Intent(ctx, WorldMapActivity::class.java))
        }) {
          Text(stringResource(R.string.common_yes))
        }
      },
    )
  }

  var deleteModulePreparing by remember { mutableStateOf(false) }
  var deleteModulePrepareError by remember { mutableStateOf<String?>(null) }
  var deleteModulePrepareRequested by remember { mutableStateOf(false) }

  var programsPrefetched by remember { mutableStateOf(false) }

  LaunchedEffect(uiState.remoteTargetName) {
    programsPrefetched = false
    if (uiState.remoteTargetName.isNotBlank()) {
      showSettings = false
      settingsCloseRequested = false
      tab = Tab.APPS
      appsRoute = AppsRoute.List
      actions.refreshPrograms()
    }
  }

  // Prefetch program list once after startup so the Programs tab opens warmer and keeps route state.
  LaunchedEffect(uiState.daemonOnline, uiState.startup.visible) {
    if (!programsPrefetched && uiState.daemonOnline && !uiState.startup.visible) {
      programsPrefetched = true
      actions.refreshPrograms()
    }
  }

  // If user opens Programs before prefetch completed or list is empty, do a single guarded refresh.
  LaunchedEffect(tab, uiState.programs) {
    if (tab == Tab.APPS && uiState.programs.isEmpty()) actions.refreshPrograms()
  }

  if (showLogs) {
    // Collect logs ONLY when the sheet is open.
    val logs by logsFlow.collectAsStateWithLifecycle()
    if (landscapeControl) {
      LandscapeLogsShelf(
        logs = logs,
        onClear = actions::clearLogs,
        onDismiss = { showLogs = false },
      )
    } else {
      LogsBottomSheet(
        logs = logs,
        onClear = actions::clearLogs,
        onDismiss = { showLogs = false },
      )
    }
  }

  programLogsTarget?.let { target ->
    ProgramLogsBrowserSheet(
      target = target,
      onDismiss = { programLogsTarget = null },
    )
  }

  if (showBackup) {
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
    var settingsContentReady by remember { mutableStateOf(false) }

    fun resetInvalidHotspotT2sIfNeeded() {
      val mode = if (appUpdate.hotspotMode == "vpn") "vpn" else "proxy"
      val program = appUpdate.hotspotProgram.ifBlank { appUpdate.hotspotT2sTarget }
      val profile = appUpdate.hotspotProfile.ifBlank {
        when (program) {
          "singbox" -> appUpdate.hotspotT2sSingboxProfile
          "wireproxy" -> appUpdate.hotspotT2sWireproxyProfile
          else -> ""
        }
      }
      val needsProfile = mode == "vpn" || program == "singbox" || program == "wireproxy"
      val hotspotInvalid = appUpdate.hotspotT2sEnabled && (
        program.isBlank() || (needsProfile && profile.isBlank())
        )
      if (hotspotInvalid) {
        actions.setHotspotT2sEnabled(false)
      }
    }

    fun dismissSettings(afterClose: (() -> Unit)? = null) {
      resetInvalidHotspotT2sIfNeeded()
      showSettings = false
      settingsCloseRequested = false
      afterClose?.invoke()
    }

    fun closeSettings(afterClose: (() -> Unit)? = null) {
      dismissSettings(afterClose)
    }

    LaunchedEffect(Unit) {
      settingsContentReady = false
      actions.refreshDaemonSettings()
      actions.refreshEnergySaver()
      actions.refreshProxyInfo()
      actions.refreshBlockedQuic()
      delay(260)
      settingsContentReady = true
    }

    LaunchedEffect(settingsCloseRequested) {
      if (settingsCloseRequested) closeSettings()
    }

    if (landscapeControl) {
      LandscapeSettingsShelf(
        onDismiss = { closeSettings() },
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .animateContentSize(animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)),
        ) {
          Crossfade(
            targetState = settingsContentReady,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            label = "settingsContentReadyLandscape",
          ) { ready ->
            if (!ready) {
              Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
              ) {
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  CircularProgressIndicator()
                  Text(
                    text = stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                  )
                }
              }
            } else {
              AppUpdateSettings(
                enabled = appUpdate.enabled,
                onToggle = actions::setAppUpdateChecksEnabled,
                onCheckNow = actions::checkAppUpdateNow,
                daemonNotificationEnabled = appUpdate.daemonStatusNotificationEnabled,
                onToggleDaemonNotification = actions::setDaemonStatusNotificationsEnabled,
                languageMode = appUpdate.languageMode,
                onLanguageModeChange = actions::setAppLanguageMode,
                themeMode = appUpdate.themeMode,
                onThemeModeChange = actions::setThemeMode,
                protectorMode = appUpdate.protectorMode,
                onProtectorModeChange = actions::setProtectorMode,
                energySaver = appUpdate.energySaver,
                energySaverBusy = appUpdate.energySaverBusy,
                onRefreshEnergySaver = actions::refreshEnergySaver,
                onSaveEnergySaver = actions::saveEnergySaver,
                selinuxPermissiveEnabled = appUpdate.selinuxPermissiveEnabled,
                ipForwardEnabled = appUpdate.ipForwardEnabled,
                tproxyEnabled = appUpdate.tproxyEnabled,
                onAdvancedSettingChange = actions::setAdvancedDaemonSetting,
                hotspotT2sEnabled = appUpdate.hotspotT2sEnabled,
                hotspotMode = appUpdate.hotspotMode,
                hotspotProgram = appUpdate.hotspotProgram,
                hotspotProfile = appUpdate.hotspotProfile,
                hotspotT2sTarget = appUpdate.hotspotT2sTarget,
                hotspotT2sSingboxProfile = appUpdate.hotspotT2sSingboxProfile,
                hotspotT2sWireproxyProfile = appUpdate.hotspotT2sWireproxyProfile,
                hotspotT2sCaptureAll = appUpdate.hotspotT2sCaptureAll,
                hotspotSingboxProfiles = appUpdate.hotspotSingboxProfiles,
                hotspotWireproxyProfiles = appUpdate.hotspotWireproxyProfiles,
                hotspotProxyPrograms = appUpdate.hotspotProxyPrograms,
                hotspotVpnPrograms = appUpdate.hotspotVpnPrograms,
                onHotspotT2sEnabledChange = actions::setHotspotT2sEnabled,
                onHotspotModeChange = actions::setHotspotMode,
                onHotspotSelectionChange = actions::setHotspotSelection,
                onHotspotT2sTargetChange = actions::setHotspotT2sTarget,
                onHotspotT2sSingboxProfileChange = actions::setHotspotT2sSingboxProfile,
                onHotspotT2sWireproxyProfileChange = actions::setHotspotT2sWireproxyProfile,
                onHotspotT2sCaptureAllChange = actions::setHotspotT2sCaptureAll,
                captivePortalEnabled = appUpdate.captivePortalEnabled,
                onCaptivePortalEnabledChange = actions::setCaptivePortalEnabled,
                proxyInfoEnabled = appUpdate.proxyInfoEnabled,
                proxyInfoBusy = appUpdate.proxyInfoBusy,
                proxyInfoAppsContent = appUpdate.proxyInfoAppsContent,
                hidingStatus = appUpdate.hidingStatus,
                onProxyInfoEnabledChange = actions::setProxyInfoEnabled,
                onLoadAppAssignments = actions::loadAppAssignments,
                onProxyInfoAppsSave = actions::saveProxyInfoApps,
                onProxyInfoAppsSaveRemovingConflicts = actions::saveProxyInfoAppsRemovingConflicts,
                blockedQuicEnabled = appUpdate.blockedQuicEnabled,
                blockedQuicBusy = appUpdate.blockedQuicBusy,
                blockedQuicAppsContent = appUpdate.blockedQuicAppsContent,
                onBlockedQuicEnabledChange = actions::setBlockedQuicEnabled,
                onBlockedQuicAppsSave = actions::saveBlockedQuicApps,
                resettingModuleIdentifier = appUpdate.resettingModuleIdentifier,
                onResetModuleIdentifier = actions::resetModuleIdentifier,
                onDeleteModule = { closeSettings { showDeleteModule = true } },
                landscapeColumns = true,
              )
            }
          }
        }
      }
    } else {
      PortraitSettingsShelf(
        onDismiss = { dismissSettings() },
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .animateContentSize(animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)),
        ) {
          Crossfade(
            targetState = settingsContentReady,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            label = "settingsContentReady",
          ) { ready ->
            if (!ready) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 220.dp)
                  .padding(horizontal = 24.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
              ) {
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  CircularProgressIndicator()
                  Text(
                    text = stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                  )
                }
              }
            } else {
              AppUpdateSettings(
                enabled = appUpdate.enabled,
                onToggle = actions::setAppUpdateChecksEnabled,
                onCheckNow = actions::checkAppUpdateNow,
                daemonNotificationEnabled = appUpdate.daemonStatusNotificationEnabled,
                onToggleDaemonNotification = actions::setDaemonStatusNotificationsEnabled,
                languageMode = appUpdate.languageMode,
                onLanguageModeChange = actions::setAppLanguageMode,
                themeMode = appUpdate.themeMode,
                onThemeModeChange = actions::setThemeMode,
                protectorMode = appUpdate.protectorMode,
                onProtectorModeChange = actions::setProtectorMode,
                energySaver = appUpdate.energySaver,
                energySaverBusy = appUpdate.energySaverBusy,
                onRefreshEnergySaver = actions::refreshEnergySaver,
                onSaveEnergySaver = actions::saveEnergySaver,
                selinuxPermissiveEnabled = appUpdate.selinuxPermissiveEnabled,
                ipForwardEnabled = appUpdate.ipForwardEnabled,
                tproxyEnabled = appUpdate.tproxyEnabled,
                onAdvancedSettingChange = actions::setAdvancedDaemonSetting,
                hotspotT2sEnabled = appUpdate.hotspotT2sEnabled,
                hotspotMode = appUpdate.hotspotMode,
                hotspotProgram = appUpdate.hotspotProgram,
                hotspotProfile = appUpdate.hotspotProfile,
                hotspotT2sTarget = appUpdate.hotspotT2sTarget,
                hotspotT2sSingboxProfile = appUpdate.hotspotT2sSingboxProfile,
                hotspotT2sWireproxyProfile = appUpdate.hotspotT2sWireproxyProfile,
                hotspotT2sCaptureAll = appUpdate.hotspotT2sCaptureAll,
                hotspotSingboxProfiles = appUpdate.hotspotSingboxProfiles,
                hotspotWireproxyProfiles = appUpdate.hotspotWireproxyProfiles,
                hotspotProxyPrograms = appUpdate.hotspotProxyPrograms,
                hotspotVpnPrograms = appUpdate.hotspotVpnPrograms,
                onHotspotT2sEnabledChange = actions::setHotspotT2sEnabled,
                onHotspotModeChange = actions::setHotspotMode,
                onHotspotSelectionChange = actions::setHotspotSelection,
                onHotspotT2sTargetChange = actions::setHotspotT2sTarget,
                onHotspotT2sSingboxProfileChange = actions::setHotspotT2sSingboxProfile,
                onHotspotT2sWireproxyProfileChange = actions::setHotspotT2sWireproxyProfile,
                onHotspotT2sCaptureAllChange = actions::setHotspotT2sCaptureAll,
                captivePortalEnabled = appUpdate.captivePortalEnabled,
                onCaptivePortalEnabledChange = actions::setCaptivePortalEnabled,
                proxyInfoEnabled = appUpdate.proxyInfoEnabled,
                proxyInfoBusy = appUpdate.proxyInfoBusy,
                proxyInfoAppsContent = appUpdate.proxyInfoAppsContent,
                hidingStatus = appUpdate.hidingStatus,
                onProxyInfoEnabledChange = actions::setProxyInfoEnabled,
                onLoadAppAssignments = actions::loadAppAssignments,
                onProxyInfoAppsSave = actions::saveProxyInfoApps,
                onProxyInfoAppsSaveRemovingConflicts = actions::saveProxyInfoAppsRemovingConflicts,
                blockedQuicEnabled = appUpdate.blockedQuicEnabled,
                blockedQuicBusy = appUpdate.blockedQuicBusy,
                blockedQuicAppsContent = appUpdate.blockedQuicAppsContent,
                onBlockedQuicEnabledChange = actions::setBlockedQuicEnabled,
                onBlockedQuicAppsSave = actions::saveBlockedQuicApps,
                resettingModuleIdentifier = appUpdate.resettingModuleIdentifier,
                onResetModuleIdentifier = actions::resetModuleIdentifier,
                onDeleteModule = { closeSettings { showDeleteModule = true } },
                landscapeColumns = false,
              )
            }
          }
        }
      }
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

  val compactBottomBar = rememberUseScrollableTabs() || rememberIsShortHeight()
  val homeTabLabel = stringResource(R.string.nav_home)
  val statsTabLabel = stringResource(R.string.nav_stats)
  val appsTabLabel = stringResource(R.string.nav_programs)
  val supportTabLabel = stringResource(R.string.nav_support)
  val floatingBottomBarReserve = WindowInsets.navigationBars
    .asPaddingValues()
    .calculateBottomPadding() + (if (compactBottomBar) 82.dp else 94.dp)
  val floatingTopBarReserve = WindowInsets.statusBars
    .asPaddingValues()
    .calculateTopPadding() + (if (REMOTE_CONTROL_FEATURE_ENABLED && uiState.remoteTargetName.isNotBlank()) 104.dp else 74.dp)

  LaunchedEffect(tab) {
    actions.setActiveMainTab(tab.name)
  }

  val canGoBack = tab == Tab.APPS && appsRoute != AppsRoute.List
  val title = when {
    tab == Tab.HOME -> stringResource(R.string.app_name)
    tab == Tab.STATS -> stringResource(R.string.nav_stats)
    tab == Tab.SUPPORT -> stringResource(R.string.nav_support)
    tab == Tab.APPS && appsRoute == AppsRoute.List -> stringResource(R.string.nav_programs)
    tab == Tab.APPS && appsRoute == AppsRoute.AnalysisTools -> stringResource(R.string.analysis_tools_title)
    tab == Tab.APPS && appsRoute == AppsRoute.OptionalTools -> stringResource(R.string.optional_tools_title)
    tab == Tab.APPS && appsRoute == AppsRoute.ConstructionStudio -> stringResource(R.string.construction_studio_title)
    tab == Tab.APPS && appsRoute == AppsRoute.DpiDetector -> stringResource(R.string.dpi_detector_title)
    tab == Tab.APPS && appsRoute == AppsRoute.NfqwsTester -> stringResource(R.string.nfqws_tester_title)
    tab == Tab.APPS && appsRoute is AppsRoute.Program -> {
      val route = appsRoute as AppsRoute.Program
      uiState.programs.firstOrNull { it.id == route.programId }?.name ?: route.programId
    }
    tab == Tab.APPS && appsRoute is AppsRoute.Profile -> {
      val route = appsRoute as AppsRoute.Profile
      val programName = uiState.programs.firstOrNull { it.id == route.programId }?.name ?: route.programId
      "$programName / ${route.profile}"
    }
    else -> stringResource(R.string.app_name)
  }

  val currentProgramLogTarget = remember(tab, appsRoute, uiState.programs) {
    if (tab != Tab.APPS) {
      null
    } else {
      when (val route = appsRoute) {
        AppsRoute.List -> null
        AppsRoute.AnalysisTools -> null
        AppsRoute.OptionalTools -> null
        AppsRoute.ConstructionStudio -> null
        AppsRoute.DpiDetector -> null
        AppsRoute.NfqwsTester -> null
        is AppsRoute.Program -> {
          val program = uiState.programs.firstOrNull { it.id == route.programId }
          if (program != null && !isProfileProgramType(program.type) && supportsProgramLogs(route.programId, profile = null)) {
            ProgramLogTarget(programId = route.programId, profile = null, title = program.name ?: route.programId)
          } else {
            null
          }
        }
        is AppsRoute.Profile -> {
          if (supportsProgramLogs(route.programId, profile = route.profile)) {
            val program = uiState.programs.firstOrNull { it.id == route.programId }
            val programName = program?.name ?: route.programId
            ProgramLogTarget(programId = route.programId, profile = route.profile, title = "$programName / ${route.profile}")
          } else {
            null
          }
        }
      }
    }
  }


  var startupHostVisible by remember { mutableStateOf(uiState.startup.visible) }

  LaunchedEffect(uiState.startup.visible) {
    if (uiState.startup.visible) {
      startupHostVisible = true
    }
  }

  val mainContentVisible = !uiState.startup.visible
  val mainContentAlpha by animateFloatAsState(
    targetValue = if (mainContentVisible) 1f else 0f,
    animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
    label = "main_shell_alpha",
  )
  val mainContentSlide by animateDpAsState(
    targetValue = if (mainContentVisible) 0.dp else 54.dp,
    animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
    label = "main_shell_slide_from_right",
  )
  val density = LocalDensity.current
  val mainContentSlidePx = with(density) { mainContentSlide.toPx() }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            MaterialTheme.colorScheme.background,
          ),
        ),
      ),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
          alpha = mainContentAlpha
          translationX = mainContentSlidePx
        }
    ) {
      if (landscapeControl) {
        LandscapeShellContent(
          tab = tab,
          onTabChange = { tab = it },
          title = title,
          canGoBack = canGoBack,
          onBack = {
            appsRoute = parentAppsRoute(appsRoute)
          },
          onTitleClick = { if (tab == Tab.HOME) showWorldMapPrompt = true },
          homeTabLabel = homeTabLabel,
          statsTabLabel = statsTabLabel,
          appsTabLabel = appsTabLabel,
          supportTabLabel = supportTabLabel,
          programLogTarget = currentProgramLogTarget,
          onOpenLogs = { target ->
            if (target == null) showLogs = true else programLogsTarget = target
          },
          onOpenBackup = { showBackup = true },
          onOpenProgramUpdates = {
            showProgramUpdates = true
            actions.resetProgramUpdatesUi()
          },
          onOpenSettings = {
            settingsCloseRequested = false
            showSettings = true
          },
          onOpenRemoteSetup = actions::openRemoteSetup,
          remoteTargetName = uiState.remoteTargetName,
          remoteTargetAddress = uiState.remoteTargetAddress,
          onRemoteDisconnect = actions::exitRemoteControl,
          appUpdate = appUpdate,
          uiStateFlow = uiStateFlow,
          appsRoute = appsRoute,
          onOpenProgram = { appsRoute = AppsRoute.Program(it) },
          onOpenProfile = { pid, pr -> appsRoute = AppsRoute.Profile(pid, pr) },
          onOpenAnalysisTools = { appsRoute = AppsRoute.AnalysisTools },
          onOpenOptionalTools = { appsRoute = AppsRoute.OptionalTools },
          onOpenConstructionStudio = { appsRoute = AppsRoute.ConstructionStudio },
          onOpenDpiDetector = { appsRoute = AppsRoute.DpiDetector },
          onOpenNfqwsTester = { appsRoute = AppsRoute.NfqwsTester },
          actions = actions,
          snackHost = snackHost,
        )
      } else {
        Scaffold(
          modifier = Modifier.fillMaxSize(),
          containerColor = Color.Transparent,
          contentColor = MaterialTheme.colorScheme.onBackground,
          contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
          Box(
            Modifier
              .fillMaxSize()
              .padding(padding),
          ) {
            TabBody(
              tab = tab,
              uiStateFlow = uiStateFlow,
              appsRoute = appsRoute,
              onOpenProgram = { appsRoute = AppsRoute.Program(it) },
              onOpenProfile = { pid, pr -> appsRoute = AppsRoute.Profile(pid, pr) },
              onOpenAnalysisTools = { appsRoute = AppsRoute.AnalysisTools },
              onOpenOptionalTools = { appsRoute = AppsRoute.OptionalTools },
              onOpenConstructionStudio = { appsRoute = AppsRoute.ConstructionStudio },
              onOpenDpiDetector = { appsRoute = AppsRoute.DpiDetector },
              onOpenNfqwsTester = { appsRoute = AppsRoute.NfqwsTester },
              actions = actions,
              snackHost = snackHost,
              tproxyEnabled = appUpdate.tproxyEnabled,
              landscapeControl = false,
              topContentPadding = floatingTopBarReserve,
              bottomContentPadding = floatingBottomBarReserve + 18.dp,
            )

            Box(
              modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = floatingTopBarReserve)
                .zIndex(1.5f),
            ) {
              AppUpdateBanner(
                state = appUpdate,
                onDismiss = actions::dismissAppUpdateBanner,
                onUpdate = {
                  if (appUpdate.downloading) actions.cancelAppUpdateDownload() else actions.startAppUpdateDownload()
                },
              )
            }
          }
        }

        FloatingTopBarCard(
          modifier = Modifier
            .align(Alignment.TopCenter)
            .zIndex(2f),
          title = title,
          canGoBack = canGoBack,
          onBack = {
            appsRoute = parentAppsRoute(appsRoute)
          },
          isHome = tab == Tab.HOME,
          onTitleClick = { if (tab == Tab.HOME) showWorldMapPrompt = true },
          programLogTarget = currentProgramLogTarget,
          onOpenLogs = { target ->
            if (target == null) showLogs = true else programLogsTarget = target
          },
          onOpenBackup = { showBackup = true },
          onOpenProgramUpdates = {
            showProgramUpdates = true
            actions.resetProgramUpdatesUi()
          },
          onOpenSettings = {
            settingsCloseRequested = false
            showSettings = true
          },
          onOpenRemoteSetup = actions::openRemoteSetup,
          remoteTargetName = uiState.remoteTargetName,
          remoteTargetAddress = uiState.remoteTargetAddress,
          onRemoteDisconnect = actions::exitRemoteControl,
        )

        FloatingBottomNavigationCard(
          modifier = Modifier.align(Alignment.BottomCenter),
          compact = compactBottomBar,
          tab = tab,
          onTabChange = { tab = it },
          homeTabLabel = homeTabLabel,
          statsTabLabel = statsTabLabel,
          appsTabLabel = appsTabLabel,
          supportTabLabel = supportTabLabel,
        )
      }

      val notificationBottomPadding = if (landscapeControl) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 18.dp
      } else {
        floatingBottomBarReserve + 10.dp
      }

      RuntimeApplyStatusCard(
        visible = appUpdate.runtimeApplyVisible,
        status = appUpdate.runtimeApplyStatus,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .zIndex(4.8f),
        bottomPadding = notificationBottomPadding + 62.dp,
      )


      FloatingSnackbarHost(
        hostState = snackHost,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .zIndex(5f),
        bottomPadding = notificationBottomPadding,
      )
    }

    if (startupHostVisible) {
      StartupDialogHost(
        uiState = uiState,
        onRetry = actions::retryDaemonStartup,
        onReinstall = actions::openModuleInstaller,
        onFullyHidden = { startupHostVisible = false },
      )
    }
  }
}


@Composable
private fun LandscapeShellContent(
  tab: Tab,
  onTabChange: (Tab) -> Unit,
  title: String,
  canGoBack: Boolean,
  onBack: () -> Unit,
  onTitleClick: () -> Unit,
  homeTabLabel: String,
  statsTabLabel: String,
  appsTabLabel: String,
  supportTabLabel: String,
  programLogTarget: ProgramLogTarget?,
  onOpenLogs: (ProgramLogTarget?) -> Unit,
  onOpenBackup: () -> Unit,
  onOpenProgramUpdates: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenRemoteSetup: () -> Unit,
  remoteTargetName: String,
  remoteTargetAddress: String,
  onRemoteDisconnect: () -> Unit,
  appUpdate: AppUpdateUiState,
  uiStateFlow: StateFlow<UiState>,
  appsRoute: AppsRoute,
  onOpenProgram: (String) -> Unit,
  onOpenProfile: (String, String) -> Unit,
  onOpenAnalysisTools: () -> Unit,
  onOpenOptionalTools: () -> Unit,
  onOpenConstructionStudio: () -> Unit,
  onOpenDpiDetector: () -> Unit,
  onOpenNfqwsTester: () -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(end = 104.dp),
    ) {
      LandscapeContentHeader(
        title = title,
        canGoBack = canGoBack,
        onBack = onBack,
        onTitleClick = onTitleClick,
        isHome = tab == Tab.HOME,
      )
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
          onOpenProgram = onOpenProgram,
          onOpenProfile = onOpenProfile,
          onOpenAnalysisTools = onOpenAnalysisTools,
          onOpenOptionalTools = onOpenOptionalTools,
          onOpenConstructionStudio = onOpenConstructionStudio,
          onOpenDpiDetector = onOpenDpiDetector,
          onOpenNfqwsTester = onOpenNfqwsTester,
          actions = actions,
          snackHost = snackHost,
          tproxyEnabled = appUpdate.tproxyEnabled,
          landscapeControl = true,
        )
      }
    }

    LandscapeQuickActions(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = 10.dp, end = 10.dp),
      programLogTarget = programLogTarget,
      onOpenLogs = onOpenLogs,
      onOpenBackup = onOpenBackup,
      onOpenProgramUpdates = onOpenProgramUpdates,
      onOpenSettings = onOpenSettings,
      onOpenRemoteSetup = onOpenRemoteSetup,
      remoteTargetName = remoteTargetName,
      remoteTargetAddress = remoteTargetAddress,
      onRemoteDisconnect = onRemoteDisconnect,
    )

    LandscapeRightNav(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 12.dp, bottom = 12.dp),
      tab = tab,
      onTabChange = onTabChange,
      homeTabLabel = homeTabLabel,
      statsTabLabel = statsTabLabel,
      appsTabLabel = appsTabLabel,
      supportTabLabel = supportTabLabel,
    )
  }
}

@Composable
private fun LandscapeContentHeader(
  title: String,
  canGoBack: Boolean,
  onBack: () -> Unit,
  onTitleClick: () -> Unit,
  isHome: Boolean,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(52.dp)
      .padding(start = 14.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (canGoBack) {
      IconButton(onClick = onBack) {
        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
      }
    }
    Text(
      text = title,
      letterSpacing = 1.6.sp,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = if (isHome) {
        Modifier
          .weight(1f)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { onTitleClick() }
      } else {
        Modifier.weight(1f)
      },
    )
  }
}


@Composable
private fun RuntimeApplyStatusCard(
  visible: Boolean,
  status: ApiModels.RuntimeApplyStatus,
  modifier: Modifier = Modifier,
  bottomPadding: Dp,
) {
  val density = LocalDensity.current.density

  AnimatedVisibility(
    visible = visible && status.visible,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp)
      .padding(bottom = bottomPadding),
    enter = slideInVertically(animationSpec = tween(260, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(180)),
    exit = slideOutVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(180)),
  ) {
    val shape = RoundedCornerShape(24.dp)
    val accent = when (status.state) {
      "success" -> Color(0xFF22C55E)
      "failed", "deferred_until_start", "no_active_runtime" -> MaterialTheme.colorScheme.error
      else -> MaterialTheme.colorScheme.primary
    }
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clip(shape),
      shape = shape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
      tonalElevation = 0.dp,
      shadowElevation = 8.dp,
      border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = accent.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        ) {
          val icon = when (status.state) {
            "success" -> Icons.Filled.CheckCircle
            "failed", "deferred_until_start", "no_active_runtime" -> Icons.Filled.ErrorOutline
            else -> Icons.Filled.Sync
          }
          Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
              .padding(7.dp)
              .size(18.dp),
            tint = accent,
          )
        }

        Box(
          modifier = Modifier
            .weight(1f)
            .clipToBounds(),
        ) {
          AnimatedContent(
            targetState = status.state to status.message,
            transitionSpec = {
              val enter = slideInVertically(animationSpec = tween(260, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(160))
              val exit = slideOutVertically(animationSpec = tween(260, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(160))
              (enter togetherWith exit).using(SizeTransform(clip = false))
            },
            label = "runtime_apply_cube_bottom_to_top",
          ) { target ->
            val message = when (target.first) {
              "failed", "deferred_until_start", "no_active_runtime" -> stringResource(R.string.runtime_apply_failed_restart_service)
              else -> target.second
            }
            val flip by transition.animateFloat(
              transitionSpec = { tween(durationMillis = 260, easing = FastOutSlowInEasing) },
              label = "runtime_apply_cube_flip",
            ) { animatedTarget -> if (animatedTarget == EnterExitState.Visible) 0f else -90f }
            Text(
              text = message.ifBlank { "Применяю правила" },
              modifier = Modifier.graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                rotationX = flip
                cameraDistance = 18f * density
              },
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FloatingSnackbarHost(
  hostState: SnackbarHostState,
  modifier: Modifier = Modifier,
  bottomPadding: Dp,
) {
  SnackbarHost(
    hostState = hostState,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp)
      .padding(bottom = bottomPadding),
  ) { data ->
    val shape = RoundedCornerShape(24.dp)
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clip(shape),
      shape = shape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
      tonalElevation = 0.dp,
      shadowElevation = 6.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        ) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier
              .padding(7.dp)
              .size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
        }

        Text(
          text = data.visuals.message,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        data.visuals.actionLabel?.let { label ->
          TextButton(
            onClick = { data.performAction() },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
          ) {
            Text(label, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}



@Composable
private fun RemoteTargetTopLine(
  name: String,
  address: String,
  onRemoteSetup: () -> Unit,
  onDisconnect: () -> Unit,
) {
  if (!REMOTE_CONTROL_FEATURE_ENABLED || name.isBlank()) return
  var expanded by remember { mutableStateOf(false) }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(28.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable { expanded = true }
        .padding(horizontal = 10.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
      Text(
        text = "Настройка: $name",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = address,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(16.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text("Удалённая настройка") },
        onClick = {
          expanded = false
          onRemoteSetup()
        },
      )
      DropdownMenuItem(
        text = { Text("Отключиться") },
        onClick = {
          expanded = false
          onDisconnect()
        },
      )
    }
  }
}

@Composable
private fun FloatingTopBarCard(
  modifier: Modifier = Modifier,
  title: String,
  canGoBack: Boolean,
  onBack: () -> Unit,
  isHome: Boolean,
  onTitleClick: () -> Unit,
  programLogTarget: ProgramLogTarget?,
  onOpenLogs: (ProgramLogTarget?) -> Unit,
  onOpenBackup: () -> Unit,
  onOpenProgramUpdates: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenRemoteSetup: () -> Unit,
  remoteTargetName: String,
  remoteTargetAddress: String,
  onRemoteDisconnect: () -> Unit,
) {
  val shape = RoundedCornerShape(24.dp)
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
        .height(if (REMOTE_CONTROL_FEATURE_ENABLED && remoteTargetName.isNotBlank()) 88.dp else 58.dp)
        .clip(shape),
      shape = shape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(start = if (canGoBack) 2.dp else 16.dp, end = 6.dp),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (canGoBack) {
            IconButton(
              onClick = onBack,
              modifier = Modifier.size(46.dp),
            ) {
              Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
          }
          Text(
            text = title,
            letterSpacing = 1.5.sp,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (isHome) {
              Modifier
                .weight(1f)
                .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                ) { onTitleClick() }
            } else {
              Modifier.weight(1f)
            },
          )
          TopBarActionCluster(
            programLogTarget = programLogTarget,
            onOpenLogs = onOpenLogs,
            onOpenBackup = onOpenBackup,
            onOpenProgramUpdates = onOpenProgramUpdates,
            onOpenSettings = onOpenSettings,
            onOpenRemoteSetup = onOpenRemoteSetup,
          )
        }
        RemoteTargetTopLine(
          name = remoteTargetName,
          address = remoteTargetAddress,
          onRemoteSetup = onOpenRemoteSetup,
          onDisconnect = onRemoteDisconnect,
        )
      }
    }
  }
}

@Composable
private fun FloatingBottomNavigationCard(
  modifier: Modifier = Modifier,
  compact: Boolean,
  tab: Tab,
  onTabChange: (Tab) -> Unit,
  homeTabLabel: String,
  statsTabLabel: String,
  appsTabLabel: String,
  supportTabLabel: String,
) {
  val shape = RoundedCornerShape(24.dp)
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
        FloatingBottomNavItem(
          selected = tab == Tab.HOME,
          onClick = { onTabChange(Tab.HOME) },
          compact = compact,
          label = homeTabLabel,
          icon = { Icon(Icons.Filled.Power, contentDescription = homeTabLabel, modifier = Modifier.size(22.dp)) },
        )
        FloatingBottomNavItem(
          selected = tab == Tab.STATS,
          onClick = { onTabChange(Tab.STATS) },
          compact = compact,
          label = statsTabLabel,
          icon = { Icon(Icons.Filled.Equalizer, contentDescription = statsTabLabel, modifier = Modifier.size(22.dp)) },
        )
        FloatingBottomNavItem(
          selected = tab == Tab.APPS,
          onClick = { onTabChange(Tab.APPS) },
          compact = compact,
          label = appsTabLabel,
          icon = { Icon(Icons.Filled.Apps, contentDescription = appsTabLabel, modifier = Modifier.size(22.dp)) },
        )
        FloatingBottomNavItem(
          selected = tab == Tab.SUPPORT,
          onClick = { onTabChange(Tab.SUPPORT) },
          compact = compact,
          label = supportTabLabel,
          icon = { Icon(Icons.Filled.Info, contentDescription = supportTabLabel, modifier = Modifier.size(22.dp)) },
        )
      }
    }
  }
}

@Composable
private fun RowScope.FloatingBottomNavItem(
  selected: Boolean,
  onClick: () -> Unit,
  compact: Boolean,
  label: String,
  icon: @Composable () -> Unit,
) {
  val itemColor = if (selected) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
  }
  val indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
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
        .background(if (selected) indicatorColor else Color.Transparent)
        .padding(
          horizontal = if (selected) 16.dp else 6.dp,
          vertical = if (selected) 5.dp else 3.dp,
        ),
      contentAlignment = Alignment.Center,
    ) {
      CompositionLocalProvider(LocalContentColor provides itemColor) {
        icon()
      }
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

@Composable
private fun glassBottomBarItemColors() = NavigationBarItemDefaults.colors(
  selectedIconColor = MaterialTheme.colorScheme.primary,
  selectedTextColor = MaterialTheme.colorScheme.primary,
  indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
  unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
  unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
)

@Composable
private fun LandscapeQuickActions(
  modifier: Modifier = Modifier,
  programLogTarget: ProgramLogTarget?,
  onOpenLogs: (ProgramLogTarget?) -> Unit,
  onOpenBackup: () -> Unit,
  onOpenProgramUpdates: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenRemoteSetup: () -> Unit,
  remoteTargetName: String,
  remoteTargetAddress: String,
  onRemoteDisconnect: () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
    tonalElevation = 3.dp,
    shadowElevation = 8.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        if (REMOTE_CONTROL_FEATURE_ENABLED) {
          IconButton(onClick = { onOpenRemoteSetup() }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Filled.Sync, contentDescription = "Remote", modifier = Modifier.size(26.dp))
          }
        }
        IconButton(onClick = { onOpenSettings() }, modifier = Modifier.size(44.dp)) {
          Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings), modifier = Modifier.size(26.dp))
        }
        if (supportsArm64ToolUpdates()) {
          IconButton(onClick = { onOpenProgramUpdates() }, modifier = Modifier.size(44.dp)) {
            Icon(
              painter = painterResource(R.drawable.ic_program_updates_custom),
              contentDescription = stringResource(R.string.cd_program_updates),
              modifier = Modifier.size(26.dp),
            )
          }
        }
        IconButton(onClick = { onOpenBackup() }, modifier = Modifier.size(44.dp)) {
          Icon(Icons.Filled.CloudDownload, contentDescription = stringResource(R.string.cd_backup), modifier = Modifier.size(26.dp))
        }
        IconButton(onClick = { onOpenLogs(programLogTarget) }, modifier = Modifier.size(44.dp)) {
          Icon(Icons.Filled.BugReport, contentDescription = stringResource(R.string.cd_logs), modifier = Modifier.size(26.dp))
        }
      }
      RemoteTargetTopLine(
        name = remoteTargetName,
        address = remoteTargetAddress,
        onRemoteSetup = onOpenRemoteSetup,
        onDisconnect = onRemoteDisconnect,
      )
    }
  }
}

@Composable
private fun LandscapeRightNav(
  modifier: Modifier = Modifier,
  tab: Tab,
  onTabChange: (Tab) -> Unit,
  homeTabLabel: String,
  statsTabLabel: String,
  appsTabLabel: String,
  supportTabLabel: String,
) {
  Surface(
    modifier = modifier
      .width(76.dp)
      .fillMaxHeight(0.70f),
    shape = RoundedCornerShape(38.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
    tonalElevation = 3.dp,
    shadowElevation = 8.dp,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceEvenly,
    ) {
      LandscapeNavIcon(
        selected = tab == Tab.HOME,
        onClick = { onTabChange(Tab.HOME) },
        icon = { Icon(Icons.Filled.Power, contentDescription = homeTabLabel, modifier = Modifier.size(30.dp)) },
      )
      LandscapeNavIcon(
        selected = tab == Tab.STATS,
        onClick = { onTabChange(Tab.STATS) },
        icon = { Icon(Icons.Filled.Equalizer, contentDescription = statsTabLabel, modifier = Modifier.size(30.dp)) },
      )
      LandscapeNavIcon(
        selected = tab == Tab.APPS,
        onClick = { onTabChange(Tab.APPS) },
        icon = { Icon(Icons.Filled.Apps, contentDescription = appsTabLabel, modifier = Modifier.size(30.dp)) },
      )
      LandscapeNavIcon(
        selected = tab == Tab.SUPPORT,
        onClick = { onTabChange(Tab.SUPPORT) },
        icon = { Icon(Icons.Filled.Info, contentDescription = supportTabLabel, modifier = Modifier.size(30.dp)) },
      )
    }
  }
}

@Composable
private fun LandscapeNavIcon(
  selected: Boolean,
  onClick: () -> Unit,
  icon: @Composable () -> Unit,
) {
  val bgAlpha by animateFloatAsState(
    targetValue = if (selected) 0.24f else 0.0f,
    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
    label = "landscape_nav_bg",
  )
  Box(
    modifier = Modifier
      .size(58.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.primary.copy(alpha = bgAlpha))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    CompositionLocalProvider(
      LocalContentColor provides if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
    ) {
      icon()
    }
  }
}



@Composable
private fun PortraitSettingsShelf(
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  // Settings are now shown as a dedicated full-screen window (Material 3),
  // not a draggable bottom-sheet card. The loading state is handled by the
  // provided content, so this frame does not show its own spinner.
  SettingsScreen(
    onDismiss = onDismiss,
    loading = false,
  ) {
    content()
  }
}

@Composable
private fun LandscapeSettingsShelf(
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  // Unified with portrait: full-screen Material 3 settings window.
  SettingsScreen(
    onDismiss = onDismiss,
    loading = false,
  ) {
    content()
  }
}


@Composable
private fun LandscapeLogsShelf(
  logs: List<LogLine>,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  var shelfVisible by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    shelfVisible = true
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing),
      contentAlignment = Alignment.CenterStart,
    ) {
      AnimatedVisibility(
        visible = shelfVisible,
        enter = fadeIn(tween(durationMillis = 180, easing = FastOutSlowInEasing)) +
          slideInHorizontally(
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            initialOffsetX = { -it / 3 },
          ) +
          scaleIn(
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            initialScale = 0.96f,
          ),
        exit = fadeOut(tween(durationMillis = 120)) +
          slideOutHorizontally(
            animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
            targetOffsetX = { -it / 4 },
          ),
      ) {
        Surface(
          modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.58f)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
          shape = RoundedCornerShape(28.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
          tonalElevation = 4.dp,
          shadowElevation = 10.dp,
        ) {
          Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
                Button(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
              }
            }
            if (logs.isEmpty()) {
              Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            } else {
              androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                items(logs.size) { idx ->
                  val l = logs[idx]
                  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))) {
                    Column(Modifier.padding(12.dp)) {
                      Text("${l.ts} • ${l.level}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                      Spacer(Modifier.height(4.dp))
                      Text(l.msg)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}


private fun supportsProgramLogs(programId: String, profile: String?): Boolean {
  return if (profile == null) {
    programId in setOf("operaproxy", "dnscrypt", "tor", "tgwsproxy")
  } else {
    programId in setOf(
      "nfqws",
      "nfqws2",
      "byedpi",
      "dpitunnel",
      "sing-box",
      "hysteria2",
      "wireproxy",
      "myproxy",
      "myprogram",
      "openvpn",
      "amneziawg",
      "tun2socks",
      "mihomo",
      "mieru",
    )
  }
}


@Composable
private fun TopBarActionCluster(
  programLogTarget: ProgramLogTarget?,
  onOpenLogs: (ProgramLogTarget?) -> Unit,
  onOpenBackup: () -> Unit,
  onOpenProgramUpdates: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenRemoteSetup: () -> Unit,
) {
  val target = programLogTarget
  var collapsed by remember { mutableStateOf(false) }
  val glow = remember { Animatable(0f) }
  val inProgramMode = target != null
  val showFullActions = !inProgramMode || !collapsed

  LaunchedEffect(target?.key) {
    if (target == null) {
      collapsed = false
      glow.animateTo(0f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
    } else {
      collapsed = false
      glow.snapTo(0f)
      glow.animateTo(0.16f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
      delay(360)
      glow.animateTo(0.58f, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing))
      collapsed = true
      glow.animateTo(0.20f, animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
    }
  }

  val outerEndPadding by animateDpAsState(
    targetValue = if (inProgramMode) 2.dp else 0.dp,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessMediumLow,
    ),
    label = "topBarActionEndPadding",
  )
  val innerHorizontalPadding by animateDpAsState(
    targetValue = when {
      !inProgramMode -> 0.dp
      collapsed -> 2.dp
      else -> 4.dp
    },
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessMediumLow,
    ),
    label = "topBarActionInnerPadding",
  )

  Box(
    modifier = Modifier
      .animateContentSize(
        animationSpec = spring(
          dampingRatio = Spring.DampingRatioNoBouncy,
          stiffness = Spring.StiffnessMediumLow,
        ),
        alignment = Alignment.CenterEnd,
      )
      .padding(end = outerEndPadding)
      .background(MaterialTheme.colorScheme.error.copy(alpha = glow.value), CircleShape)
      .padding(horizontal = innerHorizontalPadding),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { onOpenLogs(target) }) {
        Icon(Icons.Filled.BugReport, contentDescription = stringResource(R.string.cd_logs))
      }
      if (REMOTE_CONTROL_FEATURE_ENABLED) {
        CollapsingTopBarAction(visible = showFullActions) {
          IconButton(onClick = onOpenRemoteSetup) {
            Icon(Icons.Filled.Sync, contentDescription = "Remote")
          }
        }
      }
      CollapsingTopBarAction(visible = showFullActions) {
        IconButton(onClick = onOpenBackup) {
          Icon(Icons.Filled.CloudDownload, contentDescription = stringResource(R.string.cd_backup))
        }
      }
      if (supportsArm64ToolUpdates()) {
        CollapsingTopBarAction(visible = showFullActions) {
          IconButton(onClick = onOpenProgramUpdates) {
            Icon(
              painter = painterResource(R.drawable.ic_program_updates_custom),
              contentDescription = stringResource(R.string.cd_program_updates),
            )
          }
        }
      }
      CollapsingTopBarAction(visible = showFullActions) {
        IconButton(onClick = onOpenSettings) {
          Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
        }
      }
    }
  }
}

@Composable
private fun CollapsingTopBarAction(
  visible: Boolean,
  content: @Composable () -> Unit,
) {
  val width by animateDpAsState(
    targetValue = if (visible) 48.dp else 0.dp,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessMediumLow,
    ),
    label = "topBarActionWidth",
  )
  val alpha by animateFloatAsState(
    targetValue = if (visible) 1f else 0f,
    animationSpec = tween(durationMillis = if (visible) 220 else 170, easing = FastOutSlowInEasing),
    label = "topBarActionAlpha",
  )

  Box(
    modifier = Modifier
      .width(width)
      .clipToBounds(),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .width(48.dp)
        .alpha(alpha),
      contentAlignment = Alignment.Center,
    ) {
      content()
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
  onOpenAnalysisTools: () -> Unit,
  onOpenOptionalTools: () -> Unit,
  onOpenConstructionStudio: () -> Unit,
  onOpenDpiDetector: () -> Unit,
  onOpenNfqwsTester: () -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  tproxyEnabled: Boolean = false,
  landscapeControl: Boolean = false,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val stateHolder = rememberSaveableStateHolder()
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  LaunchedEffect(tab) {
    focusManager.clearFocus(force = true)
    keyboardController?.hide()
  }

  Crossfade(
    targetState = tab,
    animationSpec = tween(durationMillis = 160),
    label = "main_tab_crossfade",
  ) { currentTab ->
    stateHolder.SaveableStateProvider(currentTab.name) {
      Box(Modifier.fillMaxSize()) {
        when (currentTab) {
          Tab.HOME -> HomeScreen(
            uiStateFlow = uiStateFlow,
            actions = actions,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
          )
          Tab.STATS -> StatsScreen(uiStateFlow = uiStateFlow, actions = actions, topContentPadding = topContentPadding, bottomContentPadding = bottomContentPadding)
          Tab.SUPPORT -> SupportScreen(topContentPadding = topContentPadding)
          Tab.APPS -> AppsHost(
            uiStateFlow = uiStateFlow,
            route = appsRoute,
            onOpenProgram = onOpenProgram,
            onOpenProfile = onOpenProfile,
            onOpenAnalysisTools = onOpenAnalysisTools,
            onOpenOptionalTools = onOpenOptionalTools,
            onOpenConstructionStudio = onOpenConstructionStudio,
            onOpenDpiDetector = onOpenDpiDetector,
            onOpenNfqwsTester = onOpenNfqwsTester,
            actions = actions,
            snackHost = snackHost,
            tproxyEnabled = tproxyEnabled,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
          )
        }
      }
    }
  }
}
