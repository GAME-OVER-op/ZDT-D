package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
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
import com.android.zdtd.service.R
import com.android.zdtd.service.diagnostics.dpi.DpiDetectorEvent
import com.android.zdtd.service.diagnostics.dpi.DpiDetectorRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private data class DpiPlannedTest(
  val stageId: String,
  val titleRes: Int,
  val descRes: Int,
  val icon: ImageVector,
)

private data class DpiProbeCheckUiState(
  val name: String,
  val status: String,
  val detail: String,
  val value: String,
  val sizeLabel: String,
)

private data class DpiProbeUiState(
  val key: String,
  val title: String,
  val target: String,
  val sizeLabel: String,
  val technical: Map<String, String> = emptyMap(),
  val checks: List<DpiProbeCheckUiState> = emptyList(),
  val diagnosis: String = "",
  val status: String,
  val detail: String,
)

private data class DpiStageUiState(
  val id: String,
  val titleRes: Int,
  val descRes: Int,
  val icon: ImageVector,
  val status: String = "pending",
  val detail: String = "",
  val plannedTotal: Int = 0,
  val diagnosis: String = "",
  val probes: List<DpiProbeUiState> = emptyList(),
)

@Composable
fun DpiDetectorScreen(
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val shortHeight = rememberIsShortHeight()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val screenListState = rememberLazyListState()
  val stages = remember { mutableStateListOf<DpiStageUiState>().apply { addAll(defaultDpiStages()) } }
  var running by remember { mutableStateOf(false) }
  var hasRun by remember { mutableStateOf(false) }
  var currentStageId by remember { mutableStateOf<String?>(null) }
  var currentProbe by remember { mutableStateOf("") }
  var manuallyExpandedStageIds by remember { mutableStateOf(setOf<String>()) }
  var showAllStageIds by remember { mutableStateOf(setOf<String>()) }
  var summaryStatus by remember { mutableStateOf<String?>(null) }
  var summaryDetail by remember { mutableStateOf("") }
  var runnerJob by remember { mutableStateOf<Job?>(null) }
  var runningTick by remember { mutableStateOf(0) }

  LaunchedEffect(running) {
    if (!running) {
      runningTick = 0
      return@LaunchedEffect
    }
    while (running) {
      delay(520)
      runningTick = (runningTick + 1) % 3
    }
  }

  fun resetRunState() {
    stages.clear()
    stages.addAll(defaultDpiStages())
    currentStageId = null
    currentProbe = ""
    manuallyExpandedStageIds = emptySet()
    showAllStageIds = emptySet()
    summaryStatus = null
    summaryDetail = ""
  }

  fun startScan() {
    if (running) return
    resetRunState()
    hasRun = true
    running = true
    scope.launch {
      delay(180)
      screenListState.animateScrollToItem(3)
    }
    runnerJob = scope.launch {
      DpiDetectorRunner(context)
        .runNdjsonStream(quick = false)
        .collect { event ->
          when (event) {
            is DpiDetectorEvent.Meta -> {
              currentStageId = currentStageId ?: "dns_integrity"
              if (currentProbe.isBlank()) currentProbe = "Launching dpi-detector"
              stages.updateStage(currentStageId ?: "dns_integrity") {
                it.copy(status = if (it.status == "idle") "checking" else it.status, detail = "Preparing detector process")
              }
            }
            is DpiDetectorEvent.Started -> {
              currentStageId = event.test
              currentProbe = event.title
              stages.updateStage(event.test) { it.copy(status = "checking", detail = event.title, plannedTotal = event.totalProbes) }
            }
            is DpiDetectorEvent.Probe -> {
              currentStageId = event.test
              currentProbe = buildString {
                append(event.name.ifBlank { event.key })
                if (event.target.isNotBlank()) append(" • ").append(event.target)
              }
              stages.updateProbe(event)
            }
            is DpiDetectorEvent.Progress -> {
              currentStageId = event.test
              currentProbe = event.detail
              stages.updateStage(event.test) {
                it.copy(status = if (event.status == "running") "checking" else it.status, detail = event.detail)
              }
            }
            is DpiDetectorEvent.Result -> {
              stages.updateStage(event.test) { it.copy(status = event.status, detail = event.detail, diagnosis = event.diagnosis) }
            }
            is DpiDetectorEvent.Finished -> {
              if (event.test == "summary") {
                summaryStatus = event.status
                summaryDetail = event.data
                currentStageId = null
                currentProbe = ""
                running = false
              }
            }
            is DpiDetectorEvent.Error -> {
              summaryStatus = "error"
              summaryDetail = event.message
              running = false
              stages.replaceAllStages { stage -> stage.stopCheckingProbes("Stopped after error") }
            }
          }
        }
      running = false
    }
  }

  fun stopScan() {
    scope.launch { DpiDetectorRunner(context).stopRunningProcess() }
    runnerJob?.cancel()
    runnerJob = null
    running = false
    summaryStatus = "stopped"
    summaryDetail = "Stopped by user"
    stages.replaceAllStages { stage ->
      stage.stopCheckingProbes("Stopped by user")
    }
  }

  LazyColumn(
    state = screenListState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = if (compact) 10.dp else 12.dp,
      end = if (compact) 10.dp else 12.dp,
      top = topContentPadding + if (shortHeight) 6.dp else 10.dp,
      bottom = bottomContentPadding + 12.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(if (shortHeight) 8.dp else 10.dp),
  ) {
    item { DpiDetectorHeroCard(compact = compact) }
    item {
      DpiDetectorActionCard(
        running = running,
        runningTick = runningTick,
        onRun = ::startScan,
        onStop = ::stopScan,
      )
    }
    item {
      AnimatedVisibility(
        visible = !running && !hasRun,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = stringResource(R.string.dpi_detector_planned_tests),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
          )
          defaultDpiPlannedTests().forEach { test ->
            DpiPlannedTestCard(test = test)
          }
        }
      }
    }
    item {
      AnimatedVisibility(
        visible = running || hasRun,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        DpiProcessCard(
          running = running,
          currentStage = stages.firstOrNull { it.id == currentStageId },
          currentProbe = currentProbe,
          stages = stages,
          summaryStatus = summaryStatus,
          summaryDetail = summaryDetail,
          expandedStageIds = manuallyExpandedStageIds,
          showAllStageIds = showAllStageIds,
          onToggleStage = { stageId ->
            manuallyExpandedStageIds = if (stageId in manuallyExpandedStageIds) manuallyExpandedStageIds - stageId else manuallyExpandedStageIds + stageId
          },
          onToggleShowAll = { stageId ->
            showAllStageIds = if (stageId in showAllStageIds) showAllStageIds - stageId else showAllStageIds + stageId
          },
        )
      }
    }
  }
}

