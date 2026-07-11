package com.android.zdtd.service.nonroot

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NonRootBinaryInstaller(private val context: Context) {
    private val paths = NonRootPaths(context)

    data class InstalledBinaries(
        val busybox: File,
        val tun2socks: File,
        val t2s: File,
    )

    fun ensureBaseBinaries(): InstalledBinaries {
        paths.ensureBase()
        val busybox = installBusyBox()
        val archive = installModuleArchive()
        val expectedArchiveSha = readAssetText("busybox/zdt_module.sha256")
            .lineSequence()
            .map { it.trim().substringBefore(' ').trim() }
            .firstOrNull { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.lowercase()
        if (!expectedArchiveSha.isNullOrBlank()) {
            val actual = sha256(archive).lowercase()
            check(actual == expectedArchiveSha) { "Module archive SHA-256 mismatch: expected $expectedArchiveSha, got $actual" }
        }

        val marker = File(paths.runtime, "base_binaries.sha256")
        val currentMarker = buildString {
            append("archive=").append(sha256(archive)).append('\n')
            append("abi=").append(moduleAbi()).append('\n')
        }
        val t2s = File(paths.bin, "t2s")
        val tun2socks = File(paths.bin, "tun2socks")
        if (marker.readTextOrNull() != currentMarker || !t2s.canExecute() || !tun2socks.canExecute()) {
            extractRequiredBinaries(busybox, archive)
            marker.writeText(currentMarker)
        }
        check(t2s.canExecute()) { "t2s binary is not executable: ${t2s.absolutePath}" }
        check(tun2socks.canExecute()) { "tun2socks binary is not executable: ${tun2socks.absolutePath}" }
        return InstalledBinaries(busybox = busybox, tun2socks = tun2socks, t2s = t2s)
    }

    private fun installBusyBox(): File {
        val asset = if (preferredAbi() == "armeabi-v7a") "busybox/busybox-arm32" else "busybox/busybox-arm64"
        val target = File(paths.bin, "busybox")
        installAssetExecutable(asset, target)
        return target
    }

    private fun installModuleArchive(): File {
        val target = File(paths.runtime, "zdt_module.zip")
        installAssetFile("zdt_module.zip", target)
        return target
    }

    private fun extractRequiredBinaries(busybox: File, archive: File) {
        if (paths.staging.exists()) paths.staging.deleteRecursively()
        check(paths.staging.mkdirs()) { "Unable to create staging directory: ${paths.staging.absolutePath}" }
        val log = File(paths.logs, "binary-extract.log")
        val process = ProcessBuilder(busybox.absolutePath, "unzip", archive.absolutePath, "-d", paths.staging.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        val ok = process.waitFor(90, TimeUnit.SECONDS) && process.exitValue() == 0
        check(ok) { "BusyBox unzip failed, see ${log.absolutePath}" }

        copyBinaryFromStaging("t2s")
        copyBinaryFromStaging("tun2socks")
        paths.staging.deleteRecursively()
    }

    private fun copyBinaryFromStaging(name: String) {
        val candidates = listOf(
            File(paths.staging, "prebuilt/bin/${moduleAbi()}/$name"),
            File(paths.staging, "prebuilt/bin/${preferredAbi()}/$name"),
            File(paths.staging, "bin/$name"),
        )
        val source = candidates.firstOrNull { it.isFile && it.length() > 0L }
            ?: error("Binary $name not found in module archive for ABI ${moduleAbi()}")
        val target = File(paths.bin, name)
        val tmp = File(paths.bin, "$name.tmp")
        source.copyTo(tmp, overwrite = true)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        tmp.setExecutable(true, true)
        if (target.exists() && !target.delete()) error("Unable to replace ${target.absolutePath}")
        if (!tmp.renameTo(target)) error("Unable to install ${target.absolutePath}")
        target.setReadable(true, true)
        target.setWritable(true, true)
        target.setExecutable(true, true)
    }

    private fun installAssetExecutable(assetPath: String, target: File) {
        installAssetFile(assetPath, target)
        target.setExecutable(true, true)
    }

    private fun installAssetFile(assetPath: String, target: File) {
        val parent = target.parentFile ?: error("Invalid target: $target")
        if (!parent.exists() && !parent.mkdirs()) error("Unable to create ${parent.absolutePath}")
        val assetSha = sha256Asset(assetPath)
        if (target.isFile && sha256OrNull(target) == assetSha) return
        val tmp = File(parent, "${target.name}.tmp")
        context.assets.open(assetPath).use { input ->
            tmp.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        if (target.exists() && !target.delete()) {
            tmp.delete()
            error("Unable to replace ${target.absolutePath}")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Unable to install ${target.absolutePath}")
        }
    }

    private fun readAssetText(assetPath: String): String = context.assets.open(assetPath).bufferedReader().use { it.readText() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun sha256Asset(assetPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun sha256OrNull(file: File): String? = runCatching { sha256(file) }.getOrNull()
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun preferredAbi(): String = when {
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64-v8a"
            Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "armeabi-v7a"
            else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        }

        fun moduleAbi(): String = if (preferredAbi() == "armeabi-v7a") "arm-v7a" else "arm64-v8a"
    }
}

private fun File.readTextOrNull(): String? = if (isFile) runCatching { readText() }.getOrNull() else null
