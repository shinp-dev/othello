package com.example.othello.match

import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position

data class LocalMatchViewState(
    val game: GameState = GameState(),
    val message: String = "黒の手番です",
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
            is MoveOutcome.Played -> update(outcome.state, "${outcome.state.currentPlayer.japaneseName()}の手番です")
            is MoveOutcome.Rejected -> update(viewState.game, "そのマスには置けません")
            is MoveOutcome.Passed -> Unit
        }
    }

    fun pass() {
        when (val outcome = viewState.game.pass()) {
            is MoveOutcome.Passed -> update(outcome.state, "${outcome.state.currentPlayer.japaneseName()}の手番です（パスしました）")
            is MoveOutcome.Rejected -> update(viewState.game, "パスできる合法手がありません")
            is MoveOutcome.Played -> Unit
        }
    }

    fun reset() { update(GameState(), "黒の手番です") }

    private fun update(game: GameState, defaultMessage: String) {
        val message = when (val status = game.status) {
            is GameStatus.Finished -> when (status.result.winner) {
                com.example.othello.game.Disc.BLACK -> "黒の勝ちです"
                com.example.othello.game.Disc.WHITE -> "白の勝ちです"
                com.example.othello.game.Disc.EMPTY -> "引き分けです"
                null -> "引き分けです"
            }
            GameStatus.InProgress -> defaultMessage
        }
        viewState = LocalMatchViewState(game, message)
        listeners.toList().forEach { it(viewState) }
    }

    private fun com.example.othello.game.Disc.japaneseName(): String = if (this == com.example.othello.game.Disc.BLACK) "黒" else "白"
}
