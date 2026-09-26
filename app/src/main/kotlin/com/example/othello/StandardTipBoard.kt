package com.example.othello

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.othello.game.Position

/** Adapts a tips example to the reusable, non-interactive fantasy board renderer. */
@Composable
internal fun StandardTipBoard(example: StandardTipBoardExample, modifier: Modifier = Modifier) {
    val state = remember(example.moves) { standardTipStateFor(example.moves) }
    val frames = remember(example.frameSquares) { example.frameSquares.map(::standardTipPosition).toSet() }
    val focus = remember(example.focusSquares, frames) {
        example.focusSquares.map(::standardTipPosition).toSet() - frames
    }
    val warnings = remember(example.warningSquares) { example.warningSquares.map(::standardTipPosition).toSet() }
    val markers = buildList {
        focus.forEach { add(FantasyBoardMarker(it, FantasyBoardMarkerStyle.GOLD_RING)) }
        frames.forEach { add(FantasyBoardMarker(it, FantasyBoardMarkerStyle.SQUARE_FRAME)) }
        warnings.forEach { add(FantasyBoardMarker(it, FantasyBoardMarkerStyle.WARNING_CROSS)) }
    }

    FantasyBoard(
        board = state.board,
        markers = markers,
        modifier = modifier,
    )
}

internal fun standardTipPosition(notation: String): Position {
    require(notation.length == 2) { "Tip coordinate must use a1-h8 notation: $notation" }
    val column = notation[0].lowercaseChar() - 'a'
    val row = notation[1].digitToIntOrNull()?.minus(1) ?: -1
    require(row in 0..7 && column in 0..7) { "Tip coordinate is outside a1-h8: $notation" }
    return Position(row, column)
}
