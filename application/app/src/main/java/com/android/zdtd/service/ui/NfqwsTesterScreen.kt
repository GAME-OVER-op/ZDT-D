package com.android.zdtd.service.ui

import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
  val testerBusy = testerState.phase == NfqwsTesterPhase.RUNNING ||
    testerState.phase == NfqwsTesterPhase.PREPARING ||
    testerState.phase == NfqwsTesterPhase.WAITING_DECISION
  val canStart = overlayGranted && !serviceRunning && !testerBusy

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
      TesterHeaderCard(compact = compact)
    }

    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(260))
            .padding(if (compact) 14.dp else 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                stringResource(R.string.nfqws_tester_choose_program),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                stringResource(R.string.nfqws_tester_short_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
              )
            }
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
              contentColor = MaterialTheme.colorScheme.primary,
            ) {
              Icon(
                imageVector = Icons.Outlined.PlaylistPlay,
                contentDescription = null,
                modifier = Modifier.padding(10.dp).size(20.dp),
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = selectedProgram == "nfqws", onClick = { selectedProgram = "nfqws" }, label = { Text("nfqws") })
            FilterChip(selected = selectedProgram == "nfqws2", onClick = { selectedProgram = "nfqws2" }, label = { Text("nfqws2") })
          }

          AnimatedVisibility(
            visible = !overlayGranted,
            enter = fadeIn(animationSpec = tween(220)) + slideInVertically(initialOffsetY = { -it / 3 }, animationSpec = tween(260)),
            exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(220)),
          ) {
            WarningGateCard(
              title = stringResource(R.string.nfqws_tester_overlay_permission_title),
              description = stringResource(R.string.nfqws_tester_overlay_missing),
              icon = Icons.Outlined.Tune,
              accent = MaterialTheme.colorScheme.primary,
              buttonText = stringResource(R.string.nfqws_tester_grant_overlay),
              onButton = { overlayLauncher.launch(NfqwsTesterOverlayService.overlaySettingsIntent(context)) },
            )
          }

          AnimatedVisibility(
            visible = serviceRunning,
            enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(260)),
            exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(220)),
          ) {
            WarningGateCard(
              title = stringResource(R.string.nfqws_tester_service_state_title),
              description = stringResource(R.string.nfqws_tester_service_must_be_stopped),
              icon = Icons.Outlined.ReportProblem,
              accent = MaterialTheme.colorScheme.error,
              buttonText = stringResource(R.string.nfqws_tester_refresh_status),
              onButton = actions::refreshStatus,
            )
          }

          Crossfade(
            targetState = when {
              !overlayGranted -> ReadyState.NEEDS_OVERLAY
              serviceRunning -> ReadyState.SERVICE_RUNNING
              testerBusy -> ReadyState.TESTER_BUSY
              else -> ReadyState.READY
            },
            animationSpec = tween(220),
            label = "nfqws_tester_ready_state",
          ) { state ->
            when (state) {
              ReadyState.NEEDS_OVERLAY -> StatusBanner(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.nfqws_tester_overlay_permission_title),
                subtitle = stringResource(R.string.nfqws_tester_overlay_required),
                accent = MaterialTheme.colorScheme.primary,
              )
              ReadyState.SERVICE_RUNNING -> StatusBanner(
                icon = Icons.Outlined.ReportProblem,
                title = stringResource(R.string.nfqws_tester_service_state_title),
                subtitle = stringResource(R.string.nfqws_tester_service_must_be_stopped),
                accent = MaterialTheme.colorScheme.error,
              )
              ReadyState.TESTER_BUSY -> StatusBanner(
                icon = Icons.Outlined.HourglassTop,
                title = stringResource(R.string.nfqws_tester_session_title),
                subtitle = testerState.statusText.ifBlank { stringResource(R.string.nfqws_tester_status_waiting_decision) },
                accent = MaterialTheme.colorScheme.tertiary,
              )
              ReadyState.READY -> StatusBanner(
                icon = Icons.Outlined.CheckCircle,
                title = stringResource(R.string.nfqws_tester_service_stopped),
                subtitle = stringResource(R.string.nfqws_tester_overlay_granted),
                accent = MaterialTheme.colorScheme.primary,
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
              onClick = { NfqwsTesterOverlayService.start(context, selectedProgram) },
              enabled = canStart,
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.size(8.dp))
              Text(stringResource(R.string.nfqws_tester_start))
            }
            OutlinedButton(
              onClick = { NfqwsTesterOverlayService.stop(context) },
              enabled = testerBusy,
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.size(8.dp))
              Text(stringResource(R.string.nfqws_tester_stop))
            }
          }
        }
      }
    }

    item {
      SessionCard(testerState = testerState)
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

private enum class ReadyState {
  NEEDS_OVERLAY,
  SERVICE_RUNNING,
  TESTER_BUSY,
  READY,
}

@Composable
private fun TesterHeaderCard(compact: Boolean) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(26.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
              Color.Transparent,
              MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
            ),
          ),
        )
        .padding(if (compact) 16.dp else 18.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
          contentColor = MaterialTheme.colorScheme.primary,
        ) {
          Icon(
            imageVector = Icons.Outlined.PlaylistPlay,
            contentDescription = null,
            modifier = Modifier.padding(10.dp).size(22.dp),
          )
        }
        Text(
          stringResource(R.string.nfqws_tester_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Text(
          stringResource(R.string.nfqws_tester_intro),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
      }
    }
  }
}

