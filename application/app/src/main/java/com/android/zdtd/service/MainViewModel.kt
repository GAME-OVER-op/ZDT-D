package com.android.zdtd.service

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.content.res.Resources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.android.zdtd.service.api.ApiClient
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.api.DeviceInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import androidx.annotation.StringRes

enum class RootState {
  CHECKING,
  GRANTED,
  DENIED,
}

enum class SetupStep {
  WELCOME,
  ROOT,
  INSTALL,
  REBOOT,
  DONE,
}

enum class MigrationDialog {
  NONE,
  MAGISK_CONFIRM,
  NONMAGISK_WARN,
  NONMAGISK_CONFIRM,
  PROGRESS,
}

data class SetupUiState(
  val step: SetupStep = SetupStep.WELCOME,
  val installing: Boolean = false,
  val installLog: String = "",
  // For UI: which installer the app is going to use (Magisk / KernelSU / APatch / Manual).
  val installerLabel: String = "",
  // Manual export (when no installer is detected): we save zip to shared storage.
  val manualZipSaved: Boolean = false,
  val manualZipPath: String = "",
  val showManualDialog: Boolean = false,
  val manualDialogText: String = "",

  // Update / integrity prompts
  val showUpdatePrompt: Boolean = false,
  val updatePromptMandatory: Boolean = false,
  val updatePromptTitle: String = "",
  val updatePromptText: String = "",

  // Pre-install warnings (forced update / tamper / unsupported)
  val preInstallWarning: String? = null,

  // Reboot required screen text
  val rebootRequiredText: String = "",

  val oldVersionDetected: Boolean = false,
  // True when active module state is invalid/tampered and user must reinstall.
  val moduleReinstallRequired: Boolean = false,
  val tamperReinstallPendingReboot: Boolean = false,
  val installOk: Boolean = false,
  val installError: String? = null,

  // ----- Settings migration (after module update) -----
  val migrationAvailable: Boolean = false,
  val migrationDone: Boolean = false,
  val migrationButtonEnabled: Boolean = false,
  val migrationHintText: String = "",
  val migrationIsMagisk: Boolean = false,
  val migrationDialog: MigrationDialog = MigrationDialog.NONE,
  val migrationPercent: Int = 0,
  val migrationProgressText: String = "",
  val migrationFinished: Boolean = false,
  val migrationError: String? = null,
)

data class UiState(
  val baseUrl: String = "http://127.0.0.1:1006",
  val token: String = "",
  val device: DeviceInfo = DeviceInfo(),
  val status: ApiModels.StatusReport? = null,
  // True when the daemon API responds successfully (e.g., /api/status returns 2xx).
  val daemonOnline: Boolean = false,
  val programs: List<ApiModels.Program> = emptyList(),
  val busy: Boolean = false,
  val daemonLogTail: String = "",
)

data class LogLine(
  val ts: String,
  val level: String,
  val msg: String,
)

// ----- Backup / Restore (working_folder) -----

data class BackupItem(
  val name: String,
  val sizeBytes: Long = 0L,
  val createdAtText: String = "",
)

data class BackupUiState(
  val loading: Boolean = false,
  val items: List<BackupItem> = emptyList(),
  val error: String? = null,

  // Progress dialog (create / restore / import / delete)
  val progressVisible: Boolean = false,
  val progressTitle: String = "",
  val progressText: String = "",
  val progressPercent: Int = 0,
  val progressFinished: Boolean = false,
  val progressError: String? = null,

  // Version mismatch: allow user to force restore (advanced).
  val forceRestoreAvailable: Boolean = false,
  val forceRestoreName: String? = null,
)


// ----- Program updates (zapret / zapret2) -----

data class ProgramReleaseUi(
  val version: String,
  val downloadUrl: String,
  val publishedAt: String = "",
)

data class ProgramUpdateItemUi(
  val title: String,
  val titleRes: Int? = null,
  val installedVersion: String? = null,
  val latestVersion: String? = null,
  val latestDownloadUrl: String? = null,
  // Optional override chosen by user from the release list. If null -> use latest.
  val selectedVersion: String? = null,
  val selectedDownloadUrl: String? = null,
  val releases: List<ProgramReleaseUi> = emptyList(),
  val releasesLoading: Boolean = false,
  val releasesError: String? = null,
  val warningText: String? = null,
  val checking: Boolean = false,
  val updating: Boolean = false,
  val progressPercent: Int = 0,
  val statusText: String = "",
  val errorText: String? = null,
  val updateAvailable: Boolean = false,
)

data class ProgramUpdatesUiState(
  val stoppingService: Boolean = false,
  val zapret: ProgramUpdateItemUi = ProgramUpdateItemUi(title = "", titleRes = R.string.program_updates_zapret_title),
  val zapret2: ProgramUpdateItemUi = ProgramUpdateItemUi(title = "", titleRes = R.string.program_updates_zapret2_title),
)

sealed class BackupEvent {
  data object RequestImport : BackupEvent()
  data class ShareFile(val filePath: String, val mime: String) : BackupEvent()
}

class MainViewModel(app: Application) : AndroidViewModel(app), ZdtdActions {

  private val ctx: Context = app.applicationContext

  private fun str(@StringRes id: Int, vararg args: Any): String =
    getApplication<Application>().getString(id, *args)

  private val root = RootConfigManager(ctx)
  private val api = ApiClient(
    rootManager = root,
    baseUrlProvider = { _uiState.value.baseUrl },
    tokenProvider = { _uiState.value.token },
  )

  private val githubHttp = OkHttpClient.Builder()
    .retryOnConnectionFailure(true)
    .build()

  private val ceh = CoroutineExceptionHandler { _, e ->
    // Prevent background coroutine crashes from killing the app.
    log("ERR", "uncaught: ${e::class.java.simpleName}: ${e.message ?: e}")
  }

  private fun launchIO(block: suspend CoroutineScope.() -> Unit) =
    viewModelScope.launch(Dispatchers.IO + ceh, block = block)

  private val _rootState = MutableStateFlow(RootState.DENIED)
  val rootState: StateFlow<RootState> = _rootState.asStateFlow()

  private val _setup = MutableStateFlow(
    SetupUiState(
      step = when {
        root.isSetupDone() -> SetupStep.DONE
        root.isWelcomeAccepted() -> SetupStep.ROOT
        else -> SetupStep.WELCOME
      }
    )
  )
  val setup: StateFlow<SetupUiState> = _setup.asStateFlow()

  private val _uiState = MutableStateFlow(UiState(device = detectDeviceInfo()))
  val uiState: StateFlow<UiState> = _uiState.asStateFlow()

  private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
  val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

  // ----- Backup / Restore -----
  private val _backup = MutableStateFlow(BackupUiState())
  val backup: StateFlow<BackupUiState> = _backup.asStateFlow()

  private val _backupEvents = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 8)
  val backupEvents: SharedFlow<BackupEvent> = _backupEvents.asSharedFlow()

  // ----- Program updates (zapret / zapret2) -----
  private val _programUpdates = MutableStateFlow(ProgramUpdatesUiState())
  val programUpdates: StateFlow<ProgramUpdatesUiState> = _programUpdates.asStateFlow()

  // ----- App updates (GitHub) -----
  private val _appUpdate = MutableStateFlow(
    AppUpdateUiState(
      enabled = root.isAppUpdateCheckEnabled(),
      languageMode = root.getAppLanguageMode(),
      daemonStatusNotificationEnabled = root.isDaemonStatusNotificationEnabled(),
    )
  )
  val appUpdate: StateFlow<AppUpdateUiState> = _appUpdate.asStateFlow()

  private val _appUpdateEvents = MutableSharedFlow<AppUpdateEvent>(extraBufferCapacity = 8)
  val appUpdateEvents: SharedFlow<AppUpdateEvent> = _appUpdateEvents.asSharedFlow()

  // ----- Runtime permissions (Android 13+) -----
  private val _notificationEvents = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 4)
  val notificationEvents: SharedFlow<NotificationEvent> = _notificationEvents.asSharedFlow()

  // One-shot toasts for user-facing feedback (e.g. manual update checks).
  private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

  private fun toast(msg: String) {
    _toastEvents.tryEmit(msg)
  }

  private var appUpdateCheckedThisSession: Boolean = false
  private var appUpdateDownloadJob: Job? = null

  private var pendingEnableDaemonNotification: Boolean = false

  private var statusJob: Job? = null
  private var daemonLogJob: Job? = null
  private var appVisible: Boolean = false

  private var didInit: Boolean = false
  private var appUpdateBannerDismissedThisSession: Boolean = false
  private var startedFromLauncher: Boolean = true

  private fun isSetupDone(): Boolean = _setup.value.step == SetupStep.DONE

  init {
    // Initialization is triggered from MainActivity.onCreate via onAppStart().
  }

  fun onAppStart(fromLauncher: Boolean) {
    if (didInit) return
    didInit = true
    startedFromLauncher = fromLauncher

    // Restore cached app-update banner state (persists across restarts).
    restoreCachedAppUpdateState()

    // If the user has already accepted the welcome screen (or completed setup earlier),
    // kick off a root check automatically on app start.
    if (root.isWelcomeAccepted() || root.isSetupDone()) {
      _rootState.value = RootState.CHECKING
      ensureRootAndLoadToken()
    }
  }

  private fun parseVersionCode(modulePropText: String): Int? {
    return modulePropText.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.startsWith("versionCode=") }
      ?.substringAfter("versionCode=")
      ?.trim()
      ?.toIntOrNull()
  }

  private fun parseVersion(modulePropText: String): String? {
    return modulePropText.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.startsWith("version=") }
      ?.substringAfter("version=")
      ?.trim()
      ?.takeIf { it.isNotBlank() }
  }

  private fun readBundledModuleVersionCode(): Int? {
    val text = runCatching {
      ctx.assets.open("module.prop").bufferedReader().use { it.readText() }
    }.getOrNull() ?: return null
    return parseVersionCode(text)
  }

  private fun readBundledModuleVersionAndCode(): Pair<String?, Int?> {
    val text = runCatching {
      ctx.assets.open("module.prop").bufferedReader().use { it.readText() }
    }.getOrNull() ?: return Pair<String?, Int?>(null, null)
    return parseVersion(text) to parseVersionCode(text)
  }

  private fun readInstalledModuleVersionCode(): Int? {
    val text = runCatching {
      root.readTextFile("/data/adb/modules/ZDT-D/module.prop")
    }.getOrNull() ?: return null
    return parseVersionCode(text)
  }


  private fun isNetworkAvailable(): Boolean {
    val cm = runCatching { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }.getOrNull()
      ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
      || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
      || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
      || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
  }

  private fun normalizeTagToVersion(tag: String): String {
    var s = tag.trim()
    if (s.startsWith("v", ignoreCase = true)) s = s.substring(1)
    return s
  }

  private fun releaseApkUrl(tag: String): String {
    // Asset name is stable by design.
    return "https://github.com/GAME-OVER-op/ZDT-D/releases/download/${tag}/app-release.apk"
  }

  private suspend fun httpGetMaybeCached(
    url: String,
    etag: String?,
  ): Triple<Int, String?, String?> {
    val req = Request.Builder()
      .url(url)
      .header("User-Agent", "ZDT-D-Android")
      .apply {
        if (!etag.isNullOrBlank()) header("If-None-Match", etag)
      }
      .build()

    githubHttp.newCall(req).execute().use { resp ->
      val code = resp.code
      val newEtag = resp.header("ETag")
      val body = if (code == 200) resp.body?.string() else null
      return Triple(code, body, newEtag)
    }
  }

  private fun maybeCheckAppUpdate(force: Boolean) {
    if (!_appUpdate.value.enabled) return
    if (!force && appUpdateCheckedThisSession) return
    appUpdateCheckedThisSession = true
    launchIO { checkAppUpdateInternal(force = force) }
  }

  private suspend fun checkAppUpdateInternal(force: Boolean) {
    val manual = force
    if (!root.isAppUpdateCheckEnabled()) {
      if (manual) toast(str(R.string.mv_auto_001))
      return
    }
    if (!isNetworkAvailable()) {
      if (manual) toast(str(R.string.mv_auto_002))
      return
    }

    val now = System.currentTimeMillis()
    val cooldownMs = 12L * 60L * 60L * 1000L
    val lastTs = root.getAppUpdateLastCheckTs()
    // If device clock moved backwards, don't lock the user out of checks.
    val withinCooldown = (lastTs > 0L) && (now >= lastTs) && ((now - lastTs) < cooldownMs)
    if (!force && withinCooldown) {
      return
    }

    _appUpdate.update { it.copy(enabled = true, checking = true, errorText = null) }

    // 1) Latest release
    val latestUrl = "https://api.github.com/repos/GAME-OVER-op/ZDT-D/releases/latest"
    val etagRel = root.getGitHubEtagLatestRelease()
    val (codeRel, bodyRel, newEtagRel) = runCatching { httpGetMaybeCached(latestUrl, etagRel) }
      .getOrElse {
        _appUpdate.update { it.copy(checking = false) }
        if (manual) toast(str(R.string.mv_auto_003))
        return
      }

    val tag: String?
    val htmlUrl: String?
    when (codeRel) {
      200 -> {
        val js = runCatching { JSONObject(bodyRel ?: "{}") }.getOrNull() ?: JSONObject()
        tag = js.optString("tag_name").takeIf { it.isNotBlank() }
        htmlUrl = js.optString("html_url").takeIf { it.isNotBlank() }
        root.setCachedLatestReleaseTag(tag)
        root.setCachedLatestReleaseHtmlUrl(htmlUrl)
        root.setGitHubEtagLatestRelease(newEtagRel)
      }
      304 -> {
        tag = root.getCachedLatestReleaseTag()
        htmlUrl = root.getCachedLatestReleaseHtmlUrl()
      }
      else -> {
        _appUpdate.update { it.copy(checking = false) }
        if (manual) toast(str(R.string.mv_auto_003))
        return
      }
    }

    if (tag.isNullOrBlank()) {
      _appUpdate.update { it.copy(checking = false) }
      if (manual) toast(str(R.string.mv_auto_003))
      return
    }

    // 2) module.prop (always main)
    val modulePropUrl = "https://raw.githubusercontent.com/GAME-OVER-op/ZDT-D/main/module.prop"
    val etagProp = root.getGitHubEtagModuleProp()
    val (codeProp, bodyProp, newEtagProp) = runCatching { httpGetMaybeCached(modulePropUrl, etagProp) }
      .getOrElse {
        _appUpdate.update { it.copy(checking = false) }
        if (manual) toast(str(R.string.mv_auto_003))
        return
      }

    val remoteVersion: String?
    val remoteCode: Int?
    when (codeProp) {
      200 -> {
        val txt = bodyProp ?: ""
        remoteVersion = parseVersion(txt)
        remoteCode = parseVersionCode(txt)
        root.setCachedRemoteVersion(remoteVersion)
        root.setCachedRemoteVersionCode(remoteCode ?: 0)
        root.setGitHubEtagModuleProp(newEtagProp)
      }
      304 -> {
        remoteVersion = root.getCachedRemoteVersion()
        val c = root.getCachedRemoteVersionCode()
        remoteCode = if (c > 0) c else null
      }
      else -> {
        _appUpdate.update { it.copy(checking = false) }
        if (manual) toast(str(R.string.mv_auto_003))
        return
      }
    }

    if (remoteVersion.isNullOrBlank() || remoteCode == null || remoteCode <= 0) {
      _appUpdate.update { it.copy(checking = false) }
      if (manual) toast(str(R.string.mv_auto_003))
      return
    }

    // remoteCode is a var (filled through multiple branches), so keep it in a stable val for smart-casts.
    val rc = remoteCode!!

    // Confirm that latest release tag matches module.prop version.
    val tagVer = normalizeTagToVersion(tag)
    if (tagVer != remoteVersion) {
      // Tag and module.prop are out of sync: do not notify.
      root.setAppUpdateLastCheckTs(now) // still rate-limit to avoid spamming
      root.clearCachedAppUpdate()
      _appUpdate.update { it.copy(checking = false) }
      if (manual) toast(str(R.string.mv_auto_004))
      return
    }

    // Local comparison is based on the bundled module.prop inside the APK (next to the installer payload).
    // This ensures that online update checks follow the same versioning as the embedded module.
    val (localNameRaw, localCodeRaw) = readBundledModuleVersionAndCode()
    val localCode = localCodeRaw ?: BuildConfig.VERSION_CODE
    val localName = localNameRaw ?: BuildConfig.VERSION_NAME
    val downloadUrl = releaseApkUrl(tag)
    val updateAvailable = rc > localCode

    if (!updateAvailable) {
      root.setAppUpdateLastCheckTs(now)
      root.clearCachedAppUpdate()
      _appUpdate.update { it.copy(
        enabled = root.isAppUpdateCheckEnabled(),
        checking = false,
        bannerVisible = false,
        urgent = false,
        localVersionName = localName,
        localVersionCode = localCode,
        remoteVersionName = remoteVersion,
        remoteVersionCode = rc,
        releaseTag = tag,
        releaseHtmlUrl = htmlUrl,
        downloadUrl = downloadUrl,
        errorText = null,
      ) }
      if (manual) toast(str(R.string.mv_auto_005))
      return
    }

    val urgent = (rc / 100 == localCode / 100) && (rc % 100 != 0)

    appUpdateBannerDismissedThisSession = false

    root.setAppUpdateLastCheckTs(now)
    root.setCachedAppUpdateAvailable(true)
    root.setCachedAppUpdateUrgent(urgent)
    root.setCachedAppUpdateReleaseTag(tag)
    root.setCachedAppUpdateReleaseHtmlUrl(htmlUrl)
    root.setCachedAppUpdateRemoteVersion(remoteVersion)
    root.setCachedAppUpdateRemoteVersionCode(rc)
    root.setCachedAppUpdateDownloadUrl(downloadUrl)
    root.setCachedAppUpdateFoundTs(now)
    _appUpdate.update { it.copy(
      enabled = root.isAppUpdateCheckEnabled(),
      checking = false,
      bannerVisible = !appUpdateBannerDismissedThisSession,
      urgent = urgent,
      localVersionName = localName,
      localVersionCode = localCode,
      remoteVersionName = remoteVersion,
      remoteVersionCode = rc,
      releaseTag = tag,
      releaseHtmlUrl = htmlUrl,
      downloadUrl = downloadUrl,
      errorText = null,
    ) }

    if (manual) {
      toast(if (urgent) str(R.string.mv_auto_006) else str(R.string.mv_auto_007))
    }
  }

  
