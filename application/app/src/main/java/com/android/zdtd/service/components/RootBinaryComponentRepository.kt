package com.android.zdtd.service.components

import android.content.Context
import android.os.Build
import com.android.zdtd.service.RootConfigManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class RootBinaryComponentSpec(
  val id: String,
  val assetFile: String,
  val binaryNames: List<String>,
  val programIds: List<String>,
  val displayName: String,
  val dependencies: Set<String> = emptySet(),
)

object RootBinaryComponentCatalog {
  val moduleComponents: List<RootBinaryComponentSpec> = listOf(
    RootBinaryComponentSpec("nfqws", "nfqws.zip", listOf("nfqws"), listOf("nfqws"), "Zapret (nfqws)"),
    RootBinaryComponentSpec("nfqws2", "nfqws2.zip", listOf("nfqws2"), listOf("nfqws2"), "Zapret 2 (nfqws2)"),
    RootBinaryComponentSpec("byedpi", "byedpi.zip", listOf("byedpi"), listOf("byedpi"), "ByeDPI"),
    RootBinaryComponentSpec("dpitunnel", "dpitunnel.zip", listOf("dpitunnel-cli"), listOf("dpitunnel"), "DPITunnel"),
    RootBinaryComponentSpec("operaproxy", "opera_proxy.zip", listOf("opera-proxy"), listOf("operaproxy"), "Opera Proxy", setOf("byedpi")),
    RootBinaryComponentSpec("sing-box", "sing_box.zip", listOf("sing-box"), listOf("sing-box"), "sing-box"),
    RootBinaryComponentSpec("hysteria2", "hysteria2.zip", listOf("hysteria2"), listOf("hysteria2"), "Hysteria2"),
    RootBinaryComponentSpec("wireproxy", "wireproxy.zip", listOf("wireproxy"), listOf("wireproxy"), "WireProxy"),
    RootBinaryComponentSpec("openvpn", "openvpn.zip", listOf("openvpn"), listOf("openvpn"), "OpenVPN"),
    RootBinaryComponentSpec("amneziawg", "amneziawg.zip", listOf("amneziawg-go", "awg"), listOf("amneziawg"), "AmneziaWG"),
    RootBinaryComponentSpec("mihomo", "mihomo.zip", listOf("mihomo"), listOf("mihomo"), "Mihomo"),
    RootBinaryComponentSpec("mieru", "mieru.zip", listOf("mieru"), listOf("mieru"), "Mieru"),
    RootBinaryComponentSpec("tor", "tor.zip", listOf("torproxy", "lyrebird"), listOf("tor"), "Tor"),
  )

  private val byId = moduleComponents.associateBy { it.id }
  fun find(id: String): RootBinaryComponentSpec? = byId[id]
  val moduleIds: Set<String> = byId.keys
}

enum class RootBinaryComponentStage {
  IDLE,
  DOWNLOADING,
  UNPACKING,
  VERIFYING,
  INSTALLING,
  INSTALLED,
  FAILED,
}

data class RootBinaryComponentState(
  val id: String,
  val stage: RootBinaryComponentStage = RootBinaryComponentStage.IDLE,
  val installed: Boolean = false,
  val statusResolved: Boolean = false,
  val progressPercent: Int = 0,
  val downloadedBytes: Long = 0L,
  val totalBytes: Long = -1L,
  val selectedAbi: String = "",
  val installedVersion: String = "",
  val errorMessage: String? = null,
) {
  val isWorking: Boolean
    get() = stage == RootBinaryComponentStage.DOWNLOADING ||
      stage == RootBinaryComponentStage.UNPACKING ||
      stage == RootBinaryComponentStage.VERIFYING ||
      stage == RootBinaryComponentStage.INSTALLING
}

