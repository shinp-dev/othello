package com.example.othello

internal enum class StandardCollectionFilter(
    val cardType: StandardContentCardType? = null,
) {
    ALL,
    TRIVIA(StandardContentCardType.TRIVIA),
    BOOK(StandardContentCardType.BOOK),
    HISTORY(StandardContentCardType.HISTORY),
    PERSON(StandardContentCardType.PERSON),
    COLLAB(StandardContentCardType.COLLAB),
    UNOBTAINED,
}

internal fun availableStandardCollectionFilters(
    entries: List<StandardContentEntry>,
): List<StandardCollectionFilter> {
    val presentTypes = entries.mapTo(mutableSetOf()) { it.card.type }
    return buildList {
        add(StandardCollectionFilter.ALL)
        listOf(
            StandardCollectionFilter.TRIVIA,
            StandardCollectionFilter.BOOK,
            StandardCollectionFilter.HISTORY,
            StandardCollectionFilter.PERSON,
            StandardCollectionFilter.COLLAB,
        ).forEach { filter ->
            if (filter.cardType in presentTypes) add(filter)
        }
        if (entries.isNotEmpty()) add(StandardCollectionFilter.UNOBTAINED)
    }
}

internal fun filterStandardCollectionEntries(
    entries: List<StandardContentEntry>,
    obtainedCardIds: Set<String>,
    filter: StandardCollectionFilter,
): List<StandardContentEntry> = when (filter) {
    StandardCollectionFilter.ALL -> entries
    StandardCollectionFilter.UNOBTAINED -> entries.filter { it.card.id !in obtainedCardIds }
    else -> entries.filter { it.card.type == filter.cardType }
}

internal fun standardCollectionObtainedCount(
    entries: List<StandardContentEntry>,
    obtainedCardIds: Set<String>,
): Int = entries.count { it.card.id in obtainedCardIds }
