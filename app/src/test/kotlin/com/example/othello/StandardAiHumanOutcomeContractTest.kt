package com.example.othello

import org.junit.Test
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
