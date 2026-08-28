package com.android.zdtd.service.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.android.zdtd.service.InstallConflictUi
import com.android.zdtd.service.R
import com.android.zdtd.service.RootState
import com.android.zdtd.service.SetupUiState

private const val REMOTE_SETUP_ENTRY_ENABLED = false
// Remote setup is intentionally hidden/disabled for now: the feature is not stable yet.
// Keep the implementation and callback so development can continue later.


private fun isArm64OnlySupported(): Boolean {
  // Module binaries are built for arm64-v8a and armeabi-v7a.
  return Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }
}

private fun isModuleInstallOsSupported(): Boolean {
  return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}

private fun needsUnofficialAndroidInstallWarning(): Boolean {
  return Build.VERSION.SDK_INT in Build.VERSION_CODES.P until Build.VERSION_CODES.R
}

@Composable
private fun setupIsLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

@Composable
private fun setupTopBarColor() = if (setupIsLightTheme()) {
  MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f)
} else {
  MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
}

@Composable
private fun setupPanelColor(alpha: Float = 0.86f) = if (setupIsLightTheme()) {
  MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)
} else {
  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
}

@Composable
private fun setupPanelAccentWash(accent: androidx.compose.ui.graphics.Color, alpha: Float) = if (setupIsLightTheme()) {
  accent.copy(alpha = alpha.coerceAtMost(0.08f))
} else {
  accent.copy(alpha = alpha)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScaffold(
  title: String? = null,
  content: @Composable (PaddingValues) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  Scaffold(
    containerColor = scheme.background,
    topBar = {
      CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
          containerColor = setupTopBarColor(),
          scrolledContainerColor = setupTopBarColor(),
          titleContentColor = scheme.onSurface,
        ),
        title = {
          Text(
            title ?: stringResource(R.string.app_name),
            letterSpacing = if (title == null) 2.sp else 0.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
          )
        },
      )
    },
    content = content,
  )
}

@Composable
private fun SetupAlertDialog(
  onDismissRequest: () -> Unit,
  titleText: String,
  bodyText: String,
  confirmButtonText: String,
  onConfirm: () -> Unit,
  dismissButtonText: String? = null,
  onDismiss: (() -> Unit)? = null,
) {
  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = { Text(titleText) },
    text = { Text(bodyText) },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text(confirmButtonText) }
    },
    dismissButton = {
      if (dismissButtonText != null && onDismiss != null) {
        TextButton(onClick = onDismiss) { Text(dismissButtonText) }
      }
    },
  )
}

@Composable
fun WelcomeScreen(onAccept: () -> Unit) {
  val arm64Ok = remember { isArm64OnlySupported() }
  val compact = rememberIsCompactWidth()
  val screenPadding = rememberAdaptiveScreenPadding()

  SetupScaffold { padding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .background(MaterialTheme.colorScheme.background),
    ) {
      Box(
        modifier = Modifier
          .matchParentSize()
          .background(
            Brush.verticalGradient(
              listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = if (setupIsLightTheme()) 0.025f else 0.065f),
                Color.Transparent,
                MaterialTheme.colorScheme.secondary.copy(alpha = if (setupIsLightTheme()) 0.018f else 0.040f),
              ),
            ),
          ),
      )

      Column(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(horizontal = screenPadding, vertical = 10.dp)
          .widthIn(max = 720.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ModernSetupHeroCard(
          title = stringResource(R.string.setup_welcome_title),
          body = stringResource(R.string.setup_welcome_body),
          accent = MaterialTheme.colorScheme.primary,
          pose = SetupMascotPose.WELCOME,
          compact = compact,
          badge = stringResource(R.string.app_name),
        )

        InstallerSectionHeader(
          title = stringResource(R.string.setup_features_title),
          trailing = null,
          accent = MaterialTheme.colorScheme.primary,
        )
        ModernSetupInfoCard(
          title = stringResource(R.string.app_name),
          body = stringResource(R.string.setup_features_body),
          accent = MaterialTheme.colorScheme.primary,
        )
        ModernSetupInfoCard(
          title = stringResource(R.string.setup_notes_title),
          body = stringResource(R.string.setup_notes_body),
          accent = MaterialTheme.colorScheme.secondary,
        )

        if (!arm64Ok) {
          InstallerNoticeCard(
            text = stringResource(
              R.string.setup_arch_unsupported_fmt,
              Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            ),
            accent = MaterialTheme.colorScheme.error,
          )
        }

        SetupPrimaryButton(
          onClick = onAccept,
          enabled = arm64Ok,
          modifier = Modifier.fillMaxWidth(),
          text = stringResource(R.string.common_continue),
        )
        Spacer(Modifier.height(18.dp))
      }
    }
  }
}

