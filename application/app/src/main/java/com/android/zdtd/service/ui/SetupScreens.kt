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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
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
        title = { Text("ZDT-D", letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold) },
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
          text = "Добро пожаловать",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
          text = "ZDT-D — это root-модуль (Magisk / KernelSU / APatch), предназначенный для обхода DPI (глубокого анализа пакетов) в интернете. " +
            "Он помогает обходить блокировки сервисов и ограничения скорости.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )
        Spacer(Modifier.height(16.dp))

        Text("Основные характеристики", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
          text = "• Обход блокировок и ограничений скорости\n" +
            "• Удобный графический интерфейс для настройки",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )

        Spacer(Modifier.height(16.dp))
        Text("Основные моменты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
          text = "• Конфиденциальность и безопасность: модуль не собирает персональные данные и не вредит функциональности устройства.\n" +
            "• Свобода и открытость: полностью бесплатно и останется таковым.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )

        Spacer(Modifier.height(22.dp))
        Button(onClick = onAccept, enabled = arm64Ok, modifier = Modifier.fillMaxWidth()) {
          Text("Продолжить")
        }

        if (!arm64Ok) {
          Spacer(Modifier.height(10.dp))
          Text(
            text = "Неподдерживаемая архитектура. ZDT-D работает только на arm64-v8a. " +
              "Текущее устройство: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}.",
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
          text = "Нужен Root-доступ",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = "ZDT-D — это root-модуль (Magisk / KernelSU / APatch) для обхода DPI-блокировок и ограничений трафика. " +
            "Root нужен, потому что модуль запускает системные скрипты и сетевые компоненты на уровне ОС — " +
            "то, что обычным приложениям недоступно.\n\n" +
            "Также root используется для установки/обновления модуля, управления демоном и чтения файлов модуля (токен и логи). " +
            "Запрос root всегда подтверждается через ваш root-менеджер (Magisk / KernelSU / APatch), а разрешение можно отозвать в любой момент.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )

        Spacer(Modifier.height(18.dp))

        when (rootState) {
          RootState.CHECKING -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
              text = "Ожидание ответа от root-менеджера…",
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
            ) { Text("Запросить Root") }

            if (!arm64Ok) {
              Spacer(Modifier.height(10.dp))
              Text(
                text = "Неподдерживаемая архитектура. ZDT-D работает только на arm64-v8a. " +
                  "Текущее устройство: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
            if (rootState == RootState.DENIED) {
              Spacer(Modifier.height(10.dp))
              Text(
                text = "Root не предоставлен. Если вы нажали «Отказать», попробуйте ещё раз.",
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
          text = "Требуется перезагрузка",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = if (text.isBlank()) {
            "Для завершения установки/обновления модуля необходимо перезагрузить устройство."
          } else text,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
        )
        Spacer(Modifier.height(18.dp))

        if (setup.migrationAvailable) {
          SettingsMigrationSection(setup = setup, onRequest = onRequestMigration)
          Spacer(Modifier.height(14.dp))
        }

        val rebootEnabled = setup.migrationDialog != MigrationDialog.PROGRESS || setup.migrationFinished
        Button(onClick = onReboot, enabled = rebootEnabled, modifier = Modifier.fillMaxWidth()) { Text("Перезагрузить") }

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
      "\n\nСтарая версия модуля будет удалена перед установкой."
    } else {
      ""
    }
    AlertDialog(
      onDismissRequest = onManualDismiss,
      title = { Text("Внимание") },
      text = { Text(setup.manualDialogText + extra) },
      confirmButton = {
        TextButton(onClick = onManualConfirm) { Text("Сохранить ZIP") }
      },
      dismissButton = {
        TextButton(onClick = onManualDismiss) { Text("Отмена") }
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
          text = "Установка модуля",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = "Нажмите «Установить», чтобы установить модуль ZDT-D из встроенного пакета приложения. " +
            "Если обнаружен установщик (Magisk / KernelSU / APatch), установка пройдёт автоматически.",
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
                text = "Метод установки:",
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
        ) { Text(if (setup.installing) "Установка…" else "Установить") }

        if (!arm64Ok) {
          Spacer(Modifier.height(10.dp))
          Text(
            text = "Неподдерживаемая архитектура. ZDT-D работает только на arm64-v8a. " +
              "Текущее устройство: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}.",
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
              Text("Модуль установлен", fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Text(
                "Для применения изменений требуется перезагрузка устройства.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
              )
              Spacer(Modifier.height(12.dp))

              if (setup.migrationAvailable) {
                SettingsMigrationSection(setup = setup, onRequest = onRequestMigration)
                Spacer(Modifier.height(12.dp))
              }

              val rebootEnabled = setup.migrationDialog != MigrationDialog.PROGRESS || setup.migrationFinished
              Button(onClick = onReboot, enabled = rebootEnabled, modifier = Modifier.fillMaxWidth()) { Text("Перезагрузить") }

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
              Text("ZIP сохранён", fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Text(
                "Путь: ${setup.manualZipPath}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(6.dp))
              Text(
                "Откройте ваш root-менеджер (Magisk / KernelSU / APatch) и установите этот ZIP как модуль.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
              )
            }
          }
        }

        if (!setup.installError.isNullOrBlank()) {
          Spacer(Modifier.height(12.dp))
          Text(
            text = setup.installError ?: "Ошибка",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        if (setup.installLog.isNotBlank()) {
          Spacer(Modifier.height(18.dp))
          Text("Лог установки", style = MaterialTheme.typography.titleSmall)
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
      Text("Миграция настроек", fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(6.dp))
      Text(
        text = "Перенесёт ваши ранее настроенные программы и профили в новую версию модуля. " +
          "После обновления и перезагрузки всё будет готово — без повторной настройки.",
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
      val btnText = if (setup.migrationDone) "Настройки уже перенесены" else "Произвести миграцию"
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
        title = { Text("Подтвердите действие") },
        text = {
          Text(
            "Запустить перенос настроек в обновлённый модуль?\n\n" +
              "Мы скопируем все папки из working_folder старой версии в working_folder обновления.\n" +
              "Содержимое папки обновления будет перезаписано."
          )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Да") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Нет") } },
        properties = androidx.compose.ui.window.DialogProperties(
          dismissOnBackPress = true,
          dismissOnClickOutside = true,
        )
      )
    }

    MigrationDialog.NONMAGISK_WARN -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Внимание") },
        text = {
          Text(
            "Из-за различий в менеджере root перенос может отработать не идеально.\n\n" +
              "Продолжить?"
          )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Подтверждаю") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Нет") } },
        properties = androidx.compose.ui.window.DialogProperties(
          dismissOnBackPress = true,
          dismissOnClickOutside = true,
        )
      )
    }

    MigrationDialog.NONMAGISK_CONFIRM -> {
      AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Последнее подтверждение") },
        text = { Text("Вы уверены в своём выборе?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Да") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Нет") } },
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
        title = { Text(if (err != null) "Ошибка" else "Перенос настроек") },
        text = {
          Column {
            if (err != null) {
              Text(err)
            } else {
              val pct = setup.migrationPercent.coerceIn(0, 100)
              Text(setup.migrationProgressText.ifBlank { "Выполняется…" })
              Spacer(Modifier.height(10.dp))
              LinearProgressIndicator(progress = pct / 100f, modifier = Modifier.fillMaxWidth())
              Spacer(Modifier.height(6.dp))
              Text("$pct%", style = MaterialTheme.typography.bodySmall)
            }
          }
        },
        confirmButton = {
          if (finished) {
            TextButton(onClick = onCloseProgress) { Text("Завершить") }
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
