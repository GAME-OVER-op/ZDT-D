package com.android.zdtd.service

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Minimal crash capture.
 *
 * Reason: on-device builds often don't have easy logcat access.
 * We store the last crash stacktrace in app-private storage so it can be shown
 * later (or pulled with root).
 */
object CrashLogger {

  private const val FILE_NAME = "last_crash.txt"

  @Volatile
  private var installed = false

  fun install(ctx: Context) {
    if (installed) return
    installed = true

    val appCtx = ctx.applicationContext
    val prev = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      runCatching {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("Thread: ${t.name}")
        pw.println("Exception: ${e::class.java.name}: ${e.message}")
        pw.println("--- stack ---")
        e.printStackTrace(pw)
        pw.flush()

        File(appCtx.filesDir, FILE_NAME).writeText(sw.toString())
      }
      // Delegate to the original handler to keep Android's default behavior.
      prev?.uncaughtException(t, e)
    }
  }

  fun readLastCrash(ctx: Context): String? {
    return runCatching {
      val f = File(ctx.applicationContext.filesDir, FILE_NAME)
      if (!f.exists()) return@runCatching null
      val txt = f.readText()
      txt.takeIf { it.isNotBlank() }
    }.getOrNull()
  }

  fun clearLastCrash(ctx: Context) {
    runCatching {
      File(ctx.applicationContext.filesDir, FILE_NAME).delete()
    }
  }
}
