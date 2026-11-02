package cz.flos.pos.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FlosGreen = Color(0xFF0F3D3E)
val FlosGold = Color(0xFFF2C14E)

private val colors = lightColorScheme(
    primary = FlosGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E8E3),
    onPrimaryContainer = FlosGreen,
    secondary = Color(0xFFC2603A),
    onSecondary = Color.White,
    tertiary = FlosGold,
    background = Color(0xFFF7F4EE),
    surface = Color(0xFFFFFCF7),
    surfaceVariant = Color(0xFFECE6DC),
)

@Composable
fun FlosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