private fun restoreCachedAppUpdateState() {
  // If checks are disabled, hide banner and clear persisted "available" flag (to avoid surprises).
  if (!root.isAppUpdateCheckEnabled()) {
    root.clearCachedAppUpdate()
    _appUpdate.update { it.copy(enabled = false, bannerVisible = false, urgent = false) }
    return
  }

  val available = root.isCachedAppUpdateAvailable()
  if (!available) return

  val remoteCode = root.getCachedAppUpdateRemoteVersionCode()
  val remoteVer = root.getCachedAppUpdateRemoteVersion()
  val tag = root.getCachedAppUpdateReleaseTag()
  val htmlUrl = root.getCachedAppUpdateReleaseHtmlUrl()
  val downloadUrl = root.getCachedAppUpdateDownloadUrl()
  val urgent = root.getCachedAppUpdateUrgent()

  // Local comparison is based on bundled module.prop in the APK.
  val (localNameRaw, localCodeRaw) = readBundledModuleVersionAndCode()
  val localCode = localCodeRaw ?: BuildConfig.VERSION_CODE
  val localName = localNameRaw ?: BuildConfig.VERSION_NAME

  // If app was updated and now includes the newer module version, drop the banner.
  if (remoteCode > 0 && localCode >= remoteCode) {
    root.clearCachedAppUpdate()
    _appUpdate.update { it.copy(
      enabled = true,
      checking = false,
      bannerVisible = false,
      urgent = false,
      localVersionName = localName,
      localVersionCode = localCode,
      remoteVersionName = null,
      remoteVersionCode = null,
      releaseTag = null,
      releaseHtmlUrl = null,
      downloadUrl = null,
      errorText = null,
    ) }
    return
  }

  _appUpdate.update { it.copy(
    enabled = true,
    checking = false,
    bannerVisible = true,
    urgent = urgent,
    localVersionName = localName,
    localVersionCode = localCode,
    remoteVersionName = remoteVer,
    remoteVersionCode = remoteCode.takeIf { it > 0 },
    releaseTag = tag,
    releaseHtmlUrl = htmlUrl,
    downloadUrl = downloadUrl,
    errorText = null,
  ) }
}

fun onAppResumed() {
  // Re-check in background on resume if cooldown is over (or clock changed).
  maybeCheckAppUpdate(force = false)
  // Also re-sync banner state with current installed version (in case the app got updated).
  restoreCachedAppUpdateState()
}

