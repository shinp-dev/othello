package com.example.othello.game

sealed interface GameStatus {
    data object InProgress : GameStatus
    data class Finished(val result: GameResult) : GameStatus
}

data class GameResult(val black: Int, val white: Int) {
    val winner: Disc? = when {
        black > white -> Disc.BLACK
        white > black -> Disc.WHITE
        else -> null
    }
}

sealed interface MoveOutcome {
    data class Played(val state: GameState, val flipped: List<Position>) : MoveOutcome
    data class Passed(val state: GameState) : MoveOutcome
    data class Rejected(val reason: RejectionReason) : MoveOutcome
}

enum class RejectionReason { GAME_OVER, WRONG_TURN, ILLEGAL_MOVE, PASS_NOT_ALLOWED }

data class GameState(
    val board: Board = Board.initial(),
    val currentPlayer: Disc = Disc.BLACK,
    val consecutivePasses: Int = 0,
    val ply: Int = 0,
) {
    init {
        require(currentPlayer != Disc.EMPTY)
        require(consecutivePasses in 0..2)
        require(ply >= 0)
    }

    val legalMoves: Set<Position> get() = board.legalMoves(currentPlayer)
    val status: GameStatus
        get() = if (consecutivePasses >= 2 || board.emptyCount() == 0) {
            GameStatus.Finished(GameResult(board.count(Disc.BLACK), board.count(Disc.WHITE)))
        } else GameStatus.InProgress

    fun play(position: Position): MoveOutcome {
        if (status is GameStatus.Finished) return MoveOutcome.Rejected(RejectionReason.GAME_OVER)
        val captured = board.capturedForMove(position, currentPlayer)
        if (captured.isEmpty()) return MoveOutcome.Rejected(RejectionReason.ILLEGAL_MOVE)
        val next = copy(
            board = board.flipped(position, currentPlayer),
            currentPlayer = currentPlayer.opponent(),
            consecutivePasses = 0,
            ply = ply + 1,
        )
        return MoveOutcome.Played(next, captured)
    }

    fun pass(): MoveOutcome {
        if (status is GameStatus.Finished) return MoveOutcome.Rejected(RejectionReason.GAME_OVER)
        if (legalMoves.isNotEmpty()) return MoveOutcome.Rejected(RejectionReason.PASS_NOT_ALLOWED)
        return MoveOutcome.Passed(copy(currentPlayer = currentPlayer.opponent(), consecutivePasses = consecutivePasses + 1, ply = ply + 1))
    }

    fun stateHash(): String = board.stateHash() + ":" + currentPlayer.ordinal + ":" + consecutivePasses + ":" + ply

}
