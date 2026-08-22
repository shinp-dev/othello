package com.example.othello.analysis.edax

import com.example.othello.analysis.api.AiMoveEngine
import com.example.othello.analysis.api.AiMoveResult
import com.example.othello.analysis.api.AiMoveSettings
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import com.example.othello.game.GameState
import com.example.othello.game.Position
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

class ProductionAiMoveEngine private constructor(
    private val gateway: AiMoveGateway,
    private val fileExists: (String) -> Boolean,
) : AiMoveEngine {
    constructor() : this(NativeEdax, { File(it).isFile })
    internal constructor(gateway: AiMoveGateway) : this(gateway, { true })

    private val activeRequest = AtomicLong(NO_REQUEST)

    override suspend fun chooseBestMove(position: GameState, settings: AiMoveSettings): AiMoveResult {
        if (!gateway.available) return unavailable("Edax native libraryを読み込めません")
        val evaluationAsset = (settings.evaluationData as? EvaluationDataSource.Imported)?.asset
            ?: return unavailable("AI対局用の評価データが設定されていません")
        if (!fileExists(evaluationAsset.appPrivatePath)) {
            return unavailable("評価データが見つかりません。再インポートしてください")
        }
        val bookAsset = (settings.bookSource as? BookSource.ImportedBook)?.asset
        if (bookAsset != null && !fileExists(bookAsset.appPrivatePath)) {
            return unavailable("オープニングブックが見つかりません。削除または再インポートしてください")
        }
        if (position.legalMoves.isEmpty()) return unavailable("AIが着手できる合法手がありません")

        val requestId = EdaxExecution.requestSequence.incrementAndGet()
        activeRequest.getAndSet(requestId).takeIf { it != NO_REQUEST }?.let(gateway::cancel)
        return try {
            val (player, opponent) = position.toEdaxBoard()
            val square = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { gateway.cancel(requestId) }
                EdaxExecution.executor.execute {
                    runCatching {
                        gateway.chooseBestMove(
                            player = player,
                            opponent = opponent,
                            side = position.currentPlayer.toEdaxSide(),
                            level = settings.level,
                            moveTimeMs = settings.moveTimeMs,
                            evaluationDataPath = evaluationAsset.appPrivatePath,
                            bookPath = bookAsset?.appPrivatePath,
                            requestId = requestId,
                        )
                    }.fold(
                        onSuccess = { continuation.resumeWith(Result.success(it)) },
                        onFailure = { continuation.resumeWith(Result.failure(it)) },
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            if (square == NATIVE_CANCELLED) throw CancellationException("Edax AI move cancelled")
            val move = square.takeIf { it in 0 until 64 }?.let { Position(it / 8, it % 8) }
            require(move != null && move in position.legalMoves) { "Edax returned an illegal AI move" }
            AiMoveResult(move)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            unavailable("Edax AI着手に失敗しました: ${failure.message ?: failure::class.simpleName}")
        } finally {
            activeRequest.compareAndSet(requestId, NO_REQUEST)
        }
    }

    override fun cancel() {
        activeRequest.get().takeIf { it != NO_REQUEST }?.let(gateway::cancel)
    }

    private fun unavailable(message: String) = AiMoveResult(move = null, available = false, message = message)

    private companion object {
        const val NO_REQUEST = -1L
        const val NATIVE_CANCELLED = -1
    }
}
