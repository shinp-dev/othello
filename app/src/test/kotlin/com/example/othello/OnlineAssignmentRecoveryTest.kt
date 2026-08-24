package com.example.othello

import com.example.othello.matchmaking.AssignedDisc
import com.example.othello.matchmaking.MatchAssignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OnlineAssignmentRecoveryTest {
    @Test
    fun successfulEmptyServerClaimClearsOnlyTheCheckpointItReconciled(): Unit = runBlocking {
        var clearedMatchId: String? = null

        val restored = restoreAssignmentAndClearCheckpointWhenServerReturnsEmpty(
            checkpointMatchId = "stale-match",
            restoreActiveAssignment = { null },
            clearRecovery = { matchId ->
                clearedMatchId = matchId
                true
            },
        )

        assertNull(restored)
        assertEquals("stale-match", clearedMatchId)
    }

    @Test
    fun activeAssignmentOrMissingCheckpointIsPreserved(): Unit = runBlocking {
        val assignment = MatchAssignment("match", "opponent", AssignedDisc.BLACK)
        var clearCalled = false

        assertEquals(
            assignment,
            restoreAssignmentAndClearCheckpointWhenServerReturnsEmpty(
                checkpointMatchId = "checkpoint",
                restoreActiveAssignment = { assignment },
                clearRecovery = { clearCalled = true; true },
            ),
        )
        assertNull(
            restoreAssignmentAndClearCheckpointWhenServerReturnsEmpty(
                checkpointMatchId = null,
                restoreActiveAssignment = { null },
                clearRecovery = { clearCalled = true; true },
            ),
        )
        assertFalse(clearCalled)
    }

    @Test
    fun serverFailurePreservesCheckpoint(): Unit = runBlocking {
        var clearCalled = false

        val failure = assertFailsWith<IllegalStateException> {
            restoreAssignmentAndClearCheckpointWhenServerReturnsEmpty(
                checkpointMatchId = "checkpoint",
                restoreActiveAssignment = { error("offline") },
                clearRecovery = { clearCalled = true; true },
            )
        }

        assertEquals("offline", failure.message)
        assertFalse(clearCalled)
    }

    @Test
    fun cancellationIsRethrownAndPreservesCheckpoint(): Unit = runBlocking {
        var clearCalled = false

        val cancellation = assertFailsWith<CancellationException> {
            restoreAssignmentAndClearCheckpointWhenServerReturnsEmpty(
                checkpointMatchId = "checkpoint",
                restoreActiveAssignment = { throw CancellationException("screen left") },
                clearRecovery = { clearCalled = true; true },
            )
        }

        assertEquals("screen left", cancellation.message)
        assertFalse(clearCalled)
    }
}