@Composable
fun RootInfoScreen(rootState: RootState, onRequest: () -> Unit, onRemoteSetup: () -> Unit) {
  val arm64Ok = remember { isArm64OnlySupported() }
  val compact = rememberIsCompactWidth()
  val screenPadding = rememberAdaptiveScreenPadding()
  val rootDescription = stringResource(R.string.setup_root_body)
  val rootHeroBody = rootDescription.substringBefore("\n\n")
  val rootDetailsBody = rootDescription.substringAfter("\n\n", "")

  SetupScaffold { padding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .background(MaterialTheme.colorScheme.background),
    ) {
      Box(
        modifier = Modifier
          .matchParentSize()
          .background(
            Brush.verticalGradient(
              listOf(
                MaterialTheme.colorScheme.secondary.copy(alpha = if (setupIsLightTheme()) 0.022f else 0.060f),
                Color.Transparent,
                MaterialTheme.colorScheme.primary.copy(alpha = if (setupIsLightTheme()) 0.018f else 0.040f),
              ),
            ),
          ),
      )

      Column(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(horizontal = screenPadding, vertical = 10.dp)
          .widthIn(max = 720.dp)
          .fillMaxWidth()
          .animateContentSize(animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ModernSetupHeroCard(
          title = stringResource(R.string.setup_root_title),
          body = rootHeroBody,
          accent = MaterialTheme.colorScheme.secondary,
          pose = SetupMascotPose.ROOT,
          compact = compact,
          badge = stringResource(R.string.setup_request_root),
        )

        InstallerSectionHeader(
          title = stringResource(R.string.setup_root_title),
          trailing = null,
          accent = MaterialTheme.colorScheme.secondary,
        )

        if (rootDetailsBody.isNotBlank()) {
          ModernSetupInfoCard(
            title = stringResource(R.string.setup_notes_title),
            body = rootDetailsBody,
            accent = MaterialTheme.colorScheme.secondary,
          )
        }

        when (rootState) {
          RootState.CHECKING -> {
            ModernRootStateCard(
              checking = true,
              denied = false,
              text = stringResource(R.string.setup_root_waiting),
            )
          }
          RootState.DENIED, RootState.GRANTED -> {
            ModernRootStateCard(
              checking = false,
              denied = rootState == RootState.DENIED,
              text = if (rootState == RootState.DENIED) {
                stringResource(R.string.setup_root_denied)
              } else {
                stringResource(R.string.setup_request_root)
              },
            )

            if (!arm64Ok) {
              InstallerNoticeCard(
                text = stringResource(
                  R.string.setup_arch_unsupported_fmt,
                  Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                ),
                accent = MaterialTheme.colorScheme.error,
              )
            }

            SetupPrimaryButton(
              onClick = onRequest,
              enabled = arm64Ok,
              modifier = Modifier.fillMaxWidth(),
              text = stringResource(R.string.setup_request_root),
            )

            if (REMOTE_SETUP_ENTRY_ENABLED) {
              OutlinedButton(
                onClick = onRemoteSetup,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text("Удалённая настройка")
              }
            }
          }
        }
        Spacer(Modifier.height(18.dp))
      }
    }
  }
}



private enum class SetupMascotPose {
  WELCOME,
  ROOT,
  READY,
  INSTALLING,
  SUCCESS,
  ERROR,
}

@Composable
private fun ModernSetupHeroCard(
  title: String,
  body: String,
  accent: Color,
  pose: SetupMascotPose,
  compact: Boolean,
  badge: String,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .height(if (compact) 224.dp else 244.dp),
    shape = RoundedCornerShape(26.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.94f else 0.72f),
    border = BorderStroke(1.dp, accent.copy(alpha = if (setupIsLightTheme()) 0.16f else 0.28f)),
    tonalElevation = 0.dp,
    shadowElevation = if (setupIsLightTheme()) 1.dp else 2.dp,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.horizontalGradient(
            listOf(
              setupPanelAccentWash(accent, 0.10f),
              Color.Transparent,
              Color.Transparent,
            ),
          ),
        ),
    ) {
      Column(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .fillMaxWidth(if (compact) 0.61f else 0.62f)
          .padding(start = 18.dp, top = 18.dp, bottom = 18.dp, end = 8.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = accent.copy(alpha = 0.11f),
          border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
        ) {
          Text(
            text = badge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.height(11.dp))
        Text(
          text = title,
          style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
          maxLines = if (compact) 5 else 6,
          overflow = TextOverflow.Ellipsis,
        )
      }

      ZdtdSetupMascot(
        pose = pose,
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .width(if (compact) 146.dp else 180.dp)
          .fillMaxHeight(),
      )
    }
  }
}

