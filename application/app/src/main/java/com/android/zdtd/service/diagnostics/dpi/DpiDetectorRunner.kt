package com.android.zdtd.service.diagnostics.dpi

import android.content.Context
import com.android.zdtd.service.RootConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root launcher for the app-bundled dpi-detector helper.
 *
 * The binary lives in APK assets, is extracted to no_backup/bin, and is executed
 * through root. [runNdjsonStream] is the UI-friendly API: it emits one parsed
 * event as soon as the Rust process prints a NDJSON line.
 */
class DpiDetectorRunner(
    private val context: Context,
    private val rootConfigManager: RootConfigManager = RootConfigManager(context),
) {
    suspend fun selfTest(): DpiDetectorRunResult = runRoot(listOf("self-test"))

    suspend fun runNdjson(): DpiDetectorRunResult = runRoot(listOf("run", "--format", "ndjson"))

    fun runNdjsonStream(
        tests: List<String> = emptyList(),
        quick: Boolean = false,
        timeoutMs: Int = 5000,
    ): Flow<DpiDetectorEvent> = flow {
        val binary = withContext(Dispatchers.IO) { DpiDetectorBinary(context).ensureInstalled() }
        val args = buildList {
            add("run")
            add("--format")
            add("ndjson")
            add("--timeout")
            add(timeoutMs.toString())
            if (quick) add("--quick")
            if (tests.isNotEmpty()) {
                add("--tests")
                add(tests.joinToString(","))
            }
        }
        val command = buildRootCommand(binary, args)
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        }

        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                emit(parseEventLine(line))
            }
            val code = withContext(Dispatchers.IO) { process.waitFor() }
            if (code != 0) {
                emit(DpiDetectorEvent.Error("dpi-detector exited with code $code"))
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
            }
        }
    }

    suspend fun stopRunningProcess() = withContext(Dispatchers.IO) {
        val binary = DpiDetectorBinary(context).ensureInstalled()
        rootConfigManager.execRootSh("pkill -f " + shQuote(binary.absolutePath) + " 2>/dev/null || true")
    }

    suspend fun runRoot(args: List<String>): DpiDetectorRunResult = withContext(Dispatchers.IO) {
        val binary = DpiDetectorBinary(context).ensureInstalled()
        val command = buildRootCommand(binary, args)
        val result = rootConfigManager.execRootSh(command)
        DpiDetectorRunResult(
            success = result.isSuccess,
            code = result.code,
            stdout = result.out.joinToString("\n"),
            stderr = result.err.joinToString("\n"),
            binaryPath = binary.absolutePath,
        )
    }

    private fun parseEventLine(line: String): DpiDetectorEvent {
        return runCatching {
            val json = JSONObject(line)
            val type = json.optString("type", "")
            val test = json.optString("test", "")
            val status = json.optString("status", "")
            val detail = json.optString("detail", "")
            val data = json.opt("data")?.toString() ?: "{}"
            val sequence = json.optLong("seq", 0L)
            val timestampMs = json.optLong("ts", 0L)
            when (type) {
                "meta" -> DpiDetectorEvent.Meta(test, status, detail, data, sequence, timestampMs)
                "started" -> DpiDetectorEvent.Started(test, detail, parseTotalProbes(data), data, sequence, timestampMs)
                "probe" -> DpiDetectorEvent.Probe(
                    test = test,
                    key = json.optString("key", ""),
                    name = json.optString("name", ""),
                    target = json.optString("target", ""),
                    sizeLabel = json.optString("size_label", parseSizeLabel(json.opt("data")?.toString() ?: "{}")),
                    technical = json.optJSONObject("technical")?.toStringMap().orEmpty(),
                    checks = (json.optJSONArray("checks") ?: runCatching { JSONObject(data).optJSONArray("checks") }.getOrNull()).toProbeChecks(),
                    diagnosis = json.optString("diagnosis", runCatching { JSONObject(data).optString("diagnosis", "") }.getOrDefault("")),
                    status = status,
                    detail = detail,
                    data = data,
                    sequence = sequence,
                    timestampMs = timestampMs,
                )
                "progress" -> DpiDetectorEvent.Progress(test, status, detail, data, sequence, timestampMs)
                "result" -> DpiDetectorEvent.Result(test, status, detail, parseDiagnosis(data), data, sequence, timestampMs)
                "finished" -> DpiDetectorEvent.Finished(test, status, data, sequence, timestampMs)
                else -> DpiDetectorEvent.Error("Unknown dpi-detector event type: $type", line)
            }
        }.getOrElse { err ->
            DpiDetectorEvent.Error(err.message ?: "Failed to parse dpi-detector event", line)
        }
    }


    private fun JSONArray?.toProbeChecks(): List<DpiDetectorEvent.ProbeCheck> {
        if (this == null) return emptyList()
        val result = ArrayList<DpiDetectorEvent.ProbeCheck>()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            result += DpiDetectorEvent.ProbeCheck(
                name = item.optString("name", ""),
                status = item.optString("status", ""),
                detail = item.optString("detail", ""),
                value = item.optString("value", ""),
                sizeLabel = item.optString("size_label", ""),
            )
        }
        return result
    }

    private fun parseTotalProbes(data: String): Int {
        return runCatching {
            val json = JSONObject(data)
            json.optInt("total_probes", json.optInt("domains", json.optInt("targets", json.optInt("dc_count", 0))))
        }.getOrDefault(0)
    }

    private fun parseDiagnosis(data: String): String {
        return runCatching { JSONObject(data).optString("diagnosis", "") }.getOrDefault("")
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = opt(key)
            result[key] = when (value) {
                null, JSONObject.NULL -> ""
                is JSONObject -> value.toString()
                else -> value.toString()
            }
        }
        return result.filterValues { it.isNotBlank() }
    }

    private fun parseSizeLabel(data: String): String {
        return runCatching { JSONObject(data).optString("size_label", "") }.getOrDefault("")
    }

    private fun buildRootCommand(binary: File, args: List<String>): String {
        val quotedArgs = args.joinToString(" ") { shQuote(it) }
        return buildString {
            append("chmod 700 ")
            append(shQuote(binary.absolutePath))
            append(" 2>/dev/null || true\n")
            append("exec ")
            append(shQuote(binary.absolutePath))
            if (quotedArgs.isNotBlank()) {
                append(' ')
                append(quotedArgs)
            }
        }
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

data class DpiDetectorRunResult(
    val success: Boolean,
    val code: Int,
    val stdout: String,
    val stderr: String,
    val binaryPath: String,
)
