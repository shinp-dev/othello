package com.example.othello.analysis.edax

import com.example.othello.analysis.api.StandardCandidateProvider
import com.example.othello.analysis.api.StandardCandidateResult
import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.StandardMoveCandidate
import com.example.othello.game.GameState
import com.example.othello.game.Position
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Edax adapter dedicated to Standard AI candidate ranking. It never supplies a book. */
class ProductionStandardCandidateProvider private constructor(
    private val gateway: EdaxGateway,
    private val fileExists: (String) -> Boolean,
) : StandardCandidateProvider {
    constructor() : this(NativeEdax, { File(it).isFile })
    internal constructor(gateway: EdaxGateway) : this(gateway, { true })

    private val activeRequest = AtomicLong(NO_REQUEST)

    override suspend fun evaluate(
        position: GameState,
        edaxLevel: Int,
        evaluationData: StandardEvaluationAsset,
    ): StandardCandidateResult {
        require(edaxLevel in 1..4) { "Standard Edax level must be in 1..4" }
        if (!gateway.available) return unavailable("Edax native library is unavailable")
        if (!fileExists(evaluationData.appPrivatePath)) return unavailable("Standard evaluation data is missing")
        val requestId = EdaxExecution.requestSequence.incrementAndGet()
        activeRequest.getAndSet(requestId).takeIf { it != NO_REQUEST }?.let(EdaxExecution::cancel)
        return try {
            val (player, opponent) = position.toEdaxBoard()
            val nativeMoves = EdaxExecution.executeCancellable(requestId, gateway::cancel) {
                gateway.analyze(
                    player = player,
                    opponent = opponent,
                    side = position.currentPlayer.toEdaxSide(),
                    level = edaxLevel,
                    timePerCandidateMs = TIME_PER_CANDIDATE_MS,
                    evaluationDataPath = evaluationData.appPrivatePath,
                    bookPath = null,
                    requestId = requestId,
                )
            }
            currentCoroutineContext().ensureActive()
            if (nativeMoves.isEmpty() && position.legalMoves.isNotEmpty()) {
                throw CancellationException("Standard Edax analysis cancelled")
            }
            val candidates = nativeMoves.mapIndexed { index, move ->
                IndexedValue(index, StandardMoveCandidate(Position(move.square / 8, move.square % 8), move.score))
            }.sortedWith(
                compareByDescending<IndexedValue<StandardMoveCandidate>> { it.value.score }
                    .thenBy { it.index },
            ).map { it.value }
            require(candidates.map { it.move }.toSet() == position.legalMoves) {
                "Edax legal-move set does not match Game Core"
            }
            StandardCandidateResult(candidates)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            unavailable(failure.message ?: "Standard Edax analysis failed")
        } finally {
            activeRequest.compareAndSet(requestId, NO_REQUEST)
            EdaxExecution.forgetCancellation(requestId)
        }
    }

    override fun cancel() {
        activeRequest.get().takeIf { it != NO_REQUEST }?.let(EdaxExecution::cancel)
    }

    private fun unavailable(message: String) = StandardCandidateResult(
        rankedCandidates = emptyList(),
        available = false,
        message = message,
    )

    private companion object {
        const val TIME_PER_CANDIDATE_MS = 500
        const val NO_REQUEST = -1L
    }
}
