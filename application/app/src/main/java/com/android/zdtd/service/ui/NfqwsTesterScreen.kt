package com.android.zdtd.service.ui

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.R
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.diagnostics.nfqws.NfqwsTesterOverlayService
import com.android.zdtd.service.diagnostics.nfqws.NfqwsTesterPhase
import com.android.zdtd.service.diagnostics.nfqws.NfqwsTesterStore
import kotlinx.coroutines.flow.StateFlow

@Composable
fun NfqwsTesterScreen(
  uiStateFlow: StateFlow<UiState>,
  actions: ZdtdActions,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val context = LocalContext.current
  val compact = rememberIsCompactWidth()
  val shortHeight = rememberIsShortHeight()
  val uiState by uiStateFlow.collectAsStateWithLifecycle()
  val testerState by NfqwsTesterStore.state.collectAsStateWithLifecycle()
  var selectedProgram by remember { mutableStateOf("nfqws") }
  var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
  var selectedWorking by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    overlayGranted = Settings.canDrawOverlays(context)
    NfqwsTesterStore.update { it.copy(overlayPermissionGranted = overlayGranted) }
  }

  val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    overlayGranted = Settings.canDrawOverlays(context)
    NfqwsTesterStore.update { it.copy(overlayPermissionGranted = overlayGranted) }
  }

  val serviceRunning = ApiModels.isServiceOn(uiState.status)
  val activeProgram = testerState.program.ifBlank { selectedProgram }
  val programProfiles = uiState.programs.firstOrNull { it.id == activeProgram }?.profiles.orEmpty()

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = if (compact) 10.dp else 12.dp,
      end = if (compact) 10.dp else 12.dp,
      top = topContentPadding + if (shortHeight) 6.dp else 10.dp,
      bottom = bottomContentPadding + 12.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(if (shortHeight) 8.dp else 10.dp),
  ) {
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
      ) {
        Column(Modifier.padding(if (compact) 16.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.nfqws_tester_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          Text(
            stringResource(R.string.nfqws_tester_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
          )
        }
      }
    }
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
      ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.nfqws_tester_choose_program), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = selectedProgram == "nfqws", onClick = { selectedProgram = "nfqws" }, label = { Text("nfqws") })
            FilterChip(selected = selectedProgram == "nfqws2", onClick = { selectedProgram = "nfqws2" }, label = { Text("nfqws2") })
          }
          AnimatedVisibility(
            visible = !overlayGranted,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
          ) {
            GateCard(
              title = stringResource(R.string.nfqws_tester_overlay_permission_title),
              ok = overlayGranted,
              okText = stringResource(R.string.nfqws_tester_overlay_granted),
              failText = stringResource(R.string.nfqws_tester_overlay_missing),
              buttonText = stringResource(R.string.nfqws_tester_grant_overlay),
              onButton = { overlayLauncher.launch(NfqwsTesterOverlayService.overlaySettingsIntent(context)) },
            )
          }
          AnimatedVisibility(
            visible = serviceRunning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
          ) {
            GateCard(
              title = stringResource(R.string.nfqws_tester_service_state_title),
              ok = !serviceRunning,
              okText = stringResource(R.string.nfqws_tester_service_stopped),
              failText = stringResource(R.string.nfqws_tester_service_must_be_stopped),
              buttonText = stringResource(R.string.nfqws_tester_refresh_status),
              onButton = actions::refreshStatus,
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
              onClick = { NfqwsTesterOverlayService.start(context, selectedProgram) },
              enabled = overlayGranted && !serviceRunning && testerState.phase != NfqwsTesterPhase.RUNNING && testerState.phase != NfqwsTesterPhase.PREPARING,
            ) {
              Text(stringResource(R.string.nfqws_tester_start))
            }
            OutlinedButton(
              onClick = { NfqwsTesterOverlayService.stop(context) },
              enabled = testerState.phase == NfqwsTesterPhase.RUNNING || testerState.phase == NfqwsTesterPhase.PREPARING || testerState.phase == NfqwsTesterPhase.WAITING_DECISION,
            ) {
              Text(stringResource(R.string.nfqws_tester_stop))
            }
          }
        }
      }
    }

    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
      ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(stringResource(R.string.nfqws_tester_session_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          if (testerState.phase == NfqwsTesterPhase.PREPARING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
          }
          Text(
            testerState.errorText ?: testerState.statusText.ifBlank { stringResource(R.string.nfqws_tester_status_idle) },
            color = if (testerState.errorText != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
          )
          if (testerState.currentStrategy.isNotBlank()) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
              Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(testerState.currentStrategy, fontWeight = FontWeight.SemiBold)
                Text(
                  stringResource(
                    R.string.nfqws_tester_runtime_fmt,
                    testerState.program.uppercase(),
                    (testerState.currentIndex + 1).coerceAtLeast(0),
                    testerState.strategies.size,
                    testerState.cpuPercent,
                    testerState.rssMb,
                  ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
              }
            }
          }
        }
      }
    }

    if (testerState.working.isNotEmpty() || testerState.failed.isNotEmpty() || testerState.skipped.isNotEmpty()) {
      item {
        ResultSection(
          title = stringResource(R.string.nfqws_tester_working_title),
          items = testerState.working,
          clickable = true,
          onClick = { selectedWorking = it },
        )
      }
      item {
        ResultSection(title = stringResource(R.string.nfqws_tester_failed_title), items = testerState.failed)
      }
      item {
        ResultSection(title = stringResource(R.string.nfqws_tester_skipped_title), items = testerState.skipped)
      }
    }
  }

  selectedWorking?.let { strategy ->
    if (programProfiles.isEmpty()) {
      AlertDialog(
        onDismissRequest = { selectedWorking = null },
        title = { Text(stringResource(R.string.nfqws_tester_apply_title)) },
        text = { Text(stringResource(R.string.nfqws_tester_no_profiles_available)) },
        confirmButton = {
          Button(onClick = { selectedWorking = null }) { Text(stringResource(R.string.common_ok)) }
        },
      )
    } else {
      var selectedProfile by remember(strategy) { mutableStateOf(programProfiles.first().name) }
      AlertDialog(
        onDismissRequest = { selectedWorking = null },
        title = { Text(stringResource(R.string.nfqws_tester_apply_title)) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nfqws_tester_apply_message_fmt, strategy, activeProgram))
            programProfiles.forEach { profile ->
              Surface(
                modifier = Modifier.fillMaxWidth().clickable { selectedProfile = profile.name },
                shape = RoundedCornerShape(12.dp),
                color = if (selectedProfile == profile.name) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
              ) {
                Text(profile.name, modifier = Modifier.padding(10.dp))
              }
            }
          }
        },
        confirmButton = {
          Button(onClick = {
            actions.applyStrategicVariant(activeProgram, selectedProfile, strategy) { }
            selectedWorking = null
          }) {
            Text(stringResource(R.string.common_yes))
          }
        },
        dismissButton = {
          OutlinedButton(onClick = { selectedWorking = null }) {
            Text(stringResource(R.string.common_no))
          }
        },
      )
    }
  }
}

@Composable
private fun GateCard(
  title: String,
  ok: Boolean,
  okText: String,
  failText: String,
  buttonText: String,
  onButton: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = if (ok) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
  ) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(if (ok) okText else failText, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f))
      OutlinedButton(onClick = onButton, modifier = Modifier.align(Alignment.End)) {
        Text(buttonText)
      }
    }
  }
}

@Composable
private fun ResultSection(
  title: String,
  items: List<String>,
  clickable: Boolean = false,
  onClick: (String) -> Unit = {},
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      if (items.isEmpty()) {
        Text(stringResource(R.string.nfqws_tester_none), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      } else {
        items.forEach { item ->
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
              .then(if (clickable) Modifier.clickable { onClick(item) } else Modifier)
              .padding(10.dp),
          ) {
            Text(item)
          }
        }
      }
    }
  }
}
