package com.example.othello.network

import com.example.othello.game.Disc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire serializer for non-move terminal control messages. */
object FinishCommandJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Serializable
    private data class WireFinishCommand(
        val kind: String = "FINISH",
        val protocolVersion: Int,
        val matchId: String,
        val commandId: String,
        val ply: Int,
        val stateHash: String,
        val loserDisc: String,
        val reason: String,
    )

    fun encode(command: FinishCommand): String = json.encodeToString(
        WireFinishCommand.serializer(),
        WireFinishCommand(
            protocolVersion = command.protocolVersion,
            matchId = command.matchId,
            commandId = command.commandId,
            ply = command.ply,
            stateHash = command.stateHash,
            loserDisc = command.loserDisc.name,
            reason = command.reason.name,
        ),
    )

    fun decode(payload: String): Result<FinishCommand> = runCatching {
        val wire = json.decodeFromString(WireFinishCommand.serializer(), payload)
        require(wire.kind == "FINISH") { "not a finish command" }
        FinishCommand(
            protocolVersion = wire.protocolVersion,
            matchId = wire.matchId,
            ply = wire.ply,
            commandId = wire.commandId,
            stateHash = wire.stateHash,
            loserDisc = Disc.valueOf(wire.loserDisc).also { require(it != Disc.EMPTY) },
            reason = FinishSignalReason.valueOf(wire.reason),
        )
    }
}