private fun clearDownloadedUpdateApk() {
    val p = _appUpdate.value.downloadedPath
    if (!p.isNullOrBlank()) {
      runCatching { File(p).delete() }
    }
    _appUpdate.update { it.copy(downloadedPath = null, needsUnknownSourcesPermission = false) }
  }

  private fun updateDownloadUi(
    downloading: Boolean,
    percent: Int,
    speedBps: Long,
    path: String?,
    err: String? = null,
  ) {
    _appUpdate.update {
      it.copy(
        downloading = downloading,
        downloadPercent = percent,
        downloadSpeedBytesPerSec = max(0, speedBps),
        downloadedPath = path,
        errorText = err,
      )
    }
  }

  private fun canRequestPackageInstalls(): Boolean {
    return runCatching {
      ctx.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)
  }

  private fun hasPostNotificationsPermission(): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
      ctx,
      android.Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
  }

  private suspend fun downloadLatestApk(url: String): String? {
    val dest = File(ctx.cacheDir, "zdt_app_update.apk")
    runCatching { dest.delete() }

    val req = Request.Builder()
      .url(url)
      .header("User-Agent", "ZDT-D-Android")
      .build()

    githubHttp.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) return null
      val body = resp.body ?: return null
      val total = body.contentLength().takeIf { it > 0 } ?: -1L

      body.byteStream().use { input ->
        FileOutputStream(dest).use { out ->
          val buf = ByteArray(64 * 1024)
          var read: Int
          var done = 0L
          var lastBytes = 0L
          var lastTs = System.currentTimeMillis()
          var speedBps = 0L

          while (true) {
            read = input.read(buf)
            if (read <= 0) break
            out.write(buf, 0, read)
            done += read.toLong()

            val now = System.currentTimeMillis()
            if (now - lastTs >= 500) {
              val deltaBytes = done - lastBytes
              val deltaMs = (now - lastTs).coerceAtLeast(1)
              speedBps = (deltaBytes * 1000L) / deltaMs
              lastBytes = done
              lastTs = now
            }

            val pct = if (total > 0) ((done * 100L) / total).toInt() else 0
            updateDownloadUi(
              downloading = true,
              percent = pct.coerceIn(0, 100),
              speedBps = speedBps,
              path = dest.absolutePath,
              err = null,
            )
          }
        }
      }
    }
    return dest.absolutePath
  }

  private suspend fun readBootIdRoot(): String {
    val r = root.execRootSh("cat /proc/sys/kernel/random/boot_id 2>/dev/null || true")
    return r.out.joinToString("\n").trim()
  }

  private suspend fun snapshotAndUpdateBootState(): Boolean {
    val current = runCatching { readBootIdRoot() }.getOrDefault("")
    if (current.isBlank()) return false
    val prev = runCatching { root.getLastSeenBootId() }.getOrNull()?.trim().orEmpty()
    val changed = prev.isNotBlank() && prev != current
    if (prev != current) runCatching { root.setLastSeenBootId(current) }
    return changed
  }

  fun ensureRootAndLoadToken() {
    if (_rootState.value != RootState.CHECKING) return
    launchIO {
      val ok = runCatching { root.testRoot() }.getOrDefault(false)
      if (!ok) {
        _rootState.value = RootState.DENIED
        log("ERR", str(R.string.log_root_access_required))
        return@launchIO
      }
      _rootState.value = RootState.GRANTED

      val bootChanged = snapshotAndUpdateBootState()

      val token = runCatching { root.readApiToken() }.getOrDefault("")
      _uiState.update { it.copy(token = token) }
      if (token.isBlank()) {
        log("WARN", str(R.string.log_api_token_missing_fmt, "/data/adb/modules/ZDT-D/api/token"))
      }

      val id = "ZDT-D"

      // 1) If an update is staged, the device must reboot so Magisk can apply it.
      val updatePending = runCatching { root.isModuleUpdatePending() }.getOrDefault(false)
      if (updatePending) {
        val pendingText = runCatching { root.readTextFile("/data/adb/modules_update/${id}/module.prop") }.getOrDefault("")
        val pendingCode = parseVersionCode(pendingText)
        val reason = if (pendingCode != null) {
          str(R.string.mv_reboot_pending_update, pendingCode)
        } else {
          str(R.string.mv_reboot_pending_install)
        }
        _setup.update { st ->
          st.copy(
            step = SetupStep.REBOOT,
            rebootRequiredText = reason,
            showUpdatePrompt = false,
            updatePromptMandatory = false,
            updatePromptTitle = "",
            updatePromptText = "",
            moduleReinstallRequired = false,
            tamperReinstallPendingReboot = runCatching { root.isTamperReinstallPendingReboot() }.getOrDefault(false),
          )
        }
        refreshMigrationUiState()
        return@launchIO
      }


      // Update migration UI state (clears any stale flags when no staged update exists).
      refreshMigrationUiState()

      // 2) Check if module is installed in the active directory (modules).
      val installed = runCatching {
        root.execRoot("sh -c 'test -f /data/adb/modules/${id}/module.prop'").isSuccess
      }.getOrDefault(false)

      val oldVer = runCatching { root.hasOldModuleVersionWebroot() }.getOrDefault(false)

      if (!installed) {
        _setup.update { st ->
          st.copy(
            step = SetupStep.INSTALL,
            oldVersionDetected = oldVer,
            showUpdatePrompt = false,
            moduleReinstallRequired = false,
            tamperReinstallPendingReboot = runCatching { root.isTamperReinstallPendingReboot() }.getOrDefault(false),
          )
        }
        return@launchIO
      }

      // Mark setup done (we still verify versions/layout below).
      root.setSetupDone(true)

      // 3) Anti-tamper / legacy layout detection.
      val legacyLayout = runCatching { root.hasLegacySystemDir() }.getOrDefault(false)
      if (legacyLayout) {
        runCatching { root.setTamperReinstallPendingReboot(true) }
        _setup.update { st ->
          st.copy(
            step = SetupStep.INSTALL,
            oldVersionDetected = oldVer,
            preInstallWarning = str(R.string.mv_auto_009) +
              str(R.string.mv_auto_010),
            showUpdatePrompt = false,
            moduleReinstallRequired = true,
            tamperReinstallPendingReboot = true,
          )
        }
        return@launchIO
      }

      // 4) Version gate.
      val installedText = runCatching { root.readTextFile("/data/adb/modules/${id}/module.prop") }.getOrDefault("")
      val installedCode = parseVersionCode(installedText)
      val minSupported = 25000

      if (installedCode != null && installedCode < minSupported) {
        runCatching { root.setTamperReinstallPendingReboot(true) }
        _setup.update { st ->
          st.copy(
            step = SetupStep.INSTALL,
            oldVersionDetected = oldVer,
            preInstallWarning = str(R.string.mv_module_version_unsupported, installedCode),
            showUpdatePrompt = false,
            moduleReinstallRequired = true,
            tamperReinstallPendingReboot = true,
          )
        }
        return@launchIO
      }

      // Clear sticky anti-tamper reinstall state after a real reboot if module is clean again.
      if (bootChanged) {
        runCatching { root.setTamperReinstallPendingReboot(false) }
      }
      val stickyTamperPending = runCatching { root.isTamperReinstallPendingReboot() }.getOrDefault(false)

      // 5) Optional update prompt (shown only on a cold start from launcher).
      val bundledCode = readBundledModuleVersionCode()
      val showOptional = startedFromLauncher && installedCode != null && bundledCode != null && installedCode >= minSupported && installedCode < bundledCode

      _setup.update { st ->
        st.copy(
          step = SetupStep.DONE,
          oldVersionDetected = oldVer,
          preInstallWarning = null,
          rebootRequiredText = "",
          showUpdatePrompt = showOptional,
          updatePromptMandatory = false,
          updatePromptTitle = if (showOptional) str(R.string.mv_module_update_available) else "",
          moduleReinstallRequired = false,
          tamperReinstallPendingReboot = stickyTamperPending,
          updatePromptText = if (showOptional) {
            str(R.string.mv_module_update_prompt_text, installedCode ?: -1, bundledCode ?: -1)
          } else "",
        )
      }

      // Start polling only after setup is complete.
      maybeStartForegroundJobs()
    }
  }

  /**
   * Called from Activity.onStart/onStop.
   * We keep all polling strictly foreground-only.
   */
  fun setAppVisible(visible: Boolean) {
    appVisible = visible
    if (!visible) {
      statusJob?.cancel(); statusJob = null
      daemonLogJob?.cancel(); daemonLogJob = null
      return
    }

    maybeStartForegroundJobs()
  }

  private fun maybeStartForegroundJobs() {
    if (!appVisible) return
    if (_rootState.value != RootState.GRANTED) return
    if (!isSetupDone()) return
    startStatusPolling()
    startDaemonLogPolling()
    refreshPrograms()

    // Background update check (non-blocking).
    maybeCheckAppUpdate(force = false)
  }

  override fun retryRoot() {
    // If the user denied the initial Magisk prompt, libsu may keep a non-root shell cached.
    // Reset it so Magisk can show the prompt again on retry.
    runCatching { root.resetRootShell() }
    _rootState.value = RootState.CHECKING
    ensureRootAndLoadToken()
  }

  override fun acceptWelcome() {
    root.setWelcomeAccepted(true)
    _setup.update { it.copy(step = SetupStep.ROOT) }
  }

  override fun beginModuleInstall() {
    if (_rootState.value != RootState.GRANTED) {
      log("ERR", "root required")
      return
    }

    if (_setup.value.installing) return
    launchIO {
      val oldVer = runCatching { root.hasOldModuleVersionWebroot() }.getOrDefault(false)
      val installer = runCatching { root.detectModuleInstaller() }.getOrDefault(RootConfigManager.ModuleInstaller.UNKNOWN)
      val label = when (installer) {
        RootConfigManager.ModuleInstaller.MAGISK -> "Magisk"
        RootConfigManager.ModuleInstaller.KSU -> str(R.string.mv_auto_013)
        RootConfigManager.ModuleInstaller.APATCH -> "APatch"
        RootConfigManager.ModuleInstaller.UNKNOWN -> str(R.string.mv_auto_014)
      }

      // If we can't detect an installer, ask the user and export the zip to /sdcard.
      if (installer == RootConfigManager.ModuleInstaller.UNKNOWN) {
        _setup.update {
          it.copy(
            installing = false,
            installerLabel = label,
            manualZipSaved = false,
            manualZipPath = "",
            showManualDialog = true,
            oldVersionDetected = oldVer,
            manualDialogText = str(R.string.mv_auto_015) +
              str(R.string.mv_auto_016),
          )
        }
        return@launchIO
      }

      _setup.update {
        it.copy(
          installing = true,
          installError = null,
          installOk = false,
          installLog = "",
          installerLabel = label,
          manualZipSaved = false,
          manualZipPath = "",
          oldVersionDetected = oldVer,
        )
      }

      val (ok, out) = when (installer) {
        RootConfigManager.ModuleInstaller.MAGISK -> installViaMagisk()
        RootConfigManager.ModuleInstaller.KSU -> installViaKsu()
        RootConfigManager.ModuleInstaller.APATCH -> installViaApatch()
        RootConfigManager.ModuleInstaller.UNKNOWN -> false to ""
      }

      if (ok) {
        // Mark setup as completed so we don't show the installer again after reboot.
        root.setSetupDone(true)
        _setup.update { it.copy(installing = false, installOk = true, installLog = out) }
        refreshMigrationUiState()
      } else {
        _setup.update {
          it.copy(
            installing = false,
            installError = str(R.string.mv_auto_017),
            installLog = out,
            showManualDialog = true,
            manualZipSaved = false,
            manualZipPath = "",
            manualDialogText = str(R.string.mv_auto_018) +
              str(R.string.mv_auto_019) +
              str(R.string.mv_auto_020),
          )
        }
      }
    }
  }

  override fun dismissManualInstallDialog() {
    _setup.update { it.copy(showManualDialog = false, manualDialogText = "") }
  }

  override fun continueAfterInstall() {
    if (_rootState.value != RootState.GRANTED) return
    _setup.update { it.copy(step = SetupStep.DONE) }
    // Re-read token after installation and proceed.
    launchIO {
      val token = runCatching { root.readApiToken() }.getOrDefault("")
      _uiState.update { it.copy(token = token) }
      maybeStartForegroundJobs()
    }
  }

  override fun confirmManualInstall() {
    if (_rootState.value != RootState.GRANTED) {
      log("ERR", "root required")
      return
    }
    if (_setup.value.installing) return
    launchIO {
      val oldVer = runCatching { root.hasOldModuleVersionWebroot() }.getOrDefault(false)
      _setup.update {
        it.copy(
          installing = true,
          installError = null,
          installOk = false,
          showManualDialog = false,
          manualZipSaved = false,
          manualZipPath = "",
          oldVersionDetected = oldVer,
        )
      }

      val (ok, out, path) = exportModuleZipToSdcard()
      if (ok) {
        _setup.update {
          it.copy(
            installing = false,
            installError = null,
            installOk = false,
            installLog = out,
            manualZipSaved = true,
            manualZipPath = path,
          )
        }
      } else {
        _setup.update { it.copy(installing = false, installError = str(R.string.mv_auto_021), installLog = out) }
      }
    }
  }

  override fun beginModuleRemoval() {
    // 1) Mark Magisk module for removal.
    // 2) Start a small root watcher that waits until the app is uninstalled, then reboots.
    // 3) UI shows instructions to uninstall the app manually.
    val pkg = ctx.packageName
    launchIO {
      // Create Magisk remove marker.
      root.execRootSh("mkdir -p /data/adb/modules/ZDT-D && : > /data/adb/modules/ZDT-D/remove")

      val scriptPath = "/data/local/tmp/zdtd_uninstall_watch.sh"
      val logPath = "/data/local/tmp/zdtd_uninstall_watch.log"
      val script = """#!/system/bin/sh
PKG="$pkg"
LOG="$logPath"

echo "[\$(date)] watch start: ${'$'}PKG" >> "${'$'}LOG"

# Wait up to 15 minutes (180 * 5s)
i=0
while [ ${'$'}i -lt 180 ]; do
  if pm path "${'$'}PKG" >/dev/null 2>&1; then
    sleep 5
  else
    echo "[\$(date)] package removed, rebooting" >> "${'$'}LOG"
    sleep 5
    svc power reboot >/dev/null 2>&1 || reboot >/dev/null 2>&1
    exit 0
  fi
  i=${'$'}((i+1))
done

echo "[\$(date)] timeout waiting for uninstall" >> "${'$'}LOG"
exit 0
"""

      // Write script (safe heredoc) and chmod.
      root.execRootSh(
        """cat > '$scriptPath' <<'EOF'
$script
EOF
chmod 700 '$scriptPath'"""
      )

      // Run watcher detached.
      root.execRootSh(
        """if command -v setsid >/dev/null 2>&1; then
  setsid sh '$scriptPath' >/dev/null 2>&1 </dev/null &
elif toybox setsid >/dev/null 2>&1; then
  toybox setsid sh '$scriptPath' >/dev/null 2>&1 </dev/null &
else
  sh '$scriptPath' >/dev/null 2>&1 </dev/null &
fi""".trimIndent()
      )
    }


  }

  override fun rebootNow() {
    if (_rootState.value != RootState.GRANTED) return
    launchIO {
      runCatching { root.setTamperReinstallPendingReboot(false) }
      root.execRoot("sh -c 'svc power reboot'")
    }
  }


  // ----- Settings migration (after module update) -----

  override fun requestSettingsMigration() {
    if (_rootState.value != RootState.GRANTED) return
    if (!_setup.value.migrationAvailable || !_setup.value.migrationButtonEnabled || _setup.value.migrationDone) return
    launchIO {
      // Refresh installer type right before showing dialogs.
      val installer = runCatching { root.detectModuleInstaller() }.getOrDefault(RootConfigManager.ModuleInstaller.UNKNOWN)
      val isMagisk = installer == RootConfigManager.ModuleInstaller.MAGISK
      _setup.update { st ->
        st.copy(
          migrationIsMagisk = isMagisk,
          migrationDialog = if (isMagisk) MigrationDialog.MAGISK_CONFIRM else MigrationDialog.NONMAGISK_WARN,
          migrationError = null,
          migrationFinished = false,
        )
      }
    }
  }

  override fun dismissSettingsMigrationDialog() {
    _setup.update { st ->
      st.copy(migrationDialog = MigrationDialog.NONE)
    }
  }

  override fun confirmSettingsMigrationDialog() {
    when (val d = _setup.value.migrationDialog) {
      MigrationDialog.MAGISK_CONFIRM -> startSettingsMigration()
      MigrationDialog.NONMAGISK_WARN -> {
        _setup.update { st -> st.copy(migrationDialog = MigrationDialog.NONMAGISK_CONFIRM) }
      }
      MigrationDialog.NONMAGISK_CONFIRM -> startSettingsMigration()
      MigrationDialog.PROGRESS, MigrationDialog.NONE -> { /* no-op */ }
    }
  }

  override fun closeSettingsMigrationProgress() {
    _setup.update { st -> st.copy(migrationDialog = MigrationDialog.NONE) }
  }


  // ----- Backup / Restore (working_folder) -----

  private val backupDirPath = "/storage/emulated/0/ZDT-D_Backups"
  private val workingFolderPath = "/data/adb/modules/ZDT-D/working_folder"

  override fun refreshBackups() {
    if (_rootState.value != RootState.GRANTED) {
      _backup.update { it.copy(loading = false, items = emptyList(), error = str(R.string.mv_auto_022)) }
      return
    }
    launchIO {
      _backup.update { it.copy(loading = true, error = null) }
      runCatching {
        root.execRootSh("mkdir -p ${shQuote(backupDirPath)} 2>/dev/null || true")
        val script = buildString {
          append("cd ")
          append(shQuote(backupDirPath))
          append(" 2>/dev/null || exit 0; ")
          append("for f in *.zdtb; do ")
          append("[ -f \"${'$'}f\" ] || continue; ")
          append("sz=$(stat -c %s \"${'$'}f\" 2>/dev/null || (wc -c < \"${'$'}f\" 2>/dev/null) || echo 0); ")
          append("echo \"${'$'}f|${'$'}sz\"; ")
          append("done")
        }
        val r = root.execRootSh(script)
        val lines = r.out.joinToString("\n")
          .lineSequence()
          .map { it.trim() }
          .filter { it.isNotEmpty() }
          .toList()

        val items = lines.mapNotNull { line ->
          val parts = line.split("|", limit = 2)
          if (parts.isEmpty()) return@mapNotNull null
          val name = parts[0].trim()
          if (name.isBlank()) return@mapNotNull null
          val sz = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
          BackupItem(
            name = name,
            sizeBytes = sz,
            createdAtText = parseBackupCreatedAtText(name),
          )
        }.sortedByDescending { it.name }

        _backup.update { it.copy(loading = false, items = items, error = null) }
      }.onFailure { e ->
        _backup.update { it.copy(loading = false, error = str(R.string.mv_backup_list_read_error, (e.message ?: e.toString()))) }
      }
    }
  }

  override fun createBackup() {
  if (_rootState.value != RootState.GRANTED) return
  // Prevent starting another operation while one is running.
  if (_backup.value.progressVisible && !_backup.value.progressFinished) return

  launchIO {
    showBackupProgress(title = str(R.string.mv_auto_023), text = str(R.string.mv_auto_024), percent = 0)

    // Pre-check source.
    if (!rootPathExists(workingFolderPath)) {
      finishBackupProgress(error = str(R.string.mv_backup_settings_folder_missing, workingFolderPath))
      return@launchIO
    }
    val dirsFull = listSubdirs(workingFolderPath)
    if (dirsFull.isEmpty()) {
      finishBackupProgress(error = str(R.string.mv_auto_025))
      return@launchIO
    }

    root.execRootSh("mkdir -p ${shQuote(backupDirPath)} 2>/dev/null || true")

    val tsForFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    val backupName = "ZDT-D_backup_${tsForFile}.zdtb"
    val dest = "${backupDirPath}/${backupName}"

    // Stage folder (snapshot) to avoid tar warnings like "file changed as we read it"
    // and to avoid relying on tar append (-r), which is not supported by some Android tar builds.
    val tmpStage = "/data/local/tmp/zdt_backup_stage_${tsForFile}"
    val tmpTar = "${backupDirPath}/.tmp_${tsForFile}.tar"
    val warnings = mutableListOf<String>()

    // Prepare temp locations.
    root.execRootSh("rm -rf ${shQuote(tmpStage)} 2>/dev/null || true; rm -f ${shQuote(tmpTar)} 2>/dev/null || true; mkdir -p ${shQuote(tmpStage)}")

    // Write manifest into stage.
    val manifest = buildBackupManifest(createdAt = createdAt, dirsFull = dirsFull)
    val wrote = runCatching { root.writeTextFile("${tmpStage}/zdt_backup_manifest.json", manifest) }.getOrDefault(false)
    if (!wrote) {
      root.execRootSh("rm -rf ${shQuote(tmpStage)} 2>/dev/null || true")
      finishBackupProgress(error = str(R.string.mv_auto_026))
      return@launchIO
    }
    // Ensure manifest stays readable across different root managers / shells.
    root.execRootSh("chmod 0644 ${shQuote(tmpStage)}/zdt_backup_manifest.json 2>/dev/null || true")

    // Compute weights for progress (based on source folders).
    val sizes = mutableMapOf<String, Long>()
    var total = 0L
    for (d in dirsFull) {
      val sz = duKb(d)
      sizes[d] = sz
      total += sz
    }
    if (total <= 0L) total = dirsFull.size.toLong().coerceAtLeast(1L)

    var done = 0L

    // Copy directories into stage one by one for progress.
    for (d in dirsFull) {
      currentCoroutineContext().ensureActive()
      val name = d.substringAfterLast('/').ifBlank { "folder" }

      val pct = ((done * 80L) / total).toInt().coerceIn(0, 80)
      _backup.update { st -> st.copy(progressText = str(R.string.mv_copying_name, name), progressPercent = pct) }

      val rCopy = root.execRootSh("cp -a ${shQuote(d)} ${shQuote(tmpStage)}/ 2>/dev/null || cp -r ${shQuote(d)} ${shQuote(tmpStage)}/")
      if (!rCopy.isSuccess) {
        val err = (rCopy.out + rCopy.err).joinToString("\n").trim()
        root.execRootSh("rm -rf ${shQuote(tmpStage)} 2>/dev/null || true")
        val detail = if (err.isBlank()) "cp failed" else err
        finishBackupProgress(error = str(R.string.mv_copy_error_with_detail, name, detail))
        return@launchIO
      }

      val w = sizes[d] ?: 0L
      done += if (w > 0L) w else 1L
    }

    _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_027), progressPercent = 85) }
    val rTar = root.execRootSh("tar -cf ${shQuote(tmpTar)} -C ${shQuote(tmpStage)} .")
    if (!rTar.isSuccess) {
      val code = runCatching { rTar.code }.getOrDefault(-1)
      val err = (rTar.out + rTar.err).joinToString("\n").trim()
      if (code == 1) {
        warnings += (if (err.isBlank()) "tar warning" else err)
      } else {
        root.execRootSh("rm -rf ${shQuote(tmpStage)} 2>/dev/null || true; rm -f ${shQuote(tmpTar)} 2>/dev/null || true")
        val detail = if (err.isBlank()) "tar failed (code=$code)" else err
        finishBackupProgress(error = str(R.string.mv_backup_archive_create_failed, detail))
        return@launchIO
      }
    }

    // Verify that the tar contains something besides the manifest (otherwise we produced a useless backup).
    val rHas = root.execRootSh(
      "tar -tf ${shQuote(tmpTar)} 2>/dev/null | " +
        "while IFS= read -r e; do " +
        "case \"${'$'}e\" in ''|'.'|'./'|'zdt_backup_manifest.json'|'./zdt_backup_manifest.json') ;; " +
        "*) echo \"${'$'}e\"; break;; esac; " +
        "done"
    )
    val hasOther = rHas.out.joinToString("\n").trim().isNotBlank()
    if (!hasOther) {
      val err = (rTar.out + rTar.err).joinToString("\n").trim()
      root.execRootSh("rm -rf ${shQuote(tmpStage)} 2>/dev/null || true; rm -f ${shQuote(tmpTar)} 2>/dev/null || true")
      val detail = if (err.isBlank()) "" else err.take(200)
      val msg = if (detail.isBlank()) str(R.string.mv_backup_not_created_no_folders_short) else str(R.string.mv_backup_not_created_no_folders_detail, detail)
      finishBackupProgress(error = msg.trim())
      return@launchIO
    }

    _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_028), progressPercent = 95) }
    val gzipScript = buildString {
      append("rm -f ")
      append(shQuote(dest))
      append(" 2>/dev/null || true; ")
      append("if gzip -c ")
      append(shQuote(tmpTar))
      append(" > ")
      append(shQuote(dest))
      append(" 2>/dev/null; then :; ")
      append("elif /system/bin/toybox gzip -c ")
      append(shQuote(tmpTar))
      append(" > ")
      append(shQuote(dest))
      append(" 2>/dev/null; then :; ")
      append("else echo 'gzip not found' 1>&2; exit 1; fi; ")
      append("chmod 0644 ")
      append(shQuote(dest))
      append(" 2>/dev/null || true; ")
      append("rm -rf ")
      append(shQuote(tmpStage))
      append(" 2>/dev/null || true; ")
      append("rm -f ")
      append(shQuote(tmpTar))
      append(" 2>/dev/null || true")
    }
    val rGz = root.execRootSh(gzipScript)
    if (!rGz.isSuccess) {
      val err = (rGz.out + rGz.err).joinToString("\n").trim()
      // Cleanup stage/tar even on failure.
      root.execRootSh("rm -rf ${shQuote(tmpStage)} 2>/dev/null || true; rm -f ${shQuote(tmpTar)} 2>/dev/null || true")
      val detail = if (err.isBlank()) "gzip failed" else err
      finishBackupProgress(error = str(R.string.mv_backup_compress_failed, detail))
      return@launchIO
    }

    if (warnings.isNotEmpty()) {
      val first = warnings.first().lineSequence().firstOrNull()?.take(200).orEmpty()
      finishBackupProgress(text = str(R.string.mv_backup_done_with_warning, backupName), percent = 100)
      if (first.isNotBlank()) toast(str(R.string.mv_backup_created_with_warning, first))
    } else {
      finishBackupProgress(text = str(R.string.mv_backup_done_name, backupName), percent = 100)
    }
    refreshBackups()
  }
}
  override fun requestBackupImport() {
    if (_rootState.value != RootState.GRANTED) {
      toast(str(R.string.mv_auto_029))
      return
    }
    _backupEvents.tryEmit(BackupEvent.RequestImport)
  }

  override fun onBackupImportResult(uri: Uri?) {
    if (_rootState.value != RootState.GRANTED) return
    if (uri == null) {
      toast(str(R.string.mv_auto_030))
      return
    }
    if (_backup.value.progressVisible && !_backup.value.progressFinished) return

    launchIO {
      showBackupProgress(title = str(R.string.mv_auto_031), text = str(R.string.mv_auto_032), percent = 5)
      val tmp = File(ctx.cacheDir, "zdtb_import_${System.currentTimeMillis()}.zdtb")
      val okCopy = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
          FileOutputStream(tmp).use { output ->
            input.copyTo(output)
          }
        } ?: return@runCatching false
        true
      }.getOrDefault(false)

      if (!okCopy || !tmp.exists()) {
        finishBackupProgress(error = str(R.string.mv_auto_033))
        runCatching { tmp.delete() }
        return@launchIO
      }

      _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_034), progressPercent = 20) }
      val v = validateBackupFile(tmp.absolutePath)
      if (!v.ok) {
        finishBackupProgress(error = v.error ?: str(R.string.mv_auto_035))
        runCatching { tmp.delete() }
        return@launchIO
      }

      root.execRootSh("mkdir -p ${shQuote(backupDirPath)} 2>/dev/null || true")
      val tsForFile = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
      val name = "ZDT-D_backup_${tsForFile}_import.zdtb"
      val dest = "${backupDirPath}/${name}"
      _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_036), progressPercent = 60) }

      val r = root.execRootSh("cp -f ${shQuote(tmp.absolutePath)} ${shQuote(dest)} 2>/dev/null || cat ${shQuote(tmp.absolutePath)} > ${shQuote(dest)}; chmod 0644 ${shQuote(dest)} 2>/dev/null || true")
      runCatching { tmp.delete() }
      if (!r.isSuccess) {
        val err = (r.out + r.err).joinToString("\n").trim()
        val detail = if (err.isBlank()) "copy failed" else err
        finishBackupProgress(error = str(R.string.mv_backup_import_save_failed, detail))
        return@launchIO
      }

      finishBackupProgress(text = str(R.string.mv_backup_import_done, name), percent = 100)
      refreshBackups()
    }
  }

  override fun restoreBackup(name: String, ignoreVersionCode: Boolean) {
    if (_rootState.value != RootState.GRANTED) return
    if (_backup.value.progressVisible && !_backup.value.progressFinished) return

    launchIO {
      showBackupProgress(title = str(R.string.mv_auto_037), text = str(R.string.mv_auto_034), percent = 0)
      val path = "${backupDirPath}/${name}"

      if (!rootPathExists(path)) {
        finishBackupProgress(error = str(R.string.mv_backup_file_not_found, name))
        return@launchIO
      }

      val v = validateBackupFile(path, ignoreVersionCode = ignoreVersionCode)
      if (!v.ok) {
        // If this is a versionCode mismatch, offer "Restore anyway" in UI.
        val canForce = v.versionMismatch && !ignoreVersionCode
        finishBackupProgress(
          error = v.error ?: str(R.string.mv_auto_035),
          forceRestoreAvailable = canForce,
          forceRestoreName = if (canForce) name else null,
        )
        return@launchIO
      }

      // Stop is async: after /api/stop the processes may still be shutting down and API may temporarily reject start.
      // We must wait until /api/status confirms everything is stopped.
      _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_038), progressPercent = 5) }
      val stopOk = runCatching { api.stopService() }.getOrDefault(false)
      if (!stopOk) {
        finishBackupProgress(error = str(R.string.mv_auto_039))
        return@launchIO
      }

      _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_040), progressPercent = 8) }
      val waitStart = System.currentTimeMillis()
      val waitTimeoutMs = 30_000L
      val pollMs = 1_000L
      var stoppedAt: Long? = null
      while (System.currentTimeMillis() - waitStart < waitTimeoutMs) {
        currentCoroutineContext().ensureActive()
        val report = runCatching { api.getStatus() }.getOrNull()
        // Per requirements: status reliably reflects current state.
        if (report != null && !ApiModels.isServiceOn(report)) {
          stoppedAt = System.currentTimeMillis()
          break
        }
        delay(pollMs)
      }
      if (stoppedAt == null) {
        finishBackupProgress(error = str(R.string.mv_auto_041))
        return@launchIO
      }

      _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_042), progressPercent = 10) }
      val wipe = root.execRootSh("rm -rf ${shQuote(workingFolderPath)} 2>/dev/null || true; mkdir -p ${shQuote(workingFolderPath)} 2>/dev/null || true")
      if (!wipe.isSuccess) {
        finishBackupProgress(error = str(R.string.mv_auto_043))
        return@launchIO
      }

      // Extract into temp dir first to avoid polluting working_folder with manifest.
      val tmpDir = "/data/local/tmp/zdt_restore_${System.currentTimeMillis()}"
      _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_044), progressPercent = 20) }
      root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true; mkdir -p ${shQuote(tmpDir)}")
      val rExtract = root.execRootSh("cd ${shQuote(tmpDir)} && tar -xzf ${shQuote(path)} 2>/dev/null")
      if (!rExtract.isSuccess) {
        val err = (rExtract.out + rExtract.err).joinToString("\n").trim()
        root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true")
        val detail = if (err.isBlank()) "tar failed" else err
        finishBackupProgress(error = str(R.string.mv_backup_extract_failed, detail))
        return@launchIO
      }
      val rMf = root.execRootSh(
  "find ${shQuote(tmpDir)} -maxdepth 10 -name zdt_backup_manifest.json -type f -print -quit 2>/dev/null || true"
)
val mf = rMf.out.joinToString("\n").trim()
if (mf.isNotBlank()) {
  root.execRootSh("rm -f ${shQuote(mf)} 2>/dev/null || true")
}

      val dirs = listSubdirs(tmpDir)
      if (dirs.isEmpty()) {
        root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true")
        finishBackupProgress(error = str(R.string.mv_auto_045))
        return@launchIO
      }

      val sizes = mutableMapOf<String, Long>()
      var total = 0L
      for (d in dirs) {
        val sz = duKb(d)
        sizes[d] = sz
        total += sz
      }
      if (total <= 0L) total = dirs.size.toLong().coerceAtLeast(1L)

      var done = 0L
      for ((i, d) in dirs.withIndex()) {
        currentCoroutineContext().ensureActive()
        val folderName = d.substringAfterLast('/').ifBlank { "folder" }

        val pct = 20 + ((done * 75L) / total).toInt().coerceIn(0, 75)
        _backup.update { st -> st.copy(progressText = str(R.string.mv_copying_name, folderName), progressPercent = pct) }

        val rCopy = root.execRootSh("cp -a ${shQuote(d)} ${shQuote(workingFolderPath)}/ 2>/dev/null || cp -r ${shQuote(d)} ${shQuote(workingFolderPath)}/")
        if (!rCopy.isSuccess) {
          val err = (rCopy.out + rCopy.err).joinToString("\n").trim()
          root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true")
          val detail = if (err.isBlank()) "cp failed" else err
          finishBackupProgress(error = str(R.string.mv_copy_error_with_detail, folderName, detail))
          return@launchIO
        }

        val w = sizes[d] ?: 0L
        done += if (w > 0L) w else 1L
        val pct2 = 20 + ((done * 75L) / total).toInt().coerceIn(0, 75)
        _backup.update { st -> st.copy(progressText = str(R.string.mv_copied_count, i + 1, dirs.size), progressPercent = pct2) }
      }

      root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true")

      // After status becomes OFF we must allow a short cool-down window before sending start.
      // Restore work can happen inside this window.
      val elapsedSinceStopped = System.currentTimeMillis() - (stoppedAt ?: System.currentTimeMillis())
      val coolDownMs = 5_000L
      if (elapsedSinceStopped < coolDownMs) {
        val remain = coolDownMs - elapsedSinceStopped
        _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_046), progressPercent = 96) }
        delay(remain)
      }

      _backup.update { st -> st.copy(progressText = str(R.string.mv_auto_047), progressPercent = 97) }
      // Per requirement: send start only once (no retries/spam).
      val started = runCatching { api.startService() }.getOrDefault(false)
      if (!started) {
        finishBackupProgress(error = str(R.string.mv_auto_048))
        return@launchIO
      }

      finishBackupProgress(text = str(R.string.mv_auto_049), percent = 100)
    }
  }

  override fun deleteBackup(name: String) {
    if (_rootState.value != RootState.GRANTED) return
    launchIO {
      val path = "${backupDirPath}/${name}"
      val r = root.execRootSh("rm -f ${shQuote(path)} 2>/dev/null || true")
      if (!r.isSuccess) toast(str(R.string.mv_auto_050))
      refreshBackups()
    }
  }

  override fun shareBackup(name: String) {
    if (_rootState.value != RootState.GRANTED) return
    launchIO {
      val src = "${backupDirPath}/${name}"
      if (!rootPathExists(src)) {
        toast(str(R.string.mv_auto_051))
        return@launchIO
      }
      val outFile = File(ctx.cacheDir, "zdtb_share_${System.currentTimeMillis()}.zdtb")
      val r = root.execRootSh("cp -f ${shQuote(src)} ${shQuote(outFile.absolutePath)} 2>/dev/null || cat ${shQuote(src)} > ${shQuote(outFile.absolutePath)}; chmod 0644 ${shQuote(outFile.absolutePath)} 2>/dev/null || true")
      if (!r.isSuccess) {
        toast(str(R.string.mv_auto_052))
        return@launchIO
      }
      _backupEvents.tryEmit(BackupEvent.ShareFile(outFile.absolutePath, "application/octet-stream"))
    }
  }

  override fun closeBackupProgress() {
    _backup.update { st ->
      st.copy(
        progressVisible = false,
        progressTitle = "",
        progressText = "",
        progressPercent = 0,
        progressFinished = false,
        progressError = null,
        forceRestoreAvailable = false,
        forceRestoreName = null,
      )
    }
  }


  // ----- Program updates (zapret / zapret2) -----

  override fun resetProgramUpdatesUi() {
    _programUpdates.update { st ->
      st.copy(
        stoppingService = false,
        zapret = st.zapret.copy(
          checking = false,
          updating = false,
          progressPercent = 0,
          statusText = "",
          errorText = null,
          warningText = null,
          selectedVersion = null,
          selectedDownloadUrl = null,
          releases = emptyList(),
          releasesLoading = false,
          releasesError = null,
        ),
        zapret2 = st.zapret2.copy(
          checking = false,
          updating = false,
          progressPercent = 0,
          statusText = "",
          errorText = null,
          warningText = null,
          selectedVersion = null,
          selectedDownloadUrl = null,
          releases = emptyList(),
          releasesLoading = false,
          releasesError = null,
        ),
      )
    }
  }

  override fun stopServiceForProgramUpdatesAndCheck() {
    if (_rootState.value != RootState.GRANTED) return
    if (_programUpdates.value.stoppingService) return
    launchIO {
      _programUpdates.update { it.copy(stoppingService = true) }
      try {
        // Send stop only once.
        runCatching { api.stopService() }.getOrDefault(false)
        // Poll status until OFF (or timeout).
        val deadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < deadline) {
          runCatching { fetchAndUpdateStatus() }
          if (!ApiModels.isServiceOn(_uiState.value.status)) break
          delay(800)
        }
        if (ApiModels.isServiceOn(_uiState.value.status)) {
          toast(str(R.string.mv_auto_053))
          _programUpdates.update { st ->
            st.copy(
              zapret = st.zapret.copy(errorText = str(R.string.program_updates_err_service_running)),
              zapret2 = st.zapret2.copy(errorText = str(R.string.program_updates_err_service_running)),
            )
          }
          return@launchIO
        }
        // Auto-check both after OFF.
        checkZapretInternal()
        checkZapret2Internal()
      } finally {
        _programUpdates.update { it.copy(stoppingService = false) }
      }
    }
  }

  override fun loadZapretReleases() {
    if (_rootState.value != RootState.GRANTED) return
    launchIO { loadReleasesInternal(which = "zapret") }
  }

  override fun loadZapret2Releases() {
    if (_rootState.value != RootState.GRANTED) return
    launchIO { loadReleasesInternal(which = "zapret2") }
  }

  override fun selectZapretRelease(version: String?, downloadUrl: String?) {
    _programUpdates.update { st ->
      val installed = st.zapret.installedVersion
      val latest = st.zapret.latestVersion
      val target = version ?: latest
      val updAvail = if (!installed.isNullOrBlank() && !target.isNullOrBlank()) compareVersions(installed, target) != 0 else false
      val warn = if (!target.isNullOrBlank()) buildDowngradeWarning(program = "zapret", targetVersion = target) else null
      st.copy(
        zapret = st.zapret.copy(
          selectedVersion = version,
          selectedDownloadUrl = downloadUrl,
          warningText = warn,
          updateAvailable = updAvail,
        )
      )
    }
  }

  override fun selectZapret2Release(version: String?, downloadUrl: String?) {
    _programUpdates.update { st ->
      val installed = st.zapret2.installedVersion
      val latest = st.zapret2.latestVersion
      val target = version ?: latest
      val updAvail = if (!installed.isNullOrBlank() && !target.isNullOrBlank()) compareVersions(installed, target) != 0 else false
      val warn = if (!target.isNullOrBlank()) buildDowngradeWarning(program = "zapret2", targetVersion = target) else null
      st.copy(
        zapret2 = st.zapret2.copy(
          selectedVersion = version,
          selectedDownloadUrl = downloadUrl,
          warningText = warn,
          updateAvailable = updAvail,
        )
      )
    }
  }

  override fun checkZapretNow() {
    if (_rootState.value != RootState.GRANTED) return
    launchIO { checkZapretInternal() }
  }

  override fun checkZapret2Now() {
    if (_rootState.value != RootState.GRANTED) return
    launchIO { checkZapret2Internal() }
  }

  override fun updateZapretNow() {
    if (_rootState.value != RootState.GRANTED) return
    launchIO { updateZapretInternal() }
  }

  override fun updateZapret2Now() {
    if (_rootState.value != RootState.GRANTED) return
    launchIO { updateZapret2Internal() }
  }

  private fun requireServiceStoppedForUpdates(): Boolean {
    val on = ApiModels.isServiceOn(_uiState.value.status)
    if (on) {
      toast(str(R.string.mv_auto_054))
    }
    return !on
  }

  private suspend fun checkZapretInternal() {
    if (!requireServiceStoppedForUpdates()) return
    if (!isNetworkAvailable()) {
      toast(str(R.string.mv_auto_002))
      return
    }

    _programUpdates.update { st ->
      st.copy(zapret = st.zapret.copy(checking = true, errorText = null, statusText = str(R.string.mv_auto_055), progressPercent = 0))
    }

    val installed = runCatching {
      readInstalledVersionAny(
        listOf(
          "/data/adb/modules/ZDT-D/bin/nfqws",
          "/data/adb/modules_update/ZDT-D/bin/nfqws",
        )
      )
    }.getOrNull()
    if (installed.isNullOrBlank()) {
      _programUpdates.update { st ->
        st.copy(zapret = st.zapret.copy(checking = false, installedVersion = null, errorText = str(R.string.program_updates_err_detect_installed), statusText = ""))
      }
      return
    }

    val latest = fetchLatestZapretAsset()
    if (latest == null) {
      _programUpdates.update { st ->
        st.copy(zapret = st.zapret.copy(checking = false, installedVersion = installed, errorText = str(R.string.program_updates_err_check_latest), statusText = ""))
      }
      return
    }

    val (latestVer, latestUrl) = latest
    val targetVer = _programUpdates.value.zapret.selectedVersion ?: latestVer
    val updAvail = compareVersions(installed, targetVer) != 0
    val warn = buildDowngradeWarning(program = "zapret", targetVersion = targetVer)

    _programUpdates.update { st ->
      st.copy(
        zapret = st.zapret.copy(
          checking = false,
          installedVersion = installed,
          latestVersion = latestVer,
          latestDownloadUrl = latestUrl,
          warningText = warn,
          updateAvailable = updAvail,
          statusText = if (updAvail) str(R.string.prog_update_status_ready) else str(R.string.prog_update_status_already_installed),
          errorText = null,
        )
      )
    }
  }

  private suspend fun checkZapret2Internal() {
    if (!requireServiceStoppedForUpdates()) return
    if (!isNetworkAvailable()) {
      toast(str(R.string.mv_auto_002))
      return
    }

    _programUpdates.update { st ->
      st.copy(zapret2 = st.zapret2.copy(checking = true, errorText = null, statusText = str(R.string.mv_auto_055), progressPercent = 0))
    }

    val installed = runCatching {
      readInstalledVersionAny(
        listOf(
          "/data/adb/modules/ZDT-D/bin/nfqws2",
          "/data/adb/modules_update/ZDT-D/bin/nfqws2",
        )
      )
    }.getOrNull()
    if (installed.isNullOrBlank()) {
      _programUpdates.update { st ->
        st.copy(zapret2 = st.zapret2.copy(checking = false, installedVersion = null, errorText = str(R.string.program_updates_err_detect_installed), statusText = ""))
      }
      return
    }

    val latest = fetchLatestZapret2Asset()
    if (latest == null) {
      _programUpdates.update { st ->
        st.copy(zapret2 = st.zapret2.copy(checking = false, installedVersion = installed, errorText = str(R.string.program_updates_err_check_latest), statusText = ""))
      }
      return
    }

    val (latestVer, latestUrl) = latest
    val targetVer = _programUpdates.value.zapret2.selectedVersion ?: latestVer
    val updAvail = compareVersions(installed, targetVer) != 0
    val warn = buildDowngradeWarning(program = "zapret2", targetVersion = targetVer)

    _programUpdates.update { st ->
      st.copy(
        zapret2 = st.zapret2.copy(
          checking = false,
          installedVersion = installed,
          latestVersion = latestVer,
          latestDownloadUrl = latestUrl,
          warningText = warn,
          updateAvailable = updAvail,
          statusText = if (updAvail) str(R.string.prog_update_status_ready) else str(R.string.prog_update_status_already_installed),
          errorText = null,
        )
      )
    }
  }

  private suspend fun updateZapretInternal() {
    if (!requireServiceStoppedForUpdates()) return
    // Ensure we have target info (latest or selected).
    val stBefore = _programUpdates.value.zapret
    if (stBefore.selectedVersion.isNullOrBlank() && (stBefore.latestVersion.isNullOrBlank() || stBefore.latestDownloadUrl.isNullOrBlank())) {
      checkZapretInternal()
    }
    val st0 = _programUpdates.value.zapret
    val url = st0.selectedDownloadUrl ?: st0.latestDownloadUrl
    val targetVer = st0.selectedVersion ?: st0.latestVersion
    if (url.isNullOrBlank() || targetVer.isNullOrBlank()) return

    _programUpdates.update { st ->
      st.copy(zapret = st.zapret.copy(updating = true, progressPercent = 0, errorText = null, statusText = str(R.string.mv_auto_056)))
    }

    val zipFile = File(ctx.cacheDir, "zapret_target.zip")
    val extracted = File(ctx.cacheDir, "zapret_nfqws_${System.currentTimeMillis()}")
    runCatching { zipFile.delete() }
    runCatching { extracted.delete() }

    val okDl = downloadToFileWithProgress(url, zipFile) { pct ->
      _programUpdates.update { st ->
        val cur = st.zapret
        if (cur.progressPercent == pct) st else st.copy(zapret = cur.copy(progressPercent = pct, statusText = str(R.string.prog_update_status_downloading_pct_fmt, pct)))
      }
    }
    if (!okDl) {
      _programUpdates.update { st -> st.copy(zapret = st.zapret.copy(updating = false, errorText = str(R.string.prog_update_error_download_failed), statusText = "")) }
      return
    }

    _programUpdates.update { st -> st.copy(zapret = st.zapret.copy(statusText = str(R.string.mv_auto_057))) }
    val okExtract = extractZipSingle(zipFile, { name -> name.endsWith("/binaries/android-arm64/nfqws") }, extracted)
    if (!okExtract) {
      _programUpdates.update { st -> st.copy(zapret = st.zapret.copy(updating = false, errorText = str(R.string.prog_update_error_archive_changed), statusText = "")) }
      runCatching { zipFile.delete() }
      return
    }

    _programUpdates.update { st -> st.copy(zapret = st.zapret.copy(statusText = str(R.string.mv_auto_058), progressPercent = 100)) }
    val okInstall = installZapretBinary(extracted)
    runCatching { zipFile.delete() }
    runCatching { extracted.delete() }

    if (!okInstall) {
      _programUpdates.update { st -> st.copy(zapret = st.zapret.copy(updating = false, errorText = str(R.string.prog_update_error_install_failed), statusText = "")) }
      return
    }

    val installed = runCatching {
      readInstalledVersionAny(
        listOf(
          "/data/adb/modules/ZDT-D/bin/nfqws",
          "/data/adb/modules_update/ZDT-D/bin/nfqws",
        )
      )
    }.getOrNull()
    val updAvail = if (!installed.isNullOrBlank()) compareVersions(installed, targetVer) != 0 else false
    val warn = if (!targetVer.isNullOrBlank()) buildDowngradeWarning(program = "zapret", targetVersion = targetVer) else null
    _programUpdates.update { st ->
      st.copy(
        zapret = st.zapret.copy(
          updating = false,
          installedVersion = installed ?: st.zapret.installedVersion,
          updateAvailable = updAvail,
          warningText = warn,
          statusText = str(R.string.prog_update_status_installed),
          errorText = null,
        )
      )
    }
  }

  private suspend fun updateZapret2Internal() {
    if (!requireServiceStoppedForUpdates()) return
    val stBefore = _programUpdates.value.zapret2
    if (stBefore.selectedVersion.isNullOrBlank() && (stBefore.latestVersion.isNullOrBlank() || stBefore.latestDownloadUrl.isNullOrBlank())) {
      checkZapret2Internal()
    }
    val st0 = _programUpdates.value.zapret2
    val url = st0.selectedDownloadUrl ?: st0.latestDownloadUrl
    val targetVer = st0.selectedVersion ?: st0.latestVersion
    if (url.isNullOrBlank() || targetVer.isNullOrBlank()) return

    _programUpdates.update { st ->
      st.copy(zapret2 = st.zapret2.copy(updating = true, progressPercent = 0, errorText = null, statusText = str(R.string.mv_auto_056)))
    }

    val zipFile = File(ctx.cacheDir, "zapret2_target.zip")
    val extractDir = File(ctx.cacheDir, "zapret2_extract_${System.currentTimeMillis()}")
    runCatching { zipFile.delete() }
    runCatching { extractDir.deleteRecursively() }
    extractDir.mkdirs()

    val okDl = downloadToFileWithProgress(url, zipFile) { pct ->
      _programUpdates.update { st ->
        val cur = st.zapret2
        if (cur.progressPercent == pct) st else st.copy(zapret2 = cur.copy(progressPercent = pct, statusText = str(R.string.prog_update_status_downloading_pct_fmt, pct)))
      }
    }
    if (!okDl) {
      _programUpdates.update { st -> st.copy(zapret2 = st.zapret2.copy(updating = false, errorText = str(R.string.prog_update_error_download_failed), statusText = "")) }
      return
    }

    _programUpdates.update { st -> st.copy(zapret2 = st.zapret2.copy(statusText = str(R.string.mv_auto_057))) }
    val binOut = File(extractDir, "nfqws2")
    val luaOut = File(extractDir, "lua")
    val okExtractBin = extractZipSingle(zipFile, { name -> name.endsWith("/binaries/android-arm64/nfqws2") }, binOut)
    val okExtractLua = extractZipTree(zipFile, subDirSuffix = "/lua/", outDir = luaOut)
    if (!okExtractBin || !okExtractLua) {
      _programUpdates.update { st -> st.copy(zapret2 = st.zapret2.copy(updating = false, errorText = str(R.string.prog_update_error_archive_changed), statusText = "")) }
      runCatching { zipFile.delete() }
      runCatching { extractDir.deleteRecursively() }
      return
    }

    _programUpdates.update { st -> st.copy(zapret2 = st.zapret2.copy(statusText = str(R.string.mv_auto_058), progressPercent = 100)) }
    val okInstall = installZapret2(binOut, luaOut)
    runCatching { zipFile.delete() }
    runCatching { extractDir.deleteRecursively() }
    if (!okInstall) {
      _programUpdates.update { st -> st.copy(zapret2 = st.zapret2.copy(updating = false, errorText = str(R.string.prog_update_error_install_failed), statusText = "")) }
      return
    }

    val installed = runCatching {
      readInstalledVersionAny(
        listOf(
          "/data/adb/modules/ZDT-D/bin/nfqws2",
          "/data/adb/modules_update/ZDT-D/bin/nfqws2",
        )
      )
    }.getOrNull()
    val updAvail = if (!installed.isNullOrBlank()) compareVersions(installed, targetVer) != 0 else false
    val warn = if (!targetVer.isNullOrBlank()) buildDowngradeWarning(program = "zapret2", targetVersion = targetVer) else null
    _programUpdates.update { st ->
      st.copy(
        zapret2 = st.zapret2.copy(
          updating = false,
          installedVersion = installed ?: st.zapret2.installedVersion,
          updateAvailable = updAvail,
          warningText = warn,
          statusText = str(R.string.prog_update_status_installed),
          errorText = null,
        )
      )
    }
  }

  private suspend fun loadReleasesInternal(which: String) {
    if (!isNetworkAvailable()) {
      toast(str(R.string.mv_auto_002))
      _programUpdates.update { st ->
        when (which) {
          "zapret" -> st.copy(zapret = st.zapret.copy(releasesLoading = false, releasesError = str(R.string.program_updates_err_no_internet)))
          "zapret2" -> st.copy(zapret2 = st.zapret2.copy(releasesLoading = false, releasesError = str(R.string.program_updates_err_no_internet)))
          else -> st
        }
      }
      return
    }

    _programUpdates.update { st ->
      when (which) {
        "zapret" -> st.copy(zapret = st.zapret.copy(releasesLoading = true, releasesError = null))
        "zapret2" -> st.copy(zapret2 = st.zapret2.copy(releasesLoading = true, releasesError = null))
        else -> st
      }
    }

    val (repo, prefix) = when (which) {
      "zapret" -> Pair("bol-van/zapret", "zapret-v")
      "zapret2" -> Pair("bol-van/zapret2", "zapret2-v")
      else -> return
    }

    val releases = runCatching { fetchAllReleaseAssets(repo = repo, assetPrefix = prefix) }.getOrNull()
    if (releases == null || releases.isEmpty()) {
      _programUpdates.update { st ->
        when (which) {
          "zapret" -> st.copy(zapret = st.zapret.copy(releasesLoading = false, releasesError = str(R.string.program_updates_err_load_releases)))
          "zapret2" -> st.copy(zapret2 = st.zapret2.copy(releasesLoading = false, releasesError = str(R.string.program_updates_err_load_releases)))
          else -> st
        }
      }
      return
    }

    _programUpdates.update { st ->
      when (which) {
        "zapret" -> st.copy(zapret = st.zapret.copy(releasesLoading = false, releasesError = null, releases = releases))
        "zapret2" -> st.copy(zapret2 = st.zapret2.copy(releasesLoading = false, releasesError = null, releases = releases))
        else -> st
      }
    }
  }

  /**
   * Reads installed version by executing `<bin> -v` via root.
   * Some ROMs/SELinux setups may block executing a binary directly from the root context,
   * so we also try running it under the `shell` user (uid 2000) as a fallback.
   */
  private suspend fun readInstalledVersionAny(binaryPaths: List<String>): String? {
    if (binaryPaths.isEmpty()) return null

    // Strict format used by upstream builds.
    val strict = Regex(
      "github\\s+android\\s+version\\s+(v[0-9]+(?:\\.[0-9]+){0,3})",
      RegexOption.IGNORE_CASE
    )
    // Fallback: any vX[.Y[.Z[.W]]] token.
    val loose = Regex("\\bv[0-9]+(?:\\.[0-9]+){0,3}\\b", RegexOption.IGNORE_CASE)

    fun parseVersion(text: String): String? {
      val t = text.trim()
      strict.find(t)?.let { return it.groupValues.getOrNull(1) }
      loose.find(t)?.let { return it.value.lowercase() }
      return null
    }

    for (p in binaryPaths) {
      // 1) Quick existence check.
      val rExist = root.execRootSh("test -f ${shQuote(p)}")
      if (!rExist.isSuccess) continue

      // 2) Ensure executable bit (best-effort). Some users may have wrong permissions.
      root.execRootSh("chmod 0755 ${shQuote(p)} 2>/dev/null || true")

      // 3) Try to run directly as root.
      val r1 = root.execRootSh("${shQuote(p)} -v 2>&1 || true")
      val out1 = (r1.out + r1.err).joinToString("\n").trim()
      parseVersion(out1)?.let { return it }

      // 4) Fallback: run under shell user (uid 2000). Helps on some SELinux policies.
      val r2 = root.execRootSh("su -lp 2000 -c ${shQuote("${p} -v 2>&1")} || true")
      val out2 = (r2.out + r2.err).joinToString("\n").trim()
      parseVersion(out2)?.let { return it }
    }

    return null
  }

  private suspend fun fetchLatestZapretAsset(): Pair<String, String>? {
    return fetchLatestAsset(repo = "bol-van/zapret", assetPrefix = "zapret-v")
  }

  private suspend fun fetchLatestZapret2Asset(): Pair<String, String>? {
    return fetchLatestAsset(repo = "bol-van/zapret2", assetPrefix = "zapret2-v")
  }

  private suspend fun fetchLatestAsset(repo: String, assetPrefix: String): Pair<String, String>? {
    val url = "https://api.github.com/repos/${repo}/releases/latest"
    val req = okhttp3.Request.Builder()
      .url(url)
      .header("User-Agent", "ZDT-D-Android")
      .build()
    githubHttp.newCall(req).execute().use { resp ->
      if (resp.code != 200) return null
      val body = resp.body?.string() ?: return null
      val js = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
      val assets = js.optJSONArray("assets") ?: return null
      for (i in 0 until assets.length()) {
        val a = assets.optJSONObject(i) ?: continue
        val name = a.optString("name")
        if (name.startsWith(assetPrefix) && name.endsWith(".zip")) {
          val dl = a.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
          val ver = name.removePrefix(assetPrefix).removeSuffix(".zip")
          val v = if (ver.startsWith("v")) ver else "v${ver}"
          return Pair(v, dl)
        }
      }
      return null
    }
  }

  /**
   * Fetches ALL releases pages and returns a list of versions that have the expected zip asset.
   * GitHub API is paginated; we request 100 per page and keep going until an empty page.
   */
  private suspend fun fetchAllReleaseAssets(repo: String, assetPrefix: String): List<ProgramReleaseUi> {
    val out = LinkedHashMap<String, ProgramReleaseUi>() // preserve order, unique by version
    var page = 1
    while (true) {
      val url = "https://api.github.com/repos/${repo}/releases?per_page=100&page=${page}"
      val req = okhttp3.Request.Builder()
        .url(url)
        .header("User-Agent", "ZDT-D-Android")
        .build()

      val body = githubHttp.newCall(req).execute().use { resp ->
        if (resp.code != 200) return out.values.toList()
        resp.body?.string() ?: return out.values.toList()
      }
      val arr = runCatching { org.json.JSONArray(body) }.getOrNull() ?: break
      if (arr.length() == 0) break

      for (i in 0 until arr.length()) {
        val rel = arr.optJSONObject(i) ?: continue
        val publishedAt = rel.optString("published_at")
        val assets = rel.optJSONArray("assets") ?: continue
        var foundName: String? = null
        var foundUrl: String? = null
        for (j in 0 until assets.length()) {
          val a = assets.optJSONObject(j) ?: continue
          val name = a.optString("name")
          if (name.startsWith(assetPrefix) && name.endsWith(".zip")) {
            val dl = a.optString("browser_download_url")
            if (dl.isNotBlank()) {
              foundName = name
              foundUrl = dl
              break
            }
          }
        }
        if (foundName != null && foundUrl != null) {
          val verRaw = foundName.removePrefix(assetPrefix).removeSuffix(".zip")
          val v = if (verRaw.startsWith("v") || verRaw.startsWith("V")) verRaw else "v${verRaw}"
          if (!out.containsKey(v)) {
            out[v] = ProgramReleaseUi(version = v, downloadUrl = foundUrl, publishedAt = publishedAt)
          }
        }
      }

      page += 1
    }
    return out.values.toList()
  }

  private fun parseVersionParts(v: String): List<Int>? {
    val s = v.trim().removePrefix("v").removePrefix("V")
    if (s.isBlank()) return null
    val parts = s.split('.')
    val nums = parts.mapNotNull { it.toIntOrNull() }
    if (nums.isEmpty() || nums.size != parts.size) return null
    // Pad to 4: X.Y.Z.W (W is sub-version)
    return (nums + listOf(0, 0, 0, 0)).take(4)
  }

  /** Returns -1 if a<b, 0 if equal, +1 if a>b. */
  private fun compareVersions(a: String, b: String): Int {
    val pa = parseVersionParts(a) ?: return 0
    val pb = parseVersionParts(b) ?: return 0
    for (i in 0 until 4) {
      val da = pa[i]
      val db = pb[i]
      if (da != db) return if (da < db) -1 else 1
    }
    return 0
  }

  private fun buildDowngradeWarning(program: String, targetVersion: String): String? {
    val min = when (program) {
      "zapret" -> "v71.4"
      "zapret2" -> "v0.8.6"
      else -> return null
    }
    return if (compareVersions(targetVersion, min) < 0) {
      str(R.string.mv_version_below_min_warning, min)
    } else null
  }

  private fun downloadToFileWithProgress(url: String, outFile: File, onProgress: (Int) -> Unit): Boolean {
    val req = okhttp3.Request.Builder()
      .url(url)
      .header("User-Agent", "ZDT-D-Android")
      .build()
    githubHttp.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) return false
      val body = resp.body ?: return false
      val total = body.contentLength().takeIf { it > 0L } ?: -1L
      outFile.outputStream().use { fos ->
        body.byteStream().use { ins ->
          val buf = ByteArray(64 * 1024)
          var read: Int
          var done = 0L
          var lastPct = -1
          while (true) {
            read = ins.read(buf)
            if (read <= 0) break
            fos.write(buf, 0, read)
            done += read.toLong()
            if (total > 0) {
              val pct = ((done * 100L) / total).toInt().coerceIn(0, 100)
              if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
              }
            }
          }
        }
      }
      onProgress(100)
      return outFile.exists() && outFile.length() > 0L
    }
  }

  private fun extractZipSingle(zipFile: File, match: (String) -> Boolean, outFile: File): Boolean {
    java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
      while (true) {
        val e = zis.nextEntry ?: break
        val name = e.name
        if (!e.isDirectory && match(name)) {
          outFile.parentFile?.mkdirs()
          outFile.outputStream().use { os ->
            zis.copyTo(os)
          }
          return outFile.exists() && outFile.length() > 0L
        }
      }
    }
    return false
  }

  /** Extracts all files under any path ending with [subDirSuffix] into [outDir]. */
  private fun extractZipTree(zipFile: File, subDirSuffix: String, outDir: File): Boolean {
    var extractedAny = false
    java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
      while (true) {
        val e = zis.nextEntry ?: break
        val name = e.name
        val idx = name.indexOf(subDirSuffix)
        if (idx < 0) continue
        val rel = name.substring(idx + subDirSuffix.length)
        if (rel.isBlank()) continue
        // ZipSlip protection
        // Normalize Windows-style separators just in case.
        val cleanRel = rel.replace('\\', '/').trimStart('/')
        if (cleanRel.contains("../")) continue
        val dst = File(outDir, cleanRel)
        if (e.isDirectory) {
          dst.mkdirs()
          continue
        }
        dst.parentFile?.mkdirs()
        dst.outputStream().use { os ->
          zis.copyTo(os)
        }
        extractedAny = true
      }
    }
    return extractedAny
  }

  private suspend fun installZapretBinary(src: File): Boolean {
    val moduleRoot = "/data/adb/modules/ZDT-D"
    val dst = "${moduleRoot}/bin/nfqws"
    if (!rootPathExists(moduleRoot)) return false
    val script = """
      set -e
      mkdir -p ${shQuote(moduleRoot + "/bin")} 2>/dev/null || true
      cp -f ${shQuote(src.absolutePath)} ${shQuote(dst)} 2>/dev/null || cat ${shQuote(src.absolutePath)} > ${shQuote(dst)}
      chmod 0755 ${shQuote(dst)} 2>/dev/null || true
    """.trimIndent()
    val r = root.execRootSh(script)
    return r.isSuccess
  }

  private suspend fun installZapret2(binSrc: File, luaSrcDir: File): Boolean {
    val moduleRoot = "/data/adb/modules/ZDT-D"
    val dstBin = "${moduleRoot}/bin/nfqws2"
    val dstLua = "${moduleRoot}/strategic/lua"
    if (!rootPathExists(moduleRoot)) return false
    val script = """
      set -e
      mkdir -p ${shQuote(moduleRoot + "/bin")} 2>/dev/null || true
      mkdir -p ${shQuote(dstLua)} 2>/dev/null || true
      # replace lua contents to avoid stale files
      rm -rf ${shQuote(dstLua)}/* 2>/dev/null || true

      cp -f ${shQuote(binSrc.absolutePath)} ${shQuote(dstBin)} 2>/dev/null || cat ${shQuote(binSrc.absolutePath)} > ${shQuote(dstBin)}
      chmod 0755 ${shQuote(dstBin)} 2>/dev/null || true

      if test -d ${shQuote(luaSrcDir.absolutePath)}; then
        cp -r ${shQuote(luaSrcDir.absolutePath)}/* ${shQuote(dstLua + "/")} 2>/dev/null || true
      fi
      find ${shQuote(dstLua)} -type f -exec chmod 0755 {} \\; 2>/dev/null || true
      find ${shQuote(dstLua)} -type d -exec chmod 0755 {} \\; 2>/dev/null || true
    """.trimIndent()
    val r = root.execRootSh(script)
    return r.isSuccess
  }


  private fun startSettingsMigration() {
    if (_rootState.value != RootState.GRANTED) return
    if (_setup.value.migrationDialog == MigrationDialog.PROGRESS) return

    launchIO {
      // Always refresh availability before starting.
      refreshMigrationUiState()

      val updateId = computeStagedUpdateId()
      if (updateId.isNullOrBlank()) {
        _setup.update { st ->
          st.copy(
            migrationDialog = MigrationDialog.PROGRESS,
            migrationPercent = 0,
            migrationProgressText = "",
            migrationFinished = true,
            migrationError = str(R.string.mv_auto_059),
          )
        }
        return@launchIO
      }

      _setup.update { st ->
        st.copy(
          migrationDialog = MigrationDialog.PROGRESS,
          migrationPercent = 0,
          migrationProgressText = str(R.string.mv_auto_024),
          migrationFinished = false,
          migrationError = null,
        )
      }

      val ok = performSettingsMigration(updateId)
      if (ok) {
        // Keep dialog open; UI shows str(R.string.mv_auto_060).
        _setup.update { st ->
          st.copy(
            migrationFinished = true,
            migrationError = null,
            migrationProgressText = str(R.string.mv_auto_061),
            migrationPercent = 100,
          )
        }
      }
      // Refresh button state (done/disabled).
      refreshMigrationUiState()
    }
  }

  private suspend fun refreshMigrationUiState() {
    if (_rootState.value != RootState.GRANTED) {
      _setup.update { st ->
        st.copy(
          migrationAvailable = false,
          migrationDone = false,
          migrationButtonEnabled = false,
          migrationHintText = "",
          migrationIsMagisk = false,
          migrationDialog = if (st.migrationDialog == MigrationDialog.PROGRESS) st.migrationDialog else MigrationDialog.NONE,
          migrationPercent = if (st.migrationDialog == MigrationDialog.PROGRESS) st.migrationPercent else 0,
          migrationProgressText = if (st.migrationDialog == MigrationDialog.PROGRESS) st.migrationProgressText else "",
          migrationFinished = if (st.migrationDialog == MigrationDialog.PROGRESS) st.migrationFinished else false,
          migrationError = if (st.migrationDialog == MigrationDialog.PROGRESS) st.migrationError else null,
          moduleReinstallRequired = false,
          tamperReinstallPendingReboot = false,
        )
      }
      return
    }

    val updateId = computeStagedUpdateId()
    if (updateId.isNullOrBlank()) {
      // No staged update: hide the section and clear any remembered id.
      runCatching { root.clearMigrationDoneUpdateId() }
      _setup.update { st ->
        st.copy(
          migrationAvailable = false,
          migrationDone = false,
          migrationButtonEnabled = false,
          migrationHintText = "",
          migrationIsMagisk = false,
          tamperReinstallPendingReboot = runCatching { root.isTamperReinstallPendingReboot() }.getOrDefault(false),
        )
      }
      return
    }

    val stickyTamperPending = runCatching { root.isTamperReinstallPendingReboot() }.getOrDefault(false)

    if (_setup.value.moduleReinstallRequired || stickyTamperPending) {
      _setup.update { st ->
        st.copy(
          migrationAvailable = false,
          migrationDone = false,
          migrationButtonEnabled = false,
          migrationHintText = "",
          migrationIsMagisk = false,
          tamperReinstallPendingReboot = stickyTamperPending,
        )
      }
      return
    }

    val stored = runCatching { root.getMigrationDoneUpdateId() }.getOrNull()
    val done = stored != null && stored == updateId

    val installer = runCatching { root.detectModuleInstaller() }.getOrDefault(RootConfigManager.ModuleInstaller.UNKNOWN)
    val isMagisk = installer == RootConfigManager.ModuleInstaller.MAGISK

    val src = "/data/adb/modules/ZDT-D/working_folder"
    val dstRoot = "/data/adb/modules_update/ZDT-D"
    val dst = "$dstRoot/working_folder"

    var enabled = false
    var hint = ""

    if (done) {
      enabled = false
      hint = str(R.string.mv_auto_062)
    } else {
      val updateDirOk = rootPathExists(dstRoot)
      if (!updateDirOk) {
        enabled = false
        hint = str(R.string.mv_auto_063)
      } else if (!rootPathExists(src)) {
        enabled = false
        hint = str(R.string.mv_auto_064)
      } else {
        val dirs = listSubdirs(src)
        if (dirs.isEmpty()) {
          enabled = false
          hint = str(R.string.mv_auto_065)
        } else {
          enabled = true
          hint = ""
        }
      }
    }

    _setup.update { st ->
      st.copy(
        migrationAvailable = true,
        migrationDone = done,
        migrationButtonEnabled = enabled,
        migrationHintText = hint,
        migrationIsMagisk = isMagisk,
        tamperReinstallPendingReboot = stickyTamperPending,
      )
    }
  }

  private suspend fun computeStagedUpdateId(): String? {
    val id = "ZDT-D"
    val propPath = "/data/adb/modules_update/$id/module.prop"
    val propText = runCatching { root.readTextFile(propPath) }.getOrDefault("")
    val code = parseVersionCode(propText)
    if (code != null) return "vc:$code"

    // Fallback: short sha256 of module.prop (if available)
    val hash = runCatching {
      val script = "(sha256sum ${shQuote(propPath)} 2>/dev/null || /system/bin/toybox sha256sum ${shQuote(propPath)} 2>/dev/null || true) | head -n 1 | cut -d ' ' -f 1"
      val r = root.execRootSh(script)
      r.out.joinToString("\n").trim()
    }.getOrDefault("")
    if (hash.isNotBlank()) return "sha:${hash.take(16)}"

    // Fallback: marker file (generic pending update).
    val marker = "/data/adb/modules/$id/update"
    return if (rootPathExists(marker)) "pending_update" else null
  }

  private suspend fun rootPathExists(path: String): Boolean {
    val r = root.execRootSh("test -e ${shQuote(path)}")
    return r.isSuccess
  }

  private suspend fun listSubdirs(parent: String): List<String> {
    val script = "find ${shQuote(parent)} -mindepth 1 -maxdepth 1 -type d 2>/dev/null || true"
    val r = root.execRootSh(script)
    val out = (r.out + r.err).joinToString("\n")
    return out.lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .toList()
  }

  private suspend fun duKb(path: String): Long {
    val script = "set -- $(du -sk ${shQuote(path)} 2>/dev/null); echo ${'$'}{1:-0}"
    val r = root.execRootSh(script)
    val s = r.out.joinToString("\n").trim()
    return s.toLongOrNull() ?: 0L
  }

  private suspend fun performSettingsMigration(updateId: String): Boolean {
    val src = "/data/adb/modules/ZDT-D/working_folder"
    val dst = "/data/adb/modules_update/ZDT-D/working_folder"
    val dstRoot = "/data/adb/modules_update/ZDT-D"

    // Pre-checks (paths must exist).
    if (!rootPathExists(dstRoot)) {
      _setup.update { st ->
        st.copy(
          migrationError = str(R.string.mv_auto_066),
          migrationFinished = true,
        )
      }
      return false
    }
    if (!rootPathExists(src)) {
      _setup.update { st ->
        st.copy(
          migrationError = str(R.string.mv_auto_067),
          migrationFinished = true,
        )
      }
      return false
    }

    val dirs = listSubdirs(src)
    if (dirs.isEmpty()) {
      _setup.update { st ->
        st.copy(
          migrationError = str(R.string.mv_auto_065),
          migrationFinished = true,
        )
      }
      return false
    }

    // Compute weights for progress.
    val sizes = mutableMapOf<String, Long>()
    var total = 0L
    for (d in dirs) {
      val sz = duKb(d)
      sizes[d] = sz
      total += sz
    }
    if (total <= 0L) total = dirs.size.toLong().coerceAtLeast(1L)

    // Clean destination and copy.
    val cleanScript = "mkdir -p ${shQuote(dst)}; rm -rf ${shQuote(dst)}/* ${shQuote(dst)}/.[!.]* ${shQuote(dst)}/..?* 2>/dev/null || true"
    root.execRootSh(cleanScript)

    var done = 0L
    for ((i, d) in dirs.withIndex()) {
      currentCoroutineContext().ensureActive()
      val name = d.substringAfterLast('/').ifBlank { "folder" }
      _setup.update { st ->
        st.copy(
          migrationProgressText = str(R.string.mv_copying_name, name),
          migrationPercent = ((done * 100L) / total).toInt().coerceIn(0, 99),
        )
      }

      val copyScript = "cp -a ${shQuote(d)} ${shQuote(dst)}/ 2>/dev/null || cp -r ${shQuote(d)} ${shQuote(dst)}/"
      val r = root.execRootSh(copyScript)
      if (!r.isSuccess) {
        val err = (r.out + r.err).joinToString("\n").trim()
        _setup.update { st ->
          st.copy(
            migrationError = run {
              val detail = if (err.isBlank()) "cp failed" else err
              str(R.string.mv_copy_error_with_detail, name, detail)
            },
            migrationFinished = true,
          )
        }
        return false
      }

      val w = sizes[d] ?: 0L
      done += if (w > 0L) w else 1L
      val pct = ((done * 100L) / total).toInt().coerceIn(0, 100)
      _setup.update { st ->
        st.copy(
          migrationPercent = pct,
          migrationProgressText = str(R.string.mv_copied_count, i + 1, dirs.size),
        )
      }
    }

    // Persist "done" for this update.
    runCatching { root.setMigrationDoneUpdateId(updateId) }

    _setup.update { st ->
      st.copy(
        migrationDone = true,
        migrationButtonEnabled = false,
        migrationHintText = str(R.string.mv_auto_062),
      )
    }
    return true
  }


  override fun openModuleInstaller() {
    _setup.update { st ->
      st.copy(
        step = SetupStep.INSTALL,
        showUpdatePrompt = false,
        updatePromptMandatory = false,
        updatePromptTitle = "",
        updatePromptText = "",
        moduleReinstallRequired = false,
      )
    }
  }

  override fun dismissUpdatePrompt() {
    _setup.update { st ->
      st.copy(
        showUpdatePrompt = false,
        updatePromptMandatory = false,
        updatePromptTitle = "",
        updatePromptText = "",
      )
    }
  }

  private suspend fun installViaMagisk(): Pair<Boolean, String> {
    val (stagedOk, stageLog) = stageModuleZipToTmp()
    if (!stagedOk) return false to stageLog

    val r = root.execRoot("sh -c 'magisk --install-module /data/local/tmp/zdt_module.zip'")
    val out2 = (r.out + r.err).joinToString("\n")
    return r.isSuccess to (stageLog + "\n" + out2).trim()
  }

  private suspend fun installViaKsu(): Pair<Boolean, String> {
    val (stagedOk, stageLog) = stageModuleZipToTmp()
    if (!stagedOk) return false to stageLog

    val ksu = runCatching { root.ksuPath() }.getOrNull() ?: "ksud"
    val r = root.execRoot("sh -c ${shQuote("${ksu} module install /data/local/tmp/zdt_module.zip")}")
    val out2 = (r.out + r.err).joinToString("\n")
    return r.isSuccess to (stageLog + "\n" + out2).trim()
  }

  private suspend fun installViaApatch(): Pair<Boolean, String> {
    val (stagedOk, stageLog) = stageModuleZipToTmp()
    if (!stagedOk) return false to stageLog

    val apd = runCatching { root.apatchPath() }.getOrNull() ?: "apd"
    val r = root.execRoot("sh -c ${shQuote("${apd} module install /data/local/tmp/zdt_module.zip")}")
    val out2 = (r.out + r.err).joinToString("\n")
    return r.isSuccess to (stageLog + "\n" + out2).trim()
  }

  private suspend fun exportModuleZipToSdcard(): Triple<Boolean, String, String> {
    val cacheZip = File(ctx.cacheDir, "zdt_module.zip")
    runCatching {
      ctx.assets.open("zdt_module.zip").use { input ->
        cacheZip.outputStream().use { out -> input.copyTo(out) }
      }
    }.getOrElse {
      return Triple(false, "asset zdt_module.zip missing: ${it.message ?: it}", "")
    }

    val src = cacheZip.absolutePath
    val dst = "/sdcard/ZDT-D.zip"
    val copyRes = root.execRoot("sh -c 'cp ${shQuote(src)} ${shQuote(dst)} && chmod 644 ${shQuote(dst)}'")
    val out = (copyRes.out + copyRes.err).joinToString("\n").trim()
    if (!copyRes.isSuccess) return Triple(false, out, dst)

    val msg = buildString {
      append(str(R.string.mv_auto_068)).append(dst).append("\n\n")
      append(str(R.string.mv_auto_069))
    }
    return Triple(true, (out + "\n" + msg).trim(), dst)
  }

  private suspend fun stageModuleZipToTmp(): Pair<Boolean, String> {
    // Copy assets/zdt_module.zip to cache and then to /data/local/tmp
    val cacheZip = File(ctx.cacheDir, "zdt_module.zip")
    runCatching {
      ctx.assets.open("zdt_module.zip").use { input ->
        cacheZip.outputStream().use { out -> input.copyTo(out) }
      }
    }.getOrElse {
      return false to "asset zdt_module.zip missing: ${it.message ?: it}"
    }

    val src = cacheZip.absolutePath
    val copyRes = root.execRoot("sh -c 'cp ${shQuote(src)} /data/local/tmp/zdt_module.zip'")
    val out1 = (copyRes.out + copyRes.err).joinToString("\n")
    return copyRes.isSuccess to out1.trim()
  }

  private suspend fun installManually(): Pair<Boolean, String> {
    val unpackDir = File(ctx.cacheDir, "module_unpack")
    runCatching { unpackDir.deleteRecursively() }
    unpackDir.mkdirs()

    val extractLog = StringBuilder()
    val extractedOk = runCatching {
      extractAssetZip("zdt_module.zip", unpackDir, extractLog)
    }.getOrElse {
      return false to "extract failed: ${it.message ?: it}"
    }
    if (!extractedOk) return false to extractLog.toString()

    val src = unpackDir.absolutePath
    val id = "ZDT-D"

    // Delete existing module dirs (manual install replaces). If old version exists, user is warned in UI.
    val cmd = buildString {
      append("set -e; ")
      append("rm -rf /data/adb/modules_update/")
      append(id)
      append("; rm -rf /data/adb/modules/")
      append(id)
      append("; ")
      append("mkdir -p /data/adb/modules_update/")
      append(id)
      append("; ")
      append("cp -R ")
      append(shQuote(src))
      append("/. /data/adb/modules_update/")
      append(id)
      append("/; ")

      // Permissions: dirs 755, files 644.
      append("find /data/adb/modules_update/")
      append(id)
      append(" -type d -exec chmod 755 {} +; ")
      append("find /data/adb/modules_update/")
      append(id)
      append(" -type f -exec chmod 644 {} +; ")

      // Executables.
      append("if [ -f /data/adb/modules_update/")
      append(id)
      append("/service.sh ]; then chmod 755 /data/adb/modules_update/")
      append(id)
      append("/service.sh; fi; ")

      append("for f in post-fs-data.sh uninstall.sh customize.sh; do ")
      append("if [ -f /data/adb/modules_update/")
      append(id)
      // NOTE: we want a shell variable ($f) here; escape Kotlin string templates.
      append("/${'$'}f ]; then chmod 755 /data/adb/modules_update/")
      append(id)
      append("/${'$'}f; fi; ")
      append("done; ")

      append("if [ -d /data/adb/modules_update/")
      append(id)
      append("/bin ]; then chmod 755 /data/adb/modules_update/")
      append(id)
      append("/bin; chmod 755 /data/adb/modules_update/")
      append(id)
      append("/bin/* 2>/dev/null || true; fi; ")

      // Create marker in /data/adb/modules/<id>: module.prop + update
      append("mkdir -p /data/adb/modules/")
      append(id)
      append("; ")
      append("cp /data/adb/modules_update/")
      append(id)
      append("/module.prop /data/adb/modules/")
      append(id)
      append("/module.prop; ")
      append("touch /data/adb/modules/")
      append(id)
      append("/update; ")
      append("rm -f /data/adb/modules/")
      append(id)
      append("/disable /data/adb/modules/")
      append(id)
      append("/remove; ")
    }

    val r = root.execRoot("sh -c ${shQuote(cmd)}")
    val out = (extractLog.toString() + "\n" + (r.out + r.err).joinToString("\n")).trim()
    return r.isSuccess to out
  }


  // ----- Backup helpers -----

  private fun showBackupProgress(title: String, text: String, percent: Int) {
    _backup.update { st ->
      st.copy(
        progressVisible = true,
        progressTitle = title,
        progressText = text,
        progressPercent = percent.coerceIn(0, 100),
        progressFinished = false,
        progressError = null,
        forceRestoreAvailable = false,
        forceRestoreName = null,
      )
    }
  }

  private fun finishBackupProgress(
    text: String? = null,
    percent: Int = 100,
    error: String? = null,
    forceRestoreName: String? = null,
    forceRestoreAvailable: Boolean = false,
  ) {
    _backup.update { st ->
      st.copy(
        progressVisible = true,
        progressText = text ?: st.progressText,
        progressPercent = percent.coerceIn(0, 100),
        progressFinished = true,
        progressError = error,
        forceRestoreAvailable = forceRestoreAvailable,
        forceRestoreName = forceRestoreName,
      )
    }
  }

  private fun parseBackupCreatedAtText(name: String): String {
    val m = Regex("(\\d{4}-\\d{2}-\\d{2})_(\\d{2}-\\d{2}-\\d{2})").find(name)
    if (m != null) {
      val date = m.groupValues.getOrNull(1).orEmpty()
      val time = m.groupValues.getOrNull(2).orEmpty().replace('-', ':')
      if (date.isNotBlank() && time.isNotBlank()) return "$date $time"
    }
    return ""
  }

  private fun buildBackupManifest(createdAt: String, dirsFull: List<String>): String {
    val folders = JSONArray()
    dirsFull
      .map { it.substringAfterLast('/').trim() }
      .filter { it.isNotBlank() }
      .distinct()
      .sorted()
      .forEach { folders.put(it) }

    val obj = JSONObject()
      .put("magic", "ZDTD_BACKUP_V1")
      .put("format", 1)
      .put("module_id", "ZDT-D")
      .put("created_at", createdAt)
      .put("app_version", BuildConfig.VERSION_NAME)
      .put("app_version_code", readInstalledModuleVersionCode() ?: readBundledModuleVersionCode() ?: BuildConfig.VERSION_CODE)
      .put("folders", folders)

    return obj.toString(2)
  }

  private data class BackupValidation(val ok: Boolean, val error: String? = null, val versionMismatch: Boolean = false)

  private suspend fun validateBackupFile(path: String, ignoreVersionCode: Boolean = false): BackupValidation {
    // Quick list to detect bad paths (zip-slip style) and to ensure tar is readable.
    val rList = root.execRootSh("tar -tzf ${shQuote(path)} 2>/dev/null || true")
    val entries = rList.out
      .joinToString("\n")
      .lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .take(5000)
      .toList()

    if (entries.isEmpty()) {
      return BackupValidation(false, str(R.string.mv_auto_070))
    }

    val bad = entries.firstOrNull { e ->
      e.startsWith("/") || e.startsWith("\\") ||
        e.contains("../") || e.contains("..\\") ||
        e.contains("/..") || e.contains("\\..")
    }
    if (bad != null) {
      return BackupValidation(false, str(R.string.mv_backup_suspicious_path, bad))
    }

    // Manifest path can vary across tar writers (for example: "././zdt_backup_manifest.json").
    // Toybox tar (especially older builds) may fail when we request a different path than the one stored.
    val manifestInTar = entries.firstOrNull { it.endsWith("zdt_backup_manifest.json") }
    if (manifestInTar == null) {
      return BackupValidation(false, str(R.string.mv_auto_071))
    }

    fun normalizeTarPath(p: String): String {
      var s = p.trim()
      while (s.startsWith("./")) s = s.removePrefix("./")
      while (s.startsWith("/")) s = s.removePrefix("/")
      return s
    }

    val manifestNorm = normalizeTarPath(manifestInTar)
    val candidates = listOf(
      manifestInTar,
      manifestNorm,
      "./$manifestNorm",
      "zdt_backup_manifest.json",
      "./zdt_backup_manifest.json"
    ).distinct()

    // Read manifest. Prefer "tar -xO" (to stdout) to avoid file permission quirks.
    val rStdout = root.execRootSh(
      "(" + candidates.joinToString(" || ") { cand ->
        "tar -xOzf ${shQuote(path)} ${shQuote(cand)} 2>/dev/null"
      } + " || true)"
    )
    var manifestText = rStdout.out.joinToString("\n").trim()

    if (manifestText.isBlank()) {
      // Fallback: extract to temp dir and verify file existence+size.
      // Avoid using tar -C (it is flaky on some older Toybox builds): use "cd tmp && tar -xzf".
      val tmpDir = "/data/local/tmp/zdtb_chk_${System.currentTimeMillis()}"
      root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true; mkdir -p ${shQuote(tmpDir)}")

      for (cand in candidates) {
        root.execRootSh("cd ${shQuote(tmpDir)} && tar -xzf ${shQuote(path)} ${shQuote(cand)} 2>/dev/null || true")
      }

      // Find the manifest robustly (in case tar created nested ./ paths).
      val rFind = root.execRootSh(
        "find ${shQuote(tmpDir)} -maxdepth 10 -name zdt_backup_manifest.json -type f -print -quit 2>/dev/null || true"
      )
      val found = rFind.out.joinToString("\n").trim()
      if (found.isBlank()) {
        root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true")
        return BackupValidation(false, str(R.string.mv_auto_071))
      }

      // Ensure it is non-empty before reading.
      val rSizeOk = root.execRootSh("test -s ${shQuote(found)}")
      if (!rSizeOk.isSuccess) {
        root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true")
        return BackupValidation(false, str(R.string.mv_auto_072))
      }

      val rCat = root.execRootSh("cat ${shQuote(found)} 2>/dev/null || true")
      manifestText = rCat.out.joinToString("\n").trim()
      root.execRootSh("rm -rf ${shQuote(tmpDir)} 2>/dev/null || true")
    }

    if (manifestText.isBlank()) return BackupValidation(false, str(R.string.mv_auto_072))
    val magic = runCatching { JSONObject(manifestText).optString("magic", "") }.getOrDefault("")
    if (magic != "ZDTD_BACKUP_V1") {
      return BackupValidation(false, str(R.string.mv_auto_073))
    }

    val manifestObj = runCatching { JSONObject(manifestText) }.getOrNull()
      ?: return BackupValidation(false, str(R.string.mv_auto_073))
    val backupVersionCode = when {
      manifestObj.has("app_version_code") -> manifestObj.optInt("app_version_code", -1)
      else -> -1
    }
    // Current module/app versionCode used for backup compatibility checks.
    // Use installed module versionCode if available, otherwise fall back to bundled/build version.
    val currentVersionCode = readInstalledModuleVersionCode() ?: readBundledModuleVersionCode() ?: BuildConfig.VERSION_CODE
    val minSupportedBackupVersionCode = 25000

    // Block restore when the currently installed module/app is below the minimum supported architecture.
    // Requirement: versions below 25000 are not supported for restore in either direction.
    if (currentVersionCode in 1 until minSupportedBackupVersionCode) {
      return BackupValidation(
        ok = false,
        error = str(
          R.string.mv_restore_min_supported_current_version,
          currentVersionCode.toString(),
          minSupportedBackupVersionCode.toString()
        ),
        versionMismatch = false,
      )
    }
    if (backupVersionCode in 1 until minSupportedBackupVersionCode) {
      return BackupValidation(
        ok = false,
        error = str(
          R.string.mv_backup_min_supported_version,
          backupVersionCode.toString(),
          minSupportedBackupVersionCode.toString()
        ),
        versionMismatch = false,
      )
    }
    // Version mismatch: allow "Restore anyway" (ignoreVersionCode=true) for any mismatch direction
    // as long as both versions are within supported architecture range.
    if (!ignoreVersionCode && (backupVersionCode <= 0 || backupVersionCode != currentVersionCode)) {
      return BackupValidation(
        ok = false,
        error = str(
          R.string.mv_backup_version_mismatch,
          if (backupVersionCode > 0) backupVersionCode.toString() else "?",
          currentVersionCode.toString()
        ),
        versionMismatch = true,
      )
    }

    return BackupValidation(true, null)
  }

