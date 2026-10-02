package com.android.zdtd.service.tgwsplugin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object TgWsPluginContract {
  const val PACKAGE_NAME = "com.android.zdtd.service.plugin.com"
  const val SERVICE_CLASS = "com.android.zdtd.service.plugin.com.TgWsPluginService"
  const val BIND_ACTION = "com.android.zdtd.service.plugin.com.action.BIND_TGWS"
  const val CONTROL_PERMISSION = "com.android.zdtd.service.plugin.com.permission.CONTROL_TGWS"
  const val API_VERSION = 1
  const val APK_ASSET = "zdt-d-tgws-plugin.apk"
  const val MANIFEST_ASSET = "tgws-plugin.json"
  const val TECHNICAL_BASE_URL = "https://github.com/GAME-OVER-op/ZDT-D/releases/download/Technical_Assets"
}

data class TgWsPluginState(
  val installed: Boolean = false,
  val packagePresent: Boolean = false,
  val signatureMismatch: Boolean = false,
  val installedVersionName: String = "",
  val installedVersionCode: Long = 0L,
  val latestVersionName: String = "",
  val latestVersionCode: Long = 0L,
  val downloading: Boolean = false,
  val progressPercent: Int = 0,
  val installing: Boolean = false,
  val removing: Boolean = false,
  val errorMessage: String? = null,
) {
  val busy: Boolean get() = downloading || installing || removing
  val updateAvailable: Boolean get() = installed && latestVersionCode > installedVersionCode
}

object TgWsPluginStateBus {
  private val _state = MutableStateFlow(TgWsPluginState())
  val state: StateFlow<TgWsPluginState> = _state.asStateFlow()
  internal fun update(block: (TgWsPluginState) -> TgWsPluginState) { _state.value = block(_state.value) }
  internal fun replace(state: TgWsPluginState) { _state.value = state }
}

class TgWsPluginManager(private val context: Context) {
  private val appContext = context.applicationContext
  private val packageManager = appContext.packageManager
  private val http = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()

  fun refreshLocal(clearError: Boolean = false): TgWsPluginState {
    val rawInfo = rawInstalledPackageInfo()
    val trusted = rawInfo == null || signaturesMatchHost(rawInfo)
    val info = rawInfo?.takeIf { trusted }
    val old = TgWsPluginStateBus.state.value
    val trustError = if (rawInfo != null && !trusted) "Installed TGWS plugin signature does not match ZDT-D" else null
    val updated = old.copy(
      installed = info != null,
      packagePresent = rawInfo != null,
      signatureMismatch = rawInfo != null && !trusted,
      installedVersionName = info?.versionName.orEmpty(),
      installedVersionCode = info?.longVersionCodeCompat() ?: 0L,
      downloading = false,
      installing = false,
      removing = false,
      progressPercent = if (info != null) 100 else 0,
      errorMessage = trustError ?: if (clearError) null else old.errorMessage,
    )
    TgWsPluginStateBus.replace(updated)
    return updated
  }

  suspend fun refreshRemote(): TgWsPluginState = withContext(Dispatchers.IO) {
    refreshLocal()
    runCatching {
      val manifest = fetchManifest()
      TgWsPluginStateBus.update {
        it.copy(
          latestVersionName = manifest.versionName,
          latestVersionCode = manifest.versionCode,
          errorMessage = if (it.signatureMismatch) it.errorMessage else null,
        )
      }
    }.onFailure { error ->
      TgWsPluginStateBus.update { it.copy(errorMessage = error.message ?: error.javaClass.simpleName) }
    }
    TgWsPluginStateBus.state.value
  }

  suspend fun downloadAndInstall(): TgWsPluginState = withContext(Dispatchers.IO) {
    TgWsPluginStateBus.update { it.copy(downloading = true, installing = false, progressPercent = 0, errorMessage = null) }
    runCatching {
      val manifest = fetchManifest()
      TgWsPluginStateBus.update { it.copy(latestVersionName = manifest.versionName, latestVersionCode = manifest.versionCode) }
      val apkFile = File(appContext.cacheDir, "tgws-plugin/${TgWsPluginContract.APK_ASSET}").apply {
        parentFile?.mkdirs()
        delete()
      }
      downloadApk(apkFile)
      val actualSha = sha256(apkFile)
      check(actualSha.equals(manifest.sha256, ignoreCase = true)) {
        "TGWS plugin SHA-256 mismatch"
      }
      validateApk(apkFile, manifest)
      commitInstall(apkFile)
      TgWsPluginStateBus.update { it.copy(downloading = false, installing = true, progressPercent = 100) }
    }.onFailure { error ->
      TgWsPluginStateBus.update {
        it.copy(downloading = false, installing = false, progressPercent = 0, errorMessage = error.message ?: error.javaClass.simpleName)
      }
    }
    TgWsPluginStateBus.state.value
  }

