package com.android.zdtd.service.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

internal data class ProgramLogTarget(
  val programId: String,
  val profile: String? = null,
  val title: String,
) {
  val key: String = if (profile == null) "program/$programId" else "profile/$programId/$profile"
}

private data class ProgramLogFileUi(
  val title: String,
  val path: String,
  val sizeBytes: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgramLogsBrowserSheet(
  target: ProgramLogTarget,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var files by remember(target.key) { mutableStateOf<List<ProgramLogFileUi>>(emptyList()) }
  var loading by remember(target.key) { mutableStateOf(true) }
  var selected by remember(target.key) { mutableStateOf<ProgramLogFileUi?>(null) }

  suspend fun refreshFiles() {
    loading = true
    files = loadProgramLogFiles(context.applicationContext, target)
    loading = false
  }

  LaunchedEffect(target.key) {
    refreshFiles()
  }

  ModalBottomSheet(
    sheetState = sheetState,
    onDismissRequest = onDismiss,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(if (rememberIsShortHeight()) 0.94f else 0.88f)
        .padding(horizontal = 16.dp)
        .animateContentSize(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      ProgramLogsHeader(
        target = target,
        selected = selected,
        loading = loading,
        onBackToFiles = { selected = null },
        onRefresh = { if (selected == null) loading = true },
        refreshFiles = { refreshFiles() },
        onDismiss = onDismiss,
      )

      AnimatedContent(
        targetState = selected,
        transitionSpec = {
          val forward = targetState != null
          val enter = fadeIn(tween(150)) + slideInHorizontally(tween(210)) { if (forward) it / 8 else -it / 8 }
          val exit = fadeOut(tween(130)) + slideOutHorizontally(tween(190)) { if (forward) -it / 8 else it / 8 }
          (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "programLogsContent",
      ) { currentFile ->
        if (currentFile == null) {
          ProgramLogsFileList(
            loading = loading,
            files = files,
            onOpen = { selected = it },
          )
        } else {
          ProgramLogTailViewer(file = currentFile)
        }
      }

      Spacer(Modifier.height(12.dp))
    }
  }
}

@Composable
private fun ProgramLogsHeader(
  target: ProgramLogTarget,
  selected: ProgramLogFileUi?,
  loading: Boolean,
  onBackToFiles: () -> Unit,
  onRefresh: () -> Unit,
  refreshFiles: suspend () -> Unit,
  onDismiss: () -> Unit,
) {
  var refreshNonce by remember { mutableStateOf(0) }

  LaunchedEffect(refreshNonce) {
    if (refreshNonce > 0) refreshFiles()
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (selected != null) {
      IconButton(onClick = onBackToFiles) {
        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.program_logs_back_to_files))
      }
    } else {
      Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error)
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = selected?.title ?: stringResource(R.string.program_logs_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = target.title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (selected == null) {
      IconButton(
        enabled = !loading,
        onClick = {
          onRefresh()
          refreshNonce += 1
        },
      ) {
        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.program_logs_refresh))
      }
    }
    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
  }
}

@Composable
private fun ProgramLogsFileList(
  loading: Boolean,
  files: List<ProgramLogFileUi>,
  onOpen: (ProgramLogFileUi) -> Unit,
) {
  when {
    loading -> {
      Box(Modifier.fillMaxWidth().heightIn(min = 260.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
          CircularProgressIndicator()
          Text(stringResource(R.string.common_loading), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
        }
      }
    }
    files.isEmpty() -> {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.program_logs_empty), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
          Text(
            stringResource(R.string.program_logs_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
          )
        }
      }
    }
    else -> {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(files, key = { it.path }, contentType = { "program_log_file" }) { file ->
          ProgramLogFileCard(file = file, onOpen = { onOpen(file) })
        }
      }
    }
  }
}

