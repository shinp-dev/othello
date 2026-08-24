package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import com.example.othello.records.FinishReason
import com.example.othello.records.MatchResult
import java.util.UUID

data class MatchStartAck(
    val serverStatus: String,
    val localAcked: Boolean,
    val bothAcked: Boolean,
    val deadlineEpochMillis: Long? = null,
    val negotiationEpoch: Int = 0,
) {
    init {
        require(negotiationEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
    }
}

data class MatchSubmission(
    val matchId: String,
    val canonicalMoves: String,
    /** Kept for local UX/diagnostics. Protocol v2 never treats this as authority. */
    val result: MatchResult,
    /** Kept for cross-checking. Protocol v2 recomputes this on the server. */
    val finalPositionHash: String,
    val finishReason: FinishReason,
    val loserDisc: Disc? = null,
    val clockPayload: String? = null,
    val requestId: String = UUID.randomUUID().toString(),
)

data class MatchFinishResult(
    val serverStatus: String,
    val ratingBefore: Int? = null,
    val ratingAfter: Int? = null,
    val ratingDelta: Int? = null,
    val currentRating: Int? = null,
    val peakRating: Int? = null,
    val finalResult: MatchResult? = null,
    val finalPositionHash: String? = null,
    val deadlineEpochMillis: Long? = null,
    val negotiationEpoch: Int = 0,
) {
    init {
        require(negotiationEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
    }
}

interface OnlineMatchRepository {
    suspend fun ackMatchStarted(matchId: String): MatchStartAck
    suspend fun getMatchStartState(matchId: String): MatchStartAck
    suspend fun abandonMatch(matchId: String): Boolean
    suspend fun submitMatchResult(submission: MatchSubmission): MatchFinishResult
    suspend fun resumeMatch(matchId: String): MatchFinishResult = MatchFinishResult("ACTIVE")
    suspend fun reconcileMatch(matchId: String): MatchFinishResult = MatchFinishResult("ACTIVE")
}
