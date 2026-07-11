package com.android.zdtd.service.ui.nonroot

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.android.zdtd.service.nonroot.NonRootBinaryInstaller
import com.android.zdtd.service.nonroot.NonRootPaths
import com.android.zdtd.service.nonroot.NonRootVpnService
import com.android.zdtd.service.nonroot.RuntimeModeStore
import java.io.File

@Composable
fun NonRootShell() {
    val context = LocalContext.current
    val store = remember { RuntimeModeStore(context) }
    var running by remember { mutableStateOf(store.isNonRootRunning()) }
    var status by remember { mutableStateOf("Базовый режим готов к проверке") }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            ContextCompat.startForegroundService(context, NonRootVpnService.startIntent(context))
            running = true
            status = "Запуск VpnService → tun2socks → t2s"
        } else {
            status = "VPN-разрешение не выдано"
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Security, contentDescription = null)
                Column {
                    Text("ZDT-D Non-root", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Ограниченный режим: VpnService → tun2socks → t2s", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Статус", fontWeight = FontWeight.SemiBold)
                    Text(status)
                    Text("Текущий запуск использует t2s --non-root-mode и direct-only backend. Поддержку отдельных бинарников добавим после проверки базовой цепочки.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        runCatching { NonRootBinaryInstaller(context).ensureBaseBinaries() }
                            .onSuccess { status = "Бинарники подготовлены: ${File(NonRootPaths(context).bin, "tun2socks").absolutePath}" }
                            .onFailure { status = "Ошибка подготовки бинарников: ${it.message ?: it}" }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Подготовить") }
                Button(
                    onClick = {
                        val prepare = VpnService.prepare(context)
                        if (prepare != null) {
                            vpnPermissionLauncher.launch(prepare)
                        } else {
                            ContextCompat.startForegroundService(context, NonRootVpnService.startIntent(context))
                            running = true
                            status = "Запуск VpnService → tun2socks → t2s"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                ) {
                    Icon(Icons.Filled.Power, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Старт")
                }
            }

            OutlinedButton(
                onClick = {
                    val intent = NonRootVpnService.stopIntent(context)
                    if (Build.VERSION.SDK_INT >= 26) context.startService(intent) else context.startService(intent)
                    running = false
                    status = "Остановка запрошена"
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = running,
            ) { Text("Остановить") }

            OutlinedButton(
                onClick = {
                    store.switchToRootMode()
                    context.startActivity(Intent(context, com.android.zdtd.service.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Вернуться в root-режим") }
        }
    }
}
