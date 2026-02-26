package com.android.zdtd.service.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R

@Composable
fun DeleteModuleConfirmDialog(
  visible: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (!visible) return

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Image(
          painter = painterResource(R.drawable.zdtd_goodbye),
          contentDescription = stringResource(R.string.cd_goodbye_image),
          modifier = Modifier.size(220.dp),
        )

        Spacer(Modifier.height(12.dp))

        Text(
          text = stringResource(R.string.delete_module_title),
          style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(10.dp))

        Text(
          text = stringResource(R.string.delete_module_confirm_text),
          style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(12.dp))

        Text(
          text = stringResource(R.string.delete_module_warning_reboot),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )

        Spacer(Modifier.height(18.dp))

        Text(
          text = stringResource(R.string.delete_module_goodbye),
          style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.weight(1f))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.common_cancel))
          }

          Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.delete_module_yes))
          }
        }
      }
    }
  }
}


@Composable
fun DeleteModuleNextStepDialog(
  visible: Boolean,
  onDismiss: () -> Unit,
) {
  if (!visible) return

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Image(
          painter = painterResource(R.drawable.zdtd_goodbye),
          contentDescription = stringResource(R.string.cd_goodbye_image),
          modifier = Modifier.size(220.dp),
        )

        Spacer(Modifier.height(12.dp))

        Text(
          text = stringResource(R.string.delete_module_next_title),
          style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(12.dp))

        Text(
          text = stringResource(R.string.delete_module_next_body),
          style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(12.dp))

        Text(
          text = stringResource(R.string.delete_module_warning_reboot),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )

        Spacer(Modifier.weight(1f))

        Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.common_ok))
        }
      }
    }
  }
}


@Composable
fun DeleteModulePreparingDialog(
  visible: Boolean,
  errorText: String?,
  onDismiss: () -> Unit,
) {
  if (!visible && errorText == null) return

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        if (errorText == null) {
          CircularProgressIndicator()
          Spacer(Modifier.height(16.dp))
          Text(text = stringResource(R.string.delete_module_prepare_title), style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.height(10.dp))
          Text(text = stringResource(R.string.delete_module_prepare_body), style = MaterialTheme.typography.bodyMedium)
        } else {
          Text(text = stringResource(R.string.delete_module_prepare_error_title), style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.height(10.dp))
          Text(text = errorText, style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.height(18.dp))
          Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_ok))
          }
        }
      }
    }
  }
}