@Composable
private fun DpiDetectorHeroCard(compact: Boolean) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(26.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
              Color.Transparent,
              MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
            ),
          ),
        )
        .padding(if (compact) 16.dp else 18.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
          contentColor = MaterialTheme.colorScheme.primary,
        ) {
          Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            modifier = Modifier.padding(13.dp).size(32.dp),
          )
        }
        Text(
          text = stringResource(R.string.dpi_detector_title),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = stringResource(R.string.dpi_detector_desc),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          DpiChip(text = stringResource(R.string.dpi_detector_chip_dpi))
          DpiChip(text = stringResource(R.string.dpi_detector_chip_blocking))
          DpiChip(text = stringResource(R.string.dpi_detector_chip_analysis))
        }
      }
    }
  }
}

@Composable
private fun DpiDetectorActionCard(
  running: Boolean,
  runningTick: Int,
  onRun: () -> Unit,
  onStop: () -> Unit,
) {
  val runningText = when (runningTick) {
    0 -> stringResource(R.string.dpi_detector_running_1)
    1 -> stringResource(R.string.dpi_detector_running_2)
    else -> stringResource(R.string.dpi_detector_running_3)
  }
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = stringResource(R.string.dpi_detector_status_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = stringResource(R.string.dpi_detector_status_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        AnimatedVisibility(
          visible = running,
          enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
          exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) {
          OutlinedButton(onClick = onStop) {
            Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(R.string.dpi_detector_stop))
          }
        }
        Button(
          onClick = onRun,
          enabled = !running,
          modifier = Modifier
            .weight(1f)
            .animateContentSize(),
          colors = if (running) {
            ButtonDefaults.buttonColors(
              disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
              disabledContentColor = MaterialTheme.colorScheme.primary,
            )
          } else {
            ButtonDefaults.buttonColors()
          },
        ) {
          AnimatedContent(
            targetState = running,
            label = "dpiDetectorRunButtonContent",
          ) { isRunning ->
            if (!isRunning) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.dpi_detector_run))
              }
            } else {
              Box(modifier = Modifier.width(126.dp), contentAlignment = Alignment.Center) {
                Text(text = runningText)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DpiProcessCard(
  running: Boolean,
  currentStage: DpiStageUiState?,
  currentProbe: String,
  stages: List<DpiStageUiState>,
  summaryStatus: String?,
  summaryDetail: String,
  expandedStageIds: Set<String>,
  showAllStageIds: Set<String>,
  onToggleStage: (String) -> Unit,
  onToggleShowAll: (String) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (running) 0.34f else 0.18f)),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (running) {
          CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(if (running) R.string.dpi_detector_process_title else R.string.dpi_detector_result_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          val subtitle = when {
            currentStage != null -> stringResource(currentStage.titleRes)
            summaryStatus != null -> statusLabel(summaryStatus)
            else -> stringResource(R.string.dpi_detector_waiting)
          }
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
          )
          if (currentProbe.isNotBlank()) {
            Text(
              text = currentProbe,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      if (!running && summaryStatus != null) {
        DpiSummaryBox(status = summaryStatus, detail = summaryDetail)
      }

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stages.forEach { stage ->
          val expanded = stage.id == currentStage?.id || stage.id in expandedStageIds
          DpiStageResultCard(
            stage = stage,
            expanded = expanded,
            showAll = stage.id in showAllStageIds,
            onToggleExpanded = { onToggleStage(stage.id) },
            onToggleShowAll = { onToggleShowAll(stage.id) },
          )
        }
      }
    }
  }
}

