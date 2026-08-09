package com.example.othello.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OthelloColors = lightColorScheme(
    primary = Color(0xFF146B4B),
    onPrimary = Color.White,
    secondary = Color(0xFF49665A),
    background = Color(0xFFF8FAF7),
    surface = Color.White,
)

@Composable
fun OthelloTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OthelloColors, typography = Typography(), content = content)
}
