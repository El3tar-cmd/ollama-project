package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OllamaDarkScheme = darkColorScheme(
    primary          = OllamaGreen,
    onPrimary        = OllamaBg,
    primaryContainer = OllamaGreenDark,
    secondary        = OllamaBlue,
    onSecondary      = OllamaBg,
    tertiary         = OllamaPurple,
    background       = OllamaBg,
    onBackground     = OllamaText,
    surface          = OllamaSurface,
    onSurface        = OllamaText,
    surfaceVariant   = OllamaCard,
    onSurfaceVariant = OllamaTextDim,
    outline          = OllamaBorder,
    error            = OllamaRed,
    onError          = OllamaBg,
)

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OllamaDarkScheme,
        typography  = Typography,
        content     = content
    )
}
