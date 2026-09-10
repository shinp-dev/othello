package com.example.othello

import com.example.othello.analysis.api.StandardAiLevel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StandardOpponentCatalogTest {
    @Test
    fun animalPackCoversEveryStandardCampaignLevelExactlyOnce() {
        val pack = StandardOpponentPacks.animal

        assertEquals("animal", pack.id)
        assertEquals(StandardAiLevel.entries, pack.opponents.map { it.level })
        assertEquals(8, pack.opponents.map { it.level }.distinct().size)
        assertEquals(
            listOf(
                StandardAiLevel.LV1,
                StandardAiLevel.LV2,
                StandardAiLevel.LV7,
                StandardAiLevel.LV8,
            ),
            pack.previewLevels,
        )
    }

    @Test
    fun everyAnimalOpponentHasSeparateWinAndLoseArtwork() {
        StandardOpponentPacks.animal.opponents.forEach { opponent ->
            assertTrue(opponent.winDrawableRes != 0)
            assertTrue(opponent.loseDrawableRes != 0)
            assertTrue(opponent.winDrawableRes != opponent.loseDrawableRes)
        }
    }

    @Test
    fun levelLookupUsesThePackAsTheSinglePresentationCatalog() {
        StandardAiLevel.entries.forEach { level ->
            assertEquals(level, level.standardOpponent().level)
            assertEquals(level.standardOpponent().winDrawableRes, level.opponentWinDrawable())
            assertEquals(level.standardOpponent().loseDrawableRes, level.opponentLoseDrawable())
        }
    }
}
