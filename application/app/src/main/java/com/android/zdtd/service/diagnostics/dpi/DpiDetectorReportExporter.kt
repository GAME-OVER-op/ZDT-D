package com.android.zdtd.service.diagnostics.dpi

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import androidx.core.content.FileProvider
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class DpiDetectorReportCheck(
  val name: String,
  val status: String,
  val detail: String,
  val value: String,
  val sizeLabel: String,
)

internal data class DpiDetectorReportProbe(
  val key: String,
  val title: String,
  val target: String,
  val sizeLabel: String,
  val technical: Map<String, String>,
  val checks: List<DpiDetectorReportCheck>,
  val diagnosis: String,
  val status: String,
  val detail: String,
)

internal data class DpiDetectorReportStage(
  val id: String,
  val title: String,
  val description: String,
  val status: String,
  val detail: String,
  val plannedTotal: Int,
  val diagnosis: String,
  val probes: List<DpiDetectorReportProbe>,
)

internal data class DpiDetectorReportData(
  val startedAtMs: Long,
  val finishedAtMs: Long,
  val summaryStatus: String,
  val summaryDetail: String,
  val stages: List<DpiDetectorReportStage>,
)

internal data class DpiDetectorReportBundle(
  val directory: File,
  val archive: File,
  val reportText: File,
  val reportJson: File,
  val rawEvents: File,
  val folderName: String,
)

