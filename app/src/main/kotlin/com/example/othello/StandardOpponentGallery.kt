package com.example.othello

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.designsystem.ChanrivaSpacing

@Composable
internal fun StandardOpponentSelectionPanel(
    progress: StandardAiProgress,
    selectedLevel: StandardAiLevel,
    onLevelSelected: (StandardAiLevel) -> Unit,
    onStart: () -> Unit,
) {
    val pack = StandardOpponentPacks.animal
    val selectedIndex = pack.opponents.indexOfFirst { it.level == selectedLevel }.coerceAtLeast(0)
    val previous = pack.opponents.getOrNull(selectedIndex - 1)
    val current = pack.opponents[selectedIndex]
    val next = pack.opponents.getOrNull(selectedIndex + 1)
    var dragDistance = remember(selectedLevel) { 0f }

    fun selectIfUnlocked(opponent: StandardOpponentUi?) {
        if (opponent != null && progress.isUnlocked(opponent.level)) {
            onLevelSelected(opponent.level)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = appString(pack.titleRes),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .testTag("standard-ai-opponent-carousel")
                .pointerInput(selectedLevel, progress.highestUnlockedLevel) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragDistance += dragAmount
                        },
                        onDragEnd = {
                            when {
                                dragDistance <= -SWIPE_THRESHOLD_PX -> selectIfUnlocked(next)
                                dragDistance >= SWIPE_THRESHOLD_PX -> selectIfUnlocked(previous)
                            }
                            dragDistance = 0f
                        },
                        onDragCancel = { dragDistance = 0f },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (previous != null) {
                StandardOpponentCard(
                    opponent = previous,
                    progress = progress,
                    centered = false,
                    onClick = { selectIfUnlocked(previous) },
                    modifier = Modifier
                        .weight(SIDE_WEIGHT)
                        .height(270.dp),
                )
            } else {
                Box(Modifier.weight(SIDE_WEIGHT))
            }

            StandardOpponentCard(
                opponent = current,
                progress = progress,
                centered = true,
                onClick = onStart,
                modifier = Modifier
                    .weight(CENTER_WEIGHT)
                    .height(340.dp),
            )

            if (next != null) {
                StandardOpponentCard(
                    opponent = next,
                    progress = progress,
                    centered = false,
                    onClick = { selectIfUnlocked(next) },
                    modifier = Modifier
                        .weight(SIDE_WEIGHT)
                        .height(270.dp),
                )
            } else {
                Box(Modifier.weight(SIDE_WEIGHT))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("standard-ai-strength-guide"),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = appString(R.string.standard_ai_strength_weaker),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = appString(R.string.standard_ai_strength_stronger),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StandardOpponentCard(
    opponent: StandardOpponentUi,
    progress: StandardAiProgress,
    centered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlocked = progress.isUnlocked(opponent.level)
    val cleared = progress.isCleared(opponent.level)
    val cardColor = if (centered) CENTER_CARD_COLOR else SIDE_CARD_COLOR
    val labelColor = if (centered) CENTER_LABEL_COLOR else SIDE_LABEL_COLOR

    Card(
        onClick = onClick,
        enabled = unlocked,
        modifier = modifier
            .testTag("standard-ai-level-${opponent.level.value}")
            .graphicsLayer {
                alpha = if (centered) 1f else 0.72f
            },
        shape = RoundedCornerShape(if (centered) 22.dp else 18.dp),
        border = if (centered) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else {
            BorderStroke(1.dp, SIDE_CARD_BORDER)
        },
        colors = CardDefaults.cardColors(
            containerColor = cardColor,
            disabledContainerColor = cardColor,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Image(
                painter = painterResource(opponent.winDrawableRes),
                contentDescription = if (unlocked) {
                    appString(
                        R.string.standard_ai_opponent_named_description,
                        appString(opponent.nameRes),
                        opponent.level.value,
                    )
                } else {
                    appString(R.string.standard_ai_locked_opponent_description, opponent.level.value)
                },
                colorFilter = if (unlocked) {
                    null
                } else {
                    ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = if (centered) 42.dp else 24.dp)
                    .size(if (centered) 216.dp else 94.dp)
                    .graphicsLayer {
                        alpha = if (unlocked) 1f else 0.28f
                    },
            )

            Text(
                text = "Lv${opponent.level.value}",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(if (centered) 14.dp else 9.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (centered) 0.82f else 0.68f,
                ),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (centered) 76.dp else 48.dp),
                color = labelColor,
            ) {
                if (centered) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = appString(opponent.nameRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            when {
                !unlocked -> {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(if (centered) 12.dp else 8.dp)
                            .size(if (centered) 36.dp else 28.dp)
                            .testTag("standard-ai-state-${opponent.level.value}"),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = appString(
                                R.string.standard_ai_locked_opponent_description,
                                opponent.level.value,
                            ),
                            modifier = Modifier.padding(if (centered) 8.dp else 6.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                cleared -> {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(if (centered) 12.dp else 8.dp)
                            .size(if (centered) 36.dp else 28.dp)
                            .testTag("standard-ai-state-${opponent.level.value}"),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = appString(R.string.standard_ai_opponent_status_cleared),
                            modifier = Modifier.padding(if (centered) 7.dp else 5.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private val CENTER_CARD_COLOR = Color(0xFF1C232C)
private val CENTER_LABEL_COLOR = Color(0xFF161C23)
private val SIDE_CARD_COLOR = Color(0xFF141A21)
private val SIDE_LABEL_COLOR = Color(0xFF10151B)
private val SIDE_CARD_BORDER = Color(0xFF27313C)
private const val SIDE_WEIGHT = 0.22f
private const val CENTER_WEIGHT = 0.56f
private const val SWIPE_THRESHOLD_PX = 72f