private fun shQuote(s: String): String {
    return "'" + s.replace("'", "'\\''") + "'"
  }

  private fun extractAssetZip(assetName: String, destDir: File, log: StringBuilder): Boolean {
    val base = destDir.canonicalFile
    ctx.assets.open(assetName).use { input ->
      java.util.zip.ZipInputStream(input).use { zis ->
        while (true) {
          val e = zis.nextEntry ?: break
          val name = e.name
          if (name.startsWith("META-INF/")) continue
          if (name.isBlank()) continue

          val outFile = File(destDir, name)
          val canon = outFile.canonicalFile
          if (!canon.path.startsWith(base.path)) {
            log.append("skip suspicious entry: ").append(name).append("\n")
            continue
          }

          if (e.isDirectory) {
            canon.mkdirs()
          } else {
            canon.parentFile?.mkdirs()
            canon.outputStream().use { os ->
              zis.copyTo(os)
            }
          }
        }
      }
    }

    // Basic sanity check.
    val prop = File(destDir, "module.prop")
    if (!prop.exists()) {
      log.append("module.prop missing in asset zip\n")
      return false
    }
    log.append("extracted to: ").append(destDir.absolutePath).append("\n")
    return true
  }

  private fun startStatusPolling() {
    statusJob?.cancel()
    statusJob = launchIO {
      while (isActive) {
        try {
          fetchAndUpdateStatus()
        } catch (e: Throwable) {
          _uiState.update { st -> if (!st.daemonOnline) st else st.copy(daemonOnline = false) }
          log("ERR", "status poll failed: ${e.message ?: e}")
        }
        delay(2200)
      }
    }
  }

  private fun startDaemonLogPolling() {
    daemonLogJob?.cancel()
    daemonLogJob = launchIO {
      while (isActive) {
        try {
          refreshDaemonLogOnce()
        } catch (e: Throwable) {
          log("ERR", "daemon log poll failed: ${e.message ?: e}")
        }
        delay(1500)
      }
    }
  }

  override fun clearLogs() {
    _logs.update { emptyList() }
    log("OK", str(R.string.log_logs_cleared))
  }

  private fun log(level: String, msg: String) {
    val ts = ApiModels.fmtTs()
    _logs.update { (it + LogLine(ts, level, msg)).takeLast(250) }
  }

  override fun refreshDaemonLog() {
    launchIO { refreshDaemonLogOnce() }
  }

  private suspend fun refreshDaemonLogOnce() {
    // Root-only: /data/adb/... is not readable by the app.
    val path = "/data/adb/modules/ZDT-D/log/zdtd.log"
    val text = runCatching { root.readLogTail(path, 220) }.getOrDefault("")
    _uiState.update { st ->
      if (st.daemonLogTail == text) st else st.copy(daemonLogTail = text)
    }
  }

  override fun refreshStatus() {
    launchIO {
      try {
        fetchAndUpdateStatus()
      } catch (e: Throwable) {
        _uiState.update { st -> if (!st.daemonOnline) st else st.copy(daemonOnline = false) }
        log("ERR", "status failed: ${e.message ?: e}")
      }
    }
  }

  private suspend fun fetchAndUpdateStatus() {
    val rep = api.getStatus()
    _uiState.update { it.copy(status = rep, daemonOnline = true) }
    // Cache last-known state for the Quick Settings tile.
    root.setCachedServiceOn(ApiModels.isServiceOn(rep))
  }

  override fun toggleService() {
    if (_uiState.value.busy) return
    launchIO {
      _uiState.update { it.copy(busy = true) }
      try {
        val on = ApiModels.isServiceOn(_uiState.value.status)
        val ok = if (on) api.stopService() else api.startService()
        if (ok) root.setCachedServiceOn(!on)
        if (ok) {
          log("OK", str(if (on) R.string.log_service_stopped else R.string.log_service_started))
        }
        else log("ERR", if (on) "/api/stop failed" else "/api/start failed")
      } catch (e: Throwable) {
        log("ERR", "toggle failed: ${e.message ?: e}")
      } finally {
        _uiState.update { it.copy(busy = false) }
        refreshStatus()
      }
    }
  }

  override fun refreshPrograms() {
    launchIO {
      try {
        val list = api.getPrograms()
        // Some programs (dnscrypt / operaproxy) use active.json under working_folder for enable state.
        val patched = list.map { p ->
          val ap = activeJsonPath(p.id)
          if (ap != null) {
            val en = root.readEnabledFlag(ap)
            if (en != null) p.copy(enabled = en) else p
          } else {
            p
          }
        }
        _uiState.update { it.copy(programs = patched) }
      } catch (e: Throwable) {
        log("ERR", "programs failed: ${e.message ?: e}")
      }
    }
  }

  override fun setProgramEnabled(programId: String, enabled: Boolean, onDone: (Boolean) -> Unit) {
    launchIO {
      val ap = activeJsonPath(programId)
      val ok = runCatching {
        if (ap != null) root.writeEnabledFlag(ap, enabled)
        else api.setProgramEnabled(programId, enabled)
      }.getOrDefault(false)
      if (ok) {
        log("OK", "$programId enabled=$enabled (apply after stop/start)")
        if (ap != null) {
          // File-backed toggle: update UI immediately even if daemon API is temporarily unavailable.
          _uiState.update { st ->
            st.copy(programs = st.programs.map { p -> if (p.id == programId) p.copy(enabled = enabled) else p })
          }
        } else {
          refreshPrograms()
        }
      } else {
        log("ERR", "$programId toggle failed")
      }
      withContext(Dispatchers.Main.immediate) { onDone(ok) }
    }
  }

  private fun activeJsonPath(programId: String): String? {
    return when (programId) {
      "dnscrypt" -> "/data/adb/modules/ZDT-D/working_folder/dnscrypt/active.json"
      "operaproxy" -> "/data/adb/modules/ZDT-D/working_folder/operaproxy/active.json"
      else -> null
    }
  }

  override fun setProfileEnabled(programId: String, profile: String, enabled: Boolean, onDone: (Boolean) -> Unit) {
    launchIO {
      val ok = runCatching { api.setProfileEnabled(programId, profile, enabled) }.getOrDefault(false)
      if (ok) {
        log("OK", "$programId/$profile enabled=$enabled (apply after stop/start)")
        refreshPrograms()
      } else {
        log("ERR", "$programId/$profile toggle failed")
      }
      withContext(Dispatchers.Main.immediate) { onDone(ok) }
    }
  }

  override fun deleteProfile(programId: String, profile: String, onDone: (Boolean) -> Unit) {
    launchIO {
      val ok = runCatching { api.deleteProfile(programId, profile) }.getOrDefault(false)
      if (ok) {
        log("OK", "$programId/$profile deleted")
        refreshPrograms()
      } else {
        log("ERR", "$programId/$profile delete failed")
      }
      withContext(Dispatchers.Main.immediate) { onDone(ok) }
    }
  }

  override fun createNextProfile(programId: String, onDone: (String?) -> Unit) {
    launchIO {
      val before = _uiState.value.programs.firstOrNull { it.id == programId }?.profiles?.map { it.name }?.toSet().orEmpty()
      val ok = runCatching { api.createProfile(programId) }.getOrDefault(false)
      if (!ok) {
        log("ERR", "$programId: create profile failed")
        withContext(Dispatchers.Main.immediate) { onDone(null) }
        return@launchIO
      }

      // Refresh and detect newly created profile by diff.
      val programs = runCatching { api.getPrograms() }.getOrDefault(emptyList())
      _uiState.update { it.copy(programs = programs) }
      val after = programs.firstOrNull { it.id == programId }?.profiles?.map { it.name }?.toSet().orEmpty()
      val created = (after - before).firstOrNull()
      log("OK", "$programId/${created ?: "(new)"} created (apply after stop/start)")
      withContext(Dispatchers.Main.immediate) { onDone(created) }
    }
  }

  override fun createNamedProfile(programId: String, profile: String, onDone: (String?) -> Unit) {
    launchIO {
      val p = profile.trim()
      val before = _uiState.value.programs.firstOrNull { it.id == programId }?.profiles?.map { it.name }?.toSet().orEmpty()
      val ok = runCatching { api.createProfile(programId, p) }.getOrDefault(false)
      if (!ok) {
        log("ERR", "$programId: create profile '$p' failed")
        withContext(Dispatchers.Main.immediate) { onDone(null) }
        return@launchIO
      }

      // Refresh and prefer the explicitly requested name.
      val programs = runCatching { api.getPrograms() }.getOrDefault(emptyList())
      _uiState.update { it.copy(programs = programs) }
      val after = programs.firstOrNull { it.id == programId }?.profiles?.map { it.name }?.toSet().orEmpty()
      val created = when {
        after.contains(p) -> p
        else -> (after - before).firstOrNull() ?: p
      }
      log("OK", "$programId/$created created (apply after stop/start)")
      withContext(Dispatchers.Main.immediate) { onDone(created) }
    }
  }

  override fun loadText(path: String, onDone: (String?) -> Unit) {
    launchIO {
      val content = runCatching { api.getTextContent(path) }.getOrNull()
      if (content == null) log("ERR", "$path: load failed")
      withContext(Dispatchers.Main.immediate) { onDone(content) }
    }
  }

  override fun loadRootTextFile(path: String, onDone: (String?) -> Unit) {
    launchIO {
      val content = runCatching { root.readTextFile(path) }.getOrNull()
      if (content == null) log("ERR", "$path: root read failed")
      withContext(Dispatchers.Main.immediate) { onDone(content) }
    }
  }

  override fun saveText(path: String, content: String, onDone: (Boolean) -> Unit) {
    launchIO {
      val ok = runCatching { api.putTextContent(path, content) }.getOrDefault(false)
      if (ok) log("OK", "$path: saved (apply after stop/start)")
      else log("ERR", "$path: save failed")
      withContext(Dispatchers.Main.immediate) { onDone(ok) }
    }
  }

  override fun saveRootTextFile(path: String, content: String, onDone: (Boolean) -> Unit) {
    launchIO {
      val ok = runCatching { root.writeTextFile(path, content) }.getOrDefault(false)
      if (ok) log("OK", "$path: root saved")
      else log("ERR", "$path: root save failed")
      withContext(Dispatchers.Main.immediate) { onDone(ok) }
    }
  }

  override fun loadJsonData(path: String, onDone: (JSONObject?) -> Unit) {
    launchIO {
      val obj = runCatching { api.getJsonData(path) }.getOrNull()
      if (obj == null) log("ERR", "$path: load failed")
      withContext(Dispatchers.Main.immediate) { onDone(obj) }
    }
  }

  override fun saveJsonData(path: String, obj: JSONObject, onDone: (Boolean) -> Unit) {
    launchIO {
      val ok = runCatching { api.putJsonData(path, obj) }.getOrDefault(false)
      if (ok) log("OK", "$path: saved (apply after stop/start)")
      else log("ERR", "$path: save failed")
      withContext(Dispatchers.Main.immediate) { onDone(ok) }
    }
  }


