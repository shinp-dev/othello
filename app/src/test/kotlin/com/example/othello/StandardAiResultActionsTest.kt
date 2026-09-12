package com.example.othello

import com.example.othello.analysis.api.StandardAiLevel
import kotlin.test.assertEquals
import org.junit.Test

class StandardAiResultActionsTest {
    @Test
    fun firstClearWithUnlockLeadsToNextOpponent() {
        val actions = standardAiResultActions(
            StandardAiResultPresentation(
                outcome = StandardAiHumanOutcome.WIN,
                firstClear = true,
                unlockedLevel = StandardAiLevel.LV2,
            ),
        )

        assertEquals(StandardAiResultAction.NEXT_OPPONENT, actions.primary)
        assertEquals(StandardAiResultAction.REMATCH, actions.secondary)
    }

    @Test
    fun lossLeadsToRetry() {
        val actions = standardAiResultActions(
            StandardAiResultPresentation(outcome = StandardAiHumanOutcome.LOSS),
        )

        assertEquals(StandardAiResultAction.RETRY, actions.primary)
        assertEquals(StandardAiResultAction.CHOOSE_OPPONENT, actions.secondary)
    }

    @Test
    fun drawLeadsToRetry() {
        val actions = standardAiResultActions(
            StandardAiResultPresentation(outcome = StandardAiHumanOutcome.DRAW),
        )

        assertEquals(StandardAiResultAction.RETRY, actions.primary)
        assertEquals(StandardAiResultAction.CHOOSE_OPPONENT, actions.secondary)
    }

    @Test
    fun winWithoutUnlockLeadsToOpponentSelection() {
        val actions = standardAiResultActions(
            StandardAiResultPresentation(outcome = StandardAiHumanOutcome.WIN),
        )

        assertEquals(StandardAiResultAction.CHOOSE_OPPONENT, actions.primary)
        assertEquals(StandardAiResultAction.REMATCH, actions.secondary)
    }
}
