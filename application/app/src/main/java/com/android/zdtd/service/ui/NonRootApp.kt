package com.android.zdtd.service.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ui.settings.SettingsScreen

/**
 * Dedicated shell for the non-root runtime.
 *
 * Stats and Tools intentionally stay empty until their non-root data models are
 * implemented.  Keeping this shell separate prevents root-only state and daemon
 * assumptions from leaking into the VPN runtime.
 */
@Composable
fun NonRootApp(
  languageMode: String,
  themeMode: String,
  onLanguageModeChange: (String) -> Unit,
  onThemeModeChange: (String) -> Unit,
) {
  var tab by remember { mutableStateOf(Tab.HOME) }
  var showSettings by remember { mutableStateOf(false) }
  val compactBottomBar = rememberUseScrollableTabs() || rememberIsShortHeight()
  val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val topContentPadding = topInset + 78.dp
  val bottomContentPadding = bottomInset + if (compactBottomBar) 78.dp else 88.dp

  BackHandler(enabled = showSettings || tab != Tab.HOME) {
    if (showSettings) showSettings = false else tab = Tab.HOME
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    when (tab) {
      Tab.HOME -> NonRootHomeScreen(
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomContentPadding,
      )
      // These pages are deliberately blank until their non-root implementations
      // are designed. The shared floating chrome remains visible.
      Tab.STATS, Tab.APPS -> Box(Modifier.fillMaxSize())
      Tab.SUPPORT -> Box(Modifier.fillMaxSize().padding(bottom = bottomContentPadding)) {
        SupportScreen(topContentPadding = topContentPadding)
      }
    }

    NonRootTopBarCard(
      modifier = Modifier.align(Alignment.TopCenter),
      title = when (tab) {
        Tab.HOME -> stringResource(R.string.app_name)
        Tab.STATS -> stringResource(R.string.nav_stats)
        Tab.APPS -> stringResource(R.string.nav_programs)
        Tab.SUPPORT -> stringResource(R.string.nav_support)
      },
      onOpenSettings = { showSettings = true },
    )

    NonRootBottomNavigationCard(
      modifier = Modifier.align(Alignment.BottomCenter),
      compact = compactBottomBar,
      tab = tab,
      onTabChange = { tab = it },
    )
  }

  if (showSettings) {
    SettingsScreen(
      onDismiss = { showSettings = false },
      loading = false,
    ) {
      NonRootSettingsContent(
        languageMode = languageMode,
        onLanguageModeChange = onLanguageModeChange,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
      )
    }
  }
}

@Composable
private fun NonRootHomeScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
) {
  val screenPadding = rememberAdaptiveScreenPadding()
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      start = screenPadding,
      top = topContentPadding + 8.dp,
      end = screenPadding,
      bottom = bottomContentPadding + 8.dp,
    ),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Row(
          modifier = Modifier.padding(18.dp),
          horizontalArrangement = Arrangement.spacedBy(14.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.primary,
          ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(25.dp))
            }
          }
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
          ) {
            Text(
              text = stringResource(R.string.non_root_home_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = stringResource(R.string.non_root_home_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
            )
            Text(
              text = stringResource(R.string.non_root_home_restrictions),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NonRootTopBarCard(
  modifier: Modifier = Modifier,
  title: String,
  onOpenSettings: () -> Unit,
) {
  val shape = RoundedCornerShape(24.dp)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(horizontal = 12.dp)
      .padding(top = 8.dp),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .height(58.dp)
        .clip(shape),
      shape = shape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = title,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(46.dp)) {
          Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.settings_title),
          )
        }
      }
    }
  }
}

@Composable
private fun NonRootBottomNavigationCard(
  modifier: Modifier = Modifier,
  compact: Boolean,
  tab: Tab,
  onTabChange: (Tab) -> Unit,
) {
  val shape = RoundedCornerShape(24.dp)
  val homeLabel = stringResource(R.string.nav_home)
  val statsLabel = stringResource(R.string.nav_stats)
  val toolsLabel = stringResource(R.string.nav_programs)
  val supportLabel = stringResource(R.string.nav_support)

  Box(
    modifier = modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(horizontal = 12.dp)
      .padding(bottom = 8.dp),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .height(if (compact) 58.dp else 68.dp)
        .clip(shape),
      shape = shape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        NonRootBottomNavItem(tab == Tab.HOME, { onTabChange(Tab.HOME) }, compact, homeLabel) {
          Icon(Icons.Filled.Power, contentDescription = homeLabel, modifier = Modifier.size(22.dp))
        }
        NonRootBottomNavItem(tab == Tab.STATS, { onTabChange(Tab.STATS) }, compact, statsLabel) {
          Icon(Icons.Filled.Equalizer, contentDescription = statsLabel, modifier = Modifier.size(22.dp))
        }
        NonRootBottomNavItem(tab == Tab.APPS, { onTabChange(Tab.APPS) }, compact, toolsLabel) {
          Icon(Icons.Filled.Apps, contentDescription = toolsLabel, modifier = Modifier.size(22.dp))
        }
        NonRootBottomNavItem(tab == Tab.SUPPORT, { onTabChange(Tab.SUPPORT) }, compact, supportLabel) {
          Icon(Icons.Filled.Info, contentDescription = supportLabel, modifier = Modifier.size(22.dp))
        }
      }
    }
  }
}

@Composable
private fun RowScope.NonRootBottomNavItem(
  selected: Boolean,
  onClick: () -> Unit,
  compact: Boolean,
  label: String,
  icon: @Composable () -> Unit,
) {
  val itemColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
  val indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
  val itemShape = RoundedCornerShape(20.dp)

  Column(
    modifier = Modifier
      .weight(1f)
      .fillMaxHeight()
      .clip(itemShape)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
      ) { onClick() }
      .padding(horizontal = 2.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(18.dp))
        .background(if (selected) indicatorColor else Color.Transparent)
        .padding(
          horizontal = if (selected) 16.dp else 6.dp,
          vertical = if (selected) 5.dp else 3.dp,
        ),
      contentAlignment = Alignment.Center,
    ) {
      CompositionLocalProvider(LocalContentColor provides itemColor) { icon() }
    }
    if (!compact) {
      Spacer(Modifier.height(3.dp))
      Text(
        text = label,
        color = itemColor,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        maxLines = 1,
      )
    }
  }
}
