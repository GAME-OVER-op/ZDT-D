package com.android.zdtd.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import java.io.File

class RootConfigManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zdtd_panel", Context.MODE_PRIVATE)

    // ZDT-D API token file (Magisk module). The daemon generates this token and requires it for /api endpoints.
    private val defaultPath = "/data/adb/modules/ZDT-D/api/token"

    /**
     * Cached daemon state.
     *
     * We keep it purely for UX (Quick Settings tile) so the tile can reflect the
     * last-known state without doing background polling.
     */
    fun getCachedServiceOn(): Boolean = prefs.getBoolean("service_on", false)

    fun setCachedServiceOn(on: Boolean) {
        prefs.edit().putBoolean("service_on", on).apply()
    }

    fun getConfigPath(): String = prefs.getString("config_path", defaultPath) ?: defaultPath

    fun setConfigPath(path: String) {
        prefs.edit().putString("config_path", path.trim()).apply()
    }

    // ----- App update settings (GitHub) -----

    fun isAppUpdateCheckEnabled(): Boolean = prefs.getBoolean("app_update_check_enabled", true)

    fun setAppUpdateCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("app_update_check_enabled", enabled).apply()
    }

    // ----- Daemon status notification (app-owned) -----

    fun isDaemonStatusNotificationEnabled(): Boolean =
        prefs.getBoolean("daemon_status_notification_enabled", false)

    fun setDaemonStatusNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("daemon_status_notification_enabled", enabled).apply()
    }

    fun getAppUpdateLastCheckTs(): Long = prefs.getLong("app_update_last_check_ts", 0L)

    fun setAppUpdateLastCheckTs(ts: Long) {
        prefs.edit().putLong("app_update_last_check_ts", ts).apply()
    }

    fun getGitHubEtagLatestRelease(): String? = prefs.getString("gh_etag_latest_release", null)
    fun setGitHubEtagLatestRelease(etag: String?) {
        prefs.edit().putString("gh_etag_latest_release", etag).apply()
    }

    fun getGitHubEtagModuleProp(): String? = prefs.getString("gh_etag_module_prop", null)
    fun setGitHubEtagModuleProp(etag: String?) {
        prefs.edit().putString("gh_etag_module_prop", etag).apply()
    }

    fun getCachedLatestReleaseTag(): String? = prefs.getString("gh_latest_tag", null)
    fun setCachedLatestReleaseTag(tag: String?) {
        prefs.edit().putString("gh_latest_tag", tag).apply()
    }

    fun getCachedLatestReleaseHtmlUrl(): String? = prefs.getString("gh_latest_html_url", null)
    fun setCachedLatestReleaseHtmlUrl(url: String?) {
        prefs.edit().putString("gh_latest_html_url", url).apply()
    }

    fun getCachedRemoteVersion(): String? = prefs.getString("gh_remote_version", null)
    fun setCachedRemoteVersion(version: String?) {
        prefs.edit().putString("gh_remote_version", version).apply()
    }

    fun getCachedRemoteVersionCode(): Int = prefs.getInt("gh_remote_version_code", 0)
    fun setCachedRemoteVersionCode(code: Int) {
        prefs.edit().putInt("gh_remote_version_code", code).apply()
    }

    
    // ----- App update cached result (persisted banner) -----

    fun isCachedAppUpdateAvailable(): Boolean = prefs.getBoolean("app_update_available", false)

    fun setCachedAppUpdateAvailable(available: Boolean) {
        prefs.edit().putBoolean("app_update_available", available).apply()
    }

    fun getCachedAppUpdateUrgent(): Boolean = prefs.getBoolean("app_update_urgent", false)

    fun setCachedAppUpdateUrgent(urgent: Boolean) {
        prefs.edit().putBoolean("app_update_urgent", urgent).apply()
    }

    fun getCachedAppUpdateReleaseTag(): String? = prefs.getString("app_update_release_tag", null)

    fun setCachedAppUpdateReleaseTag(tag: String?) {
        prefs.edit().putString("app_update_release_tag", tag).apply()
    }

    fun getCachedAppUpdateReleaseHtmlUrl(): String? = prefs.getString("app_update_release_html_url", null)

    fun setCachedAppUpdateReleaseHtmlUrl(url: String?) {
        prefs.edit().putString("app_update_release_html_url", url).apply()
    }

    fun getCachedAppUpdateRemoteVersion(): String? = prefs.getString("app_update_remote_version", null)

    fun setCachedAppUpdateRemoteVersion(version: String?) {
        prefs.edit().putString("app_update_remote_version", version).apply()
    }

    fun getCachedAppUpdateRemoteVersionCode(): Int = prefs.getInt("app_update_remote_code", 0)

    fun setCachedAppUpdateRemoteVersionCode(code: Int) {
        prefs.edit().putInt("app_update_remote_code", code).apply()
    }

    fun getCachedAppUpdateDownloadUrl(): String? = prefs.getString("app_update_download_url", null)

    fun setCachedAppUpdateDownloadUrl(url: String?) {
        prefs.edit().putString("app_update_download_url", url).apply()
    }

    fun getCachedAppUpdateFoundTs(): Long = prefs.getLong("app_update_found_ts", 0L)

    fun setCachedAppUpdateFoundTs(ts: Long) {
        prefs.edit().putLong("app_update_found_ts", ts).apply()
    }

    fun clearCachedAppUpdate() {
        prefs.edit()
            .remove("app_update_available")
            .remove("app_update_urgent")
            .remove("app_update_release_tag")
            .remove("app_update_release_html_url")
            .remove("app_update_remote_version")
            .remove("app_update_remote_code")
            .remove("app_update_download_url")
            .remove("app_update_found_ts")
            .apply()
    }
