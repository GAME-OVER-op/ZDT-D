package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.RootConfigManager

/**
 * True when the app is effectively Russian: either an explicit Russian mode,
 * or auto mode following a Russian system locale. Shared by features that are
 * shown only to one language group (support author link, URnetwork).
 */
@Composable
internal fun rememberIsRussianAppLanguage(): Boolean {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val languageMode = remember(context) {
    RootConfigManager(context.applicationContext).getAppLanguageMode().trim().lowercase()
  }
  val effectiveLanguage = remember(configuration) {
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      configuration.locales.get(0)
    } else {
      @Suppress("DEPRECATION")
      configuration.locale
    }
    locale?.language?.lowercase().orEmpty()
  }
  return languageMode == "ru" || (languageMode == "auto" && effectiveLanguage == "ru")
}

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

/** Category divider used by the root settings screen. */
@Composable
internal fun SettingsCategoryHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 2.dp),
  )
}

/**
 * One card shape for every settings entry: switch, action, chip group or a
 * custom body. All sections of the app use this instead of their own radius,
 * colour and typography.
 */
@Composable
internal fun SettingsCard(
  title: String,
  body: String? = null,
  icon: ImageVector? = null,
  accent: Color = MaterialTheme.colorScheme.primary,
  danger: Boolean = false,
  enabled: Boolean = true,
  trailing: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)? = null,
  content: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val tint = if (danger) MaterialTheme.colorScheme.error else accent
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border = BorderStroke(1.dp, tint.copy(alpha = 0.24f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (icon != null) {
          Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = tint.copy(alpha = 0.14f),
            contentColor = tint,
          ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
          }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          if (!body.isNullOrBlank()) {
            Text(
              body,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
            )
          }
        }
        if (trailing != null) trailing()
      }
      if (content != null) content()
    }
  }
}

/** Convenience card for a boolean setting. */
@Composable
internal fun SettingsSwitchCard(
  title: String,
  body: String? = null,
  icon: ImageVector? = null,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
) {
  SettingsCard(
    title = title,
    body = body,
    icon = icon,
    enabled = enabled,
    trailing = {
      Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    },
  )
}

