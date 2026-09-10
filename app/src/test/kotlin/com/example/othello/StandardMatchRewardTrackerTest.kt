package com.example.othello

import com.example.othello.analysis.api.StandardTensionLevel
import kotlin.test.assertEquals
import org.junit.Test

class StandardMatchRewardTrackerTest {
    @Test
    fun criticalWinWithoutClearAiLeadBecomesClutch() {
        val tracker = StandardMatchRewardTracker()
        tracker.recordDecision(StandardTensionLevel.CRITICAL, opponentBestScore = 2)

        assertEquals(
            StandardWinReward.CLUTCH,
            tracker.classify(humanWon = true, undoUsed = false),
        )
    }

    @Test
    fun winAfterAiWasClearlyAheadBecomesComeback() {
        val tracker = StandardMatchRewardTracker()
        tracker.recordDecision(StandardTensionLevel.CALM, opponentBestScore = 8)

        assertEquals(
            StandardWinReward.COMEBACK,
            tracker.classify(humanWon = true, undoUsed = false),
        )
    }

    @Test
    fun comebackTakesPriorityOverClutch() {
        val tracker = StandardMatchRewardTracker()
        tracker.recordDecision(StandardTensionLevel.CRITICAL, opponentBestScore = 10)

        assertEquals(
            StandardWinReward.COMEBACK,
            tracker.classify(humanWon = true, undoUsed = false),
        )
    }

    @Test
    fun undoAndLossNeverEarnSpecialWinReward() {
        val tracker = StandardMatchRewardTracker()
        tracker.recordDecision(StandardTensionLevel.CRITICAL, opponentBestScore = 20)

        assertEquals(
            StandardWinReward.NONE,
            tracker.classify(humanWon = true, undoUsed = true),
        )
        assertEquals(
            StandardWinReward.NONE,
            tracker.classify(humanWon = false, undoUsed = false),
        )
    }

    @Test
    fun resetClearsAccumulatedMatchSignals() {
        val tracker = StandardMatchRewardTracker()
        tracker.recordDecision(StandardTensionLevel.CRITICAL, opponentBestScore = 20)
        tracker.reset()

        assertEquals(
            StandardWinReward.NONE,
            tracker.classify(humanWon = true, undoUsed = false),
        )
    }
}
