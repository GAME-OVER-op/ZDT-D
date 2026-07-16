package com.android.zdtd.service.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.remote.RemoteDeviceInfo
import com.android.zdtd.service.remote.RemoteProtocol
import com.android.zdtd.service.remote.RemoteSetupUiState

enum class RemoteSetupPage { HOME, HOST, CONNECT }

@Composable
fun RemoteSetupScreen(
  state: RemoteSetupUiState,
  onBack: () -> Unit,
  onStartHost: () -> Unit,
  onStopHost: () -> Unit,
  onRefreshDiscovery: () -> Unit,
  onManualConnect: (String, String, String) -> Unit,
) {
  val backStack = remember { mutableStateListOf(RemoteSetupPage.HOME) }
  var forward by remember { mutableStateOf(true) }
  val page = backStack.last()
  fun navigate(to: RemoteSetupPage) {
    if (to == page) return
    forward = true
    backStack.add(to)
  }
  fun navigateBack() {
    if (backStack.size > 1) {
      forward = false
      backStack.removeAt(backStack.lastIndex)
    } else {
      onBack()
    }
  }
  BackHandler { navigateBack() }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))))
  ) {
    Column(Modifier.fillMaxSize()) {
      RemoteSetupTopBar(
        title = when (page) {
          RemoteSetupPage.HOME -> "Удалённая настройка"
          RemoteSetupPage.HOST -> "Запустить управление"
          RemoteSetupPage.CONNECT -> "Настроить устройство"
        },
        onBack = { navigateBack() },
      )
      AnimatedContent(
        targetState = page,
        transitionSpec = {
          val enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { if (forward) it / 5 else -it / 5 }
          val exit = fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { if (forward) -it / 5 else it / 5 }
          (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "remote_setup_page",
      ) { p ->
        when (p) {
          RemoteSetupPage.HOME -> RemoteSetupHome(
            state = state,
            onHost = { navigate(RemoteSetupPage.HOST) },
            onConnect = { navigate(RemoteSetupPage.CONNECT) },
          )
          RemoteSetupPage.HOST -> RemoteHostPage(state, onStartHost, onStopHost)
          RemoteSetupPage.CONNECT -> RemoteConnectPage(
            state = state,
            onManualConnect = onManualConnect,
            onRefresh = onRefreshDiscovery,
          )
        }
      }
    }
  }
}

@Composable
private fun RemoteSetupTopBar(title: String, onBack: () -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), tonalElevation = 0.dp) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = null) }
      Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun RemoteSetupHome(state: RemoteSetupUiState, onHost: () -> Unit, onConnect: () -> Unit) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(14.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      RemoteActionCard(
        title = "Запустить удалённое управление",
        subtitle = if (state.canHost) "Открыть временный HTTP API на ${RemoteProtocol.DEFAULT_API_PORT}+ и объявить его в локальной сети." else "Доступно только на root-устройстве с установленным ZDT-D.",
        icon = Icons.Outlined.CastConnected,
        accent = MaterialTheme.colorScheme.primary,
        enabled = state.canHost,
        onClick = onHost,
      )
    }
    item {
      RemoteActionCard(
        title = "Настроить устройство",
        subtitle = "Найти устройство в сети или ввести IP, порт и код вручную.",
        icon = Icons.Outlined.SettingsRemote,
        accent = MaterialTheme.colorScheme.secondary,
        enabled = true,
        onClick = onConnect,
      )
    }
    item { StatusMessages(state) }
  }
}

