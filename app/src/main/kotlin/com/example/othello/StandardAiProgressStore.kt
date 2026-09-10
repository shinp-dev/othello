package com.example.othello

import android.content.Context
import android.content.SharedPreferences
import com.example.othello.analysis.api.StandardAiLevel

data class StandardAiProgress(
    val highestUnlockedLevel: StandardAiLevel = StandardAiLevel.LV1,
    val clearedLevels: Set<StandardAiLevel> = emptySet(),
) {
    fun isUnlocked(level: StandardAiLevel): Boolean = level.ordinal <= highestUnlockedLevel.ordinal
    fun isCleared(level: StandardAiLevel): Boolean = level in clearedLevels
    fun nextChallenge(): StandardAiLevel =
        StandardAiLevel.entries.firstOrNull { isUnlocked(it) && !isCleared(it) } ?: highestUnlockedLevel
    val conquered: Boolean get() = StandardAiLevel.LV8 in clearedLevels
}

/** Device-only Standard progression, namespaced by authenticated Supabase user ID. */
class StandardAiProgressStore internal constructor(
    private val preferences: StandardAiProgressPreferences,
) {
    constructor(context: Context) : this(
        SharedPreferencesStandardAiProgress(
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        ),
    )

    fun progress(userId: String): StandardAiProgress {
        require(userId.isNotBlank())
        val storedHighest = preferences.getInt(highestKey(userId), StandardAiLevel.LV1.value)
            .coerceIn(StandardAiLevel.LV1.value, StandardAiLevel.LV8.value)
        val cleared = preferences.getString(clearedKey(userId), "").orEmpty()
            .split(',')
            .mapNotNull(String::toIntOrNull)
            .filter { it in StandardAiLevel.LV1.value..StandardAiLevel.LV8.value }
            .map(StandardAiLevel::fromValue)
            .toSet()
        val inferredHighest = cleared.mapNotNull(StandardAiLevel::next)
            .maxByOrNull(StandardAiLevel::value)
            ?.value
            ?: StandardAiLevel.LV1.value
        val highest = maxOf(storedHighest, inferredHighest)
        return StandardAiProgress(
            highestUnlockedLevel = StandardAiLevel.fromValue(highest),
            clearedLevels = cleared.filterTo(mutableSetOf()) { it.value <= highest },
        )
    }

    fun recordResult(
        userId: String,
        level: StandardAiLevel,
        humanWon: Boolean,
        undoUsed: Boolean,
    ): StandardAiProgress {
        val current = progress(userId)
        if (!humanWon || undoUsed || !current.isUnlocked(level)) return current
        val nextHighest = level.next()?.takeIf { it.ordinal > current.highestUnlockedLevel.ordinal }
            ?: current.highestUnlockedLevel
        val updated = StandardAiProgress(
            highestUnlockedLevel = nextHighest,
            clearedLevels = current.clearedLevels + level,
        )
        preferences.putInt(highestKey(userId), updated.highestUnlockedLevel.value)
        preferences.putString(
            clearedKey(userId),
            updated.clearedLevels.sortedBy { it.value }.joinToString(",") { it.value.toString() },
        )
        return updated
    }

    private fun highestKey(userId: String) = "$userId.highest_unlocked"
    private fun clearedKey(userId: String) = "$userId.cleared"

    private companion object {
        const val PREFERENCES = "standard-ai-progress"
    }
}

internal interface StandardAiProgressPreferences {
    fun getInt(key: String, defaultValue: Int): Int
    fun getString(key: String, defaultValue: String): String?
    fun putInt(key: String, value: Int)
    fun putString(key: String, value: String)
}

private class SharedPreferencesStandardAiProgress(
    private val preferences: SharedPreferences,
) : StandardAiProgressPreferences {
    override fun getInt(key: String, defaultValue: Int): Int = preferences.getInt(key, defaultValue)
    override fun getString(key: String, defaultValue: String): String? = preferences.getString(key, defaultValue)
    override fun putInt(key: String, value: Int) { preferences.edit().putInt(key, value).apply() }
    override fun putString(key: String, value: String) { preferences.edit().putString(key, value).apply() }
}
