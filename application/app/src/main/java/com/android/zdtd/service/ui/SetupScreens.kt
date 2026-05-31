package com.android.zdtd.service.ui

import android.os.Build
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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
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

private fun isArm64OnlySupported(): Boolean {
  // Module binaries are built for arm64-v8a only.
  return Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
}

private fun isModuleInstallOsSupported(): Boolean {
  return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}

private fun needsUnofficialAndroidInstallWarning(): Boolean {
  return Build.VERSION.SDK_INT in Build.VERSION_CODES.P until Build.VERSION_CODES.R
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScaffold(content: @Composable (PaddingValues) -> Unit) {
  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Text(
            stringResource(R.string.app_name),
            letterSpacing = 2.sp,
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
  dismissButtonText: String,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismissRequest,
    title = { Text(titleText) },
    text = { Text(bodyText) },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text(confirmButtonText) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(dismissButtonText) }
    },
  )
}

@Composable
fun WelcomeScreen(onAccept: () -> Unit) {
  val arm64Ok = remember { isArm64OnlySupported() }
  val screenPadding = rememberAdaptiveScreenPadding()
  SetupScaffold { padding ->
    SetupScreenBackground(padding = padding) {
      Column(
        modifier = Modifier
          .padding(screenPadding)
          .widthIn(max = 620.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        SetupStepHeader(currentStep = 1)
        SetupHeroCard(
          icon = Icons.Filled.Security,
          stepLabel = "01 / 03",
          title = stringResource(R.string.setup_welcome_title),
          body = stringResource(R.string.setup_welcome_body),
          accent = MaterialTheme.colorScheme.primary,
        )

        SetupInfoCard(
          title = stringResource(R.string.setup_features_title),
          body = stringResource(R.string.setup_features_body),
          accent = MaterialTheme.colorScheme.secondary,
        )
        SetupInfoCard(
          title = stringResource(R.string.setup_notes_title),
          body = stringResource(R.string.setup_notes_body),
          accent = MaterialTheme.colorScheme.tertiary,
        )

        SetupPrimaryButton(
          onClick = onAccept,
          enabled = arm64Ok,
          modifier = Modifier.fillMaxWidth(),
          text = stringResource(R.string.common_continue),
        )

        if (!arm64Ok) {
          SetupInfoCard(
            title = stringResource(R.string.common_attention),
            body = stringResource(
              R.string.setup_arch_unsupported_fmt,
              Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            ),
            accent = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

@Composable
fun RootInfoScreen(rootState: RootState, onRequest: () -> Unit) {
  val arm64Ok = remember { isArm64OnlySupported() }
  val screenPadding = rememberAdaptiveScreenPadding()
  SetupScaffold { padding ->
    SetupScreenBackground(padding = padding) {
      Column(
        modifier = Modifier
          .padding(screenPadding)
          .widthIn(max = 620.dp)
          .fillMaxWidth()
          .animateContentSize(animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        SetupStepHeader(currentStep = 2)
        SetupHeroCard(
          icon = Icons.Filled.Security,
          stepLabel = "02 / 03",
          title = stringResource(R.string.setup_root_title),
          body = stringResource(R.string.setup_root_body),
          accent = MaterialTheme.colorScheme.secondary,
          bodyTextAlign = TextAlign.Start,
        )

        when (rootState) {
          RootState.CHECKING -> {
            SetupProgressCard(text = stringResource(R.string.setup_root_waiting))
          }
          RootState.DENIED, RootState.GRANTED -> {
            val enabled = arm64Ok && rootState != RootState.CHECKING
            SetupPrimaryButton(
              onClick = onRequest,
              enabled = enabled,
              modifier = Modifier.fillMaxWidth(),
              text = stringResource(R.string.setup_request_root),
            )

            if (!arm64Ok) {
              SetupInfoCard(
                title = stringResource(R.string.common_attention),
                body = stringResource(
                  R.string.setup_arch_unsupported_fmt,
                  Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                ),
                accent = MaterialTheme.colorScheme.error,
              )
            }
            if (rootState == RootState.DENIED) {
              SetupInfoCard(
                title = stringResource(R.string.common_attention),
                body = stringResource(R.string.setup_root_denied),
                accent = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }
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
  onRetryInstallWithoutZygisk: () -> Unit,
) {
  val arm64Ok = remember { isArm64OnlySupported() }
  val compact = rememberIsCompactWidth()
  val screenPadding = rememberAdaptiveScreenPadding()
  var showInstallLog by rememberSaveable(setup.installing, setup.installLog, setup.installOk, setup.installError, setup.manualZipSaved) { androidx.compose.runtime.mutableStateOf(false) }
  var showUnofficialAndroidWarning by rememberSaveable { mutableStateOf(false) }
  val osInstallOk = remember { isModuleInstallOsSupported() }
  val needsAndroidWarning = remember { needsUnofficialAndroidInstallWarning() }
  val canShowInstallLog = !setup.installing && setup.installLog.isNotBlank()
  val animatedInstallProgress by animateFloatAsState(
    targetValue = (setup.installProgressPercent.coerceIn(0, 100) / 100f),
    animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing),
    label = "install_progress_float",
  )
  val animatedInstallPercent by animateIntAsState(
    targetValue = setup.installProgressPercent.coerceIn(0, 100),
    animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing),
    label = "install_progress_int",
  )
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


  SetupScaffold { padding ->
    SetupScreenBackground(padding = padding) {
      Column(
        modifier = Modifier
          .padding(screenPadding)
          .widthIn(max = 620.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        SetupStepHeader(currentStep = 3)
        Spacer(Modifier.height(4.dp))
        SetupHeroCard(
          icon = Icons.Filled.SystemUpdateAlt,
          stepLabel = "03 / 03",
          title = stringResource(R.string.setup_install_title),
          body = stringResource(R.string.setup_install_body),
          accent = MaterialTheme.colorScheme.primary,
        )

        if (setup.installerLabel.isNotBlank()) {
          Spacer(Modifier.height(12.dp))
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          ) {
            if (compact) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = stringResource(R.string.setup_install_method),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
                Text(
                  text = setup.installerLabel,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            } else {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = stringResource(R.string.setup_install_method),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                  text = setup.installerLabel,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        AnimatedVisibility(
          visible = setup.installConflicts.isNotEmpty(),
          enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(260)),
          exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(180)),
        ) {
          Column {
            Spacer(Modifier.height(12.dp))
            setup.installConflicts.forEachIndexed { index, conflict ->
              key(conflict.modulePath) {
                AnimatedVisibility(
                  visible = true,
                  enter = fadeIn(animationSpec = tween(durationMillis = 260, delayMillis = index * 55)) +
                    expandVertically(
                      animationSpec = tween(
                        durationMillis = 320,
                        delayMillis = index * 55,
                        easing = FastOutSlowInEasing,
                      ),
                    ) +
                    slideInVertically(
                      initialOffsetY = { it / 5 },
                      animationSpec = tween(
                        durationMillis = 320,
                        delayMillis = index * 55,
                        easing = FastOutSlowInEasing,
                      ),
                    ),
                ) {
                  Column {
                    InstallConflictCard(
                      conflict = conflict,
                      onToggleRemove = { checked -> onToggleConflictRemove(conflict.modulePath, checked) },
                    )
                    Spacer(Modifier.height(10.dp))
                  }
                }
              }
            }
          }
        }

        Spacer(Modifier.height(12.dp))
        OptionalZygiskInstallCard(
          enabled = setup.installZygiskRequested,
          onToggle = onToggleZygiskInstall,
        )

        AnimatedVisibility(
          visible = setup.showKsuApatchZygiskWarning,
          enter = fadeIn(animationSpec = tween(280)) +
            expandVertically(animationSpec = tween(360, easing = FastOutSlowInEasing)) +
            slideInVertically(
              initialOffsetY = { fullHeight -> fullHeight / 6 },
              animationSpec = tween(360, easing = FastOutSlowInEasing),
            ),
          exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(180)),
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(10.dp))
            KsuApatchZygiskWarningCard()
          }
        }

        Spacer(Modifier.height(18.dp))

        if (!setup.preInstallWarning.isNullOrBlank()) {
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          ) {
            Text(
              text = setup.preInstallWarning ?: "",
              modifier = Modifier.padding(14.dp),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
          Spacer(Modifier.height(18.dp))
        }

        val canInstall = arm64Ok && osInstallOk && rootState == RootState.GRANTED && !setup.installing && !setup.installOk
        SetupPrimaryButton(
          onClick = {
            if (needsAndroidWarning) {
              showUnofficialAndroidWarning = true
            } else {
              onInstall()
            }
          },
          enabled = canInstall,
          modifier = Modifier.fillMaxWidth(),
          text = if (setup.installing) stringResource(R.string.common_installing) else stringResource(R.string.common_install),
        )

        if (!osInstallOk) {
          Spacer(Modifier.height(10.dp))
          Text(
            text = stringResource(R.string.setup_android_unsupported_fmt, Build.VERSION.RELEASE.ifBlank { "unknown" }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        } else if (needsAndroidWarning && !setup.installing && !setup.installOk) {
          Spacer(Modifier.height(10.dp))
          Text(
            text = stringResource(R.string.setup_android_unofficial_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
          )
        }

        if (!arm64Ok) {
          Spacer(Modifier.height(10.dp))
          Text(
            text = stringResource(
              R.string.setup_arch_unsupported_fmt,
              Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        val showProgressCard = setup.installing || setup.installProgressPercent > 0
        AnimatedVisibility(
          visible = showProgressCard,
          enter = fadeIn(animationSpec = tween(280)) + expandVertically(animationSpec = tween(280)),
          exit = fadeOut(animationSpec = tween(220)) + shrinkVertically(animationSpec = tween(220)),
        ) {
          Column {
            Spacer(Modifier.height(14.dp))
            Card(
              modifier = Modifier.fillMaxWidth(),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
              Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = setup.installProgressLabel.ifBlank { stringResource(R.string.setup_install_progress_preparing) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text = stringResource(R.string.setup_install_progress_percent_fmt, animatedInstallPercent),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                LinearProgressIndicator(
                  progress = animatedInstallProgress,
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge),
                )
              }
            }
          }
        }

        AnimatedVisibility(
          visible = setup.installOk,
          enter = fadeIn(animationSpec = tween(durationMillis = 520, delayMillis = 120)) +
            expandVertically(animationSpec = tween(durationMillis = 520, delayMillis = 120, easing = FastOutSlowInEasing)) +
            slideInVertically(
              initialOffsetY = { fullHeight -> fullHeight / 5 },
              animationSpec = tween(durationMillis = 520, delayMillis = 120, easing = FastOutSlowInEasing),
            ),
          exit = fadeOut(animationSpec = tween(durationMillis = 220)) +
            shrinkVertically(animationSpec = tween(durationMillis = 220)),
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(14.dp)) {
                Text(stringResource(R.string.setup_module_installed_title), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                  stringResource(R.string.setup_module_installed_body),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                )
                Spacer(Modifier.height(12.dp))

                CooldownRebootButton(
                  activeKey = setup.installOk,
                  onReboot = onReboot,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
        }

        if (setup.manualZipSaved) {
          Spacer(Modifier.height(18.dp))
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          ) {
            Column(Modifier.padding(14.dp)) {
              Text(stringResource(R.string.setup_zip_saved_title), fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Text(
                stringResource(R.string.setup_zip_saved_path_fmt, setup.manualZipPath),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(6.dp))
              Text(
                stringResource(R.string.setup_zip_saved_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
              )
            }
          }
        }

        if (!setup.installError.isNullOrBlank()) {
          Spacer(Modifier.height(12.dp))
          Text(
            text = setup.installError ?: stringResource(R.string.common_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        AnimatedVisibility(
          visible = canShowInstallLog,
          enter = fadeIn(animationSpec = tween(320)) + expandVertically(animationSpec = tween(320)),
          exit = fadeOut(animationSpec = tween(220)) + shrinkVertically(animationSpec = tween(220)),
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(18.dp))
            OutlinedButton(
              onClick = { showInstallLog = !showInstallLog },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(
                imageVector = if (showInstallLog) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
              )
              Spacer(Modifier.width(8.dp))
              Text(
                if (showInstallLog) stringResource(R.string.setup_install_log_hide)
                else stringResource(R.string.setup_install_log_show),
              )
            }
          }
        }

        AnimatedVisibility(
          visible = canShowInstallLog && showInstallLog,
          enter = fadeIn(animationSpec = tween(320)) + expandVertically(animationSpec = tween(320)),
          exit = fadeOut(animationSpec = tween(220)) + shrinkVertically(animationSpec = tween(220)),
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.setup_install_log_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                ) {
                  Text(
                    text = setup.installLog,
                    style = MaterialTheme.typography.bodySmall,
                  )
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
private fun SetupScreenBackground(
  padding: PaddingValues,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(padding)
      .background(
        Brush.linearGradient(
          listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
          ),
        ),
      ),
    contentAlignment = Alignment.Center,
    content = content,
  )
}

@Composable
private fun SetupStepHeader(currentStep: Int) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      repeat(3) { index ->
        val step = index + 1
        val active = step <= currentStep
        Box(
          modifier = Modifier
            .height(7.dp)
            .width(if (active) 30.dp else 9.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
              if (active) {
                Brush.horizontalGradient(
                  listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary,
                  ),
                )
              } else {
                Brush.horizontalGradient(
                  listOf(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                  ),
                )
              },
            ),
        )
      }
      Text(
        text = "$currentStep / 3",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
      )
    }
  }
}

@Composable
private fun SetupHeroCard(
  icon: ImageVector,
  stepLabel: String,
  title: String,
  body: String,
  accent: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier,
  bodyTextAlign: TextAlign = TextAlign.Center,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(30.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
    shadowElevation = 2.dp,
  ) {
    Box(
      modifier = Modifier
        .background(
          Brush.linearGradient(
            listOf(
              accent.copy(alpha = 0.18f),
              MaterialTheme.colorScheme.surface.copy(alpha = 0.10f),
              MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
            ),
          ),
        )
        .padding(18.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = accent.copy(alpha = 0.14f),
          border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
        ) {
          Text(
            text = stepLabel,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
          )
        }
        Spacer(Modifier.height(14.dp))
        Box(
          modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(
              Brush.linearGradient(
                listOf(
                  accent.copy(alpha = 0.92f),
                  MaterialTheme.colorScheme.secondary.copy(alpha = 0.78f),
                ),
              ),
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(34.dp),
          )
        }
        Spacer(Modifier.height(16.dp))
        Text(
          text = title,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
          textAlign = bodyTextAlign,
        )
      }
    }
  }
}

@Composable
private fun SetupInfoCard(
  title: String,
  body: String,
  accent: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
  ) {
    Row(
      modifier = Modifier
        .background(
          Brush.linearGradient(
            listOf(
              accent.copy(alpha = 0.10f),
              MaterialTheme.colorScheme.surface.copy(alpha = 0.04f),
            ),
          ),
        )
        .padding(14.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Box(
        modifier = Modifier
          .size(34.dp)
          .clip(CircleShape)
          .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (accent == MaterialTheme.colorScheme.error) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
          contentDescription = null,
          tint = accent,
          modifier = Modifier.size(19.dp),
        )
      }
      Spacer(Modifier.width(12.dp))
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )
      }
    }
  }
}

@Composable
private fun SetupProgressCard(text: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
      Spacer(Modifier.width(12.dp))
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
      )
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
  val pulse = rememberInfiniteTransition(label = "zygisk_compat_warning_pulse")
  val scale by pulse.animateFloat(
    initialValue = 1f,
    targetValue = 1.018f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1150, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "zygisk_compat_warning_scale",
  )
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      },
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = Icons.Filled.ErrorOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.size(22.dp),
      )
      Spacer(Modifier.width(10.dp))
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = stringResource(R.string.setup_zygisk_ksu_apatch_warning_title),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
          text = stringResource(R.string.setup_zygisk_ksu_apatch_warning_body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.92f),
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
  val compactLayout = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 420
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 8.dp)
        .animateContentSize(),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.setup_zygisk_install_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (!compactLayout) {
            Spacer(Modifier.height(2.dp))
            Text(
              text = stringResource(R.string.setup_zygisk_install_short),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(
          checked = enabled,
          onCheckedChange = onToggle,
          modifier = Modifier.size(36.dp),
        )
        IconButton(
          onClick = { expanded = !expanded },
          modifier = Modifier.size(34.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = stringResource(R.string.setup_zygisk_install_details_cd),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
        }
      }
      AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(180)),
      ) {
        Text(
          text = stringResource(R.string.setup_zygisk_install_details),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
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
  val compactLayout = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 420
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 8.dp)
        .animateContentSize(),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (compactLayout) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.setup_install_conflict_module_fmt, conflict.moduleName),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
          )
          IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(34.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.ErrorOutline,
              contentDescription = stringResource(R.string.setup_install_conflict_details),
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.setup_install_conflict_module_fmt, conflict.moduleName),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.setup_install_conflict_remove),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Checkbox(
            checked = conflict.markedForRemove,
            onCheckedChange = { checked -> onToggleRemove(checked) },
            modifier = Modifier.size(36.dp),
          )
          IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(34.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.ErrorOutline,
              contentDescription = stringResource(R.string.setup_install_conflict_details),
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
      AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(180)),
      ) {
        Text(
          text = conflict.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
        )
      }
      if (compactLayout) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.End,
        ) {
          Text(
            text = stringResource(R.string.setup_install_conflict_remove),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Checkbox(
            checked = conflict.markedForRemove,
            onCheckedChange = { checked -> onToggleRemove(checked) },
            modifier = Modifier.size(36.dp),
          )
        }
      }
    }
  }
}

