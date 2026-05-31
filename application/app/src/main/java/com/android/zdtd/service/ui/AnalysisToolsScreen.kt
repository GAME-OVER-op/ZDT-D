package com.android.zdtd.service.ui

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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R

@Composable
fun AnalysisToolsScreen(
  onOpenDpiDetector: () -> Unit,
  onOpenNfqwsTester: () -> Unit,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val shortHeight = rememberIsShortHeight()

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
      AnalysisIntroCard(compact = compact)
    }
    item {
      AnalysisToolCard(
        title = stringResource(R.string.dpi_detector_title),
        subtitle = stringResource(R.string.dpi_detector_short_desc),
        badge = stringResource(R.string.analysis_tools_badge_basic),
        icon = Icons.Outlined.Tune,
        accent = MaterialTheme.colorScheme.primary,
        onClick = onOpenDpiDetector,
      )
    }
    item {
      AnalysisToolCard(
        title = stringResource(R.string.nfqws_tester_title),
        subtitle = stringResource(R.string.nfqws_tester_short_desc),
        badge = stringResource(R.string.analysis_tools_badge_advanced),
        icon = Icons.Outlined.PlaylistPlay,
        accent = MaterialTheme.colorScheme.tertiary,
        onClick = onOpenNfqwsTester,
      )
    }
  }
}

@Composable
private fun AnalysisIntroCard(compact: Boolean) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
              Color.Transparent,
              MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
            ),
          ),
        )
        .padding(if (compact) 16.dp else 18.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
          contentColor = MaterialTheme.colorScheme.primary,
        ) {
          Icon(
            imageVector = Icons.Outlined.Speed,
            contentDescription = null,
            modifier = Modifier.padding(10.dp).size(22.dp),
          )
        }
        Text(
          text = stringResource(R.string.analysis_tools_header_title),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = stringResource(R.string.analysis_tools_header_desc),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
      }
    }
  }
}

@Composable
private fun AnalysisToolCard(
  title: String,
  subtitle: String,
  badge: String,
  icon: ImageVector,
  accent: Color,
  onClick: (() -> Unit)?,
) {
  val enabled = onClick != null
  val containerAlpha = if (enabled) 0.70f else 0.48f
  val accentBorderAlpha = if (enabled) 0.34f else 0.14f
  val accentGradientAlpha = if (enabled) 0.13f else 0.06f
  val accentSurfaceAlpha = if (enabled) 0.18f else 0.10f
  val subtitleAlpha = if (enabled) 0.70f else 0.48f
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(20.dp))
      .then(if (enabled) Modifier.clickable { onClick?.invoke() } else Modifier),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface.copy(alpha = containerAlpha),
    ),
    border = BorderStroke(1.dp, accent.copy(alpha = accentBorderAlpha)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.horizontalGradient(
            listOf(accent.copy(alpha = accentGradientAlpha), Color.Transparent),
          ),
        )
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = accentSurfaceAlpha),
        contentColor = accent,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.padding(12.dp).size(26.dp),
        )
      }
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          Surface(
            shape = CircleShape,
            color = accent.copy(alpha = 0.14f),
            contentColor = accent,
          ) {
            Text(
              text = badge,
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = subtitleAlpha),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (enabled) {
        Icon(
          imageVector = Icons.Outlined.ChevronRight,
          contentDescription = null,
          tint = accent.copy(alpha = 0.90f),
          modifier = Modifier.size(24.dp),
        )
      } else {
        Spacer(Modifier.size(24.dp))
      }
    }
  }
}
