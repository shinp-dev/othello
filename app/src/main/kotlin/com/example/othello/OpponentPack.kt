package com.example.othello

import com.example.othello.analysis.api.StandardAiConfig
import java.io.File
import java.time.Instant

/** IDs, unlike names, order, and tuning, must never change meaning after publication. */
internal data class PlayerKey(val packId: String, val playerId: String)

internal data class OpponentText(val translations: Map<String, String>) {
    fun resolve(language: String): String = translations[language] ?: translations.getValue("en")
}

internal enum class OpponentHomeSection { FEATURED, CHALLENGES }
internal enum class OpponentHomeStyle { HERO, COMPACT }
internal enum class OpponentUnlockCelebration { NORMAL, MILESTONE }

internal data class OpponentHomePlacement(
    val section: OpponentHomeSection,
    val style: OpponentHomeStyle,
    val order: Int,
)

internal data class Player(
    val id: String,
    val name: OpponentText,
    val order: Int,
    val requires: Set<String>,
    val ai: StandardAiConfig,
    val portrait: String,
    val winImage: String,
    val loseImage: String,
    val unlockCelebration: OpponentUnlockCelebration,
)

internal data class OpponentPack(
    val id: String,
    val version: Int,
    val title: OpponentText,
    val description: OpponentText,
    val banner: String,
    val home: OpponentHomePlacement,
    val players: List<Player>,
    val availableFrom: Instant? = null,
    val availableUntil: Instant? = null,
) {
    fun player(id: String): Player? = players.find { it.id == id }
    fun isVisible(now: Instant): Boolean =
        (availableFrom == null || now >= availableFrom) && (availableUntil == null || now < availableUntil)
    val assetPaths: Set<String> get() = setOf(banner) + players.flatMap { listOf(it.portrait, it.winImage, it.loseImage) }
}

internal data class InstalledOpponentPack(val definition: OpponentPack, val directory: File) {
    fun image(path: String): File {
        require(path in definition.assetPaths)
        return File(directory, path)
    }
}

internal data class OpponentPackSnapshot(val packs: List<InstalledOpponentPack>) {
    fun visiblePacks(now: Instant = Instant.now()): List<InstalledOpponentPack> = packs
        .filter { it.definition.isVisible(now) }
        .sortedWith(compareBy({ it.definition.home.section.ordinal }, { it.definition.home.order }, { it.definition.id }))
    fun pack(id: String): InstalledOpponentPack? = packs.find { it.definition.id == id }
}
