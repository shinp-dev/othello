package com.example.othello

import kotlin.test.*
import org.junit.Test

class StandardAiProgressStoreTest {
    private val pack = animalPack()
    private val chick = pack.players.first()
    private val rabbit = pack.players[1]

    @Test fun cleanStartAndNoUndoWinUnlockTheNextPlayer() {
        val store = StandardAiProgressStore(MemoryPreferences())
        assertEquals(chick, store.progress("user", pack).nextChallenge())
        assertFalse(store.progress("user", pack).isUnlocked(rabbit))
        val won = store.recordResult("user", pack, chick, true, false)
        assertTrue(won.isCleared(chick))
        assertTrue(won.isUnlocked(rabbit))
        assertEquals(rabbit.id, store.progress("user", pack).nextChallenge().id)
    }

    @Test fun undoLossAndLockedOpponentNeverGrantProgress() {
        val store = StandardAiProgressStore(MemoryPreferences())
        assertFalse(store.recordResult("user", pack, chick, true, true).isCleared(chick))
        assertFalse(store.recordResult("user", pack, chick, false, false).isCleared(chick))
        assertFalse(store.recordResult("user", pack, rabbit, true, false).isCleared(rabbit))
    }

    @Test fun migratesLegacyOnceAndKeepsOriginalPreferences() {
        val prefs = MemoryPreferences().apply { values["user.cleared"] = "1,2,3,4"; values["user.highest_unlocked"] = 4 }
        val store = StandardAiProgressStore(prefs)
        assertEquals("wild-chick", store.progress("user", pack).nextChallenge().id)
        prefs.values["user.cleared"] = "1,2,3,4,5,6,7,8"
        assertFalse(store.progress("user", pack).conquered)
        assertEquals(4, prefs.values["user.highest_unlocked"])
    }

    @Test fun userAndPackNamespacesAreIndependentEvenWithSamePlayerIds() {
        val store = StandardAiProgressStore(MemoryPreferences())
        val other = pack.copy(id = "beginner")
        store.recordResult("user", pack, chick, true, false)
        assertFalse(store.progress("user", other).isCleared(chick))
        assertFalse(store.progress("other", pack).isCleared(chick))
    }

    @Test fun updatingReorderingRemovingAndRestoringPlayersKeepsProgress() {
        val store = StandardAiProgressStore(MemoryPreferences())
        store.recordResult("user", pack, chick, true, false)
        val changed = pack.copy(version = 2, players = pack.players.reversed().filterNot { it.id == chick.id })
        val progress = store.progress("user", changed)
        assertTrue(chick.id in progress.clearedPlayerIds)
        assertTrue(progress.isUnlocked(rabbit))
        assertTrue(store.progress("user", pack.copy(version = 3)).isCleared(chick))
    }

    @Test fun branchesAndAllPrerequisitesAreSupportedAndConquestIsPackLocal() {
        val final = pack.players[2].copy(requires = setOf(chick.id, rabbit.id))
        val branched = pack.copy(id = "branches", players = listOf(chick, rabbit.copy(requires = emptySet()), final))
        val store = StandardAiProgressStore(MemoryPreferences())
        assertTrue(store.progress("u", branched).isUnlocked(rabbit))
        assertFalse(store.recordResult("u", branched, chick, true, false).isUnlocked(final))
        assertTrue(store.recordResult("u", branched, rabbit, true, false).isUnlocked(final))
        assertTrue(store.recordResult("u", branched, final, true, false).conquered)
        assertFalse(store.progress("u", pack).conquered)
    }

    private class MemoryPreferences : StandardAiProgressPreferences {
        val values = mutableMapOf<String, Any>()
        override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
    }
}
