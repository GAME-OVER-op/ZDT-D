package com.android.zdtd.service.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.R
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun HomeScreen(uiStateFlow: StateFlow<UiState>, actions: ZdtdActions) {
  // Collect ONLY what Home needs, and only while Home is visible.
  val online by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonOnline }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val status by remember(uiStateFlow) {
    uiStateFlow.map { it.status }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = null)

  val busy by remember(uiStateFlow) {
    uiStateFlow.map { it.busy }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = false)

  val logTail by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonLogTail }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = "")

  val on = ApiModels.isServiceOn(status)
  val scale by animateFloatAsState(targetValue = if (busy) 0.98f else 1.0f, label = "busyScale")

  // NOTE: stage16 had a simple image-based power button without transition animations.

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(12.dp))

    // Status pill
    AssistChip(
      onClick = { actions.refreshStatus() },
      label = {
        val st = if (online) stringResource(R.string.home_online) else stringResource(R.string.home_offline)
        Text(stringResource(R.string.home_daemon_status_fmt, st))
      },
      leadingIcon = {
        val c = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        Box(Modifier.size(10.dp).clip(CircleShape).background(c))
      },
    )

    Spacer(Modifier.height(18.dp))

    // Power button (image-based) — same as stage16
    val powerPainter = remember(on) {
      if (on) R.drawable.power_on else R.drawable.power_off
    }

    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(226.dp)
        .scale(scale)
        .clip(CircleShape)
        .clickable(enabled = !busy) { actions.toggleService() },
    ) {
      // The images already include the full button styling (glow/ring).
      Image(
        painter = painterResource(powerPainter),
        contentDescription = null,
        modifier = Modifier
          .fillMaxSize()
          .clip(CircleShape),
      )
    }

    Spacer(Modifier.height(18.dp))

    Text(
      if (on) stringResource(R.string.home_power_running) else stringResource(R.string.home_power_stopped),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
      if (on) stringResource(R.string.home_service_active_hint)
      else stringResource(R.string.home_service_stopped_hint),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
    )

    Spacer(Modifier.height(18.dp))

    // Daemon logs card (tail)
    val noLogDataText = stringResource(R.string.home_no_log_data)
    val lines: List<String> = remember(logTail, noLogDataText) {
      val t = logTail.trimEnd()
      if (t.isBlank()) {
        listOf(noLogDataText)
      } else {
        // Keep only a small tail for smoother UI.
        t.split('\n').takeLast(260)
      }
    }
    val lastLine: String = remember(lines) { lines.lastOrNull()?.trimEnd().orEmpty() }
    val listState = rememberLazyListState()

    // Always keep the newest line visible.
    LaunchedEffect(lines.size) {
      // reverseLayout=true => index 0 is at the bottom.
      listState.scrollToItem(0)
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
      shape = RoundedCornerShape(22.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
      Column(Modifier.padding(14.dp)) {
        Text(stringResource(R.string.home_daemon_logs_title), fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(2.dp))
        Crossfade(targetState = lastLine, animationSpec = tween(durationMillis = 180), label = "lastLine") { line ->
          if (line.isNotBlank()) {
            Text(
              line,
              style = MaterialTheme.typography.bodySmall,
              fontFamily = FontFamily.Monospace,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          } else {
            Spacer(Modifier.height(0.dp))
          }
        }
        Spacer(Modifier.height(8.dp))

        Surface(
          tonalElevation = 0.dp,
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.40f),
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        ) {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 120.dp, max = 220.dp)
              .padding(horizontal = 10.dp, vertical = 8.dp),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            // Newest first for reverseLayout.
            val display: List<String> = lines.asReversed()
            items(
              count = display.size,
              key = { idx -> "${idx}:${display[idx].hashCode()}" },
            ) { idx ->
              val line: String = display[idx]
              Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}
