package com.example.othello

import kotlin.test.*
import org.junit.Test

class StandardOpponentCatalogTest {
    @Test fun baselineContainsStableAnimalIdsAndOriginalStrengths() {
        val pack = animalPack()
        assertEquals("animal", pack.id)
        assertEquals(LegacyAnimalPlayerIds, pack.players.map { it.id })
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 2, 2), pack.players.map { it.ai.edaxLevel })
        assertTrue(pack.players.all { it.winImage != it.loseImage })
        assertEquals(OpponentUnlockCelebration.MILESTONE, pack.player("wild-chick")!!.unlockCelebration)
    }
}