@Composable
private fun DpiSummaryBox(status: String, detail: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = statusColor(status).copy(alpha = 0.11f),
    border = BorderStroke(1.dp, statusColor(status).copy(alpha = 0.28f)),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = stringResource(R.string.dpi_detector_overall_result, riskLabel(status)),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = statusColor(status),
      )
      Text(
        text = if (status == "stopped") stringResource(R.string.dpi_detector_stopped_desc) else stringResource(R.string.dpi_detector_result_keep_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
      )
      if (detail.isNotBlank() && status == "error") {
        Text(
          text = detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun DpiStageResultCard(
  stage: DpiStageUiState,
  expanded: Boolean,
  showAll: Boolean,
  onToggleExpanded: () -> Unit,
  onToggleShowAll: () -> Unit,
) {
  val completed = stage.probes.count { it.status != "pending" && it.status != "checking" }
  val total = stage.plannedTotal.takeIf { it > 0 } ?: stage.probes.size
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize()
      .clickable { onToggleExpanded() },
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
    border = BorderStroke(1.dp, statusColor(stage.status).copy(alpha = 0.20f)),
  ) {
    Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
          shape = CircleShape,
          color = statusColor(stage.status).copy(alpha = 0.13f),
          contentColor = statusColor(stage.status),
        ) {
          if (stage.status == "checking") {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
          } else {
            Icon(stage.icon, contentDescription = null, modifier = Modifier.padding(8.dp).size(18.dp))
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(stage.titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = stage.detail.ifBlank { stringResource(stage.descRes) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (stage.diagnosis.isNotBlank()) {
            Text(
              text = stringResource(R.string.dpi_detector_stage_result_prefix, diagnosisLabel(stage.diagnosis)),
              style = MaterialTheme.typography.labelSmall,
              color = statusColor(stage.status),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (total > 0) {
            Text(
              text = stringResource(R.string.dpi_detector_stage_probe_count, completed, total),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            )
          }
        }
        DpiStatusPill(status = stage.status)
        Icon(
          imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
          modifier = Modifier.size(20.dp),
        )
      }

      AnimatedVisibility(
        visible = expanded && stage.probes.isNotEmpty(),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
      ) {
        DpiProbeList(
          probes = stage.probes,
        )
      }
    }
  }
}

@Composable
private fun DpiProbeList(
  probes: List<DpiProbeUiState>,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    probes.forEach { probe ->
      key(probe.key) {
        DpiAnimatedProbeRow(probe = probe)
      }
    }
  }
}

@Composable
private fun DpiAnimatedProbeRow(
  probe: DpiProbeUiState,
  modifier: Modifier = Modifier,
) {
  var visible by remember(probe.key) { mutableStateOf(false) }
  LaunchedEffect(probe.key) { visible = true }
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
  ) {
    DpiProbeRow(probe = probe)
  }
}

@Composable
private fun DpiProbeRow(probe: DpiProbeUiState) {
  var expanded by remember(probe.key) { mutableStateOf(false) }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize()
      .clickable { expanded = !expanded },
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.38f),
    border = BorderStroke(1.dp, statusColor(probe.status).copy(alpha = 0.16f)),
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        DpiWorkflowStatusIcon(status = probe.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Text(
            text = probe.title.ifBlank { probe.key },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = listOf(probe.target, probe.detail).filter { it.isNotBlank() }.joinToString(" • "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            maxLines = if (expanded) 3 else 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (probe.sizeLabel.isNotBlank()) {
          DpiSizePill(sizeLabel = probe.sizeLabel)
        }
        DpiStatusPill(status = probe.status)
        Icon(
          imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f),
          modifier = Modifier.size(18.dp),
        )
      }

      AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
      ) {
        Column(
          modifier = Modifier.padding(start = 34.dp, top = 8.dp, end = 4.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Text(
            text = stringResource(R.string.dpi_detector_probe_basic_info),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
          )
          DpiProbeDetailLine(label = stringResource(R.string.dpi_detector_probe_detail_size), value = probe.sizeLabel.ifBlank { "—" })
          DpiProbeDetailLine(label = stringResource(R.string.dpi_detector_probe_detail_target), value = probe.target.ifBlank { "—" })
          DpiProbeDetailLine(label = stringResource(R.string.dpi_detector_probe_detail_status), value = statusLabel(probe.status))
          DpiProbeDetailLine(label = stringResource(R.string.dpi_detector_probe_detail_details), value = probe.detail.ifBlank { "—" })
          if (probe.diagnosis.isNotBlank()) {
            DpiProbeDetailLine(label = stringResource(R.string.dpi_detector_probe_detail_result), value = diagnosisLabel(probe.diagnosis))
          }
          DpiProbeDetailLine(label = stringResource(R.string.dpi_detector_probe_detail_key), value = probe.key)
          if (probe.checks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            DpiProbeChecksTable(checks = probe.checks)
          }
          if (probe.technical.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            DpiTechnicalInfoTable(technical = probe.technical)
          }
        }
      }
    }
  }
}

