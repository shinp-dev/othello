package com.example.othello

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StandardGachaEngineTest {
    @Test
    fun duplicatesCanAppearBeforeCollectionIsComplete() {
        val engine = engineWith(0.0, 0.0)
        val entries = listOf(
            entry("trivia.common.001", StandardContentRarity.COMMON),
            entry("trivia.common.002", StandardContentRarity.COMMON),
        )

        val draw = assertNotNull(
            engine.draw(
                entries = entries,
                obtainedCardIds = setOf("trivia.common.001"),
            ),
        )

        assertEquals("trivia.common.001", draw.entry.card.id)
        assertFalse(draw.isNew)
    }

    @Test
    fun raritySelectionUsesSeventyTwentyFiveFiveWeightsAcrossAvailableBuckets() {
        val entries = listOf(
            entry("common.001", StandardContentRarity.COMMON),
            entry("rare.001", StandardContentRarity.RARE),
            entry("special.001", StandardContentRarity.SPECIAL),
        )

        assertEquals(
            StandardContentRarity.COMMON,
            assertNotNull(engineWith(0.69, 0.0).draw(entries, emptySet())).entry.card.rarity,
        )
        assertEquals(
            StandardContentRarity.RARE,
            assertNotNull(engineWith(0.70, 0.0).draw(entries, emptySet())).entry.card.rarity,
        )
        assertEquals(
            StandardContentRarity.SPECIAL,
            assertNotNull(engineWith(0.96, 0.0).draw(entries, emptySet())).entry.card.rarity,
        )
    }

    @Test
    fun completedCollectionCanDrawDuplicatesWithoutMutatingPackState() {
        val entries = listOf(entry("book.001", StandardContentRarity.SPECIAL))
        val draw = assertNotNull(
            engineWith(0.5, 0.5).draw(
                entries = entries,
                obtainedCardIds = setOf("book.001"),
            ),
        )

        assertEquals("book.001", draw.entry.card.id)
        assertFalse(draw.isNew)
    }

    @Test
    fun emptySnapshotCannotDraw() {
        assertNull(engineWith(0.0).draw(emptyList(), emptySet()))
    }

    private fun engineWith(vararg values: Double): StandardGachaEngine {
        val queue = ArrayDeque(values.toList())
        return StandardGachaEngine {
            if (queue.isEmpty()) 0.0 else queue.removeFirst()
        }
    }

    private fun entry(
        id: String,
        rarity: StandardContentRarity,
    ) = StandardContentEntry(
        packId = "starter",
        card = StandardContentCard(
            id = id,
            type = StandardContentCardType.TRIVIA,
            rarity = rarity,
            title = id,
            summary = "summary",
            body = null,
            imagePath = null,
            seriesId = null,
            tags = emptySet(),
            attributes = emptyMap(),
            sourceLabel = null,
            sourceUrl = null,
            externalUrl = null,
            sortOrder = null,
        ),
        imageFile = null,
    )
}
