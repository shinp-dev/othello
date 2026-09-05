package com.example.othello

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.othello.designsystem.ChanrivaColors
import kotlin.math.min

internal enum class TheoryBoardTextSize(
    val storedValue: String,
    val targetSp: Float,
) {
    STANDARD("standard", 10f),
    LARGE("large", 12f),
    EXTRA_LARGE("extra_large", 16f),
    ;

    companion object {
        fun fromStoredValue(value: String?): TheoryBoardTextSize =
            values().firstOrNull { it.storedValue == value } ?: STANDARD
    }
}

internal class TheoryBoardTextSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var textSize: TheoryBoardTextSize
        get() = TheoryBoardTextSize.fromStoredValue(preferences.getString(KEY_TEXT_SIZE, null))
        set(value) = preferences.edit().putString(KEY_TEXT_SIZE, value.storedValue).apply()

    private companion object {
        const val FILE_NAME = "chanriva_display_settings"
        const val KEY_TEXT_SIZE = "theory_board_text_size"
    }
}

@Composable
internal fun TheoryBoardTextSizeSetting() {
    val context = LocalContext.current
    val store = remember(context) { TheoryBoardTextSettingsStore(context) }
    var selected by remember(store) { mutableStateOf(store.textSize) }

    fun select(value: TheoryBoardTextSize) {
        selected = value
        store.textSize = value
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(appString(R.string.theory_board_text_size), style = MaterialTheme.typography.titleMedium)
        Text(
            appString(R.string.theory_board_text_size_description),
            style = MaterialTheme.typography.bodySmall,
        )
        TheoryBoardTextSize.values().forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { select(option) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == option,
                    onClick = { select(option) },
                )
                Text(
                    appString(
                        when (option) {
                            TheoryBoardTextSize.STANDARD -> R.string.theory_board_text_size_standard
                            TheoryBoardTextSize.LARGE -> R.string.theory_board_text_size_large
                            TheoryBoardTextSize.EXTRA_LARGE -> R.string.theory_board_text_size_extra_large
                        },
                    ),
                )
            }
        }
    }
}

@Composable
internal fun TheoryBoardCandidateLabels(
    edaxText: String,
    metricText: String,
    isBestMove: Boolean,
    textSize: TheoryBoardTextSize,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontSizeSp = resolveTheoryBoardTextSizeSp(
            textSize = textSize,
            cellHeightDp = maxHeight.value,
            fontScale = LocalDensity.current.fontScale,
            lineCount = 2,
        )
        val textStyle = MaterialTheme.typography.labelSmall.copy(
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * THEORY_BOARD_LINE_HEIGHT_FACTOR).sp,
        )

        Column(
            Modifier.fillMaxSize().padding(horizontal = 1.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = edaxText,
                modifier = Modifier.fillMaxWidth(),
                color = if (isBestMove) ChanrivaColors.evaluation else MaterialTheme.colorScheme.onSurface,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = metricText,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun ReviewBoardEvaluationLabel(
    text: String,
    isBestMove: Boolean,
    textSize: TheoryBoardTextSize,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fontSizeSp = resolveTheoryBoardTextSizeSp(
            textSize = textSize,
            cellHeightDp = maxHeight.value,
            fontScale = LocalDensity.current.fontScale,
            lineCount = 1,
        )
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
            color = if (isBestMove) ChanrivaColors.evaluation else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * THEORY_BOARD_LINE_HEIGHT_FACTOR).sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

internal const val THEORY_BOARD_LINE_HEIGHT_FACTOR = 1.1f
private const val THEORY_BOARD_VERTICAL_SAFETY_DP = 4f

internal fun resolveTheoryBoardTextSizeSp(
    textSize: TheoryBoardTextSize,
    cellHeightDp: Float,
    fontScale: Float,
    lineCount: Int = 2,
): Float {
    val safeFontScale = fontScale.coerceAtLeast(0.1f)
    val safeLineCount = lineCount.coerceAtLeast(1)
    val availableHeightDp = (cellHeightDp - THEORY_BOARD_VERTICAL_SAFETY_DP).coerceAtLeast(1f)
    val maxByHeightSp = availableHeightDp /
        (safeLineCount * THEORY_BOARD_LINE_HEIGHT_FACTOR * safeFontScale)
    return min(textSize.targetSp, maxByHeightSp).coerceAtLeast(1f)
}