override fun listStrategicFiles(dir: String, onDone: (List<String>?) -> Unit) {
  launchIO {
    val obj = runCatching { api.getJsonData("/api/strategic/$dir") }.getOrNull()
    val arr = obj?.optJSONArray("files")
    val files = if (arr != null) (0 until arr.length()).mapNotNull { i -> arr.optString(i) } else null
    withContext(Dispatchers.Main.immediate) { onDone(files) }
  }
}

override fun loadStrategicText(dir: String, filename: String, onDone: (String?) -> Unit) {
  launchIO {
    val enc = URLEncoder.encode(filename, "UTF-8")
    val obj = runCatching { api.getJsonData("/api/strategic/$dir/$enc") }.getOrNull()
    val text = obj?.optString("content", null)
    withContext(Dispatchers.Main.immediate) { onDone(text) }
  }
}

override fun saveStrategicText(dir: String, filename: String, content: String, onDone: (Boolean) -> Unit) {
  launchIO {
    val enc = URLEncoder.encode(filename, "UTF-8")
    val payload = JSONObject().put("content", content)
    val ok = runCatching { api.putJsonData("/api/strategic/$dir/$enc", payload) }.getOrDefault(false)
    withContext(Dispatchers.Main.immediate) { onDone(ok) }
  }
}

