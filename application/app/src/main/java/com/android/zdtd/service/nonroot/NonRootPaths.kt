package com.android.zdtd.service.nonroot

import android.content.Context
import java.io.File

class NonRootPaths(context: Context) {
    val root: File = File(context.noBackupFilesDir, "nonroot")
    val bin: File = File(root, "bin")
    val api: File = File(root, "api")
    val logs: File = File(root, "logs")
    val staging: File = File(root, "staging")
    val runtime: File = File(root, "runtime")

    fun ensureBase() {
        listOf(root, bin, api, logs, staging, runtime).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) error("Unable to create ${dir.absolutePath}")
        }
    }
}
