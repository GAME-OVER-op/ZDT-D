package com.android.zdtd.service.vps

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VpsTerminalStage {
  CHECK_TMUX,
  INSTALL_TMUX,
  CHECK_SESSION,
  CREATE_SESSION,
  CONNECT,
}

enum class VpsTerminalStageState {
  PENDING,
  RUNNING,
  DONE,
}

data class VpsTerminalStep(
  val stage: VpsTerminalStage,
  val state: VpsTerminalStageState,
)

enum class VpsTerminalError {
  TMUX_INSTALL,
  SESSION_PREPARE,
  CONNECT,
  DISCONNECTED,
}

data class VpsTerminalUiState(
  val steps: List<VpsTerminalStep> = defaultTerminalSteps(),
  val ready: Boolean = false,
  val error: VpsTerminalError? = null,
  val errorDetail: String? = null,
)

private fun defaultTerminalSteps(): List<VpsTerminalStep> = listOf(
  VpsTerminalStep(VpsTerminalStage.CHECK_TMUX, VpsTerminalStageState.PENDING),
  VpsTerminalStep(VpsTerminalStage.INSTALL_TMUX, VpsTerminalStageState.PENDING),
  VpsTerminalStep(VpsTerminalStage.CHECK_SESSION, VpsTerminalStageState.PENDING),
  VpsTerminalStep(VpsTerminalStage.CREATE_SESSION, VpsTerminalStageState.PENDING),
  VpsTerminalStep(VpsTerminalStage.CONNECT, VpsTerminalStageState.PENDING),
)

data class VpsTerminalViewport(
  val columns: Int,
  val rows: Int,
  val widthPixels: Int,
  val heightPixels: Int,
)