override fun deleteStrategicFile(dir: String, filename: String, onDone: (Boolean) -> Unit) {
  launchIO {
    val enc = URLEncoder.encode(filename, "UTF-8")
    val ok = runCatching { api.deletePath("/api/strategic/$dir/$enc") }.getOrDefault(false)
    withContext(Dispatchers.Main.immediate) { onDone(ok) }
  }
}

override fun uploadStrategicFile(dir: String, filename: String, bytes: ByteArray, onDone: (Boolean) -> Unit) {
  launchIO {
    val ok = runCatching { api.uploadMultipart("/api/strategic/$dir/upload", filename, bytes) }.getOrDefault(false)
    withContext(Dispatchers.Main.immediate) { onDone(ok) }
  }
}

override fun listStrategicVariants(programId: String, onDone: (List<ApiModels.StrategyVariant>?) -> Unit) {
  launchIO {
    val obj = runCatching { api.getJsonData("/api/strategicvar/${URLEncoder.encode(programId, "UTF-8")}") }.getOrNull()
    val filesArr = obj?.optJSONArray("files")
    val metaArr = obj?.optJSONArray("meta")

    val meta = HashMap<String, ApiModels.StrategyVariant>()
    if (metaArr != null) {
      for (i in 0 until metaArr.length()) {
        val o = metaArr.optJSONObject(i) ?: continue
        val name = o.optString("name", "").trim()
        if (name.isEmpty()) continue
        val sha = o.optString("sha256", "").trim().ifEmpty { null }
        meta[name] = ApiModels.StrategyVariant(name = name, sha256 = sha)
      }
    }

    val out = ArrayList<ApiModels.StrategyVariant>()
    if (filesArr != null) {
      for (i in 0 until filesArr.length()) {
        val name = filesArr.optString(i, "").trim()
        if (name.isEmpty()) continue
        out.add(meta[name] ?: ApiModels.StrategyVariant(name = name, sha256 = null))
      }
    } else {
      // Fallback: if server doesn't expose list, return whatever meta we have.
      out.addAll(meta.values)
    }
    out.sortBy { it.name }
    withContext(Dispatchers.Main.immediate) { onDone(out) }
  }
}

