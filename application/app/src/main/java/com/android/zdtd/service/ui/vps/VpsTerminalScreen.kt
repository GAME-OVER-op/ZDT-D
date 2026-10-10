package com.android.zdtd.service.ui.vps

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.zdtd.service.R
import com.android.zdtd.service.vps.VpsInteractiveTerminalConnection
import com.android.zdtd.service.vps.VpsTerminalController
import com.android.zdtd.service.vps.VpsTerminalError
import com.android.zdtd.service.vps.VpsTerminalStage
import com.android.zdtd.service.vps.VpsTerminalStageState
import com.android.zdtd.service.vps.VpsViewModel
import java.util.concurrent.atomic.AtomicReference
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal as TerminalEmulatorView
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey

private val TerminalBackground = Color(0xFF080B10)
private val TerminalPanel = Color(0xFF10151D)
private val TerminalBorder = Color(0xFF253040)
private val TerminalForeground = Color(0xFFD7E0EB)
private val TerminalMuted = Color(0xFF8795A7)
private val TerminalGreen = Color(0xFF4ADE80)
private val TerminalAmber = Color(0xFFFBBF24)

@Composable
fun VpsTerminalScreen(
  serverId: String,
  viewModel: VpsViewModel,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val server = viewModel.server(serverId)
  if (server == null) {
    VpsTerminalMissingServer(topContentPadding, bottomContentPadding)
    return
  }

  val scope = rememberCoroutineScope()
  val connectionRef = remember(serverId) { AtomicReference<VpsInteractiveTerminalConnection?>(null) }
  val dimensionsRef = remember(serverId) { AtomicReference<TerminalDimensions?>(null) }
  val modifierManager = remember(serverId) { VpsTerminalModifierManager() }
  val emulatorRef = remember(serverId) { AtomicReference<TerminalEmulator?>(null) }

  val emulator = remember(serverId) {
    TerminalEmulatorFactory.create(
      defaultForeground = TerminalForeground,
      defaultBackground = TerminalBackground,
      onKeyboardInput = { bytes -> connectionRef.get()?.write(bytes) },
      onResize = { dimensions ->
        dimensionsRef.set(dimensions)
        connectionRef.get()?.resize(
          columns = dimensions.columns,
          rows = dimensions.rows,
          widthPixels = dimensions.widthPixels,
          heightPixels = dimensions.heightPixels,
        )
      },
    ).also(emulatorRef::set)
  }

  val controller = remember(serverId) {
    VpsTerminalController(
      server = server,
      scope = scope,
      onTerminalData = { bytes -> emulatorRef.get()?.writeInput(bytes) },
      onConnectionReady = { connection ->
        connectionRef.set(connection)
        dimensionsRef.get()?.let { dimensions ->
          connection.resize(
            columns = dimensions.columns,
            rows = dimensions.rows,
            widthPixels = dimensions.widthPixels,
            heightPixels = dimensions.heightPixels,
          )
        }
      },
    )
  }
  val state by controller.state.collectAsState()

  LaunchedEffect(controller) { controller.start() }
  DisposableEffect(controller) {
    onDispose {
      connectionRef.set(null)
      controller.close()
    }
  }

  AnimatedContent(
    targetState = state.ready,
    transitionSpec = {
      if (targetState) {
        (
          fadeIn(tween(260, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(360, easing = FastOutSlowInEasing)) { -it / 14 } +
            scaleIn(
              initialScale = 0.985f,
              transformOrigin = TransformOrigin(0.5f, 0f),
              animationSpec = tween(360, easing = FastOutSlowInEasing),
            )
          ) togetherWith (
          fadeOut(tween(190)) +
            slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { it / 22 } +
            scaleOut(
              targetScale = 0.98f,
              transformOrigin = TransformOrigin(0.5f, 0f),
              animationSpec = tween(300, easing = FastOutSlowInEasing),
            )
          )
      } else {
        (fadeIn(tween(220)) + scaleIn(initialScale = 0.985f)) togetherWith fadeOut(tween(180))
      }.using(SizeTransform(clip = false))
    },
    label = "vpsTerminalReady",
  ) { ready ->
    if (ready) {
      VpsInteractiveTerminal(
        serverName = server.name,
        emulator = emulator,
        modifierManager = modifierManager,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
    } else {
      VpsTerminalPreparation(
        state = state,
        serverName = server.name,
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
        onRetry = controller::retry,
      )
    }
  }
}

@Composable
private fun VpsInteractiveTerminal(
  serverName: String,
  emulator: TerminalEmulator,
  modifierManager: VpsTerminalModifierManager,
  topContentPadding: Dp,
  bottomContentPadding: Dp,
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val focusRequester = remember { FocusRequester() }
  var imeVisible by remember { mutableStateOf(false) }
  var softKeyboardRequested by rememberSaveable(serverName) { mutableStateOf(true) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        imeVisible = false
        softKeyboardRequested = false
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  BackHandler(enabled = imeVisible) {
    keyboardController?.hide()
    focusManager.clearFocus(force = true)
    imeVisible = false
    softKeyboardRequested = false
  }

  val clipboardManager = remember(context) { context.getSystemService(ClipboardManager::class.java) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(top = topContentPadding, bottom = bottomContentPadding)
      .imePadding()
      .background(TerminalBackground),
  ) {
    VpsTerminalStatusBar(serverName)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .background(TerminalBackground),
    ) {
      TerminalEmulatorView(
        terminalEmulator = emulator,
        modifier = Modifier.fillMaxSize(),
        initialFontSize = 12.sp,
        minFontSize = 8.sp,
        maxFontSize = 20.sp,
        backgroundColor = TerminalBackground,
        foregroundColor = TerminalForeground,
        keyboardEnabled = true,
        showSoftKeyboard = softKeyboardRequested,
        focusRequester = focusRequester,
        modifierManager = modifierManager,
        onTerminalTap = {
          if (!softKeyboardRequested) softKeyboardRequested = true
        },
        onImeVisibilityChanged = { visible ->
          val wasVisible = imeVisible
          imeVisible = visible
          if (wasVisible && !visible) softKeyboardRequested = false
        },
        onPasteRequest = {
          val text = clipboardManager?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
          if (!text.isNullOrEmpty()) emulator.pasteText(text)
        },
      )
    }

    AnimatedVisibility(visible = imeVisible) {
      VpsTerminalExtraKeys(
        emulator = emulator,
        modifierManager = modifierManager,
        onPaste = {
          val text = clipboardManager?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
          if (!text.isNullOrEmpty()) emulator.pasteText(text)
        },
      )
    }
  }
}

@Composable
private fun VpsTerminalStatusBar(serverName: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(TerminalPanel)
      .padding(horizontal = 13.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = TerminalGreen) {}
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      Text(
        text = serverName,
        color = TerminalForeground,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = VpsInteractiveTerminalConnection.SESSION_NAME,
        color = TerminalMuted,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
      )
    }
    Icon(Icons.Outlined.Terminal, contentDescription = null, tint = TerminalGreen, modifier = Modifier.size(20.dp))
  }
}

