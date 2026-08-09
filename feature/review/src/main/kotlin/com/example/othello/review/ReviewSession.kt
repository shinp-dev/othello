package com.example.othello.review

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.records.GameRecord

data class Variation(val parentPly: Int, val moves: List<Position?>)

class ReviewSession(private val record: GameRecord) {
    private val states = buildStates(record.moves)
    private val variations = mutableListOf<Variation>()
    var cursor: Int = 0
        private set

    val current: GameState get() = states[cursor]
    val currentVariations: List<Variation> get() = variations.toList()

    fun next() { if (cursor < states.lastIndex) cursor++ }
    fun previous() { if (cursor > 0) cursor-- }
    fun seek(ply: Int) { cursor = ply.coerceIn(0, states.lastIndex) }
    fun branch(moves: List<Position?>) { variations += Variation(cursor, moves.toList()) }

    suspend fun analyze(engine: AnalysisEngine, settings: AnalysisSettings = AnalysisSettings()): AnalysisResult =
        engine.analyze(ReviewPosition(current), settings)

    private fun buildStates(moves: List<Position?>): List<GameState> {
        val result = mutableListOf(GameState())
        moves.forEach { move ->
            val next = if (move == null) result.last().pass() else result.last().play(move)
            result += when (next) {
                is MoveOutcome.Played -> next.state
                is MoveOutcome.Passed -> next.state
                is MoveOutcome.Rejected -> error("record contains an invalid move at ply ${result.lastIndex}")
            }
        }
        return result
    }
}
