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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.android.zdtd.service.R
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
      stringResource(R.string.strategic_files_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    StrategicTextDirSection(
      dir = "list",
      title = stringResource(R.string.strategic_lists_title),
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
        title = stringResource(R.string.strategic_lua_title),
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
      title = stringResource(R.string.strategic_binaries_title),
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
  // Snackbar messages must be resolved in a composable context.
  val msgCantReadFile = stringResource(R.string.common_cant_read_file)
  val msgUploaded = stringResource(R.string.common_uploaded)
  val msgUploadFailed = stringResource(R.string.common_upload_failed)
  val msgCreated = stringResource(R.string.common_created)
  val msgCreateFailed = stringResource(R.string.common_create_failed)
  val msgCantLoadFile = stringResource(R.string.common_cant_load_file)
  val msgDeleted = stringResource(R.string.common_deleted)
  val msgDeleteFailed = stringResource(R.string.common_delete_failed)
  val msgSaved = stringResource(R.string.common_saved)
  val msgSaveFailed = stringResource(R.string.editor_save_failed)


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
        showSnack(msgCantReadFile)
        return@rememberLauncherForActivityResult
      }
      actions.uploadStrategicFile(dir, name, bytes) { ok ->
        showSnack(if (ok) msgUploaded else msgUploadFailed)
        if (ok) refresh()
      }
    }
  )

  if (showCreate) {
    CreateFileDialog(
      title = stringResource(R.string.common_create_file),
      onDismiss = { showCreate = false },
      onCreate = { name ->
        showCreate = false
        actions.saveStrategicText(dir, name, "") { ok ->
          showSnack(if (ok) msgCreated else msgCreateFailed)
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
        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh_cd)) }
        if (allowUpload) {
          IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
            Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.common_upload_cd))
          }
        }
        if (allowCreate) {
          IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.common_new_cd)) }
        }
      }

      if (listLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      } else if (files.isEmpty()) {
        Text(stringResource(R.string.common_no_files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
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
                          loadError = msgCantLoadFile
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
                      showSnack(if (ok) msgDeleted else msgDeleteFailed)
                      if (ok) {
                        if (expanded == f) {
                          expanded = null
                          text = ""
                        }
                        refresh()
                      }
                    }
                  }
                ) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete_cd)) }
              }
            }

            if (expanded == f) {
              if (fileLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
              } else {
                if (tooLarge) {
                  val size = sizes[f] ?: -1L
                  Text(
                    if (size >= 0) {
                      stringResource(
                        R.string.strategic_edit_disabled_too_large_with_size_fmt,
                        limitBytes,
                        dir,
                        f,
                        size,
                      )
                    } else {
                      stringResource(
                        R.string.strategic_edit_disabled_too_large_no_size_fmt,
                        limitBytes,
                        dir,
                        f,
                      )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                  )
                } else if (loadError != null) {
                  Text(
                    loadError ?: stringResource(R.string.common_error),
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
                    label = { Text(stringResource(R.string.common_content)) },
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
                            showSnack(if (ok) msgSaved else msgSaveFailed)
                          }
                        },
                        enabled = !saving
                      ) { Text(stringResource(R.string.common_save)) }
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
  val msgCantReadFile = stringResource(R.string.common_cant_read_file)
  val msgUploaded = stringResource(R.string.common_uploaded)
  val msgUploadFailed = stringResource(R.string.common_upload_failed)
  val msgCreated = stringResource(R.string.common_created)
  val msgCreateFailed = stringResource(R.string.common_create_failed)
  val msgCantLoadFile = stringResource(R.string.common_cant_load_file)
  val msgDeleted = stringResource(R.string.common_deleted)
  val msgDeleteFailed = stringResource(R.string.common_delete_failed)
  val msgSaved = stringResource(R.string.common_saved)
  val msgSaveFailed = stringResource(R.string.editor_save_failed)


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
        showSnack(msgCantReadFile)
        return@rememberLauncherForActivityResult
      }
      actions.uploadStrategicFile(dir, name, bytes) { ok ->
        showSnack(if (ok) msgUploaded else msgUploadFailed)
        if (ok) refresh()
      }
    }
  )

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh_cd)) }
        IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.common_upload_cd)) }
      }

      if (listLoading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      } else if (files.isEmpty()) {
        Text(stringResource(R.string.common_no_files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
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
                  showSnack(if (ok) msgDeleted else msgDeleteFailed)
                  if (ok) refresh()
                }
              }
            ) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_delete_cd)) }
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
          label = { Text(stringResource(R.string.common_file_name)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          singleLine = true,
        )
        Text(
          stringResource(R.string.common_safe_file_name_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
      }
    },
    confirmButton = {
      Button(onClick = { onCreate(name.trim()) }, enabled = name.trim().isNotEmpty()) { Text(stringResource(R.string.common_create)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
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
