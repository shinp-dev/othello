package com.example.othello

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.designsystem.ChanrivaColors
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
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
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
    val visibleName = if (unlocked) {
        appString(opponent.nameRes)
    } else {
        appString(R.string.standard_ai_opponent_unknown)
    }

    Card(
        onClick = onClick,
        enabled = unlocked,
        modifier = modifier
            .testTag("standard-ai-level-${opponent.level.value}")
            .graphicsLayer {
                alpha = if (centered) 1f else 0.64f
            },
        colors = CardDefaults.cardColors(
            containerColor = if (centered && unlocked) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                ChanrivaColors.surfaceElevated
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (centered) 340.dp else 270.dp)
                .padding(if (centered) ChanrivaSpacing.section else 8.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
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
                        .size(if (centered) 220.dp else 92.dp)
                        .graphicsLayer {
                            alpha = if (unlocked) 1f else 0.30f
                        },
                )

                Text(
                    text = appString(
                        R.string.standard_ai_opponent_level_name,
                        opponent.level.value,
                        visibleName,
                    ),
                    modifier = Modifier.padding(top = if (centered) 16.dp else 8.dp),
                    style = if (centered) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    fontWeight = if (centered) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }

            when {
                !unlocked -> {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(if (centered) 44.dp else 32.dp)
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
                            modifier = Modifier.padding(if (centered) 10.dp else 7.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                cleared -> {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(if (centered) 44.dp else 32.dp)
                            .testTag("standard-ai-state-${opponent.level.value}"),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = appString(R.string.standard_ai_opponent_status_cleared),
                            modifier = Modifier.padding(if (centered) 8.dp else 6.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private const val SIDE_WEIGHT = 0.22f
private const val CENTER_WEIGHT = 0.56f
private const val SWIPE_THRESHOLD_PX = 72f
