package com.example.othello

internal enum class StandardCollectionFilter(
    val cardType: StandardContentCardType,
) {
    TRIVIA(StandardContentCardType.TRIVIA),
    BOOK(StandardContentCardType.BOOK),
    HISTORY(StandardContentCardType.HISTORY),
    PERSON(StandardContentCardType.PERSON),
    COLLAB(StandardContentCardType.COLLAB),
}

internal fun availableStandardCollectionFilters(
    entries: List<StandardContentEntry>,
): List<StandardCollectionFilter> {
    val presentTypes = entries.mapTo(mutableSetOf()) { it.card.type }
    return listOf(
        StandardCollectionFilter.TRIVIA,
        StandardCollectionFilter.BOOK,
        StandardCollectionFilter.HISTORY,
        StandardCollectionFilter.PERSON,
        StandardCollectionFilter.COLLAB,
    ).filter { it.cardType in presentTypes }
}

internal fun filterStandardCollectionEntries(
    entries: List<StandardContentEntry>,
    obtainedCardIds: Set<String>,
    filter: StandardCollectionFilter,
): List<StandardContentEntry> = entries
    .asSequence()
    .filter { it.card.type == filter.cardType }
    .sortedWith(
        compareBy<StandardContentEntry>(
            { it.card.rarity.collectionSortOrder },
            { if (it.card.id in obtainedCardIds) 0 else 1 },
            { it.card.sortOrder ?: Int.MAX_VALUE },
            { it.card.id },
        ),
    )
    .toList()

internal fun standardCollectionObtainedCount(
    entries: List<StandardContentEntry>,
    obtainedCardIds: Set<String>,
): Int = entries.count { it.card.id in obtainedCardIds }

private val StandardContentRarity.collectionSortOrder: Int
    get() = when (this) {
        StandardContentRarity.COMMON -> 0
        StandardContentRarity.RARE -> 1
        StandardContentRarity.SPECIAL -> 2
    }