@Composable
private fun ModernSetupInfoCard(
  title: String,
  body: String,
  accent: Color,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.92f else 0.60f),
    border = BorderStroke(1.dp, accent.copy(alpha = if (setupIsLightTheme()) 0.12f else 0.22f)),
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier
        .background(
          Brush.horizontalGradient(
            listOf(
              setupPanelAccentWash(accent, 0.07f),
              Color.Transparent,
            ),
          ),
        )
        .padding(13.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
      ) {
        Icon(
          imageVector = Icons.Filled.Security,
          contentDescription = null,
          tint = accent,
          modifier = Modifier.padding(8.dp).size(18.dp),
        )
      }
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ModernRootStateCard(
  checking: Boolean,
  denied: Boolean,
  text: String,
) {
  val accent = when {
    denied -> MaterialTheme.colorScheme.error
    checking -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
  }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.92f else 0.60f),
    border = BorderStroke(1.dp, accent.copy(alpha = if (setupIsLightTheme()) 0.14f else 0.24f)),
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(RoundedCornerShape(13.dp))
          .background(accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
      ) {
        if (checking) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.2.dp,
            color = accent,
          )
        } else {
          Icon(
            imageVector = if (denied) Icons.Filled.ErrorOutline else Icons.Filled.Security,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(21.dp),
          )
        }
      }
      Text(
        text = text,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun RebootRequiredScreen(
  setup: SetupUiState,
  text: String,
  onReboot: () -> Unit,
) {
  val screenPadding = rememberAdaptiveScreenPadding()
  SetupScaffold { padding ->
    Box(
      Modifier
        .fillMaxSize()
        .padding(padding),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier
          .padding(screenPadding)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          imageVector = Icons.Filled.SystemUpdateAlt,
          contentDescription = null,
          modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = stringResource(R.string.setup_reboot_required_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = if (text.isBlank()) {
            stringResource(R.string.setup_reboot_required_body_default)
          } else text,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))


        CooldownRebootButton(
          activeKey = text.ifBlank { "reboot-required" },
          onReboot = onReboot,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
fun InstallModuleScreen(
  rootState: RootState,
  setup: SetupUiState,
  onInstall: () -> Unit,
  onManualConfirm: () -> Unit,
  onManualDismiss: () -> Unit,
  onContinue: () -> Unit,
  onReboot: () -> Unit,
  onRefreshConflicts: () -> Unit,
  onToggleConflictRemove: (String, Boolean) -> Unit,
  onRefreshZygiskInstallMarker: () -> Unit,
  onToggleZygiskInstall: (Boolean) -> Unit,
  onConfirmZygiskInstall: () -> Unit,
  onDismissZygiskInstallConfirm: () -> Unit,
  onDismissZygiskInstallRecovery: () -> Unit,
  onDismissMetamoduleInstallBlocked: () -> Unit,
  onRetryInstallWithoutZygisk: () -> Unit,
) {
  val arm64Ok = remember { isArm64OnlySupported() }
  val compact = rememberIsCompactWidth()
  val screenPadding = rememberAdaptiveScreenPadding()
  var showInstallLog by rememberSaveable(setup.installing, setup.installOk, setup.manualZipSaved) { mutableStateOf(false) }
  var showUnofficialAndroidWarning by rememberSaveable { mutableStateOf(false) }
  val osInstallOk = remember { isModuleInstallOsSupported() }
  val needsAndroidWarning = remember { needsUnofficialAndroidInstallWarning() }
  val canShowInstallLog = !setup.installing && setup.installLog.isNotBlank()
  val animatedInstallProgress by animateFloatAsState(
    targetValue = setup.installProgressPercent.coerceIn(0, 100) / 100f,
    animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing),
    label = "install_progress_float",
  )
  val animatedInstallPercent by animateIntAsState(
    targetValue = setup.installProgressPercent.coerceIn(0, 100),
    animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing),
    label = "install_progress_int",
  )
  val visualState = when {
    setup.installOk -> InstallerVisualState.SUCCESS
    !setup.installError.isNullOrBlank() -> InstallerVisualState.ERROR
    setup.installing -> InstallerVisualState.INSTALLING
    else -> InstallerVisualState.READY
  }
  val canInstall = arm64Ok && osInstallOk && rootState == RootState.GRANTED && !setup.installing && !setup.installOk
  val requestInstall: () -> Unit = {
    if (needsAndroidWarning) {
      showUnofficialAndroidWarning = true
    } else {
      onInstall()
    }
  }

  LaunchedEffect(Unit) {
    onRefreshConflicts()
    onRefreshZygiskInstallMarker()
  }

  if (arm64Ok && setup.showManualDialog) {
    val extra = if (setup.oldVersionDetected) {
      "\n\n" + stringResource(R.string.setup_manual_old_version_extra)
    } else {
      ""
    }
    SetupAlertDialog(
      onDismissRequest = onManualDismiss,
      titleText = stringResource(R.string.common_attention),
      bodyText = setup.manualDialogText + extra,
      confirmButtonText = stringResource(R.string.setup_save_zip),
      onConfirm = onManualConfirm,
      dismissButtonText = stringResource(R.string.common_cancel),
      onDismiss = onManualDismiss,
    )
  }

  if (showUnofficialAndroidWarning) {
    SetupAlertDialog(
      onDismissRequest = { showUnofficialAndroidWarning = false },
      titleText = stringResource(R.string.setup_android_unofficial_title),
      bodyText = stringResource(R.string.setup_android_unofficial_body),
      confirmButtonText = stringResource(R.string.setup_android_unofficial_accept),
      onConfirm = {
        showUnofficialAndroidWarning = false
        onInstall()
      },
      dismissButtonText = stringResource(R.string.setup_android_unofficial_decline),
      onDismiss = { showUnofficialAndroidWarning = false },
    )
  }

  if (setup.showZygiskInstallConfirm) {
    SetupAlertDialog(
      onDismissRequest = onDismissZygiskInstallConfirm,
      titleText = stringResource(R.string.setup_zygisk_confirm_title),
      bodyText = stringResource(R.string.setup_zygisk_confirm_body),
      confirmButtonText = stringResource(R.string.setup_zygisk_confirm_yes),
      onConfirm = onConfirmZygiskInstall,
      dismissButtonText = stringResource(R.string.setup_zygisk_confirm_no),
      onDismiss = onDismissZygiskInstallConfirm,
    )
  }

  if (setup.showZygiskInstallRecoveryDialog) {
    SetupAlertDialog(
      onDismissRequest = onDismissZygiskInstallRecovery,
      titleText = stringResource(R.string.setup_zygisk_recovery_title),
      bodyText = stringResource(R.string.setup_zygisk_recovery_body),
      confirmButtonText = stringResource(R.string.setup_zygisk_recovery_retry_without),
      onConfirm = onRetryInstallWithoutZygisk,
      dismissButtonText = stringResource(R.string.setup_zygisk_recovery_no),
      onDismiss = onDismissZygiskInstallRecovery,
    )
  }

  if (setup.showMetamoduleInstallBlockedDialog) {
    SetupAlertDialog(
      onDismissRequest = onDismissMetamoduleInstallBlocked,
      titleText = stringResource(R.string.setup_metamodule_blocked_title),
      bodyText = stringResource(R.string.setup_metamodule_blocked_body),
      confirmButtonText = stringResource(R.string.common_ok),
      onConfirm = onDismissMetamoduleInstallBlocked,
    )
  }

  SetupScaffold(title = stringResource(R.string.setup_install_title)) { padding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .background(MaterialTheme.colorScheme.background),
    ) {
      Box(
        modifier = Modifier
          .matchParentSize()
          .background(
            Brush.verticalGradient(
              listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = if (setupIsLightTheme()) 0.035f else 0.075f),
                Color.Transparent,
                MaterialTheme.colorScheme.secondary.copy(alpha = if (setupIsLightTheme()) 0.025f else 0.045f),
              ),
            ),
          ),
      )

      Column(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(horizontal = screenPadding, vertical = 10.dp)
          .widthIn(max = 720.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        InstallerHeroCard(
          setup = setup,
          state = visualState,
          compact = compact,
        )

        InstallerInfoTiles(
          installer = setup.installerLabel,
          arm64Ok = arm64Ok,
          osInstallOk = osInstallOk,
        )

        InstallerSectionHeader(
          title = stringResource(R.string.settings_title),
          trailing = null,
          accent = MaterialTheme.colorScheme.primary,
        )
        OptionalZygiskInstallCard(
          enabled = setup.installZygiskRequested,
          onToggle = onToggleZygiskInstall,
        )

        AnimatedVisibility(
          visible = setup.showKsuApatchZygiskWarning,
          enter = fadeIn(tween(220)) + expandVertically(tween(280, easing = FastOutSlowInEasing)),
          exit = fadeOut(tween(150)) + shrinkVertically(tween(180)),
        ) {
          KsuApatchZygiskWarningCard()
        }

        AnimatedVisibility(
          visible = setup.installConflicts.isNotEmpty(),
          enter = fadeIn(tween(220)) + expandVertically(tween(300, easing = FastOutSlowInEasing)),
          exit = fadeOut(tween(160)) + shrinkVertically(tween(200)),
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            InstallerSectionHeader(
              title = stringResource(R.string.setup_install_conflict_details),
              trailing = setup.installConflicts.size.toString(),
              accent = MaterialTheme.colorScheme.error,
            )
            setup.installConflicts.forEach { conflict ->
              key(conflict.modulePath) {
                InstallConflictCard(
                  conflict = conflict,
                  onToggleRemove = { checked -> onToggleConflictRemove(conflict.modulePath, checked) },
                )
              }
            }
          }
        }

        if (!setup.preInstallWarning.isNullOrBlank()) {
          InstallerNoticeCard(
            text = setup.preInstallWarning.orEmpty(),
            accent = MaterialTheme.colorScheme.error,
          )
        }

        if (!osInstallOk) {
          InstallerNoticeCard(
            text = stringResource(R.string.setup_android_unsupported_fmt, Build.VERSION.RELEASE.ifBlank { "unknown" }),
            accent = MaterialTheme.colorScheme.error,
          )
        } else if (needsAndroidWarning && !setup.installing && !setup.installOk) {
          InstallerNoticeCard(
            text = stringResource(R.string.setup_android_unofficial_hint),
            accent = MaterialTheme.colorScheme.tertiary,
          )
        }

        if (!arm64Ok) {
          InstallerNoticeCard(
            text = stringResource(
              R.string.setup_arch_unsupported_fmt,
              Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            ),
            accent = MaterialTheme.colorScheme.error,
          )
        }

        InstallerActionCard(
          state = visualState,
          setup = setup,
          animatedProgress = animatedInstallProgress,
          animatedPercent = animatedInstallPercent,
          canInstall = canInstall,
          onInstall = requestInstall,
          onReboot = onReboot,
          onShowLog = { showInstallLog = true },
          canShowLog = canShowInstallLog,
        )

        if (setup.manualZipSaved) {
          InstallerNoticeCard(
            title = stringResource(R.string.setup_zip_saved_title),
            text = stringResource(R.string.setup_zip_saved_path_fmt, setup.manualZipPath) + "\n" +
              stringResource(R.string.setup_zip_saved_body),
            accent = MaterialTheme.colorScheme.tertiary,
          )
        }

        AnimatedVisibility(
          visible = canShowInstallLog,
          enter = fadeIn(tween(220)) + expandVertically(tween(240)),
          exit = fadeOut(tween(150)) + shrinkVertically(tween(180)),
        ) {
          InstallerLogCard(
            expanded = showInstallLog,
            log = setup.installLog,
            onToggle = { showInstallLog = !showInstallLog },
          )
        }

        Spacer(Modifier.height(18.dp))
      }
    }
  }
}

