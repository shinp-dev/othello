package com.example.othello.match

/** Debug-only snapshot. SDP and credentials are intentionally never retained. */
data class MatchDiagnostics(
    val matchId: String,
    val userId: String,
    val localDisc: String,
    val opponentId: String?,
    val sessionStatus: MatchStatus,
    val iceState: String,
    val peerConnectionState: String,
    val dataChannelState: String,
    val signalingStartedAtMillis: Long? = null,
    val dataChannelOpenedAtMillis: Long? = null,
    val offerSet: Boolean = false,
    val answerSet: Boolean = false,
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val ply: Int = 0,
    val stateHash: String? = null,
    val lastError: String? = null,
    val localStartAcked: Boolean = false,
)
