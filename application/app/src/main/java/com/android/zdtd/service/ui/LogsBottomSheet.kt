package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.LogLine
import com.android.zdtd.service.R
import kotlinx.coroutines.launch

@Composable
fun LogsBottomSheet(
  logs: List<LogLine>,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  val compact = rememberIsCompactWidth() || rememberIsShortHeight()
  PortraitLogsShelf(onDismiss = onDismiss) {
    LogsSheetContent(
      logs = logs,
      compact = compact,
      onClear = onClear,
      onDismiss = onDismiss,
    )
  }
}

@Composable
private fun PortraitLogsShelf(
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val dragOffsetY = remember { Animatable(0f) }
  var visible by remember { mutableStateOf(false) }
  var dismissing by remember { mutableStateOf(false) }
  val dismissThresholdPx = with(density) { 92.dp.toPx() }
  val dismissTargetPx = with(density) { 220.dp.toPx() }

  fun dismissWithAnimation() {
    if (dismissing) return
    dismissing = true
    scope.launch {
      visible = false
      runCatching {
        dragOffsetY.animateTo(
          targetValue = dismissTargetPx,
          animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
        )
      }
      onDismiss()
    }
  }

  LaunchedEffect(Unit) {
    visible = true
  }

  Dialog(
    onDismissRequest = { dismissWithAnimation() },
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing),
      contentAlignment = Alignment.BottomCenter,
    ) {
      AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = 150, easing = FastOutSlowInEasing)) +
          slideInVertically(
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            initialOffsetY = { it / 4 },
          ) +
          scaleIn(
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
            initialScale = 0.98f,
          ),
        exit = fadeOut(tween(durationMillis = 110)) +
          slideOutVertically(
            animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
            targetOffsetY = { it / 5 },
          ),
      ) {
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .graphicsLayer { translationY = dragOffsetY.value },
          shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
          tonalElevation = 4.dp,
          shadowElevation = 10.dp,
        ) {
          Column(Modifier.fillMaxSize()) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(Unit) {
                  detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                      val next = (dragOffsetY.value + dragAmount).coerceAtLeast(0f)
                      scope.launch { dragOffsetY.snapTo(next) }
                    },
                    onDragEnd = {
                      scope.launch {
                        if (dragOffsetY.value >= dismissThresholdPx) {
                          dismissWithAnimation()
                        } else {
                          dragOffsetY.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                              dampingRatio = Spring.DampingRatioNoBouncy,
                              stiffness = Spring.StiffnessMediumLow,
                            ),
                          )
                        }
                      }
                    },
                    onDragCancel = {
                      scope.launch {
                        dragOffsetY.animateTo(
                          targetValue = 0f,
                          animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                          ),
                        )
                      }
                    },
                  )
                },
              contentAlignment = Alignment.Center,
            ) {
              Box(
                modifier = Modifier
                  .width(52.dp)
                  .height(5.dp)
                  .clip(RoundedCornerShape(100.dp))
                  .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)),
              )
            }
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds(),
            ) {
              content()
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LogsSheetContent(
  logs: List<LogLine>,
  compact: Boolean,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
    if (compact) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_clear)) }
          Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
        }
      }
    } else {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
          Button(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
      }
    }
    Spacer(Modifier.height(12.dp))
    if (logs.isEmpty()) {
      Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
      Spacer(Modifier.height(22.dp))
    } else {
      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().weight(1f),
      ) {
        items(logs, key = { it.ts + it.msg }, contentType = { "log_entry" }) { l ->
          Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))) {
            Column(Modifier.padding(12.dp)) {
              Text("${l.ts} • ${l.level}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
              Spacer(Modifier.height(4.dp))
              Text(l.msg)
            }
          }
        }
      }
    }
  }
}
