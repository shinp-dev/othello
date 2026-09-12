package com.example.othello

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.game.Disc
import com.example.othello.game.Position

@Composable
internal fun StandardTipBoard(example: StandardTipBoardExample) {
    val state = remember(example.moves) { standardTipStateFor(example.moves) }
    val focus = remember(example.focusSquares) { example.focusSquares.map(::standardTipPosition).toSet() }
    val warnings = remember(example.warningSquares) { example.warningSquares.map(::standardTipPosition).toSet() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 240.dp)
            .aspectRatio(1f)
            .background(ChanrivaColors.board)
            .padding(3.dp),
    ) {
        repeat(8) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = state.board[position]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, ChanrivaColors.boardGrid),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (position in focus) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                            )
                        }
                        if (disc != Disc.EMPTY) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(4.dp)
                                    .background(
                                        if (disc == Disc.BLACK) ChanrivaColors.blackDisc else ChanrivaColors.whiteDisc,
                                        CircleShape,
                                    )
                                    .border(1.dp, ChanrivaColors.discOutline, CircleShape),
                            )
                        }
                        if (position in warnings) {
                            Text(
                                text = "×",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
