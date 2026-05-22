package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R

@Composable
fun DeleteModuleConfirmDialog(
  visible: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return

  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
  val danger = Color(0xFFFF2D55)
  val accent = Color(0xFFA78BFA)

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    DeleteModuleFullscreenShell(
      compact = compact,
      bottomActions = {
        DeleteModuleActions(
          compact = compact,
          danger = danger,
          confirmText = stringResource(R.string.delete_module_yes),
          onDismiss = onDismiss,
          onConfirm = onConfirm,
        )
      },
    ) {
      GoodbyeImage(compact = compact)

      Spacer(Modifier.height(if (compact) 14.dp else 18.dp))

      Text(
        text = stringResource(R.string.delete_module_title),
        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(12.dp))

      Text(
        text = stringResource(R.string.delete_module_confirm_text),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(if (compact) 16.dp else 20.dp))

      DeleteModuleInfoCard(
        accent = danger,
        title = stringResource(R.string.settings_delete_module_action),
        body = stringResource(R.string.delete_module_warning_reboot),
      )

      Spacer(Modifier.height(12.dp))

      DeleteModuleInfoCard(
        accent = accent,
        title = stringResource(R.string.common_attention),
        body = stringResource(R.string.delete_module_goodbye),
        soft = true,
      )
    }
  }
}

@Composable
fun DeleteModuleNextStepDialog(
  visible: Boolean,
  onDismiss: () -> Unit,
) {
  if (!visible) return

  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
  val accent = Color(0xFFA78BFA)

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    DeleteModuleFullscreenShell(
      compact = compact,
      bottomActions = {
        Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
          shape = CircleShape,
          colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.92f),
            contentColor = Color.White,
          ),
        ) {
          Text(stringResource(R.string.common_ok), fontWeight = FontWeight.Bold)
        }
      },
    ) {
      GoodbyeImage(compact = compact)

      Spacer(Modifier.height(if (compact) 14.dp else 18.dp))

      Text(
        text = stringResource(R.string.delete_module_next_title),
        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(14.dp))

      DeleteModuleInfoCard(
        accent = accent,
        title = stringResource(R.string.common_attention),
        body = stringResource(R.string.delete_module_next_body),
      )

      Spacer(Modifier.height(12.dp))

      DeleteModuleInfoCard(
        accent = Color(0xFFFF2D55),
        title = stringResource(R.string.settings_delete_module_action),
        body = stringResource(R.string.delete_module_warning_reboot),
        soft = true,
      )
    }
  }
}

@Composable
fun DeleteModulePreparingDialog(
  visible: Boolean,
  errorText: String?,
  onDismiss: () -> Unit,
) {
  if (!visible && errorText == null) return

  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
  val accent = if (errorText == null) Color(0xFFA78BFA) else Color(0xFFFF2D55)
  val shape = RoundedCornerShape(if (compact) 26.dp else 30.dp)

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = if (compact) 22.dp else 28.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 560.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
      ) {
        Box(
          modifier = Modifier.background(
            Brush.linearGradient(
              listOf(
                accent.copy(alpha = 0.16f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
              )
            )
          )
        ) {
          Column(
            modifier = Modifier.padding(if (compact) 20.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (errorText == null) {
              CircularProgressIndicator(color = accent)
              Text(
                text = stringResource(R.string.delete_module_prepare_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
              )
              Text(
                text = stringResource(R.string.delete_module_prepare_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
              )
            } else {
              Text(
                text = stringResource(R.string.delete_module_prepare_error_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                textAlign = TextAlign.Center,
              )
              Text(
                text = errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
                textAlign = TextAlign.Center,
              )
              Spacer(Modifier.height(4.dp))
              Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
              ) {
                Text(stringResource(R.string.common_ok), fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DeleteModuleFullscreenShell(
  compact: Boolean,
  bottomActions: @Composable () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Box(Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = if (compact) 18.dp else 28.dp)
          .padding(top = if (compact) 18.dp else 28.dp)
          .padding(bottom = if (compact) 124.dp else 132.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          content = content,
        )
      }

      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .padding(horizontal = if (compact) 16.dp else 28.dp)
          .padding(bottom = if (compact) 14.dp else 18.dp),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp),
        ) {
          bottomActions()
        }
      }
    }
  }
}

@Composable
private fun GoodbyeImage(compact: Boolean) {
  val size = if (compact) 184.dp else 232.dp
  Box(
    modifier = Modifier.size(size),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = painterResource(R.drawable.zdtd_goodbye),
      contentDescription = stringResource(R.string.cd_goodbye_image),
      modifier = Modifier
        .size(size)
        .offset(x = if (compact) 12.dp else 16.dp),
    )
  }
}

@Composable
private fun DeleteModuleInfoCard(
  accent: Color,
  title: String,
  body: String,
  soft: Boolean = false,
) {
  val shape = RoundedCornerShape(24.dp)
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    color = MaterialTheme.colorScheme.surface.copy(alpha = if (soft) 0.54f else 0.72f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, accent.copy(alpha = if (soft) 0.22f else 0.34f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier
        .background(
          Brush.linearGradient(
            listOf(
              accent.copy(alpha = if (soft) 0.08f else 0.14f),
              MaterialTheme.colorScheme.surface.copy(alpha = 0.04f),
            )
          )
        )
        .padding(14.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = accent.copy(alpha = 0.18f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
      ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
      }
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
        )
        Text(
          text = body,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
        )
      }
    }
  }
}

@Composable
private fun DeleteModuleActions(
  compact: Boolean,
  danger: Color,
  confirmText: String,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  if (compact) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(
          contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
      ) {
        Text(stringResource(R.string.common_cancel), fontWeight = FontWeight.SemiBold)
      }

      Button(
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = danger, contentColor = Color.White),
      ) {
        Text(confirmText, fontWeight = FontWeight.Bold)
      }
    }
  } else {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      OutlinedButton(
        onClick = onDismiss,
        modifier = Modifier.weight(1f),
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(
          contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
      ) {
        Text(stringResource(R.string.common_cancel), fontWeight = FontWeight.SemiBold)
      }

      Button(
        onClick = onConfirm,
        modifier = Modifier.weight(1f),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = danger, contentColor = Color.White),
      ) {
        Text(confirmText, fontWeight = FontWeight.Bold)
      }
    }
  }
}
