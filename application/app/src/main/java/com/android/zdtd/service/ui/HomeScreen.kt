package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.R
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun HomeScreen(
  uiStateFlow: StateFlow<UiState>,
  actions: ZdtdActions,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  // Home collects only the state it renders. When the tab leaves composition,
  // both these collectors and the Canvas animations are disposed automatically.
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
  val landscape = rememberUseLandscapeControlLayout()
  val metrics = rememberHomeLayoutMetrics()

  var heroVisible by remember { mutableStateOf(false) }
  var logsVisible by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    heroVisible = true
    delay(110)
    logsVisible = true
  }

  if (landscape) {
    LandscapeHomeContent(
      online = online,
      on = on,
      busy = busy,
      logTail = logTail,
      detailedLogTail = detailedLogTail,
      actions = actions,
      heroVisible = heroVisible,
      logsVisible = logsVisible,
      topContentPadding = topContentPadding,
      bottomContentPadding = bottomContentPadding,
    )
    return
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = metrics.screenPadding)
      .padding(
        top = topContentPadding + metrics.topPadding,
        bottom = bottomContentPadding + metrics.bottomPadding,
      ),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    AnimatedVisibility(
      visible = heroVisible,
      enter = fadeIn(tween(440, easing = FastOutSlowInEasing)) +
        slideInVertically(
          initialOffsetY = { maxOf(it / 16, 14) },
          animationSpec = tween(440, easing = FastOutSlowInEasing),
        ) +
        scaleIn(
          initialScale = 0.988f,
          animationSpec = tween(440, easing = FastOutSlowInEasing),
        ),
      exit = fadeOut(tween(180)),
    ) {
      ServiceHeroCard(
        online = online,
        on = on,
        busy = busy,
        actions = actions,
        dialSize = metrics.dialSize,
        compact = metrics.compact,
      )
    }

    Spacer(Modifier.height(metrics.sectionSpacing))

    AnimatedVisibility(
      visible = logsVisible,
      enter = fadeIn(tween(520, easing = FastOutSlowInEasing)) +
        expandVertically(tween(520, easing = FastOutSlowInEasing)) +
        slideInVertically(
          initialOffsetY = { maxOf(it / 12, 18) },
          animationSpec = tween(520, easing = FastOutSlowInEasing),
        ),
      exit = fadeOut(tween(180)),
    ) {
      HomeLogsCard(
        logTail = logTail,
        detailedLogTail = detailedLogTail,
        compact = metrics.compact,
        shortHeight = metrics.shortHeight,
        fillHeight = false,
      )
    }
  }
}

