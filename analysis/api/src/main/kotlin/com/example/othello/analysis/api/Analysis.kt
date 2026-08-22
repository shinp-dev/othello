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

/** Review-analysis settings for evaluating every legal candidate. */
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

data class AiMoveSettings(
    val level: Int = DEFAULT_LEVEL,
    val moveTimeMs: Int = DEFAULT_MOVE_TIME_MS,
    val evaluationData: EvaluationDataSource = EvaluationDataSource.None,
    val bookSource: BookSource = BookSource.None,
) {
    init {
        require(level in MIN_LEVEL..MAX_LEVEL) { "AI match level must be in 1..8" }
        require(moveTimeMs in MIN_MOVE_TIME_MS..MAX_MOVE_TIME_MS) { "AI move time is out of range" }
    }

    companion object {
        const val DEFAULT_LEVEL = 1
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 8
        const val DEFAULT_MOVE_TIME_MS = 2_000
        const val MIN_MOVE_TIME_MS = 500
        const val MAX_MOVE_TIME_MS = 10_000
    }
}

data class AiMoveResult(
    val move: Position?,
    val available: Boolean = true,
    val message: String? = null,
)

/** Chooses one move for a live AI turn. This is intentionally separate from review analysis. */
interface AiMoveEngine {
    suspend fun chooseBestMove(position: GameState, settings: AiMoveSettings): AiMoveResult
    fun cancel() = Unit
}

interface AnalysisEngine {
    suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult
    fun cancel() = Unit
    fun clearCache() = Unit
}
