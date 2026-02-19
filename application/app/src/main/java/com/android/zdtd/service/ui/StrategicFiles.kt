package com.android.zdtd.service.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.launch

@Composable
fun ZapretStrategicFiles(
  programId: String, // nfqws | nfqws2
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      "Files under /data/adb/modules/ZDT-D/strategic/ (apply after stop/start).",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    StrategicTextDirSection(
      dir = "list",
      title = "Lists",
      actions = actions,
      snackHost = snackHost,
      allowCreate = true,
      allowEdit = true,
      allowDelete = true,
      allowUpload = true,
    )

    if (programId == "nfqws2") {
      StrategicTextDirSection(
        dir = "lua",
        title = "Lua scripts (nfqws2)",
        actions = actions,
        snackHost = snackHost,
        allowCreate = true,
        allowEdit = true,
        allowDelete = true,
        allowUpload = true,
      )
    }

    StrategicBinDirSection(
      dir = "bin",
      title = "Binaries",
      actions = actions,
      snackHost = snackHost,
    )
  }
}

@Composable
private fun StrategicTextDirSection(
  dir: String, // list | lua
  title: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  allowCreate: Boolean,
  allowEdit: Boolean,
  allowDelete: Boolean,
  allowUpload: Boolean,
) {
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current

  var files by remember { mutableStateOf<List<String>>(emptyList()) }
  var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
  var limitBytes by remember { mutableStateOf(200L * 1024L) }
  var listLoading by remember { mutableStateOf(true) }

  var expanded by remember { mutableStateOf<String?>(null) }
  var fileLoading by remember { mutableStateOf(false) }
  var text by remember { mutableStateOf("") }
  var tooLarge by remember { mutableStateOf(false) }
  var loadError by remember { mutableStateOf<String?>(null) }
  var saving by remember { mutableStateOf(false) }

  var showCreate by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun refresh() {
    listLoading = true
    actions.loadJsonData("/api/strategic/$dir") { obj ->
      val arr = obj?.optJSONArray("files")
      files = if (arr != null) (0 until arr.length()).mapNotNull { i -> arr.optString(i, null) } else emptyList()

      val sizesObj = obj?.optJSONObject("sizes")
      val map = HashMap<String, Long>()
      if (sizesObj != null) {
        for (k in sizesObj.keys()) {
          map[k] = sizesObj.optLong(k, -1L)
        }
      }
      sizes = map
      limitBytes = obj?.optLong("limit", 200L * 1024L) ?: (200L * 1024L)
      listLoading = false
    }
  }

  LaunchedEffect(dir) { refresh() }

  val uploadLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      val name = uriDisplayName(ctx, uri) ?: "file.txt"
      val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
      if (bytes == null) {
        showSnack("Can't read file")
        return@rememberLauncherForActivityResult
      }
      actions.uploadStrategicFile(dir, name, bytes) { ok ->
        showSnack(if (ok) "Uploaded" else "Upload failed")
        if (ok) refresh()
      }
    }
  )

  if (showCreate) {
    CreateFileDialog(
      title = "Create file",
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.saveStrategicText(dir, name, "") { ok ->
          showSnack(if (ok) "Created" else "Create failed")
          if (ok) refresh()
        }
      }
    )
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        if (allowUpload) {
          IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
            Icon(Icons.Default.CloudUpload, contentDescription = "Upload")
          }
        }
        if (allowCreate) {
          IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, contentDescription = "New") }
        }
      }

      if (listLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      } else if (files.isEmpty()) {
        Text("No files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      } else {
        files.forEach { f ->
          Column {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  if (expanded == f) {
                    expanded = null
                    text = ""
                    tooLarge = false
                    loadError = null
                  } else {
                    expanded = f
                    text = ""
                    tooLarge = false
                    loadError = null

                    val size = sizes[f] ?: -1L
                    if (size >= 0 && size > limitBytes) {
                      tooLarge = true
                      fileLoading = false
                    } else {
                      fileLoading = true
                      actions.loadStrategicText(dir, f) { content ->
                        if (content == null) {
                          loadError = "Can't load file (maybe too large or server error)."
                          text = ""
                        } else {
                          text = content
                        }
                        fileLoading = false
                      }
                    }
                  }
                }
                .padding(vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                f,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )

              val sz = sizes[f]
              if (sz != null && sz >= 0) {
                Text(
                  "${sz}B",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                  modifier = Modifier.padding(start = 8.dp)
                )
              }

              if (allowDelete) {
                IconButton(
                  onClick = {
                    actions.deleteStrategicFile(dir, f) { ok ->
                      showSnack(if (ok) "Deleted" else "Delete failed")
                      if (ok) {
                        if (expanded == f) {
                          expanded = null
                          text = ""
                        }
                        refresh()
                      }
                    }
                  }
                ) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
              }
            }

            if (expanded == f) {
              if (fileLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
              } else {
                if (tooLarge) {
                  val size = sizes[f] ?: -1L
                  Text(
                    "Editing is disabled: file is larger than ${limitBytes} bytes.\n" +
                      "Use upload/replace or edit externally.\n\n" +
                      "Path:\n/data/adb/modules/ZDT-D/strategic/$dir/$f\n" +
                      (if (size >= 0) "\nSize: ${size} bytes" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                  )
                } else if (loadError != null) {
                  Text(
                    loadError ?: "Error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                  )
                } else {
                  OutlinedTextField(
                    value = text,
                    onValueChange = { if (allowEdit) text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 18,
                    enabled = allowEdit && !saving,
                    label = { Text("Content") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                  )

                  Spacer(Modifier.height(8.dp))

                  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (allowEdit) {
                      Button(
                        onClick = {
                          saving = true
                          actions.saveStrategicText(dir, f, text) { ok ->
                            saving = false
                            showSnack(if (ok) "Saved" else "Save failed")
                          }
                        },
                        enabled = !saving
                      ) { Text("Save") }
                    }
                  }
                }
              }
              Spacer(Modifier.height(6.dp))
              Divider()
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StrategicBinDirSection(
  dir: String, // bin
  title: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current

  var files by remember { mutableStateOf<List<String>>(emptyList()) }
  var listLoading by remember { mutableStateOf(true) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  fun refresh() {
    listLoading = true
    actions.listStrategicFiles(dir) { list ->
      files = list ?: emptyList()
      listLoading = false
    }
  }

  LaunchedEffect(dir) { refresh() }

  val uploadLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      val name = uriDisplayName(ctx, uri) ?: "file.bin"
      val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
      if (bytes == null) {
        showSnack("Can't read file")
        return@rememberLauncherForActivityResult
      }
      actions.uploadStrategicFile(dir, name, bytes) { ok ->
        showSnack(if (ok) "Uploaded" else "Upload failed")
        if (ok) refresh()
      }
    }
  )

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.CloudUpload, contentDescription = "Upload") }
      }

      if (listLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      } else if (files.isEmpty()) {
        Text("No files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      } else {
        files.forEach { f ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(f, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(
              onClick = {
                actions.deleteStrategicFile(dir, f) { ok ->
                  showSnack(if (ok) "Deleted" else "Delete failed")
                  if (ok) refresh()
                }
              }
            ) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
          }
        }
      }
    }
  }
}

@Composable
private fun CreateFileDialog(
  title: String,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("File name") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
        )
        Text(
          "Use a safe name (letters, digits, dot, dash, underscore).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
      }
    },
    confirmButton = {
      Button(onClick = { onCreate(name.trim()) }, enabled = name.trim().isNotEmpty()) { Text("Create") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

private fun uriDisplayName(context: Context, uri: Uri): String? {
  var cursor: Cursor? = null
  return try {
    cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    if (cursor != null && cursor.moveToFirst()) {
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0) cursor.getString(idx) else null
    } else null
  } catch (_: Throwable) {
    null
  } finally {
    cursor?.close()
  }
}
