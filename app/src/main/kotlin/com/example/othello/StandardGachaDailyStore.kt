package com.example.othello

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

internal const val STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT = 3

internal data class StandardGachaDailyState(
    val day: LocalDate,
    val freeDrawsUsed: Int,
) {
    val remainingFreeDraws: Int
        get() = (STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT - freeDrawsUsed).coerceAtLeast(0)

    val canDrawForFree: Boolean
        get() = remainingFreeDraws > 0
}

/**
 * Device-local daily allowance for Standard gacha.
 *
 * A local calendar day runs from 00:00 through 23:59 in the device timezone. Moving the
 * device clock backwards never restores draws: only a date strictly after the latest stored
 * date starts a new allowance.
 *
 * This state is intentionally device-only and is removed when the app is uninstalled.
 */
internal class StandardGachaDailyStore internal constructor(
    private val preferences: StandardGachaDailyPreferences,
    private val todayProvider: () -> LocalDate,
) {
    constructor(context: Context) : this(
        SharedPreferencesStandardGachaDaily(
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        ),
        todayProvider = { LocalDate.now() },
    )

    fun state(userId: String): StandardGachaDailyState {
        require(userId.isNotBlank())
        val today = todayProvider()
        val storedDay = preferences.getString(dayKey(userId))
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val storedUsed = preferences
            .getInt(usedKey(userId), 0)
            .coerceIn(0, STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT)

        if (storedDay == null || today.isAfter(storedDay)) {
            return StandardGachaDailyState(day = today, freeDrawsUsed = 0)
                .also { persist(userId, it) }
        }

        return StandardGachaDailyState(
            day = storedDay,
            freeDrawsUsed = storedUsed,
        )
    }

    /**
     * Consumes one free draw and returns the updated state.
     * Returns null when today's free allowance has already been exhausted.
     */
    fun tryConsumeFreeDraw(userId: String): StandardGachaDailyState? {
        val current = state(userId)
        if (!current.canDrawForFree) return null

        val updated = current.copy(freeDrawsUsed = current.freeDrawsUsed + 1)
        persist(userId, updated)
        return updated
    }

    private fun persist(userId: String, state: StandardGachaDailyState) {
        preferences.putState(
            dayKey = dayKey(userId),
            dayValue = state.day.toString(),
            usedKey = usedKey(userId),
            usedValue = state.freeDrawsUsed,
        )
    }

    private fun dayKey(userId: String) = "$userId.day"
    private fun usedKey(userId: String) = "$userId.used"

    private companion object {
        const val PREFERENCES = "standard-gacha-daily"
    }
}

internal interface StandardGachaDailyPreferences {
    fun getString(key: String): String?
    fun getInt(key: String, defaultValue: Int): Int
    fun putState(
        dayKey: String,
        dayValue: String,
        usedKey: String,
        usedValue: Int,
    )
}

private class SharedPreferencesStandardGachaDaily(
    private val preferences: SharedPreferences,
) : StandardGachaDailyPreferences {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun getInt(key: String, defaultValue: Int): Int =
        preferences.getInt(key, defaultValue)

    override fun putState(
        dayKey: String,
        dayValue: String,
        usedKey: String,
        usedValue: Int,
    ) {
        preferences.edit()
            .putString(dayKey, dayValue)
            .putInt(usedKey, usedValue)
            .apply()
    }
}
