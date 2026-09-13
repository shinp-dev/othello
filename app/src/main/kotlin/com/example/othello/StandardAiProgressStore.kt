package com.example.othello

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.*

internal data class StandardAiProgress(
    val pack: OpponentPack,
    val clearedPlayerIds: Set<String> = emptySet(),
    val unlockedPlayerIds: Set<String> = emptySet(),
) {
    fun isUnlocked(player: Player): Boolean = pack.player(player.id) != null &&
        (player.id in unlockedPlayerIds || player.id in clearedPlayerIds || player.requires.all { it in clearedPlayerIds })
    fun isCleared(player: Player): Boolean = player.id in clearedPlayerIds
    fun nextChallenge(): Player = pack.players.firstOrNull { isUnlocked(it) && !isCleared(it) }
        ?: pack.players.lastOrNull(::isUnlocked) ?: pack.players.first()
    val conquered: Boolean get() = pack.players.all(::isCleared)
}

/** Device-only progress. Versions and display order never participate in persistence keys. */
internal class StandardAiProgressStore(private val preferences: StandardAiProgressPreferences) {
    constructor(context: Context) : this(SharedPreferencesStandardAiProgress(
        context.applicationContext.getSharedPreferences("standard-ai-progress", Context.MODE_PRIVATE),
    ))

    @Synchronized
    fun progress(userId: String, pack: OpponentPack): StandardAiProgress {
        require(userId.isNotBlank())
        val key = "$userId.pack.${pack.id}.v2"
        val stored = preferences.getString(key, "").orEmpty()
        val saved = if (stored.isEmpty()) null else Json.parseToJsonElement(stored).jsonObject
        fun ids(field: String) = saved?.getValue(field)?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        val legacy = if (saved == null && pack.id == "animal") legacyAnimal(userId) else emptySet<String>() to emptySet()
        val current = StandardAiProgress(pack, ids("cleared") + legacy.first, ids("unlocked") + legacy.second)
        val updated = current.copy(unlockedPlayerIds = current.unlockedPlayerIds + pack.players.filter(current::isUnlocked).map { it.id })
        save(key, updated)
        return updated
    }

    @Synchronized
    fun recordResult(userId: String, pack: OpponentPack, player: Player, humanWon: Boolean, undoUsed: Boolean): StandardAiProgress {
        val current = progress(userId, pack)
        if (!humanWon || undoUsed || !current.isUnlocked(player)) return current
        val cleared = current.copy(clearedPlayerIds = current.clearedPlayerIds + player.id)
        val updated = cleared.copy(unlockedPlayerIds = cleared.unlockedPlayerIds + pack.players.filter(cleared::isUnlocked).map { it.id })
        save("$userId.pack.${pack.id}.v2", updated)
        return updated
    }

    private fun save(key: String, progress: StandardAiProgress) {
        val json = buildJsonObject {
            put("cleared", JsonArray(progress.clearedPlayerIds.sorted().map(::JsonPrimitive)))
            put("unlocked", JsonArray(progress.unlockedPlayerIds.sorted().map(::JsonPrimitive)))
        }.toString()
        // One preference value; legacy keys and removed-player progress remain intact.
        if (preferences.getString(key, "") != json) preferences.putString(key, json)
    }

    private fun legacyAnimal(user: String): Pair<Set<String>, Set<String>> {
        val cleared = preferences.getString("$user.cleared", "").orEmpty().split(',')
            .mapNotNull(String::toIntOrNull).filter { it in 1..8 }
        val highest = maxOf(preferences.getInt("$user.highest_unlocked", 1).coerceIn(1, 8),
            cleared.maxOrNull()?.let { (it + 1).coerceAtMost(8) } ?: 1)
        return cleared.map { LegacyAnimalPlayerIds[it - 1] }.toSet() to LegacyAnimalPlayerIds.take(highest).toSet()
    }
}

/** Only migration knows the old level ordering. Never use this to build gameplay or UI. */
internal val LegacyAnimalPlayerIds = listOf("chick", "rabbit", "koala", "elephant", "wild-chick", "wild-rabbit", "wild-koala", "wild-elephant")

internal interface StandardAiProgressPreferences {
    fun getInt(key: String, defaultValue: Int): Int
    fun getString(key: String, defaultValue: String): String?
    fun putString(key: String, value: String)
}

private class SharedPreferencesStandardAiProgress(private val preferences: SharedPreferences) : StandardAiProgressPreferences {
    override fun getInt(key: String, defaultValue: Int) = preferences.getInt(key, defaultValue)
    override fun getString(key: String, defaultValue: String) = preferences.getString(key, defaultValue)
    override fun putString(key: String, value: String) { preferences.edit().putString(key, value).apply() }
}
