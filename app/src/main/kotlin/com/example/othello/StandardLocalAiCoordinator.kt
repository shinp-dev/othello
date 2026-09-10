package com.example.othello

import com.example.othello.analysis.api.StandardAiConfig
import com.example.othello.analysis.api.StandardAiEngine
import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchMode
import kotlinx.coroutines.CancellationException

/** Local-match adapter around the UI-independent Standard AI engine. */
class StandardLocalAiCoordinator(
    private val match: LocalMatchController,
    private val engine: StandardAiEngine,
    private val config: StandardAiConfig,
    private val evaluationData: StandardEvaluationAsset,
) {
    suspend fun play(): Boolean {
        if (match.viewState.mode != LocalMatchMode.AI ||
            match.viewState.game.currentPlayer != match.viewState.aiDisc ||
            match.viewState.completedRecord != null || match.viewState.aiThinking
        ) return false
        if (match.viewState.game.legalMoves.isEmpty()) return match.passAiTurn()
        val request = match.beginAiTurn() ?: return false
        return try {
            val result = engine.chooseMove(request.position, config, evaluationData)
            if (!result.available) {
                match.showAiError(request, result.message ?: "Standard AI is unavailable")
                false
            } else {
                val move = result.move?.takeIf { it in request.position.legalMoves }
                if (move == null) {
                    match.showAiError(request, "Standard AI returned no legal move")
                    false
                } else {
                    match.playAiMove(request, move)
                }
            }
        } catch (cancelled: CancellationException) {
            match.finishAiTurn(request)
            throw cancelled
        } catch (failure: Throwable) {
            match.showAiError(request, failure.message ?: "Standard AI move failed")
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
