package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.android.zdtd.service.R
import com.android.zdtd.service.api.ApiModels
import java.util.Locale

@Composable
fun AppsListScreen(
  programs: List<ApiModels.Program>,
  daemonOnline: Boolean,
  onOpenProgram: (String) -> Unit,
  onOpenAnalysisTools: () -> Unit,
  listState: LazyListState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {

  val isCompactWidth = rememberIsCompactWidth()
  val isShortHeight = rememberIsShortHeight()
  val landscapeControl = rememberUseLandscapeControlLayout()
  val compactCards = isCompactWidth && !landscapeControl
  val cardPadding = if (isCompactWidth) 8.dp else 12.dp
  val sectionGap = if (isShortHeight) 6.dp else 8.dp
  var query by rememberSaveable { mutableStateOf("") }
  val q = query.trim()

  val all = programs
  val filtered = remember(all, q) {
    if (q.isBlank()) {
      all
    } else {
      val needle = q.lowercase(Locale.ROOT)
      all.filter {
        val name = (it.name ?: "").lowercase(Locale.ROOT)
        val id = it.id.lowercase(Locale.ROOT)
        name.contains(needle) || id.contains(needle)
      }
    }
  }

  val activeShown = remember(filtered) { filtered.count { isProgramVisuallyActive(it) } }
  val profileShown = remember(filtered) { filtered.count { isProfileProgramType(it.type) } }

  val core = remember(filtered) {
    filtered
      .filter { !isProfileProgramType(it.type) }
      .sortedWith(
        compareByDescending<ApiModels.Program> { isProgramVisuallyActive(it) }
          .thenBy { (it.name ?: it.id).lowercase(Locale.ROOT) },
      )
  }
  val prof = remember(filtered) {
    filtered
      .filter { isProfileProgramType(it.type) }
      .sortedWith(
        compareByDescending<ApiModels.Program> { isProgramVisuallyActive(it) }
          .thenByDescending { it.profiles.count { p -> p.enabled } }
          .thenBy { (it.name ?: it.id).lowercase(Locale.ROOT) },
      )
  }

  LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(
      top = topContentPadding,
      bottom = bottomContentPadding,
    ),
    verticalArrangement = Arrangement.spacedBy(sectionGap),
  ) {
    item {
      ProgramsHeaderCard(
        compact = compactCards,
        shortHeight = isShortHeight,
        total = all.size,
        shown = filtered.size,
        active = activeShown,
        profilePrograms = profileShown,
        query = query,
        onQueryChange = { query = it },
        onClearQuery = { query = "" },
        onOpenAnalysisTools = onOpenAnalysisTools,
      )
    }

    if (all.isEmpty()) {
      item {
        EmptyState(
          title = stringResource(R.string.apps_list_no_programs_title),
          hint = if (daemonOnline) {
            stringResource(R.string.apps_list_no_programs_daemon_online)
          } else {
            stringResource(R.string.apps_list_no_programs_daemon_offline)
          },
        )
      }
      return@LazyColumn
    }

    if (filtered.isEmpty()) {
      item {
        EmptyState(
          title = stringResource(R.string.apps_list_nothing_found_title),
          hint = stringResource(R.string.apps_list_nothing_found_hint),
        )
      }
      return@LazyColumn
    }

    if (core.isNotEmpty()) {
      item(key = "hdr_core") {
        SectionHeader(
          compact = compactCards,
          title = stringResource(R.string.apps_list_section_core),
          subtitle = stringResource(R.string.apps_list_items_count, core.size),
          accentColor = MaterialTheme.colorScheme.tertiary,
        )
      }
      if (landscapeControl) {
        items(core.chunked(2), key = { row -> row.joinToString("|") { it.id } }, contentType = { "program_card_row" }) { row ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            row.forEach { p ->
              ProgramCard(
                modifier = Modifier.weight(1f),
                compact = false,
                program = p,
                onClick = { onOpenProgram(p.id) },
                horizontalPadding = 0.dp,
              )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
          }
        }
      } else {
        items(core, key = { it.id }, contentType = { "program_card" }) { p ->
          ProgramCard(
            compact = isCompactWidth,
            program = p,
            onClick = { onOpenProgram(p.id) },
            horizontalPadding = cardPadding,
          )
        }
      }
    }

    if (prof.isNotEmpty()) {
      item(key = "hdr_profiles") {
        SectionHeader(
          compact = compactCards,
          title = stringResource(R.string.apps_list_section_profiles),
          subtitle = stringResource(R.string.apps_list_items_count, prof.size),
          accentColor = MaterialTheme.colorScheme.error,
        )
      }
      if (landscapeControl) {
        items(prof.chunked(2), key = { row -> row.joinToString("|") { it.id } }, contentType = { "program_card_row" }) { row ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            row.forEach { p ->
              ProgramCard(
                modifier = Modifier.weight(1f),
                compact = false,
                program = p,
                onClick = { onOpenProgram(p.id) },
                horizontalPadding = 0.dp,
              )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
          }
        }
      } else {
        items(prof, key = { it.id }, contentType = { "program_card" }) { p ->
          ProgramCard(
            compact = isCompactWidth,
            program = p,
            onClick = { onOpenProgram(p.id) },
            horizontalPadding = cardPadding,
          )
        }
      }
    }

    item { Spacer(Modifier.height(4.dp)) }
  }
}

