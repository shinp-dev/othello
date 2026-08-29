package com.example.othello.review

import com.example.othello.game.Position
import kotlin.coroutines.cancellation.CancellationException

/** Tracks which variation content was most recently persisted successfully. */
class VariationSaveTracker {
    private var lastSavedMoves: List<Position?>? = null

    fun isSaved(currentMoves: List<Position?>?): Boolean =
        currentMoves != null && currentMoves == lastSavedMoves

    suspend fun save(
        currentMoves: List<Position?>,
        persist: suspend (List<Position?>) -> Unit,
    ): Result<Unit> {
        val snapshot = currentMoves.toList()
        return try {
            persist(snapshot)
            lastSavedMoves = snapshot
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }
}
