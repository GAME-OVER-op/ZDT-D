package com.android.zdtd.service.ui.t2s

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.api.T2sApiClient
import com.android.zdtd.service.api.T2sBackend
import com.android.zdtd.service.api.T2sConnection
import com.android.zdtd.service.api.T2sPollResult
import com.android.zdtd.service.api.T2sPort
import com.android.zdtd.service.api.T2sState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private data class SpeedSample(val downBps: Double, val upBps: Double, val active: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun T2sPanelScreen(
  title: String,
  scope: String,
  port: Int,
  client: T2sApiClient,
  onClose: () -> Unit,
) {
  val snackHost = remember { SnackbarHostState() }
  val coroutineScope = rememberCoroutineScope()
  val samples = remember { mutableStateListOf<SpeedSample>() }
  var selectedTab by remember { mutableIntStateOf(0) }
  var poll by remember { mutableStateOf<T2sPollResult?>(null) }
  var lastState by remember { mutableStateOf<T2sState?>(null) }
  var lastWallMs by remember { mutableStateOf(0L) }
  var downBps by remember { mutableStateOf(0.0) }
  var upBps by remember { mutableStateOf(0.0) }
  var errorText by remember { mutableStateOf<String?>(null) }
  var firstLoad by remember { mutableStateOf(true) }

  fun showSnack(message: String) {
    coroutineScope.launch { snackHost.showSnackbar(message) }
  }

  LaunchedEffect(port) {
    while (isActive) {
      try {
        val next = withContext(Dispatchers.IO) { client.poll() }
        val now = System.currentTimeMillis()
        val prev = lastState
        if (prev != null && lastWallMs > 0L) {
          val dt = ((now - lastWallMs).coerceAtLeast(250L)).toDouble() / 1000.0
          downBps = ((next.state.bytesDown - prev.bytesDown).coerceAtLeast(0L)).toDouble() / dt
          upBps = ((next.state.bytesUp - prev.bytesUp).coerceAtLeast(0L)).toDouble() / dt
        }
        poll = next
        lastState = next.state
        lastWallMs = now
        errorText = null
        firstLoad = false
        val prevSample = samples.lastOrNull()
        val smoothDown = prevSample?.let { it.downBps * 0.65 + downBps * 0.35 } ?: downBps
        val smoothUp = prevSample?.let { it.upBps * 0.65 + upBps * 0.35 } ?: upBps
        val smoothActive = prevSample?.let { (it.active * 0.65 + next.state.activeConnections * 0.35).toInt() } ?: next.state.activeConnections
        samples += SpeedSample(downBps = smoothDown, upBps = smoothUp, active = smoothActive)
        while (samples.size > 72) samples.removeAt(0)
        delay(next.suggestedIntervalMs.coerceAtLeast(next.minIntervalMs))
      } catch (t: Throwable) {
        errorText = t.message ?: "t2s API недоступен"
        firstLoad = false
        delay(1500L)
      }
    }
  }

  val state = poll?.state ?: T2sState()
  val statusColor = when {
    errorText != null -> MaterialTheme.colorScheme.error
    poll?.collecting == true -> Color(0xFFF59E0B)
    poll != null -> Color(0xFF22C55E)
    else -> MaterialTheme.colorScheme.outline
  }
  val statusText = when {
    errorText != null -> "Offline"
    poll?.collecting == true -> "Collecting"
    poll != null -> "Online"
    else -> "Connecting"
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackHost) },
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(horizontal = 8.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
          Column(Modifier.weight(1f)) {
            Text(title.ifBlank { "t2s" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(scope.ifBlank { "127.0.0.1:$port" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          StatusPill(text = statusText, color = statusColor)
        }
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            listOf(
              MaterialTheme.colorScheme.background,
              MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
              MaterialTheme.colorScheme.background,
            )
          )
        )
        .padding(padding),
    ) {
      ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
        listOf("Обзор", "Соединения", "Backends", "Настройки").forEachIndexed { index, text ->
          Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(text) })
        }
      }
      if (firstLoad) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      } else {
        AnimatedContent(targetState = selectedTab, transitionSpec = { androidx.compose.animation.fadeIn(tween(140)) togetherWith androidx.compose.animation.fadeOut(tween(120)) }, label = "t2sTab") { tab ->
          val commonModifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
          when (tab) {
            0 -> OverviewTab(commonModifier, state, port, downBps, upBps, samples, poll?.collecting == true, errorText)
            1 -> ConnectionsTab(commonModifier, state.connections) { cid ->
              coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { client.killConnection(cid) }.getOrDefault(false) }
                showSnack(if (ok) "Соединение закрыто" else "Не удалось закрыть соединение")
              }
            }
            2 -> BackendsTab(commonModifier, state.backends, onAdd = { host, backendPort, user, pass ->
              coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { client.addBackend(host, backendPort, user, pass) }.getOrDefault(false) }
                showSnack(if (ok) "Backend добавлен" else "Не удалось добавить backend")
              }
            }, onRemove = { addr ->
              coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { client.removeBackend(addr) }.getOrDefault(false) }
                showSnack(if (ok) "Backend удалён" else "Не удалось удалить backend")
              }
            })
            else -> SettingsTab(commonModifier, state, poll, onSetLimit = { value ->
              coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { client.setDownloadLimit(value) }.getOrDefault(false) }
                showSnack(if (ok) "Лимит обновлён" else "Не удалось обновить лимит")
              }
            })
          }
        }
      }
    }
  }
}