@Composable
private fun ProgramsHeaderCard(
  compact: Boolean,
  shortHeight: Boolean,
  total: Int,
  shown: Int,
  active: Int,
  profilePrograms: Int,
  query: String,
  onQueryChange: (String) -> Unit,
  onClearQuery: () -> Unit,
  onOpenAnalysisTools: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp, vertical = if (shortHeight) 6.dp else 8.dp),
    verticalArrangement = Arrangement.spacedBy(if (shortHeight) 6.dp else 8.dp),
  ) {
    SummaryMetricsRow(
      compact = compact,
      total = total,
      shown = shown,
      active = active,
      profilePrograms = profilePrograms,
    )

    SearchGlassCard(
      query = query,
      onQueryChange = onQueryChange,
      onClearQuery = onClearQuery,
      onOpenAnalysisTools = onOpenAnalysisTools,
    )
  }
}

@Composable
private fun SearchGlassCard(
  query: String,
  onQueryChange: (String) -> Unit,
  onClearQuery: () -> Unit,
  onOpenAnalysisTools: () -> Unit,
) {
  val shape = RoundedCornerShape(16.dp)
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    singleLine = true,
    leadingIcon = {
      Icon(
        Icons.Outlined.Search,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
      )
    },
    trailingIcon = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (query.isNotBlank()) {
          IconButton(onClick = onClearQuery) {
            Icon(
              imageVector = Icons.Outlined.Close,
              contentDescription = stringResource(R.string.apps_list_clear),
              modifier = Modifier.size(18.dp),
            )
          }
        }
        Box(
          modifier = Modifier
            .height(24.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        )
        IconButton(onClick = onOpenAnalysisTools) {
          Icon(
            Icons.Outlined.Tune,
            contentDescription = stringResource(R.string.analysis_tools_title),
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
          )
        }
      }
    },
    placeholder = {
      Text(
        stringResource(R.string.apps_list_search_programs),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
      )
    },
    colors = OutlinedTextFieldDefaults.colors(
      focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
      unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.50f),
      focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
      unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f),
      cursorColor = MaterialTheme.colorScheme.primary,
    ),
    shape = shape,
    modifier = Modifier
      .fillMaxWidth()
      .height(50.dp),
  )
}

