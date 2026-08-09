package com.example.othello.matchmaking

enum class AssignedDisc { BLACK, WHITE }

data class MatchAssignment(
    val matchId: String,
    val opponentId: String,
    val assignedDisc: AssignedDisc,
)

sealed interface EnqueueResult {
    data object Waiting : EnqueueResult
    data class Matched(val assignment: MatchAssignment) : EnqueueResult
}

/** The implementation calls enqueue_or_match(); it never accepts a client rating. */
interface MatchmakingRepository {
    suspend fun enqueueOrMatch(): EnqueueResult
    suspend fun cancelWaiting(): Boolean
    suspend fun heartbeatWaiting(): Boolean
}
