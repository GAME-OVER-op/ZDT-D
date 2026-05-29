package com.android.zdtd.service.diagnostics.dpi

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Installs the APK-bundled dpi-detector helper into the app-private no_backup
 * directory. The binary is stored in the APK as an asset and is executed later
 * through the app root shell layer.
 *
 * The helper can stay in no_backup between app updates. Because of that we do
 * not trust only the existing file size: before every run we compare the APK
 * asset SHA-256 with the extracted file SHA-256. If the app was updated and the
 * bundled helper changed, the old extracted binary is replaced atomically.
 */
class DpiDetectorBinary(private val context: Context) {

    val targetFile: File
        get() = File(File(context.noBackupFilesDir, "bin"), BINARY_NAME)

    fun ensureInstalled(): File {
        val target = targetFile
        val parent = target.parentFile ?: error("Invalid dpi-detector target path: $target")
        if (!parent.exists() && !parent.mkdirs()) {
            error("Unable to create dpi-detector directory: $parent")
        }

        val assetBytes = try {
            context.assets.open(ASSET_PATH).use { input -> input.readBytes() }
        } catch (missing: FileNotFoundException) {
            error(
                "Bundled dpi-detector asset is missing from the APK: $ASSET_PATH. " +
                    "Rebuild the application via the project build.sh apk flow or make sure " +
                    "generated assets contain dpi-detector/arm64-v8a/dpi-detector before assembling the APK."
            )
        }
        val assetSha256 = sha256(assetBytes)
        val shouldRewrite = !target.exists() || sha256OrNull(target) != assetSha256

        if (shouldRewrite) {
            val tmp = File(parent, "$BINARY_NAME.tmp")
            tmp.outputStream().use { output -> output.write(assetBytes) }
            tmp.setReadable(true, true)
            tmp.setWritable(true, true)
            tmp.setExecutable(true, true)

            if (target.exists() && !target.delete()) {
                tmp.delete()
                error("Unable to replace old dpi-detector binary: $target")
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("Unable to install dpi-detector binary: $target")
            }
        }

        target.setReadable(true, true)
        target.setWritable(true, true)
        target.setExecutable(true, true)
        return target
    }

    private fun sha256OrNull(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHexString()
        }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).toHexString()
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    companion object {
        const val BINARY_NAME = "dpi-detector"
        const val ASSET_PATH = "dpi-detector/arm64-v8a/dpi-detector"
    }
}
