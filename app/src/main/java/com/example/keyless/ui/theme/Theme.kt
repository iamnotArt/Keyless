package com.example.keyless.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KeylessColorScheme = lightColorScheme(
    primary = SoftOrange,
    onPrimary = Color.White,
    primaryContainer = SoftOrangeContainer,
    onPrimaryContainer = InkText,
    secondary = DeepOrange,
    onSecondary = Color.White,
    background = WhiteBackground,
    onBackground = InkText,
    surface = WhiteBackground,
    onSurface = InkText,
    surfaceVariant = CreamSurface,
    onSurfaceVariant = InkText,
    outline = Color(0xFFD8C7B6)
)

@Composable
fun KeylessTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KeylessColorScheme,
        typography = Typography,
        content = content
    )
}
