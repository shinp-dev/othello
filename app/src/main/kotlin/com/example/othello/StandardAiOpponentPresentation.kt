package com.example.othello

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.StandardAiLevel

internal enum class StandardAiHumanOutcome { WIN, LOSS, DRAW }

internal data class StandardAiResultPresentation(
    val outcome: StandardAiHumanOutcome,
    val firstClear: Boolean = false,
    val unlockedLevel: StandardAiLevel? = null,
    val conquered: Boolean = false,
    val winReward: StandardWinReward = StandardWinReward.NONE,
    val wildStageAwakened: Boolean = false,
)

@Composable
internal fun StandardAiIntroDialog(
    level: StandardAiLevel,
    onStart: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("standard-ai-intro-dialog"),
        onDismissRequest = {},
        title = {
            Text(appString(R.string.standard_ai_intro_title, level.value))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedOpponentImage(
                    drawableRes = level.opponentWinDrawable(),
                    level = level,
                    tag = "standard-ai-intro-image",
                )
                Text(
                    text = appString(R.string.standard_ai_new_opponent),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = onStart) {
                Text(appString(R.string.standard_ai_intro_start))
            }
        },
    )
}

@Composable
internal fun StandardAiResultDialog(
    level: StandardAiLevel,
    presentation: StandardAiResultPresentation,
    onDismiss: () -> Unit,
) {
    val title = when {
        presentation.wildStageAwakened -> appString(R.string.standard_ai_wild_stage_title)
        presentation.winReward == StandardWinReward.COMEBACK -> appString(R.string.standard_ai_comeback_title)
        presentation.winReward == StandardWinReward.CLUTCH -> appString(R.string.standard_ai_clutch_title)
        presentation.outcome == StandardAiHumanOutcome.WIN -> appString(R.string.standard_ai_result_win)
        presentation.outcome == StandardAiHumanOutcome.LOSS -> appString(R.string.standard_ai_opponent_wins)
        else -> appString(R.string.standard_ai_result_draw)
    }

    AlertDialog(
        modifier = Modifier.testTag("standard-ai-result-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedOpponentImage(
                    drawableRes = if (presentation.outcome == StandardAiHumanOutcome.WIN) {
                        level.opponentLoseDrawable()
                    } else {
                        level.opponentWinDrawable()
                    },
                    level = level,
                    tag = "standard-ai-result-image",
                    imageSize = if (presentation.unlockedLevel != null) 164.dp else 220.dp,
                )

                when (presentation.winReward) {
                    StandardWinReward.COMEBACK -> Text(
                        text = appString(R.string.standard_ai_comeback_message),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    StandardWinReward.CLUTCH -> Text(
                        text = appString(R.string.standard_ai_clutch_message),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    StandardWinReward.NONE -> Unit
                }

                if (presentation.firstClear) {
                    Text(
                        text = appString(R.string.standard_ai_first_clear, level.value),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                presentation.unlockedLevel?.let { unlocked ->
                    val unlockedOpponent = unlocked.standardOpponent()
                    if (presentation.wildStageAwakened) {
                        Text(
                            text = appString(R.string.standard_ai_wild_stage_unlocked),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        AnimatedOpponentImage(
                            drawableRes = unlockedOpponent.winDrawableRes,
                            level = unlocked,
                            tag = "standard-ai-unlocked-opponent-image",
                            imageSize = 174.dp,
                        )
                        Text(
                            text = appString(
                                R.string.standard_ai_wild_stage_appeared,
                                appString(unlockedOpponent.nameRes),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(
                            text = appString(
                                R.string.standard_ai_next_opponent_revealed,
                                appString(unlockedOpponent.nameRes),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AnimatedOpponentImage(
                            drawableRes = unlockedOpponent.winDrawableRes,
                            level = unlocked,
                            tag = "standard-ai-unlocked-opponent-image",
                            imageSize = 116.dp,
                        )
                    }
                }

                if (presentation.conquered) {
                    Text(
                        text = appString(R.string.standard_ai_conquered),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(appString(R.string.standard_ai_result_continue))
            }
        },
    )
}

@Composable
private fun AnimatedOpponentImage(
    drawableRes: Int,
    level: StandardAiLevel,
    tag: String,
    imageSize: Dp = 220.dp,
) {
    var visible by remember(drawableRes) { mutableStateOf(false) }
    LaunchedEffect(drawableRes) { visible = true }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.72f,
        animationSpec = tween(durationMillis = 360),
        label = "standard-ai-opponent-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "standard-ai-opponent-alpha",
    )
    Image(
        painter = painterResource(drawableRes),
        contentDescription = appString(R.string.standard_ai_opponent_image_description, level.value),
        modifier = Modifier
            .size(imageSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .testTag(tag),
    )
}
