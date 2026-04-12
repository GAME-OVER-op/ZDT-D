package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
  val powerButtonSize = rememberAdaptivePowerButtonSize()
  val isCompactWidth = rememberIsCompactWidth()
  val isShortHeight = rememberIsShortHeight()
  val contentSpacing = if (isShortHeight) 12.dp else 18.dp

  // NOTE: stage16 had a simple image-based power button without transition animations.

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(if (isShortHeight) 4.dp else 12.dp))

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

    Spacer(Modifier.height(contentSpacing))

    // Power button (image-based) — same as stage16
    val powerPainter = remember(on) {
      if (on) R.drawable.power_on else R.drawable.power_off
    }

    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(powerButtonSize)
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

    Spacer(Modifier.height(contentSpacing))

    Text(
      if (on) stringResource(R.string.home_power_running) else stringResource(R.string.home_power_stopped),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
      if (on) stringResource(R.string.home_service_active_hint)
      else stringResource(R.string.home_service_stopped_hint),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )

    Spacer(Modifier.height(contentSpacing))

    // Daemon logs card (tail)
    val noLogDataText = stringResource(R.string.home_no_log_data)
    val logLines: List<DaemonLogUiLine> = remember(logTail, noLogDataText) {
      val t = logTail.trimEnd()
      if (t.isBlank()) {
        listOf(DaemonLogUiLine(raw = noLogDataText, level = DaemonLogLevel.OTHER, text = noLogDataText))
      } else {
        t.split('\n')
          .asSequence()
          .map { it.trimEnd() }
          .filter { it.isNotBlank() }
          .toList()
          .takeLast(100)
          .map(::parseDaemonLogUiLine)
      }
    }
    var logsBlockVisible by remember { mutableStateOf(false) }
    var nextLogRenderId by remember { mutableLongStateOf(0L) }
    fun renderize(lines: List<DaemonLogUiLine>): List<DaemonLogRenderLine> =
      lines.map { DaemonLogRenderLine(id = nextLogRenderId++, line = it) }
    var displayedLogLines by remember(noLogDataText) {
      mutableStateOf(renderize(listOf(DaemonLogUiLine(raw = noLogDataText, level = DaemonLogLevel.OTHER, text = noLogDataText))))
    }
    var logRevealInitialized by remember { mutableStateOf(false) }
    val initialAnimatedTail = 14
    val lastLine: String = remember(displayedLogLines) { displayedLogLines.lastOrNull()?.line?.text?.trimEnd().orEmpty() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
      kotlinx.coroutines.delay(160)
      logsBlockVisible = true
    }

    LaunchedEffect(logLines) {
      if (!logRevealInitialized) {
        val animatedTail = initialAnimatedTail.coerceAtMost(logLines.size)
        val stableHead = logLines.dropLast(animatedTail)
        displayedLogLines = if (stableHead.isNotEmpty()) renderize(stableHead) else emptyList()
        logRevealInitialized = true
        val toReveal = logLines.takeLast(animatedTail)
        if (toReveal.isEmpty()) {
          displayedLogLines = renderize(logLines)
        } else {
          for (line in toReveal) {
            displayedLogLines = displayedLogLines + DaemonLogRenderLine(id = nextLogRenderId++, line = line)
            kotlinx.coroutines.delay(135L)
          }
        }
      } else if (logLines.map { it.raw } == displayedLogLines.map { it.line.raw }) {
        // no-op
      } else if (
        logLines.size >= displayedLogLines.size &&
        logLines.take(displayedLogLines.size).map { it.raw } == displayedLogLines.map { it.line.raw }
      ) {
        val appended = logLines.drop(displayedLogLines.size)
        if (appended.isEmpty()) {
          displayedLogLines = displayedLogLines.takeLast(logLines.size)
        } else {
          for (line in appended) {
            displayedLogLines = (displayedLogLines + DaemonLogRenderLine(id = nextLogRenderId++, line = line)).takeLast(100)
            kotlinx.coroutines.delay(135L)
          }
        }
      } else {
        displayedLogLines = renderize(logLines)
      }
    }

    // Keep the newest line visible without fighting the row enter animation.
    LaunchedEffect(displayedLogLines.size) {
      val nearBottom = listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 24
      if (nearBottom && !listState.isScrollInProgress) {
        // reverseLayout=true => index 0 is at the bottom.
        listState.scrollToItem(0)
      }
    }

    AnimatedVisibility(
      visible = logsBlockVisible,
      enter = fadeIn(animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)) +
        expandVertically(animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)) +
        slideInVertically(
          initialOffsetY = { it / 10 },
          animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        ) +
        scaleIn(
          initialScale = 0.985f,
          animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        ),
      exit = fadeOut(animationSpec = tween(durationMillis = 200)),
    ) {
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
                .heightIn(min = if (isShortHeight) 112.dp else 128.dp, max = if (isShortHeight) 192.dp else 240.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
              state = listState,
              reverseLayout = true,
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              // Newest first for reverseLayout.
              val display: List<DaemonLogRenderLine> = displayedLogLines.asReversed()
              items(
                count = display.size,
                key = { idx -> display[idx].id },
              ) { idx ->
                val item: DaemonLogRenderLine = display[idx]
                val line: DaemonLogUiLine = item.line
                val backgroundColor = when (line.level) {
                  DaemonLogLevel.WARN -> Color(0xFF2F3136)
                  DaemonLogLevel.INFO -> Color(0xFF4A1F1F)
                  DaemonLogLevel.ERROR -> Color(0xFF5A1B1B)
                  DaemonLogLevel.NOTICE -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)
                  DaemonLogLevel.OTHER -> MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                }
                val rowVisibleState = remember {
                  MutableTransitionState(false).apply { targetState = true }
                }
                AnimatedVisibility(
                  visibleState = rowVisibleState,
                  enter = fadeIn(animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)) +
                    slideInVertically(
                      initialOffsetY = { maxOf(it / 6, 18) },
                      animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    ),
                  exit = fadeOut(animationSpec = tween(durationMillis = 140)),
                ) {
                  Surface(
                    color = backgroundColor,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.Top,
                    ) {
                      if (line.level != DaemonLogLevel.OTHER) {
                        Text(
                          text = line.level.label,
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                          fontWeight = FontWeight.SemiBold,
                          fontFamily = FontFamily.Monospace,
                          modifier = Modifier.padding(top = 1.dp),
                        )
                      }
                      Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                        softWrap = true,
                        modifier = Modifier.weight(1f),
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
}


private enum class DaemonLogLevel(val label: String) {
  WARN("WARN"),
  INFO("INFO"),
  ERROR("ERROR"),
  NOTICE("NOTICE"),
  OTHER(""),
}

private data class DaemonLogUiLine(
  val raw: String,
  val level: DaemonLogLevel,
  val text: String,
)

private data class DaemonLogRenderLine(
  val id: Long,
  val line: DaemonLogUiLine,
)

private fun parseDaemonLogUiLine(raw: String): DaemonLogUiLine {
  val upper = raw.uppercase()
  val level = when {
    " WARN " in " $upper " || upper.contains("[WARN]") || upper.contains(" WARNING ") -> DaemonLogLevel.WARN
    " INFO " in " $upper " || upper.contains("[INFO]") -> DaemonLogLevel.INFO
    " ERROR " in " $upper " || upper.contains("[ERROR]") || " ERR " in " $upper " -> DaemonLogLevel.ERROR
    " NOTICE " in " $upper " || upper.contains("[NOTICE]") -> DaemonLogLevel.NOTICE
    else -> DaemonLogLevel.OTHER
  }
  val text = stripDetectedLevelPrefix(raw.trim().ifBlank { raw }, level)
  return DaemonLogUiLine(raw = raw, level = level, text = text)
}

private fun stripDetectedLevelPrefix(text: String, level: DaemonLogLevel): String {
  if (level == DaemonLogLevel.OTHER) return text
  val cleaned = when (level) {
    DaemonLogLevel.INFO -> text
      .replaceFirst(Regex("""\[INFO\]\s*""", RegexOption.IGNORE_CASE), "")
      .replaceFirst(Regex("""\bINFO\b[:\-]?\s*""", RegexOption.IGNORE_CASE), "")
    DaemonLogLevel.WARN -> text
      .replaceFirst(Regex("""\[WARN(?:ING)?\]\s*""", RegexOption.IGNORE_CASE), "")
      .replaceFirst(Regex("""\bWARN(?:ING)?\b[:\-]?\s*""", RegexOption.IGNORE_CASE), "")
    DaemonLogLevel.ERROR -> text
      .replaceFirst(Regex("""\[(?:ERROR|ERR)\]\s*""", RegexOption.IGNORE_CASE), "")
      .replaceFirst(Regex("""\b(?:ERROR|ERR)\b[:\-]?\s*""", RegexOption.IGNORE_CASE), "")
    DaemonLogLevel.NOTICE -> text
      .replaceFirst(Regex("""\[NOTICE\]\s*""", RegexOption.IGNORE_CASE), "")
      .replaceFirst(Regex("""\bNOTICE\b[:\-]?\s*""", RegexOption.IGNORE_CASE), "")
    DaemonLogLevel.OTHER -> text
  }
  return cleaned.replace(Regex("""\s{2,}"""), " ").trim().ifBlank { text }
}