  fun requestUninstall(): TgWsPluginState {
    return runCatching {
      check(rawInstalledPackageInfo() != null) { "TGWS plugin is not installed" }
      TgWsPluginStateBus.update { it.copy(removing = true, errorMessage = null) }
      // Use the platform uninstall UI instead of relying on installer-of-record privileges.
      // This also lets the user remove a conflicting package with the same package name/signature mismatch.
      val uninstallIntent = Intent(
        Intent.ACTION_DELETE,
        Uri.parse("package:${TgWsPluginContract.PACKAGE_NAME}"),
      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      appContext.startActivity(uninstallIntent)
      TgWsPluginStateBus.state.value
    }.getOrElse { error ->
      TgWsPluginStateBus.update { it.copy(removing = false, errorMessage = error.message ?: error.javaClass.simpleName) }
      TgWsPluginStateBus.state.value
    }
  }

  fun isInstalled(): Boolean {
    val info = rawInstalledPackageInfo() ?: return false
    return signaturesMatchHost(info)
  }

  private fun rawInstalledPackageInfo(): PackageInfo? = runCatching {
    packageManager.getPackageInfo(TgWsPluginContract.PACKAGE_NAME, signingFlags())
  }.getOrNull()

  private fun signaturesMatchHost(pluginInfo: PackageInfo): Boolean {
    val hostInfo = runCatching { packageManager.getPackageInfo(appContext.packageName, signingFlags()) }.getOrNull() ?: return false
    val host = signerDigests(hostInfo).toSet()
    val plugin = signerDigests(pluginInfo).toSet()
    return host.isNotEmpty() && plugin.isNotEmpty() && host.intersect(plugin).isNotEmpty()
  }

  private data class RemoteManifest(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val apiVersion: Int,
    val sha256: String,
  )

  private fun fetchManifest(): RemoteManifest {
    val request = Request.Builder()
      .url("${TgWsPluginContract.TECHNICAL_BASE_URL}/${TgWsPluginContract.MANIFEST_ASSET}")
      .header("Cache-Control", "no-cache")
      .build()
    http.newCall(request).execute().use { response ->
      check(response.isSuccessful) { "TGWS plugin manifest HTTP ${response.code}" }
      val body = response.body.string()
      val obj = JSONObject(body)
      val packageName = obj.optString("packageName")
      check(packageName == TgWsPluginContract.PACKAGE_NAME) { "Unexpected TGWS plugin package: $packageName" }
      val versionName = obj.optString("versionName")
      val versionCode = obj.optLong("versionCode", 0L)
      val apiVersion = obj.optInt("apiVersion", 0)
      val digest = obj.optString("sha256").lowercase()
      check(versionName.isNotBlank() && versionCode > 0L && apiVersion == TgWsPluginContract.API_VERSION && digest.matches(Regex("[0-9a-f]{64}"))) {
        "Invalid or incompatible TGWS plugin manifest"
      }
      return RemoteManifest(packageName, versionName, versionCode, apiVersion, digest)
    }
  }

  private fun downloadApk(destination: File) {
    val request = Request.Builder()
      .url("${TgWsPluginContract.TECHNICAL_BASE_URL}/${TgWsPluginContract.APK_ASSET}")
      .header("Cache-Control", "no-cache")
      .build()
    http.newCall(request).execute().use { response ->
      check(response.isSuccessful) { "TGWS plugin APK HTTP ${response.code}" }
      val body = response.body
      val total = body.contentLength()
      body.byteStream().use { input ->
        destination.outputStream().buffered().use { output ->
          val buffer = ByteArray(64 * 1024)
          var copied = 0L
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            if (total > 0L) {
              val percent = ((copied * 100L) / total).toInt().coerceIn(0, 99)
              TgWsPluginStateBus.update { it.copy(progressPercent = percent) }
            }
          }
        }
      }
      check(destination.isFile && destination.length() > 0L) { "TGWS plugin APK download is empty" }
    }
  }

  private fun validateApk(apk: File, manifest: RemoteManifest) {
    val info = packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())
      ?: error("Unable to inspect TGWS plugin APK")
    check(info.packageName == manifest.packageName) { "TGWS plugin package mismatch" }
    check(info.longVersionCodeCompat() == manifest.versionCode) { "TGWS plugin versionCode mismatch" }
    val hostDigests = signerDigests(packageManager.getPackageInfo(appContext.packageName, signingFlags())).toSet()
    val pluginDigests = signerDigests(info).toSet()
    check(hostDigests.isNotEmpty()) { "Unable to read ZDT-D signing certificate" }
    check(pluginDigests.isNotEmpty()) { "TGWS plugin APK is unsigned" }
    check(hostDigests.intersect(pluginDigests).isNotEmpty()) { "TGWS plugin signature does not match ZDT-D" }
  }

  private fun commitInstall(apk: File) {
    val installer = packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(TgWsPluginContract.PACKAGE_NAME)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
      }
    }
    val sessionId = installer.createSession(params)
    installer.openSession(sessionId).use { session ->
      apk.inputStream().use { input ->
        session.openWrite("base.apk", 0L, apk.length()).use { output ->
          input.copyTo(output)
          session.fsync(output)
        }
      }
      val callback = Intent(appContext, TgWsPluginInstallReceiver::class.java)
        .setAction(TgWsPluginInstallReceiver.ACTION_INSTALL_RESULT)
        .putExtra("session_id", sessionId)
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
      val pending = PendingIntent.getBroadcast(appContext, 99031, callback, flags)
      session.commit(pending.intentSender)
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun signerDigests(info: PackageInfo): List<String> {
    val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val signingInfo = info.signingInfo ?: return emptyList()
      if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners.toList()
      else signingInfo.signingCertificateHistory.toList()
    } else {
      @Suppress("DEPRECATION")
      info.signatures?.toList().orEmpty()
    }
    return certs.map { sig ->
      MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }
  }

  private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    PackageManager.GET_SIGNING_CERTIFICATES
  } else {
    @Suppress("DEPRECATION")
    PackageManager.GET_SIGNATURES
  }

  private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    longVersionCode
  } else {
    @Suppress("DEPRECATION")
    versionCode.toLong()
  }
}
