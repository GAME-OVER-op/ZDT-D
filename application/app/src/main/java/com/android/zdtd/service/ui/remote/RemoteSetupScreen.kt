package com.android.zdtd.service.ui.remote

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.remote.RemoteDeviceInfo
import com.android.zdtd.service.remote.RemoteSetupUiState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors

enum class RemoteSetupPage { HOME, HOST, CONNECT, HISTORY, SCAN }

@Composable
fun RemoteSetupScreen(
  state: RemoteSetupUiState,
  onBack: () -> Unit,
  onStartHost: () -> Unit,
  onStopHost: () -> Unit,
  onRefreshDiscovery: () -> Unit,
  onManualConnect: (String, String, String) -> Unit,
  onQrScanned: (String) -> Unit,
  onConnectKnown: (RemoteDeviceInfo, String) -> Unit,
  onRequestCameraPermission: () -> Unit,
) {
  var page by remember { mutableStateOf(RemoteSetupPage.HOME) }
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
          RemoteSetupPage.HISTORY -> "История"
          RemoteSetupPage.SCAN -> "Сканировать QR"
        },
        onBack = { if (page == RemoteSetupPage.HOME) onBack() else page = RemoteSetupPage.HOME },
      )
      AnimatedContent(
        targetState = page,
        transitionSpec = {
          val enter = fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 7 }
          val exit = fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -it / 7 }
          (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "remote_setup_page",
      ) { p ->
        when (p) {
          RemoteSetupPage.HOME -> RemoteSetupHome(
            state = state,
            onHost = { page = RemoteSetupPage.HOST },
            onConnect = { page = RemoteSetupPage.CONNECT },
            onHistory = { page = RemoteSetupPage.HISTORY },
          )
          RemoteSetupPage.HOST -> RemoteHostPage(state, onStartHost, onStopHost)
          RemoteSetupPage.CONNECT -> RemoteConnectPage(
            state = state,
            onScan = { page = RemoteSetupPage.SCAN },
            onManualConnect = onManualConnect,
            onRefresh = onRefreshDiscovery,
            onConnectKnown = onConnectKnown,
          )
          RemoteSetupPage.HISTORY -> RemoteHistoryPage(state, onRefreshDiscovery, onConnectKnown)
          RemoteSetupPage.SCAN -> RemoteQrScannerPage(
            permissionGranted = state.cameraPermissionGranted,
            onRequestPermission = onRequestCameraPermission,
            onResult = {
              onQrScanned(it)
              page = RemoteSetupPage.CONNECT
            },
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
private fun RemoteSetupHome(state: RemoteSetupUiState, onHost: () -> Unit, onConnect: () -> Unit, onHistory: () -> Unit) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(14.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      RemoteActionCard(
        title = "Запустить удалённое управление",
        subtitle = if (state.canHost) "Открыть временный порт 10320–10340, показать код и QR для телефона." else "Доступно только на root-устройстве с установленным ZDT-D.",
        icon = Icons.Outlined.CastConnected,
        accent = MaterialTheme.colorScheme.primary,
        enabled = state.canHost,
        onClick = onHost,
      )
    }
    item {
      RemoteActionCard(
        title = "Настроить устройство",
        subtitle = "Сканировать QR-код или ввести IP, порт и код подключения вручную.",
        icon = Icons.Outlined.SettingsRemote,
        accent = MaterialTheme.colorScheme.secondary,
        enabled = true,
        onClick = onConnect,
      )
    }
    item {
      RemoteActionCard(
        title = "История",
        subtitle = "Прошлые устройства и устройства ZDT-D, найденные сейчас в локальной сети.",
        icon = Icons.Outlined.History,
        accent = MaterialTheme.colorScheme.tertiary,
        enabled = true,
        onClick = onHistory,
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
    val containerMaxHeight = maxHeight
    val wide = containerMaxWidth >= 720.dp
    if (!host.running) {
      CardBlock {
        Text("Режим хоста", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
          "Телефон подключается к этому устройству, а все root/API/file-действия выполняет приложение ZDT-D здесь.",
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
            .weight(1f)
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
            Text("Адрес", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
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
        val qrSize = minOf(containerMaxHeight - 28.dp, containerMaxWidth * 0.44f)
        QrImage(
          payload = host.qrPayload,
          modifier = Modifier
            .size(qrSize)
            .padding(2.dp),
        )
      }
    } else {
      LazyColumn(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
          CardBlock {
            Text("Удалённое управление запущено", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Адрес: ${host.host}:${host.port}", fontWeight = FontWeight.SemiBold)
            Text("Код: ${host.code}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            host.pairedDevice.takeIf { it.isNotBlank() }?.let { Text("Подключено: $it") }
            QrImage(host.qrPayload, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
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
  onScan: () -> Unit,
  onManualConnect: (String, String, String) -> Unit,
  onRefresh: () -> Unit,
  onConnectKnown: (RemoteDeviceInfo, String) -> Unit,
) {
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("10320") }
  var code by remember { mutableStateOf("") }
  LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
    item {
      CardBlock {
        Text("Подключение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.QrCode2, null); Spacer(Modifier.size(8.dp)); Text("Сканировать QR") }
        Divider()
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
        port = d.port.takeIf { it > 0 }?.toString() ?: "10320"
        code = ""
      }
    }
    item { StatusMessages(state) }
  }
}

@Composable
private fun RemoteHistoryPage(state: RemoteSetupUiState, onRefresh: () -> Unit, onConnectKnown: (RemoteDeviceInfo, String) -> Unit) {
  LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
    item {
      DeviceListBlock("История и доступные устройства", state.discovered + state.history, onRefresh) { d ->
        if (d.sessionToken.isNotBlank()) onConnectKnown(d, "")
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

@Composable
private fun QrImage(payload: String, modifier: Modifier = Modifier) {
  val bmp = remember(payload) { createQrBitmap(payload, 720) }
  Image(
    bitmap = bmp.asImageBitmap(),
    contentDescription = null,
    modifier = modifier
      .clip(RoundedCornerShape(22.dp))
      .background(Color.White)
      .padding(18.dp),
  )
}

private fun createQrBitmap(text: String, size: Int): Bitmap {
  val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
  val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
  return bmp
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun RemoteQrScannerPage(permissionGranted: Boolean, onRequestPermission: () -> Unit, onResult: (String) -> Unit) {
  var cameraError by remember { mutableStateOf("") }
  CardBlock {
    Text("Наведите камеру на QR-код на Android TV", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
    if (cameraError.isNotBlank()) {
      Text(cameraError, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    if (!permissionGranted) {
      Text("Нужно разрешение камеры для сканирования QR.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
      Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("Разрешить камеру") }
    } else {
      val context = LocalContext.current
      val owner = LocalLifecycleOwner.current
      val executor = remember { Executors.newSingleThreadExecutor() }
      DisposableEffect(Unit) { onDispose { executor.shutdown() } }
      AndroidView(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .clip(RoundedCornerShape(24.dp)),
        factory = { ctx ->
          val previewView = PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
          }
          val providerFuture = ProcessCameraProvider.getInstance(ctx)
          val scanned = java.util.concurrent.atomic.AtomicBoolean(false)
          providerFuture.addListener({
            val providerResult = runCatching { providerFuture.get() }
            val provider = providerResult.getOrNull()
            if (provider == null) {
              cameraError = providerResult.exceptionOrNull()?.message ?: "Не удалось открыть камеру"
            } else {
              val scanner = BarcodeScanning.getClient()
              var bound = false
              var lastError = ""
              val selectors = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
              for (selector in selectors) {
                if (bound) break
                runCatching {
                  val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                  val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                  analysis.setAnalyzer(executor) { proxy ->
                    if (scanned.get()) {
                      proxy.close()
                    } else {
                      val media = proxy.image
                      if (media == null) {
                        proxy.close()
                      } else {
                        try {
                          val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                          scanner.process(image)
                            .addOnSuccessListener { codes ->
                              val raw = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                              if (!raw.isNullOrBlank() && scanned.compareAndSet(false, true)) {
                                analysis.clearAnalyzer()
                                onResult(raw)
                              }
                            }
                            .addOnFailureListener { cameraError = it.message ?: "Ошибка сканирования QR" }
                            .addOnCompleteListener { proxy.close() }
                        } catch (e: Throwable) {
                          proxy.close()
                          cameraError = e.message ?: "Ошибка камеры"
                        }
                      }
                    }
                  }
                  provider.unbindAll()
                  provider.bindToLifecycle(owner, selector, preview, analysis)
                  bound = true
                }.onFailure { e ->
                  lastError = e.message ?: "Ошибка камеры"
                }
              }
              if (bound) {
                cameraError = ""
              } else {
                cameraError = lastError.ifBlank { "Камера не найдена на устройстве" }
              }
            }
          }, ContextCompat.getMainExecutor(context))
          previewView
        }
      )
    }
  }
}

