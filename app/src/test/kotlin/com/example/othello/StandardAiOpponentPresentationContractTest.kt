package com.example.othello

import com.example.othello.analysis.api.StandardAiLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class StandardAiOpponentPresentationContractTest {
    @Test
    fun campaignHasEightOpponentLevels() {
        assertEquals((1..8).toList(), StandardAiLevel.entries.map { it.value })
    }
}
