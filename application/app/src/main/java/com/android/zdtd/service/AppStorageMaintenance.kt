package com.android.zdtd.service

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

/** Centralized ownership and cleanup for disposable app files. */
internal object AppStorageMaintenance {
  private const val REPORT_MAX_AGE_MS = 24L * 60L * 60L * 1000L
  private const val SHARE_MAX_AGE_MS = 60L * 60L * 1000L
  private const val INSTALLER_MAX_AGE_MS = 60L * 60L * 1000L
  private const val LOG_MAX_BYTES = 4L * 1024L * 1024L
  private const val PREFS_NAME = "storage_maintenance"
  private const val PREF_INSTALLER_PATHS = "pending_installer_paths"
  private const val PREF_LAST_VERSION_CODE = "last_cleanup_version_code"
  @Volatile private var startupCleanupCompleted = false

  private val exactCacheFiles = setOf(
    "zdt_module.zip",
    "zdt_module.zip.part",
    "zdt_module_version_check.zip",
    "zapret_target.zip",
    "zapret2_target.zip",
    "mihomo_target.gz",
    "mieru_target.tar.gz",
  )

  private val exactCacheDirectories = setOf(
    "module_unpack",
  )

  private val installerCacheEntries = setOf(
    "zdt_app_update.apk",
    "online-module-full-fallback",
    "full-upgrade",
    "tgws-plugin",
  )

  private val transientPrefixes = listOf(
    "zdtb_import_",
    "zdtb_external_",
    "zdtb_validate_",
    "zapret_nfqws_",
    "zapret2_extract_",
    "mihomo_",
    "mieru_",
    "opera_proxy_",
    "remote_write_",
    "remote_read_",
    "myprogram_upload_",
  )

  /**
   * Runs before a new UI session starts, when no in-process download or unpack
   * operation can own one of these paths. Files needed by the Android package
   * installer are kept only until the installer returns or the next cold start.
   */
  @Synchronized
  fun cleanupOnAppStart(context: Context) {
    if (startupCleanupCompleted) return
    startupCleanupCompleted = true
    val cacheDir = context.applicationContext.cacheDir
    val appVersionChanged = rememberCurrentVersion(context)
    pendingInstallerPaths(context).forEach { deleteCacheArtifact(context, it) }
    savePendingInstallerPaths(context, emptySet())
    cacheDir.listFiles().orEmpty().forEach { entry ->
      when {
        entry.name in exactCacheFiles -> deleteRecursively(entry)
        entry.name in exactCacheDirectories -> deleteRecursively(entry)
        entry.name in installerCacheEntries && (appVersionChanged || isOlderThan(entry, INSTALLER_MAX_AGE_MS)) -> deleteRecursively(entry)
        transientPrefixes.any { prefix -> entry.name.startsWith(prefix) } -> deleteRecursively(entry)
        entry.name.startsWith("zdtb_share_") && isOlderThan(entry, SHARE_MAX_AGE_MS) -> deleteRecursively(entry)
      }
    }
    deleteRecursively(File(cacheDir, "components/tgwsproxy"))
    File(cacheDir, "components").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
    deleteChildrenOlderThan(File(cacheDir, "dpi-detector-reports"), REPORT_MAX_AGE_MS)
    deleteChildrenOlderThan(File(cacheDir, "vps-share"), REPORT_MAX_AGE_MS)
    val vpnActive = NonRootVpnRuntime.state.value in setOf(NonRootVpnState.STARTING, NonRootVpnState.RUNNING, NonRootVpnState.STOPPING)
    val tgWsActive = NonRootTgWsRuntime.state.value in setOf(NonRootTgWsRuntimeState.STARTING, NonRootTgWsRuntimeState.RUNNING)
    if (BuildConfig.IS_NON_ROOT_BUILD && !vpnActive) cleanupNonRootRuntimeWorkspace(context)
    if (BuildConfig.IS_NON_ROOT_BUILD && !vpnActive && !tgWsActive) trimNonRootLogs(context)
  }

  @Synchronized
  fun deleteCacheArtifact(context: Context, path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    val cacheRoot = runCatching { context.applicationContext.cacheDir.canonicalFile }.getOrNull() ?: return false
    val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
    if (target != cacheRoot && target.parentFile != cacheRoot && !target.path.startsWith(cacheRoot.path + File.separator)) {
      return false
    }
    val deleted = deleteRecursively(target)
    val pending = pendingInstallerPaths(context)
    if (target.path in pending) savePendingInstallerPaths(context, pending - target.path)
    var parent = target.parentFile
    while (parent != null && parent != cacheRoot && parent.isDirectory && parent.list().isNullOrEmpty()) {
      if (!parent.delete()) break
      parent = parent.parentFile
    }
    return deleted
  }

  @Synchronized
  fun markInstallerArtifact(context: Context, path: String) {
    val cacheRoot = runCatching { context.applicationContext.cacheDir.canonicalFile }.getOrNull() ?: return
    val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return
    if (!target.path.startsWith(cacheRoot.path + File.separator)) return
    savePendingInstallerPaths(context, pendingInstallerPaths(context) + target.path)
  }

  fun deleteCacheEntriesWithPrefix(context: Context, prefix: String) {
    context.applicationContext.cacheDir.listFiles().orEmpty()
      .filter { it.name.startsWith(prefix) }
      .forEach(::deleteRecursively)
  }

