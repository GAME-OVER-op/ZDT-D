package com.android.zdtd.service.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.RootState
import com.android.zdtd.service.SetupUiState
import com.android.zdtd.service.MigrationDialog

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
        title = { Text(stringResource(R.string.app_name), letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold) },
      )
    },
    content = content,
  )
}

@Composable
fun WelcomeScreen(onAccept: () -> Unit) {
  val arm64Ok = remember { isArm64OnlySupported() }
  SetupScaffold { padding ->
    Box(
      Modifier
        .fillMaxSize()
        .padding(padding),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier
          .padding(24.dp)
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
        )
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.setup_features_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.setup_features_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
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
  SetupScaffold { padding ->
    Box(
      Modifier
        .fillMaxSize()
        .padding(padding),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier
          .padding(24.dp)
          .fillMaxWidth()
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
  onRequestMigration: () -> Unit,
  onDismissMigrationDialog: () -> Unit,
  onConfirmMigrationDialog: () -> Unit,
  onCloseMigrationProgress: () -> Unit,
) {
  SetupScaffold { padding ->
    Box(
      Modifier
        .fillMaxSize()
        .padding(padding),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier
          .padding(24.dp)
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
        )
        Spacer(Modifier.height(18.dp))

        if (setup.migrationAvailable && !setup.moduleReinstallRequired && !setup.tamperReinstallPendingReboot) {
          SettingsMigrationSection(setup = setup, onRequest = onRequestMigration)
          Spacer(Modifier.height(14.dp))
        }

        val rebootEnabled = setup.migrationDialog != MigrationDialog.PROGRESS || setup.migrationFinished
        Button(onClick = onReboot, enabled = rebootEnabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_reboot)) }

        SettingsMigrationDialogs(
          setup = setup,
          onDismiss = onDismissMigrationDialog,
          onConfirm = onConfirmMigrationDialog,
          onCloseProgress = onCloseMigrationProgress,
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
  onRequestMigration: () -> Unit,
  onDismissMigrationDialog: () -> Unit,
  onConfirmMigrationDialog: () -> Unit,
  onCloseMigrationProgress: () -> Unit,
) {
  val arm64Ok = remember { isArm64OnlySupported() }
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
          .padding(24.dp)
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
        )

        if (setup.installerLabel.isNotBlank()) {
          Spacer(Modifier.height(12.dp))
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          ) {
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

        if (setup.installing) {
          Spacer(Modifier.height(14.dp))
          CircularProgressIndicator()
        }

        if (setup.installOk) {
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

              if (setup.migrationAvailable && !setup.moduleReinstallRequired && !setup.tamperReinstallPendingReboot) {
                SettingsMigrationSection(setup = setup, onRequest = onRequestMigration)
                Spacer(Modifier.height(12.dp))
              }

              val rebootEnabled = setup.migrationDialog != MigrationDialog.PROGRESS || setup.migrationFinished
              Button(onClick = onReboot, enabled = rebootEnabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_reboot)) }

              SettingsMigrationDialogs(
                setup = setup,
                onDismiss = onDismissMigrationDialog,
                onConfirm = onConfirmMigrationDialog,
                onCloseProgress = onCloseMigrationProgress,
              )
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

        if (setup.installLog.isNotBlank()) {
          Spacer(Modifier.height(18.dp))
          Text(stringResource(R.string.setup_install_log_title), style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(8.dp))
          Card(Modifier.fillMaxWidth()) {
            Text(
              text = setup.installLog,
              modifier = Modifier.padding(12.dp),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    }
  }
}


@Composable
private fun SettingsMigrationSection(setup: SetupUiState, onRequest: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(Modifier.padding(14.dp)) {
      Text(stringResource(R.string.setup_migration_title), fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.setup_migration_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
      )

      if (setup.migrationHintText.isNotBlank() && !setup.migrationDone) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = setup.migrationHintText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
      }

      Spacer(Modifier.height(12.dp))
      val btnText = if (setup.migrationDone) stringResource(R.string.setup_migration_done) else stringResource(R.string.setup_migration_action)
      Button(
        onClick = onRequest,
        enabled = setup.migrationButtonEnabled && !setup.migrationDone,
        modifier = Modifier.fillMaxWidth(),
      ) { Text(btnText) }
    }
  }
}

@Composable
private fun SettingsMigrationDialogs(
  setup: SetupUiState,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  onCloseProgress: () -> Unit,
) {
  when (setup.migrationDialog) {
    MigrationDialog.NONE -> Unit

    MigrationDialog.MAGISK_CONFIRM -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_confirm_action)) },
        text = {
          Text(
            stringResource(R.string.setup_migration_confirm_body)
          )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_no)) } },
        properties = androidx.compose.ui.window.DialogProperties(
          dismissOnBackPress = true,
          dismissOnClickOutside = true,
        )
      )
    }

    MigrationDialog.NONMAGISK_WARN -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_attention)) },
        text = {
          Text(
            stringResource(R.string.setup_migration_nonmagisk_warn)
          )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_no)) } },
        properties = androidx.compose.ui.window.DialogProperties(
          dismissOnBackPress = true,
          dismissOnClickOutside = true,
        )
      )
    }

    MigrationDialog.NONMAGISK_CONFIRM -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_migration_last_confirm_title)) },
        text = { Text(stringResource(R.string.setup_migration_last_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_no)) } },
        properties = androidx.compose.ui.window.DialogProperties(
          dismissOnBackPress = true,
          dismissOnClickOutside = true,
        )
      )
    }

    MigrationDialog.PROGRESS -> {
      val finished = setup.migrationFinished
      val err = setup.migrationError
      AlertDialog(
        onDismissRequest = { if (finished) onCloseProgress() },
        title = { Text(if (err != null) stringResource(R.string.common_error) else stringResource(R.string.setup_migration_progress_title)) },
        text = {
          Column {
            if (err != null) {
              Text(err)
            } else {
              val pct = setup.migrationPercent.coerceIn(0, 100)
              Text(setup.migrationProgressText.ifBlank { stringResource(R.string.common_in_progress) })
              Spacer(Modifier.height(10.dp))
              LinearProgressIndicator(progress = pct / 100f, modifier = Modifier.fillMaxWidth())
              Spacer(Modifier.height(6.dp))
              Text("$pct%", style = MaterialTheme.typography.bodySmall)
            }
          }
        },
        confirmButton = {
          if (finished) {
            TextButton(onClick = onCloseProgress) { Text(stringResource(R.string.common_finish)) }
          }
        },
        dismissButton = {},
        properties = androidx.compose.ui.window.DialogProperties(
          dismissOnBackPress = finished,
          dismissOnClickOutside = finished,
        )
      )
    }
  }
}
