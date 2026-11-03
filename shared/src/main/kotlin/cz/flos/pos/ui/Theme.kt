package cz.flos.pos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

// Flos palette: violet with a warm coral accent and a mint for "paid / OK" states.
val FlosViolet = Color(0xFF5B4BDB)
val FlosCoral = Color(0xFFFF6B5A)
val FlosMint = Color(0xFF12B886)

private val light = lightColorScheme(
    primary = FlosViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E2FF),
    onPrimaryContainer = Color(0xFF1E1564),
    secondary = FlosCoral,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1DC),
    onSecondaryContainer = Color(0xFF5C1209),
    tertiary = FlosMint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F5E8),
    onTertiaryContainer = Color(0xFF00382A),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF181A24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181A24),
    surfaceVariant = Color(0xFFECEEF6),
    onSurfaceVariant = Color(0xFF5A5F73),
    surfaceContainer = Color(0xFFF0F1F8),
    surfaceContainerHigh = Color(0xFFEAEBF4),
    outline = Color(0xFFC9CCDA),
    outlineVariant = Color(0xFFE1E3EE),
    error = Color(0xFFD9364C),
)

private val dark = darkColorScheme(
    primary = Color(0xFFB3A9FF),
    onPrimary = Color(0xFF241A73),
    primaryContainer = Color(0xFF3E31B8),
    onPrimaryContainer = Color(0xFFE6E2FF),
    secondary = Color(0xFFFF9A8C),
    onSecondary = Color(0xFF5C1209),
    tertiary = Color(0xFF5FE0B3),
    onTertiary = Color(0xFF00382A),
    background = Color(0xFF111219),
    onBackground = Color(0xFFE4E5EE),
    surface = Color(0xFF181A23),
    onSurface = Color(0xFFE4E5EE),
    surfaceVariant = Color(0xFF262938),
    onSurfaceVariant = Color(0xFFB4B8CB),
    surfaceContainer = Color(0xFF1E202B),
    surfaceContainerHigh = Color(0xFF252835),
    outline = Color(0xFF494D60),
    outlineVariant = Color(0xFF33364A),
    error = Color(0xFFFF8A98),
)

/** Rounded everywhere: tiles, cards, dialogs and buttons. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Category colours offered in the product editor. */
val TilePalette: List<Long> = listOf(
    0xFF6C5CE7L, // violet
    0xFF12B886L, // mint
    0xFFFF6B5AL, // coral
    0xFF2F80EDL, // blue
    0xFFF7B731L, // amber
    0xFFE84393L, // pink
    0xFF00B8D4L, // cyan
    0xFF8E6E53L, // mocha
    0xFF2D3142L, // graphite
)

/** Black or white text, whichever reads better on [background]. */
fun contentColorOn(background: Color): Color = if (background.luminance() > 0.55f) Color(0xFF1B1B1F) else Color.White

@Composable
fun FlosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = Typography(), shapes = shapes, content = content)
}