override fun applyStrategicVariant(programId: String, profile: String, file: String, onDone: (Boolean) -> Unit) {
  launchIO {
    val payload = JSONObject()
      .put("program", programId)
      .put("profile", profile)
      .put("file", file)
    val ok = runCatching { api.postJsonData("/api/strategicvar/apply", payload) }.getOrDefault(false)
    withContext(Dispatchers.Main.immediate) { onDone(ok) }
  }
}

  // ----- App update (GitHub) -----

  private fun applyAppLanguageMode(mode: String) {
  val m = mode.trim().lowercase()
  when (m) {
    // Auto: clear overrides so the app follows the system locale.
    // With only EN (default) + RU resources this matches the rule:
    // system ru -> RU, any other -> EN (fallback).
    "auto", "" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    "ru" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
    "en" -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
    else -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
  }
}

  override fun setAppUpdateChecksEnabled(enabled: Boolean) {
    root.setAppUpdateCheckEnabled(enabled)
    _appUpdate.update { st ->
      st.copy(
        enabled = enabled,
        bannerVisible = if (enabled) st.bannerVisible else false,
        checking = false,
        errorText = null,
        needsUnknownSourcesPermission = false,
      )
    }
    if (!enabled) {
      root.clearCachedAppUpdate()
      cancelAppUpdateDownload()
    } else {
      // Optionally re-check when user enables it.
      maybeCheckAppUpdate(force = true)
    }
  }

  override fun setDaemonStatusNotificationsEnabled(enabled: Boolean) {
    if (!enabled) {
      pendingEnableDaemonNotification = false
      root.setDaemonStatusNotificationEnabled(false)
      _appUpdate.update { it.copy(daemonStatusNotificationEnabled = false) }
      DaemonStatusNotifier.cancel(ctx)
      return
    }

    // Enabling: request runtime permission on Android 13+.
    if (!hasPostNotificationsPermission()) {
      pendingEnableDaemonNotification = true
      _notificationEvents.tryEmit(NotificationEvent.RequestPostNotificationsPermission)
      toast(str(R.string.mv_auto_074))
      return
    }

    pendingEnableDaemonNotification = false
    root.setDaemonStatusNotificationEnabled(true)
    _appUpdate.update { it.copy(daemonStatusNotificationEnabled = true) }
  }

  override fun setAppLanguageMode(mode: String) {
    root.setAppLanguageMode(mode)
    applyAppLanguageMode(root.getAppLanguageMode())
    _appUpdate.update { it.copy(languageMode = root.getAppLanguageMode()) }
  }

  override fun checkAppUpdateNow() {
    maybeCheckAppUpdate(force = true)
  }

  override fun dismissAppUpdateBanner() {
    appUpdateBannerDismissedThisSession = true
    _appUpdate.update { it.copy(bannerVisible = false, errorText = null) }
  }

  override fun startAppUpdateDownload() {
    val url = _appUpdate.value.downloadUrl
    val releaseUrl = _appUpdate.value.releaseHtmlUrl ?: "https://github.com/GAME-OVER-op/ZDT-D/releases"
    if (url.isNullOrBlank()) {
      _appUpdateEvents.tryEmit(AppUpdateEvent.OpenUrl(releaseUrl))
      return
    }

    if (_appUpdate.value.downloading) return

    appUpdateDownloadJob?.cancel()
    appUpdateDownloadJob = viewModelScope.launch(Dispatchers.IO + ceh) {
      updateDownloadUi(downloading = true, percent = 0, speedBps = 0, path = null, err = null)
      try {
        val path = downloadLatestApk(url)
        if (!currentCoroutineContext().isActive) return@launch
        if (path.isNullOrBlank()) {
          updateDownloadUi(downloading = false, percent = 0, speedBps = 0, path = null, err = str(R.string.mv_auto_075))
          return@launch
        }
        updateDownloadUi(downloading = false, percent = 100, speedBps = 0, path = path, err = null)

        if (canRequestPackageInstalls()) {
          _appUpdateEvents.tryEmit(AppUpdateEvent.InstallApk(path))
        } else {
          _appUpdate.update { it.copy(needsUnknownSourcesPermission = true) }
        }
      } catch (_: CancellationException) {
        // cancelled
        updateDownloadUi(downloading = false, percent = 0, speedBps = 0, path = null, err = null)
      } catch (e: Throwable) {
        updateDownloadUi(downloading = false, percent = 0, speedBps = 0, path = null, err = str(R.string.mv_error_with_detail, (e.message ?: e.toString())))
      }
    }
  }

  override fun cancelAppUpdateDownload() {
    appUpdateDownloadJob?.cancel()
    appUpdateDownloadJob = null
    clearDownloadedUpdateApk()
    _appUpdate.update { it.copy(downloading = false, downloadPercent = 0, downloadSpeedBytesPerSec = 0, errorText = null) }
  }

  override fun requestUnknownSourcesPermission() {
    _appUpdate.update { it.copy(needsUnknownSourcesPermission = false) }
    _appUpdateEvents.tryEmit(AppUpdateEvent.OpenUnknownSourcesSettings)
  }

  override fun declineUnknownSourcesPermission() {
    val releaseUrl = _appUpdate.value.releaseHtmlUrl ?: "https://github.com/GAME-OVER-op/ZDT-D/releases"
    clearDownloadedUpdateApk()
    _appUpdate.update { it.copy(bannerVisible = false, errorText = null) }
    _appUpdateEvents.tryEmit(AppUpdateEvent.OpenUrl(releaseUrl))
  }

  override fun onUnknownSourcesPermissionResult(granted: Boolean) {
    val releaseUrl = _appUpdate.value.releaseHtmlUrl ?: "https://github.com/GAME-OVER-op/ZDT-D/releases"
    val path = _appUpdate.value.downloadedPath
    _appUpdate.update { it.copy(needsUnknownSourcesPermission = false) }

    if (granted && !path.isNullOrBlank()) {
      _appUpdateEvents.tryEmit(AppUpdateEvent.InstallApk(path))
    } else {
      clearDownloadedUpdateApk()
      _appUpdate.update { it.copy(bannerVisible = false, errorText = null) }
      _appUpdateEvents.tryEmit(AppUpdateEvent.OpenUrl(releaseUrl))
    }
  }

  override fun onPostNotificationsPermissionResult(granted: Boolean) {
    val pending = pendingEnableDaemonNotification
    pendingEnableDaemonNotification = false
    if (!pending) return

    if (granted) {
      root.setDaemonStatusNotificationEnabled(true)
      _appUpdate.update { it.copy(daemonStatusNotificationEnabled = true) }
      toast(str(R.string.mv_auto_076))
    } else {
      root.setDaemonStatusNotificationEnabled(false)
      _appUpdate.update { it.copy(daemonStatusNotificationEnabled = false) }
      toast(str(R.string.mv_auto_077))
    }
  }


  private fun detectDeviceInfo(): DeviceInfo {
    val cpu = detectCpuName()
    val ram = getTotalRamMb()
    return DeviceInfo(cpuName = cpu, totalRamMb = ram.takeIf { it > 0 })
  }

  private fun getTotalRamMb(): Long {
    return try {
      val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val mi = ActivityManager.MemoryInfo()
      am.getMemoryInfo(mi)
      (mi.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
    } catch (_: Throwable) {
      0L
    }
  }

  private fun detectCpuName(): String {
    val socModel = tryGetBuildField("SOC_MODEL") ?: getProp("ro.soc.model")
    val socMfr = tryGetBuildField("SOC_MANUFACTURER") ?: getProp("ro.soc.manufacturer")

    val modelClean = socModel?.trim().orEmpty()
    val mfrClean = socMfr?.trim().orEmpty()
    if (modelClean.isNotBlank() && !modelClean.equals("unknown", ignoreCase = true)) {
      return if (mfrClean.isNotBlank() && !modelClean.contains(mfrClean, ignoreCase = true)) {
        "$mfrClean $modelClean".trim()
      } else {
        modelClean
      }
    }

    readCpuInfoLine("Hardware")?.let { return it }
    readCpuInfoLine("model name")?.let { return it }
    readCpuInfoLine("Processor")?.let { return it }

    val hw = Build.HARDWARE?.trim().orEmpty()
    return hw.ifBlank { str(R.string.stats_unknown_cpu) }
  }

  private fun tryGetBuildField(fieldName: String): String? {
    return try {
      val f = Build::class.java.getDeclaredField(fieldName)
      (f.get(null) as? String)?.takeIf { it.isNotBlank() && it != "UNKNOWN" }
    } catch (_: Throwable) {
      null
    }
  }

  private fun getProp(name: String): String? {
    val candidates = listOf("/system/bin/getprop", "getprop")
    for (bin in candidates) {
      try {
        val p = ProcessBuilder(bin, name).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (out.isNotBlank()) return out
      } catch (_: Throwable) {
        // ignore
      }
    }
    return null
  }

  private fun readCpuInfoLine(key: String): String? {
    return try {
      val text = runCatching { File("/proc/cpuinfo").readText() }.getOrNull() ?: return null
      text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(key, ignoreCase = true) && it.contains(":") }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
      null
    }
  }
}