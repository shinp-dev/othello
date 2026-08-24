package com.example.othello

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.network.ClockSnapshot
import com.example.othello.matchmaking.AssignedDisc
import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import com.example.othello.records.FinishReason
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class OnlineMatchRecoveryStoreTest {
    @Test
    fun codecRoundTripsActiveTranscriptAndResultOutbox() {
        val recoveredGame = (GameState().play(Position(2, 3)) as MoveOutcome.Played).state
        val original = OnlineMatchRecoverySnapshot(
            userId = "user-a",
            matchId = "match-a",
            opponentId = "user-b",
            assignedDisc = AssignedDisc.WHITE,
            opponentRating = 1_612,
            negotiationEpoch = 3,
            canonicalMoves = "d3",
            stateHash = recoveredGame.stateHash(),
            blackRemainingMillis = 281_000,
            whiteRemainingMillis = 275_000,
            runningDisc = Disc.WHITE,
            pendingFinishReason = FinishReason.DISCONNECT,
            pendingLoserDisc = Disc.WHITE,
            pendingResultRequestId = "request-1",
            updatedAtEpochMillis = 123_456,
        )

        assertEquals(original, OnlineMatchRecoveryCodec.decode(OnlineMatchRecoveryCodec.encode(original)))
        assertEquals(ClockSnapshot(281_000, 274_000), original.adjustedClock(124_456))
    }

    @Test
    fun malformedOrOversizedTranscriptIsRejected() {
        assertFails {
            OnlineMatchRecoverySnapshot(
                "user-a", "match-a", "user-b", AssignedDisc.BLACK, null, 0,
                "z9", "hash", 1, 1, updatedAtEpochMillis = 1,
            )
        }
        assertFails {
            OnlineMatchRecoverySnapshot(
                "user-a", "match-a", "user-b", AssignedDisc.BLACK, null, 0,
                "d3".repeat(121), "hash", 1, 1, updatedAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun recoverySnapshotRejectsAnEpochBeyondTheMatchBudget() {
        assertFails {
            OnlineMatchRecoverySnapshot(
                userId = "user-a",
                matchId = "match-a",
                opponentId = "user-b",
                assignedDisc = AssignedDisc.BLACK,
                opponentRating = null,
                negotiationEpoch = MAX_MATCH_NEGOTIATION_EPOCH + 1,
                canonicalMoves = "",
                stateHash = GameState().stateHash(),
                blackRemainingMillis = 1,
                whiteRemainingMillis = 1,
                updatedAtEpochMillis = 1,
            )
        }
    }
}
