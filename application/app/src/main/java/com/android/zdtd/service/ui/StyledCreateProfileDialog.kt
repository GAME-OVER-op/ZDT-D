package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.R

@Composable
internal fun StyledCreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
  titleRes: Int,
  rulesRes: Int,
  invalidNameRes: Int,
  existsErrorRes: Int = R.string.profile_already_exists,
  nameLabelRes: Int = R.string.profile_name_label,
  validator: (String) -> Boolean,
) {
  var rawName by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val existingSet = remember(existing) { existing.toSet() }
  val invalidText = stringResource(invalidNameRes)
  val existsText = stringResource(existsErrorRes)
  val compact = rememberIsCompactWidth()
  val accent = Color(0xFFA78BFA)
  val accentSoft = Color(0xFFFF4D7D)
  val dialogShape = RoundedCornerShape(if (compact) 26.dp else 30.dp)

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = if (compact) 22.dp else 28.dp),
      shape = dialogShape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      contentColor = MaterialTheme.colorScheme.onSurface,
      border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
      tonalElevation = 0.dp,
      shadowElevation = 14.dp,
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            brush = Brush.linearGradient(
              listOf(
                accent.copy(alpha = 0.18f),
                accentSoft.copy(alpha = 0.07f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.04f),
              )
            ),
            shape = dialogShape,
          )
      ) {
        Column(
          modifier = Modifier.padding(if (compact) 18.dp else 22.dp),
          verticalArrangement = Arrangement.spacedBy(if (compact) 13.dp else 15.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Surface(
              modifier = Modifier.size(if (compact) 44.dp else 48.dp),
              shape = CircleShape,
              color = accent.copy(alpha = 0.20f),
              contentColor = Color.White,
              border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
              tonalElevation = 0.dp,
              shadowElevation = 0.dp,
            ) {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(
                  Icons.Filled.Add,
                  contentDescription = null,
                  modifier = Modifier.size(if (compact) 23.dp else 25.dp),
                )
              }
            }
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Text(
                text = stringResource(titleRes),
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = stringResource(R.string.tab_profiles),
                style = MaterialTheme.typography.labelMedium,
                color = accent.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
              )
            }
          }

          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
          ) {
            Text(
              text = stringResource(rulesRes),
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }

          OutlinedTextField(
            value = rawName,
            onValueChange = { value ->
              rawName = value.take(10)
              error = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(nameLabelRes)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            singleLine = true,
            supportingText = {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("A-Z a-z 0-9 _ -")
                Text(stringResource(R.string.profile_name_len_fmt, rawName.length))
              }
            },
            isError = error != null,
            shape = RoundedCornerShape(16.dp),
          )

          if (error != null) {
            Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
              contentColor = MaterialTheme.colorScheme.error,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.26f)),
              tonalElevation = 0.dp,
              shadowElevation = 0.dp,
            ) {
              Text(
                text = error.orEmpty(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedButton(
              onClick = onDismiss,
              shape = RoundedCornerShape(100.dp),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
              contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
              Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.SemiBold)
            }
            Button(
              onClick = {
                val name = rawName.trim()
                when {
                  !validator(name) -> error = invalidText
                  name in existingSet -> error = existsText
                  else -> onCreate(name)
                }
              },
              enabled = rawName.isNotBlank(),
              shape = RoundedCornerShape(100.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Color.White,
                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f),
              ),
              contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
              Text(stringResource(R.string.action_create), fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }
}