@Composable
private fun OverviewTab(modifier: Modifier, state: T2sState, port: Int, downBps: Double, upBps: Double, samples: List<SpeedSample>, collecting: Boolean, errorText: String?) {
  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          IconBubble(color = Color(0xFF38BDF8)) { Icon(Icons.Filled.Speed, contentDescription = null, tint = Color(0xFF38BDF8)) }
          Column(Modifier.weight(1f)) {
            Text("t2s runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Web: 127.0.0.1:$port • PID: ${state.instance.pid.takeIf { it > 0 } ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          StatusPill(if (collecting) "Сбор данных" else if (errorText == null) "Live" else "Offline", if (errorText == null) Color(0xFF22C55E) else MaterialTheme.colorScheme.error)
        }
        if (errorText != null) InfoText(errorText, MaterialTheme.colorScheme.error)
      }
    }
    item {
      PanelCard {
        Text("Скорость", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          AnimatedMetricBox("↓ Download", downBps, ::formatSpeed, Color(0xFF38BDF8), Modifier.weight(1f))
          AnimatedMetricBox("↑ Upload", upBps, ::formatSpeed, Color(0xFF8B5CF6), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          AnimatedMetricBox("Всего ↓", state.bytesDown.toDouble(), { formatBytes(it.toLong()) }, Color(0xFF38BDF8), Modifier.weight(1f))
          AnimatedMetricBox("Всего ↑", state.bytesUp.toDouble(), { formatBytes(it.toLong()) }, Color(0xFF8B5CF6), Modifier.weight(1f))
        }
      }
    }
    item { SpeedChartCard(samples) }
    item {
      PanelCard {
        Text("Состояние", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          AnimatedMetricBox("Соединения", state.activeConnections.toDouble(), { it.toInt().toString() }, Color(0xFF22C55E), Modifier.weight(1f))
          AnimatedMetricBox("Ошибки", state.errors.toDouble(), { it.toLong().toString() }, MaterialTheme.colorScheme.error, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          AnimatedMetricBox("SOCKS OK", state.socksOk.toDouble(), { it.toLong().toString() }, Color(0xFF22C55E), Modifier.weight(1f))
          AnimatedMetricBox("SOCKS FAIL", state.socksFail.toDouble(), { it.toLong().toString() }, Color(0xFFF97316), Modifier.weight(1f))
        }
      }
    }
    item { PortsCard(state.ports) }
  }
}

@Composable
private fun ConnectionsTab(modifier: Modifier, connections: List<T2sConnection>, onKill: (String) -> Unit) {
  var filter by remember { mutableStateOf("") }
  val filtered = remember(connections, filter) {
    val q = filter.trim().lowercase()
    if (q.isEmpty()) connections else connections.filter { it.domain.lowercase().contains(q) || it.peer.lowercase().contains(q) || it.dstIp.lowercase().contains(q) }
  }
  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item { OutlinedTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Фильтр по домену/IP") }) }
    if (filtered.isEmpty()) item { EmptyCard("Активных соединений нет") }
    items(filtered, key = { it.cid }) { conn -> ConnectionCard(conn, onKill) }
  }
}

