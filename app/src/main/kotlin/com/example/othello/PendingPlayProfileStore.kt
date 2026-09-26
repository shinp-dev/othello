package com.example.othello

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal durable outbox record for a name the user has already confirmed. */
internal interface PendingPlayProfileStore {
    suspend fun load(userId: String): Long?
    suspend fun save(userId: String, displayNameId: Long): Boolean
    suspend fun clear(userId: String): Boolean
}

internal class SharedPreferencesPendingPlayProfileStore(context: Context) : PendingPlayProfileStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun load(userId: String): Long? = withContext(Dispatchers.IO) {
        val key = key(userId)
        if (!preferences.contains(key)) return@withContext null
        preferences.getLong(key, 0L).takeIf { it > 0L }
            ?: throw IllegalStateException("Stored pending play-profile id is invalid")
    }

    override suspend fun save(userId: String, displayNameId: Long): Boolean = withContext(Dispatchers.IO) {
        require(displayNameId > 0L)
        preferences.edit().putLong(key(userId), displayNameId).commit()
    }

    override suspend fun clear(userId: String): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().remove(key(userId)).commit()
    }

    private fun key(userId: String): String {
        require(userId.isNotBlank())
        return "pending_display_name_id_$userId"
    }

    private companion object {
        const val PREFERENCES = "pending_play_profile_v1"
    }
}
