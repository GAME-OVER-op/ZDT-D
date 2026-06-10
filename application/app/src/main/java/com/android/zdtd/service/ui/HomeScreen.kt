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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Power
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
fun HomeScreen(
  uiStateFlow: StateFlow<UiState>,
  actions: ZdtdActions,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
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

  val detailedLogTail by remember(uiStateFlow) {
    uiStateFlow.map { it.daemonLogDetailedTail }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = "")

  val on = ApiModels.isServiceOn(status)
  val scale by animateFloatAsState(targetValue = if (busy) 0.98f else 1.0f, label = "busyScale")
  val powerButtonSize = rememberAdaptivePowerButtonSize()
  val isCompactWidth = rememberIsCompactWidth()
  val isShortHeight = rememberIsShortHeight()
  val landscapeControl = rememberUseLandscapeControlLayout()
  val contentSpacing = if (isShortHeight) 12.dp else 18.dp

  // NOTE: stage16 had a simple image-based power button without transition animations.

  if (landscapeControl) {
    LandscapeHomeContent(
      online = online,
      on = on,
      busy = busy,
      scale = scale,
      logTail = logTail,
      detailedLogTail = detailedLogTail,
      actions = actions,
    )
    return
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp)
      .padding(top = topContentPadding + 12.dp, bottom = bottomContentPadding + 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(if (isShortHeight) 4.dp else 12.dp))

    val serviceStatusText = if (online) stringResource(R.string.home_online) else stringResource(R.string.home_offline)
    val powerStateText = if (on) stringResource(R.string.home_power_running) else stringResource(R.string.home_power_stopped)
    val powerHintText = if (on) {
      stringResource(R.string.home_service_active_hint)
    } else {
      stringResource(R.string.home_service_stopped_hint)
    }
    val statusAccent = when {
      on -> Color(0xFF22C55E)
      online -> MaterialTheme.colorScheme.primary
      else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)),
      shape = RoundedCornerShape(30.dp),
      border = BorderStroke(1.dp, statusAccent.copy(alpha = if (online) 0.34f else 0.14f)),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            Brush.radialGradient(
              colors = listOf(
                statusAccent.copy(alpha = 0.16f),
                Color.Transparent,
              ),
              radius = 560f,
            ),
          )
          .padding(horizontal = 16.dp, vertical = if (isShortHeight) 14.dp else 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Surface(
          modifier = Modifier.clickable { actions.refreshStatus() },
          shape = RoundedCornerShape(999.dp),
          color = statusAccent.copy(alpha = if (online) 0.14f else 0.08f),
          border = BorderStroke(1.dp, statusAccent.copy(alpha = if (online) 0.42f else 0.18f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(statusAccent))
            Text(
              text = stringResource(R.string.home_daemon_status_fmt, serviceStatusText),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            )
          }
        }

        Spacer(Modifier.height(if (isShortHeight) 10.dp else 16.dp))

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
          ThemeAwarePowerButtonContent(
            on = on,
            painterRes = powerPainter,
          )
        }

        Spacer(Modifier.height(if (isShortHeight) 10.dp else 14.dp))

        Surface(
          shape = RoundedCornerShape(999.dp),
          color = statusAccent.copy(alpha = if (on) 0.18f else 0.10f),
          border = BorderStroke(1.dp, statusAccent.copy(alpha = if (on) 0.42f else 0.18f)),
        ) {
          Text(
            text = powerStateText,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = if (on) statusAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
          )
        }

        Spacer(Modifier.height(8.dp))

        Text(
          text = powerHintText,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          textAlign = TextAlign.Center,
        )
      }
    }

    Spacer(Modifier.height(contentSpacing))

    // Daemon logs card (tail)
    var logSourceMenuExpanded by remember { mutableStateOf(false) }
    var selectedLogSource by remember { mutableStateOf(HomeLogSource.MAIN) }
    var logSourceSwitchNonce by remember { mutableLongStateOf(0L) }
    var pendingImmediateTailSnapNonce by remember { mutableLongStateOf(0L) }
    fun selectLogSource(source: HomeLogSource) {
      if (selectedLogSource != source) {
        selectedLogSource = source
        logSourceSwitchNonce++
      }
      logSourceMenuExpanded = false
    }
    val noLogDataText = stringResource(R.string.home_no_log_data)
    val mainLogsText = stringResource(R.string.home_logs_main)
    val detailedLogsText = stringResource(R.string.home_logs_detailed)
    val activeLogTail = when (selectedLogSource) {
      HomeLogSource.MAIN -> logTail
      HomeLogSource.DETAILED -> detailedLogTail
    }
    val logLines: List<DaemonLogUiLine> = remember(activeLogTail, noLogDataText) {
      val t = activeLogTail.trimEnd()
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
    fun rawMatches(lines: List<DaemonLogUiLine>, displayed: List<DaemonLogRenderLine>): Boolean {
      if (lines.size != displayed.size) return false
      for (index in lines.indices) {
        if (lines[index].raw != displayed[index].line.raw) return false
      }
      return true
    }
    fun displayedIsPrefixOf(lines: List<DaemonLogUiLine>, displayed: List<DaemonLogRenderLine>): Boolean {
      if (lines.size < displayed.size) return false
      for (index in displayed.indices) {
        if (lines[index].raw != displayed[index].line.raw) return false
      }
      return true
    }
    fun mergeSlidingTail(lines: List<DaemonLogUiLine>, displayed: List<DaemonLogRenderLine>): List<DaemonLogRenderLine>? {
      val maxOverlap = minOf(lines.size, displayed.size)
      for (overlap in maxOverlap downTo 1) {
        val displayedStart = displayed.size - overlap
        var matches = true
        for (i in 0 until overlap) {
          if (displayed[displayedStart + i].line.raw != lines[i].raw) {
            matches = false
            break
          }
        }
        if (matches) {
          val kept = displayed.takeLast(overlap)
          val appended = lines.drop(overlap).map { DaemonLogRenderLine(id = nextLogRenderId++, line = it) }
          return (kept + appended).takeLast(100)
        }
      }
      return null
    }
    var displayedLogLines by remember(noLogDataText) {
      mutableStateOf(renderize(listOf(DaemonLogUiLine(raw = noLogDataText, level = DaemonLogLevel.OTHER, text = noLogDataText))))
    }
    var logRevealInitialized by remember { mutableStateOf(false) }
    val logRevealDelayMs = 32L
    val lastLine: String = remember(displayedLogLines) { displayedLogLines.lastOrNull()?.line?.text?.trimEnd().orEmpty() }
    val newestLogRenderId: Long = displayedLogLines.lastOrNull()?.id ?: -1L
    val listState = rememberLazyListState()
    var followNewestLogLine by remember { mutableStateOf(true) }
    var userScrolledAwayDuringGesture by remember { mutableStateOf(false) }
    var manualScrollIdleNonce by remember { mutableLongStateOf(0L) }
    val autoReleaseToBottomDelayMs = 5_000L
    fun isLogListNearBottom(): Boolean =
      listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 96

    LaunchedEffect(Unit) {
      kotlinx.coroutines.delay(160)
      logsBlockVisible = true
    }

    LaunchedEffect(selectedLogSource) {
      followNewestLogLine = true
      userScrolledAwayDuringGesture = false
      logRevealInitialized = false
    }

    LaunchedEffect(selectedLogSource, logLines) {
      if (!logRevealInitialized) {
        displayedLogLines = renderize(logLines)
        logRevealInitialized = true
        pendingImmediateTailSnapNonce = if (logSourceSwitchNonce > 0L) logSourceSwitchNonce else 1L
      } else if (rawMatches(logLines, displayedLogLines)) {
        // no-op
      } else if (displayedIsPrefixOf(logLines, displayedLogLines)) {
        val appended = logLines.drop(displayedLogLines.size)
        if (appended.isEmpty()) {
          displayedLogLines = displayedLogLines.takeLast(logLines.size)
        } else {
          for (line in appended) {
            displayedLogLines = (displayedLogLines + DaemonLogRenderLine(id = nextLogRenderId++, line = line)).takeLast(100)
            kotlinx.coroutines.delay(logRevealDelayMs)
          }
        }
      } else {
        displayedLogLines = mergeSlidingTail(logLines, displayedLogLines) ?: renderize(logLines)
      }
    }

    // Keep the newest line visible while the user is following the tail.
    // If the user scrolls upward to read older lines, do not fight them; after
    // 5 seconds from the end of that manual gesture, gently release the list
    // back to the newest line. Log updates themselves must not restart this timer.
    LaunchedEffect(listState) {
      snapshotFlow {
        Triple(
          listState.firstVisibleItemIndex,
          listState.firstVisibleItemScrollOffset,
          listState.isScrollInProgress,
        )
      }.collect { (_, _, scrolling) ->
        val nearBottom = isLogListNearBottom()
        if (nearBottom) {
          followNewestLogLine = true
          userScrolledAwayDuringGesture = false
        } else if (scrolling) {
          followNewestLogLine = false
          userScrolledAwayDuringGesture = true
        } else if (userScrolledAwayDuringGesture) {
          userScrolledAwayDuringGesture = false
          manualScrollIdleNonce++
        }
      }
    }

    LaunchedEffect(manualScrollIdleNonce, selectedLogSource) {
      if (manualScrollIdleNonce > 0L && !followNewestLogLine) {
        kotlinx.coroutines.delay(autoReleaseToBottomDelayMs)
        if (!listState.isScrollInProgress && !isLogListNearBottom()) {
          followNewestLogLine = true
          // reverseLayout=true => index 0 is the visual bottom/newest line.
          listState.animateScrollToItem(0)
        }
      }
    }

    LaunchedEffect(pendingImmediateTailSnapNonce, displayedLogLines.size) {
      if (pendingImmediateTailSnapNonce > 0L) {
        followNewestLogLine = true
        // reverseLayout=true => index 0 is the visual bottom/newest line.
        listState.scrollToItem(0, 0)
        pendingImmediateTailSnapNonce = 0L
      }
    }

    LaunchedEffect(selectedLogSource, newestLogRenderId) {
      if ((followNewestLogLine || isLogListNearBottom()) && !listState.isScrollInProgress) {
        followNewestLogLine = true
        // reverseLayout=true => index 0 is the visual bottom/newest line.
        listState.scrollToItem(0, 0)
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
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
              Box {
                IconButton(
                  onClick = { logSourceMenuExpanded = true },
                  modifier = Modifier.size(32.dp),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.home_logs_source_menu),
                    modifier = Modifier.size(18.dp),
                  )
                }
                DropdownMenu(
                  expanded = logSourceMenuExpanded,
                  onDismissRequest = { logSourceMenuExpanded = false },
                ) {
                  DropdownMenuItem(
                    text = { Text(mainLogsText) },
                    onClick = { selectLogSource(HomeLogSource.MAIN) },
                  )
                  DropdownMenuItem(
                    text = { Text(detailedLogsText) },
                    onClick = { selectLogSource(HomeLogSource.DETAILED) },
                  )
                }
              }
              Text(stringResource(R.string.home_daemon_logs_title), fontWeight = FontWeight.SemiBold)
            }
            AssistChip(
              onClick = { logSourceMenuExpanded = true },
              label = {
                Text(
                  if (selectedLogSource == HomeLogSource.MAIN) mainLogsText else detailedLogsText,
                  style = MaterialTheme.typography.labelSmall,
                )
              },
            )
          }

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
                contentType = { "daemon_log_line" },
              ) { idx ->
                val item: DaemonLogRenderLine = display[idx]
                val line: DaemonLogUiLine = item.line
                val backgroundColor = daemonLogLineBackground(line.level)
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

@Composable
private fun isLightColorScheme(): Boolean =
  MaterialTheme.colorScheme.background.luminance() > 0.5f

@Composable
private fun ThemeAwarePowerButtonContent(
  on: Boolean,
  painterRes: Int,
) {
  if (isLightColorScheme()) {
    LightThemePowerButtonContent(on = on)
  } else {
    Image(
      painter = painterResource(painterRes),
      contentDescription = null,
      modifier = Modifier
        .fillMaxSize()
        .clip(CircleShape),
    )
  }
}

@Composable
private fun LightThemePowerButtonContent(on: Boolean) {
  val scheme = MaterialTheme.colorScheme
  val accent = if (on) Color(0xFF16A34A) else scheme.primary
  val outerBorder = if (on) accent.copy(alpha = 0.42f) else scheme.outline.copy(alpha = 0.34f)
  Box(
    modifier = Modifier
      .fillMaxSize()
      .clip(CircleShape)
      .background(
        Brush.radialGradient(
          colors = listOf(
            Color.White,
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
          ),
          radius = 720f,
        )
      )
      .border(2.dp, outerBorder, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize(0.72f)
        .clip(CircleShape)
        .background(accent.copy(alpha = if (on) 0.13f else 0.10f))
        .border(1.dp, accent.copy(alpha = if (on) 0.34f else 0.24f), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Power,
        contentDescription = null,
        tint = accent,
        modifier = Modifier.fillMaxSize(0.50f),
      )
    }
  }
}

@Composable
private fun daemonLogLineBackground(level: DaemonLogLevel): Color {
  val scheme = MaterialTheme.colorScheme
  return if (isLightColorScheme()) {
    when (level) {
      DaemonLogLevel.WARN -> Color(0xFFFFF4D6)
      DaemonLogLevel.INFO -> Color(0xFFFFE8E8)
      DaemonLogLevel.ERROR -> Color(0xFFFFDAD6)
      DaemonLogLevel.NOTICE -> scheme.surfaceVariant.copy(alpha = 0.56f)
      DaemonLogLevel.OTHER -> scheme.surface.copy(alpha = 0.78f)
    }
  } else {
    when (level) {
      DaemonLogLevel.WARN -> Color(0xFF2F3136)
      DaemonLogLevel.INFO -> Color(0xFF4A1F1F)
      DaemonLogLevel.ERROR -> Color(0xFF5A1B1B)
      DaemonLogLevel.NOTICE -> scheme.surfaceVariant.copy(alpha = 0.70f)
      DaemonLogLevel.OTHER -> scheme.surface.copy(alpha = 0.55f)
    }
  }
}


@Composable
private fun LandscapeHomeContent(
  online: Boolean,
  on: Boolean,
  busy: Boolean,
  scale: Float,
  logTail: String,
  detailedLogTail: String,
  actions: ZdtdActions,
) {
  val noLogDataText = stringResource(R.string.home_no_log_data)
  val mainLogsText = stringResource(R.string.home_logs_main)
  val detailedLogsText = stringResource(R.string.home_logs_detailed)
  var logSourceMenuExpanded by remember { mutableStateOf(false) }
  var selectedLogSource by remember { mutableStateOf(HomeLogSource.MAIN) }
  val activeLogTail = when (selectedLogSource) {
    HomeLogSource.MAIN -> logTail
    HomeLogSource.DETAILED -> detailedLogTail
  }
  val lines = remember(activeLogTail, noLogDataText) {
    activeLogTail.trimEnd()
      .split('\n')
      .asSequence()
      .map { it.trimEnd() }
      .filter { it.isNotBlank() }
      .toList()
      .takeLast(40)
      .ifEmpty { listOf(noLogDataText) }
      .map(::parseDaemonLogUiLine)
      .asReversed()
  }
  val powerPainter = remember(on) { if (on) R.drawable.power_on else R.drawable.power_off }
  val statusText = if (online) stringResource(R.string.home_online) else stringResource(R.string.home_offline)

  Row(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 18.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Card(
      modifier = Modifier
        .weight(0.44f)
        .fillMaxHeight(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
      shape = RoundedCornerShape(26.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        AssistChip(
          onClick = { actions.refreshStatus() },
          label = { Text(stringResource(R.string.home_daemon_status_fmt, statusText)) },
          leadingIcon = {
            val c = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            Box(Modifier.size(10.dp).clip(CircleShape).background(c))
          },
        )
        Spacer(Modifier.height(10.dp))
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(168.dp)
            .scale(scale)
            .clip(CircleShape)
            .clickable(enabled = !busy) { actions.toggleService() },
        ) {
          ThemeAwarePowerButtonContent(
            on = on,
            painterRes = powerPainter,
          )
        }
        Spacer(Modifier.height(10.dp))
        Text(
          if (on) stringResource(R.string.home_power_running) else stringResource(R.string.home_power_stopped),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center,
        )
        Text(
          if (on) stringResource(R.string.home_service_active_hint) else stringResource(R.string.home_service_stopped_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          textAlign = TextAlign.Center,
        )
      }
    }

    Card(
      modifier = Modifier
        .weight(0.56f)
        .fillMaxHeight(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
      shape = RoundedCornerShape(26.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
      Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
              IconButton(
                onClick = { logSourceMenuExpanded = true },
                modifier = Modifier.size(34.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Menu,
                  contentDescription = stringResource(R.string.home_logs_source_menu),
                  modifier = Modifier.size(20.dp),
                )
              }
              DropdownMenu(
                expanded = logSourceMenuExpanded,
                onDismissRequest = { logSourceMenuExpanded = false },
              ) {
                DropdownMenuItem(
                  text = { Text(mainLogsText) },
                  onClick = {
                    selectedLogSource = HomeLogSource.MAIN
                    logSourceMenuExpanded = false
                  },
                )
                DropdownMenuItem(
                  text = { Text(detailedLogsText) },
                  onClick = {
                    selectedLogSource = HomeLogSource.DETAILED
                    logSourceMenuExpanded = false
                  },
                )
              }
            }
            Text(stringResource(R.string.home_daemon_logs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          }
          AssistChip(
            onClick = { logSourceMenuExpanded = true },
            label = {
              Text(
                if (selectedLogSource == HomeLogSource.MAIN) mainLogsText else detailedLogsText,
                style = MaterialTheme.typography.labelSmall,
              )
            },
          )
        }
        Surface(
          tonalElevation = 0.dp,
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
          modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
          LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            items(lines.size) { index ->
              val line = lines[index]
              Surface(
                color = daemonLogLineBackground(line.level),
                shape = RoundedCornerShape(12.dp),
              ) {
                Text(
                  text = if (line.level == DaemonLogLevel.OTHER) line.text else "${line.level.label} ${line.text}",
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = FontFamily.Monospace,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                )
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

private enum class HomeLogSource {
  MAIN,
  DETAILED,
}

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
