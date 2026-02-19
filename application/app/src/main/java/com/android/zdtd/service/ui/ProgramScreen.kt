package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramScreen(
  programs: List<ApiModels.Program>,
  programId: String,
  onOpenProfile: (String, String) -> Unit,
  actions: ZdtdActions,
  snackHost: SnackbarHostState,
) {
  val program = programs.firstOrNull { it.id == programId }
  if (program == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Program not found") }
    return
  }

  val scope = rememberCoroutineScope()

  val hasStrategicFiles = program.id == "nfqws" || program.id == "nfqws2"
  var programTab by remember(program.id) { mutableStateOf(0) }

  var showCreateProfile by remember { mutableStateOf(false) }

  fun showSnack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  val sortedProfiles = remember(program.profiles) { sortProfilesDesc(program.profiles) }
  // Delete is allowed only in decreasing order: if there are profile1 and profile2,
  // the UI should only allow deleting profile2 first.
  val maxNumericProfileIdx = remember(program.profiles) {
    program.profiles.map { profileIndex(it.name) }.filter { it != Int.MIN_VALUE }.maxOrNull() ?: Int.MIN_VALUE
  }

  // Dialogs must be invoked from a @Composable context (not inside LazyColumn/LazyListScope).
  if (program.type == "profiles" && showCreateProfile && (!hasStrategicFiles || programTab == 0)) {
    CreateProfileDialog(
      existing = program.profiles.map { it.name },
      onDismiss = { showCreateProfile = false },
      onCreate = { name ->
        showCreateProfile = false
        actions.createNamedProfile(program.id, name) { created ->
          if (created != null) {
            showSnack("Profile created: $created")
            onOpenProfile(program.id, created)
          } else {
            showSnack("Create failed")
          }
        }
      },
      snackHost = snackHost,
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Text(program.name ?: program.id, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(4.dp))
      Text(
        "Changes apply after stop/start.",
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        style = MaterialTheme.typography.bodySmall,
      )
    }

    // Global enabled toggle:
    // - MUST exist for dnscrypt + operaproxy (per Danil)
    // - MUST NOT exist for zapret/dpitunnel/byedpi (it's useless there)
    if (program.id == "dnscrypt" || program.id == "operaproxy") {
      item {
        EnabledCard(
          title = "Enabled",
          checked = program.enabled,
          onCheckedChange = { v -> actions.setProgramEnabled(program.id, v) },
        )
      }
    }

    when {
      
program.type == "profiles" -> {
        item {
          if (hasStrategicFiles) {
            TabRow(selectedTabIndex = programTab) {
              Tab(selected = programTab == 0, onClick = { programTab = 0 }, text = { Text("Profiles") })
              Tab(selected = programTab == 1, onClick = { programTab = 1 }, text = { Text("Files") })
            }
            Spacer(Modifier.height(10.dp))
          }

          if (!hasStrategicFiles || programTab == 0) {
            ProfilesHeader(onAdd = { showCreateProfile = true })
          }
        }

        if (!hasStrategicFiles || programTab == 0) {
          items(sortedProfiles, key = { it.name }) { prof ->
            ProfileRow(
              programId = program.id,
              profile = prof,
              onOpen = { onOpenProfile(program.id, prof.name) },
              onToggle = { v -> actions.setProfileEnabled(program.id, prof.name, v) },
              onDelete = {
                actions.deleteProfile(program.id, prof.name) { ok ->
                  showSnack(if (ok) "Deleted" else "Delete failed")
                }
              },
              deletable = run {
                val idx = profileIndex(prof.name)
                idx == Int.MIN_VALUE || idx == maxNumericProfileIdx
              },
            )
          }
        } else {
          item {
            ZapretStrategicFiles(programId = program.id, actions = actions, snackHost = snackHost)
          }
        }
      }

      program.id == "dnscrypt" -> {
        item {
          TextEditorCard(
            title = "dnscrypt-proxy.toml",
            desc = "Edit main config; restart required.",
            path = "/api/programs/dnscrypt/config",
            actions = actions,
            snackHost = snackHost,
          )
        }
        item {
          Spacer(Modifier.height(10.dp))
          DnscryptSettingFilesSection(actions = actions, snackHost = snackHost)
        }
      }

      program.id == "operaproxy" -> {
        item {
          OperaProxySection(actions = actions, snackHost = snackHost)
        }
      }

      else -> {
        item {
          Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
            Column(Modifier.padding(12.dp)) {
              Text("Not implemented yet", fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Text(
                "Для этого модуля пока нет нативной формы. Можно добавить позже по твоим указаниям.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
              )
            }
          }
        }
      }
    }

    item { Spacer(Modifier.height(80.dp)) }
  }
}

