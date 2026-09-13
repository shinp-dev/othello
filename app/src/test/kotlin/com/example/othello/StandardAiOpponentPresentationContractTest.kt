package com.example.othello

import org.junit.Test
import kotlin.test.assertEquals

class StandardAiOpponentPresentationContractTest {
    @Test
    fun campaignHasEightOpponentLevels() {
        assertEquals((1..8).toList(), animalPack().players.map { it.order })
    }
}
