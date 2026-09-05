package com.example.othello

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.game.Position

private const val BOARD_SIZE = 8
private val CoordinateGutter = 18.dp
private val CoordinateLabelColor = ChanrivaColors.textDisabled

internal fun Position.coordinateLabel(): String = "${('a'.code + column).toChar()}${row + 1}"

/** Draws the fixed-orientation board frame and delegates each cell to the caller. */
@Composable
internal fun CoordinateBoard(
    cellContent: @Composable (Position, Modifier) -> Unit,
) {
    val context = LocalContext.current
    val boardTextSize = remember(context) { TheoryBoardTextSettingsStore(context).textSize }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(ChanrivaColors.board)
            .padding(3.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(CoordinateGutter)) {
                Spacer(Modifier.width(CoordinateGutter))
                repeat(BOARD_SIZE) { column ->
                    Text(
                        text = ('a'.code + column).toChar().toString(),
                        modifier = Modifier.weight(1f).fillMaxHeight().wrapContentHeight(Alignment.CenterVertically),
                        color = CoordinateLabelColor,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.width(CoordinateGutter))
            }
            repeat(BOARD_SIZE) { row ->
                Row(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                    Text(
                        text = (row + 1).toString(),
                        modifier = Modifier.width(CoordinateGutter).fillMaxHeight().wrapContentHeight(Alignment.CenterVertically),
                        color = CoordinateLabelColor,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                    repeat(BOARD_SIZE) { column ->
                        BoxWithConstraints(
                            Modifier
                                .fillMaxHeight()
                                .weight(1f, fill = true)
                                .border(0.5.dp, ChanrivaColors.boardGrid),
                        ) {
                            val fontSizeSp = resolveTheoryBoardTextSizeSp(
                                textSize = boardTextSize,
                                cellHeightDp = maxHeight.value,
                                fontScale = density.fontScale,
                                lineCount = 1,
                            )
                            val inheritedTypography = MaterialTheme.typography
                            MaterialTheme(
                                typography = inheritedTypography.copy(
                                    labelSmall = inheritedTypography.labelSmall.copy(
                                        fontSize = fontSizeSp.sp,
                                        lineHeight = (fontSizeSp * THEORY_BOARD_LINE_HEIGHT_FACTOR).sp,
                                    ),
                                ),
                            ) {
                                cellContent(Position(row, column), Modifier.fillMaxSize())
                            }
                        }
                    }
                    Spacer(Modifier.width(CoordinateGutter))
                }
            }
            Row(Modifier.fillMaxWidth().height(CoordinateGutter)) {
                Spacer(Modifier.width(CoordinateGutter))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(CoordinateGutter))
            }
        }
    }
}
