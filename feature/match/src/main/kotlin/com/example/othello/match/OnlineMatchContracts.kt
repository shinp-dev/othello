package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.records.FinishReason
import com.example.othello.records.MatchResult

data class MatchStartAck(
    val serverStatus: String,
    val localAcked: Boolean,
    val bothAcked: Boolean,
)

data class MatchSubmission(
    val matchId: String,
    val canonicalMoves: String,
    val result: MatchResult,
    val finalPositionHash: String,
    val finishReason: FinishReason,
    val clockPayload: String? = null,
)

data class MatchFinishResult(
    val serverStatus: String,
    val ratingBefore: Int? = null,
    val ratingAfter: Int? = null,
    val ratingDelta: Int? = null,
    val currentRating: Int? = null,
    val peakRating: Int? = null,
)

interface OnlineMatchRepository {
    suspend fun ackMatchStarted(matchId: String): MatchStartAck
    suspend fun getMatchStartState(matchId: String): MatchStartAck
    suspend fun abandonMatch(matchId: String): Boolean
    suspend fun submitMatchResult(submission: MatchSubmission): MatchFinishResult
}
