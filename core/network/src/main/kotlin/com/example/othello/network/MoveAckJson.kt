package com.example.othello.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Strict Protocol v2 wire serializer for application-level move acknowledgements. */
object MoveAckJson {
    private const val KIND = "MOVE_ACK"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Serializable
    private data class WireMoveAck(
        val kind: String = KIND,
        val protocolVersion: Int,
        val matchId: String,
        val commandId: String,
        val acknowledgedPly: Int,
        val stateHash: String,
    )

    fun encode(ack: MoveAck): String {
        validate(ack)
        return json.encodeToString(
            WireMoveAck.serializer(),
            WireMoveAck(
                protocolVersion = ack.protocolVersion,
                matchId = ack.matchId,
                commandId = ack.commandId,
                acknowledgedPly = ack.acknowledgedPly,
                stateHash = ack.stateHash,
            ),
        )
    }

    fun decode(payload: String): Result<MoveAck> = try {
        val wire = json.decodeFromString(WireMoveAck.serializer(), payload)
        require(wire.kind == KIND) { "not a move acknowledgement" }
        Result.success(
            MoveAck(
                protocolVersion = wire.protocolVersion,
                matchId = wire.matchId,
                commandId = wire.commandId,
                acknowledgedPly = wire.acknowledgedPly,
                stateHash = wire.stateHash,
            ).also(::validate),
        )
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun validate(ack: MoveAck) {
        require(ack.protocolVersion == CURRENT_PROTOCOL_VERSION) { "unsupported protocol version" }
        require(ack.matchId.isNotBlank()) { "match id is required" }
        require(ack.commandId.isNotBlank()) { "command id is required" }
        require(ack.acknowledgedPly >= 0) { "acknowledged ply must be non-negative" }
        requireValidStateHash(ack.stateHash, ack.acknowledgedPly)
    }
}
