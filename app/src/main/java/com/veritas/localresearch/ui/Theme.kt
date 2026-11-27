package com.veritas.localresearch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val WarningYellowBg = Color(0xFFFFE082)
val WarningYellowText = Color(0xFF3E2723)
val VerifiedGreen = Color(0xFF2E7D32)
val CitationBlue = Color(0xFF1565C0)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80CBC4),
    surface = Color(0xFF121418),
    background = Color(0xFF0E1013)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D47A1),
    secondary = Color(0xFF00695C),
    surface = Color(0xFFFDFEFE),
    background = Color(0xFFF4F6F9)
)

@Composable
fun VeritasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