@Composable
private fun ProgramLogFileCard(
  file: ProgramLogFileUi,
  onOpen: () -> Unit,
) {
  Card(
    onClick = onOpen,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(file.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(file.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Text(formatBytes(file.sizeBytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    }
  }
}

@Composable
private fun ProgramLogTailViewer(file: ProgramLogFileUi) {
  val context = LocalContext.current
  val root = remember(context) { RootConfigManager(context.applicationContext) }
  var text by remember(file.path) { mutableStateOf("") }
  var loading by remember(file.path) { mutableStateOf(true) }
  val lines = remember(text) { text.lines().filter { it.isNotBlank() }.takeLast(260) }
  val listState = rememberLazyListState()

  LaunchedEffect(file.path) {
    while (true) {
      val next = withContext(Dispatchers.IO) {
        runCatching { root.readLogTail(file.path, 320) }.getOrDefault("")
      }
      text = next
      loading = false
      delay(750)
    }
  }

  LaunchedEffect(lines.size) {
    if (lines.isNotEmpty() && !listState.isScrollInProgress) {
      runCatching { listState.animateScrollToItem(0) }
    }
  }

  Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(9.dp)
          .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.small)
      )
      Text(
        text = stringResource(R.string.program_logs_realtime),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
      )
      Spacer(Modifier.width(4.dp))
      Text(
        text = formatBytes(file.sizeBytes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
      )
    }

    Card(
      modifier = Modifier.fillMaxSize(),
      colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.26f)),
    ) {
      if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
      } else if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.TopStart) {
          Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
      } else {
        LazyColumn(
          state = listState,
          reverseLayout = true,
          modifier = Modifier.fillMaxSize().padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          itemsIndexed(lines.asReversed(), key = { index, line -> "$index:${line.hashCode()}" }, contentType = { _, _ -> "program_log_line" }) { _, line ->
            Text(
              text = line,
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            )
          }
        }
      }
    }
  }
}

private suspend fun loadProgramLogFiles(context: Context, target: ProgramLogTarget): List<ProgramLogFileUi> = withContext(Dispatchers.IO) {
  val root = RootConfigManager(context.applicationContext)
  val script = buildProgramLogsListScript(target)
  if (script.isBlank()) return@withContext emptyList()
  val raw = runCatching { root.execRootSh(script).out.joinToString("\n") }.getOrDefault("")
  raw.lineSequence()
    .mapNotNull { line ->
      val parts = line.split('\t', limit = 3)
      if (parts.size != 3) return@mapNotNull null
      val size = parts[0].trim().toLongOrNull() ?: 0L
      val title = parts[1].trim().ifBlank { parts[2].substringAfterLast('/') }
      val path = parts[2].trim()
      if (!path.startsWith("/data/adb/modules/ZDT-D/")) return@mapNotNull null
      ProgramLogFileUi(title = title, path = path, sizeBytes = max(0L, size))
    }
    .distinctBy { it.path }
    .sortedWith(compareBy<ProgramLogFileUi> { it.title.lowercase() }.thenBy { it.path })
    .toList()
}