@Composable
private fun WarningGateCard(
  title: String,
  description: String,
  icon: ImageVector,
  accent: Color,
  buttonText: String,
  onButton: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = accent.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.20f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.14f), contentColor = accent) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp))
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
      }
      OutlinedButton(onClick = onButton) {
        Text(buttonText)
      }
    }
  }
}

@Composable
private fun StatusBanner(
  icon: ImageVector,
  title: String,
  subtitle: String,
  accent: Color,
) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = accent.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(shape = CircleShape, color = accent.copy(alpha = 0.14f), contentColor = accent) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(8.dp).size(18.dp))
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
      }
    }
  }
}

@Composable
private fun SessionCard(testerState: com.android.zdtd.service.diagnostics.nfqws.NfqwsTesterSessionState) {
  val statusAccent = when {
    testerState.errorText != null -> MaterialTheme.colorScheme.error
    testerState.phase == NfqwsTesterPhase.WAITING_DECISION -> MaterialTheme.colorScheme.tertiary
    testerState.phase == NfqwsTesterPhase.RUNNING || testerState.phase == NfqwsTesterPhase.PREPARING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
  }
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
    border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.14f)),
  ) {
    Column(
      Modifier
        .fillMaxWidth()
        .animateContentSize(animationSpec = tween(260))
        .padding(15.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(stringResource(R.string.nfqws_tester_session_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            testerState.errorText ?: testerState.statusText.ifBlank { stringResource(R.string.nfqws_tester_status_idle) },
            color = if (testerState.errorText != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Surface(
          shape = CircleShape,
          color = statusAccent.copy(alpha = 0.12f),
          contentColor = statusAccent,
        ) {
          AnimatedContent(targetState = testerState.phase, label = "nfqws_tester_phase_icon") { phase ->
            val image = when (phase) {
              NfqwsTesterPhase.PREPARING -> Icons.Outlined.HourglassTop
              NfqwsTesterPhase.RUNNING -> Icons.Outlined.PlayArrow
              NfqwsTesterPhase.WAITING_DECISION -> Icons.Outlined.CheckCircle
              NfqwsTesterPhase.FINISHED -> Icons.Outlined.CheckCircle
              NfqwsTesterPhase.ERROR -> Icons.Outlined.ReportProblem
              else -> Icons.Outlined.Refresh
            }
            Icon(image, contentDescription = null, modifier = Modifier.padding(10.dp).size(18.dp))
          }
        }
      }

      if (testerState.phase == NfqwsTesterPhase.PREPARING) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }

      AnimatedVisibility(
        visible = testerState.currentStrategy.isNotBlank(),
        enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(260)),
        exit = fadeOut(animationSpec = tween(180)) + shrinkVertically(animationSpec = tween(220)),
      ) {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        ) {
          Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              testerState.currentStrategy,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            if (testerState.strategies.isNotEmpty()) {
              LinearProgressIndicator(
                progress = ((testerState.currentIndex + 1).coerceAtLeast(0).toFloat() / testerState.strategies.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              MetricChip(text = stringResource(R.string.nfqws_tester_chip_cpu_fmt, testerState.cpuPercent))
              MetricChip(text = stringResource(R.string.nfqws_tester_chip_ram_fmt, testerState.rssMb))
            }
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

@Composable
private fun MetricChip(text: String) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
  ) {
    Text(
      text,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
    )
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
    shape = RoundedCornerShape(22.dp),
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
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
              .then(if (clickable) Modifier.clickable { onClick(item) } else Modifier)
              .padding(11.dp),
          ) {
            Text(item)
          }
        }
      }
    }
  }
}