class ComponentSelectionStore(private val root: RootConfigManager) {
  @Synchronized
  fun read(): Set<String>? {
    val exists = runCatching { root.execRootSh("test -f ${quote(STATE_PATH)}").isSuccess }.getOrDefault(false)
    if (!exists) return null
    return root.readTextFile(STATE_PATH)
      .lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() && it.matches(ID_RE) }
      .toSet()
  }

  @Synchronized
  fun write(ids: Set<String>): Boolean {
    val safe = ids.filter { it in allowedStateIds }.sorted()
    val text = if (safe.isEmpty()) "" else safe.joinToString(separator = "\n", postfix = "\n")
    val ok = root.writeTextFile(STATE_PATH, text)
    if (ok) root.execRootSh("chmod 700 /data/adb/ZDT-D 2>/dev/null || true; chmod 600 ${quote(STATE_PATH)} 2>/dev/null || true")
    return ok
  }

  @Synchronized
  fun writeModuleSelection(ids: Set<String>): Boolean {
    val extras = currentOrLegacyInstalled()
      .filterTo(mutableSetOf()) { it !in RootBinaryComponentCatalog.moduleIds }
    return write(extras + ids.intersect(RootBinaryComponentCatalog.moduleIds))
  }

  @Synchronized
  fun add(id: String): Boolean {
    if (id !in allowedStateIds) return false
    val current = currentOrLegacyInstalled()
    current += id
    return write(current)
  }

  @Synchronized
  fun remove(id: String): Boolean {
    if (id !in allowedStateIds) return false
    val current = currentOrLegacyInstalled()
    current -= id
    return write(current)
  }

  private fun currentOrLegacyInstalled(): MutableSet<String> {
    read()?.let { return it.toMutableSet() }
    // Migration from builds that predate the persistent component state file. Reconstruct the
    // complete logical set from the active module before the first add/remove/update writes it,
    // otherwise one operation on an old/full installation could accidentally forget every
    // other installed tool for a later online module update.
    return mutableSetOf<String>().apply {
      RootBinaryComponentCatalog.moduleComponents.forEach { spec ->
        val installed = spec.binaryNames.all { binary -> executableInModule(binary) }
        if (installed) add(spec.id)
      }
      if (executableInModule("sing-box-lx")) add("sing-box-lx")
      if (executableInModule("tg-ws-proxy")) add("tgwsproxy")
    }
  }

  private fun executableInModule(binary: String): Boolean {
    if (binary == "byedpi") {
      return listOf("byedpi", "ciadpi-zdt", "ciadpi", "bye_dpi")
        .any { candidate -> root.execRootSh("test -x ${quote("/data/adb/modules/ZDT-D/bin/$candidate")}").isSuccess }
    }
    return root.execRootSh("test -x ${quote("/data/adb/modules/ZDT-D/bin/$binary")}").isSuccess
  }

  @Synchronized
  fun clear(): Boolean = root.execRootSh("rm -f ${quote(STATE_PATH)} 2>/dev/null || true").isSuccess

  private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  companion object {
    const val STATE_PATH = "/data/adb/ZDT-D/components"
    private val ID_RE = Regex("^[A-Za-z0-9._-]+$")
    val allowedStateIds: Set<String> = RootBinaryComponentCatalog.moduleIds + setOf("sing-box-lx", "tgwsproxy")
  }
}

