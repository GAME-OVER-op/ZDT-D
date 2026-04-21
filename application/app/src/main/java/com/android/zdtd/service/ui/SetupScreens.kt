package com.android.zdtd.service.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.InstallConflictUi
import com.android.zdtd.service.R
import com.android.zdtd.service.RootState
import com.android.zdtd.service.SetupUiState

private fun isArm64OnlySupported(): Boolean {
  // Module binaries are built for arm64-v8a only.
  return Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
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
fun WelcomeScreen(onAccept: () -> Unit) {
  val arm64Ok = remember { isArm64OnlySupported() }
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
        horizontalAlignment = Alignment.Start,
      ) {
        Text(
          text = stringResource(R.string.setup_welcome_title),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.setup_welcome_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
          textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.setup_features_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.setup_features_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
          textAlign = TextAlign.Start,
        )

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_notes_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.setup_notes_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )

        Spacer(Modifier.height(22.dp))
        Button(onClick = onAccept, enabled = arm64Ok, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.common_continue))
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
      }
    }
  }
}

@Composable
fun RootInfoScreen(rootState: RootState, onRequest: () -> Unit) {
  val arm64Ok = remember { isArm64OnlySupported() }
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
          .animateContentSize(animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
          .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          imageVector = Icons.Filled.Security,
          contentDescription = null,
          modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = stringResource(R.string.setup_root_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = stringResource(R.string.setup_root_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )

        Spacer(Modifier.height(18.dp))

        when (rootState) {
          RootState.CHECKING -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
              text = stringResource(R.string.setup_root_waiting),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
          }
          RootState.DENIED, RootState.GRANTED -> {
            val enabled = arm64Ok && rootState != RootState.CHECKING
            Button(
              onClick = onRequest,
              enabled = enabled,
              modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.setup_request_root)) }

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
            if (rootState == RootState.DENIED) {
              Spacer(Modifier.height(10.dp))
              Text(
                text = stringResource(R.string.setup_root_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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


        Button(onClick = onReboot, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_reboot)) }
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
) {
  val arm64Ok = remember { isArm64OnlySupported() }
  val compact = rememberIsCompactWidth()
  val screenPadding = rememberAdaptiveScreenPadding()
  var showInstallLog by rememberSaveable(setup.installing, setup.installLog, setup.installOk, setup.installError, setup.manualZipSaved) { androidx.compose.runtime.mutableStateOf(false) }
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
  }
  if (arm64Ok && setup.showManualDialog) {
    val extra = if (setup.oldVersionDetected) {
      "\n\n" + stringResource(R.string.setup_manual_old_version_extra)
    } else {
      ""
    }
    AlertDialog(
      onDismissRequest = onManualDismiss,
      title = { Text(stringResource(R.string.common_attention)) },
      text = { Text(setup.manualDialogText + extra) },
      confirmButton = {
        TextButton(onClick = onManualConfirm) { Text(stringResource(R.string.setup_save_zip)) }
      },
      dismissButton = {
        TextButton(onClick = onManualDismiss) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }

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
          text = stringResource(R.string.setup_install_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = stringResource(R.string.setup_install_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
          textAlign = TextAlign.Center,
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

        val canInstall = arm64Ok && rootState == RootState.GRANTED && !setup.installing && !setup.installOk
        Button(
          onClick = onInstall,
          enabled = canInstall,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (setup.installing) stringResource(R.string.common_installing) else stringResource(R.string.common_install))
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

                Button(onClick = onReboot, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_reboot)) }
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

