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
        val request = match.beginAiTurn() ?: return false
        return try {
            val result = engine.chooseBestMove(request.position, settings)
            if (!result.available) {
                match.showAiError(request, result.message ?: "Edax AI move is unavailable")
                false
            } else {
                val move = result.move?.takeIf { it in request.position.legalMoves }
                if (move == null) {
                    match.showAiError(request, "Edax returned no legal move")
                    false
                } else match.playAiMove(request, move)
            }
        } catch (cancelled: CancellationException) {
            match.finishAiTurn(request)
            throw cancelled
        } catch (failure: Throwable) {
            match.showAiError(request, failure.message ?: "AI move failed")
            false
        } finally {
            match.finishAiTurn(request)
        }
    }

    fun cancel() {
        engine.cancel()
        match.cancelAiTurn()
    }

    fun cancelForUndo() = cancel()
}