  fun deleteBackupShareCopies(context: Context, except: File? = null) {
    val keep = except?.let { runCatching { it.canonicalPath }.getOrNull() }
    context.applicationContext.cacheDir.listFiles().orEmpty()
      .filter { it.name.startsWith("zdtb_share_") }
      .filter { runCatching { it.canonicalPath }.getOrNull() != keep }
      .forEach(::deleteRecursively)
  }

  fun trimNonRootLogs(context: Context) {
    val logsDir = NonRootRuntimeStore(context.applicationContext).logsDir
    logsDir.listFiles().orEmpty()
      .filter { it.isFile }
      .forEach { trimFileToTail(it, LOG_MAX_BYTES) }
  }

  fun trimNonRootVpnLogs(context: Context) {
    val logsDir = NonRootRuntimeStore(context.applicationContext).logsDir
    logsDir.listFiles().orEmpty()
      .filter { it.isFile && !it.name.startsWith("tgwsproxy") }
      .forEach { trimFileToTail(it, LOG_MAX_BYTES) }
  }

  fun trimLogFile(file: File) {
    trimFileToTail(file, LOG_MAX_BYTES)
  }

  fun cleanupNonRootRuntimeWorkspace(context: Context) {
    if (!BuildConfig.IS_NON_ROOT_BUILD) return
    val store = NonRootRuntimeStore(context.applicationContext)
    runCatching { store.runtimeDir.deleteRecursively() }
    runCatching { store.runtimeDir.mkdirs() }
    store.configsDir.listFiles().orEmpty()
      .filter { it.isFile && it.name != "opera-ca.bundle" }
      .forEach { runCatching { it.delete() } }
  }

  private fun deleteChildrenOlderThan(directory: File, maxAgeMs: Long) {
    val now = System.currentTimeMillis()
    directory.listFiles().orEmpty()
      .filter { now - it.lastModified().coerceAtMost(now) >= maxAgeMs }
      .forEach(::deleteRecursively)
    if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
  }

  private fun isOlderThan(file: File, maxAgeMs: Long): Boolean {
    val now = System.currentTimeMillis()
    return now - newestModified(file).coerceAtMost(now) >= maxAgeMs
  }

  private fun newestModified(file: File): Long {
    if (!file.isDirectory) return file.lastModified()
    return maxOf(file.lastModified(), file.listFiles().orEmpty().maxOfOrNull { newestModified(it) } ?: 0L)
  }

  private fun pendingInstallerPaths(context: Context): Set<String> =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getStringSet(PREF_INSTALLER_PATHS, emptySet())
      ?.toSet()
      .orEmpty()

  private fun rememberCurrentVersion(context: Context): Boolean {
    val appContext = context.applicationContext
    val info = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()
    val current = when {
      info == null -> -1L
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
      else -> {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
      }
    }
    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val previous = prefs.getLong(PREF_LAST_VERSION_CODE, Long.MIN_VALUE)
    prefs.edit().putLong(PREF_LAST_VERSION_CODE, current).apply()
    return previous != current
  }

  private fun savePendingInstallerPaths(context: Context, paths: Set<String>) {
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putStringSet(PREF_INSTALLER_PATHS, paths)
      .apply()
  }

  private fun deleteRecursively(file: File): Boolean = runCatching {
    if (!file.exists()) true else if (file.isDirectory) file.deleteRecursively() else file.delete()
  }.getOrDefault(false)

  private fun trimFileToTail(file: File, maxBytes: Long) {
    if (!file.isFile || file.length() <= maxBytes) return
    val temp = File(file.parentFile, ".${file.name}.trim")
    runCatching {
      RandomAccessFile(file, "r").use { input ->
        val keep = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val data = ByteArray(keep)
        input.seek(input.length() - keep)
        input.readFully(data)
        FileOutputStream(temp, false).use { it.write(data) }
      }
      if (!file.delete() || !temp.renameTo(file)) {
        FileInputStream(temp).use { input ->
          FileOutputStream(file, false).use { output -> input.copyTo(output) }
        }
        temp.delete()
      }
    }.onFailure { temp.delete() }
  }
}

/** Bounded file output used for verbose native/plugin logs. */
internal object BoundedLogWriter {
  private const val MAX_BYTES = 4L * 1024L * 1024L
  private val lock = Any()

  fun appendLine(file: File, line: String) {
    val bytes = (line.take(64 * 1024) + "\n").toByteArray(Charsets.UTF_8)
    synchronized(lock) {
      file.parentFile?.mkdirs()
      rotateIfNeeded(file, bytes.size.toLong())
      FileOutputStream(file, true).use { it.write(bytes) }
    }
  }

  fun copyProcessOutput(input: InputStream, file: File) {
    file.parentFile?.mkdirs()
    synchronized(lock) {
      file.delete()
      File(file.parentFile, "${file.name}.1").delete()
    }
    input.buffered().use { source ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        synchronized(lock) {
          rotateIfNeeded(file, read.toLong())
          FileOutputStream(file, true).use { output -> output.write(buffer, 0, read) }
        }
      }
    }
  }

  private fun rotateIfNeeded(file: File, incomingBytes: Long) {
    if (file.length() + incomingBytes <= MAX_BYTES) return
    val previous = File(file.parentFile, "${file.name}.1")
    previous.delete()
    if (!file.renameTo(previous)) file.delete()
  }
}
