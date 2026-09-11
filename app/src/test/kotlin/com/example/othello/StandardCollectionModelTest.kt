package com.example.othello

import kotlin.test.assertEquals
import org.junit.Test

class StandardCollectionModelTest {
    @Test
    fun availableFiltersShowOnlyCategoriesThatExist() {
        val entries = listOf(
            entry("trivia.001", StandardContentCardType.TRIVIA),
            entry("history.001", StandardContentCardType.HISTORY),
            entry("book.001", StandardContentCardType.BOOK),
        )

        assertEquals(
            listOf(
                StandardCollectionFilter.TRIVIA,
                StandardCollectionFilter.BOOK,
                StandardCollectionFilter.HISTORY,
            ),
            availableStandardCollectionFilters(entries),
        )
    }

    @Test
    fun categoryEntriesSortByRarityThenObtainedStateThenStableOrder() {
        val entries = listOf(
            entry(
                id = "history.special.obtained",
                type = StandardContentCardType.HISTORY,
                rarity = StandardContentRarity.SPECIAL,
                sortOrder = 0,
            ),
            entry(
                id = "history.common.unobtained",
                type = StandardContentCardType.HISTORY,
                rarity = StandardContentRarity.COMMON,
                sortOrder = 1,
            ),
            entry(
                id = "history.rare.unobtained",
                type = StandardContentCardType.HISTORY,
                rarity = StandardContentRarity.RARE,
                sortOrder = 0,
            ),
            entry(
                id = "history.common.obtained.second",
                type = StandardContentCardType.HISTORY,
                rarity = StandardContentRarity.COMMON,
                sortOrder = 2,
            ),
            entry(
                id = "history.common.obtained.first",
                type = StandardContentCardType.HISTORY,
                rarity = StandardContentRarity.COMMON,
                sortOrder = 0,
            ),
            entry(
                id = "history.rare.obtained",
                type = StandardContentCardType.HISTORY,
                rarity = StandardContentRarity.RARE,
                sortOrder = 10,
            ),
            entry("book.001", StandardContentCardType.BOOK),
        )
        val obtained = setOf(
            "history.special.obtained",
            "history.common.obtained.second",
            "history.common.obtained.first",
            "history.rare.obtained",
        )

        assertEquals(
            listOf(
                "history.common.obtained.first",
                "history.common.obtained.second",
                "history.common.unobtained",
                "history.rare.obtained",
                "history.rare.unobtained",
                "history.special.obtained",
            ),
            filterStandardCollectionEntries(
                entries = entries,
                obtainedCardIds = obtained,
                filter = StandardCollectionFilter.HISTORY,
            ).map { it.card.id },
        )
        assertEquals(4, standardCollectionObtainedCount(entries, obtained))
    }

    private fun entry(
        id: String,
        type: StandardContentCardType,
        rarity: StandardContentRarity = StandardContentRarity.COMMON,
        sortOrder: Int? = null,
    ) = StandardContentEntry(
        packId = type.wireName,
        card = StandardContentCard(
            id = id,
            type = type,
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
            sortOrder = sortOrder,
        ),
        imageFile = null,
    )
}
