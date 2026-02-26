package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.launch
import java.security.MessageDigest

private fun sha256HexUtf8(s: String): String {
  val md = MessageDigest.getInstance("SHA-256")
  val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
  val sb = StringBuilder(bytes.size * 2)
  for (b in bytes) sb.append(String.format("%02x", b))
  return sb.toString()
}

@Composable
fun StrategicVarConfigCard(
  programId: String,
  profile: String,
  configPath: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  var text by remember(configPath) { mutableStateOf("") }
  var lastLoaded by remember(configPath) { mutableStateOf("") }
  var loading by remember(configPath) { mutableStateOf(true) }
  var saving by remember(configPath) { mutableStateOf(false) }

  var variants by remember(programId) { mutableStateOf<List<ApiModels.StrategyVariant>>(emptyList()) }
  var variantsLoading by remember(programId) { mutableStateOf(true) }
  var menuOpen by remember(programId) { mutableStateOf(false) }
  var applying by remember(programId, profile) { mutableStateOf(false) }

  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current

  fun reloadConfig() {
    loading = true
    actions.loadText(configPath) { content ->
      text = content ?: ""
      lastLoaded = text
      loading = false
    }
  }

  fun reloadVariants() {
    variantsLoading = true
    actions.listStrategicVariants(programId) { v ->
      variants = v ?: emptyList()
      variantsLoading = false
    }
  }

  LaunchedEffect(configPath) { reloadConfig() }
  LaunchedEffect(programId) { reloadVariants() }

  val savedHash = remember(lastLoaded) { sha256HexUtf8(lastLoaded) }
  val matched = remember(savedHash, variants) {
    variants.firstOrNull { it.sha256 != null && it.sha256.equals(savedHash, ignoreCase = true) }
  }

  val strategyLabel = when {
    variantsLoading -> stringResource(R.string.strategic_strategies_loading)
    variants.isEmpty() -> stringResource(R.string.strategic_strategies_none)
    matched != null -> stringResource(R.string.strategic_strategy_selected, matched.name.removeSuffix(".txt"))
    else -> stringResource(R.string.strategic_user_config)
  }
  val isUserConfig = !variantsLoading && variants.isNotEmpty() && matched == null

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.strategic_config_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Spacer(Modifier.height(2.dp))
          Text(
            stringResource(R.string.strategic_config_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
        Button(
          onClick = {
            saving = true
            actions.saveText(configPath, text) { ok ->
              saving = false
              if (ok) lastLoaded = text
              scope.launch {
                snackHost.showSnackbar(if (ok) ctx.getString(R.string.editor_saved_apply_restart) else ctx.getString(R.string.editor_save_failed))
              }
            }
          },
          enabled = !loading && !saving && !applying && text != lastLoaded,
        ) {
          Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
        }
      }

      Spacer(Modifier.height(10.dp))

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        AssistChip(
          onClick = { },
          label = { Text(strategyLabel) },
          colors = if (isUserConfig) {
            AssistChipDefaults.assistChipColors(
              containerColor = MaterialTheme.colorScheme.errorContainer,
              labelColor = MaterialTheme.colorScheme.onErrorContainer,
            )
          } else {
            AssistChipDefaults.assistChipColors()
          }
        )

        Box {
          Button(
            onClick = { menuOpen = true },
            enabled = !loading && !saving && !applying && !variantsLoading && variants.isNotEmpty(),
          ) {
            Text(if (applying) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_choose))
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            variants.forEach { v ->
              DropdownMenuItem(
                text = { Text(v.name.removeSuffix(".txt")) },
                onClick = {
                  menuOpen = false
                  applying = true
                  actions.applyStrategicVariant(programId, profile, v.name) { ok ->
                    applying = false
                    if (ok) {
                      reloadConfig()
                    }
                    scope.launch {
                      snackHost.showSnackbar(
                        if (ok) ctx.getString(R.string.common_applied_with_value, v.name.removeSuffix(".txt"))
                        else ctx.getString(R.string.common_apply_failed)
                      )
                    }
                  }
                }
              )
            }
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        enabled = !loading && !saving && !applying,
        label = { Text(if (loading) stringResource(R.string.common_loading) else "") },
        maxLines = 24,
      )
    }
  }
}
