package com.example.othello.analysis.api

import com.example.othello.game.GameState
import com.example.othello.game.Position

data class ReviewPosition(val state: GameState)
data class AnalysisSettings(val depth: Int = 4, val bookProvider: BookProvider? = null)
interface BookProvider { fun lookup(position: ReviewPosition): Map<Position, Int> }
data class MoveEvaluation(val move: Position, val score: Int)
data class AnalysisResult(val evaluations: List<MoveEvaluation>)

interface AnalysisEngine {
    suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult
}