@Composable
private fun BackendsTab(modifier: Modifier, backends: List<T2sBackend>, onAdd: (String, Int, String, String) -> Unit, onRemove: (String) -> Unit) {
  var showAdd by remember { mutableStateOf(false) }
  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          IconBubble(Color(0xFF22C55E)) { Icon(Icons.Filled.NetworkCheck, contentDescription = null, tint = Color(0xFF22C55E)) }
          Column(Modifier.weight(1f)) {
            Text("SOCKS backends", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Проверка и состояние обновляются автоматически без кнопки обновления.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить") }
        }
      }
    }
    if (backends.isEmpty()) item { EmptyCard("Backends не найдены") }
    items(backends, key = { it.addr }) { backend -> BackendCard(backend, onRemove) }
  }
  if (showAdd) AddBackendDialog(onDismiss = { showAdd = false }, onAdd = { host, p, u, pass -> showAdd = false; onAdd(host, p, u, pass) })
}

@Composable
private fun SettingsTab(modifier: Modifier, state: T2sState, poll: T2sPollResult?, onSetLimit: (Double) -> Unit) {
  var limitText by remember(state.downloadLimitMbit) { mutableStateOf(if (state.downloadLimitMbit > 0.0) "%.2f".format(state.downloadLimitMbit) else "0") }
  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(top = 14.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      PanelCard {
        Text("Лимиты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(value = limitText, onValueChange = { limitText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("Download Mbit/s") }, supportingText = { Text("0 = без ограничения") })
          Button(onClick = { onSetLimit(limitText.replace(',', '.').toDoubleOrNull() ?: 0.0) }) { Text("Сохранить") }
        }
      }
    }
    item {
      PanelCard {
        Text("Runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        InfoRow("Backend mode", state.runtime.backendMode.ifBlank { state.instance.backendMode })
        InfoRow("Max connections", state.runtime.maxConns.toString())
        InfoRow("Buffer size", state.runtime.bufferSize.toString())
        InfoRow("Idle timeout", "${state.runtime.idleTimeout}s")
        InfoRow("Connect timeout", "${state.runtime.connectTimeout}s")
        InfoRow("Priority speed aware", if (state.runtime.prioritySpeedAware) "on" else "off")
      }
    }
    item {
      PanelCard {
        Text("Опрос API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        InfoRow("Service collecting", if (poll?.collecting == true) "yes" else "no")
        InfoRow("Suggested interval", "${poll?.suggestedIntervalMs ?: 1000} ms")
        InfoRow("Min interval", "${poll?.minIntervalMs ?: 750} ms")
        InfoText("Экран сам соблюдает интервал, который возвращает t2s, чтобы не дёргать сбор состояния слишком часто.", MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    item {
      PanelCard {
        Text("Instance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        InfoRow("Scope", state.instance.scope)
        InfoRow("Instance ID", state.instance.instanceId)
        InfoRow("Program", state.instance.program)
        InfoRow("Profile", state.instance.profile.ifBlank { "—" })
        InfoRow("Listen", state.instance.listenPort.toString())
        InfoRow("External", if (state.instance.externalPort > 0) state.instance.externalPort.toString() else "disabled")
      }
    }
  }
}

@Composable
private fun SpeedChartCard(samples: List<SpeedSample>) = PanelCard {
  Text("График скорости", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
  if (samples.size < 2) {
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
      Text("Ожидание данных…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  } else {
    val downColor = Color(0xFF38BDF8)
    val upColor = Color(0xFF8B5CF6)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f)
    val maxSpeed = max(1.0, samples.maxOf { max(it.downBps, it.upBps) })
    val scale = speedScale(maxSpeed)
    val labels = listOf(1.0, 0.66, 0.33, 0.0).map { formatScaledSpeed(maxSpeed * it, scale) }
    val animatedSampleCount by animateFloatAsState(
      targetValue = samples.size.toFloat(),
      animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
      label = "t2s_chart_sample_count",
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f).height(190.dp)) {
        Canvas(Modifier.fillMaxSize()) {
          val chartWidth = size.width
          val chartHeight = size.height
          val revealProgress = if (samples.size <= 1) 1f else ((animatedSampleCount - 1f) / (samples.size - 1f)).coerceIn(0f, 1f)
          val visibleWidth = chartWidth * revealProgress

          repeat(4) { i ->
            val y = chartHeight * i / 3f
            drawLine(gridColor, Offset(0f, y), Offset(chartWidth, y), strokeWidth = 1.dp.toPx())
          }

          fun pointsFor(selector: (SpeedSample) -> Double): List<Offset> = samples.mapIndexed { i, sample ->
            val x = if (samples.size == 1) 0f else chartWidth * i / (samples.size - 1).toFloat()
            val y = (chartHeight - (selector(sample) / maxSpeed).toFloat() * chartHeight).coerceIn(0f, chartHeight)
            Offset(x, y)
          }

          fun smoothPath(points: List<Offset>): Path {
            val path = Path()
            if (points.isEmpty()) return path
            path.moveTo(points.first().x, points.first().y)
            for (i in 0 until points.lastIndex) {
              val current = points[i]
              val next = points[i + 1]
              val previous = points.getOrNull(i - 1) ?: current
              val afterNext = points.getOrNull(i + 2) ?: next
              val cp1 = Offset(
                current.x + (next.x - previous.x) * 0.16f,
                current.y + (next.y - previous.y) * 0.16f,
              )
              val cp2 = Offset(
                next.x - (afterNext.x - current.x) * 0.16f,
                next.y - (afterNext.y - current.y) * 0.16f,
              )
              path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, next.x, next.y)
            }
            return path
          }

          fun visiblePoints(points: List<Offset>): List<Offset> {
            if (points.isEmpty()) return emptyList()
            val out = ArrayList<Offset>()
            for (i in points.indices) {
              val point = points[i]
              if (point.x <= visibleWidth) {
                out += point
              } else {
                val previous = points.getOrNull(i - 1)
                if (previous != null && previous.x < visibleWidth) {
                  val span = (point.x - previous.x).coerceAtLeast(1f)
                  val t = ((visibleWidth - previous.x) / span).coerceIn(0f, 1f)
                  out += Offset(
                    x = visibleWidth,
                    y = previous.y + (point.y - previous.y) * t,
                  )
                }
                break
              }
            }
            if (out.isEmpty()) out += Offset(0f, points.first().y)
            return out
          }

          fun filledPath(points: List<Offset>): Path {
            val path = smoothPath(points)
            if (points.isNotEmpty()) {
              path.lineTo(points.last().x, chartHeight)
              path.lineTo(points.first().x, chartHeight)
              path.close()
            }
            return path
          }

          val downPoints = visiblePoints(pointsFor { it.downBps })
          val upPoints = visiblePoints(pointsFor { it.upBps })
          drawPath(filledPath(downPoints), color = downColor.copy(alpha = 0.12f))
          drawPath(filledPath(upPoints), color = upColor.copy(alpha = 0.10f))
          drawPath(smoothPath(downPoints), color = downColor, style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round))
          drawPath(smoothPath(upPoints), color = upColor, style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round))
        }
      }
      Column(
        modifier = Modifier.width(72.dp).height(190.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
      ) {
        labels.forEach { label ->
          Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatusPill("↓ download", downColor)
      StatusPill("↑ upload", upColor)
    }
  }
}

@Composable private fun PortsCard(ports: List<T2sPort>) = PanelCard {
  Text("Порты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
  if (ports.isEmpty()) InfoText("Порты не загружены", MaterialTheme.colorScheme.onSurfaceVariant)
  ports.forEach { p ->
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(p.label.ifBlank { "port" }, fontWeight = FontWeight.SemiBold)
        Text(p.listen, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("active ${p.active} • ↓ ${formatBytes(p.down)} • ↑ ${formatBytes(p.up)}", style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable private fun ConnectionCard(conn: T2sConnection, onKill: (String) -> Unit) = PanelCard {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    IconBubble(Color(0xFF38BDF8)) { Icon(Icons.Filled.Memory, contentDescription = null, tint = Color(0xFF38BDF8)) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(conn.domain.ifBlank { "Domain not resolved" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("${conn.peer} → ${conn.dstIp}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("${conn.ingress} • ${conn.mode} • ${conn.server} • ↓ ${formatBytes(conn.down)} ↑ ${formatBytes(conn.up)}", style = MaterialTheme.typography.bodySmall)
    }
    IconButton(onClick = { onKill(conn.cid) }) { Icon(Icons.Filled.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
  }
}

@Composable private fun BackendCard(backend: T2sBackend, onRemove: (String) -> Unit) = PanelCard {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    IconBubble(if (backend.healthy) Color(0xFF22C55E) else Color(0xFFF97316)) { Box(Modifier.size(10.dp).background(if (backend.healthy) Color(0xFF22C55E) else Color(0xFFF97316), CircleShape)) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(backend.addr, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("${backend.state} • socks ${backend.socksPingMs?.let { "%.0f ms".format(it) } ?: "—"} • internet ${backend.internetPingMs?.let { "%.0f ms".format(it) } ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("traffic ${formatBytes(backend.totalBytes)} • speed ${formatSpeed(backend.recentBps)}${if (backend.degraded) " • degraded" else ""}", style = MaterialTheme.typography.bodySmall)
      if (backend.lastError.isNotBlank()) Text(backend.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    IconButton(onClick = { onRemove(backend.addr) }) { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
  }
}

@Composable private fun AddBackendDialog(onDismiss: () -> Unit, onAdd: (String, Int, String, String) -> Unit) {
  var host by remember { mutableStateOf("127.0.0.1") }
  var port by remember { mutableStateOf("") }
  var user by remember { mutableStateOf("") }
  var pass by remember { mutableStateOf("") }
  AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить SOCKS backend") }, text = {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true)
      OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
      OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
      OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true)
    }
  }, confirmButton = {
    Button(onClick = {
      val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
      if (parsedPort != null) onAdd(host, parsedPort, user, pass)
    }) { Text("Добавить") }
  }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable private fun PanelCard(content: @Composable ColumnScope.() -> Unit) = Card(
  modifier = Modifier.fillMaxWidth(),
  shape = RoundedCornerShape(24.dp),
  colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f)),
  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }

@Composable private fun EmptyCard(text: String) = PanelCard { Box(Modifier.fillMaxWidth().heightIn(min = 90.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun IconBubble(color: Color, content: @Composable () -> Unit) = Surface(Modifier.size(44.dp), shape = CircleShape, color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color.copy(alpha = 0.30f))) { Box(contentAlignment = Alignment.Center) { content() } }
@Composable private fun StatusPill(text: String, color: Color) = Surface(shape = CircleShape, color = color.copy(alpha = 0.13f), border = BorderStroke(1.dp, color.copy(alpha = 0.28f))) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color) }
@Composable private fun MetricBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) = Surface(modifier, shape = RoundedCornerShape(18.dp), color = color.copy(alpha = 0.10f), border = BorderStroke(1.dp, color.copy(alpha = 0.20f))) { Column(Modifier.padding(12.dp)) { Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color) } }

@Composable
private fun AnimatedMetricBox(
  label: String,
  targetValue: Double,
  formatter: (Double) -> String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  val safeTarget = targetValue.coerceAtLeast(0.0).toFloat()
  val animated by animateFloatAsState(
    targetValue = safeTarget,
    animationSpec = tween(durationMillis = 680, easing = FastOutSlowInEasing),
    label = "animated_metric_$label",
  )
  MetricBox(label, formatter(animated.toDouble()), color, modifier)
}

@Composable private fun InfoRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text(label, Modifier.weight(0.45f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Text(value.ifBlank { "—" }, Modifier.weight(0.55f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
@Composable private fun InfoText(text: String, color: Color) = Text(text, style = MaterialTheme.typography.bodySmall, color = color)

private data class SpeedScale(val divisor: Double, val unit: String)

private fun speedScale(maxValue: Double): SpeedScale {
  val units = listOf("B/s", "KB/s", "MB/s", "GB/s", "TB/s")
  var divisor = 1.0
  var index = 0
  while (maxValue / divisor >= 1024.0 && index < units.lastIndex) {
    divisor *= 1024.0
    index += 1
  }
  return SpeedScale(divisor, units[index])
}

private fun formatScaledSpeed(value: Double, scale: SpeedScale): String {
  val scaled = value / scale.divisor
  return when {
    scaled <= 0.0 -> "0 ${scale.unit}"
    scaled >= 100.0 -> "%.0f %s".format(scaled, scale.unit)
    scaled >= 10.0 -> "%.1f %s".format(scaled, scale.unit)
    else -> "%.2f %s".format(scaled, scale.unit)
  }
}

private fun formatBytes(value: Long): String {
  val units = listOf("B", "KB", "MB", "GB", "TB")
  var v = value.toDouble()
  var i = 0
  while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
  return if (i == 0) "${value} B" else "%.1f %s".format(v, units[i])
}
private fun formatSpeed(value: Double): String = "${formatBytes(value.toLong().coerceAtLeast(0L))}/s"
