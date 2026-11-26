package com.android.zdtd.service.ui

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.android.zdtd.service.R
import com.android.zdtd.service.SupportDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class DiagnosticsPhase { IDLE, COLLECTING, READY, ERROR }

private data class DiagnosticsSectionUi(
  val key: String,
  val titleRes: Int,
  val descRes: Int,
  val icon: ImageVector,
)

/** Root-only card that turns Support into a real diagnostics tool. */
@Composable
internal fun SupportDiagnosticsCard() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var selectedRaw by rememberSaveable {
    mutableStateOf(SupportDiagnostics.DEFAULT_SECTIONS.joinToString(","))
  }
  val selected = remember(selectedRaw) {
    selectedRaw.split(',')
      .map { it.trim() }
      .filter { it in SupportDiagnostics.DEFAULT_SECTIONS }
      .toSet()
  }

  var phase by remember { mutableStateOf(DiagnosticsPhase.IDLE) }
  var progress by remember { mutableStateOf(0f) }
  var stageProgress by remember { mutableStateOf(emptyMap<String, Int>()) }
  var result by remember { mutableStateOf<SupportDiagnostics.Result?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var hint by remember { mutableStateOf<String?>(null) }

  val sections = listOf(
    DiagnosticsSectionUi(
      SupportDiagnostics.SECTION_DEVICE,
      R.string.support_diag_section_device,
      R.string.support_diag_section_device_desc,
      Icons.Filled.Devices,
    ),
    DiagnosticsSectionUi(
      SupportDiagnostics.SECTION_DAEMON,
      R.string.support_diag_section_daemon,
      R.string.support_diag_section_daemon_desc,
      Icons.Filled.Terminal,
    ),
    DiagnosticsSectionUi(
      SupportDiagnostics.SECTION_NETWORK,
      R.string.support_diag_section_network,
      R.string.support_diag_section_network_desc,
      Icons.Filled.Security,
    ),
    DiagnosticsSectionUi(
      SupportDiagnostics.SECTION_LOGS,
      R.string.support_diag_section_logs,
      R.string.support_diag_section_logs_desc,
      Icons.Filled.Description,
    ),
    DiagnosticsSectionUi(
      SupportDiagnostics.SECTION_MODULE,
      R.string.support_diag_section_module,
      R.string.support_diag_section_module_desc,
      Icons.Filled.Folder,
    ),
  )

  val sectionTitle: @Composable (String) -> String = { key ->
    when (key) {
      SupportDiagnostics.SECTION_DEVICE -> stringResource(R.string.support_diag_section_device)
      SupportDiagnostics.SECTION_DAEMON -> stringResource(R.string.support_diag_section_daemon)
      SupportDiagnostics.SECTION_NETWORK -> stringResource(R.string.support_diag_section_network)
      SupportDiagnostics.SECTION_LOGS -> stringResource(R.string.support_diag_section_logs)
      else -> stringResource(R.string.support_diag_section_module)
    }
  }

  var appeared by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { appeared = true }
  val appear by animateFloatAsState(
    targetValue = if (appeared) 1f else 0f,
    animationSpec = tween(420, easing = FastOutSlowInEasing),
    label = "supportDiagnosticsAppear",
  )

  fun toggle(key: String) {
    val next = if (key in selected) selected - key else selected + key
    selectedRaw = next.joinToString(",")
  }

  fun collect() {
    if (selected.isEmpty() || phase == DiagnosticsPhase.COLLECTING) return
    phase = DiagnosticsPhase.COLLECTING
    progress = 0f
    stageProgress = emptyMap()
    result = null
    error = null
    hint = null
    scope.launch {
      val onStage: (String, Int) -> Unit = { key, percent ->
        stageProgress = stageProgress + (key to percent)
        progress = (stageProgress.values.average().toFloat() / 100f).coerceIn(0f, 1f)
      }
      runCatching { SupportDiagnostics.collect(context, selected, onStage) }
        .onSuccess { report ->
          result = report
          phase = DiagnosticsPhase.READY
          progress = 1f
        }
        .onFailure { throwable ->
          error = throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
          phase = DiagnosticsPhase.ERROR
        }
    }
  }

  fun share(file: File) {
    runCatching {
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
    }.onFailure { hint = it.message ?: context.getString(R.string.support_diag_error, "") }
  }

  fun copy(file: File) {
    runCatching {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("ZDT-D", file.readText(Charsets.UTF_8)))
      hint = context.getString(R.string.support_diag_copied)
    }.onFailure { hint = it.message ?: context.getString(R.string.support_diag_error, "") }
  }

  LaunchedEffect(hint) {
    if (hint != null) {
      delay(2_400L)
      hint = null
    }
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer {
        alpha = appear
        translationY = (1f - appear) * 28f
      }
      .animateContentSize(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      val glow = rememberInfiniteTransition(label = "supportDiagnosticsGlow")
      val glowAlpha by glow.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.20f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "supportDiagnosticsGlowAlpha",
      )
      val drift by glow.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(4_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "supportDiagnosticsGlowDrift",
      )
      val iconRotation by animateFloatAsState(
        targetValue = if (phase == DiagnosticsPhase.COLLECTING) 360f else 0f,
        animationSpec = spring(),
        label = "supportDiagnosticsIconRotation",
      )

      Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        Box(
          modifier = Modifier
            .matchParentSize()
            .offset(x = drift.dp)
            .background(
              Brush.radialGradient(
                listOf(
                  MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                  Color.Transparent,
                ),
              ),
            ),
        )
        Row(
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier = Modifier
              .size(46.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
              .graphicsLayer { rotationZ = iconRotation },
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Filled.Description,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp),
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.support_diag_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              stringResource(R.string.support_diag_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }
        }
      }

      if (phase == DiagnosticsPhase.IDLE) {
        sections.forEachIndexed { index, section ->
          StaggeredReveal(index = index) {
            DiagnosticsSectionRow(
              section = section,
              checked = section.key in selected,
              onToggle = { toggle(section.key) },
            )
          }
        }

        AnimatedVisibility(
          visible = selected.isEmpty(),
          enter = fadeIn() + expandVertically(),
          exit = fadeOut() + shrinkVertically(),
        ) {
          Text(
            stringResource(R.string.support_diag_empty_selection),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }

        DiagnosticsCollectButton(
          enabled = selected.isNotEmpty(),
          onClick = ::collect,
        )
      }

      AnimatedVisibility(
        visible = phase == DiagnosticsPhase.COLLECTING,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            stringResource(R.string.support_diag_collecting),
            style = MaterialTheme.typography.titleSmall,
          )
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
          )
          sections.filter { it.key in selected }.forEach { section ->
            val percent = stageProgress[section.key] ?: 0
            val done = percent >= 100
            val color by animateColorAsState(
              targetValue = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
              animationSpec = tween(240),
              label = "supportStageColor",
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Box(
                modifier = Modifier
                  .size(20.dp)
                  .clip(CircleShape)
                  .background(color.copy(alpha = if (done) 0.18f else 0.08f)),
                contentAlignment = Alignment.Center,
              ) {
                if (done) {
                  Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp),
                  )
                }
              }
              Text(
                sectionTitle(section.key),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f),
              )
              Text(
                "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = color,
              )
            }
          }
        }
      }

      AnimatedVisibility(
        visible = phase == DiagnosticsPhase.READY && result != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        val report = result
        if (report != null) {
          DiagnosticsReadyContent(
            report = report,
            onShare = { share(report.file) },
            onCopy = { copy(report.file) },
            onAgain = {
              result = null
              phase = DiagnosticsPhase.IDLE
            },
          )
        }
      }

      AnimatedVisibility(
        visible = phase == DiagnosticsPhase.ERROR,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            stringResource(R.string.support_diag_error, error.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
          OutlinedButton(onClick = ::collect, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.support_diag_again))
          }
        }
      }

      AnimatedVisibility(
        visible = !hint.isNullOrBlank(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        Text(
          hint.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
internal fun StaggeredReveal(index: Int, content: @Composable () -> Unit) {
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(index) { delay(index * 55L); visible = true }
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(260)) + slideInVertically(initialOffsetY = { it / 5 }, animationSpec = tween(320)),
    exit = fadeOut() + shrinkVertically(),
  ) {
    content()
  }
}

@Composable
private fun DiagnosticsSectionRow(
  section: DiagnosticsSectionUi,
  checked: Boolean,
  onToggle: () -> Unit,
) {
  val scale by animateFloatAsState(
    targetValue = if (checked) 1f else 0.985f,
    animationSpec = spring(dampingRatio = 0.7f, stiffness = 320f),
    label = "supportSectionScale",
  )
  val container by animateColorAsState(
    targetValue = if (checked) {
      MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    },
    animationSpec = tween(220),
    label = "supportSectionContainer",
  )
  val iconTint by animateColorAsState(
    targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    animationSpec = tween(220),
    label = "supportSectionTint",
  )

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .clickable(onClick = onToggle),
    shape = RoundedCornerShape(16.dp),
    color = container,
    border = BorderStroke(
      1.dp,
      MaterialTheme.colorScheme.outline.copy(alpha = if (checked) 0.42f else 0.16f),
    ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(iconTint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(section.icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(section.titleRes),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
        )
        AnimatedVisibility(
          visible = checked,
          enter = fadeIn() + expandVertically(),
          exit = fadeOut() + shrinkVertically(),
        ) {
          Text(
            stringResource(section.descRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      val checkScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.7f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 340f),
        label = "supportSectionCheck",
      )
      Box(
        modifier = Modifier
          .size(22.dp)
          .clip(CircleShape)
          .background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
          .then(
            if (checked) Modifier else BorderStroke(1.dp, MaterialTheme.colorScheme.outline).let { Modifier.border(it) },
          ),
        contentAlignment = Alignment.Center,
      ) {
        if (checked) {
          Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(14.dp).graphicsLayer {
              scaleX = checkScale
              scaleY = checkScale
            },
          )
        }
      }
    }
  }
}

@Composable
private fun DiagnosticsCollectButton(enabled: Boolean, onClick: () -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed && enabled) 0.975f else 1f,
    animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
    label = "supportCollectPress",
  )
  Button(
    onClick = onClick,
    enabled = enabled,
    interactionSource = interaction,
    modifier = Modifier.fillMaxWidth().height(50.dp).graphicsLayer {
      scaleX = scale
      scaleY = scale
    },
  ) {
    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.support_diag_collect))
  }
}

@Composable
private fun DiagnosticsReadyContent(
  report: SupportDiagnostics.Result,
  onShare: () -> Unit,
  onCopy: () -> Unit,
  onAgain: () -> Unit,
) {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { shown = true }
  val scale by animateFloatAsState(
    targetValue = if (shown) 1f else 0.6f,
    animationSpec = spring(dampingRatio = 0.55f, stiffness = 280f),
    label = "supportReadyScale",
  )

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .graphicsLayer { alpha = scale; scaleY = 0.9f + scale * 0.1f }
      .clip(RoundedCornerShape(16.dp))
      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Box(
        modifier = Modifier
          .size(34.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primary)
          .graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Filled.Check,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(19.dp),
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.support_diag_ready),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          stringResource(R.string.support_diag_size, formatSize(report.sizeBytes)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
      }
    }
    Text(
      stringResource(R.string.support_diag_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onShare, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.action_share), maxLines = 1)
      }
      OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.action_copy), maxLines = 1)
      }
    }
    OutlinedButton(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
      Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
      Spacer(Modifier.width(6.dp))
      Text(stringResource(R.string.support_diag_again))
    }
  }
}

private fun formatSize(bytes: Long): String = when {
  bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
  bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
  else -> "$bytes B"
}
