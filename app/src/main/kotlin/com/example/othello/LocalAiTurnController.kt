package com.example.othello

import com.example.othello.analysis.api.AiMoveEngine
import com.example.othello.analysis.api.AiMoveSettings
import com.example.othello.match.LocalMatchController
import kotlinx.coroutines.CancellationException

/** App-layer adapter for the dedicated, whole-turn AI move search. */
class LocalAiTurnController(
    private val match: LocalMatchController,
    private val engine: AiMoveEngine,
) {
    suspend fun play(settings: AiMoveSettings): Boolean {
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
            val result = engine.chooseBestMove(match.viewState.game, settings)
            if (!result.available) {
                match.showError(result.message ?: "Edax AI move is unavailable")
                false
            } else {
                val move = result.move?.takeIf { it in match.viewState.game.legalMoves }
                if (move == null) {
                    match.showError("Edax returned no legal move")
                    false
                } else match.playAiMove(move)
            }
        } catch (cancelled: CancellationException) {
            match.setAiThinking(false)
            throw cancelled
        } catch (failure: Throwable) {
            match.showError(failure.message ?: "AI move failed")
            false
        } finally {
            if (match.viewState.aiThinking) match.setAiThinking(false)
        }
    }
}
