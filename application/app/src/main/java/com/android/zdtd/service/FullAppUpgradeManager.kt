package com.android.zdtd.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

enum class FullAppUpgradeStatus { IDLE, CHECKING, DOWNLOADING, READY, INSTALLING, ERROR }

data class FullAppUpgradeState(
  val status: FullAppUpgradeStatus = FullAppUpgradeStatus.IDLE,
  val progressPercent: Int = 0,
  val versionName: String = "",
  val errorMessage: String? = null,
) {
  val busy: Boolean get() = status == FullAppUpgradeStatus.CHECKING || status == FullAppUpgradeStatus.DOWNLOADING
}

/** Downloads the bundled root distribution when a non-root user chooses to upgrade. */
class FullAppUpgradeManager(context: Context) {
  private val appContext = context.applicationContext
  private val packageManager = appContext.packageManager
  private val http = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
  private val _state = MutableStateFlow(FullAppUpgradeState())
  val state: StateFlow<FullAppUpgradeState> = _state.asStateFlow()

  suspend fun downloadLatestBundledApk(): Boolean = withContext(Dispatchers.IO) {
    check(BuildConfig.IS_NON_ROOT_BUILD) { "Full APK upgrade is available only in the non-root distribution" }
    if (_state.value.busy) return@withContext false
    _state.value = FullAppUpgradeState(status = FullAppUpgradeStatus.CHECKING)
    runCatching {
      val release = fetchLatestRelease()
      val destination = downloadedApk().apply {
        parentFile?.mkdirs()
        delete()
      }
      _state.value = FullAppUpgradeState(
        status = FullAppUpgradeStatus.DOWNLOADING,
        progressPercent = 0,
        versionName = release.versionName,
      )
      download(release.downloadUrl, destination, release.versionName)
      validateBundledApk(destination)
      _state.value = FullAppUpgradeState(
        status = FullAppUpgradeStatus.READY,
        progressPercent = 100,
        versionName = release.versionName,
      )
      true
    }.getOrElse { error ->
      downloadedApk().delete()
      if (error is CancellationException) throw error
      _state.value = FullAppUpgradeState(
        status = FullAppUpgradeStatus.ERROR,
        errorMessage = error.message ?: error.javaClass.simpleName,
      )
      false
    }
  }

  fun installDownloadedApk(): Boolean = runCatching {
    val apk = downloadedApk()
    check(apk.isFile && apk.length() > 0L) { "Full ZDT-D APK has not been downloaded" }
    validateBundledApk(apk)
    _state.value = _state.value.copy(status = FullAppUpgradeStatus.INSTALLING, errorMessage = null)
    val uri = FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    appContext.startActivity(intent)
    true
  }.getOrElse { error ->
    _state.value = _state.value.copy(
      status = FullAppUpgradeStatus.ERROR,
      errorMessage = error.message ?: error.javaClass.simpleName,
    )
    false
  }

  fun markInstallPermissionDenied() {
    _state.value = _state.value.copy(
      status = FullAppUpgradeStatus.ERROR,
      errorMessage = appContext.getString(R.string.permission_required_body),
    )
  }

  private data class ReleaseAsset(val versionName: String, val downloadUrl: String)

  private fun fetchLatestRelease(): ReleaseAsset {
    val request = Request.Builder()
      .url("https://api.github.com/repos/GAME-OVER-op/ZDT-D/releases/latest")
      .header("User-Agent", "ZDT-D-Android")
      .header("Accept", "application/vnd.github+json")
      .build()
    http.newCall(request).execute().use { response ->
      check(response.isSuccessful) { "GitHub release HTTP ${response.code}" }
      val json = JSONObject(response.body.string())
      val tag = json.optString("tag_name").trim().removePrefix("V").removePrefix("v")
      val assets = json.optJSONArray("assets") ?: error("Latest release has no assets")
      for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("name") == FULL_APK_ASSET_NAME) {
          val url = asset.optString("browser_download_url").trim()
          check(url.startsWith("https://github.com/GAME-OVER-op/ZDT-D/releases/download/")) {
            "Unexpected full APK download URL"
          }
          return ReleaseAsset(tag, url)
        }
      }
      error("$FULL_APK_ASSET_NAME is missing from the latest release")
    }
  }

  private suspend fun download(url: String, destination: File, versionName: String) {
    val request = Request.Builder().url(url).header("User-Agent", "ZDT-D-Android").build()
    http.newCall(request).execute().use { response ->
      check(response.isSuccessful) { "Full APK download HTTP ${response.code}" }
      val body = response.body
      val total = body.contentLength()
      check(total != 0L && total < MAX_APK_BYTES) { "Invalid full APK size: $total" }
      body.byteStream().use { input ->
        destination.outputStream().buffered().use { output ->
          val buffer = ByteArray(64 * 1024)
          var copied = 0L
          while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            check(copied <= MAX_APK_BYTES) { "Full APK exceeds the size limit" }
            if (total > 0L) {
              _state.value = FullAppUpgradeState(
                status = FullAppUpgradeStatus.DOWNLOADING,
                progressPercent = ((copied * 100L) / total).toInt().coerceIn(0, 99),
                versionName = versionName,
              )
            }
          }
        }
      }
      check(destination.length() > 0L) { "Downloaded full APK is empty" }
      if (total > 0L) check(destination.length() == total) { "Downloaded full APK is incomplete" }
    }
  }

  private fun validateBundledApk(apk: File): PackageInfo {
    val info = packageManager.getPackageArchiveInfo(apk.absolutePath, packageInfoFlags())
      ?: error("Unable to inspect downloaded full APK")
    check(info.packageName == BuildConfig.APPLICATION_ID) { "Full APK package mismatch" }
    check(info.longVersionCodeCompat() >= BuildConfig.VERSION_CODE.toLong()) {
      "Full APK version is older than the installed non-root version"
    }
    val distribution = info.applicationInfo?.metaData?.getString(DISTRIBUTION_META_DATA).orEmpty()
    check(distribution == "bundled") { "Downloaded APK is not the bundled root distribution" }
    val installed = packageManager.getPackageInfo(appContext.packageName, packageInfoFlags())
    val installedSigners = signerDigests(installed).toSet()
    val archiveSigners = signerDigests(info).toSet()
    check(installedSigners.isNotEmpty() && archiveSigners.isNotEmpty()) { "Unable to read APK signing certificate" }
    check(installedSigners.intersect(archiveSigners).isNotEmpty()) { "Full APK signature does not match ZDT-D" }
    return info
  }

  private fun packageInfoFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
  } else {
    @Suppress("DEPRECATION")
    PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA
  }

  private fun signerDigests(info: PackageInfo): List<String> {
    val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val signingInfo = info.signingInfo ?: return emptyList()
      if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners.toList()
      else signingInfo.signingCertificateHistory.toList()
    } else {
      @Suppress("DEPRECATION")
      info.signatures?.toList().orEmpty()
    }
    return certificates.map { certificate ->
      MessageDigest.getInstance("SHA-256")
        .digest(certificate.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
  }

  private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    longVersionCode
  } else {
    @Suppress("DEPRECATION")
    versionCode.toLong()
  }

  private fun downloadedApk(): File = File(appContext.cacheDir, "full-upgrade/$FULL_APK_ASSET_NAME")

  private companion object {
    const val FULL_APK_ASSET_NAME = "app-release.apk"
    const val DISTRIBUTION_META_DATA = "com.android.zdtd.service.DISTRIBUTION"
    const val MAX_APK_BYTES = 1024L * 1024L * 1024L
  }
}
