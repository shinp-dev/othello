package com.example.othello

import androidx.annotation.StringRes
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position

internal enum class StandardWinningTipTier(@StringRes val titleRes: Int) {
    BASIC(R.string.standard_winning_tips_tier_basic),
    STEP_UP(R.string.standard_winning_tips_tier_step_up),
    EXPERT(R.string.standard_winning_tips_tier_expert),
}

internal data class StandardTipBoardExample(
    val moves: List<String>,
    val focusSquares: Set<String> = emptySet(),
    val warningSquares: Set<String> = emptySet(),
)

internal data class StandardWinningTip(
    val id: String,
    val tier: StandardWinningTipTier,
    @StringRes val titleRes: Int,
    @StringRes val leadRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val takeawayRes: Int,
    @StringRes val boardCaptionRes: Int,
    val boardExample: StandardTipBoardExample,
)

private fun moves(value: String): List<String> = value.split(" ")

internal val standardWinningTips: List<StandardWinningTip> = listOf(
    StandardWinningTip(
        id = "take-less-early",
        tier = StandardWinningTipTier.BASIC,
        titleRes = R.string.standard_winning_tip_take_less_title,
        leadRes = R.string.standard_winning_tip_take_less_lead,
        bodyRes = R.string.standard_winning_tip_take_less_body,
        takeawayRes = R.string.standard_winning_tip_take_less_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_take_less_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("d3 c3 b3 e3 f3 c5 f6 g2 b5"),
            focusSquares = setOf("c2"),
            warningSquares = setOf("a3"),
        ),
    ),
    StandardWinningTip(
        id = "limit-mobility",
        tier = StandardWinningTipTier.BASIC,
        titleRes = R.string.standard_winning_tip_mobility_title,
        leadRes = R.string.standard_winning_tip_mobility_lead,
        bodyRes = R.string.standard_winning_tip_mobility_body,
        takeawayRes = R.string.standard_winning_tip_mobility_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_mobility_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("d3 c3 b3 e3 f3 c5 f6 g2"),
            focusSquares = setOf("f4"),
            warningSquares = setOf("d6"),
        ),
    ),
    StandardWinningTip(
        id = "corners",
        tier = StandardWinningTipTier.BASIC,
        titleRes = R.string.standard_winning_tip_corner_title,
        leadRes = R.string.standard_winning_tip_corner_lead,
        bodyRes = R.string.standard_winning_tip_corner_body,
        takeawayRes = R.string.standard_winning_tip_corner_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_corner_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6"),
            focusSquares = setOf("a8"),
        ),
    ),
    StandardWinningTip(
        id = "avoid-x-square",
        tier = StandardWinningTipTier.BASIC,
        titleRes = R.string.standard_winning_tip_x_square_title,
        leadRes = R.string.standard_winning_tip_x_square_lead,
        bodyRes = R.string.standard_winning_tip_x_square_body,
        takeawayRes = R.string.standard_winning_tip_x_square_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_x_square_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 e8 d1 a8 c7 a4 b8 c3"),
            focusSquares = setOf("a1"),
            warningSquares = setOf("b2"),
        ),
    ),
    StandardWinningTip(
        id = "do-not-rush-edge",
        tier = StandardWinningTipTier.STEP_UP,
        titleRes = R.string.standard_winning_tip_edge_title,
        leadRes = R.string.standard_winning_tip_edge_lead,
        bodyRes = R.string.standard_winning_tip_edge_body,
        takeawayRes = R.string.standard_winning_tip_edge_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_edge_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2"),
            focusSquares = setOf("e2"),
            warningSquares = setOf("f8"),
        ),
    ),
    StandardWinningTip(
        id = "count-at-end",
        tier = StandardWinningTipTier.STEP_UP,
        titleRes = R.string.standard_winning_tip_endgame_title,
        leadRes = R.string.standard_winning_tip_endgame_lead,
        bodyRes = R.string.standard_winning_tip_endgame_body,
        takeawayRes = R.string.standard_winning_tip_endgame_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_endgame_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 e8 d1 a8 c7 a4 b8 c3 f5 e1 b3 c1 b2 a2 d8 f8 a1 f6 g7 g4 b4 a3 g8 a6 g5 g6 a5 h7 h3 g3 g2 h6 f2 g1"),
            focusSquares = setOf("e2"),
        ),
    ),
    StandardWinningTip(
        id = "stable-discs",
        tier = StandardWinningTipTier.STEP_UP,
        titleRes = R.string.standard_winning_tip_stable_title,
        leadRes = R.string.standard_winning_tip_stable_lead,
        bodyRes = R.string.standard_winning_tip_stable_body,
        takeawayRes = R.string.standard_winning_tip_stable_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_stable_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 a8"),
            focusSquares = setOf("a8"),
        ),
    ),
    StandardWinningTip(
        id = "parity",
        tier = StandardWinningTipTier.EXPERT,
        titleRes = R.string.standard_winning_tip_parity_title,
        leadRes = R.string.standard_winning_tip_parity_lead,
        bodyRes = R.string.standard_winning_tip_parity_body,
        takeawayRes = R.string.standard_winning_tip_parity_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_parity_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 e8 d1 a8 c7 a4 b8 c3 f5 e1 b3 c1 b2 a2 d8 f8 a1 f6 g7 g4 b4 a3 g8 a6 g5 g6 a5 h7 h3 g3 g2 h6 f2 g1"),
            focusSquares = setOf("h1", "h2"),
        ),
    ),
    StandardWinningTip(
        id = "count-tempo",
        tier = StandardWinningTipTier.EXPERT,
        titleRes = R.string.standard_winning_tip_tempo_title,
        leadRes = R.string.standard_winning_tip_tempo_lead,
        bodyRes = R.string.standard_winning_tip_tempo_body,
        takeawayRes = R.string.standard_winning_tip_tempo_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_tempo_caption,
        boardExample = StandardTipBoardExample(
            moves = moves("c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 e8 d1 a8 c7 a4 b8 c3 f5 e1 b3 c1 b2 a2 d8 f8 a1 f6 g7 g4 b4 a3 g8 a6 g5 g6 a5 h7 h3 g3 g2 h6 f2 g1"),
            focusSquares = setOf("f1", "e2", "f3"),
        ),
    ),
)

internal fun standardTipPosition(notation: String): Position {
    require(notation.length == 2) { "Tip coordinate must use a1-h8 notation: $notation" }
    val column = notation[0].lowercaseChar() - 'a'
    val row = notation[1].digitToIntOrNull()?.minus(1) ?: -1
    require(row in 0..7 && column in 0..7) { "Tip coordinate is outside a1-h8: $notation" }
    return Position(row, column)
}

internal fun standardTipStateFor(moves: List<String>): GameState {
    var state = GameState()
    moves.forEach { notation ->
        if (state.legalMoves.isEmpty()) {
            state = when (val pass = state.pass()) {
                is MoveOutcome.Passed -> pass.state
                else -> error("Tip fixture cannot pass before $notation: $pass")
            }
        }
        state = when (val outcome = state.play(standardTipPosition(notation))) {
            is MoveOutcome.Played -> outcome.state
            else -> error("Illegal tip fixture move $notation: $outcome")
        }
    }
    return state
}
