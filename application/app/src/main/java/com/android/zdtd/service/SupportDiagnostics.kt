package com.android.zdtd.service

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a shareable text report for support. Root-only by design: every
 * /data/adb read goes through [RootConfigManager]. Nothing is uploaded, and
 * all output is scrubbed of tokens and IP addresses before it reaches a file.
 */
object SupportDiagnostics {

  const val SECTION_DEVICE = "device"
  const val SECTION_DAEMON = "daemon"
  const val SECTION_NETWORK = "network"
  const val SECTION_LOGS = "logs"
  const val SECTION_MODULE = "module"

  val DEFAULT_SECTIONS = setOf(
    SECTION_DEVICE,
    SECTION_DAEMON,
    SECTION_NETWORK,
    SECTION_LOGS,
    SECTION_MODULE,
  )

  data class Result(
    val file: File,
    val sizeBytes: Long,
    val generatedAtMs: Long,
  )

  suspend fun collect(
    context: Context,
    sections: Set<String>,
    onStage: (String, Int) -> Unit,
  ): Result = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val root = RootConfigManager(appContext)
    val generatedAtMs = System.currentTimeMillis()
    val report = StringBuilder()
    val selected = DEFAULT_SECTIONS.filter { it in sections }

    report.appendLine("ZDT-D support report")
    report.appendLine("====================")
    report.appendLine("Generated: ${formatTime(generatedAtMs)}")
    report.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) / ${BuildConfig.DISTRIBUTION_MODE}")
    report.appendLine()

    var completed = 0
    fun runStage(key: String, block: StringBuilder.() -> Unit) {
      onStage(key, (completed * 100) / selected.size.coerceAtLeast(1))
      report.block()
      if (report.length > MAX_REPORT_CHARS) {
        report.setLength(MAX_REPORT_CHARS)
        report.appendLine()
        report.appendLine("[report truncated]")
      }
      completed += 1
      onStage(key, (completed * 100) / selected.size.coerceAtLeast(1))
    }

    if (SECTION_DEVICE in sections) {
      runStage(SECTION_DEVICE) {
        appendLine("=== DEVICE ===")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Product: ${Build.PRODUCT}")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()
        appendLine("--- uname / SELinux ---")
        appendLine(read(root, "uname -a 2>/dev/null; getenforce 2>/dev/null"))
        appendLine()
        appendLine("--- /system/build.prop ---")
        appendLine(read(root, "cat /system/build.prop 2>/dev/null", MAX_SECTION_CHARS))
      }
    }

    if (SECTION_DAEMON in sections) {
      runStage(SECTION_DAEMON) {
        appendLine("=== DAEMON ===")
        val tails = runCatching {
          root.readLogTailPair(
            "/data/adb/modules/ZDT-D/log/zdtd.log",
            "/data/adb/modules/ZDT-D/log/deamon.log",
            300,
          )
        }.getOrElse { "" to "" }
        appendLine("--- zdtd.log (tail 300) ---")
        appendLine(tails?.first.orEmpty())
        appendLine("--- deamon.log (tail 300) ---")
        appendLine(tails?.second.orEmpty())
        appendLine()
        appendLine("--- api/status.json ---")
        appendLine(read(root, "cat /data/adb/modules/ZDT-D/api/status.json 2>/dev/null"))
        appendLine()
        appendLine("--- relevant processes ---")
        appendLine(
          read(
            root,
            "ps -A 2>/dev/null | grep -E 'zdtd|nfqws|byedpi|tun2socks|sing-box|hysteria|mieru|tor|dnscrypt|tg-ws|mihomo|openvpn|amnezia' | grep -v grep",
          ),
        )
        appendLine()
        appendLine("--- last app crash ---")
        appendLine(CrashLogger.readLastCrash(appContext).orEmpty())
      }
    }

    if (SECTION_NETWORK in sections) {
      runStage(SECTION_NETWORK) {
        appendLine("=== NETWORK RULES (IP addresses removed) ===")
        appendLine("--- iptables nat ---")
        appendLine(read(root, "iptables -t nat -L -v -n 2>&1", MAX_SECTION_CHARS))
        appendLine("--- iptables mangle ---")
        appendLine(read(root, "iptables -t mangle -L -v -n 2>&1", MAX_SECTION_CHARS))
        appendLine("--- ip6tables nat ---")
        appendLine(read(root, "ip6tables -t nat -L -v -n 2>&1", MAX_SECTION_CHARS))
        appendLine("--- ip6tables mangle ---")
        appendLine(read(root, "ip6tables -t mangle -L -v -n 2>&1", MAX_SECTION_CHARS))
      }
    }

    if (SECTION_LOGS in sections) {
      runStage(SECTION_LOGS) {
        appendLine("=== PROGRAM LOGS (tail 60, max 40 files) ===")
        appendLine(
          read(
            root,
            """
              i=0
              find /data/adb/modules/ZDT-D/working_folder -type f -name '*.log' 2>/dev/null | head -n 40 | while IFS= read -r f; do
                i=${'$'}((i + 1))
                printf '\n### [%s] %s\n' "${'$'}i" "${'$'}f"
                tail -n 60 "${'$'}f" 2>/dev/null
              done
            """.trimIndent(),
            MAX_SECTION_CHARS * 2,
          ),
        )
      }
    }

    if (SECTION_MODULE in sections) {
      runStage(SECTION_MODULE) {
        appendLine("=== MODULE ===")
        appendLine("--- module.prop ---")
        appendLine(read(root, "cat /data/adb/modules/ZDT-D/module.prop 2>/dev/null"))
        appendLine()
        appendLine("--- module directory ---")
        appendLine(read(root, "ls -la /data/adb/modules/ZDT-D/ 2>/dev/null"))
        appendLine()
        appendLine("--- working folder sizes ---")
        appendLine(read(root, "du -sh /data/adb/modules/ZDT-D/working_folder/* 2>/dev/null | head -n 60"))
        appendLine()
        appendLine("--- storage ---")
        appendLine(read(root, "df -h /data 2>/dev/null"))
        appendLine()
        appendLine("--- root modules ---")
        appendLine(read(root, "ls -1 /data/adb/modules 2>/dev/null"))
      }
    }

    if (selected.isEmpty()) {
      report.appendLine("[no sections selected]")
    }

    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(generatedAtMs))
    val file = File(appContext.cacheDir, "zdt-support-$timestamp.txt")
    file.writeText(scrub(report.toString()).take(MAX_REPORT_CHARS), Charsets.UTF_8)
    Result(file, file.length(), generatedAtMs)
  }

  private const val MAX_SECTION_CHARS = 40_000
  private const val MAX_REPORT_CHARS = 180_000

  private fun read(root: RootConfigManager, command: String, limit: Int = MAX_SECTION_CHARS): String =
    runCatching { root.execRootSh(command).out.joinToString("\n") }
      .getOrDefault("")
      .take(limit)

  private fun formatTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(ms))

  /** Removes credentials and every IP address from the final text. */
  internal fun scrub(text: String): String {
    var value = text
    value = JWT.replace(value, "<redacted-token>")
    value = CREDENTIAL.replace(value, "$1<redacted>")
    value = URL_CREDENTIALS.replace(value, "http://<redacted>@")
    value = IPV4.replace(value, "<ip>")
    value = IPV6.replace(value, "<ip>")
    return value
  }

  private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
  private val CREDENTIAL = Regex(
    """(?i)(["']?(?:token|password|passwd|secret|authorization|private[_-]?key)["']?\s*[:=]\s*)["']?[^\s,;&"']+""",
  )
  private val URL_CREDENTIALS = Regex("""https?://[^/\s:@]+:[^@\s]+@""")
  private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
  private val IPV6 = Regex(
    """(?:\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{0,4}\b)|(?:\b(?:[0-9a-fA-F]{1,4})?::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{0,4}\b)""",
  )
}
