package com.example.othello.network

import com.example.othello.game.CanonicalMoves
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Strict Protocol v2 wire serializer for transcript resynchronization. */
object SyncMessageJson {
    private const val KIND = "SYNC"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Serializable
    private data class WireSyncMessage(
        val kind: String = KIND,
        val protocolVersion: Int,
        val matchId: String,
        val requestId: String,
        val syncType: String,
        val ply: Int,
        val stateHash: String,
        val transcript: String? = null,
    )

    fun encode(message: SyncMessage): String {
        validate(message)
        return json.encodeToString(
            WireSyncMessage.serializer(),
            WireSyncMessage(
                protocolVersion = message.protocolVersion,
                matchId = message.matchId,
                requestId = message.requestId,
                syncType = message.type.name,
                ply = message.ply,
                stateHash = message.stateHash,
                transcript = message.transcript,
            ),
        )
    }

    fun decode(payload: String): Result<SyncMessage> = try {
        val wire = json.decodeFromString(WireSyncMessage.serializer(), payload)
        require(wire.kind == KIND) { "not a sync message" }
        Result.success(
            SyncMessage(
                protocolVersion = wire.protocolVersion,
                matchId = wire.matchId,
                requestId = wire.requestId,
                type = SyncMessageType.valueOf(wire.syncType),
                ply = wire.ply,
                stateHash = wire.stateHash,
                transcript = wire.transcript,
            ).also(::validate),
        )
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun validate(message: SyncMessage) {
        require(message.protocolVersion == CURRENT_PROTOCOL_VERSION) { "unsupported protocol version" }
        require(message.matchId.isNotBlank()) { "match id is required" }
        require(message.requestId.isNotBlank()) { "request id is required" }
        require(message.ply >= 0) { "ply must be non-negative" }
        requireValidStateHash(message.stateHash, message.ply)
        when (message.type) {
            SyncMessageType.REQUEST -> require(message.transcript == null) { "sync request cannot contain a transcript" }
            SyncMessageType.SNAPSHOT -> {
                val transcript = requireNotNull(message.transcript) { "sync snapshot requires a transcript" }
                require(transcript.length <= MAX_SYNC_TRANSCRIPT_CHARS) { "sync transcript is too large" }
                CanonicalMoves.decode(transcript)
                require(transcript.length / 2 == message.ply) { "sync transcript ply does not match snapshot ply" }
            }
        }
    }
}

private val STATE_HASH_PATTERN = Regex("^[0-9a-f]{16}:[12]:[0-2]:([0-9]+)$")

internal fun requireValidStateHash(stateHash: String, expectedPly: Int) {
    val match = requireNotNull(STATE_HASH_PATTERN.matchEntire(stateHash)) { "invalid state hash" }
    require(match.groupValues[1].toIntOrNull() == expectedPly) { "state hash ply does not match message ply" }
}
