package com.android.zdtd.service.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.NonRootCascadeBackendMode
import com.android.zdtd.service.NonRootCascadeProfile
import com.android.zdtd.service.NonRootCascadeRouteItem
import com.android.zdtd.service.NonRootCascadeState
import com.android.zdtd.service.NonRootDirectOperaConfig
import com.android.zdtd.service.NonRootPortRegistry
import com.android.zdtd.service.NonRootSniEntry
import com.android.zdtd.service.NonRootT2sConfig
import com.android.zdtd.service.NonRootWorkMode
import com.android.zdtd.service.NonRootVpnState
import com.android.zdtd.service.R
import com.android.zdtd.service.ui.settings.SettingsScreen

/** Dedicated shell for the app-owned non-root path. */
@Composable
fun NonRootApp(
  languageMode: String,
  themeMode: String,
  workMode: NonRootWorkMode,
  directOperaPort: Int,
  directByeDpiPort: Int,
  directOperaConfig: NonRootDirectOperaConfig,
  cascadeState: NonRootCascadeState,
  t2sApiPort: Int,
  vpnState: NonRootVpnState,
  vpnLastError: String?,
  onVpnStart: () -> Unit,
  onVpnStop: () -> Unit,
  onLanguageModeChange: (String) -> Unit,
  onThemeModeChange: (String) -> Unit,
  onWorkModeChange: (NonRootWorkMode) -> Unit,
  onDirectOperaPortChange: (Int) -> Boolean,
  onDirectByeDpiPortChange: (Int) -> Boolean,
  onDirectOperaConfigChange: (NonRootDirectOperaConfig) -> Unit,
  onCreateCascadeProfile: (String) -> Unit,
  onUpdateCascadeProfile: (NonRootCascadeProfile) -> Unit,
  onCascadeProfilePortChange: (String, Int) -> Boolean,
  onCascadeProfileByeDpiPortChange: (String, Int) -> Boolean,
  onDeleteCascadeProfile: (String) -> Unit,
  onCascadeBackendModeChange: (NonRootCascadeBackendMode) -> Unit,
  onT2sConfigChange: (NonRootT2sConfig) -> Unit,
  onCascadeRouteChange: (List<NonRootCascadeRouteItem>) -> Unit,
) {
  var tab by remember { mutableStateOf(Tab.HOME) }
  var showSettings by remember { mutableStateOf(false) }
  var cascadeProfileId by remember { mutableStateOf<String?>(null) }
  var showT2sSettings by remember { mutableStateOf(false) }
  val compactBottomBar = rememberUseScrollableTabs() || rememberIsShortHeight()
  val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  val topContentPadding = topInset + 78.dp
  val bottomContentPadding = bottomInset + if (compactBottomBar) 78.dp else 88.dp

  BackHandler(enabled = showSettings || cascadeProfileId != null || showT2sSettings || tab != Tab.HOME) {
    when {
      showSettings -> showSettings = false
      cascadeProfileId != null -> cascadeProfileId = null
      showT2sSettings -> showT2sSettings = false
      else -> tab = Tab.HOME
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    val editedProfile = cascadeProfileId?.let { id -> cascadeState.profiles.firstOrNull { it.id == id } }
    when {
      editedProfile != null -> NonRootCascadeProfileEditorScreen(
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomInset + 16.dp,
        profile = editedProfile,
        onUpdateProfile = onUpdateCascadeProfile,
        onPortChange = onCascadeProfilePortChange,
        onByeDpiPortChange = onCascadeProfileByeDpiPortChange,
        onDelete = { id ->
          onDeleteCascadeProfile(id)
          cascadeProfileId = null
        },
      )
      showT2sSettings -> NonRootT2sSettingsScreen(
        topContentPadding = topContentPadding,
        bottomContentPadding = bottomInset + 16.dp,
        state = cascadeState,
        onBackendModeChange = onCascadeBackendModeChange,
        onT2sConfigChange = onT2sConfigChange,
        onRouteChange = onCascadeRouteChange,
        onUpdateProfile = onUpdateCascadeProfile,
      )
      else -> when (tab) {
        Tab.HOME -> NonRootHomeScreen(
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
          workMode = workMode,
          vpnState = vpnState,
          vpnLastError = vpnLastError,
          onVpnStart = onVpnStart,
          onVpnStop = onVpnStop,
        )
        Tab.STATS -> NonRootStatsScreen(
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
          workMode = workMode,
          t2sApiPort = t2sApiPort,
          vpnState = vpnState,
        )
        Tab.APPS -> NonRootToolsScreen(
          topContentPadding = topContentPadding,
          bottomContentPadding = bottomContentPadding,
          workMode = workMode,
          directOperaPort = directOperaPort,
          directByeDpiPort = directByeDpiPort,
          directOperaConfig = directOperaConfig,
          cascadeState = cascadeState,
          configurationEnabled = vpnState == NonRootVpnState.STOPPED || vpnState == NonRootVpnState.ERROR,
          onWorkModeChange = onWorkModeChange,
          onDirectOperaPortChange = onDirectOperaPortChange,
          onDirectByeDpiPortChange = onDirectByeDpiPortChange,
          onDirectOperaConfigChange = onDirectOperaConfigChange,
          onCreateCascadeProfile = onCreateCascadeProfile,
          onUpdateCascadeProfile = onUpdateCascadeProfile,
          onOpenCascadeProfile = { cascadeProfileId = it },
          onOpenT2sSettings = { showT2sSettings = true },
        )
        Tab.SUPPORT -> Box(Modifier.fillMaxSize().padding(bottom = bottomContentPadding)) {
          SupportScreen(topContentPadding = topContentPadding)
        }
      }
    }

    NonRootTopBarCard(
      modifier = Modifier.align(Alignment.TopCenter),
      title = when {
        editedProfile != null -> stringResource(R.string.non_root_profile_settings)
        showT2sSettings -> stringResource(R.string.non_root_t2s_settings)
        else -> when (tab) {
          Tab.HOME -> stringResource(R.string.app_name)
          Tab.STATS -> stringResource(R.string.nav_stats)
          Tab.APPS -> stringResource(R.string.nav_programs)
          Tab.SUPPORT -> stringResource(R.string.nav_support)
        }
      },
      onBack = when {
        editedProfile != null -> ({ cascadeProfileId = null })
        showT2sSettings -> ({ showT2sSettings = false })
        else -> null
      },
      onOpenSettings = { showSettings = true },
    )

    if (editedProfile == null && !showT2sSettings) {
      NonRootBottomNavigationCard(
        modifier = Modifier.align(Alignment.BottomCenter),
        compact = compactBottomBar,
        tab = tab,
        onTabChange = { tab = it },
      )
    }
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
  workMode: NonRootWorkMode,
  vpnState: NonRootVpnState,
  vpnLastError: String?,
  onVpnStart: () -> Unit,
  onVpnStop: () -> Unit,
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
        modifier = Modifier
          .fillMaxWidth()
          .animateContentSize(animationSpec = tween(220)),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Column(
          modifier = Modifier.padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Row(
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

          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                  text = stringResource(R.string.non_root_current_mode),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedContent(targetState = workMode, label = "nonRootMode") { mode ->
                  Text(
                    text = stringResource(
                      if (mode == NonRootWorkMode.DIRECT) R.string.non_root_mode_direct
                      else R.string.non_root_mode_cascade,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                }
              }
              Surface(
                shape = RoundedCornerShape(100.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
              ) {
                Text(
                  text = stringResource(
                    when (vpnState) {
                      NonRootVpnState.STOPPED -> R.string.home_power_stopped
                      NonRootVpnState.STARTING -> R.string.home_power_starting
                      NonRootVpnState.RUNNING -> R.string.home_power_running
                      NonRootVpnState.STOPPING -> R.string.home_power_stopping
                      NonRootVpnState.ERROR -> R.string.common_error
                    }
                  ),
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.SemiBold,
                )
              }
            }
          }

          AnimatedVisibility(visible = vpnState == NonRootVpnState.ERROR && !vpnLastError.isNullOrBlank()) {
            Text(
              text = vpnLastError.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }

          Button(
            onClick = {
              if (vpnState == NonRootVpnState.RUNNING) onVpnStop() else onVpnStart()
            },
            enabled = vpnState != NonRootVpnState.STARTING && vpnState != NonRootVpnState.STOPPING,
            modifier = Modifier.fillMaxWidth().height(52.dp),
          ) {
            Icon(Icons.Filled.Power, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(
              stringResource(if (vpnState == NonRootVpnState.RUNNING) R.string.widget_action_stop else R.string.widget_action_start),
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NonRootToolsScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  workMode: NonRootWorkMode,
  directOperaPort: Int,
  directByeDpiPort: Int,
  directOperaConfig: NonRootDirectOperaConfig,
  cascadeState: NonRootCascadeState,
  configurationEnabled: Boolean,
  onWorkModeChange: (NonRootWorkMode) -> Unit,
  onDirectOperaPortChange: (Int) -> Boolean,
  onDirectByeDpiPortChange: (Int) -> Boolean,
  onDirectOperaConfigChange: (NonRootDirectOperaConfig) -> Unit,
  onCreateCascadeProfile: (String) -> Unit,
  onUpdateCascadeProfile: (NonRootCascadeProfile) -> Unit,
  onOpenCascadeProfile: (String) -> Unit,
  onOpenT2sSettings: () -> Unit,
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
      NonRootModeCard(
        workMode = workMode,
        enabled = configurationEnabled,
        onWorkModeChange = onWorkModeChange,
      )
    }
    item {
      AnimatedContent(targetState = workMode, label = "nonRootToolsMode") { mode ->
        if (mode == NonRootWorkMode.DIRECT) {
          NonRootDirectToolsCard(
            port = directOperaPort,
            byedpiPort = directByeDpiPort,
            config = directOperaConfig,
            onPortChange = onDirectOperaPortChange,
            onByeDpiPortChange = onDirectByeDpiPortChange,
            onConfigChange = onDirectOperaConfigChange,
          )
        } else {
          NonRootCascadeToolsContent(
            state = cascadeState,
            onCreateProfile = onCreateCascadeProfile,
            onUpdateProfile = onUpdateCascadeProfile,
            onOpenProfile = onOpenCascadeProfile,
            onOpenT2sSettings = onOpenT2sSettings,
          )
        }
      }
    }
  }
}

@Composable
private fun NonRootModeCard(
  workMode: NonRootWorkMode,
  enabled: Boolean,
  onWorkModeChange: (NonRootWorkMode) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = stringResource(R.string.non_root_work_mode_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NonRootModeChoice(
          modifier = Modifier.weight(1f),
          selected = workMode == NonRootWorkMode.DIRECT,
          enabled = enabled,
          title = stringResource(R.string.non_root_mode_direct),
          onClick = { onWorkModeChange(NonRootWorkMode.DIRECT) },
        )
        NonRootModeChoice(
          modifier = Modifier.weight(1f),
          selected = workMode == NonRootWorkMode.CASCADE,
          enabled = enabled,
          title = stringResource(R.string.non_root_mode_cascade),
          onClick = { onWorkModeChange(NonRootWorkMode.CASCADE) },
        )
      }
      Text(
        text = stringResource(
          if (workMode == NonRootWorkMode.DIRECT) R.string.non_root_mode_direct_desc
          else R.string.non_root_mode_cascade_desc,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun NonRootModeChoice(
  modifier: Modifier,
  selected: Boolean,
  enabled: Boolean,
  title: String,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(18.dp)
  Surface(
    modifier = modifier
      .clip(shape)
      .clickable(enabled = enabled, onClick = onClick),
    shape = shape,
    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    contentColor = (if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
      .copy(alpha = if (enabled) 1f else 0.55f),
    border = BorderStroke(
      1.dp,
      if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
      else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
    ),
  ) {
    Text(
      text = title,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
  }
}

@Composable
private fun NonRootDirectToolsCard(
  port: Int,
  byedpiPort: Int,
  config: NonRootDirectOperaConfig,
  onPortChange: (Int) -> Boolean,
  onByeDpiPortChange: (Int) -> Boolean,
  onConfigChange: (NonRootDirectOperaConfig) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    NonRootByeDpiCard(
      port = byedpiPort,
      config = config,
      onPortChange = onByeDpiPortChange,
      onConfigChange = onConfigChange,
    )
    NonRootOperaProxyCard(
      port = port,
      config = config,
      onPortChange = onPortChange,
      onConfigChange = onConfigChange,
    )
  }
}

@Composable
internal fun NonRootByeDpiCard(
  port: Int,
  config: NonRootDirectOperaConfig,
  onPortChange: (Int) -> Boolean,
  onConfigChange: (NonRootDirectOperaConfig) -> Unit,
) {
  var portText by remember(port) { mutableStateOf(port.toString()) }
  var portError by remember(port) { mutableStateOf(false) }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
          modifier = Modifier.size(46.dp),
          shape = RoundedCornerShape(15.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val iconRes = programIconRes("byedpi")
            if (iconRes != null) Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(26.dp))
            else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(23.dp))
          }
        }
        Text(
          text = stringResource(R.string.tab_byedpi),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
      }

      OutlinedTextField(
        value = portText,
        onValueChange = { raw ->
          if (raw.length <= 5 && raw.all(Char::isDigit)) {
            portText = raw
            val parsed = raw.toIntOrNull()
            portError = when {
              parsed == null -> raw.isNotEmpty()
              parsed !in NonRootPortRegistry.MIN_PORT..NonRootPortRegistry.MAX_PORT -> true
              else -> !onPortChange(parsed)
            }
          }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.common_port)) },
        prefix = { Text("${NonRootPortRegistry.LOOPBACK}:") },
        supportingText = {
          Text(if (portError) stringResource(R.string.non_root_port_error) else stringResource(R.string.non_root_loopback_port_hint))
        },
        isError = portError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )

      OutlinedTextField(
        value = config.byedpiStartArgs,
        onValueChange = { onConfigChange(config.copy(byedpiStartArgs = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.byedpi_start_args_title)) },
        supportingText = { Text(stringResource(R.string.byedpi_start_args_desc)) },
        minLines = 2,
        maxLines = 5,
      )
      OutlinedTextField(
        value = config.byedpiRestartArgs,
        onValueChange = { onConfigChange(config.copy(byedpiRestartArgs = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.byedpi_restart_args_title)) },
        supportingText = { Text(stringResource(R.string.byedpi_restart_args_desc)) },
        minLines = 2,
        maxLines = 5,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.non_root_byedpi_restart_after_opera),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = stringResource(R.string.non_root_byedpi_restart_after_opera_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = config.restartByedpiAfterOpera,
          onCheckedChange = { onConfigChange(config.copy(restartByedpiAfterOpera = it)) },
        )
      }
    }
  }
}

@Composable
internal fun NonRootOperaProxyCard(
  port: Int,
  config: NonRootDirectOperaConfig,
  onPortChange: (Int) -> Boolean,
  onConfigChange: (NonRootDirectOperaConfig) -> Unit,
  descriptionRes: Int = R.string.non_root_direct_opera_desc,
) {
  var portText by remember { mutableStateOf(port.toString()) }
  var portError by remember { mutableStateOf(false) }
  var advancedExpanded by remember { mutableStateOf(false) }
  LaunchedEffect(port) {
    if (!portError) portText = port.toString()
  }

  fun updateSni(index: Int, item: NonRootSniEntry) {
    val updated = config.sniEntries.toMutableList()
    if (index in updated.indices) {
      updated[index] = item
      onConfigChange(config.copy(sniEntries = updated))
    }
  }

  Surface(
    modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
          modifier = Modifier.size(48.dp),
          shape = RoundedCornerShape(15.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
          contentColor = MaterialTheme.colorScheme.primary,
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val iconRes = programIconRes("operaproxy")
            if (iconRes != null) Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(27.dp))
            else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(24.dp))
          }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            text = stringResource(R.string.opera_proxy_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      OutlinedTextField(
        value = portText,
        onValueChange = { raw ->
          if (raw.length <= 5 && raw.all(Char::isDigit)) {
            portText = raw
            val parsed = raw.toIntOrNull()
            portError = when {
              parsed == null -> raw.isNotEmpty()
              parsed !in NonRootPortRegistry.MIN_PORT..NonRootPortRegistry.MAX_PORT -> true
              else -> !onPortChange(parsed)
            }
          }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.common_port)) },
        prefix = { Text("${NonRootPortRegistry.LOOPBACK}:") },
        supportingText = {
          Text(if (portError) stringResource(R.string.non_root_port_error) else stringResource(R.string.non_root_loopback_port_hint))
        },
        isError = portError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )

      Text(
        text = stringResource(R.string.tab_servers),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("EU" to R.string.region_europe, "AS" to R.string.region_asia, "AM" to R.string.region_america).forEach { (code, label) ->
          NonRootSmallChoice(
            modifier = Modifier.weight(1f),
            selected = config.serverRegion == code,
            label = stringResource(label),
            onClick = { onConfigChange(config.copy(serverRegion = code)) },
          )
        }
      }

      Text(
        text = stringResource(R.string.tab_sni),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = stringResource(R.string.operaproxy_sni_section_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      config.sniEntries.forEachIndexed { index, entry ->
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = stringResource(R.string.operaproxy_sni_entry_title_fmt, index + 1),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
              )
              IconButton(
                onClick = {
                  val updated = config.sniEntries.toMutableList().also { it.removeAt(index) }
                  onConfigChange(config.copy(sniEntries = updated))
                },
              ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
              }
            }
            OutlinedTextField(
              value = entry.sni,
              onValueChange = { updateSni(index, entry.copy(sni = it)) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text("SNI") },
              placeholder = { Text(stringResource(R.string.operaproxy_sni_placeholder)) },
              singleLine = true,
            )
            OutlinedTextField(
              value = entry.overrideProxyAddress,
              onValueChange = { updateSni(index, entry.copy(overrideProxyAddress = it)) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.operaproxy_sni_server_address)) },
              placeholder = { Text(stringResource(R.string.operaproxy_sni_server_address_placeholder)) },
              supportingText = { Text(stringResource(R.string.operaproxy_sni_server_address_hint)) },
              singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.operaproxy_sni_use_byedpi), fontWeight = FontWeight.Medium)
                Text(
                  stringResource(R.string.operaproxy_sni_use_byedpi_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = entry.useByedpi,
                onCheckedChange = { updateSni(index, entry.copy(useByedpi = it)) },
              )
            }
          }
        }
      }
      TextButton(
        onClick = { onConfigChange(config.copy(sniEntries = config.sniEntries + NonRootSniEntry())) },
      ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.operaproxy_sni_create_new))
      }

      TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
        Text(stringResource(R.string.settings_advanced_title))
      }
      AnimatedVisibility(visible = advancedExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = config.apiProxy,
            onValueChange = { onConfigChange(config.copy(apiProxy = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("-api-proxy") },
            supportingText = { Text(stringResource(R.string.opera_args_api_proxy_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NonRootSmallChoice(
              modifier = Modifier.weight(1f),
              selected = config.serverSelection == "fastest",
              label = "fastest",
              onClick = { onConfigChange(config.copy(serverSelection = "fastest")) },
            )
            NonRootSmallChoice(
              modifier = Modifier.weight(1f),
              selected = config.serverSelection == "random",
              label = "random",
              onClick = { onConfigChange(config.copy(serverSelection = "random")) },
            )
          }
          OutlinedTextField(
            value = config.serverSelectionDlLimit,
            onValueChange = { onConfigChange(config.copy(serverSelectionDlLimit = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("-server-selection-dl-limit") },
            supportingText = { Text(stringResource(R.string.opera_args_dl_limit_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          )
          OutlinedTextField(
            value = config.serverSelectionTestUrl,
            onValueChange = { onConfigChange(config.copy(serverSelectionTestUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("-server-selection-test-url") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          )
          OutlinedTextField(
            value = config.initRetryInterval,
            onValueChange = { onConfigChange(config.copy(initRetryInterval = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("-init-retry-interval") },
            supportingText = { Text(stringResource(R.string.opera_args_init_retry_hint)) },
            singleLine = true,
          )
          OutlinedTextField(
            value = config.verbosity,
            onValueChange = { onConfigChange(config.copy(verbosity = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("-verbosity") },
            supportingText = { Text(stringResource(R.string.opera_args_verbosity_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          )
          OutlinedTextField(
            value = config.apiUserAgent,
            onValueChange = { onConfigChange(config.copy(apiUserAgent = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.opera_args_ua_title)) },
            supportingText = { Text(stringResource(R.string.opera_args_ua_desc)) },
            singleLine = true,
          )
          OutlinedTextField(
            value = config.bootstrapDns.joinToString("\n"),
            onValueChange = { raw ->
              onConfigChange(config.copy(bootstrapDns = raw.lines().map { it.trim() }))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.opera_args_bootstrap_dns_title)) },
            supportingText = { Text(stringResource(R.string.opera_args_bootstrap_dns_desc)) },
            minLines = 2,
            maxLines = 5,
          )
        }
      }
    }
  }
}

@Composable
internal fun NonRootSmallChoice(
  modifier: Modifier,
  selected: Boolean,
  label: String,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(14.dp)
  Surface(
    modifier = modifier.clip(shape).clickable(onClick = onClick),
    shape = shape,
    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(
      1.dp,
      if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
      else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
    ),
  ) {
    Text(
      text = label,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun NonRootCascadeIntroCard() {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Text(
        text = stringResource(R.string.non_root_mode_cascade),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.non_root_cascade_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun NonRootTopBarCard(
  modifier: Modifier = Modifier,
  title: String,
  onBack: (() -> Unit)? = null,
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
          .padding(start = if (onBack == null) 16.dp else 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (onBack != null) {
          IconButton(onClick = onBack, modifier = Modifier.size(46.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
          }
        }
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
