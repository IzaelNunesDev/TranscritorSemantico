package com.example.transcritorsemantico.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = SeaGlass,
    onPrimary = InkNight,
    secondary = OceanTeal,
    onSecondary = InkNight,
    tertiary = Color(0xFFFFC48A),
    background = InkNight,
    onBackground = Color(0xFFEAF4F8),
    surface = DeepSurface,
    onSurface = Color(0xFFEAF4F8),
    surfaceContainerHigh = Color(0xFF173142),
    onSurfaceVariant = Color(0xFFA9C2CC),
    secondaryContainer = Color(0xFF23495C),
    onSecondaryContainer = Color(0xFFD8F5F7),
)

private val LightColorScheme = lightColorScheme(
    primary = SlateBlue,
    onPrimary = Color.White,
    secondary = OceanTeal,
    onSecondary = Color.White,
    tertiary = Ember,
    background = SandMist,
    onBackground = InkNight,
    surface = Color.White,
    onSurface = InkNight,
    surfaceContainerHigh = WarmCloud,
    onSurfaceVariant = Graphite.copy(alpha = 0.72f),
    secondaryContainer = IcePanel,
    onSecondaryContainer = Color(0xFF11344A),
)

@Composable
fun TranscritorSemanticoTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
