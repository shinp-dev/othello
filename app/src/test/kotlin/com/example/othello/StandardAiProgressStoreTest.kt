package com.example.othello

import com.example.othello.analysis.api.StandardAiLevel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardAiProgressStoreTest {
    @Test
    fun onlyLevelOneIsInitiallyUnlocked() {
        val progress = store().progress("user-a")

        assertTrue(progress.isUnlocked(StandardAiLevel.LV1))
        StandardAiLevel.entries.drop(1).forEach { assertFalse(progress.isUnlocked(it)) }
        assertEquals(StandardAiLevel.LV1, progress.nextChallenge())
    }

    @Test
    fun noUndoWinUnlocksTheNextLevelAndUnlockedLevelsRemainSelectable() {
        val store = store()

        val afterLevelOne = store.recordResult("user", StandardAiLevel.LV1, humanWon = true, undoUsed = false)
        val reloaded = store.progress("user")

        assertTrue(afterLevelOne.isCleared(StandardAiLevel.LV1))
        assertTrue(reloaded.isUnlocked(StandardAiLevel.LV1))
        assertTrue(reloaded.isUnlocked(StandardAiLevel.LV2))
        assertEquals(StandardAiLevel.LV2, reloaded.nextChallenge())
    }

    @Test
    fun undoWinAndLossDoNotUnlockTheNextLevel() {
        val undoWin = store().recordResult("user", StandardAiLevel.LV1, humanWon = true, undoUsed = true)
        val loss = store().recordResult("other", StandardAiLevel.LV1, humanWon = false, undoUsed = false)

        assertEquals(StandardAiLevel.LV1, undoWin.highestUnlockedLevel)
        assertEquals(StandardAiLevel.LV1, loss.highestUnlockedLevel)
        assertFalse(undoWin.isCleared(StandardAiLevel.LV1))
    }

    @Test
    fun levelFourWinUnlocksLevelFiveAndLevelSevenWinUnlocksLevelEight() {
        val store = store()
        StandardAiLevel.entries.take(3).forEach { level ->
            store.recordResult("winner", level, humanWon = true, undoUsed = false)
        }

        val afterLevelFour = store.recordResult(
            "winner",
            StandardAiLevel.LV4,
            humanWon = true,
            undoUsed = false,
        )
        StandardAiLevel.entries.slice(4..5).forEach { level ->
            store.recordResult("winner", level, humanWon = true, undoUsed = false)
        }
        val afterLevelSeven = store.recordResult(
            "winner",
            StandardAiLevel.LV7,
            humanWon = true,
            undoUsed = false,
        )

        assertTrue(afterLevelFour.isUnlocked(StandardAiLevel.LV5))
        assertTrue(afterLevelSeven.isUnlocked(StandardAiLevel.LV8))
        assertFalse(afterLevelSeven.conquered)
    }

    @Test
    fun legacyLevelFourCompletionMigratesToUnlockedLevelFive() {
        val preferences = MemoryPreferences().apply {
            putInt("user.highest_unlocked", 4)
            putString("user.cleared", "1,2,3,4")
        }

        val progress = StandardAiProgressStore(preferences).progress("user")

        assertEquals(StandardAiLevel.LV5, progress.highestUnlockedLevel)
        assertEquals(StandardAiLevel.LV5, progress.nextChallenge())
    }

    @Test
    fun progressionStopsAtLevelEightAndIsNamespacedByUser() {
        val store = store()
        StandardAiLevel.entries.forEach { level ->
            store.recordResult("winner", level, humanWon = true, undoUsed = false)
        }

        val winner = store.progress("winner")
        val other = store.progress("other")

        assertEquals(StandardAiLevel.LV8, winner.highestUnlockedLevel)
        assertTrue(winner.conquered)
        assertEquals(StandardAiLevel.LV1, other.highestUnlockedLevel)
        assertFalse(other.conquered)
    }

    private fun store() = StandardAiProgressStore(MemoryPreferences())

    private class MemoryPreferences : StandardAiProgressPreferences {
        private val values = mutableMapOf<String, Any>()
        override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        override fun getString(key: String, defaultValue: String): String? = values[key] as? String ?: defaultValue
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun putString(key: String, value: String) { values[key] = value }
    }
}
