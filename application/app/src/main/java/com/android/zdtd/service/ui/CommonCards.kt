package com.android.zdtd.service.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import kotlin.math.max

@Composable
fun EnabledCard(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  val compactWidth = rememberIsCompactWidth()
  val stateText = stringResource(if (checked) R.string.enabled_state_on else R.string.enabled_state_off)
  val stateColor = if (checked) Color(0xFF22C55E) else MaterialTheme.colorScheme.error
  val progress = remember { Animatable(if (checked) 1f else 0f) }
  var animating by remember { mutableStateOf(false) }
  var animationToken by remember { mutableStateOf(0) }

  LaunchedEffect(checked) {
    val token = animationToken + 1
    animationToken = token
    animating = true
    try {
      progress.animateTo(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
      )
    } finally {
      if (animationToken == token) animating = false
    }
  }

  val wave = if (animating) {
    val infinite = rememberInfiniteTransition(label = "enabled_card_wave")
    val value by infinite.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 950, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
      ),
      label = "enabled_card_wave_value",
    )
    value
  } else {
    0f
  }

  val onColor = Color(0xFF22C55E)
  val offColor = MaterialTheme.colorScheme.error
  val baseColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
  val activeBorder = if (checked) onColor else MaterialTheme.colorScheme.outline
  val shape = RoundedCornerShape(18.dp)

  Card(
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = baseColor),
    border = BorderStroke(1.dp, activeBorder.copy(alpha = if (checked) 0.42f else 0.22f)),
  ) {
    Box(Modifier.fillMaxWidth()) {
      Canvas(Modifier.matchParentSize()) {
        val p = progress.value.coerceIn(0f, 1f)
        val currentColor = lerp(offColor, onColor, p)
        val targetColor = if (checked) onColor else offColor
        val towardTarget = if (checked) p else 1f - p
        val maxDim = max(size.width, size.height)
        val origin = Offset(size.width - 56.dp.toPx(), size.height / 2f)
        val pulse = if (animating) wave * maxDim * 0.10f else 0f
        val radius = maxDim * (0.12f + 0.78f * towardTarget) + pulse

        drawRoundRect(
          color = currentColor.copy(alpha = 0.040f + 0.060f * towardTarget),
          size = size,
        )
        drawCircle(
          color = targetColor.copy(alpha = if (animating) 0.17f else 0.10f),
          radius = radius,
          center = origin,
        )
        if (animating) {
          drawCircle(
            color = targetColor.copy(alpha = 0.09f * (1f - wave.coerceIn(0f, 1f))),
            radius = maxDim * (0.22f + wave * 0.78f),
            center = origin,
          )
        }
      }

      if (compactWidth) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          EnabledCardText(title = title, stateText = stateText, stateColor = stateColor)
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
          }
        }
      } else {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          EnabledCardText(
            title = title,
            stateText = stateText,
            stateColor = stateColor,
            modifier = Modifier.weight(1f),
          )
          Spacer(Modifier.width(10.dp))
          Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
      }
    }
  }
}

