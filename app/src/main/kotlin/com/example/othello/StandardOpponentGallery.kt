package com.example.othello

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaSpacing

@Composable
internal fun StandardOpponentSelectionPanel(
    progress: StandardAiProgress,
    selectedLevel: StandardAiLevel,
    onLevelSelected: (StandardAiLevel) -> Unit,
) {
    val pack = StandardOpponentPacks.animal
    val nextLevel = progress.nextChallenge()
    val nextOpponent = pack.opponent(nextLevel)
    val teaserLevel = progress.highestUnlockedLevel.next()
    val clearedCount = progress.clearedLevels.size
    val totalCount = StandardAiLevel.entries.size

    Column(
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
    ) {
        Text(
            text = appString(R.string.standard_ai_pack_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = appString(pack.titleRes),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = appString(pack.supportingTextRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = appString(R.string.standard_ai_collection_progress, clearedCount, totalCount),
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(
            progress = clearedCount.toFloat() / totalCount.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (!progress.conquered) {
            Text(
                text = appString(R.string.standard_ai_next_challenge),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = appString(
                    R.string.standard_ai_opponent_level_name,
                    nextLevel.value,
                    appString(nextOpponent.nameRes),
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = appString(
                    when (nextLevel) {
                        StandardAiLevel.LV4 -> R.string.standard_ai_next_reward_wild
                        StandardAiLevel.LV8 -> R.string.standard_ai_next_reward_conquer
                        else -> R.string.standard_ai_next_reward_opponent
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        StandardOpponentGroup(
            title = appString(R.string.standard_ai_group_basic),
            opponents = pack.opponents.take(4),
            progress = progress,
            selectedLevel = selectedLevel,
            nextLevel = nextLevel,
            teaserLevel = teaserLevel,
            onLevelSelected = onLevelSelected,
        )
        StandardOpponentGroup(
            title = appString(R.string.standard_ai_group_serious),
            opponents = pack.opponents.drop(4),
            progress = progress,
            selectedLevel = selectedLevel,
            nextLevel = nextLevel,
            teaserLevel = teaserLevel,
            onLevelSelected = onLevelSelected,
        )
    }
}

@Composable
private fun StandardOpponentGroup(
    title: String,
    opponents: List<StandardOpponentUi>,
    progress: StandardAiProgress,
    selectedLevel: StandardAiLevel,
    nextLevel: StandardAiLevel,
    teaserLevel: StandardAiLevel?,
    onLevelSelected: (StandardAiLevel) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        opponents.chunked(OPPONENTS_PER_ROW).forEach { rowOpponents ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
            ) {
                rowOpponents.forEach { opponent ->
                    StandardOpponentCard(
                        opponent = opponent,
                        progress = progress,
                        selected = opponent.level == selectedLevel,
                        next = opponent.level == nextLevel,
                        teaser = opponent.level == teaserLevel,
                        onClick = { onLevelSelected(opponent.level) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(OPPONENTS_PER_ROW - rowOpponents.size) {
                    Column(Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
private fun StandardOpponentCard(
    opponent: StandardOpponentUi,
    progress: StandardAiProgress,
    selected: Boolean,
    next: Boolean,
    teaser: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlocked = progress.isUnlocked(opponent.level)
    val cleared = progress.isCleared(opponent.level)
    val visibleName = if (unlocked) appString(opponent.nameRes) else appString(R.string.standard_ai_opponent_unknown)
    val statusRes = when {
        !unlocked && teaser -> R.string.standard_ai_opponent_status_teaser
        !unlocked -> R.string.standard_ai_opponent_status_locked
        cleared -> R.string.standard_ai_opponent_status_cleared
        next -> R.string.standard_ai_opponent_status_next
        selected -> R.string.standard_ai_opponent_status_selected
        else -> R.string.standard_ai_opponent_status_available
    }
    val containerColor = if (selected && unlocked) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        ChanrivaColors.surfaceElevated
    }

    Card(
        onClick = onClick,
        enabled = unlocked,
        modifier = modifier.testTag("standard-ai-opponent-${opponent.level.value}"),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ChanrivaSpacing.control),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                colorFilter = if (unlocked) null else ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .size(124.dp)
                    .graphicsLayer {
                        alpha = when {
                            unlocked -> 1f
                            teaser -> 0.62f
                            else -> 0.28f
                        }
                    },
            )
            Text(
                text = appString(
                    R.string.standard_ai_opponent_level_name,
                    opponent.level.value,
                    visibleName,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = appString(statusRes),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected && unlocked) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private const val OPPONENTS_PER_ROW = 2
