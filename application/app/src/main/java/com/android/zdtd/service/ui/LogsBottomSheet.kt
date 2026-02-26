package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.LogLine
import com.android.zdtd.service.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsBottomSheet(
  logs: List<LogLine>,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.logs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
          Button(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
      }
      Spacer(Modifier.height(12.dp))
      if (logs.isEmpty()) {
        Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.height(22.dp))
      } else {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        ) {
          items(logs, key = { it.ts + it.msg }) { l ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))) {
              Column(Modifier.padding(12.dp)) {
                Text("${l.ts} • ${l.level}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(4.dp))
                Text(l.msg)
              }
            }
          }
        }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}