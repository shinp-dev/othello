package com.example.othello

import kotlin.test.Test
import kotlin.test.assertEquals

class StandardAiHumanOutcomeContractTest {
    @Test
    fun presentationOutcomeEnumCoversWinLossAndDraw() {
        assertEquals(
            listOf("WIN", "LOSS", "DRAW"),
            StandardAiHumanOutcome.entries.map { it.name },
        )
    }
}
