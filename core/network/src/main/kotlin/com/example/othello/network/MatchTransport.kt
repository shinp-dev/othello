package com.example.othello.network

import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position

data class MoveCommand(
    val matchId: String,
    val ply: Int,
    val move: Position?,
    val commandId: String,
    val previousStateHash: String,
)

sealed interface CommandValidation {
    data class Accepted(val state: GameState) : CommandValidation
    data class Duplicate(val commandId: String) : CommandValidation
    data class Rejected(val violation: ProtocolViolation) : CommandValidation
}

enum class ProtocolViolation { MATCH_MISMATCH, PLY_MISMATCH, HASH_MISMATCH, WRONG_TURN, ILLEGAL_MOVE, INVALID_PASS }

interface MatchTransport {
    suspend fun send(command: MoveCommand)
    fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable
}

class MoveCommandValidator(private val matchId: String) {
    private val handledCommandIds = mutableSetOf<String>()

    fun validate(state: GameState, command: MoveCommand): CommandValidation {
        if (command.commandId in handledCommandIds) return CommandValidation.Duplicate(command.commandId)
        if (command.matchId != matchId) return CommandValidation.Rejected(ProtocolViolation.MATCH_MISMATCH)
        if (command.ply != state.ply) return CommandValidation.Rejected(ProtocolViolation.PLY_MISMATCH)
        if (command.previousStateHash != state.stateHash()) return CommandValidation.Rejected(ProtocolViolation.HASH_MISMATCH)
        val outcome: MoveOutcome = command.move?.let(state::play) ?: state.pass()
        val next = when (outcome) {
            is MoveOutcome.Played -> outcome.state
            is MoveOutcome.Passed -> outcome.state
            is MoveOutcome.Rejected -> return CommandValidation.Rejected(
                if (command.move == null) ProtocolViolation.INVALID_PASS else ProtocolViolation.ILLEGAL_MOVE,
            )
        }
        handledCommandIds += command.commandId
        return CommandValidation.Accepted(next)
    }
}