@Composable
private fun ServiceHeroCard(
  online: Boolean,
  on: Boolean,
  busy: Boolean,
  actions: ZdtdActions,
  dialSize: Dp,
  compact: Boolean,
  modifier: Modifier = Modifier,
  fillHeight: Boolean = false,
) {
  val visualState = remember(online, on, busy) {
    when {
      busy && on -> HomeServiceVisualState.STOPPING
      busy -> HomeServiceVisualState.STARTING
      !online -> HomeServiceVisualState.UNAVAILABLE
      on -> HomeServiceVisualState.RUNNING
      else -> HomeServiceVisualState.STOPPED
    }
  }
  val scheme = MaterialTheme.colorScheme
  val light = isLightColorScheme()
  val targetAccent = when (visualState) {
    HomeServiceVisualState.RUNNING -> Color(0xFF20C96B)
    HomeServiceVisualState.STARTING -> scheme.secondary
    HomeServiceVisualState.STOPPING -> scheme.tertiary
    HomeServiceVisualState.STOPPED -> scheme.primary
    HomeServiceVisualState.UNAVAILABLE -> scheme.outline
  }
  val accent by animateColorAsState(
    targetValue = targetAccent,
    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
    label = "homeAccent",
  )

  val statusText = when (visualState) {
    HomeServiceVisualState.RUNNING -> stringResource(R.string.home_online)
    HomeServiceVisualState.STARTING -> stringResource(R.string.home_power_starting)
    HomeServiceVisualState.STOPPING -> stringResource(R.string.home_power_stopping)
    HomeServiceVisualState.STOPPED -> stringResource(R.string.home_power_stopped)
    HomeServiceVisualState.UNAVAILABLE -> stringResource(R.string.home_offline)
  }
  val actionText = when {
    busy && on -> stringResource(R.string.home_power_stopping)
    busy -> stringResource(R.string.home_power_starting)
    on -> stringResource(R.string.home_action_stop_service)
    else -> stringResource(R.string.home_action_start_service)
  }
  val hintText = if (on) {
    stringResource(R.string.home_service_active_hint)
  } else {
    stringResource(R.string.home_service_stopped_hint)
  }

  val cardBackground = if (light) {
    scheme.surfaceContainerLowest.copy(alpha = 0.98f)
  } else {
    scheme.surface.copy(alpha = 0.82f)
  }
  val borderColor = accent.copy(alpha = if (on || busy) 0.46f else 0.26f)

  Card(
    modifier = modifier
      .fillMaxWidth()
      .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
      .animateContentSize(animationSpec = tween(320, easing = FastOutSlowInEasing)),
    colors = CardDefaults.cardColors(containerColor = cardBackground),
    shape = RoundedCornerShape(if (compact) 22.dp else 28.dp),
    border = BorderStroke(1.dp, borderColor),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.radialGradient(
            colors = if (light) {
              listOf(accent.copy(alpha = 0.080f), Color.Transparent)
            } else {
              listOf(accent.copy(alpha = 0.095f), Color.Transparent)
            },
            radius = if (compact) 520f else 720f,
          )
        ),
    ) {
      TechGridBackground(
        accent = accent,
        light = light,
        modifier = Modifier.fillMaxSize(),
      )

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(
            horizontal = if (compact) 12.dp else 18.dp,
            vertical = if (compact) 13.dp else 18.dp,
          ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (fillHeight) Arrangement.Center else Arrangement.Top,
      ) {
        ServiceStatusPill(
          text = statusText,
          accent = accent,
          onRefresh = actions::refreshStatus,
          compact = compact,
        )

        Spacer(Modifier.height(if (compact) 8.dp else 13.dp))

        AnimatedPowerDial(
          on = on,
          busy = busy,
          accent = accent,
          size = dialSize,
          enabled = !busy,
          contentDescription = actionText,
          onClick = actions::toggleService,
        )

        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))

        ServiceActionButton(
          text = actionText,
          accent = accent,
          busy = busy,
          enabled = !busy,
          compact = compact,
          onClick = actions::toggleService,
        )

        Spacer(Modifier.height(if (compact) 7.dp else 9.dp))

        AnimatedContent(
          targetState = hintText,
          transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically { it / 5 }) togetherWith
              (fadeOut(tween(160)) + slideOutVertically { -it / 5 })
          },
          label = "homeHint",
        ) { text ->
          Text(
            text = text,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface.copy(alpha = if (light) 0.68f else 0.72f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun ServiceStatusPill(
  text: String,
  accent: Color,
  onRefresh: () -> Unit,
  compact: Boolean,
) {
  val scheme = MaterialTheme.colorScheme
  val light = isLightColorScheme()
  val pulseTransition = rememberInfiniteTransition(label = "statusPulse")
  val pulse by pulseTransition.animateFloat(
    initialValue = 0.50f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(1500, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "statusPulseAlpha",
  )

  Surface(
    modifier = Modifier.clickable(onClick = onRefresh),
    shape = RoundedCornerShape(999.dp),
    color = if (light) accent.copy(alpha = 0.075f) else accent.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = if (compact) 11.dp else 14.dp,
        vertical = if (compact) 6.dp else 7.dp,
      ),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp),
    ) {
      Box(
        modifier = Modifier
          .size(if (compact) 8.dp else 9.dp)
          .graphicsLayer {
            alpha = 0.72f + (pulse * 0.28f)
            scaleX = 0.88f + (pulse * 0.12f)
            scaleY = 0.88f + (pulse * 0.12f)
          }
          .clip(CircleShape)
          .background(accent)
          .border(1.dp, scheme.surface.copy(alpha = 0.45f), CircleShape),
      )
      Crossfade(
        targetState = text,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "serviceStatusText",
      ) { value ->
        Text(
          text = value,
          style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = scheme.onSurface.copy(alpha = 0.92f),
        )
      }
    }
  }
}

@Composable
private fun ServiceActionButton(
  text: String,
  accent: Color,
  busy: Boolean,
  enabled: Boolean,
  compact: Boolean,
  onClick: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val light = isLightColorScheme()
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) 0.975f else 1f,
    animationSpec = tween(120, easing = FastOutSlowInEasing),
    label = "actionPress",
  )

  Surface(
    modifier = Modifier
      .scale(scale)
      .clickable(
        enabled = enabled,
        interactionSource = interaction,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      ),
    shape = RoundedCornerShape(if (compact) 14.dp else 16.dp),
    color = if (light) accent.copy(alpha = 0.075f) else accent.copy(alpha = 0.115f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.48f)),
  ) {
    Row(
      modifier = Modifier.padding(
        horizontal = if (compact) 16.dp else 22.dp,
        vertical = if (compact) 8.dp else 10.dp,
      ),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      AnimatedVisibility(
        visible = busy,
        enter = fadeIn(tween(160)) + expandVertically(tween(180)),
        exit = fadeOut(tween(120)),
      ) {
        Row {
          CircularProgressIndicator(
            modifier = Modifier.size(if (compact) 14.dp else 16.dp),
            color = accent,
            strokeWidth = 2.dp,
          )
          Spacer(Modifier.size(8.dp))
        }
      }
      Crossfade(
        targetState = text,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "actionText",
      ) { value ->
        Text(
          text = value,
          style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = accent,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

@Composable
private fun AnimatedPowerDial(
  on: Boolean,
  busy: Boolean,
  accent: Color,
  size: Dp,
  enabled: Boolean,
  contentDescription: String,
  onClick: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val light = isLightColorScheme()
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val pressScale by animateFloatAsState(
    targetValue = when {
      pressed -> 0.955f
      busy -> 0.985f
      else -> 1f
    },
    animationSpec = tween(150, easing = FastOutSlowInEasing),
    label = "dialPressScale",
  )
  val stateProgress by animateFloatAsState(
    targetValue = if (on) 1f else 0f,
    animationSpec = tween(520, easing = FastOutSlowInEasing),
    label = "dialStateProgress",
  )

  // One animation clock drives all moving dial parts. It exists only while Home
  // is composed, so the decorative motion consumes no UI work on other tabs.
  val motion = rememberInfiniteTransition(label = "powerDialMotion")
  val outerRotation by motion.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(18_000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "outerRotation",
  )
  val innerRotation by motion.animateFloat(
    initialValue = 0f,
    targetValue = -360f,
    animationSpec = infiniteRepeatable(
      animation = tween(12_500, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "innerRotation",
  )
  val orbitRotation by motion.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(if (busy) 2_100 else 5_600, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "orbitRotation",
  )
  val pulse by motion.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(if (busy) 700 else 1_900, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "dialPulse",
  )

  val red = if (light) Color(0xFFCB1728) else Color(0xFFFF2A3D)
  val green = if (light) Color(0xFF159447) else Color(0xFF28E07A)
  val centerAccent by animateColorAsState(
    targetValue = if (on) green else red,
    animationSpec = tween(520, easing = FastOutSlowInEasing),
    label = "centerAccent",
  )
  val outerAccent by animateColorAsState(
    targetValue = if (on) red else accent,
    animationSpec = tween(520, easing = FastOutSlowInEasing),
    label = "outerAccent",
  )

  Canvas(
    modifier = Modifier
      .size(size)
      .scale(pressScale)
      .semantics { this.contentDescription = contentDescription }
      .clickable(
        enabled = enabled,
        interactionSource = interaction,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      ),
  ) {
    val dim = min(this.size.width, this.size.height)
    val c = center
    val outerR = dim * 0.475f
    val ringR = dim * 0.405f
    val ticksR = dim * 0.355f
    val centerR = dim * 0.255f
    val px = density

    // Soft ambient halo. On light theme this becomes a very restrained tint so
    // the control remains readable on white surfaces instead of looking muddy.
    drawCircle(
      brush = Brush.radialGradient(
        colors = if (light) {
          listOf(
            centerAccent.copy(alpha = 0.08f + pulse * 0.025f),
            Color.Transparent,
          )
        } else {
          listOf(
            centerAccent.copy(alpha = 0.12f + pulse * 0.05f),
            Color.Transparent,
          )
        },
        center = c,
        radius = outerR * 1.08f,
      ),
      radius = outerR * 1.08f,
      center = c,
    )

    // Outer mechanical body.
    val bodyColor = if (light) scheme.surfaceContainerHigh else Color(0xFF101318)
    val bodyEdge = if (light) scheme.outline.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.15f)
    drawCircle(color = bodyColor, radius = outerR * 0.94f, center = c)
    drawCircle(color = bodyEdge, radius = outerR * 0.94f, center = c, style = Stroke(width = 1.2f * px))
    drawCircle(color = bodyEdge.copy(alpha = 0.58f), radius = outerR * 0.86f, center = c, style = Stroke(width = 1f * px))

    // Glowing outer ring plus rotating segmented elements.
    drawCircle(
      color = outerAccent.copy(alpha = if (light) 0.12f else 0.15f + pulse * 0.05f),
      radius = outerR,
      center = c,
      style = Stroke(width = 9f * px),
    )
    drawCircle(
      color = outerAccent.copy(alpha = 0.88f),
      radius = outerR,
      center = c,
      style = Stroke(width = 2.0f * px),
    )

    rotate(degrees = outerRotation, pivot = c) {
      for (i in 0 until 6) {
        drawArc(
          color = outerAccent.copy(alpha = if (i % 2 == 0) 0.92f else 0.50f),
          startAngle = i * 60f + 5f,
          sweepAngle = if (i % 2 == 0) 26f else 13f,
          useCenter = false,
          topLeft = Offset(c.x - outerR * 0.91f, c.y - outerR * 0.91f),
          size = Size(outerR * 1.82f, outerR * 1.82f),
          style = Stroke(width = (if (busy) 2.8f else 1.7f) * px, cap = StrokeCap.Round),
        )
      }
    }

    // Inner orbit/radar ring.
    drawCircle(
      color = centerAccent.copy(alpha = if (light) 0.22f else 0.28f),
      radius = ringR,
      center = c,
      style = Stroke(width = 1.2f * px),
    )
    rotate(degrees = innerRotation, pivot = c) {
      for (i in 0 until 8) {
        drawArc(
          color = centerAccent.copy(alpha = if (i % 3 == 0) 0.76f else 0.34f),
          startAngle = i * 45f + 4f,
          sweepAngle = if (i % 2 == 0) 16f else 8f,
          useCenter = false,
          topLeft = Offset(c.x - ringR, c.y - ringR),
          size = Size(ringR * 2f, ringR * 2f),
          style = Stroke(width = 1.25f * px, cap = StrokeCap.Round),
        )
      }
    }

    // Fine rotating ticks.
    val tickCount = 72
    for (i in 0 until tickCount) {
      val degree = (i * 360f / tickCount) + innerRotation * 0.35f
      val rad = degree * PI.toFloat() / 180f
      val major = i % 6 == 0
      val startR = ticksR - (if (major) dim * 0.028f else dim * 0.015f)
      val endR = ticksR
      val start = Offset(c.x + cos(rad) * startR, c.y + sin(rad) * startR)
      val end = Offset(c.x + cos(rad) * endR, c.y + sin(rad) * endR)
      drawLine(
        color = if (major) centerAccent.copy(alpha = 0.78f) else scheme.onSurface.copy(alpha = if (light) 0.18f else 0.24f),
        start = start,
        end = end,
        strokeWidth = if (major) 1.3f * px else 0.8f * px,
        cap = StrokeCap.Round,
      )
    }

    // Orbiting highlights. A broad transparent point is the glow, a small solid
    // point is the actual light source.
    val orbitRad = orbitRotation * PI.toFloat() / 180f
    val orbitR = outerR * 0.90f
    val orbitPoint = Offset(c.x + cos(orbitRad) * orbitR, c.y + sin(orbitRad) * orbitR)
    drawCircle(
      color = outerAccent.copy(alpha = if (light) 0.08f else 0.15f),
      radius = (7.5f + pulse * 3f) * px,
      center = orbitPoint,
    )
    drawCircle(
      color = outerAccent.copy(alpha = 0.95f),
      radius = 2.1f * px,
      center = orbitPoint,
    )

    val secondRad = (orbitRotation + 150f) * PI.toFloat() / 180f
    val secondPoint = Offset(c.x + cos(secondRad) * ringR, c.y + sin(secondRad) * ringR)
    drawCircle(
      color = centerAccent.copy(alpha = if (light) 0.08f else 0.15f),
      radius = (6f + pulse * 2f) * px,
      center = secondPoint,
    )
    drawCircle(color = centerAccent.copy(alpha = 0.92f), radius = 1.7f * px, center = secondPoint)

    // Central button body and state ring.
    val centerSurface = if (light) scheme.surfaceContainerLowest else Color(0xFF171B20)
    drawCircle(
      brush = Brush.radialGradient(
        colors = if (light) {
          listOf(Color.White, scheme.surfaceContainerLow, scheme.surfaceContainerHigh)
        } else {
          listOf(Color(0xFF242A30), centerSurface, Color(0xFF0D1014))
        },
        center = Offset(c.x - centerR * 0.20f, c.y - centerR * 0.24f),
        radius = centerR * 1.5f,
      ),
      radius = centerR,
      center = c,
    )
    drawCircle(
      color = centerAccent.copy(alpha = if (light) 0.12f else 0.18f + pulse * 0.05f),
      radius = centerR * 1.02f,
      center = c,
      style = Stroke(width = 7f * px),
    )
    drawCircle(
      color = centerAccent.copy(alpha = 0.90f),
      radius = centerR,
      center = c,
      style = Stroke(width = 2f * px),
    )

    // Power glyph drawn as vector primitives — no raster resource is involved.
    val glyphR = centerR * 0.39f
    val glyphWidth = maxOf(2.6.dp.toPx(), dim / 86f)
    drawArc(
      color = centerAccent,
      startAngle = -38f,
      sweepAngle = 256f,
      useCenter = false,
      topLeft = Offset(c.x - glyphR, c.y - glyphR),
      size = Size(glyphR * 2f, glyphR * 2f),
      style = Stroke(width = glyphWidth, cap = StrokeCap.Round),
    )
    drawLine(
      color = centerAccent,
      start = Offset(c.x, c.y - glyphR * 1.13f),
      end = Offset(c.x, c.y - glyphR * 0.10f),
      strokeWidth = glyphWidth,
      cap = StrokeCap.Round,
    )

    // Busy state adds one fast-looking bright segment without allocating a
    // second animation clock.
    if (busy) {
      drawArc(
        color = accent.copy(alpha = 0.95f),
        startAngle = orbitRotation - 24f,
        sweepAngle = 48f,
        useCenter = false,
        topLeft = Offset(c.x - ringR * 0.82f, c.y - ringR * 0.82f),
        size = Size(ringR * 1.64f, ringR * 1.64f),
        style = Stroke(width = 2.5f * px, cap = StrokeCap.Round),
      )
    }

    // stateProgress intentionally participates in drawing so the Canvas is
    // smoothly invalidated across on/off transitions in addition to color.
    if (stateProgress in 0.001f..0.999f) {
      drawCircle(
        color = centerAccent.copy(alpha = 0.04f + 0.05f * stateProgress),
        radius = centerR * (1.12f + 0.04f * stateProgress),
        center = c,
      )
    }
  }
}

@Composable
private fun TechGridBackground(
  accent: Color,
  light: Boolean,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  Canvas(modifier = modifier) {
    val grid = 26.dp.toPx()
    val lineColor = if (light) {
      scheme.outline.copy(alpha = 0.045f)
    } else {
      scheme.onSurface.copy(alpha = 0.028f)
    }
    var x = 0f
    while (x <= size.width) {
      drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.7.dp.toPx())
      x += grid
    }
    var y = 0f
    while (y <= size.height) {
      drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.7.dp.toPx())
      y += grid
    }

    // Sparse circuit-like decorative traces. They are static geometry, not an
    // asset, and remain intentionally subtle in both themes.
    val trace = accent.copy(alpha = if (light) 0.055f else 0.045f)
    val mid = size.height * 0.52f
    val step = 18.dp.toPx()
    drawLine(trace, Offset(0f, mid - step), Offset(size.width * 0.17f, mid - step), 0.8.dp.toPx())
    drawLine(trace, Offset(size.width * 0.17f, mid - step), Offset(size.width * 0.23f, mid - step * 2f), 0.8.dp.toPx())
    drawLine(trace, Offset(size.width * 0.77f, mid + step * 1.4f), Offset(size.width * 0.84f, mid + step * 0.4f), 0.8.dp.toPx())
    drawLine(trace, Offset(size.width * 0.84f, mid + step * 0.4f), Offset(size.width, mid + step * 0.4f), 0.8.dp.toPx())
  }
}

@Composable
private fun HomeLogsCard(
  logTail: String,
  detailedLogTail: String,
  compact: Boolean,
  shortHeight: Boolean,
  fillHeight: Boolean,
  modifier: Modifier = Modifier,
) {
  val noLogDataText = stringResource(R.string.home_no_log_data)
  val mainLogsText = stringResource(R.string.home_logs_main)
  val detailedLogsText = stringResource(R.string.home_logs_detailed)
  val scheme = MaterialTheme.colorScheme
  val light = isLightColorScheme()

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

  val activeLogTail = when (selectedLogSource) {
    HomeLogSource.MAIN -> logTail
    HomeLogSource.DETAILED -> detailedLogTail
  }
  val logLines: List<DaemonLogUiLine> = remember(activeLogTail, noLogDataText) {
    val value = activeLogTail.trimEnd()
    if (value.isBlank()) {
      listOf(DaemonLogUiLine(raw = noLogDataText, level = DaemonLogLevel.OTHER, text = noLogDataText))
    } else {
      value.split('\n')
        .asSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()
        .takeLast(100)
        .map(::parseDaemonLogUiLine)
    }
  }

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

  fun mergeSlidingTail(
    lines: List<DaemonLogUiLine>,
    displayed: List<DaemonLogRenderLine>,
  ): List<DaemonLogRenderLine>? {
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
    mutableStateOf(
      renderize(
        listOf(DaemonLogUiLine(raw = noLogDataText, level = DaemonLogLevel.OTHER, text = noLogDataText))
      )
    )
  }
  var logRevealInitialized by remember { mutableStateOf(false) }
  val logRevealDelayMs = 28L
  val newestLogRenderId = displayedLogLines.lastOrNull()?.id ?: -1L
  val listState = rememberLazyListState()
  var followNewestLogLine by remember { mutableStateOf(true) }
  var userScrolledAwayDuringGesture by remember { mutableStateOf(false) }
  var manualScrollIdleNonce by remember { mutableLongStateOf(0L) }
  val autoReleaseToBottomDelayMs = 5_000L

  fun isLogListNearBottom(): Boolean =
    listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 96

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
          displayedLogLines = (
            displayedLogLines + DaemonLogRenderLine(id = nextLogRenderId++, line = line)
            ).takeLast(100)
          delay(logRevealDelayMs)
        }
      }
    } else {
      displayedLogLines = mergeSlidingTail(logLines, displayedLogLines) ?: renderize(logLines)
    }
  }

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
      delay(autoReleaseToBottomDelayMs)
      if (!listState.isScrollInProgress && !isLogListNearBottom()) {
        followNewestLogLine = true
        listState.animateScrollToItem(0)
      }
    }
  }

  LaunchedEffect(pendingImmediateTailSnapNonce, displayedLogLines.size) {
    if (pendingImmediateTailSnapNonce > 0L) {
      followNewestLogLine = true
      listState.scrollToItem(0, 0)
      pendingImmediateTailSnapNonce = 0L
    }
  }

  LaunchedEffect(selectedLogSource, newestLogRenderId) {
    if ((followNewestLogLine || isLogListNearBottom()) && !listState.isScrollInProgress) {
      followNewestLogLine = true
      if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8) {
        listState.animateScrollToItem(0)
      } else {
        listState.scrollToItem(0, 0)
      }
    }
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
      .animateContentSize(animationSpec = tween(320, easing = FastOutSlowInEasing)),
    colors = CardDefaults.cardColors(
      containerColor = if (light) scheme.surfaceContainerLowest.copy(alpha = 0.98f)
      else scheme.surface.copy(alpha = 0.80f),
    ),
    shape = RoundedCornerShape(if (compact) 18.dp else 22.dp),
    border = BorderStroke(1.dp, scheme.outline.copy(alpha = if (light) 0.22f else 0.18f)),
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(if (compact) 11.dp else 14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp),
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            shape = RoundedCornerShape(if (compact) 9.dp else 10.dp),
            color = scheme.primary.copy(alpha = if (light) 0.08f else 0.10f),
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.24f)),
          ) {
            Box(
              modifier = Modifier.size(if (compact) 31.dp else 35.dp),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Filled.Subject,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(if (compact) 17.dp else 19.dp),
              )
            }
          }
          Text(
            text = stringResource(R.string.home_daemon_logs_title),
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
        }

        Box {
          Surface(
            modifier = Modifier.clickable { logSourceMenuExpanded = true },
            shape = RoundedCornerShape(if (compact) 11.dp else 12.dp),
            color = scheme.surfaceContainer.copy(alpha = if (light) 0.86f else 0.56f),
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.28f)),
          ) {
            Row(
              modifier = Modifier.padding(
                horizontal = if (compact) 9.dp else 11.dp,
                vertical = if (compact) 6.dp else 7.dp,
              ),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = stringResource(R.string.home_logs_source_menu),
                modifier = Modifier.size(if (compact) 15.dp else 17.dp),
                tint = scheme.onSurface.copy(alpha = 0.75f),
              )
              AnimatedContent(
                targetState = selectedLogSource,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                label = "logSourceLabel",
              ) { source ->
                Text(
                  text = if (source == HomeLogSource.MAIN) mainLogsText else detailedLogsText,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                )
              }
              Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 15.dp else 17.dp),
                tint = scheme.onSurface.copy(alpha = 0.62f),
              )
            }
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
      }

      Spacer(Modifier.height(if (compact) 9.dp else 11.dp))

      Surface(
        tonalElevation = 0.dp,
        color = if (light) scheme.surfaceContainerLow.copy(alpha = 0.78f)
        else scheme.surfaceContainerLowest.copy(alpha = 0.52f),
        shape = RoundedCornerShape(if (compact) 13.dp else 15.dp),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = if (light) 0.18f else 0.14f)),
        modifier = if (fillHeight) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
      ) {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .then(
              if (fillHeight) Modifier.fillMaxHeight()
              else Modifier.heightIn(
                min = if (shortHeight) 110.dp else 126.dp,
                max = if (shortHeight) 190.dp else 238.dp,
              )
            )
            .padding(horizontal = if (compact) 7.dp else 8.dp, vertical = if (compact) 7.dp else 8.dp),
          state = listState,
          reverseLayout = true,
          verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
        ) {
          val display = displayedLogLines.asReversed()
          items(
            count = display.size,
            key = { index -> display[index].id },
            contentType = { "daemon_log_line" },
          ) { index ->
            val item = display[index]
            HomeLogRow(
              item = item,
              compact = compact,
              modifier = Modifier.animateItem(
                fadeInSpec = tween(250, easing = FastOutSlowInEasing),
                placementSpec = tween(300, easing = FastOutSlowInEasing),
                fadeOutSpec = tween(130),
              ),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun HomeLogRow(
  item: DaemonLogRenderLine,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  val line = item.line
  val scheme = MaterialTheme.colorScheme
  val light = isLightColorScheme()
  val accent = daemonLogAccent(line.level)
  val rowVisibleState = remember(item.id) {
    MutableTransitionState(false).apply { targetState = true }
  }

  AnimatedVisibility(
    visibleState = rowVisibleState,
    modifier = modifier,
    enter = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
      slideInVertically(
        initialOffsetY = { maxOf(it / 4, 14) },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
      ) +
      expandVertically(tween(260, easing = FastOutSlowInEasing)),
    exit = fadeOut(tween(120)),
  ) {
    Surface(
      color = if (light) scheme.surfaceContainerLowest.copy(alpha = 0.96f)
      else scheme.surfaceContainerLow.copy(alpha = 0.72f),
      shape = RoundedCornerShape(if (compact) 10.dp else 11.dp),
      border = BorderStroke(1.dp, accent.copy(alpha = if (line.level == DaemonLogLevel.OTHER) 0.10f else 0.23f)),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = if (compact) 8.dp else 9.dp, vertical = if (compact) 6.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 8.dp),
      ) {
        Box(
          modifier = Modifier
            .size(if (compact) 6.dp else 7.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (line.level == DaemonLogLevel.OTHER) 0.42f else 0.92f)),
        )

        if (line.level != DaemonLogLevel.OTHER) {
          Surface(
            shape = RoundedCornerShape(7.dp),
            color = accent.copy(alpha = if (light) 0.08f else 0.12f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
          ) {
            Text(
              text = line.level.label,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
              style = MaterialTheme.typography.labelSmall,
              color = accent,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Monospace,
            )
          }
        }

        Text(
          text = line.text,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = scheme.onSurface.copy(alpha = 0.90f),
          softWrap = true,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun LandscapeHomeContent(
  online: Boolean,
  on: Boolean,
  busy: Boolean,
  logTail: String,
  detailedLogTail: String,
  actions: ZdtdActions,
  heroVisible: Boolean,
  logsVisible: Boolean,
  topContentPadding: Dp,
  bottomContentPadding: Dp,
) {
  Row(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp)
      .padding(top = topContentPadding + 10.dp, bottom = bottomContentPadding + 10.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AnimatedVisibility(
      visible = heroVisible,
      modifier = Modifier.weight(0.44f).fillMaxHeight(),
      enter = fadeIn(tween(380, easing = FastOutSlowInEasing)) + scaleIn(initialScale = 0.985f, animationSpec = tween(380, easing = FastOutSlowInEasing)),
      exit = fadeOut(tween(150)),
    ) {
      ServiceHeroCard(
        online = online,
        on = on,
        busy = busy,
        actions = actions,
        dialSize = 158.dp,
        compact = true,
        modifier = Modifier.fillMaxSize(),
        fillHeight = true,
      )
    }

    AnimatedVisibility(
      visible = logsVisible,
      modifier = Modifier.weight(0.56f).fillMaxHeight(),
      enter = fadeIn(tween(450, easing = FastOutSlowInEasing)) +
        slideInVertically(
          initialOffsetY = { it / 14 },
          animationSpec = tween(450, easing = FastOutSlowInEasing),
        ),
      exit = fadeOut(tween(150)),
    ) {
      HomeLogsCard(
        logTail = logTail,
        detailedLogTail = detailedLogTail,
        compact = true,
        shortHeight = true,
        fillHeight = true,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun daemonLogAccent(level: DaemonLogLevel): Color {
  val scheme = MaterialTheme.colorScheme
  return when (level) {
    DaemonLogLevel.WARN -> scheme.tertiary
    DaemonLogLevel.INFO -> scheme.primary
    DaemonLogLevel.ERROR -> scheme.error
    DaemonLogLevel.NOTICE -> scheme.secondary
    DaemonLogLevel.OTHER -> scheme.outline
  }
}

@Composable
private fun isLightColorScheme(): Boolean =
  MaterialTheme.colorScheme.background.luminance() > 0.5f

@Composable
private fun rememberHomeLayoutMetrics(): HomeLayoutMetrics {
  val configuration = LocalConfiguration.current
  val width = configuration.screenWidthDp
  val height = configuration.screenHeightDp
  return remember(width, height) {
    val compact = width < 360 || height < 700
    val short = height < 760
    when {
      width < 340 -> HomeLayoutMetrics(
        compact = true,
        shortHeight = short,
        screenPadding = 10.dp,
        topPadding = 8.dp,
        bottomPadding = 12.dp,
        sectionSpacing = 10.dp,
        dialSize = 164.dp,
      )
      width < 380 -> HomeLayoutMetrics(
        compact = compact,
        shortHeight = short,
        screenPadding = 12.dp,
        topPadding = 10.dp,
        bottomPadding = 14.dp,
        sectionSpacing = 12.dp,
        dialSize = if (short) 172.dp else 184.dp,
      )
      width < 430 -> HomeLayoutMetrics(
        compact = false,
        shortHeight = short,
        screenPadding = 16.dp,
        topPadding = 12.dp,
        bottomPadding = 16.dp,
        sectionSpacing = 14.dp,
        dialSize = if (short) 188.dp else 204.dp,
      )
      else -> HomeLayoutMetrics(
        compact = false,
        shortHeight = short,
        screenPadding = 20.dp,
        topPadding = 14.dp,
        bottomPadding = 18.dp,
        sectionSpacing = 16.dp,
        dialSize = if (width >= 600) 224.dp else 214.dp,
      )
    }
  }
}

private data class HomeLayoutMetrics(
  val compact: Boolean,
  val shortHeight: Boolean,
  val screenPadding: Dp,
  val topPadding: Dp,
  val bottomPadding: Dp,
  val sectionSpacing: Dp,
  val dialSize: Dp,
)

private enum class HomeServiceVisualState {
  RUNNING,
  STARTING,
  STOPPING,
  STOPPED,
  UNAVAILABLE,
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
