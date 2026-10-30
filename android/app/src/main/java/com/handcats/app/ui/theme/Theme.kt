package com.handcats.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Forest, onPrimary = Color.White, primaryContainer = Mint, onPrimaryContainer = Forest,
    secondary = Orange, secondaryContainer = Peach, onSecondaryContainer = Ink,
    background = Cream, onBackground = Ink, surface = Cream, onSurface = Ink,
    surfaceVariant = Mint, onSurfaceVariant = Muted, outline = Muted, outlineVariant = Line,
    error = Color(0xFFAC403B), errorContainer = Color(0xFFF9EAE5),
)
@Composable fun HandCatsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography, content = content)
}
