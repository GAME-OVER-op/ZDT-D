package com.android.zdtd.service.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * App-wide theme mode selectable by the user in Settings.
 *
 * Persisted as a lowercase string ("system" | "light" | "dark") in [com.android.zdtd.service.RootConfigManager].
 */
enum class ZdtdThemeMode {
  SYSTEM,
  LIGHT,
  DARK;

  fun storageValue(): String = when (this) {
    SYSTEM -> "system"
    LIGHT -> "light"
    DARK -> "dark"
  }

  companion object {
    fun fromStorage(value: String?): ZdtdThemeMode = when (value?.trim()?.lowercase()) {
      "light" -> LIGHT
      "dark" -> DARK
      else -> SYSTEM
    }
  }
}

// ---------------------------------------------------------------------------
// Brand palette
// ZDT-D identity accents are preserved across both schemes: red primary,
// blue secondary, yellow tertiary. The neutrals follow Material 3 tonal
// guidance so the UI reads as a modern "Google style" surface system.
// ---------------------------------------------------------------------------

// Brand seeds
private val BrandRed = Color(0xFFFF2A3D)
private val BrandRedDeep = Color(0xFFC11623)
private val BrandBlue = Color(0xFF2AA6FF)
private val BrandBlueDeep = Color(0xFF0A6FC2)
private val BrandYellow = Color(0xFFFFD12A)
private val BrandYellowDeep = Color(0xFF8A6D00)

// ----- Dark scheme -----
private val DarkScheme = darkColorScheme(
  primary = BrandRed,
  onPrimary = Color(0xFF2A0407),
  primaryContainer = Color(0xFF7A1019),
  onPrimaryContainer = Color(0xFFFFD9DC),

  secondary = BrandBlue,
  onSecondary = Color(0xFF00253D),
  secondaryContainer = Color(0xFF124A73),
  onSecondaryContainer = Color(0xFFCDE7FF),

  tertiary = BrandYellow,
  onTertiary = Color(0xFF3A2E00),
  tertiaryContainer = Color(0xFF5E4A00),
  onTertiaryContainer = Color(0xFFFFE9A6),

  error = Color(0xFFFFB4AB),
  onError = Color(0xFF690005),
  errorContainer = Color(0xFF93000A),
  onErrorContainer = Color(0xFFFFDAD6),

  background = Color(0xFF0B0D10),
  onBackground = Color(0xFFE3E2E6),
  surface = Color(0xFF121419),
  onSurface = Color(0xFFE3E2E6),
  surfaceVariant = Color(0xFF42474E),
  onSurfaceVariant = Color(0xFFC2C7CF),
  surfaceTint = BrandRed,

  inverseSurface = Color(0xFFE3E2E6),
  inverseOnSurface = Color(0xFF1A1C20),
  inversePrimary = BrandRedDeep,

  outline = Color(0xFF8C9199),
  outlineVariant = Color(0xFF42474E),
  scrim = Color(0xFF000000),

  surfaceContainerLowest = Color(0xFF06080B),
  surfaceContainerLow = Color(0xFF14161B),
  surfaceContainer = Color(0xFF181A1F),
  surfaceContainerHigh = Color(0xFF22242A),
  surfaceContainerHighest = Color(0xFF2D2F35),
  surfaceBright = Color(0xFF383A40),
  surfaceDim = Color(0xFF0B0D10),
)

// ----- Light scheme -----
private val LightScheme = lightColorScheme(
  primary = BrandRedDeep,
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFFFDADC),
  onPrimaryContainer = Color(0xFF400008),

  secondary = BrandBlueDeep,
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFFCDE7FF),
  onSecondaryContainer = Color(0xFF001D32),

  tertiary = BrandYellowDeep,
  onTertiary = Color(0xFFFFFFFF),
  tertiaryContainer = Color(0xFFFFE08B),
  onTertiaryContainer = Color(0xFF241A00),

  error = Color(0xFFBA1A1A),
  onError = Color(0xFFFFFFFF),
  errorContainer = Color(0xFFFFDAD6),
  onErrorContainer = Color(0xFF410002),

  background = Color(0xFFFCFCFF),
  onBackground = Color(0xFF1A1C20),
  surface = Color(0xFFFCFCFF),
  onSurface = Color(0xFF1A1C20),
  surfaceVariant = Color(0xFFDDE2EB),
  onSurfaceVariant = Color(0xFF42474E),
  surfaceTint = BrandRedDeep,

  inverseSurface = Color(0xFF2F3036),
  inverseOnSurface = Color(0xFFF1F0F4),
  inversePrimary = BrandRed,

  outline = Color(0xFF72777F),
  outlineVariant = Color(0xFFC2C7CF),
  scrim = Color(0xFF000000),

  surfaceContainerLowest = Color(0xFFFFFFFF),
  surfaceContainerLow = Color(0xFFF4F4F8),
  surfaceContainer = Color(0xFFEEEEF2),
  surfaceContainerHigh = Color(0xFFE8E9ED),
  surfaceContainerHighest = Color(0xFFE2E3E7),
  surfaceBright = Color(0xFFFCFCFF),
  surfaceDim = Color(0xFFDBDBDF),
)

// Material 3 style rounded shapes (Google-like soft corners).
private val ZdtdShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(24.dp),
  extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Root theme wrapper.
 *
 * @param themeMode user-selected mode. SYSTEM follows the OS light/dark setting.
 */
@Composable
fun ZdtdTheme(
  themeMode: ZdtdThemeMode = ZdtdThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val useDark = when (themeMode) {
    ZdtdThemeMode.SYSTEM -> isSystemInDarkTheme()
    ZdtdThemeMode.LIGHT -> false
    ZdtdThemeMode.DARK -> true
  }
  MaterialTheme(
    colorScheme = if (useDark) DarkScheme else LightScheme,
    typography = MaterialTheme.typography,
    shapes = ZdtdShapes,
    content = content,
  )
}