@Composable
private fun RemoteHostPage(state: RemoteSetupUiState, onStart: () -> Unit, onStop: () -> Unit) {
  val host = state.host
  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .padding(14.dp),
  ) {
    val containerMaxWidth = maxWidth
    val wide = containerMaxWidth >= 720.dp
    if (!host.running) {
      CardBlock {
        Text("Режим хоста", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
          "Телефон подключается к этому устройству, а root/API/file-действия выполняет ZDT-D здесь. Доступ защищён временным токеном текущей сессии.",
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        Button(onClick = onStart, enabled = state.canHost, modifier = Modifier.fillMaxWidth()) {
          Text("Запустить удалённое управление")
        }
        StatusMessages(state)
      }
    } else if (wide) {
      Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
          shape = RoundedCornerShape(28.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(20.dp),
            verticalArrangement = Arrangement.Center,
          ) {
            Text("Удалённое управление запущено", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Адрес HTTP API", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
            Text("${host.host}:${host.port}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Text("Код подключения", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
            Text(host.code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            host.pairedDevice.takeIf { it.isNotBlank() }?.let {
              Spacer(Modifier.height(14.dp))
              Text("Подключено: $it", color = Color(0xFF22C55E), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
            StatusMessages(state)
          }
        }
      }
    } else {
      LazyColumn(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
          CardBlock {
            Text("Удалённое управление запущено", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("HTTP API: ${host.host}:${host.port}", fontWeight = FontWeight.SemiBold)
            Text("Код: ${host.code}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            host.pairedDevice.takeIf { it.isNotBlank() }?.let { Text("Подключено: $it") }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
          }
        }
        item { StatusMessages(state) }
      }
    }
  }
}

@Composable
private fun RemoteConnectPage(
  state: RemoteSetupUiState,
  onManualConnect: (String, String, String) -> Unit,
  onRefresh: () -> Unit,
) {
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf(RemoteProtocol.DEFAULT_API_PORT.toString()) }
  var code by remember { mutableStateOf("") }
  LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
    item {
      CardBlock {
        Text("Подключение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(host, { host = it }, label = { Text("IP-адрес") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Порт") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(8) }, label = { Text("Код из 8 символов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onManualConnect(host, port, code) }, enabled = !state.connecting, modifier = Modifier.fillMaxWidth()) {
          if (state.connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Подключиться")
        }
      }
    }
    item {
      DeviceListBlock("Найдено в сети", state.discovered, onRefresh) { d ->
        host = d.host
        port = d.port.takeIf { it > 0 }?.toString() ?: RemoteProtocol.DEFAULT_API_PORT.toString()
        code = ""
      }
    }
    item { StatusMessages(state) }
  }
}

@Composable
private fun DeviceListBlock(title: String, devices: List<RemoteDeviceInfo>, onRefresh: () -> Unit, onDeviceClick: (RemoteDeviceInfo) -> Unit) {
  CardBlock {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
      IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, null) }
    }
    val unique = devices.distinctBy { it.deviceId.ifBlank { "${it.host}:${it.port}" } }
    if (unique.isEmpty()) {
      Text("Устройства пока не найдены", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
    } else {
      unique.forEach { d ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onDeviceClick(d) }
            .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(shape = CircleShape, color = if (d.online) Color(0xFF22C55E).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant) {
            Icon(Icons.Outlined.Devices, null, modifier = Modifier.padding(8.dp), tint = if (d.online) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Column(Modifier.weight(1f)) {
            Text(d.displayTitle(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${d.host}:${d.port} • versionCode=${d.appVersionCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
          }
        }
      }
    }
  }
}

@Composable
private fun RemoteActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, enabled: Boolean, onClick: () -> Unit) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(24.dp))
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 0.82f else 0.50f)),
    border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.34f else 0.16f)),
  ) {
    Row(Modifier.background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.14f), Color.Transparent))).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Surface(shape = CircleShape, color = accent.copy(alpha = 0.16f)) { Icon(icon, null, Modifier.padding(12.dp).size(28.dp), tint = accent) }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f))
      }
    }
  }
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
  ) {
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
  }
}

@Composable
private fun StatusMessages(state: RemoteSetupUiState) {
  AnimatedVisibility(state.message.isNotBlank() || state.error.isNotBlank()) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(18.dp),
      color = if (state.error.isNotBlank()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
      Text(
        text = state.error.ifBlank { state.message },
        modifier = Modifier.padding(12.dp),
        color = if (state.error.isNotBlank()) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}