@Composable
private fun DpiWorkflowStatusIcon(status: String) {
  val normalized = normalizeProbeStatus(status)
  Surface(
    shape = CircleShape,
    color = statusColor(normalized).copy(alpha = 0.15f),
    contentColor = statusColor(normalized),
  ) {
    Box(
      modifier = Modifier.padding(4.dp).size(18.dp),
      contentAlignment = Alignment.Center,
    ) {
      when (normalized) {
        "checking" -> DpiWorkflowRunningIcon(modifier = Modifier.size(16.dp))
        "available" -> Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(17.dp))
        "blocked" -> Icon(Icons.Outlined.Cancel, contentDescription = null, modifier = Modifier.size(17.dp))
        "suspicious" -> Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(17.dp))
        "error" -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(17.dp))
        "stopped" -> Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(17.dp))
        else -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(16.dp))
      }
    }
  }
}


@Composable
private fun DpiWorkflowRunningIcon(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(
      modifier = Modifier.matchParentSize(),
      strokeWidth = 2.dp,
      color = MaterialTheme.colorScheme.primary,
    )
    Box(
      modifier = Modifier
        .size(5.dp)
        .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
  }
}


@Composable
private fun DpiProbeChecksTable(checks: List<DpiProbeCheckUiState>) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = stringResource(R.string.dpi_detector_probe_address_checks),
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
    )
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
    ) {
      Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        checks.forEach { check ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            DpiWorkflowStatusIcon(status = check.status)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
              Text(
                text = check.name.ifBlank { stringResource(R.string.dpi_detector_probe_check_unknown) },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = listOf(check.value, check.detail).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "—" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
              )
            }
            if (check.sizeLabel.isNotBlank()) {
              DpiSizePill(sizeLabel = check.sizeLabel)
            }
            DpiStatusPill(status = check.status)
          }
        }
      }
    }
  }
}

