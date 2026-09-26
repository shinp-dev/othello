package com.example.othello

import androidx.annotation.DrawableRes
import com.example.othello.profile.PlayDisplayName
import kotlin.random.Random

internal data class ChanrivaNameCandidate(
    val id: Long,
    val displayName: String,
    val isRare: Boolean,
    @DrawableRes val plateRes: Int,
)

internal class InvalidPlayNameCatalogException(message: String) : IllegalStateException(message)

/** Draws exactly three distinct active rows, with a single independent 10% rare roll. */
internal fun drawChanrivaNameCandidates(
    activeNames: List<PlayDisplayName>,
    excludedIds: Set<Long> = emptySet(),
    rareRoll: () -> Double = { Random.nextDouble() },
    chooseDistinct: (List<PlayDisplayName>, Int) -> List<PlayDisplayName> = { pool, count ->
        pool.shuffled(Random.Default).take(count)
    },
): List<PlayDisplayName> {
    if (activeNames.any { it.id <= 0 || it.displayName.isBlank() }) {
        throw InvalidPlayNameCatalogException("The active play-name catalog contains an invalid row")
    }

    val available = activeNames
        .distinctBy(PlayDisplayName::id)
        .filterNot { it.id in excludedIds }
    val normalNames = available.filterNot(PlayDisplayName::isRare)
    val rareNames = available.filter(PlayDisplayName::isRare)
    if (normalNames.size < 3 || rareNames.isEmpty()) {
        throw InvalidPlayNameCatalogException("The active play-name catalog cannot safely produce three names")
    }

    val includeRare = rareRoll() < RARE_CHANCE
    val selectedNormals = chooseDistinct(normalNames, if (includeRare) 2 else 3)
    val selectedRare = if (includeRare) chooseDistinct(rareNames, 1) else emptyList()
    val selected = selectedNormals + selectedRare
    if (selected.size != 3 || selected.map(PlayDisplayName::id).distinct().size != 3) {
        throw InvalidPlayNameCatalogException("The play-name draw did not produce three distinct rows")
    }
    if (selected.any { candidate -> available.none { it.id == candidate.id && it == candidate } }) {
        throw InvalidPlayNameCatalogException("The play-name draw returned a row outside the active catalog")
    }
    if (selected.take(2).any(PlayDisplayName::isRare) || selected.drop(2).any { it.isRare != includeRare }) {
        throw InvalidPlayNameCatalogException("Rare play names may appear only as the third candidate")
    }
    return selected
}

internal fun List<PlayDisplayName>.toChanrivaNameCandidates(): List<ChanrivaNameCandidate> = mapIndexed { index, name ->
    ChanrivaNameCandidate(
        id = name.id,
        displayName = name.displayName,
        isRare = name.isRare,
        plateRes = when (index) {
            0 -> R.drawable.chanriva_name_plate_emerald
            1 -> R.drawable.chanriva_name_plate_starry
            else -> if (name.isRare) R.drawable.chanriva_name_plate_rare else R.drawable.chanriva_name_plate_emerald
        },
    )
}

private const val RARE_CHANCE = 0.10
