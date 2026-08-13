package com.example.othello.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Shared visual language for the quiet, analysis-first Chanriva UI. */
object ChanrivaColors {
    val background = Color(0xFF090B10)
    val surface = Color(0xFF0D1016)
    val surfaceElevated = Color(0xFF11161E)
    val surfaceVariant = Color(0xFF171C24)
    val outline = Color(0xFF303844)
    val outlineStrong = Color(0xFF485362)
    val textPrimary = Color(0xFFF1F3F5)
    val textSecondary = Color(0xFFABB4BF)
    val textDisabled = Color(0xFF68727E)
    val accent = Color(0xFFE5484D)
    val accentPressed = Color(0xFFB9363D)
    val accentSoft = Color(0xFF61262E)
    val danger = Color(0xFFFF7B82)

    // The board is intentionally a little lighter than the app background.
    val board = Color(0xFF202B38)
    val boardGrid = Color(0xFF445262)
    val blackDisc = Color(0xFF101419)
    val whiteDisc = Color(0xFFF2F0EA)
    val legalMove = Color(0xFF9CA8B5)
    val evaluation = accent
}

object ChanrivaSpacing {
    val page = 20.dp
    val section = 16.dp
    val card = 14.dp
    val compact = 10.dp
    val control = 8.dp
}

private val ChanrivaTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
    )
}

private val ChanrivaColorScheme = darkColorScheme(
    primary = ChanrivaColors.accent,
    onPrimary = Color.White,
    primaryContainer = ChanrivaColors.accentSoft,
    onPrimaryContainer = ChanrivaColors.textPrimary,
    secondary = ChanrivaColors.textSecondary,
    onSecondary = ChanrivaColors.background,
    secondaryContainer = ChanrivaColors.surfaceVariant,
    onSecondaryContainer = ChanrivaColors.textPrimary,
    background = ChanrivaColors.background,
    onBackground = ChanrivaColors.textPrimary,
    surface = ChanrivaColors.surface,
    onSurface = ChanrivaColors.textPrimary,
    surfaceVariant = ChanrivaColors.surfaceVariant,
    onSurfaceVariant = ChanrivaColors.textSecondary,
    outline = ChanrivaColors.outline,
    outlineVariant = ChanrivaColors.outlineStrong,
    error = ChanrivaColors.danger,
    onError = Color(0xFF2A090C),
)

private val ChanrivaShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable
fun OthelloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ChanrivaColorScheme,
        typography = ChanrivaTypography,
        shapes = ChanrivaShapes,
        content = content,
    )
}
