package com.android.zdtd.service.singboxlx

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

enum class SingBoxLxComponentStage {
    IDLE,
    DOWNLOADING,
    UNPACKING,
    VERIFYING,
    INSTALLING,
    INSTALLED,
    FAILED,
}

enum class SingBoxLxComponentError {
    STATUS,
    UNSUPPORTED_ABI,
    DOWNLOAD,
    PACKAGE,
    INSTALL,
    REMOVE,
}

data class SingBoxLxComponentState(
    val stage: SingBoxLxComponentStage = SingBoxLxComponentStage.IDLE,
    val installed: Boolean = false,
    /** True only after the app has actually probed the optional component state. */
    val statusResolved: Boolean = false,
    val fileName: String = SingBoxLxComponentRepository.ARCHIVE_FILE,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val selectedAbi: String = "",
    val installedVersion: String = "",
    val error: SingBoxLxComponentError? = null,
    val errorDetail: String? = null,
) {
    val isWorking: Boolean
        get() = stage == SingBoxLxComponentStage.DOWNLOADING ||
            stage == SingBoxLxComponentStage.UNPACKING ||
            stage == SingBoxLxComponentStage.VERIFYING ||
            stage == SingBoxLxComponentStage.INSTALLING
}

/**
 * Root-only optional sing-box-lx delivery.
 *
 * The binary deliberately lives outside the bundled module payload. It is downloaded from the
 * stable Technical_Assets release, verified against the archive manifest and then atomically
 * swapped into the Magisk module. The previous executable is restored if the freshly installed
 * binary cannot answer `version` on the device.
 */
