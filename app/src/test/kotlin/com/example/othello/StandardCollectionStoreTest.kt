package com.example.othello

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardCollectionStoreTest {
    @Test
    fun storesOnlyStableCardIdsAndIsNamespacedByUser() {
        val preferences = MemoryPreferences()
        val store = StandardCollectionStore(preferences)

        assertTrue(store.markObtained("user-a", "trivia.001"))
        assertFalse(store.markObtained("user-a", "trivia.001"))
        assertTrue(store.markObtained("user-a", "book.004"))

        assertEquals(setOf("trivia.001", "book.004"), store.obtainedCardIds("user-a"))
        assertTrue(store.isObtained("user-a", "trivia.001"))
        assertFalse(store.isObtained("user-b", "trivia.001"))
    }

    @Test
    fun collectionStateHasNoPackVersionMigrationConcept() {
        val preferences = MemoryPreferences()
        val store = StandardCollectionStore(preferences)
        store.markObtained("user", "book.003")

        // A pack can move from v5 to v6 without touching this store at all.
        assertEquals(setOf("book.003"), StandardCollectionStore(preferences).obtainedCardIds("user"))
    }

    private class MemoryPreferences : StandardCollectionPreferences {
        private val values = mutableMapOf<String, Set<String>>()

        override fun getStringSet(key: String): Set<String>? = values[key]?.toSet()

        override fun putStringSet(key: String, value: Set<String>) {
            values[key] = value.toSet()
        }
    }
}
