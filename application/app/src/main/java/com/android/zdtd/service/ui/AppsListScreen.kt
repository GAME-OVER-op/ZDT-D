
package com.android.zdtd.service.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.api.ApiModels
import java.util.Locale

@Composable
fun AppsListScreen(
  programs: List<ApiModels.Program>,
  daemonOnline: Boolean,
  onOpenProgram: (String) -> Unit,
) {

  val isCompactWidth = rememberIsCompactWidth()
  val isShortHeight = rememberIsShortHeight()
  val cardPadding = if (isCompactWidth) 14.dp else 16.dp
  val sectionGap = if (isShortHeight) 8.dp else 12.dp
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
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(sectionGap),
  ) {
    item {
      ProgramsHeaderCard(
        compact = isCompactWidth,
        shortHeight = isShortHeight,
        daemonOnline = daemonOnline,
        total = all.size,
        shown = filtered.size,
        active = activeShown,
        profilePrograms = profileShown,
        query = query,
        onQueryChange = { query = it },
        onClearQuery = { query = "" },
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
          compact = isCompactWidth,
          title = stringResource(R.string.apps_list_section_core),
          subtitle = stringResource(R.string.apps_list_items_count, core.size),
        )
      }
      items(core, key = { it.id }, contentType = { "program_card" }) { p ->
        ProgramCard(
          compact = isCompactWidth,
          program = p,
          onClick = { onOpenProgram(p.id) },
          horizontalPadding = cardPadding,
        )
      }
    }

    if (prof.isNotEmpty()) {
      item(key = "hdr_profiles") {
        SectionHeader(
          compact = isCompactWidth,
          title = stringResource(R.string.apps_list_section_profiles),
          subtitle = stringResource(R.string.apps_list_items_count, prof.size),
        )
      }
      items(prof, key = { it.id }, contentType = { "program_card" }) { p ->
        ProgramCard(
          compact = isCompactWidth,
          program = p,
          onClick = { onOpenProgram(p.id) },
          horizontalPadding = cardPadding,
        )
      }
    }

    item { Spacer(Modifier.height(8.dp)) }
  }
}

@Composable
private fun ProgramsHeaderCard(
  compact: Boolean,
  shortHeight: Boolean,
  daemonOnline: Boolean,
  total: Int,
  shown: Int,
  active: Int,
  profilePrograms: Int,
  query: String,
  onQueryChange: (String) -> Unit,
  onClearQuery: () -> Unit,
) {
  ElevatedCard(
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 16.dp),
  ) {
    Column(
      Modifier.padding(if (compact) 14.dp else 16.dp),
      verticalArrangement = Arrangement.spacedBy(if (shortHeight) 10.dp else 12.dp),
    ) {
      if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Text(
            stringResource(R.string.apps_list_programs_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          StatusChip(daemonOnline = daemonOnline)
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(
              stringResource(R.string.apps_list_programs_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = stringResource(R.string.apps_list_header_hint),
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
          }
          Spacer(Modifier.width(12.dp))
          StatusChip(daemonOnline = daemonOnline)
        }
      }

      if (compact) {
        Text(
          text = stringResource(R.string.apps_list_header_hint),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        )
      }

      SummaryMetricsRow(
        compact = compact,
        total = total,
        shown = shown,
        active = active,
        profilePrograms = profilePrograms,
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))

      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
          if (query.isNotBlank()) {
            IconButton(onClick = onClearQuery) {
              Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.apps_list_clear),
                modifier = Modifier.size(20.dp),
              )
            }
          }
        },
        label = { Text(stringResource(R.string.apps_list_search_programs)) },
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun StatusChip(daemonOnline: Boolean) {
  val label = if (daemonOnline) stringResource(R.string.apps_list_online_upper) else stringResource(R.string.apps_list_offline_upper)
  AssistChip(
    onClick = {},
    label = { Text(label) },
    colors = AssistChipDefaults.assistChipColors(
      containerColor = if (daemonOnline) {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f)
      } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
      },
    ),
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
  if (compact) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      SummaryMetricCard(
        title = if (shown == total) {
          stringResource(R.string.apps_list_total_fmt, total)
        } else {
          stringResource(R.string.apps_list_showing_fmt, shown, total)
        },
        value = active.toString(),
        hint = stringResource(R.string.apps_list_chip_enabled),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SummaryMetricCard(
          title = stringResource(R.string.apps_list_section_core),
          value = (shown - profilePrograms).coerceAtLeast(0).toString(),
          hint = stringResource(R.string.apps_list_items_count, (shown - profilePrograms).coerceAtLeast(0)),
          modifier = Modifier.weight(1f),
        )
        SummaryMetricCard(
          title = stringResource(R.string.apps_list_section_profiles),
          value = profilePrograms.toString(),
          hint = stringResource(R.string.apps_list_items_count, profilePrograms),
          modifier = Modifier.weight(1f),
        )
      }
    }
  } else {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
      SummaryMetricCard(
        title = if (shown == total) {
          stringResource(R.string.apps_list_total_fmt, total)
        } else {
          stringResource(R.string.apps_list_showing_fmt, shown, total)
        },
        value = active.toString(),
        hint = stringResource(R.string.apps_list_chip_enabled),
        modifier = Modifier.weight(1.25f),
      )
      SummaryMetricCard(
        title = stringResource(R.string.apps_list_section_core),
        value = (shown - profilePrograms).coerceAtLeast(0).toString(),
        hint = stringResource(R.string.apps_list_items_count, (shown - profilePrograms).coerceAtLeast(0)),
        modifier = Modifier.weight(1f),
      )
      SummaryMetricCard(
        title = stringResource(R.string.apps_list_section_profiles),
        value = profilePrograms.toString(),
        hint = stringResource(R.string.apps_list_items_count, profilePrograms),
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
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    shape = MaterialTheme.shapes.large,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = hint,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
      )
    }
  }
}

