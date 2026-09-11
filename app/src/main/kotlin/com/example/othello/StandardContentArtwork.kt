package com.example.othello

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun StandardContentArtwork(
    entry: StandardContentEntry,
    obtained: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val customBitmap = remember(entry.imageFile?.absolutePath, obtained) {
        if (obtained) {
            entry.imageFile
                ?.takeIf { it.isFile }
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?.asImageBitmap()
        } else {
            null
        }
    }
    val categoryBitmap = remember(entry.card.type) {
        runCatching {
            BitmapFactory.decodeResource(context.resources, entry.card.type.iconRes)
                ?.asImageBitmap()
        }.getOrNull()
    }
    val shape = RoundedCornerShape(20.dp)
    val colors = entry.card.rarity.visualColors

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(colors.highlight, colors.background),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!obtained) {
            androidx.compose.material3.Text(
                text = "?",
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                color = colors.foreground.copy(alpha = 0.68f),
            )
        } else if (customBitmap != null) {
            Image(
                bitmap = customBitmap,
                contentDescription = entry.card.title,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (categoryBitmap != null) {
            Image(
                bitmap = categoryBitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
            )
        } else {
            androidx.compose.material3.Text(
                text = "?",
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                color = colors.foreground.copy(alpha = 0.68f),
            )
        }
    }
}

internal val StandardContentCardType.iconRes: Int
    get() = when (this) {
        StandardContentCardType.TRIVIA -> R.drawable.standard_content_icon_trivia
        StandardContentCardType.BOOK -> R.drawable.standard_content_icon_book
        StandardContentCardType.PERSON -> R.drawable.standard_content_icon_person
        StandardContentCardType.HISTORY -> R.drawable.standard_content_icon_history
        StandardContentCardType.COLLAB -> R.drawable.standard_content_icon_collab
    }

internal data class StandardContentRarityVisualColors(
    val background: Color,
    val highlight: Color,
    val foreground: Color,
)

internal val StandardContentRarity.visualColors: StandardContentRarityVisualColors
    get() = when (this) {
        StandardContentRarity.COMMON -> StandardContentRarityVisualColors(
            background = Color(0xFF173D32),
            highlight = Color(0xFF2D6A53),
            foreground = Color(0xFFE4F3EB),
        )
        StandardContentRarity.RARE -> StandardContentRarityVisualColors(
            background = Color(0xFF343B45),
            highlight = Color(0xFF687381),
            foreground = Color(0xFFF0F2F5),
        )
        StandardContentRarity.SPECIAL -> StandardContentRarityVisualColors(
            background = Color(0xFF5A4315),
            highlight = Color(0xFFB08A32),
            foreground = Color(0xFFFFF2C7),
        )
    }
