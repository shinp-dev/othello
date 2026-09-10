package com.example.othello

import android.content.Context
import com.example.othello.analysis.api.StandardAiLevel

/** Remembers which Standard AI opponent introductions this signed-in user has already seen. */
internal class StandardAiIntroStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasSeen(userId: String, level: StandardAiLevel): Boolean {
        require(userId.isNotBlank())
        return preferences.getBoolean(key(userId, level), false)
    }

    fun markSeen(userId: String, level: StandardAiLevel) {
        require(userId.isNotBlank())
        preferences.edit().putBoolean(key(userId, level), true).apply()
    }

    private fun key(userId: String, level: StandardAiLevel): String = "$userId.intro.${level.value}"

    private companion object {
        const val PREFERENCES = "standard-ai-intro"
    }
}
