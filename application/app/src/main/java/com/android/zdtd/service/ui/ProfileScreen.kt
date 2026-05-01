package com.android.zdtd.service.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
) {
  val compact = rememberIsCompactWidth()
  val useScrollableTabs = rememberUseScrollableTabs()
  val program = programs.firstOrNull { it.id == programId }
  val prof = program?.profiles?.firstOrNull { it.name == profile }

  val pfx = "/api/programs/$programId/profiles/$profile"

  // Default open: Apps tab (Danil request).
  var tab by remember { mutableStateOf(0) }
  val contentScroll = rememberScrollState()
  val screenScroll = rememberScrollState()

  Column(
    Modifier
      .fillMaxSize()
      .padding(if (compact) 12.dp else 16.dp)
      .then(if (compact) Modifier.verticalScroll(screenScroll) else Modifier),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text("$programId / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Text(
      stringResource(R.string.changes_apply_after_restart),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = stringResource(R.string.enabled_card_profile_title),
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled(programId, profile, v) },
    )

    if (useScrollableTabs) {
      ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_apps), maxLines = 2) })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_config), maxLines = 2) })
        if (programId == "operaproxy") {
          Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_sni), maxLines = 2) })
        }
      }
    } else {
      TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_apps), maxLines = 2) })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_config), maxLines = 2) })
        if (programId == "operaproxy") {
          Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_sni), maxLines = 2) })
        }
      }
    }

    // On compact screens, scroll the whole screen instead of a weighted inner box.
    // This avoids the tab content collapsing to an almost empty area under the profile switch.
    val contentModifier = if (compact) {
      Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
    } else {
      Modifier
        .fillMaxWidth()
        .weight(1f, fill = true)
        .verticalScroll(contentScroll)
        .navigationBarsPadding()
    }

    Box(modifier = contentModifier) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (tab) {
          0 -> {
            // Apps lists: for nfqws/dpitunnel -> common/mobile/wifi; others -> common only.
            if (programId == "nfqws" || programId == "nfqws2" || programId == "dpitunnel") {
              NfqwsAppListsSection(pfx = pfx, actions = actions, snackHost = snackHost)
            } else {
              AppListPickerCard(
                title = stringResource(R.string.apps_common_title),
                desc = stringResource(R.string.apps_common_desc),
                path = "$pfx/apps/user",
                actions = actions,
                snackHost = snackHost,
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
      }
    }
  }
}