private fun buildProgramLogsListScript(target: ProgramLogTarget): String {
  val commands = mutableListOf<String>()
  fun addStatic(title: String, path: String) {
    commands += "if test -f ${shQuote(path)}; then s=\$(wc -c < ${shQuote(path)} 2>/dev/null || echo 0); printf '%s\\t%s\\t%s\\n' \"\$s\" ${shQuote(title)} ${shQuote(path)}; fi"
  }
  fun addFindLogs(dir: String, maxDepth: Int = 1, name: String = "*.log") {
    commands += "if test -d ${shQuote(dir)}; then find ${shQuote(dir)} -maxdepth $maxDepth -type f -name ${shQuote(name)} 2>/dev/null | while IFS= read -r p; do s=\$(wc -c < \"\$p\" 2>/dev/null || echo 0); b=\$(basename \"\$p\"); printf '%s\\t%s\\t%s\\n' \"\$s\" \"\$b\" \"\$p\"; done; fi"
  }
  fun addFindExact(dir: String, maxDepth: Int, exactFile: String) {
    commands += "if test -d ${shQuote(dir)}; then find ${shQuote(dir)} -maxdepth $maxDepth -type f -name ${shQuote(exactFile)} 2>/dev/null | while IFS= read -r p; do s=\$(wc -c < \"\$p\" 2>/dev/null || echo 0); rel=\${p#${shQuote(dir).removeSurrounding("'")}/}; printf '%s\\t%s\\t%s\\n' \"\$s\" \"\$rel\" \"\$p\"; done; fi"
  }

  val profile = target.profile
  if (profile == null) {
    when (target.programId) {
      "operaproxy" -> addFindLogs("/data/adb/modules/ZDT-D/working_folder/operaproxy/log")
      "dnscrypt" -> addFindLogs("/data/adb/modules/ZDT-D/working_folder/dnscrypt/log")
      "tor" -> {
        addStatic("tor.log", "/data/adb/modules/ZDT-D/working_folder/tor/log/tor.log")
        addStatic("t2s.log", "/data/adb/modules/ZDT-D/working_folder/tor/log/t2s.log")
      }
    }
  } else {
    when (target.programId) {
      "nfqws" -> addStatic("nfqws.log", "/data/adb/modules/ZDT-D/working_folder/nfqws/$profile/log/nfqws.log")
      "nfqws2" -> addStatic("nfqws.log", "/data/adb/modules/ZDT-D/working_folder/nfqws2/$profile/log/nfqws.log")
      "byedpi" -> addStatic("byedpi.log", "/data/adb/modules/ZDT-D/working_folder/byedpi/$profile/log/byedpi.log")
      "dpitunnel" -> addStatic("dpitunnel.log", "/data/adb/modules/ZDT-D/working_folder/dpitunnel/$profile/log/dpitunnel.log")
      "sing-box" -> {
        val base = "/data/adb/modules/ZDT-D/working_folder/singbox/profile/$profile"
        addStatic("t2s.log", "$base/log/t2s.log")
        addFindExact("$base/server", 3, "sing-box.log")
      }
      "wireproxy" -> {
        val base = "/data/adb/modules/ZDT-D/working_folder/wireproxy/profile/$profile"
        addStatic("t2s.log", "$base/log/t2s.log")
        addFindExact("$base/server", 3, "wireproxy.log")
      }
      "myproxy" -> addStatic("t2s.log", "/data/adb/modules/ZDT-D/working_folder/myproxy/profile/$profile/log/t2s.log")
      "myprogram" -> {
        val base = "/data/adb/modules/ZDT-D/working_folder/myprogram/profile/$profile/log"
        addStatic("program.log", "$base/program.log")
        addStatic("t2s.log", "$base/t2s.log")
      }
      "openvpn" -> addStatic("openvpn.log", "/data/adb/modules/ZDT-D/working_folder/openvpn/profile/$profile/log/openvpn.log")
      "amneziawg" -> {
        val base = "/data/adb/modules/ZDT-D/working_folder/amneziawg/profile/$profile/log"
        addStatic("start.log", "$base/start.log")
        addStatic("amneziawg-go.log", "$base/amneziawg-go.log")
        addStatic("awg.log", "$base/awg.log")
      }
      "tun2socks" -> addStatic("tun2socks.log", "/data/adb/modules/ZDT-D/working_folder/tun2socks/profile/$profile/log/tun2socks.log")
      "mihomo" -> {
        val base = "/data/adb/modules/ZDT-D/working_folder/mihomo/profile/$profile/log"
        addStatic("mihomo.log", "$base/mihomo.log")
        addStatic("tun2socks.log", "$base/tun2socks.log")
      }
      "mieru" -> {
        val base = "/data/adb/modules/ZDT-D/working_folder/mieru/profile/$profile/log"
        addStatic("mieru.log", "$base/mieru.log")
        addStatic("tun2proxy.log", "$base/tun2proxy.log")
      }
    }
  }

  return commands.joinToString("\n")
}

private fun formatBytes(bytes: Long): String {
  val kb = 1024.0
  val mb = kb * 1024.0
  return when {
    bytes >= mb -> "%.1f MB".format(bytes / mb)
    bytes >= kb -> "%.1f KB".format(bytes / kb)
    else -> "$bytes B"
  }
}

private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
