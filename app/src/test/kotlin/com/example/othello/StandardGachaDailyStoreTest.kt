package com.example.othello

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class StandardGachaDailyStoreTest {
    @Test
    fun allowsConfiguredFreeDrawsPerLocalDay() {
        val preferences = MemoryPreferences()
        var today = LocalDate.of(2026, 9, 11)
        val store = StandardGachaDailyStore(preferences) { today }

        repeat(STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT) {
            assertNotNull(store.tryConsumeFreeDraw("user"))
        }

        val exhausted = store.state("user")
        assertEquals(STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT, exhausted.freeDrawsUsed)
        assertEquals(0, exhausted.remainingFreeDraws)
        assertFalse(exhausted.canDrawForFree)
        assertNull(store.tryConsumeFreeDraw("user"))
    }

    @Test
    fun nextLocalDateRestoresAllowance() {
        val preferences = MemoryPreferences()
        var today = LocalDate.of(2026, 9, 11)
        val store = StandardGachaDailyStore(preferences) { today }

        repeat(STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT) { assertNotNull(store.tryConsumeFreeDraw("user")) }
        today = LocalDate.of(2026, 9, 12)

        val reset = store.state("user")
        assertEquals(LocalDate.of(2026, 9, 12), reset.day)
        assertEquals(0, reset.freeDrawsUsed)
        assertEquals(STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT, reset.remainingFreeDraws)
        assertTrue(reset.canDrawForFree)
    }

    @Test
    fun movingClockBackwardsDoesNotRestoreDraws() {
        val preferences = MemoryPreferences()
        var today = LocalDate.of(2026, 9, 11)
        val store = StandardGachaDailyStore(preferences) { today }

        repeat(3) { assertNotNull(store.tryConsumeFreeDraw("user")) }
        today = LocalDate.of(2026, 9, 10)

        val rolledBack = store.state("user")
        assertEquals(LocalDate.of(2026, 9, 11), rolledBack.day)
        assertEquals(STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT, rolledBack.freeDrawsUsed)
        assertFalse(rolledBack.canDrawForFree)
    }

    @Test
    fun allowanceIsNamespacedByUser() {
        val preferences = MemoryPreferences()
        val today = LocalDate.of(2026, 9, 11)
        val store = StandardGachaDailyStore(preferences) { today }

        assertNotNull(store.tryConsumeFreeDraw("user-a"))

        assertEquals(STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT - 1, store.state("user-a").remainingFreeDraws)
        assertEquals(STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT, store.state("user-b").remainingFreeDraws)
    }

    private class MemoryPreferences : StandardGachaDailyPreferences {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()

        override fun getString(key: String): String? = strings[key]

        override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue

        override fun putState(
            dayKey: String,
            dayValue: String,
            usedKey: String,
            usedValue: Int,
        ) {
            strings[dayKey] = dayValue
            ints[usedKey] = usedValue
        }
    }
}
