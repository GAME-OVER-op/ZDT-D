package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One canonical content padding for all root tool/profile screens.
 *
 * Before this helper every screen added its own magic offsets (+6/+8/+10/+12
 * on top, +12/+14/+16/+18 on bottom), so switching sections visibly jumped.
 * Keeping a single rule makes the screens feel like parts of one app.
 */
@Composable
internal fun rememberScreenContentPadding(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
): PaddingValues {
  val horizontal = if (rememberIsCompactWidth()) 10.dp else 12.dp
  val top = topContentPadding + if (rememberIsShortHeight()) 6.dp else 10.dp
  return PaddingValues(
    start = horizontal,
    end = horizontal,
    top = top,
    bottom = bottomContentPadding + 12.dp,
  )
}

/**
 * The single intro-card silhouette used at the top of every tool screen.
 * `trailing` hosts per-screen extras (status text, action button, chips).
 */
@Composable
internal fun ScreenIntroHeader(
  title: String,
  description: String,
  icon: ImageVector? = null,
  accent: Color = MaterialTheme.colorScheme.primary,
  compact: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
  content: (@Composable ColumnScope.() -> Unit)? = null,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(if (compact) 16.dp else 18.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (icon != null) {
        Surface(
          shape = CircleShape,
          color = accent.copy(alpha = 0.16f),
          contentColor = accent,
        ) {
          Icon(icon, contentDescription = null, modifier = Modifier.padding(11.dp).size(22.dp))
        }
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          if (description.isNotBlank()) {
            Text(
              description,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }
        }
        if (trailing != null) trailing()
      }
      if (content != null) content()
    }
  }
}

@Composable
internal fun EmptyStateCard(
  title: String,
  hint: String,
  icon: ImageVector? = null,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      horizontalAlignment = if (icon != null) Alignment.CenterHorizontally else Alignment.Start,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
      }
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      if (hint.isNotBlank()) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
      }
    }
  }
}