private enum class InstallerVisualState {
  READY,
  INSTALLING,
  SUCCESS,
  ERROR,
}

@Composable
private fun InstallerHeroCard(
  setup: SetupUiState,
  state: InstallerVisualState,
  compact: Boolean,
) {
  val accent = when (state) {
    InstallerVisualState.SUCCESS -> Color(0xFF2ECC71)
    InstallerVisualState.ERROR -> MaterialTheme.colorScheme.error
    InstallerVisualState.INSTALLING -> MaterialTheme.colorScheme.primary
    InstallerVisualState.READY -> MaterialTheme.colorScheme.primary
  }
  val operationLabel = when {
    setup.buildUpdateAvailable -> stringResource(R.string.mv_module_update_available)
    setup.moduleReinstallRequired || setup.explicitReinstallRequested -> stringResource(R.string.startup_reinstall_module)
    else -> stringResource(R.string.setup_install_title)
  }
  val targetBuild = listOfNotNull(
    setup.buildVersionName.takeIf { it.isNotBlank() }?.let { "v$it" },
    setup.buildNumber?.let { "#$it" },
  ).joinToString(" ")
  val installedBuild = listOfNotNull(
    setup.installedVersionName.takeIf { it.isNotBlank() }?.let { "v$it" },
    setup.installedBuildNumber?.let { "#$it" },
  ).joinToString(" ")
  val buildLine = when {
    setup.buildUpdateAvailable && installedBuild.isNotBlank() && targetBuild.isNotBlank() -> "$installedBuild  →  $targetBuild"
    targetBuild.isNotBlank() && setup.buildType.isNotBlank() -> "$targetBuild  ·  ${setup.buildType}"
    else -> targetBuild
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .height(if (compact) 214.dp else 230.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.90f else 0.72f),
    shape = RoundedCornerShape(26.dp),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    tonalElevation = 0.dp,
    shadowElevation = if (setupIsLightTheme()) 0.dp else 2.dp,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.horizontalGradient(
            listOf(
              setupPanelAccentWash(accent, 0.13f),
              Color.Transparent,
              Color.Transparent,
            ),
          ),
        ),
    ) {
      Column(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .fillMaxWidth(if (compact) 0.60f else 0.62f)
          .padding(start = 18.dp, top = 18.dp, bottom = 18.dp, end = 8.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = accent.copy(alpha = 0.13f),
          border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        ) {
          Text(
            text = operationLabel,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.height(11.dp))
        Text(
          text = "ZDT-D Module",
          style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
        )
        if (buildLine.isNotBlank()) {
          Spacer(Modifier.height(6.dp))
          Text(
            text = buildLine,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.height(10.dp))
        Text(
          text = when (state) {
            InstallerVisualState.INSTALLING -> setup.installProgressLabel.ifBlank { stringResource(R.string.setup_install_progress_preparing) }
            InstallerVisualState.SUCCESS -> stringResource(R.string.setup_module_installed_body)
            InstallerVisualState.ERROR -> setup.installError ?: stringResource(R.string.common_error)
            InstallerVisualState.READY -> stringResource(R.string.setup_install_body)
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          maxLines = if (compact) 3 else 4,
          overflow = TextOverflow.Ellipsis,
        )
      }

      ZdtdSetupMascot(
        pose = when (state) {
          InstallerVisualState.READY -> SetupMascotPose.READY
          InstallerVisualState.INSTALLING -> SetupMascotPose.INSTALLING
          InstallerVisualState.SUCCESS -> SetupMascotPose.SUCCESS
          InstallerVisualState.ERROR -> SetupMascotPose.ERROR
        },
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .width(if (compact) 142.dp else 174.dp)
          .fillMaxHeight(),
      )
    }
  }
}

@Composable
private fun ZdtdSetupMascot(
  pose: SetupMascotPose,
  modifier: Modifier = Modifier,
) {
  val lightTheme = setupIsLightTheme()
  val loop = rememberInfiniteTransition(label = "setup_mascot_motion")
  val breath by loop.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "setup_mascot_breath",
  )
  val hairDrift by loop.animateFloat(
    initialValue = -1f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2250, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "setup_mascot_hair",
  )
  val stateShift by animateFloatAsState(
    targetValue = when (pose) {
      SetupMascotPose.WELCOME -> 2f
      SetupMascotPose.ROOT -> -2f
      SetupMascotPose.READY -> 0f
      SetupMascotPose.INSTALLING -> -4f
      SetupMascotPose.SUCCESS -> 3f
      SetupMascotPose.ERROR -> -2f
    },
    animationSpec = tween(560, easing = FastOutSlowInEasing),
    label = "setup_mascot_state_shift",
  )
  val stateTilt by animateFloatAsState(
    targetValue = when (pose) {
      SetupMascotPose.WELCOME -> 0.25f
      SetupMascotPose.ROOT -> -0.30f
      SetupMascotPose.READY -> 0f
      SetupMascotPose.INSTALLING -> -0.8f
      SetupMascotPose.SUCCESS -> 0.65f
      SetupMascotPose.ERROR -> -0.45f
    },
    animationSpec = tween(560, easing = FastOutSlowInEasing),
    label = "setup_mascot_state_tilt",
  )
  val accent = when (pose) {
    SetupMascotPose.SUCCESS -> Color(0xFF2ECC71)
    SetupMascotPose.ERROR -> MaterialTheme.colorScheme.error
    SetupMascotPose.ROOT -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
  }
  val basePainter = painterResource(
    if (lightTheme) R.drawable.zdtd_installer_mascot_light else R.drawable.zdtd_installer_mascot
  )

  Box(
    modifier = modifier
      .clip(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
      .background(
        if (lightTheme) MaterialTheme.colorScheme.surfaceContainerLowest
        else Color(0xFF070A12)
      ),
  ) {
    Image(
      painter = basePainter,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      alignment = Alignment.TopCenter,
      modifier = Modifier
        .matchParentSize()
        .graphicsLayer {
          scaleX = 1.018f + breath * 0.008f
          scaleY = 1.018f + breath * 0.014f
          translationX = stateShift + hairDrift * 0.55f
          translationY = breath * 1.6f
          rotationZ = stateTilt + hairDrift * 0.12f
        },
    )

    Image(
      painter = painterResource(
        if (lightTheme) {
          R.drawable.zdtd_installer_mascot_light_highlights
        } else {
          R.drawable.zdtd_installer_mascot_highlights
        },
      ),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      alignment = Alignment.TopCenter,
      modifier = Modifier
        .matchParentSize()
        .graphicsLayer {
          alpha = if (lightTheme) 0.22f + breath * 0.14f else 0.32f + breath * 0.22f
          scaleX = 1.02f
          scaleY = 1.02f
          translationX = stateShift + hairDrift * 2.2f
          translationY = -breath * 0.8f
          rotationZ = stateTilt + hairDrift * 0.24f
        },
    )

    Box(
      modifier = Modifier
        .matchParentSize()
        .background(
          Brush.verticalGradient(
            if (lightTheme) {
              listOf(
                Color.Transparent,
                Color.Transparent,
                MaterialTheme.colorScheme.surface.copy(alpha = 0.13f),
              )
            } else {
              listOf(
                Color.Transparent,
                Color.Transparent,
                Color(0xFF070A12).copy(alpha = 0.22f),
              )
            },
          ),
        ),
    )
    Box(
      modifier = Modifier
        .matchParentSize()
        .background(
          Brush.horizontalGradient(
            if (lightTheme) {
              listOf(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.10f),
                accent.copy(alpha = 0.025f + breath * 0.02f),
              )
            } else {
              listOf(
                Color(0xFF070A12).copy(alpha = 0.90f),
                Color.Transparent,
                accent.copy(alpha = 0.08f + breath * 0.05f),
              )
            },
          ),
        ),
    )
    Surface(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(10.dp),
      color = if (lightTheme) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)
      } else {
        Color.Black.copy(alpha = 0.42f)
      },
      shape = RoundedCornerShape(999.dp),
      border = BorderStroke(1.dp, accent.copy(alpha = 0.36f)),
      tonalElevation = 0.dp,
    ) {
      Text(
        text = "ZDT-D",
        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (lightTheme) MaterialTheme.colorScheme.onSurface else Color.White,
      )
    }
  }
}

