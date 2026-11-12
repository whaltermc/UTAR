package com.utar.runner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UtarColors = darkColorScheme(
    primary = Color(0xFFE9C7A3),
    onPrimary = Color(0xFF2A1A0B),
    primaryContainer = Color(0xFF3A2A18),
    onPrimaryContainer = Color(0xFFF6DFC6),
    secondary = Color(0xFF8FD6CE),
    onSecondary = Color(0xFF08251F),
    secondaryContainer = Color(0xFF16332F),
    onSecondaryContainer = Color(0xFFCDEFE9),
    background = Color(0xFF0A0D13),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF0F141C),
    onSurface = Color(0xFFE8E6E1),
    surfaceVariant = Color(0xFF161D28),
    onSurfaceVariant = Color(0xFFA9B2C0),
    outline = Color(0xFF3A4452),
    outlineVariant = Color(0xFF232B36),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF3A0909),
)

@Composable
fun UtarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UtarColors,
        typography = Typography(),
        content = content,
    )
}
