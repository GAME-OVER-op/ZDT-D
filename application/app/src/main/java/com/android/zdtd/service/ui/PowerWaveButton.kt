package com.android.zdtd.service.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

internal enum class PowerUiState {
  Stopped,
  Starting,
  Running,
  Stopping,
}

@Composable
internal fun PowerWaveButton(
  state: PowerUiState,
  buttonSize: Dp,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isRunning = state == PowerUiState.Running || state == PowerUiState.Stopping
  val isAnimating = state == PowerUiState.Starting || state == PowerUiState.Stopping

  val fillColor = if (isRunning) Color(0xFFE53935) else Color(0xFF8E8E8E) // red / grey

  // Waves should match the current "active" feel:
  // - Starting: grey waves (service not yet running)
  // - Stopping: red waves (service still running while stopping)
  val waveColor = remember(state) {
    if (state == PowerUiState.Stopping) Color(0xFFFF5252) else Color.White
  }

  val infinite = rememberInfiniteTransition(label = "powerWaves")
  val progress by infinite.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 950, easing = LinearEasing),
    ),
    label = "waveProgress",
  )

  Surface(
    modifier = modifier
      .size(buttonSize)
      .clickable(enabled = enabled) { onClick() },
    shape = CircleShape,
    color = fillColor,
    tonalElevation = 2.dp,
    shadowElevation = 10.dp,
  ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      // Wave animation layer
      if (isAnimating) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          // DrawScope.size (Size) is in px.
          val rMax = min(size.width, size.height) / 2f
          val stroke = Stroke(width = 5.dp.toPx())

          // 3 rings, phase-shifted
          val phases = floatArrayOf(0f, 0.33f, 0.66f)
          for (phase in phases) {
            val p = (progress + phase) % 1f
            val radiusFrac = if (state == PowerUiState.Starting) p else (1f - p)
            val radius = rMax * radiusFrac.coerceIn(0f, 1f)
            val alpha = (1f - p) * 0.28f
            drawCircle(
              color = waveColor.copy(alpha = alpha),
              radius = radius,
              style = stroke,
            )
          }

          // Subtle inner ring for depth
          drawCircle(
            color = Color.White.copy(alpha = 0.10f),
            radius = rMax * 0.82f,
            style = Stroke(width = 2.dp.toPx()),
          )
        }
      }

      Icon(
        imageVector = Icons.Rounded.PowerSettingsNew,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.96f),
        modifier = Modifier.size(84.dp),
      )
    }
  }
}