@Composable
private fun SummaryMetricsRow(
  compact: Boolean,
  total: Int,
  shown: Int,
  active: Int,
  profilePrograms: Int,
) {
  val coreCount = (shown - profilePrograms).coerceAtLeast(0)
  val totalTitle = if (shown == total) {
    stringResource(R.string.apps_list_total_fmt, total)
  } else {
    stringResource(R.string.apps_list_showing_fmt, shown, total)
  }
  if (compact) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
      SummaryMetricCard(
        title = totalTitle,
        value = shown.toString(),
        hint = stringResource(R.string.apps_list_chip_enabled_count, active),
        icon = Icons.Outlined.Extension,
        accentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1.12f),
      )
      SummaryMetricCard(
        title = stringResource(R.string.apps_list_section_core),
        value = coreCount.toString(),
        hint = stringResource(R.string.apps_list_items_count, coreCount),
        icon = Icons.Outlined.Tune,
        accentColor = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.weight(1f),
      )
      SummaryMetricCard(
        title = stringResource(R.string.apps_list_section_profiles),
        value = profilePrograms.toString(),
        hint = stringResource(R.string.apps_list_items_count, profilePrograms),
        icon = Icons.Outlined.Public,
        accentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
    }
  } else {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
      SummaryMetricCard(
        title = totalTitle,
        value = shown.toString(),
        hint = stringResource(R.string.apps_list_chip_enabled_count, active),
        icon = Icons.Outlined.Extension,
        accentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1.12f),
      )
      SummaryMetricCard(
        title = stringResource(R.string.apps_list_section_core),
        value = coreCount.toString(),
        hint = stringResource(R.string.apps_list_items_count, coreCount),
        icon = Icons.Outlined.Tune,
        accentColor = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.weight(1f),
      )
      SummaryMetricCard(
        title = stringResource(R.string.apps_list_section_profiles),
        value = profilePrograms.toString(),
        hint = stringResource(R.string.apps_list_items_count, profilePrograms),
        icon = Icons.Outlined.Public,
        accentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun SummaryMetricCard(
  title: String,
  value: String,
  hint: String,
  icon: ImageVector,
  accentColor: Color,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(16.dp)
  Surface(
    modifier = modifier,
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    shape = shape,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
  ) {
    Box(Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(8.dp)
          .size(36.dp)
          .background(accentColor.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(18.dp),
        )
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = value,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
        Text(
          text = hint,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(compact: Boolean, title: String, subtitle: String, accentColor: Color) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp, vertical = if (compact) 0.dp else 1.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier
        .width(3.dp)
        .height(22.dp)
        .background(accentColor, RoundedCornerShape(100.dp)),
    )
    Text(
      title,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Surface(
      color = accentColor.copy(alpha = 0.14f),
      contentColor = accentColor,
      border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
      shape = RoundedCornerShape(100.dp),
    ) {
      Text(
        subtitle,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun EmptyState(title: String, hint: String) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    shape = RoundedCornerShape(20.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(hint, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
  }
}

@Composable
private fun ProgramCard(
  modifier: Modifier = Modifier,
  compact: Boolean,
  program: ApiModels.Program,
  onClick: () -> Unit,
  horizontalPadding: androidx.compose.ui.unit.Dp,
) {
  val title = program.name ?: program.id
  val subtitle = programDescription(program.id)

  val isProfiles = isProfileProgramType(program.type)
  val profilesTotal = program.profiles.size
  val profilesEnabled = program.profiles.count { it.enabled }
  val isActive = isProgramVisuallyActive(program)

  val primaryChip = if (isProfiles) {
    stringResource(R.string.apps_list_chip_profiles, profilesTotal)
  } else if (program.enabled) {
    stringResource(R.string.apps_list_chip_enabled)
  } else {
    stringResource(R.string.apps_list_chip_disabled)
  }

  val secondaryChip = if (isProfiles) {
    stringResource(R.string.apps_list_chip_enabled_count, profilesEnabled)
  } else null

  val activeAccent = MaterialTheme.colorScheme.tertiary
  val profileAccent = MaterialTheme.colorScheme.error
  val idleAccent = MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
  val accentColor = when {
    isProfiles && isActive -> profileAccent
    isProfiles -> profileAccent.copy(alpha = 0.80f)
    isActive -> activeAccent
    else -> idleAccent
  }
  val containerColor = when {
    isActive -> MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    isProfiles -> MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)
    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
  }
  val shape = RoundedCornerShape(16.dp)
  val gradientStart = if (isActive) accentColor.copy(alpha = 0.18f) else accentColor.copy(alpha = 0.04f)
  val gradientEnd = containerColor.copy(alpha = 0.72f)

  Card(
    onClick = onClick,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = horizontalPadding),
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = containerColor),
    border = BorderStroke(1.dp, accentColor.copy(alpha = if (isActive) 0.62f else 0.22f)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(Brush.horizontalGradient(listOf(gradientStart, gradientEnd))),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 9.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
      ) {
        Surface(
          modifier = Modifier.size(if (compact) 50.dp else 56.dp),
          color = accentColor.copy(alpha = if (isActive) 0.16f else 0.10f),
          contentColor = accentColor,
          shape = CircleShape,
          tonalElevation = 0.dp,
          shadowElevation = 0.dp,
          border = BorderStroke(1.dp, accentColor.copy(alpha = if (isActive) 0.42f else 0.24f)),
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val iconSize = if (compact) 28.dp else 31.dp
            val iconRes = programIconRes(program.id)
            if (iconRes != null) {
              Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
              )
            } else {
              Icon(
                imageVector = programIcon(program.id),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 23.dp else 25.dp),
              )
            }
          }
        }

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = if (compact) 2 else 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (compact) 2 else 1,
            overflow = TextOverflow.Ellipsis,
          )

          Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ProgramBadgeRow(
              label = primaryChip,
              containerColor = if (isProfiles) {
                profileAccent.copy(alpha = 0.15f)
              } else if (isActive) {
                Color(0xFF22C55E).copy(alpha = 0.16f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)
              },
              contentColor = if (isProfiles) profileAccent else if (isActive) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
            if (!secondaryChip.isNullOrBlank()) {
              ProgramBadgeRow(
                label = secondaryChip,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                contentColor = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }

        Icon(
          imageVector = Icons.Outlined.ChevronRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
          modifier = Modifier.size(22.dp),
        )
      }
    }
  }
}

@Composable
private fun ProgramBadgeRow(label: String, containerColor: Color, contentColor: Color) {
  Surface(
    color = containerColor,
    contentColor = contentColor,
    shape = RoundedCornerShape(100.dp),
    border = BorderStroke(1.dp, contentColor.copy(alpha = 0.28f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Box(
        modifier = Modifier
          .size(6.dp)
          .background(contentColor, CircleShape),
      )
      Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
      )
    }
  }
}

private fun isProgramVisuallyActive(program: ApiModels.Program): Boolean {
  return if (isProfileProgramType(program.type)) {
    program.profiles.any { it.enabled }
  } else {
    program.enabled
  }
}

@Composable
private fun programDescription(id: String): String {
  return toolDescription(id)
}

internal fun programIconRes(id: String): Int? {
  return when (id) {
    "operaproxy" -> R.drawable.ic_tool_operaproxy
    "nfqws" -> R.drawable.ic_tool_zapret
    "nfqws2" -> R.drawable.ic_tool_zapret2
    "byedpi" -> R.drawable.ic_tool_byedpi
    "dpitunnel" -> R.drawable.ic_tool_dpitunnel
    "wireproxy" -> R.drawable.ic_tool_wireproxy
    "tor" -> R.drawable.ic_tool_tor
    "myproxy" -> R.drawable.ic_tool_myproxy
    "myprogram" -> R.drawable.ic_tool_myprogram
    "openvpn" -> R.drawable.ic_tool_openvpn
    "amneziawg" -> R.drawable.ic_tool_amneziawg
    "tun2socks" -> R.drawable.ic_tool_tun2socks
    "myvpn" -> R.drawable.ic_tool_myvpn
    "mihomo" -> R.drawable.ic_tool_mihomo
    "mieru" -> R.drawable.ic_tool_mieru
    "sing-box" -> R.drawable.ic_tool_sing_box
    else -> null
  }
}

internal fun programIcon(id: String): ImageVector {
  return when (id) {
    "dnscrypt" -> Icons.Outlined.Dns
    "operaproxy" -> Icons.Outlined.SwapHoriz
    "nfqws" -> Icons.Outlined.Tune
    "nfqws2" -> Icons.Outlined.Tune
    "byedpi" -> Icons.Outlined.Public
    "dpitunnel" -> Icons.Outlined.AltRoute
    "wireproxy" -> Icons.Outlined.AltRoute
    "tor" -> Icons.Outlined.Public
    "myproxy" -> Icons.Outlined.SwapHoriz
    "myprogram" -> Icons.Outlined.Extension
    "openvpn" -> Icons.Outlined.AltRoute
    "amneziawg" -> Icons.Outlined.AltRoute
    "tun2socks" -> Icons.Outlined.AltRoute
    "myvpn" -> Icons.Outlined.AltRoute
    "mihomo" -> Icons.Outlined.AltRoute
    "mieru" -> Icons.Outlined.Extension
    "sing-box" -> Icons.Outlined.Extension
    else -> Icons.Outlined.Extension
  }
}
