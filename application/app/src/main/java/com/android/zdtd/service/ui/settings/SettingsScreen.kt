package com.android.zdtd.service.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.ui.rememberIsTelevision

/**
 * Full-screen settings surface rendered inside the app's existing Compose window.
 *
 * Keeping Settings in the same window avoids OEM-specific Dialog/Window compositor
 * overhead while preserving the dedicated top app bar and back navigation.
 */
@Composable
fun SettingsScreen(
  onDismiss: () -> Unit,
  loading: Boolean,
  showBackButton: Boolean = true,
  content: @Composable () -> Unit,
) {
  val television = rememberIsTelevision()
  val backFocusRequester = remember { FocusRequester() }

  LaunchedEffect(television) {
    if (television) backFocusRequester.requestFocus()
  }

  // No Scaffold/TopAppBar: the settings surface is borderless, like every other
  // screen. The back control floats above the content as a round button.
  Box(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = 64.dp),
    ) {
      if (loading) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator()
            Text(
              text = stringResource(R.string.common_loading),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      } else {
        content()
      }
    }

    if (showBackButton) {
    IconButton(
      onClick = onDismiss,
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(12.dp)
        .size(46.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .focusRequester(backFocusRequester),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = stringResource(R.string.common_back),
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }
    }
  }
}
