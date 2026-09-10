package com.example.othello

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.StandardAiLevel

internal enum class StandardAiHumanOutcome { WIN, LOSS, DRAW }

internal data class StandardAiResultPresentation(
    val outcome: StandardAiHumanOutcome,
    val firstClear: Boolean = false,
    val unlockedLevel: StandardAiLevel? = null,
    val conquered: Boolean = false,
)

@Composable
internal fun StandardAiIntroDialog(
    level: StandardAiLevel,
    onStart: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("standard-ai-intro-dialog"),
        onDismissRequest = onStart,
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
    AlertDialog(
        modifier = Modifier.testTag("standard-ai-result-dialog"),
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (presentation.outcome) {
                    StandardAiHumanOutcome.WIN -> appString(R.string.standard_ai_result_win)
                    StandardAiHumanOutcome.LOSS -> appString(R.string.standard_ai_opponent_wins)
                    StandardAiHumanOutcome.DRAW -> appString(R.string.standard_ai_result_draw)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedOpponentImage(
                    drawableRes = if (presentation.outcome == StandardAiHumanOutcome.WIN) {
                        level.opponentLoseDrawable()
                    } else {
                        level.opponentWinDrawable()
                    },
                    level = level,
                    tag = "standard-ai-result-image",
                )
                if (presentation.firstClear) {
                    Text(
                        text = appString(R.string.standard_ai_first_clear, level.value),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                presentation.unlockedLevel?.let { unlocked ->
                    Text(
                        text = appString(R.string.standard_ai_next_level_unlocked, unlocked.value),
                        style = MaterialTheme.typography.titleMedium,
                    )
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
) {
    var visible by remember(drawableRes) { mutableStateOf(false) }
    LaunchedEffect(drawableRes) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)) +
            scaleIn(animationSpec = tween(360), initialScale = 0.72f),
    ) {
        Image(
            painter = painterResource(drawableRes),
            contentDescription = appString(R.string.standard_ai_opponent_image_description, level.value),
            modifier = Modifier
                .size(220.dp)
                .testTag(tag),
        )
    }
}

private fun StandardAiLevel.opponentWinDrawable(): Int = when (this) {
    StandardAiLevel.LV1 -> R.drawable.standard_ai_lv01_chick_win
    StandardAiLevel.LV2 -> R.drawable.standard_ai_lv02_rabbit_win
    StandardAiLevel.LV3 -> R.drawable.standard_ai_lv03_koala_win
    StandardAiLevel.LV4 -> R.drawable.standard_ai_lv04_elephant_win
    StandardAiLevel.LV5 -> R.drawable.standard_ai_lv05_wild_chick_win
    StandardAiLevel.LV6 -> R.drawable.standard_ai_lv06_wild_rabbit_win
    StandardAiLevel.LV7 -> R.drawable.standard_ai_lv07_wild_koala_win
    StandardAiLevel.LV8 -> R.drawable.standard_ai_lv08_wild_elephant_win
}

private fun StandardAiLevel.opponentLoseDrawable(): Int = when (this) {
    StandardAiLevel.LV1 -> R.drawable.standard_ai_lv01_chick_lose
    StandardAiLevel.LV2 -> R.drawable.standard_ai_lv02_rabbit_lose
    StandardAiLevel.LV3 -> R.drawable.standard_ai_lv03_koala_lose
    StandardAiLevel.LV4 -> R.drawable.standard_ai_lv04_elephant_lose
    StandardAiLevel.LV5 -> R.drawable.standard_ai_lv05_wild_chick_lose
    StandardAiLevel.LV6 -> R.drawable.standard_ai_lv06_wild_rabbit_lose
    StandardAiLevel.LV7 -> R.drawable.standard_ai_lv07_wild_koala_lose
    StandardAiLevel.LV8 -> R.drawable.standard_ai_lv08_wild_elephant_lose
}
