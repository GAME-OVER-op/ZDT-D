package com.android.zdtd.service.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import kotlin.math.max

@Composable
fun EnabledCard(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  val compactWidth = rememberIsCompactWidth()
  val stateText = stringResource(if (checked) R.string.enabled_state_on else R.string.enabled_state_off)
  val stateColor = if (checked) Color(0xFF22C55E) else MaterialTheme.colorScheme.error
  val progress = remember { Animatable(if (checked) 1f else 0f) }
  var animating by remember { mutableStateOf(false) }
  var animationToken by remember { mutableStateOf(0) }

  LaunchedEffect(checked) {
    val token = animationToken + 1
    animationToken = token
    animating = true
    try {
      progress.animateTo(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
      )
    } finally {
      if (animationToken == token) animating = false
    }
  }

  val infinite = rememberInfiniteTransition(label = "enabled_card_wave")
  val wave by infinite.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 950, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "enabled_card_wave_value",
  )

  val onColor = Color(0xFF22C55E)
  val offColor = MaterialTheme.colorScheme.error
  val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)

  Card(colors = CardDefaults.cardColors(containerColor = baseColor)) {
    Box(Modifier.fillMaxWidth()) {
      Canvas(Modifier.matchParentSize()) {
        val p = progress.value.coerceIn(0f, 1f)
        val currentColor = lerp(offColor, onColor, p)
        val targetColor = if (checked) onColor else offColor
        val towardTarget = if (checked) p else 1f - p
        val maxDim = max(size.width, size.height)
        val origin = Offset(size.width - 34.dp.toPx(), size.height / 2f)
        val pulse = if (animating) wave * maxDim * 0.12f else 0f
        val radius = maxDim * (0.18f + 0.92f * towardTarget) + pulse

        drawRoundRect(
          color = currentColor.copy(alpha = 0.055f + 0.065f * towardTarget),
          size = size,
        )
        drawCircle(
          color = targetColor.copy(alpha = if (animating) 0.20f else 0.13f),
          radius = radius,
          center = origin,
        )
        if (animating) {
          drawCircle(
            color = targetColor.copy(alpha = 0.12f * (1f - wave.coerceIn(0f, 1f))),
            radius = maxDim * (0.28f + wave * 0.88f),
            center = origin,
          )
        }
      }

      if (compactWidth) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          EnabledCardText(title = title, stateText = stateText, stateColor = stateColor)
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
          }
        }
      } else {
        Row(
          Modifier.fillMaxWidth().padding(12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          EnabledCardText(
            title = title,
            stateText = stateText,
            stateColor = stateColor,
            modifier = Modifier.weight(1f),
          )
          Spacer(Modifier.width(12.dp))
          Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
      }
    }
  }
}

@Composable
private fun EnabledCardText(
  title: String,
  stateText: String,
  stateColor: Color,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Spacer(Modifier.height(3.dp))
    Text(
      stateText,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = stateColor,
      maxLines = 1,
    )
    Spacer(Modifier.height(2.dp))
    Text(
      stringResource(R.string.enabled_card_apply_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )
  }
}