@Composable
private fun ProfilesHeader(onAdd: () -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text("Profiles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    FilledTonalButton(onClick = onAdd) {
      Icon(Icons.Filled.Add, contentDescription = null)
      Spacer(Modifier.width(6.dp))
      Text("Add")
    }
  }
}

private fun sortProfilesDesc(list: List<ApiModels.Profile>): List<ApiModels.Profile> {
  // Danil: delete profiles in decreasing order; also show newest (highest) first.
  return list.sortedWith(compareByDescending<ApiModels.Profile> {
    profileIndex(it.name)
  }.thenByDescending { it.name.lowercase() })
}

private fun profileIndex(name: String): Int {
  // Supports both "1" and "profile1" formats.
  val n = name.trim()
  n.toIntOrNull()?.let { return it }
  if (n.startsWith("profile", ignoreCase = true)) {
    return n.drop(7).toIntOrNull() ?: Int.MIN_VALUE
  }
  return Int.MIN_VALUE
}

@Composable
private fun ProfileRow(
  programId: String,
  profile: ApiModels.Profile,
  onOpen: () -> Unit,
  onToggle: (Boolean) -> Unit,
  onDelete: () -> Unit,
  deletable: Boolean,
) {
  var askDelete by remember { mutableStateOf(false) }
  if (askDelete) {
    AlertDialog(
      onDismissRequest = { askDelete = false },
      title = { Text("Delete profile") },
      text = { Text("$programId / ${profile.name}") },
      confirmButton = {
        Button(onClick = { askDelete = false; onDelete() }) { Text("Delete") }
      },
      dismissButton = { OutlinedButton(onClick = { askDelete = false }) { Text("Cancel") } },
    )
  }

  Card(
    onClick = onOpen,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(profile.name, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text("Apply after stop/start", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
      }
      Switch(checked = profile.enabled, onCheckedChange = onToggle)
      if (deletable) {
        IconButton(onClick = { askDelete = true }) {
          Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }
      }
    }
  }
}

@Composable
private fun CreateProfileDialog(
  existing: List<String>,
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit,
  snackHost: SnackbarHostState,
) {
  val scope = rememberCoroutineScope()
  val existingNorm = remember(existing) { existing.map { normalizeProfileName(it) }.toSet() }

  var raw by remember { mutableStateOf("") }
  val name = remember(raw) { normalizeProfileName(raw) }
  var error by remember { mutableStateOf<String?>(null) }

  fun snack(msg: String) {
    scope.launch { snackHost.showSnackbar(msg) }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Create profile") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "Name: English only, no spaces (spaces become _), max 10 chars.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )

        OutlinedTextField(
          value = name,
          onValueChange = { v ->
            // Keep 'raw' so we can normalize consistently; but show normalized in the field.
            raw = v
            error = null
          },
          label = { Text("Profile name") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
          supportingText = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Allowed: a-z 0-9 _ -")
              Text("${name.length}/10")
            }
          },
          isError = error != null,
        )

        if (error != null) {
          Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val n = name.trim()
          when {
            n.isEmpty() -> {
              error = "Enter a name"
              snack("Invalid profile name")
            }
            existingNorm.contains(n) -> {
              error = "Profile already exists"
              snack("Profile already exists")
            }
            else -> onCreate(n)
          }
        },
        enabled = name.isNotBlank(),
      ) { Text("Create") }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

