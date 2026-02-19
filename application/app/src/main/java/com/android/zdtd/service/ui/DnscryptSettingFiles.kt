package com.android.zdtd.service.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

@Composable
fun DnscryptSettingFilesSection(
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  var files by remember { mutableStateOf<List<String>>(emptyList()) }
  var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
  var editable by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
  var limitBytes by remember { mutableStateOf(200_000L) }
  var listLoading by remember { mutableStateOf(true) }

  var expandedFile by remember { mutableStateOf<String?>(null) }
  var fileText by remember { mutableStateOf("") }
  var lastLoaded by remember { mutableStateOf("") }
  var fileLoading by remember { mutableStateOf(false) }
  var fileSaving by remember { mutableStateOf(false) }
  var fileLoadedOk by remember { mutableStateOf(false) }
  var fileTooLarge by remember { mutableStateOf(false) }
  var fileTooLargeSize by remember { mutableStateOf<Long?>(null) }

  val scope = rememberCoroutineScope()

  fun loadList() {
    listLoading = true
    actions.loadJsonData("/api/programs/dnscrypt/setting-files") { obj ->
      listLoading = false
      val arr = obj?.optJSONArray("files")
      files = arr?.toStringList().orEmpty()

      // Optional fields from newer daemon versions
      sizes = obj?.optJSONObject("sizes")?.toLongMap().orEmpty()
      editable = obj?.optJSONObject("editable")?.toBoolMap().orEmpty()
      val lim = obj?.optLong("limit", limitBytes) ?: limitBytes
      if (lim > 0) limitBytes = lim
    }
  }

  LaunchedEffect(Unit) {
    loadList()
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Additional setting files", style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(2.dp))
          Text(
            "Files from dnscrypt/setting (edit existing files only).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
        TextButton(
          onClick = { loadList() },
          enabled = !listLoading && !fileLoading && !fileSaving,
        ) {
          Text("Refresh")
        }
      }

      Spacer(Modifier.height(10.dp))

      if (listLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
      }

      if (!listLoading && files.isEmpty()) {
        Text(
          "No additional files found in dnscrypt/setting.",
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        return@Column
      }

      files.forEach { name ->
        val expanded = expandedFile == name
        val isEditable = editable[name] ?: true
        val sz = sizes[name]

        ElevatedCard(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        ) {
          Column(Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !fileLoading && !fileSaving) {
                  if (expanded) {
                    expandedFile = null
                  } else {
                    expandedFile = name
                    // Reset per-file state
                    fileText = ""
                    lastLoaded = ""
                    fileLoadedOk = false
                    fileTooLarge = false
                    fileTooLargeSize = null

                    // If daemon already told us this file is too large, don't even request the content.
                    if (!isEditable) {
                      fileTooLarge = true
                      fileTooLargeSize = sz
                      fileLoadedOk = false
                      fileLoading = false
                      return@clickable
                    }

                    fileLoading = true
                    val path = "/api/programs/dnscrypt/setting-files/${enc(name)}"
                    actions.loadJsonData(path) { obj ->
                      fileLoading = false
                      val ok = obj?.optBoolean("ok", false) ?: false
                      if (ok) {
                        val content = obj?.optString("content", "") ?: ""
                        fileText = content
                        lastLoaded = content
                        fileLoadedOk = true
                      } else {
                        val err = obj?.optString("error", "") ?: ""
                        if (err == "too_large") {
                          fileTooLarge = true
                          fileTooLargeSize = obj?.optLong("size")
                          val lim2 = obj?.optLong("limit", limitBytes) ?: limitBytes
                          if (lim2 > 0) limitBytes = lim2
                        }
                        fileLoadedOk = false
                      }
                    }
                  }
                }
                .padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                name,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              if (!isEditable) {
                Spacer(Modifier.width(8.dp))
                Text(
                  "TOO LARGE",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
              }
              Spacer(Modifier.width(8.dp))
              Text(
                if (expanded) "▲" else "▼",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              )
            }

            if (expanded) {
              Divider()
              Column(Modifier.padding(12.dp)) {
                if (fileLoading) {
                  LinearProgressIndicator(Modifier.fillMaxWidth())
                  Spacer(Modifier.height(10.dp))
                }

                if (fileTooLarge) {
                  val fullPath = "/data/adb/modules/ZDT-D/working_folder/dnscrypt/setting/$name"
                  val szText = fileTooLargeSize?.toString() ?: (sz?.toString() ?: "?")
                  Text(
                    "Editing is disabled for this file because it exceeds the size limit (${limitBytes} bytes).",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
                  )
                  Spacer(Modifier.height(6.dp))
                  Text(
                    "File: $fullPath\nSize: $szText bytes\nUse another editor to modify it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    fontFamily = FontFamily.Monospace,
                  )
                } else if (!fileLoadedOk) {
                  Text(
                    "Failed to load file content.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
                  )
                  Spacer(Modifier.height(6.dp))
                  Text(
                    "Try Refresh or reopen this file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                  )
                } else {
                  OutlinedTextField(
                    value = fileText,
                    onValueChange = { fileText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    enabled = !fileLoading && !fileSaving,
                    maxLines = 24,
                  )
                }

                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                  Button(
                    onClick = {
                      fileSaving = true
                      val path = "/api/programs/dnscrypt/setting-files/${enc(name)}"
                      val payload = JSONObject().put("content", fileText)
                      actions.saveJsonData(path, payload) { ok ->
                        fileSaving = false
                        if (ok) lastLoaded = fileText
                        scope.launch {
                          snackHost.showSnackbar(if (ok) "Saved (apply after stop/start)" else "Save failed")
                        }
                      }
                    },
                    enabled = fileLoadedOk && !fileTooLarge && !fileLoading && !fileSaving && fileText != lastLoaded,
                  ) {
                    Text(if (fileSaving) "..." else "Save")
                  }
                }
              }
            }
          }
        }

        Spacer(Modifier.height(10.dp))
      }
    }
  }
}

private fun JSONArray.toStringList(): List<String> {
  val out = ArrayList<String>(length())
  for (i in 0 until length()) out.add(optString(i))
  return out.filter { it.isNotBlank() }
}

private fun JSONObject.toLongMap(): Map<String, Long> {
  val it = keys()
  val out = LinkedHashMap<String, Long>()
  while (it.hasNext()) {
    val k = it.next()
    if (k.isNullOrBlank()) continue
    out[k] = optLong(k, 0L)
  }
  return out
}

private fun JSONObject.toBoolMap(): Map<String, Boolean> {
  val it = keys()
  val out = LinkedHashMap<String, Boolean>()
  while (it.hasNext()) {
    val k = it.next()
    if (k.isNullOrBlank()) continue
    out[k] = optBoolean(k, true)
  }
  return out
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
