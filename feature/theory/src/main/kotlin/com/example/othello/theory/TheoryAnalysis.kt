package com.example.othello.theory

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
import com.example.othello.game.GameState
import com.example.othello.game.Position
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class TheoryAnalysisCacheKey(
    val board: String,
    val currentPlayer: Disc,
    val level: Int,
    val timePerCandidateMs: Int,
    val evaluationIdentity: String?,
    val bookIdentity: String?,
) {
    fun stableIdentity(): String = listOf(
        board,
        currentPlayer.name,
        level.toString(),
        timePerCandidateMs.toString(),
        evaluationIdentity ?: "none",
        bookIdentity ?: "none",
    ).joinToString("|")

    fun fileId(): String = MessageDigest.getInstance("SHA-256")
        .digest(stableIdentity().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        fun from(state: GameState, settings: AnalysisSettings): TheoryAnalysisCacheKey =
            TheoryAnalysisCacheKey(
                board = state.board.toCompactString(),
                currentPlayer = state.currentPlayer,
                level = settings.level,
                timePerCandidateMs = settings.timePerCandidateMs,
                evaluationIdentity = (settings.evaluationData as? EvaluationDataSource.Imported)
                    ?.asset
                    ?.identitySha256,
                bookIdentity = (settings.bookSource as? BookSource.ImportedBook)
                    ?.asset
                    ?.identitySha256,
            )

        fun positionIdentity(state: GameState): String =
            state.board.toCompactString() + ":" + state.currentPlayer.name
    }
}

interface TheoryAnalysisCache {
    suspend fun get(key: TheoryAnalysisCacheKey): AnalysisResult?
    suspend fun put(key: TheoryAnalysisCacheKey, result: AnalysisResult): TheoryAnalysisCacheWriteToken?
    suspend fun remove(key: TheoryAnalysisCacheKey)
    suspend fun removeIfOwned(key: TheoryAnalysisCacheKey, token: TheoryAnalysisCacheWriteToken)
    suspend fun clear()
}

@JvmInline
value class TheoryAnalysisCacheWriteToken(val value: String)

/** Dedicated generation guard and cache coordinator for theory exploration. */
class TheoryAnalysisCoordinator(
    private val cache: TheoryAnalysisCache,
) {
    private var generation = 0L

    suspend fun begin(state: GameState, settings: AnalysisSettings): TheoryAnalysisStart {
        val key = TheoryAnalysisCacheKey.from(state, settings)
        val request = synchronized(this) {
            TheoryAnalysisRequest(++generation, key, state, settings)
        }
        if (state.legalMoves.isEmpty()) return TheoryAnalysisStart.NoLegalMoves

        var cached = cache.get(key)
        if (cached != null && !cached.isCompleteFor(state)) {
            cache.remove(key)
            cached = null
        }
        return synchronized(this) {
            if (!isCurrentLocked(request, state, settings)) {
                TheoryAnalysisStart.Stale
            } else {
                cached?.let(TheoryAnalysisStart::Cached) ?: TheoryAnalysisStart.Analyze(request)
            }
        }
    }

    suspend fun complete(
        request: TheoryAnalysisRequest,
        currentState: GameState,
        currentSettings: AnalysisSettings,
        result: AnalysisResult,
    ): TheoryAnalysisCompletion {
        val acceptedAtCompletion = synchronized(this) {
            isCurrentLocked(request, currentState, currentSettings)
        }
        if (!acceptedAtCompletion) return TheoryAnalysisCompletion.STALE
        if (!result.isCompleteFor(request.state)) return TheoryAnalysisCompletion.FAILED

        val writeToken = cache.put(request.key, result)
        val completion = synchronized(this) {
            if (isCurrentLocked(request, currentState, currentSettings)) {
                TheoryAnalysisCompletion.ACCEPTED
            } else {
                TheoryAnalysisCompletion.STALE
            }
        }
        if (completion == TheoryAnalysisCompletion.STALE && writeToken != null) {
            cache.removeIfOwned(request.key, writeToken)
        }
        return completion
    }

    fun isCurrent(
        request: TheoryAnalysisRequest,
        currentState: GameState,
        currentSettings: AnalysisSettings,
    ): Boolean = synchronized(this) { isCurrentLocked(request, currentState, currentSettings) }

    fun invalidate() = synchronized(this) {
        generation++
    }

    private fun isCurrentLocked(
        request: TheoryAnalysisRequest,
        currentState: GameState,
        currentSettings: AnalysisSettings,
    ): Boolean = request.generation == generation &&
        request.key == TheoryAnalysisCacheKey.from(currentState, currentSettings)
}