@Composable
private fun VpsTerminalExtraKeys(
  emulator: TerminalEmulator,
  modifierManager: VpsTerminalModifierManager,
  onPaste: () -> Unit,
) {
  val scroll = rememberScrollState()
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = TerminalPanel,
    tonalElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(scroll)
        .padding(horizontal = 8.dp, vertical = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          VpsTerminalModifierKey("CTRL", modifierManager.ctrlActive) { modifierManager.toggleCtrl() }
          VpsTerminalModifierKey("ALT", modifierManager.altActive) { modifierManager.toggleAlt() }
          VpsTerminalKey("ESC") { dispatchTerminalKey(emulator, modifierManager, VTermKey.ESCAPE) }
          VpsTerminalKey("TAB") { dispatchTerminalKey(emulator, modifierManager, VTermKey.TAB) }
          VpsTerminalKey("HOME") { dispatchTerminalKey(emulator, modifierManager, VTermKey.HOME) }
          VpsTerminalKey("END") { dispatchTerminalKey(emulator, modifierManager, VTermKey.END) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          VpsTerminalModifierKey("SHIFT", modifierManager.shiftActive) { modifierManager.toggleShift() }
          VpsTerminalKey("|") { dispatchTerminalChar(emulator, modifierManager, '|'.code) }
          VpsTerminalKey("/") { dispatchTerminalChar(emulator, modifierManager, '/'.code) }
          VpsTerminalKey("-") { dispatchTerminalChar(emulator, modifierManager, '-'.code) }
          VpsTerminalKey("~") { dispatchTerminalChar(emulator, modifierManager, '~'.code) }
          VpsTerminalIconKey { onPaste() }
        }
      }
      VpsTerminalDPad(emulator, modifierManager)
    }
  }
}

