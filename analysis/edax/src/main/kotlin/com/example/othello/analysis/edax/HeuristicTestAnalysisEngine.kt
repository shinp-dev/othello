package com.example.othello.analysis.edax

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.api.EvaluationPerspective
import com.example.othello.analysis.api.EvaluationScore
import com.example.othello.analysis.api.MoveEvaluation
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.MoveOutcome

/** Test/debug-only deterministic heuristic. It must never be presented as Edax. */
class HeuristicTestAnalysisEngine : AnalysisEngine {
    override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult =
        AnalysisResult(position.state.legalMoves.map { move ->
            val next = (position.state.play(move) as MoveOutcome.Played).state
            val own = next.board.count(position.state.currentPlayer)
            val opponent = next.board.count(position.state.currentPlayer.opponent())
            val cornerBonus = if (move.row in 0..7 && move.column in 0..7 && (move.row == 0 || move.row == 7) && (move.column == 0 || move.column == 7)) 80 else 0
            MoveEvaluation(move, EvaluationScore((own - opponent) * 10 + cornerBonus, EvaluationPerspective.SIDE_TO_MOVE, EvaluationKind.HEURISTIC))
        }.sortedByDescending { it.score.value })
}
