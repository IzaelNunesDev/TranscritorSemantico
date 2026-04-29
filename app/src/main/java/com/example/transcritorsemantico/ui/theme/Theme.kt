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
    surfaceContainerHigh = Color(0xFF163040),
    onSurfaceVariant = Color(0xFFA9C2CC),
    secondaryContainer = Color(0xFF20485B),
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
    onSurfaceVariant = Color(0xFF51626D),
    secondaryContainer = Color(0xFFD5F5F4),
    onSecondaryContainer = Color(0xFF083337),
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
