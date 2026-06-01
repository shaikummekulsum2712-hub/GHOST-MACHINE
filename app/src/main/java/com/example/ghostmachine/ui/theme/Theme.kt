package com.example.ghostmachine.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GhostDarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = TextPrimary,
    primaryContainer = AccentBlueDim,
    onPrimaryContainer = TextPrimary,
    secondary = AccentIndigo,
    onSecondary = TextPrimary,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DividerColor,
    outlineVariant = AiBubbleBorder,
    error = StatusError,
    onError = Color.White
)

@Composable
fun GHOSTMACHINETheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GhostDarkColorScheme,
        typography = Typography,
        content = content
    )
}