@Composable
private fun InstallerInfoTiles(
  installer: String,
  arm64Ok: Boolean,
  osInstallOk: Boolean,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    InstallerInfoTile(
      modifier = Modifier.weight(1f),
      label = stringResource(R.string.setup_install_method).trimEnd(':').trim(),
      value = installer.ifBlank { "—" },
      ok = installer.isNotBlank(),
    )
    InstallerInfoTile(
      modifier = Modifier.weight(1f),
      label = "ABI",
      value = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
      ok = arm64Ok,
    )
    InstallerInfoTile(
      modifier = Modifier.weight(1f),
      label = "Android",
      value = "${Build.VERSION.RELEASE} · ${Build.VERSION.SDK_INT}",
      ok = osInstallOk,
    )
  }
}

@Composable
private fun InstallerInfoTile(
  label: String,
  value: String,
  ok: Boolean,
  modifier: Modifier = Modifier,
) {
  val accent = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.86f else 0.58f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.20f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun InstallerSectionHeader(
  title: String,
  trailing: String?,
  accent: Color,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp, vertical = 1.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Box(
      modifier = Modifier
        .width(4.dp)
        .height(24.dp)
        .clip(RoundedCornerShape(999.dp))
        .background(accent),
    )
    Text(
      text = title,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (!trailing.isNullOrBlank()) {
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
      ) {
        Text(
          text = trailing,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = accent,
        )
      }
    }
  }
}

