package com.android.zdtd.service.diagnostics.nfqws

import android.content.Context
import com.android.zdtd.service.RootConfigManager
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NfqwsTesterRunner(
    private val context: Context,
    private val rootConfigManager: RootConfigManager = RootConfigManager(context),
) {
    suspend fun listStrategies(program: String): List<String> = withContext(Dispatchers.IO) {
        val obj = runJson(listOf("list", "--program", program))
        val arr = obj.optJSONArray("strategies") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val value = arr.optString(i, "")
                if (value.isNotBlank()) add(value)
            }
        }
    }

    suspend fun startStrategy(program: String, configPath: String, qnum: Int = 200): JSONObject = withContext(Dispatchers.IO) {
        runJson(listOf("start", "--program", program, "--config", configPath, "--qnum", qnum.toString()))
    }

    suspend fun stopTester(): JSONObject = withContext(Dispatchers.IO) {
        runJson(listOf("stop"))
    }

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        runJson(listOf("status"))
    }

    suspend fun usage(pid: Int): JSONObject = withContext(Dispatchers.IO) {
        runJson(listOf("usage", "--pid", pid.toString()))
    }

    suspend fun isTesterLockActive(): Boolean = withContext(Dispatchers.IO) {
        rootConfigManager.execRootSh("test -f /data/adb/modules/ZDT-D/working_folder/nfqws_tester/session.json").isSuccess
    }

    private suspend fun runJson(args: List<String>): JSONObject = withContext(Dispatchers.IO) {
        val binary = NfqwsTesterBinary(context).ensureInstalled()
        val command = buildRootCommand(binary.absolutePath, args)
        val result = rootConfigManager.execRootSh(command)
        val raw = (result.out + result.err).joinToString("\n").trim()
        if (!result.isSuccess && raw.isBlank()) {
            throw IllegalStateException("nfqws_tester command failed with code ${result.code}")
        }
        JSONObject(raw.ifBlank { "{}" })
    }

    private fun buildRootCommand(binaryPath: String, args: List<String>): String {
        val quotedArgs = args.joinToString(" ") { shQuote(it) }
        return buildString {
            append("chmod 700 ")
            append(shQuote(binaryPath))
            append(" 2>/dev/null || true\n")
            append("exec ")
            append(shQuote(binaryPath))
            if (quotedArgs.isNotBlank()) {
                append(' ')
                append(quotedArgs)
            }
        }
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
