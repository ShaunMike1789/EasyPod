package com.smgray.easypod.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EasyPodLightColors = lightColorScheme(
    primary = Color(0xFF173F3A),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFB77A00),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF4F635F),
    background = Color(0xFFF8F7F3),
    surface = Color(0xFFF8F7F3),
    surfaceVariant = Color(0xFFE6E8E2),
    onSurface = Color(0xFF1A1C1B),
)

private val EasyPodDarkColors = darkColorScheme(
    primary = Color(0xFF9FD4CA),
    onPrimary = Color(0xFF003731),
    secondary = Color(0xFFFFBA40),
    onSecondary = Color(0xFF432C00),
    background = Color(0xFF111413),
    surface = Color(0xFF111413),
    surfaceVariant = Color(0xFF3F4946),
)

@Composable
fun EasyPodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) EasyPodDarkColors else EasyPodLightColors,
        content = content,
    )
}

