package com.example.othello.analysis.edax

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.api.EvaluationPerspective
import com.example.othello.analysis.api.EvaluationScore
import com.example.othello.analysis.api.MoveEvaluation
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.Disc
import com.example.othello.game.Position
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class NativeMove(
    val square: Int,
    val score: Int,
    val kind: EvaluationKind,
    val depth: Int?,
    val selectivityPercent: Int?,
)

internal interface EdaxGateway {
    val available: Boolean
    val version: String?
    fun validateEvaluationData(path: String): String?
    fun validateBook(path: String): String?
    fun analyze(
        player: Long,
        opponent: Long,
        side: Int,
        level: Int,
        evaluationDataPath: String,
        bookPath: String?,
        requestId: Long,
    ): List<NativeMove>
    fun cancel(requestId: Long)
}

internal object NativeEdax : EdaxGateway {
    private val loadResult = runCatching { System.loadLibrary("edax_jni") }

    override val available: Boolean get() = loadResult.isSuccess
    override val version: String? get() = if (available) runCatching { nativeVersion() }.getOrNull() else null

    override fun validateEvaluationData(path: String): String? =
        if (available) nativeValidateEvaluationData(path) else "Edax native library is unavailable"

    override fun validateBook(path: String): String? =
        if (available) nativeValidateBook(path) else "Edax native library is unavailable"

    override fun analyze(
        player: Long,
        opponent: Long,
        side: Int,
        level: Int,
        evaluationDataPath: String,
        bookPath: String?,
        requestId: Long,
    ): List<NativeMove> {
        val encoded = nativeAnalyze(player, opponent, side, level, evaluationDataPath, bookPath, requestId)
        require(encoded.size % NATIVE_FIELDS == 0) { "Malformed Edax JNI result" }
        return encoded.asList().chunked(NATIVE_FIELDS).map { values ->
            NativeMove(
                square = values[0],
                score = values[1],
                kind = when (values[2]) {
                    0 -> EvaluationKind.EXACT
                    1 -> EvaluationKind.HEURISTIC
                    2 -> EvaluationKind.BOOK
                    else -> error("Unknown Edax evaluation kind")
                },
                depth = values[3].takeIf { it >= 0 },
                selectivityPercent = values[4].takeIf { it in 0..100 },
            )
        }
    }

    override fun cancel(requestId: Long) {
        if (available) nativeCancel(requestId)
    }

    private external fun nativeVersion(): String
    private external fun nativeValidateEvaluationData(path: String): String?
    private external fun nativeValidateBook(path: String): String?
    private external fun nativeAnalyze(
        player: Long,
        opponent: Long,
        side: Int,
        level: Int,
        evaluationDataPath: String,
        bookPath: String?,
        requestId: Long,
    ): IntArray
    private external fun nativeCancel(requestId: Long)

    private const val NATIVE_FIELDS = 5
}

/** Production post-game adapter. Live-match modules cannot depend on this Android module. */
class ProductionAnalysisEngine private constructor(
    private val gateway: EdaxGateway,
    private val fileExists: (String) -> Boolean,
) : AnalysisEngine {
    constructor() : this(NativeEdax, { File(it).isFile })
    internal constructor(gateway: EdaxGateway) : this(gateway, { true })

    private val requestSequence = AtomicLong()
    private val activeRequest = AtomicLong(NO_REQUEST)
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<CacheKey, AnalysisResult>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, AnalysisResult>?): Boolean =
            size > CACHE_CAPACITY
    }

    val nativeAvailable: Boolean get() = gateway.available
    val nativeVersion: String? get() = gateway.version

    override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult {
        if (!gateway.available) return unavailable("Edax native libraryを読み込めません")
        val evaluationAsset = (settings.evaluationData as? EvaluationDataSource.Imported)?.asset
            ?: return unavailable("解析用評価データが設定されていません")
        if (!fileExists(evaluationAsset.appPrivatePath)) return unavailable("解析用評価データが見つかりません。再インポートしてください")
        val bookAsset = (settings.bookSource as? BookSource.ImportedBook)?.asset
        if (bookAsset != null && !fileExists(bookAsset.appPrivatePath)) {
            return unavailable("オープニングブックが見つかりません。削除または再インポートしてください")
        }

        val state = position.state
        val key = CacheKey(
            board = state.board.toCompactString(),
            currentPlayer = state.currentPlayer,
            level = settings.level,
            evaluationIdentity = evaluationAsset.identitySha256,
            bookIdentity = bookAsset?.identitySha256,
        )
        synchronized(cacheLock) { cache[key] }?.let { return it }

        val requestId = requestSequence.incrementAndGet()
        activeRequest.getAndSet(requestId).takeIf { it != NO_REQUEST }?.let(gateway::cancel)
        return try {
            val (player, opponent) = state.toEdaxBoard()
            val nativeMoves = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { gateway.cancel(requestId) }
                ANALYSIS_EXECUTOR.execute {
                    runCatching {
                        gateway.analyze(
                        player = player,
                        opponent = opponent,
                        side = state.currentPlayer.toEdaxSide(),
                        level = settings.level,
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
            if (nativeMoves.isEmpty() && state.legalMoves.isNotEmpty()) throw CancellationException("Edax analysis cancelled")
            val nativePositions = nativeMoves.map { Position(it.square / 8, it.square % 8) }.toSet()
            require(nativePositions == state.legalMoves) { "Edax legal-move set does not match Game Core" }
            AnalysisResult(
                evaluations = nativeMoves.map { move ->
                    MoveEvaluation(
                        move = Position(move.square / 8, move.square % 8),
                        score = EvaluationScore(
                            value = move.score,
                            perspective = EvaluationPerspective.SIDE_TO_MOVE,
                            kind = move.kind,
                            searchedDepth = move.depth,
                            selectivityPercent = move.selectivityPercent,
                        ),
                    )
                }.sortedByDescending { it.score.value },
            ).also { synchronized(cacheLock) { cache[key] = it } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            unavailable("Edax解析に失敗しました: ${failure.message ?: failure::class.simpleName}")
        } finally {
            activeRequest.compareAndSet(requestId, NO_REQUEST)
        }
    }

    override fun cancel() {
        activeRequest.get().takeIf { it != NO_REQUEST }?.let(gateway::cancel)
    }

    override fun clearCache() = synchronized(cacheLock) { cache.clear() }

    private fun unavailable(message: String) = AnalysisResult(emptyList(), available = false, message = message)

    private data class CacheKey(
        val board: String,
        val currentPlayer: Disc,
        val level: Int,
        val evaluationIdentity: String,
        val bookIdentity: String?,
    )

    private companion object {
        const val CACHE_CAPACITY = 32
        const val NO_REQUEST = -1L
        val ANALYSIS_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "EdaxAnalysis").apply { isDaemon = true }
        }
    }
}

private fun com.example.othello.game.GameState.toEdaxBoard(): Pair<Long, Long> {
    fun bits(disc: Disc): Long = board.positionsOf(disc).fold(0L) { value, position -> value or (1L shl position.index()) }
    return bits(currentPlayer) to bits(currentPlayer.opponent())
}

private fun Disc.toEdaxSide(): Int = when (this) {
    Disc.BLACK -> 0
    Disc.WHITE -> 1
    Disc.EMPTY -> error("Empty cannot be the side to move")
}
