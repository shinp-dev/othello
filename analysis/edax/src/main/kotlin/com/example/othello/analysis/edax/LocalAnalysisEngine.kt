package com.example.othello.analysis.edax

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.MoveEvaluation
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.Disc
import com.example.othello.game.MoveOutcome

/** Deterministic fallback used until the Edax JNI binary is supplied. */
class LocalAnalysisEngine : AnalysisEngine {
    override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult =
        AnalysisResult(position.state.legalMoves.map { move ->
            val outcome = position.state.play(move)
            val next = (outcome as MoveOutcome.Played).state
            val own = next.board.count(position.state.currentPlayer)
            val opponent = next.board.count(position.state.currentPlayer.opponent())
            MoveEvaluation(move, (own - opponent) * 10 + if (move.row in 0..7 && move.column in 0..7 && (move.row == 0 || move.row == 7) && (move.column == 0 || move.column == 7)) 80 else 0)
        }.sortedByDescending { it.score })
}

// This source set is the only place allowed to later load Edax through JNI.
private val Disc.isPlayer: Boolean get() = this == Disc.BLACK || this == Disc.WHITE