internal object DpiDetectorReportExporter {
  private const val SHARED_ROOT = "/storage/emulated/0/ZDT-D_Files/Dpi_detector"
  private val fileTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US).withZone(ZoneOffset.UTC)
  private val displayTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

  fun createBundle(
    context: Context,
    data: DpiDetectorReportData,
    rawEventLines: List<String>,
  ): DpiDetectorReportBundle {
    cleanupOldBundles(context)
    val timestamp = fileTimestampFormatter.format(Instant.ofEpochMilli(data.finishedAtMs.coerceAtLeast(System.currentTimeMillis())))
    val folderName = "dpi_detector_$timestamp"
    val directory = File(context.cacheDir, "dpi-detector-reports/$folderName").apply {
      deleteRecursively()
      mkdirs()
    }
    val reportJson = File(directory, "report.json")
    val reportText = File(directory, "report.txt")
    val rawEvents = File(directory, "raw_events.ndjson")
    val archive = File(directory, "$folderName.zip")

    reportJson.writeText(buildJson(data).toString(2), Charsets.UTF_8)
    reportText.writeText(buildText(data), Charsets.UTF_8)
    rawEvents.writeText(rawEventLines.joinToString(separator = "\n", postfix = if (rawEventLines.isEmpty()) "" else "\n"), Charsets.UTF_8)
    createZip(archive, listOf(reportText, reportJson, rawEvents))

    return DpiDetectorReportBundle(
      directory = directory,
      archive = archive,
      reportText = reportText,
      reportJson = reportJson,
      rawEvents = rawEvents,
      folderName = folderName,
    )
  }

  fun saveToSharedStorage(context: Context, bundle: DpiDetectorReportBundle): Result<String> = runCatching {
    val destination = "$SHARED_ROOT/${bundle.folderName}"
    val sourceFiles = listOf(bundle.reportText, bundle.reportJson, bundle.rawEvents, bundle.archive)
    val copyCommands = sourceFiles.joinToString("\n") { file ->
      "cp -f ${shellQuote(file.absolutePath)} ${shellQuote("$destination/${file.name}")}"
    }
    val script = """
      set -e
      mkdir -p ${shellQuote(destination)}
      $copyCommands
      chmod 0664 ${shellQuote("$destination/report.txt")} ${shellQuote("$destination/report.json")} ${shellQuote("$destination/raw_events.ndjson")} ${shellQuote("$destination/${bundle.archive.name}")}
      chown 1023:1023 ${shellQuote(destination)} ${shellQuote("$destination/report.txt")} ${shellQuote("$destination/report.json")} ${shellQuote("$destination/raw_events.ndjson")} ${shellQuote("$destination/${bundle.archive.name}")} 2>/dev/null || true
    """.trimIndent()
    val result = RootConfigManager(context).execRootSh(script)
    check(result.isSuccess) {
      result.err.joinToString("\n").ifBlank { result.out.joinToString("\n").ifBlank { "Unable to save DPI Detector report" } }
    }
    val savedFiles = sourceFiles.map { "$destination/${it.name}" }.toTypedArray()
    MediaScannerConnection.scanFile(context, savedFiles, null, null)
    destination
  }

  fun shareArchive(context: Context, bundle: DpiDetectorReportBundle): Result<Unit> = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", bundle.archive)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "application/zip"
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, bundle.archive.name)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
  }

  private fun buildJson(data: DpiDetectorReportData): JSONObject = JSONObject().apply {
    put("schema_version", 1)
    put("generated_at", displayTimestamp(data.finishedAtMs))
    put("started_at", displayTimestamp(data.startedAtMs))
    put("finished_at", displayTimestamp(data.finishedAtMs))
    put("duration_ms", (data.finishedAtMs - data.startedAtMs).coerceAtLeast(0L))
    put("app", JSONObject().apply {
      put("package", BuildConfig.APPLICATION_ID)
      put("version_name", BuildConfig.VERSION_NAME)
      put("version_code", BuildConfig.VERSION_CODE)
    })
    put("device", JSONObject().apply {
      put("manufacturer", Build.MANUFACTURER)
      put("model", Build.MODEL)
      put("device", Build.DEVICE)
      put("android_release", Build.VERSION.RELEASE)
      put("sdk", Build.VERSION.SDK_INT)
      put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
    })
    put("summary", JSONObject().apply {
      put("status", data.summaryStatus)
      put("detail", parseJsonOrString(data.summaryDetail))
    })
    put("stages", JSONArray().apply {
      data.stages.forEach { stage ->
        put(JSONObject().apply {
          put("id", stage.id)
          put("title", stage.title)
          put("description", stage.description)
          put("status", stage.status)
          put("detail", stage.detail)
          put("planned_total", stage.plannedTotal)
          put("diagnosis", stage.diagnosis)
          put("probes", JSONArray().apply {
            stage.probes.forEach { probe ->
              put(JSONObject().apply {
                put("key", probe.key)
                put("title", probe.title)
                put("target", probe.target)
                put("size_label", probe.sizeLabel)
                put("status", probe.status)
                put("detail", probe.detail)
                put("diagnosis", probe.diagnosis)
                put("technical", JSONObject(probe.technical))
                put("checks", JSONArray().apply {
                  probe.checks.forEach { check ->
                    put(JSONObject().apply {
                      put("name", check.name)
                      put("status", check.status)
                      put("detail", check.detail)
                      put("value", check.value)
                      put("size_label", check.sizeLabel)
                    })
                  }
                })
              })
            }
          })
        })
      }
    })
  }

  private fun buildText(data: DpiDetectorReportData): String = buildString {
    appendLine("ZDT-D DPI Detector report")
    appendLine("=========================")
    appendLine("Generated: ${displayTimestamp(data.finishedAtMs)}")
    appendLine("Started: ${displayTimestamp(data.startedAtMs)}")
    appendLine("Finished: ${displayTimestamp(data.finishedAtMs)}")
    appendLine("Duration: ${formatDuration((data.finishedAtMs - data.startedAtMs).coerceAtLeast(0L))}")
    appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
    appendLine()
    appendLine("Overall status: ${data.summaryStatus}")
    if (data.summaryDetail.isNotBlank()) appendLine("Summary data: ${data.summaryDetail}")

    data.stages.forEachIndexed { index, stage ->
      appendLine()
      appendLine("${index + 1}. ${stage.title} [${stage.status}]")
      appendLine("   ID: ${stage.id}")
      if (stage.detail.isNotBlank()) appendLine("   Detail: ${stage.detail}")
      if (stage.diagnosis.isNotBlank()) appendLine("   Diagnosis: ${stage.diagnosis}")
      appendLine("   Probes: ${stage.probes.size}${if (stage.plannedTotal > 0) "/${stage.plannedTotal}" else ""}")
      stage.probes.forEachIndexed { probeIndex, probe ->
        appendLine("   ${probeIndex + 1}) ${probe.title.ifBlank { probe.key }} [${probe.status}]")
        if (probe.target.isNotBlank()) appendLine("      Target: ${probe.target}")
        if (probe.sizeLabel.isNotBlank()) appendLine("      Size: ${probe.sizeLabel}")
        if (probe.detail.isNotBlank()) appendLine("      Detail: ${probe.detail}")
        if (probe.diagnosis.isNotBlank()) appendLine("      Diagnosis: ${probe.diagnosis}")
        probe.technical.forEach { (key, value) -> appendLine("      $key: $value") }
        probe.checks.forEach { check ->
          append("      - ${check.name.ifBlank { "check" }} [${check.status}]")
          if (check.value.isNotBlank()) append(" value=${check.value}")
          if (check.sizeLabel.isNotBlank()) append(" size=${check.sizeLabel}")
          if (check.detail.isNotBlank()) append(" — ${check.detail}")
          appendLine()
        }
      }
    }
  }

  private fun createZip(archive: File, files: List<File>) {
    ZipOutputStream(FileOutputStream(archive)).use { zip ->
      files.forEach { file ->
        zip.putNextEntry(ZipEntry(file.name).apply { time = file.lastModified() })
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
      }
    }
  }

  private fun parseJsonOrString(value: String): Any {
    if (value.isBlank()) return ""
    return runCatching { JSONObject(value) as Any }
      .recoverCatching { JSONArray(value) as Any }
      .getOrDefault(value)
  }

  private fun displayTimestamp(value: Long): String = if (value > 0L) {
    displayTimestampFormatter.format(Instant.ofEpochMilli(value))
  } else {
    "unknown"
  }

  private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000L
    val minutes = seconds / 60L
    return "%02d:%02d".format(Locale.US, minutes, seconds % 60L)
  }

  private fun cleanupOldBundles(context: Context) {
    val root = File(context.cacheDir, "dpi-detector-reports")
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
  }

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
