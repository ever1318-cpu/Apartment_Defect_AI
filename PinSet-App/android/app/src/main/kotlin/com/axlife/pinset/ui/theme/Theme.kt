package com.axlife.pinset.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDark,
    secondary = Secondary,
    onSecondary = Color.White,
    background = BgApp,
    onBackground = TextMain,
    surface = SurfaceColor,
    onSurface = TextMain,
    surfaceVariant = PrimaryLight,
    onSurfaceVariant = TextSub,
    error = Danger,
    onError = Color.White,
    outline = BorderLine
)

@Composable
fun PinSetTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