@Composable
private fun VpsTerminalDPad(emulator: TerminalEmulator, modifierManager: VpsTerminalModifierManager) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
    VpsTerminalKey("↑", width = 34.dp) { dispatchTerminalKey(emulator, modifierManager, VTermKey.UP) }
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
      VpsTerminalKey("←", width = 34.dp) { dispatchTerminalKey(emulator, modifierManager, VTermKey.LEFT) }
      VpsTerminalKey("↓", width = 34.dp) { dispatchTerminalKey(emulator, modifierManager, VTermKey.DOWN) }
      VpsTerminalKey("→", width = 34.dp) { dispatchTerminalKey(emulator, modifierManager, VTermKey.RIGHT) }
    }
  }
}

@Composable
private fun VpsTerminalKey(
  label: String,
  width: Dp = 54.dp,
  onClick: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .width(width)
      .height(31.dp)
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(8.dp),
    color = Color(0xFF19212C),
    border = BorderStroke(1.dp, TerminalBorder),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, color = TerminalForeground, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun VpsTerminalModifierKey(label: String, active: Boolean, onClick: () -> Unit) {
  Surface(
    modifier = Modifier
      .width(58.dp)
      .height(31.dp)
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(8.dp),
    color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else Color(0xFF19212C),
    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else TerminalBorder),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        label,
        color = if (active) MaterialTheme.colorScheme.onPrimary else TerminalForeground,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
private fun VpsTerminalIconKey(onClick: () -> Unit) {
  Surface(
    modifier = Modifier
      .width(54.dp)
      .height(31.dp)
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(8.dp),
    color = Color(0xFF19212C),
    border = BorderStroke(1.dp, TerminalBorder),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.vps_terminal_paste), tint = TerminalForeground, modifier = Modifier.size(16.dp))
    }
  }
}

private fun dispatchTerminalKey(emulator: TerminalEmulator, modifierManager: VpsTerminalModifierManager, key: Int) {
  emulator.dispatchKey(modifierManager.mask(), key)
  modifierManager.clearTransients()
}

private fun dispatchTerminalChar(emulator: TerminalEmulator, modifierManager: VpsTerminalModifierManager, codePoint: Int) {
  emulator.dispatchCharacter(modifierManager.mask(), codePoint)
  modifierManager.clearTransients()
}

private class VpsTerminalModifierManager : ModifierManager {
  var ctrlActive by mutableStateOf(false)
    private set
  var altActive by mutableStateOf(false)
    private set
  var shiftActive by mutableStateOf(false)
    private set

  fun toggleCtrl() { ctrlActive = !ctrlActive }
  fun toggleAlt() { altActive = !altActive }
  fun toggleShift() { shiftActive = !shiftActive }

  fun mask(): Int =
    (if (shiftActive) 0x01 else 0) or
      (if (altActive) 0x02 else 0) or
      (if (ctrlActive) 0x04 else 0)

  override fun isCtrlActive(): Boolean = ctrlActive
  override fun isAltActive(): Boolean = altActive
  override fun isShiftActive(): Boolean = shiftActive

  override fun clearTransients() {
    ctrlActive = false
    altActive = false
    shiftActive = false
  }
}