@Composable
private fun DpiTechnicalInfoTable(technical: Map<String, String>) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = stringResource(R.string.dpi_detector_probe_technical_info),
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
    )
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
    ) {
      Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        technical.entries.sortedBy { it.key }.forEach { (key, value) ->
          DpiProbeDetailLine(label = technicalLabel(key), value = value.ifBlank { "—" })
        }
      }
    }
  }
}

private fun technicalLabel(key: String): String = key
  .replace('_', ' ')
  .replace('-', ' ')
  .split(' ')
  .filter { it.isNotBlank() }
  .joinToString(" ") { part -> part.replaceFirstChar { ch -> ch.uppercase() } }


@Composable
private fun DpiProbeDetailLine(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
      modifier = Modifier.width(92.dp),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
      modifier = Modifier.weight(1f),
    )
  }
}


@Composable
private fun DpiSizePill(sizeLabel: String) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
    contentColor = MaterialTheme.colorScheme.secondary,
  ) {
    Text(
      text = sizeLabel,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun DpiStatusPill(status: String) {
  Surface(
    shape = CircleShape,
    color = statusColor(status).copy(alpha = 0.13f),
    contentColor = statusColor(status),
  ) {
    Text(
      text = statusLabel(status),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun DpiPlannedTestCard(test: DpiPlannedTest) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.54f)),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
  ) {
    Row(
      modifier = Modifier.padding(13.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
        contentColor = MaterialTheme.colorScheme.primary,
      ) {
        Icon(
          imageVector = test.icon,
          contentDescription = null,
          modifier = Modifier.padding(9.dp).size(20.dp),
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(test.titleRes),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = stringResource(test.descRes),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
      }
    }
  }
}

@Composable
private fun DpiChip(text: String) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
    contentColor = MaterialTheme.colorScheme.primary,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
    )
  }
}

private fun defaultDpiStages(): List<DpiStageUiState> = listOf(
  DpiStageUiState("dns_integrity", R.string.dpi_detector_stage_dns_integrity_title, R.string.dpi_detector_stage_dns_integrity_desc, Icons.Outlined.Dns),
  DpiStageUiState("dns_availability", R.string.dpi_detector_stage_dns_availability_title, R.string.dpi_detector_stage_dns_availability_desc, Icons.Outlined.Public),
  DpiStageUiState("domains", R.string.dpi_detector_stage_domains_title, R.string.dpi_detector_stage_domains_desc, Icons.Outlined.Tune),
  DpiStageUiState("tcp16", R.string.dpi_detector_stage_tcp16_title, R.string.dpi_detector_stage_tcp16_desc, Icons.Outlined.AltRoute),
  DpiStageUiState("whitelist_sni", R.string.dpi_detector_stage_sni_title, R.string.dpi_detector_stage_sni_desc, Icons.Outlined.Public),
  DpiStageUiState("telegram", R.string.dpi_detector_stage_telegram_title, R.string.dpi_detector_stage_telegram_desc, Icons.Outlined.Extension),
)

private fun defaultDpiPlannedTests(): List<DpiPlannedTest> = defaultDpiStages().map {
  DpiPlannedTest(it.id, it.titleRes, it.descRes, it.icon)
}

private fun MutableList<DpiStageUiState>.updateStage(id: String, transform: (DpiStageUiState) -> DpiStageUiState) {
  val index = indexOfFirst { it.id == id }
  if (index >= 0) this[index] = transform(this[index])
}

private fun MutableList<DpiStageUiState>.replaceAllStages(transform: (DpiStageUiState) -> DpiStageUiState) {
  indices.forEach { index -> this[index] = transform(this[index]) }
}

private fun MutableList<DpiStageUiState>.updateProbe(event: DpiDetectorEvent.Probe) {
  updateStage(event.test) { stage ->
    val probe = DpiProbeUiState(
      key = event.key.ifBlank { "${event.test}:${event.name}:${event.target}" },
      title = event.name,
      target = event.target,
      sizeLabel = event.sizeLabel,
      technical = event.technical,
      checks = event.checks.map { check ->
        DpiProbeCheckUiState(
          name = check.name,
          status = normalizeProbeStatus(check.status),
          detail = check.detail,
          value = check.value,
          sizeLabel = check.sizeLabel,
        )
      },
      diagnosis = event.diagnosis,
      status = normalizeProbeStatus(event.status),
      detail = event.detail,
    )
    val probes = stage.probes.toMutableList()
    val existing = probes.indexOfFirst { it.key == probe.key }
    if (existing >= 0) probes[existing] = probe else probes.add(probe)
    val stageStatus = if (probe.status == "checking") "checking" else stage.status.let { if (it == "pending") "checking" else it }
    stage.copy(status = stageStatus, detail = probe.detail, probes = probes)
  }
}


