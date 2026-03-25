package com.android.zdtd.service

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import android.content.res.Resources
import com.android.zdtd.service.ui.ZdtdApp
import com.android.zdtd.service.ui.theme.ZdtdTheme
import java.io.File
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

  private val vm: MainViewModel by viewModels()

  private val unknownSourcesLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    val granted = runCatching { packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    vm.onUnknownSourcesPermissionResult(granted)
  }

  private val backupImportLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    vm.onBackupImportResult(uri)
  }

  private val postNotificationsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    vm.onPostNotificationsPermissionResult(granted)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Apply persisted app language before composing UI.
runCatching {
  val root = RootConfigManager(applicationContext)
  val mode = root.getAppLanguageMode().trim().lowercase()
  when (mode) {
    // Auto: clear overrides so the app follows the system locale.
    // With only EN (default) + RU resources this matches the rule:
    // system ru -> RU, any other -> EN (fallback).
    "auto", "" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    "ru" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
    "en" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
    else -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
  }
}

    enableEdgeToEdge()

    // Capture crashes to a local file so we can diagnose issues even without logcat.
    CrashLogger.install(applicationContext)

    // Detect a true cold start from launcher (to show the optional module update prompt).
    val fromLauncher = intent?.action == Intent.ACTION_MAIN && (intent?.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
    vm.onAppStart(fromLauncher)

    // Handle update events (open browser / request permission / install APK).
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          vm.appUpdateEvents.collect { e ->
            when (e) {
              is AppUpdateEvent.OpenUrl -> openUrl(e.url)
              AppUpdateEvent.OpenUnknownSourcesSettings -> openUnknownSourcesSettings()
              is AppUpdateEvent.InstallApk -> installApk(e.filePath)
            }
          }
        }

        launch {
          vm.backupEvents.collect { e ->
            when (e) {
              BackupEvent.RequestImport -> backupImportLauncher.launch(arrayOf("*/*"))
              is BackupEvent.ShareFile -> shareFile(e.filePath, e.mime)
            }
          }
        }

        launch {
          vm.toastEvents.collect { msg ->
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
          }
        }

        launch {
          vm.notificationEvents.collect { e ->
            when (e) {
              NotificationEvent.RequestPostNotificationsPermission -> {
                if (Build.VERSION.SDK_INT >= 33) {
                  postNotificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                  vm.onPostNotificationsPermissionResult(true)
                }
              }
            }
          }
        }
      }
    }

    setContent {
      ZdtdTheme {
        Surface {
          val rootState by vm.rootState.collectAsStateWithLifecycle()

          ZdtdApp(
            rootState = rootState,
            setupFlow = vm.setup,
            uiStateFlow = vm.uiState,
            logsFlow = vm.logs,
            appUpdateFlow = vm.appUpdate,
            backupFlow = vm.backup,
            programUpdatesFlow = vm.programUpdates,
            actions = remember(vm) { vm },
          )
        }
      }
    }
  }

  private fun openUrl(url: String) {
    runCatching {
      val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
      startActivity(i)
    }
  }

  private fun openUnknownSourcesSettings() {
    val uri = Uri.parse("package:$packageName")
    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
    unknownSourcesLauncher.launch(i)
  }

  private fun installApk(filePath: String) {
    runCatching {
      val f = File(filePath)
      val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
      val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      startActivity(i)
    }.onFailure {
      // Fallback to releases page if install intent fails.
      openUrl("https://github.com/GAME-OVER-op/ZDT-D/releases")
    }
  }

  private fun shareFile(filePath: String, mime: String) {
    runCatching {
      val f = File(filePath)
      val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", f)
      val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      startActivity(Intent.createChooser(i, getString(R.string.ma_share_backup)))
    }.onFailure {
      Toast.makeText(this, getString(R.string.ma_share_failed), Toast.LENGTH_SHORT).show()
    }
  }

  override fun onStart() {
    super.onStart()
    vm.setAppVisible(true)
  }

  override fun onStop() {
    vm.setAppVisible(false)
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    vm.onAppResumed()
  }
}
