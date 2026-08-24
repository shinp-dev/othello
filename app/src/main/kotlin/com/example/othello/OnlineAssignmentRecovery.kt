package com.example.othello

import com.example.othello.matchmaking.MatchAssignment

/**
 * Reconciles the local checkpoint only after the server claim completed successfully.
 * Exceptions, including cancellation, deliberately escape before any local deletion.
 */
internal suspend fun restoreAssignmentAndClearCheckpointWhenServerReturnsEmpty(
    checkpointMatchId: String?,
    restoreActiveAssignment: suspend () -> MatchAssignment?,
    clearRecovery: (String) -> Boolean,
): MatchAssignment? {
    val assignment = restoreActiveAssignment()
    if (assignment == null && checkpointMatchId != null) {
        clearRecovery(checkpointMatchId)
    }
    return assignment
}