@Composable
private fun InstallerNoticeCard(
  text: String,
  accent: Color,
  title: String? = null,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.86f else 0.58f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
  ) {
    Row(
      modifier = Modifier
        .background(
          Brush.horizontalGradient(
            listOf(accent.copy(alpha = if (setupIsLightTheme()) 0.045f else 0.10f), Color.Transparent),
          ),
        )
        .padding(12.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
      ) {
        Icon(
          imageVector = Icons.Filled.ErrorOutline,
          contentDescription = null,
          tint = accent,
          modifier = Modifier.padding(7.dp).size(18.dp),
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!title.isNullOrBlank()) {
          Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
        Text(
          text = text,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }
    }
  }
}

@Composable
private fun InstallerActionCard(
  state: InstallerVisualState,
  setup: SetupUiState,
  animatedProgress: Float,
  animatedPercent: Int,
  canInstall: Boolean,
  onInstall: () -> Unit,
  onReboot: () -> Unit,
  onShowLog: () -> Unit,
  canShowLog: Boolean,
) {
  val accent = when (state) {
    InstallerVisualState.SUCCESS -> Color(0xFF2ECC71)
    InstallerVisualState.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
  }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(animationSpec = tween(380, easing = FastOutSlowInEasing)),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.92f else 0.68f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
    shadowElevation = if (setupIsLightTheme()) 0.dp else 2.dp,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              accent.copy(alpha = if (setupIsLightTheme()) 0.055f else 0.14f),
              Color.Transparent,
              MaterialTheme.colorScheme.secondary.copy(alpha = if (setupIsLightTheme()) 0.02f else 0.055f),
            ),
          ),
        )
        .padding(16.dp),
    ) {
      AnimatedContent(
        targetState = state,
        transitionSpec = {
          fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith
            fadeOut(tween(140, easing = FastOutSlowInEasing))
        },
        label = "installer_state_content",
      ) { target ->
        when (target) {
          InstallerVisualState.READY -> {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
              InstallerStateHeader(
                icon = Icons.Filled.SystemUpdateAlt,
                accent = accent,
                title = stringResource(R.string.setup_install_title),
                body = stringResource(R.string.setup_install_progress_preparing),
              )
              Button(
                onClick = onInstall,
                enabled = canInstall,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
              ) {
                Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.common_install), fontWeight = FontWeight.Bold)
              }
            }
          }

          InstallerVisualState.INSTALLING -> {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                InstallerRoundIcon(Icons.Filled.SystemUpdateAlt, accent)
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = stringResource(R.string.common_installing),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(
                    text = setup.installProgressLabel.ifBlank { stringResource(R.string.setup_install_progress_preparing) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                  )
                }
                Text(
                  text = stringResource(R.string.setup_install_progress_percent_fmt, animatedPercent),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = accent,
                )
              }
              LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                  .fillMaxWidth()
                  .height(7.dp)
                  .clip(RoundedCornerShape(999.dp)),
              )
            }
          }

          InstallerVisualState.SUCCESS -> {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
              InstallerStateHeader(
                icon = Icons.Filled.CheckCircle,
                accent = accent,
                title = stringResource(R.string.setup_module_installed_title),
                body = stringResource(R.string.setup_module_installed_body),
              )
              CooldownRebootButton(
                activeKey = setup.installOk,
                onReboot = onReboot,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }

          InstallerVisualState.ERROR -> {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
              InstallerStateHeader(
                icon = Icons.Filled.ErrorOutline,
                accent = accent,
                title = stringResource(R.string.setup_install_progress_failed),
                body = setup.installError ?: stringResource(R.string.common_error),
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Button(
                  onClick = onInstall,
                  enabled = canInstall,
                  modifier = Modifier.weight(1f),
                  shape = RoundedCornerShape(15.dp),
                ) {
                  Text(stringResource(R.string.common_retry), fontWeight = FontWeight.Bold)
                }
                if (canShowLog) {
                  OutlinedButton(
                    onClick = onShowLog,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(15.dp),
                  ) {
                    Text(stringResource(R.string.setup_install_log_show))
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

@Composable
private fun InstallerStateHeader(
  icon: ImageVector,
  accent: Color,
  title: String,
  body: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    InstallerRoundIcon(icon, accent)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      )
    }
  }
}

@Composable
private fun InstallerRoundIcon(icon: ImageVector, accent: Color) {
  Surface(
    shape = CircleShape,
    color = accent.copy(alpha = 0.13f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = accent,
      modifier = Modifier.padding(10.dp).size(22.dp),
    )
  }
}

@Composable
private fun InstallerLogCard(
  expanded: Boolean,
  log: String,
  onToggle: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(animationSpec = tween(300, easing = FastOutSlowInEasing)),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.88f else 0.60f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      TextButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(
          imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = null,
        )
        Spacer(Modifier.width(7.dp))
        Text(
          if (expanded) stringResource(R.string.setup_install_log_hide)
          else stringResource(R.string.setup_install_log_show),
          fontWeight = FontWeight.SemiBold,
        )
      }
      AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(200)) + expandVertically(tween(260)),
        exit = fadeOut(tween(130)) + shrinkVertically(tween(180)),
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 250.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        ) {
          Text(
            text = log,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
          )
        }
      }
    }
  }
}

