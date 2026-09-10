package com.example.othello.analysis.edax

import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.game.GameState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ProductionStandardCandidateProviderTest {
    @Test
    fun standardCandidatesUseOnlyTheDedicatedEvalAndNeverABook() = runBlocking {
        val moves = GameState().legalMoves.mapIndexed { index, move ->
            NativeMove(move.index(), 10 - index, EvaluationKind.HEURISTIC, 1, 100)
        }
        val gateway = RecordingGateway(moves)
        val provider = ProductionStandardCandidateProvider(gateway)

        val result = provider.evaluate(
            GameState(),
            edaxLevel = 4,
            evaluationData = asset("standard-only"),
        )

        assertTrue(result.available)
        assertEquals("standard-only", gateway.evaluationPath)
        assertNull(gateway.bookPath)
        assertEquals(4, gateway.level)
        assertEquals(500, gateway.timePerCandidateMs)
    }

    @Test
    fun rankingIsScoreDescendingAndPreservesEdaxOrderForTies() = runBlocking {
        val legal = GameState().legalMoves.toList()
        val gateway = RecordingGateway(
            listOf(
                NativeMove(legal[0].index(), 2, EvaluationKind.HEURISTIC, 1, 100),
                NativeMove(legal[1].index(), 5, EvaluationKind.HEURISTIC, 1, 100),
                NativeMove(legal[2].index(), 5, EvaluationKind.HEURISTIC, 1, 100),
                NativeMove(legal[3].index(), -1, EvaluationKind.HEURISTIC, 1, 100),
            ),
        )

        val candidates = ProductionStandardCandidateProvider(gateway)
            .evaluate(GameState(), edaxLevel = 1, evaluationData = asset("standard"))
            .rankedCandidates

        assertEquals(listOf(legal[1], legal[2], legal[0], legal[3]), candidates.map { it.move })
    }

    private fun asset(path: String) = StandardEvaluationAsset(path, "a".repeat(64))

    private class RecordingGateway(
        private val moves: List<NativeMove>,
    ) : EdaxGateway {
        override val available = true
        override val version = "test"
        var evaluationPath: String? = null
        var bookPath: String? = "not-called"
        var level: Int? = null
        var timePerCandidateMs: Int? = null

        override fun validateEvaluationData(path: String): String? = null
        override fun validateBook(path: String): String? = null
        override fun analyze(
            player: Long,
            opponent: Long,
            side: Int,
            level: Int,
            timePerCandidateMs: Int,
            evaluationDataPath: String,
            bookPath: String?,
            requestId: Long,
        ): List<NativeMove> {
            this.evaluationPath = evaluationDataPath
            this.bookPath = bookPath
            this.level = level
            this.timePerCandidateMs = timePerCandidateMs
            return moves
        }

        override fun cancel(requestId: Long) = Unit
    }
}
