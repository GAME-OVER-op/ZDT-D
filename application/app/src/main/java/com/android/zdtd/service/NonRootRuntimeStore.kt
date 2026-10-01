package com.android.zdtd.service

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom

/**
 * Private, app-owned runtime layout for the non-root execution path.
 *
 * Nothing in this store depends on /data/adb or the Magisk module.  Native
 * programs receive these paths explicitly when the VPN runtime is wired up.
 */
class NonRootRuntimeStore(context: Context) {
  private val appContext = context.applicationContext
  val rootDir: File = File(appContext.filesDir, "nonroot")
  val apiDir: File = File(rootDir, "api")
  val runtimeDir: File = File(rootDir, "runtime")
  val configsDir: File = File(rootDir, "configs")
  val logsDir: File = File(rootDir, "logs")
  val tokenFile: File = File(apiDir, "token")

  fun ensureLayout(): File {
    listOf(rootDir, apiDir, runtimeDir, configsDir, logsDir).forEach { dir ->
      check(dir.exists() || dir.mkdirs()) { "Unable to create non-root runtime directory: ${dir.absolutePath}" }
    }
    return ensureApiToken()
  }

  fun ensureApiToken(): File {
    check(apiDir.exists() || apiDir.mkdirs()) { "Unable to create non-root API directory: ${apiDir.absolutePath}" }
    val current = runCatching { tokenFile.readText().trim() }.getOrDefault("")
    if (current.isNotEmpty() && current.toByteArray(Charsets.UTF_8).size <= 255) {
      return tokenFile
    }

    val random = ByteArray(32)
    SecureRandom().nextBytes(random)
    val token = Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val tmp = File(apiDir, ".token-${System.nanoTime()}.tmp")
    tmp.writeText(token + "\n")
    tmp.setReadable(false, false)
    tmp.setWritable(false, false)
    tmp.setReadable(true, true)
    tmp.setWritable(true, true)
    if (tokenFile.exists()) {
      tokenFile.setWritable(true, true)
      check(tokenFile.delete()) { "Unable to replace invalid non-root API token" }
    }
    if (!tmp.renameTo(tokenFile)) {
      tokenFile.writeText(token + "\n")
      tmp.delete()
    }
    tokenFile.setReadable(false, false)
    tokenFile.setWritable(false, false)
    tokenFile.setReadable(true, true)
    tokenFile.setWritable(true, true)
    return tokenFile
  }
}
