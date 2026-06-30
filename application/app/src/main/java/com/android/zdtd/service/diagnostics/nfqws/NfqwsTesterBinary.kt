package com.android.zdtd.service.diagnostics.nfqws

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

class NfqwsTesterBinary(private val context: Context) {
    val targetFile: File
        get() = File(File(context.noBackupFilesDir, "bin"), BINARY_NAME)

    private val assetPath: String
        get() = "nfqws-tester/" + preferredAbi() + "/nfqws_tester"

    fun ensureInstalled(): File {
        val target = targetFile
        val parent = target.parentFile ?: error("Invalid nfqws_tester target path: $target")
        if (!parent.exists() && !parent.mkdirs()) {
            error("Unable to create nfqws_tester directory: $parent")
        }

        val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
        val assetSha = sha256(assetBytes)
        val shouldRewrite = !target.exists() || sha256OrNull(target) != assetSha
        if (shouldRewrite) {
            val tmp = File(parent, "$BINARY_NAME.tmp")
            tmp.outputStream().use { it.write(assetBytes) }
            tmp.setReadable(true, true)
            tmp.setWritable(true, true)
            tmp.setExecutable(true, true)
            if (target.exists() && !target.delete()) {
                tmp.delete()
                error("Unable to replace old nfqws_tester binary: $target")
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("Unable to install nfqws_tester binary: $target")
            }
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
        target.setExecutable(true, true)
        return target
    }

    private fun sha256OrNull(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        toHexString(digest.digest())
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return toHexString(digest.digest(bytes))
    }

    private fun toHexString(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val BINARY_NAME = "nfqws_tester"
        private fun preferredAbi(): String = when {
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64-v8a"
            Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "armeabi-v7a"
            else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        }
    }
}