class SingBoxLxComponentRepository(
    private val context: Context,
    private val root: RootConfigManager,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun currentState(): SingBoxLxComponentState {
        val installed = isInstalled()
        return SingBoxLxComponentState(
            stage = if (installed) SingBoxLxComponentStage.INSTALLED else SingBoxLxComponentStage.IDLE,
            installed = installed,
            statusResolved = true,
            selectedAbi = selectAbi().orEmpty(),
            installedVersion = if (installed) readInstalledVersion() else "",
        )
    }

    fun isInstalled(): Boolean = runCatching {
        root.execRootSh("test -x ${shQuote(MODULE_BIN_PATH)}").isSuccess
    }.getOrDefault(false)

    fun remove(): SingBoxLxComponentState {
        val script = "rm -f ${shQuote(MODULE_BIN_PATH)} ${shQuote(MODULE_BIN_TMP_PATH)} " +
            "${shQuote(MODULE_BIN_BACKUP_PATH)} ${shQuote(COMPONENT_INFO_PATH)} " +
            "${shQuote(COMPONENT_INFO_TMP_PATH)} ${shQuote(COMPONENT_INFO_BACKUP_PATH)} 2>/dev/null || true"
        val result = root.execRootSh(script)
        val installed = isInstalled()
        return if (result.isSuccess && !installed) {
            SingBoxLxComponentState(
                stage = SingBoxLxComponentStage.IDLE,
                installed = false,
                statusResolved = true,
                selectedAbi = selectAbi().orEmpty(),
            )
        } else {
            SingBoxLxComponentState(
                stage = SingBoxLxComponentStage.FAILED,
                installed = installed,
                statusResolved = true,
                selectedAbi = selectAbi().orEmpty(),
                installedVersion = if (installed) readInstalledVersion() else "",
                error = SingBoxLxComponentError.REMOVE,
                errorDetail = commandError(result.err, result.out),
            )
        }
    }

    fun downloadAndInstall(onState: (SingBoxLxComponentState) -> Unit): SingBoxLxComponentState {
        val abi = selectAbi() ?: return failed(
            error = SingBoxLxComponentError.UNSUPPORTED_ABI,
            detail = Build.SUPPORTED_ABIS.joinToString(),
        )

        val workDir = componentDir()
        val archiveTmp = File(workDir, "$ARCHIVE_FILE.tmp")
        val archive = File(workDir, ARCHIVE_FILE)
        val unpackDir = File(workDir, "unpack")
        val selectedFile = File(unpackDir, "$abi/$BINARY_NAME")
        var binaryInstalled = false
        val hadExistingBinary = isInstalled()

        try {
            if (!workDir.exists() && !workDir.mkdirs()) {
                return failed(SingBoxLxComponentError.PACKAGE, "Unable to create component cache directory", abi)
            }
            archiveTmp.delete()
            archive.delete()
            unpackDir.deleteRecursively()

            onState(SingBoxLxComponentState(stage = SingBoxLxComponentStage.DOWNLOADING, statusResolved = true, selectedAbi = abi))
            downloadArchive(archiveTmp, abi, onState)
            if (!archiveTmp.renameTo(archive)) {
                archiveTmp.copyTo(archive, overwrite = true)
                archiveTmp.delete()
            }

            onState(SingBoxLxComponentState(stage = SingBoxLxComponentStage.UNPACKING, statusResolved = true, progressPercent = 100, selectedAbi = abi))
            unzipSelectedAbi(archive, unpackDir, abi)

            onState(SingBoxLxComponentState(stage = SingBoxLxComponentStage.VERIFYING, statusResolved = true, progressPercent = 100, selectedAbi = abi))
            val manifest = readManifest(archive)
            validateManifest(manifest)
            val version = manifest.optString("version", "").trim()
            verifyBinary(selectedFile, abi, manifest)

            onState(
                SingBoxLxComponentState(
                    stage = SingBoxLxComponentStage.INSTALLING,
                    statusResolved = true,
                    progressPercent = 100,
                    selectedAbi = abi,
                    installedVersion = version,
                )
            )
            installRootBinary(selectedFile)
            binaryInstalled = true
            writeComponentInfo(abi, version, manifest)
            finalizeRootInstall()

            archive.delete()
            unpackDir.deleteRecursively()

            return SingBoxLxComponentState(
                stage = SingBoxLxComponentStage.INSTALLED,
                installed = true,
                statusResolved = true,
                progressPercent = 100,
                selectedAbi = abi,
                installedVersion = version,
            )
        } catch (error: ComponentFailure) {
            archiveTmp.delete()
            archive.delete()
            unpackDir.deleteRecursively()
            if (binaryInstalled) rollbackRootBinary(hadExistingBinary)
            root.execRootSh("rm -f ${shQuote(MODULE_BIN_TMP_PATH)} 2>/dev/null || true")
            return failed(error.kind, error.message, abi)
        } catch (error: Throwable) {
            archiveTmp.delete()
            archive.delete()
            unpackDir.deleteRecursively()
            if (binaryInstalled) rollbackRootBinary(hadExistingBinary)
            root.execRootSh("rm -f ${shQuote(MODULE_BIN_TMP_PATH)} 2>/dev/null || true")
            return failed(SingBoxLxComponentError.PACKAGE, error.message ?: error.javaClass.simpleName, abi)
        }
    }

    private fun downloadArchive(
        targetTmpFile: File,
        abi: String,
        onState: (SingBoxLxComponentState) -> Unit,
    ) {
        val request = Request.Builder()
            .url("$DOWNLOAD_URL?ts=${System.currentTimeMillis()}")
            .header("User-Agent", "ZDT-D Android")
            .header("Cache-Control", "no-cache")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) fail(SingBoxLxComponentError.DOWNLOAD, "HTTP ${response.code}")
                val body = response.body
                val totalBytes = body.contentLength()
                if (totalBytes > MAX_EXPECTED_ARCHIVE_BYTES) {
                    fail(SingBoxLxComponentError.DOWNLOAD, "$ARCHIVE_FILE is unexpectedly large")
                }
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
                            if (downloaded > MAX_EXPECTED_ARCHIVE_BYTES) {
                                fail(SingBoxLxComponentError.DOWNLOAD, "$ARCHIVE_FILE exceeded the size limit")
                            }
                            val percent = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (percent != lastProgress || downloaded - lastProgressBytes >= PROGRESS_EMIT_BYTES) {
                                lastProgress = percent
                                lastProgressBytes = downloaded
                                onState(
                                    SingBoxLxComponentState(
                                        stage = SingBoxLxComponentStage.DOWNLOADING,
                                        statusResolved = true,
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
        } catch (error: ComponentFailure) {
            throw error
        } catch (error: IOException) {
            fail(SingBoxLxComponentError.DOWNLOAD, error.message ?: error.javaClass.simpleName)
        }

        if (!targetTmpFile.isFile || targetTmpFile.length() <= MIN_EXPECTED_ARCHIVE_BYTES) {
            targetTmpFile.delete()
            fail(SingBoxLxComponentError.DOWNLOAD, "$ARCHIVE_FILE is missing or too small")
        }
        if (targetTmpFile.length() > MAX_EXPECTED_ARCHIVE_BYTES) {
            targetTmpFile.delete()
            fail(SingBoxLxComponentError.DOWNLOAD, "$ARCHIVE_FILE is unexpectedly large")
        }
    }

    private fun unzipSelectedAbi(archive: File, targetDir: File, abi: String) {
        try {
            ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_FILE)
                    ?: fail(SingBoxLxComponentError.PACKAGE, "$MANIFEST_FILE is missing")
                targetDir.mkdirs()
                zip.getInputStream(manifestEntry).use { input ->
                    File(targetDir, MANIFEST_FILE).outputStream().buffered().use { output -> input.copyTo(output) }
                }

                val binaryEntry = zip.getEntry("$abi/$BINARY_NAME")
                    ?: fail(SingBoxLxComponentError.PACKAGE, "$abi/$BINARY_NAME is missing")
                val outputFile = File(targetDir, "$abi/$BINARY_NAME")
                outputFile.parentFile?.mkdirs()
                zip.getInputStream(binaryEntry).use { input ->
                    outputFile.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
        } catch (error: ComponentFailure) {
            throw error
        } catch (error: Throwable) {
            fail(SingBoxLxComponentError.PACKAGE, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun readManifest(archive: File): JSONObject {
        try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(MANIFEST_FILE)
                    ?: fail(SingBoxLxComponentError.PACKAGE, "$MANIFEST_FILE is missing")
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                return JSONObject(text)
            }
        } catch (error: ComponentFailure) {
            throw error
        } catch (error: Throwable) {
            fail(SingBoxLxComponentError.PACKAGE, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun validateManifest(manifest: JSONObject) {
        if (manifest.optString("name", "") != COMPONENT_NAME) {
            fail(SingBoxLxComponentError.PACKAGE, "Unexpected component name")
        }
        if (manifest.optString("version", "").trim().isEmpty()) {
            fail(SingBoxLxComponentError.PACKAGE, "Version is missing from manifest")
        }
        if (manifest.optString("source", "").trim() != EXPECTED_SOURCE) {
            fail(SingBoxLxComponentError.PACKAGE, "Unexpected component source")
        }
        val ref = manifest.optString("ref", "").trim()
        if (!ref.matches(Regex("^v[0-9A-Za-z._+-]+$"))) {
            fail(SingBoxLxComponentError.PACKAGE, "Component ref is missing or invalid")
        }
        val commit = manifest.optString("commit", "").trim()
        if (!commit.matches(Regex("^[0-9a-fA-F]{40}$"))) {
            fail(SingBoxLxComponentError.PACKAGE, "Component commit is missing or invalid")
        }
        if (manifest.optString("license", "").trim() != EXPECTED_LICENSE) {
            fail(SingBoxLxComponentError.PACKAGE, "Unexpected component license")
        }
        val buildTags = manifest.optString("build_tags", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val missingTags = REQUIRED_BUILD_TAGS - buildTags
        if (missingTags.isNotEmpty()) {
            fail(SingBoxLxComponentError.PACKAGE, "Required build tags are missing: ${missingTags.sorted().joinToString()}")
        }
    }

    private fun verifyBinary(file: File, abi: String, manifest: JSONObject) {
        if (!file.isFile || file.length() <= MIN_EXPECTED_BINARY_BYTES) {
            fail(SingBoxLxComponentError.PACKAGE, "$abi/$BINARY_NAME is missing or too small")
        }
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 ||
                magic[0] != 0x7F.toByte() ||
                magic[1] != 'E'.code.toByte() ||
                magic[2] != 'L'.code.toByte() ||
                magic[3] != 'F'.code.toByte()
            ) {
                fail(SingBoxLxComponentError.PACKAGE, "$abi/$BINARY_NAME is not an ELF binary")
            }
        }

        val fileMeta = manifest.optJSONObject("files")?.optJSONObject("$abi/$BINARY_NAME")
            ?: fail(SingBoxLxComponentError.PACKAGE, "Manifest entry for $abi/$BINARY_NAME is missing")
        val expectedSha = fileMeta.optString("sha256", "").trim()
        if (!expectedSha.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            fail(SingBoxLxComponentError.PACKAGE, "Manifest SHA-256 is invalid")
        }
        val expectedSize = fileMeta.optLong("size", -1L)
        if (expectedSize <= 0L || file.length() != expectedSize) {
            fail(SingBoxLxComponentError.PACKAGE, "Size mismatch for $abi/$BINARY_NAME")
        }
        val actualSha = sha256(file)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            fail(SingBoxLxComponentError.PACKAGE, "SHA-256 mismatch for $abi/$BINARY_NAME")
        }
    }

    private fun installRootBinary(source: File) {
        val sourceQ = shQuote(source.absolutePath)
        val binDirQ = shQuote(MODULE_BIN_DIR)
        val targetQ = shQuote(MODULE_BIN_PATH)
        val tmpQ = shQuote(MODULE_BIN_TMP_PATH)
        val backupQ = shQuote(MODULE_BIN_BACKUP_PATH)
        val script = """
            set -e
            mkdir -p $binDirQ
            rm -f $tmpQ $backupQ
            if [ -e $targetQ ]; then cp -fp $targetQ $backupQ; fi
            cp -f $sourceQ $tmpQ
            chmod 0755 $tmpQ
            mv -f $tmpQ $targetQ
            chmod 0755 $targetQ
            if ! $targetQ version >/dev/null 2>&1; then
              rm -f $targetQ
              if [ -e $backupQ ]; then
                mv -f $backupQ $targetQ
                chmod 0755 $targetQ
              fi
              exit 42
            fi
            test -x $targetQ
        """.trimIndent()
        val result = root.execRootSh(script)
        if (!result.isSuccess) {
            val detail = commandError(result.err, result.out).ifBlank { "Root install or runtime verification failed" }
            fail(SingBoxLxComponentError.INSTALL, detail)
        }
    }

    private fun writeComponentInfo(abi: String, version: String, manifest: JSONObject) {
        val info = JSONObject()
            .put("installed", true)
            .put("name", COMPONENT_NAME)
            .put("selected_abi", abi)
            .put("installed_version", version)
            .put("source", manifest.optString("source", ""))
            .put("ref", manifest.optString("ref", ""))
            .put("commit", manifest.optString("commit", ""))
            .put("license", manifest.optString("license", ""))
            .put("build_tags", manifest.optString("build_tags", ""))
            .put("installed_at_unix", System.currentTimeMillis() / 1000L)

        if (!root.writeTextFile(COMPONENT_INFO_TMP_PATH, info.toString(2))) {
            fail(SingBoxLxComponentError.INSTALL, "Unable to write component metadata")
        }

        val infoQ = shQuote(COMPONENT_INFO_PATH)
        val tmpQ = shQuote(COMPONENT_INFO_TMP_PATH)
        val backupQ = shQuote(COMPONENT_INFO_BACKUP_PATH)
        val dirQ = shQuote(COMPONENT_INFO_DIR)
        val script = """
            set -e
            mkdir -p $dirQ
            chmod 0600 $tmpQ
            rm -f $backupQ
            if [ -e $infoQ ]; then cp -fp $infoQ $backupQ; fi
            if ! { mv -f $tmpQ $infoQ && chmod 0600 $infoQ; }; then
              rm -f $infoQ
              if [ -e $backupQ ]; then mv -f $backupQ $infoQ; fi
              exit 43
            fi
        """.trimIndent()
        val result = root.execRootSh(script)
        if (!result.isSuccess) {
            val detail = commandError(result.err, result.out).ifBlank { "Unable to commit component metadata" }
            fail(SingBoxLxComponentError.INSTALL, detail)
        }
    }

    private fun finalizeRootInstall() {
        root.execRootSh(
            "rm -f ${shQuote(MODULE_BIN_BACKUP_PATH)} ${shQuote(MODULE_BIN_TMP_PATH)} " +
                "${shQuote(COMPONENT_INFO_BACKUP_PATH)} ${shQuote(COMPONENT_INFO_TMP_PATH)} 2>/dev/null || true"
        )
    }

    private fun rollbackRootBinary(hadExistingBinary: Boolean) {
        val targetQ = shQuote(MODULE_BIN_PATH)
        val backupQ = shQuote(MODULE_BIN_BACKUP_PATH)
        val tmpQ = shQuote(MODULE_BIN_TMP_PATH)
        val script = if (hadExistingBinary) {
            "if [ -e $backupQ ]; then rm -f $targetQ; mv -f $backupQ $targetQ; chmod 0755 $targetQ; fi; rm -f $tmpQ"
        } else {
            "rm -f $targetQ $backupQ $tmpQ"
        }
        root.execRootSh(script)
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

    private fun failed(
        error: SingBoxLxComponentError,
        detail: String? = null,
        abi: String = selectAbi().orEmpty(),
    ): SingBoxLxComponentState {
        val installed = isInstalled()
        return SingBoxLxComponentState(
            stage = SingBoxLxComponentStage.FAILED,
            installed = installed,
            statusResolved = true,
            selectedAbi = abi,
            installedVersion = if (installed) readInstalledVersion() else "",
            error = error,
            errorDetail = detail?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun fail(kind: SingBoxLxComponentError, detail: String): Nothing =
        throw ComponentFailure(kind, detail)

    private fun commandError(err: List<String>, out: List<String>): String =
        (err + out).joinToString("\n").trim().take(MAX_ERROR_DETAIL_CHARS)

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

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private class ComponentFailure(
        val kind: SingBoxLxComponentError,
        message: String,
    ) : RuntimeException(message)

    companion object {
        const val ARCHIVE_FILE = "sing_box_lx.zip"
        const val BINARY_NAME = "sing-box-lx"
        const val COMPONENT_NAME = "sing-box-lx"
        private const val MANIFEST_FILE = "manifest.json"
        private const val COMPONENT_CACHE_DIR = "components/singboxlx"
        private const val MODULE_BIN_DIR = "/data/adb/modules/ZDT-D/bin"
        const val MODULE_BIN_PATH = "$MODULE_BIN_DIR/$BINARY_NAME"
        private const val MODULE_BIN_TMP_PATH = "$MODULE_BIN_PATH.tmp"
        private const val MODULE_BIN_BACKUP_PATH = "$MODULE_BIN_PATH.bak"
        private const val COMPONENT_INFO_DIR = "/data/adb/modules/ZDT-D/working_folder/singbox-lx"
        private const val COMPONENT_INFO_PATH = "$COMPONENT_INFO_DIR/component.json"
        private const val COMPONENT_INFO_TMP_PATH = "$COMPONENT_INFO_PATH.tmp"
        private const val COMPONENT_INFO_BACKUP_PATH = "$COMPONENT_INFO_PATH.bak"
        private const val DOWNLOAD_URL = "https://github.com/GAME-OVER-op/ZDT-D/releases/download/Technical_Assets/$ARCHIVE_FILE"
        private const val EXPECTED_SOURCE = "https://github.com/Leadaxe/sing-box-lx"
        private const val EXPECTED_LICENSE = "GPL-3.0"
        private val REQUIRED_BUILD_TAGS = setOf("with_gvisor", "with_quic", "with_utls", "with_xhttp", "with_awg", "with_lx_chain")
        private const val MIN_EXPECTED_ARCHIVE_BYTES = 1_000_000L
        private const val MAX_EXPECTED_ARCHIVE_BYTES = 128L * 1024L * 1024L
        private const val MIN_EXPECTED_BINARY_BYTES = 1_000_000L
        private const val PROGRESS_EMIT_BYTES = 256L * 1024L
        private const val MAX_ERROR_DETAIL_CHARS = 1200
    }
}
