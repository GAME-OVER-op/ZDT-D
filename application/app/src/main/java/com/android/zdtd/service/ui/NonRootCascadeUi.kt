package com.android.zdtd.service.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.animateItem
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import com.android.zdtd.service.NonRootCascadeRouteItemType
import com.android.zdtd.service.NonRootCascadeState
import com.android.zdtd.service.NonRootT2sConfig
import com.android.zdtd.service.NonRootPortRegistry
import com.android.zdtd.service.R
import java.util.UUID
import kotlin.math.abs

@Composable
internal fun NonRootCascadeToolsContent(
  state: NonRootCascadeState,
  onCreateProfile: (String) -> Unit,
  onUpdateProfile: (NonRootCascadeProfile) -> Unit,
  onOpenProfile: (String) -> Unit,
  onOpenT2sSettings: () -> Unit,
) {
  var showCreateDialog by remember { mutableStateOf(false) }
  val byId = remember(state.profiles) { state.profiles.associateBy { it.id } }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (state.profiles.isEmpty()) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
      ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
    } else {
      state.route.forEach { routeItem ->
        when (routeItem.type) {
          NonRootCascadeRouteItemType.PROFILE -> byId[routeItem.profileId]?.let { profile ->
            NonRootCascadeProfileCard(
              profile = profile,
              onEnabledChange = { onUpdateProfile(profile.copy(enabled = it)) },
              onOpen = { onOpenProfile(profile.id) },
            )
          }
          NonRootCascadeRouteItemType.GROUP -> NonRootRouteDividerRow(
            title = stringResource(R.string.non_root_t2s_group_short),
          )
          NonRootCascadeRouteItemType.DIRECT_START -> NonRootRouteDividerRow(
            title = stringResource(R.string.non_root_t2s_direct_short),
          )
          NonRootCascadeRouteItemType.DIRECT_BLOCK -> NonRootRouteDividerRow(
            title = stringResource(R.string.non_root_t2s_block_direct_short),
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(
        onClick = { showCreateDialog = true },
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.non_root_create_profile))
      }
      Button(
        onClick = onOpenT2sSettings,
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.non_root_t2s_settings))
      }
    }
  }

  if (showCreateDialog) {
    NonRootCreateProfileDialog(
      onDismiss = { showCreateDialog = false },
      onCreate = { name ->
        onCreateProfile(name)
        showCreateDialog = false
      },
    )
  }
}

@Composable
private fun NonRootCreateProfileDialog(
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.non_root_create_profile)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = stringResource(R.string.non_root_profile_tool_label),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            val icon = programIconRes(NonRootCascadeProfile.TOOL_OPERA_PROXY)
            if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.opera_proxy_title), fontWeight = FontWeight.SemiBold)
          }
        }
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.non_root_profile_name)) },
          singleLine = true,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onCreate(name) }) { Text(stringResource(R.string.action_create)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
    },
  )
}

@Composable
private fun NonRootCascadeProfileCard(
  profile: NonRootCascadeProfile,
  onEnabledChange: (Boolean) -> Unit,
  onOpen: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(
      1.dp,
      if (profile.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
      else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
    ),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        modifier = Modifier.size(48.dp),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.primary,
      ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val icon = programIconRes(profile.toolId)
          if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(27.dp))
          else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(24.dp))
        }
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          text = stringResource(R.string.non_root_profile_title_fmt, stringResource(R.string.opera_proxy_title), profile.name),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${NonRootPortRegistry.LOOPBACK}:${profile.port}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = profile.enabled,
        onCheckedChange = onEnabledChange,
      )
    }
  }
}

@Composable
private fun NonRootRouteDividerRow(title: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(Modifier.weight(1f).height(1.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)) {}
      }
      Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
      )
      Box(Modifier.weight(1f).height(1.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().height(1.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)) {}
      }
    }
  }
}

