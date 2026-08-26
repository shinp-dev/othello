package com.example.othello

import com.example.othello.game.Position
import com.example.othello.theory.TheoryExplorationSession
import com.example.othello.theory.TheorySessionJson
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TheorySessionFileStoreTest {
    @Test
    fun wholeVariationTreeCurrentNodeAndMetricSurviveReload() = runBlocking {
        val root = createTempDirectory("theory-session-store").toFile()
        try {
            val store = JsonFileTheorySessionStore(File(root, "session.json"))
            val session = TheoryExplorationSession.fresh()
            session.play(session.current.legalMoves.sortedBy(Position::index).first())
            val alternatives = session.current.legalMoves.sortedBy(Position::index).take(2)
            session.play(alternatives[0])
            session.goBack()
            session.play(alternatives[1])
            session.selectMetric("frontier_discs")
            val snapshot = session.snapshot()

            store.save(snapshot)
            val loaded = assertNotNull(store.load())
            val restored = assertNotNull(TheoryExplorationSession.restore(loaded))

            assertEquals(snapshot, loaded)
            assertEquals(session.current, restored.current)
            assertEquals("frontier_discs", restored.selectedMetricId)
            assertTrue(restored.goBack())
            assertEquals(2, restored.continuations.size)
            assertTrue(!File(root, "local-game-records.jsonl").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedFileIsQuarantinedAndReturnsFreshStartSignal() = runBlocking {
        val root = createTempDirectory("theory-session-corrupt").toFile()
        try {
            val file = File(root, "session.json").apply { writeText("{broken") }
            val store = JsonFileTheorySessionStore(file)

            assertNull(store.load())

            assertTrue(!file.exists())
            assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("session.json.corrupt-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun semanticallyInvalidSessionIsNeverPersistedOrRestored() = runBlocking {
        val root = createTempDirectory("theory-session-invalid").toFile()
        try {
            val file = File(root, "session.json")
            val store = JsonFileTheorySessionStore(file)
            val invalid = TheoryExplorationSession.fresh().snapshot().copy(currentBoard = "tampered")

            assertFailsWith<IllegalArgumentException> { store.save(invalid) }
            assertTrue(!file.exists())

            file.writeText(TheorySessionJson.encode(invalid))
            assertNull(store.load())
            assertTrue(!file.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
