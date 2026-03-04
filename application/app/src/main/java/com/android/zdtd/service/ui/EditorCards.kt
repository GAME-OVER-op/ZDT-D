package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
  val compactWidth = rememberIsCompactWidth()
  val shortHeight = rememberIsShortHeight()
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

  fun save() {
    saving = true
    actions.saveText(path, text) { ok ->
      saving = false
      if (ok) lastLoaded = text
      scope.launch { snackHost.showSnackbar(if (ok) msgSavedApplyRestart else msgSaveFailed) }
    }
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp)) {
      if (compactWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
          }
          Button(onClick = { save() }, enabled = !loading && !saving && text != lastLoaded, modifier = Modifier.fillMaxWidth()) {
            Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
          }
        }
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
          }
          Button(onClick = { save() }, enabled = !loading && !saving && text != lastLoaded) {
            Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (shortHeight) 120.dp else 140.dp),
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
  val compactWidth = rememberIsCompactWidth()
  val shortHeight = rememberIsShortHeight()
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

  fun save() {
    val parsed = runCatching { JSONObject(text) }.getOrNull()
    if (parsed == null) {
      scope.launch { snackHost.showSnackbar(msgInvalidJson) }
      return
    }
    saving = true
    actions.saveJsonData(path, parsed) { ok ->
      saving = false
      if (ok) lastLoaded = parsed.toString(2)
      scope.launch { snackHost.showSnackbar(if (ok) msgSavedApplyRestart else msgSaveFailed) }
    }
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp)) {
      if (compactWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
          }
          Button(onClick = { save() }, enabled = !loading && !saving && text != lastLoaded, modifier = Modifier.fillMaxWidth()) {
            Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
          }
        }
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
          }
          Button(onClick = { save() }, enabled = !loading && !saving && text != lastLoaded) {
            Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (shortHeight) 128.dp else 160.dp),
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
