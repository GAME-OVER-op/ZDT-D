package com.android.zdtd.service.tgwsproxy

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

enum class TgWsProxyComponentStage {
    IDLE,
    DOWNLOADING,
    UNPACKING,
    VERIFYING,
    INSTALLING,
    INSTALLED,
    FAILED,
}

data class TgWsProxyComponentState(
    val stage: TgWsProxyComponentStage = TgWsProxyComponentStage.IDLE,
    val installed: Boolean = false,
    val fileName: String = TgWsProxyComponentRepository.ARCHIVE_FILE,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val selectedAbi: String = "",
    val installedVersion: String = "",
    val errorMessage: String? = null,
) {
    val isWorking: Boolean
        get() = stage == TgWsProxyComponentStage.DOWNLOADING ||
            stage == TgWsProxyComponentStage.UNPACKING ||
            stage == TgWsProxyComponentStage.VERIFYING ||
            stage == TgWsProxyComponentStage.INSTALLING
}

class TgWsProxyComponentRepository(
    private val context: Context,
    private val root: RootConfigManager,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun currentState(): TgWsProxyComponentState {
        val installed = isInstalled()
        return TgWsProxyComponentState(
            stage = if (installed) TgWsProxyComponentStage.INSTALLED else TgWsProxyComponentStage.IDLE,
            installed = installed,
            selectedAbi = selectAbi().orEmpty(),
            installedVersion = if (installed) readInstalledVersion() else "",
        )
    }

    fun isInstalled(): Boolean {
        return runCatching {
            root.execRootSh("test -x ${shQuote(MODULE_BIN_PATH)}").isSuccess
        }.getOrDefault(false)
    }

    fun remove(): TgWsProxyComponentState {
        val ok = root.execRootSh("rm -f ${shQuote(MODULE_BIN_PATH)} ${shQuote(COMPONENT_INFO_PATH)} 2>/dev/null || true").isSuccess
        val installed = isInstalled()
        return if (ok && !installed) {
            TgWsProxyComponentState(
                stage = TgWsProxyComponentStage.IDLE,
                installed = false,
                selectedAbi = selectAbi().orEmpty(),
            )
        } else {
            TgWsProxyComponentState(
                stage = TgWsProxyComponentStage.FAILED,
                installed = installed,
                selectedAbi = selectAbi().orEmpty(),
                errorMessage = "Unable to remove $MODULE_BIN_PATH",
            )
        }
    }

    fun downloadAndInstall(onState: (TgWsProxyComponentState) -> Unit): TgWsProxyComponentState {
        val abi = selectAbi() ?: return TgWsProxyComponentState(
            stage = TgWsProxyComponentStage.FAILED,
            installed = isInstalled(),
            errorMessage = "Unsupported CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}",
        )

        val workDir = componentDir()
        val archiveTmp = File(workDir, "$ARCHIVE_FILE.tmp")
        val archive = File(workDir, ARCHIVE_FILE)
        val unpackDir = File(workDir, "unpack")
        val selectedFile = File(unpackDir, "$abi/$BINARY_NAME")
        val rootTmpPath = "$MODULE_BIN_PATH.tmp"

        runCatching {
            if (!workDir.exists() && !workDir.mkdirs()) error("Unable to create component directory")
            archiveTmp.delete()
            archive.delete()
            unpackDir.deleteRecursively()

            onState(TgWsProxyComponentState(stage = TgWsProxyComponentStage.DOWNLOADING, selectedAbi = abi))
            downloadArchive(archiveTmp, abi, onState)
            if (!archiveTmp.renameTo(archive)) {
                archiveTmp.copyTo(archive, overwrite = true)
                archiveTmp.delete()
            }

            onState(TgWsProxyComponentState(stage = TgWsProxyComponentStage.UNPACKING, progressPercent = 100, selectedAbi = abi))
            unzipSelectedAbi(archive, unpackDir, abi)

            onState(TgWsProxyComponentState(stage = TgWsProxyComponentStage.VERIFYING, progressPercent = 100, selectedAbi = abi))
            val manifest = readManifest(archive)
            val version = manifest.optString("version", "")
            verifyBinary(selectedFile, abi, manifest)

            onState(TgWsProxyComponentState(stage = TgWsProxyComponentStage.INSTALLING, progressPercent = 100, selectedAbi = abi, installedVersion = version))
            installRootBinary(selectedFile, rootTmpPath)
            writeComponentInfo(abi, version, manifest)

            archive.delete()
            unpackDir.deleteRecursively()

            return TgWsProxyComponentState(
                stage = TgWsProxyComponentStage.INSTALLED,
                installed = true,
                progressPercent = 100,
                selectedAbi = abi,
                installedVersion = version,
            )
        }.getOrElse { error ->
            archiveTmp.delete()
            archive.delete()
            unpackDir.deleteRecursively()
            root.execRootSh("rm -f ${shQuote(rootTmpPath)} 2>/dev/null || true")
            return TgWsProxyComponentState(
                stage = TgWsProxyComponentStage.FAILED,
                installed = isInstalled(),
                selectedAbi = abi,
                errorMessage = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun downloadArchive(targetTmpFile: File, abi: String, onState: (TgWsProxyComponentState) -> Unit) {
        val request = Request.Builder()
            .url(DOWNLOAD_URL)
            .header("User-Agent", "ZDT-D Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download $ARCHIVE_FILE: HTTP ${response.code}")
            val body = response.body
            val totalBytes = body.contentLength()
            var downloaded = 0L
            var lastProgress = -1
            var lastProgressBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            body.byteStream().use { input ->
                targetTmpFile.outputStream().buffered().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read.toLong()
                        val percent = if (totalBytes > 0L) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                        if (percent != lastProgress || downloaded - lastProgressBytes >= PROGRESS_EMIT_BYTES) {
                            lastProgress = percent
                            lastProgressBytes = downloaded
                            onState(
                                TgWsProxyComponentState(
                                    stage = TgWsProxyComponentStage.DOWNLOADING,
                                    progressPercent = percent,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes,
                                    selectedAbi = abi,
                                )
                            )
                        }
                    }
                }
            }
        }

        if (!targetTmpFile.isFile || targetTmpFile.length() <= MIN_EXPECTED_ARCHIVE_BYTES) {
            targetTmpFile.delete()
            error("Downloaded $ARCHIVE_FILE is missing or too small")
        }
    }

    private fun unzipSelectedAbi(archive: File, targetDir: File, abi: String) {
        ZipFile(archive).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_FILE) ?: error("$MANIFEST_FILE is missing")
            targetDir.mkdirs()
            zip.getInputStream(manifestEntry).use { input ->
                File(targetDir, MANIFEST_FILE).outputStream().buffered().use { output -> input.copyTo(output) }
            }

            val binaryEntry = zip.getEntry("$abi/$BINARY_NAME") ?: error("$abi/$BINARY_NAME is missing")
            val outputFile = File(targetDir, "$abi/$BINARY_NAME")
            outputFile.parentFile?.mkdirs()
            zip.getInputStream(binaryEntry).use { input ->
                outputFile.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun readManifest(archive: File): JSONObject {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(MANIFEST_FILE) ?: error("$MANIFEST_FILE is missing")
            val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        }
    }

    private fun verifyBinary(file: File, abi: String, manifest: JSONObject) {
        if (!file.isFile || file.length() <= MIN_EXPECTED_BINARY_BYTES) error("$abi/$BINARY_NAME is missing or too small")
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || magic[0] != 0x7F.toByte() || magic[1] != 'E'.code.toByte() || magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()) {
                error("$abi/$BINARY_NAME is not an ELF binary")
            }
        }

        val expected = manifest.optJSONObject("files")
            ?.optJSONObject("$abi/$BINARY_NAME")
            ?.optString("sha256", "")
            ?.trim()
            .orEmpty()
        if (expected.isNotEmpty()) {
            val actual = sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) error("SHA-256 mismatch for $abi/$BINARY_NAME")
        }
    }

    private fun installRootBinary(source: File, rootTmpPath: String) {
        val sourceQ = shQuote(source.absolutePath)
        val targetQ = shQuote(MODULE_BIN_PATH)
        val tmpQ = shQuote(rootTmpPath)
        val binDirQ = shQuote(MODULE_BIN_DIR)
        val script = "mkdir -p $binDirQ && " +
            "cp -f $sourceQ $tmpQ && " +
            "chmod 0755 $tmpQ && " +
            "mv -f $tmpQ $targetQ && " +
            "chmod 0755 $targetQ && " +
            "test -x $targetQ"
        val result = root.execRootSh(script)
        if (!result.isSuccess) {
            val err = (result.err + result.out).joinToString("\n").trim()
            error(if (err.isBlank()) "Root install failed" else err)
        }
    }

    private fun writeComponentInfo(abi: String, version: String, manifest: JSONObject) {
        val info = JSONObject()
            .put("installed", true)
            .put("name", "tg-ws-proxy")
            .put("selected_abi", abi)
            .put("installed_version", version)
            .put("source", manifest.optString("source", ""))
            .put("ref", manifest.optString("ref", ""))
            .put("installed_at_unix", System.currentTimeMillis() / 1000L)
        root.writeTextFile(COMPONENT_INFO_PATH, info.toString(2))
    }

    private fun readInstalledVersion(): String {
        val raw = runCatching { root.readTextFile(COMPONENT_INFO_PATH) }.getOrDefault("").trim()
        return runCatching { JSONObject(raw).optString("installed_version", "") }.getOrDefault("")
    }

    private fun selectAbi(): String? {
        val supported = Build.SUPPORTED_ABIS.toList()
        return when {
            supported.contains("arm64-v8a") -> "arm64-v8a"
            supported.contains("armeabi-v7a") -> "armeabi-v7a"
            else -> null
        }
    }

    private fun componentDir(): File = File(context.cacheDir, COMPONENT_CACHE_DIR)

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

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        const val ARCHIVE_FILE = "tg_ws_proxy.zip"
        const val BINARY_NAME = "tg-ws-proxy"
        private const val MANIFEST_FILE = "manifest.json"
        private const val COMPONENT_CACHE_DIR = "components/tgwsproxy"
        private const val MODULE_BIN_DIR = "/data/adb/modules/ZDT-D/bin"
        const val MODULE_BIN_PATH = "$MODULE_BIN_DIR/$BINARY_NAME"
        private const val COMPONENT_INFO_PATH = "/data/adb/modules/ZDT-D/working_folder/tgwsproxy/component.json"
        private const val DOWNLOAD_URL = "https://github.com/GAME-OVER-op/ZDT-D/releases/download/Technical_Assets/tg_ws_proxy.zip"
        private const val MIN_EXPECTED_ARCHIVE_BYTES = 128_000L
        private const val MIN_EXPECTED_BINARY_BYTES = 64_000L
        private const val PROGRESS_EMIT_BYTES = 256L * 1024L
    }
}
