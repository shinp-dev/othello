package com.example.othello.match

import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.Disc

data class LocalMatchViewState(
    val game: GameState = GameState(),
    val message: String = "黒の手番です",
    val moves: List<Position?> = emptyList(),
)

class LocalMatchController {
    var viewState: LocalMatchViewState = LocalMatchViewState()
        private set

    private val listeners = mutableSetOf<(LocalMatchViewState) -> Unit>()

    fun observe(listener: (LocalMatchViewState) -> Unit): AutoCloseable {
        listeners += listener
        listener(viewState)
        return AutoCloseable { listeners -= listener }
    }

    fun play(position: Position) {
        when (val outcome = viewState.game.play(position)) {
            is MoveOutcome.Played -> advance(outcome.state, viewState.moves + position, "${outcome.state.currentPlayer.japaneseName()}の手番です")
            is MoveOutcome.Rejected -> update(viewState.game, viewState.moves, "そのマスには置けません")
            is MoveOutcome.Passed -> Unit
        }
    }

    fun pass() {
        when (val outcome = viewState.game.pass()) {
            is MoveOutcome.Passed -> advance(outcome.state, viewState.moves + null, "${outcome.state.currentPlayer.japaneseName()}の手番です（パスしました）")
            is MoveOutcome.Rejected -> update(viewState.game, viewState.moves, "パスできる合法手がありません")
            is MoveOutcome.Played -> Unit
        }
    }

    fun reset() { update(GameState(), emptyList(), "黒の手番です") }

    private fun advance(game: GameState, moves: List<Position?>, defaultMessage: String) {
        var next = game
        var nextMoves = moves
        var forcedPasses = 0
        while (next.status is GameStatus.InProgress && next.legalMoves.isEmpty()) {
            when (val pass = next.pass()) {
                is MoveOutcome.Passed -> { next = pass.state; nextMoves += null; forcedPasses++ }
                is MoveOutcome.Rejected, is MoveOutcome.Played -> break
            }
        }
        update(next, nextMoves, if (forcedPasses == 0) defaultMessage else "${next.currentPlayer.japaneseName()}の手番です（自動パス）")
    }

    private fun update(game: GameState, moves: List<Position?>, defaultMessage: String) {
        val message = when (val status = game.status) {
            is GameStatus.Finished -> when (status.result.winner) {
                Disc.BLACK -> "黒の勝ちです"
                Disc.WHITE -> "白の勝ちです"
                Disc.EMPTY -> "引き分けです"
                null -> "引き分けです"
            }
            GameStatus.InProgress -> defaultMessage
        }
        viewState = LocalMatchViewState(game, message, moves)
        listeners.toList().forEach { it(viewState) }
    }

    private fun Disc.japaneseName(): String = if (this == Disc.BLACK) "黒" else "白"
}
