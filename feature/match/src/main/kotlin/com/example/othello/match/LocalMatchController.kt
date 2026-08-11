package com.example.othello.match

import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.Disc
import com.example.othello.game.TurnResolver

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

    fun reset() { update(GameState(), emptyList(), "黒の手番です") }

    private fun advance(game: GameState, moves: List<Position?>, defaultMessage: String) {
        val resolution = TurnResolver.resolveForcedPasses(game)
        val nextMoves = moves + List(resolution.forcedPasses) { null }
        update(resolution.state, nextMoves, if (resolution.forcedPasses == 0) defaultMessage else "${resolution.state.currentPlayer.japaneseName()}の手番です（自動パス）")
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
