package com.example.othello.game

/**
 * Resolves deterministic forced passes shared by local and online matches.
 * A pass is never represented as a DataChannel command.
 */
data class TurnResolution(val state: GameState, val forcedPasses: Int)

object TurnResolver {
    fun resolveForcedPasses(state: GameState): TurnResolution {
        var next = state
        var forcedPasses = 0
        while (next.status is GameStatus.InProgress && next.legalMoves.isEmpty()) {
            next = (next.pass() as MoveOutcome.Passed).state
            forcedPasses++
        }
        return TurnResolution(next, forcedPasses)
    }
}
