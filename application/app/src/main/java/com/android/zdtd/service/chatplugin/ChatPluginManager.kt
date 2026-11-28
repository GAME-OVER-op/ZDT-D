package com.android.zdtd.service.chatplugin

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.R
import com.android.zdtd.service.tgwsplugin.TgWsPluginContract
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class ChatPluginManager(private val context: Context) {
  enum class Installation { ABSENT, TRUSTED, CONFLICT }

  private val pm = context.packageManager
  private val cache = File(context.cacheDir, "chat-plugin")
  private val apk = File(cache, APK_ASSET)

  fun installation(): Installation {
    val info = try {
      pm.getPackageInfo(PACKAGE, signingFlags)
    } catch (_: PackageManager.NameNotFoundException) {
      return Installation.ABSENT
    }
    return if (trusted(info)) Installation.TRUSTED else Installation.CONFLICT
  }

  fun open(): Boolean = runCatching {
    if (installation() != Installation.TRUSTED) return false
    context.startActivity(Intent(Intent.ACTION_MAIN)
      .setComponent(ComponentName(PACKAGE, ACTIVITY))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    true
  }.getOrDefault(false)

  suspend fun prepare(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
    check(installation() != Installation.CONFLICT)
    val manifest = request(MANIFEST_ASSET).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Chat manifest HTTP ${response.code}")
      val source = checkNotNull(response.body).source()
      if (source.request(MAX_MANIFEST_BYTES + 1)) throw IOException("Chat manifest exceeds limit")
      parseManifest(JSONObject(source.readUtf8()))
    }
    currentCoroutineContext().ensureActive()
    check(cache.isDirectory || cache.mkdirs())
    val temp = File.createTempFile("chat-", ".apk", cache)
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      request(APK_ASSET).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Chat APK HTTP ${response.code}")
        val body = checkNotNull(response.body)
        check(body.contentLength() < 0 || body.contentLength() == manifest.size)
        body.byteStream().use { input ->
          temp.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            var percent = -1
            while (true) {
              currentCoroutineContext().ensureActive()
              val n = input.read(buffer)
              if (n < 0) break
              count += n
              check(count <= manifest.size)
              digest.update(buffer, 0, n)
              output.write(buffer, 0, n)
              val next = (count * 100 / manifest.size).toInt()
              if (next != percent) {
                percent = next
                onProgress(next)
              }
            }
            check(count == manifest.size)
          }
        }
      }
      check(digest.digest().hex() == manifest.sha256)
      val info = inspectApk(temp)
      val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
      check(version == manifest.versionCode && info.versionName == manifest.versionName)
      check(temp.renameTo(apk))
    } finally {
      temp.delete()
    }
  }

  fun installerIntent(): Intent {
    inspectApk(apk)
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
    @Suppress("DEPRECATION")
    return Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
      .putExtra(Intent.EXTRA_RETURN_RESULT, true)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  fun clearPreparedApk() { apk.delete() }

  private fun inspectApk(file: File): PackageInfo {
    check(file.isFile && file.length() in 1..MAX_APK_BYTES)
    val info = checkNotNull(pm.getPackageArchiveInfo(file.absolutePath, signingFlags))
    check(info.packageName == PACKAGE && trusted(info))
    return info
  }

  private fun trusted(info: PackageInfo): Boolean {
    val host = signatures(pm.getPackageInfo(context.packageName, signingFlags))
    return host.isNotEmpty() && host == signatures(info)
  }

  @Suppress("DEPRECATION")
  private fun signatures(info: PackageInfo): Set<String> {
    val certs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return certs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
  }

  @Suppress("DEPRECATION")
  private val signingFlags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

  private fun request(asset: String) = http.newCall(Request.Builder()
    .url("${TgWsPluginContract.TECHNICAL_BASE_URL}/$asset").build())

  private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

  internal data class Manifest(val versionName: String, val versionCode: Long, val size: Long, val sha256: String)

  companion object {
    const val PACKAGE = "com.zdtd.chat"
    const val ACTIVITY = "com.zdtd.chat.MainActivity"
    private const val MAX_MANIFEST_BYTES = 8192L
    private const val MAX_APK_BYTES = 100L * 1024 * 1024
    private const val APK_ASSET = "zdt-d-chat-plugin.apk"
    private const val MANIFEST_ASSET = "chat-plugin.json"
    private val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.MINUTES).build()

    internal fun parseManifest(json: JSONObject): Manifest {
      check(json.getString("packageName") == PACKAGE && json.getString("asset") == APK_ASSET && json.getInt("apiVersion") == 1)
      val manifest = Manifest(json.getString("versionName"), json.getLong("versionCode"), json.getLong("size"), json.getString("sha256"))
      check(manifest.versionName.isNotBlank() && manifest.versionCode > 0 && manifest.size in 1..MAX_APK_BYTES &&
        manifest.sha256.matches(Regex("[0-9a-f]{64}")))
      return manifest
    }
  }
}

class ChatShortcutActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (intent.action == ACTION_OPEN && !ChatPluginManager(this).open()) {
      Toast.makeText(this, R.string.chat_plugin_missing, Toast.LENGTH_SHORT).show()
    }
    finish()
  }

  companion object { internal const val ACTION_OPEN = "com.android.zdtd.service.OPEN_CHAT_PLUGIN" }
}

class ChatShortcutReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) { changes.update { it + 1 } }

  companion object { internal val changes = MutableStateFlow(0) }
}