@Composable
private fun SectionHeader(compact: Boolean, title: String, subtitle: String) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.36f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    shape = MaterialTheme.shapes.large,
  ) {
    if (compact) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        AssistChip(
          onClick = {},
          label = { Text(subtitle) },
          colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
          ),
        )
      }
    } else {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        AssistChip(
          onClick = {},
          label = { Text(subtitle) },
          colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
          ),
        )
      }
    }
  }
}

@Composable
private fun EmptyState(title: String, hint: String) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    shape = MaterialTheme.shapes.extraLarge,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(hint, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
  }
}

@Composable
private fun ProgramCard(
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
  val profileAccent = MaterialTheme.colorScheme.primary
  val idleAccent = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
  val accentColor = when {
    isProfiles && isActive -> profileAccent
    isProfiles -> profileAccent.copy(alpha = 0.65f)
    isActive -> activeAccent
    else -> idleAccent
  }
  val containerColor = when {
    isActive -> MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    isProfiles -> MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)
    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.64f)
  }

  Card(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = horizontalPadding),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .width(6.dp)
          .height(if (compact) 108.dp else 94.dp)
          .background(accentColor),
      )

      Column(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = if (compact) Alignment.Top else Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Surface(
              color = accentColor.copy(alpha = 0.14f),
              shape = MaterialTheme.shapes.medium,
              tonalElevation = 0.dp,
              shadowElevation = 0.dp,
            ) {
              Icon(
                imageVector = programIcon(program.id),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier
                  .padding(8.dp)
                  .size(20.dp),
              )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(title, fontWeight = FontWeight.SemiBold, maxLines = if (compact) 2 else 1)
              Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          Spacer(Modifier.width(10.dp))
          Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
          )
        }

        if (compact) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgramBadgeRow(
              label = primaryChip,
              containerColor = if (isProfiles) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
              } else if (isActive) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)
              },
            )
            if (!secondaryChip.isNullOrBlank()) {
              ProgramBadgeRow(
                label = secondaryChip,
                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
              )
            }
          }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgramBadgeRow(
              label = primaryChip,
              containerColor = if (isProfiles) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
              } else if (isActive) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)
              },
            )
            if (!secondaryChip.isNullOrBlank()) {
              ProgramBadgeRow(
                label = secondaryChip,
                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProgramBadgeRow(label: String, containerColor: Color) {
  AssistChip(
    onClick = {},
    label = { Text(label) },
    colors = AssistChipDefaults.assistChipColors(containerColor = containerColor),
  )
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
  return when (id) {
    "dnscrypt" -> stringResource(R.string.apps_list_desc_dnscrypt)
    "operaproxy" -> stringResource(R.string.apps_list_desc_operaproxy)
    "nfqws" -> stringResource(R.string.apps_list_desc_nfqws)
    "nfqws2" -> stringResource(R.string.apps_list_desc_nfqws2)
    "byedpi" -> stringResource(R.string.apps_list_desc_byedpi)
    "dpitunnel" -> stringResource(R.string.apps_list_desc_dpitunnel)
    "wireproxy" -> stringResource(R.string.apps_list_desc_wireproxy)
    "tor" -> stringResource(R.string.apps_list_desc_tor)
    "myproxy" -> stringResource(R.string.apps_list_desc_myproxy)
    else -> stringResource(R.string.apps_list_desc_default)
  }
}

private fun programIcon(id: String): ImageVector {
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
    else -> Icons.Outlined.Extension
  }
}