@Composable
internal fun NonRootCascadeProfileEditorScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  profile: NonRootCascadeProfile,
  onUpdateProfile: (NonRootCascadeProfile) -> Unit,
  onPortChange: (String, Int) -> Boolean,
  onByeDpiPortChange: (String, Int) -> Boolean,
  onDelete: (String) -> Unit,
) {
  val screenPadding = rememberAdaptiveScreenPadding()
  var nameText by remember(profile.id) { mutableStateOf(profile.name) }
  var deleteConfirm by remember { mutableStateOf(false) }

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
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = stringResource(R.string.non_root_profile_settings),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          OutlinedTextField(
            value = nameText,
            onValueChange = { nameText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.non_root_profile_name)) },
            singleLine = true,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.non_root_profile_enabled), fontWeight = FontWeight.SemiBold)
              Text(
                stringResource(R.string.non_root_profile_enabled_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = profile.enabled,
              onCheckedChange = { onUpdateProfile(profile.copy(enabled = it)) },
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = { onUpdateProfile(profile.copy(name = nameText)) },
              enabled = nameText.trim().isNotEmpty(),
              modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_save)) }
            OutlinedButton(
              onClick = { deleteConfirm = true },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.size(6.dp))
              Text(stringResource(R.string.action_delete))
            }
          }
        }
      }
    }
    item {
      NonRootByeDpiCard(
        port = profile.byedpiPort,
        config = profile.operaConfig,
        onPortChange = { onByeDpiPortChange(profile.id, it) },
        onConfigChange = { onUpdateProfile(profile.copy(operaConfig = it)) },
      )
    }
    item {
      NonRootOperaProxyCard(
        port = profile.port,
        config = profile.operaConfig,
        onPortChange = { onPortChange(profile.id, it) },
        onConfigChange = { onUpdateProfile(profile.copy(operaConfig = it)) },
        descriptionRes = R.string.non_root_cascade_opera_desc,
      )
    }
  }

  if (deleteConfirm) {
    AlertDialog(
      onDismissRequest = { deleteConfirm = false },
      title = { Text(stringResource(R.string.non_root_delete_profile_title)) },
      text = { Text(stringResource(R.string.non_root_delete_profile_body, profile.name)) },
      confirmButton = {
        TextButton(onClick = { onDelete(profile.id); deleteConfirm = false }) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
      },
    )
  }
}

