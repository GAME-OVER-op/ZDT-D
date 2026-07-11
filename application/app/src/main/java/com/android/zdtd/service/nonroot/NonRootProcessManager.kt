package com.android.zdtd.service.nonroot

import java.io.File
import java.util.concurrent.TimeUnit

class NonRootProcessManager(private val logDir: File) {
    private val processes = LinkedHashMap<String, Process>()

    @Synchronized
    fun start(name: String, binary: File, args: List<String>, cwd: File? = null): Process {
        stop(name)
        if (!logDir.exists()) logDir.mkdirs()
        val logFile = File(logDir, "$name.log")
        logFile.parentFile?.mkdirs()
        val pb = ProcessBuilder(listOf(binary.absolutePath) + args)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        if (cwd != null) pb.directory(cwd)
        val p = pb.start()
        processes[name] = p
        return p
    }

    @Synchronized
    fun stop(name: String) {
        processes.remove(name)?.stopProcess()
    }

    @Synchronized
    fun stopAll() {
        processes.keys.toList().asReversed().forEach { stop(it) }
    }

    @Synchronized
    fun isRunning(name: String): Boolean = processes[name]?.isAlive == true

    private fun Process.stopProcess() {
        runCatching { destroy() }
        runCatching {
            if (!waitFor(1800, TimeUnit.MILLISECONDS)) {
                destroyForcibly()
                waitFor(1200, TimeUnit.MILLISECONDS)
            }
        }
    }
}
