package com.example.othello.network

import com.example.othello.game.Position
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The only wire serializer for DataChannel move commands. */
object MoveCommandJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Serializable
    private data class WireMoveCommand(
        val protocolVersion: Int,
        val matchId: String,
        val commandId: String,
        val ply: Int,
        val row: Int,
        val column: Int,
        val previousStateHash: String,
        val blackRemainingMillis: Long? = null,
        val whiteRemainingMillis: Long? = null,
    )

    fun encode(command: MoveCommand): String = json.encodeToString(
        WireMoveCommand.serializer(),
        WireMoveCommand(
            protocolVersion = command.protocolVersion,
            matchId = command.matchId,
            commandId = command.commandId,
            ply = command.ply,
            row = command.move.row,
            column = command.move.column,
            previousStateHash = command.previousStateHash,
            blackRemainingMillis = command.clockSnapshot?.blackRemainingMillis,
            whiteRemainingMillis = command.clockSnapshot?.whiteRemainingMillis,
        ),
    )

    fun decode(payload: String): Result<MoveCommand> = runCatching {
        val wire = json.decodeFromString(WireMoveCommand.serializer(), payload)
        MoveCommand(
            protocolVersion = wire.protocolVersion,
            matchId = wire.matchId,
            ply = wire.ply,
            move = Position(wire.row, wire.column),
            commandId = wire.commandId,
            previousStateHash = wire.previousStateHash,
            clockSnapshot = if (wire.blackRemainingMillis != null && wire.whiteRemainingMillis != null) {
                ClockSnapshot(wire.blackRemainingMillis, wire.whiteRemainingMillis)
            } else null,
        )
    }
}
