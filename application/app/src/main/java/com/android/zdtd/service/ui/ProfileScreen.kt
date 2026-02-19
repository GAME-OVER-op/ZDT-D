package com.android.zdtd.service.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels

@Composable
fun ProfileScreen(
  programs: List<ApiModels.Program>,
  programId: String,
  profile: String,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val program = programs.firstOrNull { it.id == programId }
  val prof = program?.profiles?.firstOrNull { it.name == profile }

  val pfx = "/api/programs/$programId/profiles/$profile"

  // Default open: Apps tab (Danil request).
  var tab by remember { mutableStateOf(0) }
  val contentScroll = rememberScrollState()

  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("$programId / $profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
      "Changes apply after stop/start.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
    )

    EnabledCard(
      title = "Profile enabled",
      checked = prof?.enabled ?: false,
      onCheckedChange = { v -> actions.setProfileEnabled(programId, profile, v) },
    )

    TabRow(selectedTabIndex = tab) {
      Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Apps") })
      Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Config") })
      if (programId == "operaproxy") {
        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("SNI") })
      }
    }

    // On small screens, tab content may not fit (e.g. Apps common/mobile/Wi‑Fi lists).
    // Make the tab content scrollable while keeping header + tabs visible.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f, fill = true)
        .verticalScroll(contentScroll)
        .navigationBarsPadding(),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (tab) {
          0 -> {
            // Apps lists: for nfqws/dpitunnel -> common/mobile/wifi; others -> common only.
            if (programId == "nfqws" || programId == "nfqws2" || programId == "dpitunnel") {
              NfqwsAppListsSection(pfx = pfx, actions = actions, snackHost = snackHost)
            } else {
              AppListPickerCard(
                title = "Apps (common)",
                desc = "Select installed apps; package names are saved automatically.",
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
                title = "config.txt",
                desc = "Profile config.",
                path = "$pfx/config",
                actions = actions,
                snackHost = snackHost,
              )
            }
          }
          2 -> {
            if (programId == "operaproxy") {
              TextEditorCard(
                title = "fake_sni.txt",
                desc = "Profile fake SNI list.",
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
