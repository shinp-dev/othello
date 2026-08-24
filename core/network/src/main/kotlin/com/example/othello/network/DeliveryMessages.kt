package com.example.othello.network

/** Receiver confirmation that a move command has been applied to this exact state. */
data class MoveAck(
    val matchId: String,
    val commandId: String,
    val acknowledgedPly: Int,
    val stateHash: String,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
)

enum class SyncMessageType { REQUEST, SNAPSHOT }

/**
 * A request advertises the sender's last-known state. A snapshot carries the full
 * canonical transcript so the receiver can replay and verify it before recovery.
 */
data class SyncMessage(
    val matchId: String,
    val requestId: String,
    val type: SyncMessageType,
    val ply: Int,
    val stateHash: String,
    val transcript: String? = null,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
)

const val MAX_SYNC_TRANSCRIPT_CHARS: Int = 240