class RootBinaryComponentRepository(
  private val context: Context,
  private val root: RootConfigManager,
  private val selectionStore: ComponentSelectionStore,
) {
  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

  fun currentState(spec: RootBinaryComponentSpec): RootBinaryComponentState {
    val installed = isInstalled(spec)
    return RootBinaryComponentState(
      id = spec.id,
      stage = if (installed) RootBinaryComponentStage.INSTALLED else RootBinaryComponentStage.IDLE,
      installed = installed,
      statusResolved = true,
      selectedAbi = selectAbi().orEmpty(),
      installedVersion = if (installed) readInstalledVersion(spec) else "",
    )
  }

  fun isInstalled(spec: RootBinaryComponentSpec): Boolean {
    if (spec.id == "byedpi") {
      return BYEDPI_BINARY_CANDIDATES.any { binary ->
        runCatching { root.execRootSh("test -x ${quote("$MODULE_BIN_DIR/$binary")}").isSuccess }.getOrDefault(false)
      }
    }
    return spec.binaryNames.all { binary ->
      runCatching { root.execRootSh("test -x ${quote("$MODULE_BIN_DIR/$binary")}").isSuccess }.getOrDefault(false)
    }
  }

  fun remove(spec: RootBinaryComponentSpec): RootBinaryComponentState {
    val dependent = RootBinaryComponentCatalog.moduleComponents
      .firstOrNull { candidate -> spec.id in candidate.dependencies && isInstalled(candidate) }
    if (dependent != null) {
      return failed(spec, "${spec.displayName} is required by ${dependent.displayName}. Remove ${dependent.displayName} first.")
    }
    val running = runningBinary(spec)
    if (running != null) {
      return failed(spec, "Stop ZDT-D before removing the running component ($running).")
    }
    val managed = managedBinaryNames(spec)
    val removeScript = buildString {
      append("set -e; ")
      for (binary in managed) {
        val target = "$MODULE_BIN_DIR/$binary"
        val backup = "$target.zdt-component-remove.bak"
        append("rm -f ").append(quote(backup)).append("; ")
        append("if [ -f ").append(quote(target)).append(" ]; then cp -p ")
          .append(quote(target)).append(' ').append(quote(backup)).append("; fi; ")
      }
      append("rm -f ")
      append(managed.joinToString(" ") { quote("$MODULE_BIN_DIR/$it") })
    }
    val result = root.execRootSh(removeScript)
    val installed = isInstalled(spec)
    if (!result.isSuccess || installed) {
      restoreRemoveBackups(managed)
      return failed(spec, commandError(result.err, result.out).ifBlank { "Component files could not be removed" })
    }
    if (!selectionStore.remove(spec.id)) {
      restoreRemoveBackups(managed)
      return failed(spec, "Unable to persist component removal state")
    }
    cleanupRemoveBackups(managed)
    root.execRootSh("rm -f ${quote(infoPath(spec))} 2>/dev/null || true")
    return RootBinaryComponentState(
      id = spec.id,
      stage = RootBinaryComponentStage.IDLE,
      installed = false,
      statusResolved = true,
      selectedAbi = selectAbi().orEmpty(),
    )
  }

  fun downloadAndInstall(
    spec: RootBinaryComponentSpec,
    onState: (RootBinaryComponentState) -> Unit,
  ): RootBinaryComponentState {
    val abi = selectAbi() ?: return failed(spec, Build.SUPPORTED_ABIS.joinToString())
    runningBinary(spec)?.let { running ->
      return failed(spec, "Stop ZDT-D before updating the running component ($running).", abi)
    }
    val workDir = File(context.cacheDir, "components/${spec.id}")
    val archiveTmp = File(workDir, "${spec.assetFile}.tmp")
    val archive = File(workDir, spec.assetFile)
    val unpackDir = File(workDir, "unpack")
    val hadExisting = spec.binaryNames.associateWith { name ->
      runCatching { root.execRootSh("test -f ${quote("$MODULE_BIN_DIR/$name")}").isSuccess }.getOrDefault(false)
    }

    try {
      if (!workDir.exists() && !workDir.mkdirs()) error("Unable to create component cache directory")
      archiveTmp.delete()
      archive.delete()
      unpackDir.deleteRecursively()

      onState(state(spec, RootBinaryComponentStage.DOWNLOADING, abi))
      download(spec, archiveTmp, abi, onState)
      if (!archiveTmp.renameTo(archive)) {
        archiveTmp.copyTo(archive, overwrite = true)
        archiveTmp.delete()
      }

      onState(state(spec, RootBinaryComponentStage.UNPACKING, abi, progress = 100))
      unzipExpected(spec, archive, unpackDir, abi)

      onState(state(spec, RootBinaryComponentStage.VERIFYING, abi, progress = 100))
      val manifest = readManifest(archive)
      val version = validateManifestAndFiles(spec, manifest, unpackDir, abi)

      onState(state(spec, RootBinaryComponentStage.INSTALLING, abi, progress = 100, version = version))
      installAtomically(spec, unpackDir, abi, hadExisting)
      writeInfo(spec, abi, version, manifest)
      check(selectionStore.add(spec.id)) { "Unable to persist installed component state" }
      cleanupBackups(spec)
      cleanupLegacyAliases(spec)

      archive.delete()
      unpackDir.deleteRecursively()
      return RootBinaryComponentState(
        id = spec.id,
        stage = RootBinaryComponentStage.INSTALLED,
        installed = true,
        statusResolved = true,
        progressPercent = 100,
        selectedAbi = abi,
        installedVersion = version,
      )
    } catch (error: Throwable) {
      archiveTmp.delete()
      archive.delete()
      unpackDir.deleteRecursively()
      rollback(spec, hadExisting)
      return failed(spec, error.message ?: error.javaClass.simpleName, abi)
    }
  }

  private fun download(
    spec: RootBinaryComponentSpec,
    target: File,
    abi: String,
    onState: (RootBinaryComponentState) -> Unit,
  ) {
    val request = Request.Builder()
      .url("$TECHNICAL_ASSETS_BASE/${spec.assetFile}?ts=${System.currentTimeMillis()}")
      .header("User-Agent", "ZDT-D Android")
      .header("Cache-Control", "no-cache")
      .build()
    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body
        val total = body.contentLength()
        if (total > MAX_ARCHIVE_BYTES) error("Archive is unexpectedly large")
        var downloaded = 0L
        var lastPercent = -1
        body.byteStream().use { input ->
          target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              output.write(buffer, 0, read)
              downloaded += read
              if (downloaded > MAX_ARCHIVE_BYTES) error("Archive exceeded size limit")
              val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
              if (percent != lastPercent) {
                lastPercent = percent
                onState(
                  state(
                    spec,
                    RootBinaryComponentStage.DOWNLOADING,
                    abi,
                    progress = percent,
                    downloaded = downloaded,
                    total = total,
                  )
                )
              }
            }
          }
        }
      }
    } catch (e: IOException) {
      throw IOException("Download failed: ${e.message ?: e.javaClass.simpleName}", e)
    }
    if (!target.isFile || target.length() < MIN_ARCHIVE_BYTES) error("Downloaded archive is empty or too small")
  }

  private fun unzipExpected(spec: RootBinaryComponentSpec, archive: File, targetDir: File, abi: String) {
    ZipFile(archive).use { zip ->
      targetDir.mkdirs()
      zip.getEntry(MANIFEST_FILE) ?: error("$MANIFEST_FILE is missing")
      for (binary in spec.binaryNames) {
        val rel = "$abi/$binary"
        val entry = zip.getEntry(rel) ?: error("$rel is missing")
        if (entry.size > MAX_BINARY_BYTES) error("$rel is unexpectedly large")
        val out = File(targetDir, rel)
        out.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
          out.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              total += read
              if (total > MAX_BINARY_BYTES) error("$rel exceeded the extraction size limit")
              output.write(buffer, 0, read)
            }
          }
        }
      }
    }
  }

  private fun readManifest(archive: File): JSONObject = ZipFile(archive).use { zip ->
    val entry = zip.getEntry(MANIFEST_FILE) ?: error("$MANIFEST_FILE is missing")
    if (entry.size > MAX_MANIFEST_BYTES) error("$MANIFEST_FILE is unexpectedly large")
    val bytes = zip.getInputStream(entry).use { input ->
      val out = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (out.size() + read > MAX_MANIFEST_BYTES) error("$MANIFEST_FILE exceeded the size limit")
        out.write(buffer, 0, read)
      }
      out.toByteArray()
    }
    JSONObject(bytes.toString(Charsets.UTF_8))
  }

  private fun validateManifestAndFiles(
    spec: RootBinaryComponentSpec,
    manifest: JSONObject,
    unpackDir: File,
    abi: String,
  ): String {
    check(manifest.optInt("schema", 0) == 1) { "Unsupported manifest schema" }
    check(manifest.optString("component", "").trim() == spec.id) { "Unexpected component id" }
    val version = manifest.optString("version", "").trim()
    check(version.isNotEmpty()) { "Component version is missing" }
    val files = manifest.optJSONObject("files") ?: error("Manifest files map is missing")
    for (binary in spec.binaryNames) {
      val rel = "$abi/$binary"
      val file = File(unpackDir, rel)
      check(file.isFile && file.length() > 0L) { "$rel is missing or empty" }
      file.inputStream().use { input ->
        val magic = ByteArray(4)
        check(input.read(magic) == 4 && magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()) {
          "$rel is not an ELF binary"
        }
      }
      val meta = files.optJSONObject(rel) ?: error("Manifest entry for $rel is missing")
      val expectedSha = meta.optString("sha256", "").trim()
      check(expectedSha.matches(Regex("^[0-9a-fA-F]{64}$"))) { "Invalid SHA-256 for $rel" }
      val expectedSize = meta.optLong("size", -1L)
      check(expectedSize == file.length()) { "Size mismatch for $rel" }
      val actualSha = sha256(file)
      check(actualSha.equals(expectedSha, ignoreCase = true)) { "SHA-256 mismatch for $rel" }
    }
    return version
  }

  private fun installAtomically(
    spec: RootBinaryComponentSpec,
    unpackDir: File,
    abi: String,
    hadExisting: Map<String, Boolean>,
  ) {
    val script = buildString {
      append("set -e; mkdir -p ").append(quote(MODULE_BIN_DIR)).append("; ")
      for (binary in spec.binaryNames) {
        val target = "$MODULE_BIN_DIR/$binary"
        val tmp = "$target.zdt-component.tmp"
        val bak = "$target.zdt-component.bak"
        val source = File(unpackDir, "$abi/$binary").absolutePath
        append("rm -f ").append(quote(tmp)).append(' ').append(quote(bak)).append("; ")
        if (hadExisting[binary] == true) append("cp -p ").append(quote(target)).append(' ').append(quote(bak)).append("; ")
        append("cp -f ").append(quote(source)).append(' ').append(quote(tmp)).append("; chmod 0755 ").append(quote(tmp)).append("; ")
      }
      for (binary in spec.binaryNames) {
        val target = "$MODULE_BIN_DIR/$binary"
        val tmp = "$target.zdt-component.tmp"
        append("mv -f ").append(quote(tmp)).append(' ').append(quote(target)).append("; chmod 0755 ").append(quote(target)).append("; test -x ").append(quote(target)).append("; ")
      }
    }
    val result = root.execRootSh(script)
    if (!result.isSuccess) error(commandError(result.err, result.out).ifBlank { "Root install failed" })
  }

  private fun cleanupBackups(spec: RootBinaryComponentSpec) {
    val backups = spec.binaryNames.joinToString(" ") { quote("$MODULE_BIN_DIR/$it.zdt-component.bak") }
    root.execRootSh("rm -f $backups 2>/dev/null || true")
  }

  private fun cleanupLegacyAliases(spec: RootBinaryComponentSpec) {
    if (spec.id != "byedpi") return
    val legacy = BYEDPI_BINARY_CANDIDATES
      .filter { it != "byedpi" }
      .joinToString(" ") { quote("$MODULE_BIN_DIR/$it") }
    root.execRootSh("rm -f $legacy 2>/dev/null || true")
  }

  private fun managedBinaryNames(spec: RootBinaryComponentSpec): List<String> =
    if (spec.id == "byedpi") BYEDPI_BINARY_CANDIDATES else spec.binaryNames

  private fun rollback(spec: RootBinaryComponentSpec, hadExisting: Map<String, Boolean>) {
    val script = buildString {
      for (binary in spec.binaryNames) {
        val target = "$MODULE_BIN_DIR/$binary"
        val tmp = "$target.zdt-component.tmp"
        val bak = "$target.zdt-component.bak"
        append("rm -f ").append(quote(tmp)).append("; ")
        if (hadExisting[binary] == true) {
          append("if [ -f ").append(quote(bak)).append(" ]; then mv -f ").append(quote(bak)).append(' ').append(quote(target)).append("; chmod 0755 ").append(quote(target)).append("; fi; ")
        } else {
          append("rm -f ").append(quote(target)).append(' ').append(quote(bak)).append("; ")
        }
      }
    }
    root.execRootSh(script)
    root.execRootSh("rm -f ${quote(infoPath(spec))} 2>/dev/null || true")
  }


  private fun restoreRemoveBackups(binaries: List<String>) {
    val script = buildString {
      for (binary in binaries) {
        val target = "$MODULE_BIN_DIR/$binary"
        val backup = "$target.zdt-component-remove.bak"
        append("if [ -f ").append(quote(backup)).append(" ]; then mv -f ")
          .append(quote(backup)).append(' ').append(quote(target))
          .append("; chmod 0755 ").append(quote(target)).append("; fi; ")
      }
    }
    root.execRootSh(script)
  }

  private fun cleanupRemoveBackups(binaries: List<String>) {
    val backups = binaries.joinToString(" ") { quote("$MODULE_BIN_DIR/$it.zdt-component-remove.bak") }
    root.execRootSh("rm -f $backups 2>/dev/null || true")
  }

  private fun runningBinary(spec: RootBinaryComponentSpec): String? {
    for (binary in managedBinaryNames(spec)) {
      if (binary == "awg") continue
      val result = root.execRootSh("pidof ${quote(binary)} >/dev/null 2>&1")
      if (result.isSuccess) return binary
    }
    return null
  }

  private fun writeInfo(spec: RootBinaryComponentSpec, abi: String, version: String, manifest: JSONObject) {
    val info = JSONObject()
      .put("installed", true)
      .put("component", spec.id)
      .put("selected_abi", abi)
      .put("installed_version", version)
      .put("build", manifest.optString("build", ""))
      .put("installed_at_unix", System.currentTimeMillis() / 1000L)
    check(root.writeTextFile(infoPath(spec), info.toString(2))) { "Unable to write component installation metadata" }
  }

  private fun readInstalledVersion(spec: RootBinaryComponentSpec): String {
    val raw = runCatching { root.readTextFile(infoPath(spec)) }.getOrDefault("")
    return runCatching { JSONObject(raw).optString("installed_version", "") }.getOrDefault("")
  }

  private fun infoPath(spec: RootBinaryComponentSpec): String = "$COMPONENT_INFO_DIR/${spec.id}.json"

  private fun selectAbi(): String? = when {
    Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64-v8a"
    Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armeabi-v7a"
    else -> null
  }

  private fun state(
    spec: RootBinaryComponentSpec,
    stage: RootBinaryComponentStage,
    abi: String,
    progress: Int = 0,
    downloaded: Long = 0L,
    total: Long = -1L,
    version: String = "",
  ) = RootBinaryComponentState(
    id = spec.id,
    stage = stage,
    installed = isInstalled(spec),
    statusResolved = true,
    progressPercent = progress,
    downloadedBytes = downloaded,
    totalBytes = total,
    selectedAbi = abi,
    installedVersion = version,
  )

  private fun failed(spec: RootBinaryComponentSpec, message: String, abi: String = selectAbi().orEmpty()) = RootBinaryComponentState(
    id = spec.id,
    stage = RootBinaryComponentStage.FAILED,
    installed = isInstalled(spec),
    statusResolved = true,
    selectedAbi = abi,
    installedVersion = if (isInstalled(spec)) readInstalledVersion(spec) else "",
    errorMessage = message.trim().take(MAX_ERROR_CHARS),
  )

  private fun commandError(err: List<String>, out: List<String>): String = (err + out).joinToString("\n").trim().take(MAX_ERROR_CHARS)

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
  }

  private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  companion object {
    private const val TECHNICAL_ASSETS_BASE = "https://github.com/GAME-OVER-op/ZDT-D/releases/download/Technical_Assets"
    private const val MODULE_BIN_DIR = "/data/adb/modules/ZDT-D/bin"
    private const val COMPONENT_INFO_DIR = "/data/adb/ZDT-D/component-info"
    private const val MANIFEST_FILE = "manifest.json"
    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    private const val MIN_ARCHIVE_BYTES = 128L
    private const val MAX_BINARY_BYTES = 128L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MAX_ERROR_CHARS = 1600
    private val BYEDPI_BINARY_CANDIDATES = listOf("byedpi", "ciadpi-zdt", "ciadpi", "bye_dpi")
  }
}