@Composable
private fun SetupPrimaryButton(
  onClick: () -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  text: String,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed && enabled) 0.985f else 1f,
    animationSpec = tween(durationMillis = 110),
    label = "setup_button_press_scale",
  )
  Button(
    onClick = onClick,
    enabled = enabled,
    interactionSource = interactionSource,
    elevation = ButtonDefaults.buttonElevation(
      defaultElevation = 2.dp,
      pressedElevation = 6.dp,
      disabledElevation = 0.dp,
    ),
    modifier = modifier
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
      .heightIn(min = 48.dp),
  ) {
    Text(text)
  }
}

@Composable
private fun CooldownRebootButton(
  activeKey: Any?,
  onReboot: () -> Unit,
  modifier: Modifier = Modifier,
  seconds: Int = 10,
) {
  var remaining by remember(activeKey) { mutableStateOf(seconds) }
  LaunchedEffect(activeKey) {
    remaining = seconds
    while (remaining > 0) {
      delay(1000)
      remaining -= 1
    }
  }
  val enabled = remaining <= 0
  val alpha by animateFloatAsState(
    targetValue = if (enabled) 1f else 0.58f,
    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
    label = "reboot_button_cooldown_alpha",
  )
  Button(
    onClick = onReboot,
    enabled = enabled,
    modifier = modifier.graphicsLayer { this.alpha = alpha },
  ) {
    Text(
      if (enabled) stringResource(R.string.common_reboot)
      else stringResource(R.string.common_reboot_wait_fmt, remaining),
    )
  }
}

