package com.android.zdtd.service.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.R

@Composable
fun ProfileScreen(
  programs: List<ApiModels.Program>,
  programId: String,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
) {
  val compact = rememberIsCompactWidth()
  val effectiveTopContentPadding = if (topContentPadding > 10.dp) topContentPadding - 6.dp else topContentPadding
  val effectiveBottomContentPadding = if (bottomContentPadding > 0.dp) bottomContentPadding + 14.dp else 88.dp
  val program = programs.firstOrNull { it.id == programId }
  val prof = program?.profiles?.firstOrNull { it.name == profile }
  val toolName = toolDisplayName(programId, program?.name)

  val pfx = "/api/programs/$programId/profiles/$profile"

  // Default open: Apps tab (Danil request).
  var tab by remember { mutableStateOf(0) }
  val screenScroll = rememberScrollState()

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(screenScroll)
      .padding(horizontal = if (compact) 12.dp else 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Spacer(Modifier.height(effectiveTopContentPadding))

    StrategicProfileEnabledCard(
      programId = programId,
      toolName = toolName,
      profile = profile,
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled(programId, profile, v) },
    )

    val tabs = buildList {
      add(0 to stringResource(R.string.tab_apps))
      add(1 to stringResource(R.string.tab_config))
      if (programId == "operaproxy") add(2 to stringResource(R.string.tab_sni))
    }
    StrategicProfileTabs(
      tabs = tabs,
      selected = tab,
      onSelect = { tab = it },
    )

    when (tab) {
      0 -> {
        if (programId == "nfqws" || programId == "nfqws2" || programId == "dpitunnel") {
          NfqwsAppListsSection(pfx = pfx, actions = actions, snackHost = snackHost, programs = programs)
        } else {
          AppListPickerCard(
            title = stringResource(R.string.apps_common_title),
            desc = stringResource(R.string.apps_common_desc),
            path = "$pfx/apps/user",
            actions = actions,
            snackHost = snackHost,
            programs = programs,
          )
        }
      }
      1 -> {
        if (programId == "nfqws" || programId == "nfqws2" || programId == "dpitunnel" || programId == "byedpi") {
          StrategicVarConfigCard(
            programId = programId,
            profile = profile,
            configPath = "$pfx/config",
            actions = actions,
            snackHost = snackHost,
          )
        } else {
          TextEditorCard(
            title = stringResource(R.string.profile_config_txt_title),
            desc = stringResource(R.string.profile_config_desc),
            path = "$pfx/config",
            actions = actions,
            snackHost = snackHost,
          )
        }
      }
      2 -> {
        if (programId == "operaproxy") {
          TextEditorCard(
            title = stringResource(R.string.profile_fake_sni_txt_title),
            desc = stringResource(R.string.profile_fake_sni_desc),
            path = "$pfx/sni",
            actions = actions,
            snackHost = snackHost,
          )
        }
      }
    }

    Spacer(Modifier.height(effectiveBottomContentPadding))
  }
}

@Composable
private fun StrategicProfileEnabledCard(
  programId: String,
  toolName: String,
  profile: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  val compact = rememberIsCompactWidth()
  val accent = strategicAccentFor(programId, checked)
  val stateText = stringResource(if (checked) R.string.enabled_state_on else R.string.enabled_state_off)

  Card(
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)),
    border = BorderStroke(1.dp, accent.copy(alpha = if (checked) 0.42f else 0.22f)),
  ) {
    Box(
      Modifier
        .fillMaxWidth()
        .background(
          Brush.linearGradient(
            listOf(
              accent.copy(alpha = if (checked) 0.16f else 0.07f),
              MaterialTheme.colorScheme.surface.copy(alpha = 0.02f),
            )
          )
        )
        .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 11.dp else 12.dp)
    ) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          modifier = Modifier.size(if (compact) 44.dp else 48.dp),
          color = accent.copy(alpha = if (checked) 0.18f else 0.11f),
          contentColor = accent,
          shape = CircleShape,
          border = BorderStroke(1.dp, accent.copy(alpha = if (checked) 0.46f else 0.25f)),
        ) {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(if (compact) 21.dp else 23.dp))
          }
        }
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            stringResource(R.string.enabled_card_profile_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            "$toolName / $profile",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Surface(
            shape = RoundedCornerShape(100.dp),
            color = accent.copy(alpha = if (checked) 0.16f else 0.10f),
            contentColor = accent,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
          ) {
            Text(
              stateText,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
      }
    }
  }
}

@Composable
private fun StrategicProfileTabs(
  tabs: List<Pair<Int, String>>,
  selected: Int,
  onSelect: (Int) -> Unit,
) {
  val compact = rememberIsCompactWidth()
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(5.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      tabs.forEach { (index, label) ->
        val active = selected == index
        Surface(
          modifier = Modifier
            .weight(1f)
            .clickable { onSelect(index) },
          shape = RoundedCornerShape(16.dp),
          color = if (active) Color(0xFF38BDF8).copy(alpha = 0.18f) else Color.Transparent,
          contentColor = if (active) Color(0xFF7DD3FC) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
          border = if (active) BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.28f)) else null,
        ) {
          Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = if (compact) 8.dp else 9.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              label,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

private fun strategicAccentFor(programId: String, checked: Boolean): Color {
  if (!checked) return Color(0xFF94A3B8)
  return when (programId) {
    "nfqws" -> Color(0xFF38BDF8)
    "nfqws2" -> Color(0xFF60A5FA)
    "dpitunnel" -> Color(0xFFA78BFA)
    "byedpi" -> Color(0xFFF97316)
    else -> Color(0xFF22C55E)
  }
}
