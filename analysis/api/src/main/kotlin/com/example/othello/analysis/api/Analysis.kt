package com.example.othello.analysis.api

import com.example.othello.game.GameState
import com.example.othello.game.Position

data class ReviewPosition(val state: GameState)
sealed interface BookSource {
    data object None : BookSource
    data class ImportedBook(val appPrivatePath: String) : BookSource
}

data class AnalysisSettings(val depth: Int = 4, val bookSource: BookSource = BookSource.None, val bookProvider: BookProvider? = null)
interface BookProvider { fun lookup(position: ReviewPosition): Map<Position, EvaluationScore> }

enum class EvaluationPerspective { SIDE_TO_MOVE }
enum class EvaluationKind { EXACT, HEURISTIC, UNAVAILABLE }
data class EvaluationScore(val value: Int, val perspective: EvaluationPerspective, val kind: EvaluationKind)
data class MoveEvaluation(val move: Position, val score: EvaluationScore)
data class AnalysisResult(val evaluations: List<MoveEvaluation>, val available: Boolean = true, val message: String? = null)

interface AnalysisEngine {
    suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult
}
