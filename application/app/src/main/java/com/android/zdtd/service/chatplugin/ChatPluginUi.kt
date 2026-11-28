package com.android.zdtd.service.chatplugin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon as LauncherIcon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChatPluginUi(
  val offer: Boolean, val fallback: Boolean, val busy: Boolean, val progress: Int?, val status: Int?,
  val onInstall: () -> Unit, val onDecline: () -> Unit, val onOpen: () -> Unit, val onPin: () -> Unit,
)

private const val SHORTCUT_ID = "zdt_chat"

@Composable
internal fun rememberChatPluginUi(): ChatPluginUi {
  val context = LocalContext.current
  val compatible = BuildConfig.IS_ROOT_BUILD && "arm64-v8a" in Build.SUPPORTED_ABIS
  val manager = remember(context) { ChatPluginManager(context) }
  val prefs = remember(context) { context.getSharedPreferences("chat_plugin", Context.MODE_PRIVATE) }
  val scope = rememberCoroutineScope()
  val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
  val pinChanges by ChatShortcutReceiver.changes.collectAsState()
  var dismissed by remember { mutableStateOf(prefs.getBoolean("offer_dismissed", false)) }
  var installed by remember { mutableStateOf(ChatPluginManager.Installation.ABSENT) }
  var checked by remember { mutableStateOf(false) }
  var pinned by remember { mutableStateOf(false) }
  var downloading by remember { mutableStateOf(false) }
  var installing by rememberSaveable { mutableStateOf(false) }
  var promptPin by rememberSaveable { mutableStateOf(false) }
  var progress by remember { mutableStateOf<Int?>(null) }
  var error by remember { mutableStateOf<Int?>(null) }

  fun refresh() {
    if (!compatible) return
    installed = manager.installation()
    pinned = if (Build.VERSION.SDK_INT >= 26) runCatching {
      context.getSystemService(ShortcutManager::class.java)?.pinnedShortcuts?.any { it.id == SHORTCUT_ID } == true
    }.getOrDefault(false) else false
    checked = true
  }
  fun decline() {
    dismissed = true
    prefs.edit().putBoolean("offer_dismissed", true).apply()
  }
  fun toast(id: Int) { Toast.makeText(context, id, Toast.LENGTH_SHORT).show() }
  fun pin() {
    if (Build.VERSION.SDK_INT < 26 || installed != ChatPluginManager.Installation.TRUSTED) return
    val requested = runCatching {
      val launcher = context.getSystemService(ShortcutManager::class.java) ?: return@runCatching false
      if (!launcher.isRequestPinShortcutSupported) return@runCatching false
      val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
        .setShortLabel(context.getString(R.string.chat_plugin_title))
        .setIcon(LauncherIcon.createWithResource(context, R.drawable.ic_chat_plugin))
        .setIntent(Intent(context, ChatShortcutActivity::class.java).setAction(ChatShortcutActivity.ACTION_OPEN))
        .build()
      val done = PendingIntent.getBroadcast(context, 0, Intent(context, ChatShortcutReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
      launcher.requestPinShortcut(shortcut, done.intentSender)
    }.getOrDefault(false)
    if (!requested) toast(R.string.chat_plugin_shortcut_unsupported)
  }

  val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    installing = false
    refresh()
    manager.clearPreparedApk()
    if (installed == ChatPluginManager.Installation.TRUSTED) {
      decline()
      promptPin = !pinned
    } else error = R.string.chat_plugin_install_incomplete
  }
  fun launchInstaller() {
    scope.launch {
      try {
        installer.launch(withContext(Dispatchers.IO) { manager.installerIntent() })
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (_: Exception) {
        installing = false
        manager.clearPreparedApk()
        error = R.string.chat_plugin_install_incomplete
      }
    }
  }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    if (context.packageManager.canRequestPackageInstalls()) launchInstaller()
    else {
      installing = false
      manager.clearPreparedApk()
      error = R.string.chat_plugin_install_permission
    }
  }
  LaunchedEffect(lifecycle, pinChanges) { if (lifecycle == Lifecycle.State.RESUMED) refresh() }
  LaunchedEffect(lifecycle, promptPin) {
    if (lifecycle == Lifecycle.State.RESUMED && promptPin) {
      promptPin = false
      refresh()
      if (!pinned) pin()
    }
  }

  return ChatPluginUi(
    offer = checked && !dismissed && installed != ChatPluginManager.Installation.TRUSTED,
    fallback = checked && installed == ChatPluginManager.Installation.TRUSTED && !pinned,
    busy = downloading || installing,
    progress = progress.takeIf { downloading },
    status = error ?: R.string.chat_plugin_signature_conflict.takeIf { installed == ChatPluginManager.Installation.CONFLICT },
    onInstall = {
      if (compatible && !downloading && !installing && installed == ChatPluginManager.Installation.ABSENT) {
        downloading = true
        progress = 0
        error = null
        scope.launch {
          try {
            manager.prepare { progress = it }
            downloading = false
            installing = true
            if (context.packageManager.canRequestPackageInstalls()) launchInstaller()
            else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)))
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (_: Exception) {
            installing = false
            manager.clearPreparedApk()
            error = R.string.chat_plugin_download_failed
          } finally {
            downloading = false
          }
        }
      }
    },
    onDecline = ::decline,
    onOpen = { if (!manager.open()) { refresh(); toast(R.string.chat_plugin_missing) } },
    onPin = ::pin,
  )
}

@Composable
internal fun ChatPluginOffer(ui: ChatPluginUi) {
  if (!ui.offer) return
  AlertDialog(
    onDismissRequest = { if (!ui.busy) ui.onDecline() },
    icon = { Icon(painterResource(R.drawable.ic_chat_plugin), contentDescription = null) },
    title = { Text(stringResource(R.string.chat_plugin_title)) },
    text = {
      Column {
        Text(stringResource(ui.status ?: R.string.chat_plugin_offer))
        ui.progress?.let { value -> LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.fillMaxWidth()) }
      }
    },
    confirmButton = {
      TextButton(onClick = ui.onInstall, enabled = !ui.busy && ui.status != R.string.chat_plugin_signature_conflict) {
        Text(stringResource(R.string.chat_plugin_try))
      }
    },
    dismissButton = { TextButton(onClick = ui.onDecline, enabled = !ui.busy) { Text(stringResource(R.string.chat_plugin_decline)) } },
  )
}

@Composable
internal fun ChatPluginTopAction(ui: ChatPluginUi, size: Int = 48) {
  if (!ui.fallback) return
  Box(Modifier.size(size.dp).clip(CircleShape).combinedClickable(
    role = Role.Button, onClick = ui.onOpen, onLongClick = ui.onPin,
    onLongClickLabel = stringResource(R.string.chat_plugin_pin),
  ), contentAlignment = Alignment.Center) {
    Icon(painterResource(R.drawable.ic_chat_plugin), contentDescription = stringResource(R.string.chat_plugin_action), modifier = Modifier.size(24.dp))
  }
}
