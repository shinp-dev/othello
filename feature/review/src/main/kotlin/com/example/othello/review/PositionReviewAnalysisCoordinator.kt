package com.example.othello.review

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import java.util.LinkedHashMap

/** Owns automatic-analysis requests and their session-scoped result cache. */
class PositionReviewAnalysisCoordinator(
    cacheCapacity: Int = DEFAULT_CACHE_CAPACITY,
) {
    private val guard = AnalysisRequestGuard()
    private val cache = PositionReviewAnalysisLruCache<PositionReviewAnalysisCacheKey, AnalysisResult>(cacheCapacity)
    private var configuration: PositionReviewAnalysisConfiguration? = null

    @Synchronized
    fun begin(state: GameState, settings: AnalysisSettings): PositionReviewAnalysisStart {
        val nextConfiguration = PositionReviewAnalysisConfiguration.from(settings)
        if (configuration != nextConfiguration) {
            cache.clear()
            configuration = nextConfiguration
        }

        val token = guard.begin(state.stateHash())
        if (state.legalMoves.isEmpty()) return PositionReviewAnalysisStart.NoLegalMoves

        val key = PositionReviewAnalysisCacheKey.from(state, nextConfiguration)
        return cache[key]?.let(PositionReviewAnalysisStart::Cached)
            ?: PositionReviewAnalysisStart.Analyze(
                PositionReviewAnalysisRequest(token, key, state, settings),
            )
    }

    @Synchronized
    fun complete(
        request: PositionReviewAnalysisRequest,
        currentState: GameState,
        currentSettings: AnalysisSettings,
        result: AnalysisResult,
    ): PositionReviewAnalysisCompletion {
        if (!isCurrentLocked(request, currentState, currentSettings)) {
            return PositionReviewAnalysisCompletion.STALE
        }
        if (!result.available) return PositionReviewAnalysisCompletion.FAILED

        cache[request.key] = result
        return PositionReviewAnalysisCompletion.ACCEPTED
    }

    @Synchronized
    fun isCurrent(
        request: PositionReviewAnalysisRequest,
        currentState: GameState,
        currentSettings: AnalysisSettings,
    ): Boolean = isCurrentLocked(request, currentState, currentSettings)

    @Synchronized
    fun invalidate() {
        guard.invalidate()
    }

    @Synchronized
    fun clear() {
        guard.invalidate()
        cache.clear()
        configuration = null
    }

    @Synchronized
    internal fun cachedEntryCount(): Int = cache.size

    private fun isCurrentLocked(
        request: PositionReviewAnalysisRequest,
        currentState: GameState,
        currentSettings: AnalysisSettings,
    ): Boolean {
        val currentConfiguration = PositionReviewAnalysisConfiguration.from(currentSettings)
        return configuration == currentConfiguration &&
            request.key == PositionReviewAnalysisCacheKey.from(currentState, currentConfiguration) &&
            guard.isCurrent(request.token, currentState.stateHash())
    }

    companion object {
        const val DEFAULT_CACHE_CAPACITY = 64
    }
}

sealed interface PositionReviewAnalysisStart {
    data class Cached(val result: AnalysisResult) : PositionReviewAnalysisStart
    data class Analyze(val request: PositionReviewAnalysisRequest) : PositionReviewAnalysisStart
    data object NoLegalMoves : PositionReviewAnalysisStart
}

enum class PositionReviewAnalysisCompletion {
    ACCEPTED,
    FAILED,
    STALE,
}

class PositionReviewAnalysisRequest internal constructor(
    internal val token: AnalysisRequestToken,
    internal val key: PositionReviewAnalysisCacheKey,
    val state: GameState,
    val settings: AnalysisSettings,
) {
    suspend fun execute(engine: AnalysisEngine): AnalysisResult =
        engine.analyze(ReviewPosition(state), settings)
}

internal data class PositionReviewAnalysisConfiguration(
    val level: Int,
    val timePerCandidateMs: Int,
    val evaluationIdentity: String?,
    val bookIdentity: String?,
) {
    companion object {
        fun from(settings: AnalysisSettings): PositionReviewAnalysisConfiguration =
            PositionReviewAnalysisConfiguration(
                level = settings.level,
                timePerCandidateMs = settings.timePerCandidateMs,
                evaluationIdentity = (settings.evaluationData as? EvaluationDataSource.Imported)
                    ?.asset
                    ?.identitySha256,
                bookIdentity = (settings.bookSource as? BookSource.ImportedBook)
                    ?.asset
                    ?.identitySha256,
            )
    }
}

internal data class PositionReviewAnalysisCacheKey(
    val board: String,
    val currentPlayer: Disc,
    val configuration: PositionReviewAnalysisConfiguration,
) {
    companion object {
        fun from(
            state: GameState,
            configuration: PositionReviewAnalysisConfiguration,
        ): PositionReviewAnalysisCacheKey = PositionReviewAnalysisCacheKey(
            board = state.board.toCompactString(),
            currentPlayer = state.currentPlayer,
            configuration = configuration,
        )
    }
}

internal class PositionReviewAnalysisLruCache<K, V>(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "cache capacity must be positive" }
    }

    private val entries = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }

    val size: Int get() = entries.size

    operator fun get(key: K): V? = entries[key]

    operator fun set(key: K, value: V) {
        entries[key] = value
    }

    fun contains(key: K): Boolean = entries.containsKey(key)

    fun clear() {
        entries.clear()
    }
}
