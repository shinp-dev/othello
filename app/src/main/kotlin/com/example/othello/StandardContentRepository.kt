package com.example.othello

import java.io.File

internal data class StandardContentEntry(
    val packId: String,
    val card: StandardContentCard,
    val imageFile: File?,
)

internal data class StandardContentSnapshot(
    val entries: List<StandardContentEntry>,
) {
    private val byId: Map<String, StandardContentEntry> = entries.associateBy { it.card.id }

    fun card(cardId: String): StandardContentEntry? = byId[cardId]

    fun cards(type: StandardContentCardType): List<StandardContentEntry> =
        entries.filter { it.card.type == type }
}

internal class StandardContentRepository(
    private val packManager: StandardContentPackManager,
) {
    fun snapshot(): StandardContentSnapshot {
        val entries = packManager.activePackIds()
            .sorted()
            .flatMap { packId ->
                val pack = packManager.loadActivePack(packId)
                    ?: throw StandardContentFormatException("Active Standard content pack disappeared")
                pack.cards.map { card ->
                    StandardContentEntry(
                        packId = packId,
                        card = card,
                        imageFile = card.imagePath?.let { File(pack.directory, it) },
                    )
                }
            }

        val duplicateIds = entries.groupBy { it.card.id }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            throw StandardContentFormatException(
                "Duplicate card ids across active packs: ${duplicateIds.sorted().joinToString()}",
            )
        }

        return StandardContentSnapshot(
            entries = entries.sortedWith(
                compareBy<StandardContentEntry>(
                    { it.card.sortOrder ?: Int.MAX_VALUE },
                    { it.card.id },
                ),
            ),
        )
    }
}
