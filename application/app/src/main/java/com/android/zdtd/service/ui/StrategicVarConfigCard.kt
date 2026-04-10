package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.launch

private fun sha256HexUtf8(s: String): String {
  val md = MessageDigest.getInstance("SHA-256")
  val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
  val sb = StringBuilder(bytes.size * 2)
  for (b in bytes) sb.append(String.format("%02x", b))
  return sb.toString()
}

private fun ApiModels.StrategyVariant.displayName(): String = name.removeSuffix(".txt")

@Composable
fun StrategicVarConfigCard(
  programId: String,
  profile: String,
  configPath: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val compactWidth = rememberIsCompactWidth()
  val shortHeight = rememberIsShortHeight()
  val compactChooser = rememberUseScrollableTabs() || shortHeight

  var text by remember(configPath) { mutableStateOf("") }
  var lastLoaded by remember(configPath) { mutableStateOf("") }
  var loading by remember(configPath) { mutableStateOf(true) }
  var saving by remember(configPath) { mutableStateOf(false) }

  var variants by remember(programId) { mutableStateOf<List<ApiModels.StrategyVariant>>(emptyList()) }
  var variantsLoading by remember(programId) { mutableStateOf(true) }
  var chooserOpen by remember(programId) { mutableStateOf(false) }
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
      variants = (v ?: emptyList()).sortedBy { it.name.lowercase(Locale.ROOT) }
      variantsLoading = false
    }
  }

  fun applyVariant(variant: ApiModels.StrategyVariant) {
    chooserOpen = false
    applying = true
    actions.applyStrategicVariant(programId, profile, variant.name) { ok ->
      applying = false
      if (ok) reloadConfig()
      scope.launch {
        snackHost.showSnackbar(
          if (ok) ctx.getString(R.string.common_applied_with_value, variant.displayName())
          else ctx.getString(R.string.common_apply_failed)
        )
      }
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
    matched != null -> stringResource(R.string.strategic_strategy_selected, matched.displayName())
    else -> stringResource(R.string.strategic_user_config)
  }
  val isUserConfig = !variantsLoading && variants.isNotEmpty() && matched == null
  val canChooseVariant = !loading && !saving && !applying && !variantsLoading && variants.isNotEmpty()

  fun saveConfig() {
    saving = true
    actions.saveText(configPath, text) { ok ->
      saving = false
      if (ok) lastLoaded = text
      scope.launch {
        snackHost.showSnackbar(
          if (ok) ctx.getString(R.string.editor_saved_apply_restart)
          else ctx.getString(R.string.editor_save_failed)
        )
      }
    }
  }

  if (chooserOpen) {
    StrategicVariantsBottomSheet(
      variants = variants,
      currentVariantName = matched?.name,
      onDismiss = { chooserOpen = false },
      onChoose = ::applyVariant,
    )
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Column(Modifier.padding(12.dp)) {
      if (compactWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.fillMaxWidth()) {
            Text(
              stringResource(R.string.strategic_config_title),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              stringResource(R.string.strategic_config_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
          Button(
            onClick = { saveConfig() },
            enabled = !loading && !saving && !applying && text != lastLoaded,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
          }
        }
      } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) {
            Text(
              stringResource(R.string.strategic_config_title),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              stringResource(R.string.strategic_config_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
          }
          Button(
            onClick = { saveConfig() },
            enabled = !loading && !saving && !applying && text != lastLoaded,
          ) {
            Text(if (saving) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_save))
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      val chipColors = if (isUserConfig) {
        AssistChipDefaults.assistChipColors(
          containerColor = MaterialTheme.colorScheme.errorContainer,
          labelColor = MaterialTheme.colorScheme.onErrorContainer,
        )
      } else {
        AssistChipDefaults.assistChipColors()
      }

      if (compactChooser) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          AssistChip(
            modifier = Modifier.weight(1f),
            onClick = { },
            label = {
              Text(
                strategyLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            colors = chipColors,
          )
          Surface(
            shape = CircleShape,
            color = if (canChooseVariant) {
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
            } else {
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
            },
          ) {
            IconButton(
              onClick = { chooserOpen = true },
              enabled = canChooseVariant,
              modifier = Modifier.size(48.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = stringResource(R.string.common_choose),
              )
            }
          }
        }
      } else {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          AssistChip(
            modifier = Modifier.weight(1f),
            onClick = { },
            label = {
              Text(
                strategyLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            colors = chipColors,
          )
          Spacer(Modifier.width(12.dp))
          Button(
            onClick = { chooserOpen = true },
            enabled = canChooseVariant,
          ) {
            Text(if (applying) stringResource(R.string.common_ellipsis) else stringResource(R.string.common_choose))
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (shortHeight) 120.dp else 140.dp),
        enabled = !loading && !saving && !applying,
        label = { Text(if (loading) stringResource(R.string.common_loading) else "") },
        maxLines = 24,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategicVariantsBottomSheet(
  variants: List<ApiModels.StrategyVariant>,
  currentVariantName: String?,
  onDismiss: () -> Unit,
  onChoose: (ApiModels.StrategyVariant) -> Unit,
) {
  val shortHeight = rememberIsShortHeight()
  val narrowWidth = rememberIsNarrowWidth()
  val currentDisplay = currentVariantName?.removeSuffix(".txt") ?: stringResource(R.string.strategic_user_config)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            stringResource(R.string.strategic_variants_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            stringResource(R.string.current_value_fmt, currentDisplay),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            maxLines = if (narrowWidth) 2 else 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.width(12.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
          IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
          }
        }
      }

      Spacer(Modifier.height(8.dp))
      Text(
        stringResource(R.string.strategic_variants_sheet_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      )

      Spacer(Modifier.height(12.dp))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = if (shortHeight) 220.dp else 260.dp, max = if (shortHeight) 420.dp else 560.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
      ) {
        items(variants, key = { it.name }) { variant ->
          val isCurrent = variant.name == currentVariantName
          Card(
            onClick = { onChoose(variant) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
              }
            ),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(Modifier.weight(1f)) {
                Text(
                  text = variant.displayName(),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                  text = if (isCurrent) {
                    stringResource(R.string.strategic_variants_current_hint)
                  } else {
                    stringResource(R.string.strategic_variants_tap_to_apply)
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                )
              }
              if (isCurrent) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                  Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Filled.Check,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimary,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