sealed interface TheoryAnalysisStart {
    data class Cached(val result: AnalysisResult) : TheoryAnalysisStart
    data class Analyze(val request: TheoryAnalysisRequest) : TheoryAnalysisStart
    data object NoLegalMoves : TheoryAnalysisStart
    data object Stale : TheoryAnalysisStart
}

enum class TheoryAnalysisCompletion { ACCEPTED, FAILED, STALE }

class TheoryAnalysisRequest internal constructor(
    internal val generation: Long,
    internal val key: TheoryAnalysisCacheKey,
    val state: GameState,
    val settings: AnalysisSettings,
) {
    suspend fun execute(engine: AnalysisEngine): AnalysisResult =
        engine.analyze(ReviewPosition(state), settings)
}

private fun AnalysisResult.isCompleteFor(state: GameState): Boolean {
    if (!available || evaluations.size != state.legalMoves.size) return false
    return evaluations.map { it.move }.toSet() == state.legalMoves
}

object TheoryAnalysisCacheJson {
    private const val SCHEMA_VERSION = 1
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(key: TheoryAnalysisCacheKey, result: AnalysisResult): String = json.encodeToString(
        CacheEntryDto(
            schemaVersion = SCHEMA_VERSION,
            key = CacheKeyDto.from(key),
            result = AnalysisResultDto.from(result),
        ),
    )

    fun decode(encoded: String, expectedKey: TheoryAnalysisCacheKey): AnalysisResult {
        val entry = json.decodeFromString<CacheEntryDto>(encoded)
        require(entry.schemaVersion == SCHEMA_VERSION)
        require(entry.key.toModel() == expectedKey)
        return entry.result.toModel().also { require(it.available) }
    }
}

@Serializable
private data class CacheEntryDto(
    val schemaVersion: Int,
    val key: CacheKeyDto,
    val result: AnalysisResultDto,
)

@Serializable
private data class CacheKeyDto(
    val board: String,
    val currentPlayer: String,
    val level: Int,
    val timePerCandidateMs: Int,
    val evaluationIdentity: String?,
    val bookIdentity: String?,
) {
    fun toModel(): TheoryAnalysisCacheKey = TheoryAnalysisCacheKey(
        board = board,
        currentPlayer = Disc.valueOf(currentPlayer),
        level = level,
        timePerCandidateMs = timePerCandidateMs,
        evaluationIdentity = evaluationIdentity,
        bookIdentity = bookIdentity,
    )

    companion object {
        fun from(key: TheoryAnalysisCacheKey): CacheKeyDto = CacheKeyDto(
            board = key.board,
            currentPlayer = key.currentPlayer.name,
            level = key.level,
            timePerCandidateMs = key.timePerCandidateMs,
            evaluationIdentity = key.evaluationIdentity,
            bookIdentity = key.bookIdentity,
        )
    }
}

@Serializable
private data class AnalysisResultDto(
    val evaluations: List<MoveEvaluationDto>,
    val available: Boolean,
    val message: String?,
) {
    fun toModel(): AnalysisResult = AnalysisResult(
        evaluations = evaluations.map(MoveEvaluationDto::toModel),
        available = available,
        message = message,
    )

    companion object {
        fun from(result: AnalysisResult): AnalysisResultDto = AnalysisResultDto(
            evaluations = result.evaluations.map(MoveEvaluationDto::from),
            available = result.available,
            message = result.message,
        )
    }
}

@Serializable
private data class MoveEvaluationDto(
    val row: Int,
    val column: Int,
    val value: Int,
    val perspective: String,
    val kind: String,
    val searchedDepth: Int?,
    val selectivityPercent: Int?,
) {
    fun toModel(): MoveEvaluation = MoveEvaluation(
        move = Position(row, column),
        score = EvaluationScore(
            value = value,
            perspective = EvaluationPerspective.valueOf(perspective),
            kind = EvaluationKind.valueOf(kind),
            searchedDepth = searchedDepth,
            selectivityPercent = selectivityPercent,
        ),
    )

    companion object {
        fun from(evaluation: MoveEvaluation): MoveEvaluationDto = MoveEvaluationDto(
            row = evaluation.move.row,
            column = evaluation.move.column,
            value = evaluation.score.value,
            perspective = evaluation.score.perspective.name,
            kind = evaluation.score.kind.name,
            searchedDepth = evaluation.score.searchedDepth,
            selectivityPercent = evaluation.score.selectivityPercent,
        )
    }
}
