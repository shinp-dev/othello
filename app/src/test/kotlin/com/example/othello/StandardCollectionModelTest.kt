package com.example.othello

import kotlin.test.assertEquals
import org.junit.Test

class StandardCollectionModelTest {
    @Test
    fun availableFiltersFollowPackContentsInsteadOfShowingEmptyFutureCategories() {
        val entries = listOf(
            entry("trivia.001", StandardContentCardType.TRIVIA),
            entry("history.001", StandardContentCardType.HISTORY),
            entry("book.001", StandardContentCardType.BOOK),
        )

        assertEquals(
            listOf(
                StandardCollectionFilter.ALL,
                StandardCollectionFilter.TRIVIA,
                StandardCollectionFilter.BOOK,
                StandardCollectionFilter.HISTORY,
                StandardCollectionFilter.UNOBTAINED,
            ),
            availableStandardCollectionFilters(entries),
        )
    }

    @Test
    fun filteringUsesStableCardIdsAndNeverPackVersions() {
        val entries = listOf(
            entry("trivia.001", StandardContentCardType.TRIVIA),
            entry("book.001", StandardContentCardType.BOOK),
            entry("book.002", StandardContentCardType.BOOK),
        )
        val obtained = setOf("trivia.001", "book.002")

        assertEquals(
            listOf("book.001"),
            filterStandardCollectionEntries(
                entries,
                obtained,
                StandardCollectionFilter.UNOBTAINED,
            ).map { it.card.id },
        )
        assertEquals(
            listOf("book.001", "book.002"),
            filterStandardCollectionEntries(
                entries,
                obtained,
                StandardCollectionFilter.BOOK,
            ).map { it.card.id },
        )
        assertEquals(2, standardCollectionObtainedCount(entries, obtained))
    }

    private fun entry(
        id: String,
        type: StandardContentCardType,
    ) = StandardContentEntry(
        packId = type.wireName,
        card = StandardContentCard(
            id = id,
            type = type,
            rarity = StandardContentRarity.COMMON,
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
