package com.example.othello

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.match.LocalMatchController
import kotlinx.coroutines.CancellationException

/** App-layer adapter that reuses the configured Edax analysis for a local AI turn. */
class LocalAiTurnController(
    private val match: LocalMatchController,
    private val engine: AnalysisEngine,
) {
    suspend fun play(settings: AnalysisSettings): Boolean {
        if (match.viewState.mode != com.example.othello.match.LocalMatchMode.AI ||
            match.viewState.game.currentPlayer != match.viewState.aiDisc ||
            match.viewState.completedRecord != null ||
            match.viewState.aiThinking
        ) return false
        if (match.viewState.game.legalMoves.isEmpty()) {
            return match.passAiTurn()
        }
        match.setAiThinking(true)
        return try {
            val result = engine.analyze(ReviewPosition(match.viewState.game), settings)
            if (!result.available) {
                match.showError(result.message ?: "Edax analysis is unavailable")
                false
            } else {
                val move = result.evaluations
                    .asSequence()
                    .filter { it.move in match.viewState.game.legalMoves }
                    .maxByOrNull { it.score.value }
                    ?.move
                if (move == null) {
                    match.showError("Edax returned no legal move")
                    false
                } else match.playAiMove(move)
            }
        } catch (cancelled: CancellationException) {
            match.setAiThinking(false)
            throw cancelled
        } catch (failure: Throwable) {
            match.showError(failure.message ?: "AI analysis failed")
            false
        } finally {
            if (match.viewState.aiThinking) match.setAiThinking(false)
        }
    }
}