class VpsTerminalController(
  private val server: VpsServer,
  private val scope: CoroutineScope,
  private val onTerminalData: (ByteArray) -> Unit,
  private val onConnectionReady: (VpsInteractiveTerminalConnection) -> Unit,
  private val initialViewport: () -> VpsTerminalViewport? = { null },
  private val ssh: VpsSshClient = VpsSshClient(),
) {
  private val _state = MutableStateFlow(VpsTerminalUiState())
  val state: StateFlow<VpsTerminalUiState> = _state.asStateFlow()

  private val generation = AtomicInteger(0)
  private var preparationJob: Job? = null
  private var connection: VpsInteractiveTerminalConnection? = null
  @Volatile private var destroyed = false

  fun start() {
    if (destroyed) return
    val runGeneration = generation.incrementAndGet()
    preparationJob?.cancel()
    connection?.disconnect()
    connection = null
    _state.value = VpsTerminalUiState()

    preparationJob = scope.launch {
      try {
        val checkTmuxStarted = setRunning(VpsTerminalStage.CHECK_TMUX)
        val tmuxCheck = ssh.execute(server, "command -v tmux >/dev/null 2>&1", timeoutMs = 20_000L)
        if (runGeneration != generation.get() || destroyed) return@launch
        setDoneAfter(VpsTerminalStage.CHECK_TMUX, checkTmuxStarted)

        if (!tmuxCheck.successful) {
          val installTmuxStarted = setRunning(VpsTerminalStage.INSTALL_TMUX)
          val install = ssh.execute(server, TMUX_INSTALL_COMMAND, timeoutMs = 300_000L)
          if (runGeneration != generation.get() || destroyed) return@launch
          if (!install.successful) {
            fail(VpsTerminalError.TMUX_INSTALL, install.output)
            return@launch
          }
          setDoneAfter(VpsTerminalStage.INSTALL_TMUX, installTmuxStarted)
        } else {
          skipAsDone(VpsTerminalStage.INSTALL_TMUX)
        }

        val checkSessionStarted = setRunning(VpsTerminalStage.CHECK_SESSION)
        val sessionCheck = ssh.execute(
          server,
          "tmux has-session -t ${VpsInteractiveTerminalConnection.SESSION_NAME} >/dev/null 2>&1",
          timeoutMs = 20_000L,
        )
        if (runGeneration != generation.get() || destroyed) return@launch
        setDoneAfter(VpsTerminalStage.CHECK_SESSION, checkSessionStarted)

        if (!sessionCheck.successful) {
          val createSessionStarted = setRunning(VpsTerminalStage.CREATE_SESSION)
          val create = ssh.execute(
            server,
            "tmux new-session -d -s ${VpsInteractiveTerminalConnection.SESSION_NAME}",
            timeoutMs = 30_000L,
          )
          if (runGeneration != generation.get() || destroyed) return@launch
          if (!create.successful) {
            fail(VpsTerminalError.SESSION_PREPARE, create.output)
            return@launch
          }
          setDoneAfter(VpsTerminalStage.CREATE_SESSION, createSessionStarted)
        } else {
          skipAsDone(VpsTerminalStage.CREATE_SESSION)
        }

        val connectStarted = setRunning(VpsTerminalStage.CONNECT)
        // Ask tmux to expose a real blinking block cursor to the VT renderer.
        // Older tmux versions may not support these options, so preparation
        // must never fail only because a cosmetic option is unavailable.
        runCatching {
          ssh.execute(
            server,
            "tmux set-option -q -w -t ${VpsInteractiveTerminalConnection.SESSION_NAME} cursor-style blinking-block >/dev/null 2>&1 || true; " +
              "tmux set-option -q -w -t ${VpsInteractiveTerminalConnection.SESSION_NAME} scroll-on-clear on >/dev/null 2>&1 || true",
            timeoutMs = 10_000L,
          )
        }
        val viewport = initialViewport()
        val opened = ssh.openInteractiveTerminal(
          server = server,
          initialColumns = viewport?.columns ?: 80,
          initialRows = viewport?.rows ?: 24,
          initialWidthPixels = viewport?.widthPixels ?: 0,
          initialHeightPixels = viewport?.heightPixels ?: 0,
          onData = onTerminalData,
          onClosed = { error ->
            if (!destroyed && runGeneration == generation.get()) {
              connection = null
              _state.value = _state.value.copy(
                ready = false,
                error = VpsTerminalError.DISCONNECTED,
                errorDetail = error?.message,
              )
            }
          },
        )
        if (runGeneration != generation.get() || destroyed) {
          opened.disconnect()
          return@launch
        }
        connection = opened
        onConnectionReady(opened)
        opened.start()
        setDoneAfter(VpsTerminalStage.CONNECT, connectStarted, minVisibleMs = 190L)
        if (runGeneration != generation.get() || destroyed || connection !== opened || _state.value.error != null) return@launch
        _state.value = _state.value.copy(ready = true, error = null, errorDetail = null)
      } catch (error: Throwable) {
        if (runGeneration == generation.get() && !destroyed) {
          fail(VpsTerminalError.CONNECT, error.message)
        }
      }
    }
  }

  fun retry() = start()

  fun close() {
    destroyed = true
    generation.incrementAndGet()
    preparationJob?.cancel()
    preparationJob = null
    connection?.disconnect()
    connection = null
  }

  private fun setRunning(stage: VpsTerminalStage): Long {
    _state.value = _state.value.copy(
      steps = _state.value.steps.map {
        if (it.stage == stage) it.copy(state = VpsTerminalStageState.RUNNING) else it
      },
      error = null,
      errorDetail = null,
    )
    return System.nanoTime()
  }

  private suspend fun setDoneAfter(
    stage: VpsTerminalStage,
    startedAtNanos: Long,
    minVisibleMs: Long = 165L,
  ) {
    val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
    val remainingMs = minVisibleMs - elapsedMs
    if (remainingMs > 0L) delay(remainingMs)
    setDone(stage)
  }

  private fun setDone(stage: VpsTerminalStage) {
    _state.value = _state.value.copy(
      steps = _state.value.steps.map {
        if (it.stage == stage) it.copy(state = VpsTerminalStageState.DONE) else it
      },
    )
  }

  private suspend fun skipAsDone(stage: VpsTerminalStage) {
    // Give the previous stage enough time to settle visually before a skipped
    // step changes state; this avoids several checks snapping to green at once.
    delay(90L)
    setDone(stage)
  }

  private fun fail(error: VpsTerminalError, detail: String?) {
    _state.value = _state.value.copy(
      ready = false,
      error = error,
      errorDetail = detail?.trim()?.take(1600),
    )
  }

  companion object {
    private val TMUX_INSTALL_COMMAND = """
      set -e
      if command -v tmux >/dev/null 2>&1; then
        exit 0
      fi
      if [ "§(id -u)" = "0" ]; then
        SUDO=""
      elif command -v sudo >/dev/null 2>&1; then
        SUDO="sudo -n"
      else
        echo 'tmux is not installed and this account has no root/sudo access.'
        exit 126
      fi
      if command -v apt-get >/dev/null 2>&1; then
        export DEBIAN_FRONTEND=noninteractive
        §SUDO apt-get update -qq
        §SUDO apt-get install -y tmux
      elif command -v dnf >/dev/null 2>&1; then
        §SUDO dnf install -y tmux
      elif command -v yum >/dev/null 2>&1; then
        §SUDO yum install -y tmux
      elif command -v apk >/dev/null 2>&1; then
        §SUDO apk add --no-cache tmux
      elif command -v pacman >/dev/null 2>&1; then
        §SUDO pacman -Sy --noconfirm tmux
      elif command -v zypper >/dev/null 2>&1; then
        §SUDO zypper --non-interactive install tmux
      else
        echo 'No supported package manager found (apt-get/dnf/yum/apk/pacman/zypper).'
        exit 127
      fi
      command -v tmux >/dev/null 2>&1
    """.trimIndent().replace('§', '$')
  }
}
