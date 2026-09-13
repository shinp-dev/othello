package com.example.othello

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class StandardAiHumanOutcome { WIN, LOSS, DRAW }

internal data class StandardAiResultPresentation(
    val outcome: StandardAiHumanOutcome,
    val firstClear: Boolean = false,
    val unlockedPlayer: Player? = null,
    val conquered: Boolean = false,
    val winReward: StandardWinReward = StandardWinReward.NONE,
    val milestoneUnlocked: Boolean = false,
)

internal enum class StandardAiResultAction { NEXT_OPPONENT, RETRY, CHOOSE_OPPONENT, REMATCH }

internal data class StandardAiResultActions(
    val primary: StandardAiResultAction,
    val secondary: StandardAiResultAction,
)

internal fun standardAiResultActions(presentation: StandardAiResultPresentation): StandardAiResultActions = when {
    presentation.unlockedPlayer != null -> StandardAiResultActions(
        primary = StandardAiResultAction.NEXT_OPPONENT,
        secondary = StandardAiResultAction.REMATCH,
    )
    presentation.outcome != StandardAiHumanOutcome.WIN -> StandardAiResultActions(
        primary = StandardAiResultAction.RETRY,
        secondary = StandardAiResultAction.CHOOSE_OPPONENT,
    )
    else -> StandardAiResultActions(
        primary = StandardAiResultAction.CHOOSE_OPPONENT,
        secondary = StandardAiResultAction.REMATCH,
    )
}

@Composable
internal fun StandardAiIntroDialog(
    installedPack: InstalledOpponentPack,
    player: Player,
    onStart: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("standard-ai-intro-dialog"),
        onDismissRequest = {},
        title = {
            Text(opponentText(player.name))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedOpponentImage(
                    image = installedPack.image(player.portrait),
                    name = opponentText(player.name),
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
    installedPack: InstalledOpponentPack,
    player: Player,
    presentation: StandardAiResultPresentation,
    onRetry: () -> Unit,
    onChooseOpponent: (Player) -> Unit,
    saveFailed: Boolean = false,
    onRetrySave: () -> Unit = {},
) {
    val actions = standardAiResultActions(presentation)
    val selectionPlayer = presentation.unlockedPlayer ?: player
    val title = when {
        presentation.milestoneUnlocked -> appString(R.string.opponent_milestone_title)
        presentation.winReward == StandardWinReward.COMEBACK -> appString(R.string.standard_ai_comeback_title)
        presentation.winReward == StandardWinReward.CLUTCH -> appString(R.string.standard_ai_clutch_title)
        presentation.outcome == StandardAiHumanOutcome.WIN -> appString(R.string.standard_ai_result_win)
        presentation.outcome == StandardAiHumanOutcome.LOSS -> appString(R.string.standard_ai_opponent_wins)
        else -> appString(R.string.standard_ai_result_draw)
    }

    AlertDialog(
        modifier = Modifier.testTag("standard-ai-result-dialog"),
        onDismissRequest = { onChooseOpponent(selectionPlayer) },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnimatedOpponentImage(
                    image = if (presentation.outcome == StandardAiHumanOutcome.WIN) {
                        installedPack.image(player.loseImage)
                    } else {
                        installedPack.image(player.winImage)
                    },
                    name = opponentText(player.name),
                    tag = "standard-ai-result-image",
                    imageSize = if (presentation.unlockedPlayer != null) 164.dp else 220.dp,
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
                        text = appString(R.string.opponent_first_clear, opponentText(player.name)),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                presentation.unlockedPlayer?.let { unlocked ->
                    val unlockedOpponent = unlocked
                    if (presentation.milestoneUnlocked) {
                        Text(
                            text = appString(R.string.opponent_milestone_unlocked),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        AnimatedOpponentImage(
                            image = installedPack.image(unlockedOpponent.portrait),
                            name = opponentText(unlocked.name),
                            tag = "standard-ai-unlocked-opponent-image",
                            imageSize = 174.dp,
                        )
                        Text(
                            text = appString(
                                R.string.standard_ai_wild_stage_appeared,
                                opponentText(unlockedOpponent.name),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(
                            text = appString(
                                R.string.standard_ai_next_opponent_revealed,
                                opponentText(unlockedOpponent.name),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AnimatedOpponentImage(
                            image = installedPack.image(unlockedOpponent.portrait),
                            name = opponentText(unlocked.name),
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

                if (saveFailed) {
                    Text(
                        text = appString(R.string.local_record_save_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onRetrySave) {
                        Text(appString(R.string.retry_save))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { runStandardAiResultAction(actions.primary, selectionPlayer, onRetry, onChooseOpponent) }) {
                Text(standardAiResultActionLabel(actions.primary, presentation.conquered))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { runStandardAiResultAction(actions.secondary, selectionPlayer, onRetry, onChooseOpponent) },
            ) {
                Text(standardAiResultActionLabel(actions.secondary, presentation.conquered))
            }
        },
    )
}

private fun runStandardAiResultAction(
    action: StandardAiResultAction,
    selectionPlayer: Player,
    onRetry: () -> Unit,
    onChooseOpponent: (Player) -> Unit,
) {
    when (action) {
        StandardAiResultAction.RETRY,
        StandardAiResultAction.REMATCH,
        -> onRetry()
        StandardAiResultAction.NEXT_OPPONENT,
        StandardAiResultAction.CHOOSE_OPPONENT,
        -> onChooseOpponent(selectionPlayer)
    }
}

@Composable
private fun standardAiResultActionLabel(action: StandardAiResultAction, conquered: Boolean): String = when (action) {
    StandardAiResultAction.NEXT_OPPONENT -> appString(R.string.standard_ai_result_next_opponent)
    StandardAiResultAction.RETRY -> appString(R.string.standard_ai_result_retry)
    StandardAiResultAction.REMATCH -> appString(R.string.standard_ai_result_rematch)
    StandardAiResultAction.CHOOSE_OPPONENT -> appString(
        if (conquered) R.string.standard_ai_result_opponent_list else R.string.standard_ai_result_choose_opponent,
    )
}

@Composable
private fun AnimatedOpponentImage(
    image: java.io.File,
    name: String,
    tag: String,
    imageSize: Dp = 220.dp,
) {
    var visible by remember(image) { mutableStateOf(false) }
    LaunchedEffect(image) { visible = true }
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
    AsyncImage(
        model = image,
        contentDescription = name,
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