@Composable
internal fun NonRootT2sSettingsScreen(
  topContentPadding: Dp,
  bottomContentPadding: Dp,
  state: NonRootCascadeState,
  onBackendModeChange: (NonRootCascadeBackendMode) -> Unit,
  onT2sConfigChange: (NonRootT2sConfig) -> Unit,
  onRouteChange: (List<NonRootCascadeRouteItem>) -> Unit,
  onUpdateProfile: (NonRootCascadeProfile) -> Unit,
) {
  val screenPadding = rememberAdaptiveScreenPadding()
  val byId = remember(state.profiles) { state.profiles.associateBy { it.id } }
  var helpType by remember { mutableStateOf<NonRootCascadeRouteItemType?>(null) }
  var deleteZoneActive by remember { mutableStateOf(false) }

  Box(modifier = Modifier.fillMaxSize()) {
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
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = stringResource(R.string.non_root_t2s_backend_mode),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NonRootSmallChoice(
              modifier = Modifier.weight(1f),
              selected = state.backendMode == NonRootCascadeBackendMode.BALANCE,
              label = stringResource(R.string.myproxy_backend_mode_balance),
              onClick = {
                if (state.route.any { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }) {
                  onRouteChange(state.route.filterNot { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK })
                }
                onBackendModeChange(NonRootCascadeBackendMode.BALANCE)
              },
            )
            NonRootSmallChoice(
              modifier = Modifier.weight(1f),
              selected = state.backendMode == NonRootCascadeBackendMode.PRIORITY,
              label = stringResource(R.string.myproxy_backend_mode_priority),
              onClick = { onBackendModeChange(NonRootCascadeBackendMode.PRIORITY) },
            )
          }
          Text(
            text = stringResource(
              if (state.backendMode == NonRootCascadeBackendMode.PRIORITY) R.string.myproxy_backend_mode_priority_desc
              else R.string.myproxy_backend_mode_balance_desc,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    item {
      NonRootT2sRuntimeSettingsCard(
        mode = state.backendMode,
        config = state.t2s,
        onChange = onT2sConfigChange,
      )
    }

    item {
      Text(
        text = stringResource(R.string.non_root_t2s_route_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.non_root_t2s_route_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (state.route.isEmpty()) {
      item {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
          Text(
            text = stringResource(R.string.non_root_t2s_route_empty),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    itemsIndexed(
      items = state.route,
      key = { index, item -> routeItemKey(item, index) },
    ) { index, item ->
      NonRootT2sRouteRow(
        modifier = Modifier.animateItem(),
        index = index,
        item = item,
        route = state.route,
        profile = byId[item.profileId],
        onMove = { from, to ->
          val moved = moveRouteItem(state.route, from, to)
          if (moved == null) {
            false
          } else {
            onRouteChange(moved)
            true
          }
        },
        onRemoveSpecial = { removeIndex ->
          val current = state.route.getOrNull(removeIndex)
          if (current != null && current.type != NonRootCascadeRouteItemType.PROFILE) {
            onRouteChange(state.route.toMutableList().also { it.removeAt(removeIndex) })
          }
        },
        onDeleteZoneActiveChange = { deleteZoneActive = it },
        onEnabledChange = { enabled ->
          byId[item.profileId]?.let { onUpdateProfile(it.copy(enabled = enabled)) }
        },
      )
    }

    item {
      Text(
        text = stringResource(R.string.non_root_t2s_palette_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.non_root_t2s_palette_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NonRootPaletteItem(
          title = stringResource(R.string.non_root_t2s_group),
          type = NonRootCascadeRouteItemType.GROUP,
          enabled = state.route.count { it.type == NonRootCascadeRouteItemType.PROFILE } >= 2,
          onHelp = { helpType = NonRootCascadeRouteItemType.GROUP },
          onDragIntoRoute = {
            defaultSpecialInsertion(state.route, NonRootCascadeRouteItemType.GROUP)?.let(onRouteChange)
          },
        )
        NonRootPaletteItem(
          title = stringResource(R.string.non_root_t2s_direct),
          type = NonRootCascadeRouteItemType.DIRECT_START,
          enabled = state.backendMode == NonRootCascadeBackendMode.PRIORITY &&
            state.route.none { it.type == NonRootCascadeRouteItemType.DIRECT_START },
          onHelp = { helpType = NonRootCascadeRouteItemType.DIRECT_START },
          onDragIntoRoute = {
            defaultSpecialInsertion(state.route, NonRootCascadeRouteItemType.DIRECT_START)?.let(onRouteChange)
          },
        )
        NonRootPaletteItem(
          title = stringResource(R.string.non_root_t2s_block_direct),
          type = NonRootCascadeRouteItemType.DIRECT_BLOCK,
          enabled = state.backendMode == NonRootCascadeBackendMode.PRIORITY &&
            state.route.any { it.type == NonRootCascadeRouteItemType.PROFILE } &&
            state.route.none { it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK },
          onHelp = { helpType = NonRootCascadeRouteItemType.DIRECT_BLOCK },
          onDragIntoRoute = {
            defaultSpecialInsertion(state.route, NonRootCascadeRouteItemType.DIRECT_BLOCK)?.let(onRouteChange)
          },
        )
      }
    }
    }

    AnimatedVisibility(
      visible = deleteZoneActive,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(start = screenPadding, end = screenPadding, bottom = bottomContentPadding + 8.dp),
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 6.dp,
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Filled.Delete, contentDescription = null)
          Spacer(Modifier.size(8.dp))
          Text(stringResource(R.string.action_delete), fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }

  helpType?.let { type ->
    AlertDialog(
      onDismissRequest = { helpType = null },
      title = { Text(specialTitle(type)) },
      text = { Text(specialHelp(type)) },
      confirmButton = {
        TextButton(onClick = { helpType = null }) { Text(stringResource(R.string.action_ok)) }
      },
    )
  }
}

@Composable
private fun NonRootT2sRouteRow(
  modifier: Modifier = Modifier,
  index: Int,
  item: NonRootCascadeRouteItem,
  route: List<NonRootCascadeRouteItem>,
  profile: NonRootCascadeProfile?,
  onMove: (Int, Int) -> Boolean,
  onRemoveSpecial: (Int) -> Unit,
  onDeleteZoneActiveChange: (Boolean) -> Unit,
  onEnabledChange: (Boolean) -> Unit,
) {
  val stableKey = routeItemKey(item, index)
  val latestOnMove by rememberUpdatedState(onMove)
  val latestOnRemoveSpecial by rememberUpdatedState(onRemoveSpecial)
  val latestOnDeleteZoneActiveChange by rememberUpdatedState(onDeleteZoneActiveChange)
  val latestRouteSize by rememberUpdatedState(route.size)
  var dragTotal by remember(stableKey) { mutableFloatStateOf(0f) }
  var downwardTotal by remember(stableKey) { mutableFloatStateOf(0f) }
  var removeOnEnd by remember(stableKey) { mutableStateOf(false) }
  var gestureIndex by remember(stableKey) { mutableIntStateOf(index) }

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .animateContentSize(animationSpec = tween(180)),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.DragHandle,
        contentDescription = stringResource(R.string.non_root_t2s_drag_handle),
        modifier = Modifier
          .size(32.dp)
          .pointerInput(stableKey, route.size) {
            detectDragGesturesAfterLongPress(
              onDragStart = {
                gestureIndex = index
                dragTotal = 0f
                downwardTotal = 0f
                removeOnEnd = false
              },
              onDragCancel = {
                latestOnDeleteZoneActiveChange(false)
                dragTotal = 0f
                downwardTotal = 0f
                removeOnEnd = false
              },
              onDragEnd = {
                if (removeOnEnd && item.type != NonRootCascadeRouteItemType.PROFILE) {
                  latestOnRemoveSpecial(gestureIndex)
                }
                latestOnDeleteZoneActiveChange(false)
                dragTotal = 0f
                downwardTotal = 0f
                removeOnEnd = false
              },
              onDrag = { change, dragAmount ->
                change.consume()
                dragTotal += dragAmount.y
                downwardTotal = (downwardTotal + dragAmount.y).coerceAtLeast(0f)
                if (item.type != NonRootCascadeRouteItemType.PROFILE && downwardTotal > 150.dp.toPx()) {
                  if (!removeOnEnd) latestOnDeleteZoneActiveChange(true)
                  removeOnEnd = true
                  return@detectDragGesturesAfterLongPress
                }
                if (!removeOnEnd && abs(dragTotal) >= 48.dp.toPx()) {
                  val target = if (dragTotal > 0f) gestureIndex + 1 else gestureIndex - 1
                  if (target in 0 until latestRouteSize && latestOnMove(gestureIndex, target)) {
                    gestureIndex = target
                  }
                  dragTotal = 0f
                }
              },
            )
          },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      when (item.type) {
        NonRootCascadeRouteItemType.PROFILE -> {
          val value = profile
          if (value != null) {
            val icon = programIconRes(value.toolId)
            if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            else Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(25.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text = stringResource(R.string.non_root_profile_title_fmt, stringResource(R.string.opera_proxy_title), value.name),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = "${NonRootPortRegistry.LOOPBACK}:${value.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(checked = value.enabled, onCheckedChange = onEnabledChange)
          }
        }
        else -> {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(specialTitle(item.type), fontWeight = FontWeight.SemiBold)
            Text(
              if (removeOnEnd) stringResource(R.string.action_delete) else specialPositionHint(item.type),
              style = MaterialTheme.typography.bodySmall,
              color = if (removeOnEnd) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NonRootPaletteItem(
  title: String,
  type: NonRootCascadeRouteItemType,
  enabled: Boolean,
  onHelp: () -> Unit,
  onDragIntoRoute: () -> Unit,
) {
  var draggedUp by remember(type) { mutableStateOf(false) }
  val contentAlpha = if (enabled) 1f else 0.45f
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.DragHandle,
        contentDescription = stringResource(R.string.non_root_t2s_drag_handle),
        modifier = Modifier
          .size(32.dp)
          .pointerInput(type, enabled) {
            if (!enabled) return@pointerInput
            var total = 0f
            detectDragGesturesAfterLongPress(
              onDragStart = { total = 0f; draggedUp = false },
              onDragCancel = { total = 0f; draggedUp = false },
              onDragEnd = {
                if (draggedUp) onDragIntoRoute()
                total = 0f
                draggedUp = false
              },
              onDrag = { change, amount ->
                change.consume()
                total += amount.y
                if (total < -70.dp.toPx()) draggedUp = true
              },
            )
          },
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
      )
      Text(
        text = title,
        modifier = Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
        fontWeight = FontWeight.SemiBold,
      )
      IconButton(onClick = onHelp) {
        Icon(Icons.Filled.HelpOutline, contentDescription = stringResource(R.string.non_root_t2s_help))
      }
    }
  }
}

@Composable
private fun NonRootT2sRuntimeSettingsCard(
  mode: NonRootCascadeBackendMode,
  config: NonRootT2sConfig,
  onChange: (NonRootT2sConfig) -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(180)),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = stringResource(R.string.non_root_t2s_runtime_settings),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )

      AnimatedVisibility(visible = mode == NonRootCascadeBackendMode.PRIORITY) {
        NonRootT2sSwitchRow(
          title = stringResource(R.string.myproxy_priority_speed_aware_title),
          description = stringResource(R.string.myproxy_priority_speed_aware_desc),
          checked = config.prioritySpeedAware,
          onCheckedChange = { onChange(config.copy(prioritySpeedAware = it)) },
        )
      }

      NonRootT2sNumberField(
        value = config.maxConnections.toString(),
        label = stringResource(R.string.d2s_max_connections),
        onValue = { it.toIntOrNull()?.let { value -> onChange(config.copy(maxConnections = value)) } },
      )
      NonRootT2sNumberField(
        value = config.idleTimeoutSeconds.toString(),
        label = stringResource(R.string.non_root_t2s_idle_timeout),
        onValue = { it.toIntOrNull()?.let { value -> onChange(config.copy(idleTimeoutSeconds = value)) } },
      )
      NonRootT2sNumberField(
        value = config.connectTimeoutSeconds.toString(),
        label = stringResource(R.string.d2s_connect_timeout),
        onValue = { it.toIntOrNull()?.let { value -> onChange(config.copy(connectTimeoutSeconds = value)) } },
      )
      NonRootT2sNumberField(
        value = config.bufferSize.toString(),
        label = stringResource(R.string.non_root_t2s_buffer_size),
        onValue = { it.toIntOrNull()?.let { value -> onChange(config.copy(bufferSize = value)) } },
      )
      NonRootT2sNumberField(
        value = config.downloadLimitMbit,
        label = stringResource(R.string.non_root_t2s_download_limit),
        decimal = true,
        onValue = { raw -> if (raw.toDoubleOrNull() != null) onChange(config.copy(downloadLimitMbit = raw)) },
      )
      NonRootT2sSwitchRow(
        title = stringResource(R.string.non_root_t2s_peer_coordination),
        description = stringResource(R.string.non_root_t2s_peer_coordination_desc),
        checked = config.peerCoordination,
        onCheckedChange = { onChange(config.copy(peerCoordination = it)) },
      )
      NonRootT2sSwitchRow(
        title = stringResource(R.string.non_root_t2s_serialize_connects),
        description = stringResource(R.string.non_root_t2s_serialize_connects_desc),
        checked = config.serializeBackendConnects,
        onCheckedChange = { onChange(config.copy(serializeBackendConnects = it)) },
      )
      NonRootT2sNumberField(
        value = config.connectStaggerMs.toString(),
        label = stringResource(R.string.non_root_t2s_connect_stagger),
        onValue = { it.toIntOrNull()?.let { value -> onChange(config.copy(connectStaggerMs = value)) } },
      )
    }
  }
}

@Composable
private fun NonRootT2sSwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun NonRootT2sNumberField(
  value: String,
  label: String,
  decimal: Boolean = false,
  onValue: (String) -> Unit,
) {
  var text by remember(value) { mutableStateOf(value) }
  OutlinedTextField(
    value = text,
    onValueChange = { raw ->
      val accepted = if (decimal) raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1 else raw.all(Char::isDigit)
      if (accepted) {
        text = raw
        if (raw.isNotBlank()) onValue(raw)
      }
    },
    modifier = Modifier.fillMaxWidth(),
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
  )
}

private fun routeItemKey(item: NonRootCascadeRouteItem, index: Int): String = when (item.type) {
  NonRootCascadeRouteItemType.PROFILE -> "profile:${item.profileId}"
  NonRootCascadeRouteItemType.GROUP -> "group:${item.markerId.ifBlank { index.toString() }}"
  NonRootCascadeRouteItemType.DIRECT_START -> "direct:start:${item.markerId.ifBlank { "single" }}"
  NonRootCascadeRouteItemType.DIRECT_BLOCK -> "direct:block:${item.markerId.ifBlank { "single" }}"
}

private fun moveRouteItem(
  route: List<NonRootCascadeRouteItem>,
  from: Int,
  to: Int,
): List<NonRootCascadeRouteItem>? {
  if (from !in route.indices || to !in route.indices || from == to) return null
  val updated = route.toMutableList()
  val item = updated.removeAt(from)
  updated.add(to, item)
  return updated.takeIf(::isValidRouteLayout)
}

private fun isValidRouteLayout(route: List<NonRootCascadeRouteItem>): Boolean {
  if (route.count { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK } > 1) return false
  route.forEachIndexed { index, item ->
    when (item.type) {
      NonRootCascadeRouteItemType.DIRECT_START -> if (index != 0) return false
      NonRootCascadeRouteItemType.DIRECT_BLOCK -> if (index != route.lastIndex) return false
      NonRootCascadeRouteItemType.GROUP -> {
        if (index == 0 || index == route.lastIndex) return false
        if (route[index - 1].type != NonRootCascadeRouteItemType.PROFILE) return false
        if (route[index + 1].type != NonRootCascadeRouteItemType.PROFILE) return false
      }
      NonRootCascadeRouteItemType.PROFILE -> Unit
    }
  }
  return true
}

private fun defaultSpecialInsertion(
  route: List<NonRootCascadeRouteItem>,
  type: NonRootCascadeRouteItemType,
): List<NonRootCascadeRouteItem>? {
  val updated = route.toMutableList()
  when (type) {
    NonRootCascadeRouteItemType.DIRECT_START -> {
      updated.removeAll { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
      updated.add(0, newSpecialRouteItem(type))
    }
    NonRootCascadeRouteItemType.DIRECT_BLOCK -> {
      updated.removeAll { it.type == NonRootCascadeRouteItemType.DIRECT_START || it.type == NonRootCascadeRouteItemType.DIRECT_BLOCK }
      updated.add(newSpecialRouteItem(type))
    }
    NonRootCascadeRouteItemType.GROUP -> {
      val insertion = route.indices.firstOrNull { index ->
        index < route.lastIndex &&
          route[index].type == NonRootCascadeRouteItemType.PROFILE &&
          route[index + 1].type == NonRootCascadeRouteItemType.PROFILE
      } ?: return null
      updated.add(insertion + 1, newSpecialRouteItem(type))
    }
    NonRootCascadeRouteItemType.PROFILE -> return null
  }
  return updated.takeIf(::isValidRouteLayout)
}


private fun newSpecialRouteItem(type: NonRootCascadeRouteItemType): NonRootCascadeRouteItem =
  NonRootCascadeRouteItem(type = type, markerId = UUID.randomUUID().toString())

@Composable
private fun specialTitle(type: NonRootCascadeRouteItemType): String = when (type) {
  NonRootCascadeRouteItemType.GROUP -> stringResource(R.string.non_root_t2s_group)
  NonRootCascadeRouteItemType.DIRECT_START -> stringResource(R.string.non_root_t2s_direct)
  NonRootCascadeRouteItemType.DIRECT_BLOCK -> stringResource(R.string.non_root_t2s_block_direct)
  NonRootCascadeRouteItemType.PROFILE -> stringResource(R.string.non_root_profile_tool_label)
}

@Composable
private fun specialHelp(type: NonRootCascadeRouteItemType): String = when (type) {
  NonRootCascadeRouteItemType.GROUP -> stringResource(R.string.non_root_t2s_group_help)
  NonRootCascadeRouteItemType.DIRECT_START -> stringResource(R.string.non_root_t2s_direct_help)
  NonRootCascadeRouteItemType.DIRECT_BLOCK -> stringResource(R.string.non_root_t2s_block_direct_help)
  NonRootCascadeRouteItemType.PROFILE -> ""
}

@Composable
private fun specialPositionHint(type: NonRootCascadeRouteItemType): String = when (type) {
  NonRootCascadeRouteItemType.GROUP -> stringResource(R.string.non_root_t2s_group_position)
  NonRootCascadeRouteItemType.DIRECT_START -> stringResource(R.string.non_root_t2s_direct_position)
  NonRootCascadeRouteItemType.DIRECT_BLOCK -> stringResource(R.string.non_root_t2s_block_direct_position)
  NonRootCascadeRouteItemType.PROFILE -> ""
}
