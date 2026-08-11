package com.example.othello.analysis.api

import com.example.othello.game.GameState
import com.example.othello.game.Position

data class ReviewPosition(val state: GameState)

data class AnalysisAsset(
    val appPrivatePath: String,
    val identitySha256: String,
)

sealed interface EvaluationDataSource {
    data object None : EvaluationDataSource
    data class Imported(val asset: AnalysisAsset) : EvaluationDataSource
}

sealed interface BookSource {
    data object None : BookSource
    data class ImportedBook(val asset: AnalysisAsset) : BookSource
}

/** Edax game level. This is deliberately the only search-strength control exposed by the app. */
data class AnalysisSettings(
    val level: Int = 8,
    val evaluationData: EvaluationDataSource = EvaluationDataSource.None,
    val bookSource: BookSource = BookSource.None,
) {
    init { require(level in 1..18) { "analysis level must be in 1..18" } }
}

enum class EvaluationPerspective { SIDE_TO_MOVE }

/**
 * HEURISTIC is Edax's depth-dependent estimate in final-disc-difference units.
 * EXACT is a complete endgame result. BOOK is the score stored in the imported Edax book.
 */
enum class EvaluationKind { EXACT, HEURISTIC, BOOK }

data class EvaluationScore(
    val value: Int,
    val perspective: EvaluationPerspective,
    val kind: EvaluationKind,
    val searchedDepth: Int? = null,
    val selectivityPercent: Int? = null,
)

data class MoveEvaluation(val move: Position, val score: EvaluationScore)

data class AnalysisResult(
    val evaluations: List<MoveEvaluation>,
    val available: Boolean = true,
    val message: String? = null,
)

interface AnalysisEngine {
    suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult
    fun cancel() = Unit
    fun clearCache() = Unit
}