private fun normalizeProfileName(input: String): String {
  // Requirements from Danil:
  // - English only
  // - no spaces (convert spaces to _)
  // - max length 10
  val sb = StringBuilder(10)
  for (ch in input.lowercase()) {
    if (sb.length >= 10) break
    val c = when {
      ch.isWhitespace() -> '_'
      ch in 'a'..'z' -> ch
      ch in '0'..'9' -> ch
      ch == '_' || ch == '-' -> ch
      else -> null
    }
    if (c != null) sb.append(c)
  }
  return sb.toString()
}

@Composable
private fun OperaProxySection(actions: ZdtdActions, snackHost: SnackbarHostState) {
  var tab by remember { mutableStateOf(0) }

  val scope = rememberCoroutineScope()
  fun snack(msg: String) { scope.launch { snackHost.showSnackbar(msg) } }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Opera Proxy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    TabRow(selectedTabIndex = tab) {
      Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Apps") })
      Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Byedpi") })
      Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("SNI") })
      Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Servers") })
    }

    when (tab) {
      0 -> {
        // Apps lists: common + mobile + Wi‑Fi (like nfqws).
        NfqwsAppListsSection(
          pfx = "/api/programs/operaproxy",
          actions = actions,
          snackHost = snackHost,
        )
      }
      1 -> {
        TextEditorCard(
          title = "byedpi start_args",
          desc = "Arguments for byedpi start.",
          path = "/api/programs/operaproxy/byedpi/start_args",
          actions = actions,
          snackHost = snackHost,
        )
        Spacer(Modifier.height(10.dp))
        TextEditorCard(
          title = "byedpi restart_args",
          desc = "Arguments for byedpi restart.",
          path = "/api/programs/operaproxy/byedpi/restart_args",
          actions = actions,
          snackHost = snackHost,
        )
        Spacer(Modifier.height(10.dp))
        JsonEditorCard(
          title = "ports",
          desc = "Ports map.",
          path = "/api/programs/operaproxy/ports",
          actions = actions,
          snackHost = snackHost,
        )
      }
      2 -> {
        TextEditorCard(
          title = "fake_sni.txt",
          desc = "Fake SNI list.",
          path = "/api/programs/operaproxy/sni",
          actions = actions,
          snackHost = snackHost,
        )
      }
      3 -> {
        OperaServerSection(actions = actions, snack = ::snack)
      }
    }
  }
}

@Composable
private fun OperaServerSection(
  actions: ZdtdActions,
  snack: (String) -> Unit,
) {
  val apiPath = "/api/programs/operaproxy/server"

  fun normalize(raw: String?): String {
    val v = raw.orEmpty().trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().uppercase()
    return if (v == "EU" || v == "AS" || v == "AM") v else "EU"
  }

  var value by remember { mutableStateOf("EU") }
  var loaded by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    actions.loadText(apiPath) { raw ->
      value = normalize(raw)
      loaded = true
    }
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Servers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(
        "Select Opera server region used by opera-proxy (-country).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RegionButton(label = "Europe", code = "EU", selected = value == "EU") {
          actions.saveText(apiPath, "EU\n") { ok ->
            if (ok) {
              value = "EU"
              snack("Saved")
            } else {
              snack("Save failed")
            }
          }
        }
        RegionButton(label = "Asia", code = "AS", selected = value == "AS") {
          actions.saveText(apiPath, "AS\n") { ok ->
            if (ok) {
              value = "AS"
              snack("Saved")
            } else {
              snack("Save failed")
            }
          }
        }
        RegionButton(label = "America", code = "AM", selected = value == "AM") {
          actions.saveText(apiPath, "AM\n") { ok ->
            if (ok) {
              value = "AM"
              snack("Saved")
            } else {
              snack("Save failed")
            }
          }
        }
      }

      if (!loaded) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      } else {
        Text(
          "Current: $value",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
      }
    }
  }
}

@Composable
private fun RegionButton(
  label: String,
  code: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  if (selected) {
    Button(onClick = onClick) { Text(label) }
  } else {
    OutlinedButton(onClick = onClick) { Text(label) }
  }
}
