package com.example.othello.records

import com.example.othello.game.Disc
import com.example.othello.game.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalGameRecordTest {
    @Test
    fun jsonRoundTripKeepsCanonicalCompleteLineAndMetadata() {
        val record = LocalGameRecord("local-1", listOf(Position(2, 3), Position(2, 2)), 42, LocalRecordType.LOCAL_AI, MatchResult.WHITE_WIN, FinishReason.RESIGNATION, Disc.WHITE)
        val decoded = LocalGameRecordJson.decode(LocalGameRecordJson.encode(record))
        assertEquals(record, decoded)
        assertEquals("d3c3", decoded.canonicalMoves)
        assertEquals(3, LocalGameRecord.replay(decoded.moves).size)
    }

    @Test
    fun canonicalLineIsValidatedFromInitialPosition() {
        val record = LocalGameRecord("research", listOf(Position(2, 3)), 1, LocalRecordType.RESEARCH_LINE)
        assertTrue(record.canonicalMoves == "d3")
        assertEquals(2, LocalGameRecord.replay(record.moves).size)
    }

    @Test
    fun inMemoryPersistenceSupportsSaveReadAndSingleDelete() = kotlinx.coroutines.runBlocking {
        val store = FakeLocalStore()
        val first = LocalGameRecord("first", emptyList(), 1, LocalRecordType.LOCAL_HUMAN)
        val second = LocalGameRecord("second", listOf(Position(2, 3)), 2, LocalRecordType.LOCAL_HUMAN)
        store.save(first)
        store.save(second)
        assertEquals(listOf(second, first), store.list())
        store.delete(first.localId)
        assertEquals(listOf(second), store.list())
    }

    private class FakeLocalStore : LocalGameRecordStore {
        private val values = linkedMapOf<String, LocalGameRecord>()
        override suspend fun list(limit: Int) = values.values.sortedByDescending { it.createdAtEpochMillis }.take(limit)
        override suspend fun save(record: LocalGameRecord) { values[record.localId] = record }
        override suspend fun delete(localId: String) { values.remove(localId) }
    }
}
