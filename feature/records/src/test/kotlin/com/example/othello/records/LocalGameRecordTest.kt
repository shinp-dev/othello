package com.example.othello.records

import com.example.othello.game.Disc
import com.example.othello.game.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun lineDecoderRecoversHealthyRecordsAndReportsCorruption() {
        val record = LocalGameRecord("healthy", listOf(Position(2, 3)), 1, LocalRecordType.LOCAL_HUMAN)
        val decoded = LocalGameRecordJson.decodeListRecovering(
            LocalGameRecordJson.encode(record) + "\n{broken-json}\n",
        )

        assertEquals(listOf(record), decoded.records)
        assertEquals(1, decoded.corruptLines.size)
        assertTrue(decoded.hasCorruption)
    }

    @Test
    fun legacyJsonWithoutOnlineSourceMetadataStillDecodes() {
        val legacy = """{"local_id":"legacy","moves":"d3","created_at":42,"type":"LOCAL_AI","result":null,"finish_reason":null,"player_disc":null}"""

        val decoded = LocalGameRecordJson.decode(legacy)

        assertEquals("legacy", decoded.localId)
        assertEquals(LocalRecordType.LOCAL_AI, decoded.type)
        assertEquals("d3", decoded.canonicalMoves)
        assertNull(decoded.sourceMatchId)
    }

    @Test
    fun allPreexistingRecordTypesKeepTheirJsonIdentity() {
        val types = listOf(LocalRecordType.LOCAL_HUMAN, LocalRecordType.LOCAL_AI, LocalRecordType.RESEARCH_LINE)

        types.forEach { type ->
            val record = LocalGameRecord("legacy-${type.name}", listOf(Position(2, 3)), 7, type)
            assertEquals(record, LocalGameRecordJson.decode(LocalGameRecordJson.encode(record)))
        }
    }

    @Test
    fun onlineRecordCreatesIdempotentDeviceOnlyCopyAndRoundTrips() {
        val online = GameRecord(
            matchId = "match-42",
            players = listOf("black-user", "white-user"),
            moves = listOf(Position(2, 3), Position(2, 2)),
            result = MatchResult.WHITE_WIN,
            startedAtEpochMillis = 10,
            finishedAtEpochMillis = 20,
            timeControl = "300000",
            finishReason = FinishReason.RESIGNATION,
        )

        val first = online.toLocalCopy("white-user")
        val second = online.toLocalCopy("white-user")
        val decoded = LocalGameRecordJson.decode(LocalGameRecordJson.encode(first))

        assertEquals(first, second)
        assertEquals(first, decoded)
        assertEquals("online:match-42", decoded.localId)
        assertEquals(LocalRecordType.ONLINE_SAVED, decoded.type)
        assertEquals("match-42", decoded.sourceMatchId)
        assertEquals(Disc.WHITE, decoded.playerDisc)
        assertEquals(online.result, decoded.result)
        assertEquals(online.finishReason, decoded.finishReason)
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
