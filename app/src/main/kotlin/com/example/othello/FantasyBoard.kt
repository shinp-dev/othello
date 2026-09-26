package com.example.othello

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.Position

/** Static, reusable 8×8 board artwork, discs, and cell-centered markers. */
@Composable
internal fun FantasyBoard(
    board: Board,
    markers: List<FantasyBoardMarker> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { testTag = "fantasy-board" },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.fantasy_board_surface),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            Modifier
                .fillMaxWidth(0.965f)
                .fillMaxHeight(0.945f)
                .align(Alignment.Center)
                .offset(y = (-3).dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            repeat(Board.SIZE) { row ->
                Row(Modifier.weight(1f)) {
                    repeat(Board.SIZE) { column ->
                        val position = Position(row, column)
                        Box(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (board[position]) {
                                Disc.BLACK -> Image(
                                    painter = painterResource(R.drawable.fantasy_disc_black),
                                    contentDescription = "Black disc at ${position.row},${position.column}",
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    contentScale = ContentScale.Fit,
                                )
                                Disc.WHITE -> Image(
                                    painter = painterResource(R.drawable.fantasy_disc_white),
                                    contentDescription = "White disc at ${position.row},${position.column}",
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    contentScale = ContentScale.Fit,
                                )
                                Disc.EMPTY -> Unit
                            }
                            markers.filter { it.position == position }.forEach { marker ->
                                val (resource, inset) = when (marker.style) {
                                    FantasyBoardMarkerStyle.GOLD_RING -> R.drawable.fantasy_marker_ring to 5.dp
                                    FantasyBoardMarkerStyle.SQUARE_FRAME -> R.drawable.fantasy_marker_frame to 0.dp
                                    FantasyBoardMarkerStyle.WARNING_CROSS -> R.drawable.fantasy_marker_x to 6.dp
                                }
                                Image(
                                    painter = painterResource(resource),
                                    contentDescription = when (marker.style) {
                                        FantasyBoardMarkerStyle.GOLD_RING -> "Highlighted square"
                                        FantasyBoardMarkerStyle.SQUARE_FRAME -> "Framed square"
                                        FantasyBoardMarkerStyle.WARNING_CROSS -> "Avoid this square"
                                    },
                                    modifier = Modifier.fillMaxSize().padding(inset),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class FantasyBoardMarker(
    val position: Position,
    val style: FantasyBoardMarkerStyle,
)

internal enum class FantasyBoardMarkerStyle {
    GOLD_RING,
    SQUARE_FRAME,
    WARNING_CROSS,
}
