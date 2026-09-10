package com.example.othello

import android.content.Context
import android.content.SharedPreferences

/**
 * Device-only collection state.
 *
 * Pack versions are intentionally absent from this store. A published card id keeps the
 * same meaning forever, so a pack refresh never needs to migrate collection state.
 */
internal class StandardCollectionStore internal constructor(
    private val preferences: StandardCollectionPreferences,
) {
    constructor(context: Context) : this(
        SharedPreferencesStandardCollection(
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        ),
    )

    fun obtainedCardIds(userId: String): Set<String> {
        require(userId.isNotBlank())
        return preferences.getStringSet(obtainedKey(userId)).orEmpty().toSet()
    }

    fun isObtained(userId: String, cardId: String): Boolean =
        cardId in obtainedCardIds(userId)

    /**
     * Returns true only when this call adds a new card id.
     */
    fun markObtained(userId: String, cardId: String): Boolean {
        require(userId.isNotBlank())
        require(CARD_ID_REGEX.matches(cardId)) { "Invalid Standard content card id" }
        val current = obtainedCardIds(userId)
        if (cardId in current) return false
        preferences.putStringSet(obtainedKey(userId), current + cardId)
        return true
    }

    private fun obtainedKey(userId: String) = "$userId.obtained"

    private companion object {
        const val PREFERENCES = "standard-content-collection"
        val CARD_ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    }
}

internal interface StandardCollectionPreferences {
    fun getStringSet(key: String): Set<String>?
    fun putStringSet(key: String, value: Set<String>)
}

private class SharedPreferencesStandardCollection(
    private val preferences: SharedPreferences,
) : StandardCollectionPreferences {
    override fun getStringSet(key: String): Set<String>? =
        preferences.getStringSet(key, null)?.toSet()

    override fun putStringSet(key: String, value: Set<String>) {
        preferences.edit().putStringSet(key, value.toSet()).apply()
    }
}
