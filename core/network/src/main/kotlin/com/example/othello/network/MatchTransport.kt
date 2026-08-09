package com.example.othello.network

import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.Disc

data class MoveCommand(
    val matchId: String,
    val ply: Int,
    /** DataChannel commands carry real moves only; forced passes are derived locally. */
    val move: Position,
    val commandId: String,
    val previousStateHash: String,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
)

const val CURRENT_PROTOCOL_VERSION: Int = 1

enum class TransportState { NEW, CONNECTING, OPEN, CLOSING, CLOSED, FAILED }

data class TransportDiagnostics(
    val state: TransportState,
    val iceState: String = "UNKNOWN",
    val peerConnectionState: String = "UNKNOWN",
    val dataChannelState: String = "UNKNOWN",
)

sealed interface CommandValidation {
    data class Accepted(val state: GameState) : CommandValidation
    data class Duplicate(val commandId: String) : CommandValidation
    data class Rejected(val violation: ProtocolViolation) : CommandValidation
}

enum class ProtocolViolation {
    PROTOCOL_VERSION_MISMATCH,
    MATCH_MISMATCH,
    PLY_MISMATCH,
    HASH_MISMATCH,
    WRONG_TURN,
    ILLEGAL_MOVE,
    COMMAND_ID_REUSE,
}

interface MatchTransport {
    suspend fun send(command: MoveCommand)
    fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable
    fun observeState(onState: (TransportState) -> Unit): AutoCloseable = AutoCloseable { }
    fun diagnostics(): TransportDiagnostics = TransportDiagnostics(TransportState.NEW)
    fun close() { }
}

class MoveCommandValidator(private val matchId: String, private val remoteDisc: Disc) {
    private val commandFingerprints = mutableMapOf<String, String>()

    fun validate(state: GameState, command: MoveCommand): CommandValidation {
        if (command.protocolVersion != CURRENT_PROTOCOL_VERSION) {
            return CommandValidation.Rejected(ProtocolViolation.PROTOCOL_VERSION_MISMATCH)
        }
        if (command.matchId != matchId) return CommandValidation.Rejected(ProtocolViolation.MATCH_MISMATCH)
        val fingerprint = command.fingerprint()
        commandFingerprints[command.commandId]?.let { previous ->
            return if (previous == fingerprint) CommandValidation.Duplicate(command.commandId)
            else CommandValidation.Rejected(ProtocolViolation.COMMAND_ID_REUSE)
        }
        // The first payload is reserved even when validation rejects it. A retry may
        // duplicate that payload, but the command id can never be repurposed.
        commandFingerprints[command.commandId] = fingerprint
        if (command.ply != state.ply) return CommandValidation.Rejected(ProtocolViolation.PLY_MISMATCH)
        if (command.previousStateHash != state.stateHash()) return CommandValidation.Rejected(ProtocolViolation.HASH_MISMATCH)
        if (state.currentPlayer != remoteDisc) return CommandValidation.Rejected(ProtocolViolation.WRONG_TURN)
        val outcome: MoveOutcome = state.play(command.move)
        val next = when (outcome) {
            is MoveOutcome.Played -> outcome.state
            is MoveOutcome.Passed -> error("GameState.play cannot produce a pass")
            is MoveOutcome.Rejected -> return CommandValidation.Rejected(ProtocolViolation.ILLEGAL_MOVE)
        }
        return CommandValidation.Accepted(next)
    }

    private fun MoveCommand.fingerprint(): String = buildString {
        append(protocolVersion).append('|').append(matchId).append('|').append(ply).append('|').append(commandId).append('|').append(previousStateHash).append('|')
        append(move.row).append(',').append(move.column)
    }
}