@Composable
fun ProfileStatusCard(
  programId: String,
  profileName: String,
  checked: Boolean,
  onOpen: () -> Unit,
  onCheckedChange: (Boolean) -> Unit,
  onDelete: () -> Unit,
  deletable: Boolean = true,
) {
  var askDelete by remember { mutableStateOf(false) }
  if (askDelete) {
    AlertDialog(
      onDismissRequest = { askDelete = false },
      title = { Text(stringResource(R.string.delete_profile_title)) },
      text = { Text("$programId / $profileName") },
      confirmButton = {
        Button(onClick = { askDelete = false; onDelete() }) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        OutlinedButton(onClick = { askDelete = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  val compactWidth = rememberIsCompactWidth()
  val progress = remember { Animatable(if (checked) 1f else 0f) }
  var animating by remember { mutableStateOf(false) }
  var animationToken by remember { mutableStateOf(0) }

  LaunchedEffect(checked) {
    val token = animationToken + 1
    animationToken = token
    animating = true
    try {
      progress.animateTo(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 560, easing = FastOutSlowInEasing),
      )
    } finally {
      if (animationToken == token) animating = false
    }
  }

  val wave = if (animating) {
    val infinite = rememberInfiniteTransition(label = "profile_card_wave")
    val value by infinite.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 900, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
      ),
      label = "profile_card_wave_value",
    )
    value
  } else {
    0f
  }

  val onColor = Color(0xFF22C55E)
  val offColor = MaterialTheme.colorScheme.error
  val baseColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
  val accentColor = if (checked) onColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
  val shape = RoundedCornerShape(18.dp)

  Card(
    onClick = onOpen,
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = baseColor),
    border = BorderStroke(1.dp, accentColor.copy(alpha = if (checked) 0.42f else 0.24f)),
  ) {
    Box(Modifier.fillMaxWidth()) {
      Canvas(Modifier.matchParentSize()) {
        val p = progress.value.coerceIn(0f, 1f)
        val currentColor = lerp(offColor, onColor, p)
        val targetColor = if (checked) onColor else offColor
        val towardTarget = if (checked) p else 1f - p
        val maxDim = max(size.width, size.height)
        val origin = Offset(size.width - 112.dp.toPx(), size.height / 2f)
        val pulse = if (animating) wave * maxDim * 0.10f else 0f
        val radius = maxDim * (0.10f + 0.76f * towardTarget) + pulse

        drawRoundRect(
          color = currentColor.copy(alpha = 0.030f + 0.055f * towardTarget),
          size = size,
        )
        drawCircle(
          color = targetColor.copy(alpha = if (animating) 0.16f else 0.10f),
          radius = radius,
          center = origin,
        )
        if (animating) {
          drawCircle(
            color = targetColor.copy(alpha = 0.085f * (1f - wave.coerceIn(0f, 1f))),
            radius = maxDim * (0.20f + wave * 0.70f),
            center = origin,
          )
        }
      }

      if (compactWidth) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          ProfileIconBadge(checked = checked)
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            ProfileStatusCardText(profileName = profileName)
            ProfileEnabledPill(checked = checked)
          }
          Column(
            modifier = Modifier.widthIn(min = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            if (deletable) {
              IconButton(onClick = { askDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
              }
            } else {
              Spacer(Modifier.height(40.dp))
            }
          }
        }
      } else {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ProfileIconBadge(checked = checked)
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            ProfileStatusCardText(profileName = profileName)
            ProfileEnabledPill(checked = checked)
          }
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            if (deletable) {
              IconButton(onClick = { askDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
              }
            }
          }
        }
      }
    }
  }
}


@Composable
private fun ProfileIconBadge(checked: Boolean) {
  val accentColor = if (checked) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
  Surface(
    modifier = Modifier.size(52.dp),
    color = accentColor.copy(alpha = if (checked) 0.15f else 0.10f),
    contentColor = accentColor,
    shape = CircleShape,
    border = BorderStroke(1.dp, accentColor.copy(alpha = if (checked) 0.42f else 0.24f)),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Filled.Extension,
        contentDescription = null,
        modifier = Modifier.size(23.dp),
      )
    }
  }
}

@Composable
private fun ProfileEnabledPill(checked: Boolean) {
  val accentColor = if (checked) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
  val text = stringResource(if (checked) R.string.enabled_state_on else R.string.enabled_state_off)
  Surface(
    color = accentColor.copy(alpha = if (checked) 0.15f else 0.10f),
    contentColor = accentColor,
    shape = RoundedCornerShape(100.dp),
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.24f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier
          .size(6.dp)
          .background(accentColor, CircleShape),
      )
      Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun ProfileStatusCardText(
  profileName: String,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Text(
      profileName,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      stringResource(R.string.apply_after_restart_short),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun EnabledCardText(
  title: String,
  stateText: String,
  stateColor: Color,
  modifier: Modifier = Modifier,
) {
  Column(modifier) {
    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Spacer(Modifier.height(2.dp))
    Text(
      stateText,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = stateColor,
      maxLines = 1,
    )
    Spacer(Modifier.height(2.dp))
    Text(
      stringResource(R.string.enabled_card_apply_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )
  }
}
