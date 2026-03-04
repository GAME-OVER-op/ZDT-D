package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberIsCompactWidth(): Boolean {
  val configuration = LocalConfiguration.current
  return remember(configuration.screenWidthDp) { configuration.screenWidthDp < 360 }
}

@Composable
internal fun rememberIsNarrowWidth(): Boolean {
  val configuration = LocalConfiguration.current
  return remember(configuration.screenWidthDp) { configuration.screenWidthDp < 400 }
}

@Composable
internal fun rememberIsShortHeight(): Boolean {
  val configuration = LocalConfiguration.current
  return remember(configuration.screenHeightDp) { configuration.screenHeightDp < 760 }
}

@Composable
internal fun rememberAdaptivePowerButtonSize(): Dp {
  val configuration = LocalConfiguration.current
  val width = configuration.screenWidthDp
  val height = configuration.screenHeightDp
  return remember(width, height) {
    when {
      width < 340 || height < 700 -> 176.dp
      width < 380 || height < 760 -> 192.dp
      width < 420 -> 208.dp
      else -> 226.dp
    }
  }
}

@Composable
internal fun rememberAdaptiveScreenPadding(): Dp {
  val configuration = LocalConfiguration.current
  val width = configuration.screenWidthDp
  return remember(width) {
    when {
      width < 360 -> 16.dp
      width < 420 -> 20.dp
      else -> 24.dp
    }
  }
}

@Composable
internal fun rememberUseScrollableTabs(): Boolean {
  val configuration = LocalConfiguration.current
  val width = configuration.screenWidthDp
  return remember(width) { width < 420 }
}


@Composable
internal fun rememberNeedsMinWidthScaling(minWidthDp: Int = 420): Boolean {
  val configuration = LocalConfiguration.current
  return remember(configuration.screenWidthDp, minWidthDp) { configuration.screenWidthDp < minWidthDp }
}

@Composable
internal fun MinWidthScaleContainer(
  modifier: Modifier = Modifier,
  minWidth: Dp = 420.dp,
  contentAlignment: Alignment = Alignment.TopCenter,
  content: @Composable () -> Unit,
) {
  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .clipToBounds(),
    contentAlignment = contentAlignment,
  ) {
    val targetWidth = if (maxWidth < minWidth) minWidth else maxWidth
    val scale = if (maxWidth < minWidth && minWidth.value > 0f) {
      maxWidth.value / minWidth.value
    } else {
      1f
    }

    Box(
      modifier = Modifier
        .width(targetWidth)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
          transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
        },
      contentAlignment = contentAlignment,
    ) {
      content()
    }
  }
}
