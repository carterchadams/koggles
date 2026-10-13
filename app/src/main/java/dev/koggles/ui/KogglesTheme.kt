package dev.koggles.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The branding's neon-on-black scheme (brand-images phase): black surfaces, the swirl's cyan, magenta and gold
 * as the accents. Fixed, not system-theme dependent. Every role a widget the app composes reads in Material 3
 * 1.4.0 is set here (FilterChip and Slider read secondaryContainer, an unchecked Switch reads
 * surfaceContainerHighest and outline, OutlinedButton reads outlineVariant); surfaceTint is black so tonal
 * elevation never greys the panel.
 */
val KogglesColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF003C44),
    onPrimaryContainer = Color(0xFF00E5FF),
    inversePrimary = Color(0xFF007A8A),
    secondary = Color(0xFFFF2BD6),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF3A0A33),
    onSecondaryContainer = Color(0xFFFFD0F3),
    tertiary = Color(0xFFFFC400),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF3D2E00),
    onTertiaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF101018),
    onSurfaceVariant = Color(0xFFB0BEC5),
    surfaceTint = Color(0xFF000000),
    inverseSurface = Color(0xFFE6E6F0),
    inverseOnSurface = Color(0xFF000000),
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF5C0000),
    onErrorContainer = Color(0xFFFFB4AB),
    outline = Color(0x8000E5FF),
    outlineVariant = Color(0x4000E5FF),
    scrim = Color(0xFF000000),
    surfaceContainerHighest = Color(0xFF1A1A24),
    surfaceContainerHigh = Color(0xFF14141C),
    surfaceContainer = Color(0xFF0E0E14),
    surfaceContainerLow = Color(0xFF080810),
    surfaceContainerLowest = Color(0xFF000000),
)

@Composable
fun KogglesTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = KogglesColors, content = content)
