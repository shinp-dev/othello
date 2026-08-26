package com.example.othello

import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.review.PositionReviewSession
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PositionReviewFileStoreTest {
    @Test
    fun saveReloadAndDeleteRemainSeparateFromGameRecords() = runBlocking {
        val root = createTempDirectory("position-review-store").toFile()
        try {
            val file = File(root, "position-reviews.jsonl")
            val store = JsonFilePositionReviewStore(file)
            val session = PositionReviewSession(Board.initial(), Disc.WHITE)
            session.play(session.current.legalMoves.first())
            val record = session.toRecord("p1", "大会局面", 10, 20)

            store.save(record)
            val reloaded = store.list()

            assertEquals(listOf(record), reloaded)
            val restored = PositionReviewSession(reloaded.single())
            assertEquals(session.rootState, restored.rootState)
            assertEquals(session.history, restored.history)
            assertEquals(session.cursor, restored.cursor)
            assertTrue(!File(root, "local-game-records.jsonl").exists())

            store.delete(record.id)
            assertTrue(store.list().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
