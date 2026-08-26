package com.example.othello.theory

import com.example.othello.game.GameStatus
import com.example.othello.game.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TheorySessionTest {
    @Test
    fun playingFromAnEarlierNodeKeepsTheOriginalContinuation() {
        val session = TheoryExplorationSession.fresh()
        assertTrue(session.play(sortedMoves(session).first()))
        val branchPoint = session.current
        val alternatives = sortedMoves(session).take(2)
        assertEquals(2, alternatives.size)

        assertTrue(session.play(alternatives[0]))
        val original = session.current
        assertTrue(session.goBack())
        assertEquals(branchPoint, session.current)
        assertTrue(session.play(alternatives[1]))
        val alternate = session.current
        assertTrue(session.goBack())

        assertEquals(2, session.continuations.size)
        assertTrue(session.selectContinuation(session.continuations.first { it.move == alternatives[0] }.nodeId))
        assertEquals(original, session.current)
        assertTrue(session.goBack())
        assertTrue(session.selectContinuation(session.continuations.first { it.move == alternatives[1] }.nodeId))
        assertEquals(alternate, session.current)
    }

    @Test
    fun replayingAnExistingMoveSelectsItsNodeWithoutDuplicatingIt() {
        val session = TheoryExplorationSession.fresh()
        val move = sortedMoves(session).first()
        assertTrue(session.play(move))
        val childId = session.currentNodeId
        assertTrue(session.goBack())

        assertTrue(session.play(move))

        assertEquals(childId, session.currentNodeId)
        assertEquals(2, session.nodeCount)
    }

    @Test
    fun forwardRequiresSelectionWhenSeveralContinuationsExist() {
        val session = branchedSession()
        assertTrue(session.goBack())

        assertFalse(session.goForward())
        assertEquals(2, session.continuations.size)
        assertTrue(session.selectContinuation(session.continuations.last().nodeId))
    }

    @Test
    fun snapshotRestoresWholeTreeCurrentNodeAndSelectedMetric() {
        val session = branchedSession()
        assertTrue(session.selectMetric("potential_mobility"))
        val expectedState = session.current
        val encoded = TheorySessionJson.encode(session.snapshot())

        val restored = assertNotNull(TheoryExplorationSession.restore(TheorySessionJson.decode(encoded)))

        assertEquals(session.nodeCount, restored.nodeCount)
        assertEquals(expectedState, restored.current)
        assertEquals("potential_mobility", restored.selectedMetricId)
        assertTrue(restored.goBack())
        assertEquals(2, restored.continuations.size)
    }

    @Test
    fun incompatibleOrTamperedSnapshotIsRejectedWithoutThrowing() {
        val snapshot = TheoryExplorationSession.fresh().snapshot()

        assertNull(TheoryExplorationSession.restore(snapshot.copy(schemaVersion = 999)))
        assertNull(TheoryExplorationSession.restore(snapshot.copy(currentBoard = "tampered")))
        assertNull(
            TheoryExplorationSession.restore(
                snapshot.copy(
                    nodes = snapshot.nodes + TheoryNodeSnapshot(1, 0, TheoryMoveSnapshot(0, 0)),
                    nextNodeId = 2,
                ),
            ),
        )
    }

    @Test
    fun unknownRemovedMetricFallsBackToRegistryDefault() {
        val snapshot = TheoryExplorationSession.fresh().snapshot().copy(selectedMetricId = "removed")

        val restored = assertNotNull(TheoryExplorationSession.restore(snapshot))

        assertEquals(TheoryMetricRegistry.default.id, restored.selectedMetricId)
    }

    @Test
    fun normalPlayEventuallyResolvesPassesAndTerminalStateInsideNodes() {
        val session = TheoryExplorationSession.fresh()
        var sawForcedPass = false
        repeat(64) {
            if (session.current.status is GameStatus.Finished) return@repeat
            val beforePly = session.current.ply
            val move = session.current.legalMoves.minWithOrNull(
                compareBy<Position> { candidate ->
                    TheoryMetricEvaluator.evaluateAll(session.current)[candidate]
                        ?.value("opponent_mobility") ?: Int.MAX_VALUE
                }.thenBy(Position::index),
            ) ?: return@repeat
            assertTrue(session.play(move))
            if (session.current.ply > beforePly + 1) sawForcedPass = true
        }

        assertTrue(sawForcedPass)
        assertTrue(session.current.status is GameStatus.Finished)
        assertTrue(session.current.legalMoves.isEmpty())
    }

    private fun branchedSession(): TheoryExplorationSession = TheoryExplorationSession.fresh().also { session ->
        assertTrue(session.play(sortedMoves(session).first()))
        val alternatives = sortedMoves(session).take(2)
        assertTrue(session.play(alternatives[0]))
        assertTrue(session.goBack())
        assertTrue(session.play(alternatives[1]))
    }

    private fun sortedMoves(session: TheoryExplorationSession): List<Position> =
        session.current.legalMoves.sortedBy(Position::index)
}
