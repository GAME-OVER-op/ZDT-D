package com.android.zdtd.service.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R

/**
 * Shared root-only prompt for any action that requires the optional sing-box-lx binary.
 * Import and deep-link flows reuse this instead of inventing separate missing-component UI.
 */
@Composable
internal fun SingBoxLxInstallRequiredDialog(
  onDismiss: () -> Unit,
  onOpenOptionalTools: () -> Unit,
) {
  val buttonShape = RoundedCornerShape(18.dp)
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
    title = { Text(stringResource(R.string.sing_box_lx_install_required_title)) },
    text = { Text(stringResource(R.string.sing_box_lx_install_required_message)) },
    confirmButton = {
      Button(
        onClick = onOpenOptionalTools,
        modifier = Modifier.tvFocusHighlight(buttonShape),
      ) {
        Text(stringResource(R.string.sing_box_lx_open_optional_tools))
      }
    },
    dismissButton = {
      OutlinedButton(
        onClick = onDismiss,
        modifier = Modifier.tvFocusHighlight(buttonShape),
      ) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}
