package com.example.othello

import kotlin.random.Random

internal data class StandardGachaDraw(
    val entry: StandardContentEntry,
    val isNew: Boolean,
)

internal class StandardGachaEngine(
    private val randomUnit: () -> Double = { Random.Default.nextDouble() },
) {
    fun draw(
        entries: List<StandardContentEntry>,
        obtainedCardIds: Set<String>,
    ): StandardGachaDraw? {
        if (entries.isEmpty()) return null

        val uncollected = entries.filter { it.card.id !in obtainedCardIds }
        val pool = uncollected.ifEmpty { entries }
        val byRarity = pool.groupBy { it.card.rarity }
        val availableWeights = rarityWeights.filter { (rarity, _) ->
            byRarity[rarity].orEmpty().isNotEmpty()
        }

        val totalWeight = availableWeights.sumOf { it.second }
        val rarityRoll = unitValue() * totalWeight.toDouble()
        var accumulated = 0.0
        val selectedRarity = availableWeights.firstOrNull { (_, weight) ->
            accumulated += weight
            rarityRoll < accumulated
        }?.first ?: availableWeights.last().first

        val candidates = requireNotNull(byRarity[selectedRarity]).sortedBy { it.card.id }
        val candidateIndex = (unitValue() * candidates.size)
            .toInt()
            .coerceIn(0, candidates.lastIndex)
        val entry = candidates[candidateIndex]

        return StandardGachaDraw(
            entry = entry,
            isNew = entry.card.id !in obtainedCardIds,
        )
    }

    private fun unitValue(): Double =
        randomUnit().coerceIn(0.0, 0.9999999999999999)

    private companion object {
        val rarityWeights = listOf(
            StandardContentRarity.COMMON to 70,
            StandardContentRarity.RARE to 25,
            StandardContentRarity.SPECIAL to 5,
        )
    }
}
