package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun EnabledCard(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
          "Apply after stop/start",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
      Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
  }
}