private fun DpiStageUiState.stopCheckingProbes(detailText: String): DpiStageUiState {
  val updatedProbes = probes.map { probe ->
    if (probe.status == "checking") {
      probe.copy(status = "stopped", detail = probe.detail.ifBlank { detailText })
    } else {
      probe
    }
  }
  val updatedStatus = if (status == "checking") "stopped" else status
  val updatedDetail = if (status == "checking") detail.ifBlank { detailText } else detail
  return copy(status = updatedStatus, detail = updatedDetail, probes = updatedProbes)
}

private fun normalizeProbeStatus(status: String): String = when (status) {
  "ok" -> "available"
  "running" -> "checking"
  "timeout", "refused", "unreachable", "failed", "http_451_blocked" -> "blocked"
  "reset", "tcp_rst", "tls_rst", "tls_alert", "tls_spoof", "tls_mitm", "tcp16", "pool_timeout",
  "partial", "not_found", "stalled", "slow", "download", "skipped", "redirect_suspicious" -> "suspicious"
  else -> status.ifBlank { "pending" }
}

@Composable
private fun diagnosisLabel(code: String): String = when (code) {
  "clean" -> stringResource(R.string.dpi_detector_diagnosis_clean)
  "possible_dns_block" -> stringResource(R.string.dpi_detector_diagnosis_dns_block)
  "possible_tls_sni_block" -> stringResource(R.string.dpi_detector_diagnosis_tls_sni_block)
  "possible_http_filtering" -> stringResource(R.string.dpi_detector_diagnosis_http_filtering)
  "possible_tcp_block" -> stringResource(R.string.dpi_detector_diagnosis_tcp_block)
  "possible_domain_block" -> stringResource(R.string.dpi_detector_diagnosis_domain_block)
  "possible_telegram_block" -> stringResource(R.string.dpi_detector_diagnosis_telegram_block)
  "possible_sni_bypass_found" -> stringResource(R.string.dpi_detector_diagnosis_sni_bypass)
  "partial_unavailable" -> stringResource(R.string.dpi_detector_diagnosis_partial_unavailable)
  "blocked_unknown" -> stringResource(R.string.dpi_detector_diagnosis_blocked_unknown)
  else -> code.ifBlank { stringResource(R.string.dpi_detector_diagnosis_unknown) }
}

@Composable
private fun statusLabel(status: String): String = when (normalizeProbeStatus(status)) {
  "pending" -> stringResource(R.string.dpi_detector_status_pending)
  "checking" -> stringResource(R.string.dpi_detector_status_checking)
  "available" -> stringResource(R.string.dpi_detector_status_available)
  "blocked" -> stringResource(R.string.dpi_detector_status_blocked)
  "suspicious" -> stringResource(R.string.dpi_detector_status_suspicious)
  "error" -> stringResource(R.string.dpi_detector_status_error)
  "stopped" -> stringResource(R.string.dpi_detector_status_stopped)
  "low" -> stringResource(R.string.dpi_detector_risk_low)
  "medium" -> stringResource(R.string.dpi_detector_risk_medium)
  "high" -> stringResource(R.string.dpi_detector_risk_high)
  else -> normalizeProbeStatus(status)
}

@Composable
private fun riskLabel(status: String): String = when (status) {
  "low" -> stringResource(R.string.dpi_detector_risk_low)
  "medium" -> stringResource(R.string.dpi_detector_risk_medium)
  "high" -> stringResource(R.string.dpi_detector_risk_high)
  "stopped" -> stringResource(R.string.dpi_detector_status_stopped)
  "error" -> stringResource(R.string.dpi_detector_status_error)
  else -> statusLabel(status)
}

@Composable
private fun statusColor(status: String): Color = when (normalizeProbeStatus(status)) {
  "available", "low" -> Color(0xFF4ADE80)
  "suspicious", "medium" -> Color(0xFFFACC15)
  "blocked", "error", "high" -> MaterialTheme.colorScheme.error
  "checking" -> MaterialTheme.colorScheme.primary
  "stopped" -> MaterialTheme.colorScheme.tertiary
  else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
}
