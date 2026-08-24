package com.example.othello.network

import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.Disc

data class ClockSnapshot(
    val blackRemainingMillis: Long,
    val whiteRemainingMillis: Long,
) {
    init {
        require(blackRemainingMillis >= 0)
        require(whiteRemainingMillis >= 0)
    }

    fun remaining(disc: Disc): Long = when (disc) {
        Disc.BLACK -> blackRemainingMillis
        Disc.WHITE -> whiteRemainingMillis
        Disc.EMPTY -> error("EMPTY has no clock")
    }
}

data class MoveCommand(
    val matchId: String,
    val ply: Int,
    /** DataChannel commands carry real moves only; forced passes are derived locally. */
    val move: Position,
    val commandId: String,
    val previousStateHash: String,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val clockSnapshot: ClockSnapshot? = null,
)

enum class FinishSignalReason { RESIGNATION, TIMEOUT, DISCONNECT }

/** A terminal control message sent on DataChannel; it never carries a move. */
data class FinishCommand(
    val matchId: String,
    val ply: Int,
    val commandId: String,
    val stateHash: String,
    val loserDisc: Disc,
    val reason: FinishSignalReason,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val clockSnapshot: ClockSnapshot? = null,
)

const val CURRENT_PROTOCOL_VERSION: Int = 2

enum class TransportState { NEW, CONNECTING, CONNECTED, OPEN, DISCONNECTED, CLOSING, CLOSED, FAILED }

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
    INVALID_CLOCK_SNAPSHOT,
}

interface MatchTransport {
    suspend fun send(command: MoveCommand)
    suspend fun sendFinish(command: FinishCommand) {
        throw UnsupportedOperationException("finish commands are not supported")
    }
    suspend fun sendMoveAck(ack: MoveAck) {
        throw UnsupportedOperationException("move acknowledgements are not supported")
    }
    suspend fun sendSync(message: SyncMessage) {
        throw UnsupportedOperationException("sync messages are not supported")
    }
    fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable
    fun observeFinish(onCommand: (FinishCommand) -> Unit): AutoCloseable = AutoCloseable { }
    fun observeMoveAck(onAck: (MoveAck) -> Unit): AutoCloseable = AutoCloseable { }
    fun observeSync(onMessage: (SyncMessage) -> Unit): AutoCloseable = AutoCloseable { }
    fun observeState(onState: (TransportState) -> Unit): AutoCloseable = AutoCloseable { }
    fun diagnostics(): TransportDiagnostics = TransportDiagnostics(TransportState.NEW)
    fun close() { }
}

class FinishCommandValidator(private val matchId: String, private val remoteDisc: Disc) {
    private val commandFingerprints = mutableMapOf<String, String>()

    fun validate(state: GameState, command: FinishCommand): FinishCommandValidation {
        if (command.protocolVersion != CURRENT_PROTOCOL_VERSION) {
            return FinishCommandValidation.Rejected(ProtocolViolation.PROTOCOL_VERSION_MISMATCH)
        }
        if (command.matchId != matchId) return FinishCommandValidation.Rejected(ProtocolViolation.MATCH_MISMATCH)
        if (command.clockSnapshot?.let { it.blackRemainingMillis < 0 || it.whiteRemainingMillis < 0 } == true) {
            return FinishCommandValidation.Rejected(ProtocolViolation.INVALID_CLOCK_SNAPSHOT)
        }
        val fingerprint = command.fingerprint()
        commandFingerprints[command.commandId]?.let { previous ->
            return if (previous == fingerprint) FinishCommandValidation.Duplicate(command.commandId)
            else FinishCommandValidation.Rejected(ProtocolViolation.COMMAND_ID_REUSE)
        }
        commandFingerprints[command.commandId] = fingerprint
        if (command.ply != state.ply) return FinishCommandValidation.Rejected(ProtocolViolation.PLY_MISMATCH)
        if (command.stateHash != state.stateHash()) return FinishCommandValidation.Rejected(ProtocolViolation.HASH_MISMATCH)
        if (command.loserDisc != remoteDisc) return FinishCommandValidation.Rejected(ProtocolViolation.WRONG_TURN)
        return FinishCommandValidation.Accepted(command)
    }

    private fun FinishCommand.fingerprint(): String = buildString {
        append(protocolVersion).append('|').append(matchId).append('|').append(ply).append('|').append(commandId).append('|')
        append(stateHash).append('|').append(loserDisc.name).append('|').append(reason.name)
        clockSnapshot?.let { append('|').append(it.blackRemainingMillis).append(',').append(it.whiteRemainingMillis) }
    }
}

sealed interface FinishCommandValidation {
    data class Accepted(val command: FinishCommand) : FinishCommandValidation
    data class Duplicate(val commandId: String) : FinishCommandValidation
    data class Rejected(val violation: ProtocolViolation) : FinishCommandValidation
}

class MoveCommandValidator(private val matchId: String, private val remoteDisc: Disc) {
    private val commandFingerprints = mutableMapOf<String, String>()

    fun validate(state: GameState, command: MoveCommand): CommandValidation {
        if (command.protocolVersion != CURRENT_PROTOCOL_VERSION) {
            return CommandValidation.Rejected(ProtocolViolation.PROTOCOL_VERSION_MISMATCH)
        }
        if (command.matchId != matchId) return CommandValidation.Rejected(ProtocolViolation.MATCH_MISMATCH)
        if (command.clockSnapshot?.let { it.blackRemainingMillis < 0 || it.whiteRemainingMillis < 0 } == true) {
            return CommandValidation.Rejected(ProtocolViolation.INVALID_CLOCK_SNAPSHOT)
        }
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
        clockSnapshot?.let { append('|').append(it.blackRemainingMillis).append(',').append(it.whiteRemainingMillis) }
    }
}
