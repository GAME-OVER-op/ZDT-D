package com.android.zdtd.service.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/** Shared TV/D-pad focus treatment for root screens; matches the non-root visual language. */
internal fun Modifier.tvFocusHighlight(
  shape: Shape,
  enabled: Boolean = true,
): Modifier = this.nonRootFocusHighlight(shape, enabled)
