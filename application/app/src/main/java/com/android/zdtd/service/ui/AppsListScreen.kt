package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.api.ApiModels
import java.util.Locale

@Composable
fun AppsListScreen(
  programs: List<ApiModels.Program>,
  daemonOnline: Boolean,
  onOpenProgram: (String) -> Unit,
) {

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

  val core = remember(filtered) {
    filtered.filter { it.type != "profiles" }.sortedBy { (it.name ?: it.id).lowercase(Locale.ROOT) }
  }
  val prof = remember(filtered) {
    filtered.filter { it.type == "profiles" }.sortedBy { (it.name ?: it.id).lowercase(Locale.ROOT) }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ProgramsHeaderCard(
        daemonOnline = daemonOnline,
        total = all.size,
        shown = filtered.size,
      )
    }

    item {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
          if (query.isNotBlank()) {
            IconButton(onClick = { query = "" }) {
              Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Clear",
                modifier = Modifier.size(20.dp),
              )
            }
          }
        },
        label = { Text("Search programs") },
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
      )
    }

    if (all.isEmpty()) {
      item {
        EmptyState(
          title = "No programs",
      hint = if (daemonOnline) {
            "The daemon is online, but it returned an empty program list."
          } else {
            "The daemon is offline. Start ZDT-D first."
          },
        )
      }
      return@LazyColumn
    }

    if (filtered.isEmpty()) {
      item {
        EmptyState(
          title = "Nothing found",
          hint = "Try another search query.",
        )
      }
      return@LazyColumn
    }

    if (core.isNotEmpty()) {
      // NOTE: We intentionally avoid LazyListScope.stickyHeader to stay compatible
      // with older Compose Foundation versions used in some Android toolchains.
      item(key = "hdr_core") {
        SectionHeader(title = "Core", subtitle = "${core.size} item(s)")
      }
      items(core, key = { it.id }) { p ->
        ProgramCard(program = p, onClick = { onOpenProgram(p.id) })
      }
    }

    if (prof.isNotEmpty()) {
      item(key = "hdr_profiles") {
        SectionHeader(title = "Profiles", subtitle = "${prof.size} item(s)")
      }
      items(prof, key = { it.id }) { p ->
        ProgramCard(program = p, onClick = { onOpenProgram(p.id) })
      }
    }

    item { Spacer(Modifier.size(6.dp)) }
  }
}

@Composable
private fun ProgramsHeaderCard(daemonOnline: Boolean, total: Int, shown: Int) {
  ElevatedCard(
    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 16.dp),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Programs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val label = if (daemonOnline) "ONLINE" else "OFFLINE"
        AssistChip(
          onClick = {},
          label = { Text(label) },
          colors = AssistChipDefaults.assistChipColors(
            containerColor = if (daemonOnline) MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
          ),
        )
      }

      Text(
        text = "Open a program to edit configs, profiles and app lists.",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      )

      val line = if (total == shown) "Total: $total" else "Showing: $shown / $total"
      Text(line, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
  }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
    }
  }
}

@Composable
private fun EmptyState(title: String, hint: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(hint, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
  }
}

@Composable
private fun ProgramCard(program: ApiModels.Program, onClick: () -> Unit) {
  val title = program.name ?: program.id
  val subtitle = programDescription(program.id)

  val isProfiles = program.type == "profiles"
  val profilesTotal = program.profiles.size
  val profilesEnabled = program.profiles.count { it.enabled }

  val primaryChip = if (isProfiles) "profiles $profilesTotal" else if (program.enabled) "enabled" else "disabled"
  val secondaryChip = if (isProfiles) "enabled $profilesEnabled" else null

  val chipColor = if (isProfiles) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
  else if (program.enabled) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
  else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)

  Card(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Icon(
            imageVector = programIcon(program.id),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.size(22.dp),
          )
          Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
              subtitle,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }

        Icon(
          imageVector = Icons.Outlined.ChevronRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
          onClick = {},
          label = { Text(primaryChip) },
          colors = AssistChipDefaults.assistChipColors(containerColor = chipColor),
        )
        if (!secondaryChip.isNullOrBlank()) {
          AssistChip(
            onClick = {},
            label = { Text(secondaryChip) },
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
          )
        }
      }
    }
  }
}

private fun programDescription(id: String): String {
  return when (id) {
    "dnscrypt" -> "Local encrypted DNS resolver"
    "operaproxy" -> "byedpi \u2192 opera-proxy \u2192 t2s chain"
    "nfqws" -> "zapret NFQUEUE DPI bypass"
    "nfqws2" -> "zapret2 NFQUEUE DPI bypass"
    "byedpi" -> "Local TCP proxy"
    "dpitunnel" -> "TCP+UDP tunnel"
    else -> "Configure and manage"
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
    else -> Icons.Outlined.Extension
  }
}