@Composable
private fun KsuApatchZygiskWarningCard() {
  val accent = MaterialTheme.colorScheme.tertiary
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.88f else 0.60f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier
        .background(
          Brush.horizontalGradient(
            listOf(accent.copy(alpha = if (setupIsLightTheme()) 0.045f else 0.11f), Color.Transparent),
          ),
        )
        .padding(12.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = accent.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
      ) {
        Icon(
          imageVector = Icons.Filled.ErrorOutline,
          contentDescription = null,
          tint = accent,
          modifier = Modifier.padding(7.dp).size(18.dp),
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = stringResource(R.string.setup_zygisk_ksu_apatch_warning_title),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = stringResource(R.string.setup_zygisk_ksu_apatch_warning_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
      }
    }
  }
}

@Composable
private fun OptionalZygiskInstallCard(
  enabled: Boolean,
  onToggle: (Boolean) -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing)),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.90f else 0.62f),
    border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.30f else 0.16f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.horizontalGradient(
            listOf(
              MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.075f else 0.025f),
              Color.Transparent,
            ),
          ),
        )
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
        ) {
          Icon(
            imageVector = Icons.Filled.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(9.dp).size(20.dp),
          )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.setup_zygisk_install_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = stringResource(R.string.setup_zygisk_install_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Switch(
          checked = enabled,
          onCheckedChange = onToggle,
        )
        IconButton(
          onClick = { expanded = !expanded },
          modifier = Modifier.size(34.dp),
        ) {
          Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(R.string.setup_zygisk_install_details_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp),
          )
        }
      }
      AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(190)) + expandVertically(tween(240)),
        exit = fadeOut(tween(130)) + shrinkVertically(tween(170)),
      ) {
        Text(
          text = stringResource(R.string.setup_zygisk_install_details),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
          modifier = Modifier.padding(start = 50.dp, end = 4.dp, bottom = 3.dp),
        )
      }
    }
  }
}

@Composable
private fun InstallConflictCard(
  conflict: InstallConflictUi,
  onToggleRemove: (Boolean) -> Unit,
) {
  var expanded by rememberSaveable(conflict.modulePath) { mutableStateOf(false) }
  val accent = MaterialTheme.colorScheme.error
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing)),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (setupIsLightTheme()) 0.90f else 0.60f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.horizontalGradient(
            listOf(accent.copy(alpha = if (setupIsLightTheme()) 0.04f else 0.085f), Color.Transparent),
          ),
        )
        .padding(horizontal = 12.dp, vertical = 9.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = accent.copy(alpha = 0.12f),
          border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
        ) {
          Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.padding(7.dp).size(17.dp),
          )
        }
        Text(
          text = stringResource(R.string.setup_install_conflict_module_fmt, conflict.moduleName),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = stringResource(R.string.setup_install_conflict_remove),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
          )
          Checkbox(
            checked = conflict.markedForRemove,
            onCheckedChange = onToggleRemove,
            modifier = Modifier.size(36.dp),
          )
        }
        IconButton(
          onClick = { expanded = !expanded },
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(R.string.setup_install_conflict_details),
            tint = accent,
            modifier = Modifier.size(18.dp),
          )
        }
      }
      AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(190)) + expandVertically(tween(230)),
        exit = fadeOut(tween(130)) + shrinkVertically(tween(170)),
      ) {
        Text(
          text = conflict.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
          modifier = Modifier.padding(start = 42.dp, end = 4.dp, bottom = 3.dp),
        )
      }
    }
  }
}