// ----- Setup flags (app-local) -----

    fun isWelcomeAccepted(): Boolean = prefs.getBoolean("welcome_accepted", false)

    fun setWelcomeAccepted(accepted: Boolean) {
        // Use commit() so the flag is persisted immediately (important when user reboots right after install).
        prefs.edit().putBoolean("welcome_accepted", accepted).commit()
    }

    /**
     * Setup completion flag.
     *
     * We use it to avoid showing the installer flow again after a successful module installation
     * and device reboot. The actual source of truth remains the module files under /data/adb.
     */
    fun isSetupDone(): Boolean = prefs.getBoolean("setup_done", false)

    fun setSetupDone(done: Boolean) {
        // Use commit() so the flag is persisted immediately (important when user reboots right after install).
        prefs.edit().putBoolean("setup_done", done).commit()
    }


    // ----- Settings migration (after module update) -----

    /**
     * Stores the update-id for which settings migration has been successfully completed.
     * The id is derived from the staged module update under /data/adb/modules_update/ZDT-D.
     */
    fun getMigrationDoneUpdateId(): String? = prefs.getString("migration_done_update_id", null)

    fun setMigrationDoneUpdateId(updateId: String) {
        // commit() so we don't lose it if the user reboots right after migration.
        prefs.edit().putString("migration_done_update_id", updateId).commit()
    }

    fun clearMigrationDoneUpdateId() {
        prefs.edit().remove("migration_done_update_id").commit()
    }

    // ----- Module / installer helpers (root) -----

    enum class ModuleInstaller {
        MAGISK,
        KSU,
        APATCH,
        UNKNOWN,
    }

    fun hasMagiskCli(): Boolean {
        // 'command -v' is a shell builtin, so run via sh -c.
        val r = Shell.cmd("sh -c 'command -v magisk >/dev/null 2>&1'").exec()
        return r.isSuccess
    }

    fun hasKsuCli(): Boolean {
        // KernelSU / Next / forks typically expose ksud under /data/adb/ksu/bin.
        val r1 = Shell.cmd("sh -c 'test -x /data/adb/ksu/bin/ksud'").exec()
        if (r1.isSuccess) return true
        val r2 = Shell.cmd("sh -c 'command -v ksud >/dev/null 2>&1'").exec()
        return r2.isSuccess
    }

    fun hasApatchCli(): Boolean {
        // APatch exposes apd under /data/adb/ap/bin.
        val r1 = Shell.cmd("sh -c 'test -x /data/adb/ap/bin/apd'").exec()
        if (r1.isSuccess) return true
        val r2 = Shell.cmd("sh -c 'command -v apd >/dev/null 2>&1'").exec()
        return r2.isSuccess
    }

    fun detectModuleInstaller(): ModuleInstaller {
        return when {
            hasMagiskCli() -> ModuleInstaller.MAGISK
            hasKsuCli() -> ModuleInstaller.KSU
            hasApatchCli() -> ModuleInstaller.APATCH
            else -> ModuleInstaller.UNKNOWN
        }
    }

    fun ksuPath(): String? {
        val r = Shell.cmd("sh -c 'test -x /data/adb/ksu/bin/ksud'").exec()
        return if (r.isSuccess) "/data/adb/ksu/bin/ksud" else "ksud"
    }

    fun apatchPath(): String? {
        val r = Shell.cmd("sh -c 'test -x /data/adb/ap/bin/apd'").exec()
        return if (r.isSuccess) "/data/adb/ap/bin/apd" else "apd"
    }

    fun isModuleInstalled(): Boolean {
        val id = "ZDT-D"
        val r = Shell.cmd("sh -c 'test -f /data/adb/modules/${id}/module.prop || test -f /data/adb/modules_update/${id}/module.prop'").exec()
        return r.isSuccess
    }

    fun hasOldModuleVersionWebroot(): Boolean {
        val id = "ZDT-D"
        val r = Shell.cmd("sh -c 'test -d /data/adb/modules/${id}/webroot || test -d /data/adb/modules_update/${id}/webroot'").exec()
        return r.isSuccess
    }

    /** True when a module update is staged and requires reboot to apply. */
    fun isModuleUpdatePending(): Boolean {
        val id = "ZDT-D"
        val r = Shell.cmd("sh -c 'test -f /data/adb/modules/${id}/update || test -f /data/adb/modules_update/${id}/module.prop'").exec()
        return r.isSuccess
    }

    /**
     * Legacy / suspicious module layout detection.
     * Old versions used a /system folder; supported versions do not.
     */
    fun hasLegacySystemDir(): Boolean {
        val id = "ZDT-D"
        val r = Shell.cmd("sh -c 'test -d /data/adb/modules/${id}/system || test -d /data/adb/modules_update/${id}/system'").exec()
        return r.isSuccess
    }

    fun execRoot(cmd: String): Shell.Result {
        return Shell.cmd(cmd).exec()
    }

    /** Execute a shell script via `sh -c` (avoids nested quoting issues). */
    fun execRootSh(script: String): Shell.Result {
        return Shell.cmd("sh", "-c", script).exec()
    }


    private fun candidateConfigPaths(): List<String> {
        val p = getConfigPath()
        return listOf(
            p,
            "/data/adb/modules/ZDT-D/api/token",
            "/data/adb/modules/ZDT-D/api/token",
            "/data/adb/modules/zdtd/api/token"
        ).distinct()
    }

    // Triggers Magisk prompt on first call.
    fun testRoot(): Boolean {
        val r = Shell.cmd("id -u").exec()
        return r.isSuccess && r.out.joinToString("\n").trim() == "0"
    }

    /**
     * Best-effort: reset libsu cached shell so Magisk can show the root prompt again.
     *
     * On some setups, after the user denies the initial prompt, libsu keeps a non-root
     * shell instance cached. Subsequent [testRoot] calls then run without prompting.
     */
    fun resetRootShell() {
        // 1) Close an existing global shell instance if we can.
        runCatching {
            val shell = Shell.getShell()
            // Use reflection for maximum compatibility across libsu versions.
            shell.javaClass.methods
                .firstOrNull { it.name == "close" && it.parameterTypes.isEmpty() }
                ?.invoke(shell)
        }

        // 2) Also try a static Shell.close() if present.
        runCatching {
            val m = Shell::class.java.methods.firstOrNull {
                it.name == "close" && it.parameterTypes.isEmpty() && java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            m?.invoke(null)
        }
    }

    private fun shQuote(s: String): String {
        // Safe single-quote for sh: ' -> '\''
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private fun readFileAt(path: String): String {
        val cmd = "cat ${shQuote(path)} 2>/dev/null || true"
        val r = Shell.cmd(cmd).exec()
        return r.out.joinToString("\n")
    }

    /** Root-only: read a small text file fully (best-effort). */
    fun readTextFile(path: String): String {
        return readFileAt(path)
    }

    /** Root-only: write a text file (creates parent dir). */
    fun writeTextFile(path: String, content: String): Boolean {
        val parent = runCatching { File(path).parent }.getOrNull().orEmpty()
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = buildString {
            if (parent.isNotBlank()) {
                append("mkdir -p ")
                append(shQuote(parent))
                append(" 2>/dev/null; ")
            }
            append("echo ")
            append(shQuote(b64))
            append(" | (base64 -d 2>/dev/null || /system/bin/toybox base64 -d 2>/dev/null) > ")
            append(shQuote(path))
            append(" 2>/dev/null")
        }
        val r = Shell.cmd(cmd).exec()
        return r.isSuccess
    }

    /** Root-only: read {"enabled": bool} from a json file, returns null if missing/invalid. */
    fun readEnabledFlag(jsonPath: String): Boolean? {
        val raw = readFileAt(jsonPath).trim()
        if (raw.isBlank()) return null
        return runCatching {
            JSONObject(raw).optBoolean("enabled", false)
        }.getOrNull()
    }

    /** Root-only: write {"enabled": bool} to a json file. */
    fun writeEnabledFlag(jsonPath: String, enabled: Boolean): Boolean {
        val obj = JSONObject().put("enabled", enabled)
        // Keep it simple and predictable.
        val text = obj.toString()
        return writeTextFile(jsonPath, text)
    }

    /**
     * Root-only helper: read last [lines] lines of a file (fallback to cat).
     * Used for daemon logs under /data/adb/modules/...
     */
    fun readLogTail(path: String, lines: Int = 200): String {
        val n = lines.coerceIn(20, 2000)
        val cmd = "(tail -n $n ${shQuote(path)} 2>/dev/null || cat ${shQuote(path)} 2>/dev/null || true)"
        val r = Shell.cmd(cmd).exec()
        return r.out.joinToString("\n")
    }

    // Reads ZDT-D API token from the module token file (root required).
    fun readApiToken(): String {
        // 1) Probe a few likely locations.
        for (path in candidateConfigPaths()) {
            val raw = readFileAt(path)
            val t = extractToken(raw)
            if (t.isNotEmpty()) {
                if (path != getConfigPath()) setConfigPath(path)
                return t
            }
        }

        // 2) Try discover module folder by name.
        try {
            val r = Shell.cmd("ls -1 /data/adb/modules 2>/dev/null | grep -i 'zdt' | head -n 8").exec()
            val mods = r.out.joinToString("\n")
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            for (mod in mods) {
                val path = "/data/adb/modules/$mod/api/token"
                val raw = readFileAt(path)
                val t = extractToken(raw)
                if (t.isNotEmpty()) {
                    setConfigPath(path)
                    return t
                }
            }
        } catch (_: Throwable) {
            // ignore
        }

        return ""
    }

    private fun extractToken(raw: String): String {
        if (raw.isBlank()) return ""
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        if (line.length > 256) return ""
        return line
    }

    // ----- Root-proxy HTTP (fallback) -----

    fun proxyGet(path: String): String = proxyRequest("GET", path, "")

    fun proxyPost(path: String, body: String): String = proxyRequest("POST", path, body)

    fun proxyPut(path: String, body: String): String = proxyRequest("PUT", path, body)

    fun proxyDelete(path: String, body: String = ""): String = proxyRequest("DELETE", path, body)


/**
 * Root-only multipart upload helper for strategic files.
 * Writes bytes to /data/local/tmp and uploads with curl -F.
 *
 * Returns JSON: { code, body, error? }
 */
fun proxyUploadMultipart(path: String, filename: String, bytes: ByteArray): JSONObject {
    val token = readApiToken()
    val url = "http://127.0.0.1:1006$path"

    val res = JSONObject()
    if (token.isBlank()) {
        res.put("code", 0)
        res.put("body", "")
        res.put("error", "token_missing")
        return res
    }

    val hdr1 = shQuote("X-Api-Key: $token")
    val hdr2 = shQuote("Authorization: Bearer $token")

    val tmp = "/data/local/tmp/zdtd_upload_${System.currentTimeMillis()}"
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

    // Prepare file, upload, cleanup. We force filename via curl's ;filename=.
    val form = shQuote("file=@$tmp;filename=$filename")
    val tmpQ = shQuote(tmp)
    val urlQ = shQuote(url)

    // Run a small shell script as `sh -c <script>` (no outer quoting),
    // so nested quotes produced by shQuote(...) won't break parsing.
    val script =
        "echo ${shQuote(b64)} " +
            "| (base64 -d 2>/dev/null || /system/bin/toybox base64 -d 2>/dev/null) > $tmpQ && " +
            "(" +
            "curl -s -m 20 -H $hdr1 -H $hdr2 -F $form -o - -w '\\n__HTTP__%{http_code}' $urlQ" +
            " || /data/data/com.termux/files/usr/bin/curl -s -m 20 -H $hdr1 -H $hdr2 -F $form -o - -w '\\n__HTTP__%{http_code}' $urlQ" +
            ")" +
            "; rm -f $tmpQ"

    val r = Shell.cmd("sh", "-c", script).exec()
    val out = (r.out + r.err).joinToString("\n")
    val idx = out.lastIndexOf("__HTTP__")
    if (idx >= 0) {
        val body = out.substring(0, idx).trimEnd()
        val codeStr = out.substring(idx + "__HTTP__".length).trim()
        val code = codeStr.toIntOrNull() ?: 0
        res.put("code", code)
        res.put("body", body)
        if (code == 0) res.put("error", "curl_failed")
    } else {
        res.put("code", 0)
        res.put("body", out)
        res.put("error", "bad_response")
    }
    return res
}


    private fun proxyRequest(method: String, path: String, body: String): String {
        val token = readApiToken()
        val url = "http://127.0.0.1:1006$path"

        val res = JSONObject()
        if (token.isBlank()) {
            res.put("code", 0)
            res.put("body", "")
            res.put("error", "token_missing")
            return res.toString()
        }

        val hdr1 = shQuote("X-Api-Key: $token")
        val hdr2 = shQuote("Authorization: Bearer $token")

        val cmd = if (method.uppercase() == "GET") {
            "(curl -s -m 3 -H $hdr1 -H $hdr2 -o - -w '\\n__HTTP__%{http_code}' ${shQuote(url)}" +
                " || /data/data/com.termux/files/usr/bin/curl -s -m 3 -H $hdr1 -H $hdr2 -o - -w '\\n__HTTP__%{http_code}' ${shQuote(url)})"
        } else {
            val b64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "echo ${shQuote(b64)} | (base64 -d 2>/dev/null || /system/bin/toybox base64 -d 2>/dev/null) | " +
                "(curl -s -m 3 -H $hdr1 -H $hdr2 -H ${shQuote("Content-Type: application/json")} -X ${method.uppercase()} --data-binary @- -o - -w '\\n__HTTP__%{http_code}' ${shQuote(url)}" +
                " || /data/data/com.termux/files/usr/bin/curl -s -m 3 -H $hdr1 -H $hdr2 -H ${shQuote("Content-Type: application/json")} -X ${method.uppercase()} --data-binary @- -o - -w '\\n__HTTP__%{http_code}' ${shQuote(url)})"
        }

        val r = Shell.cmd(cmd).exec()
        val out = r.out.joinToString("\n")

        val marker = "\n__HTTP__"
        val idx = out.lastIndexOf(marker)
        val bodyText: String
        val code: Int
        if (idx >= 0) {
            bodyText = out.substring(0, idx)
            code = out.substring(idx + marker.length).trim().toIntOrNull() ?: 0
        } else {
            bodyText = out
            code = if (r.isSuccess) 200 else 0
        }

        res.put("code", code)
        res.put("body", bodyText)
        if (!r.isSuccess && r.err.isNotEmpty()) {
            res.put("shell_error", r.err.joinToString("\n"))
        }
        return res.toString()
    }
}