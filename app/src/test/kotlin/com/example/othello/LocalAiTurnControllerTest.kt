package com.example.othello

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.api.EvaluationPerspective
import com.example.othello.analysis.api.EvaluationScore
import com.example.othello.analysis.api.MoveEvaluation
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class LocalAiTurnControllerTest {
    @Test
    fun usesConfiguredLevelAndSelectsHighestLegalMove() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val engine = FakeEngine()

        assertTrue(LocalAiTurnController(match, engine).play(AnalysisSettings(level = 14)))

        assertEquals(14, engine.lastSettings?.level)
        assertEquals(engine.bestMove, match.viewState.moves.single())
        assertTrue(match.viewState.moves.single() in match.viewState.game.board.positionsOf(Disc.BLACK))
    }

    @Test
    fun aiWhiteUsesOnlyLegalMoves() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        val engine = FakeEngine()
        assertTrue(match.play(Position(2, 3)))

        assertTrue(LocalAiTurnController(match, engine).play(AnalysisSettings(level = 9)))
        assertEquals(engine.bestMove, match.viewState.moves.last())
        assertTrue(match.viewState.moves.last() in match.viewState.game.board.positionsOf(Disc.WHITE))
    }

    @Test
    fun unavailableAndFailedAnalysisDoNotChangeTheGame() = runBlocking {
        val unavailableMatch = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val unavailable = object : AnalysisEngine {
            override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings) =
                AnalysisResult(emptyList(), available = false, message = "unavailable")
        }
        assertFalse(LocalAiTurnController(unavailableMatch, unavailable).play(AnalysisSettings()))
        assertTrue(unavailableMatch.viewState.moves.isEmpty())
        assertFalse(unavailableMatch.viewState.aiThinking)

        val failedMatch = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val failed = object : AnalysisEngine {
            override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult =
                error("analysis failure")
        }
        assertFalse(LocalAiTurnController(failedMatch, failed).play(AnalysisSettings()))
        assertTrue(failedMatch.viewState.moves.isEmpty())
        assertFalse(failedMatch.viewState.aiThinking)
    }

    @Test
    fun cancelledAnalysisRestoresThinkingState() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val engine = object : AnalysisEngine {
            override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult =
                throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            LocalAiTurnController(match, engine).play(AnalysisSettings())
        }
        assertTrue(match.viewState.moves.isEmpty())
        assertFalse(match.viewState.aiThinking)
    }

    @Test
    fun concurrentAiRequestsProduceAtMostOneMove() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val engine = BlockingEngine()
        val controller = LocalAiTurnController(match, engine)
        val first = async(start = CoroutineStart.DEFAULT) { controller.play(AnalysisSettings()) }
        engine.started.await()

        assertFalse(controller.play(AnalysisSettings()))
        engine.release.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, match.viewState.moves.size)
    }

    private class FakeEngine : AnalysisEngine {
        var lastSettings: AnalysisSettings? = null
        var bestMove: Position? = null
        override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult {
            lastSettings = settings
            val moves = position.state.legalMoves.toList()
            bestMove = moves.last()
            return AnalysisResult(moves.mapIndexed { index, move ->
                MoveEvaluation(move, EvaluationScore(index, EvaluationPerspective.SIDE_TO_MOVE, EvaluationKind.HEURISTIC))
            })
        }
    }

    private class BlockingEngine : AnalysisEngine {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult {
            started.complete(Unit)
            release.await()
            return AnalysisResult(
                position.state.legalMoves.mapIndexed { index, move ->
                    MoveEvaluation(move, EvaluationScore(index, EvaluationPerspective.SIDE_TO_MOVE, EvaluationKind.HEURISTIC))
                },
            )
        }
    }
}
