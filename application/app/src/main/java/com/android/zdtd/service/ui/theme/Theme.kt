package com.android.zdtd.service.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Bg = Color(0xFF050608)
private val Bg2 = Color(0xFF070812)
private val Red = Color(0xFFFF2A3D)
private val Blue = Color(0xFF2AA6FF)
private val Yellow = Color(0xFFFFD12A)

private val DarkScheme = darkColorScheme(
  primary = Red,
  secondary = Blue,
  tertiary = Yellow,
  background = Bg,
  surface = Bg2,
  onPrimary = Color.White,
  onSecondary = Color.White,
  onTertiary = Color.Black,
  onBackground = Color(0xFFEEEEEE),
  onSurface = Color(0xFFEEEEEE),
)

@Composable
fun ZdtdTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = DarkScheme,
    typography = MaterialTheme.typography,
    content = content,
  )
}
