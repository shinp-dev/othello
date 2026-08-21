package com.example.othello

import com.example.othello.game.Position
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalRecordType
import com.example.othello.records.FinishReason
import com.example.othello.records.GameRecord
import com.example.othello.records.MatchResult
import com.example.othello.records.toLocalCopy
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LocalGameRecordFileStoreTest {
    @Test
    fun saveListDeleteAndSameIdSaveAreIdempotent() = runBlocking {
        val root = Files.createTempDirectory("local-record-store").toFile()
        try {
            val store = JsonFileLocalGameRecordStore(File(root, "local-game-records.jsonl"))
            val first = record("first", 1)
            val second = record("second", 2)

            store.save(first)
            store.save(second)
            store.save(first.copy(createdAtEpochMillis = 3))

            assertEquals(listOf(first.copy(createdAtEpochMillis = 3), second), store.list())
            store.delete(second.localId)
            assertEquals(listOf(first.copy(createdAtEpochMillis = 3)), store.list())
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun corruptLineIsBackedUpAndHealthyRecordsRemainUsable() = runBlocking {
        val root = Files.createTempDirectory("local-record-corruption").toFile()
        try {
            val file = File(root, "local-game-records.jsonl")
            val store = JsonFileLocalGameRecordStore(file)
            val first = record("first", 1)
            val second = record("second", 2)
            store.save(first)
            store.save(second)
            file.appendText("\n{not-json}\n")

            val result = store.listResult()

            assertEquals(2, result.records.size)
            assertEquals(1, result.corruptLineCount)
            assertTrue(result.recoveryCompleted)
            assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("local-game-records.jsonl.corrupt-") })

            val third = record("third", 3)
            store.save(third)
            assertEquals(3, store.list().size)
            store.delete(first.localId)
            assertEquals(setOf(second.localId, third.localId), store.list().map { it.localId }.toSet())
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun tempWriteFailureLeavesExistingFileReadable() = runBlocking {
        val root = Files.createTempDirectory("local-record-atomic").toFile()
        try {
            val file = File(root, "local-game-records.jsonl")
            val store = JsonFileLocalGameRecordStore(file)
            val first = record("first", 1)
            store.save(first)

            val tempPath = File(root, "${file.name}.tmp")
            assertTrue(tempPath.mkdir())
            assertFailsWith<Exception> { store.save(record("second", 2)) }
            assertEquals(listOf(first), store.list())
            assertFailsWith<Exception> { store.delete(first.localId) }
            assertEquals(listOf(first), store.list())
            tempPath.deleteRecursively()
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun onlineCopySavesLocallyAndRepeatedSaveDoesNotDuplicate() = runBlocking {
        val root = Files.createTempDirectory("online-local-record-store").toFile()
        try {
            val store = JsonFileLocalGameRecordStore(File(root, "local-game-records.jsonl"))
            val online = GameRecord(
                matchId = "server-match",
                players = listOf("me", "opponent"),
                moves = listOf(Position(2, 3)),
                result = MatchResult.BLACK_WIN,
                startedAtEpochMillis = 1,
                finishedAtEpochMillis = 2,
                timeControl = "300000",
                finishReason = FinishReason.NORMAL,
            )
            val copy = online.toLocalCopy("me")

            store.save(copy)
            store.save(online.toLocalCopy("me"))

            assertEquals(listOf(copy), store.list())
            assertEquals("server-match", store.list().single().sourceMatchId)
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    private fun record(id: String, createdAt: Long) =
        LocalGameRecord(id, listOf(Position(2, 3)), createdAt, LocalRecordType.LOCAL_HUMAN)
}
