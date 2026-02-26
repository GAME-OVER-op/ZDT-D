package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R

@Composable
fun EnabledCard(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
          stringResource(R.string.enabled_card_apply_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
      }
      Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
  }
}
