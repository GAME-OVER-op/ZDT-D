package com.android.zdtd.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Root-level process control for restarting the local ZDT-D daemon. */
class DaemonProcessControl(
  private val root: RootConfigManager,
) {
  private val moduleDir = "/data/adb/modules/ZDT-D"
  private val moduleUpdateDir = "/data/adb/modules_update/ZDT-D"
  private val lowerModuleDir = "/data/adb/modules/zdtd"
  private val daemonBinary = "$moduleDir/bin/zdtd"
  private val daemonLog = "$moduleDir/log/deamon.log"
  private val scanEndMarker = "__ZDTD_SCAN_END__"
  private val rootGuard = "[ \"\$(id -u)\" = 0 ] || exit 97; "

  /**
   * `/data/adb` is unreadable for the app sandbox, so existence must be checked
   * through the root shell rather than `File.exists()`.
   */
  private fun daemonBinaryExists(): Boolean {
    val r = runCatching { root.execRootSh(rootGuard + "test -x ${shQuote(daemonBinary)}") }.getOrElse {
      throw IllegalStateException("root probe failed for $daemonBinary", it)
    }
    return when (r.code) {
      0 -> true
      1 -> false
      else -> throw IllegalStateException("root probe failed for $daemonBinary (code=${r.code})")
    }
  }

  private suspend fun isDaemonAlive(): Boolean = withContext(Dispatchers.IO) {
    processIds().isNotEmpty()
  }

  /**
   * Replaces a live-but-unreachable daemon and starts exactly one replacement.
   * The daemon applies its saved runtime state and can adopt an existing runtime.
   */
  suspend fun restart(): Boolean = withContext(Dispatchers.IO) {
    if (!daemonBinaryExists()) return@withContext false

    if (isDaemonAlive()) {
      signalDaemonPids("TERM")
      delay(700)
      if (isDaemonAlive()) {
        signalDaemonPids("KILL")
        delay(400)
      }
      check(processIds().isEmpty()) { "old daemon survived SIGKILL" }
    }

    val start = root.execRootSh(
      buildString {
        append(rootGuard)
        append("mkdir -p ")
        append(shQuote("$moduleDir/log"))
        append("; cd ")
        append(shQuote(moduleDir))
        append(" 2>/dev/null; export PATH=")
        append(shQuote("$moduleDir/bin:/system/bin:/system/xbin"))
        append(":\$PATH; setsid ")
        append(shQuote(daemonBinary))
        append(" >>")
        append(shQuote(daemonLog))
        append(" 2>&1 </dev/null & echo started")
      }
    )
    if (!start.isSuccess) {
      throw IllegalStateException("daemon launch command failed (code=${start.code}): ${start.err.joinToString(" ")}")
    }

    repeat(10) {
      if (isDaemonAlive()) return@withContext true
      delay(200)
    }
    false
  }

  /** Returns daemon PIDs whose `/proc/<pid>/exe` matches an owned module path. */
  private fun processIds(): List<String> {
    val r = runCatching { root.execRootSh(pidsScript()) }.getOrElse {
      throw IllegalStateException("root process scan failed", it)
    }
    if (!r.isSuccess) {
      throw IllegalStateException("root process scan failed (code=${r.code}): ${r.err.joinToString(" ")}")
    }
    val lines = r.out.map { it.trim() }
    check(lines.any { it == scanEndMarker }) { "root process scan did not complete" }
    return lines.filter { it.isNotEmpty() && it.all(Char::isDigit) }
  }

  private fun signalDaemonPids(signal: String) {
    val targets = processIds()
    if (targets.isEmpty()) return

    val failed = ArrayList<String>()
    for (pid in targets) {
      val r = runCatching { root.execRootSh(rootGuard + "kill -$signal $pid") }.getOrNull()
      if (r?.isSuccess != true) failed += pid
    }
    if (failed.isEmpty()) return

    val aliveNow = processIds()
    val survived = failed.filter { it in aliveNow }
    check(survived.isEmpty()) {
      "failed to signal pid(s) ${survived.joinToString(",")} with SIG$signal"
    }
  }

  /** Reads every process symlink with one `ls` instead of spawning `readlink` per PID. */
  private fun pidsScript(): String {
    val daemonPaths = listOf(
      "$moduleDir/bin/zdtd",
      "$moduleUpdateDir/bin/zdtd",
      "$lowerModuleDir/bin/zdtd",
    )
    return buildString {
      append(rootGuard)
      append("ls -l /proc/[0-9]*/exe 2>/dev/null | while IFS= read -r line; do ")
      append("exe=\${line#* -> }; ")
      append("[ \"\$exe\" != \"\$line\" ] || continue; ")
      append("source=\${line%% -> *}; ")
      append("pid=\${source%/exe}; ")
      append("pid=\${pid##*/}; ")
      append("exe=\${exe% (deleted)}; ")
      append("case \"\$exe\" in ")
      append(daemonPaths.joinToString("|"))
      append(") echo \"\$pid\";; esac; ")
      append("done; echo \"$scanEndMarker\"")
    }
  }

  private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