@Composable
private fun VpsTerminalPreparation(
  state: com.android.zdtd.service.vps.VpsTerminalUiState,
  serverName: String,
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  onRetry: () -> Unit,
) {
  val rotation by rememberInfiniteTransition(label = "vpsTerminalPrep").animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(animation = tween(900)),
    label = "vpsTerminalPrepRotation",
  )

  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(top = topContentPadding, bottom = bottomContentPadding)
      .padding(horizontal = 16.dp, vertical = 14.dp),
    contentAlignment = Alignment.Center,
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = TerminalBackground),
      border = BorderStroke(1.dp, TerminalBorder),
    ) {
      Column(
        Modifier
          .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
          .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Surface(modifier = Modifier.size(44.dp), shape = CircleShape, color = Color(0xFF152033)) {
            Box(contentAlignment = Alignment.Center) {
              Icon(Icons.Outlined.Terminal, contentDescription = null, tint = TerminalGreen, modifier = Modifier.size(24.dp))
            }
          }
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.vps_terminal_preparing), color = TerminalForeground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(serverName, color = TerminalMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }

        Surface(shape = RoundedCornerShape(16.dp), color = TerminalPanel, border = BorderStroke(1.dp, TerminalBorder)) {
          Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            state.steps.forEach { step ->
              val active = step.state == VpsTerminalStageState.RUNNING
              val rowColor by animateColorAsState(
                targetValue = if (active) TerminalAmber.copy(alpha = 0.09f) else Color.Transparent,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "terminalStageBackground_${step.stage}",
              )
              val textColor by animateColorAsState(
                targetValue = if (step.state == VpsTerminalStageState.PENDING) TerminalMuted else TerminalForeground,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
                label = "terminalStageText_${step.stage}",
              )
              val rowScale by animateFloatAsState(
                targetValue = if (active) 1.018f else 1f,
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                label = "terminalStageScale_${step.stage}",
              )

              Surface(
                modifier = Modifier.fillMaxWidth().scale(rowScale),
                shape = RoundedCornerShape(9.dp),
                color = rowColor,
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  AnimatedContent(
                    targetState = step.state,
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center,
                    transitionSpec = {
                      (fadeIn(tween(150)) + scaleIn(initialScale = 0.62f, animationSpec = tween(190, easing = FastOutSlowInEasing))) togetherWith
                        (fadeOut(tween(110)) + scaleOut(targetScale = 0.78f, animationSpec = tween(150)))
                    },
                    label = "terminalStageIcon_${step.stage}",
                  ) { stageState ->
                    when (stageState) {
                      VpsTerminalStageState.DONE -> Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = TerminalGreen,
                        modifier = Modifier.size(18.dp),
                      )
                      VpsTerminalStageState.RUNNING -> Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = TerminalAmber,
                        modifier = Modifier.size(18.dp).rotate(rotation),
                      )
                      VpsTerminalStageState.PENDING -> Surface(
                        modifier = Modifier.size(14.dp).alpha(0.6f),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, TerminalMuted),
                        color = Color.Transparent,
                      ) {}
                    }
                  }
                  Spacer(Modifier.width(10.dp))
                  Text(
                    text = terminalStageText(step.stage),
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                  )
                }
              }
            }
          }
        }

        val terminalError = state.error
        AnimatedVisibility(
          visible = terminalError != null,
          enter = fadeIn(tween(180)) + expandVertically(tween(260, easing = FastOutSlowInEasing)),
          exit = fadeOut(tween(140)) + shrinkVertically(tween(220, easing = FastOutSlowInEasing)),
        ) {
          terminalError?.let { error ->
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.17f),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.42f)),
            ) {
              Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
                  Spacer(Modifier.width(8.dp))
                  Text(terminalErrorText(error), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
                state.errorDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                  Text(detail, color = TerminalMuted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                  Icon(Icons.Outlined.Refresh, contentDescription = null)
                  Spacer(Modifier.width(7.dp))
                  Text(stringResource(R.string.vps_terminal_retry))
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
private fun terminalStageText(stage: VpsTerminalStage): String = when (stage) {
  VpsTerminalStage.CHECK_TMUX -> stringResource(R.string.vps_terminal_check_tmux)
  VpsTerminalStage.INSTALL_TMUX -> stringResource(R.string.vps_terminal_install_tmux)
  VpsTerminalStage.CHECK_SESSION -> stringResource(R.string.vps_terminal_check_session)
  VpsTerminalStage.CREATE_SESSION -> stringResource(R.string.vps_terminal_create_session)
  VpsTerminalStage.CONNECT -> stringResource(R.string.vps_terminal_connect)
}

@Composable
private fun terminalErrorText(error: VpsTerminalError): String = when (error) {
  VpsTerminalError.TMUX_INSTALL -> stringResource(R.string.vps_terminal_error_tmux)
  VpsTerminalError.SESSION_PREPARE -> stringResource(R.string.vps_terminal_error_session)
  VpsTerminalError.CONNECT -> stringResource(R.string.vps_terminal_error_connect)
  VpsTerminalError.DISCONNECTED -> stringResource(R.string.vps_terminal_error_disconnected)
}

@Composable
private fun VpsTerminalMissingServer(topContentPadding: Dp, bottomContentPadding: Dp) {
  Box(
    modifier = Modifier.fillMaxSize().padding(top = topContentPadding, bottom = bottomContentPadding),
    contentAlignment = Alignment.Center,
  ) {
    Text(stringResource(R.string.vps_server_not_found), color = MaterialTheme.colorScheme.error)
  }
}
