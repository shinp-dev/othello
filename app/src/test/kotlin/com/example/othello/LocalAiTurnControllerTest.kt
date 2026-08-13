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
import kotlin.test.assertTrue
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
}
