package com.example.othello

import android.content.Context

/** Remembers which Standard AI opponent introductions this signed-in user has already seen. */
internal class StandardAiIntroStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasSeen(userId: String, player: PlayerKey): Boolean {
        require(userId.isNotBlank())
        val legacyIndex = if (player.packId == "animal") LegacyAnimalPlayerIds.indexOf(player.playerId) else -1
        return preferences.getBoolean(key(userId, player), false) ||
            (legacyIndex >= 0 && preferences.getBoolean("$userId.intro.${legacyIndex + 1}", false))
    }

    fun markSeen(userId: String, player: PlayerKey) {
        require(userId.isNotBlank())
        preferences.edit().putBoolean(key(userId, player), true).apply()
    }

    private fun key(userId: String, player: PlayerKey): String = "$userId.pack.${player.packId}.intro.${player.playerId}"

    private companion object {
        const val PREFERENCES = "standard-ai-intro"
    }
}
