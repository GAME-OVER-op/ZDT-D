package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun TextEditorCard(
  title: String,
  desc: String,
  path: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  // Snackbar messages must be resolved in a composable context.
  val msgSavedApplyRestart = stringResource(R.string.editor_saved_apply_restart)
  val msgSaveFailed = stringResource(R.string.editor_save_failed)

  var text by remember(path) { mutableStateOf("") }
  var lastLoaded by remember(path) { mutableStateOf("") }
  var loading by remember(path) { mutableStateOf(true) }
  var saving by remember(path) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(path) {
    loading = true
    actions.loadText(path) { content ->
      text = content ?: ""
      lastLoaded = text
      loading = false
    }
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(2.dp))
          Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        Button(
          onClick = {
            saving = true
            actions.saveText(path, text) { ok ->
              saving = false
              if (ok) lastLoaded = text
              scope.launch {
                snackHost.showSnackbar(if (ok) msgSavedApplyRestart else msgSaveFailed)
              }
            }
          },
          enabled = !loading && !saving && text != lastLoaded,
        ) {
          Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        enabled = !loading && !saving,
        label = { Text(if (loading) stringResource(R.string.common_loading) else "") },
        maxLines = 24,
      )
    }
  }
}

@Composable
fun JsonEditorCard(
  title: String,
  desc: String,
  path: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  // Snackbar messages must be resolved in a composable context.
  val msgSavedApplyRestart = stringResource(R.string.editor_saved_apply_restart)
  val msgSaveFailed = stringResource(R.string.editor_save_failed)
  val msgInvalidJson = stringResource(R.string.editor_invalid_json)

  var text by remember(path) { mutableStateOf("{}") }
  var lastLoaded by remember(path) { mutableStateOf("{}") }
  var loading by remember(path) { mutableStateOf(true) }
  var saving by remember(path) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(path) {
    loading = true
    actions.loadJsonData(path) { obj ->
      val pretty = (obj ?: JSONObject()).toString(2)
      text = pretty
      lastLoaded = pretty
      loading = false
    }
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(2.dp))
          Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        Button(
          onClick = {
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed == null) {
              scope.launch { snackHost.showSnackbar(msgInvalidJson) }
              return@Button
            }
            saving = true
            actions.saveJsonData(path, parsed) { ok ->
              saving = false
              if (ok) lastLoaded = JSONObject(text).toString(2)
              scope.launch {
                snackHost.showSnackbar(if (ok) msgSavedApplyRestart else msgSaveFailed)
              }
            }
          },
          enabled = !loading && !saving && text != lastLoaded,
        ) {
          Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        enabled = !loading && !saving,
        label = { Text(if (loading) stringResource(R.string.common_loading) else "") },
        maxLines = 28,
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Text,
          capitalization = KeyboardCapitalization.None,
          autoCorrect = false,
        ),
      )
    }
  }
}
